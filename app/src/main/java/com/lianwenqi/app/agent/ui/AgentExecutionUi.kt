package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * F4 执行过程卡片。
 *
 * 节点与工具状态全部来自 Room 中的真实执行记录；不根据模型文字推断状态。
 */
@Composable
fun AgentExecutionCard(
    nodes: List<AgentExecutionNode>,
    toolExecutions: List<AgentToolExecution>,
    executing: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onDismissError: () -> Unit
) {
    if (nodes.isEmpty() && !executing && error == null) return

    var expandedTools by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val sortedNodes = nodes.sortedBy { it.sequence }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "执行过程",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "执行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (executing) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("执行中…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("取消执行") }
                }
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onDismissError) { Text("关闭提示") }
            }

            sortedNodes.forEach { node ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                AgentNodeRow(
                    node = node,
                    tools = toolExecutions.filter { it.nodeId == node.id },
                    expandedTools = expandedTools,
                    onToggleTool = { id ->
                        expandedTools = if (id in expandedTools) {
                            expandedTools - id
                        } else {
                            expandedTools + id
                        }
                    }
                )
            }

            val taskLevelTools = toolExecutions.filter { it.nodeId == null }
            if (taskLevelTools.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text("任务级工具调用", style = MaterialTheme.typography.labelSmall)
                taskLevelTools.forEach { tool ->
                    ToolExecutionRow(
                        tool = tool,
                        expanded = tool.id in expandedTools,
                        onToggle = {
                            expandedTools = if (tool.id in expandedTools) {
                                expandedTools - tool.id
                            } else {
                                expandedTools + tool.id
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentNodeRow(
    node: AgentExecutionNode,
    tools: List<AgentToolExecution>,
    expandedTools: Set<Long>,
    onToggleTool: (Long) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                nodeStatusSymbol(node.status),
                color = nodeStatusColor(node.status),
                modifier = Modifier.width(22.dp)
            )
            Text(
                node.title.ifBlank { node.nodeType },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.weight(1f))
            Text(
                nodeStatusLabel(node.status),
                style = MaterialTheme.typography.labelSmall,
                color = nodeStatusColor(node.status)
            )
        }
        if (node.description.isNotBlank()) {
            Text(
                node.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 22.dp)
            )
        }
        nodeSummary(node)?.let { summary ->
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp)
            )
        }
        if (node.errorMessage.isNotBlank()) {
            Text(
                node.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp)
            )
        }
        if (tools.isNotEmpty()) {
            tools.forEach { tool ->
                ToolExecutionRow(
                    tool = tool,
                    expanded = tool.id in expandedTools,
                    onToggle = { onToggleTool(tool.id) }
                )
            }
        }
    }
}

@Composable
private fun ToolExecutionRow(
    tool: AgentToolExecution,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                toolStatusSymbol(tool.status),
                color = toolStatusColor(tool.status),
                modifier = Modifier.width(18.dp)
            )
            Text(
                tool.toolName,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                toolStatusLabel(tool.status),
                style = MaterialTheme.typography.labelSmall,
                color = toolStatusColor(tool.status)
            )
            tool.durationMs?.let {
                Spacer(Modifier.width(6.dp))
                Text("${it}ms", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (expanded) {
            Text(
                "参数：${tool.argumentsJson.take(800)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (tool.resultJson.isNotBlank() && tool.resultJson != "{}") {
                Text(
                    "结果：${tool.resultJson.take(1_600)}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (tool.errorMessage.isNotBlank()) {
                Text(
                    "错误：${tool.errorMessage.take(800)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun nodeSummary(node: AgentExecutionNode): String? {
    val obj = runCatching { agentJson.parseToJsonElement(node.outputJson).jsonObject }
        .getOrNull() ?: return null
    val summary = obj["result"]?.jsonPrimitive?.contentOrNull
        ?: obj["summary"]?.jsonPrimitive?.contentOrNull
    return summary?.take(300)?.ifBlank { null }
}

private fun nodeStatusSymbol(status: String): String = when (status) {
    AgentNodeStatus.COMPLETED -> "✓"
    AgentNodeStatus.RUNNING -> "●"
    AgentNodeStatus.FAILED -> "✕"
    AgentNodeStatus.SKIPPED -> "–"
    AgentNodeStatus.CANCELLED -> "×"
    else -> "○"
}

private fun nodeStatusColor(status: String): Color = when (status) {
    AgentNodeStatus.COMPLETED -> Color(0xFF2E7D32)
    AgentNodeStatus.RUNNING -> Color(0xFF1565C0)
    AgentNodeStatus.FAILED -> Color(0xFFC62828)
    AgentNodeStatus.CANCELLED, AgentNodeStatus.SKIPPED -> Color(0xFF757575)
    else -> Color(0xFF9E9E9E)
}

private fun nodeStatusLabel(status: String): String = when (status) {
    AgentNodeStatus.PENDING -> "等待"
    AgentNodeStatus.RUNNING -> "执行中"
    AgentNodeStatus.WAITING -> "等待中"
    AgentNodeStatus.COMPLETED -> "完成"
    AgentNodeStatus.FAILED -> "失败"
    AgentNodeStatus.SKIPPED -> "跳过"
    AgentNodeStatus.CANCELLED -> "取消"
    else -> status
}

private fun toolStatusSymbol(status: String): String = when (status) {
    AgentToolStatus.SUCCEEDED -> "✓"
    AgentToolStatus.RUNNING -> "●"
    AgentToolStatus.FAILED -> "✕"
    AgentToolStatus.SKIPPED -> "–"
    AgentToolStatus.CANCELLED -> "×"
    AgentToolStatus.UNCERTAIN -> "?"
    else -> "○"
}

private fun toolStatusColor(status: String): Color = when (status) {
    AgentToolStatus.SUCCEEDED -> Color(0xFF2E7D32)
    AgentToolStatus.RUNNING -> Color(0xFF1565C0)
    AgentToolStatus.FAILED, AgentToolStatus.UNCERTAIN -> Color(0xFFC62828)
    AgentToolStatus.CANCELLED, AgentToolStatus.SKIPPED -> Color(0xFF757575)
    else -> Color(0xFF9E9E9E)
}

private fun toolStatusLabel(status: String): String = when (status) {
    AgentToolStatus.PENDING -> "等待"
    AgentToolStatus.RUNNING -> "执行中"
    AgentToolStatus.SUCCEEDED -> "成功"
    AgentToolStatus.FAILED -> "失败"
    AgentToolStatus.SKIPPED -> "跳过"
    AgentToolStatus.CANCELLED -> "取消"
    AgentToolStatus.UNCERTAIN -> "状态未知"
    else -> status
}
