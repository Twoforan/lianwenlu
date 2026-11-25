package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 通过本地 HTTP 服务捕获 AiClient 实际发出的请求体，验证：
 * - 相同前缀 + 只改最后一条用户消息时，序列化后的前缀逐字节一致；
 * - 相邻 system 消息合并后，文本顺序不变；
 * - 工具定义顺序稳定；
 * - chat / responses / anthropic 的普通与流式 usage 不丢失。
 *
 * 本测试不访问真实供应商，只验证请求组装与统计链路；真实命中率用
 * [CacheHitProbeTest]（需要环境变量，默认跳过）。
 */
class AiClientPromptCacheTest {

    private lateinit var server: TinyHttpServer
    private lateinit var client: AiClient

    private val chatBodies = Collections.synchronizedList(mutableListOf<String>())
    private val responsesBodies = Collections.synchronizedList(mutableListOf<String>())
    private val anthropicBodies = Collections.synchronizedList(mutableListOf<String>())
    private val json = Json { ignoreUnknownKeys = true }

    private val chatResponse = """
        {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
         "usage":{"prompt_tokens":2000,"completion_tokens":10,"total_tokens":2010,
         "prompt_tokens_details":{"cached_tokens":1500}}}
    """.trimIndent()

    private val chatStreamResponse = buildString {
        append("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n")
        append("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n")
        append(
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2000,\"completion_tokens\":10," +
                "\"total_tokens\":2010,\"prompt_tokens_details\":{\"cached_tokens\":1500}}}\n\n"
        )
        append("data: [DONE]\n\n")
    }

    private val responsesResponse = """
        {"output_text":"ok","output":[],
         "usage":{"input_tokens":1000,"output_tokens":10,"total_tokens":1010,
         "input_tokens_details":{"cached_tokens":800}}}
    """.trimIndent()

    private val anthropicResponse = """
        {"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",
         "usage":{"input_tokens":100,"output_tokens":10,
         "cache_read_input_tokens":900,"cache_creation_input_tokens":300}}
    """.trimIndent()

    @Before
    fun setUp() {
        server = TinyHttpServer()
        server.on("/v1/chat/completions") { body ->
            chatBodies += body
            val stream = body.contains("\"stream\":true")
            TinyHttpServer.Response(
                body = if (stream) chatStreamResponse else chatResponse,
                contentType = if (stream) "text/event-stream" else "application/json"
            )
        }
        server.on("/v1/responses") { body ->
            responsesBodies += body
            TinyHttpServer.Response(responsesResponse, "application/json")
        }
        server.on("/v1/messages") { body ->
            anthropicBodies += body
            TinyHttpServer.Response(anthropicResponse, "application/json")
        }
        server.start()
        client = AiClient(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun endpoint(apiType: ApiType) = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "local-test",
            baseUrl = "http://127.0.0.1:${server.port}/v1",
            apiType = apiType,
            apiKey = "sk-test"
        ),
        // 多密钥架构后，实际请求使用密钥上的 Key（Provider.apiKey 仅保留给旧数据）
        apiKey = "sk-test",
        modelName = "test-model",
        apiType = apiType,
        temperature = 0.8f,
        maxTokens = 256,
        systemPrompt = "预设提示"
    )

    private fun turn(role: ChatRole, content: String) = ChatTurn(role, content)

    private fun messagesOf(body: String) =
        json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray

    @Test
    fun `chat 相同前缀只改最后用户消息时前缀逐字节一致且 system 已合并`() = runBlocking {
        val endpoint = endpoint(ApiType.CHAT_COMPLETIONS)
        val prefix = listOf(
            turn(ChatRole.SYSTEM, "【文风约束】短句为主"),
            turn(ChatRole.SYSTEM, "自定义前缀"),
            turn(ChatRole.SYSTEM, "以下是参考资料：书A")
        )
        client.chat(endpoint, prefix + turn(ChatRole.USER, "写第一章"))
        client.chat(endpoint, prefix + turn(ChatRole.USER, "写第二章"))

        val first = messagesOf(chatBodies[0])
        val second = messagesOf(chatBodies[1])
        assertEquals(first.dropLast(1), second.dropLast(1))
        // 预设提示 + 三个 system 块合并成一条，顺序不变
        assertEquals(2, first.size)
        val system = first[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.startsWith("预设提示"))
        assertTrue(system.indexOf("【文风约束】") < system.indexOf("自定义前缀"))
        assertTrue(system.indexOf("自定义前缀") < system.indexOf("以下是参考资料"))
    }

