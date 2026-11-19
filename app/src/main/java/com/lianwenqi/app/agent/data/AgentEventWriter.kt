package com.lianwenqi.app.agent.data

import com.lianwenqi.app.agent.data.dao.AgentTaskDao
import com.lianwenqi.app.agent.data.entity.AgentEvent

/** 一次待追加事件（由事件仓库批量接口使用） */
data class AgentEventDraft(
    val eventType: String,
    val nodeId: Long? = null,
    val toolExecutionId: Long? = null,
    val payloadJson: String = "{}"
)

/**
 * 事件序号生成器。
 *
 * 必须在调用方的 Room 事务内使用：序号取当前任务最大值 +1 后立即插入，
 * `(taskId, sequence)` 唯一索引兜底，避免并发写入产生重复序号。
 */
internal class AgentEventWriter(private val dao: AgentTaskDao) {

    suspend fun append(
        taskId: Long,
        eventType: String,
        nodeId: Long? = null,
        toolExecutionId: Long? = null,
        payloadJson: String = "{}"
    ): AgentEvent {
        require(eventType.isNotBlank()) { "eventType 不能为空" }
        requireJsonObject(payloadJson, "payloadJson")
        val sequence = dao.latestEventSequence(taskId) + 1
        val now = System.currentTimeMillis()
        val event = AgentEvent(
            taskId = taskId,
            sequence = sequence,
            eventType = eventType,
            nodeId = nodeId,
            toolExecutionId = toolExecutionId,
            payloadJson = payloadJson,
            createdAt = now
        )
        return event.copy(id = dao.insertEvent(event))
    }
}
