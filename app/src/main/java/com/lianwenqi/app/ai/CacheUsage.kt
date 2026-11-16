package com.lianwenqi.app.ai

/**
 * 一次响应的缓存统计。
 *
 * 语义（三态，必须严格区分）：
 * - [cachedTokens] = null：服务端没有返回可识别的缓存字段，或完全没有返回 usage；
 * - [cachedTokens] = 0：服务端明确报告本次缓存命中为 0；
 * - [cachedTokens] > 0：服务端报告的实际缓存命中 token 数。
 *
 * [promptTokens] 是命中率的分母（本次请求的总输入 token），同样是三态：
 * - null：服务端没有提供可用的输入 token（不允许据此计算命中率）；
 * - 0：服务端明确报告输入为 0；
 * - > 0：服务端报告的真实输入 token。
 *
 * [cacheCreationTokens] 只表达「写入缓存」的量（Anthropic cache_creation_input_tokens），
 * 绝不并入 [cachedTokens]；[usageReported] 表示服务端是否返回了 usage 对象。
 * [cacheField] 记录实际解析成功的字段路径；[cacheConflicts] 记录同时出现且数值不一致的
 * 候选字段（只含字段名与数值，用于诊断，不改变既有优先级）。
 */
data class CacheUsage(
    val cachedTokens: Int? = null,
    val usageReported: Boolean = false,
    val cacheField: String? = null,
    val cacheCreationTokens: Int? = null,
    /** 命中率分母：服务端报告的总输入 token；null 表示未提供，禁止用 0 补 */
    val promptTokens: Int? = null,
    /** 服务端报告的输出 token；null 表示未提供（本地估算值不得填到这里） */
    val completionTokens: Int? = null,
    /** 多个缓存字段同时存在但数值不一致时的诊断信息（字段名=数值） */
    val cacheConflicts: List<String> = emptyList()
)

/** 命中率的展示状态；未知与「口径不完整」必须和「明确 0」区分开 */
enum class CacheRateState {
    /** 没有可统计的请求 */
    NO_REQUEST,

    /** 供应商没有提供缓存字段 */
    UNKNOWN,

    /** 供应商提供了缓存量，但输入分母未知（不允许计算百分比） */
    INCOMPLETE,

    /** 缓存量大于输入量，上游 usage 自相矛盾 */
    ANOMALY,

    /** 明确未命中 */
    ZERO,

    /** 正常命中 */
    HIT
}

data class CacheRate(val state: CacheRateState, val percent: Int? = null)

/**
 * 命中率计算：全应用唯一入口，避免各处自行除法导致口径分叉。
 *
 * 负数视为数据库哨兵（未知），与 null 等价；绝不把未知当 0。
 */
object CacheRateCalculator {

    fun of(promptTokens: Int?, cachedTokens: Int?): CacheRate {
        val prompt = promptTokens?.takeIf { it >= 0 }
        val cached = cachedTokens?.takeIf { it >= 0 }
        if (cached == null) return CacheRate(CacheRateState.UNKNOWN)
        if (prompt == null || prompt <= 0) return CacheRate(CacheRateState.INCOMPLETE)
        if (cached > prompt) return CacheRate(CacheRateState.ANOMALY)
        if (cached == 0) return CacheRate(CacheRateState.ZERO, 0)
        // 用 Long 计算，避免超大上下文下 cached * 100 溢出 Int
        return CacheRate(CacheRateState.HIT, (cached.toLong() * 100 / prompt).toInt())
    }

