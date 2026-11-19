package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.CacheUsage
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AgentModelClientTest {

    private val endpoint = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "测试供应商",
            baseUrl = "https://example.com",
            apiType = ApiType.CHAT_COMPLETIONS,
            apiKey = "sk-test"
        ),
        modelName = "planner-model",
        temperature = 0.8f,
        maxTokens = 4096,
        systemPrompt = "普通聊天的系统提示，不应进入 Agent"
    )

    @Test
    fun 请求隔离_清空普通系统提示并固定Agent温度() = runBlocking {
        var captured: ModelEndpoint? = null
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { e, _ ->
                captured = e
                AiResult.ok("{}")
            }
        )
        val response = client.complete(1L, listOf(ChatTurn(ChatRole.USER, "hi")))
        assertTrue(response.success)
        assertEquals("", captured!!.systemPrompt)
        assertEquals(AgentModelClient.AGENT_TEMPERATURE, captured!!.temperature, 0.0001f)
        assertEquals(4096, captured!!.maxTokens)
        assertEquals("planner-model", response.modelName)
        assertEquals("测试供应商", response.providerName)
    }

    @Test
    fun Token统计三态保留() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ ->
                AiResult(
                    success = true,
                    content = "ok",
                    promptTokens = 123,
                    completionTokens = 45,
                    cacheUsage = CacheUsage(
                        promptTokens = 123,
                        completionTokens = 45,
                        cachedTokens = 10,
                        usageReported = true
                    )
                )
            }
        )
        val usage = client.complete(1L, emptyList()).usage
        assertEquals(123, usage.promptTokens)
        assertEquals(45, usage.completionTokens)
        assertEquals(10, usage.cachedTokens)
    }

    @Test
    fun 未提供Token时保持未知而不是0() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("ok") }
        )
        val usage = client.complete(1L, emptyList()).usage
        assertEquals(-1, usage.promptTokens)
        assertEquals(-1, usage.cachedTokens)
        assertEquals(0, usage.completionTokens)
    }

    @Test
    fun 模型配置不存在时返回明确失败() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { null },
            invoke = { _, _ -> AiResult.ok("{}") }
        )
        val response = client.complete(99L, emptyList())
        assertFalse(response.success)
        assertTrue(response.errorMessage.contains("未找到模型配置"))
    }

    @Test
    fun 请求异常映射为失败而不是崩溃() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> throw IOException("network down") }
        )
        val response = client.complete(1L, emptyList())
        assertFalse(response.success)
        assertTrue(response.errorMessage.isNotBlank())
    }

    @Test
    fun 取消异常原样抛出() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> throw CancellationException("cancelled") }
        )
        val result = runCatching { client.complete(1L, emptyList()) }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test
    fun 空响应视为失败() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("") }
        )
        val response = client.complete(1L, emptyList())
        assertFalse(response.success)
        assertTrue(response.errorMessage.contains("返回为空"))
    }
}
