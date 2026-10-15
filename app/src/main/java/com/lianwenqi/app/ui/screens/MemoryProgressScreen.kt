package com.lianwenqi.app.ui.screens

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
import androidx.compose.material3.Button
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

    private val _progress = MutableStateFlow<List<BookMemoryProgress>>(emptyList())
    val progress: StateFlow<List<BookMemoryProgress>> = _progress

    /** 书本筛选：null = 全部 */
    val selectedBookId = MutableStateFlow<Long?>(null)

    /** 正在进行的向量化进度；为空表示空闲 */
    val vectorizing = MutableStateFlow<VectorizeProgress?>(null)

    private var job: Job? = null

    init {
        viewModelScope.launch {
            books.collectLatest { _progress.value = compute() }
        }
    }

    fun select(bookId: Long?) {
        selectedBookId.value = bookId
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

/** 向量化进度：可按书筛选，展示「已向量化至 第X卷 第Y章 / 共N章」，仅统计正文 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryProgressScreen(onBack: () -> Unit) {
    val vm = appViewModel { MemoryProgressViewModel(it) }
    val books by vm.books.collectAsState()
    val progress by vm.progress.collectAsState()
    val selectedBookId by vm.selectedBookId.collectAsState()
    val vectorizing by vm.vectorizing.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    val filtered = if (selectedBookId == null) progress else progress.filter { it.book.id == selectedBookId }

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
                        books.firstOrNull { it.id == selectedBookId }?.title ?: "全部书籍",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("全部书籍") },
                        onClick = { vm.select(null); menuOpen = false }
                    )
                    books.forEach { book ->
                        DropdownMenuItem(
                            text = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { vm.select(book.id); menuOpen = false }
                        )
                    }
                }
            }

            // 选定了单本书时可手动触发 / 续跑向量化
            if (selectedBookId != null) {
                val running = vectorizing?.bookId == selectedBookId
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Button(
                        onClick = { selectedBookId?.let { vm.startVectorize(it) } },
                        enabled = !running
                    ) {
                        Text(if (running) "向量化中…" else "开始 / 继续向量化")
                    }
                    vectorizing?.takeIf { it.bookId == selectedBookId }?.let { p ->
                        Spacer(Modifier.height(6.dp))
                        val where = listOf(p.volumeTitle, p.chapterTitle).filter { it.isNotBlank() }
                        Text(
                            if (p.chapterTitle.isBlank()) "准备中…"
                            else "正在处理 ${where.joinToString(" ")}（${p.done}/${p.total}）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    vectorizing?.error?.let { err ->
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
                        Card(Modifier.fillMaxWidth()) {
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
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                "仅统计书籍正文；梗概与大纲不参与向量化",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun progressLine(p: BookMemoryProgress): String {
    if (p.doneChapters == 0) return "尚未向量化 / 共 ${p.totalChapters} 章"
    val at = listOf(p.lastVolumeTitle, p.lastChapterTitle).filter { it.isNotBlank() }.joinToString(" ")
    return "已向量化至 $at / 共 ${p.totalChapters} 章（已向量化 ${p.doneChapters} 章）"
}
