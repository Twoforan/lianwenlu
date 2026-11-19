package com.lianwenqi.app.agent.background

import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.write.AgentWriteExecutionService

/**
 * F7 用户任务控制入口。
 *
 * UI、通知按钮、恢复扫描都通过这里暂停 / 恢复 / 取消任务；
 * 不直接启动执行协程，也不绕过状态机。
 */
class AgentTaskController(
    private val tasks: AgentTaskRepository,
    private val nodes: AgentExecutionNodeRepository,
    private val sessions: AgentSessionRepository,
    private val events: AgentEventRepository,
    private val writes: AgentWriteOperationRepository,
    private val writeService: AgentWriteExecutionService,
    private val leases: AgentExecutionLeaseRepository,
    private val scheduler: AgentTaskScheduler
) {

    /** 开始 / 继续执行：只负责入队，重复入队由唯一 Work + 租约挡住 */
    suspend fun requestExecution(taskId: Long, reason: String = "USER"): Boolean {
        val task = tasks.getTask(taskId) ?: return false
        if (AgentTaskStatus.isTerminal(task.status)) return false
        if (task.status == AgentTaskStatus.CREATED || task.status == AgentTaskStatus.PLANNING) {
            return false
        }
        if (task.status == AgentTaskStatus.PAUSED) return false
        if (task.status == AgentTaskStatus.WAITING_CONFIRMATION) {
            if (!planConfirmed(taskId)) return false
            if (writes.hasUnresolved(taskId)) return false
        }
        return runCatching {
            scheduler.enqueue(taskId, reason)
            true
        }.getOrDefault(false)
    }

    suspend fun pause(taskId: Long): Boolean {
        val task = tasks.getTask(taskId) ?: return false
        if (AgentTaskStatus.isTerminal(task.status)) return false
        if (!tasks.requestPause(taskId)) return false
        if (task.status == AgentTaskStatus.RUNNING && hasActiveLease(task)) {
            // 取消 Work 会触发协程取消；执行器看到 pauseRequested 后落为 PAUSED。
            scheduler.cancelScheduled(taskId)
            return true
        }
        if (task.status == AgentTaskStatus.RUNNING && !hasActiveLease(task)) {
            parkRunningNodes(taskId)
            tasks.updateStatus(taskId, AgentTaskStatus.PAUSED)
        }
        return true
    }

    suspend fun resume(taskId: Long): Boolean {
        val task = tasks.getTask(taskId) ?: return false
        if (AgentTaskStatus.isTerminal(task.status)) return false
        if (!tasks.requestResume(taskId)) return false
        if (task.status == AgentTaskStatus.WAITING_CONFIRMATION) {
            if (!planConfirmed(taskId)) return false
            if (writes.hasUnresolved(taskId)) return false
        }
        return runCatching {
            scheduler.enqueue(taskId, "USER_RESUME")
            true
        }.getOrDefault(false)
    }

    /**
     * 取消任务。
     *
     * 先写持久化取消标记，再取消 Work；执行器协程取消时会读取标记并落为 CANCELLED。
     * 没有活跃执行者时直接完成状态迁移，避免任务永久停在 RUNNING。
     */
    suspend fun cancel(taskId: Long): Boolean {
        val task = tasks.getTask(taskId) ?: return false
        if (AgentTaskStatus.isTerminal(task.status)) return false
        tasks.setCancelRequested(taskId, true)
        if (task.status == AgentTaskStatus.RUNNING && hasActiveLease(task)) {
            scheduler.cancelScheduled(taskId)
            return true
        }
        finalizeCancel(task)
        return true
    }

    /** 用户逐项处理完待确认写入后，继续任务；仍有未解决操作时不继续 */
    suspend fun continueAfterWriteDecision(taskId: Long): Boolean {
        val task = tasks.getTask(taskId) ?: return false
        if (task.status != AgentTaskStatus.WAITING_CONFIRMATION) return false
        if (!planConfirmed(taskId)) return false
        if (writes.hasUnresolved(taskId)) return false
        return runCatching {
            scheduler.enqueue(taskId, "WRITE_DECISION")
            true
        }.getOrDefault(false)
    }

    suspend fun planConfirmed(taskId: Long): Boolean =
        events.eventsOfType(taskId, AgentEventType.CONFIRMATION_RECEIVED).isNotEmpty()

    private suspend fun finalizeCancel(task: AgentTask) {
        writeService.cancelPending(task.id, "用户取消任务")
        task.currentNodeId?.let { nodeId ->
            runCatching {
                nodes.updateStatus(nodeId, AgentNodeStatus.CANCELLED, errorMessage = "用户取消")
            }
        }
        tasks.updateExecutionCursor(task.id, task.executionCursor, task.currentNodeId)
        runCatching { tasks.updateStatus(task.id, AgentTaskStatus.CANCELLED, "用户取消") }
        runCatching {
            sessions.addMessage(
                sessionId = task.sessionId,
                taskId = task.id,
                role = AgentMessageRole.ASSISTANT,
                kind = AgentMessageKind.NOTICE,
                content = "任务已取消，已完成的步骤结果保留；已成功的写入不会自动撤销。"
            )
        }
    }

    private suspend fun parkRunningNodes(taskId: Long) {
        nodes.interruptedNodes(taskId).forEach { node ->
            runCatching {
                nodes.updateStatus(node.id, AgentNodeStatus.WAITING, errorMessage = "用户暂停")
            }
        }
    }

    private fun hasActiveLease(task: AgentTask): Boolean {
        val now = System.currentTimeMillis()
        val heartbeatFresh = (task.lastHeartbeatAt ?: 0L) >
            (now - AgentExecutionLeaseRepository.STALE_HEARTBEAT_MS)
        return task.executionOwner.isNotBlank() &&
            (task.executionLeaseUntil ?: 0L) > now &&
            heartbeatFresh
    }
}
