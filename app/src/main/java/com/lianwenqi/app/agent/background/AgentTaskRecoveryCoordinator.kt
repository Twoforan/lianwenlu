package com.lianwenqi.app.agent.background

import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.jsonPayload
import kotlinx.serialization.json.put

/**
 * F7 恢复扫描。
 *
 * 应用启动、周期 Worker、手动操作都会调用这里。
 * 只把“可恢复的 RUNNING 任务”重新入队：
 * - 租约有效 → 已有执行者，跳过；
 * - 租约过期 / 心跳过旧 → 入队，由 Worker 原子取得租约后再恢复；
 * - WAITING_CONFIRMATION / PAUSED / 终态 → 不自动恢复。
 */
class AgentTaskRecoveryCoordinator(
    private val tasks: AgentTaskRepository,
    private val leases: AgentExecutionLeaseRepository,
    private val writes: AgentWriteOperationRepository,
    private val events: AgentEventRepository,
    private val scheduler: AgentTaskScheduler
) {

    suspend fun reconcile(reason: String, processStartedAt: Long = 0L): Int {
        val candidates = leases.interruptedRunningTasks(processStartedAt)
        var scheduled = 0
        candidates.forEach { task ->
            if (AgentTaskStatus.isTerminal(task.status)) return@forEach
            if (task.status != AgentTaskStatus.RUNNING) return@forEach
            val ok = runCatching {
                scheduler.enqueue(task.id, "RECOVERY:$reason")
                true
            }.getOrDefault(false)
            if (ok) {
                scheduled++
                appendScheduleEventIfNeeded(task.id, reason)
            }
        }
        // RUNNING 但存在未解决写入的任务也入队一次：Runner 会把它们安全地停在等待确认状态。
        tasks.unfinishedTasks()
            .filter { it.status == AgentTaskStatus.RUNNING }
            .filter { writes.hasUnresolved(it.id) }
            .forEach { task ->
                if (candidates.none { it.id == task.id }) {
                    val ok = runCatching {
                        scheduler.enqueue(task.id, "RECOVERY_WAITING_WRITE:$reason")
                        true
                    }.getOrDefault(false)
                    if (ok) scheduled++
                }
            }
        return scheduled
    }

    private suspend fun appendScheduleEventIfNeeded(taskId: Long, reason: String) {
        val latest = runCatching { events.latestEvent(taskId) }.getOrNull()
        val now = System.currentTimeMillis()
        if (latest?.eventType == AgentEventType.TASK_RETRY_SCHEDULED &&
            now - latest.createdAt < SCHEDULE_EVENT_DEDUP_MS
        ) {
            return
        }
        runCatching {
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_RETRY_SCHEDULED,
                payloadJson = jsonPayload {
                    put("reason", reason.take(100))
                    put("source", "RECOVERY_SCAN")
                }
            )
        }
    }

    companion object {
        private const val SCHEDULE_EVENT_DEDUP_MS = 5 * 60 * 1000L
    }
}
