package com.lianwenqi.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.defaultOutlineKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookDetailViewModel(
    private val container: AppContainer,
    private val bookId: Long
) : ViewModel() {

    private val bookRepository = container.bookRepository
    private val outlineRepository = container.outlineRepository

    val book: StateFlow<Book?> =
        bookRepository.observeBook(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val volumes: StateFlow<List<Volume>> =
        bookRepository.observeVolumes(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chapters: StateFlow<List<Chapter>> =
        bookRepository.observeChapters(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outline: StateFlow<List<OutlineItem>> =
        outlineRepository.observeOutline(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createVolume() = viewModelScope.launch { bookRepository.createVolume(bookId) }

    fun createChapter(volumeId: Long? = null, onCreated: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = bookRepository.createChapter(bookId, volumeId)
        onCreated(id)
    }

    fun deleteChapter(chapterId: Long) = viewModelScope.launch { bookRepository.deleteChapter(bookId, chapterId) }

    fun deleteVolume(volumeId: Long) = viewModelScope.launch { bookRepository.deleteVolume(volumeId) }

    fun renameVolume(volume: Volume, title: String) =
        viewModelScope.launch { bookRepository.renameVolume(volume, title) }

    fun renameChapter(chapter: Chapter, title: String) = viewModelScope.launch {
        bookRepository.saveChapter(chapter.copy(title = title))
    }

    fun saveOutline(item: OutlineItem) = viewModelScope.launch { outlineRepository.saveOutline(item) }

    /** 大纲字段表：未保存过时返回 null，由界面套用默认字段 */
    suspend fun outlineFields(category: String): List<String>? =
        container.prefs.outlineFields(bookId, category)

    fun saveOutlineFields(category: String, names: List<String>) = viewModelScope.launch {
        container.prefs.setOutlineFields(bookId, category, names)
    }

    fun deleteOutline(id: Long) = viewModelScope.launch { outlineRepository.deleteOutline(id) }

    /** 把某个大纲条目移动到另一个分类标签下 */
    fun moveOutline(item: OutlineItem, category: String) = viewModelScope.launch {
        if (category == item.category) return@launch
        outlineRepository.saveOutline(item.copy(category = category))
    }

    // ---------------- 大纲分类标签 ----------------

    /** 当前书籍的大纲分类标签（存储值有序列表） */
    private val _categories = MutableStateFlow(defaultOutlineKeys)
    val categories: StateFlow<List<String>> = _categories.asStateFlow()

    /** 被「常驻化」的分类标签集合 */
    private val _pinnedCategories = MutableStateFlow<Set<String>>(emptySet())
    val pinnedCategories: StateFlow<Set<String>> = _pinnedCategories.asStateFlow()

    init {
        viewModelScope.launch {
            refreshCategories()
            _pinnedCategories.value = container.prefs.pinnedOutlineCategories(bookId)
        }
    }

    private suspend fun refreshCategories() {
        _categories.value = container.prefs.outlineCategories(bookId) ?: defaultOutlineKeys
    }

    /** 常驻化 / 取消常驻化一个分类标签 */
    fun togglePinnedCategory(key: String) = viewModelScope.launch {
        val current = container.prefs.pinnedOutlineCategories(bookId)
        val next = if (key in current) current - key else current + key
        container.prefs.setPinnedOutlineCategories(bookId, next)
        _pinnedCategories.value = next
    }

    fun addCategory(label: String) = viewModelScope.launch {
        val name = label.trim()
        if (name.isEmpty()) return@launch
        val current = container.prefs.outlineCategories(bookId) ?: defaultOutlineKeys
        if (name in current) return@launch
        container.prefs.setOutlineCategories(bookId, current + name)
        refreshCategories()
    }

    /** 删除一个分类标签，并移除该标签下的全部条目 */
    fun deleteCategory(key: String) = viewModelScope.launch {
        val current = container.prefs.outlineCategories(bookId) ?: defaultOutlineKeys
        container.prefs.setOutlineCategories(bookId, current - key)
        outlineRepository.deleteOutlineByCategory(bookId, key)
        val pinned = container.prefs.pinnedOutlineCategories(bookId)
        if (key in pinned) {
            container.prefs.setPinnedOutlineCategories(bookId, pinned - key)
            _pinnedCategories.value = pinned - key
        }
        refreshCategories()
    }

    /** 重命名一个分类标签，同步迁移该标签下的条目并保留字段模板 */
    fun renameCategory(oldKey: String, newLabel: String) = viewModelScope.launch {
        val name = newLabel.trim()
        val current = container.prefs.outlineCategories(bookId) ?: defaultOutlineKeys
        if (name.isEmpty() || name == oldKey || name in current) return@launch
        container.prefs.setOutlineCategories(bookId, current.map { if (it == oldKey) name else it })
        // 字段模板跟随新分类名，避免重命名后新增条目回落到默认字段
        val fields = container.prefs.outlineFields(bookId, oldKey) ?: defaultOutlineFields(oldKey)
        container.prefs.setOutlineFields(bookId, name, fields)
        outlineRepository.renameOutlineCategory(bookId, oldKey, name)
        // 常驻化状态跟随分类一起迁移
        val pinned = container.prefs.pinnedOutlineCategories(bookId)
        if (oldKey in pinned) {
            val next = pinned - oldKey + name
            container.prefs.setPinnedOutlineCategories(bookId, next)
            _pinnedCategories.value = next
        }
        refreshCategories()
    }

    /** 排序：按传入的 id 顺序重排分卷 */
    fun reorderVolumeIds(ids: List<Long>) = viewModelScope.launch {
        val byId = volumes.value.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] }
        if (ordered.size >= 2) bookRepository.reorderVolumes(ordered)
    }

    /** 排序：按传入的 id 顺序重排章节 */
    fun reorderChapterIds(ids: List<Long>) = viewModelScope.launch {
        if (ids.size >= 2) bookRepository.reorderChapters(ids)
    }

    // ---------------- 章节批量管理 ----------------

    /** 批量删除所选章节 */
    fun deleteChapters(ids: List<Long>) = viewModelScope.launch {
        if (ids.isNotEmpty()) bookRepository.deleteChapters(bookId, ids)
    }

    /** 把所选章节移动到指定分卷（volumeId 为 null 表示未分卷） */
    fun moveChaptersToVolume(ids: List<Long>, volumeId: Long?) = viewModelScope.launch {
        if (ids.isNotEmpty()) bookRepository.moveChaptersToVolume(bookId, ids, volumeId)
    }

    /** 新建分卷并把所选章节移入 */
    fun createVolumeAndMoveChapters(ids: List<Long>) = viewModelScope.launch {
        val vid = bookRepository.createVolume(bookId)
        if (ids.isNotEmpty()) bookRepository.moveChaptersToVolume(bookId, ids, vid)
    }

    /** 把所选章节按顺序合并为一章 */
    fun mergeChapters(ids: List<Long>) = viewModelScope.launch {
        if (ids.size >= 2) bookRepository.mergeChapters(bookId, ids)
    }

    /** 章节序号重排 */
    fun renumberChapters() = viewModelScope.launch {
        bookRepository.renumberChapters(bookId)
    }
}