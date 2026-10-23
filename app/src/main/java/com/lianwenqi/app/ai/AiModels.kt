package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.Provider
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** 对话消息（发送给模型的统一结构） */
data class ChatTurn(val role: ChatRole, val content: String)

/** 工具定义：[parametersJson] 为 JSON Schema 原文，直接透传给供应商 */
data class AiToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String
)

/** 模型发起的一次工具调用；[arguments] 为工具参数的原始 JSON 字符串 */
data class AiToolCall(val id: String, val name: String, val arguments: String)

data class AiRequest(
    val turns: List<ChatTurn>,
    val model: String,
    val temperature: Float,
    val maxTokens: Int,
    val systemPrompt: String = "",
    /** 本轮提供给模型的工具定义；为空表示普通对话（不带 tools） */
    val tools: List<AiToolSpec> = emptyList()
)

data class AiResult(
    val success: Boolean,
    val content: String = "",
    /** 失败原因（按类型区分）；成功时为 null */
    val error: AiError? = null,
    /** 深度思考内容（reasoning_content / responses 的 reasoning 项），为空表示模型没有思考过程 */
    val reasoning: String = "",
    /** 本次消耗的输入 token（usage.prompt_tokens） */
    val promptTokens: Int = 0,
    /** 本次消耗的输出 token（usage.completion_tokens） */
    val completionTokens: Int = 0,
    /** 本次输入中命中缓存的 token 数（OpenAI cached_tokens / Anthropic cache_read_input_tokens） */
    val cachedTokens: Int = 0,
    /** 模型本轮发起的工具调用（参数已按流式分片累积完整） */
    val toolCalls: List<AiToolCall> = emptyList(),
    /** 本次带 tools 的请求被供应商拒绝后，已自动重试一次无 tools 请求（用于提示用户手动手动导入） */
    val toolsUnsupported: Boolean = false
) {
    companion object {
        fun ok(
            text: String,
            reasoning: String = "",
            promptTokens: Int = 0,
            completionTokens: Int = 0,
            cachedTokens: Int = 0,
            toolCalls: List<AiToolCall> = emptyList()
        ) = AiResult(
            true, text,
            reasoning = reasoning,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            toolCalls = toolCalls
        )

        fun fail(error: AiError) = AiResult(false, error = error)

        /**
         * 内容级失败（如「模型返回为空」「回复被截断」）：不属于网络/鉴权/超时等传输问题，
         * 归入 [AiError.Unknown]，同时把原始提示文本保留在 userMessage 中。
         */
        fun fail(message: String) =
            AiResult(false, error = AiError.Unknown(IllegalStateException(message)))
    }
}

// ---------- OpenAI 兼容协议 DTO ----------

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
    /** OpenAI 推理系（o1/o3/o4/gpt-5 等）的思考强度：low / medium / high */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /** 开关型思考（智谱 GLM、豆包 seed 等）：thinking.type = enabled / disabled */
    val thinking: ThinkingParam? = null,
    /** 通义千问系的思考开关与思考预算 */
    @SerialName("chat_template_kwargs") val chatTemplateKwargs: ChatTemplateKwargs? = null,
    /** 可用工具列表（OpenAI 兼容格式）；为空时不发送该字段 */
    val tools: List<ChatCompletionTool>? = null
)

/** chat/completions 的工具定义：{type:"function", function:{name,description,parameters}} */
@Serializable
data class ChatCompletionTool(
    @EncodeDefault val type: String = "function",
    val function: ChatFunctionDef
)

@Serializable
data class ChatFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/** 响应里的工具调用（流式分片与完整响应共用；流式时 index 标识第几个调用） */
@Serializable
data class ToolCallDto(
    val index: Int? = null,
    val id: String? = null,
    val function: ToolCallFunctionDto? = null
)

@Serializable
data class ToolCallFunctionDto(
    val name: String? = null,
    val arguments: String? = null
)

/** OpenAI 兼容接口的 thinking 开关（{"type":"enabled"|"disabled"}） */
@Serializable
data class ThinkingParam(val type: String)

/** 通义千问的思考开关（chat_template_kwargs） */
@Serializable
data class ChatTemplateKwargs(
    @SerialName("enable_thinking") val enableThinking: Boolean,
    @SerialName("thinking_budget") val thinkingBudget: Int? = null
)

/** Responses API 的思考强度（reasoning.effort） */
@Serializable
data class ResponsesReasoningParam(val effort: String? = null)

/** Anthropic 扩展思考（thinking.type + budget_tokens） */
@Serializable
data class AnthropicThinking(
    val type: String,
    @SerialName("budget_tokens") val budgetTokens: Int? = null
)

/** 流式请求时要求服务端在最后一个分片中返回 usage（必须真正发送，故强制编码默认值） */
@Serializable
data class StreamOptions(@EncodeDefault @SerialName("include_usage") val includeUsage: Boolean = true)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    /** 其中命中缓存的前缀 token 数 */
    @SerialName("prompt_tokens_details") val promptTokensDetails: PromptTokensDetails? = null
)

