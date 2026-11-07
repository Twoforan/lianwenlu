package com.lianwenqi.app.data.volume

/**
 * 单次请求的容量规划结果。
 * 所有 Token 均为估算值，必须与真实 usage 分开展示。
 */
sealed interface CapacityPlan {
    data class Ready(
        val outputTokens: Int,
        val inputBudgetTokens: Int,
        val systemPromptTokens: Int,
        val safetyMarginTokens: Int
    ) : CapacityPlan

    data class TooSmall(val failure: CompressionFailure) : CapacityPlan
}

/**
 * 容量规划器：集中处理「上下文窗口 − 系统提示词 − 输出预留 − 安全余量」的计算，
 * 保证提取与整合使用同一套公式，并让边界条件可以单独测试。
 *
 * 绝不能把 `AiModel.maxContext`（历史消息条数）当作 Token 窗口；调用方只传真实
 * `contextWindow` 或明确的临时覆盖值。
 */
class CompressionCapacityPlanner(
    private val lengthPlanner: CompressionLengthPlanner = DefaultCompressionLengthPlanner,
    private val safetyMarginTokens: Int = SAFETY_MARGIN_TOKENS,
    private val perRequestOverheadTokens: Int = PER_REQUEST_OVERHEAD_TOKENS,
    private val minContextWindow: Int = MIN_CONTEXT_WINDOW,
    private val minCallOutputTokens: Int = MIN_CALL_OUTPUT_TOKENS,
    private val minExtractionOutputTokens: Int = MIN_EXTRACTION_OUTPUT_TOKENS,
    private val minInputBudgetTokens: Int = MIN_INPUT_BUDGET_TOKENS
) {

    /** 校验上下文窗口是否可用；0 / 负数视为未知，不猜测 */
    fun validateContextWindow(contextWindow: Int): CompressionFailure? = when {
        contextWindow <= 0 -> CompressionFailure.UnknownContextWindow
        contextWindow < minContextWindow -> CompressionFailure.ContextWindowTooSmall(
            contextWindow,
            "低于最低 $minContextWindow Token"
        )
        else -> null
    }

    /**
     * 分批提取的容量：输出预留取「长度规划、模型输出上限、上下文比例」三者的最小值，
     * 输入预算为窗口扣除输出、系统提示词、安全余量与请求包装后的剩余量。
     */
    fun extraction(
        contextWindow: Int,
        maxOutputTokens: Int,
        complexity: CompressionComplexity,
        systemPromptTokens: Int
    ): CapacityPlan {
        validateContextWindow(contextWindow)?.let { return CapacityPlan.TooSmall(it) }
        val outputCap = outputCap(maxOutputTokens, contextWindow)
        val target = lengthPlanner.plan(complexity, outputCap, contextWindow)
        val outputTokens = minOf(
            maxOf(target, minExtractionOutputTokens),
            outputCap,
            maxOf(minCallOutputTokens, contextWindow / 3)
        ).coerceAtLeast(minCallOutputTokens)
        return ready(contextWindow, outputTokens, systemPromptTokens)
    }

    /** 全卷整合的容量：输出预留使用最终长度规划，输入预算同样扣除全部固定开销 */
    fun merge(
        contextWindow: Int,
        maxOutputTokens: Int,
        complexity: CompressionComplexity,
        systemPromptTokens: Int
    ): CapacityPlan {
        validateContextWindow(contextWindow)?.let { return CapacityPlan.TooSmall(it) }
        val outputCap = outputCap(maxOutputTokens, contextWindow)
        val outputTokens = lengthPlanner.plan(complexity, outputCap, contextWindow)
            .coerceAtLeast(minCallOutputTokens)
        return ready(contextWindow, outputTokens, systemPromptTokens)
    }

    /** 模型输出上限；<=0 表示不限，此时用窗口的一半作为保守上限 */
    fun outputCap(maxOutputTokens: Int, contextWindow: Int): Int {
        val configured = if (maxOutputTokens > 0) maxOutputTokens else contextWindow / 2
        return minOf(configured, contextWindow / 2).coerceAtLeast(minCallOutputTokens)
    }

    /**
     * 以「单次最大提交字符数」为输入上限的提取容量。
     * 字符预算直接按 Token 估算值使用（中文约 1 字 ≈ 1 Token），不再依赖模型的上下文窗口配置。
     */
    fun extractionForInputBudget(
        inputBudgetTokens: Int,
        maxOutputTokens: Int,
        complexity: CompressionComplexity
    ): CapacityPlan {
        validateInputBudget(inputBudgetTokens)?.let { return CapacityPlan.TooSmall(it) }
        val outputCap = inputBudgetOutputCap(maxOutputTokens, inputBudgetTokens)
        val target = lengthPlanner.plan(complexity, outputCap, inputBudgetTokens * 2)
        val outputTokens = minOf(maxOf(target, minExtractionOutputTokens), outputCap)
            .coerceAtLeast(minCallOutputTokens)
        return CapacityPlan.Ready(
            outputTokens = outputTokens,
            inputBudgetTokens = inputBudgetTokens,
            systemPromptTokens = 0,
            safetyMarginTokens = safetyMarginTokens
        )
    }

    /** 以「单次最大提交字符数」为输入上限的整合容量 */
    fun mergeForInputBudget(
        inputBudgetTokens: Int,
        maxOutputTokens: Int,
        complexity: CompressionComplexity
    ): CapacityPlan {
        validateInputBudget(inputBudgetTokens)?.let { return CapacityPlan.TooSmall(it) }
        val outputCap = inputBudgetOutputCap(maxOutputTokens, inputBudgetTokens)
        val outputTokens = lengthPlanner.plan(complexity, outputCap, inputBudgetTokens * 2)
            .coerceAtLeast(minCallOutputTokens)
        return CapacityPlan.Ready(
            outputTokens = outputTokens,
            inputBudgetTokens = inputBudgetTokens,
            systemPromptTokens = 0,
            safetyMarginTokens = safetyMarginTokens
        )
    }

    /** 输出上限：不超过模型配置上限，也不超过单次输入预算的一半 */
    private fun inputBudgetOutputCap(maxOutputTokens: Int, inputBudgetTokens: Int): Int {
        val configured = if (maxOutputTokens > 0) maxOutputTokens else inputBudgetTokens / 2
        return minOf(configured, maxOf(inputBudgetTokens / 2, minCallOutputTokens))
            .coerceAtLeast(minCallOutputTokens)
    }

    private fun validateInputBudget(inputBudgetTokens: Int): CompressionFailure? = when {
        inputBudgetTokens <= 0 -> CompressionFailure.InputBudgetTooSmall(
            inputBudgetTokens,
            "未配置单次最大提交字符数"
        )
        inputBudgetTokens < minInputBudgetTokens -> CompressionFailure.InputBudgetTooSmall(
            inputBudgetTokens,
            "低于最低 $minInputBudgetTokens 字"
        )
        else -> null
    }

    /** 分级整合每一份中间结果的目标输出，保证下一轮输入确实收缩 */
    fun intermediateTarget(finalTarget: Int): Int = lengthPlanner.intermediateTarget(finalTarget)

    /**
     * 最终字数目标（按 1 字 ≈ 1 Token 折算）：用户设置优先，
     * 但不超过模型最大输出 Token；[targetOutputChars] <= 0 时沿用复杂度规划。
     */
    fun finalTargetForChars(targetOutputChars: Int, maxOutputTokens: Int, planned: Int): Int {
        if (targetOutputChars <= 0) return planned
        val cap = if (maxOutputTokens > 0) maxOutputTokens else targetOutputChars
        return targetOutputChars.coerceAtMost(cap).coerceAtLeast(minCallOutputTokens)
    }

    private fun ready(
        contextWindow: Int,
        outputTokens: Int,
        systemPromptTokens: Int
    ): CapacityPlan {
        val inputBudget = contextWindow - outputTokens - systemPromptTokens -
            safetyMarginTokens - perRequestOverheadTokens
        if (inputBudget <= 0) {
            return CapacityPlan.TooSmall(
                CompressionFailure.ContextWindowTooSmall(
                    contextWindow,
                    "扣除系统提示词与输出预算 ${outputTokens} Token 后没有可用的输入容量"
                )
            )
        }
        if (inputBudget < minInputBudgetTokens) {
            return CapacityPlan.TooSmall(
                CompressionFailure.ContextWindowTooSmall(
                    contextWindow,
                    "可用输入容量仅 $inputBudget Token，低于最低 $minInputBudgetTokens Token"
                )
            )
        }
        return CapacityPlan.Ready(
            outputTokens = outputTokens,
            inputBudgetTokens = inputBudget,
            systemPromptTokens = systemPromptTokens,
            safetyMarginTokens = safetyMarginTokens
        )
    }

    companion object {
        const val MIN_CONTEXT_WINDOW = 1024
        const val MIN_CALL_OUTPUT_TOKENS = 256
        const val MIN_EXTRACTION_OUTPUT_TOKENS = 1024
        const val MIN_INPUT_BUDGET_TOKENS = 128
        const val SAFETY_MARGIN_TOKENS = 256
        const val PER_REQUEST_OVERHEAD_TOKENS = 128
        const val CHAPTER_OVERHEAD_TOKENS = 24
    }
}
