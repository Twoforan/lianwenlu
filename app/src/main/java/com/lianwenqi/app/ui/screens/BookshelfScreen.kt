package com.lianwenqi.app.ui.screens

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianwenqi.app.R
import com.lianwenqi.app.ui.theme.ShelfWhite
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

/**
 * 书架配色：浅色值与设计稿完全一致；夜间模式使用对应深色值，避免硬编码浅色在深色主题下不可读。
 */
private data class ShelfPalette(
    val page: Color,
    val mainText: Color,
    val secondaryText: Color,
    val divider: Color,
    val chipBg: Color,
    val chipText: Color,
    val headerIcon: Color,
    val metaText: Color,
    val pinText: Color
)

private val LightShelfPalette = ShelfPalette(
    page = ShelfWhite,
    mainText = Color(0xFF29272C),
    secondaryText = Color(0xFF77717D),
    divider = Color(0xFFEFEDF0),
    chipBg = Color(0xFFF0EDF8),
    chipText = Color(0xFF37333E),
    headerIcon = Color(0xFF39363D),
    metaText = Color(0xFF66616B),
    pinText = Color(0xFF4B60BB)
)

private val DarkShelfPalette = ShelfPalette(
    page = Color(0xFF17161C),
    mainText = Color(0xFFE9E6EF),
    secondaryText = Color(0xFFA5A0AE),
    divider = Color(0xFF2C2A34),
    chipBg = Color(0xFF2A2734),
    chipText = Color(0xFFDAD5E4),
    headerIcon = Color(0xFFD8D4DE),
    metaText = Color(0xFFA9A3B2),
    pinText = Color(0xFF9FB0F2)
)

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
    val styleLibrary by vm.styleLibrary.collectAsState()
    val palette = if (night) DarkShelfPalette else LightShelfPalette

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
    // 复制目标书籍：非空时展示「复制内容多选」弹窗
    var copyTarget by remember { mutableStateOf<BookSummary?>(null) }
    // 新增 / 复制成功后待定位的新书 id：列表刷新后滚动到该位置
    var locateBookId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(visibleBooks, locateBookId, listMode) {
        val target = locateBookId ?: return@LaunchedEffect
        val index = visibleBooks.indexOfFirst { it.book.id == target }
        if (index >= 0) {
            if (listMode) listState.animateScrollToItem(index) else gridState.animateScrollToItem(index)
            locateBookId = null
        }
    }

    // 批量管理下按返回键退出多选
    BackHandler(enabled = batchMode) {
        batchMode = false
        selected.value = emptySet()
    }

    Scaffold(
        containerColor = palette.page,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.page)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(56.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (batchMode) "已选 ${selected.value.size} 本" else "书架",
                    color = palette.mainText,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    HeaderIcon(
                        iconRes = if (night) R.drawable.ic_shelf_sunny else R.drawable.ic_shelf_moon,
                        description = if (night) "切换日间模式" else "切换夜间模式",
                        color = palette.headerIcon
                    ) { vm.toggleNight() }
                    HeaderIcon(R.drawable.ic_shelf_add, "新建书籍", palette.headerIcon) {
                        showCreate = true
                    }
                    Box {
                        HeaderIcon(R.drawable.ic_shelf_more, "更多", palette.headerIcon) {
                            showMoreMenu = true
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
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 17.dp, end = 17.dp, top = 2.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Box {
                        ToolbarChip(
                            text = "☷  " + (if (sortMode == "TIME") "按时间" else "按名称") +
                                if (sortAsc) " ↑" else " ↓",
                            palette = palette,
                            onClick = { showSortMenu = true }
                        )
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
                    Text(
                        "共 ${visibleBooks.size} 本",
                        color = palette.secondaryText,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    // 分组筛选：只显示某个分组的书籍
                    Box {
                        ToolbarChip(
                            text = groups.firstOrNull { it.id == groupFilter }?.name
                                ?: "全部分组",
                            palette = palette,
                            onClick = { showGroupFilterMenu = true }
                        )
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
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 17.dp, end = 17.dp, bottom = 12.dp)
                    ) {
                        items(visibleBooks, key = { it.book.id }) { item ->
                            ShelfBookRow(
                                item = item,
                                checked = item.book.id in selected.value,
                                batchMode = batchMode,
                                palette = palette,
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
                        state = gridState,
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
                                onClick = { onOpenBook(item.book.id) },
                                onMore = { sheetBook = item }
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
            styleLibrary = styleLibrary,
            onRetryStyleProfiles = vm::retryStyleProfiles,
            onPickCover = { uri, cb -> vm.pickCover(uri, cb) },
            onCreateGroup = { vm.createGroupAndGet(it) },
            onConfirm = { r ->
                // 创建成功后关闭弹窗并定位到书架里的新书；失败时保留弹窗并提示原因
                vm.createBook(
                    title = r.title,
                    intro = r.intro,
                    coverPath = r.coverPath,
                    coverColor = r.coverColor,
                    volumePrefix = r.volumePrefix,
                    chapterPrefix = r.chapterPrefix,
                    groupId = r.groupId,
                    styleId = r.styleId,
                    onCreated = { id ->
                        showCreate = false
                        locateBookId = id
                    },
                    onError = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                )
            },
            onDismiss = { showCreate = false }
        )
    }

    // 编辑书籍：封面 / 书名 / 简介 / 分组 / 前缀 / 文风绑定（换绑或解除绑定）
    editBookTarget?.let { target ->
        BookFormDialog(
            dialogTitle = "编辑书籍",
            initial = target,
            groups = groups,
            styleLibrary = styleLibrary,
            onRetryStyleProfiles = vm::retryStyleProfiles,
            onPickCover = { uri, cb -> vm.pickCover(uri, cb) },
            onCreateGroup = { vm.createGroupAndGet(it) },
            onConfirm = { r ->
                // 保存成功后关闭弹窗；失败时保留弹窗与原绑定，并提示原因
                vm.updateBook(
                    bookId = target.id,
                    title = r.title,
                    intro = r.intro,
                    coverPath = r.coverPath,
                    coverColor = r.coverColor,
                    volumePrefix = r.volumePrefix,
                    chapterPrefix = r.chapterPrefix,
                    groupId = r.groupId,
                    styleId = r.styleId,
                    onUpdated = {
                        editBookTarget = null
                        // 文风块在会话前缀里冻结：换绑 / 解绑只影响之后新建的对话
                        if (r.styleId != target.styleId) {
                            val message = if (r.styleId == 0L) {
                                "文风已解绑，需新建对话后才会生效"
                            } else {
                                "文风已换绑，需新建对话后才会生效"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                    onError = { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                )
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
                        copyTarget = item
                        sheetBook = null
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

    // 复制本书：勾选要复制的内容（至少一项）
    copyTarget?.let { target ->
        var copyOutline by remember(target.book.id) { mutableStateOf(true) }
        var copySummaries by remember(target.book.id) { mutableStateOf(false) }
        var copyContent by remember(target.book.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { copyTarget = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("复制《${target.book.title}》") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "选择要复制到新书的内容：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CopyOptionRow(
                        label = "复制本书大纲",
                        checked = copyOutline,
                        onCheckedChange = { copyOutline = it }
                    )
                    CopyOptionRow(
                        label = "复制本书所有梗概（章节梗概 + 卷梗概）",
                        checked = copySummaries,
                        onCheckedChange = { copySummaries = it }
                    )
                    CopyOptionRow(
                        label = "复制本书所有章节正文",
                        checked = copyContent,
                        onCheckedChange = { copyContent = it }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = copyOutline || copySummaries || copyContent,
                    onClick = {
                        copyTarget = null
                        vm.copyBook(target.book.id, copyOutline, copySummaries, copyContent) { newId ->
                            if (newId > 0) {
                                Toast.makeText(context, "已复制为新书", Toast.LENGTH_SHORT).show()
                                locateBookId = newId
                            } else {
                                Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) { Text("开始复制") }
            },
            dismissButton = { TextButton(onClick = { copyTarget = null }) { Text("取消") } }
        )
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

/** 「复制本书」弹窗里的一行复选项 */
@Composable
private fun CopyOptionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
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
private fun HeaderIcon(
    iconRes: Int,
    description: String,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = description,
            colorFilter = ColorFilter.tint(color),
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun ToolbarChip(text: String, palette: ShelfPalette, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(palette.chipBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp)
            .height(38.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = palette.chipText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBookRow(
    item: BookSummary,
    checked: Boolean,
    batchMode: Boolean,
    palette: ShelfPalette,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onPin: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .combinedClickable(
                onClick = { if (batchMode) onToggleCheck() else onClick() },
                onLongClick = onMore
            )
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (batchMode) {
            androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = { onToggleCheck() })
            Spacer(Modifier.width(4.dp))
        }
        BookCover(
            book = item.book,
            modifier = Modifier.size(width = 66.dp, height = 82.dp),
            cornerRadius = 7.dp,
            titleSize = 12
        )

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 22.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = item.book.title,
                color = palette.mainText,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (item.chapterCount == 0) "暂未创建正文"
                else "${item.wordCount} 字 · ${item.chapterCount} 章",
                color = palette.metaText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1
            )
            item.book.lastChapterTitle?.let {
                Text(
                    "上次编辑：$it",
                    color = palette.metaText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "修改 ${timeFmt.format(Date(item.book.updatedAt))}",
                color = palette.metaText,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            modifier = Modifier.width(24.dp).height(82.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "⋮",
                modifier = Modifier.clickable(onClick = onMore),
                color = palette.mainText,
                fontSize = 23.sp,
                lineHeight = 24.sp
            )
            if (item.book.pinned) {
                Text(
                    "置顶",
                    modifier = Modifier.clickable(onClick = onPin),
                    color = palette.pinText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            } else {
                Text(
                    "置顶",
                    modifier = Modifier.clickable(onClick = onPin),
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().height(1.dp).background(palette.divider)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    item: BookSummary,
    batchMode: Boolean,
    checked: Boolean,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    Column(
        modifier = Modifier.combinedClickable(
            onClick = { if (batchMode) onToggleCheck() else onClick() },
            onLongClick = onMore
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth()) {
            BookCover(
                book = item.book,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
                titleSize = 12
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
