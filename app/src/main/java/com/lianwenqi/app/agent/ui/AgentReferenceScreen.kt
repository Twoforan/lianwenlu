package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lianwenqi.app.agent.context.AgentContextSource
import com.lianwenqi.app.agent.context.ResolvedAgentReference
import com.lianwenqi.app.agent.data.dao.AgentMaterialMessageRow
import com.lianwenqi.app.agent.data.dao.AgentReferenceChapterRow
import com.lianwenqi.app.agent.data.dao.AgentToolMaterialRow
import com.lianwenqi.app.agent.data.entity.AgentMaterialCandidate
import com.lianwenqi.app.agent.data.entity.AgentReferenceLimits
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentSessionReference
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskReference
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.ui.appViewModel

/**
 * F5 参考资料选择与管理（全屏 Dialog）。
 *
 * 三类资料分开展示，不混在一个无法区分来源的列表里；
 * 已选列表同时展示会话级引用与当前任务实际使用的快照。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentReferenceScreen(
    sessionId: Long,
    currentTaskId: Long?,
    onDismiss: () -> Unit,
    extraRows: List<AgentSelectedExtraRow> = emptyList(),
    onToggleExtraMode: (AgentSelectedExtraRow) -> Unit = {},
    onRemoveExtra: (AgentSelectedExtraRow) -> Unit = {},
    onSave: () -> Unit = {}
) {
    val vm = appViewModel(key = "agent-refs-$sessionId") {
        AgentReferenceViewModel(it, sessionId)
    }
    LaunchedEffect(currentTaskId) { vm.setCurrentTaskId(currentTaskId) }

    val references by vm.references.collectAsState()
    val statusMap by vm.statusMap.collectAsState()
    val taskReferences by vm.taskReferences.collectAsState()
    val books by vm.books.collectAsState()
    val preview by vm.preview.collectAsState()
    val notice by vm.notice.collectAsState()

    LaunchedEffect(Unit) { vm.refreshStatusesNow() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = { Text("已选资料") },
                        actions = {
                            TextButton(
                                onClick = {
                                    onSave()
                                    onDismiss()
                                }
                            ) { Text("保存") }
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.Close, contentDescription = "关闭")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    val contextCount = extraRows.size
                    Text(
                        "任务上下文 $contextCount 项 · 参考资料 ${references.size}/" +
                            "${AgentReferenceLimits.MAX_SESSION_REFERENCES} 条（CONTEXT 注入，REFERENCE_ONLY 只登记）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    notice?.let { text ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = vm::clearNotice) { Text("关闭") }
                            }
                        }
                    }
                    SelectedTab(
                        references = references,
                        statusMap = statusMap,
                        taskReferences = taskReferences,
                        books = books,
                        onToggleMode = vm::toggleReferenceMode,
                        onPreview = vm::preview,
                        onRemove = vm::removeReference,
                        extraRows = extraRows,
                        onToggleExtraMode = onToggleExtraMode,
                        onRemoveExtra = onRemoveExtra
                    )
                }
            }
        }
    }

    preview?.let { resolved ->
        ReferencePreviewDialog(resolved = resolved, onDismiss = vm::dismissPreview)
    }
}

// ---------------- 已选 ----------------

/** 「已选资料」中的一条上下文资料（章节 / 卷梗概 / 大纲 / 历史 Agent） */
data class AgentSelectedExtraRow(
    val key: String,
    val group: String,
    val title: String,
    val detail: String,
    val mode: String,
    /** 完整文本，用于点击查看 */
    val content: String = ""
)

