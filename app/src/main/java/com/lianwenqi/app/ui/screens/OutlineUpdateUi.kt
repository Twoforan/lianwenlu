package com.lianwenqi.app.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lianwenqi.app.data.OUTLINE_HEAD_LABEL
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.data.encodeOutlineFields
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.VolumeOutlineSuggestion
import com.lianwenqi.app.data.entity.VolumeOutlineUpdate
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.data.volume.OutlineApplySummary
import com.lianwenqi.app.data.volume.OutlineOperation
import com.lianwenqi.app.data.volume.OutlineScopeResolved
import com.lianwenqi.app.data.volume.OutlineSuggestionStatus
import com.lianwenqi.app.data.volume.OutlineUpdateStatus
import com.lianwenqi.app.data.volume.outlineContentIsFieldJson
import kotlinx.coroutines.launch

/**
 * 大纲差异预览。所有建议都已经过范围 / 冲突 / 格式校验；
 * 冲突与无效项可见但不可勾选，DELETE 默认不选且写入前需要二次确认。
 *
 * 建议默认折叠，点标题行展开后可逐条修改「新内容」；
 * 底部为确认 / 后台运行 / 放弃，右上角 X 与「后台运行」等价：分析在应用级作用域执行，
 * 关闭弹窗只是隐藏界面，不会中断正在进行的差异分析，结果会保存下来供稍后查看。
 */
