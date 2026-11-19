package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentWriteLimits
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.put

/** 写入提议草稿；由写入工具在只读校验后组装 */
data class AgentWriteDraft(
    val taskId: Long,
    val sessionId: Long,
    val nodeId: Long? = null,
    val toolName: String,
    val operationType: String,
    val targetType: String,
    val targetId: Long,
    val bookId: Long,
    val targetTitleSnapshot: String = "",
    val bookTitleSnapshot: String = "",
    val riskLevel: String,
    val argumentsJson: String = "{}",
    val beforeSnapshot: String = "{}",
    val proposedContent: String = "",
    val operationSummary: String = "",
    val reversible: Boolean = true,
    val idempotencyKey: String
)

/** 原子开始执行的结果；[started] 表示本次调用真正完成了 APPROVED → EXECUTING 迁移 */
data class AgentWriteBeginResult(
    val operation: AgentWriteOperation,
    val started: Boolean
)

/**
 * F6 受控写入操作仓库。
 *
 * 所有状态迁移都在事务内完成，并写入真实 AgentEvent；
 * 提议阶段只写本表，不触碰任何小说业务数据。
 */
class AgentWriteOperationRepository(private val db: AppDatabase) {

    private val dao = db.agentTaskDao()
    private val events = AgentEventWriter(dao)

    /**
     * 幂等创建写入提议。
     *
     * 同一 `idempotencyKey` 已存在时直接返回原记录，不重复写事件；
     * 一般写入 + AUTO_ALLOW_NORMAL 会直接进入 APPROVED，其余进入 WAITING_CONFIRMATION。
     */
    suspend fun propose(draft: AgentWriteDraft, policy: String): AgentWriteOperation =
        db.withTransaction {
            val existing = dao.findWriteOperationByKey(draft.idempotencyKey)
            if (existing != null) return@withTransaction existing
            require(draft.taskId > 0) { "taskId 不合法" }
            val unresolved = dao.countWriteOperationsWithStatuses(
                draft.taskId,
                AgentWriteOperationStatus.unresolved
            )
            require(unresolved < AgentWriteLimits.MAX_UNRESOLVED_PER_TASK) {
                "单任务待确认写入已达上限（${AgentWriteLimits.MAX_UNRESOLVED_PER_TASK}）"
            }
            require(draft.riskLevel in AgentWriteRiskLevel.all) {
                "非法写入风险等级：${draft.riskLevel}"
            }
            val auto = AgentWritePermissionPolicy.canAutoExecute(draft.riskLevel, policy)
            val now = System.currentTimeMillis()
            val operation = AgentWriteOperation(
                taskId = draft.taskId,
                sessionId = draft.sessionId,
                nodeId = draft.nodeId,
                toolName = draft.toolName,
                operationType = draft.operationType,
                targetType = draft.targetType,
                targetId = draft.targetId,
                bookId = draft.bookId,
                targetTitleSnapshot = draft.targetTitleSnapshot.take(AgentWriteLimits.MAX_SUMMARY_CHARS),
                bookTitleSnapshot = draft.bookTitleSnapshot.take(AgentWriteLimits.MAX_SUMMARY_CHARS),
                riskLevel = draft.riskLevel,
                argumentsJson = draft.argumentsJson,
                beforeSnapshot = draft.beforeSnapshot.take(AgentWriteLimits.MAX_SNAPSHOT_CHARS),
                proposedContent = draft.proposedContent.take(AgentWriteLimits.MAX_SNAPSHOT_CHARS),
                operationSummary = draft.operationSummary.take(AgentWriteLimits.MAX_SUMMARY_CHARS),
                permissionStatus = if (auto) {
                    AgentWritePermissionStatus.AUTO_APPROVED
                } else {
                    AgentWritePermissionStatus.PENDING
                },
                executionStatus = if (auto) {
                    AgentWriteOperationStatus.APPROVED
                } else {
                    AgentWriteOperationStatus.WAITING_CONFIRMATION
                },
                idempotencyKey = draft.idempotencyKey,
                reversible = draft.reversible,
                createdAt = now,
                updatedAt = now
            )
            val id = dao.insertWriteOperation(operation)
            if (id <= 0L) {
                return@withTransaction dao.findWriteOperationByKey(draft.idempotencyKey)
                    ?: error("写入操作唯一约束冲突但未找到原记录")
            }
            val saved = operation.copy(id = id)
            events.append(
                taskId = draft.taskId,
                eventType = AgentEventType.WRITE_OPERATION_PROPOSED,
                nodeId = draft.nodeId,
                payloadJson = operationPayload(saved)
            )
            events.append(
                taskId = draft.taskId,
                eventType = if (auto) {
                    AgentEventType.WRITE_OPERATION_APPROVED
                } else {
                    AgentEventType.WRITE_OPERATION_WAITING_CONFIRMATION
                },
                nodeId = draft.nodeId,
                payloadJson = operationPayload(saved)
            )
            saved
        }

