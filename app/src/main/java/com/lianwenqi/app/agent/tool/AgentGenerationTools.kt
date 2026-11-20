package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.agent.data.jsonPayload
import com.lianwenqi.app.ai.ChapterGenerationService
import com.lianwenqi.app.data.outlineFieldsForDisplay
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import kotlinx.serialization.json.put

/**
 * Agent 生成工具：只调用共享生成服务产出草稿，不直接修改章节。
 *
 * 生成结果作为工具输出返回给模型供后续步骤使用；
 * 需要保存时必须再由模型调用受控写入工具，经用户确认后才会写入。
 */
class AgentGenerationTools(
    private val bookOf: suspend (Long) -> Book?,
    private val chapterOf: suspend (Long) -> Chapter?,
    private val chaptersOf: suspend (Long) -> List<Chapter>,
    private val outlineOf: suspend (Long) -> List<OutlineItem>,
    private val volumesOf: suspend (Long) -> List<Volume>,
    private val volumeSummariesOf: suspend (Long) -> List<VolumeSummary>,
    private val styleBlockOf: suspend (Long?) -> String,
    private val memoryRetrieve: suspend (Long, String, Int) -> List<String>,
    private val modelIdOfTask: suspend (Long) -> Long?,
    private val summaryWordRange: suspend () -> IntRange,
    private val contentWordRange: suspend () -> IntRange,
    private val generationService: ChapterGenerationService
) {

    fun create(): List<AgentTool> = listOf(
        generateChapterSynopsis(),
        generateChapterContent()
    )

    private fun generateChapterSynopsis(): AgentTool = AgentTool(
        toolKey = "generate_chapter_synopsis",
        name = "generate_chapter_synopsis",
        description = "生成指定章节的梗概草稿；只返回文本，不写入章节。" +
            "需要保存时另行调用 update_chapter_synopsis。",
        capabilityKey = "GENERATE_SYNOPSIS",
        permissionLevel = AgentToolPermission.GENERATE,
        maxArgumentsChars = 8 * 1024,
        parameters = listOf(
            chapterIdParam(),
            instructionParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val chapterId = args.long("chapterId")
        val chapter = chapterOf(chapterId)
            ?: return@AgentTool AgentToolResult.fail("章节不存在：$chapterId")
        if (chapter.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权生成其他书籍的章节：$chapterId")
        }
        val modelId = modelIdOfTask(context.taskId)
            ?: return@AgentTool AgentToolResult.fail("当前任务未关联可用模型")
        val result = generationService.generateSynopsis(
            modelId = modelId,
            chapterTitle = chapter.title,
            instruction = args.string("instruction").trim().take(MAX_INSTRUCTION_CHARS),
            range = summaryWordRange(),
            styleBlock = styleBlockOf(bookId),
            referenceBlock = buildSynopsisReference(book, chapter)
        )
        if (!result.success) {
            return@AgentTool AgentToolResult.fail(result.errorMessage.ifBlank { "梗概生成失败" })
        }
        generationOk(
            generationType = "SYNOPSIS",
            chapter = chapter,
            text = result.text,
            over = result.over,
            under = result.under
        )
    }

    private fun generateChapterContent(): AgentTool = AgentTool(
        toolKey = "generate_chapter_content",
        name = "generate_chapter_content",
        description = "生成指定章节的正文草稿；只返回文本，不写入章节。" +
            "需要保存时另行调用 update_chapter_content。" +
            "如果本章还没有已保存的梗概，请在 summary 参数中提供梗概。",
        capabilityKey = "GENERATE_CONTENT",
        permissionLevel = AgentToolPermission.GENERATE,
        maxArgumentsChars = 24 * 1024,
        parameters = listOf(
            chapterIdParam(),
            AgentToolParam(
                name = "summary",
                type = AgentToolParamType.STRING,
                description = "可选：本次撰写依据的梗概；不传时使用本章已保存的梗概",
                required = false,
                maxLength = MAX_SUMMARY_CHARS
            ),
            instructionParam()
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("当前书籍不存在")
        val chapterId = args.long("chapterId")
        val chapter = chapterOf(chapterId)
            ?: return@AgentTool AgentToolResult.fail("章节不存在：$chapterId")
        if (chapter.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权生成其他书籍的章节：$chapterId")
        }
        val summary = args.string("summary").trim()
            .takeIf { it.isNotEmpty() }
            ?: chapter.summary.trim()
        if (summary.isBlank()) {
            return@AgentTool AgentToolResult.fail(
                "本章没有可用梗概：请先调用 generate_chapter_synopsis，" +
                    "或在 summary 参数中提供梗概"
            )
        }
        val modelId = modelIdOfTask(context.taskId)
            ?: return@AgentTool AgentToolResult.fail("当前任务未关联可用模型")
        val result = generationService.generateContent(
            modelId = modelId,
            chapterTitle = chapter.title,
            instruction = args.string("instruction").trim().take(MAX_INSTRUCTION_CHARS),
            summary = summary,
            outlineBlock = buildContentReference(book, chapter),
            memoryBlock = buildMemoryBlock(bookId, summary),
            range = contentWordRange(),
            styleBlock = styleBlockOf(bookId)
        )
        if (!result.success) {
            return@AgentTool AgentToolResult.fail(result.errorMessage.ifBlank { "正文生成失败" })
        }
        generationOk(
            generationType = "CONTENT",
            chapter = chapter,
            text = result.text,
            over = result.over,
            under = result.under
        )
    }

    private fun generationOk(
        generationType: String,
        chapter: Chapter,
        text: String,
        over: Boolean,
        under: Boolean
    ): AgentToolResult {
        val capped = text.take(MAX_RESULT_TEXT_CHARS)
        val truncated = capped.length < text.length
        val label = if (generationType == "SYNOPSIS") "梗概" else "正文"
        return AgentToolResult.ok(
            contentJson = jsonPayload {
                put("generationType", generationType)
                put("chapterId", chapter.id)
                put("chapterTitle", chapter.title)
                put("wordCount", text.length)
                put("text", capped)
                put("wordRangeExceeded", over)
                put("wordRangeUnder", under)
                put("truncated", truncated)
                put("written", false)
                put(
                    "note",
                    if (truncated) {
                        "生成结果过长，仅返回前 $MAX_RESULT_TEXT_CHARS 字；尚未写入章节"
                    } else {
                        "仅生成草稿，尚未写入章节；如需保存请调用受控写入工具"
                    }
                )
            },
            summary = "已生成《${chapter.title}》$label ${text.length} 字（未写入章节）",
            truncated = truncated
        )
    }

    private suspend fun buildSynopsisReference(book: Book, chapter: Chapter): String = buildString {
        append("【书籍】《").append(book.title).append('》')
        if (book.intro.isNotBlank()) {
            append("（简介：").append(book.intro.take(MAX_BOOK_INTRO_CHARS)).append("）")
        }
        append('\n')
        val outline = buildOutlineBlock(book.id)
        if (outline.isNotBlank()) append('\n').append(outline)
        val volumes = buildVolumeSummaryBlock(book.id)
        if (volumes.isNotBlank()) append('\n').append(volumes)
        val prior = buildPriorChapterBlock(book.id, chapter, includeContent = false)
        if (prior.isNotBlank()) append('\n').append(prior)
    }.trimEnd()

    private suspend fun buildContentReference(book: Book, chapter: Chapter): String = buildString {
        append("【书籍】《").append(book.title).append('》')
        if (book.intro.isNotBlank()) {
            append("（简介：").append(book.intro.take(MAX_BOOK_INTRO_CHARS)).append("）")
        }
        append('\n')
        val outline = buildOutlineBlock(book.id)
        if (outline.isNotBlank()) append('\n').append(outline)
        val volumes = buildVolumeSummaryBlock(book.id)
        if (volumes.isNotBlank()) append('\n').append(volumes)
        val prior = buildPriorChapterBlock(book.id, chapter, includeContent = true)
        if (prior.isNotBlank()) append('\n').append(prior)
    }.trimEnd()

    private suspend fun buildOutlineBlock(bookId: Long): String {
        val items = outlineOf(bookId)
        if (items.isEmpty()) return ""
        val sb = StringBuilder("【作品大纲】\n")
        items.take(MAX_OUTLINE_ITEMS).forEach { item ->
            if (sb.length >= MAX_OUTLINE_CHARS) return@forEach
            val fields = outlineFieldsForDisplay(item.title, item.content)
            val head = fields.firstOrNull()?.second?.ifBlank { item.title } ?: item.title
            sb.append("【").append(outlineCategoryLabel(item.category)).append("】")
                .append("｛").append(head.take(MAX_OUTLINE_HEAD_CHARS)).append("｝\n")
            fields.drop(1).take(MAX_OUTLINE_FIELDS).forEach { field ->
                if (sb.length >= MAX_OUTLINE_CHARS) return@forEach
                sb.append(field.first).append("：")
                    .append(field.second.take(MAX_OUTLINE_FIELD_CHARS)).append('\n')
            }
        }
        return sb.toString().trimEnd().take(MAX_OUTLINE_CHARS)
    }

    private suspend fun buildVolumeSummaryBlock(bookId: Long): String {
        val summaries = volumeSummariesOf(bookId).filter { it.isActive }
        if (summaries.isEmpty()) return ""
        val volumeTitles = volumesOf(bookId).associate { it.id to it.title }
        val sb = StringBuilder("【整卷梗概】\n")
        summaries.take(MAX_VOLUME_SUMMARIES).forEach { summary ->
            if (sb.length >= MAX_VOLUME_CHARS) return@forEach
            sb.append("《").append(volumeTitles[summary.volumeId] ?: "分卷").append("》\n")
                .append(summary.effectiveContent.trim().take(MAX_VOLUME_SUMMARY_CHARS)).append('\n')
        }
        return sb.toString().trimEnd().take(MAX_VOLUME_CHARS)
    }

    private suspend fun buildPriorChapterBlock(
        bookId: Long,
        chapter: Chapter,
        includeContent: Boolean
    ): String {
        val prior = chaptersOf(bookId).filter {
            it.id != chapter.id && it.sortOrder < chapter.sortOrder
        }
        if (prior.isEmpty()) return ""
        val sb = StringBuilder("【前情章节】\n")
        prior.takeLast(MAX_PRIOR_CHAPTERS).forEach { previous ->
            sb.append("《").append(previous.title).append("》")
            if (previous.summary.isNotBlank()) {
                sb.append(" 梗概：").append(previous.summary.take(MAX_PRIOR_SUMMARY_CHARS))
            }
            sb.append('\n')
        }
        if (includeContent) {
            val content = prior.lastOrNull()?.content.orEmpty()
            if (content.isNotBlank()) {
                sb.append("【上一章正文结尾】\n")
                    .append(content.takeLast(MAX_PRIOR_CONTENT_CHARS)).append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    private suspend fun buildMemoryBlock(bookId: Long, summary: String): String {
        val hits = try {
            memoryRetrieve(bookId, summary.take(MAX_MEMORY_QUERY_CHARS), MAX_MEMORY_HITS)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("【记忆检索参考资料】\n")
        hits.forEach { hit ->
            if (sb.length >= MAX_MEMORY_CHARS) return@forEach
            sb.append(hit.take(MAX_MEMORY_HIT_CHARS)).append('\n')
        }
        return sb.toString().trimEnd().take(MAX_MEMORY_CHARS)
    }

    private fun chapterIdParam() = AgentToolParam(
        name = "chapterId",
        type = AgentToolParamType.LONG,
        description = "章节 id",
        required = true,
        minValue = 1
    )

    private fun instructionParam() = AgentToolParam(
        name = "instruction",
        type = AgentToolParamType.STRING,
        description = "可选写作要求；为空时使用默认生成指令",
        required = false,
        maxLength = MAX_INSTRUCTION_CHARS
    )

    companion object {
        private const val NO_BOOK = "当前 Agent 会话未绑定书籍，无法生成章节内容"

        private const val MAX_INSTRUCTION_CHARS = 2_000
        private const val MAX_SUMMARY_CHARS = 20_000
        private const val MAX_RESULT_TEXT_CHARS = 20_000

        private const val MAX_BOOK_INTRO_CHARS = 300
        private const val MAX_OUTLINE_ITEMS = 40
        private const val MAX_OUTLINE_HEAD_CHARS = 100
        private const val MAX_OUTLINE_FIELDS = 6
        private const val MAX_OUTLINE_FIELD_CHARS = 300
        private const val MAX_OUTLINE_CHARS = 10_000

        private const val MAX_VOLUME_SUMMARIES = 5
        private const val MAX_VOLUME_SUMMARY_CHARS = 2_000
        private const val MAX_VOLUME_CHARS = 6_000

        private const val MAX_PRIOR_CHAPTERS = 3
        private const val MAX_PRIOR_SUMMARY_CHARS = 1_200
        private const val MAX_PRIOR_CONTENT_CHARS = 2_000

        private const val MAX_MEMORY_QUERY_CHARS = 500
        private const val MAX_MEMORY_HITS = 5
        private const val MAX_MEMORY_HIT_CHARS = 1_000
        private const val MAX_MEMORY_CHARS = 4_000
    }
}
