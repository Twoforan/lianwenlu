package com.lianwenqi.app.ai

import android.util.Log
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.util.extractLeakedToolCalls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
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
     * 当前配置下实际请求的对话地址。
     * 设置页「测试对话」会把它展示出来，便于直接核对 baseUrl 是否少了 /v1、接口类型是否选错。
     */
    fun chatUrl(baseUrl: String, apiType: ApiType): String = endpoint(baseUrl, apiType)

    /**
     * 真实对话连通性测试：发送一次最小的非流式请求，
     * 同时验证 baseUrl + apiKey + 接口类型 + 模型名 四项是否都可用。
     *
     * 与「测试读取模型」不同：`/models` 能通并不代表 `/chat/completions` 能通，
     * 中转站经常只实现其中一部分，所以必须真正发一次对话请求才能确认。
     */
    suspend fun testChat(provider: Provider, modelName: String): AiResult =
        chat(
            endpoint = ModelEndpoint(
                provider = provider,
                modelName = modelName,
                apiType = provider.apiType,
                temperature = 0.3f,
                // 0 表示不发送 max_tokens，避免测试被输出上限截断
                maxTokens = 0,
                reasoningEnabled = false
            ),
            turns = listOf(ChatTurn(ChatRole.USER, "连通性测试：请只回复 OK"))
        )

    /**
     * 发送对话请求。
     * [onDelta] 非空时使用流式（SSE）请求，边收边回调 (正文增量, 思考增量)，用于「实时显示生成」；
     * 为空时一次性返回整段结果。
     * [tools] 非空时随请求携带工具定义；供应商拒绝该字段（400）时自动重试一次不带 tools 的请求，
     * 并在结果上标记 [AiResult.toolsUnsupported]，由上层提示改用手动导入。
     * 流式请求失败且判定为供应商不支持 SSE 时，自动回退一次非流式请求。
     */
    suspend fun chat(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec> = emptyList(),
        onDelta: ((String, String) -> Unit)? = null,
        /** 本轮是否请求模型内置的联网搜索（仅 chat/completions 支持，随 tools 一起发送） */
        webSearch: Boolean = false
    ): AiResult =
        withContext(Dispatchers.IO) {
            val apiKey = endpoint.provider.apiKey
            if (endpoint.provider.baseUrl.isBlank()) return@withContext AiResult.fail("未配置 baseUrl")
            if (apiKey.isBlank()) return@withContext AiResult.fail("未配置 apiKey")

            val wantsStream = onDelta != null
            var sseDisabled = false

            suspend fun runOnce(withTools: Boolean, stream: Boolean): AiResult = runCatching {
                val activeTools = if (withTools) tools else emptyList()
                val search = webSearch && withTools && endpoint.apiType == ApiType.CHAT_COMPLETIONS
                when (endpoint.apiType) {
                    ApiType.CHAT_COMPLETIONS ->
                        if (stream) streamCompletions(endpoint, turns, activeTools, search, onDelta!!)
                        else chatCompletions(endpoint, turns, activeTools, search)
                    ApiType.RESPONSES ->
                        if (stream) streamResponses(endpoint, stripImages(turns), activeTools, onDelta!!)
                        else responses(endpoint, stripImages(turns), activeTools)
                    ApiType.ANTHROPIC_MESSAGES ->
                        if (stream) streamAnthropic(endpoint, stripImages(turns), activeTools, onDelta!!)
                        else anthropicMessages(endpoint, stripImages(turns), activeTools)
                }
            }.getOrElse {
                // 用户主动「停止响应」时协程被取消，需要原样抛出，不能当成请求失败
                if (it is CancellationException) throw it
                // 按异常类型映射到具体 AiError（超时 / 网络 / 解析 / 未知），不再压成一句字符串
                AiResult.fail(AiError.fromThrowable(it))
            }

            // 不支持 SSE 的供应商：本会话内自动回退一次非流式请求，避免用户反复失败
            suspend fun attempt(withTools: Boolean): AiResult {
                if (wantsStream && !sseDisabled) {
                    val streamed = runOnce(withTools, stream = true)
                    if (!sseUnsupported(streamed)) return streamed
                    sseDisabled = true
                }
                return runOnce(withTools, stream = false)
            }

            val hasTools = tools.isNotEmpty() || webSearch
            val first = attempt(hasTools)
            val rejectedTools = hasTools && (first.error as? AiError.Server)?.code == 400
            if (rejectedTools) {
                val retry = attempt(false)
                if (retry.success) retry.copy(toolsUnsupported = true) else retry
            } else first
        }

    /**
     * 判断流式请求失败是否因为供应商不支持 SSE。
     *
     * 常见表现：服务端 400 提示 stream 参数未知 / 不支持，或忽略 stream 直接返回普通 JSON，
     * 使 SSE 解析读到 EOF 报「响应中断」。命中后 [chat] 会回退一次非流式请求。
     */
    private fun sseUnsupported(result: AiResult): Boolean {
        if (result.success) return false
        return when (val error = result.error) {
            is AiError.Server -> {
                val body = error.body.orEmpty().lowercase()
                body.contains("stream") && listOf(
                    "not support", "unsupported", "unknown", "unrecognized", "invalid"
                ).any { body.contains(it) }
            }

            is AiError.Network -> error.cause.message.orEmpty().contains("响应中断")
            else -> false
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

    /**
     * 流式请求的空闲看门狗。
     *
     * 供应商（如 DeepSeek）在排队或长时间思考时会持续发送 SSE 注释行 / 空行做心跳保活，
     * 这些心跳会不断重置 OkHttp 的单次读超时，使服务端明明已经停滞、连接却永远不超时，
     * 界面就会一直停在「正在生成中」，只能手动点停止。
     * 这里单独以「最后一次收到有效增量」为基准做兜底超时，任意心跳都不能续命。
     */
    private class StreamIdleGuard(private val timeoutMs: Long) {
        private var lastActivity = System.nanoTime()

        /** 收到有效增量（正文 / 思考 / 工具参数 / 结束标志 / usage）时调用 */
        fun touch() {
            lastActivity = System.nanoTime()
        }

        fun expired(): Boolean =
            (System.nanoTime() - lastActivity) / 1_000_000 > timeoutMs
    }

    /**
     * 实际发送的系统提示：开启「思考过程强制使用英文」时在末尾追加语言要求。
     * 只约束模型内部的思考 / 推理语言，正文输出仍按原提示词（中文）生成。
     */
    private fun systemPromptOf(endpoint: ModelEndpoint): String {
        if (!endpoint.forceEnglishThinking) return endpoint.systemPrompt
        return if (endpoint.systemPrompt.isBlank()) ENGLISH_THINKING_HINT
        else endpoint.systemPrompt.trimEnd() + "\n\n" + ENGLISH_THINKING_HINT
    }

    private fun buildMessages(endpoint: ModelEndpoint, turns: List<ChatTurn>): List<OutboundMessageDto> =
        mergeAdjacentSystemTurns(buildList {
            val system = systemPromptOf(endpoint)
            if (system.isNotBlank()) add(OutboundMessageDto.text("system", system))
            turns.forEach {
                add(OutboundMessageDto.of(it.role.name.lowercase(), it.content, it.images))
            }
        })

    /**
     * chat/completions 的相邻 system 消息合并为一条。
     *
     * 目的：MiniMax 等供应商的被动缓存顺序是「System Prompt → 工具列表 → 用户消息」，
     * 把模型预设提示 + 文风 / 自定义前缀 / 参考资料合并进同一条 system 消息，
     * 可避免它们被拆到 system 段之外；同时减少连续 system 消息触发的网关校验差异。
     * 只合并纯文本 system，保持原文顺序，用空行分隔，不改变任何指令内容。
     */
    private fun mergeAdjacentSystemTurns(messages: List<OutboundMessageDto>): List<OutboundMessageDto> {
        val merged = ArrayList<OutboundMessageDto>(messages.size)
        messages.forEach { msg ->
            val last = merged.lastOrNull()
            val lastText = last?.content as? JsonPrimitive
            val currentText = msg.content as? JsonPrimitive
            if (last != null && lastText != null && currentText != null &&
                last.role == "system" && msg.role == "system" &&
                lastText.isString && currentText.isString &&
                last.reasoningContent == null && last.toolCalls == null &&
                msg.reasoningContent == null && msg.toolCalls == null
            ) {
                merged[merged.lastIndex] = last.copy(
                    content = JsonPrimitive(lastText.content + "\n\n" + currentText.content)
                )
            } else {
                merged += msg
            }
        }
        return merged
    }

    /** responses 的 input 同样合并相邻 system 消息（纯文本，顺序不变） */
    private fun mergeAdjacentSystemInputs(messages: List<ChatMessageDto>): List<ChatMessageDto> {
        val merged = ArrayList<ChatMessageDto>(messages.size)
        messages.forEach { msg ->
            val last = merged.lastOrNull()
            if (last != null &&
                last.role == "system" && msg.role == "system" &&
                last.reasoningContent == null && last.toolCalls == null &&
                msg.reasoningContent == null && msg.toolCalls == null
            ) {
                merged[merged.lastIndex] = last.copy(
                    content = last.content.orEmpty() + "\n\n" + msg.content.orEmpty()
                )
            } else {
                merged += msg
            }
        }
        return merged
    }

    /**
     * 不支持多模态的接口（Responses / Anthropic）遇到图片时降级：
     * 去掉图片数据并在正文后补一行说明，避免静默丢内容让用户以为图片已生效。
     */
    private fun stripImages(turns: List<ChatTurn>): List<ChatTurn> =
        turns.map {
            if (it.images.isEmpty()) {
                it
            } else {
                it.copy(
                    content = it.content + "\n[图片已省略：当前接口不支持]",
                    images = emptyList()
                )
            }
        }

    /**
     * 按「标准化 → 查表映射 → 夹取」得到该模型在当前接口下真正要注入的思考参数。
     * 关闭思考或模型不支持时返回空（不发送任何思考参数，保持与旧版本一致的行为）。
     */
    private fun reasoningFields(endpoint: ModelEndpoint, apiType: ApiType): ReasoningFields {
        val plan = reasoningPlan(endpoint.modelName, endpoint.reasoningLevel)
        val on = endpoint.reasoningEnabled
        return when (plan.style) {
            // OpenAI 推理系：只有开启时才发 reasoning_effort（o 系无法真正关闭思考，故关闭时不发）
            ReasoningStyle.OPENAI_EFFORT -> when {
                !on -> ReasoningFields()
                apiType == ApiType.CHAT_COMPLETIONS ->
                    ReasoningFields(effort = ReasoningLevel.effortValue(plan.level))
                apiType == ApiType.RESPONSES ->
                    ReasoningFields(reasoning = ResponsesReasoningParam(ReasoningLevel.effortValue(plan.level)))
                else -> ReasoningFields()
            }
            // DeepSeek V4 系：reasoning_effort 一个参数同时控制开关与强度，关闭时发 none
            ReasoningStyle.DEEPSEEK -> if (apiType == ApiType.CHAT_COMPLETIONS) {
                ReasoningFields(
                    effort = if (on) ReasoningLevel.deepseekEffortValue(plan.level) else "none"
                )
            } else ReasoningFields()
            // Anthropic 扩展思考：仅 anthropic 接口生效；关闭时不发（Claude 默认不思考）
            ReasoningStyle.ANTHROPIC -> if (on && apiType == ApiType.ANTHROPIC_MESSAGES) {
                val budget = ReasoningLevel.budgetTokens(plan.level)
                ReasoningFields(
                    anthropicThinking = AnthropicThinking("enabled", budget),
                    requiredMaxTokens = budget + REASONING_MAX_TOKENS_GAP
                )
            } else ReasoningFields()
            // 通义千问：用 chat_template_kwargs 显式开关（关闭也要发 false，否则默认开启）
            ReasoningStyle.QWEN -> if (apiType == ApiType.CHAT_COMPLETIONS) {
                ReasoningFields(
                    kwargs = ChatTemplateKwargs(
                        enableThinking = on,
                        thinkingBudget = if (on) ReasoningLevel.budgetTokens(plan.level) else null
                    )
                )
            } else ReasoningFields()
            // 开关型 thinking：显式发 enabled / disabled
            ReasoningStyle.TOGGLE -> if (apiType == ApiType.CHAT_COMPLETIONS) {
                ReasoningFields(thinking = ThinkingParam(if (on) "enabled" else "disabled"))
            } else ReasoningFields()
            ReasoningStyle.UNSUPPORTED -> ReasoningFields()
        }
    }

    /** 工具 Schema 原文 → JsonObject；非法时退回一个空的 object schema，避免整个请求失败 */
    private fun schemaOf(spec: AiToolSpec): JsonObject =
        runCatching { json.parseToJsonElement(spec.parametersJson) as? JsonObject }.getOrNull()
            ?: buildJsonObject { put("type", "object"); put("properties", buildJsonObject { }) }

    private fun completionsTools(tools: List<AiToolSpec>, webSearch: Boolean = false): List<JsonElement>? {
        val list = buildList {
            tools.forEach {
                add(
                    json.encodeToJsonElement(
                        ChatCompletionTool.serializer(),
                        ChatCompletionTool(function = ChatFunctionDef(it.name, it.description, schemaOf(it)))
                    )
                )
            }
            // 模型内置联网搜索：与函数工具同属 tools 数组，模型拒绝时随 400 重试一并去掉
            if (webSearch) add(buildJsonObject { put("type", "web_search") })
        }
        return list.takeIf { it.isNotEmpty() }
    }

    private fun responsesTools(tools: List<AiToolSpec>): List<ResponsesTool>? =
        tools.takeIf { it.isNotEmpty() }?.map { ResponsesTool(name = it.name, description = it.description, parameters = schemaOf(it)) }

    private fun anthropicTools(tools: List<AiToolSpec>): List<AnthropicTool>? =
        tools.takeIf { it.isNotEmpty() }?.map { AnthropicTool(name = it.name, description = it.description, inputSchema = schemaOf(it)) }

    /** 完整响应里的工具调用（chat/completions） */
    private fun toolCallsOf(message: ChatMessageDto?): List<AiToolCall> =
        message?.toolCalls.orEmpty().mapNotNull { call ->
            val name = call.function?.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AiToolCall(
                id = call.id.orEmpty(),
                name = name,
                arguments = call.function?.arguments?.takeIf { it.isNotBlank() } ?: "{}"
            )
        }

    /** 完整响应里的工具调用（responses） */
    private fun toolCallsOf(parsed: ResponsesResponse): List<AiToolCall> =
        parsed.output.filter { it.type == "function_call" }.mapNotNull { item ->
            val name = item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AiToolCall(
                id = item.callId.orEmpty().ifBlank { item.id.orEmpty() },
                name = name,
                arguments = item.arguments?.takeIf { it.isNotBlank() } ?: "{}"
            )
        }

    /**
     * 部分供应商（含部分 DeepSeek 兼容实现）会把工具调用写进正文而不是 `tool_calls`。
     * 这里在正文里还原，并把泄漏片段从正文剔除；顺带标记「检测到语法但没能还原」供上层提示。
     */
    private fun recoverLeakedToolCalls(
        content: String?,
        calls: List<AiToolCall>,
        tools: List<AiToolSpec>
    ): Triple<String, List<AiToolCall>, Boolean> {
        val text = content.orEmpty()
        if (tools.isEmpty() || calls.isNotEmpty() || text.isBlank()) return Triple(text, calls, false)
        val leak = extractLeakedToolCalls(text, tools.map { it.name }.toSet())
        return Triple(leak.cleanedContent, leak.calls, leak.syntaxDetected)
    }

    private suspend fun chatCompletions(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec>,
        webSearch: Boolean = false
    ): AiResult {
        val rf = reasoningFields(endpoint, ApiType.CHAT_COMPLETIONS)
        val payload = ChatCompletionRequest(
            model = endpoint.modelName,
            messages = buildMessages(endpoint, turns),
            temperature = endpoint.temperature,
            maxTokens = endpoint.maxTokens.takeIf { it > 0 },
            reasoningEffort = rf.effort,
            thinking = rf.thinking,
            chatTemplateKwargs = rf.kwargs,
            tools = completionsTools(tools, webSearch)
        )
        val bodyText = json.encodeToString(ChatCompletionRequest.serializer(), payload)
        val body = bodyText.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.CHAT_COMPLETIONS))
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val startedNs = System.nanoTime()
        execute(request).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return AiResult.fail(errorForStatus(resp.code, text.take(300)))
            val parsed = json.decodeFromString<ChatCompletionResponse>(text)
            parsed.error?.message?.let { return AiResult.fail(it) }
            val choice = parsed.choices.firstOrNull()
            val reasoning = choice?.message?.reasoningContent.orEmpty()
            val (content, calls, leaked) = recoverLeakedToolCalls(
                choice?.message?.content ?: choice?.text,
                toolCallsOf(choice?.message),
                tools
            )
            val promptTokens = parsed.usage?.promptTokens ?: estimateTokens(turns.joinToString("") { it.content })
            val cacheUsage = CacheUsageParser.fromChatCompletion(parsed.usage)
            val result = if (content.isBlank()) {
                if (calls.isNotEmpty()) AiResult.ok(
                    "",
                    reasoning,
                    promptTokens,
                    parsed.usage?.completionTokens ?: 0,
                    cacheUsage,
                    calls
                )
                else if (choice?.finishReason == "length") AiResult.fail(TRUNCATED_HINT) else AiResult.fail("模型返回为空")
            } else AiResult.ok(
                content,
                reasoning,
                promptTokens,
                parsed.usage?.completionTokens ?: estimateTokens(content + reasoning),
                cacheUsage,
                calls,
                leakedToolSyntax = leaked
            )
            logCacheDiagnostics(
                endpoint, streaming = false, result = result, resp = resp, turns = turns,
                fullRequestBody = bodyText, toolsText = toolsText(payload.tools),
                toolCount = payload.tools?.size ?: 0, latencyMs = elapsedMs(startedNs)
            )
            return result
        }
    }

    /** 流式 chat completions：逐分片回调，最后一个分片带 usage */
    private suspend fun streamCompletions(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec>,
        webSearch: Boolean = false,
        onDelta: (String, String) -> Unit
    ): AiResult {
        val rf = reasoningFields(endpoint, ApiType.CHAT_COMPLETIONS)
        val payload = ChatCompletionRequest(
            model = endpoint.modelName,
            messages = buildMessages(endpoint, turns),
            temperature = endpoint.temperature,
            maxTokens = endpoint.maxTokens.takeIf { it > 0 },
            stream = true,
            streamOptions = StreamOptions(true),
            reasoningEffort = rf.effort,
            thinking = rf.thinking,
            chatTemplateKwargs = rf.kwargs,
            tools = completionsTools(tools, webSearch)
        )
        val bodyText = json.encodeToString(ChatCompletionRequest.serializer(), payload)
        val body = bodyText.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.CHAT_COMPLETIONS))
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val startedNs = System.nanoTime()
        execute(request).use { resp ->
            if (!resp.isSuccessful) {
                return AiResult.fail(errorForStatus(resp.code, resp.body?.string().orEmpty().take(300)))
            }
            val source = resp.body?.source() ?: return AiResult.fail("模型返回为空")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolAcc = ToolCallAccumulator()
            val usageAcc = StreamUsageAccumulator()
            var truncated = false
            var errorMessage: String? = null
            // 是否收到协议结束标志（[DONE] 或 finish_reason）。未收到就 EOF，视为中途断流
            var terminated = false
            var streamEnded = false
            val idleGuard = StreamIdleGuard(STREAM_IDLE_TIMEOUT_MS)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (idleGuard.expired()) return AiResult.fail(STREAM_IDLE_HINT)
                val line = readLineOrNull(source)
                if (line == null) {
                    streamEnded = true
                    break
                }
                // 空行 / 心跳注释（DeepSeek 的 `: keep-alive`）不算有效输出，不能给看门狗续命
                if (line.isBlank() || !line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    terminated = true
                    break
                }
                val chunk = runCatching {
                    json.decodeFromString(ChatCompletionChunk.serializer(), data)
                }.getOrNull() ?: continue
                idleGuard.touch()
                chunk.error?.message?.let { errorMessage = it }
                usageAcc.onChatChunk(chunk)
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
                // 工具调用参数是分片到达的，单独累积，绝不混进正文 / 思考内容
                delta?.toolCalls?.forEach { c ->
                    toolAcc.chatDelta(c.index, c.id, c.function?.name, c.function?.arguments)
                }
            }
            errorMessage?.let { return AiResult.fail(it) }
            // 中途断流：连接关闭但没收到结束标志 → 明确报错，不静默当成成功
            if (streamEnded && !terminated) {
                return AiResult.fail(AiError.Network(IOException("响应中断：连接在完整结束前被关闭")))
            }
            val text = content.toString()
            val (finalText, finalCalls, leaked) = recoverLeakedToolCalls(text, toolAcc.build(), tools)
            if (finalText.isBlank() && finalCalls.isEmpty()) {
                return if (truncated) AiResult.fail(TRUNCATED_HINT) else AiResult.fail("模型返回为空")
            }
            val usage = usageAcc.chatUsage
            val result = AiResult.ok(
                finalText,
                reasoning.toString(),
                usage?.promptTokens ?: estimateTokens(turns.joinToString("") { it.content }),
                usage?.completionTokens ?: estimateTokens(finalText + reasoning),
                CacheUsageParser.fromChatCompletion(usage),
                finalCalls,
                leakedToolSyntax = leaked
            )
            logCacheDiagnostics(
                endpoint, streaming = true, result = result, resp = resp, turns = turns,
                fullRequestBody = bodyText, toolsText = toolsText(payload.tools),
                toolCount = payload.tools?.size ?: 0, latencyMs = elapsedMs(startedNs)
            )
            return result
        }
    }

    private suspend fun responses(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec>
    ): AiResult {
        val payload = ResponsesRequest(
            model = endpoint.modelName,
            input = mergeAdjacentSystemInputs(turns.map { ChatMessageDto(it.role.name.lowercase(), it.content) }),
            temperature = endpoint.temperature,
            maxOutputTokens = endpoint.maxTokens.takeIf { it > 0 },
            instructions = systemPromptOf(endpoint).ifBlank { null },
            promptCacheKey = cacheKey(endpoint),
            reasoning = reasoningFields(endpoint, ApiType.RESPONSES).reasoning,
            tools = responsesTools(tools)
        )
        val bodyText = json.encodeToString(ResponsesRequest.serializer(), payload)
        val body = bodyText.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.RESPONSES))
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val startedNs = System.nanoTime()
        execute(request).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return AiResult.fail(errorForStatus(resp.code, text.take(300)))
            val parsed = json.decodeFromString<ResponsesResponse>(text)
            parsed.error?.message?.let { return AiResult.fail(it) }
            val reasoning = extractResponsesReasoning(parsed)
            val (content, calls, leaked) = recoverLeakedToolCalls(
                extractResponsesText(parsed),
                toolCallsOf(parsed),
                tools
            )
            val result = if (content.isBlank() && calls.isEmpty()) AiResult.fail("模型返回为空")
            else AiResult.ok(
                content,
                reasoning,
                parsed.usage?.inputTokens ?: estimateTokens(turns.joinToString("") { it.content }),
                parsed.usage?.outputTokens ?: estimateTokens(content + reasoning),
                CacheUsageParser.fromResponses(parsed.usage),
                calls,
                leakedToolSyntax = leaked
            )
            logCacheDiagnostics(
                endpoint, streaming = false, result = result, resp = resp, turns = turns,
                fullRequestBody = bodyText, toolsText = toolsText(payload.tools),
                toolCount = payload.tools?.size ?: 0, latencyMs = elapsedMs(startedNs)
            )
            return result
        }
    }

    /** 流式 responses：监听 output_text / reasoning 增量事件 */
    private suspend fun streamResponses(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec>,
        onDelta: (String, String) -> Unit
    ): AiResult {
        val payload = ResponsesRequest(
            model = endpoint.modelName,
            input = mergeAdjacentSystemInputs(turns.map { ChatMessageDto(it.role.name.lowercase(), it.content) }),
            temperature = endpoint.temperature,
            maxOutputTokens = endpoint.maxTokens.takeIf { it > 0 },
            instructions = systemPromptOf(endpoint).ifBlank { null },
            stream = true,
            promptCacheKey = cacheKey(endpoint),
            reasoning = reasoningFields(endpoint, ApiType.RESPONSES).reasoning,
            tools = responsesTools(tools)
        )
        val bodyText = json.encodeToString(ResponsesRequest.serializer(), payload)
        val body = bodyText.toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.RESPONSES))
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val startedNs = System.nanoTime()
        execute(request).use { resp ->
            if (!resp.isSuccessful) {
                return AiResult.fail(errorForStatus(resp.code, resp.body?.string().orEmpty().take(300)))
            }
            val source = resp.body?.source() ?: return AiResult.fail("模型返回为空")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolAcc = ToolCallAccumulator()
            val usageAcc = StreamUsageAccumulator()
            var errorMessage: String? = null
            // 结束标志：response.completed（或 [DONE]）。未收到就 EOF，视为中途断流
            var terminated = false
            var streamEnded = false
            val idleGuard = StreamIdleGuard(STREAM_IDLE_TIMEOUT_MS)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (idleGuard.expired()) return AiResult.fail(STREAM_IDLE_HINT)
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
                idleGuard.touch()
                event.error?.message?.let { errorMessage = it }
                usageAcc.onResponsesEvent(event)
                when {
                    event.type == "response.output_text.delta" && !event.delta.isNullOrEmpty() -> {
                        content.append(event.delta)
                        onDelta(event.delta, "")
                    }
                    event.type.contains("reasoning") && event.type.endsWith(".delta") && !event.delta.isNullOrEmpty() -> {
                        reasoning.append(event.delta)
                        onDelta("", event.delta)
                    }
                    event.type == "response.output_item.added" && event.item?.type == "function_call" -> {
                        toolAcc.responsesAdded(event.item.id, event.item.callId, event.item.name, event.item.arguments)
                    }
                    event.type == "response.function_call_arguments.delta" -> {
                        toolAcc.responsesDelta(event.itemId ?: event.item?.id, event.delta)
                    }
                    event.type == "response.completed" -> {
                        terminated = true
                    }
                }
            }
            errorMessage?.let { return AiResult.fail(it) }
            // 中途断流：连接关闭但没收到 response.completed → 明确报错
            if (streamEnded && !terminated) {
                return AiResult.fail(AiError.Network(IOException("响应中断：连接在完整结束前被关闭")))
            }
            val text = content.toString()
            val (finalText, finalCalls, leaked) = recoverLeakedToolCalls(text, toolAcc.build(), tools)
            if (finalText.isBlank() && finalCalls.isEmpty()) {
                return AiResult.fail("模型返回为空")
            }
            val usage = usageAcc.responsesUsage
            val result = AiResult.ok(
                finalText,
                reasoning.toString(),
                usage?.inputTokens ?: estimateTokens(turns.joinToString("") { it.content }),
                usage?.outputTokens ?: estimateTokens(finalText + reasoning),
                CacheUsageParser.fromResponses(usage),
                finalCalls,
                leakedToolSyntax = leaked
            )
            logCacheDiagnostics(
                endpoint, streaming = true, result = result, resp = resp, turns = turns,
                fullRequestBody = bodyText, toolsText = toolsText(payload.tools),
                toolCount = payload.tools?.size ?: 0, latencyMs = elapsedMs(startedNs)
            )
            return result
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
     *  - 缓存点1：稳定前缀的断点（[ChatTurn.cacheBreakpoint] 标记的第一块），
     *    没有标记时回落到 system 的最后一块；
     *  - 缓存点2：messages 中「历史对话的最后一条」（最后一条是本次新消息，不参与缓存）。
     * 这样前缀逐字节稳定、只有尾部增长，缓存命中率最高；DEFAULT 的「临时消息」天然落在末尾。
     */
    private fun buildAnthropicRequest(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        stream: Boolean,
        tools: List<AiToolSpec> = emptyList()
    ): AnthropicRequest {
        val prompt = systemPromptOf(endpoint)
        val systemTurns = turns.filter { it.role == ChatRole.SYSTEM }
        val system = buildList {
            if (prompt.isNotBlank()) {
                add(AnthropicBlock(type = "text", text = prompt))
            }
            systemTurns.forEach {
                add(AnthropicBlock(type = "text", text = it.content))
            }
        }.toMutableList()
        if (system.isNotEmpty()) {
            // 取「第一处」显式断点：稳定前缀（文风/前缀/参考资料）优先；
            // 没有稳定前缀时退回到写作指令本身，避免断点落在章节标题、字数范围等动态块上。
            val offset = if (prompt.isNotBlank()) 1 else 0
            val marked = systemTurns.indexOfFirst { it.cacheBreakpoint }
            val index = if (marked >= 0) offset + marked else system.lastIndex
            system[index] = system[index].copy(cacheControl = AnthropicCacheControl())
        }

        // messages：去掉 SYSTEM 轮 → 去掉空内容 → 合并相邻同角色（Anthropic 要求 user/assistant 交替）
        // → 截掉开头的 assistant（Anthropic 要求首条为 user）
        val msgs = mutableListOf<AnthropicMessage>()
        turns.filter { it.role != ChatRole.SYSTEM && it.content.isNotBlank() }.forEach { turn ->
            val role = turn.role.name.lowercase()
            val last = msgs.lastOrNull()
            if (last != null && last.role == role) {
                val text = last.content.joinToString("\n\n") { it.text.orEmpty() } +
                    "\n\n" + turn.content
                msgs[msgs.lastIndex] = last.copy(
                    content = listOf(AnthropicBlock(type = "text", text = text))
                )
            } else {
                msgs += AnthropicMessage(role, listOf(AnthropicBlock(type = "text", text = turn.content)))
            }
        }
        while (msgs.isNotEmpty() && msgs.first().role != "user") msgs.removeAt(0)
        if (msgs.size >= 2) {
            val i = msgs.size - 2
            val blocks = msgs[i].content.toMutableList()
            if (blocks.isNotEmpty()) {
                blocks[blocks.lastIndex] = blocks.last().copy(cacheControl = AnthropicCacheControl())
            }
            msgs[i] = msgs[i].copy(content = blocks)
        }

        val rf = reasoningFields(endpoint, ApiType.ANTHROPIC_MESSAGES)
        val thinking = rf.anthropicThinking
        val baseMax = endpoint.maxTokens.takeIf { it > 0 } ?: ANTHROPIC_DEFAULT_MAX_TOKENS
        return AnthropicRequest(
            model = endpoint.modelName,
            // Anthropic 的 max_tokens 必填；开启思考时需大于思考预算，否则会被服务端拒绝
            maxTokens = if (thinking != null) maxOf(baseMax, rf.requiredMaxTokens) else baseMax,
            system = system.ifEmpty { null },
            messages = msgs,
            // Anthropic 的 temperature 取值范围是 0~1；开启思考时不允许改温度（省略即用默认 1）
            temperature = if (thinking != null) null else endpoint.temperature.coerceIn(0f, 1f),
            stream = stream,
            thinking = thinking,
            tools = anthropicTools(tools)
        )
    }

    private fun anthropicRequest(endpoint: ModelEndpoint, bodyText: String): Request =
        Request.Builder()
            .url(endpoint(endpoint.provider.baseUrl, ApiType.ANTHROPIC_MESSAGES))
            .header("x-api-key", endpoint.provider.apiKey)
            .header("Authorization", "Bearer ${endpoint.provider.apiKey}")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
            .post(bodyText.toRequestBody(JSON_MEDIA))
            .build()

    private suspend fun anthropicMessages(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec>
    ): AiResult {
        val payload = buildAnthropicRequest(endpoint, turns, stream = false, tools = tools)
        val bodyText = json.encodeToString(AnthropicRequest.serializer(), payload)
        val startedNs = System.nanoTime()
        execute(anthropicRequest(endpoint, bodyText)).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return AiResult.fail(errorForStatus(resp.code, text.take(300)))
            val parsed = json.decodeFromString<AnthropicResponse>(text)
            parsed.error?.message?.let { return AiResult.fail(it) }
            val reasoning = parsed.content.filter { it.type == "thinking" }.mapNotNull { it.thinking }.joinToString("")
            val calls = parsed.content.filter { it.type == "tool_use" }.mapNotNull { block ->
                val name = block.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                AiToolCall(
                    id = block.id.orEmpty(),
                    name = name,
                    arguments = block.input?.toString()?.takeIf { it.isNotBlank() } ?: "{}"
                )
            }
            val rawContent = parsed.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
            val (content, finalCalls, leaked) = recoverLeakedToolCalls(rawContent, calls, tools)
            if (content.isBlank() && finalCalls.isEmpty()) {
                return if (parsed.stopReason == "max_tokens") AiResult.fail(TRUNCATED_HINT)
                else AiResult.fail("模型返回为空")
            }
            val u = parsed.usage
            // 分母只在供应商明确返回 input_tokens 时成立；缺失时保持未知，绝不用 0 补，
            // 否则「纯缓存读取」会被错误算成 100%。本地估算只用于展示，不进入命中率。
            val totalInput = u?.inputTokens?.let { input ->
                input + (u.cacheReadInputTokens ?: 0) + (u.cacheCreationInputTokens ?: 0)
            }
            val result = AiResult.ok(
                content,
                reasoning,
                totalInput ?: estimateTokens(turns.joinToString("") { it.content }),
                u?.outputTokens ?: estimateTokens(content + reasoning),
                CacheUsageParser.fromAnthropic(u),
                finalCalls,
                leakedToolSyntax = leaked
            )
            logCacheDiagnostics(
                endpoint, streaming = false, result = result, resp = resp, turns = turns,
                fullRequestBody = bodyText, toolsText = toolsText(payload.tools),
                toolCount = payload.tools?.size ?: 0, latencyMs = elapsedMs(startedNs)
            )
            return result
        }
    }

    private suspend fun streamAnthropic(
        endpoint: ModelEndpoint,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec>,
        onDelta: (String, String) -> Unit
    ): AiResult {
        val payload = buildAnthropicRequest(endpoint, turns, stream = true, tools = tools)
        val bodyText = json.encodeToString(AnthropicRequest.serializer(), payload)
        val startedNs = System.nanoTime()
        execute(anthropicRequest(endpoint, bodyText)).use { resp ->
            if (!resp.isSuccessful) {
                return AiResult.fail(errorForStatus(resp.code, resp.body?.string().orEmpty().take(300)))
            }
            val source = resp.body?.source() ?: return AiResult.fail("模型返回为空")
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val toolAcc = ToolCallAccumulator()
            val usageAcc = StreamUsageAccumulator()
            var truncated = false
            var errorMessage: String? = null
            // 结束标志：message_stop。未收到就 EOF，视为中途断流
            var terminated = false
            var streamEnded = false
            val idleGuard = StreamIdleGuard(STREAM_IDLE_TIMEOUT_MS)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (idleGuard.expired()) return AiResult.fail(STREAM_IDLE_HINT)
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
                idleGuard.touch()
                event.error?.message?.let { errorMessage = it }
                usageAcc.onAnthropicEvent(event)
                when (event.type) {
                    "message_delta" -> {
                        if (event.delta?.stopReason == "max_tokens") truncated = true
                    }
                    "message_stop" -> terminated = true
                    "content_block_start" -> {
                        val block = event.contentBlock
                        if (block?.type == "tool_use") {
                            toolAcc.anthropicStart(event.index, block.id, block.name)
                        }
                    }
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
                        // 工具参数分片：只进累积器，不作为正文展示
                        if (event.delta?.type == "input_json_delta" || !event.delta?.partialJson.isNullOrEmpty()) {
                            toolAcc.anthropicDelta(event.index, event.delta?.partialJson)
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
            val (finalText, finalCalls, leaked) = recoverLeakedToolCalls(text, toolAcc.build(), tools)
            if (finalText.isBlank() && finalCalls.isEmpty()) {
                return if (truncated) AiResult.fail(TRUNCATED_HINT) else AiResult.fail("模型返回为空")
            }
            val u = usageAcc.anthropicUsage
            // 同非流式：input_tokens 缺失时分母未知，不参与命中率计算
            val totalInput = u?.inputTokens?.let { input ->
                input + (u.cacheReadInputTokens ?: 0) + (u.cacheCreationInputTokens ?: 0)
            }
            val result = AiResult.ok(
                finalText,
                reasoning.toString(),
                totalInput ?: estimateTokens(turns.joinToString("") { it.content }),
                u?.outputTokens?.takeIf { it > 0 } ?: estimateTokens(finalText + reasoning),
                CacheUsageParser.fromAnthropic(u),
                finalCalls,
                leakedToolSyntax = leaked
            )
            logCacheDiagnostics(
                endpoint, streaming = true, result = result, resp = resp, turns = turns,
                fullRequestBody = bodyText, toolsText = toolsText(payload.tools),
                toolCount = payload.tools?.size ?: 0, latencyMs = elapsedMs(startedNs)
            )
            return result
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

    /**
     * 缓存统计与 Prompt 前缀诊断日志：只记录计数、摘要、角色统计与字段路径，
     * 绝不记录系统提示词、用户消息、小说正文、API Key 或完整请求体。
     *
     * 日志级别为 Debug；可通过 `adb shell setprop log.tag.AiCache DEBUG` 单独打开。
     */
    private fun logCacheDiagnostics(
        endpoint: ModelEndpoint,
        streaming: Boolean,
        result: AiResult,
        resp: Response,
        turns: List<ChatTurn>,
        fullRequestBody: String,
        toolsText: String,
        toolCount: Int,
        latencyMs: Long
    ) {
        if (!result.success) return
        val cache = result.cacheUsage
        val fingerprint = PromptDiagnostics.fingerprint(
            systemPrompt = systemPromptOf(endpoint),
            turns = turns,
            toolsText = toolsText,
            toolCount = toolCount,
            fullRequestBody = fullRequestBody
        )
        val diff = PromptDiagnostics.remember(
            PromptDiagnostics.endpointKey(endpoint, streaming),
            fingerprint
        )
        // 命中率只使用供应商明确提供的分母；分母缺失时标记 incomplete，绝不补 0
        val denominator = cache.promptTokens
        val rate = CacheRateCalculator.of(denominator, cache.cachedTokens)
        val hitRate = when (rate.state) {
            CacheRateState.HIT, CacheRateState.ZERO -> "${rate.percent}%"
            CacheRateState.ANOMALY -> "anomaly"
            CacheRateState.INCOMPLETE -> "incomplete"
            CacheRateState.UNKNOWN -> "unknown"
            CacheRateState.NO_REQUEST -> "none"
        }
        val cacheMode = when (endpoint.apiType) {
            ApiType.ANTHROPIC_MESSAGES -> "anthropic-explicit"
            // Responses 的 prompt_cache_key 只做路由提示，不改变缓存匹配方式
            ApiType.RESPONSES -> "passive-prefix+prompt-cache-key"
            ApiType.CHAT_COMPLETIONS -> "passive-prefix"
        }
        val cacheState = when (rate.state) {
            CacheRateState.UNKNOWN -> "unknown"
            CacheRateState.INCOMPLETE -> "incomplete"
            CacheRateState.ANOMALY -> "anomaly"
            CacheRateState.ZERO -> "zero"
            CacheRateState.HIT -> "hit"
            CacheRateState.NO_REQUEST -> "none"
        }
        Log.d(
            CACHE_LOG_TAG,
            "provider=${endpoint.provider.name} apiType=${endpoint.apiType} model=${endpoint.modelName} " +
                "stream=$streaming cacheMode=$cacheMode " +
                "promptTokens=${result.promptTokens} reportedPromptTokens=${denominator ?: "null"} " +
                "cachedTokens=${cache.cachedTokens ?: "null"} " +
                "cacheHitRate=$hitRate cacheState=$cacheState usageReported=${cache.usageReported} " +
                "cacheField=${cache.cacheField ?: "null"} " +
                "cacheCreationTokens=${cache.cacheCreationTokens ?: "null"} " +
                "cacheConflicts=${cache.cacheConflicts.joinToString(";").ifBlank { "none" }} " +
                "systemChars=${fingerprint.systemChars} historyChars=${fingerprint.historyChars} " +
                "tailChars=${fingerprint.tailChars} roleSummary=${fingerprint.roleSummary} " +
                "toolCount=${fingerprint.toolCount} " +
                "systemHash=${fingerprint.systemHash} toolsHash=${fingerprint.toolsHash} " +
                "conversationPrefixHash=${fingerprint.conversationPrefixHash} " +
                "fullRequestHash=${fingerprint.fullRequestHash} " +
                "firstDifferent=${diff.firstDifferentSegment ?: "none"} " +
                "intervalMs=${diff.intervalMs ?: "null"} latencyMs=$latencyMs " +
                "requestId=${requestIdOf(resp) ?: "null"}"
        )
    }

    private fun requestIdOf(resp: Response): String? =
        resp.header("x-request-id")
            ?: resp.header("request-id")
            ?: resp.header("x-oneapi-request-id")

    /** 工具定义摘要输入：只用于计算 toolsHash，不写日志正文 */
    private fun toolsText(tools: List<*>?): String =
        tools?.joinToString("\u0000") { it.toString() }.orEmpty()

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 缓存统计诊断日志的 tag */
        private const val CACHE_LOG_TAG = "AiCache"

        /** 推理模型的思考过程吃满 max_tokens 时，正文会为空，给出可操作的提示 */
        private const val TRUNCATED_HINT = "回复被截断：模型思考过长占满了最大输出 token，请在「设置 → 模型」中调大「最大输出 token」后重试"

        /**
         * 流式请求的「无有效增量」兜底超时：只认正文 / 思考 / 工具参数 / 结束标志，心跳不算。
         * 与 OkHttp 的读超时（180s）对齐，但不受心跳保活影响，避免服务端停滞时永久「正在生成中」。
         */
        private const val STREAM_IDLE_TIMEOUT_MS = 180_000L

        private const val STREAM_IDLE_HINT = "模型长时间没有返回任何内容（超过 3 分钟），已中止本次请求，请稍后重试"

        private const val ANTHROPIC_VERSION = "2023-06-01"

        /** 用户选「不限输出」时，Anthropic 仍要求填 max_tokens，这里给一个足够大的默认值 */
        private const val ANTHROPIC_DEFAULT_MAX_TOKENS = 8192

        /** 开启思考时，Anthropic 的 max_tokens 需比思考预算多出的余量，留给正文输出 */
        private const val REASONING_MAX_TOKENS_GAP = 1024

        /** 「思考过程强制使用英文」追加到系统提示末尾的要求（正文语言不受影响） */
        private const val ENGLISH_THINKING_HINT =
            "Thinking language requirement: if you perform internal reasoning or thinking, do the entire thinking process in English only. " +
                "The final answer/output that is shown to the user must still be written in Chinese (中文)."

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** 一次请求要注入的思考参数（按当前接口类型取用；全为 null 表示不注入任何思考参数） */
private class ReasoningFields(
    /** chat completions 的 reasoning_effort */
    val effort: String? = null,
    /** chat completions 的 thinking 开关（GLM / 豆包等） */
    val thinking: ThinkingParam? = null,
    /** chat completions 的 chat_template_kwargs（千问） */
    val kwargs: ChatTemplateKwargs? = null,
    /** responses 接口的 reasoning.effort */
    val reasoning: ResponsesReasoningParam? = null,
    /** anthropic 接口的 thinking 配置 */
    val anthropicThinking: AnthropicThinking? = null,
    /** 开启 anthropic 思考时，max_tokens 至少要达到的值 */
    val requiredMaxTokens: Int = 0
)