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
import com.lianwenqi.app.data.style.StyleLibraryState
import com.lianwenqi.app.data.style.StyleProfileRow
import com.lianwenqi.app.data.style.StyleProfileSummaryReader
import com.lianwenqi.app.util.FileUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModel(private val container: AppContainer) : ViewModel() {

    companion object {
        const val STYLE_UNAVAILABLE_MESSAGE = "所选文风档案已不存在，请重新选择或改为「不使用文风」"
        private const val BOOK_UNAVAILABLE_MESSAGE = "书籍已不存在或已被删除，无法保存修改"
    }

    private val bookRepository = container.bookRepository
    private val styleRepository = container.styleRepository
    private val prefs = container.prefs

    private val styleRetry = MutableStateFlow(0)

    /**
     * 新建书籍时可选的文风档案：直接订阅 F1 的持久化档案，不使用示例数据。
     * 读取失败时为错误状态（不伪装成空列表），可由 [retryStyleProfiles] 重新订阅。
     */
    val styleLibrary: StateFlow<StyleLibraryState> = styleRetry
        .flatMapLatest {
            styleRepository.observeProfiles()
                .map { profiles ->
                    StyleLibraryState(
                        loading = false,
                        rows = profiles.map { profile ->
                            StyleProfileRow(profile, StyleProfileSummaryReader.read(profile))
                        }
                    )
                }
                // 放在内层：读取失败不终止外层重试流，重新订阅时仍会重新查询
                .catch { error ->
                    emit(
                        StyleLibraryState(
                            loading = false,
                            error = "读取文风档案失败：${error.message ?: "未知错误"}"
                        )
                    )
                }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StyleLibraryState())

    /** 重新订阅文风档案列表（加载失败或档案被删除后刷新） */
    fun retryStyleProfiles() {
        styleRetry.value++
    }

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

    /**
     * 新建书籍并保存文风绑定。
     * 先经 StyleRepository 确认所选档案仍存在；创建后回读书籍记录确认 styleId 已正确落库。
     * 失败时不回调 [onCreated]（界面不会显示创建成功），并给出明确原因。
     */
    fun createBook(
        title: String,
        intro: String,
        coverPath: String?,
        coverColor: Int,
        volumePrefix: String,
        chapterPrefix: String,
        groupId: Long?,
        styleId: Long,
        onCreated: (Long) -> Unit,
        onError: (String) -> Unit
    ) = viewModelScope.launch {
        try {
            if (styleId != 0L && styleRepository.getProfile(styleId) == null) {
                retryStyleProfiles()
                onError(STYLE_UNAVAILABLE_MESSAGE)
                return@launch
            }
            val id = bookRepository.createBook(
                title, intro, coverPath, coverColor, volumePrefix, chapterPrefix, groupId, styleId
            )
            val saved = bookRepository.getBook(id)
            if (saved == null || saved.styleId != styleId) {
                onError("书籍已创建，但文风绑定未能确认，请重新打开书籍检查")
                return@launch
            }
            onCreated(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // 事务内档案存在性校验失败（选择后档案恰好被删除）
            retryStyleProfiles()
            onError(STYLE_UNAVAILABLE_MESSAGE)
        } catch (e: Exception) {
            onError("创建书籍失败：${e.message ?: "未知错误"}")
        }
    }

    /** 正在保存的书籍 id：避免重复点击「保存」时并发写入互相覆盖 */
    private val savingBookIds = mutableSetOf<Long>()

    /**
     * 保存书籍编辑结果（含文风换绑 / 解绑）。
     * 先读取数据库最新记录再合并表单字段，避免用打开弹窗时的旧快照覆盖其他已保存字段；
     * 由 Repository 在事务内校验书籍与目标档案仍然存在，保存后回读确认 styleId 已落库。
     * 成功才回调 [onUpdated]，失败回调 [onError] 且不关闭编辑界面，原有绑定保持不变。
     */
    fun updateBook(
        bookId: Long,
        title: String,
        intro: String,
        coverPath: String?,
        coverColor: Int,
        volumePrefix: String,
        chapterPrefix: String,
        groupId: Long?,
        styleId: Long,
        onUpdated: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (bookId in savingBookIds) return
        savingBookIds += bookId
        viewModelScope.launch {
            try {
                val latest = bookRepository.getBook(bookId)
                if (latest == null) {
                    onError(BOOK_UNAVAILABLE_MESSAGE)
                    return@launch
                }
                val saved = bookRepository.updateBook(
                    latest.copy(
                        title = title,
                        intro = intro,
                        coverPath = coverPath,
                        coverColor = coverColor,
                        volumePrefix = volumePrefix,
                        chapterPrefix = chapterPrefix,
                        groupId = groupId,
                        styleId = styleId
                    )
                )
                if (!saved) {
                    onError(BOOK_UNAVAILABLE_MESSAGE)
                    return@launch
                }
                // 回读确认：以数据库中的真实结果为准，避免只改了界面状态
                val confirmed = bookRepository.getBook(bookId)
                when {
                    confirmed == null -> onError(BOOK_UNAVAILABLE_MESSAGE)
                    confirmed.styleId != styleId ->
                        onError("书籍已保存，但文风绑定未能确认，请重新打开书籍设置检查")
                    else -> onUpdated()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                // 保存过程中目标档案被删除：事务已回滚，原绑定保留
                retryStyleProfiles()
                onError(STYLE_UNAVAILABLE_MESSAGE)
            } catch (e: Exception) {
                onError("保存书籍失败：${e.message ?: "未知错误"}")
            } finally {
                savingBookIds -= bookId
            }
        }
    }

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