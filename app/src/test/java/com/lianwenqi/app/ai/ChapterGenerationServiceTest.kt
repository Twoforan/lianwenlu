package com.lianwenqi.app.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterGenerationServiceTest {

    private val capturedTurns = mutableListOf<List<ChatTurn>>()

    private fun service(reply: String) = ChapterGenerationService { _, request, _ ->
        capturedTurns += request.turns
        AiResult.ok(reply)
    }

    @Test
    fun 梗概生成复用提示词并执行字数兜底() = runBlocking {
        val result = service("第一句。".repeat(20)).generateSynopsis(
            modelId = 1L,
            chapterTitle = "第一章",
            instruction = "",
            range = 10..20,
            styleBlock = "【文风约束】简洁",
            referenceBlock = "【大纲】主角设定"
        )

        assertTrue(result.success)
        assertTrue("超出上限应被截断", result.over)
        assertTrue(com.lianwenqi.app.util.TextTool.countWords(result.text) <= 20)

        val content = capturedTurns.single().joinToString("\n") { it.content }
        assertTrue(content.contains("【文风约束】简洁"))
        assertTrue(content.contains("主角设定"))
        assertTrue(content.contains("第一章"))
        assertTrue(content.contains(SUMMARY_FALLBACK_INPUT))
    }

    @Test
    fun 正文生成携带梗概大纲记忆与用户要求() = runBlocking {
        val result = service("正文内容").generateContent(
            modelId = 1L,
            chapterTitle = "第一章",
            instruction = "写得更紧张",
            summary = "主角入门",
            outlineBlock = "【大纲】主线",
            memoryBlock = "【记忆】前情",
            range = null,
            styleBlock = "【文风约束】克制"
        )

        assertTrue(result.success)
        val content = capturedTurns.single().joinToString("\n") { it.content }
        assertTrue(content.contains("【文风约束】克制"))
        assertTrue(content.contains("【本章梗概】"))
        assertTrue(content.contains("主角入门"))
        assertTrue(content.contains("【大纲 / 人物设定】"))
        assertTrue(content.contains("主线"))
        assertTrue(content.contains("【记忆检索参考资料】"))
        assertTrue(content.contains("前情"))
        assertTrue(content.contains("写得更紧张"))
    }

    @Test
    fun 模型返回空内容时失败() = runBlocking {
        val result = service("   ").generateSynopsis(
            modelId = 1L,
            chapterTitle = "第一章",
            instruction = "",
            range = null
        )
        assertFalse(result.success)
        assertEquals("模型返回空内容", result.errorMessage)
    }

    @Test
    fun 未配置模型时不发起请求() = runBlocking {
        val result = service("不应调用").generateSynopsis(
            modelId = 0L,
            chapterTitle = "第一章",
            instruction = "",
            range = null
        )
        assertFalse(result.success)
        assertEquals("请先在设置中配置并选择一个模型", result.errorMessage)
        assertTrue(capturedTurns.isEmpty())
    }

    @Test
    fun 传入流式回调时透传给模型请求() = runBlocking {
        var received: ((String, String) -> Unit)? = null
        val streaming = ChapterGenerationService { _, _, onDelta ->
            received = onDelta
            onDelta?.invoke("增量", "")
            AiResult.ok("增量文本")
        }
        val result = streaming.generateContent(
            modelId = 1L,
            chapterTitle = "第一章",
            instruction = "",
            summary = "主角入门",
            outlineBlock = "",
            memoryBlock = "",
            range = null,
            onDelta = { _, _ -> }
        )

        assertTrue(result.success)
        assertTrue("onDelta 应透传到模型请求", received != null)
    }
}