    suspend fun get(id: Long): AgentWriteOperation? = dao.getWriteOperation(id)

    suspend fun getByKey(key: String): AgentWriteOperation? =
        dao.findWriteOperationByKey(key)

    suspend fun ofTask(taskId: Long): List<AgentWriteOperation> =
        dao.writeOperationsOfTask(taskId)

    fun observeOfTask(taskId: Long): Flow<List<AgentWriteOperation>> =
        dao.observeWriteOperationsOfTask(taskId)

    suspend fun unresolvedOfTask(taskId: Long): List<AgentWriteOperation> =
        dao.writeOperationsOfTaskWithStatuses(taskId, AgentWriteOperationStatus.unresolved)

    suspend fun hasUnresolved(taskId: Long): Boolean =
        dao.countWriteOperationsWithStatuses(taskId, AgentWriteOperationStatus.unresolved) > 0

    suspend fun hasOperationForNode(taskId: Long, nodeId: Long): Boolean =
        dao.countWriteOperationsForNode(taskId, nodeId) > 0

    /** 用户批准：WAITING / PROPOSED → APPROVED；已批准 / 已执行时幂等返回 */
    suspend fun approve(id: Long): AgentWriteOperation = db.withTransaction {
        val operation = dao.getWriteOperation(id)
            ?: throw IllegalArgumentException("写入操作不存在：$id")
        if (operation.executionStatus != AgentWriteOperationStatus.WAITING_CONFIRMATION &&
            operation.executionStatus != AgentWriteOperationStatus.PROPOSED
        ) {
            return@withTransaction operation
        }
        val now = System.currentTimeMillis()
        val next = operation.copy(
            permissionStatus = AgentWritePermissionStatus.APPROVED,
            executionStatus = AgentWriteOperationStatus.APPROVED,
            confirmedAt = operation.confirmedAt ?: now,
            errorMessage = "",
            updatedAt = now
        )
        dao.updateWriteOperation(next)
        events.append(
            taskId = next.taskId,
            eventType = AgentEventType.WRITE_OPERATION_APPROVED,
            nodeId = next.nodeId,
            payloadJson = operationPayload(next)
        )
        next
    }

    /** 用户拒绝：未进入 EXECUTING 的操作才能拒绝 */
    suspend fun reject(id: Long): AgentWriteOperation = db.withTransaction {
        val operation = dao.getWriteOperation(id)
            ?: throw IllegalArgumentException("写入操作不存在：$id")
        if (operation.executionStatus == AgentWriteOperationStatus.EXECUTING ||
            AgentWriteOperationStatus.isTerminal(operation.executionStatus)
        ) {
            return@withTransaction operation
        }
        val now = System.currentTimeMillis()
        val next = operation.copy(
            permissionStatus = AgentWritePermissionStatus.REJECTED,
            executionStatus = AgentWriteOperationStatus.REJECTED,
            errorMessage = "用户拒绝该写入操作",
            updatedAt = now
        )
        dao.updateWriteOperation(next)
        events.append(
            taskId = next.taskId,
            eventType = AgentEventType.WRITE_OPERATION_REJECTED,
            nodeId = next.nodeId,
            payloadJson = operationPayload(next)
        )
        next
    }

    /**
     * 原子开始执行：只有 APPROVED 可以进入 EXECUTING。
     * 并发第二次调用会看到 EXECUTING / 终态并返回当前记录，不会重复写入。
     */
    suspend fun beginExecution(id: Long): AgentWriteBeginResult = db.withTransaction {
        val operation = dao.getWriteOperation(id)
            ?: throw IllegalArgumentException("写入操作不存在：$id")
        if (operation.executionStatus != AgentWriteOperationStatus.APPROVED) {
            return@withTransaction AgentWriteBeginResult(operation, started = false)
        }
        val now = System.currentTimeMillis()
        val next = operation.copy(
            executionStatus = AgentWriteOperationStatus.EXECUTING,
            errorMessage = "",
            updatedAt = now
        )
        dao.updateWriteOperation(next)
        events.append(
            taskId = next.taskId,
            eventType = AgentEventType.WRITE_OPERATION_STARTED,
            nodeId = next.nodeId,
            payloadJson = operationPayload(next)
        )
        AgentWriteBeginResult(next, started = true)
    }

