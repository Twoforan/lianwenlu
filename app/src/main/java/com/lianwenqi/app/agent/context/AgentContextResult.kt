package com.lianwenqi.app.agent.context

/**
 * 一次上下文组装的结果。
 *
 * [promptBlock] 直接进入执行 Prompt；[items] 用于 UI 展示、
 * 落库任务引用快照的注入结果与测试断言。
 */
data class AgentContextResult(
    val taskId: Long,
    val items: List<AgentContextItem> = emptyList(),
    val promptBlock: String = "",
    val estimatedTokens: Int = 0,
    val usedChars: Int = 0,
    val budgetChars: Int = 0
) {
    val injectedCount: Int get() = items.count { it.injected }
    val truncatedCount: Int get() = items.count { it.status == AgentContextItemStatus.TRUNCATED }
    val invalidCount: Int get() = items.count { it.status == AgentContextItemStatus.INVALID }

    /** 供执行结束提示使用的简短摘要 */
    fun summaryLine(): String = when {
        items.isEmpty() -> "本次任务没有授权参考资料。"
        else -> "授权参考资料：注入 $injectedCount 条" +
            (if (truncatedCount > 0) "（截断 $truncatedCount 条）" else "") +
            (if (invalidCount > 0) "，失效 $invalidCount 条" else "") + "。"
    }

    companion object {
        fun empty(taskId: Long): AgentContextResult = AgentContextResult(taskId = taskId)
    }
}
