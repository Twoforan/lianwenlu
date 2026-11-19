package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentModelRequest
import com.lianwenqi.app.agent.data.entity.AgentModelRequestStatus
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentRecoveryLog
import com.lianwenqi.app.agent.data.entity.AgentRecoveryResult
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** F7 任务状态展示：区分后台运行、恢复中、等待确认、恢复失败 */
fun agentTaskRuntimeStatusLabel(
    task: AgentTask,
    hasPendingWrites: Boolean
): String = when {
    task.status == AgentTaskStatus.RUNNING && hasActiveLease(task) ->
        if (task.executionAttempt > 1) "正在恢复" else "后台运行中"

    task.status == AgentTaskStatus.RUNNING -> "执行中"
    task.status == AgentTaskStatus.WAITING_CONFIRMATION && hasPendingWrites -> "等待用户确认"
    task.status == AgentTaskStatus.WAITING_CONFIRMATION -> "等待确认计划"
    task.status == AgentTaskStatus.PAUSED -> "已暂停"
    task.status == AgentTaskStatus.COMPLETED -> "已完成"
    task.status == AgentTaskStatus.FAILED ->
        if (task.recoveryCount > 0) "恢复失败" else "执行失败"

    task.status == AgentTaskStatus.CANCELLED -> "已取消"
    task.status == AgentTaskStatus.PLANNING -> "规划中"
    else -> "待规划"
}

private fun hasActiveLease(task: AgentTask): Boolean {
    val now = System.currentTimeMillis()
    val heartbeatFresh = (task.lastHeartbeatAt ?: 0L) > (now - 120_000L)
    return task.executionOwner.isNotBlank() &&
        (task.executionLeaseUntil ?: 0L) > now &&
        heartbeatFresh
}

@Composable
fun AgentTaskDetailDialog(
    task: AgentTask?,
    session: AgentSession?,
    bookTitle: String?,
    nodes: List<AgentExecutionNode>,
    writes: List<AgentWriteOperation>,
    recoveryLogs: List<AgentRecoveryLog>,
    modelRequests: List<AgentModelRequest>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onOpenInChat: () -> Unit,
    onDismiss: () -> Unit
) {
    if (task == null) return
    val stepNodes = nodes.filter { it.nodeType != "TASK_COMPLETE" }
    val completed = stepNodes.count { it.status == AgentNodeStatus.COMPLETED }
    val currentNode = task.currentNodeId?.let { id -> nodes.firstOrNull { it.id == id } }
    val pendingWrites = writes.filter {
        it.executionStatus in setOf(
            "PROPOSED", "WAITING_CONFIRMATION", "APPROVED", "EXECUTING"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("任务详情", fontWeight = FontWeight.Bold)
                Text(
                    agentTaskRuntimeStatusLabel(task, pendingWrites.isNotEmpty()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                DetailLine("任务目标", task.userGoal.take(500))
                DetailLine("所属会话", session?.title ?: "未知会话")
                DetailLine("关联书籍", bookTitle ?: "未绑定书籍")
                DetailLine(
                    "当前节点",
                    currentNode?.title?.ifBlank { currentNode.nodeType } ?: "无"
                )
                DetailLine("已完成节点", "$completed / ${stepNodes.size}")
                DetailLine("创建时间", formatTime(task.createdAt))
                DetailLine("最近更新", formatTime(task.updatedAt))
                DetailLine(
                    "最后执行",
                    task.lastExecutionAt?.let(::formatTime) ?: "尚未执行"
                )
                DetailLine("执行尝试", "${task.executionAttempt} 次")
                DetailLine("恢复次数", "${task.recoveryCount} 次")
                if (task.recoveryCount > 0) {
                    DetailLine(
                        "最近恢复",
                        task.lastRecoveryAt?.let(::formatTime) ?: "未记录"
                    )
                }
                if (task.errorMessage.isNotBlank()) {
                    DetailLine("最近错误", task.errorMessage.take(500))
                }
                DetailLine("待确认写入", "${pendingWrites.size} 个")

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("恢复日志", style = MaterialTheme.typography.titleSmall)
                if (recoveryLogs.isEmpty()) {
                    Text(
                        "暂无恢复日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    recoveryLogs.takeLast(5).reversed().forEach { log ->
                        RecoveryLogRow(log)
                    }
                }

                if (modelRequests.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("最近模型请求", style = MaterialTheme.typography.titleSmall)
                    modelRequests.takeLast(5).reversed().forEach { request ->
                        ModelRequestRow(request)
                    }
                    if (modelRequests.any { it.status == AgentModelRequestStatus.UNKNOWN }) {
                        Text(
                            "存在状态未知的模型请求：恢复后可能重新发送并产生额外费用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.status == AgentTaskStatus.RUNNING) {
                    TextButton(onClick = onPause) { Text("暂停") }
                }
                if (task.status == AgentTaskStatus.PAUSED) {
                    TextButton(onClick = onResume) { Text("恢复") }
                }
                if (!AgentTaskStatus.isTerminal(task.status)) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
                TextButton(onClick = onOpenInChat) { Text("进入任务") }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.status == AgentTaskStatus.FAILED ||
                    task.status == AgentTaskStatus.CANCELLED
                ) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun RecoveryLogRow(log: AgentRecoveryLog) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "#${log.attempt} ${log.reason.ifBlank { "恢复" }} · ${recoveryResultLabel(log.result)}",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${formatTime(log.startedAt)} · 游标 ${log.cursorBefore} → ${log.cursorAfter}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val flags = buildList {
            if (log.duplicateRequestPossible) add("可能重复请求")
            if (log.inconsistentDataFound) add("发现数据不一致")
            if (log.uncertainToolCount > 0) add("不确定工具 ${log.uncertainToolCount}")
        }
        if (flags.isNotEmpty()) {
            Text(
                flags.joinToString(" / "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (log.message.isNotBlank()) {
            Text(log.message, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ModelRequestRow(request: AgentModelRequest) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "第 ${request.round + 1} 轮 · 尝试 ${request.attempt} · ${request.status}",
            style = MaterialTheme.typography.labelSmall
        )
        if (request.failureKind.isNotBlank()) {
            Text(
                "${request.failureKind}：${request.errorMessage.take(200)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun recoveryResultLabel(value: String): String = when (value) {
    AgentRecoveryResult.SUCCEEDED -> "恢复成功"
    AgentRecoveryResult.FAILED -> "恢复失败"
    AgentRecoveryResult.WAITING_CONFIRMATION -> "等待确认"
    else -> "跳过"
}

private fun formatTime(value: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
