package com.lianwenqi.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineCategory
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.OUTLINE_HEAD_LABEL
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.data.encodeOutlineFields
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.ui.components.BookCover
import com.lianwenqi.app.util.ExportUtil
import com.lianwenqi.app.util.TextTool
import kotlinx.coroutines.launch

/** 全屏排序页的排序对象 */
private sealed interface SortTarget {
    object Volumes : SortTarget
    data class Chapters(val volumeId: Long?, val volumeTitle: String) : SortTarget
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BookDetailScreen(
    bookId: Long,
    onBack: () -> Unit,
    onOpenChapter: (Long) -> Unit
) {
    val vm = appViewModel(key = "book-$bookId") { BookDetailViewModel(it, bookId) }
    val book by vm.book.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val outline by vm.outline.collectAsState()
    val categories by vm.categories.collectAsState()
    val pinnedCategories by vm.pinnedCategories.collectAsState()

    var desc by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var sortTarget by remember { mutableStateOf<SortTarget?>(null) }
    var batchManage by remember { mutableStateOf(false) }
    var showNewChapter by remember { mutableStateOf(false) }

    val target = sortTarget
    if (target != null) {
        val rows: List<Pair<Long, String>> = when (target) {
            is SortTarget.Volumes -> volumes.map { it.id to it.title }
            is SortTarget.Chapters -> chapters.filter { it.volumeId == target.volumeId }.map { it.id to it.title }
        }
        val title = when (target) {
            is SortTarget.Volumes -> "排序分卷"
            is SortTarget.Chapters -> "排序章节 · ${target.volumeTitle}"
        }
        SortPage(
            title = title,
            rows = rows,
            onBack = { sortTarget = null },
            onCommit = { ids ->
                when (target) {
                    is SortTarget.Volumes -> vm.reorderVolumeIds(ids)
                    is SortTarget.Chapters -> vm.reorderChapterIds(ids)
                }
            }
        )
        return
    }

    // 章节批量管理：全屏页
    if (batchManage) {
        BatchManagePage(
            book = book,
            volumes = volumes,
            chapters = chapters,
            onBack = { batchManage = false },
            onReorder = { vm.reorderChapterIds(it) },
            onMoveToVolume = { ids, vid -> vm.moveChaptersToVolume(ids, vid) },
            onMoveToNewVolume = { ids -> vm.createVolumeAndMoveChapters(ids) },
            onDelete = { ids -> vm.deleteChapters(ids) },
            onMerge = { ids -> vm.mergeChapters(ids) },
            onRenumber = { vm.renumberChapters() }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "目录", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { sortTarget = SortTarget.Volumes }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                    }
                    IconButton(onClick = { desc = !desc }) {
                        Text(if (desc) "倒序" else "正序",
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            book?.let { b ->
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(book = b, modifier = Modifier.size(width = 64.dp, height = 88.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (b.intro.isNotBlank()) {
                            Text(
                                b.intro,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        val total = chapters.sumOf { TextTool.countWords(it.content) }
                        Text("${chapters.size} 章 · $total 字",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        // 有分卷时先让用户选择新建到哪一卷
                        onClick = { if (volumes.isEmpty()) vm.createChapter() else showNewChapter = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("新建", maxLines = 1) }
                    OutlinedButton(
                        onClick = { vm.createVolume() },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("新建分卷", maxLines = 1) }
                    // 继续上次 = 最近编辑过的章节，直接进入章节编辑页
                    val lastChapter = chapters.maxByOrNull { it.updatedAt }
                    OutlinedButton(
                        onClick = { lastChapter?.let { onOpenChapter(it.id) } },
                        enabled = lastChapter != null,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) { Text("继续上次", maxLines = 1) }
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("目录") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("大纲") })
            }

            if (tab == 0) {
                CatalogTab(
                    volumes = volumes,
                    chapters = chapters,
                    desc = desc,
                    lastChapterId = book?.lastChapterId,
                    onOpenChapter = onOpenChapter,
                    onNewChapter = { vid -> vm.createChapter(vid) },
                    onDeleteChapter = { vm.deleteChapter(it) },
                    onDeleteVolume = { vm.deleteVolume(it) },
                    onRenameVolume = { v, t -> vm.renameVolume(v, t) },
                    onRenameChapter = { c, t -> vm.renameChapter(c, t) },
                    onSortVolumes = { sortTarget = SortTarget.Volumes },
                    onBatchManage = { batchManage = true }
                )
            } else {
                OutlineTab(
                    bookId = bookId,
                    outline = outline,
                    categories = categories,
                    pinnedCategories = pinnedCategories,
                    loadFields = { c -> vm.outlineFields(c) ?: defaultOutlineFields(c) },
                    onSaveFields = { c, names -> vm.saveOutlineFields(c, names) },
                    onAddCategory = { vm.addCategory(it) },
                    onDeleteCategory = { vm.deleteCategory(it) },
                    onRenameCategory = { k, n -> vm.renameCategory(k, n) },
                    onTogglePinned = { vm.togglePinnedCategory(it) },
                    onSave = { vm.saveOutline(it) },
                    onDelete = { vm.deleteOutline(it) },
                    onMove = { item, cat -> vm.moveOutline(item, cat) }
                )
            }
        }
    }

    // 新建章节：选择放入哪一卷
    if (showNewChapter) {
        ActionMenuDialog(
            title = "新建章节 · 选择分卷",
            actions = volumes.map { v -> v.title to { vm.createChapter(v.id); showNewChapter = false } },
            onDismiss = { showNewChapter = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogTab(
    volumes: List<Volume>,
    chapters: List<Chapter>,
    desc: Boolean,
    lastChapterId: Long?,
    onOpenChapter: (Long) -> Unit,
    onNewChapter: (Long) -> Unit,
    onDeleteChapter: (Long) -> Unit,
    onDeleteVolume: (Long) -> Unit,
    onRenameVolume: (Volume, String) -> Unit,
    onRenameChapter: (Chapter, String) -> Unit,
    onSortVolumes: () -> Unit,
    onBatchManage: () -> Unit
) {
    // 默认只展开「上次最新编辑章节」所属的分卷，其他分卷收起；
    // 用户手动展开 / 收起后记入 collapsedOverride，之后不再随数据变化重置
    val defaultCollapsed = remember(volumes, chapters, lastChapterId) {
        val lastId = lastChapterId ?: chapters.maxByOrNull { it.updatedAt }?.id
        val lastVolumeId = chapters.firstOrNull { it.id == lastId }?.volumeId
        volumes.map { it.id }.filter { it != lastVolumeId }.toSet()
    }
    var collapsedOverride by remember { mutableStateOf<Set<Long>?>(null) }
    val collapsed = collapsedOverride ?: defaultCollapsed
    var menuTarget by remember { mutableStateOf<Any?>(null) }
    var renameTarget by remember { mutableStateOf<Any?>(null) }
    var deleteChapterTarget by remember { mutableStateOf<Chapter?>(null) }
    var deleteVolumeTarget by remember { mutableStateOf<Volume?>(null) }

    val volumeList = if (desc) volumes.reversed() else volumes
    val looseChapters = chapters.filter { it.volumeId == null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        volumeList.forEach { volume ->
            val vChapters = chapters.filter { it.volumeId == volume.id }
                .let { if (desc) it.reversed() else it }
            val isOpen = volume.id !in collapsed
            item(key = "v-${volume.id}") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            collapsedOverride =
                                if (isOpen) collapsed + volume.id
                                else collapsed - volume.id
                        }
                        .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isOpen) "收起" else "展开",
                        modifier = Modifier.size(20.dp).rotate(if (isOpen) 0f else -90f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(volume.title, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        val words = vChapters.sumOf { TextTool.countWords(it.content) }
                        Text(
                            "${vChapters.size}章 ${words}字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { menuTarget = volume }) {
                        Icon(Icons.Filled.Edit, contentDescription = "分卷操作", Modifier.size(18.dp))
                    }
                }
            }
            if (isOpen) {
                items(vChapters, key = { "c-${it.id}" }) { chapter ->
                    ChapterRow(
                        chapter = chapter,
                        onClick = { onOpenChapter(chapter.id) },
                        onLongClick = { menuTarget = chapter }
                    )
                }
                item(key = "new-${volume.id}") {
                    TextButton(
                        onClick = {
                            // 在收起的分卷里新建章节时，顺手展开该卷，避免看不到新章节
                            collapsedOverride = collapsed - volume.id
                            onNewChapter(volume.id)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("新建章节")
                    }
                }
            }
        }
        if (looseChapters.isNotEmpty()) {
            item {
                Text(
                    "未分卷",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(looseChapters, key = { "c-${it.id}" }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    onClick = { onOpenChapter(chapter.id) },
                    onLongClick = { menuTarget = chapter }
                )
            }
        }
    }

    // 长按 / 操作 菜单
    when (val target = menuTarget) {
        is Volume -> ActionMenuDialog(
            title = target.title,
            actions = listOf(
                "排序" to { onSortVolumes(); menuTarget = null },
                "重命名" to { renameTarget = target; menuTarget = null },
                "删除分卷" to { deleteVolumeTarget = target; menuTarget = null }
            ),
            onDismiss = { menuTarget = null }
        )
        is Chapter -> ActionMenuDialog(
            title = target.title,
            actions = listOf(
                "批量管理" to { onBatchManage(); menuTarget = null },
                "重命名" to { renameTarget = target; menuTarget = null },
                "删除章节" to { deleteChapterTarget = target; menuTarget = null }
            ),
            onDismiss = { menuTarget = null }
        )
        else -> Unit
    }

    // 重命名弹窗
    when (val target = renameTarget) {
        is Volume -> NameInputDialog(
            title = "重命名分卷",
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { onRenameVolume(target, it); renameTarget = null }
        )
        is Chapter -> NameInputDialog(
            title = "重命名章节",
            initial = target.title,
            onDismiss = { renameTarget = null },
            onConfirm = { onRenameChapter(target, it); renameTarget = null }
        )
        else -> Unit
    }

    // 删除确认
    deleteChapterTarget?.let { c ->
        ConfirmDialog(
            title = "删除章节「${c.title}」？",
            message = "删除后该章节的正文与梗概将无法恢复。",
            onConfirm = { onDeleteChapter(c.id) },
            onDismiss = { deleteChapterTarget = null }
        )
    }
    deleteVolumeTarget?.let { v ->
        val count = chapters.count { it.volumeId == v.id }
        ConfirmDialog(
            title = "删除分卷「${v.title}」？",
            message = if (count > 0) "该分卷下的 $count 个章节也会被一并删除。" else "该分卷下暂无章节。",
            onConfirm = { onDeleteVolume(v.id) },
            onDismiss = { deleteVolumeTarget = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(chapter: Chapter, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(chapter.title, style = MaterialTheme.typography.bodyLarge)
        Text("${TextTool.countWords(chapter.content)} 字",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}

@Composable
private fun OutlineTab(
    bookId: Long,
    outline: List<OutlineItem>,
    categories: List<String>,
    pinnedCategories: Set<String>,
    loadFields: suspend (String) -> List<String>,
    onSaveFields: (String, List<String>) -> Unit,
    onAddCategory: (String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onTogglePinned: (String) -> Unit,
    onSave: (OutlineItem) -> Unit,
    onDelete: (Long) -> Unit,
    onMove: (OutlineItem, String) -> Unit
) {
    var category by remember { mutableStateOf(categories.firstOrNull().orEmpty()) }
    var editing by remember { mutableStateOf<OutlineItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showAddLabel by remember { mutableStateOf(false) }
    // 长按标签后弹出的菜单 / 编辑名称 / 删除确认
    var labelMenu by remember { mutableStateOf<String?>(null) }
    var labelToRename by remember { mutableStateOf<String?>(null) }
    var labelToDelete by remember { mutableStateOf<String?>(null) }
    var itemToDelete by remember { mutableStateOf<OutlineItem?>(null) }
    // 待移动到其他标签的条目
    var itemToMove by remember { mutableStateOf<OutlineItem?>(null) }

    // 标签被删除后，回落到第一个标签
    LaunchedEffect(categories) {
        if (category !in categories) category = categories.firstOrNull().orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.forEach { key ->
                    // 长按标签弹出「常驻化 / 编辑名称 / 删除」菜单
                    Box {
                        CategoryChip(
                            label = outlineCategoryLabel(key),
                            selected = key == category,
                            pinned = key in pinnedCategories,
                            onClick = { category = key },
                            onLongClick = { labelMenu = key }
                        )
                        DropdownMenu(
                            expanded = labelMenu == key,
                            onDismissRequest = { labelMenu = null }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (key in pinnedCategories) "取消常驻化" else "常驻化") },
                                onClick = {
                                    labelMenu = null
                                    onTogglePinned(key)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("编辑名称") },
                                onClick = {
                                    labelMenu = null
                                    labelToRename = key
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    labelMenu = null
                                    labelToDelete = key
                                }
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { showAddLabel = true }) {
                Icon(Icons.Filled.AddCircleOutline, contentDescription = "新增标签")
            }
        }

        if (categories.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("点右上角「＋」新增一个标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        val items = outline.filter { it.category == category }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            if (items.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Text("暂无内容", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(items, key = { it.id }) { item ->
                OutlineCard(
                    item = item,
                    onEdit = { editing = item; showEditor = true },
                    onMove = { itemToMove = item },
                    onDelete = { itemToDelete = item }
                )
            }
            item(key = "new") {
                TextButton(
                    onClick = { editing = null; showEditor = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("新建${outlineCategoryLabel(category)}+") }
            }
        }
    }

    if (showAddLabel) {
        AddLabelDialog(
            onDismiss = { showAddLabel = false },
            onConfirm = { onAddCategory(it); showAddLabel = false }
        )
    }

    labelToRename?.let { key ->
        NameInputDialog(
            title = "编辑标签名称",
            initial = outlineCategoryLabel(key),
            onDismiss = { labelToRename = null },
            onConfirm = {
                onRenameCategory(key, it)
                labelToRename = null
            }
        )
    }

    labelToDelete?.let { key ->
        val count = outline.count { it.category == key }
        ConfirmDialog(
            title = "删除标签「${outlineCategoryLabel(key)}」？",
            message = if (count > 0) "该标签下的 $count 条内容也会被一并删除。" else "该标签下暂无内容。",
            onConfirm = { onDeleteCategory(key) },
            onDismiss = { labelToDelete = null }
        )
    }

    itemToDelete?.let { item ->
        ConfirmDialog(
            title = "删除「${item.title.ifBlank { "该条目" }}」？",
            message = "删除后无法恢复。",
            onConfirm = { onDelete(item.id) },
            onDismiss = { itemToDelete = null }
        )
    }

    // 移动条目到其他标签：列出除当前标签外的全部分类
    itemToMove?.let { item ->
        val others = categories.filter { it != item.category }
        ActionMenuDialog(
            title = "移动「${item.title.ifBlank { "该条目" }}」到其他标签",
            actions = if (others.isEmpty()) emptyList()
            else others.map { key ->
                outlineCategoryLabel(key) to {
                    onMove(item, key)
                    itemToMove = null
                }
            },
            onDismiss = { itemToMove = null }
        )
    }

    if (showEditor) {
        OutlineEditorDialog(
            categoryKey = category,
            categoryLabel = outlineCategoryLabel(category),
            initial = editing,
            loadFields = loadFields,
            onSaveFields = { onSaveFields(category, it) },
            onDismiss = { showEditor = false; editing = null },
            onSave = { fs ->
                onSave(
                    (editing ?: OutlineItem(bookId = bookId, category = category, title = ""))
                        .copy(
                            category = category,
                            title = outlineTitleOf(fs),
                            content = outlineContentOf(fs)
                        )
                )
                showEditor = false
                editing = null
            }
        )
    }
}

/** 顶部分类标签（可长按删除）；[pinned] 为真时在标签前显示图钉图标表示已常驻化 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    pinned: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pinned) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = "已常驻化",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 大纲条目卡片：默认最多三行，超出可点击展开；已完整展示时点击无反应 */
@Composable
private fun OutlineCard(
    item: OutlineItem,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    val pairs = remember(item.content) { parseOutlineFields(item.content) }
    val head = pairs.firstOrNull()
    val body = pairs.drop(1)

    var expanded by remember(item.id) { mutableStateOf(false) }
    var overflowed by remember(item.id) { mutableStateOf(false) }
    val canExpand = body.isNotEmpty() && (expanded || overflowed)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(enabled = canExpand) { expanded = !expanded }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                // 限制标签宽度，标题过长时省略，避免把右侧编辑/删除按钮挤出屏幕
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Text(
                    head?.let { it.second.ifBlank { it.first } } ?: item.title,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑", Modifier.size(18.dp))
            }
            IconButton(onClick = onMove) {
                Icon(Icons.Filled.DriveFileMove, contentDescription = "移动", Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", Modifier.size(18.dp))
            }
        }
        if (body.isNotEmpty()) {
            Text(
                body.joinToString("\n") { "${it.first}：${it.second}" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                onTextLayout = { if (it.hasVisualOverflow) overflowed = true },
                modifier = Modifier.padding(top = 8.dp)
            )
            if (canExpand && !expanded) {
                Text(
                    "点击展开全部",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** 各分类未保存过「表」时的默认字段；第一项固定为只读的「显示标签」 */
internal fun defaultOutlineFields(key: String): List<String> = when (key) {
    OutlineCategory.STORY.name -> listOf(OUTLINE_HEAD_LABEL, "梗概")
    OutlineCategory.CHARACTER.name -> listOf(OUTLINE_HEAD_LABEL, "性别", "身份", "经历")
    OutlineCategory.WORLD.name -> listOf(OUTLINE_HEAD_LABEL, "类型", "描述")
    OutlineCategory.INSPIRATION.name -> listOf(OUTLINE_HEAD_LABEL, "内容")
    else -> listOf(OUTLINE_HEAD_LABEL, "内容")
}

/**
 * 把大纲内容解析成「字段名 → 值」列表。
 * 内容按结构化存储，字段值里的冒号 / 换行都会原样保留，不会被自动拆成新项；
 * 新增项完全由用户通过「新增项+」决定。
 */
internal fun parseOutlineFields(content: String): List<Pair<String, String>> =
    decodeOutlineFields(content)

/** 把表单字段序列化成大纲内容（结构化存储，字段值中的冒号与换行原样保留） */
internal fun outlineContentOf(fields: List<OutlineField>): String =
    encodeOutlineFields(fields.map { it.name to it.value })

/** 大纲条目标题取第一个字段值的首行，避免多行值把标题撑成多行 */
internal fun outlineTitleOf(fields: List<OutlineField>): String {
    val first = fields.firstOrNull { it.name.isNotBlank() } ?: return ""
    val firstLine = first.value.lineSequence().firstOrNull()?.trim().orEmpty()
    return firstLine.ifBlank { first.name }
}

@Composable
internal fun OutlineEditorDialog(
    categoryKey: String,
    categoryLabel: String,
    initial: OutlineItem?,
    loadFields: suspend (String) -> List<String>,
    onSaveFields: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    onSave: (List<OutlineField>) -> Unit
) {
    val context = LocalContext.current
    var fields by remember { mutableStateOf<List<OutlineField>>(emptyList()) }
    // 正在内联改名的项下标
    var renameIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberScrollState()

    LaunchedEffect(categoryKey, initial?.id) {
        val existing = parseOutlineFields(initial?.content.orEmpty())
        fields = if (existing.isNotEmpty()) {
            // 第一项标题锁定为「显示标签」，只保留其内容（即条目对外显示的标签文字）
            existing.mapIndexed { index, pair ->
                OutlineField(if (index == 0) OUTLINE_HEAD_LABEL else pair.first, pair.second)
            }
        } else {
            loadFields(categoryKey).mapIndexed { index, name ->
                OutlineField(
                    if (index == 0) OUTLINE_HEAD_LABEL else name,
                    if (index == 0) initial?.title.orEmpty() else ""
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp).size(18.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        if (initial == null) "新建$categoryLabel" else "编辑$categoryLabel",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text("首项「显示标签」为条目对外显示的文字，不可改名/删除；其余项点击 ✎ 可改名", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Box(Modifier.heightIn(max = 380.dp)) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(end = 10.dp)
                ) {
                    fields.forEachIndexed { index, field ->
                        if (index == 0) {
                            // 第一项为「显示标签」：只读，不可改名、不可删除
                            Text(
                                OUTLINE_HEAD_LABEL,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (renameIndex == index) {
                            OutlinedTextField(
                                value = field.name,
                                onValueChange = { v ->
                                    fields = fields.toMutableList().also { it[index] = field.copy(name = v) }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { renameIndex = null }),
                                trailingIcon = {
                                    IconButton(onClick = { renameIndex = null }) {
                                        Icon(Icons.Filled.Check, contentDescription = "完成", Modifier.size(18.dp))
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    field.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { renameIndex = index }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Edit, contentDescription = "修改项名", Modifier.size(16.dp))
                                }
                                IconButton(onClick = { deleteIndex = index }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, contentDescription = "删除该项", Modifier.size(16.dp))
                                }
                            }
                        }
                        OutlinedTextField(
                            value = field.value,
                            onValueChange = { v ->
                                fields = fields.toMutableList().also { it[index] = field.copy(value = v) }
                            },
                            placeholder = { Text("请输入内容") },
                            // 值随内容自适应高度：默认一行，最多 8 行
                            minLines = 1,
                            maxLines = 8,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    TextButton(
                        onClick = { fields = fields + OutlineField("新增项", "") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("新增项+") }
                }
                // 滚动条
                ThinScrollbar(
                    scrollState = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    onSaveFields(fields.map { it.name })
                    Toast.makeText(context, "已保存当前表", Toast.LENGTH_SHORT).show()
                }) { Text("保存当前表") }
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    enabled = fields.isNotEmpty() && fields.first().value.isNotBlank(),
                    onClick = { onSave(fields) },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("保存") }
            }
        }
    )

    deleteIndex?.let { index ->
        ConfirmDialog(
            title = "删除「${fields.getOrNull(index)?.name.orEmpty()}」项？",
            message = "该项将从当前表格中移除。",
            onConfirm = { fields = fields.filterIndexed { i, _ -> i != index } },
            onDismiss = { deleteIndex = null }
        )
    }
}

/** 大纲表中的一个字段：字段名 + 值 */
internal data class OutlineField(val name: String, val value: String)

/** 简易竖向滚动条 */
@Composable
private fun ThinScrollbar(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val max = scrollState.maxValue
    val viewport = scrollState.viewportSize
    if (max <= 0 || viewport <= 0) return
    val fraction = (viewport.toFloat() / (max + viewport).toFloat()).coerceIn(0.12f, 1f)
    BoxWithConstraints(modifier) {
        val trackPx = constraints.maxHeight.toFloat()
        val thumbPx = (trackPx * fraction).coerceAtLeast(24f)
        val scrollable = (trackPx - thumbPx).coerceAtLeast(0f)
        val offsetPx = scrollable * (scrollState.value.toFloat() / max.toFloat())
        Box(
            Modifier
                .offset { IntOffset(0, offsetPx.toInt()) }
                .width(4.dp)
                .height(with(LocalDensity.current) { thumbPx.toDp() })
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

/** 全屏拖拽排序页面 */
@Composable
private fun SortPage(
    title: String,
    rows: List<Pair<Long, String>>,
    onBack: () -> Unit,
    onCommit: (List<Long>) -> Unit
) {
    val order = remember { mutableStateListOf<Pair<Long, String>>().apply { addAll(rows) } }
    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onCommit(order.map { it.first }); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { onCommit(order.map { it.first }); onBack() }) { Text("完成") }
        }
        Text(
            "长按并拖动可调整顺序",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            itemsIndexed(order, key = { _, r -> r.first }) { index, row ->
                val currentIndex by rememberUpdatedState(index)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (index == draggingIndex) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (index == draggingIndex) dragOffset else 0f
                        }
                        .background(
                            if (index == draggingIndex) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = currentIndex
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    val info = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == draggingIndex } ?: return@detectDragGesturesAfterLongPress
                                    val center = info.offset + dragOffset + info.size / 2f
                                    val target = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { center >= it.offset && center <= it.offset + it.size }
                                        ?: return@detectDragGesturesAfterLongPress
                                    if (target.index != draggingIndex) {
                                        val moved = order.removeAt(draggingIndex)
                                        order.add(target.index, moved)
                                        dragOffset = info.offset + dragOffset - target.offset
                                        draggingIndex = target.index
                                    }
                                },
                                onDragEnd = {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                    onCommit(order.map { it.first })
                                },
                                onDragCancel = {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                }
                            )
                        }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        row.second,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/** 批量操作区的一个文字按钮 */
private data class BatchAction(
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 全屏章节批量管理页：多选 + 拖拽排序 + 批量操作（移动 / 删除 / 合并 / 导出 / 序号重排）。
 * 与 [SortPage] 一样是 Screen 内的全屏覆盖层，返回即退出批量模式。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatchManagePage(
    book: Book?,
    volumes: List<Volume>,
    chapters: List<Chapter>,
    onBack: () -> Unit,
    onReorder: (List<Long>) -> Unit,
    onMoveToVolume: (List<Long>, Long?) -> Unit,
    onMoveToNewVolume: (List<Long>) -> Unit,
    onDelete: (List<Long>) -> Unit,
    onMerge: (List<Long>) -> Unit,
    onRenumber: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val order = remember { mutableStateListOf<Chapter>() }
    val selected = remember { mutableStateOf(setOf<Long>()) }
    val listState = rememberLazyListState()
    var draggingIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    var showMovePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMerge by remember { mutableStateOf(false) }

    // 以数据库顺序为准重建列表，并剔除已不存在的选中项
    LaunchedEffect(chapters) {
        order.clear()
        order.addAll(chapters)
        selected.value = selected.value.intersect(chapters.map { it.id }.toSet())
    }

    val allSelected = order.isNotEmpty() && order.all { it.id in selected.value }
    val selectedIds = order.filter { it.id in selected.value }.map { it.id }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶栏：返回 / 居中标题（实时显示已选数量）/ 全选
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = order.isNotEmpty(),
                    onClick = {
                        selected.value = if (allSelected) emptySet() else order.map { it.id }.toSet()
                    }
                ) { Text(if (allSelected) "取消全选" else "全选") }
            }
            Text(
                "批量管理(${selected.value.size})",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            itemsIndexed(order, key = { _, c -> c.id }) { index, chapter ->
                val currentIndex by rememberUpdatedState(index)
                val checked = chapter.id in selected.value
                Row(
                    Modifier
                        .fillMaxWidth()
                        .zIndex(if (index == draggingIndex) 1f else 0f)
                        .graphicsLayer { translationY = if (index == draggingIndex) dragOffset else 0f }
                        .background(
                            if (index == draggingIndex) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .clickable {
                            selected.value =
                                if (checked) selected.value - chapter.id else selected.value + chapter.id
                        }
                        .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 圆形勾选框
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                if (checked) MaterialTheme.colorScheme.primary else Color.Transparent
                            )
                            .border(
                                2.dp,
                                if (checked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (checked) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        chapter.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${TextTool.countWords(chapter.content)}字",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 拖拽手柄：按住即可拖动调整顺序（拖动结束即时落库）
                    Box(
                        Modifier
                            .size(40.dp)
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingIndex = currentIndex
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val info = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == draggingIndex }
                                            ?: return@detectDragGestures
                                        val center = info.offset + dragOffset + info.size / 2f
                                        val targetItem = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { center >= it.offset && center <= it.offset + it.size }
                                            ?: return@detectDragGestures
                                        if (targetItem.index != draggingIndex) {
                                            val moved = order.removeAt(draggingIndex)
                                            order.add(targetItem.index, moved)
                                            dragOffset = info.offset + dragOffset - targetItem.offset
                                            draggingIndex = targetItem.index
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = -1
                                        dragOffset = 0f
                                        onReorder(order.map { it.id })
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffset = 0f
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "拖动排序",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        // 底部批量操作区：两行三列
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            shadowElevation = 12.dp
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                BatchActionRow(
                    listOf(
                        BatchAction("移动至", enabled = selectedIds.isNotEmpty()) { showMovePicker = true },
                        BatchAction("删除所选", enabled = selectedIds.isNotEmpty(), danger = true) {
                            confirmDelete = true
                        },
                        BatchAction("合并为单章", enabled = selectedIds.size >= 2) { confirmMerge = true }
                    )
                )
                BatchActionRow(
                    listOf(
                        BatchAction("合并生成TXT", enabled = selectedIds.isNotEmpty()) {
                            book?.let { b ->
                                val sel = order.filter { it.id in selected.value }
                                scope.launch {
                                    val f = ExportUtil.chaptersToTxt(context, b, sel)
                                    ExportUtil.shareFile(context, f, "text/plain", "分享章节")
                                }
                            }
                        },
                        BatchAction("合并生成WORD", enabled = selectedIds.isNotEmpty()) {
                            book?.let { b ->
                                val sel = order.filter { it.id in selected.value }
                                scope.launch {
                                    val f = ExportUtil.bookToWord(context, b, sel, singleFile = true)
                                    ExportUtil.shareFile(context, f, "application/msword", "分享章节")
                                }
                            }
                        },
                        BatchAction("章节序号重排", enabled = order.isNotEmpty()) { onRenumber() }
                    )
                )
            }
        }
    }

    // 移动至：选择目标分卷（含新建分卷）
    if (showMovePicker) {
        val ids = selectedIds
        ActionMenuDialog(
            title = "移动至分卷（已选 ${ids.size} 章）",
            actions = buildList {
                add("（未分卷）" to { onMoveToVolume(ids, null); showMovePicker = false })
                volumes.forEach { v -> add(v.title to { onMoveToVolume(ids, v.id); showMovePicker = false }) }
                add("新建分卷并移入" to { onMoveToNewVolume(ids); showMovePicker = false })
            },
            onDismiss = { showMovePicker = false }
        )
    }

    if (confirmDelete) {
        val count = selectedIds.size
        ConfirmDialog(
            title = "删除已选的 $count 个章节？",
            message = "所选章节的正文与梗概将无法恢复。",
            onConfirm = { onDelete(selectedIds) },
            onDismiss = { confirmDelete = false }
        )
    }

    if (confirmMerge) {
        val count = selectedIds.size
        ConfirmDialog(
            title = "将已选的 $count 个章节合并为单章？",
            message = "合并后原章节将被删除，合并结果无法自动拆分。",
            confirmText = "合并",
            onConfirm = { onMerge(selectedIds) },
            onDismiss = { confirmMerge = false }
        )
    }
}

/** 批量操作区的一行：三个等宽文字按钮 */
@Composable
private fun BatchActionRow(buttons: List<BatchAction>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        buttons.forEach { b ->
            TextButton(
                onClick = b.onClick,
                enabled = b.enabled,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                colors = if (b.danger) ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.textButtonColors()
            ) {
                Text(
                    b.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

/** 统一样式的操作菜单弹窗 */
@Composable
private fun ActionMenuDialog(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                actions.forEachIndexed { i, (label, action) ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { action() }
                            .padding(vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (label.startsWith("删除")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** 统一样式的新增标签弹窗 */
@Composable
private fun AddLabelDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        icon = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
        },
        title = { Text("新增标签", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    "输入新的分类标签，如「设定」「伏笔」等",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("标签名") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun NameInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}