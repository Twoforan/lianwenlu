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

/** 大纲分类参数；itemCount 用于删除前确认分类下条目未变化 */
@Serializable
data class AgentOutlineCategoryArgs(
    val category: String,
    val itemCount: Int = 0
)

/** 移动大纲节点参数 */
@Serializable
data class AgentOutlineMoveArgs(
    val outlineId: Long,
    val category: String
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
    private val transactionRunner: AgentWriteTransactionRunner,
    /** 当前书籍的大纲分类标签；用于新增 / 删除分类的去重与冲突检测 */
    private val outlineCategories: suspend (Long) -> List<String> = { emptyList() },
    /** 某分类下的大纲条目数 */
    private val outlineItemCount: suspend (Long, String) -> Int = { _, _ -> 0 },
    /** 把分类注册到书籍的大纲标签列表（已存在则忽略） */
    private val addOutlineCategory: suspend (Long, String) -> Unit = { _, _ -> },
    /** 仅从书籍的大纲标签列表移除分类，不删除条目 */
    private val removeOutlineCategory: suspend (Long, String) -> Unit = { _, _ -> },
    /** 删除分类及其下全部条目 */
    private val deleteOutlineCategory: suspend (Long, String) -> Unit = { _, _ -> }
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

            AgentWriteOperationType.CREATE_OUTLINE_CATEGORY -> {
                val args = decodeCategoryArgs(operation.argumentsJson)
                    ?: return "分类参数无法解析"
                if (args.category in outlineCategories(operation.bookId)) {
                    "分类已存在，请重新预览并确认"
                } else {
                    null
                }
            }

            AgentWriteOperationType.DELETE_OUTLINE_CATEGORY -> {
                val args = decodeCategoryArgs(operation.argumentsJson)
                    ?: return "分类参数无法解析"
                when {
                    args.category !in outlineCategories(operation.bookId) ->
                        "分类已不存在，请重新预览并确认"

                    outlineItemCount(operation.bookId, args.category) != args.itemCount ->
                        "分类下的大纲条目已变化，请重新预览并确认"

                    else -> null
                }
            }

            AgentWriteOperationType.MOVE_OUTLINE_NODE -> {
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
                    val args = decodeMoveArgs(operation.argumentsJson)
                        ?: return "移动参数无法解析"
                    if (args.category == current.category) {
                        "节点已在该分类中，请重新预览并确认"
                    } else {
                        null
                    }
                }
            }

            AgentWriteOperationType.DELETE_OUTLINE_NODE -> {
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

            AgentWriteOperationType.CREATE_CHAPTER,
            AgentWriteOperationType.CREATE_VOLUME -> null

            AgentWriteOperationType.DELETE_CHAPTER -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: return "执行前快照无法解析"
                val current = writer.chapterMeta(operation.targetId)
                    ?: return "目标章节已删除"
                if (current.bookId != operation.bookId) return "目标章节已不属于当前书籍"
                if (current.title != before.title) {
                    "章节已在预览后发生变化，请重新预览并确认"
                } else {
                    null
                }
            }

            AgentWriteOperationType.DELETE_VOLUME -> {
                val before = decodeVolume(operation.beforeSnapshot)
                    ?: return "执行前快照无法解析"
                val current = writer.volume(operation.targetId)
                    ?: return "目标分卷已删除"
                if (current.bookId != operation.bookId) return "目标分卷已不属于当前书籍"
                if (current.title != before.title) {
                    "分卷已在预览后发生变化，请重新预览并确认"
                } else {
                    null
                }
            }

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

            AgentWriteOperationType.CREATE_OUTLINE_CATEGORY -> {
                val args = decodeCategoryArgs(operation.argumentsJson)
                    ?: return "分类参数无法解析"
                when {
                    args.category !in outlineCategories(operation.bookId) ->
                        "分类已不存在，无法撤销"

                    outlineItemCount(operation.bookId, args.category) > 0 ->
                        "分类下已有大纲条目，拒绝自动撤销"

                    else -> null
                }
            }

            AgentWriteOperationType.DELETE_OUTLINE_CATEGORY ->
                "删除大纲分类不支持撤销"

            AgentWriteOperationType.MOVE_OUTLINE_NODE -> {
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

            AgentWriteOperationType.CREATE_CHAPTER -> {
                val createdId = operation.createdTargetId
                    ?: return "未记录新建章节 id，无法撤销"
                val after = decodeChapter(operation.afterSnapshot)
                    ?: return "执行后快照无法解析"
                val current = writer.chapterMeta(createdId)
                    ?: return "新建章节已不存在，无法确认撤销"
                val content = writer.chapterContent(createdId).orEmpty()
                if (current.title != after.title ||
                    current.summary != after.summary ||
                    content.isNotEmpty()
                ) {
                    "新建章节已被编辑，拒绝自动撤销"
                } else {
                    null
                }
            }

            AgentWriteOperationType.CREATE_VOLUME -> {
                val createdId = operation.createdTargetId
                    ?: return "未记录新建分卷 id，无法撤销"
                val after = decodeVolume(operation.afterSnapshot)
                    ?: return "执行后快照无法解析"
                val current = writer.volume(createdId)
                    ?: return "新建分卷已不存在，无法确认撤销"
                if (current.title != after.title || writer.volumeChapterCount(createdId) > 0) {
                    "新建分卷已被修改或已有章节，拒绝自动撤销"
                } else {
                    null
                }
            }

            AgentWriteOperationType.DELETE_CHAPTER,
            AgentWriteOperationType.DELETE_VOLUME -> "删除操作不可撤销"

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
                // 新分类必须同步注册到书籍标签列表，否则大纲页没有对应标签页
                addOutlineCategory(operation.bookId, args.category)
                val after = writer.outline(createdId)
                    ?: error("新建大纲后无法读取节点")
                operations.markSucceeded(
                    operation.id,
                    encodeOutline(after),
                    createdTargetId = createdId
                )
            }

            AgentWriteOperationType.CREATE_OUTLINE_CATEGORY -> {
                val args = decodeCategoryArgs(operation.argumentsJson)
                    ?: error("新建分类参数无法解析")
                addOutlineCategory(operation.bookId, args.category)
                operations.markSucceeded(operation.id, "{}")
            }

            AgentWriteOperationType.DELETE_OUTLINE_CATEGORY -> {
                val args = decodeCategoryArgs(operation.argumentsJson)
                    ?: error("删除分类参数无法解析")
                deleteOutlineCategory(operation.bookId, args.category)
                operations.markSucceeded(operation.id, "{}")
            }

            AgentWriteOperationType.MOVE_OUTLINE_NODE -> {
                val args = decodeMoveArgs(operation.argumentsJson)
                    ?: error("移动大纲参数无法解析")
                val current = writer.outline(operation.targetId)
                    ?: error("目标大纲节点不存在")
                writer.updateOutlineContent(
                    outlineId = operation.targetId,
                    category = args.category,
                    tag = current.tag,
                    title = current.title,
                    content = current.content
                )
                addOutlineCategory(operation.bookId, args.category)
                val after = writer.outline(operation.targetId)
                    ?: error("移动后无法读取大纲节点")
                operations.markSucceeded(operation.id, encodeOutline(after))
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
                addOutlineCategory(operation.bookId, category)
                val after = writer.outline(operation.targetId)
                    ?: error("修改后大纲节点无法读取")
                operations.markSucceeded(operation.id, encodeOutline(after))
            }

            AgentWriteOperationType.CREATE_CHAPTER -> {
                val args = decodeChapterCreateArgs(operation.argumentsJson)
                    ?: error("新建章节参数无法解析")
                val createdId = writer.createChapter(
                    bookId = operation.bookId,
                    volumeId = args.volumeId,
                    title = args.title,
                    summary = args.summary
                )
                val meta = writer.chapterMeta(createdId)
                    ?: error("新建章节后无法读取章节")
                val after = AgentChapterSnapshot(
                    id = meta.id,
                    bookId = meta.bookId,
                    title = meta.title,
                    summary = meta.summary,
                    content = "",
                    contentIncluded = false,
                    contentLength = 0,
                    updatedAt = meta.updatedAt
                )
                operations.markSucceeded(
                    operation.id,
                    encodeChapter(after),
                    createdTargetId = createdId
                )
            }

            AgentWriteOperationType.DELETE_CHAPTER -> {
                val meta = writer.chapterMeta(operation.targetId)
                    ?: error("目标章节不存在")
                if (meta.bookId != operation.bookId) error("目标章节已不属于当前书籍")
                writer.deleteChapter(operation.bookId, operation.targetId)
                operations.markSucceeded(operation.id, "{}")
            }

            AgentWriteOperationType.DELETE_OUTLINE_NODE -> {
                val current = writer.outline(operation.targetId)
                    ?: error("目标大纲节点不存在")
                if (current.bookId != operation.bookId) error("目标大纲节点已不属于当前书籍")
                writer.deleteOutline(operation.targetId)
                operations.markSucceeded(operation.id, "{}")
            }

            AgentWriteOperationType.CREATE_VOLUME -> {
                val args = decodeVolumeCreateArgs(operation.argumentsJson)
                    ?: error("新建分卷参数无法解析")
                val createdId = writer.createVolume(operation.bookId, args.title)
                val after = writer.volume(createdId)
                    ?: error("新建分卷后无法读取分卷")
                operations.markSucceeded(
                    operation.id,
                    encodeVolume(after),
                    createdTargetId = createdId
                )
            }

            AgentWriteOperationType.DELETE_VOLUME -> {
                val volume = writer.volume(operation.targetId)
                    ?: error("目标分卷不存在")
                if (volume.bookId != operation.bookId) error("目标分卷已不属于当前书籍")
                writer.deleteVolume(operation.targetId)
                operations.markSucceeded(operation.id, "{}")
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

            AgentWriteOperationType.CREATE_OUTLINE_CATEGORY -> {
                val args = decodeCategoryArgs(operation.argumentsJson)
                    ?: error("分类参数无法解析")
                removeOutlineCategory(operation.bookId, args.category)
            }

            AgentWriteOperationType.MOVE_OUTLINE_NODE -> {
                val before = decodeOutline(operation.beforeSnapshot)
                    ?: error("执行前快照无法解析")
                writer.updateOutlineContent(
                    outlineId = operation.targetId,
                    category = before.category,
                    tag = before.tag,
                    title = before.title,
                    content = before.content
                )
                addOutlineCategory(operation.bookId, before.category)
            }

            AgentWriteOperationType.CREATE_CHAPTER -> {
                val createdId = operation.createdTargetId
                    ?: error("未记录新建章节 id")
                writer.deleteChapter(operation.bookId, createdId)
            }

            AgentWriteOperationType.CREATE_VOLUME -> {
                val createdId = operation.createdTargetId
                    ?: error("未记录新建分卷 id")
                writer.deleteVolume(createdId)
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

    private fun decodeCategoryArgs(raw: String): AgentOutlineCategoryArgs? = runCatching {
        agentJson.decodeFromString(AgentOutlineCategoryArgs.serializer(), raw)
    }.getOrNull()

    private fun decodeMoveArgs(raw: String): AgentOutlineMoveArgs? = runCatching {
        agentJson.decodeFromString(AgentOutlineMoveArgs.serializer(), raw)
    }.getOrNull()

    private fun decodeChapterCreateArgs(raw: String): AgentChapterCreateArgs? = runCatching {
        agentJson.decodeFromString(AgentChapterCreateArgs.serializer(), raw)
    }.getOrNull()

    private fun decodeVolumeCreateArgs(raw: String): AgentVolumeCreateArgs? = runCatching {
        agentJson.decodeFromString(AgentVolumeCreateArgs.serializer(), raw)
    }.getOrNull()

    private fun decodeVolume(raw: String): AgentVolumeSnapshot? = runCatching {
        agentJson.decodeFromString(AgentVolumeSnapshot.serializer(), raw)
    }.getOrNull()

    private fun encodeVolume(snapshot: AgentVolumeSnapshot): String =
        agentJson.encodeToString(AgentVolumeSnapshot.serializer(), snapshot)
}