    suspend fun markSucceeded(
        id: Long,
        afterSnapshot: String,
        createdTargetId: Long? = null
    ): AgentWriteOperation = markSucceededInternal(
        id = id,
        afterSnapshot = afterSnapshot,
        createdTargetId = createdTargetId,
        recovered = false
    )

    /**
     * F7：恢复时核对发现业务数据已经写入，但状态仍是 EXECUTING。
     * 只允许 EXECUTING → SUCCEEDED，事件标记 recovered=true。
     */
    suspend fun markSucceededFromRecovery(
        id: Long,
        afterSnapshot: String
    ): AgentWriteOperation = markSucceededInternal(
        id = id,
        afterSnapshot = afterSnapshot,
        createdTargetId = null,
        recovered = true
    )

    private suspend fun markSucceededInternal(
        id: Long,
        afterSnapshot: String,
        createdTargetId: Long?,
        recovered: Boolean
    ): AgentWriteOperation = db.withTransaction {
        val operation = requireStatus(id, AgentWriteOperationStatus.EXECUTING)
        val now = System.currentTimeMillis()
        val next = operation.copy(
            executionStatus = AgentWriteOperationStatus.SUCCEEDED,
            afterSnapshot = afterSnapshot.take(AgentWriteLimits.MAX_SNAPSHOT_CHARS),
            createdTargetId = createdTargetId ?: operation.createdTargetId,
            executedAt = now,
            updatedAt = now,
            errorMessage = ""
        )
        dao.updateWriteOperation(next)
        events.append(
            taskId = next.taskId,
            eventType = AgentEventType.WRITE_OPERATION_SUCCEEDED,
            nodeId = next.nodeId,
            payloadJson = jsonPayload {
                put("operationId", next.id)
                put("operationType", next.operationType)
                put("targetType", next.targetType)
                put("targetId", next.targetId)
                put("riskLevel", next.riskLevel)
                put("permissionStatus", next.permissionStatus)
                put("executionStatus", next.executionStatus)
                if (recovered) put("recovered", true)
            }
        )
        next
    }

    suspend fun markFailed(id: Long, message: String): AgentWriteOperation = db.withTransaction {
        val operation = dao.getWriteOperation(id)
            ?: throw IllegalArgumentException("写入操作不存在：$id")
        if (AgentWriteOperationStatus.isTerminal(operation.executionStatus)) {
            return@withTransaction operation
        }
        val now = System.currentTimeMillis()
        val next = operation.copy(
            executionStatus = AgentWriteOperationStatus.FAILED,
            errorMessage = message.take(AgentWriteLimits.MAX_SUMMARY_CHARS),
            updatedAt = now
        )
        dao.updateWriteOperation(next)
        events.append(
            taskId = next.taskId,
            eventType = AgentEventType.WRITE_OPERATION_FAILED,
            nodeId = next.nodeId,
            payloadJson = operationPayload(next)
        )
        next
    }

    suspend fun markConflict(id: Long, message: String): AgentWriteOperation =
        db.withTransaction {
            val operation = requireStatus(id, AgentWriteOperationStatus.EXECUTING)
            val now = System.currentTimeMillis()
            val next = operation.copy(
                executionStatus = AgentWriteOperationStatus.CONFLICT,
                errorMessage = message.take(AgentWriteLimits.MAX_SUMMARY_CHARS),
                updatedAt = now
            )
            dao.updateWriteOperation(next)
            events.append(
                taskId = next.taskId,
                eventType = AgentEventType.WRITE_OPERATION_CONFLICT,
                nodeId = next.nodeId,
                payloadJson = operationPayload(next)
            )
            next
        }

    suspend fun markRolledBack(id: Long): AgentWriteOperation = db.withTransaction {
        val operation = requireStatus(id, AgentWriteOperationStatus.SUCCEEDED)
        val now = System.currentTimeMillis()
        val next = operation.copy(
            executionStatus = AgentWriteOperationStatus.ROLLED_BACK,
            rolledBackAt = now,
            updatedAt = now,
            rollbackMessage = ""
        )
        dao.updateWriteOperation(next)
        events.append(
            taskId = next.taskId,
            eventType = AgentEventType.WRITE_OPERATION_ROLLED_BACK,
            nodeId = next.nodeId,
            payloadJson = operationPayload(next)
        )
        next
    }

