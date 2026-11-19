package com.lianwenqi.app.agent.context

import com.lianwenqi.app.data.volume.CompressionTokenEstimator

/**
 * 上下文预算。
 *
 * 单位是「字符」而不是精确 Token：模型上下文窗口不一定能可靠读取，
 * 因此使用保守的字符预算，所有 Token 仅为估算值。
 *
 * 预算只约束「用户授权参考资料」这一区段；
 * 当前任务目标、计划与当前工具结果由 F4 原有逻辑保留，不会被历史资料挤出。
 */
data class AgentContextBudget(
    /** 本次执行所有授权资料合计最多注入的字符数 */
    val maxReferenceChars: Int = 16_000,
    /** 单条资料默认最多注入的字符数 */
    val perReferenceChars: Int = 6_000,
    /** 单条章节正文最多注入的字符数（正文单独放宽） */
    val maxChapterContentChars: Int = 8_000,
    /** 低于该字符数时不再注入，避免只剩无意义的残片 */
    val minUsefulChars: Int = 200,
    /** 单条资料元信息（标题 / 来源定位）上限 */
    val metadataMaxBytes: Int = 4 * 1024,
    /** 模型上下文窗口；0 表示未知，此时仅使用字符预算 */
    val modelContextWindowTokens: Int = 0
) {
    fun estimateTokens(text: String): Int = CompressionTokenEstimator.estimate(text)

    fun maxCharsFor(sourceType: String): Int =
        if (sourceType == com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType.CHAPTER_CONTENT) {
            minOf(perReferenceChars, maxChapterContentChars)
        } else {
            perReferenceChars
        }

    companion object {
        /** 与 F4 工具结果预算相互独立；历史资料区段默认最多 16,000 字 */
        val DEFAULT = AgentContextBudget()
    }
}
