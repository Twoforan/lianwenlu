package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentEvent
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Agent 事件仓库（追加写入）。
 *
 * 事件序号在事务内生成；不提供更新 / 删除单条事件的 API，
 * 会话删除时由 [AgentSessionRepository] 统一清理。
 */
class AgentEventRepository(private val db: AppDatabase) {

    private val dao = db.agentTaskDao()
    private val writer = AgentEventWriter(dao)

    /** 追加单条事件；任务不存在时抛异常，payload 必须是合法 JSON 对象 */
    suspend fun append(
        taskId: Long,
        eventType: String,
        nodeId: Long? = null,
        toolExecutionId: Long? = null,
        payloadJson: String = "{}"
    ): Long = db.withTransaction {
        requireTask(taskId)
        writer.append(taskId, eventType, nodeId, toolExecutionId, payloadJson).id
    }

    /** 批量追加：同一事务内按传入顺序生成连续序号；任一 payload 非法时整批不写入 */
    suspend fun appendAll(taskId: Long, events: List<AgentEventDraft>): List<Long> {
        events.forEach {
            require(it.eventType.isNotBlank()) { "eventType 不能为空" }
            requireJsonObject(it.payloadJson, "payloadJson")
        }
        return db.withTransaction {
            requireTask(taskId)
            events.map { draft ->
                writer.append(
                    taskId = taskId,
                    eventType = draft.eventType,
                    nodeId = draft.nodeId,
                    toolExecutionId = draft.toolExecutionId,
                    payloadJson = draft.payloadJson
                ).id
            }
        }
    }

    suspend fun eventsOfTask(taskId: Long): List<AgentEvent> = dao.eventsOfTask(taskId)

    fun observeEvents(taskId: Long): Flow<List<AgentEvent>> = dao.observeEventsOfTask(taskId)

    /** 增量事件：只返回 sequence 严格大于 [sequence] 的事件 */
    suspend fun eventsAfter(taskId: Long, sequence: Long): List<AgentEvent> =
        dao.eventsAfter(taskId, sequence)

    suspend fun latestEvent(taskId: Long): AgentEvent? = dao.latestEvent(taskId)

    /** 最新事件序号；没有任何事件时返回 -1 */
    suspend fun latestSequence(taskId: Long): Long = dao.latestEventSequence(taskId)

    suspend fun eventsOfType(taskId: Long, eventType: String): List<AgentEvent> =
        dao.eventsOfType(taskId, eventType)

    private suspend fun requireTask(taskId: Long) {
        require(dao.getTask(taskId) != null) { "Agent 任务不存在：$taskId" }
    }
}
