package com.lianwenqi.app.agent.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.lianwenqi.app.MainActivity
import com.lianwenqi.app.R
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus

/** Agent 后台任务通知；只展示任务标题 / 目标摘要，不展示正文或提示词 */
object AgentTaskNotifications {

    const val CHANNEL_ID = "agent_task_execution"
    private const val CHANNEL_NAME = "Agent 任务执行"
    private const val NOTIFICATION_ID_BASE = 42_000

    fun notificationId(taskId: Long): Int =
        (NOTIFICATION_ID_BASE + (taskId % 1_000).toInt())

    fun foregroundInfo(context: Context, task: AgentTask): ForegroundInfo {
        ensureChannel(context)
        val notification = buildNotification(context, task)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId(task.id),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId(task.id), notification)
        }
    }

    private fun buildNotification(context: Context, task: AgentTask): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId(task.id),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = actionPendingIntent(context, task.id, AgentTaskActionReceiver.ACTION_PAUSE)
        val cancelIntent = actionPendingIntent(context, task.id, AgentTaskActionReceiver.ACTION_CANCEL)
        val statusText = when (task.status) {
            AgentTaskStatus.RUNNING -> "正在执行"
            AgentTaskStatus.WAITING_CONFIRMATION -> "等待确认"
            AgentTaskStatus.PAUSED -> "已暂停"
            else -> task.status
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_task)
            .setContentTitle("Agent 任务：$statusText")
            .setContentText(task.title.ifBlank { task.userGoal.take(60) })
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    buildString {
                        appendLine(task.title.ifBlank { "Agent 任务" })
                        appendLine("状态：$statusText")
                        append(task.userGoal.take(200))
                    }
                )
            )
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "暂停", pauseIntent)
            .addAction(0, "取消", cancelIntent)
            .build()
    }

    private fun actionPendingIntent(
        context: Context,
        taskId: Long,
        action: String
    ): PendingIntent {
        val intent = Intent(context, AgentTaskActionReceiver::class.java).apply {
            this.action = action
            putExtra(AgentTaskActionReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            (action.hashCode() * 31 + taskId).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示 Agent 任务的后台执行状态"
                setShowBadge(false)
            }
        )
    }
}
