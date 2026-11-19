package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.agent.data.dao.AgentChapterMeta
import com.lianwenqi.app.agent.data.dao.AgentChapterSearchHit
import com.lianwenqi.app.agent.data.jsonPayload
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * F4 首批真实只读工具工厂。
 *
 * 所有业务读取通过注入的现有 Repository / DAO lambda 完成；
 * 工具参数不包含 bookId，书籍由 [AgentExecutionContext] 注入，避免模型跨书访问。
 */
class AgentReadOnlyTools(
    private val bookOf: suspend (Long) -> Book?,
    private val chapterCountOf: suspend (Long) -> Int,
    private val chapterMetaOf: suspend (Long) -> AgentChapterMeta?,
    private val contentSliceOf: suspend (Long, Int, Int) -> String?,
    private val searchHitsOf: suspend (Long, String, Int, Int) -> List<AgentChapterSearchHit>,
    private val outlineOf: suspend (Long) -> List<OutlineItem>,
    private val volumesOf: suspend (Long) -> List<Volume>,
    private val volumeSummariesOf: suspend (Long) -> List<VolumeSummary>,
    private val memoryRetrieve: suspend (Long, String, Int) -> List<String>
) {

    fun create(): List<AgentTool> = listOf(
        getBookInfo(),
        getOutline(),
        getChapterSynopsis(),
        getChapterContent(),
        searchChapters(),
        retrieveMemory(),
        getVolumeSynopsis()
    )

    private fun getBookInfo(): AgentTool = AgentTool(
        toolKey = "get_book_info",
        name = "get_book_info",
        description = "读取当前 Agent 会话绑定书籍的基本信息：书名、简介、章节数、分卷列表。",
        capabilityKey = "READ_BOOK_INFO",
        parameters = emptyList()
    ) { _, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val book = bookOf(bookId) ?: return@AgentTool AgentToolResult.fail("书籍不存在")
        val chapterCount = chapterCountOf(bookId)
        val volumes = volumesOf(bookId)
        val intro = book.intro.take(500)
        AgentToolResult.ok(
            contentJson = jsonPayload {
                put("bookId", book.id)
                put("title", book.title)
                put("intro", intro)
                put("chapterCount", chapterCount)
                put("volumeCount", volumes.size)
                putJsonArray("volumes") { volumes.forEach { add(it.title) } }
                if (book.intro.length > intro.length) put("introTruncated", true)
            },
            summary = "《${book.title}》共 $chapterCount 章 / ${volumes.size} 卷"
        )
    }

    private fun getOutline(): AgentTool = AgentTool(
        toolKey = "get_outline",
        name = "get_outline",
        description = "读取书籍大纲条目，可按分类筛选（如 CHARACTER、WORLD、STORY 或自定义标签）。",
        capabilityKey = "READ_OUTLINE",
        parameters = listOf(
            AgentToolParam(
                name = "category",
                type = AgentToolParamType.STRING,
                description = "可选分类存储值或展示名，例如 CHARACTER / 人设 / WORLD / 世界观",
                required = false,
                minLength = 1,
                maxLength = 50
            )
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val categoryFilter = args.string("category").trim()
        val all = outlineOf(bookId)
        val filtered = if (categoryFilter.isBlank()) {
            all
        } else {
            all.filter {
                it.category == categoryFilter ||
                    outlineCategoryLabel(it.category) == categoryFilter
            }
        }
        val limited = filtered.take(MAX_OUTLINE_ITEMS)
        var truncated = filtered.size > limited.size
        var totalChars = 0
        val itemsJson = buildJsonArraySafe {
            limited.forEach { item ->
                val fields = decodeOutlineFields(item.content)
                val head = fields.firstOrNull()?.second?.ifBlank { item.title } ?: item.title
                var itemChars = head.length
                add(buildJsonObject {
                    put("id", item.id)
                    put("category", outlineCategoryLabel(item.category))
                    put("title", head)
                    put("pinned", item.pinned)
                    putJsonArray("fields") {
                        fields.drop(1).forEach { field ->
                            val value = field.second.take(MAX_OUTLINE_FIELD_CHARS)
                            if (value.length < field.second.length) truncated = true
                            itemChars += field.first.length + value.length
                            add(buildJsonObject {
                                put("key", field.first)
                                put("value", value)
                            })
                        }
                    }
                })
                totalChars += itemChars
                if (totalChars > MAX_OUTLINE_TOTAL_CHARS) {
                    truncated = true
                    return@buildJsonArraySafe
                }
            }
        }
        AgentToolResult.ok(
            contentJson = jsonPayload {
                if (categoryFilter.isNotBlank()) put("category", categoryFilter)
                put("itemCount", limited.size)
                put("truncated", truncated)
                put("items", itemsJson)
            },
            summary = "读取大纲 ${limited.size} 条",
            truncated = truncated
        )
    }

    private fun getChapterSynopsis(): AgentTool = AgentTool(
        toolKey = "get_chapter_synopsis",
        name = "get_chapter_synopsis",
        description = "读取指定章节的标题与梗概；一次最多 20 章，只返回当前书籍的章节。",
        capabilityKey = "READ_CHAPTER_SYNOPSIS",
        parameters = listOf(
            AgentToolParam(
                name = "chapterIds",
                type = AgentToolParamType.LONG_ARRAY,
                description = "章节 id 列表，最多 20 个",
                required = true,
                maxItems = MAX_CHAPTERS_PER_CALL
            )
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val ids = args.longList("chapterIds").distinct()
        if (ids.isEmpty()) return@AgentTool AgentToolResult.fail("chapterIds 不能为空")
        val items = mutableListOf<AgentChapterMeta>()
        ids.forEach { id ->
            val meta = chapterMetaOf(id)
                ?: return@AgentTool AgentToolResult.fail("章节不存在：$id")
            if (meta.bookId != bookId) {
                return@AgentTool AgentToolResult.fail("无权访问章节：$id（不属于当前书籍）")
            }
            items += meta
        }
        AgentToolResult.ok(
            contentJson = jsonPayload {
                putJsonArray("items") {
                    items.forEach { meta ->
                        add(buildJsonObject {
                            put("chapterId", meta.id)
                            put("title", meta.title)
                            put("synopsis", meta.summary.take(MAX_SYNOPSIS_CHARS))
                            put("contentLength", meta.contentLength)
                        })
                    }
                }
            },
            summary = "读取 ${items.size} 章梗概"
        )
    }

    private fun getChapterContent(): AgentTool = AgentTool(
        toolKey = "get_chapter_content",
        name = "get_chapter_content",
        description = "按偏移读取章节正文片段；单次最多 8000 字，返回 nextOffset 以便继续读取。",
        capabilityKey = "READ_CHAPTER_CONTENT",
        parameters = listOf(
            AgentToolParam(
                name = "chapterId",
                type = AgentToolParamType.LONG,
                description = "章节 id",
                required = true,
                minValue = 1
            ),
            AgentToolParam(
                name = "offset",
                type = AgentToolParamType.LONG,
                description = "正文起始偏移，从 0 开始",
                required = false,
                minValue = 0
            ),
            AgentToolParam(
                name = "maxChars",
                type = AgentToolParamType.LONG,
                description = "本次最多读取字数，1000-8000",
                required = false,
                minValue = 1000,
                maxValue = MAX_CONTENT_SLICE_CHARS.toLong()
            )
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val chapterId = args.long("chapterId")
        val offset = args.long("offset", 0L).coerceAtLeast(0L)
        val maxChars = args.long("maxChars", DEFAULT_CONTENT_SLICE_CHARS.toLong())
            .coerceIn(1000L, MAX_CONTENT_SLICE_CHARS.toLong())
            .toInt()
        val meta = chapterMetaOf(chapterId)
            ?: return@AgentTool AgentToolResult.fail("章节不存在：$chapterId")
        if (meta.bookId != bookId) {
            return@AgentTool AgentToolResult.fail("无权访问章节：$chapterId（不属于当前书籍）")
        }
        val safeOffset = offset.coerceAtMost(meta.contentLength.toLong()).toInt()
        val content = if (safeOffset < meta.contentLength) {
            contentSliceOf(chapterId, safeOffset, maxChars).orEmpty()
        } else {
            ""
        }
        val nextOffset = safeOffset + content.length
        val truncated = nextOffset < meta.contentLength
        AgentToolResult.ok(
            contentJson = jsonPayload {
                put("chapterId", chapterId)
                put("title", meta.title)
                put("offset", safeOffset)
                put("totalLength", meta.contentLength)
                put("content", content)
                put("nextOffset", nextOffset)
                put("truncated", truncated)
            },
            summary = "读取《${meta.title}》正文 $safeOffset-$nextOffset / ${meta.contentLength} 字",
            truncated = truncated
        )
    }

    private fun searchChapters(): AgentTool = AgentTool(
        toolKey = "search_chapters",
        name = "search_chapters",
        description = "按关键词检索当前书籍的章节标题、梗概与正文，返回最多 10 条摘要命中。",
        capabilityKey = "SEARCH_CHAPTERS",
        parameters = listOf(
            AgentToolParam(
                name = "keyword",
                type = AgentToolParamType.STRING,
                description = "检索关键词，1-100 字",
                required = true,
                minLength = 1,
                maxLength = 100
            )
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val keyword = args.string("keyword").trim()
        if (keyword.isEmpty()) return@AgentTool AgentToolResult.fail("keyword 不能为空")
        val hits = searchHitsOf(bookId, keyword, SEARCH_SNIPPET_CHARS, MAX_SEARCH_HITS)
        val volumeTitles = volumesOf(bookId).associate { it.id to it.title }
        AgentToolResult.ok(
            contentJson = jsonPayload {
                put("keyword", keyword)
                put("hitCount", hits.size)
                putJsonArray("items") {
                    hits.forEach { hit ->
                        add(buildJsonObject {
                            put("chapterId", hit.id)
                            put("title", hit.title)
                            put("volume", hit.volumeId?.let { volumeTitles[it] } ?: "未分卷")
                            put("summarySnippet", hit.summarySnippet)
                            put("contentSnippet", hit.contentSnippet)
                        })
                    }
                }
            },
            summary = "关键词「$keyword」命中 ${hits.size} 章"
        )
    }

    private fun retrieveMemory(): AgentTool = AgentTool(
        toolKey = "retrieve_memory",
        name = "retrieve_memory",
        description = "从当前书籍的长期记忆向量中检索与查询相关的正文片段，最多 10 条。",
        capabilityKey = "RETRIEVE_MEMORY",
        parameters = listOf(
            AgentToolParam(
                name = "query",
                type = AgentToolParamType.STRING,
                description = "语义检索查询，1-500 字",
                required = true,
                minLength = 1,
                maxLength = 500
            ),
            AgentToolParam(
                name = "topK",
                type = AgentToolParamType.LONG,
                description = "返回片段数，1-10，默认 5",
                required = false,
                minValue = 1,
                maxValue = 10
            )
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val query = args.string("query").trim()
        if (query.isEmpty()) return@AgentTool AgentToolResult.fail("query 不能为空")
        val topK = args.long("topK", 5L).coerceIn(1L, 10L).toInt()
        val hits = memoryRetrieve(bookId, query, topK)
        AgentToolResult.ok(
            contentJson = jsonPayload {
                put("query", query)
                put("hitCount", hits.size)
                put("note", if (hits.isEmpty()) "未命中或记忆检索未配置" else "")
                putJsonArray("items") { hits.forEach { add(it.take(MAX_MEMORY_HIT_CHARS)) } }
            },
            summary = "记忆检索命中 ${hits.size} 条"
        )
    }

    private fun getVolumeSynopsis(): AgentTool = AgentTool(
        toolKey = "get_volume_synopsis",
        name = "get_volume_synopsis",
        description = "读取当前书籍的生效整卷梗概；可按 volumeId 筛选，最多 10 卷。",
        capabilityKey = "READ_VOLUME_SYNOPSIS",
        parameters = listOf(
            AgentToolParam(
                name = "volumeId",
                type = AgentToolParamType.LONG,
                description = "可选分卷 id；不传则返回本书全部生效卷梗概",
                required = false,
                minValue = 1
            )
        )
    ) { args, context ->
        val bookId = context.bookId ?: return@AgentTool AgentToolResult.fail(NO_BOOK)
        val volumeId = args.long("volumeId", 0L).takeIf { it > 0 }
        val summaries = volumeSummariesOf(bookId)
            .filter { volumeId == null || it.volumeId == volumeId }
            .take(MAX_VOLUME_SUMMARIES)
        val volumes = volumesOf(bookId).associate { it.id to it.title }
        AgentToolResult.ok(
            contentJson = jsonPayload {
                put("hitCount", summaries.size)
                put("note", if (summaries.isEmpty()) "没有可用的生效整卷梗概" else "")
                putJsonArray("items") {
                    summaries.forEach { summary ->
                        add(buildJsonObject {
                            put("volumeId", summary.volumeId)
                            put("volumeTitle", volumes[summary.volumeId] ?: "分卷")
                            put("summary", summary.effectiveContent.take(MAX_VOLUME_SUMMARY_CHARS))
                        })
                    }
                }
            },
            summary = "读取 ${summaries.size} 卷梗概"
        )
    }

    private fun buildJsonArraySafe(
        block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit
    ) = kotlinx.serialization.json.buildJsonArray(block)

    companion object {
        private const val NO_BOOK = "当前 Agent 会话未绑定书籍，无法读取小说资料"
        private const val MAX_OUTLINE_ITEMS = 50
        private const val MAX_OUTLINE_FIELD_CHARS = 500
        private const val MAX_OUTLINE_TOTAL_CHARS = 20_000
        private const val MAX_CHAPTERS_PER_CALL = 20
        private const val MAX_SYNOPSIS_CHARS = 2_000
        private const val MAX_CONTENT_SLICE_CHARS = 8_000
        private const val DEFAULT_CONTENT_SLICE_CHARS = 6_000
        private const val SEARCH_SNIPPET_CHARS = 300
        private const val MAX_SEARCH_HITS = 10
        private const val MAX_MEMORY_HIT_CHARS = 2_000
        private const val MAX_VOLUME_SUMMARIES = 10
        private const val MAX_VOLUME_SUMMARY_CHARS = 4_000
    }
}
