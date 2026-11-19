package com.lianwenqi.app.agent.context

/** 一份授权资料在本次组装中的最终状态 */
enum class AgentContextItemStatus {
    /** 完整注入 */
    INJECTED,

    /** 受预算或单条上限限制，只注入了部分内容 */
    TRUNCATED,

    /** REFERENCE_ONLY：只登记，不进入上下文 */
    SKIPPED_REFERENCE_ONLY,

    /** 总预算不足，未注入 */
    SKIPPED_BUDGET,

    /** 来源已失效 / 未授权 / 类型不允许 */
    INVALID,

    /** 来源存在但内容为空 */
    EMPTY
}

/**
 * 上下文中的一份授权资料。
 *
 * [content] 只保存真正进入 Prompt 的部分；[contentTotalChars] 是来源完整长度。
 * 来源信息始终保留，便于模型与 UI 追溯。
 */
data class AgentContextItem(
    val referenceId: Long,
    val sourceType: String,
    val sourceId: Long,
    val bookId: Long? = null,
    val chapterId: Long? = null,
    val title: String,
    val bookTitle: String = "",
    val volumeTitle: String = "",
    val chapterTitle: String = "",
    val referenceMode: String,
    val status: AgentContextItemStatus,
    val statusMessage: String = "",
    val content: String = "",
    val contentStart: Int = 0,
    val contentChars: Int = 0,
    val contentTotalChars: Int = 0,
    val truncated: Boolean = false,
    val estimatedTokens: Int = 0,
    val sourceTimestamp: Long? = null,
    val historical: Boolean = true
) {
    val injected: Boolean
        get() = status == AgentContextItemStatus.INJECTED ||
            status == AgentContextItemStatus.TRUNCATED
}
