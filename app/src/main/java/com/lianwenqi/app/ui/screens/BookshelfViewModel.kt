package com.lianwenqi.app.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.BookSummary
import com.lianwenqi.app.data.SortMode
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.util.FileUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModel(private val container: AppContainer) : ViewModel() {

    private val bookRepository = container.bookRepository
    private val prefs = container.prefs

    val nightMode: StateFlow<Boolean> =
        prefs.nightMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val listMode: StateFlow<Boolean> =
        prefs.listMode.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val sortMode: StateFlow<String> =
        prefs.sortMode.stateIn(viewModelScope, SharingStarted.Eagerly, "TIME")

    val sortAsc: StateFlow<Boolean> =
        prefs.sortAsc.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val groups: StateFlow<List<BookGroup>> =
        bookRepository.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val books: StateFlow<List<BookSummary>> =
        combine(prefs.sortMode, prefs.sortAsc) { mode, asc -> SortMode.valueOf(mode) to asc }
            .flatMapLatest { (mode, asc) -> bookRepository.observeBookSummaries(mode, asc) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前筛选的分组 id，null 表示全部 */
    val groupFilter = MutableStateFlow<Long?>(null)

    /** 按分组筛选后的书籍列表 */
    val visibleBooks: StateFlow<List<BookSummary>> =
        combine(books, groupFilter) { list, gid ->
            if (gid == null) list else list.filter { it.book.groupId == gid }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setGroupFilter(groupId: Long?) {
        groupFilter.value = groupId
    }

    fun toggleNight() = viewModelScope.launch { prefs.setNightMode(!nightMode.value) }

    fun toggleListMode() = viewModelScope.launch { prefs.setListMode(!listMode.value) }

    fun setSort(mode: SortMode, asc: Boolean) = viewModelScope.launch {
        prefs.setSortMode(mode.name)
        prefs.setSortAsc(asc)
    }

    fun pickCover(uri: Uri, onPicked: (String?) -> Unit) {
        viewModelScope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                FileUtil.copyToInternal(container.context, uri)
            }
            onPicked(path)
        }
    }

    fun createBook(
        title: String,
        intro: String,
        coverPath: String?,
        coverColor: Int,
        volumePrefix: String,
        chapterPrefix: String,
        groupId: Long?,
        onCreated: (Long) -> Unit
    ) = viewModelScope.launch {
        val id = bookRepository.createBook(title, intro, coverPath, coverColor, volumePrefix, chapterPrefix, groupId)
        onCreated(id)
    }

    /** 保存书籍编辑结果 */
    fun updateBook(book: Book) = viewModelScope.launch { bookRepository.updateBook(book) }

    fun togglePin(book: Book) = viewModelScope.launch { bookRepository.setPinned(book.id, !book.pinned) }

    fun deleteBook(bookId: Long) = viewModelScope.launch { bookRepository.deleteBook(bookId) }

    /** 批量删除所选书籍 */
    fun deleteBooks(ids: Set<Long>) = viewModelScope.launch { ids.forEach { bookRepository.deleteBook(it) } }

    /**
     * 复制本书：新建空白书并完整复制原书大纲。
     * 大纲在大库里是数据行，此外「常驻化分类 / 自定义分类 / 字段模板」按书籍 id 存在 DataStore，
     * 必须一并迁移到新书，否则新书里已常驻化的分类会丢失。回调返回新书标题（失败为 null）。
     */
    fun copyBookOutline(bookId: Long, onCopied: (String?) -> Unit) = viewModelScope.launch {
        val newId = bookRepository.copyBookOutline(bookId)
        if (newId > 0) prefs.copyOutlinePrefs(bookId, newId)
        onCopied(if (newId > 0) bookRepository.getBook(newId)?.title else null)
    }

    fun moveToGroup(bookId: Long, groupId: Long?) = viewModelScope.launch {
        bookRepository.moveToGroup(bookId, groupId)
    }

    /** 批量把所选书籍纳入分组 */
    fun moveBooksToGroup(ids: Set<Long>, groupId: Long?) = viewModelScope.launch {
        ids.forEach { bookRepository.moveToGroup(it, groupId) }
    }

    fun createGroup(name: String) = viewModelScope.launch { bookRepository.createGroup(name) }

    /** 新建分组并返回新分组 id（创建书籍表单内现场建组用） */
    suspend fun createGroupAndGet(name: String): Long = bookRepository.createGroup(name)

    suspend fun chaptersOf(bookId: Long): List<Chapter> = bookRepository.getChapters(bookId)

    suspend fun volumesOf(bookId: Long) = bookRepository.getVolumes(bookId)
}