package com.lianwenqi.app.ui.screens

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianwenqi.app.R
import com.lianwenqi.app.data.BookSummary
import com.lianwenqi.app.data.SortMode
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.ui.components.BookCover
import com.lianwenqi.app.ui.components.BookFormDialog
import com.lianwenqi.app.ui.components.DeleteConfirmDialog
import com.lianwenqi.app.ui.components.EmptyHint
import com.lianwenqi.app.util.ExportUtil
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

/**
 * 书架配色：浅色值与设计稿完全一致；夜间模式使用对应深色值，避免硬编码浅色在深色主题下不可读。
 */
private data class ShelfPalette(
    val page: Color,
    val card: Color,
    val cardBorder: Color,
    val mainText: Color,
    val secondaryText: Color,
    val chipBg: Color,
    val chipBorder: Color,
    val chipText: Color,
    val headerIcon: Color,
    val iconButtonBg: Color,
    val iconButtonBorder: Color,
    val accent: Color,
    val pinText: Color
)

private val LightShelfPalette = ShelfPalette(
    page = Color(0xFFF5F6FA),
    card = Color.White,
    cardBorder = Color(0xFFEAECF2),
    mainText = Color(0xFF000000),
    secondaryText = Color(0xFF939BAD),
    chipBg = Color.White,
    chipBorder = Color(0xFFE3E6EE),
    chipText = Color(0xFF000000),
    headerIcon = Color(0xFF000000),
    iconButtonBg = Color.White,
    iconButtonBorder = Color(0xFFE3E6EE),
    accent = Color(0xFF6658E8),
    pinText = Color(0xFF6658E8)
)

