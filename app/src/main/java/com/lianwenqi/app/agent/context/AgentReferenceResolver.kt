package com.lianwenqi.app.agent.context

import com.lianwenqi.app.agent.data.dao.AgentReferenceChapterMeta
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanParseResult
import com.lianwenqi.app.agent.planning.AgentPlanParser

/** 一条待解析的引用目标；会话引用与任务快照都转换为该结构 */
data class AgentReferenceTarget(
    val referenceId: Long,
    val sourceType: String,
    val sourceId: Long,
    val bookId: Long? = null,
    val chapterId: Long? = null,
    val titleSnapshot: String = "",
    val referenceMode: String = ""
)

/**
 * 一条引用经过本地校验与读取后的结果。
 *
 * [content] 是真正允许进入模型上下文的部分；
 * 来源失效时 [valid] = false 且 [invalidReason] 非空，不静默替换来源。
 */
data class ResolvedAgentReference(
    val target: AgentReferenceTarget,
    val valid: Boolean,
    val invalidReason: String = "",
    val title: String = "",
    val bookTitle: String = "",
    val volumeTitle: String = "",
    /** 章节类资料的章节标题；非章节来源为空 */
    val chapterTitle: String = "",
    val content: String = "",
    val contentStart: Int = 0,
    val contentTotalChars: Int = 0,
    val truncated: Boolean = false,
    val empty: Boolean = false,
    val historicalNote: String = "",
    val sourceTimestamp: Long? = null,
    val sourceLabel: String = AgentContextSource.label(target.sourceType)
) {
    val displayTitle: String
        get() = title.ifBlank {
            target.titleSnapshot.ifBlank { "来源 #${target.sourceId}" }
        }
}

/**
 * 历史资料解析器（F5）。
 *
 * 只通过注入的只读 lambda 读取来源，不直接访问数据库，便于 JVM 单元测试。
 * 所有来源都必须经过：
 * 1. 存在性校验；
 * 2. 书籍归属校验（跨书引用必须与用户选择时记录的 bookId 一致）；
 * 3. 类型白名单校验（拒绝 PromptLog / 隐藏 reasoning / 系统提示）；
 * 4. 当前任务隔离（不能引用当前正在执行的任务资料）。
 */