    /** 撤销冲突：保留 SUCCEEDED 状态，只记录提示与事件，避免把撤销失败伪装成成功 */
    suspend fun recordRollbackConflict(id: Long, message: String): AgentWriteOperation =
        db.withTransaction {
            val operation = requireStatus(id, AgentWriteOperationStatus.SUCCEEDED)
            val now = System.currentTimeMillis()
            val next = operation.copy(
                rollbackMessage = message.take(AgentWriteLimits.MAX_SUMMARY_CHARS),
                updatedAt = now
            )
            dao.updateWriteOperation(next)
            events.append(
                taskId = next.taskId,
                eventType = AgentEventType.WRITE_OPERATION_CONFLICT,
                nodeId = next.nodeId,
                payloadJson = jsonPayload {
                    put("operationId", next.id)
                    put("rollback", true)
                    put("message", message.take(AgentWriteLimits.MAX_SUMMARY_CHARS))
                }
            )
            next
        }

    suspend fun attachToolExecution(id: Long, toolExecutionId: Long) = db.withTransaction {
        val operation = dao.getWriteOperation(id) ?: return@withTransaction
        if (operation.toolExecutionId == toolExecutionId) return@withTransaction
        dao.updateWriteOperation(
            operation.copy(
                toolExecutionId = toolExecutionId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 用户取消任务 / 取消执行：未执行的提议全部失效 */
    suspend fun cancelPending(taskId: Long, reason: String = "任务已取消") =
        db.withTransaction {
            val pending = dao.writeOperationsOfTaskWithStatuses(
                taskId,
                AgentWriteOperationStatus.cancellable
            )
            pending.forEach { operation ->
                val next = operation.copy(
                    executionStatus = AgentWriteOperationStatus.CANCELLED,
                    errorMessage = reason.take(AgentWriteLimits.MAX_SUMMARY_CHARS),
                    updatedAt = System.currentTimeMillis()
                )
                dao.updateWriteOperation(next)
                events.append(
                    taskId = taskId,
                    eventType = AgentEventType.WRITE_OPERATION_CANCELLED,
                    nodeId = next.nodeId,
                    payloadJson = operationPayload(next)
                )
            }
        }

    /**
     * 进程中断后遗留的 EXECUTING：下一次任务启动前标记失败，避免被当成成功或静默重放。
     * 不重试、不猜测写入结果。
     */
    suspend fun failStaleExecuting(taskId: Long) = db.withTransaction {
        val stale = dao.writeOperationsOfTaskWithStatuses(
            taskId,
            listOf(AgentWriteOperationStatus.EXECUTING)
        )
        stale.forEach { operation ->
            val next = operation.copy(
                executionStatus = AgentWriteOperationStatus.FAILED,
                errorMessage = "上次执行中断，写入状态未知；未自动重试",
                updatedAt = System.currentTimeMillis()
            )
            dao.updateWriteOperation(next)
            events.append(
                taskId = taskId,
                eventType = AgentEventType.WRITE_OPERATION_FAILED,
                nodeId = next.nodeId,
                payloadJson = jsonPayload {
                    put("operationId", next.id)
                    put("status", next.executionStatus)
                    put("stale", true)
                    put("message", next.errorMessage)
                }
            )
        }
    }

    suspend fun deleteForTask(taskId: Long) = dao.deleteWriteOperationsForTask(taskId)

    suspend fun deleteForSession(sessionId: Long) =
        dao.deleteWriteOperationsForSession(sessionId)

    private suspend fun requireStatus(id: Long, status: String): AgentWriteOperation {
        val operation = dao.getWriteOperation(id)
            ?: throw IllegalArgumentException("写入操作不存在：$id")
        require(operation.executionStatus == status) {
            "写入操作 $id 当前状态为 ${operation.executionStatus}，不能变更为 $status"
        }
        return operation
    }

    private fun operationPayload(operation: AgentWriteOperation): String = jsonPayload {
        put("operationId", operation.id)
        put("operationType", operation.operationType)
        put("targetType", operation.targetType)
        put("targetId", operation.targetId)
        put("riskLevel", operation.riskLevel)
        put("permissionStatus", operation.permissionStatus)
        put("executionStatus", operation.executionStatus)
        if (operation.errorMessage.isNotBlank()) {
            put("errorMessage", operation.errorMessage.take(AgentWriteLimits.MAX_SUMMARY_CHARS))
        }
    }
}
