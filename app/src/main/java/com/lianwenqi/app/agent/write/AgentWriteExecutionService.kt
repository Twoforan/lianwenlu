package com.lianwenqi.app.agent.write

import com.lianwenqi.app.agent.data.AgentWriteDraft
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** 大纲新建参数（与工具参数保持一致） */
@Serializable
data class AgentOutlineCreateArgs(
    val category: String,
    val tag: String = "",
    val title: String,
    val content: String
)

/** 大纲修改参数；未提供的字段保持原值 */
@Serializable
data class AgentOutlineUpdateArgs(
    val category: String? = null,
    val tag: String? = null,
    val title: String? = null,
    val content: String? = null
)

/** 一次写入提议的结果 */
data class AgentWriteProposalResult(
    val operation: AgentWriteOperation,
    val requiresConfirmation: Boolean,
    val autoExecuted: Boolean,
    val errorMessage: String = ""
)

/**
 * F6 受控写入执行服务。
 *
 * 提议阶段只写 agent_write_operation；批准后才重新校验并写业务数据。
 * 同一操作的执行 / 撤销使用进程内 Mutex，数据库状态机与唯一幂等键作为最终保障。
 */
class AgentWriteExecutionService(
    private val operations: AgentWriteOperationRepository,
    private val writer: AgentWriteBusinessWriter,
    private val policyProvider: suspend () -> String,
    private val transactionRunner: AgentWriteTransactionRunner
) {

    private val mutexes = ConcurrentHashMap<Long, Mutex>()

    suspend fun propose(draft: AgentWriteDraft): AgentWriteProposalResult {
        val policy = runCatching { policyProvider() }
            .getOrDefault(AgentWritePermissionPolicy.ASK_EVERY_TIME)
        val proposed = operations.propose(draft, policy)
        if (proposed.executionStatus == AgentWriteOperationStatus.APPROVED) {
            val executed = executeApproved(proposed.id)
            return AgentWriteProposalResult(
                operation = executed,
                requiresConfirmation = false,
                autoExecuted = true,
                errorMessage = executionErrorMessage(executed)
            )
        }
        return AgentWriteProposalResult(
            operation = proposed,
            requiresConfirmation = proposed.executionStatus ==
                AgentWriteOperationStatus.WAITING_CONFIRMATION ||
                proposed.executionStatus == AgentWriteOperationStatus.PROPOSED,
            autoExecuted = false,
            errorMessage = executionErrorMessage(proposed)
        )
    }

    /** 用户批准并立即执行；重复调用幂等 */
    suspend fun approveAndExecute(operationId: Long): AgentWriteOperation {
        val approved = operations.approve(operationId)
        if (approved.executionStatus != AgentWriteOperationStatus.APPROVED) {
            return approved
        }
        return executeApproved(operationId)
    }

    suspend fun reject(operationId: Long): AgentWriteOperation = operations.reject(operationId)

    /**
     * 执行已批准操作。
     *
     * 先原子 APPROVED → EXECUTING；只有真正完成迁移的调用才写业务数据。
     * 业务写入与 SUCCEEDED 状态在同一事务内提交；异常时回滚业务写入并标记 FAILED。
     */
    suspend fun executeApproved(operationId: Long): AgentWriteOperation {
        val mutex = mutexes.computeIfAbsent(operationId) { Mutex() }
        return try {
            mutex.withLock {
                val begin = operations.beginExecution(operationId)
                if (!begin.started) {
                    return@withLock operations.get(operationId) ?: begin.operation
                }
                val result = try {
                    transactionRunner.run {
                        val operation = operations.get(operationId)
                            ?: error("写入操作不存在：$operationId")
                        val conflict = detectConflict(operation)
                        if (conflict != null) {
                            operations.markConflict(operationId, conflict)
                        } else {
                            performWrite(operation)
                        }
                    }
                    operations.get(operationId) ?: begin.operation
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    operations.markFailed(operationId, e.message ?: "写入失败")
                    operations.get(operationId) ?: begin.operation
                }
                result
            }
        } finally {
            val current = runCatching { operations.get(operationId) }.getOrNull()
            if (current == null || AgentWriteOperationStatus.isTerminal(current.executionStatus)) {
                mutexes.remove(operationId, mutex)
            }
        }
    }

    /**
     * 撤销已成功操作。
     *
     * 撤销前比较当前数据与执行后快照；不一致时不覆盖新内容，只记录撤销冲突。
     */
    suspend fun rollback(operationId: Long): AgentWriteOperation {
        val mutex = mutexes.computeIfAbsent(operationId) { Mutex() }
        return try {
            mutex.withLock {
                val operation = operations.get(operationId)
                    ?: throw IllegalArgumentException("写入操作不存在：$operationId")
                if (operation.executionStatus == AgentWriteOperationStatus.ROLLED_BACK) {
                    return operation
                }
                require(operation.executionStatus == AgentWriteOperationStatus.SUCCEEDED) {
                    "只有成功写入的操作可以撤销，当前为 ${operation.executionStatus}"
                }
                require(operation.reversible) { "该操作不支持撤销" }
                transactionRunner.run {
                    val current = operations.get(operationId)
                        ?: error("写入操作不存在：$operationId")
                    val conflict = detectRollbackConflict(current)
                    if (conflict != null) {
                        operations.recordRollbackConflict(operationId, conflict)
                    } else {
                        restore(current)
                        operations.markRolledBack(operationId)
                    }
                }
                operations.get(operationId) ?: operation
            }
        } finally {
            val current = runCatching { operations.get(operationId) }.getOrNull()
            if (current == null || AgentWriteOperationStatus.isTerminal(current.executionStatus)) {
                mutexes.remove(operationId, mutex)
            }
        }
    }

    suspend fun cancelPending(taskId: Long, reason: String = "任务已取消") =
        operations.cancelPending(taskId, reason)

    suspend fun failStaleExecuting(taskId: Long) = operations.failStaleExecuting(taskId)

    // ---------------- 冲突检测 ----------------

    private suspend fun detectConflict(operation: AgentWriteOperation): String? {
        return when (operation.operationType) {
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: return "执行前快照无法解析"
                val current = writer.chapterMeta(operation.targetId)
                    ?: return "目标章节已删除"
                if (current.bookId != operation.bookId) return "目标章节已不属于当前书籍"
                if (current.summary != before.summary) {
                    "章节梗概已在预览后发生变化，请重新预览并确认"
                } else {
                    null
                }
            }

            AgentWriteOperationType.UPDATE_CHAPTER_CONTENT -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: return "执行前快照无法解析"
                val meta = writer.chapterMeta(operation.targetId)
                    ?: return "目标章节已删除"
                if (meta.bookId != operation.bookId) return "目标章节已不属于当前书籍"
                val current = writer.chapterContent(operation.targetId)
                    ?: return "目标章节正文无法读取"
                if (current != before.content) {
                    "章节正文已在预览后发生变化，请重新预览并确认"
                } else {
                    null
                }
            }

            AgentWriteOperationType.UPDATE_OUTLINE_NODE -> {
                val before = decodeOutline(operation.beforeSnapshot)
                    ?: return "执行前快照无法解析"
                val current = writer.outline(operation.targetId)
                    ?: return "目标大纲节点已删除"
                if (current.bookId != operation.bookId) return "目标大纲节点已不属于当前书籍"
                if (current.category != before.category ||
                    current.tag != before.tag ||
                    current.title != before.title ||
                    current.content != before.content
                ) {
                    "大纲节点已在预览后发生变化，请重新预览并确认"
                } else {
                    null
                }
            }

            AgentWriteOperationType.CREATE_OUTLINE_NODE -> null
            else -> "不支持的写入操作类型：${operation.operationType}"
        }
    }

    private suspend fun detectRollbackConflict(operation: AgentWriteOperation): String? {
        return when (operation.operationType) {
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS -> {
                val after = decodeChapter(operation.afterSnapshot)
                    ?: return "执行后快照无法解析"
                val current = writer.chapterMeta(operation.targetId)
                    ?: return "目标章节已删除，无法撤销"
                if (current.summary != after.summary) {
                    "章节梗概已被其他修改覆盖，拒绝自动撤销"
                } else {
                    null
                }
            }

            AgentWriteOperationType.UPDATE_CHAPTER_CONTENT -> {
                val after = decodeChapter(operation.afterSnapshot)
                    ?: return "执行后快照无法解析"
                val current = writer.chapterContent(operation.targetId)
                    ?: return "目标章节正文无法读取，无法撤销"
                if (current != after.content) {
                    "章节正文已被其他修改覆盖，拒绝自动撤销"
                } else {
                    null
                }
            }

            AgentWriteOperationType.UPDATE_OUTLINE_NODE -> {
                val after = decodeOutline(operation.afterSnapshot)
                    ?: return "执行后快照无法解析"
                val current = writer.outline(operation.targetId)
                    ?: return "目标大纲节点已删除，无法撤销"
                if (current.category != after.category ||
                    current.tag != after.tag ||
                    current.title != after.title ||
                    current.content != after.content
                ) {
                    "大纲节点已被其他修改覆盖，拒绝自动撤销"
                } else {
                    null
                }
            }

            AgentWriteOperationType.CREATE_OUTLINE_NODE -> {
                val createdId = operation.createdTargetId
                    ?: return "未记录新建节点 id，无法撤销"
                val after = decodeOutline(operation.afterSnapshot)
                    ?: return "执行后快照无法解析"
                val current = writer.outline(createdId)
                    ?: return "新建大纲节点已被删除，无法确认撤销"
                if (current.category != after.category ||
                    current.tag != after.tag ||
                    current.title != after.title ||
                    current.content != after.content
                ) {
                    "新建大纲节点已被修改，拒绝自动撤销"
                } else {
                    null
                }
            }

            else -> "不支持的撤销类型：${operation.operationType}"
        }
    }

    private suspend fun performWrite(operation: AgentWriteOperation) {
        when (operation.operationType) {
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: error("执行前快照无法解析")
                writer.updateChapterSynopsis(operation.targetId, operation.proposedContent)
                val afterMeta = writer.chapterMeta(operation.targetId)
                    ?: error("写入后章节不存在")
                val after = before.copy(
                    summary = operation.proposedContent,
                    content = "",
                    contentIncluded = false,
                    contentLength = afterMeta.contentLength,
                    updatedAt = afterMeta.updatedAt
                )
                operations.markSucceeded(operation.id, encodeChapter(after))
            }

            AgentWriteOperationType.UPDATE_CHAPTER_CONTENT -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: error("执行前快照无法解析")
                writer.updateChapterContent(operation.targetId, operation.proposedContent)
                val content = writer.chapterContent(operation.targetId)
                    ?: error("写入后章节正文无法读取")
                val afterMeta = writer.chapterMeta(operation.targetId)
                    ?: error("写入后章节不存在")
                val after = before.copy(
                    content = content,
                    contentIncluded = true,
                    contentLength = content.length,
                    updatedAt = afterMeta.updatedAt
                )
                operations.markSucceeded(operation.id, encodeChapter(after))
            }

            AgentWriteOperationType.CREATE_OUTLINE_NODE -> {
                val args = decodeCreateArgs(operation.argumentsJson)
                    ?: error("新建大纲参数无法解析")
                val createdId = writer.insertOutline(
                    bookId = operation.bookId,
                    category = args.category,
                    tag = args.tag,
                    title = args.title,
                    content = args.content
                )
                val after = writer.outline(createdId)
                    ?: error("新建大纲后无法读取节点")
                operations.markSucceeded(
                    operation.id,
                    encodeOutline(after),
                    createdTargetId = createdId
                )
            }

            AgentWriteOperationType.UPDATE_OUTLINE_NODE -> {
                val args = decodeUpdateArgs(operation.argumentsJson)
                    ?: error("修改大纲参数无法解析")
                val current = writer.outline(operation.targetId)
                    ?: error("目标大纲节点不存在")
                val category = args.category ?: current.category
                val tag = args.tag ?: current.tag
                val title = args.title ?: current.title
                val content = args.content ?: current.content
                writer.updateOutlineContent(
                    outlineId = operation.targetId,
                    category = category,
                    tag = tag,
                    title = title,
                    content = content
                )
                val after = writer.outline(operation.targetId)
                    ?: error("修改后大纲节点无法读取")
                operations.markSucceeded(operation.id, encodeOutline(after))
            }

            else -> error("不支持的写入操作类型：${operation.operationType}")
        }
    }

    private suspend fun restore(operation: AgentWriteOperation) {
        when (operation.operationType) {
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: error("执行前快照无法解析")
                writer.updateChapterSynopsis(operation.targetId, before.summary)
            }

            AgentWriteOperationType.UPDATE_CHAPTER_CONTENT -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: error("执行前快照无法解析")
                writer.updateChapterContent(operation.targetId, before.content)
            }

            AgentWriteOperationType.UPDATE_OUTLINE_NODE -> {
                val before = decodeOutline(operation.beforeSnapshot)
                    ?: error("执行前快照无法解析")
                writer.updateOutlineContent(
                    outlineId = operation.targetId,
                    category = before.category,
                    tag = before.tag,
                    title = before.title,
                    content = before.content
                )
            }

            AgentWriteOperationType.CREATE_OUTLINE_NODE -> {
                val createdId = operation.createdTargetId
                    ?: error("未记录新建节点 id")
                writer.deleteOutline(createdId)
            }

            else -> error("不支持的撤销类型：${operation.operationType}")
        }
    }

    private fun executionErrorMessage(operation: AgentWriteOperation): String = when {
        operation.executionStatus == AgentWriteOperationStatus.REJECTED ->
            operation.errorMessage.ifBlank { "用户已拒绝该写入操作" }

        operation.executionStatus == AgentWriteOperationStatus.FAILED ->
            operation.errorMessage.ifBlank { "写入失败" }

        operation.executionStatus == AgentWriteOperationStatus.CONFLICT ->
            operation.errorMessage.ifBlank { "目标数据已变化，需要重新预览" }

        else -> ""
    }

    private fun decodeChapter(raw: String): AgentChapterSnapshot? = runCatching {
        agentJson.decodeFromString(AgentChapterSnapshot.serializer(), raw)
    }.getOrNull()

    private fun encodeChapter(snapshot: AgentChapterSnapshot): String =
        agentJson.encodeToString(AgentChapterSnapshot.serializer(), snapshot)

    private fun decodeOutline(raw: String): AgentOutlineSnapshot? = runCatching {
        agentJson.decodeFromString(AgentOutlineSnapshot.serializer(), raw)
    }.getOrNull()

    private fun encodeOutline(snapshot: AgentOutlineSnapshot): String =
        agentJson.encodeToString(AgentOutlineSnapshot.serializer(), snapshot)

    private fun decodeCreateArgs(raw: String): AgentOutlineCreateArgs? = runCatching {
        agentJson.decodeFromString(AgentOutlineCreateArgs.serializer(), raw)
    }.getOrNull()

    private fun decodeUpdateArgs(raw: String): AgentOutlineUpdateArgs? = runCatching {
        agentJson.decodeFromString(AgentOutlineUpdateArgs.serializer(), raw)
    }.getOrNull()
}
