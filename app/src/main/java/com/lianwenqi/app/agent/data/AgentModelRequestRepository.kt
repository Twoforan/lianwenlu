package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentModelRequest
import com.lianwenqi.app.agent.data.entity.AgentModelRequestStatus
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.put

/**
 * F7 模型请求证据仓库。
 *
 * 供应商不支持严格幂等请求，本表不承诺“重复请求不会产生费用”，
 * 只保证本地可以判断请求是否开始、是否结束、是否处于不确定状态。
 */
class AgentModelRequestRepository(private val db: AppDatabase) {

    private val dao = db.agentTaskDao()
    private val events = AgentEventWriter(dao)

    /**
     * 记录一次即将发出的模型请求。
     * attempt 按「任务 + 节点 + 轮次」自动递增，requestKey 唯一。
     */
    suspend fun begin(
        taskId: Long,
        nodeId: Long,
        runId: String,
        round: Int,
        modelId: Long,
        modelName: String = ""
    ): AgentModelRequest = db.withTransaction {
        val attempt = dao.maxModelRequestAttempt(taskId, nodeId, round) + 1
        val key = "$runId:$nodeId:$round:$attempt"
        val now = System.currentTimeMillis()
        val request = AgentModelRequest(
            taskId = taskId,
            nodeId = nodeId,
            runId = runId,
            round = round,
            attempt = attempt,
            requestKey = key,
            status = AgentModelRequestStatus.STARTED,
            modelId = modelId,
            modelName = modelName,
            startedAt = now,
            createdAt = now,
            updatedAt = now
        )
        val id = dao.insertModelRequest(request)
        events.append(
            taskId = taskId,
            eventType = AgentEventType.MODEL_REQUEST_STARTED,
            nodeId = nodeId,
            payloadJson = jsonPayload {
                put("requestId", id)
                put("round", round)
                put("attempt", attempt)
                put("modelId", modelId)
            }
        )
        request.copy(id = id)
    }

    suspend fun succeed(
        id: Long,
        latencyMs: Long,
        responseChars: Int,
        toolCallCount: Int
    ): AgentModelRequest = finish(
        id = id,
        status = AgentModelRequestStatus.SUCCEEDED,
        latencyMs = latencyMs,
        responseChars = responseChars,
        toolCallCount = toolCallCount,
        failureKind = "",
        errorMessage = "",
        eventType = AgentEventType.MODEL_REQUEST_COMPLETED
    )

    suspend fun fail(
        id: Long,
        failureKind: String,
        errorMessage: String,
        latencyMs: Long? = null
    ): AgentModelRequest = finish(
        id = id,
        status = AgentModelRequestStatus.FAILED,
        latencyMs = latencyMs,
        responseChars = 0,
        toolCallCount = 0,
        failureKind = failureKind,
        errorMessage = errorMessage,
        eventType = AgentEventType.MODEL_REQUEST_FAILED
    )

    /** 标记响应已被消费：工具结果已落库，或最终文本已写入节点 */
    suspend fun markApplied(id: Long) {
        val request = dao.getModelRequest(id) ?: return
        if (request.applied) return
        dao.updateModelRequest(
            request.copy(
                applied = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun countUnappliedSucceeded(taskId: Long): Int =
        dao.countUnappliedSucceededRequests(taskId)

    /**
     * 恢复时把「响应已返回但未消费」的请求标记为 UNKNOWN，并记录重复请求风险。
     * 不保存响应正文，因此无法复用上一次响应。
     */
    suspend fun markUnappliedSucceededUnknown(taskId: Long): Int = db.withTransaction {
        val affected = dao.markUnappliedSucceededRequestsUnknown(
            taskId = taskId,
            message = "模型响应已返回但未保存处理结果；恢复后重新请求可能产生重复费用",
            now = System.currentTimeMillis()
        )
        if (affected > 0) {
            events.append(
                taskId = taskId,
                eventType = AgentEventType.MODEL_REQUEST_UNKNOWN,
                payloadJson = jsonPayload {
                    put("count", affected)
                    put("reason", "RESPONSE_NOT_APPLIED")
                    put("duplicateRequestPossible", true)
                }
            )
        }
        affected
    }

    suspend fun cancelStarted(taskId: Long) {
        dao.startedModelRequests(taskId).forEach {
            finish(
                id = it.id,
                status = AgentModelRequestStatus.CANCELLED,
                latencyMs = null,
                responseChars = it.responseChars,
                toolCallCount = it.toolCallCount,
                failureKind = "",
                errorMessage = "用户取消任务",
                eventType = AgentEventType.MODEL_REQUEST_FAILED
            )
        }
    }

    /**
     * 进程中断后仍为 STARTED 的请求：标记 UNKNOWN，并返回数量。
     * 数量大于 0 表示恢复后重新请求可能产生重复费用。
     */
    suspend fun markInterruptedUnknown(taskId: Long): Int = db.withTransaction {
        val now = System.currentTimeMillis()
        val message = "进程中断，无法确认供应商是否已处理该请求；重新请求可能产生重复费用"
        val affected = dao.markStartedModelRequestsUnknown(taskId, message, now)
        if (affected > 0) {
            events.append(
                taskId = taskId,
                eventType = AgentEventType.MODEL_REQUEST_UNKNOWN,
                payloadJson = jsonPayload {
                    put("count", affected)
                    put("duplicateRequestPossible", true)
                }
            )
        }
        affected
    }

    suspend fun requestsOfTask(taskId: Long): List<AgentModelRequest> =
        dao.modelRequestsOfTask(taskId)

    fun observeOfTask(taskId: Long): Flow<List<AgentModelRequest>> =
        dao.observeModelRequestsOfTask(taskId)

    suspend fun startedOfTask(taskId: Long): List<AgentModelRequest> =
        dao.startedModelRequests(taskId)

    suspend fun deleteForTask(taskId: Long) = dao.deleteModelRequestsForTask(taskId)

    suspend fun deleteForSession(sessionId: Long) =
        dao.deleteModelRequestsForSession(sessionId)

    private suspend fun finish(
        id: Long,
        status: String,
        latencyMs: Long?,
        responseChars: Int,
        toolCallCount: Int,
        failureKind: String,
        errorMessage: String,
        eventType: String
    ): AgentModelRequest = db.withTransaction {
        val request = dao.getModelRequest(id)
            ?: throw IllegalArgumentException("模型请求记录不存在：$id")
        val now = System.currentTimeMillis()
        val next = request.copy(
            status = status,
            completedAt = now,
            latencyMs = latencyMs ?: request.latencyMs,
            responseChars = responseChars,
            toolCallCount = toolCallCount,
            failureKind = failureKind,
            errorMessage = errorMessage.take(1_000),
            updatedAt = now
        )
        dao.updateModelRequest(next)
        events.append(
            taskId = request.taskId,
            eventType = eventType,
            nodeId = request.nodeId,
            payloadJson = jsonPayload {
                put("requestId", id)
                put("status", status)
                if (failureKind.isNotBlank()) put("failureKind", failureKind)
                if (latencyMs != null) put("latencyMs", latencyMs)
            }
        )
        next
    }
}
