package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ChapterGenerationService
import com.lianwenqi.app.data.encodeOutlineFields
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGenerationToolsTest {

    private val book = Book(id = 1L, title = "测试书", intro = "简介")
    private val chapter = Chapter(
        id = 11L,
        bookId = 1L,
        title = "第一章",
        summary = "已有梗概",
        sortOrder = 1
    )
    private val outline = OutlineItem(
        id = 21L,
        bookId = 1L,
        category = "CHARACTER",
        title = "主角",
        content = encodeOutlineFields(listOf("显示标签" to "主角", "姓名" to "林某"))
    )

    private fun tools(
        chapter: Chapter = this.chapter,
        reply: String = "生成结果",
        onRequest: (List<com.lianwenqi.app.ai.ChatTurn>, Long) -> Unit = { _, _ -> }
    ): List<AgentTool> = AgentGenerationTools(
        bookOf = { if (it == book.id) book else null },
        chapterOf = { if (it == chapter.id) chapter else null },
        chaptersOf = { listOf(chapter) },
        outlineOf = { listOf(outline) },
        volumesOf = { emptyList() },
        volumeSummariesOf = { emptyList() },
        styleBlockOf = { "【文风约束】简洁" },
        memoryRetrieve = { _, _, _ -> listOf("记忆片段") },
        modelIdOfTask = { 7L },
        summaryWordRange = { 10..100 },
        contentWordRange = { 100..200 },
        generationService = ChapterGenerationService { modelId, request, _ ->
            onRequest(request.turns, modelId)
            AiResult.ok(reply)
        }
    ).create()

    private fun context() = AgentExecutionContext(
        sessionId = 1L,
        taskId = 2L,
        runId = 3L,
        bookId = book.id,
        nodeId = 4L
    )

    @Test
    fun 生成梗概只返回草稿不写入() = runBlocking {
        var requestTurns: List<com.lianwenqi.app.ai.ChatTurn> = emptyList()
        var requestModelId = 0L
        val tool = tools(onRequest = { turns, modelId ->
            requestTurns = turns
            requestModelId = modelId
        }).first { it.toolKey == "generate_chapter_synopsis" }

        val result = tool.execute(
            AgentToolArgs(buildJsonObject { put("chapterId", chapter.id) }),
            context()
        )

        assertTrue(result.success)
        val payload = Json.parseToJsonElement(result.contentJson).jsonObject
        assertFalse(payload.getValue("written").jsonPrimitive.boolean)
        assertEquals("生成结果", payload.getValue("text").jsonPrimitive.content)
        assertTrue(payload.getValue("note").jsonPrimitive.content.contains("未写入章节"))
        assertTrue(result.summary.contains("未写入章节"))
        assertEquals(7L, requestModelId)
        assertNull("生成工具不得产生写入操作", result.writeOperationId)

        val content = requestTurns.joinToString("\n") { it.content }
        assertTrue(content.contains("【文风约束】简洁"))
        assertTrue(content.contains("主角"))
        assertTrue(content.contains("第一章"))
    }

    @Test
    fun 生成正文使用传入梗概并携带参考资料() = runBlocking {
        var requestTurns: List<com.lianwenqi.app.ai.ChatTurn> = emptyList()
        val tool = tools { turns, _ -> requestTurns = turns }
            .first { it.toolKey == "generate_chapter_content" }

        val result = tool.execute(
            AgentToolArgs(
                buildJsonObject {
                    put("chapterId", chapter.id)
                    put("summary", "本次使用的梗概")
                    put("instruction", "写得更紧张")
                }
            ),
            context()
        )

        assertTrue(result.success)
        val content = requestTurns.joinToString("\n") { it.content }
        assertTrue(content.contains("本次使用的梗概"))
        assertTrue(content.contains("写得更紧张"))
        assertTrue(content.contains("记忆片段"))
    }

    @Test
    fun 没有梗概时拒绝生成正文() = runBlocking {
        val emptySummaryChapter = chapter.copy(id = 12L, summary = "")
        val tool = tools(chapter = emptySummaryChapter)
            .first { it.toolKey == "generate_chapter_content" }

        val result = tool.execute(
            AgentToolArgs(buildJsonObject { put("chapterId", emptySummaryChapter.id) }),
            context()
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.contains("没有可用梗概"))
    }

    @Test
    fun 拒绝生成其他书籍的章节() = runBlocking {
        val otherBookChapter = chapter.copy(bookId = 99L)
        val tool = tools(chapter = otherBookChapter)
            .first { it.toolKey == "generate_chapter_synopsis" }

        val result = tool.execute(
            AgentToolArgs(buildJsonObject { put("chapterId", otherBookChapter.id) }),
            context()
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage.contains("无权生成"))
    }

    @Test
    fun 生成工具以生成权限注册且能力正确() {
        val generationTools = tools()
        assertTrue(generationTools.all { it.permissionLevel == AgentToolPermission.GENERATE })
        assertTrue(
            generationTools.any {
                it.capabilityKey == "GENERATE_SYNOPSIS" && it.name == "generate_chapter_synopsis"
            }
        )
        assertTrue(
            generationTools.any {
                it.capabilityKey == "GENERATE_CONTENT" && it.name == "generate_chapter_content"
            }
        )
    }
}
