package com.lianwenqi.app.agent.write

import com.lianwenqi.app.agent.data.AgentWriteDraft
import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.data.entity.AgentWriteLimits
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.agent.data.entity.AgentWriteTargetType
import com.lianwenqi.app.agent.data.jsonPayload
import com.lianwenqi.app.agent.tool.AgentTool
import com.lianwenqi.app.agent.tool.AgentToolParam
import com.lianwenqi.app.agent.tool.AgentToolParamType
import com.lianwenqi.app.agent.tool.AgentToolPermission
import com.lianwenqi.app.agent.tool.AgentToolResult
import com.lianwenqi.app.ai.PromptDiagnostics
import com.lianwenqi.app.data.entity.Book
import kotlinx.serialization.json.put

/**
 * F6 第一批受控写入工具。
 *
 * 工具本身只做校验、生成预览并落库提议，不写任何小说业务数据；
 * 真正的写入必须经过 AgentWriteExecutionService 的授权与执行阶段。
 */
class AgentWriteTools(
    private val bookOf: suspend (Long) -> Book?,
    private val chapterMetaOf: suspend (Long) -> AgentChapterSnapshot?,
    private val chapterContentOf: suspend (Long) -> String?,
    private val outlineOf: suspend (Long) -> AgentOutlineSnapshot?,
    private val writeService: AgentWriteExecutionService
) {

    fun create(): List<AgentTool> = listOf(
        updateChapterSynopsis(),
        updateChapterContent(),
        createOutlineNode(),
        updateOutlineNode()
    )

    private fun updateChapterSynopsis(): AgentTool = AgentTool(
        toolKey = "update_chapter_synopsis",
        name = "update_chapter_synopsis",
        description = "提出修改指定章节的梗概；只生成待确认操作，不会立即写入。",
        capabilityKey = "WRITE_CHAPTER_SYNOPSIS",
        permissionLevel = AgentToolPermission.UPDATE,
        maxArgumentsChars = 24_000,
        parameters = listOf(
            chapterIdParam(),
            AgentToolParam(
                name = "synopsis",
                type = AgentToolParamType.STRING,
                description = "新的章节梗概",
                required = true,
                minLength = 1,
                maxLength = AgentWriteLimits.MAX_SYNOPSIS_CHARS
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val chapterId = args.long("chapterId")
        val chapter = chapterMetaOf(chapterId)
            ?: return@AgentTool AgentToolResult.fail("章节不存在：$chapterId")
        if (chapter.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权修改其他书籍的章节：$chapterId")
        }
        val synopsis = args.string("synopsis")
        if (synopsis.isBlank()) return@AgentTool AgentToolResult.fail("梗概不能为空")
        if (synopsis.length > AgentWriteLimits.MAX_SYNOPSIS_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "梗概超过上限（${AgentWriteLimits.MAX_SYNOPSIS_CHARS} 字）"
            )
        }
        if (chapter.summary == synopsis) {
            return@AgentTool AgentToolResult.fail("新梗概与当前梗概相同，未产生修改")
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val before = chapter.copy(
            content = "",
            contentIncluded = false,
            contentLength = chapter.contentLength
        )
        val summary = "修改《${chapter.title}》梗概：" +
            "${chapter.summary.length} 字 → ${synopsis.length} 字" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "update_chapter_synopsis",
            operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
            targetType = AgentWriteTargetType.CHAPTER,
            targetId = chapterId,
            book = book,
            targetTitle = chapter.title,
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            argumentsJson = jsonPayload {
                put("chapterId", chapterId)
                if (reason.isNotBlank()) put("reason", reason)
            },
            beforeSnapshot = agentJson.encodeToString(
                AgentChapterSnapshot.serializer(),
                before
            ),
            proposedContent = synopsis,
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "update_chapter_synopsis",
                "$chapterId:${PromptDiagnostics.hash(synopsis)}"
            )
        )
    }

    private fun updateChapterContent(): AgentTool = AgentTool(
        toolKey = "update_chapter_content",
        name = "update_chapter_content",
        description = "提出覆盖指定章节正文；高风险操作，必须逐次确认，不会立即写入。",
        capabilityKey = "WRITE_CHAPTER_CONTENT",
        permissionLevel = AgentToolPermission.OVERWRITE,
        maxArgumentsChars = 120_000,
        parameters = listOf(
            chapterIdParam(),
            AgentToolParam(
                name = "content",
                type = AgentToolParamType.STRING,
                description = "新的章节正文",
                required = true,
                minLength = 1,
                maxLength = AgentWriteLimits.MAX_CONTENT_CHARS
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val chapterId = args.long("chapterId")
        val chapter = chapterMetaOf(chapterId)
            ?: return@AgentTool AgentToolResult.fail("章节不存在：$chapterId")
        if (chapter.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权修改其他书籍的章节：$chapterId")
        }
        if (chapter.contentLength > AgentWriteLimits.MAX_CONTENT_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "当前章节正文超过受控写入上限（${AgentWriteLimits.MAX_CONTENT_CHARS} 字）"
            )
        }
        val content = args.string("content")
        if (content.isBlank()) return@AgentTool AgentToolResult.fail("正文不能为空")
        if (content.length > AgentWriteLimits.MAX_CONTENT_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "正文超过上限（${AgentWriteLimits.MAX_CONTENT_CHARS} 字）"
            )
        }
        val currentContent = chapterContentOf(chapterId)
            ?: return@AgentTool AgentToolResult.fail("当前章节正文无法读取")
        if (currentContent == content) {
            return@AgentTool AgentToolResult.fail("新正文与当前正文相同，未产生修改")
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val before = chapter.copy(
            content = currentContent,
            contentIncluded = true,
            contentLength = currentContent.length
        )
        val summary = "覆盖《${chapter.title}》正文：" +
            "${currentContent.length} 字 → ${content.length} 字" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "update_chapter_content",
            operationType = AgentWriteOperationType.UPDATE_CHAPTER_CONTENT,
            targetType = AgentWriteTargetType.CHAPTER,
            targetId = chapterId,
            book = book,
            targetTitle = chapter.title,
            riskLevel = AgentWriteRiskLevel.HIGH_RISK_WRITE,
            argumentsJson = jsonPayload {
                put("chapterId", chapterId)
                if (reason.isNotBlank()) put("reason", reason)
            },
            beforeSnapshot = agentJson.encodeToString(
                AgentChapterSnapshot.serializer(),
                before
            ),
            proposedContent = content,
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "update_chapter_content",
                "$chapterId:${PromptDiagnostics.hash(content)}"
            )
        )
    }

    private fun createOutlineNode(): AgentTool = AgentTool(
        toolKey = "create_outline_node",
        name = "create_outline_node",
        description = "提出在当前书籍中创建大纲节点；只生成待确认操作，不会立即写入。",
        capabilityKey = "CREATE_OUTLINE_NODE",
        permissionLevel = AgentToolPermission.CREATE,
        maxArgumentsChars = 24_000,
        parameters = listOf(
            AgentToolParam(
                name = "category",
                type = AgentToolParamType.STRING,
                description = "大纲分类存储值或展示名，例如 STORY / 人设 / WORLD",
                required = true,
                minLength = 1,
                maxLength = 50
            ),
            AgentToolParam(
                name = "title",
                type = AgentToolParamType.STRING,
                description = "节点标题",
                required = true,
                minLength = 1,
                maxLength = AgentWriteLimits.MAX_OUTLINE_TITLE_CHARS
            ),
            AgentToolParam(
                name = "content",
                type = AgentToolParamType.STRING,
                description = "节点内容",
                required = true,
                minLength = 1,
                maxLength = AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS
            ),
            AgentToolParam(
                name = "tag",
                type = AgentToolParamType.STRING,
                description = "可选标签，例如明线 / 暗线",
                required = false,
                maxLength = 100
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val category = args.string("category").trim()
        val title = args.string("title").trim()
        val content = args.string("content")
        val tag = args.string("tag").trim()
        if (category.isEmpty()) return@AgentTool AgentToolResult.fail("category 不能为空")
        if (title.isEmpty()) return@AgentTool AgentToolResult.fail("title 不能为空")
        if (content.isBlank()) return@AgentTool AgentToolResult.fail("content 不能为空")
        if (content.length > AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "大纲内容超过上限（${AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS} 字）"
            )
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val argsJson = agentJson.encodeToString(
            AgentOutlineCreateArgs.serializer(),
            AgentOutlineCreateArgs(category, tag, title, content)
        )
        val summary = "在《${book.title}》的「$category」中创建大纲节点《$title》" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "create_outline_node",
            operationType = AgentWriteOperationType.CREATE_OUTLINE_NODE,
            targetType = AgentWriteTargetType.OUTLINE,
            targetId = 0L,
            book = book,
            targetTitle = title,
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            argumentsJson = argsJson,
            beforeSnapshot = "{}",
            proposedContent = content,
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "create_outline_node",
                "$category:$tag:$title:${PromptDiagnostics.hash(content)}"
            )
        )
    }

    private fun updateOutlineNode(): AgentTool = AgentTool(
        toolKey = "update_outline_node",
        name = "update_outline_node",
        description = "提出修改当前书籍中已有的大纲节点；只生成待确认操作，不会立即写入。",
        capabilityKey = "UPDATE_OUTLINE_NODE",
        permissionLevel = AgentToolPermission.UPDATE,
        maxArgumentsChars = 24_000,
        parameters = listOf(
            AgentToolParam(
                name = "outlineId",
                type = AgentToolParamType.LONG,
                description = "大纲条目 id",
                required = true,
                minValue = 1
            ),
            AgentToolParam(
                name = "category",
                type = AgentToolParamType.STRING,
                description = "可选新分类",
                required = false,
                maxLength = 50
            ),
            AgentToolParam(
                name = "title",
                type = AgentToolParamType.STRING,
                description = "可选新标题",
                required = false,
                maxLength = AgentWriteLimits.MAX_OUTLINE_TITLE_CHARS
            ),
            AgentToolParam(
                name = "content",
                type = AgentToolParamType.STRING,
                description = "可选新内容",
                required = false,
                maxLength = AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS
            ),
            AgentToolParam(
                name = "tag",
                type = AgentToolParamType.STRING,
                description = "可选新标签",
                required = false,
                maxLength = 100
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val outlineId = args.long("outlineId")
        val current = outlineOf(outlineId)
            ?: return@AgentTool AgentToolResult.fail("大纲节点不存在：$outlineId")
        if (current.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权修改其他书籍的大纲节点：$outlineId")
        }
        val category = args.string("category").trim().takeIf { it.isNotEmpty() }
        val title = args.string("title").trim().takeIf { it.isNotEmpty() }
        val content = args.string("content").takeIf { it.isNotEmpty() }
        val tag = args.string("tag").trim().takeIf { it.isNotEmpty() }
        if (category == null && title == null && content == null && tag == null) {
            return@AgentTool AgentToolResult.fail("至少需要提供一个要修改的字段")
        }
        if (content != null && content.length > AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "大纲内容超过上限（${AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS} 字）"
            )
        }
        val mergedCategory = category ?: current.category
        val mergedTitle = title ?: current.title
        val mergedContent = content ?: current.content
        val mergedTag = tag ?: current.tag
        if (mergedCategory == current.category && mergedTitle == current.title &&
            mergedContent == current.content && mergedTag == current.tag
        ) {
            return@AgentTool AgentToolResult.fail("修改后内容与当前节点相同，未产生修改")
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val updateArgs = AgentOutlineUpdateArgs(
            category = category,
            tag = tag,
            title = title,
            content = content
        )
        val changed = buildList {
            if (category != null) add("分类")
            if (tag != null) add("标签")
            if (title != null) add("标题")
            if (content != null) add("内容")
        }.joinToString("、")
        val summary = "修改大纲节点《${current.title}》的$changed 字段" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "update_outline_node",
            operationType = AgentWriteOperationType.UPDATE_OUTLINE_NODE,
            targetType = AgentWriteTargetType.OUTLINE,
            targetId = outlineId,
            book = book,
            targetTitle = current.title,
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            argumentsJson = agentJson.encodeToString(
                AgentOutlineUpdateArgs.serializer(),
                updateArgs
            ),
            beforeSnapshot = agentJson.encodeToString(
                AgentOutlineSnapshot.serializer(),
                current
            ),
            proposedContent = mergedContent,
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "update_outline_node",
                "$outlineId:$mergedCategory:$mergedTag:$mergedTitle:" +
                    PromptDiagnostics.hash(mergedContent)
            )
        )
    }

    private suspend fun propose(
        context: com.lianwenqi.app.agent.tool.AgentExecutionContext,
        toolName: String,
        operationType: String,
        targetType: String,
        targetId: Long,
        book: Book,
        targetTitle: String,
        riskLevel: String,
        argumentsJson: String,
        beforeSnapshot: String,
        proposedContent: String,
        operationSummary: String,
        reversible: Boolean,
        idempotencyKey: String
    ): AgentToolResult {
        val result = writeService.propose(
            AgentWriteDraft(
                taskId = context.taskId,
                sessionId = context.sessionId,
                nodeId = context.nodeId,
                toolName = toolName,
                operationType = operationType,
                targetType = targetType,
                targetId = targetId,
                bookId = book.id,
                targetTitleSnapshot = targetTitle,
                bookTitleSnapshot = book.title,
                riskLevel = riskLevel,
                argumentsJson = argumentsJson,
                beforeSnapshot = beforeSnapshot,
                proposedContent = proposedContent,
                operationSummary = operationSummary,
                reversible = reversible,
                idempotencyKey = idempotencyKey
            )
        )
        val operation = result.operation
        return when {
            operation.executionStatus == AgentWriteOperationStatus.SUCCEEDED ->
                AgentToolResult.ok(
                    contentJson = resultJson(operation, "SUCCEEDED"),
                    summary = "写入成功：${operation.operationSummary}",
                    writeOperationId = operation.id
                )

            operation.executionStatus == AgentWriteOperationStatus.REJECTED ->
                AgentToolResult.fail(
                    result.errorMessage.ifBlank { "用户已拒绝该写入操作" },
                    writeOperationId = operation.id
                )

            operation.executionStatus == AgentWriteOperationStatus.FAILED ->
                AgentToolResult.fail(
                    result.errorMessage.ifBlank { "写入失败" },
                    writeOperationId = operation.id
                )

            operation.executionStatus == AgentWriteOperationStatus.CONFLICT ->
                AgentToolResult.fail(
                    result.errorMessage.ifBlank { "目标数据已变化，需要重新预览" },
                    writeOperationId = operation.id
                )

            operation.executionStatus == AgentWriteOperationStatus.EXECUTING ->
                AgentToolResult.fail(
                    "写入操作正在执行，请稍后重试",
                    writeOperationId = operation.id
                )

            result.requiresConfirmation ->
                AgentToolResult.ok(
                    contentJson = resultJson(operation, "WAITING_CONFIRMATION"),
                    summary = "已生成写入预览，等待用户确认",
                    writeOperationId = operation.id,
                    writeRequiresConfirmation = true
                )

            else -> AgentToolResult.ok(
                contentJson = resultJson(operation, operation.executionStatus),
                summary = operation.operationSummary,
                writeOperationId = operation.id
            )
        }
    }

    private fun resultJson(
        operation: com.lianwenqi.app.agent.data.entity.AgentWriteOperation,
        status: String
    ): String = jsonPayload {
        put("operationId", operation.id)
        put("operationType", operation.operationType)
        put("targetType", operation.targetType)
        put("targetId", operation.targetId)
        put("riskLevel", operation.riskLevel)
        put("status", status)
        put("reversible", operation.reversible)
        put("operationSummary", operation.operationSummary)
    }

    private fun chapterIdParam() = AgentToolParam(
        name = "chapterId",
        type = AgentToolParamType.LONG,
        description = "章节 id",
        required = true,
        minValue = 1
    )

    private fun reasonParam() = AgentToolParam(
        name = "reason",
        type = AgentToolParamType.STRING,
        description = "可选修改原因，用于向用户说明",
        required = false,
        maxLength = AgentWriteLimits.MAX_REASON_CHARS
    )

    private fun writeKey(taskId: Long, toolName: String, material: String): String =
        "write:$taskId:$toolName:${PromptDiagnostics.hash(material)}"

    companion object {
        private const val NO_BOOK = "当前 Agent 会话未绑定书籍，无法写入小说资料"
    }
}
