package com.lianwenqi.app.agent.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Agent 任务调度抽象。
 *
 * UI / 恢复扫描只能通过本接口安排执行，不得直接启动第二个执行器。
 */
interface AgentTaskScheduler {
    suspend fun enqueue(taskId: Long, reason: String = "USER")
    suspend fun cancelScheduled(taskId: Long)
    suspend fun enqueueRecoveryScan(reason: String = "MANUAL")
    suspend fun ensurePeriodicRecovery()
}

/**
 * WorkManager 实现。
 *
 * 每个任务使用稳定唯一名称 `agent-task-<id>`，KEEP 策略防止重复 Worker；
 * 真正的并发保护仍由数据库执行租约负责。
 */
class WorkManagerAgentTaskScheduler(private val context: Context) : AgentTaskScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun enqueue(taskId: Long, reason: String) {
        if (taskId <= 0) return
        val scheduled = await {
            workManager.enqueueUniqueWork(
                workName(taskId),
                ExistingWorkPolicy.KEEP,
                taskRequest(taskId, reason)
            )
        }
        check(scheduled) { "WorkManager 调度失败，任务未入队" }
    }

    override suspend fun cancelScheduled(taskId: Long) {
        if (taskId <= 0) return
        await { workManager.cancelUniqueWork(workName(taskId)) }
    }

    override suspend fun enqueueRecoveryScan(reason: String) {
        await {
            workManager.enqueueUniqueWork(
                RECOVERY_SCAN_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<AgentTaskRecoveryWorker>()
                    .setInputData(workDataOf(AgentTaskRecoveryWorker.KEY_REASON to reason))
                    .addTag(RECOVERY_TAG)
                    .build()
            )
        }
    }

    override suspend fun ensurePeriodicRecovery() {
        await {
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_RECOVERY_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AgentTaskRecoveryWorker>(
                    PERIODIC_INTERVAL_MINUTES,
                    TimeUnit.MINUTES
                )
                    .addTag(RECOVERY_TAG)
                    .build()
            )
        }
    }

    /**
     * 等待 WorkManager 把任务真正写入自己的数据库并完成调度。
     * 不等待的话，进程在 enqueue 后立刻死亡可能丢失唯一 Work。
     */
    private suspend fun await(operation: () -> androidx.work.Operation): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                operation().result.get(10, TimeUnit.SECONDS)
                true
            }.getOrDefault(false)
        }

    private fun taskRequest(taskId: Long, reason: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInputData(
                workDataOf(
                    AgentTaskWorker.KEY_TASK_ID to taskId,
                    AgentTaskWorker.KEY_REASON to reason
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS
            )
            .addTag(TASK_TAG)
            .build()

    companion object {
        const val TASK_TAG = "agent-task"
        const val RECOVERY_TAG = "agent-recovery"
        const val RECOVERY_SCAN_WORK = "agent-recovery-scan"
        const val PERIODIC_RECOVERY_WORK = "agent-recovery-periodic"
        const val PERIODIC_INTERVAL_MINUTES = 15L

        fun workName(taskId: Long): String = "agent-task-$taskId"
    }
}