    /**
     * 聚合口径：只要存在「有 usage 但无缓存字段」的请求，就不给出百分比；
     * 存在「有 usage 但无输入 token」的请求时标记为口径不完整。
     */
    fun ofAggregate(
        promptTokens: Int,
        cachedTokens: Int,
        requestCount: Int,
        requestsWithUsage: Int,
        unknownCacheRequests: Int,
        unknownPromptRequests: Int
    ): CacheRate {
        if (requestCount <= 0) return CacheRate(CacheRateState.NO_REQUEST)
        if (requestsWithUsage <= 0) return CacheRate(CacheRateState.UNKNOWN)
        if (unknownCacheRequests > 0) return CacheRate(CacheRateState.UNKNOWN)
        if (unknownPromptRequests > 0) return CacheRate(CacheRateState.INCOMPLETE)
        // 旧版本记录可能只有 0/0（没有可用的分母与分子），按「暂无供应商数据」展示
        if (promptTokens <= 0 && cachedTokens <= 0) return CacheRate(CacheRateState.UNKNOWN)
        return of(promptTokens, cachedTokens)
    }
}

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
        val (tokens, field, conflicts) = firstReported(
            usage.promptTokensDetails?.cachedTokens to FIELD_CHAT_PROMPT_DETAILS,
            usage.promptCacheHitTokens to FIELD_CHAT_CACHE_HIT,
            usage.inputTokensDetails?.cachedTokens to FIELD_CHAT_INPUT_DETAILS,
            usage.cachedTokens to FIELD_CHAT_CACHED
        )
        return CacheUsage(
            cachedTokens = tokens,
            usageReported = true,
            cacheField = field,
            promptTokens = usage.promptTokens,
            completionTokens = usage.completionTokens,
            cacheConflicts = conflicts
        )
    }

    /** responses：标准 input_tokens_details + 兼容 chat 结构的中转站字段 */
    fun fromResponses(usage: ResponsesUsage?): CacheUsage {
        if (usage == null) return CacheUsage()
        val (tokens, field, conflicts) = firstReported(
            usage.inputTokensDetails?.cachedTokens to FIELD_RESPONSES_INPUT_DETAILS,
            usage.promptTokensDetails?.cachedTokens to FIELD_RESPONSES_PROMPT_DETAILS,
            usage.cachedTokens to FIELD_RESPONSES_CACHED
        )
        return CacheUsage(
            cachedTokens = tokens,
            usageReported = true,
            cacheField = field,
            promptTokens = usage.inputTokens,
            completionTokens = usage.outputTokens,
            cacheConflicts = conflicts
        )
    }

    /**
     * anthropic messages：只有 cache_read_input_tokens 是缓存「命中量」。
     * cache_creation_input_tokens 单独保留；二者字段缺失时保持 null。
     *
     * 分母 = input_tokens + cache_read + cache_creation。
     * input_tokens 缺失时整个分母为 null（未知），绝不用 0 补，
     * 否则「纯缓存读取」会被错误算成 100%。
     */
    fun fromAnthropic(usage: AnthropicUsage?): CacheUsage {
        if (usage == null) return CacheUsage()
        val read = usage.cacheReadInputTokens
        val totalInput = usage.inputTokens?.let { input ->
            input + (usage.cacheReadInputTokens ?: 0) + (usage.cacheCreationInputTokens ?: 0)
        }
        return CacheUsage(
            cachedTokens = read,
            usageReported = true,
            cacheField = if (read != null) FIELD_ANTHROPIC_CACHE_READ else null,
            cacheCreationTokens = usage.cacheCreationInputTokens,
            promptTokens = totalInput,
            completionTokens = usage.outputTokens
        )
    }

    private fun firstReported(
        vararg candidates: Pair<Int?, String>
    ): Triple<Int?, String?, List<String>> {
        val present = candidates.mapNotNull { (value, field) ->
            value?.let { CacheFieldValue(field, it) }
        }
        val chosen = present.firstOrNull()
        // 保留既有优先级（第一个非空字段），只把冲突记录下来供诊断
        val conflicts = if (present.map { it.value }.distinct().size > 1) {
            listOf(present.joinToString(", ") { "${it.field}=${it.value}" })
        } else {
            emptyList()
        }
        return Triple(chosen?.value, chosen?.field, conflicts)
    }

    private data class CacheFieldValue(val field: String, val value: Int)

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
 * 流式响应的 usage 累积器。
 *
 * 三类协议的 usage 都是「累计快照」而不是增量：同一字段后到的值只会更大或相同。
 * 因此这里按字段做非空合并，并取较大值：
 * - 最终事件缺少字段时，保留之前已收到的有效值；
 * - 不把多个事件相加，避免重复累计；
 * - 不把增量语义的异常事件当成完整快照覆盖已有数据。
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
        chunk.usage?.let { chatUsage = mergeChat(chatUsage, it) }
    }

    fun onResponsesEvent(event: ResponsesStreamEvent) {
        event.response?.usage?.let { responsesUsage = mergeResponses(responsesUsage, it) }
    }

    fun onAnthropicEvent(event: AnthropicStreamEvent) {
        val usage = event.message?.usage ?: event.usage ?: return
        anthropicUsage = mergeAnthropic(anthropicUsage, usage)
    }

    private fun mergeChat(old: Usage?, new: Usage): Usage {
        if (old == null) return new
        return Usage(
            promptTokens = maxCounter(old.promptTokens, new.promptTokens),
            completionTokens = maxCounter(old.completionTokens, new.completionTokens),
            totalTokens = maxCounter(old.totalTokens, new.totalTokens),
            promptTokensDetails = mergeDetails(old.promptTokensDetails, new.promptTokensDetails),
            inputTokensDetails = mergeDetails(old.inputTokensDetails, new.inputTokensDetails),
            promptCacheHitTokens = maxCounter(old.promptCacheHitTokens, new.promptCacheHitTokens),
            cachedTokens = maxCounter(old.cachedTokens, new.cachedTokens)
        )
    }

    private fun mergeResponses(old: ResponsesUsage?, new: ResponsesUsage): ResponsesUsage {
        if (old == null) return new
        return ResponsesUsage(
            inputTokens = maxCounter(old.inputTokens, new.inputTokens),
            outputTokens = maxCounter(old.outputTokens, new.outputTokens),
            totalTokens = maxCounter(old.totalTokens, new.totalTokens),
            inputTokensDetails = mergeDetails(old.inputTokensDetails, new.inputTokensDetails),
            promptTokensDetails = mergeDetails(old.promptTokensDetails, new.promptTokensDetails),
            cachedTokens = maxCounter(old.cachedTokens, new.cachedTokens)
        )
    }

    private fun mergeAnthropic(old: AnthropicUsage?, new: AnthropicUsage): AnthropicUsage {
        if (old == null) return new
        return AnthropicUsage(
            inputTokens = maxCounter(old.inputTokens, new.inputTokens),
            outputTokens = maxCounter(old.outputTokens, new.outputTokens),
            cacheReadInputTokens = maxCounter(old.cacheReadInputTokens, new.cacheReadInputTokens),
            cacheCreationInputTokens = maxCounter(old.cacheCreationInputTokens, new.cacheCreationInputTokens)
        )
    }

    /** 累计快照只会增长；取较大值可避免「最后的占位 0」覆盖前面的有效值 */
    private fun maxCounter(old: Int?, new: Int?): Int? = when {
        old == null -> new
        new == null -> old
        else -> maxOf(old, new)
    }

    private fun mergeDetails(
        old: PromptTokensDetails?,
        new: PromptTokensDetails?
    ): PromptTokensDetails? {
        val oldCached = old?.cachedTokens
        val newCached = new?.cachedTokens
        return when {
            oldCached == null && newCached == null -> new ?: old
            oldCached == null -> new
            newCached == null -> old
            else -> PromptTokensDetails(maxOf(oldCached, newCached))
        }
    }
}
