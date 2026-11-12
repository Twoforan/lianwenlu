package com.lianwenqi.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.data.MAX_CHAPTER_CONTENT_CHARS
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.OutlineCategory
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.style.SummarySentence
import com.lianwenqi.app.util.FormatSettings
import com.lianwenqi.app.util.StyleSettings
import com.lianwenqi.app.util.TextTool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterEditorViewModel(
    private val container: AppContainer,
    val bookId: Long,
    initialChapterId: Long
) : ViewModel() {

    private val bookRepository = container.bookRepository
    private val outlineRepository = container.outlineRepository
    private val configRepository = container.configRepository
    private val prefs = container.prefs

    var currentChapterId: Long = initialChapterId
        private set

    val book: StateFlow<Book?> =
        bookRepository.observeBook(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val chapters: StateFlow<List<Chapter>> =
        bookRepository.observeChapters(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val volumes: StateFlow<List<Volume>> =
        bookRepository.observeVolumes(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outline: StateFlow<List<OutlineItem>> =
        outlineRepository.observeOutline(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val prompts: StateFlow<List<Prompt>> =
        configRepository.observePrompts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val format: StateFlow<FormatSettings> =
        prefs.format.stateIn(viewModelScope, SharingStarted.Eagerly, FormatSettings())

    val style: StateFlow<StyleSettings> =
        prefs.style.stateIn(viewModelScope, SharingStarted.Eagerly, StyleSettings())

    val keepScreenOn: StateFlow<Boolean> =
        prefs.keepScreenOn.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var saveJob: Job? = null

    /** 当前章节正文超过 [MAX_CHAPTER_CONTENT_CHARS]，编辑页无法整章载入（会触发游标窗口崩溃） */
    private val _oversizedChapter = MutableStateFlow(false)
    val oversizedChapter: StateFlow<Boolean> = _oversizedChapter

    fun switchChapter(id: Long) {
        currentChapterId = id
    }

    suspend fun loadChapter(id: Long = currentChapterId): Chapter? {
        // 先只查长度：超长章节直接返回空，避免整章读取抛 SQLiteBlobTooBigException 闪退
        if (bookRepository.chapterContentLength(id) > MAX_CHAPTER_CONTENT_CHARS) {
            _oversizedChapter.value = true
            return null
        }
        _oversizedChapter.value = false
        return bookRepository.getChapter(id)
    }

    /** 章节梗概标签（与梗概正文分开保存，不拼接进梗概） */
    fun observeSummaryTags(chapterId: Long) =
        container.styleTagRepository.observeSummaryTags(chapterId)

    /**
     * 当前书籍绑定文风档案的已有标签。
     * 章节标签区用它过滤有效句段关联、判定「每段都有标签」以及作为选择候选。
     */
    val summaryTagProfileTags: StateFlow<List<StyleTag>> =
        book.map { it?.styleId ?: 0L }
            .distinctUntilChanged()
            .flatMapLatest { styleId ->
                if (styleId <= 0L) flowOf(emptyList())
                else container.styleTagRepository.observeProfileTags(styleId)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 用户全局标签：与档案标签一起作为句段标签的展示与多选候选 */
    val summaryTagUserTags: StateFlow<List<StyleTag>> =
        container.styleTagRepository.observeUserTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 「本章标签」区展开状态（默认收起，记住上次选择） */
    val summaryTagsExpanded: StateFlow<Boolean> =
        prefs.summaryTagsExpanded.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleSummaryTagsExpanded() = viewModelScope.launch {
        prefs.setSummaryTagsExpanded(!summaryTagsExpanded.value)
    }

    /**
     * 用户为某个梗概句段多选标签后保存：该句段原有的自动关联与旧选择一起被本次多选替换，
     * 新关联带完整句段定位，可直接参与正文抽样。
     */
    fun saveSegmentSummaryTags(
        summaryHash: String,
        segment: SummarySentence,
        tags: List<StyleTag>
    ) = viewModelScope.launch {
        runCatching {
            container.styleTagRepository.replaceSegmentSummaryTags(
                chapterId = currentChapterId,
                summaryHash = summaryHash,
                sentenceIndex = segment.index,
                sentenceStart = segment.start,
                sentenceEnd = segment.end,
                sentenceText = segment.text,
                tags = tags
            )
        }
    }

    fun deleteSummaryTag(id: Long) = viewModelScope.launch {
        runCatching { container.styleTagRepository.deleteSummaryTag(id) }
    }

    fun createChapter(volumeId: Long?, onCreated: (Long) -> Unit) = viewModelScope.launch {
        val id = bookRepository.createChapter(bookId, volumeId)
        currentChapterId = id
        onCreated(id)
    }

    /** 即时储存：输入停止后自动落库 */
    fun onChange(title: String, summary: String, content: String, cursor: Int) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            commit(currentChapterId, title, summary, content, cursor)
        }
    }

    fun saveNow(title: String, summary: String, content: String, cursor: Int) = viewModelScope.launch {
        if (_oversizedChapter.value) return@launch
        saveJob?.cancel()
        commit(currentChapterId, title, summary, content, cursor)
        bookRepository.touchBook(bookId, currentChapterId, title)
    }

    /**
     * 切换章节时保存「上一章」的缓冲区。
     * 必须显式指定章节 id，否则新建章节后 currentChapterId 已指向新章节，会把旧内容覆盖到新章节上。
     * 这里不更新书籍的「最近阅读」指针。
     */
    fun saveChapter(chapterId: Long, title: String, summary: String, content: String, cursor: Int) =
        viewModelScope.launch {
            // 超长章节没有载入正文，缓冲区是空的，落库会覆盖真实内容，必须拦掉
            if (_oversizedChapter.value) return@launch
            saveJob?.cancel()
            bookRepository.saveChapterContent(chapterId, title, summary, content, cursor)
        }

    private suspend fun commit(chapterId: Long, title: String, summary: String, content: String, cursor: Int) {
        if (_oversizedChapter.value) return
        bookRepository.saveChapterContent(chapterId, title, summary, content, cursor)
        bookRepository.touchBook(bookId, chapterId, title)
    }

    fun applyOneKeyFormat(text: String, settings: FormatSettings): String =
        TextTool.oneKeyFormat(text, settings)

    fun applySmartPunctuation(text: String, settings: FormatSettings): String =
        TextTool.smartPunctuation(text, settings)

    fun updateFormat(block: (FormatSettings) -> FormatSettings) = viewModelScope.launch {
        prefs.updateFormat(block)
    }

    fun updateStyle(block: (StyleSettings) -> StyleSettings) = viewModelScope.launch {
        prefs.updateStyle(block)
    }

    fun setKeepScreenOn(v: Boolean) = viewModelScope.launch { prefs.setKeepScreenOn(v) }

    suspend fun searchInBook(keyword: String): List<Chapter> = bookRepository.searchInBook(bookId, keyword)

    suspend fun outlineByCategory(category: OutlineCategory): List<OutlineItem> =
        outlineRepository.getOutline(bookId).filter { it.category == category.name }

    /** 抽屉里编辑大纲条目 */
    fun saveOutline(item: OutlineItem) = viewModelScope.launch { outlineRepository.saveOutline(item) }

    /** 大纲「表」模板：按书籍 + 分类存取字段名 */
    suspend fun outlineFields(category: String): List<String>? = prefs.outlineFields(bookId, category)

    fun saveOutlineFields(category: String, names: List<String>) = viewModelScope.launch {
        prefs.setOutlineFields(bookId, category, names)
    }

    /** 工具箱的 AI 取名等：直接调用当前选中的模型 */
    fun aiGenerate(prompt: String, onResult: (String) -> Unit) = viewModelScope.launch {
        val modelId = prefs.activeModelId.first()
        if (modelId <= 0L) {
            onResult("请先在「设置」中配置并选择模型")
            return@launch
        }
        val result = configRepository.sendToModel(
            modelId,
            AiRequest(
                turns = listOf(ChatTurn(ChatRole.USER, prompt)),
                model = "",
                temperature = 0f,
                maxTokens = 0
            )
        )
        onResult(if (result.success) result.content else (result.error?.userMessage ?: "生成失败"))
    }

    fun wordCount(text: String): Int = TextTool.countWords(text)
}