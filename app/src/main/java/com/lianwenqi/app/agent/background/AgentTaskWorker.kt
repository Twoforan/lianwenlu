package com.lianwenqi.app.agent.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.execution.AgentRunOutcome
import com.lianwenqi.app.appContainer
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * 唯一 Agent 执行 Worker。
 *
 * - 同一任务由唯一 Work 名称负责，重复入队被 KEEP 策略挡住；
 * - Worker 启动后由 AgentTaskRunner 再取得数据库执行租约；
 * - 临时失败返回 Result.retry()，永久失败返回 Result.failure()；
 * - 用户暂停 / 取消 / 等待确认都返回 success（由状态机决定后续）。
 */
class AgentTaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, 0L)
        if (taskId <= 0L) return Result.failure()
        val container = applicationContext.appContainer
        val task = container.agentTaskRepository.getTask(taskId)
            ?: return Result.success()
        if (AgentTaskStatus.isTerminal(task.status)) return Result.success()
        if (task.status == AgentTaskStatus.CREATED ||
            task.status == AgentTaskStatus.PLANNING ||
            task.status == AgentTaskStatus.PAUSED
        ) {
            return Result.success()
        }

        // 前台通知不是任务成功的保证；系统限制时继续尝试执行，但不超过 Worker 窗口。
        runCatching {
            setForeground(AgentTaskNotifications.foregroundInfo(applicationContext, task))
        }.onFailure {
            Log.w(TAG, "无法启动前台通知，任务继续在 WorkManager 窗口内执行", it)
        }

        val reason = inputData.getString(KEY_REASON) ?: "WORKER"
        val owner = UUID.randomUUID().toString()
        return try {
            val outcome = container.agentTaskRunner.run(
                taskId = taskId,
                owner = owner,
                reason = reason
            )
            when (outcome.type) {
                AgentRunOutcome.RETRY -> Result.retry()
                AgentRunOutcome.FAILED -> Result.failure()
                AgentRunOutcome.LEASE_LOST -> Result.failure()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            // 系统停止 / 用户取消 Work：任务状态由 Runner / Engine 的取消处理负责。
            if (isStopped) Result.retry() else throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Agent Worker 执行异常", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TASK_ID = "taskId"
        const val KEY_REASON = "reason"
        private const val TAG = "AgentTaskWorker"
    }
}
