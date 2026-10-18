package com.lianwenqi.app.ui.screens

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lianwenqi.app.data.BookSummary
import com.lianwenqi.app.data.SortMode
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.ui.components.BookCover
import com.lianwenqi.app.ui.components.BookFormDialog
import com.lianwenqi.app.ui.components.EmptyHint
import com.lianwenqi.app.util.ExportUtil
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onOpenBook: (Long) -> Unit,
    onPreviewBook: (Long, Long) -> Unit,
    onOpenAi: () -> Unit
) {
    val vm = appViewModel { BookshelfViewModel(it) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val books by vm.books.collectAsState()
    val visibleBooks by vm.visibleBooks.collectAsState()
    val groupFilter by vm.groupFilter.collectAsState()
    val listMode by vm.listMode.collectAsState()
    val night by vm.nightMode.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val sortAsc by vm.sortAsc.collectAsState()
    val groups by vm.groups.collectAsState()

    var showCreate by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var sheetBook by remember { mutableStateOf<BookSummary?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var showCreateGroup by remember { mutableStateOf(false) }
    var showGroupFilterMenu by remember { mutableStateOf(false) }
    var showBatchGroupPicker by remember { mutableStateOf(false) }
    var batchMode by remember { mutableStateOf(false) }
    // 待确认删除：单本 / 批量
    var deleteTarget by remember { mutableStateOf<Book?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    // 编辑目标书籍（更多菜单「编辑」）
    var editBookTarget by remember { mutableStateOf<Book?>(null) }
    val selected = remember { mutableStateOf(setOf<Long>()) }

    // 批量管理下按返回键退出多选
    BackHandler(enabled = batchMode) {
        batchMode = false
        selected.value = emptySet()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (batchMode) "已选 ${selected.value.size} 本" else "书架") },
                actions = {
                    IconButton(onClick = { vm.toggleNight() }) {
                        Icon(
                            if (night) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = "夜间模式"
                        )
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建书籍")
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (listMode) "图墙模式" else "列表模式") },
                                leadingIcon = { Icon(Icons.Filled.GridView, null) },
                                onClick = { vm.toggleListMode(); showMoreMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("按时间") },
                                onClick = {
                                    vm.setSort(SortMode.TIME, asc = !sortAsc)
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("按名称") },
                                onClick = {
                                    vm.setSort(SortMode.NAME, asc = !sortAsc)
                                    showMoreMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (batchMode) "退出批量管理" else "批量管理") },
                                onClick = { batchMode = !batchMode; showMoreMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("创建分组") },
                                onClick = { showCreateGroup = true; showMoreMenu = false }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clickable { showSortMenu = true }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    (if (sortMode == "TIME") "按时间" else "按名称") + if (sortAsc) " ↑" else " ↓",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("按时间") },
                                onClick = { vm.setSort(SortMode.TIME, sortAsc); showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("按名称") },
                                onClick = { vm.setSort(SortMode.NAME, sortAsc); showSortMenu = false }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("正序") },
                                onClick = { vm.setSort(SortMode.valueOf(sortMode), true); showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("倒序") },
                                onClick = { vm.setSort(SortMode.valueOf(sortMode), false); showSortMenu = false }
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "共 ${visibleBooks.size} 本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    // 分组筛选：只显示某个分组的书籍
                    Box {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (groupFilter != null) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.clickable { showGroupFilterMenu = true }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Folder, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    groups.firstOrNull { it.id == groupFilter }?.name ?: "全部分组",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(4.dp))
                                if (groupFilter != null) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "清除筛选",
                                        modifier = Modifier.size(16.dp).clickable { vm.setGroupFilter(null) }
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = showGroupFilterMenu,
                            onDismissRequest = { showGroupFilterMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部分组") },
                                onClick = { vm.setGroupFilter(null); showGroupFilterMenu = false }
                            )
                            groups.forEach { g ->
                                DropdownMenuItem(
                                    text = { Text(g.name) },
                                    onClick = { vm.setGroupFilter(g.id); showGroupFilterMenu = false }
                                )
                            }
                        }
                    }
                }

                if (visibleBooks.isEmpty()) {
                    EmptyHint(
                        if (books.isEmpty()) "还没有书籍，点击右上角 + 新建"
                        else "该分组下暂无书籍",
                        Modifier.fillMaxSize()
                    )
                } else if (listMode) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(visibleBooks, key = { it.book.id }) { item ->
                            BookRow(
                                item = item,
                                checked = item.book.id in selected.value,
                                batchMode = batchMode,
                                onToggleCheck = { toggleSelection(selected, item.book.id) },
                                onClick = { onOpenBook(item.book.id) },
                                onMore = { sheetBook = item },
                                onPin = { vm.togglePin(item.book) }
                            )
                        }
                    }
                } else {
                    // 图墙模式：一行三本
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(visibleBooks, key = { it.book.id }) { item ->
                            BookGridItem(
                                item = item,
                                batchMode = batchMode,
                                checked = item.book.id in selected.value,
                                onToggleCheck = { toggleSelection(selected, item.book.id) },
                                onClick = { onOpenBook(item.book.id) }
                            )
                        }
                    }
                }
            }

            // 批量管理：多选后可删除所选 / 纳入分组
            if (batchMode) {
                val allSelected = visibleBooks.isNotEmpty() && visibleBooks.all { it.book.id in selected.value }
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 12.dp
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已选 ${selected.value.size} 本",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                enabled = visibleBooks.isNotEmpty(),
                                onClick = {
                                    selected.value =
                                        if (allSelected) emptySet()
                                        else visibleBooks.map { it.book.id }.toSet()
                                }
                            ) { Text(if (allSelected) "取消全选" else "全选") }
                        }
                        Spacer(Modifier.height(8.dp))
                        // 三个按钮等分一行：压缩内边距并禁止换行，避免「纳入分组」被挤成两行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    batchMode = false
                                    selected.value = emptySet()
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) { Text("取消", maxLines = 1, softWrap = false) }
                            OutlinedButton(
                                onClick = { showBatchGroupPicker = true },
                                enabled = selected.value.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("纳入分组", maxLines = 1, softWrap = false)
                            }
                            Button(
                                onClick = { confirmBatchDelete = true },
                                enabled = selected.value.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("删除", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        BookFormDialog(
            dialogTitle = "新建书籍",
            initial = null,
            groups = groups,
            onPickCover = { uri, cb -> vm.pickCover(uri, cb) },
            onCreateGroup = { vm.createGroupAndGet(it) },
            onConfirm = { r ->
                // 新建后停留在书架，由列表刷新显示新书
                vm.createBook(r.title, r.intro, r.coverPath, r.coverColor, r.volumePrefix, r.chapterPrefix, r.groupId) { _ ->
                    showCreate = false
                }
            },
            onDismiss = { showCreate = false }
        )
    }

    // 编辑书籍：封面 / 书名 / 简介 / 分组 / 前缀
    editBookTarget?.let { target ->
        BookFormDialog(
            dialogTitle = "编辑书籍",
            initial = target,
            groups = groups,
            onPickCover = { uri, cb -> vm.pickCover(uri, cb) },
            onCreateGroup = { vm.createGroupAndGet(it) },
            onConfirm = { r ->
                vm.updateBook(
                    target.copy(
                        title = r.title,
                        intro = r.intro,
                        coverPath = r.coverPath,
                        coverColor = r.coverColor,
                        volumePrefix = r.volumePrefix,
                        chapterPrefix = r.chapterPrefix,
                        groupId = r.groupId
                    )
                )
                editBookTarget = null
            },
            onDismiss = { editBookTarget = null }
        )
    }

    if (showCreateGroup) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateGroup = false },
            title = { Text("创建分组") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分组名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { if (name.isNotBlank()) vm.createGroup(name.trim()); showCreateGroup = false }
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showCreateGroup = false }) { Text("取消") } }
        )
    }

    sheetBook?.let { item ->
        ModalBottomSheet(onDismissRequest = { sheetBook = null }) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    item.book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                HorizontalDivider()
                // 宫格操作：图标 + 文案，四列一行
                val actions = listOf(
                    SheetActionItem(Icons.Filled.MenuBook, "预览本书") {
                        sheetBook = null
                        scope.launch {
                            val first = vm.chaptersOf(item.book.id).firstOrNull()
                            if (first == null) {
                                Toast.makeText(context, "该书还没有章节", Toast.LENGTH_SHORT).show()
                            } else {
                                onPreviewBook(item.book.id, first.id)
                            }
                        }
                    },
                    SheetActionItem(Icons.Filled.Edit, "编辑本书") {
                        editBookTarget = item.book
                        sheetBook = null
                    },
                    SheetActionItem(Icons.Filled.FileDownload, "导出书籍") { showExportDialog = true },
                    SheetActionItem(Icons.Filled.DriveFileMove, "移至分组") { showGroupPicker = true },
                    SheetActionItem(Icons.Filled.Share, "分享本书") {
                        sheetBook = null
                        scope.launch {
                            val chapters = vm.chaptersOf(item.book.id)
                            val volumes = vm.volumesOf(item.book.id)
                            val f = ExportUtil.bookToTxt(context, item.book, volumes, chapters, singleFile = true)
                            ExportUtil.shareFile(context, f, "text/plain", "分享书籍")
                        }
                    },
                    SheetActionItem(Icons.Filled.VerticalAlignTop, if (item.book.pinned) "取消置顶" else "置顶本书") {
                        vm.togglePin(item.book); sheetBook = null
                    },
                    SheetActionItem(Icons.Filled.ContentCopy, "复制本书") {
                        val srcTitle = item.book.title
                        sheetBook = null
                        vm.copyBookOutline(item.book.id) { title ->
                            Toast.makeText(
                                context,
                                if (title != null) "已复制《$srcTitle》的大纲到《$title》" else "复制失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    SheetActionItem(Icons.Filled.DeleteOutline, "删除本书", danger = true) {
                        deleteTarget = item.book
                        sheetBook = null
                    }
                )
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    actions.chunked(4).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth()) {
                            rowItems.forEach { a -> SheetGridAction(a, Modifier.weight(1f)) }
                            repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }

    // 单本删除确认
    deleteTarget?.let { b ->
        ConfirmDialog(
            title = "删除《${b.title}》？",
            message = "该书的全部章节、大纲与对话数据将一并删除，且无法恢复。",
            onConfirm = { vm.deleteBook(b.id) },
            onDismiss = { deleteTarget = null }
        )
    }

    // 批量删除确认
    if (confirmBatchDelete) {
        val count = selected.value.size
        ConfirmDialog(
            title = "删除已选的 $count 本书？",
            message = "所选书籍的全部章节、大纲与对话数据将一并删除，且无法恢复。",
            onConfirm = {
                vm.deleteBooks(selected.value)
                selected.value = emptySet()
                batchMode = false
            },
            onDismiss = { confirmBatchDelete = false }
        )
    }

    if (showExportDialog) {
        val book = sheetBook?.book
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出书籍") },
            text = {
                Column {
                    ExportOption("导出整本为单个 txt") {
                        doExport(scope, context, vm, book, ExportKind.BOOK_TXT_SINGLE)
                        showExportDialog = false; sheetBook = null
                    }
                    ExportOption("导出所有章节为 txt") {
                        doExport(scope, context, vm, book, ExportKind.BOOK_TXT_ALL)
                        showExportDialog = false; sheetBook = null
                    }
                    ExportOption("导出整本为单个 word") {
                        doExport(scope, context, vm, book, ExportKind.BOOK_DOC_SINGLE)
                        showExportDialog = false; sheetBook = null
                    }
                    ExportOption("导出所有章节为 word") {
                        doExport(scope, context, vm, book, ExportKind.BOOK_DOC_ALL)
                        showExportDialog = false; sheetBook = null
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExportDialog = false }) { Text("关闭") } }
        )
    }

    if (showGroupPicker) {
        AlertDialog(
            onDismissRequest = { showGroupPicker = false },
            title = { Text("移动到分组") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ExportOption("（不分组）") {
                        sheetBook?.let { vm.moveToGroup(it.book.id, null) }
                        showGroupPicker = false; sheetBook = null
                    }
                    groups.forEach { g ->
                        ExportOption(g.name) {
                            sheetBook?.let { vm.moveToGroup(it.book.id, g.id) }
                            showGroupPicker = false; sheetBook = null
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showGroupPicker = false }) { Text("取消") } }
        )
    }

    if (showBatchGroupPicker) {
        AlertDialog(
            onDismissRequest = { showBatchGroupPicker = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("纳入分组（已选 ${selected.value.size} 本）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (groups.isEmpty()) {
                        Text("暂无分组，可先在右上角菜单中创建分组")
                    }
                    ExportOption("（不分组）") {
                        vm.moveBooksToGroup(selected.value, null)
                        showBatchGroupPicker = false
                        selected.value = emptySet()
                        batchMode = false
                    }
                    groups.forEach { g ->
                        ExportOption(g.name) {
                            vm.moveBooksToGroup(selected.value, g.id)
                            showBatchGroupPicker = false
                            selected.value = emptySet()
                            batchMode = false
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchGroupPicker = false }) { Text("取消") }
            }
        )
    }
}

