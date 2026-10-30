package com.lianwenqi.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Schema
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.SortMode
import com.lianwenqi.app.data.VectorizeProgress
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.ui.appViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 单本书的向量化进度概览（仅统计正文） */
data class BookMemoryProgress(
    val book: Book,
    val totalChapters: Int,
    val doneChapters: Int,
    /** 最后已向量化章节所在的卷标题（无卷时为空） */
    val lastVolumeTitle: String,
    /** 最后已向量化章节的标题 */
    val lastChapterTitle: String,
    /** 正文改动后尚未重新向量化的章节数 */
    val outdatedChapters: Int = 0
)

/** 详情页里单个章节的向量化状态 */
data class ChapterMemoryStatus(
    val id: Long,
    val title: String,
    val vectorized: Boolean,
    /** 已有向量但正文已改动，需要重新向量化 */
    val outdated: Boolean = false
)

/** 详情页里的一卷（[volumeId] 为 null 表示未分卷章节） */
data class VolumeMemoryStatus(val volumeId: Long?, val title: String, val chapters: List<ChapterMemoryStatus>)

/** 某本书的向量化详情：按卷分组展示每章是否已向量化 */
data class BookMemoryDetail(val book: Book, val volumes: List<VolumeMemoryStatus>)

class MemoryProgressViewModel(private val container: AppContainer) : ViewModel() {
    private val bookRepo: BookRepository = container.bookRepository
    private val memory = container.memoryRepository

    val books: StateFlow<List<Book>> = bookRepo.observeBooks(SortMode.TIME, false)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 分组列表（下拉筛选用） */
    val groups: StateFlow<List<BookGroup>> = bookRepo.observeGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _progress = MutableStateFlow<List<BookMemoryProgress>>(emptyList())
    val progress: StateFlow<List<BookMemoryProgress>> = _progress

    /** 当前打开的书籍向量化详情；为空表示停留在列表页 */
    private val _detail = MutableStateFlow<BookMemoryDetail?>(null)
    val detail: StateFlow<BookMemoryDetail?> = _detail

    /** 分组筛选：null = 全部书籍，-1 = 未分组，其余为分组 id */
    val selectedGroupId = MutableStateFlow<Long?>(null)

    /** 正在进行的向量化进度；为空表示空闲 */
    val vectorizing = MutableStateFlow<VectorizeProgress?>(null)

    private var job: Job? = null

    init {
        viewModelScope.launch {
            books.collectLatest { _progress.value = compute() }
        }
    }

    fun selectGroup(groupId: Long?) {
        selectedGroupId.value = groupId
    }

    /** 打开某本书的向量化详情（每卷每章的已向量化状态） */
    fun openDetail(bookId: Long) {
        viewModelScope.launch { _detail.value = computeDetail(bookId) }
    }

    fun closeDetail() {
        _detail.value = null
    }

    /** 更新向量化：匹配章节是否有更新，仅对变化的章节重跑 */
    fun startVectorize(bookId: Long) = runVectorizeOp {
        memory.vectorize(bookId) { vectorizing.value = it }
    }

    /** 重新向量化：删除本书全部旧向量后全量重建 */
    fun revectorize(bookId: Long) = runVectorizeOp {
        memory.revectorize(bookId) { vectorizing.value = it }
    }

    /** 重新向量化单章：删除该章旧向量后重建这一章 */
    fun revectorizeChapter(bookId: Long, chapterId: Long) = runVectorizeOp {
        memory.revectorizeChapter(bookId, chapterId) { vectorizing.value = it }
    }