private val DarkShelfPalette = ShelfPalette(
    page = Color(0xFF17161C),
    card = Color(0xFF201F28),
    cardBorder = Color(0xFF2E2C38),
    mainText = Color(0xFFF2EFF7),
    secondaryText = Color(0xFF9C97A8),
    chipBg = Color(0xFF201F28),
    chipBorder = Color(0xFF34313F),
    chipText = Color(0xFFF2EFF7),
    headerIcon = Color(0xFFE4E0EC),
    iconButtonBg = Color(0xFF201F28),
    iconButtonBorder = Color(0xFF34313F),
    accent = Color(0xFF8677F0),
    pinText = Color(0xFF9F94FF)
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
    /** 自定义书架背景图；null 时沿用默认纯色背景 */
    val shelfBackground by vm.shelfBackground.collectAsState()
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

    // 自定义背景铺满整页（含顶部栏与状态栏区域）；无背景时保持原纯色页面
    val customBackground = shelfBackground
    Box(Modifier.fillMaxSize()) {
        if (customBackground != null) {
            AsyncImage(
                model = File(customBackground),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    Scaffold(
        containerColor = if (customBackground != null) Color.Transparent else palette.page,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (customBackground != null) Color.Transparent else palette.page
                    )
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 19.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(49.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (batchMode) "已选 ${selected.value.size} 本" else "书架",
                        color = palette.mainText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SquareIconButton(
                            iconRes = if (night) R.drawable.ic_shelf_sunny else R.drawable.ic_shelf_moon,
                            description = if (night) "切换日间模式" else "切换夜间模式",
                            iconWidth = 20.dp,
                            iconHeight = 20.dp,
                            tint = palette.headerIcon,
                            background = palette.iconButtonBg,
                            borderColor = palette.iconButtonBorder
                        ) { vm.toggleNight() }
                        SquareIconButton(
                            iconRes = R.drawable.ic_shelf_add,
                            description = "新建书籍",
                            iconWidth = 12.dp,
                            iconHeight = 12.dp,
                            tint = Color.White,
                            background = palette.accent,
                            borderColor = null,
                            shape = RoundedCornerShape(13.dp),
                            shadowElevation = 7.dp
                        ) { showCreate = true }
                        Box {
                            SquareIconButton(
                                iconRes = R.drawable.ic_shelf_more_h,
                                description = "更多",
                                iconWidth = 18.dp,
                                iconHeight = 4.dp,
                                tint = palette.headerIcon,
                                background = palette.iconButtonBg,
                                borderColor = palette.iconButtonBorder
                            ) { showMoreMenu = true }
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
                Spacer(Modifier.height(21.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().height(31.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "共${visibleBooks.size}本",
                        color = palette.secondaryText,
                        fontSize = 11.sp,
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
                    Spacer(Modifier.width(10.dp))
                    Box {
                        SortButton(sortMode = sortMode, sortAsc = sortAsc, palette = palette) {
                            showSortMenu = true
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
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
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {

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
                        contentPadding = PaddingValues(start = 19.dp, end = 19.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    // 图墙模式：一行三本，封面 110×135，书名与置顶同一行
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 29.dp,
                            end = 29.dp,
                            top = 12.dp,
                            bottom = 12.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(25.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(visibleBooks, key = { it.book.id }) { item ->
                            BookGridItem(
                                item = item,
                                batchMode = batchMode,
                                checked = item.book.id in selected.value,
                                palette = palette,
                                onToggleCheck = { toggleSelection(selected, item.book.id) },
                                onClick = { onOpenBook(item.book.id) },
                                onMore = { sheetBook = item },
                                onPin = { vm.togglePin(item.book) }
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
            dialogTitle = "添加书籍",
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
                    coverGlyph = r.coverGlyph,
                    coverEnglish = r.coverEnglish,
                    coverTitleMode = r.coverTitleMode,
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
                    coverGlyph = r.coverGlyph,
                    coverEnglish = r.coverEnglish,
                    coverTitleMode = r.coverTitleMode,
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
        DeleteConfirmDialog(
            type = "书籍",
            name = "《${b.title}》",
            note = "该书的全部章节、大纲与对话数据将一并删除，且无法恢复。",
            onConfirm = {
                vm.deleteBook(b.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    // 批量删除确认
    if (confirmBatchDelete) {
        val count = selected.value.size
        DeleteConfirmDialog(
            type = "书籍",
            name = "已选的 $count 本",
            note = "所选书籍的全部章节、大纲与对话数据将一并删除，且无法恢复。",
            onConfirm = {
                vm.deleteBooks(selected.value)
                selected.value = emptySet()
                batchMode = false
                confirmBatchDelete = false
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

/** 顶部方形图标按钮：36dp 白底描边 / 紫色新建按钮共用 */
@Composable
private fun SquareIconButton(
    iconRes: Int,
    description: String,
    iconWidth: Dp,
    iconHeight: Dp,
    tint: Color,
    background: Color,
    borderColor: Color?,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    shadowElevation: Dp = 0.dp,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = shape,
        color = background,
        border = borderColor?.let { BorderStroke(1.dp, it) },
        shadowElevation = shadowElevation
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = description,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(width = iconWidth, height = iconHeight)
            )
        }
    }
}

/** 第二行白色胶囊：分组筛选 */
@Composable
private fun ToolbarChip(text: String, palette: ShelfPalette, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(31.dp)
            .widthIn(min = 75.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.chipBg)
            .border(1.dp, palette.chipBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = palette.chipText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 第二行紫色排序按钮：图标随当前排序方式与正倒序变化 */
@Composable
private fun SortButton(
    sortMode: String,
    sortAsc: Boolean,
    palette: ShelfPalette,
    onClick: () -> Unit
) {
    val iconRes = when {
        sortMode == "TIME" && sortAsc -> R.drawable.ic_sort_time_asc
        sortMode == "TIME" -> R.drawable.ic_sort_time_desc
        sortAsc -> R.drawable.ic_sort_name_asc
        else -> R.drawable.ic_sort_name_desc
    }
    Surface(
        modifier = Modifier.size(31.dp),
        shape = RoundedCornerShape(10.dp),
        color = palette.accent,
        shadowElevation = 5.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = "排序方式",
                modifier = Modifier.size(18.dp)
            )
        }
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
    val highlighted = batchMode && checked
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .combinedClickable(
                onClick = { if (batchMode) onToggleCheck() else onClick() },
                onLongClick = onMore
            ),
        shape = RoundedCornerShape(15.dp),
        color = palette.card,
        border = BorderStroke(
            if (highlighted) 1.5.dp else 1.dp,
            if (highlighted) palette.accent else palette.cardBorder
        ),
        shadowElevation = 2.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, top = 11.dp, end = 10.dp, bottom = 10.dp)
            ) {
                Box {
                    BookCover(
                        book = item.book,
                        modifier = Modifier.width(78.dp).height(107.dp),
                        cornerRadius = 9.dp,
                        titleSize = 10
                    )
                    if (batchMode) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(if (checked) palette.accent else Color.White.copy(alpha = 0.92f))
                                .border(
                                    1.dp,
                                    if (checked) palette.accent else Color.Gray.copy(alpha = 0.45f),
                                    CircleShape
                                )
                                .clickable(onClick = onToggleCheck),
                            contentAlignment = Alignment.Center
                        ) {
                            if (checked) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.width(11.dp))

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(
                        text = item.book.title,
                        color = palette.mainText,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, end = 26.dp)
                    )
                    Spacer(Modifier.height(15.dp))
                    Text(
                        text = if (item.chapterCount == 0) "暂未创建正文"
                        else "${item.wordCount}字·${item.chapterCount}章",
                        color = palette.secondaryText,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        maxLines = 1
                    )
                    Text(
                        text = item.book.lastChapterTitle?.let {
                            "上次编辑：$it\n修改 ${timeFmt.format(Date(item.book.updatedAt))}"
                        } ?: "修改 ${timeFmt.format(Date(item.book.updatedAt))}",
                        color = palette.secondaryText,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "创建 ${timeFmt.format(Date(item.book.createdAt))}",
                        color = palette.secondaryText,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        maxLines = 1
                    )
                }
            }

            // 右上角更多
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 9.dp, end = 10.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_shelf_more_h),
                    contentDescription = "更多",
                    colorFilter = ColorFilter.tint(palette.mainText),
                    modifier = Modifier.size(width = 18.dp, height = 4.dp)
                )
            }

            // 右下角置顶
            Text(
                text = "置顶",
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onPin)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                color = if (item.book.pinned) palette.pinText else palette.secondaryText,
                fontSize = 10.sp,
                fontWeight = if (item.book.pinned) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridItem(
    item: BookSummary,
    batchMode: Boolean,
    checked: Boolean,
    palette: ShelfPalette,
    onToggleCheck: () -> Unit,
    onClick: () -> Unit,
    onMore: () -> Unit,
    onPin: () -> Unit
) {
    Column(
        modifier = Modifier.combinedClickable(
            onClick = { if (batchMode) onToggleCheck() else onClick() },
            onLongClick = onMore
        ),
        horizontalAlignment = Alignment.Start
    ) {
        Box(Modifier.fillMaxWidth()) {
            BookCover(
                book = item.book,
                modifier = Modifier.fillMaxWidth().aspectRatio(110f / 135f),
                cornerRadius = 10.dp,
                titleSize = 10
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                item.book.title,
                color = palette.mainText,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "置顶",
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onPin)
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                color = if (item.book.pinned) palette.pinText else palette.secondaryText,
                fontSize = 7.sp,
                fontWeight = if (item.book.pinned) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
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
