package com.lianwenqi.app.agent.data.entity

/**
 * UI 选择历史 Agent 资料时的候选。
 *
 * [key] = sourceType:sourceId，用于多选去重；
 * 任务计划与任务结果共用同一个 taskId，但来源类型不同，因此不会互相覆盖。
 */
data class AgentMaterialCandidate(
    val key: String,
    val sourceType: String,
    val sourceId: Long,
    val title: String,
    val bookId: Long? = null
)