    /** 串行执行一次向量化任务，结束后同时刷新列表概览与详情页状态 */
    private fun runVectorizeOp(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                block()
            } finally {
                _progress.value = compute()
                _detail.value?.let { _detail.value = computeDetail(it.book.id) }
                vectorizing.value = null
            }
        }
    }

    private suspend fun compute(): List<BookMemoryProgress> = books.value.map { book ->
        val chapters = bookRepo.getChapters(book.id)
        val volumeTitles = bookRepo.getVolumes(book.id).associate { it.id to it.title }
        // 「已向量化」以向量块记录的正文时间为准：正文改动后旧块仍在，但必须显示为未完成，
        // 否则用户会以为自动更新生效了，实际检索仍命中旧正文
        val times = memory.vectorizedTimes(book.id)
        fun upToDate(ch: com.lianwenqi.app.data.entity.Chapter) =
            ch.content.isNotBlank() && (times[ch.id] ?: 0L) >= ch.updatedAt
        val done = chapters.filter(::upToDate)
        val outdated = chapters.count { ch ->
            ch.content.isNotBlank() && (times[ch.id] ?: 0L) > 0L && (times[ch.id] ?: 0L) < ch.updatedAt
        }
        val last = done.lastOrNull()
        BookMemoryProgress(
            book = book,
            totalChapters = chapters.size,
            doneChapters = done.size,
            lastVolumeTitle = last?.volumeId?.let { volumeTitles[it] } ?: "",
            lastChapterTitle = last?.title ?: "",
            outdatedChapters = outdated
        )
    }

    private suspend fun computeDetail(bookId: Long): BookMemoryDetail? {
        val book = books.value.firstOrNull { it.id == bookId } ?: return null
        val chapters = bookRepo.getChapters(bookId)
        val times = memory.vectorizedTimes(bookId)
        fun status(ch: com.lianwenqi.app.data.entity.Chapter): ChapterMemoryStatus {
            val vectorizedAt = times[ch.id]
            val hasContent = ch.content.isNotBlank()
            return ChapterMemoryStatus(
                id = ch.id,
                title = ch.title,
                vectorized = hasContent && vectorizedAt != null,
                outdated = hasContent && vectorizedAt != null && vectorizedAt < ch.updatedAt
            )
        }
        val sections = ArrayList<VolumeMemoryStatus>()
        bookRepo.getVolumes(bookId).forEach { v ->
            sections += VolumeMemoryStatus(v.id, v.title, chapters.filter { it.volumeId == v.id }.map(::status))
        }
        val loose = chapters.filter { it.volumeId == null }
        if (loose.isNotEmpty()) sections += VolumeMemoryStatus(null, "未分卷", loose.map(::status))
        return BookMemoryDetail(book, sections)
    }
}