/** 批量管理下切换某本书的勾选状态 */
private fun toggleSelection(selected: androidx.compose.runtime.MutableState<Set<Long>>, id: Long) {
    selected.value =
        if (id in selected.value) selected.value - id else selected.value + id
}

private enum class ExportKind { BOOK_TXT_SINGLE, BOOK_TXT_ALL, BOOK_DOC_SINGLE, BOOK_DOC_ALL }

private fun doExport(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    vm: BookshelfViewModel,
    book: Book?,
    kind: ExportKind
) {
    if (book == null) return
    scope.launch {
        val chapters = vm.chaptersOf(book.id)
        when (kind) {
            ExportKind.BOOK_TXT_SINGLE -> {
                val f = ExportUtil.bookToTxt(context, book, vm.volumesOf(book.id), chapters, true)
                ExportUtil.shareFile(context, f, "text/plain")
            }
            ExportKind.BOOK_TXT_ALL -> {
                val f = ExportUtil.bookToTxt(context, book, vm.volumesOf(book.id), chapters, false)
                ExportUtil.shareFile(context, f, "text/plain")
            }
            ExportKind.BOOK_DOC_SINGLE -> {
                val f = ExportUtil.bookToWord(context, book, chapters, true)
                ExportUtil.shareFile(context, f, "application/msword")
            }
            ExportKind.BOOK_DOC_ALL -> {
                val f = ExportUtil.bookToWord(context, book, chapters, false)
                ExportUtil.shareFile(context, f, "application/msword")
            }
        }
    }
}