/** OpenAI 命中缓存的 token 数（usage.prompt_tokens_details.cached_tokens） */
@Serializable
data class PromptTokensDetails(@SerialName("cached_tokens") val cachedTokens: Int = 0)

@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** 模型发起的工具调用（仅解析响应时出现） */
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null
) {
    @Serializable
    data class Choice(
        val message: ChatMessageDto? = null,
        val text: String? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )
}

/** 流式分片（SSE data: 行） */
@Serializable
data class ChatCompletionChunk(
    val choices: List<ChunkChoice> = emptyList(),
    val usage: Usage? = null,
    val error: ApiError? = null
) {
    @Serializable
    data class ChunkChoice(
        val delta: Delta? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Delta(
        val content: String? = null,
        @SerialName("reasoning_content") val reasoningContent: String? = null,
        /** 流式工具调用分片：按 index 累积 id / name / arguments */
        @SerialName("tool_calls") val toolCalls: List<ToolCallDto>? = null
    )
}

@Serializable
data class ApiError(val message: String? = null, val type: String? = null, val code: String? = null)

/** Responses API（OpenAI /v1/responses） */
@Serializable
data class ResponsesRequest(
    val model: String,
    val input: List<ChatMessageDto>,
    val temperature: Float? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val instructions: String? = null,
    val stream: Boolean = false,
    /**
     * GPT 的缓存路由键：带上同一个名字，服务端会把请求尽量分派到存有你前缀缓存的那台机器，
     * 从而提高缓存命中率（名字相同的请求每分钟不要超过 15 次）。
     */
    @SerialName("prompt_cache_key") val promptCacheKey: String? = null,
    /** 思考强度（GPT/o 系在 responses 接口下用 reasoning.effort） */
    val reasoning: ResponsesReasoningParam? = null,
    /** 可用工具列表（Responses 格式：{type:"function", name, description, parameters}） */
    val tools: List<ResponsesTool>? = null
)

/** responses 接口的工具定义（与 chat completions 不同，function 字段是平铺的） */
@Serializable
data class ResponsesTool(
    @EncodeDefault val type: String = "function",
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ResponsesResponse(
    @SerialName("output_text") val outputText: String? = null,
    val output: List<OutputItem> = emptyList(),
    val usage: ResponsesUsage? = null,
    val error: ApiError? = null
) {
    @Serializable
    data class OutputItem(
        val type: String = "",
        val role: String = "",
        val content: List<ContentPart> = emptyList(),
        /** function_call 项：调用 id / 名称 / 参数 JSON */
        val id: String? = null,
        @SerialName("call_id") val callId: String? = null,
        val name: String? = null,
        val arguments: String? = null
    )
    @Serializable
    data class ContentPart(val type: String = "", val text: String? = null)
}

@Serializable
data class ResponsesUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("input_tokens_details") val inputTokensDetails: PromptTokensDetails? = null
)

/** Responses API 流式事件（SSE data: 行） */
@Serializable
data class ResponsesStreamEvent(
    val type: String = "",
    val delta: String? = null,
    val response: ResponsesResponse? = null,
    val error: ApiError? = null,
    /** output_item.added 事件里新出现的项（function_call 时带 id/call_id/name/arguments） */
    val item: ResponsesResponse.OutputItem? = null,
    /** function_call_arguments.delta/done 事件对应的项 id */
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("output_index") val outputIndex: Int? = null,
    /** function_call_arguments.done 事件给的完整参数 */
    val arguments: String? = null
)

@Serializable
data class ModelListResponse(
    val data: List<ModelItem> = emptyList(),
    val error: ApiError? = null
) {
    @Serializable
    data class ModelItem(val id: String = "")
}

/** 供应商 + 模型的可发送配置 */
data class ModelEndpoint(
    val provider: Provider,
    val modelName: String,
    val apiType: ApiType = provider.apiType,
    val temperature: Float = 0.8f,
    /** <= 0 表示不限输出（请求时不发送 max_tokens / max_output_tokens） */
    val maxTokens: Int = 2048,
    /** 携带的历史消息条数上限 */
    val maxContext: Int = 20,
    val systemPrompt: String = "",
    /** 是否开启思考/推理模式（默认开启） */
    val reasoningEnabled: Boolean = true,
    /** 思考强度档位存储值：low / medium / high / max（默认最高强度） */
    val reasoningLevel: String = ReasoningLevel.DEFAULT.storage
)

// ---------- Anthropic Messages 协议 DTO（/v1/messages） ----------

/** 缓存断点标记：标记到哪一块，服务端就把「从开头到该块」的前缀缓存下来 */
@Serializable
data class AnthropicCacheControl(@EncodeDefault val type: String = "ephemeral")

@Serializable
data class AnthropicBlock(
    /** type 为协议必填字段，强制编码（json 配置了 encodeDefaults=false） */
    @EncodeDefault val type: String = "text",
    val text: String? = null,
    /** thinking 块的正文（仅解析响应时出现） */
    val thinking: String? = null,
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null,
    /** tool_use 块：调用 id / 工具名 / 参数对象（仅解析响应时出现） */
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null
)

/** anthropic 的工具定义：{name, description, input_schema} */
@Serializable
data class AnthropicTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
data class AnthropicMessage(val role: String, val content: List<AnthropicBlock>)

@Serializable
data class AnthropicRequest(
    val model: String,
    /** Anthropic 要求必填 */
    @SerialName("max_tokens") val maxTokens: Int,
    /** 系统提示词（预设提示词 + 大纲/资料），断点打在最后一块上即「缓存点1」 */
    val system: List<AnthropicBlock>? = null,
    val messages: List<AnthropicMessage>,
    val temperature: Float? = null,
    val stream: Boolean = false,
    /** 扩展思考：thinking.type = enabled + budget_tokens */
    val thinking: AnthropicThinking? = null,
    /** 可用工具列表 */
    val tools: List<AnthropicTool>? = null
)

@Serializable
data class AnthropicUsage(
    /** 未命中缓存的输入 token */
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    /** 本次从缓存读取（命中）的 token 数，即缓存命中率分子 */
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    /** 本次写入缓存的 token 数（首次会以 1.25 倍计价） */
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0
)

@Serializable
data class AnthropicResponse(
    val content: List<AnthropicBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
    val error: ApiError? = null
)

/** Anthropic 流式事件（SSE data: 行，事件类型在 JSON 的 type 字段里） */
@Serializable
data class AnthropicStreamEvent(
    val type: String = "",
    val delta: AnthropicDelta? = null,
    val message: AnthropicResponse? = null,
    val usage: AnthropicUsage? = null,
    val error: ApiError? = null,
    /** 内容块索引（tool_use 分片累积用） */
    val index: Int? = null,
    /** content_block_start 事件里的新块（type=tool_use 时带 id/name） */
    @SerialName("content_block") val contentBlock: AnthropicBlock? = null
)

@Serializable
data class AnthropicDelta(
    val type: String = "",
    val text: String? = null,
    val thinking: String? = null,
    /** message_delta 里携带的结束原因，max_tokens 表示被输出上限截断 */
    @SerialName("stop_reason") val stopReason: String? = null,
    /** input_json_delta 事件里的工具参数分片 */
    @SerialName("partial_json") val partialJson: String? = null
)

/**
 * 流式工具调用分片累积器：把各协议按索引/项 id 到达的 id、名称、参数分片拼成完整调用。
 * 参数只在流结束后再解析 JSON；工具参数绝不能混进正文或思考内容。
 */
class ToolCallAccumulator {
    private class Buffer(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())

    private val chat = LinkedHashMap<Int, Buffer>()
    private val responses = LinkedHashMap<String, Buffer>()
    private val anthropic = LinkedHashMap<Int, Buffer>()
    private var anonymousIndex = 0

    /** chat/completions 的 delta.tool_calls 分片 */
    fun chatDelta(index: Int?, id: String?, name: String?, arguments: String?) {
        val i = index ?: (chat.keys.maxOrNull()?.plus(1) ?: 0)
        val b = chat.getOrPut(i) { Buffer() }
        if (!id.isNullOrBlank()) b.id = id
        if (!name.isNullOrBlank()) b.name = name
        if (!arguments.isNullOrEmpty()) b.args.append(arguments)
    }

    /** responses 的 response.output_item.added */
    fun responsesAdded(itemId: String?, callId: String?, name: String?, arguments: String?) {
        val key = itemId ?: callId ?: "item-${responses.size}"
        val b = responses.getOrPut(key) { Buffer() }
        if (!callId.isNullOrBlank()) b.id = callId
        if (!name.isNullOrBlank()) b.name = name
        if (!arguments.isNullOrEmpty()) b.args.append(arguments)
    }

    /** responses 的 response.function_call_arguments.delta */
    fun responsesDelta(itemId: String?, delta: String?) {
        if (delta.isNullOrEmpty()) return
        val key = itemId ?: responses.keys.lastOrNull() ?: return
        responses.getOrPut(key) { Buffer() }.args.append(delta)
    }

    /** anthropic 的 content_block_start（type=tool_use） */
    fun anthropicStart(index: Int?, id: String?, name: String?) {
        val i = index ?: anonymousIndex++
        val b = anthropic.getOrPut(i) { Buffer() }
        if (!id.isNullOrBlank()) b.id = id
        if (!name.isNullOrBlank()) b.name = name
    }

    /** anthropic 的 input_json_delta（partial_json 分片） */
    fun anthropicDelta(index: Int?, partialJson: String?) {
        if (partialJson.isNullOrEmpty()) return
        val i = index ?: anthropic.keys.lastOrNull() ?: return
        anthropic.getOrPut(i) { Buffer() }.args.append(partialJson)
    }

    fun build(): List<AiToolCall> =
        (chat.values + responses.values + anthropic.values)
            .filter { it.name.isNotBlank() }
            .mapIndexed { i, b ->
                AiToolCall(
                    id = b.id.ifBlank { "call_$i" },
                    name = b.name,
                    arguments = b.args.toString().ifBlank { "{}" }
                )
            }
}