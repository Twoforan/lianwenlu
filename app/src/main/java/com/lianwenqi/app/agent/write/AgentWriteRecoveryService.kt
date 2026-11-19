package com.lianwenqi.app.agent.write

import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType

/** 写入中断核对结果；只包含计数和提示，不包含正文 */
data class AgentWriteRecoveryReport(
    val recoveredSucceeded: Int = 0,
    val markedFailed: Int = 0,
    val conflicts: Int = 0,
    val message: String = ""
) {
    val inconsistent: Boolean get() = conflicts > 0
    val changed: Boolean get() = recoveredSucceeded > 0 || markedFailed > 0 || conflicts > 0
}

/**
 * F7 写入恢复核对。
 *
 * 进程可能在「业务数据已写入，但 SUCCEEDED 状态尚未提交」之间死亡。
 * 恢复时重新读取真实业务数据：
 * - 与提议内容一致 → 补记 SUCCEEDED，不重复写入；
 * - 仍与执行前快照一致 → 标记 FAILED，不自动重试；
 * - 被其他修改改过 → CONFLICT，不覆盖；
 * - 新建大纲无法定位已创建节点 → FAILED，并明确提示需要人工核对。
 */
class AgentWriteRecoveryService(
    private val operations: AgentWriteOperationRepository,
    private val writer: AgentWriteBusinessWriter
) {

    suspend fun reconcileInterrupted(taskId: Long): AgentWriteRecoveryReport {
        val executing = operations.ofTask(taskId)
            .filter { it.executionStatus == AgentWriteOperationStatus.EXECUTING }
        if (executing.isEmpty()) return AgentWriteRecoveryReport()

        var recovered = 0
        var failed = 0
        var conflicts = 0
        val messages = mutableListOf<String>()

        executing.forEach { operation ->
            val inspection = runCatching { inspect(operation) }
                .getOrElse {
                    Inspection.NotApplied(
                        "恢复核对失败：" + (it.message ?: "未知错误") + "；未自动重试"
                    )
                }
            when (inspection) {
                is Inspection.Applied -> {
                    operations.markSucceededFromRecovery(operation.id, inspection.afterSnapshot)
                    recovered++
                    messages += "操作 #${operation.id} 已确认写入，补记成功"
                }

                is Inspection.NotApplied -> {
                    operations.markFailed(operation.id, inspection.message)
                    failed++
                    messages += "操作 #${operation.id} 未确认写入，标记失败"
                }

                is Inspection.Conflicted -> {
                    operations.markConflict(operation.id, inspection.message)
                    conflicts++
                    messages += "操作 #${operation.id} 数据已变化，标记冲突"
                }
            }
        }

        return AgentWriteRecoveryReport(
            recoveredSucceeded = recovered,
            markedFailed = failed,
            conflicts = conflicts,
            message = messages.joinToString("；").take(1_000)
        )
    }

    private suspend fun inspect(operation: AgentWriteOperation): Inspection =
        when (operation.operationType) {
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: return Inspection.NotApplied("执行前快照无法解析；未自动重试")
                val current = writer.chapterMeta(operation.targetId)
                    ?: return Inspection.NotApplied("目标章节已删除；未自动重试")
                when {
                    current.summary == operation.proposedContent -> Inspection.Applied(
                        encodeChapter(
                            before.copy(
                                summary = operation.proposedContent,
                                content = "",
                                contentIncluded = false,
                                contentLength = current.contentLength,
                                updatedAt = current.updatedAt
                            )
                        )
                    )

                    current.summary == before.summary ->
                        Inspection.NotApplied("上次写入未生效；未自动重试")

                    else -> Inspection.Conflicted("章节梗概已被其他修改覆盖，拒绝自动恢复")
                }
            }

            AgentWriteOperationType.UPDATE_CHAPTER_CONTENT -> {
                val before = decodeChapter(operation.beforeSnapshot)
                    ?: return Inspection.NotApplied("执行前快照无法解析；未自动重试")
                val currentContent = writer.chapterContent(operation.targetId)
                    ?: return Inspection.NotApplied("目标章节正文无法读取；未自动重试")
                val currentMeta = writer.chapterMeta(operation.targetId)
                when {
                    currentContent == operation.proposedContent -> Inspection.Applied(
                        encodeChapter(
                            before.copy(
                                content = currentContent,
                                contentIncluded = true,
                                contentLength = currentContent.length,
                                updatedAt = currentMeta?.updatedAt ?: before.updatedAt
                            )
                        )
                    )

                    currentContent == before.content ->
                        Inspection.NotApplied("上次正文覆盖未生效；未自动重试")

                    else -> Inspection.Conflicted("章节正文已被其他修改覆盖，拒绝自动恢复")
                }
            }

            AgentWriteOperationType.CREATE_OUTLINE_NODE ->
                Inspection.NotApplied(
                    "新建大纲操作中断，无法定位可能已创建的节点；请人工核对后重新提议"
                )

            AgentWriteOperationType.UPDATE_OUTLINE_NODE -> {
                val before = decodeOutline(operation.beforeSnapshot)
                    ?: return Inspection.NotApplied("执行前快照无法解析；未自动重试")
                val current = writer.outline(operation.targetId)
                    ?: return Inspection.NotApplied("目标大纲节点已删除；未自动重试")
                val args = decodeUpdateArgs(operation.argumentsJson)
                    ?: return Inspection.NotApplied("修改大纲参数无法解析；未自动重试")
                val expectedCategory = args.category ?: before.category
                val expectedTag = args.tag ?: before.tag
                val expectedTitle = args.title ?: before.title
                val expectedContent = args.content ?: before.content
                when {
                    current.category == expectedCategory &&
                        current.tag == expectedTag &&
                        current.title == expectedTitle &&
                        current.content == expectedContent -> Inspection.Applied(
                        encodeOutline(current)
                    )

                    current.category == before.category &&
                        current.tag == before.tag &&
                        current.title == before.title &&
                        current.content == before.content ->
                        Inspection.NotApplied("上次大纲修改未生效；未自动重试")

                    else -> Inspection.Conflicted("大纲节点已被其他修改覆盖，拒绝自动恢复")
                }
            }

            else -> Inspection.NotApplied("不支持的写入类型；未自动重试")
        }

    private sealed interface Inspection {
        data class Applied(val afterSnapshot: String) : Inspection
        data class NotApplied(val message: String) : Inspection
        data class Conflicted(val message: String) : Inspection
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

    private fun decodeUpdateArgs(raw: String): AgentOutlineUpdateArgs? = runCatching {
        agentJson.decodeFromString(AgentOutlineUpdateArgs.serializer(), raw)
    }.getOrNull()
}
