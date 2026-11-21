package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.data.dao.AgentChapterMeta
import com.lianwenqi.app.agent.data.dao.AgentChapterSearchHit
import com.lianwenqi.app.data.encodeOutlineFields
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineCategory
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeSummary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentReadOnlyToolsTest {

    private val content = "0123456789".repeat(1000)

    private val book = Book(id = 1, title = "测试书", intro = "简介")
    private val chapter = AgentChapterMeta(
        id = 10,
        bookId = 1,
        volumeId = 100,
        title = "第一章",
        summary = "章节梗概",
        contentLength = content.length
    )
    private val otherChapter = AgentChapterMeta(
        id = 20,
        bookId = 2,
        volumeId = 200,
        title = "别人的章节",
        summary = "不应被读取",
        contentLength = 10
    )
    /** 新建书籍的空白章节：0 字仍应作为有效结构展示 */
    private val emptyChapter = AgentChapterMeta(
        id = 30,
        bookId = 1,
        volumeId = 100,
        title = "第一章",
        summary = "",
        contentLength = 0
    )
    private val outline = OutlineItem(
        id = 1,
        bookId = 1,
        category = OutlineCategory.CHARACTER.name,
        title = "人物",
        content = encodeOutlineFields(
            listOf(
                "显示标签" to "主角",
                "姓名" to "张三",
                "关系" to "主角的友人"
            )
        )
    )
    private val volume = Volume(id = 100, bookId = 1, title = "第一卷")
    private val volumeSummary = VolumeSummary(
        id = 1,
        bookId = 1,
        volumeId = 100,
        modelContent = "第一卷梗概",
        isCurrent = true
    )

    private val tools = AgentReadOnlyTools(
        bookOf = { if (it == 1L) book else null },
        chapterCountOf = { 1 },
        chapterMetaOf = { id ->
            when (id) {
                10L -> chapter
                20L -> otherChapter
                30L -> emptyChapter
                else -> null
            }
        },
        contentSliceOf = { id, offset, length ->
            when (id) {
                10L -> content.substring(offset.coerceIn(0, content.length))
                    .take(length)
                30L -> ""
                else -> null
            }
        },
        searchHitsOf = { _, keyword, _, _ ->
            listOf(
                AgentChapterSearchHit(
                    id = 10,
                    bookId = 1,
                    volumeId = 100,
                    title = "第一章",
                    summarySnippet = "命中 $keyword",
                    contentSnippet = "正文片段"
                )
            )
        },
        outlineOf = { listOf(outline) },
        volumesOf = { listOf(volume) },
        volumeSummariesOf = { listOf(volumeSummary) },
        chaptersOf = {
            listOf(
                Chapter(
                    id = 30,
                    bookId = 1,
                    volumeId = 100,
                    title = "第一章",
                    summary = "",
                    content = ""
                )
            )
        },
        memoryRetrieve = { _, _, _ -> listOf("记忆片段 A", "记忆片段 B") }
    ).create().associateBy { it.name }

    private fun args(json: String): AgentToolArgs =
        AgentToolArgs(agentJson.parseToJsonElement(json) as JsonObject)

    private fun context(bookId: Long? = 1L) = AgentExecutionContext(
        sessionId = 1,
        taskId = 1,
        runId = 1,
        bookId = bookId
    )

    @Test
    fun 未绑定书籍时拒绝读取() = runBlocking {
        val result = tools.getValue("get_book_info").execute(args("{}"), context(null))
        assertFalse(result.success)
        assertTrue(result.errorMessage.contains("未绑定书籍"))
    }

    @Test
    fun 读取书籍基本信息() = runBlocking {
        val result = tools.getValue("get_book_info").execute(args("{}"), context())
        assertTrue(result.success)
        assertTrue(result.contentJson.contains("测试书"))
        assertTrue(result.contentJson.contains("chapterCount"))
    }

    @Test
    fun 读取大纲并按分类筛选() = runBlocking {
        val all = tools.getValue("get_outline").execute(args("{}"), context())
        assertTrue(all.success)
        assertTrue(all.contentJson.contains("张三"))

        val character = tools.getValue("get_outline")
            .execute(args("""{"category":"CHARACTER"}"""), context())
        assertTrue(character.success)
        assertTrue(character.contentJson.contains("张三"))

        val world = tools.getValue("get_outline")
            .execute(args("""{"category":"WORLD"}"""), context())
        assertTrue(world.success)
        assertFalse(world.contentJson.contains("张三"))
    }

    @Test
    fun 读取章节梗概并拦截跨书章节() = runBlocking {
        val ok = tools.getValue("get_chapter_synopsis")
            .execute(args("""{"chapterIds":[10]}"""), context())
        assertTrue(ok.success)
        assertTrue(ok.contentJson.contains("章节梗概"))

        val crossBook = tools.getValue("get_chapter_synopsis")
            .execute(args("""{"chapterIds":[20]}"""), context())
        assertFalse(crossBook.success)
        assertTrue(crossBook.errorMessage.contains("无权访问"))

        val missing = tools.getValue("get_chapter_synopsis")
            .execute(args("""{"chapterIds":[99]}"""), context())
        assertFalse(missing.success)
    }

    @Test
    fun 按偏移读取正文并标记截断() = runBlocking {
        val result = tools.getValue("get_chapter_content")
            .execute(args("""{"chapterId":10,"offset":10,"maxChars":100}"""), context())
        assertTrue(result.success)
        assertTrue(result.contentJson.contains("\"offset\": 10") || result.contentJson.contains("\"offset\":10"))
        assertTrue(result.contentJson.contains("\"truncated\": true") ||
            result.contentJson.contains("\"truncated\":true"))
    }

    @Test
    fun 搜索章节返回摘要命中() = runBlocking {
        val result = tools.getValue("search_chapters")
            .execute(args("""{"keyword":"冲突"}"""), context())
        assertTrue(result.success)
        assertTrue(result.contentJson.contains("命中 冲突"))
        assertTrue(result.contentJson.contains("第一章"))
    }

    @Test
    fun 记忆检索返回片段() = runBlocking {
        val result = tools.getValue("retrieve_memory")
            .execute(args("""{"query":"人物关系"}"""), context())
        assertTrue(result.success)
        assertTrue(result.contentJson.contains("记忆片段 A"))
    }

    @Test
    fun 读取生效卷梗概() = runBlocking {
        val result = tools.getValue("get_volume_synopsis")
            .execute(args("""{"volumeId":100}"""), context())
        assertTrue(result.success)
        assertTrue(result.contentJson.contains("第一卷梗概"))
        assertTrue(result.contentJson.contains("第一卷"))
    }

    @Test
    fun 零字章节仍出现在结构列表中() = runBlocking {
        val result = tools.getValue("list_chapters")
            .execute(args("""{"volumeId":100}"""), context())
        assertTrue(result.success)
        assertTrue(result.contentJson.contains("\"chapterId\":30") ||
            result.contentJson.contains("\"chapterId\": 30"))
        assertTrue(result.contentJson.contains("\"contentLength\": 0") ||
            result.contentJson.contains("\"contentLength\":0"))
    }

    @Test
    fun 读取零字章节正文成功返回空内容() = runBlocking {
        val result = tools.getValue("get_chapter_content")
            .execute(args("""{"chapterId":30}"""), context())
        assertTrue(result.success)
        assertTrue(result.contentJson.contains("\"totalLength\": 0") ||
            result.contentJson.contains("\"totalLength\":0"))
    }
}
