package com.lianwenqi.app.agent.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lianwenqi.app.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 通知栏「暂停 / 取消」按钮；只写数据库标记，由唯一执行器完成状态迁移 */
class AgentTaskActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        if (taskId <= 0L) return
        val action = intent.action ?: return
        val pendingResult = goAsync()
        val container = context.appContainer
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_PAUSE -> container.agentTaskController.pause(taskId)
                    ACTION_CANCEL -> container.agentTaskController.cancel(taskId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.lianwenqi.app.agent.action.PAUSE_TASK"
        const val ACTION_CANCEL = "com.lianwenqi.app.agent.action.CANCEL_TASK"
        const val EXTRA_TASK_ID = "taskId"
    }
}