@Composable
fun OutlineDiffDialog(
    sessionId: Long,
    update: VolumeOutlineUpdate?,
    suggestions: List<VolumeOutlineSuggestion>,
    onApply: suspend (List<Long>) -> OutlineApplySummary,
    onEditContent: suspend (Long, String) -> Unit,
    onRetry: () -> Unit,
    onLater: () -> Unit,
    onDiscard: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedIds by remember(update?.id) { mutableStateOf<Set<Long>>(emptySet()) }
    var applying by remember { mutableStateOf(false) }
    var result by remember(update?.id) { mutableStateOf<OutlineApplySummary?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var discarding by remember { mutableStateOf(false) }
    /** 展开编辑后尚未确认的草稿：suggestionId → 新内容 */
    val editedContents = remember(update?.id) { mutableStateMapOf<Long, String>() }

    fun commitAndApply() {
        applying = true
        scope.launch {
            // 先落库编辑结果，再按最新内容写入
            editedContents.toMap().forEach { (id, content) ->
                runCatching { onEditContent(id, content) }
            }
            editedContents.clear()
            result = onApply(selectedIds.toList())
            selectedIds = emptySet()
            applying = false
        }
    }


    LaunchedEffect(suggestions) {
        // 状态变化后（例如写入冲突 / 自动写入完成）自动移除已不可选的勾选
        selectedIds = selectedIds.filter { id ->
            suggestions.firstOrNull { it.id == id }?.status in OutlineSuggestionStatus.selectable
        }.toSet()
    }

    val selectable = suggestions.filter {
        it.status in OutlineSuggestionStatus.selectable
    }
    val safeSelectAll = selectable.filter {
        it.operation != OutlineOperation.DELETE && it.operation != OutlineOperation.UNKNOWN
    }
    val selectedDeletes = selectable.filter {
        it.id in selectedIds && it.operation == OutlineOperation.DELETE
    }
    val ready = update?.status == OutlineUpdateStatus.READY
    val analyzing = update == null || update.status == OutlineUpdateStatus.ANALYZING
    val failed = update?.status == OutlineUpdateStatus.FAILED

    AlertDialog(
        onDismissRequest = { if (!applying && !discarding) onLater() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("大纲差异预览", modifier = Modifier.weight(1f))
                IconButton(
                    enabled = !applying && !discarding,
                    onClick = onLater
                ) {
                    Icon(Icons.Default.Close, contentDescription = "后台运行")
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "只有你勾选并确认的条目才会写入；卷梗概的确认不等于同意修改大纲。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                if (update != null) {
                    val scopeCount = OutlineScopeResolved.decodeIds(update.scopeIds).size
                    Text(
                        "目标范围：${update.scopeLabel.ifBlank { "$scopeCount 条" }} · " +
                            "模式：${modeLabel(update.mode)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))

                when {
                    failed -> {
                        Text(
                            update?.errorMessage?.ifBlank { "大纲差异分析失败" } ?: "大纲差异分析失败",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    analyzing -> {
                        Text(
                            "正在分析大纲差异…结果会自动保存。可点右上角 × 或「后台运行」，" +
                                "分析会在后台继续，稍后回来查看即可。"
                        )
                    }

                    ready -> {
                        if (update?.note?.isNotBlank() == true) {
                            Text(
                                update.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (suggestions.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("本卷没有产生需要同步的大纲建议。")
                        } else {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    enabled = !applying && safeSelectAll.isNotEmpty(),
                                    onClick = {
                                        selectedIds = safeSelectAll
                                            .filter { it.status == OutlineSuggestionStatus.PENDING }
                                            .map { it.id }.toSet()
                                    }
                                ) { Text("全选（不含删除）") }
                                TextButton(
                                    enabled = !applying && selectedIds.isNotEmpty(),
                                    onClick = { selectedIds = emptySet() }
                                ) { Text("全部取消") }
                            }
                            Spacer(Modifier.height(4.dp))
                            suggestions.forEach { suggestion ->
                                OutlineSuggestionRow(
                                    suggestion = suggestion,
                                    checked = suggestion.id in selectedIds,
                                    enabled = !applying,
                                    draftContent = editedContents[suggestion.id],
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) {
                                            selectedIds + suggestion.id
                                        } else {
                                            selectedIds - suggestion.id
                                        }
                                    },
                                    onDraftChange = { content ->
                                        editedContents[suggestion.id] = content
                                    },
                                    onPersistDraft = { content ->
                                        onEditContent(suggestion.id, content)
                                    }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }

                if (result != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            buildString {
                                append("已写入 ").append(result!!.applied).append(" 条")
                                if (result!!.conflicts > 0) append("；冲突 ").append(result!!.conflicts).append(" 条")
                                if (result!!.invalid > 0) append("；无效 ").append(result!!.invalid).append(" 条")
                                if (result!!.failed > 0) append("；失败 ").append(result!!.failed).append(" 条")
                                if (result!!.messages.isNotEmpty()) {
                                    append("\n").append(result!!.messages.take(4).joinToString("\n"))
                                }
                            },
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (discarding) {
                    Text("正在放弃…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                failed -> TextButton(
                    enabled = !applying,
                    onClick = onRetry
                ) { Text("重新分析") }

                // 分析进行中：关闭弹窗不取消任务，等同于转到后台继续
                analyzing -> TextButton(
                    enabled = !applying && !discarding,
                    onClick = onLater
                ) { Text("后台运行") }

                else -> Button(
                    enabled = ready && !applying && !discarding && selectedIds.isNotEmpty(),
                    onClick = {
                        if (selectedDeletes.isNotEmpty()) {
                            confirmDelete = true
                        } else {
                            commitAndApply()
                        }
                    }
                ) {
                    Text(
                        when {
                            applying -> "正在写入…"
                            else -> "确认（${selectedIds.size}）"
                        }
                    )
                }
            }
        },
        dismissButton = {
            // 分析进行中只在 confirmButton 位置提供「后台运行」，避免出现两个相同按钮
            if (!analyzing) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 显式的后台运行入口：与右上角 X 行为一致，不取消正在进行的分析；
                    // 分析已失败时没有任务在跑，这里只是关闭弹窗
                    TextButton(
                        enabled = !applying && !discarding,
                        onClick = onLater
                    ) { Text(if (failed) "关闭" else "后台运行") }
                    if (ready || discarding) {
                        TextButton(
                            enabled = ready && !applying && !discarding,
                            onClick = {
                                discarding = true
                                scope.launch {
                                    onDiscard()
                                    discarding = false
                                    onLater()
                                }
                            }
                        ) { Text(if (discarding) "正在放弃…" else "放弃") }
                    }
                }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("确认删除 ${selectedDeletes.size} 条大纲？") },
            text = {
                Text(
                    selectedDeletes.joinToString("\n") { "· ${it.title.ifBlank { "条目 ${it.targetOutlineItemId}" }}" } +
                        "\n\n删除后无法从大纲中恢复。即使更新模式为「自动」，删除也必须由你在这里明确确认。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    commitAndApply()
                }) { Text("确认删除并写入") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun OutlineSuggestionRow(
    suggestion: VolumeOutlineSuggestion,
    checked: Boolean,
    enabled: Boolean,
    draftContent: String?,
    onCheckedChange: (Boolean) -> Unit,
    onDraftChange: (String) -> Unit,
    onPersistDraft: suspend (String) -> Unit
) {
    val selectable = suggestion.status in OutlineSuggestionStatus.selectable
    val editable = enabled && selectable && suggestion.operation != OutlineOperation.DELETE
    val color = operationColor(suggestion.operation)
    val rowScope = rememberCoroutineScope()
    var expanded by remember(suggestion.id) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // 收起时把未保存的草稿落库，保证「稍后处理」后回来还能看到修改
                        if (expanded) {
                            draftContent
                                ?.takeIf { it != suggestion.newContent }
                                ?.let { content -> rowScope.launch { onPersistDraft(content) } }
                        }
                        expanded = !expanded
                    }
                    .padding(start = 4.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    enabled = enabled && selectable,
                    onCheckedChange = onCheckedChange
                )
                Text(
                    OutlineOperation.label(suggestion.operation),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    suggestion.title.ifBlank { "（未命名）" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                Text(
                    buildString {
                        append(outlineCategoryLabel(suggestion.category))
                        append(" · 置信度 ").append((suggestion.confidence * 100).toInt()).append("%")
                        if (suggestion.operation == OutlineOperation.DELETE) {
                            append(" · 默认不选，需明确确认")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (suggestion.status != OutlineSuggestionStatus.PENDING) {
                    Text(
                        "${OutlineSuggestionStatus.label(suggestion.status)}：" +
                            suggestion.statusMessage.ifBlank {
                                if (suggestion.status == OutlineSuggestionStatus.APPLIED ||
                                    suggestion.status == OutlineSuggestionStatus.AUTO_APPLIED
                                ) "已写入" else "不可写入"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selectable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
                val fieldChanges = remember(suggestion.oldContent, suggestion.newContent) {
                    changedFieldNames(suggestion.oldContent, suggestion.newContent)
                }
                if (fieldChanges.isNotEmpty()) {
                    Text(
                        "更新字段：${fieldChanges.joinToString("、")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
                if (expanded) {
                    if (suggestion.reason.isNotBlank()) {
                        Text(
                            "理由：${suggestion.reason}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (suggestion.relatedEvents.isNotBlank()) {
                        Text(
                            "相关事件：${suggestion.relatedEvents.replace('\n', '、')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (suggestion.operation != OutlineOperation.DELETE) {
                        Spacer(Modifier.height(6.dp))
                        OutlineContentEditor(
                            suggestionId = suggestion.id,
                            oldContent = suggestion.oldContent,
                            initialDraft = draftContent ?: suggestion.newContent,
                            editable = editable,
                            onDraftChange = onDraftChange
                        )
                    }
                }
            }
        }
    }
}

/**
 * 展开后的可编辑内容：只展示本次真正变化的字段（含将被移除的字段），
 * 每个字段旁提供「还原」，把该字段恢复为旧值后不再写入；
 * 纯文本则回落到「修改前 / 修改后」单框编辑。
 */
@Composable
private fun OutlineContentEditor(
    suggestionId: Long,
    oldContent: String,
    initialDraft: String,
    editable: Boolean,
    onDraftChange: (String) -> Unit
) {
    if (outlineContentIsFieldJson(initialDraft)) {
        val oldFields = runCatching { decodeOutlineFields(oldContent) }.getOrNull().orEmpty()
        val initialFields = remember(suggestionId) {
            runCatching { decodeOutlineFields(initialDraft) }.getOrNull().orEmpty()
        }
        var fields by remember(suggestionId) { mutableStateOf(initialFields) }
        // 已还原字段的还原前取值；存在即表示该字段当前处于「已还原」状态
        val revertedValues = remember(suggestionId) { mutableStateMapOf<String, String?>() }
        // 变化字段集合在首次展开时固定，避免还原后该字段整行消失
        val changedLabels = remember(suggestionId) {
            (initialFields.map { it.first } + oldFields.map { it.first }).distinct()
                .filter { label ->
                    oldFields.firstOrNull { it.first == label }?.second !=
                        initialFields.firstOrNull { it.first == label }?.second
                }
        }
        if (changedLabels.isEmpty()) {
            Text(
                "本次没有字段变化。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }
        changedLabels.forEach { label ->
            val oldValue = oldFields.firstOrNull { it.first == label }?.second
            val current = fields.firstOrNull { it.first == label }?.second
            val reverted = revertedValues.containsKey(label)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                if (reverted) {
                    TextButton(
                        enabled = editable,
                        onClick = {
                            val previous = revertedValues.remove(label)
                            val next = fields.toMutableList()
                            val index = next.indexOfFirst { it.first == label }
                            when {
                                previous == null -> if (index >= 0) next.removeAt(index)
                                index >= 0 -> next[index] = label to previous
                                else -> next.add(label to previous)
                            }
                            fields = next
                            onDraftChange(encodeOutlineFields(next))
                        }
                    ) { Text("撤销") }
                } else if (oldValue != null) {
                    TextButton(
                        enabled = editable,
                        onClick = {
                            val next = restoreField(fields, oldFields, label, oldValue)
                            revertedValues[label] = current
                            fields = next
                            onDraftChange(encodeOutlineFields(next))
                        }
                    ) { Text("还原") }
                }
            }
            if (oldValue != null) {
                Text(
                    "旧：${oldValue.ifBlank { "（空）" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (current != null) {
                OutlinedTextField(
                    value = current,
                    onValueChange = { changed ->
                        revertedValues.remove(label)
                        val next = fields.toMutableList().apply {
                            val index = indexOfFirst { it.first == label }
                            if (index >= 0) this[index] = label to changed else add(label to changed)
                        }
                        fields = next
                        onDraftChange(encodeOutlineFields(next))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable,
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 1
                )
            } else {
                Text(
                    "（该字段将被移除）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    } else {
        var text by remember(suggestionId) { mutableStateOf(initialDraft) }
        Text("修改前", style = MaterialTheme.typography.labelMedium)
        Text(
            oldContent.ifBlank { "（新增条目，无修改前内容）" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { changed ->
                text = changed
                onDraftChange(changed)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
            label = { Text("修改后（可编辑）") },
            textStyle = MaterialTheme.typography.bodySmall,
            minLines = 2
        )
    }
}

/** 把某个字段恢复为旧值；该字段已被移除时按旧字段顺序插回 */
private fun restoreField(
    fields: List<Pair<String, String>>,
    oldFields: List<Pair<String, String>>,
    label: String,
    oldValue: String
): List<Pair<String, String>> {
    val next = fields.toMutableList()
    val index = next.indexOfFirst { it.first == label }
    if (index >= 0) {
        next[index] = label to oldValue
        return next
    }
    val oldIndex = oldFields.indexOfFirst { it.first == label }
    val before = oldFields.take(oldIndex.coerceAtLeast(0)).map { it.first }.toSet()
    val insertAt = next.indexOfLast { it.first in before }.let { if (it >= 0) it + 1 else next.size }
    next.add(insertAt, label to oldValue)
    return next
}

/** 本次实际变化的字段名（新增、修改、移除都算） */
private fun changedFieldNames(oldContent: String, newContent: String): List<String> {
    if (oldContent.isBlank() || !outlineContentIsFieldJson(newContent)) return emptyList()
    val oldFields = runCatching { decodeOutlineFields(oldContent) }.getOrNull().orEmpty()
    val newFields = runCatching { decodeOutlineFields(newContent) }.getOrNull().orEmpty()
    return (newFields.map { it.first } + oldFields.map { it.first }).distinct()
        .filter { label ->
            oldFields.firstOrNull { it.first == label }?.second !=
                newFields.firstOrNull { it.first == label }?.second
        }
}

@Composable
private fun operationColor(operation: String) = when (operation) {
    OutlineOperation.ADD -> MaterialTheme.colorScheme.primary
    OutlineOperation.DELETE -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}

private fun modeLabel(mode: String): String =
    com.lianwenqi.app.data.CompressionOutlineUpdateMode.label(mode)
