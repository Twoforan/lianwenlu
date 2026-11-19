package com.lianwenqi.app.agent.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lianwenqi.app.appContainer

/**
 * 周期 / 手动恢复扫描 Worker。
 *
 * 不执行具体任务，只把可恢复任务重新交给唯一 Work；
 * 具体恢复由 AgentTaskWorker + AgentTaskRunner 完成。
 */
class AgentTaskRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON) ?: "PERIODIC"
        return runCatching {
            val container = applicationContext.appContainer
            container.agentTaskRecoveryCoordinator.reconcile(
                reason = reason,
                processStartedAt = container.processStartedAt
            )
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        const val KEY_REASON = "reason"
    }
}
