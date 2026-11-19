package com.lianwenqi.app.agent.execution

/** 一次只读执行的结果 */
sealed interface AgentExecutionResult {
    val taskId: Long

    data class Completed(
        override val taskId: Long,
        val summary: String
    ) : AgentExecutionResult

    data class Failed(
        override val taskId: Long,
        val message: String
    ) : AgentExecutionResult

    /** 启动前检查失败：状态不允许、未确认、含不可执行步骤、已有执行实例等 */
    data class Blocked(
        override val taskId: Long,
        val message: String
    ) : AgentExecutionResult

    data class Cancelled(override val taskId: Long) : AgentExecutionResult

    /** F7：用户暂停，执行器已保存游标 */
    data class Paused(override val taskId: Long) : AgentExecutionResult

    /** F7：等待用户确认写入；后台执行已停止，不会自动确认 */
    data class WaitingConfirmation(
        override val taskId: Long,
        val pendingCount: Int
    ) : AgentExecutionResult
}
