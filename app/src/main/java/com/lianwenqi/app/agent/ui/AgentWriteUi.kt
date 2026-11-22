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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.data.isStructuredOutlineContent
import com.lianwenqi.app.data.outlineFieldsForDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F6 写入操作卡片。
 *
 * 待确认操作与历史操作全部来自 Room；确认 / 拒绝 / 撤销按钮只负责发出本地操作，
 * 权限与执行状态由 AgentWriteExecutionService 决定。
 */
@Composable
fun AgentWriteOperationsCard(
    operations: List<AgentWriteOperation>,
    notice: String?,
    policy: String,
    onApprove: (Long) -> Unit,
    onReject: (Long) -> Unit,
    onApproveAllNormal: () -> Unit,
    onRollback: (Long) -> Unit,
    onOpen: (AgentWriteOperation) -> Unit,
    onOpenPolicy: () -> Unit,
    onDismissNotice: () -> Unit
) {
    if (operations.isEmpty() && notice == null) return
    val pending = operations.filter {
        it.executionStatus in AgentWriteOperationStatus.unresolved
    }
    val normalPending = pending.filter {
        it.riskLevel == AgentWriteRiskLevel.NORMAL_WRITE &&
            (it.executionStatus == AgentWriteOperationStatus.WAITING_CONFIRMATION ||
                it.executionStatus == AgentWriteOperationStatus.PROPOSED)
    }
    val history = operations.filter {
        it.executionStatus !in AgentWriteOperationStatus.unresolved
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "写入操作",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenPolicy) {
                    Text("权限：" + AgentWritePermissionPolicy.label(policy))
                }
            }
            if (notice != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismissNotice) { Text("关闭") }
                    }
                }
            }

            if (pending.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "等待确认（${pending.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                pending.forEach { operation ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    WriteOperationRow(
                        operation = operation,
                        onOpen = { onOpen(operation) },
                        onApprove = { onApprove(operation.id) },
                        onReject = { onReject(operation.id) }
                    )
                }
                if (policy == AgentWritePermissionPolicy.BATCH_CONFIRM &&
                    normalPending.size >= 2
                ) {
                    TextButton(onClick = onApproveAllNormal) {
                        Text("批量确认一般写入（${normalPending.size}）")
                    }
                }
            }

            if (history.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "操作记录",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                history.takeLast(20).forEach { operation ->
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    WriteOperationLogRow(
                        operation = operation,
                        onOpen = { onOpen(operation) },
                        onRollback = { onRollback(operation.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WriteOperationRow(
    operation: AgentWriteOperation,
    onOpen: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val waiting = operation.executionStatus == AgentWriteOperationStatus.WAITING_CONFIRMATION ||
        operation.executionStatus == AgentWriteOperationStatus.PROPOSED
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RiskBadge(operation.riskLevel)
            Spacer(Modifier.height(2.dp))
            Text(
                AgentWriteOperationType.label(operation.operationType),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                AgentWriteOperationStatus.label(operation.executionStatus),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(operation.executionStatus)
            )
        }
        Text(
            "${operation.bookTitleSnapshot} / ${operation.targetTitleSnapshot}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            operation.operationSummary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (operation.executionStatus == AgentWriteOperationStatus.EXECUTING) {
            Text("正在写入…", style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpen) { Text("查看预览") }
            if (waiting) {
                TextButton(onClick = onReject) { Text("拒绝") }
                if (operation.riskLevel == AgentWriteRiskLevel.HIGH_RISK_WRITE) {
                    Button(
                        onClick = onApprove,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text(confirmLabel(operation.operationType)) }
                } else {
                    TextButton(onClick = onApprove) { Text(confirmLabel(operation.operationType)) }
                }
            }
        }
    }
}

@Composable
private fun WriteOperationLogRow(
    operation: AgentWriteOperation,
    onOpen: () -> Unit,
    onRollback: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                AgentWriteOperationType.label(operation.operationType),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                AgentWriteOperationStatus.label(operation.executionStatus),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(operation.executionStatus)
            )
        }
        Text(
            "${operation.bookTitleSnapshot} / ${operation.targetTitleSnapshot} · " +
                formatTime(operation.updatedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (operation.errorMessage.isNotBlank()) {
            Text(
                operation.errorMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (operation.rollbackMessage.isNotBlank()) {
            Text(
                operation.rollbackMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpen) { Text("详情") }
            if (operation.executionStatus == AgentWriteOperationStatus.SUCCEEDED &&
                operation.reversible
            ) {
                TextButton(onClick = onRollback) { Text("撤销") }
            }
        }
    }
}

/** 单条写入操作的完整预览 / 日志详情 */
@Composable
fun AgentWritePreviewDialog(
    operation: AgentWriteOperation,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRollback: () -> Unit,
    onDismiss: () -> Unit
) {
    val pending = operation.executionStatus == AgentWriteOperationStatus.WAITING_CONFIRMATION ||
        operation.executionStatus == AgentWriteOperationStatus.PROPOSED
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(AgentWriteOperationType.label(operation.operationType))
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskBadge(operation.riskLevel)
                    Text(
                        "  " + AgentWriteOperationStatus.label(operation.executionStatus),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(operation.executionStatus)
                    )
                }
                Text(
                    "目标：${operation.bookTitleSnapshot} / ${operation.targetTitleSnapshot}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "发起：Agent 任务 #${operation.taskId} · 工具：${operation.toolName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    operation.operationSummary,
                    style = MaterialTheme.typography.bodySmall
                )
                operation.confirmedAt?.let {
                    Text(
                        "确认时间：${formatTime(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                operation.executedAt?.let {
                    Text(
                        "执行时间：${formatTime(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                operation.rolledBackAt?.let {
                    Text(
                        "撤销时间：${formatTime(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    buildString {
                        append(if (operation.reversible) "支持撤销" else "不支持撤销")
                        if (operation.operationType == AgentWriteOperationType.UPDATE_CHAPTER_CONTENT) {
                            append(" · 会覆盖原正文")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (operation.errorMessage.isNotBlank()) {
                    Text(
                        operation.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (operation.rollbackMessage.isNotBlank()) {
                    Text(
                        operation.rollbackMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (operation.operationType == AgentWriteOperationType.CREATE_OUTLINE_NODE) {
                    Text("新建内容", style = MaterialTheme.typography.labelMedium)
                    OutlineFieldsPreview(operation.proposedContent)
                } else if (operation.operationType == AgentWriteOperationType.UPDATE_OUTLINE_NODE) {
                    Text("修改后内容", style = MaterialTheme.typography.labelMedium)
                    OutlineFieldsPreview(operation.proposedContent)
                } else if (operation.operationType == AgentWriteOperationType.DELETE_OUTLINE_NODE) {
                    Text("将删除的内容", style = MaterialTheme.typography.labelMedium)
                    OutlineFieldsPreview(beforeContent(operation))
                } else if (operation.operationType ==
                    AgentWriteOperationType.CREATE_OUTLINE_CATEGORY
                ) {
                    Text("新分类", style = MaterialTheme.typography.labelMedium)
                    Text(
                        operation.proposedContent,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (operation.operationType ==
                    AgentWriteOperationType.DELETE_OUTLINE_CATEGORY
                ) {
                    Text(
                        "将删除该分类及其下全部大纲条目，且不可恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (operation.operationType == AgentWriteOperationType.MOVE_OUTLINE_NODE) {
                    Text("移动后内容", style = MaterialTheme.typography.labelMedium)
                    OutlineFieldsPreview(operation.proposedContent)
                } else {
                    Text("修改前后差异", style = MaterialTheme.typography.labelMedium)
                    AgentWriteDiffView(
                        before = beforeContent(operation),
                        after = operation.proposedContent
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "确认只对当前展示的目标和内容有效；目标或内容变化后需要重新确认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            when {
                pending -> Row {
                    TextButton(onClick = onReject) { Text("拒绝") }
                    if (operation.riskLevel == AgentWriteRiskLevel.HIGH_RISK_WRITE ||
                        operation.riskLevel == AgentWriteRiskLevel.DESTRUCTIVE
                    ) {
                        Button(
                            onClick = onApprove,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text(confirmLabel(operation.operationType)) }
                    } else {
                        TextButton(onClick = onApprove) {
                            Text(confirmLabel(operation.operationType))
                        }
                    }
                }

                operation.executionStatus == AgentWriteOperationStatus.SUCCEEDED &&
                    operation.reversible ->
                    TextButton(onClick = onRollback) { Text("撤销此操作") }

                else -> TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (pending || (operation.executionStatus == AgentWriteOperationStatus.SUCCEEDED &&
                    operation.reversible)
            ) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

/** 大纲字段预览：显示标签与其它字段分行展示，与普通聊天写入大纲的字段列表一致 */
@Composable
private fun OutlineFieldsPreview(content: String) {
    val fields = if (isStructuredOutlineContent(content)) {
        decodeOutlineFields(content)
    } else {
        outlineFieldsForDisplay("", content)
    }
    if (fields.isEmpty()) {
        Text(
            content,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        return
    }
    fields.forEach { (name, value) ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Text(
                name.ifBlank { "内容" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                value.ifBlank { "（空）" },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** 批量确认一般写入：只合并 NORMAL_WRITE，高风险必须逐项确认 */
@Composable
fun AgentWriteBatchConfirmDialog(
    operations: List<AgentWriteOperation>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val normal = operations.filter {
        it.riskLevel == AgentWriteRiskLevel.NORMAL_WRITE &&
            (it.executionStatus == AgentWriteOperationStatus.WAITING_CONFIRMATION ||
                it.executionStatus == AgentWriteOperationStatus.PROPOSED)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量确认一般写入") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "以下 ${normal.size} 项一般写入将一次确认；" +
                        "高风险写入不会包含在批量确认中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                normal.forEach { operation ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        AgentWriteOperationType.label(operation.operationType),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "${operation.bookTitleSnapshot} / ${operation.targetTitleSnapshot}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        operation.operationSummary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认 ${normal.size} 项一般写入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 权限策略选择 */
@Composable
fun AgentWritePolicyDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent 写入权限") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "计划确认不等于写入授权。权限策略只影响后续写入提议，不回溯已确认操作；" +
                        "高风险写入和破坏性操作始终逐次确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                PermissionOption(
                    value = AgentWritePermissionPolicy.ASK_EVERY_TIME,
                    current = current,
                    title = "每次操作都询问",
                    description = "默认策略；每一项写入都需要你确认。",
                    onSelect = onSelect
                )
                PermissionOption(
                    value = AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL,
                    current = current,
                    title = "一般写入自动执行",
                    description = "只有修改梗概、创建 / 修改大纲等一般写入自动执行；" +
                        "覆盖正文仍逐次确认。",
                    onSelect = onSelect
                )
                PermissionOption(
                    value = AgentWritePermissionPolicy.BATCH_CONFIRM,
                    current = current,
                    title = "一般写入批量确认",
                    description = "一般写入可以合并预览、一次确认；高风险仍单独确认。",
                    onSelect = onSelect
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

@Composable
private fun PermissionOption(
    value: String,
    current: String,
    title: String,
    description: String,
    onSelect: (String) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(
            selected = current == value,
            onClick = { onSelect(value) }
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------- 差异展示 ----------------

internal enum class DiffKind { SAME, ADDED, REMOVED }

internal data class DiffLine(val kind: DiffKind, val text: String)

@Composable
private fun AgentWriteDiffView(before: String, after: String) {
    val lines = buildAgentWriteDiff(before, after)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        lines.forEach { line ->
            val color = when (line.kind) {
                DiffKind.ADDED -> Color(0xFF2E7D32)
                DiffKind.REMOVED -> Color(0xFFC62828)
                DiffKind.SAME -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val prefix = when (line.kind) {
                DiffKind.ADDED -> "+ "
                DiffKind.REMOVED -> "- "
                DiffKind.SAME -> "  "
            }
            Text(
                prefix + line.text,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = color
            )
        }
        if (before.isBlank()) {
            Text(
                "（原内容为空）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 行级差异：先去掉公共前后缀，再对变化窗口做 LCS，避免大正文全量比较。
 * 最多展示 200 行变化窗口，超出部分明确截断。
 */
internal fun buildAgentWriteDiff(
    before: String,
    after: String,
    maxWindowLines: Int = 200
): List<DiffLine> {
    if (before == after) return listOf(DiffLine(DiffKind.SAME, before))
    if (before.isBlank()) return after.lines().map { DiffLine(DiffKind.ADDED, it) }
    if (after.isBlank()) return before.lines().map { DiffLine(DiffKind.REMOVED, it) }
    val beforeLines = before.lines()
    val afterLines = after.lines()
    var prefix = 0
    while (prefix < beforeLines.size && prefix < afterLines.size &&
        beforeLines[prefix] == afterLines[prefix]
    ) {
        prefix++
    }
    var suffix = 0
    while (suffix < beforeLines.size - prefix && suffix < afterLines.size - prefix &&
        beforeLines[beforeLines.size - 1 - suffix] == afterLines[afterLines.size - 1 - suffix]
    ) {
        suffix++
    }
    val beforeWindow = beforeLines.subList(prefix, beforeLines.size - suffix)
        .take(maxWindowLines)
    val afterWindow = afterLines.subList(prefix, afterLines.size - suffix)
        .take(maxWindowLines)
    val result = mutableListOf<DiffLine>()
    beforeLines.take(prefix).takeLast(3).forEach { result += DiffLine(DiffKind.SAME, it) }
    result += lcsDiff(beforeWindow, afterWindow)
    afterLines.takeLast(suffix).take(3).forEach { result += DiffLine(DiffKind.SAME, it) }
    if (beforeLines.size - suffix - prefix > maxWindowLines ||
        afterLines.size - suffix - prefix > maxWindowLines
    ) {
        result += DiffLine(DiffKind.SAME, "（差异预览已截断，仅展示前 $maxWindowLines 行变化）")
    }
    return result
}

private fun lcsDiff(before: List<String>, after: List<String>): List<DiffLine> {
    if (before.isEmpty()) return after.map { DiffLine(DiffKind.ADDED, it) }
    if (after.isEmpty()) return before.map { DiffLine(DiffKind.REMOVED, it) }
    val dp = Array(before.size + 1) { IntArray(after.size + 1) }
    for (i in before.indices.reversed()) {
        for (j in after.indices.reversed()) {
            dp[i][j] = if (before[i] == after[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }
    val result = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < before.size && j < after.size) {
        when {
            before[i] == after[j] -> {
                result += DiffLine(DiffKind.SAME, before[i])
                i++
                j++
            }

            dp[i + 1][j] >= dp[i][j + 1] -> {
                result += DiffLine(DiffKind.REMOVED, before[i])
                i++
            }

            else -> {
                result += DiffLine(DiffKind.ADDED, after[j])
                j++
            }
        }
    }
    while (i < before.size) result += DiffLine(DiffKind.REMOVED, before[i++])
    while (j < after.size) result += DiffLine(DiffKind.ADDED, after[j++])
    return result
}

// ---------------- 公共展示 ----------------

@Composable
private fun RiskBadge(risk: String) {
    val (label, color) = when (risk) {
        AgentWriteRiskLevel.HIGH_RISK_WRITE -> "高风险" to Color(0xFFC62828)
        AgentWriteRiskLevel.DESTRUCTIVE -> "破坏性" to Color(0xFFB71C1C)
        else -> "一般写入" to Color(0xFF2E7D32)
    }
    Surface(color = color.copy(alpha = 0.12f)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

private fun confirmLabel(operationType: String): String = when (operationType) {
    AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS -> "确认修改梗概"
    AgentWriteOperationType.UPDATE_CHAPTER_CONTENT -> "确认覆盖正文"
    AgentWriteOperationType.CREATE_OUTLINE_NODE -> "确认创建大纲节点"
    AgentWriteOperationType.UPDATE_OUTLINE_NODE -> "确认修改大纲节点"
    AgentWriteOperationType.DELETE_OUTLINE_NODE -> "确认删除大纲节点"
    AgentWriteOperationType.CREATE_OUTLINE_CATEGORY -> "确认新增大纲分类"
    AgentWriteOperationType.DELETE_OUTLINE_CATEGORY -> "确认删除大纲分类"
    AgentWriteOperationType.MOVE_OUTLINE_NODE -> "确认移动大纲节点"
    AgentWriteOperationType.CREATE_CHAPTER -> "确认新建章节"
    AgentWriteOperationType.DELETE_CHAPTER -> "确认删除章节"
    AgentWriteOperationType.CREATE_VOLUME -> "确认新建分卷"
    AgentWriteOperationType.DELETE_VOLUME -> "确认删除分卷"
    else -> "确认写入"
}

private fun statusColor(status: String): Color = when (status) {
    AgentWriteOperationStatus.SUCCEEDED -> Color(0xFF2E7D32)
    AgentWriteOperationStatus.FAILED,
    AgentWriteOperationStatus.CONFLICT,
    AgentWriteOperationStatus.REJECTED -> Color(0xFFC62828)

    AgentWriteOperationStatus.EXECUTING -> Color(0xFF1565C0)
    AgentWriteOperationStatus.ROLLED_BACK,
    AgentWriteOperationStatus.CANCELLED -> Color(0xFF757575)

    else -> Color(0xFFF57C00)
}

private fun formatTime(time: Long): String = runCatching {
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}.getOrDefault("")

/** 从 beforeSnapshot 中提取用于展示的原文；失败时返回空串 */
private fun beforeContent(operation: AgentWriteOperation): String {
    val raw = operation.beforeSnapshot
    return runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?: return ""
        when (operation.operationType) {
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS ->
                obj["summary"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content ?: ""

            AgentWriteOperationType.UPDATE_CHAPTER_CONTENT ->
                obj["content"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content ?: ""

            else -> {
                obj["content"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content ?: ""
            }
        }
    }.getOrDefault("")
}
