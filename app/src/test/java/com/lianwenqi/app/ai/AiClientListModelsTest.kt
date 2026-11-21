package com.lianwenqi.app.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiClient.listModels] 的配置校验回归测试。
 *
 * 背景：apiKey 里写入中文时，OkHttp 会在构造 header 时抛 IllegalArgumentException；
 * 旧实现把请求构造放在 try 之外，异常逃出协程直接把 App 打崩（用户反馈 OPPO 机型闪退）。
 * 这里覆盖「未填写 / 含非法字符 / 未填 baseUrl」三类，确保只返回 Result.failure 而不抛异常，
 * 且这几个分支在联网之前就返回，不依赖网络。
 */
class AiClientListModelsTest {

    private val client = AiClient()

    private fun errorOf(result: Result<List<String>>): AiError =
        (result.exceptionOrNull() as AiException).error

    @Test
    fun blankApiKey_returnsFailureWithoutThrowing() = runTest {
        val result = client.listModels("https://api.openai.com/v1", "")
        assertTrue(result.isFailure)
        val error = errorOf(result)
        assertTrue(error is AiError.InvalidConfig)
        assertEquals("未填写 apikey", error.userMessage)
    }

    @Test
    fun chineseApiKey_returnsFailureWithoutThrowing() = runTest {
        val result = client.listModels("https://api.openai.com/v1", "中文密钥")
        assertTrue(result.isFailure)
        assertTrue(errorOf(result) is AiError.InvalidConfig)
    }

    @Test
    fun blankBaseUrl_returnsFailureWithoutThrowing() = runTest {
        val result = client.listModels("", "sk-abcdef")
        assertTrue(result.isFailure)
        assertTrue(errorOf(result) is AiError.InvalidConfig)
    }

    @Test
    fun 解析供应商模型列表里的上下文窗口字段() {
        val json = Json { ignoreUnknownKeys = true }
        val body = """
            {"data":[
              {"id":"openrouter-model","context_length":128000},
              {"id":"relay-model","context_window":32000},
              {"id":"vllm-model","max_model_len":8192},
              {"id":"nested-model","top_provider":{"context_length":200000}},
              {"id":"unknown-model"}
            ]}
        """.trimIndent()
        val parsed = json.decodeFromString<ModelListResponse>(body).data
            .map { it.id to it.resolvedContextWindow() }
        assertEquals(
            listOf(
                "openrouter-model" to 128000,
                "relay-model" to 32000,
                "vllm-model" to 8192,
                "nested-model" to 200000,
                "unknown-model" to 0
            ),
            parsed
        )
    }
}