    @Test
    fun `chat 流式请求最后 usage 块被解析且 include_usage 已发送`() = runBlocking {
        val result = client.chat(
            endpoint(ApiType.CHAT_COMPLETIONS),
            listOf(turn(ChatRole.USER, "写")),
            onDelta = { _, _ -> }
        )
        assertTrue(result.success)
        assertEquals("你好", result.content)
        assertEquals(2000, result.promptTokens)
        assertEquals(1500, result.cachedTokens)
        assertTrue(chatBodies.single().contains("\"stream_options\":{\"include_usage\":true}"))
    }

    @Test
    fun `chat 工具定义顺序稳定且两次请求完全一致`() = runBlocking {
        val endpoint = endpoint(ApiType.CHAT_COMPLETIONS)
        val tools = listOf(
            AiToolSpec("tool_a", "第一个工具", """{"type":"object","properties":{}}"""),
            AiToolSpec("tool_b", "第二个工具", """{"type":"object","properties":{}}""")
        )
        client.chat(endpoint, listOf(turn(ChatRole.USER, "写")), tools = tools)
        client.chat(endpoint, listOf(turn(ChatRole.USER, "写")), tools = tools)

        val firstTools = json.parseToJsonElement(chatBodies[0]).jsonObject["tools"]!!.jsonArray
        val secondTools = json.parseToJsonElement(chatBodies[1]).jsonObject["tools"]!!.jsonArray
        assertEquals(firstTools, secondTools)
        assertEquals(
            listOf("tool_a", "tool_b"),
            firstTools.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        )
    }

    @Test
    fun `responses 输入合并 system 且缓存统计不丢失`() = runBlocking {
        val result = client.chat(
            endpoint(ApiType.RESPONSES),
            listOf(
                turn(ChatRole.SYSTEM, "【文风约束】"),
                turn(ChatRole.SYSTEM, "自定义前缀"),
                turn(ChatRole.USER, "写")
            )
        )
        assertTrue(result.success)
        assertEquals(800, result.cachedTokens)
        val body = json.parseToJsonElement(responsesBodies.single()).jsonObject
        assertEquals("预设提示", body["instructions"]!!.jsonPrimitive.content)
        val input = body["input"]!!.jsonArray
        assertEquals(2, input.size)
        val system = input[0].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(system.startsWith("【文风约束】\n\n自定义前缀"))
    }

    @Test
    fun `anthropic 相同前缀只改最后用户消息时 system 与历史逐字节一致`() = runBlocking {
        val endpoint = endpoint(ApiType.ANTHROPIC_MESSAGES)
        val prefix = listOf(
            turn(ChatRole.SYSTEM, "【文风约束】"),
            turn(ChatRole.SYSTEM, "自定义前缀"),
            turn(ChatRole.USER, "前情提问"),
            turn(ChatRole.ASSISTANT, "前情回答")
        )
        val first = client.chat(endpoint, prefix + turn(ChatRole.USER, "写第一章"))
        client.chat(endpoint, prefix + turn(ChatRole.USER, "写第二章"))

        assertTrue(first.success)
        assertEquals(900, first.cachedTokens)
        assertEquals(300, first.cacheCreationTokens)
        val body1 = json.parseToJsonElement(anthropicBodies[0]).jsonObject
        val body2 = json.parseToJsonElement(anthropicBodies[1]).jsonObject
        assertEquals(body1["system"], body2["system"])
        assertEquals(
            body1["messages"]!!.jsonArray.dropLast(1),
            body2["messages"]!!.jsonArray.dropLast(1)
        )
    }
}

/**
 * 极简本地 HTTP 服务：逐字节读取请求头，按 Content-Length 读取请求体，
 * 每个连接只处理一个请求（响应头 Connection: close）。
 * 不依赖 com.sun.net.httpserver（Android 单元测试的 Kotlin 编译类路径不包含该模块）。
 */
private class TinyHttpServer {
    data class Response(val body: String, val contentType: String)

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val handlers = ConcurrentHashMap<String, (String) -> Response>()
    private val captured = ConcurrentHashMap<String, MutableList<String>>()

    val port: Int get() = serverSocket.localPort

    fun on(path: String, handler: (String) -> Response) {
        handlers[path] = handler
        captured[path] = Collections.synchronizedList(mutableListOf())
    }

    fun start() {
        Thread(::acceptLoop, "tiny-http").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        runCatching { serverSocket.close() }
    }

    private fun acceptLoop() {
        while (true) {
            val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
            socket.use { handle(it) }
        }
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        val path = requestLine.split(" ").getOrNull(1) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bodyBytes, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        val body = String(bodyBytes, 0, offset, Charsets.UTF_8)
        captured[path]?.add(body)
        val response = handlers[path]?.invoke(body) ?: Response("", "text/plain")
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "x-request-id: local-test\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString("UTF-8")
    }
}