@Composable
internal fun SelectedTab(
    references: List<AgentSessionReference>,
    statusMap: Map<Long, ResolvedAgentReference>,
    taskReferences: List<AgentTaskReference>,
    books: List<Book>,
    onToggleMode: (AgentSessionReference) -> Unit,
    onPreview: (AgentSessionReference) -> Unit,
    onRemove: (AgentSessionReference) -> Unit,
    extraRows: List<AgentSelectedExtraRow> = emptyList(),
    onToggleExtraMode: (AgentSelectedExtraRow) -> Unit = {},
    onRemoveExtra: (AgentSelectedExtraRow) -> Unit = {}
) {
    val hasContextSelection = extraRows.isNotEmpty()
    var previewRow by remember { mutableStateOf<AgentSelectedExtraRow?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        // Agent 模式下的上下文选择：章节 / 卷梗概 / 大纲 / 历史 Agent
        extraRows.groupBy { it.group }.forEach { (group, rows) ->
            item(key = "extra-header-$group") { SectionHeader("$group（${rows.size}）") }
            items(rows, key = { it.key }) { row ->
                SelectedContextRow(
                    row = row,
                    onClick = { previewRow = row },
                    onToggleMode = { onToggleExtraMode(row) },
                    onRemove = { onRemoveExtra(row) }
                )
                HorizontalDivider()
            }
        }
        if (taskReferences.isNotEmpty()) {
            item {
                SectionHeader("当前任务实际使用（${taskReferences.size} 条，执行期间冻结）")
            }
            items(taskReferences, key = { "task-${it.id}" }) { reference ->
                TaskReferenceRow(
                    reference = reference,
                    bookTitle = books.firstOrNull { it.id == reference.bookId }?.title
                )
                HorizontalDivider()
            }
            item { SectionHeader("会话级引用（${references.size} 条）") }
        }
        if (references.isEmpty() && taskReferences.isEmpty() && !hasContextSelection) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "尚未选择资料。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(references, key = { it.id }) { reference ->
                SelectedReferenceRow(
                    reference = reference,
                    status = statusMap[reference.id],
                    onToggleMode = { onToggleMode(reference) },
                    onPreview = { onPreview(reference) },
                    onRemove = { onRemove(reference) }
                )
                HorizontalDivider()
            }
        }
    }

    previewRow?.let { row ->
        AgentExtraPreviewDialog(
            row = row,
            onDismiss = { previewRow = null },
            onRemove = {
                onRemoveExtra(row)
                previewRow = null
            }
        )
    }
}

/** 已选上下文的一条资料：点击查看全文，右侧模式标注 + 移除按钮 */
@Composable
private fun SelectedContextRow(
    row: AgentSelectedExtraRow,
    onClick: () -> Unit,
    onToggleMode: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                row.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        AssistChip(
            onClick = onToggleMode,
            label = {
                Text(
                    if (row.mode == AgentReferenceMode.CONTEXT) "CONTEXT" else "REFERENCE_ONLY"
                )
            }
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "移除")
        }
    }
}