/** 底部「更多」宫格的一项：图标 + 文案 */
private data class SheetActionItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/** 底部「更多」宫格按钮：圆形底 + 图标 + 文案（参考系统图库风格） */
@Composable
private fun SheetGridAction(item: SheetActionItem, modifier: Modifier = Modifier) {
    val tint = if (item.danger) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurface
    Column(
        modifier = modifier.clickable(onClick = item.onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = if (item.danger) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(12.dp).size(22.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExportOption(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

@Composable
private fun BookRow(
    item: BookSummary,
    checked: Boolean,
    batchMode: Boolean,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onPin: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { if (batchMode) onToggleCheck() else onClick() },
        colors = CardDefaults.cardColors()
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            if (batchMode) {
                androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggleCheck() })
            }
            BookCover(
                book = item.book,
                modifier = Modifier.width(60.dp).height(84.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.book.pinned) {
                        Text("置顶 ", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        item.book.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${item.wordCount} 字 · ${item.chapterCount} 章",
                    style = MaterialTheme.typography.bodySmall
                )
                item.book.lastChapterTitle?.let {
                    Text(
                        "上次编辑：$it",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "创建 ${timeFmt.format(Date(item.book.createdAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "修改 ${timeFmt.format(Date(item.book.updatedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = onMore) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                }
                TextButton(onClick = onPin) {
                    Text(if (item.book.pinned) "取消置顶" else "置顶",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun BookGridItem(
    item: BookSummary,
    batchMode: Boolean,
    checked: Boolean,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable { if (batchMode) onToggleCheck() else onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth()) {
            BookCover(
                book = item.book,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)
            )
            if (batchMode) {
                androidx.compose.material3.Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggleCheck() },
                    modifier = Modifier.align(Alignment.TopEnd)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            item.book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text("${item.wordCount} 字", style = MaterialTheme.typography.labelSmall)
    }
}

/** 统一样式的二次确认弹窗（删除等破坏性操作） */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "删除",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
        },
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
