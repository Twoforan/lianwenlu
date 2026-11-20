package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentClarification
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentTask
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
    clarifications: List<AgentClarification> = emptyList(),
    executing: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onRetryNode: (Long) -> Unit = {},
    onEndTask: () -> Unit = {},
    onContinueWaiting: () -> Unit = {},
    onEndWaiting: () -> Unit = {},
    recoveryEnabled: Boolean = false,
    currentNodeId: Long? = null,
    /** 历史任务：只展示执行记录，不提供重试 / 结束等操作 */
    readOnly: Boolean = false,
    /** 用户主动点了「结束」：整个执行过程按「任务完成」展示 */
    endedByUser: Boolean = false
) {
    if (nodes.isEmpty() && !executing && error == null) return

    var expandedTools by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 只展示已经完成的节点和当前正在执行的节点；尚未开始的 PENDING 节点不占位
    val sortedNodes = nodes
        .filter { it.status != AgentNodeStatus.PENDING || it.id == currentNodeId }
        .sortedBy { it.sequence }

    var expanded by rememberSaveable { mutableStateOf(false) }

    val hasFailed = sortedNodes.any { it.status == AgentNodeStatus.FAILED }
    val hasWaiting = sortedNodes.any { it.status == AgentNodeStatus.WAITING }
    val allFinished = sortedNodes.isNotEmpty() && sortedNodes.all {
        it.status == AgentNodeStatus.COMPLETED || it.status == AgentNodeStatus.SKIPPED
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "执行过程",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (executing) {
                    TextButton(onClick = onCancel) { Text("取消执行") }
                } else {
                    // 节点级状态不再逐条写成对话消息，由卡片头部汇总展示
                    Text(
                        when {
                            endedByUser -> "任务完成"
                            hasFailed -> "执行失败"
                            hasWaiting -> "等待中"
                            allFinished -> "已完成"
                            else -> "${sortedNodes.size} 个节点"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasFailed && !endedByUser) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起执行记录" else "展开执行记录",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 折叠时汇总展示结束原因 / 失败原因，避免状态只剩一处标记
            if (!expanded && (endedByUser || hasFailed)) {
                val reason = if (endedByUser) {
                    AgentTask.USER_ENDED_REASON
                } else {
                    sortedNodes.firstOrNull { it.status == AgentNodeStatus.FAILED }
                        ?.errorMessage
                        ?.takeIf { it.isNotBlank() }
                }
                if (reason != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (endedByUser) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (expanded) {
                // 展开时完整展示全部节点，由聊天页统一滚动，不做卡片内部滑动
                Column(Modifier.fillMaxWidth()) {
                    if (executing) {
                        Spacer(Modifier.height(4.dp))
                        Text("执行中…", style = MaterialTheme.typography.bodySmall)
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
                        // 当前节点处于「等待中」时，提供继续 / 结束任务两个操作
                        val waitingCurrent = node.status == AgentNodeStatus.WAITING &&
                            node.id == currentNodeId
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
                            },
                            actionsEnabled = !executing && !endedByUser &&
                                (recoveryEnabled || waitingCurrent),
                            showActions = !readOnly && !endedByUser &&
                                (node.status == AgentNodeStatus.FAILED ||
                                waitingCurrent ||
                                (recoveryEnabled && node.id == currentNodeId)),
                            waitingActions = waitingCurrent,
                            onRetry = { node.id?.let(onRetryNode) },
                            onEnd = onEndTask,
                            onContinue = onContinueWaiting,
                            onEndWaiting = onEndWaiting
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

                    // 用户对提问的补充信息归入执行过程，不再作为用户对话展示
                    if (clarifications.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text("补充信息", style = MaterialTheme.typography.labelSmall)
                        clarifications.takeLast(6).forEach { clarification ->
                            Text(
                                "· ${clarification.answer.take(200)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // 展开后底部保留收起入口，长列表无需回到顶部
                TextButton(
                    onClick = { expanded = false },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("收起执行记录")
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
    onToggleTool: (Long) -> Unit,
    actionsEnabled: Boolean = false,
    showActions: Boolean = false,
    waitingActions: Boolean = false,
    onRetry: () -> Unit = {},
    onEnd: () -> Unit = {},
    onContinue: () -> Unit = {},
    onEndWaiting: () -> Unit = {}
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
        // 只有失败节点才展示错误文本；等待/执行中的节点可能残留上一轮的重试说明
        if (node.status == AgentNodeStatus.FAILED && node.errorMessage.isNotBlank()) {
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
        // 失败节点提供原地重试与结束任务；等待中的节点提供继续与结束任务
        if (showActions) {
            Row(
                Modifier.padding(start = 22.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (waitingActions) {
                    TextButton(onClick = onContinue, enabled = actionsEnabled) { Text("继续") }
                    TextButton(onClick = onEndWaiting, enabled = actionsEnabled) { Text("结束任务") }
                } else {
                    TextButton(onClick = onRetry, enabled = actionsEnabled) { Text("重试") }
                    TextButton(onClick = onEnd, enabled = actionsEnabled) { Text("结束") }
                }
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

/**
 * 节点产出只保留重要条目，让用户大致了解 Agent 做了什么。
 * 不展示长篇说明，也不把写入正文原样铺开。
 */
private fun nodeSummary(node: AgentExecutionNode): String? {
    val obj = runCatching { agentJson.parseToJsonElement(node.outputJson).jsonObject }
        .getOrNull() ?: return null
    val raw = obj["result"]?.jsonPrimitive?.contentOrNull
        ?: obj["summary"]?.jsonPrimitive?.contentOrNull
    if (raw.isNullOrBlank()) return null
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    // 优先保留标题 / 列表等条目，避免把整段正文当作执行摘要
    val keyItems = lines
        .filter { isKeyLine(it) }
        .map { it.trimStart('#', '-', '*', '•', '·', ' ').trim() }
        .filter { it.isNotEmpty() }
        .map { it.take(SUMMARY_ITEM_MAX_CHARS) }
        .distinct()
        .take(SUMMARY_MAX_ITEMS)
    val preview = if (keyItems.isNotEmpty()) {
        keyItems
    } else {
        listOf(lines.first().take(SUMMARY_ITEM_MAX_CHARS))
    }
    return preview.joinToString("\n")
}

/** 形如标题或列表的条目：Markdown 标题、无序列表、有序列表 */
private fun isKeyLine(line: String): Boolean {
    if (line.startsWith("#")) return true
    if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("•")) return true
    return line.length > 2 && line[0].isDigit() &&
        (line[1] == '.' || line[1] == '、' || line[1] == ')')
}

private const val SUMMARY_MAX_ITEMS = 3
private const val SUMMARY_ITEM_MAX_CHARS = 60

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