/** 已选资料全文预览：右上角 X，底部移除 / 确定 */
@Composable
private fun AgentExtraPreviewDialog(
    row: AgentSelectedExtraRow,
    onDismiss: () -> Unit,
    onRemove: () -> Unit
) {
    val hasContent = row.content.isNotBlank()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, top = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            row.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider()
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                ) {
                    Text(
                        if (hasContent) row.content else "暂无正文",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (hasContent) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onRemove) { Text("移除") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun SelectedReferenceRow(
    reference: AgentSessionReference,
    status: ResolvedAgentReference?,
    onToggleMode: () -> Unit,
    onPreview: () -> Unit,
    onRemove: () -> Unit
) {
    val isContext = reference.referenceMode == AgentReferenceMode.CONTEXT
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                AgentContextSource.label(reference.sourceType),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = isContext,
                onClick = onToggleMode,
                label = { Text(if (isContext) "CONTEXT" else "REFERENCE_ONLY") }
            )
        }
        Text(
            status?.displayTitle ?: reference.titleSnapshot,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val sourceLine = listOfNotNull(
            status?.bookTitle?.takeIf { it.isNotBlank() },
            status?.volumeTitle?.takeIf { it.isNotBlank() },
            status?.chapterTitle?.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (sourceLine.isNotBlank()) {
            Text(
                "来源：$sourceLine",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            referenceStatusText(status),
            style = MaterialTheme.typography.labelSmall,
            color = if (status?.valid == false) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onPreview) { Text("查看") }
            TextButton(onClick = onRemove) { Text("移除") }
        }
    }
}

@Composable
private fun TaskReferenceRow(
    reference: AgentTaskReference,
    bookTitle: String?
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "${AgentContextSource.label(reference.sourceType)} · " +
                reference.sourceTitleSnapshot.ifBlank { "来源 #${reference.sourceId}" },
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            buildString {
                append("来源书籍：${bookTitle ?: "未知书籍"}")
                append(" · 模式：${reference.referenceMode}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            taskReferenceStatusText(reference),
            style = MaterialTheme.typography.labelSmall,
            color = if (reference.validated && !reference.valid) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun referenceStatusText(status: ResolvedAgentReference?): String = when {
    status == null -> "校验中…"
    !status.valid -> "已失效：${status.invalidReason.ifBlank { "来源不可用" }}"
    status.contentTotalChars > 0 -> "有效 · 来源 ${status.contentTotalChars} 字"
    else -> "有效"
}

private fun taskReferenceStatusText(reference: AgentTaskReference): String = when {
    !reference.validated -> "等待执行时校验"
    !reference.valid -> "已失效：${reference.invalidReason.ifBlank { "来源不可用" }}"
    reference.injected && reference.truncated ->
        "已注入（截断 ${reference.contentChars}/${reference.contentTotalChars} 字）"

    reference.injected -> "已注入 ${reference.contentChars} 字"
    reference.referenceMode == AgentReferenceMode.REFERENCE_ONLY -> "仅登记，未注入"
    else -> "未注入（${reference.invalidReason.ifBlank { "内容为空或预算不足" }}）"
}

// ---------------- 章节选择 ----------------

@Composable
internal fun ChapterPickerTab(
    sourceType: String,
    books: List<Book>,
    volumes: List<Volume>,
    chapters: List<AgentReferenceChapterRow>,
    filterBookId: Long?,
    filterVolumeId: Long?,
    search: String,
    selectedIds: Set<Long>,
    loading: Boolean,
    onSearchChange: (String) -> Unit,
    onFilterBook: (Long?) -> Unit,
    onFilterVolume: (Long?) -> Unit,
    onToggle: (Long) -> Unit,
    onAdd: (String) -> Unit
) {
    val volumeTitles = volumes.associate { it.id to it.title }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            placeholder = { Text("搜索章节标题或梗概") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PickerDropdown(
                label = books.firstOrNull { it.id == filterBookId }?.title ?: "选择书籍",
                items = books.map { it.id },
                itemLabel = { id -> books.firstOrNull { it.id == id }?.title ?: "书籍 #$id" },
                onSelect = onFilterBook
            )
            Spacer(Modifier.width(8.dp))
            PickerDropdown(
                label = filterVolumeId?.let { volumeTitles[it] } ?: "全部分卷",
                items = listOf<Long?>(null) + volumes.map { it.id },
                itemLabel = { id -> id?.let { volumeTitles[it] } ?: "全部分卷" },
                onSelect = onFilterVolume
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${chapters.size} 章",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.height(22.dp))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (chapters.isEmpty() && !loading) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "没有符合条件的章节",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(chapters, key = { it.id }) { row ->
                ChapterCandidateRow(
                    row = row,
                    volumeTitle = row.volumeId?.let { volumeTitles[it] },
                    showContent = sourceType == AgentReferenceSourceType.CHAPTER_CONTENT,
                    selected = row.id in selectedIds,
                    onToggle = { onToggle(row.id) }
                )
                HorizontalDivider()
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已选 ${selectedIds.size} 章",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { onAdd(sourceType) }
                ) { Text("添加引用") }
            }
        }
    }
}

@Composable
private fun ChapterCandidateRow(
    row: AgentReferenceChapterRow,
    volumeTitle: String?,
    showContent: Boolean,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append(volumeTitle ?: "未分卷")
                    if (showContent) {
                        append(" · 正文 ${row.contentLength} 字")
                    } else {
                        append(" · 梗概 ${row.synopsisLength} 字")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.synopsisSnippet.isNotBlank()) {
                Text(
                    row.synopsisSnippet,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------- Agent 历史 ----------------

@Composable
internal fun AgentHistoryTab(
    sessions: List<AgentSession>,
    books: List<Book>,
    search: String,
    filterBookId: Long?,
    expandedSessionId: Long?,
    tasks: List<AgentTask>,
    selectedMaterials: Map<String, AgentMaterialCandidate>,
    onSearchChange: (String) -> Unit,
    onFilterBook: (Long?) -> Unit,
    onToggleSession: (Long) -> Unit,
    onTogglePlan: (AgentTask) -> Unit,
    onToggleResult: (AgentTask) -> Unit,
    onOpenMaterial: (AgentTask) -> Unit,
    onAdd: () -> Unit
) {
    val bookTitles = books.associate { it.id to it.title }
    val filtered = sessions.filter { session ->
        (filterBookId == null || session.bookId == filterBookId) &&
            (search.isBlank() || session.title.contains(search, ignoreCase = true))
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            placeholder = { Text("搜索历史 Agent 会话") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PickerDropdown(
                label = filterBookId?.let { bookTitles[it] } ?: "全部书籍",
                items = listOf<Long?>(null) + books.map { it.id },
                itemLabel = { id -> id?.let { bookTitles[it] } ?: "全部书籍" },
                onSelect = onFilterBook
            )
            Spacer(Modifier.weight(1f))
            Text(
                "不会整段引用会话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (filtered.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无其他 Agent 历史会话",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(filtered, key = { it.id }) { session ->
                AgentSessionMaterialRow(
                    session = session,
                    bookTitle = session.bookId?.let { bookTitles[it] },
                    expanded = session.id == expandedSessionId,
                    tasks = if (session.id == expandedSessionId) tasks else emptyList(),
                    selectedMaterials = selectedMaterials,
                    onToggleSession = { onToggleSession(session.id) },
                    onTogglePlan = onTogglePlan,
                    onToggleResult = onToggleResult,
                    onOpenMaterial = onOpenMaterial
                )
                HorizontalDivider()
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "已选 ${selectedMaterials.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = selectedMaterials.isNotEmpty(),
                    onClick = onAdd
                ) { Text("添加引用") }
            }
        }
    }
}

@Composable
private fun AgentSessionMaterialRow(
    session: AgentSession,
    bookTitle: String?,
    expanded: Boolean,
    tasks: List<AgentTask>,
    selectedMaterials: Map<String, AgentMaterialCandidate>,
    onToggleSession: () -> Unit,
    onTogglePlan: (AgentTask) -> Unit,
    onToggleResult: (AgentTask) -> Unit,
    onOpenMaterial: (AgentTask) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleSession)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        append(bookTitle ?: "未绑定书籍")
                        append(" · 会话 #${session.id}")
                        if (expanded) append(" · ${tasks.size} 个任务")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) {
            tasks.forEach { task ->
                AgentTaskMaterialRow(
                    task = task,
                    selectedMaterials = selectedMaterials,
                    onTogglePlan = { onTogglePlan(task) },
                    onToggleResult = { onToggleResult(task) },
                    onOpenMaterial = { onOpenMaterial(task) }
                )
            }
        }
    }
}

@Composable
private fun AgentTaskMaterialRow(
    task: AgentTask,
    selectedMaterials: Map<String, AgentMaterialCandidate>,
    onTogglePlan: () -> Unit,
    onToggleResult: () -> Unit,
    onOpenMaterial: () -> Unit
) {
    val planKey = AgentReferenceSourceType.AGENT_TASK_PLAN + ":" + task.id
    val resultKey = AgentReferenceSourceType.AGENT_TASK_RESULT + ":" + task.id
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 16.dp, bottom = 12.dp)) {
        Text(task.title, style = MaterialTheme.typography.bodyMedium)
        Text(
            task.userGoal,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "状态：${task.status}",
            style = MaterialTheme.typography.labelSmall,
            color = if (task.status == AgentTaskStatus.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = planKey in selectedMaterials,
                onClick = onTogglePlan,
                label = { Text("任务计划") }
            )
            if (task.status == AgentTaskStatus.COMPLETED) {
                FilterChip(
                    selected = resultKey in selectedMaterials,
                    onClick = onToggleResult,
                    label = { Text("最终结果") }
                )
            }
            AssistChipButton(text = "消息 / 工具结果", onClick = onOpenMaterial)
        }
    }
}

