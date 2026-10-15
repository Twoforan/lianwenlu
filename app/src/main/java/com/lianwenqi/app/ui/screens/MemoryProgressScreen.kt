package com.lianwenqi.app.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
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
    val lastChapterTitle: String
)

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

    fun startVectorize(bookId: Long) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                memory.vectorize(bookId) { vectorizing.value = it }
            } finally {
                _progress.value = compute()
                vectorizing.value = null
            }
        }
    }

    private suspend fun compute(): List<BookMemoryProgress> = books.value.map { book ->
        val chapters = bookRepo.getChapters(book.id)
        val volumeTitles = bookRepo.getVolumes(book.id).associate { it.id to it.title }
        val done = memory.vectorizedChapterIds(book.id).toSet()
        val last = chapters.lastOrNull { it.id in done }
        BookMemoryProgress(
            book = book,
            totalChapters = chapters.size,
            doneChapters = chapters.count { it.id in done },
            lastVolumeTitle = last?.volumeId?.let { volumeTitles[it] } ?: "",
            lastChapterTitle = last?.title ?: ""
        )
    }
}

/** 向量化进度：可按分组筛选，展示「已向量化至 第X卷 第Y章 / 共N章」，仅统计正文 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryProgressScreen(onBack: () -> Unit) {
    val vm = appViewModel { MemoryProgressViewModel(it) }
    val groups by vm.groups.collectAsState()
    val progress by vm.progress.collectAsState()
    val selectedGroupId by vm.selectedGroupId.collectAsState()
    val vectorizing by vm.vectorizing.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var askBook by remember { mutableStateOf<Book?>(null) }
    // 分组筛选：null = 全部书籍，-1 = 未分组，其余为分组 id
    val filtered = progress.filter { p ->
        when (selectedGroupId) {
            null -> true
            -1L -> p.book.groupId == null
            else -> p.book.groupId == selectedGroupId
        }
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

            // 向量化进行中 / 出错时的状态（点击书本后触发）
            vectorizing?.let { p ->
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
                        val running = vectorizing?.bookId == item.book.id
                        Card(
                            Modifier
                                .fillMaxWidth()
                                // 点击书籍询问是否向量化；向量化进行中时不可点
                                .clickable(enabled = vectorizing == null) { askBook = item.book }
                        ) {
                            Column(Modifier.padding(12.dp)) {
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
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "点击书籍可开始 / 继续向量化（已向量化的章节会自动跳过）；" +
                                    "仅统计书籍正文，梗概与大纲不参与向量化",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    askBook?.let { book ->
        AlertDialog(
            onDismissRequest = { askBook = null },
            title = { Text("向量化") },
            text = {
                Text(
                    "是否向量化《${book.title}》？\n\n" +
                        "将从全书正文开始向量化，已向量化的章节会自动跳过。"
                )
            },
            confirmButton = {
                TextButton(onClick = { askBook = null; vm.startVectorize(book.id) }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { askBook = null }) { Text("取消") } }
        )
    }
}

/** 分组筛选按钮文案：null=全部书籍，-1=未分组，其余取分组名（找不到时回退「全部书籍」） */
private fun filterLabel(selectedGroupId: Long?, groups: List<BookGroup>): String = when (selectedGroupId) {
    null -> "全部书籍"
    -1L -> "未分组"
    else -> groups.firstOrNull { it.id == selectedGroupId }?.name ?: "全部书籍"
}

private fun progressLine(p: BookMemoryProgress): String {
    if (p.doneChapters == 0) return "尚未向量化 / 共 ${p.totalChapters} 章"
    val at = listOf(p.lastVolumeTitle, p.lastChapterTitle).filter { it.isNotBlank() }.joinToString(" ")
    return "已向量化至 $at / 共 ${p.totalChapters} 章（已向量化 ${p.doneChapters} 章）"
}
