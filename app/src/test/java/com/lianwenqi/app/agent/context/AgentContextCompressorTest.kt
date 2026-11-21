package com.lianwenqi.app.agent.context

import com.lianwenqi.app.agent.planning.AgentModelClient
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompressorTest {

    private val endpoint = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "测试供应商",
            baseUrl = "https://example.com",
            apiType = ApiType.CHAT_COMPLETIONS,
            apiKey = "sk-test"
        ),
        modelName = "test-model",
        contextWindow = 8_000
    )

    private fun compressor(summary: String = "【摘要】目标、进度、待确认写入") =
        AgentContextCompressor(
            modelClient = AgentModelClient(
                endpointFor = { endpoint },
                invoke = { _, _ -> AiResult.ok(summary) }
            )
        )

    private fun longTurns(count: Int = 40): List<ChatTurn> =
        List(count) { index ->
            ChatTurn(
                if (index % 2 == 0) ChatRole.USER else ChatRole.ASSISTANT,
                "第 $index 轮：这是一段用于触发压缩的长内容。".repeat(20)
            )
        }

    @Test
    fun 达到窗口比例才触发压缩() {
        val compressor = compressor()
        val small = listOf(ChatTurn(ChatRole.USER, "短内容"))
        assertFalse(compressor.shouldCompress(small, 8_000))
        assertTrue(compressor.shouldCompress(longTurns(), 8_000))
    }

    @Test
    fun 压缩成功返回摘要并重建更短的消息() = runBlocking {
        val compressor = compressor()
        val turns = listOf(ChatTurn(ChatRole.SYSTEM, "系统提示")) +
            listOf(ChatTurn(ChatRole.USER, "原始任务：写第一卷")) +
            longTurns()
        val result = compressor.compress(1L, turns, 8_000)
        assertTrue(result is AgentContextCompressor.Result.Success)
        val success = result as AgentContextCompressor.Result.Success
        assertTrue(success.afterTokens < success.beforeTokens)
        assertEquals("【摘要】目标、进度、待确认写入", success.summary)

        val rebuilt = compressor.rebuildTurns(turns, success.summary, 8_000)
        assertTrue(rebuilt.first().role == ChatRole.SYSTEM)
        assertTrue(rebuilt.any { it.content.contains("原始任务：写第一卷") })
        assertTrue(rebuilt.any { it.content.startsWith("【上下文摘要") })
    }

    @Test
    fun 压缩请求失败返回失败而不是伪装成功() = runBlocking {
        val failing = AgentContextCompressor(
            modelClient = AgentModelClient(
                endpointFor = { endpoint },
                invoke = { _, _ -> AiResult.fail("网络错误") }
            )
        )
        val result = failing.compress(1L, longTurns(), 8_000)
        assertTrue(result is AgentContextCompressor.Result.Failure)
    }

    @Test
    fun 自动模式压缩时不原样保留超长首条指令() = runBlocking {
        val compressor = compressor()
        val hugeFirst = ChatTurn(ChatRole.USER, "资料：".repeat(6_000))
        val turns = listOf(ChatTurn(ChatRole.SYSTEM, "系统提示"), hugeFirst) + longTurns()
        val result = compressor.compress(1L, turns, 8_000, preserveFirstUser = false)
        assertTrue(result is AgentContextCompressor.Result.Success)
        val rebuilt = compressor.rebuildTurns(
            turns,
            (result as AgentContextCompressor.Result.Success).summary,
            8_000,
            preserveFirstUser = false
        )
        assertTrue(rebuilt.none { it === hugeFirst || it.content == hugeFirst.content })
        assertTrue(rebuilt.any { it.content.startsWith("【上下文摘要") })
        assertTrue(compressor.shouldCompress(turns, 8_000))
    }

    @Test
    fun 模型未配置窗口时使用默认窗口() {
        val compressor = compressor()
        assertEquals(512_000, compressor.windowTokens(0))
        assertEquals(8_000, compressor.windowTokens(8_000))
    }

    @Test
    fun 自动模式回答判定() {
        assertTrue(AgentContextCompressor.isAffirmative("压缩后继续"))
        assertTrue(AgentContextCompressor.isAffirmative("好的，继续"))
        assertFalse(AgentContextCompressor.isAffirmative("不压缩，新开对话"))
        assertFalse(AgentContextCompressor.isAffirmative("取消"))
        assertFalse(AgentContextCompressor.isAffirmative(""))
    }
}