@Composable
private fun AssistChipButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(text) }
}

@Composable
private fun MaterialPickerDialog(
    state: AgentMaterialDialogState,
    bookId: Long?,
    selected: Map<String, AgentMaterialCandidate>,
    onToggle: (AgentMaterialCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择任务资料") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("任务：${state.task.title}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "只选择你明确需要的消息或工具结果，不会整段引用任务。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text("消息", style = MaterialTheme.typography.labelMedium)
                if (state.messages.isEmpty()) {
                    Text(
                        "没有可引用的消息",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.messages.forEach { message ->
                    MessageMaterialRow(
                        message = message,
                        bookId = bookId,
                        selected = selected,
                        onToggle = onToggle
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("历史工具结果", style = MaterialTheme.typography.labelMedium)
                if (state.tools.isEmpty()) {
                    Text(
                        "没有成功的历史工具结果",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.tools.forEach { tool ->
                    ToolMaterialRow(
                        tool = tool,
                        bookId = bookId,
                        selected = selected,
                        onToggle = onToggle
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

@Composable
private fun MessageMaterialRow(
    message: AgentMaterialMessageRow,
    bookId: Long?,
    selected: Map<String, AgentMaterialCandidate>,
    onToggle: (AgentMaterialCandidate) -> Unit
) {
    val candidate = AgentMaterialCandidate(
        key = AgentReferenceSourceType.AGENT_MESSAGE + ":" + message.id,
        sourceType = AgentReferenceSourceType.AGENT_MESSAGE,
        sourceId = message.id,
        title = "消息 #${message.sequence} · ${AgentContextSource.messageKindLabel(message.kind)}",
        bookId = bookId
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(candidate) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = candidate.key in selected,
            onCheckedChange = { onToggle(candidate) }
        )
        Column(Modifier.weight(1f)) {
            Text(
                AgentContextSource.messageKindLabel(message.kind) + " · ${message.contentLength} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                message.contentSnippet,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ToolMaterialRow(
    tool: AgentToolMaterialRow,
    bookId: Long?,
    selected: Map<String, AgentMaterialCandidate>,
    onToggle: (AgentMaterialCandidate) -> Unit
) {
    val candidate = AgentMaterialCandidate(
        key = AgentReferenceSourceType.AGENT_TOOL_RESULT + ":" + tool.id,
        sourceType = AgentReferenceSourceType.AGENT_TOOL_RESULT,
        sourceId = tool.id,
        title = (tool.toolDisplayName.ifBlank { tool.toolName }) + " · 历史工具结果",
        bookId = bookId
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(candidate) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = candidate.key in selected,
            onCheckedChange = { onToggle(candidate) }
        )
        Column(Modifier.weight(1f)) {
            Text(
                "${tool.toolDisplayName.ifBlank { tool.toolName }} · ${tool.resultLength} 字",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                tool.resultSnippet,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------- 预览 ----------------

@Composable
private fun ReferencePreviewDialog(
    resolved: ResolvedAgentReference,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (resolved.valid) "资料预览" else "资料不可用") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "${AgentContextSource.label(resolved.target.sourceType)} · ${resolved.displayTitle}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    sourceDescription(resolved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (!resolved.valid) {
                    Text(
                        resolved.invalidReason,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        if (resolved.truncated) {
                            "预览为前 ${resolved.content.length} 字，来源共 ${resolved.contentTotalChars} 字。"
                        } else {
                            "来源共 ${resolved.contentTotalChars} 字。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(resolved.content.ifBlank { "（内容为空）" })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun sourceDescription(resolved: ResolvedAgentReference): String = listOfNotNull(
    resolved.bookTitle.takeIf { it.isNotBlank() },
    resolved.volumeTitle.takeIf { it.isNotBlank() },
    resolved.chapterTitle.takeIf { it.isNotBlank() }
).joinToString(" · ").ifBlank { "来源 #${resolved.target.sourceId}" }

// ---------------- 通用下拉 ----------------

@Composable
private fun <T> PickerDropdown(
    label: String,
    items: List<T>,
    itemLabel: (T) -> String = { it.toString() },
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(itemLabel(item)) },
                    onClick = {
                        expanded = false
                        onSelect(item)
                    }
                )
            }
        }
    }
}
