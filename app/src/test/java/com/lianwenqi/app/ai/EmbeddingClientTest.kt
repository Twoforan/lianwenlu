package com.lianwenqi.app.ai

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EmbeddingClient] 的请求与错误映射测试。
 * 用 OkHttp 拦截器伪造响应（不依赖网络），覆盖成功还原、鉴权失败、解析失败、维度不一致与配置校验。
 */
class EmbeddingClientTest {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun clientReturning(handler: (Request) -> Response): EmbeddingClient {
        val okhttp = OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
        return EmbeddingClient(okhttp)
    }

    private fun responseOf(request: Request, code: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody(jsonMedia))
            .build()

    private fun errorOf(result: Result<List<FloatArray>>): AiError =
        (result.exceptionOrNull() as AiException).error

    @Test
    fun success_restoresInputOrderByIndex() = runTest {
        // 服务端乱序返回 index 2/0/1，客户端应按 index 还原
        val body = """
            {"data":[
              {"index":2,"embedding":[3.0,3.0]},
              {"index":0,"embedding":[1.0,1.0]},
              {"index":1,"embedding":[2.0,2.0]}
            ]}
        """.trimIndent()
        val client = clientReturning { req -> responseOf(req, 200, body) }
        val result = client.embed(listOf("甲", "乙", "丙"), "https://api.test/v1", "sk-abc", "m")
        assertTrue(result.isSuccess)
        val vectors = result.getOrThrow()
        assertEquals(3, vectors.size)
        assertEquals(1f, vectors[0][0], 0f)
        assertEquals(2f, vectors[1][0], 0f)
        assertEquals(3f, vectors[2][0], 0f)
    }

    @Test
    fun endpointAppendsEmbeddingsSegment() = runTest {
        var seenUrl = ""
        val client = clientReturning { req ->
            seenUrl = req.url.toString()
            responseOf(req, 200, """{"data":[{"index":0,"embedding":[1.0]}]}""")
        }
        client.embed(listOf("甲"), "https://api.test/v1/", "sk-abc", "m")
        assertEquals("https://api.test/v1/embeddings", seenUrl)
    }

    @Test
    fun unauthorized_returnsUnauthorizedError() = runTest {
        val client = clientReturning { req -> responseOf(req, 401, """{"error":{"message":"bad key"}}""") }
        val result = client.embed(listOf("甲"), "https://api.test/v1", "sk-abc", "m")
        assertTrue(result.isFailure)
        assertTrue(errorOf(result) is AiError.Unauthorized)
    }

    @Test
    fun serverError_returnsServerError() = runTest {
        val client = clientReturning { req -> responseOf(req, 500, "boom") }
        val result = client.embed(listOf("甲"), "https://api.test/v1", "sk-abc", "m")
        assertTrue(errorOf(result) is AiError.Server)
    }

    @Test
    fun malformedJson_returnsParseError() = runTest {
        val client = clientReturning { req -> responseOf(req, 200, "{不是 JSON") }
        val result = client.embed(listOf("甲"), "https://api.test/v1", "sk-abc", "m")
        assertTrue(errorOf(result) is AiError.Parse)
    }

    @Test
    fun countMismatch_returnsParseError() = runTest {
        val client = clientReturning { req ->
            responseOf(req, 200, """{"data":[{"index":0,"embedding":[1.0]}]}""")
        }
        val result = client.embed(listOf("甲", "乙"), "https://api.test/v1", "sk-abc", "m")
        assertTrue(errorOf(result) is AiError.Parse)
    }

    @Test
    fun dimMismatch_returnsInvalidConfig() = runTest {
        val body = """
            {"data":[
              {"index":0,"embedding":[1.0,2.0]},
              {"index":1,"embedding":[1.0,2.0,3.0]}
            ]}
        """.trimIndent()
        val client = clientReturning { req -> responseOf(req, 200, body) }
        val result = client.embed(listOf("甲", "乙"), "https://api.test/v1", "sk-abc", "m")
        assertTrue(errorOf(result) is AiError.InvalidConfig)
    }

    @Test
    fun blankApiKey_returnsInvalidConfigWithoutThrowing() = runTest {
        val client = EmbeddingClient()
        val result = client.embed(listOf("甲"), "https://api.test/v1", "", "m")
        val error = errorOf(result)
        assertTrue(error is AiError.InvalidConfig)
        assertEquals("未填写 apikey", error.userMessage)
    }

    @Test
    fun chineseApiKey_returnsInvalidConfigWithoutThrowing() = runTest {
        val client = EmbeddingClient()
        val result = client.embed(listOf("甲"), "https://api.test/v1", "中文密钥", "m")
        assertTrue(errorOf(result) is AiError.InvalidConfig)
    }

    @Test
    fun blankModel_returnsInvalidConfig() = runTest {
        val client = EmbeddingClient()
        val result = client.embed(listOf("甲"), "https://api.test/v1", "sk-abc", "  ")
        assertTrue(errorOf(result) is AiError.InvalidConfig)
    }
}
