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
import com.lianwenqi.app.data.buildOutlineContentForAgent
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.data.encodeOutlineFields
import com.lianwenqi.app.data.isStructuredOutlineContent
import com.lianwenqi.app.data.outlineDisplayName
import com.lianwenqi.app.data.outlineFieldsForDisplay
import com.lianwenqi.app.data.parseOutlineFieldText
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.normalizeOutlineCategory
import com.lianwenqi.app.data.entity.outlineCategoryLabel
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
    private val volumeOf: suspend (Long) -> AgentVolumeSnapshot?,
    private val writeService: AgentWriteExecutionService,
    /** 当前书籍的大纲分类标签（存储值有序列表） */
    private val outlineCategoriesOf: suspend (Long) -> List<String> = { emptyList() },
    /** 某分类下的大纲条目数 */
    private val outlineItemCountOf: suspend (Long, String) -> Int = { _, _ -> 0 }
) {

    fun create(): List<AgentTool> = listOf(
        updateChapterSynopsis(),
        updateChapterContent(),
        createOutlineNode(),
        updateOutlineNode(),
        deleteOutlineNode(),
        createOutlineCategory(),
        deleteOutlineCategory(),
        moveOutlineNode(),
        createChapter(),
        deleteChapter(),
        createVolume(),
        deleteVolume()
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
                name = "fields",
                type = AgentToolParamType.STRING,
                description = "结构化字段，每行「字段名：内容」，例如「身份：...」「性格：...」；" +
                    "会与显示标签分开保存，推荐使用",
                required = false,
                maxLength = AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS
            ),
            AgentToolParam(
                name = "content",
                type = AgentToolParamType.STRING,
                description = "无固定字段时的整段内容；与 fields 至少提供一个",
                required = false,
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
        val category = normalizeOutlineCategory(args.string("category"))
        val title = args.string("title").trim()
        val fieldsText = args.string("fields")
        val content = args.string("content")
        val tag = args.string("tag").trim()
        if (category.isEmpty()) return@AgentTool AgentToolResult.fail("category 不能为空")
        if (title.isEmpty()) return@AgentTool AgentToolResult.fail("title 不能为空")
        if (fieldsText.isBlank() && content.isBlank()) {
            return@AgentTool AgentToolResult.fail("fields 与 content 至少提供一个")
        }
        val bodyLength = fieldsText.length + content.length
        if (bodyLength > AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "大纲内容超过上限（${AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS} 字）"
            )
        }
        // 统一转为结构化字段：显示标签一项，其余按「字段名：内容」分开存储
        val storedContent = buildOutlineContentForAgent(
            title = title,
            fieldsText = fieldsText,
            content = content
        )
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val argsJson = agentJson.encodeToString(
            AgentOutlineCreateArgs.serializer(),
            AgentOutlineCreateArgs(category, tag, title, storedContent)
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
            proposedContent = storedContent,
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "create_outline_node",
                "$category:$tag:$title:${PromptDiagnostics.hash(storedContent)}"
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
                name = "fields",
                type = AgentToolParamType.STRING,
                description = "可选新字段，每行「字段名：内容」；按字段名覆盖或新增，与显示标签分开保存",
                required = false,
                maxLength = AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS
            ),
            AgentToolParam(
                name = "content",
                type = AgentToolParamType.STRING,
                description = "可选整段新内容；提供时整体替换原有字段",
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
            ?.let(::normalizeOutlineCategory)
        val title = args.string("title").trim().takeIf { it.isNotEmpty() }
        val fieldsText = args.string("fields").takeIf { it.isNotBlank() }
        val content = args.string("content").takeIf { it.isNotEmpty() }
        val tag = args.string("tag").trim().takeIf { it.isNotEmpty() }
        if (category == null && title == null && content == null &&
            fieldsText == null && tag == null
        ) {
            return@AgentTool AgentToolResult.fail("至少需要提供一个要修改的字段")
        }
        if ((content?.length ?: 0) + (fieldsText?.length ?: 0) >
            AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS
        ) {
            return@AgentTool AgentToolResult.fail(
                "大纲内容超过上限（${AgentWriteLimits.MAX_OUTLINE_CONTENT_CHARS} 字）"
            )
        }
        val mergedCategory = category ?: current.category
        val mergedTitle = title ?: current.title
        val mergedTag = tag ?: current.tag
        // 显示标签与条目 title 保持一致；无 title 修改时沿用原显示标签
        val mergedHead = title ?: outlineDisplayName(current.title, current.content)
        val mergedContent = when {
            content != null -> buildOutlineContentForAgent(
                title = mergedHead,
                fieldsText = fieldsText.orEmpty(),
                content = content
            )

            fieldsText != null -> {
                val fields = LinkedHashMap<String, String>()
                outlineFieldsForDisplay(current.title, current.content)
                    .filter { it.first != "显示标签" }
                    .forEach { fields[it.first] = it.second }
                parseOutlineFieldText(fieldsText).forEach { (name, value) ->
                    if (name != "显示标签") fields[name] = value
                }
                encodeOutlineFields(
                    listOf("显示标签" to mergedHead) + fields.map { it.key to it.value }
                )
            }

            title != null && isStructuredOutlineContent(current.content) -> {
                val rest = decodeOutlineFields(current.content)
                    .filter { it.first != "显示标签" }
                encodeOutlineFields(
                    listOf("显示标签" to mergedHead) + rest.map { it.first to it.second }
                )
            }

            else -> current.content
        }
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
            content = mergedContent.takeIf { it != current.content }
        )
        val changed = buildList {
            if (category != null) add("分类")
            if (tag != null) add("标签")
            if (title != null) add("标题")
            if (content != null || fieldsText != null) add("内容")
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

    private fun deleteOutlineNode(): AgentTool = AgentTool(
        toolKey = "delete_outline_node",
        name = "delete_outline_node",
        description = "提出删除指定大纲节点；高风险且不可恢复，必须经用户逐条确认。",
        capabilityKey = "DELETE_OUTLINE_NODE",
        permissionLevel = AgentToolPermission.OVERWRITE,
        maxArgumentsChars = 8_000,
        parameters = listOf(
            AgentToolParam(
                name = "outlineId",
                type = AgentToolParamType.LONG,
                description = "要删除的大纲条目 id（可用 get_outline 查询）",
                required = true,
                minValue = 1
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
            return@AgentTool AgentToolResult.fail("无权删除其他书籍的大纲节点：$outlineId")
        }
        val displayName = outlineDisplayName(current.title, current.content)
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val summary = "删除大纲节点《$displayName》（不可恢复）" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "delete_outline_node",
            operationType = AgentWriteOperationType.DELETE_OUTLINE_NODE,
            targetType = AgentWriteTargetType.OUTLINE,
            targetId = outlineId,
            book = book,
            targetTitle = displayName,
            riskLevel = AgentWriteRiskLevel.HIGH_RISK_WRITE,
            argumentsJson = jsonPayload {
                put("outlineId", outlineId)
                put("title", displayName)
                if (reason.isNotBlank()) put("reason", reason)
            },
            beforeSnapshot = agentJson.encodeToString(
                AgentOutlineSnapshot.serializer(),
                current
            ),
            proposedContent = "",
            operationSummary = summary,
            reversible = false,
            idempotencyKey = writeKey(
                context.taskId,
                "delete_outline_node",
                "$outlineId:${PromptDiagnostics.hash(displayName)}"
            )
        )
    }

    private fun createOutlineCategory(): AgentTool = AgentTool(
        toolKey = "create_outline_category",
        name = "create_outline_category",
        description = "提出在当前书籍中新建大纲分类标签（如「伏笔」「势力」）；" +
            "分类已存在时不会重复创建。只生成待确认操作，不会立即写入。",
        capabilityKey = "CREATE_OUTLINE_CATEGORY",
        permissionLevel = AgentToolPermission.CREATE,
        maxArgumentsChars = 4_000,
        parameters = listOf(
            AgentToolParam(
                name = "category",
                type = AgentToolParamType.STRING,
                description = "新分类标签名称，例如「伏笔」「势力」",
                required = true,
                minLength = 1,
                maxLength = 50
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val raw = args.string("category").trim()
        if (raw.isEmpty()) return@AgentTool AgentToolResult.fail("category 不能为空")
        val category = normalizeOutlineCategory(raw)
        val existing = outlineCategoriesOf(bookId)
        if (existing.any { it == category || outlineCategoryLabel(it) == raw }) {
            return@AgentTool AgentToolResult.fail("分类已存在：$raw")
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val summary = "在《${book.title}》中新建大纲分类「$category」" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "create_outline_category",
            operationType = AgentWriteOperationType.CREATE_OUTLINE_CATEGORY,
            targetType = AgentWriteTargetType.OUTLINE,
            targetId = 0L,
            book = book,
            targetTitle = category,
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            argumentsJson = jsonPayload {
                put("category", category)
                if (reason.isNotBlank()) put("reason", reason)
            },
            beforeSnapshot = "{}",
            proposedContent = category,
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "create_outline_category",
                category
            )
        )
    }

    private fun deleteOutlineCategory(): AgentTool = AgentTool(
        toolKey = "delete_outline_category",
        name = "delete_outline_category",
        description = "提出删除指定大纲分类标签，并一并删除该分类下的全部大纲条目；" +
            "高风险且不可恢复，必须经用户逐条确认。",
        capabilityKey = "DELETE_OUTLINE_CATEGORY",
        permissionLevel = AgentToolPermission.OVERWRITE,
        maxArgumentsChars = 4_000,
        parameters = listOf(
            AgentToolParam(
                name = "category",
                type = AgentToolParamType.STRING,
                description = "要删除的分类标签存储值或展示名，例如 CHARACTER / 人设 / 伏笔",
                required = true,
                minLength = 1,
                maxLength = 50
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val raw = args.string("category").trim()
        if (raw.isEmpty()) return@AgentTool AgentToolResult.fail("category 不能为空")
        val categories = outlineCategoriesOf(bookId)
        val category = categories.firstOrNull { it == raw || outlineCategoryLabel(it) == raw }
            ?: normalizeOutlineCategory(raw).takeIf { it in categories }
            ?: return@AgentTool AgentToolResult.fail("分类不存在：$raw")
        val label = outlineCategoryLabel(category)
        val itemCount = outlineItemCountOf(bookId, category)
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val summary = "删除大纲分类「$label」及其下 $itemCount 条大纲条目（不可恢复）" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "delete_outline_category",
            operationType = AgentWriteOperationType.DELETE_OUTLINE_CATEGORY,
            targetType = AgentWriteTargetType.OUTLINE,
            targetId = 0L,
            book = book,
            targetTitle = label,
            riskLevel = AgentWriteRiskLevel.HIGH_RISK_WRITE,
            argumentsJson = jsonPayload {
                put("category", category)
                put("itemCount", itemCount)
                put("label", label)
                if (reason.isNotBlank()) put("reason", reason)
            },
            beforeSnapshot = "{}",
            proposedContent = "",
            operationSummary = summary,
            reversible = false,
            idempotencyKey = writeKey(
                context.taskId,
                "delete_outline_category",
                "$category:$itemCount"
            )
        )
    }

    private fun moveOutlineNode(): AgentTool = AgentTool(
        toolKey = "move_outline_node",
        name = "move_outline_node",
        description = "提出把已有大纲节点移动到另一个分类标签；目标分类不存在时会一并新建该分类。",
        capabilityKey = "MOVE_OUTLINE_NODE",
        permissionLevel = AgentToolPermission.UPDATE,
        maxArgumentsChars = 4_000,
        parameters = listOf(
            AgentToolParam(
                name = "outlineId",
                type = AgentToolParamType.LONG,
                description = "要移动的大纲条目 id（可用 get_outline 查询）",
                required = true,
                minValue = 1
            ),
            AgentToolParam(
                name = "category",
                type = AgentToolParamType.STRING,
                description = "目标分类标签存储值或展示名，例如 CHARACTER / 人设 / 伏笔",
                required = true,
                minLength = 1,
                maxLength = 50
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
            return@AgentTool AgentToolResult.fail("无权移动其他书籍的大纲节点：$outlineId")
        }
        val raw = args.string("category").trim()
        if (raw.isEmpty()) return@AgentTool AgentToolResult.fail("category 不能为空")
        val category = normalizeOutlineCategory(raw)
        if (category == current.category) {
            return@AgentTool AgentToolResult.fail("节点已在该分类中，无需移动")
        }
        val displayName = outlineDisplayName(current.title, current.content)
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val summary = "将大纲节点《$displayName》从「${outlineCategoryLabel(current.category)}」" +
            "移动到「${outlineCategoryLabel(category)}」" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "move_outline_node",
            operationType = AgentWriteOperationType.MOVE_OUTLINE_NODE,
            targetType = AgentWriteTargetType.OUTLINE,
            targetId = outlineId,
            book = book,
            targetTitle = displayName,
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            argumentsJson = jsonPayload {
                put("outlineId", outlineId)
                put("category", category)
                if (reason.isNotBlank()) put("reason", reason)
            },
            beforeSnapshot = agentJson.encodeToString(
                AgentOutlineSnapshot.serializer(),
                current
            ),
            proposedContent = current.content,
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "move_outline_node",
                "$outlineId:$category:${PromptDiagnostics.hash(current.content)}"
            )
        )
    }

    private fun createChapter(): AgentTool = AgentTool(
        toolKey = "create_chapter",
        name = "create_chapter",
        description = "提出在当前书籍中新建章节，可指定分卷与初始梗概；只生成待确认操作，不会立即写入。",
        capabilityKey = "CREATE_CHAPTER",
        permissionLevel = AgentToolPermission.CREATE,
        maxArgumentsChars = 24_000,
        parameters = listOf(
            AgentToolParam(
                name = "title",
                type = AgentToolParamType.STRING,
                description = "章节标题",
                required = true,
                minLength = 1,
                maxLength = MAX_VOLUME_OR_CHAPTER_TITLE_CHARS
            ),
            AgentToolParam(
                name = "volumeId",
                type = AgentToolParamType.LONG,
                description = "可选分卷 id；不传时放入最后一卷",
                required = false,
                minValue = 1
            ),
            AgentToolParam(
                name = "synopsis",
                type = AgentToolParamType.STRING,
                description = "可选章节初始梗概",
                required = false,
                minLength = 1,
                maxLength = AgentWriteLimits.MAX_SYNOPSIS_CHARS
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val title = args.string("title").trim()
        if (title.isEmpty()) return@AgentTool AgentToolResult.fail("title 不能为空")
        if (title.length > MAX_VOLUME_OR_CHAPTER_TITLE_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "章节标题超过上限（$MAX_VOLUME_OR_CHAPTER_TITLE_CHARS 字）"
            )
        }
        val volumeId = args.long("volumeId").takeIf { it > 0 }
        val volume = if (volumeId == null) {
            null
        } else {
            volumeOf(volumeId)
                ?: return@AgentTool AgentToolResult.fail("分卷不存在：$volumeId")
        }
        if (volume != null && volume.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权操作其他书籍的分卷：$volumeId")
        }
        val synopsis = args.string("synopsis")
        if (synopsis.length > AgentWriteLimits.MAX_SYNOPSIS_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "梗概超过上限（${AgentWriteLimits.MAX_SYNOPSIS_CHARS} 字）"
            )
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val summary = buildString {
            append("在《").append(book.title).append("》")
            if (volume != null) append("的《").append(volume.title).append("》")
            append("新建章节《").append(title).append("》")
            if (synopsis.isNotBlank()) append("，附 ${synopsis.length} 字梗概")
            reason.takeIf { it.isNotBlank() }?.let { append("；原因：").append(it) }
        }
        propose(
            context = context,
            toolName = "create_chapter",
            operationType = AgentWriteOperationType.CREATE_CHAPTER,
            targetType = AgentWriteTargetType.CHAPTER,
            targetId = 0L,
            book = book,
            targetTitle = title,
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            argumentsJson = agentJson.encodeToString(
                AgentChapterCreateArgs.serializer(),
                AgentChapterCreateArgs(volumeId = volumeId, title = title, summary = synopsis)
            ),
            beforeSnapshot = "{}",
            proposedContent = synopsis.ifBlank { title },
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "create_chapter",
                "${bookId}:${volumeId ?: 0}:$title:${PromptDiagnostics.hash(synopsis)}"
            )
        )
    }

    private fun deleteChapter(): AgentTool = AgentTool(
        toolKey = "delete_chapter",
        name = "delete_chapter",
        description = "提出删除指定章节；高风险且不可恢复，必须经用户逐条确认。",
        capabilityKey = "DELETE_CHAPTER",
        permissionLevel = AgentToolPermission.OVERWRITE,
        maxArgumentsChars = 8_000,
        parameters = listOf(
            chapterIdParam(),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val chapterId = args.long("chapterId")
        val chapter = chapterMetaOf(chapterId)
            ?: return@AgentTool AgentToolResult.fail("章节不存在：$chapterId")
        if (chapter.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权删除其他书籍的章节：$chapterId")
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val summary = "删除《${chapter.title}》（含正文与记忆块，不可恢复）" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "delete_chapter",
            operationType = AgentWriteOperationType.DELETE_CHAPTER,
            targetType = AgentWriteTargetType.CHAPTER,
            targetId = chapterId,
            book = book,
            targetTitle = chapter.title,
            riskLevel = AgentWriteRiskLevel.HIGH_RISK_WRITE,
            argumentsJson = jsonPayload {
                put("chapterId", chapterId)
                put("title", chapter.title)
                if (reason.isNotBlank()) put("reason", reason)
            },
            beforeSnapshot = agentJson.encodeToString(
                AgentChapterSnapshot.serializer(),
                chapter.copy(content = "", contentIncluded = false)
            ),
            proposedContent = "",
            operationSummary = summary,
            reversible = false,
            idempotencyKey = writeKey(
                context.taskId,
                "delete_chapter",
                "$chapterId:${PromptDiagnostics.hash(chapter.title)}"
            )
        )
    }

    private fun createVolume(): AgentTool = AgentTool(
        toolKey = "create_volume",
        name = "create_volume",
        description = "提出在当前书籍中新建分卷；只生成待确认操作，不会立即写入。",
        capabilityKey = "CREATE_VOLUME",
        permissionLevel = AgentToolPermission.CREATE,
        maxArgumentsChars = 8_000,
        parameters = listOf(
            AgentToolParam(
                name = "title",
                type = AgentToolParamType.STRING,
                description = "分卷标题",
                required = true,
                minLength = 1,
                maxLength = MAX_VOLUME_OR_CHAPTER_TITLE_CHARS
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val title = args.string("title").trim()
        if (title.isEmpty()) return@AgentTool AgentToolResult.fail("title 不能为空")
        if (title.length > MAX_VOLUME_OR_CHAPTER_TITLE_CHARS) {
            return@AgentTool AgentToolResult.fail(
                "分卷标题超过上限（$MAX_VOLUME_OR_CHAPTER_TITLE_CHARS 字）"
            )
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val summary = "在《${book.title}》新建分卷《$title》" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "create_volume",
            operationType = AgentWriteOperationType.CREATE_VOLUME,
            targetType = AgentWriteTargetType.VOLUME,
            targetId = 0L,
            book = book,
            targetTitle = title,
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            argumentsJson = agentJson.encodeToString(
                AgentVolumeCreateArgs.serializer(),
                AgentVolumeCreateArgs(title = title)
            ),
            beforeSnapshot = "{}",
            proposedContent = title,
            operationSummary = summary,
            reversible = true,
            idempotencyKey = writeKey(
                context.taskId,
                "create_volume",
                "$bookId:$title"
            )
        )
    }

    private fun deleteVolume(): AgentTool = AgentTool(
        toolKey = "delete_volume",
        name = "delete_volume",
        description = "提出删除指定分卷及其下全部章节；高风险且不可恢复，必须经用户逐条确认。",
        capabilityKey = "DELETE_VOLUME",
        permissionLevel = AgentToolPermission.OVERWRITE,
        maxArgumentsChars = 8_000,
        parameters = listOf(
            AgentToolParam(
                name = "volumeId",
                type = AgentToolParamType.LONG,
                description = "分卷 id",
                required = true,
                minValue = 1
            ),
            reasonParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val volumeId = args.long("volumeId")
        val volume = volumeOf(volumeId)
            ?: return@AgentTool AgentToolResult.fail("分卷不存在：$volumeId")
        if (volume.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权删除其他书籍的分卷：$volumeId")
        }
        val reason = args.string("reason").take(AgentWriteLimits.MAX_REASON_CHARS)
        val summary = "删除分卷《${volume.title}》及其下全部章节（不可恢复）" +
            reason.takeIf { it.isNotBlank() }?.let { "；原因：$it" }.orEmpty()
        propose(
            context = context,
            toolName = "delete_volume",
            operationType = AgentWriteOperationType.DELETE_VOLUME,
            targetType = AgentWriteTargetType.VOLUME,
            targetId = volumeId,
            book = book,
            targetTitle = volume.title,
            riskLevel = AgentWriteRiskLevel.HIGH_RISK_WRITE,
            argumentsJson = jsonPayload {
                put("volumeId", volumeId)
                put("title", volume.title)
                if (reason.isNotBlank()) put("reason", reason)
            },
            beforeSnapshot = agentJson.encodeToString(
                AgentVolumeSnapshot.serializer(),
                volume
            ),
            proposedContent = "",
            operationSummary = summary,
            reversible = false,
            idempotencyKey = writeKey(
                context.taskId,
                "delete_volume",
                "$volumeId:${PromptDiagnostics.hash(volume.title)}"
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
        private const val MAX_VOLUME_OR_CHAPTER_TITLE_CHARS = 200
    }
}
