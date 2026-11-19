package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.put

/**
 * Agent 工具执行记录仓库。
 *
 * 每次工具调用独立记录；参数 / 结果使用 JSON 字符串保存。
 * F2 不执行工具，也不自动重放或去重 `idempotencyKey`，只提供数据与查询能力。
 */
class AgentToolExecutionRepository(private val db: AppDatabase) {

    private val dao = db.agentTaskDao()
    private val events = AgentEventWriter(dao)

    /**
     * 创建一条工具执行记录（PENDING），不追加事件。
     * [attempt] 为空时按「同一任务 / 节点 / 工具」已有记录数 +1 生成。
     */
    suspend fun createExecution(
        taskId: Long,
        toolName: String,
        toolDisplayName: String = "",
        nodeId: Long? = null,
        argumentsJson: String = "{}",
        idempotencyKey: String? = null,
        attempt: Int? = null
    ): Long {
        require(toolName.isNotBlank()) { "toolName 不能为空" }
        requireJsonObject(argumentsJson, "argumentsJson")
        return db.withTransaction {
            require(dao.getTask(taskId) != null) { "Agent 任务不存在：$taskId" }
            if (nodeId != null) {
                val node = dao.getNode(nodeId)
                    ?: throw IllegalArgumentException("执行节点不存在：$nodeId")
                require(node.taskId == taskId) { "执行节点 $nodeId 不属于任务 $taskId" }
            }
            val nextAttempt = attempt ?: if (nodeId != null) {
                dao.maxAttemptForNode(taskId, nodeId, toolName) + 1
            } else {
                dao.maxAttemptForTask(taskId, toolName) + 1
            }
            require(nextAttempt > 0) { "attempt 必须大于 0" }
            dao.insertToolExecution(
                AgentToolExecution(
                    taskId = taskId,
                    nodeId = nodeId,
                    toolName = toolName,
                    toolDisplayName = toolDisplayName,
                    status = AgentToolStatus.PENDING,
                    argumentsJson = argumentsJson,
                    idempotencyKey = idempotencyKey,
                    attempt = nextAttempt,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun getExecution(id: Long): AgentToolExecution? = dao.getToolExecution(id)

    suspend fun executionsOfTask(taskId: Long): List<AgentToolExecution> =
        dao.toolExecutionsOfTask(taskId)

    /** 执行过程 UI 订阅：按任务观察工具执行记录 */
    fun observeExecutions(taskId: Long): Flow<List<AgentToolExecution>> =
        dao.observeToolExecutionsOfTask(taskId)

    suspend fun executionsOfNode(taskId: Long, nodeId: Long): List<AgentToolExecution> =
        dao.toolExecutionsOfNode(taskId, nodeId)

    suspend fun executionsWithStatus(taskId: Long, status: String): List<AgentToolExecution> =
        dao.toolExecutionsWithStatuses(taskId, listOf(status))

    /** 中断后用于判断哪些工具调用停在执行中 */
    suspend fun runningExecutions(taskId: Long): List<AgentToolExecution> =
        executionsWithStatus(taskId, AgentToolStatus.RUNNING)

    /** 已成功完成的工具执行记录（恢复时用于跳过已成功步骤） */
    suspend fun succeededExecutions(taskId: Long): List<AgentToolExecution> =
        executionsWithStatus(taskId, AgentToolStatus.SUCCEEDED)

    /**
     * F7：把进程中断后仍为 RUNNING 的工具记录标记为 UNCERTAIN。
     *
     * 只读工具可以安全重试；写入工具必须核对 agent_write_operation 的真实状态。
     * 返回受影响数量，并逐条追加事件。
     */
    suspend fun markInterruptedUncertain(taskId: Long): Int = db.withTransaction {
        val running = dao.runningToolExecutions(taskId)
        if (running.isEmpty()) return@withTransaction 0
        val now = System.currentTimeMillis()
        val message = "进程中断，无法确认工具是否已完成；未自动重放"
        val affected = dao.markRunningToolExecutionsUncertain(taskId, message, now)
        running.forEach { execution ->
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TOOL_CALL_FAILED,
                nodeId = execution.nodeId,
                toolExecutionId = execution.id,
                payloadJson = jsonPayload {
                    put("toolName", execution.toolName)
                    put("status", AgentToolStatus.UNCERTAIN)
                    put("interrupted", true)
                    put("message", message)
                }
            )
        }
        affected
    }

    suspend fun findByIdempotencyKey(key: String): AgentToolExecution? =
        dao.findByIdempotencyKey(key)

    /** 开始执行：PENDING → RUNNING，并追加 TOOL_CALL_STARTED */
    suspend fun start(executionId: Long): Unit = db.withTransaction {
        val execution = dao.getToolExecution(executionId)
            ?: throw IllegalArgumentException("工具执行记录不存在：$executionId")
        require(execution.status == AgentToolStatus.PENDING) {
            "只有 PENDING 状态可以开始执行，当前为 ${execution.status}"
        }
        val now = System.currentTimeMillis()
        val next = execution.copy(status = AgentToolStatus.RUNNING, startedAt = now)
        dao.updateToolExecution(next)
        events.append(
            taskId = execution.taskId,
            eventType = AgentEventType.TOOL_CALL_STARTED,
            nodeId = execution.nodeId,
            toolExecutionId = executionId,
            payloadJson = jsonPayload {
                put("toolName", execution.toolName)
                put("attempt", execution.attempt)
                execution.nodeId?.let { put("nodeId", it) }
            }
        )
        Unit
    }

    /** 执行成功：RUNNING → SUCCEEDED，保存结果与耗时，并追加 TOOL_CALL_COMPLETED */
    suspend fun succeed(
        executionId: Long,
        resultJson: String = "{}",
        durationMs: Long? = null
    ) = finish(
        executionId = executionId,
        status = AgentToolStatus.SUCCEEDED,
        resultJson = resultJson,
        errorMessage = null,
        durationMs = durationMs,
        eventType = AgentEventType.TOOL_CALL_COMPLETED
    )

    /** 执行失败：RUNNING → FAILED，保存错误信息与可选结果，并追加 TOOL_CALL_FAILED */
    suspend fun fail(
        executionId: Long,
        errorMessage: String,
        resultJson: String? = null,
        durationMs: Long? = null
    ) = finish(
        executionId = executionId,
        status = AgentToolStatus.FAILED,
        resultJson = resultJson,
        errorMessage = errorMessage,
        durationMs = durationMs,
        eventType = AgentEventType.TOOL_CALL_FAILED
    )

    /** 跳过：PENDING / RUNNING → SKIPPED，并追加 TOOL_CALL_SKIPPED */
    suspend fun skip(
        executionId: Long,
        reason: String = ""
    ) = finish(
        executionId = executionId,
        status = AgentToolStatus.SKIPPED,
        resultJson = null,
        errorMessage = reason,
        durationMs = null,
        eventType = AgentEventType.TOOL_CALL_SKIPPED,
        allowedFrom = listOf(AgentToolStatus.PENDING, AgentToolStatus.RUNNING)
    )

    /** 取消：非终态 → CANCELLED，并追加 TOOL_CALL_CANCELLED */
    suspend fun cancel(
        executionId: Long,
        reason: String = ""
    ) = finish(
        executionId = executionId,
        status = AgentToolStatus.CANCELLED,
        resultJson = null,
        errorMessage = reason,
        durationMs = null,
        eventType = AgentEventType.TOOL_CALL_CANCELLED,
        allowedFrom = listOf(AgentToolStatus.PENDING, AgentToolStatus.RUNNING)
    )

    /** 单独保存 / 修正结果（不改变状态，不追加事件） */
    suspend fun updateResult(executionId: Long, resultJson: String) {
        requireJsonObject(resultJson, "resultJson")
        db.withTransaction {
            val execution = dao.getToolExecution(executionId)
                ?: throw IllegalArgumentException("工具执行记录不存在：$executionId")
            dao.updateToolExecution(execution.copy(resultJson = resultJson))
        }
    }

    private suspend fun finish(
        executionId: Long,
        status: String,
        resultJson: String?,
        errorMessage: String?,
        durationMs: Long?,
        eventType: String,
        allowedFrom: List<String> = listOf(AgentToolStatus.RUNNING)
    ): Unit = db.withTransaction {
        val execution = dao.getToolExecution(executionId)
            ?: throw IllegalArgumentException("工具执行记录不存在：$executionId")
        require(execution.status in allowedFrom) {
            "当前状态 ${execution.status} 不能变更为 $status"
        }
        resultJson?.let { requireJsonObject(it, "resultJson") }
        val now = System.currentTimeMillis()
        val duration = durationMs ?: execution.startedAt?.let { (now - it).coerceAtLeast(0) }
        val next = execution.copy(
            status = status,
            resultJson = resultJson ?: execution.resultJson,
            errorMessage = errorMessage ?: execution.errorMessage,
            completedAt = now,
            durationMs = duration
        )
        dao.updateToolExecution(next)
        events.append(
            taskId = execution.taskId,
            eventType = eventType,
            nodeId = execution.nodeId,
            toolExecutionId = executionId,
            payloadJson = jsonPayload {
                put("toolName", execution.toolName)
                put("status", status)
                if (duration != null) put("durationMs", duration)
                if (!next.errorMessage.isBlank()) put("errorMessage", next.errorMessage)
            }
        )
        Unit
    }
}
