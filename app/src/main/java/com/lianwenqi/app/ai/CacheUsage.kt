package com.lianwenqi.app.ai

/**
 * 一次响应的缓存统计。
 *
 * 语义（三态，必须严格区分）：
 * - [cachedTokens] = null：服务端没有返回可识别的缓存字段，或完全没有返回 usage；
 * - [cachedTokens] = 0：服务端明确报告本次缓存命中为 0；
 * - [cachedTokens] > 0：服务端报告的实际缓存命中 token 数。
 *
 * [cacheCreationTokens] 只表达「写入缓存」的量（Anthropic cache_creation_input_tokens），
 * 绝不并入 [cachedTokens]；[usageReported] 表示服务端是否返回了 usage 对象。
 * [cacheField] 记录实际解析成功的字段路径，仅用于诊断。
 */
data class CacheUsage(
    val cachedTokens: Int? = null,
    val usageReported: Boolean = false,
    val cacheField: String? = null,
    val cacheCreationTokens: Int? = null
)

/**
 * 各协议 usage → [CacheUsage] 的统一解析入口。
 *
 * 普通请求与流式请求共用这里的逻辑，避免两条链路对「字段缺失」的处理不一致。
 * 字段只按协议实际定义与已确认的供应商扩展解析，不做基于供应商名称的猜测。
 */
object CacheUsageParser {

    /** chat/completions：标准字段 + DeepSeek 扩展 + 部分中转站的兼容字段 */
    fun fromChatCompletion(usage: Usage?): CacheUsage {
        if (usage == null) return CacheUsage()
        val (tokens, field) = firstReported(
            usage.promptTokensDetails?.cachedTokens to FIELD_CHAT_PROMPT_DETAILS,
            usage.promptCacheHitTokens to FIELD_CHAT_CACHE_HIT,
            usage.inputTokensDetails?.cachedTokens to FIELD_CHAT_INPUT_DETAILS,
            usage.cachedTokens to FIELD_CHAT_CACHED
        )
        return CacheUsage(cachedTokens = tokens, usageReported = true, cacheField = field)
    }

    /** responses：标准 input_tokens_details + 兼容 chat 结构的中转站字段 */
    fun fromResponses(usage: ResponsesUsage?): CacheUsage {
        if (usage == null) return CacheUsage()
        val (tokens, field) = firstReported(
            usage.inputTokensDetails?.cachedTokens to FIELD_RESPONSES_INPUT_DETAILS,
            usage.promptTokensDetails?.cachedTokens to FIELD_RESPONSES_PROMPT_DETAILS,
            usage.cachedTokens to FIELD_RESPONSES_CACHED
        )
        return CacheUsage(cachedTokens = tokens, usageReported = true, cacheField = field)
    }

    /**
     * anthropic messages：只有 cache_read_input_tokens 是缓存「命中量」。
     * cache_creation_input_tokens 单独保留；二者字段缺失时保持 null。
     */
    fun fromAnthropic(usage: AnthropicUsage?): CacheUsage {
        if (usage == null) return CacheUsage()
        val read = usage.cacheReadInputTokens
        return CacheUsage(
            cachedTokens = read,
            usageReported = true,
            cacheField = if (read != null) FIELD_ANTHROPIC_CACHE_READ else null,
            cacheCreationTokens = usage.cacheCreationInputTokens
        )
    }

    private fun firstReported(vararg candidates: Pair<Int?, String>): Pair<Int?, String?> {
        candidates.forEach { (value, field) -> if (value != null) return value to field }
        return null to null
    }

    const val FIELD_CHAT_PROMPT_DETAILS = "usage.prompt_tokens_details.cached_tokens"
    const val FIELD_CHAT_CACHE_HIT = "usage.prompt_cache_hit_tokens"
    const val FIELD_CHAT_INPUT_DETAILS = "usage.input_tokens_details.cached_tokens"
    const val FIELD_CHAT_CACHED = "usage.cached_tokens"
    const val FIELD_RESPONSES_INPUT_DETAILS = "usage.input_tokens_details.cached_tokens"
    const val FIELD_RESPONSES_PROMPT_DETAILS = "usage.prompt_tokens_details.cached_tokens"
    const val FIELD_RESPONSES_CACHED = "usage.cached_tokens"
    const val FIELD_ANTHROPIC_CACHE_READ = "usage.cache_read_input_tokens"
}

/**
 * 流式响应的 usage 累积器：只接收携带 usage 的数据块，最后一个生效。
 *
 * - chat/completions：usage 通常出现在末尾的独立分片（choices 为空）；
 * - responses：usage 通常随 response.completed 的 response 对象返回；
 * - anthropic：message_start 给输入 / 缓存字段，message_delta 给输出字段，
 *   逐字段合并，后到的非空值覆盖旧值。
 *
 * 没有收到 usage 时保持 null，绝不根据正文或 prompt 长度推算缓存命中量。
 */
class StreamUsageAccumulator {
    var chatUsage: Usage? = null
        private set
    var responsesUsage: ResponsesUsage? = null
        private set
    var anthropicUsage: AnthropicUsage? = null
        private set

    fun onChatChunk(chunk: ChatCompletionChunk) {
        chunk.usage?.let { chatUsage = it }
    }

    fun onResponsesEvent(event: ResponsesStreamEvent) {
        event.response?.usage?.let { responsesUsage = it }
    }

    fun onAnthropicEvent(event: AnthropicStreamEvent) {
        val usage = event.message?.usage ?: event.usage ?: return
        anthropicUsage = mergeAnthropic(anthropicUsage, usage)
    }

    private fun mergeAnthropic(old: AnthropicUsage?, new: AnthropicUsage): AnthropicUsage =
        AnthropicUsage(
            inputTokens = new.inputTokens ?: old?.inputTokens,
            outputTokens = new.outputTokens ?: old?.outputTokens,
            cacheReadInputTokens = new.cacheReadInputTokens ?: old?.cacheReadInputTokens,
            cacheCreationInputTokens = new.cacheCreationInputTokens ?: old?.cacheCreationInputTokens
        )
}
