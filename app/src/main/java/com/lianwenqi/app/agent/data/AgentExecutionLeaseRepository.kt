package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.data.AppDatabase
import kotlinx.serialization.json.put

/** 当前执行租约快照 */
data class AgentExecutionLease(
    val taskId: Long,
    val owner: String,
    val leaseUntil: Long,
    val attempt: Int,
    val acquiredAt: Long
)

/**
 * F7 数据库级执行租约。
 *
 * 只解决“同一任务同一时刻只有一个执行者”，不替代工具调用与写入操作的幂等机制。
 * 所有占用 / 续租 / 释放都通过单条 UPDATE 原子完成，不依赖进程内 Mutex。
 */
class AgentExecutionLeaseRepository(private val db: AppDatabase) {

    private val dao = db.agentTaskDao()
    private val events = AgentEventWriter(dao)

    suspend fun acquire(
        taskId: Long,
        owner: String,
        processStartedAt: Long = 0L
    ): AgentExecutionLease? = db.withTransaction {
        require(owner.isNotBlank()) { "executionOwner 不能为空" }
        val now = System.currentTimeMillis()
        // 上一进程的心跳必然早于当前进程启动时间；即使未满 120 秒也视为失联。
        val staleBefore = maxOf(now - STALE_HEARTBEAT_MS, processStartedAt)
        val affected = dao.tryAcquireExecutionLease(
            id = taskId,
            owner = owner,
            leaseUntil = now + LEASE_TTL_MS,
            now = now,
            staleBefore = staleBefore
        )
        if (affected != 1) return@withTransaction null
        val task = dao.getTask(taskId) ?: return@withTransaction null
        events.append(
            taskId = taskId,
            eventType = AgentEventType.TASK_EXECUTION_LEASE_ACQUIRED,
            payloadJson = jsonPayload {
                put("owner", owner)
                put("attempt", task.executionAttempt)
                put("leaseMs", LEASE_TTL_MS)
            }
        )
        AgentExecutionLease(
            taskId = taskId,
            owner = owner,
            leaseUntil = task.executionLeaseUntil ?: (now + LEASE_TTL_MS),
            attempt = task.executionAttempt,
            acquiredAt = now
        )
    }

    /** 续租；返回 false 表示租约已丢失，执行者必须立即停止写任务状态 */
    suspend fun renew(taskId: Long, owner: String): Boolean {
        val now = System.currentTimeMillis()
        return dao.renewExecutionLease(
            id = taskId,
            owner = owner,
            leaseUntil = now + LEASE_TTL_MS,
            now = now
        ) == 1
    }

    suspend fun release(taskId: Long, owner: String) {
        dao.releaseExecutionLease(taskId, owner, System.currentTimeMillis())
    }

    suspend fun isHeld(taskId: Long, owner: String): Boolean {
        val now = System.currentTimeMillis()
        return dao.taskHeldByOwner(taskId, owner, now) != null
    }

    /**
     * 租约已过期 / 心跳过旧的 RUNNING 任务；用于启动与周期恢复扫描。
     *
     * [processStartedAt]：当前进程启动时间。上一进程留下的心跳必然早于它，
     * 即使租约时间还没到期，也视为执行者已死亡，保证应用重启后立即恢复而不是等满 TTL。
     */
    suspend fun interruptedRunningTasks(processStartedAt: Long = 0L): List<AgentTask> {
        val now = System.currentTimeMillis()
        val staleBefore = maxOf(now - STALE_HEARTBEAT_MS, processStartedAt)
        return dao.interruptedRunningTasks(now, staleBefore)
    }

    /** 记录一次租约过期；由恢复流程在重新取得租约前写入 */
    suspend fun recordExpired(taskId: Long, previousOwner: String, attempt: Int): Unit =
        db.withTransaction {
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_EXECUTION_LEASE_EXPIRED,
                payloadJson = jsonPayload {
                    put("previousOwner", previousOwner)
                    put("attempt", attempt)
                }
            )
            Unit
        }

    companion object {
        /** 租约有效期；执行者每 15 秒续租一次 */
        const val LEASE_TTL_MS = 60_000L

        /** 心跳超过该时间视为执行者已失联，用于设备时间回拨兜底 */
        const val STALE_HEARTBEAT_MS = 120_000L

        const val HEARTBEAT_INTERVAL_MS = 15_000L
    }
}
