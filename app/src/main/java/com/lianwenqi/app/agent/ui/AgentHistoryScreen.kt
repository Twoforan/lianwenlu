package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup

/** 历史列表的一行：分组标题或会话 */
private data class AgentHistoryEntry(
    val header: String? = null,
    val session: AgentSession? = null
)

/**
 * Agent 独立历史会话页面（全屏 Dialog）。
 *
 * 只展示 `agent_session`：普通聊天会话不会出现在这里，
 * 这里也不会出现在章节侧边栏 AI 中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHistoryScreen(
    sessions: List<AgentSession>,
    books: List<Book>,
    groups: List<BookGroup>,
    search: String,
    onSearchChange: (String) -> Unit,
    currentSessionId: Long?,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
    /** 非空时只显示绑定该书的 Agent 会话，并隐藏分组筛选 */
    fixedBookId: Long? = null,
    /** 勾选模式：显示复选框、全选与保存，保存后才会进入已选资料 */
    selectionEnabled: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (Long) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onSaveSelection: (() -> Unit)? = null
) {
    var renaming by remember { mutableStateOf<AgentSession?>(null) }
    var deleting by remember { mutableStateOf<AgentSession?>(null) }
    // 筛选：null=全部书籍，-1=未分组，其余为分组 id
    var filterGroupId by remember { mutableStateOf<Long?>(null) }
    val bookById = remember(books) { books.associateBy { it.id } }
    val scopedSessions = remember(sessions, fixedBookId) {
        if (fixedBookId == null) sessions else sessions.filter { it.bookId == fixedBookId }
    }
    val filtered = remember(scopedSessions, filterGroupId, bookById) {
        scopedSessions.filter { session ->
            val groupId = bookById[session.bookId]?.groupId
            when (filterGroupId) {
                null -> true
                -1L -> groupId == null
                else -> groupId == filterGroupId
            }
        }
    }
    val groupNames = remember(groups) { groups.associate { it.id to it.name } }
    // 分组显示：按书籍分组分段展示；未分组 / 未绑定书籍单独成段
    val entries = remember(filtered, filterGroupId, groupNames, bookById, fixedBookId) {
        if (fixedBookId != null) {
            filtered.map { AgentHistoryEntry(session = it) }
        } else {
            val sectionOf: (AgentSession) -> String = { session ->
                if (session.bookId == null) "未绑定书籍"
                else {
                    val gid = bookById[session.bookId]?.groupId
                    if (gid == null) "未分组" else groupNames[gid] ?: "未分组"
                }
            }
            val ordered = if (filterGroupId != null) {
                listOf(filtered)
            } else {
                filtered.groupBy(sectionOf).values.toList()
            }
            buildList {
                ordered.forEach { list ->
                    if (list.isEmpty()) return@forEach
                    add(AgentHistoryEntry(header = sectionOf(list.first())))
                    list.forEach { add(AgentHistoryEntry(session = it)) }
                }
            }
        }
    }
    val current = renaming
    val pendingDelete = deleting

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = { Text("Agent 历史") },
                        actions = {
                            if (selectionEnabled) {
                                TextButton(onClick = onSelectAll) { Text("全选") }
                                if (onSaveSelection != null) {
                                    TextButton(
                                        onClick = {
                                            onSaveSelection()
                                            onDismiss()
                                        }
                                    ) { Text("保存") }
                                }
                            } else {
                                IconButton(onClick = onNew) {
                                    Icon(Icons.Filled.Add, contentDescription = "新建 Agent 会话")
                                }
                            }
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
                    OutlinedTextField(
                        value = search,
                        onValueChange = onSearchChange,
                        placeholder = { Text("搜索 Agent 任务") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (fixedBookId == null) {
                            GroupFilter(
                                groups = groups,
                                selectedId = filterGroupId,
                                onSelect = { filterGroupId = it }
                            )
                        } else {
                            Text(
                                "仅显示《${books.firstOrNull { it.id == fixedBookId }?.title ?: "本书"}》的 Agent 任务",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "共 ${filtered.size} 个会话",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    if (entries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "暂无 Agent 会话",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(
                                entries,
                                key = {
                                    it.session?.let { s -> "s-${s.id}" } ?: "h-${it.header}"
                                }
                            ) { entry ->
                                val session = entry.session
                                if (session == null) {
                                    Text(
                                        entry.header.orEmpty(),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(
                                            start = 16.dp,
                                            end = 16.dp,
                                            top = 12.dp,
                                            bottom = 4.dp
                                        )
                                    )
                                } else {
                                    AgentSessionRow(
                                        session = session,
                                        bookTitle = books.firstOrNull { it.id == session.bookId }?.title,
                                        isCurrent = session.id == currentSessionId,
                                        onOpen = { onOpen(session.id) },
                                        onRename = { renaming = session },
                                        onDelete = { deleting = session },
                                        selectionEnabled = selectionEnabled,
                                        selected = session.id in selectedIds,
                                        onToggleSelect = { onToggleSelect(session.id) }
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    current?.let { target ->
        var title by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("修改会话标题") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("会话标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        onRename(target.id, title)
                        renaming = null
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除 Agent 会话") },
            text = {
                Text(
                    "将删除「${target.title}」及其 Agent 消息、资料引用。" +
                        "不会影响普通聊天、书籍、章节、大纲或文风数据。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(target.id)
                        deleting = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun GroupFilter(
    groups: List<BookGroup>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(
                when (selectedId) {
                    null -> "全部书籍"
                    -1L -> "未分组"
                    else -> groups.firstOrNull { it.id == selectedId }?.name ?: "全部书籍"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全部书籍") },
                onClick = {
                    expanded = false
                    onSelect(null)
                }
            )
            DropdownMenuItem(
                text = { Text("未分组") },
                onClick = {
                    expanded = false
                    onSelect(-1L)
                }
            )
            groups.forEach { group ->
                DropdownMenuItem(
                    text = { Text(group.name) },
                    onClick = {
                        expanded = false
                        onSelect(group.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun AgentSessionRow(
    session: AgentSession,
    bookTitle: String?,
    isCurrent: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    selectionEnabled: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {}
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = if (selectionEnabled) onToggleSelect else onOpen)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionEnabled) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                buildString {
                    append(if (bookTitle != null) "绑定：$bookTitle" else "未绑定书籍")
                    if (isCurrent) append(" · 当前会话")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!selectionEnabled) {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "会话操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("打开") },
                        onClick = {
                            menu = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("改名") },
                        onClick = {
                            menu = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("删除") },
                        onClick = {
                            menu = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}
