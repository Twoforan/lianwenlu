package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OpenAI 兼容的 AI 客户端：支持 chat completions 与 responses 两种接口 */
class AiClient(
    private val client: OkHttpClient = defaultClient()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private fun endpoint(baseUrl: String, apiType: ApiType): String {
        val base = baseUrl.trim().trimEnd('/')
        return when (apiType) {
            ApiType.CHAT_COMPLETIONS ->
                if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            ApiType.RESPONSES ->
                if (base.endsWith("/responses")) base else "$base/responses"
            ApiType.ANTHROPIC_MESSAGES ->
                if (base.endsWith("/messages")) base else "$base/messages"
        }
    }

    /** HTTP 状态码 → 错误类型：401/403 归为鉴权失败，其余非 2xx 归为服务器错误 */
    private fun errorForStatus(code: Int, body: String?): AiError = when (code) {
        401, 403 -> AiError.Unauthorized()
        else -> AiError.Server(code, body)
    }

    /**
     * 读取供应商可用模型列表。
     * 失败时返回 [Result.failure]（异常为 [AiException]，内含可区分类型的 [AiError]），
     * 不再把不同性质的错误压成一句「读取模型失败：xxx」。
     *
     * 注意：未填 apiKey / apiKey 含非法字符（如中文）/ baseUrl 非法 都在此处提前拦截或兜底为
     * [AiError.InvalidConfig]，绝不能抛出异常 —— 一旦异常逃出协程会直接把 App 打崩
     * （OkHttp 对含中文的 header 会抛 IllegalArgumentException）。
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> {
        if (apiKey.isBlank()) {
            return Result.failure(AiException(AiError.InvalidConfig("未填写 apikey")))
        }
        val bad = apiKey.firstOrNull { it.code >= 0x7f || it.code <= 0x1f }
        if (bad != null) {
            return Result.failure(AiException(AiError.InvalidConfig("apikey 含非法字符（不支持中文或控制字符），请重新填写")))
        }
        val trimmedBase = baseUrl.trim()
        if (trimmedBase.isBlank()) {
            return Result.failure(AiException(AiError.InvalidConfig("未填写 baseUrl")))
        }
        if (!trimmedBase.startsWith("http://") && !trimmedBase.startsWith("https://")) {
            return Result.failure(AiException(AiError.InvalidConfig("baseUrl 格式不正确（需以 http:// 或 https:// 开头）")))
        }
        return withContext(Dispatchers.IO) {
            val base = trimmedBase.trimEnd('/')
            val url = if (base.endsWith("/models")) base else "$base/models"
            try {
                // 构造请求也放进 try：非法 url / 非法 header 都只会变成 Result.failure，不会崩 App
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(AiException(errorForStatus(resp.code, body.take(200))))
                    }
                    Result.success(json.decodeFromString<ModelListResponse>(body).data.map { it.id })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(AiException(AiError.fromThrowable(e)))
            }
        }
    }

    /**
     * 发送对话请求。
     * [onDelta] 非空时使用流式（SSE）请求，边收边回调 (正文增量, 思考增量)，用于「实时显示生成」；
     * 为空时一次性返回整段结果。
     */
    suspend fun chat(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        onDelta: ((String, String) -> Unit)? = null
    ): AiResult =
        withContext(Dispatchers.IO) {
            val apiKey = endpoint.provider.apiKey
            if (endpoint.provider.baseUrl.isBlank()) return@withContext AiResult.fail("未配置 baseUrl")
            if (apiKey.isBlank()) return@withContext AiResult.fail("未配置 apiKey")
            runCatching {
                when (endpoint.apiType) {
                    ApiType.CHAT_COMPLETIONS ->
                        if (onDelta != null) streamCompletions(endpoint, turns, onDelta)
                        else chatCompletions(endpoint, turns)
                    ApiType.RESPONSES ->
                        if (onDelta != null) streamResponses(endpoint, turns, onDelta)
                        else responses(endpoint, turns)
                    ApiType.ANTHROPIC_MESSAGES ->
                        if (onDelta != null) streamAnthropic(endpoint, turns, onDelta)
                        else anthropicMessages(endpoint, turns)
                }
            }.getOrElse {
                // 用户主动「停止响应」时协程被取消，需要原样抛出，不能当成请求失败
                if (it is CancellationException) throw it
                // 按异常类型映射到具体 AiError（超时 / 网络 / 解析 / 未知），不再压成一句字符串
                AiResult.fail(AiError.fromThrowable(it))
            }
        }

    /** 把阻塞式的 OkHttp 调用包装成可取消的挂起函数：协程取消时同步取消在途请求 */
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            try {
                cont.resume(call.execute())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }

    /**
     * 读取 SSE 的一行。读取本身是阻塞调用，若在等待数据期间协程被取消（「停止响应」），
     * 需要主动关闭数据源以立刻中断阻塞读取；否则协程会一直卡在读取上，
     * 表现就是停止响应后界面长时间停在「正在生成」。
     */
    private suspend fun readLineOrNull(source: BufferedSource): String? =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { source.close() } }
            try {
                cont.resume(source.readUtf8Line())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }

    private fun buildMessages(endpoint: ModelEndpoint, turns: List<ChatTurn>): List<ChatMessageDto> =
        buildList {
            if (endpoint.systemPrompt.isNotBlank()) add(ChatMessageDto("system", endpoint.systemPrompt))
            turns.forEach { add(ChatMessageDto(it.role.name.lowercase(), it.content)) }
        }

    private suspend fun chatCompletions(endpoint: ModelEndpoint, turns: List<ChatTurn>): AiResult {
        val payload = ChatCompletionRequest(
            model = endpoint.modelName,
            messages = buildMessages(endpoint, turns),
            temperature = endpoint.temperature,
            maxTokens = endpoint.maxTokens.takeIf { it > 0 }
        )
        val body = json.encodeToString(ChatCompletionRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.CHAT_COMPLETIONS))
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        execute(request).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return AiResult.fail(errorForStatus(resp.code, text.take(300)))
            val parsed = json.decodeFromString<ChatCompletionResponse>(text)
            parsed.error?.message?.let { return AiResult.fail(it) }
            val choice = parsed.choices.firstOrNull()
            val content = choice?.message?.content ?: choice?.text
            val reasoning = choice?.message?.reasoningContent.orEmpty()
            return if (content.isNullOrBlank()) {
                if (choice?.finishReason == "length") AiResult.fail(TRUNCATED_HINT) else AiResult.fail("模型返回为空")
            } else AiResult.ok(
                content,
                reasoning,
                parsed.usage?.promptTokens ?: estimateTokens(turns.joinToString("") { it.content }),
                parsed.usage?.completionTokens ?: estimateTokens(content + reasoning),
                parsed.usage?.promptTokensDetails?.cachedTokens ?: 0
            )
        }
    }

    /** 流式 chat completions：逐分片回调，最后一个分片带 usage */
    private suspend fun streamCompletions(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        onDelta: (String, String) -> Unit
    ): AiResult {
        val payload = ChatCompletionRequest(
            model = endpoint.modelName,
            messages = buildMessages(endpoint, turns),
            temperature = endpoint.temperature,
            maxTokens = endpoint.maxTokens.takeIf { it > 0 },
            stream = true,
            streamOptions = StreamOptions(true)
        )
        val body = json.encodeToString(ChatCompletionRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.CHAT_COMPLETIONS))
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        execute(request).use { resp ->
            if (!resp.isSuccessful) {
                return AiResult.fail(errorForStatus(resp.code, resp.body?.string().orEmpty().take(300)))
            }
            val source = resp.body?.source() ?: return AiResult.fail("模型返回为空")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var usage: Usage? = null
            var truncated = false
            var errorMessage: String? = null
            // 是否收到协议结束标志（[DONE] 或 finish_reason）。未收到就 EOF，视为中途断流
            var terminated = false
            var streamEnded = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = readLineOrNull(source)
                if (line == null) {
                    streamEnded = true
                    break
                }
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    terminated = true
                    break
                }
                val chunk = runCatching {
                    json.decodeFromString(ChatCompletionChunk.serializer(), data)
                }.getOrNull() ?: continue
                chunk.error?.message?.let { errorMessage = it }
                chunk.usage?.let { usage = it }
                val choice = chunk.choices.firstOrNull()
                if (choice?.finishReason != null) terminated = true
                if (choice?.finishReason == "length") truncated = true
                val delta = choice?.delta
                val dc = delta?.content
                val dr = delta?.reasoningContent
                if (!dc.isNullOrEmpty()) {
                    content.append(dc)
                    onDelta(dc, "")
                }
                if (!dr.isNullOrEmpty()) {
                    reasoning.append(dr)
                    onDelta("", dr)
                }
            }
            errorMessage?.let { return AiResult.fail(it) }
            // 中途断流：连接关闭但没收到结束标志 → 明确报错，不静默当成成功
            if (streamEnded && !terminated) {
                return AiResult.fail(AiError.Network(IOException("响应中断：连接在完整结束前被关闭")))
            }
            val text = content.toString()
            if (text.isBlank()) {
                return if (truncated) AiResult.fail(TRUNCATED_HINT) else AiResult.fail("模型返回为空")
            }
            return AiResult.ok(
                text,
                reasoning.toString(),
                usage?.promptTokens ?: estimateTokens(turns.joinToString("") { it.content }),
                usage?.completionTokens ?: estimateTokens(text + reasoning),
                usage?.promptTokensDetails?.cachedTokens ?: 0
            )
        }
    }

    private suspend fun responses(endpoint: ModelEndpoint, turns: List<ChatTurn>): AiResult {
        val payload = ResponsesRequest(
            model = endpoint.modelName,
            input = turns.map { ChatMessageDto(it.role.name.lowercase(), it.content) },
            temperature = endpoint.temperature,
            maxOutputTokens = endpoint.maxTokens.takeIf { it > 0 },
            instructions = endpoint.systemPrompt.ifBlank { null },
            promptCacheKey = cacheKey(endpoint)
        )
        val body = json.encodeToString(ResponsesRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.RESPONSES))
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        execute(request).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return AiResult.fail(errorForStatus(resp.code, text.take(300)))
            val parsed = json.decodeFromString<ResponsesResponse>(text)
            parsed.error?.message?.let { return AiResult.fail(it) }
            val content = extractResponsesText(parsed)
            val reasoning = extractResponsesReasoning(parsed)
            return if (content.isNullOrBlank()) AiResult.fail("模型返回为空")
            else AiResult.ok(
                content,
                reasoning,
                parsed.usage?.inputTokens ?: estimateTokens(turns.joinToString("") { it.content }),
                parsed.usage?.outputTokens ?: estimateTokens(content + reasoning),
                parsed.usage?.inputTokensDetails?.cachedTokens ?: 0
            )
        }
    }

    /** 流式 responses：监听 output_text / reasoning 增量事件 */
    private suspend fun streamResponses(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        onDelta: (String, String) -> Unit
    ): AiResult {
        val payload = ResponsesRequest(
            model = endpoint.modelName,
            input = turns.map { ChatMessageDto(it.role.name.lowercase(), it.content) },
            temperature = endpoint.temperature,
            maxOutputTokens = endpoint.maxTokens.takeIf { it > 0 },
            instructions = endpoint.systemPrompt.ifBlank { null },
            stream = true,
            promptCacheKey = cacheKey(endpoint)
        )
        val body = json.encodeToString(ResponsesRequest.serializer(), payload)
            .toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.RESPONSES))
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        execute(request).use { resp ->
            if (!resp.isSuccessful) {
                return AiResult.fail(errorForStatus(resp.code, resp.body?.string().orEmpty().take(300)))
            }
            val source = resp.body?.source() ?: return AiResult.fail("模型返回为空")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var usage: ResponsesUsage? = null
            var errorMessage: String? = null
            // 结束标志：response.completed（或 [DONE]）。未收到就 EOF，视为中途断流
            var terminated = false
            var streamEnded = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = readLineOrNull(source)
                if (line == null) {
                    streamEnded = true
                    break
                }
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    terminated = true
                    break
                }
                val event = runCatching {
                    json.decodeFromString(ResponsesStreamEvent.serializer(), data)
                }.getOrNull() ?: continue
                event.error?.message?.let { errorMessage = it }
                when {
                    event.type == "response.output_text.delta" && !event.delta.isNullOrEmpty() -> {
                        content.append(event.delta)
                        onDelta(event.delta, "")
                    }
                    event.type.contains("reasoning") && event.type.endsWith(".delta") && !event.delta.isNullOrEmpty() -> {
                        reasoning.append(event.delta)
                        onDelta("", event.delta)
                    }
                    event.type == "response.completed" -> {
                        terminated = true
                        event.response?.usage?.let { usage = it }
                    }
                }
            }
            errorMessage?.let { return AiResult.fail(it) }
            // 中途断流：连接关闭但没收到 response.completed → 明确报错
            if (streamEnded && !terminated) {
                return AiResult.fail(AiError.Network(IOException("响应中断：连接在完整结束前被关闭")))
            }
            val text = content.toString()
            if (text.isBlank()) {
                return AiResult.fail("模型返回为空")
            }
            return AiResult.ok(
                text,
                reasoning.toString(),
                usage?.inputTokens ?: estimateTokens(turns.joinToString("") { it.content }),
                usage?.outputTokens ?: estimateTokens(text + reasoning),
                usage?.inputTokensDetails?.cachedTokens ?: 0
            )
        }
    }

    private fun extractResponsesText(parsed: ResponsesResponse): String? =
        parsed.outputText
            ?: parsed.output.flatMap { it.content }
                .mapNotNull { it.text }
                .joinToString("")
                .ifBlank { null }

    private fun extractResponsesReasoning(parsed: ResponsesResponse): String =
        parsed.output
            .filter { it.type == "reasoning" }
            .flatMap { it.content }
            .mapNotNull { it.text }
            .joinToString("")

    /** GPT/Responses 的缓存路由键：同一模型固定同一个名字，请求尽量回到存有前缀缓存的那台机器 */
    private fun cacheKey(endpoint: ModelEndpoint): String = "lianwenqi-${endpoint.modelName}"

    // ---------- Anthropic Messages（Claude 原生协议，用 cache_control 显式标注缓存断点） ----------

    /**
     * 把统一的 turns 拆成 Anthropic 的 system + messages，并打上两个缓存断点：
     *  - 缓存点1：system 的最后一块（预设提示词 + 大纲/资料），这段最稳定；
     *  - 缓存点2：messages 中「历史对话的最后一条」（最后一条是本次新消息，不参与缓存）。
     * 这样前缀逐字节稳定、只有尾部增长，缓存命中率最高；DEFAULT 的「临时消息」天然落在末尾。
     */
    private fun buildAnthropicRequest(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        stream: Boolean
    ): AnthropicRequest {
        val system = buildList {
            if (endpoint.systemPrompt.isNotBlank()) {
                add(AnthropicBlock(type = "text", text = endpoint.systemPrompt))
            }
            turns.filter { it.role == ChatRole.SYSTEM }.forEach {
                add(AnthropicBlock(type = "text", text = it.content))
            }
        }.toMutableList()
        if (system.isNotEmpty()) {
            system[system.lastIndex] = system.last().copy(cacheControl = AnthropicCacheControl())
        }

        // messages：去掉 SYSTEM 轮 → 去掉空内容 → 截掉开头的 assistant（Anthropic 要求首条为 user）
        val msgs = turns.filter { it.role != ChatRole.SYSTEM && it.content.isNotBlank() }
            .map { AnthropicMessage(it.role.name.lowercase(), listOf(AnthropicBlock(type = "text", text = it.content))) }
            .toMutableList()
        while (msgs.isNotEmpty() && msgs.first().role != "user") msgs.removeAt(0)
        if (msgs.size >= 2) {
            val i = msgs.size - 2
            val blocks = msgs[i].content.toMutableList()
            if (blocks.isNotEmpty()) {
                blocks[blocks.lastIndex] = blocks.last().copy(cacheControl = AnthropicCacheControl())
            }
            msgs[i] = msgs[i].copy(content = blocks)
        }

        return AnthropicRequest(
            model = endpoint.modelName,
            // Anthropic 的 max_tokens 必填；用户选「不限」时给一个足够大的值
            maxTokens = endpoint.maxTokens.takeIf { it > 0 } ?: ANTHROPIC_DEFAULT_MAX_TOKENS,
            system = system.ifEmpty { null },
            messages = msgs,
            // Anthropic 的 temperature 取值范围是 0~1，超出会被拒绝
            temperature = endpoint.temperature.coerceIn(0f, 1f),
            stream = stream
        )
    }

    private fun anthropicRequest(endpoint: ModelEndpoint, payload: AnthropicRequest): Request =
        Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.ANTHROPIC_MESSAGES))
            .header("x-api-key", endpoint.provider.apiKey)
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(json.encodeToString(AnthropicRequest.serializer(), payload).toRequestBody(JSON_MEDIA))
            .build()

    private suspend fun anthropicMessages(endpoint: ModelEndpoint, turns: List<ChatTurn>): AiResult {
        val payload = buildAnthropicRequest(endpoint, turns, stream = false)
        execute(anthropicRequest(endpoint, payload)).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return AiResult.fail(errorForStatus(resp.code, text.take(300)))
            val parsed = json.decodeFromString<AnthropicResponse>(text)
            parsed.error?.message?.let { return AiResult.fail(it) }
            val content = parsed.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
            val reasoning = parsed.content.filter { it.type == "thinking" }.mapNotNull { it.thinking }.joinToString("")
            if (content.isBlank()) {
                return if (parsed.stopReason == "max_tokens") AiResult.fail(TRUNCATED_HINT)
                else AiResult.fail("模型返回为空")
            }
            val u = parsed.usage
            val totalInput = (u?.inputTokens ?: 0) + (u?.cacheReadInputTokens ?: 0) + (u?.cacheCreationInputTokens ?: 0)
            return AiResult.ok(
                content,
                reasoning,
                if (totalInput > 0) totalInput else estimateTokens(turns.joinToString("") { it.content }),
                u?.outputTokens ?: estimateTokens(content + reasoning),
                u?.cacheReadInputTokens ?: 0
            )
        }
    }

    private suspend fun streamAnthropic(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        onDelta: (String, String) -> Unit
    ): AiResult {
        val payload = buildAnthropicRequest(endpoint, turns, stream = true)
        execute(anthropicRequest(endpoint, payload)).use { resp ->
            if (!resp.isSuccessful) {
                return AiResult.fail(errorForStatus(resp.code, resp.body?.string().orEmpty().take(300)))
            }
            val source = resp.body?.source() ?: return AiResult.fail("模型返回为空")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            var inputTokens = 0
            var outputTokens = 0
            var cacheRead = 0
            var cacheCreation = 0
            var truncated = false
            var errorMessage: String? = null
            // 结束标志：message_stop。未收到就 EOF，视为中途断流
            var terminated = false
            var streamEnded = false
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = readLineOrNull(source)
                if (line == null) {
                    streamEnded = true
                    break
                }
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                val event = runCatching {
                    json.decodeFromString(AnthropicStreamEvent.serializer(), data)
                }.getOrNull() ?: continue
                event.error?.message?.let { errorMessage = it }
                when (event.type) {
                    "message_start" -> event.message?.usage?.let {
                        inputTokens = it.inputTokens
                        cacheRead = it.cacheReadInputTokens
                        cacheCreation = it.cacheCreationInputTokens
                    }
                    "message_delta" -> {
                        event.usage?.let { outputTokens = it.outputTokens }
                        if (event.delta?.stopReason == "max_tokens") truncated = true
                    }
                    "message_stop" -> terminated = true
                    "content_block_delta" -> {
                        val t = event.delta?.text
                        val k = event.delta?.thinking
                        if (!t.isNullOrEmpty()) {
                            content.append(t)
                            onDelta(t, "")
                        }
                        if (!k.isNullOrEmpty()) {
                            reasoning.append(k)
                            onDelta("", k)
                        }
                    }
                }
            }
            errorMessage?.let { return AiResult.fail(it) }
            // 中途断流：连接关闭但没收到 message_stop → 明确报错
            if (streamEnded && !terminated) {
                return AiResult.fail(AiError.Network(IOException("响应中断：连接在完整结束前被关闭")))
            }
            val text = content.toString()
            if (text.isBlank()) {
                return if (truncated) AiResult.fail(TRUNCATED_HINT) else AiResult.fail("模型返回为空")
            }
            val totalInput = inputTokens + cacheRead + cacheCreation
            return AiResult.ok(
                text,
                reasoning.toString(),
                if (totalInput > 0) totalInput else estimateTokens(turns.joinToString("") { it.content }),
                outputTokens.takeIf { it > 0 } ?: estimateTokens(text + reasoning),
                cacheRead
            )
        }
    }

    /** 服务端未返回 usage 时，按字符粗略估算 token（中日韩字符约 1 字 1 token，其余约 4 字符 1 token） */
    private fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        text.forEach { if (it.code > 0x2E80) cjk++ else other++ }
        return (cjk + other / 4).coerceAtLeast(1)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 推理模型的思考过程吃满 max_tokens 时，正文会为空，给出可操作的提示 */
        private const val TRUNCATED_HINT = "回复被截断：模型思考过长占满了最大输出 token，请在「设置 → 模型」中调大「最大输出 token」后重试"

        private const val ANTHROPIC_VERSION = "2023-06-01"

        /** 用户选「不限输出」时，Anthropic 仍要求填 max_tokens，这里给一个足够大的默认值 */
        private const val ANTHROPIC_DEFAULT_MAX_TOKENS = 8192

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}