package com.lianwenqi.app.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存统计的协议解析与流式累积测试。
 *
 * 核心约定：字段缺失 → null；服务端明确返回 0 → 0；绝不把普通输入 token 或缓存创建量当命中量。
 */
class CacheUsageTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ---------------- chat/completions ----------------

    private fun chatUsage(body: String): CacheUsage {
        val parsed = json.decodeFromString(ChatCompletionResponse.serializer(), body)
        return CacheUsageParser.fromChatCompletion(parsed.usage)
    }

    @Test
    fun `标准 OpenAI 缓存字段为 1000 时解析为 1000`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":2000,"completion_tokens":10,"total_tokens":2010,
               "prompt_tokens_details":{"cached_tokens":1000}}}"""
        )
        assertEquals(1000, usage.cachedTokens)
        assertTrue(usage.usageReported)
        assertEquals(CacheUsageParser.FIELD_CHAT_PROMPT_DETAILS, usage.cacheField)
    }

    @Test
    fun `标准 OpenAI 缓存字段明确为 0 时保留 0`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":2000,"completion_tokens":10,"total_tokens":2010,
               "prompt_tokens_details":{"cached_tokens":0}}}"""
        )
        assertEquals(0, usage.cachedTokens)
        assertTrue(usage.usageReported)
        assertEquals(CacheUsageParser.FIELD_CHAT_PROMPT_DETAILS, usage.cacheField)
    }

    @Test
    fun `usage 存在但完全没有缓存字段时返回 null`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":2000,"completion_tokens":10,"total_tokens":2010}}"""
        )
        assertNull(usage.cachedTokens)
        assertTrue(usage.usageReported)
        assertNull(usage.cacheField)
    }

    @Test
    fun `details 存在但没有 cached_tokens 时返回 null 而不是 0`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":2000,"completion_tokens":10,"total_tokens":2010,
               "prompt_tokens_details":{"audio_tokens":0}}}"""
        )
        assertNull(usage.cachedTokens)
        assertTrue(usage.usageReported)
    }

    @Test
    fun `完全没有 usage 时返回 null 且 usageReported 为 false`() {
        val usage = chatUsage("""{"choices":[]}""")
        assertNull(usage.cachedTokens)
        assertTrue(!usage.usageReported)
    }

    @Test
    fun `DeepSeek 顶层 prompt_cache_hit_tokens 可以解析`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":5000,"completion_tokens":20,"total_tokens":5020,
               "prompt_cache_hit_tokens":3600,"prompt_cache_miss_tokens":1400}}"""
        )
        assertEquals(3600, usage.cachedTokens)
        assertEquals(CacheUsageParser.FIELD_CHAT_CACHE_HIT, usage.cacheField)
    }

    @Test
    fun `中转站顶层 cached_tokens 可以解析`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":900,"completion_tokens":10,"total_tokens":910,
               "cached_tokens":700}}"""
        )
        assertEquals(700, usage.cachedTokens)
        assertEquals(CacheUsageParser.FIELD_CHAT_CACHED, usage.cacheField)
    }

    @Test
    fun `未知供应商缓存字段不崩溃并返回 null`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":900,"completion_tokens":10,"total_tokens":910,
               "prompt_tokens_details":{"cached_content_token_count":800},"x_cache":123}}"""
        )
        assertNull(usage.cachedTokens)
        assertTrue(usage.usageReported)
    }

    @Test
    fun `普通输入 token 不会被当成缓存命中量`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":8000,"completion_tokens":50,"total_tokens":8050}}"""
        )
        assertNull(usage.cachedTokens)
    }

    @Test
    fun `Gemini OpenAI 兼容响应中的标准缓存字段可以解析`() {
        // Gemini OpenAI 兼容接口实测会返回 prompt_tokens_details.cached_tokens，
        // 同时带有 completion_tokens_details / service_tier 等额外字段。
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":3000,"completion_tokens":80,"total_tokens":3080,
               "prompt_tokens_details":{"cached_tokens":1408,"audio_tokens":0},
               "completion_tokens_details":{"reasoning_tokens":512,"audio_tokens":0}},
               "service_tier":"default"}"""
        )
        assertEquals(1408, usage.cachedTokens)
    }

    @Test
    fun `MiniMax OpenAI 兼容响应中的标准缓存字段可以解析`() {
        val usage = chatUsage(
            """{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":300,"total_tokens":1500,
               "prompt_tokens_details":{"cached_tokens":800}}}"""
        )
        assertEquals(800, usage.cachedTokens)
    }

    // ---------------- responses ----------------

    private fun responsesUsage(body: String): CacheUsage {
        val parsed = json.decodeFromString(ResponsesResponse.serializer(), body)
        return CacheUsageParser.fromResponses(parsed.usage)
    }

    @Test
    fun `Responses 标准 input_tokens_details 解析正确`() {
        val usage = responsesUsage(
            """{"output":[],"usage":{"input_tokens":2000,"output_tokens":20,"total_tokens":2020,
               "input_tokens_details":{"cached_tokens":1500}}}"""
        )
        assertEquals(1500, usage.cachedTokens)
        assertEquals(CacheUsageParser.FIELD_RESPONSES_INPUT_DETAILS, usage.cacheField)
    }

    @Test
    fun `Responses 明确 0 时保留 0`() {
        val usage = responsesUsage(
            """{"output":[],"usage":{"input_tokens":2000,"output_tokens":20,"total_tokens":2020,
               "input_tokens_details":{"cached_tokens":0}}}"""
        )
        assertEquals(0, usage.cachedTokens)
    }

    @Test
    fun `Responses 字段缺失时返回 null`() {
        val usage = responsesUsage(
            """{"output":[],"usage":{"input_tokens":2000,"output_tokens":20,"total_tokens":2020}}"""
        )
        assertNull(usage.cachedTokens)
        assertTrue(usage.usageReported)
    }

    @Test
    fun `Responses 完全没有 usage 时返回 null`() {
        val usage = responsesUsage("""{"output":[]}""")
        assertNull(usage.cachedTokens)
        assertTrue(!usage.usageReported)
    }

    // ---------------- anthropic ----------------

    private fun anthropicUsage(body: String): CacheUsage {
        val parsed = json.decodeFromString(AnthropicResponse.serializer(), body)
        return CacheUsageParser.fromAnthropic(parsed.usage)
    }

    @Test
    fun `Anthropic cache_read 为 1000 时解析为 1000 且创建量独立保留`() {
        val usage = anthropicUsage(
            """{"content":[],"usage":{"input_tokens":100,"output_tokens":10,
               "cache_read_input_tokens":1000,"cache_creation_input_tokens":400}}"""
        )
        assertEquals(1000, usage.cachedTokens)
        assertEquals(400, usage.cacheCreationTokens)
        assertEquals(CacheUsageParser.FIELD_ANTHROPIC_CACHE_READ, usage.cacheField)
    }

    @Test
    fun `Anthropic cache_read 明确为 0 时保留 0`() {
        val usage = anthropicUsage(
            """{"content":[],"usage":{"input_tokens":100,"output_tokens":10,
               "cache_read_input_tokens":0,"cache_creation_input_tokens":400}}"""
        )
        assertEquals(0, usage.cachedTokens)
        assertEquals(400, usage.cacheCreationTokens)
    }

    @Test
    fun `Anthropic 只有 cache_creation 时命中量保持 null 且不把创建量当命中`() {
        val usage = anthropicUsage(
            """{"content":[],"usage":{"input_tokens":100,"output_tokens":10,
               "cache_creation_input_tokens":400}}"""
        )
        assertNull(usage.cachedTokens)
        assertEquals(400, usage.cacheCreationTokens)
        assertNull(usage.cacheField)
    }

    @Test
    fun `Anthropic usage 存在但没有缓存字段时返回 null`() {
        val usage = anthropicUsage(
            """{"content":[],"usage":{"input_tokens":100,"output_tokens":10}}"""
        )
        assertNull(usage.cachedTokens)
        assertTrue(usage.usageReported)
    }

    @Test
    fun `Anthropic 完全没有 usage 时返回 null`() {
        val usage = anthropicUsage("""{"content":[]}""")
        assertNull(usage.cachedTokens)
        assertTrue(!usage.usageReported)
    }

    // ---------------- 流式累积 ----------------

    private fun chatChunk(body: String): ChatCompletionChunk =
        json.decodeFromString(ChatCompletionChunk.serializer(), body)

    @Test
    fun `流式 chat 普通分片不收 usage 最后一个数据块携带 usage`() {
        val acc = StreamUsageAccumulator()
        acc.onChatChunk(chatChunk("""{"choices":[{"delta":{"content":"你"}}]}"""))
        acc.onChatChunk(chatChunk("""{"choices":[{"delta":{"content":"好"}}]}"""))
        acc.onChatChunk(
            chatChunk(
                """{"choices":[],"usage":{"prompt_tokens":3000,"completion_tokens":20,"total_tokens":3020,
                   "prompt_tokens_details":{"cached_tokens":2400}}}"""
            )
        )
        val usage = CacheUsageParser.fromChatCompletion(acc.chatUsage)
        assertEquals(2400, usage.cachedTokens)
        assertTrue(usage.usageReported)
    }

    @Test
    fun `流式 chat 多个 usage 数据块时最后一个生效`() {
        val acc = StreamUsageAccumulator()
        acc.onChatChunk(
            chatChunk("""{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":1,
                "prompt_tokens_details":{"cached_tokens":10}}}""")
        )
        acc.onChatChunk(
            chatChunk("""{"choices":[],"usage":{"prompt_tokens":3000,"completion_tokens":20,
                "prompt_tokens_details":{"cached_tokens":2000}}}""")
        )
        assertEquals(2000, CacheUsageParser.fromChatCompletion(acc.chatUsage).cachedTokens)
    }

    @Test
    fun `流式 chat 收到 usage 但没有缓存字段时返回 null`() {
        val acc = StreamUsageAccumulator()
        acc.onChatChunk(chatChunk("""{"choices":[],"usage":{"prompt_tokens":3000,"completion_tokens":20}}"""))
        val usage = CacheUsageParser.fromChatCompletion(acc.chatUsage)
        assertNull(usage.cachedTokens)
        assertTrue(usage.usageReported)
    }

    @Test
    fun `流式 chat usage 明确返回 0 时保留 0`() {
        val acc = StreamUsageAccumulator()
        acc.onChatChunk(
            chatChunk("""{"choices":[],"usage":{"prompt_tokens":3000,"completion_tokens":20,
                "prompt_tokens_details":{"cached_tokens":0}}}""")
        )
        assertEquals(0, CacheUsageParser.fromChatCompletion(acc.chatUsage).cachedTokens)
    }

    @Test
    fun `流式 chat 中途断流没有收到 usage 时返回 null 且不推算`() {
        val acc = StreamUsageAccumulator()
        acc.onChatChunk(chatChunk("""{"choices":[{"delta":{"content":"写到一半"}}]}"""))
        val usage = CacheUsageParser.fromChatCompletion(acc.chatUsage)
        assertNull(usage.cachedTokens)
        assertTrue(!usage.usageReported)
    }

    @Test
    fun `流式 responses completed 事件携带 usage 时解析正确`() {
        val acc = StreamUsageAccumulator()
        acc.onResponsesEvent(
            json.decodeFromString(
                ResponsesStreamEvent.serializer(),
                """{"type":"response.output_text.delta","delta":"hello"}"""
            )
        )
        acc.onResponsesEvent(
            json.decodeFromString(
                ResponsesStreamEvent.serializer(),
                """{"type":"response.completed","response":{"output":[],
                   "usage":{"input_tokens":2000,"output_tokens":20,
                   "input_tokens_details":{"cached_tokens":1600}}}}"""
            )
        )
        val usage = CacheUsageParser.fromResponses(acc.responsesUsage)
        assertEquals(1600, usage.cachedTokens)
    }

    @Test
    fun `流式 anthropic 合并 message_start 与 message_delta 的 usage`() {
        val acc = StreamUsageAccumulator()
        acc.onAnthropicEvent(
            json.decodeFromString(
                AnthropicStreamEvent.serializer(),
                """{"type":"message_start","message":{"content":[],"usage":{"input_tokens":100,
                   "cache_read_input_tokens":900,"cache_creation_input_tokens":300}}}"""
            )
        )
        acc.onAnthropicEvent(
            json.decodeFromString(
                AnthropicStreamEvent.serializer(),
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":30}}"""
            )
        )
        val usage = CacheUsageParser.fromAnthropic(acc.anthropicUsage)
        assertEquals(900, usage.cachedTokens)
        assertEquals(300, usage.cacheCreationTokens)
        assertEquals(30, acc.anthropicUsage?.outputTokens)
        assertEquals(100, acc.anthropicUsage?.inputTokens)
    }

    @Test
    fun `流式 chat 请求序列化必须包含 include_usage`() {
        val payload = ChatCompletionRequest(
            model = "test-model",
            messages = emptyList(),
            stream = true,
            streamOptions = StreamOptions(true)
        )
        val text = json.encodeToString(ChatCompletionRequest.serializer(), payload)
        assertTrue(text.contains("\"stream_options\":{\"include_usage\":true}"))
    }
}