class AgentReferenceResolver(
    private val chapterMetaOf: suspend (Long) -> AgentReferenceChapterMeta?,
    private val chapterContentOf: suspend (Long, Int, Int) -> String?,
    private val bookTitleOf: suspend (Long) -> String?,
    private val volumeTitleOf: suspend (Long) -> String?,
    private val agentTaskOf: suspend (Long) -> AgentTask?,
    private val agentSessionOf: suspend (Long) -> AgentSession?,
    private val agentMessageOf: suspend (Long) -> AgentMessage?,
    private val toolExecutionOf: suspend (Long) -> AgentToolExecution?,
    private val finalAnswerOf: suspend (Long) -> String?,
    private val planParser: AgentPlanParser = AgentPlanParser()
) {

    /**
     * 解析一条引用。
     *
     * [contentMaxChars] <= 0 表示只做状态校验，不加载正文。
     * [excludedTaskId] 是当前正在执行的任务，禁止引用自身资料。
     */
    suspend fun resolve(
        target: AgentReferenceTarget,
        contentMaxChars: Int,
        excludedTaskId: Long = 0L
    ): ResolvedAgentReference = when (target.sourceType) {
        AgentReferenceSourceType.CHAPTER_SYNOPSIS -> resolveChapterSynopsis(target, contentMaxChars)
        AgentReferenceSourceType.CHAPTER_CONTENT -> resolveChapterContent(target, contentMaxChars)
        AgentReferenceSourceType.AGENT_TASK_PLAN ->
            resolveAgentTaskPlan(target, contentMaxChars, excludedTaskId)
        AgentReferenceSourceType.AGENT_TASK_RESULT ->
            resolveAgentTaskResult(target, contentMaxChars, excludedTaskId)
        AgentReferenceSourceType.AGENT_MESSAGE ->
            resolveAgentMessage(target, contentMaxChars, excludedTaskId)
        AgentReferenceSourceType.AGENT_TOOL_RESULT ->
            resolveAgentToolResult(target, contentMaxChars, excludedTaskId)
        AgentReferenceSourceType.AGENT_SESSION -> invalid(
            target,
            "不支持整段历史会话引用，请选择具体任务、消息或工具结果"
        )
        else -> invalid(target, "F5 不支持该来源类型：${target.sourceType}")
    }

    // ---------------- 章节 ----------------

    private suspend fun resolveChapterSynopsis(
        target: AgentReferenceTarget,
        contentMaxChars: Int
    ): ResolvedAgentReference {
        val meta = chapterMeta(target) ?: return invalid(target, "章节已删除或不存在")
        chapterBookMismatch(target, meta)?.let { return invalid(target, it) }
        val full = meta.summary
        val content = slice(full, contentMaxChars)
        return ResolvedAgentReference(
            target = target,
            valid = true,
            title = meta.title,
            bookTitle = bookTitleOf(meta.bookId).orEmpty(),
            volumeTitle = meta.volumeId?.let { volumeTitleOf(it) }.orEmpty(),
            chapterTitle = meta.title,
            content = content,
            contentTotalChars = full.length,
            truncated = contentMaxChars > 0 && full.length > content.length,
            empty = full.isBlank(),
            historicalNote = AgentContextSource.historicalNote(target.sourceType),
            sourceTimestamp = meta.updatedAt
        )
    }

    private suspend fun resolveChapterContent(
        target: AgentReferenceTarget,
        contentMaxChars: Int
    ): ResolvedAgentReference {
        val meta = chapterMeta(target) ?: return invalid(target, "章节已删除或不存在")
        chapterBookMismatch(target, meta)?.let { return invalid(target, it) }
        val total = meta.contentLength
        val requested = if (contentMaxChars <= 0) 0 else minOf(contentMaxChars, total)
        val content = if (requested > 0) {
            chapterContentOf(meta.id, 0, requested).orEmpty().take(requested)
        } else {
            ""
        }
        return ResolvedAgentReference(
            target = target,
            valid = true,
            title = meta.title,
            bookTitle = bookTitleOf(meta.bookId).orEmpty(),
            volumeTitle = meta.volumeId?.let { volumeTitleOf(it) }.orEmpty(),
            chapterTitle = meta.title,
            content = content,
            contentStart = 0,
            contentTotalChars = total,
            truncated = requested > 0 && content.length < total,
            empty = total == 0,
            historicalNote = AgentContextSource.historicalNote(target.sourceType),
            sourceTimestamp = meta.updatedAt
        )
    }

    private suspend fun chapterMeta(target: AgentReferenceTarget): AgentReferenceChapterMeta? {
        val chapterId = target.chapterId ?: target.sourceId
        return chapterMetaOf(chapterId)
    }

    private fun chapterBookMismatch(
        target: AgentReferenceTarget,
        meta: AgentReferenceChapterMeta
    ): String? =
        if (target.bookId != null && target.bookId != meta.bookId) {
            "来源书籍与引用记录不一致"
        } else {
            null
        }

    // ---------------- 历史 Agent 任务 ----------------

    private suspend fun resolveAgentTaskPlan(
        target: AgentReferenceTarget,
        contentMaxChars: Int,
        excludedTaskId: Long
    ): ResolvedAgentReference {
        val task = agentTask(target.sourceId, excludedTaskId)
            ?: return invalid(target, taskInvalidReason(target.sourceId, excludedTaskId))
        val session = agentSessionOf(task.sessionId)
            ?: return invalid(target, "历史任务所属会话已删除")
        bookMismatch(target, session)?.let { return invalid(target, it) }
        val plan = when (val parsed = planParser.parse(task.planJson)) {
            is AgentPlanParseResult.Success -> parsed.plan
            is AgentPlanParseResult.Failure ->
                if (task.planJson.isBlank() || task.planJson == "{}") {
                    AgentPlan()
                } else {
                    return invalid(target, "历史计划无法解析，未注入原文")
                }
        }
        val full = renderPlan(task, plan)
        val content = slice(full, contentMaxChars)
        return ResolvedAgentReference(
            target = target,
            valid = true,
            title = "任务：${task.title}",
            bookTitle = session.bookId?.let { bookTitleOf(it) }.orEmpty(),
            content = content,
            contentTotalChars = full.length,
            truncated = contentMaxChars > 0 && full.length > content.length,
            empty = full.isBlank(),
            historicalNote = AgentContextSource.historicalNote(target.sourceType),
            sourceTimestamp = task.completedAt ?: task.updatedAt
        )
    }

    private suspend fun resolveAgentTaskResult(
        target: AgentReferenceTarget,
        contentMaxChars: Int,
        excludedTaskId: Long
    ): ResolvedAgentReference {
        val task = agentTask(target.sourceId, excludedTaskId)
            ?: return invalid(target, taskInvalidReason(target.sourceId, excludedTaskId))
        val session = agentSessionOf(task.sessionId)
            ?: return invalid(target, "历史任务所属会话已删除")
        bookMismatch(target, session)?.let { return invalid(target, it) }
        val full = finalAnswerOf(task.id).orEmpty()
        val content = slice(full, contentMaxChars)
        return ResolvedAgentReference(
            target = target,
            valid = true,
            title = "任务结果：${task.title}",
            bookTitle = session.bookId?.let { bookTitleOf(it) }.orEmpty(),
            content = content,
            contentTotalChars = full.length,
            truncated = contentMaxChars > 0 && full.length > content.length,
            empty = full.isBlank(),
            historicalNote = AgentContextSource.historicalNote(target.sourceType),
            sourceTimestamp = task.completedAt ?: task.updatedAt
        )
    }

    private suspend fun agentTask(taskId: Long, excludedTaskId: Long): AgentTask? {
        if (excludedTaskId > 0 && taskId == excludedTaskId) return null
        return agentTaskOf(taskId)
    }

    private fun taskInvalidReason(taskId: Long, excludedTaskId: Long): String =
        if (excludedTaskId > 0 && taskId == excludedTaskId) {
            "不能引用当前正在执行的任务"
        } else {
            "历史任务已删除或不存在"
        }

    private fun renderPlan(task: AgentTask, plan: AgentPlan): String = buildString {
        appendLine("历史任务目标：${task.userGoal.take(4_000)}")
        appendLine("历史任务标题：${task.title.take(200)}")
        appendLine("历史任务状态：${task.status}")
        if (plan.goalSummary.isNotBlank()) {
            appendLine("历史计划概括：${plan.goalSummary.take(1_000)}")
        }
        appendLine("历史计划步骤：")
        if (plan.steps.isEmpty()) {
            appendLine("（该任务没有结构化计划）")
        } else {
            plan.steps.forEachIndexed { index, step ->
                appendLine(
                    "${index + 1}. ${step.title.take(200)}（${step.capabilityKey}）：" +
                        "${step.description.take(1_000)}；预期产出：${step.expectedOutput.take(300)}"
                )
            }
        }
    }.trimEnd()

    // ---------------- 历史消息 / 工具结果 ----------------

    private suspend fun resolveAgentMessage(
        target: AgentReferenceTarget,
        contentMaxChars: Int,
        excludedTaskId: Long
    ): ResolvedAgentReference {
        val message = agentMessageOf(target.sourceId)
            ?: return invalid(target, "历史消息已删除或不存在")
        if (message.kind !in REFERABLE_MESSAGE_KINDS) {
            return invalid(target, "该消息类型不提供引用入口：" + message.kind)
        }
        if (excludedTaskId > 0 && message.taskId == excludedTaskId) {
            return invalid(target, "不能引用当前正在执行的任务消息")
        }
        val session = agentSessionOf(message.sessionId)
            ?: return invalid(target, "历史消息所属会话已删除")
        bookMismatch(target, session)?.let { return invalid(target, it) }
        // 只允许 content；promptLog / thinkingSummary 永远不进入引用
        val full = message.content
        val content = slice(full, contentMaxChars)
        return ResolvedAgentReference(
            target = target,
            valid = true,
            title = "消息 #${message.sequence} · " +
                AgentContextSource.messageKindLabel(message.kind),
            bookTitle = session.bookId?.let { bookTitleOf(it) }.orEmpty(),
            content = content,
            contentTotalChars = full.length,
            truncated = contentMaxChars > 0 && full.length > content.length,
            empty = full.isBlank(),
            historicalNote = AgentContextSource.historicalNote(target.sourceType),
            sourceTimestamp = message.createdAt
        )
    }

    private suspend fun resolveAgentToolResult(
        target: AgentReferenceTarget,
        contentMaxChars: Int,
        excludedTaskId: Long
    ): ResolvedAgentReference {
        val execution = toolExecutionOf(target.sourceId)
            ?: return invalid(target, "历史工具结果已删除或不存在")
        if (execution.status != AgentToolStatus.SUCCEEDED) {
            return invalid(
                target,
                "历史工具结果状态为 ${execution.status}，只有成功结果可以引用"
            )
        }
        if (excludedTaskId > 0 && execution.taskId == excludedTaskId) {
            return invalid(target, "不能引用当前正在执行任务的工具结果")
        }
        val task = agentTaskOf(execution.taskId)
            ?: return invalid(target, "历史工具结果所属任务已删除")
        val session = agentSessionOf(task.sessionId)
            ?: return invalid(target, "历史工具结果所属会话已删除")
        bookMismatch(target, session)?.let { return invalid(target, it) }
        val full = execution.resultJson
        val content = slice(full, contentMaxChars)
        return ResolvedAgentReference(
            target = target,
            valid = true,
            title = (execution.toolDisplayName.ifBlank { execution.toolName }) + " · 历史工具结果",
            bookTitle = session.bookId?.let { bookTitleOf(it) }.orEmpty(),
            content = content,
            contentTotalChars = full.length,
            truncated = contentMaxChars > 0 && full.length > content.length,
            empty = full.isBlank() || full == "{}",
            historicalNote = AgentContextSource.historicalNote(target.sourceType),
            sourceTimestamp = execution.completedAt ?: execution.createdAt
        )
    }

    // ---------------- 公共校验 ----------------

    private fun bookMismatch(
        target: AgentReferenceTarget,
        session: AgentSession
    ): String? {
        val referenceBookId = target.bookId ?: return null
        val actualBookId = session.bookId ?: return null
        return if (referenceBookId != actualBookId) {
            "来源书籍与引用记录不一致"
        } else {
            null
        }
    }

    private fun slice(text: String, maxChars: Int): String =
        if (maxChars <= 0) "" else text.take(maxChars)

    private fun invalid(target: AgentReferenceTarget, reason: String): ResolvedAgentReference =
        ResolvedAgentReference(
            target = target,
            valid = false,
            invalidReason = reason,
            title = target.titleSnapshot,
            sourceLabel = AgentContextSource.label(target.sourceType)
        )

    companion object {
        /** 可被引用的消息类型；NOTICE / SUMMARY 不提供引用入口 */
        val REFERABLE_MESSAGE_KINDS = setOf(
            AgentMessageKind.USER_INPUT,
            AgentMessageKind.FINAL_ANSWER,
            AgentMessageKind.PLAN
        )
    }
}