/** 向量化进度：可按分组筛选；每本书可「更新向量化 / 重新向量化」，点击书籍查看逐章状态 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryProgressScreen(onBack: () -> Unit) {
    val vm = appViewModel { MemoryProgressViewModel(it) }
    val groups by vm.groups.collectAsState()
    val progress by vm.progress.collectAsState()
    val detail by vm.detail.collectAsState()
    val selectedGroupId by vm.selectedGroupId.collectAsState()
    val vectorizing by vm.vectorizing.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var askUpdate by remember { mutableStateOf<Book?>(null) }
    var askRebuild by remember { mutableStateOf<Book?>(null) }
    // 分组筛选：null = 全部书籍，-1 = 未分组，其余为分组 id
    val filtered = progress.filter { p ->
        when (selectedGroupId) {
            null -> true
            -1L -> p.book.groupId == null
            else -> p.book.groupId == selectedGroupId
        }
    }

    // 详情页打开时，系统返回键先关闭详情、回到「向量化进度」列表页
    BackHandler(enabled = detail != null) { vm.closeDetail() }

    // 书籍详情页：全屏覆盖
    val opened = detail
    if (opened != null) {
        MemoryDetailPage(
            detail = opened,
            vectorizing = vectorizing,
            onBack = { vm.closeDetail() },
            onRevertChapter = { cid -> vm.revectorizeChapter(opened.book.id, cid) }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("向量化进度") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(
                        filterLabel(selectedGroupId, groups),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("全部书籍") },
                        onClick = { vm.selectGroup(null); menuOpen = false }
                    )
                    // 各个分组
                    groups.forEach { g ->
                        DropdownMenuItem(
                            text = { Text(g.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { vm.selectGroup(g.id); menuOpen = false }
                        )
                    }
                    // 未分组
                    DropdownMenuItem(
                        text = { Text("未分组") },
                        onClick = { vm.selectGroup(-1L); menuOpen = false }
                    )
                }
            }

            VectorizeStatus(vectorizing)

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无可统计的书籍", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.book.id }) { item ->
                        val busy = vectorizing != null
                        val running = vectorizing?.bookId == item.book.id
                        Card(
                            Modifier
                                .fillMaxWidth()
                                // 点击书籍查看各章节向量化情况；向量化进行中时不可点
                                .clickable(enabled = !busy) { vm.openDetail(item.book.id) }
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.book.title,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        progressLine(item),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (running) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "向量化中…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                // 文字区与操作区之间的竖分隔线
                                Box(
                                    Modifier
                                        .padding(horizontal = 6.dp)
                                        .width(1.dp)
                                        .height(40.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant)
                                )
                                VectorizeIconButton(
                                    icon = Icons.Outlined.Schema,
                                    label = "重新向量化",
                                    enabled = !busy
                                ) { askRebuild = item.book }
                                VectorizeIconButton(
                                    icon = Icons.Filled.Sync,
                                    label = "更新向量化",
                                    enabled = !busy
                                ) { askUpdate = item.book }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "点击书籍可查看各章节的向量化状态；左侧图标为「重新向量化」（删除旧向量后全量重建），" +
                                    "右侧图标为「更新向量化」（仅重跑有更新的章节）；仅统计书籍正文，梗概与大纲不参与向量化",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // 更新向量化：增量匹配
    askUpdate?.let { book ->
        AlertDialog(
            onDismissRequest = { askUpdate = null },
            title = { Text("更新向量化") },
            text = {
                Text(
                    "是否更新《${book.title}》的向量化？\n\n" +
                        "将对比章节内容，仅对已更新的章节重新向量化，未变化的章节会自动跳过。"
                )
            },
            confirmButton = {
                TextButton(onClick = { askUpdate = null; vm.startVectorize(book.id) }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { askUpdate = null }) { Text("取消") } }
        )
    }

    // 重新向量化：先删旧映射，再全量重建
    askRebuild?.let { book ->
        AlertDialog(
            onDismissRequest = { askRebuild = null },
            title = { Text("重新向量化") },
            text = {
                Text(
                    "是否重新向量化《${book.title}》？\n\n" +
                        "将先删除该书已有的全部向量，再按当前向量源重新建立，全书章节都会被重跑。"
                )
            },
            confirmButton = {
                TextButton(onClick = { askRebuild = null; vm.revectorize(book.id) }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { askRebuild = null }) { Text("取消") } }
        )
    }
}

/** 书籍向量化详情：按卷分组列出每章是否已向量化，点击章节可重新建立该章向量 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryDetailPage(
    detail: BookMemoryDetail,
    vectorizing: VectorizeProgress?,
    onBack: () -> Unit,
    onRevertChapter: (Long) -> Unit
) {
    var askChapter by remember { mutableStateOf<ChapterMemoryStatus?>(null) }
    val total = detail.volumes.sumOf { it.chapters.size }
    val done = detail.volumes.sumOf { s -> s.chapters.count { it.vectorized && !it.outdated } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(detail.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            VectorizeStatus(vectorizing)

            if (total == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("本书暂无章节", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        Text(
                            "已向量化 $done / 共 $total 章",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    detail.volumes.forEach { section ->
                        item(key = "vol-${section.volumeId ?: "loose"}") {
                            Text(
                                section.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        items(section.chapters, key = { "ch-${it.id}" }) { chapter ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    // 点击章节：删除该章旧向量后重新建立
                                    .clickable(enabled = vectorizing == null) { askChapter = chapter }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    chapter.title,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val statusLabel = when {
                                    !chapter.vectorized -> "未向量化"
                                    chapter.outdated -> "需要更新"
                                    else -> "已向量化"
                                }
                                Text(
                                    statusLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        !chapter.vectorized -> MaterialTheme.colorScheme.onSurfaceVariant
                                        chapter.outdated -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.primary
                                    }
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                    item {
                        Text(
                            "点击章节可重新建立该章的向量化（原有向量会被删除后重建）",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    askChapter?.let { c ->
        AlertDialog(
            onDismissRequest = { askChapter = null },
            title = { Text("重新向量化「${c.title}」？") },
            text = { Text("将先删除该章已有的向量，再重新建立。") },
            confirmButton = {
                TextButton(onClick = { askChapter = null; onRevertChapter(c.id) }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { askChapter = null }) { Text("取消") } }
        )
    }
}

/** 每个书籍行右侧的图标按钮：淡紫圆角方块 + 图标 */
@Composable
private fun VectorizeIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bg = if (enabled) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (enabled) MaterialTheme.colorScheme.onSecondaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .padding(start = 4.dp)
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** 向量化进行中 / 出错时的状态条；空闲时什么都不显示 */
@Composable
private fun VectorizeStatus(vectorizing: VectorizeProgress?) {
    if (vectorizing == null) return
    val p = vectorizing
    Column(Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(6.dp))
        val where = listOf(p.volumeTitle, p.chapterTitle).filter { it.isNotBlank() }
        Text(
            if (p.chapterTitle.isBlank()) "准备中…"
            else "正在处理 ${where.joinToString(" ")}（${p.done}/${p.total}）",
            style = MaterialTheme.typography.bodySmall
        )
        p.error?.let { err ->
            Spacer(Modifier.height(4.dp))
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
}

/** 分组筛选按钮文案：null=全部书籍，-1=未分组，其余取分组名（找不到时回退「全部书籍」） */
private fun filterLabel(selectedGroupId: Long?, groups: List<BookGroup>): String = when (selectedGroupId) {
    null -> "全部书籍"
    -1L -> "未分组"
    else -> groups.firstOrNull { it.id == selectedGroupId }?.name ?: "全部书籍"
}

private fun progressLine(p: BookMemoryProgress): String {
    val stale = if (p.outdatedChapters > 0) "，${p.outdatedChapters} 章需要更新" else ""
    if (p.doneChapters == 0) return "尚未向量化 / 共 ${p.totalChapters} 章$stale"
    val at = listOf(p.lastVolumeTitle, p.lastChapterTitle).filter { it.isNotBlank() }.joinToString(" ")
    return "已向量化至 $at / 共 ${p.totalChapters} 章（已向量化 ${p.doneChapters} 章$stale）"
}
