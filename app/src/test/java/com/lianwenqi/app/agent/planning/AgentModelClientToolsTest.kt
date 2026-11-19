package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.AiToolCall
import com.lianwenqi.app.ai.AiToolSpec
import com.lianwenqi.app.ai.CacheUsage
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

class AgentModelClientToolsTest {

    private val endpoint = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "测试供应商",
            baseUrl = "https://example.com",
            apiType = ApiType.CHAT_COMPLETIONS,
            apiKey = "sk-test"
        ),
        modelName = "tool-model",
        temperature = 0.8f,
        maxTokens = 4096,
        systemPrompt = "普通聊天系统提示"
    )

    private val specs = listOf(
        AiToolSpec(
            name = "get_outline",
            description = "读取大纲",
            parametersJson = """{"type":"object","properties":{}}"""
        )
    )

    @Test
    fun 工具请求携带工具并解析tool_calls() = runBlocking {
        var capturedTools: List<AiToolSpec> = emptyList()
        var capturedEndpoint: ModelEndpoint? = null
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("plan") },
            invokeWithTools = { e, _, tools ->
                capturedEndpoint = e
                capturedTools = tools
                AiResult(
                    success = true,
                    content = "",
                    toolCalls = listOf(
                        AiToolCall(id = "call_1", name = "get_outline", arguments = """{"category":"CHARACTER"}""")
                    ),
                    cacheUsage = CacheUsage()
                )
            }
        )
        val response = client.completeWithTools(1L, listOf(ChatTurn(ChatRole.USER, "hi")), specs)
        assertTrue(response.success)
        assertEquals(1, capturedTools.size)
        assertEquals("", capturedEndpoint!!.systemPrompt)
        assertEquals(AgentModelClient.AGENT_TEMPERATURE, capturedEndpoint!!.temperature, 0.0001f)
        assertEquals(1, response.toolCalls.size)
        assertEquals("call_1", response.toolCalls.single().id)
        assertEquals("get_outline", response.toolCalls.single().name)
    }

    @Test
    fun 工具调用时允许空文本() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("plan") },
            invokeWithTools = { _, _, _ ->
                AiResult(
                    success = true,
                    content = "",
                    toolCalls = listOf(AiToolCall("c", "get_outline", "{}")),
                    cacheUsage = CacheUsage()
                )
            }
        )
        assertTrue(client.completeWithTools(1L, emptyList(), specs).success)
    }

    @Test
    fun 供应商不支持工具时保留标记() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("plan") },
            invokeWithTools = { _, _, _ ->
                AiResult(
                    success = true,
                    content = "ok",
                    toolsUnsupported = true,
                    cacheUsage = CacheUsage()
                )
            }
        )
        val response = client.completeWithTools(1L, emptyList(), specs)
        assertTrue(response.success)
        assertTrue(response.toolsUnsupported)
    }

    @Test
    fun 未配置工具入口时返回失败() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("plan") }
        )
        val response = client.completeWithTools(1L, emptyList(), specs)
        assertFalse(response.success)
        assertTrue(response.errorMessage.contains("工具调用入口未配置"))
    }

    @Test
    fun 规划请求仍然不携带工具() = runBlocking {
        var toolsInvoked = false
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("plan") },
            invokeWithTools = { _, _, _ ->
                toolsInvoked = true
                AiResult.ok("x")
            }
        )
        assertEquals("plan", client.complete(1L, emptyList()).content)
        assertFalse(toolsInvoked)
    }
}
