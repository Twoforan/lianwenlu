package com.lianwenqi.app.ui.screens

import android.net.Uri
import android.util.Log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.FlowState
import com.lianwenqi.app.ai.GenerationDialog
import com.lianwenqi.app.ai.GenerationDialogKind
import com.lianwenqi.app.ai.GenerationMode
import com.lianwenqi.app.ai.GenerationPlan
import com.lianwenqi.app.ai.MemoryScopeChapter
import com.lianwenqi.app.ai.MemoryScopePick
import com.lianwenqi.app.ai.MemoryScopeVolume
import com.lianwenqi.app.ai.RefreshRetrievalChoice
import com.lianwenqi.app.ai.RetrievalMode
import com.lianwenqi.app.ai.SUMMARY_FALLBACK_INPUT
import com.lianwenqi.app.ai.CONTENT_FALLBACK_INPUT
import com.lianwenqi.app.ai.HISTORY_CONTENT_HEADER
import com.lianwenqi.app.ai.HISTORY_DIALOGUE_HEADER
import com.lianwenqi.app.ai.HISTORY_SUMMARY_HEADER
import com.lianwenqi.app.ai.SummaryFailureChoice
import com.lianwenqi.app.ai.buildContentTurns
import com.lianwenqi.app.ai.buildGenerationPrefixTurns
import com.lianwenqi.app.ai.buildRetrievalQuery
import com.lianwenqi.app.ai.buildStyleSampleBlock
import com.lianwenqi.app.ai.buildSummaryReferenceBlock
import com.lianwenqi.app.ai.buildSummarySentenceTagBlock
import com.lianwenqi.app.ai.buildSummaryTurns
import com.lianwenqi.app.ai.contentDialogOrder
import com.lianwenqi.app.ai.enforceWordRange
import com.lianwenqi.app.data.AiChapterImporter
import com.lianwenqi.app.data.AiOutlineImporter
import com.lianwenqi.app.data.AttachmentStore
import com.lianwenqi.app.data.ChatAttachment
import com.lianwenqi.app.data.ChatEvent
import com.lianwenqi.app.data.GEN_KIND_SUMMARY
import com.lianwenqi.app.data.HistoryChapters
import com.lianwenqi.app.data.HistoryContentTurn
import com.lianwenqi.app.data.HistoryPrefix
import com.lianwenqi.app.data.MAX_CHAT_MESSAGE_CHARS
import com.lianwenqi.app.data.MemoryGateway
import com.lianwenqi.app.data.PromptLog
import com.lianwenqi.app.data.PromptLogContext
import com.lianwenqi.app.data.PromptLogNode
import com.lianwenqi.app.data.PromptLogRound
import com.lianwenqi.app.data.SendChatMessageUseCase
import com.lianwenqi.app.data.SendChatRequest
import com.lianwenqi.app.data.VectorizeProgress
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.ChatSession
import com.lianwenqi.app.data.entity.ContextSnapshot
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.PrefixEntry
import com.lianwenqi.app.data.entity.SessionPrefix
import com.lianwenqi.app.data.entity.decodeContextSnapshot
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.data.entity.defaultOutlineKeys
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeCompressionSession
import com.lianwenqi.app.data.entity.VolumeCompressionStatus
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.volume.CompressionEstimateResult
import com.lianwenqi.app.data.volume.CompressionStartResult
import com.lianwenqi.app.data.volume.OutlineScopeResolver
import com.lianwenqi.app.data.volume.VolumeCompressionRequest
import com.lianwenqi.app.data.volume.VolumeSummarySelection
import com.lianwenqi.app.data.VolumeCompressionConfig
import com.lianwenqi.app.data.CompressionSourceMode
import com.lianwenqi.app.util.AI_CHAPTER_TOOL_PROMPT
import com.lianwenqi.app.util.AI_OUTLINE_TOOL_PROMPT
import com.lianwenqi.app.util.AI_TOOL_IMPORT_CHAPTER
import com.lianwenqi.app.util.AI_TOOL_WRITE_OUTLINE
import com.lianwenqi.app.util.AiChapterBlock
import com.lianwenqi.app.util.AiOutlineBlock
import com.lianwenqi.app.util.detectImportKindHint
import com.lianwenqi.app.util.isChapterLikeOutlineBlock
import com.lianwenqi.app.data.style.StyleLibraryState
import com.lianwenqi.app.data.style.StyleProfileRow
import com.lianwenqi.app.data.style.StyleProfileSummaryReader
import com.lianwenqi.app.data.style.SummarySentenceSample
import com.lianwenqi.app.data.style.SummarySentenceSampler
import com.lianwenqi.app.data.style.SummarySentenceSegmenter
import com.lianwenqi.app.data.style.SummarySentenceSplitter
import com.lianwenqi.app.data.style.SummarySentenceTagResolver
import com.lianwenqi.app.data.style.SummaryTagValidity
import com.lianwenqi.app.data.style.SummaryTaggingOutcome
import com.lianwenqi.app.data.style.SummaryWithTagsCodec
import com.lianwenqi.app.util.parseAiChapterBlocks
import com.lianwenqi.app.util.parseAiOutlineBlocks
import com.lianwenqi.app.util.shortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "AiChatViewModel"

/** 消息超长的统一提示：只提示、不提交，也不做任何截断 */
private const val MSG_TOO_LONG = "消息过长，请缩短后重试"

/** 记忆检索自动向量化的日志 tag：失败不打断发送，但必须能在 logcat 里定位原因 */
private const val MEMORY_LOG_TAG = "MemoryAutoVectorize"

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModel(
    private val container: AppContainer,
    private val defaultBookId: Long? = null,
    private val fixedChapterId: Long? = null,
    /**
     * 是否允许 AI 工具（写入大纲 / 章节梗概导入）生效。
     * 导航栏 AI 聊天页为 true；编辑器侧边 AI 助手为 false —— 侧边对话不携带工具提示词，
     * 也不解析工具区块（避免勾选状态被两个界面共用后，工具提示词串到侧边对话里）。
     */
    private val enableAiTools: Boolean = true
) : ViewModel() {

    private val bookRepository = container.bookRepository
    private val chatRepository = container.chatRepository
    private val outlineRepository = container.outlineRepository
    private val configRepository = container.configRepository
    private val prefs = container.prefs
    private val styleRepository = container.styleRepository

    /** 「发送消息」用例：组装上下文 → 流式请求 → 写回助手消息 → 解析工具区块 */
    private val sendChatMessage = SendChatMessageUseCase(chatRepository, configRepository)

    val prompts: StateFlow<List<Prompt>> =
        configRepository.observePrompts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val models: StateFlow<List<AiModel>> =
        configRepository.observeModels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val providers: StateFlow<List<Provider>> =
        configRepository.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val books: StateFlow<List<Book>> =
        bookRepository.observeBooks(com.lianwenqi.app.data.SortMode.TIME, false)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 书籍分组（选书弹窗的分组筛选用） */
    val groups: StateFlow<List<BookGroup>> =
        bookRepository.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 新建书籍弹窗里的文风档案：与书架新建弹窗共用同一套读取 / 重试逻辑
    private val styleRetry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
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

    val sessions: StateFlow<List<ChatSession>> =
        chatRepository.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId.asStateFlow()

    /** 会话恢复完成信号：恢复完成前不允许兜底新建，避免切章瞬间多建一个会话 */
    private val sessionReady = CompletableDeferred<Unit>()

    /** 侧边助手（固定章节）所属书籍：以进入面板时传入的书为准，不依赖可能被重置的勾选状态 */
    private fun sidePanelBookId(): Long? = if (fixedChapterId != null) defaultBookId else null

    private companion object {
        /** 进程内「书 → 最近会话」缓存：切章重建 ViewModel 时先同步填上，首帧即原会话，避免空会话闪现代替真实会话 */
        val lastSessionByBook = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    }

    val messages: StateFlow<List<ChatMessage>> = _sessionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeModelId: StateFlow<Long> = prefs.activeModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
    val outline: StateFlow<List<OutlineItem>> = _outline.asStateFlow()

    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<Volume>> = _volumes.asStateFlow()

    // ---------------- F7：整卷梗概上下文资产 ----------------

    private val volumeSummaryRepository = container.volumeSummaryRepository
    private val compressionCoordinator = container.volumeCompressionCoordinator

    /** 当前书籍的生效卷梗概（每个分卷最多一条生效版本） */
    private val _activeVolumeSummaries = MutableStateFlow<List<VolumeSummary>>(emptyList())
    val activeVolumeSummaries: StateFlow<List<VolumeSummary>> = _activeVolumeSummaries.asStateFlow()

    /** 生效卷梗概的过期标记：summaryId → 是否可能已过期（只提示，不自动取消选择） */
    private val _volumeSummaryStale = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val volumeSummaryStale: StateFlow<Map<Long, Boolean>> = _volumeSummaryStale.asStateFlow()

    /** 正在进行的卷压缩任务：volumeId → 任务（用于分卷行展示进度，不重复创建） */
    private val _volumeSummaryTasks =
        MutableStateFlow<Map<Long, VolumeCompressionSession>>(emptyMap())
    val volumeSummaryTasks: StateFlow<Map<Long, VolumeCompressionSession>> =
        _volumeSummaryTasks.asStateFlow()

    /** 显式勾选的整卷梗概版本 id（VolumeSummary.id）；与章节材料完全独立 */
    val selectedVolumeSummaryIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 自动压缩前需要用户选择来源的卷（「每次询问」） */
    val volumeSummaryAsk = MutableStateFlow<VolumeSummaryAsk?>(null)

    /** 压缩完成、等待确认的候选卷梗概 */
    val volumeSummaryCandidate = MutableStateFlow<VolumeSummaryCandidate?>(null)

    /** 卷梗概相关的一次性提示（未生成 / 无输入 / 已开始后台压缩等） */
    val volumeSummaryMessage = MutableStateFlow<String?>(null)

    private val summaryWatchJobs = mutableMapOf<Long, Job>()

    /** 会话列表筛选：所选书籍的分卷 / 章节 */
    private val _filterVolumes = MutableStateFlow<List<Volume>>(emptyList())
    val filterVolumes: StateFlow<List<Volume>> = _filterVolumes.asStateFlow()
    private val _filterChapters = MutableStateFlow<List<Chapter>>(emptyList())
    val filterChapters: StateFlow<List<Chapter>> = _filterChapters.asStateFlow()

    val selectedBookIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedChapterIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedOutlineIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 纳入「梗概」的章节（须同时在 [selectedChapterIds] 内才生效） */
    val summaryChapterIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 纳入「正文」的章节（须同时在 [selectedChapterIds] 内才生效） */
    val contentChapterIds = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * 章节上下文是否处于「全选」状态。
     * 编辑器场景下当前打开的章节不进上下文，所以「全选」的具体 id 集合会随章节切换而变化；
     * 记录该标记后，切到别的章节时仍是「全选」而不是被还原成上一章的具体列表。
     * [summaryAllSelected] / [contentAllSelected] 同理，用于「梗概 / 正文」两列的全选状态。
     */
    val chapterAllSelected = MutableStateFlow(false)
    val summaryAllSelected = MutableStateFlow(false)
    val contentAllSelected = MutableStateFlow(false)

    /** 「＋本章梗概」：是否把当前打开章节的梗概额外加入上下文 */
    val currentSummaryOn = MutableStateFlow(false)
    val sending = MutableStateFlow(false)
    val errorMessage = MutableStateFlow<String?>(null)
    val input = MutableStateFlow("")

    /** 附件本地存储：图片压缩落盘、文件与链接正文提取 */
    private val attachmentStore = AttachmentStore(container.context)

    /** 待发送的附件（图片 / 文件 / 链接），发送时随消息落库并清空 */
    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments

    /** 联网搜索开关：仅对标记「支持联网搜索」的模型显示并生效 */
    private val _webSearchEnabled = MutableStateFlow(false)
    val webSearchEnabled: StateFlow<Boolean> = _webSearchEnabled

    fun toggleWebSearch() {
        _webSearchEnabled.value = !_webSearchEnabled.value
    }

    /** 添加图片附件：解码压缩后落盘，失败时给出提示 */
    fun attachImage(uri: Uri, name: String) = viewModelScope.launch {
        val attachment = withContext(Dispatchers.IO) { attachmentStore.saveImage(uri, name) }
        if (attachment == null) {
            errorMessage.value = "图片读取失败，请换一张试试"
        } else {
            _pendingAttachments.value = _pendingAttachments.value + attachment
        }
    }

    /** 添加文本文件附件 */
    fun attachFile(uri: Uri, name: String) = viewModelScope.launch {
        val attachment = withContext(Dispatchers.IO) { attachmentStore.saveTextFile(uri, name) }
        if (attachment == null) {
            errorMessage.value = "文件读取失败：只支持纯文本类文件，且内容不能为空"
        } else {
            _pendingAttachments.value = _pendingAttachments.value + attachment
        }
    }

    /** 添加链接附件：抓取网页正文，失败时回落为只带链接 */
    fun attachLink(url: String) = viewModelScope.launch {
        if (url.isBlank()) return@launch
        val attachment = attachmentStore.fetchLink(url.trim())
        _pendingAttachments.value = _pendingAttachments.value + attachment
    }

    /** 移除待发送附件（同时清理本地缓存文件） */
    fun removeAttachment(attachment: ChatAttachment) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it == attachment }
        attachmentStore.deleteFiles(listOf(attachment))
    }

    /**
     * 「记忆检索」勾选框（导航栏 AI 聊天页与侧边 AI 助手共用）。
     * 勾选后发送时用「本章梗概」检索本书已向量化的正文块，
     * 命中片段作为附带提示词随本轮用户消息一起落库，成为历史对话的一部分（不写会话前缀）。
     *
     * 勾选状态取用户手动勾选的持久值；开启「记忆检索是否限定范围」后，
     * 每次勾选都会先弹出章节范围选择（默认全部章节）。
     */
    private val memoryBookIdFlow: Flow<Long?> = combine(selectedBookIds, _sessionId) { ids, sid ->
        ids.singleOrNull()
            ?: sid?.let { chatRepository.getSession(it)?.bookId }
            ?: defaultBookId
    }

    private val bookVectorizedFlow: Flow<Boolean> = memoryBookIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(false)
        else container.memoryRepository.observeVectorizedChapterIds(id).map { it.isNotEmpty() }
    }

    /**
     * 向量化工具是否已配置可用（API 模式需填齐 baseUrl / apiKey / 向量模型名，本地模式需已勾选）。
     * 未配置时「记忆检索」不可勾选。
     */
    val memoryToolConfigured: StateFlow<Boolean> = prefs.memoryVectorizationReady
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 当前章节是否有梗概。记忆检索的 query 就是本章梗概，没有梗概则无从匹配。
     * 编辑器侧边助手场景跟随章节变化实时刷新；导航栏 AI 对话没有「本章」，恒为 true
     * （查询改回退用本轮提示词）。
     */
    private val chapterSummaryReady: Flow<Boolean> = fixedChapterId?.let { id ->
        bookRepository.observeChapter(id).map { !it?.summary.isNullOrBlank() }
    } ?: flowOf(true)

    /** 记忆检索的可用上下文：绑定书籍、本书是否已向量化、本章是否有梗概 */
    private data class MemoryAvailability(
        val bookId: Long?,
        val vectorized: Boolean,
        val summaryReady: Boolean
    )

    private val memoryAvailability: Flow<MemoryAvailability> =
        combine(memoryBookIdFlow, bookVectorizedFlow, chapterSummaryReady) { bookId, vectorized, summaryReady ->
            MemoryAvailability(bookId, vectorized, summaryReady)
        }

    val memoryRetrievalEnabled: StateFlow<Boolean> = combine(
        prefs.memoryRetrievalEnabled, memoryToolConfigured, memoryAvailability
    ) { manual, toolReady, avail ->
        // 未配置向量化工具、或本章没有梗概（无从匹配）时一律不生效；否则取用户手动勾选的持久值
        toolReady && avail.summaryReady && manual
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 「文风样式」勾选框（默认关闭，持久化上次选择）。
     * 只有本次生成请求勾选了它，才对本章梗概句子自动打标、生成正文时抽样注入参考块；
     * 未勾选时只生成梗概 / 正文，不调用标签分析模型。
     */
    val styleEnabled: StateFlow<Boolean> =
        prefs.genStyleEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleStyleEnabled() = viewModelScope.launch {
        prefs.setGenStyleEnabled(!styleEnabled.value)
    }

    /** 勾选记忆检索时的提示（Toast 用，展示后由 UI 清空） */
    val memoryToast = MutableStateFlow<String?>(null)

    /**
     * 记忆检索的进行状态（供界面显示「正在检索中 / 检索完成」）。
     * 本地向量化工具属于本机 CPU 推理，一轮检索需要向量化 query + 重排候选，
     * 首次还会加载重排模型，耗时明显；这里如实反映检索的真实生命周期，不用定时器假装。
     */
    val memorySearchState = MutableStateFlow(MemorySearchState.IDLE)

    fun clearMemoryToast() {
        memoryToast.value = null
    }

    /** 未选择书籍 / 未向量化时的确认弹窗 */
    val pendingEmptyVectorizeAsk = MutableStateFlow(false)

    /** 用户为某本书选定的记忆检索范围（章节 id） */
    private data class MemoryScopeSelection(val bookId: Long, val chapterIds: Set<Long>)

    private val memoryScopeSelection = MutableStateFlow<MemoryScopeSelection?>(null)

    /** 勾选记忆检索后待确认的检索范围；非空时界面弹窗（开启「限定范围」时才出现） */
    val pendingMemoryScopePick = MutableStateFlow<MemoryScopePick?>(null)

    /** 「仅本会话首次询问」：本会话是否已经确认过检索范围 */
    private var memoryScopeAskedInSession = false

    /** 选定范围时的向量化快照；之后新向量化 / 更新的章节会自动纳入检索 */
    private var memoryScopeVectorSnapshot: Map<Long, Long> = emptyMap()

    /**
     * 切换记忆检索：
     * - 取消勾选：直接关闭持久勾选；
     * - 勾选：必须先有书籍（未选书时提示，不勾选）；本书从未向量化时先弹窗询问是否向量化全书；
     *   本书已向量化但正文有更新时，先带进度提示补齐增量向量化，完成后才继续；
     *   开启「限定范围」时接着让用户从**已向量化的章节**中选择检索范围。
     */
    fun toggleMemoryRetrieval() = viewModelScope.launch {
        if (memoryRetrievalEnabled.value) {
            // 取消勾选后不再检索，旧的「检索完成」标记同步清掉，避免误导
            memorySearchState.value = MemorySearchState.IDLE
            prefs.setMemoryRetrievalEnabled(false)
            return@launch
        }
        if (!memoryToolConfigured.value) {
            memoryToast.value = "请先在设置中配置向量化工具（选择向量模型或启用本地工具）"
            return@launch
        }
        // 记忆检索以本章梗概为查询条件，本章没有梗概就无从匹配
        fixedChapterId?.let { chapterId ->
            if (bookRepository.getChapter(chapterId)?.summary.isNullOrBlank()) {
                memoryToast.value = "本章暂无梗概，无法进行记忆检索匹配"
                return@launch
            }
        }
        val bookId = currentMemoryBookId()
        if (bookId == null) {
            memoryToast.value = "请先选择书籍，再进行记忆检索"
            return@launch
        }
        // 已有向量化在进行：忽略这次点击，避免重复触发同一本书的向量化
        if (memoryVectorizing.value != null) return@launch
        // 本书还没有任何向量化结果：先询问是否向量化，等向量化完成后再选范围 / 勾选
        if (container.memoryRepository.latestVectorizedUpdatedAt(bookId) == null) {
            pendingEmptyVectorizeAsk.value = true
            return@launch
        }
        // 已向量化过但正文有更新：先补齐增量向量化（界面显示进度、按钮置灰），完成后再继续
        if (hasNewerContentThanVectorized(bookId)) {
            memoryVectorizing.value = VectorizeProgress(bookId, running = true)
            var failed: String? = null
            runCatching {
                container.memoryRepository.vectorize(bookId) { progress ->
                    if (progress.error != null) failed = progress.error
                    memoryVectorizing.value = progress
                }
            }.onFailure {
                failed = it.message ?: "向量化失败"
                Log.w(MEMORY_LOG_TAG, "勾选记忆检索时向量化失败", it)
            }
            memoryVectorizing.value = null
            // 失败或仍有未补齐的正文：不勾选，避免带着旧向量做检索
            if (failed != null || hasNewerContentThanVectorized(bookId)) {
                memoryToast.value = "向量化未完成：${failed ?: "请稍后重试"}"
                return@launch
            }
            memoryToast.value = "向量化完成"
        }
        requestMemoryScopeOrEnable(bookId)
    }

    /** 范围弹窗确认：记住本书的检索范围并真正勾选记忆检索 */
    fun confirmMemoryScope(ids: Set<Long>) = viewModelScope.launch {
        if (pendingMemoryScopePick.value == null) return@launch
        pendingMemoryScopePick.value = null
        if (ids.isEmpty()) {
            memoryToast.value = "请至少选择一个章节"
            return@launch
        }
        val bookId = currentMemoryBookId() ?: return@launch
        memoryScopeSelection.value = MemoryScopeSelection(bookId, ids)
        memoryScopeAskedInSession = true
        memoryScopeVectorSnapshot = container.memoryRepository.vectorizedTimes(bookId)
        prefs.setMemoryRetrievalEnabled(true)
    }

    fun cancelMemoryScope() {
        pendingMemoryScopePick.value = null
    }

    /**
     * 已有向量化结果时继续勾选流程：开启「限定范围」先弹范围选择（只列已向量化章节），
     * 否则直接勾选、检索覆盖全书。
     */
    private suspend fun requestMemoryScopeOrEnable(bookId: Long) {
        if (prefs.memoryScopeLimitEnabled.first()) {
            // 开启「仅本会话首次询问」且本会话已确认过范围：直接沿用，不再弹窗
            if (prefs.memoryScopeAskOncePerSession.first() &&
                memoryScopeAskedInSession &&
                memoryScopeSelection.value?.bookId == bookId
            ) {
                prefs.setMemoryRetrievalEnabled(true)
                return
            }
            val pick = buildMemoryScopePick(bookId, onlyVectorized = true)
            if (pick != null) {
                pendingMemoryScopePick.value = pick
                return
            }
            // 没有任何已向量化章节可限定：退回全书检索，不阻断勾选
            memoryScopeSelection.value = null
        } else {
            memoryScopeSelection.value = null
        }
        prefs.setMemoryRetrievalEnabled(true)
    }

    /** 组装范围弹窗数据：章节按分卷组织（只含已向量化章节），默认沿用本书上次的选择（否则全选） */
    private suspend fun buildMemoryScopePick(
        bookId: Long,
        onlyVectorized: Boolean
    ): MemoryScopePick? {
        val allChapters = bookRepository.getChapters(bookId)
        val chapters = if (onlyVectorized) {
            val vectorized = container.memoryRepository.vectorizedChapterIds(bookId).toSet()
            allChapters.filter { it.id in vectorized }
        } else {
            allChapters
        }
        if (chapters.isEmpty()) return null
        // 只展示含有已向量化章节的分卷
        val volumeIds = chapters.mapNotNull { it.volumeId }.toSet()
        val volumes = bookRepository.getVolumes(bookId).filter { it.id in volumeIds }
        val remembered = memoryScopeSelection.value?.takeIf { it.bookId == bookId }?.chapterIds
        val selected = remembered?.filter { id -> chapters.any { it.id == id } }?.toSet()
            ?: chapters.map { it.id }.toSet()
        return MemoryScopePick(
            chapters = chapters.map { MemoryScopeChapter(it.id, it.title, it.volumeId) },
            volumes = volumes.map { MemoryScopeVolume(it.id, it.title) },
            selected = selected
        )
    }

    /** 「当前书本没有向量化」弹窗应答：可以 → 先向量化全书，完成后（如需）再选检索范围；不可以 → 保持不勾选 */
    fun resolveEmptyVectorizeAsk(confirm: Boolean) = viewModelScope.launch {
        pendingEmptyVectorizeAsk.value = false
        if (!confirm) return@launch
        val bookId = currentMemoryBookId() ?: return@launch
        container.memoryRepository.vectorize(bookId) { memoryVectorizing.value = it }
        memoryVectorizing.value = null
        if (container.memoryRepository.latestVectorizedUpdatedAt(bookId) == null) {
            memoryToast.value = "向量化未完成，请稍后重试"
            return@launch
        }
        requestMemoryScopeOrEnable(bookId)
    }

    /**
     * A3 关闭时：正文有更新且已勾选记忆检索 → 发送前弹窗询问「是否向量化到最新章节」。
     * 用户答「是」后先续跑向量化再发送；答「否」直接用已有进度发送。
     */
    val pendingMemoryAsk = MutableStateFlow<MemoryVectorizeAsk?>(null)

    /** 本次等待的向量化进度（弹窗选「是」后显示，避免界面看起来卡住） */
    val memoryVectorizing = MutableStateFlow<VectorizeProgress?>(null)

    // ---------------- 生成梗概 / 生成正文 ----------------
    // 状态机：CollectingConfig 阶段一次性弹完所有选择型弹窗并写入 GenerationPlan；
    // Executing 阶段只读 plan，不再弹任何需要用户输入的窗。任一弹窗取消即整体终止，不发模型请求。

    private val _generationState = MutableStateFlow(FlowState.Idle)
    val generationState: StateFlow<FlowState> = _generationState.asStateFlow()

    /** 当前需要 UI 弹出的选择型弹窗；为空表示无需弹窗 */
    private val _generationDialog = MutableStateFlow<GenerationDialog?>(null)
    val generationDialog: StateFlow<GenerationDialog?> = _generationDialog.asStateFlow()

    /** 本次生成计划（收集完成后只读） */
    private val _generationPlan = MutableStateFlow<GenerationPlan?>(null)
    val generationPlan: StateFlow<GenerationPlan?> = _generationPlan.asStateFlow()

    /** 生成的提示 / 兜底文案（超上限已截断、略短等），展示后由 UI 调用 [clearGenerationMessage] 清空 */
    val generationMessage = MutableStateFlow<String?>(null)

    /** 执行阶段为 true：两个生成按钮禁用，并提供「停止生成」 */
    val generationRunning: StateFlow<Boolean> = _generationState
        .map { it == FlowState.Executing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun clearGenerationMessage() {
        generationMessage.value = null
    }

    /**
     * 对话分组的展开状态（键为分组 key）。
     * 默认全部收起成块；用户点开 / 收起后写入偏好，下次进入沿用上次的效果。
     */
    val expandedChatGroups: StateFlow<Set<String>> =
        prefs.chatExpandedGroups.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** 记录用户对某个对话分组的展开 / 收起操作 */
    fun setChatGroupExpanded(key: String, expanded: Boolean) = viewModelScope.launch {
        prefs.setChatGroupExpanded(key, expanded)
    }

    private var generationJob: Job? = null
    private var generationCancelled = false
    private var generationOutlinePick: CompletableDeferred<List<Long>?>? = null
    private var generationMemoryScopeAsk: CompletableDeferred<Set<Long>?>? = null
    private var generationRefreshAsk: CompletableDeferred<RefreshRetrievalChoice?>? = null
    private var generationSummaryFailed: CompletableDeferred<SummaryFailureChoice>? = null

    /** 生成任务令牌：任务收尾时据此判断是否仍需复位状态，避免旧任务清掉新任务的状态 */
    private var generationSeq = 0

    /** 正在流式回显的生成轮次（用户消息 id）；0 表示当前没有回显中的生成 */
    private var generationStreamUserId = 0L

    /** 梗概子调用结果 */
    private sealed interface SummaryCall {
        /** 成功产出：[promptTokens] 等随回答落库，供界面展示 token 消耗与缓存命中率 */
        data class Ok(
            val text: String,
            val promptTokens: Int = 0,
            val completionTokens: Int = 0,
            val cachedTokens: Int = 0,
            /** 本次同时生成并已落库的章节标签（未开启 / 未解析出来时为空） */
            val tags: List<ChapterSummaryTag> = emptyList()
        ) : SummaryCall

        data class Failed(val message: String) : SummaryCall
    }

    /**
     * 侧边抽屉「生成梗概」：限定范围开启时先弹标签选择，否则直接执行。
     * 生成结果作为一条普通 AI 回答进入本会话（用户消息 = 输入框提示词 + 「生成梗概」标记），
     * 不再自动写回本章；由用户在回答操作栏点「写入梗概」决定是否写入。
     */
    fun startGenerateSummary() = launchGeneration { sid ->
        executeGenerateSummary(sid, input.value, editingUserMessageId.value)
    }

    /**
     * 「生成梗概」的实际执行。
     * [regenerateUserId] > 0 时表示这是对某条「生成梗概」轮次的「重新生成」：
     * 复用该用户消息、追加新的回答版本，不改写原提示词，也不清空输入框。
     */
    private suspend fun executeGenerateSummary(
        sid: Long,
        userPrompt: String,
        editingId: Long,
        regenerateUserId: Long = 0L
    ) {
        // 提示词过长：提示后直接放弃本次生成（不截断、不落库）
        if (userPrompt.trim().length > MAX_CHAT_MESSAGE_CHARS) {
            generationMessage.value = MSG_TOO_LONG
            return
        }
        val chapterId = fixedChapterId
            ?: regenerateUserId.takeIf { it > 0L }
                ?.let { chatRepository.getMessage(it)?.chapterId }
                ?.takeIf { it > 0L }
        if (chapterId == null) {
            noticeThenCancel("请在章节编辑页使用「生成梗概」")
            return
        }
        val chapterTitle = bookRepository.getChapter(chapterId)?.title.orEmpty()
        val plan = GenerationPlan(
            mode = GenerationMode.SUMMARY_ONLY,
            summaryWordRange = prefs.genSummaryWordRange.first(),
            contentWordRange = null,
            selectedOutlineIds = if (prefs.genSummaryLimitScope.first()) {
                askSummaryOutlines() ?: return
            } else {
                emptyList()
            },
            memoryRetrieval = RetrievalMode.OFF
        )
        _generationPlan.value = plan
        startExecuting()
        val modelId = activeModelId.value
        if (modelId <= 0L) {
            generationMessage.value = "请先在设置中配置并选择一个模型"
            _generationState.value = FlowState.Failed
            return
        }
        // 生成路径也走 [prepareSessionPrefix]：首次调用把已勾选的大纲 / 上下文冻结成会话前缀，
        // 之后逐轮复用 → 参考资料前缀跨轮逐字节稳定，缓存命中率随轮次提高
        val prefix = prepareSessionPrefix(sid, applyAdaptive = false, excludeChapterId = chapterId)
        val ref = buildGenerationReference(prefix, plan.selectedOutlineIds)
        // 像普通发送一样落一条「用户消息」：输入框提示词 + 「生成梗概」标记；
        // 本轮新增的参考资料随消息落库成为历史的一部分，故上面对选定标签的「已提交」标记语义成立。
        // 若用户点了某条历史提示词的「编辑」后再生成，则改写那一轮并追加新回答版本（见 [openGenerationRound]）
        val round = openGenerationRound(
            sid = sid,
            chapterId = chapterId,
            label = genLabelSummary,
            userPrompt = userPrompt,
            contextSnapshot = currentSnapshot().encode(),
            promptExtra = ref.extra,
            editingId = editingId,
            regenerateUserId = regenerateUserId
        )
        // 提示词已随本轮用户消息落库（相当于发送成功），清空输入框，避免下一轮误重复发送。
        // 「重新生成」不新增用户消息，也不应清空用户当前正在编辑的输入。
        if (regenerateUserId == 0L) input.value = ""
        // 选定标签的内容已随本轮消息落库，此时记入前缀去重（后续轮次只写「参见大纲『X』」）
        markGenerationOutlines(sid, ref.sessionPrefix, ref.freshOutlineIds)
        // 生成请求：聊天同构前缀块 +（按「历史对话前缀」开关）历史块 + 生成专用块（指令 + 新增参考资料 + 用户输入）
        val history = buildHistoryDialogue(prefix, sid, round.userId)
        // 「文风样式」勾选才打标：读取用户全局标签 + 当前书籍绑定的档案标签；都为空时只生成梗概
        val withTags = prefs.genStyleEnabled.first()
        val tagProfileId = styleProfileIdOfChapter(chapterId)
        val tagProfileTags = summaryTaggingTags(tagProfileId, withTags)
        val turns = assembleGenerationTurns(
            prefix, ref, history,
            buildSummaryTurns(userPrompt, plan.summaryWordRange, ref.extra, chapterTitle, withTags, tagProfileTags)
        )
        // 生成过程中的逐步输出实时回显在本轮对话的回答位置（显示「正在生成」）
        beginGenerationStreaming(round.userId)
        when (val r = runSummaryCall(plan, turns, stream = true, withTags = withTags, chapterId = chapterId, profileTags = tagProfileTags)) {
            is SummaryCall.Ok -> {
                persistGenerationAnswer(
                    sid = sid,
                    round = round,
                    text = r.text,
                    promptTokens = r.promptTokens,
                    completionTokens = r.completionTokens,
                    cachedTokens = r.cachedTokens,
                    chapterId = chapterId,
                    promptLog = buildGenerationLog(
                        modelId = modelId,
                        prefix = prefix,
                        rounds = history.rounds,
                        historyDialogue = history.text,
                        genKind = GEN_KIND_SUMMARY,
                        summary = "",
                        memory = "",
                        autoOutline = ref.autoOutlineLog,
                        userPrompt = userPrompt,
                        fullPrompt = userPrompt.trim().ifBlank { SUMMARY_FALLBACK_INPUT }
                    ).encode()
                )
                _generationState.value = FlowState.Done
            }
            is SummaryCall.Failed -> {
                generationMessage.value = r.message
                _generationState.value = FlowState.Failed
            }
        }
    }

    /**
     * 侧边抽屉「生成正文」：按固定顺序一次性弹完所有决策弹窗（梗概标签多选 → 记忆检索范围），
     * 记忆检索是否启用直接跟随 AI 面板的「记忆检索」勾选，不再弹窗询问；
     * 之后进入 Executing 只读执行；任一弹窗取消即整体终止。
     */
    fun startGenerateContent(onSummary: (String) -> Unit) = launchGeneration { sid ->
        executeGenerateContent(sid, input.value, editingUserMessageId.value, 0L, onSummary)
    }

    /**
     * 「生成正文」的实际执行。
     * [regenerateUserId] > 0 时表示这是对某条「生成正文」轮次的「重新生成」：
     * 复用该用户消息、追加新的回答版本，不改写原提示词，也不清空输入框；
     * 本章梗概等仍按生成流程作为临时 SYSTEM 块提交。
     */
    private suspend fun executeGenerateContent(
        sid: Long,
        userPrompt: String,
        editingId: Long,
        regenerateUserId: Long,
        onSummary: (String) -> Unit
    ) {
        // 提示词过长：提示后直接放弃本次生成（不截断、不落库）
        if (userPrompt.trim().length > MAX_CHAT_MESSAGE_CHARS) {
            generationMessage.value = MSG_TOO_LONG
            return
        }
        val chapterId = fixedChapterId
            ?: regenerateUserId.takeIf { it > 0L }
                ?.let { chatRepository.getMessage(it)?.chapterId }
                ?.takeIf { it > 0L }
        if (chapterId == null) {
            noticeThenCancel("请在章节编辑页使用「生成正文」")
            return
        }
        val chapter = bookRepository.getChapter(chapterId)
        val chapterTitle = chapter?.title.orEmpty()
        val withSummary = prefs.genContentWithSummary.first()
        val limitScope = prefs.genSummaryLimitScope.first()
        val mode = if (withSummary) {
            GenerationMode.CONTENT_WITH_SUMMARY
        } else {
            if (bookRepository.getChapter(chapterId)?.summary.orEmpty().isBlank()) {
                noticeThenCancel("无梗概：请先填写本章梗概，或勾选「生成正文同时生成梗概」后再生成正文")
                return
            }
            GenerationMode.CONTENT_ONLY
        }
        val memoryScopeLimit = prefs.memoryScopeLimitEnabled.first()
        // 记忆检索直接跟随 AI 面板里「记忆检索」的勾选：勾选即开启，取消勾选即关闭
        val memory = if (memoryRetrievalEnabled.value) RetrievalMode.ON else RetrievalMode.OFF
        var picked = emptyList<Long>()
        // ① 选梗概参考标签：顺序固定，取消即整体终止
        for (kind in contentDialogOrder(mode, limitScope)) {
            when (kind) {
                GenerationDialogKind.PICK_SUMMARY_OUTLINES ->
                    picked = askSummaryOutlines() ?: return
            }
        }
        // ② 记忆检索已勾选且开启「记忆检索是否限定范围」：直接沿用勾选记忆检索时已选定的范围，
        //    不再重复弹窗；仅当从未选过、或所选章节已失效（被删 / 未向量化）时才补弹一次
        var memoryChapterIds: Set<Long>? = null
        if (memory == RetrievalMode.ON && memoryScopeLimit) {
            val memoryBookId = currentMemoryBookId()
            if (memoryBookId != null) {
                val remembered = memoryScopeSelection.value
                    ?.takeIf { it.bookId == memoryBookId }
                    ?.chapterIds
                    ?.let { ids ->
                        val vectorized = container.memoryRepository.vectorizedChapterIds(memoryBookId).toSet()
                        // 开启「仅本会话首次询问」后，自动纳入选定范围之后新向量化 / 更新的章节
                        val refreshed = if (prefs.memoryScopeAskOncePerSession.first()) {
                            container.memoryRepository.vectorizedTimes(memoryBookId)
                                .filter { (id, time) -> memoryScopeVectorSnapshot[id] != time }
                                .keys
                        } else {
                            emptySet()
                        }
                        (ids + refreshed).filter { it in vectorized }.toSet()
                    }
                    ?.takeIf { it.isNotEmpty() }
                if (remembered != null) {
                    memoryChapterIds = remembered
                } else {
                    val pick = buildMemoryScopePick(memoryBookId, onlyVectorized = true)
                    // 没有任何已向量化章节时不弹范围选择，按全书检索继续生成（结果自然为空）
                    if (pick != null) {
                        memoryChapterIds = askMemoryScope(pick) ?: return
                    }
                }
            }
        }
        if (generationCancelled) return
        val plan = GenerationPlan(
            mode = mode,
            summaryWordRange = prefs.genSummaryWordRange.first(),
            contentWordRange = prefs.genContentWordRange.first(),
            selectedOutlineIds = picked,
            memoryRetrieval = memory,
            memoryChapterIds = memoryChapterIds
        )
        _generationPlan.value = plan
        startExecuting()

        val modelId = activeModelId.value
        if (modelId <= 0L) {
            generationMessage.value = "请先在设置中配置并选择一个模型"
            _generationState.value = FlowState.Failed
            return
        }
        val memoryCap = prefs.memoryBlockQuota.first()
        val memoryScope = prefs.memorySearchScope.first()

        // 生成路径也走 [prepareSessionPrefix]：首次调用把已勾选的大纲 / 上下文冻结成会话前缀
        val prefix = prepareSessionPrefix(sid, applyAdaptive = false, excludeChapterId = chapterId)
        // 选定标签只在「会生成梗概」时作为指定大纲参考资料提交：
        // 已在本会话前缀里的只写「参见大纲『X』」；未提交过的取完整内容并入本轮新增参考资料
        val ref = buildGenerationReference(
            prefix,
            if (mode == GenerationMode.CONTENT_ONLY) emptyList() else plan.selectedOutlineIds
        )
        var sp = ref.sessionPrefix

        // 历史截止位置：编辑态取被编辑的消息；「重新生成」取被重新生成的那条用户消息
        // （其旧回答 id 大于该消息，不进入历史对话块，避免模型把旧成品当成前情续写）
        val historyEnd = editingId.takeIf { it > 0L }
            ?: regenerateUserId.takeIf { it > 0L }
            ?: Long.MAX_VALUE
        val history = buildHistoryDialogue(prefix, sid, historyEnd)
        val summaryTagsEnabled = prefs.genStyleEnabled.first()
        val summaryTagProfileTags = if (summaryTagsEnabled) {
            summaryTaggingTags(styleProfileIdOfChapter(chapterId), true)
        } else {
            emptyList()
        }
        // 阶段提示：让「生成梗概 + 文风样本」这类长流程有明确的进度反馈
        generationStage.value = if (mode == GenerationMode.CONTENT_WITH_SUMMARY) "生成梗概中" else null
        val summary = if (mode == GenerationMode.CONTENT_WITH_SUMMARY) {
            awaitSummaryWithRetry(
                plan,
                assembleGenerationTurns(
                    prefix, ref, history,
                    buildSummaryTurns(
                        userPrompt, plan.summaryWordRange, ref.extra, chapterTitle,
                        summaryTagsEnabled, summaryTagProfileTags
                    )
                ),
                chapterId,
                onSummary,
                withTags = summaryTagsEnabled,
                profileTags = summaryTagProfileTags
            )
                ?: run {
                    if (!generationCancelled) _generationState.value = FlowState.Failed
                    return
                }
        } else {
            bookRepository.getChapter(chapterId)?.summary.orEmpty()
        }

        // 「适配性筛选」：以本轮实际使用的梗概匹配非常驻化大纲条目的「显示标签」，
        // 命中的（此前未提交过的）作为「新增大纲资料」随本轮用户消息落库，并记入前缀去重
        val adaptive = if (prefs.prefixAdaptiveFilterEnabled.first()) {
            val freshIds = adaptiveMatchedOutlineIds(summary).filterNot { sp.outlineInjected(it, prefix.outlineOn) }
            if (freshIds.isEmpty()) {
                ""
            } else {
                val entries = freshIds.mapNotNull { renderOutlineEntry(it) }
                sp = sp.markOutlinesInjected(freshIds)
                chatRepository.updateSessionPrefix(sid, sp.encode())
                renderAdaptiveOutlineBlock(sp, entries)
            }
        } else {
            ""
        }

        // 编辑已有轮次 / 重新生成：先问是否重新做记忆检索与文风抽样。
        // 上一轮结果存在时可选「沿用上次」，避免每次都无声重跑一遍检索与抽样。
        val roundUserId = editingId.takeIf { it > 0L } ?: regenerateUserId.takeIf { it > 0L }
        val previous = roundUserId?.let { previousGenerationExtras(sid, it) } ?: PreviousGenExtras()
        val canReuseMemory = plan.memoryRetrieval == RetrievalMode.ON && previous.memory.isNotBlank()
        val canReuseStyle = summaryTagsEnabled && previous.styleSamples.isNotBlank()
        var reuseMemory: String? = null
        var reuseStyle: String? = null
        if (roundUserId != null && (canReuseMemory || canReuseStyle)) {
            when (askRefreshRetrieval(canReuseMemory, canReuseStyle)) {
                RefreshRetrievalChoice.REFRESH -> Unit
                RefreshRetrievalChoice.REUSE -> {
                    if (canReuseMemory) reuseMemory = previous.memory
                    if (canReuseStyle) reuseStyle = previous.styleSamples
                }
                RefreshRetrievalChoice.CANCEL, null -> return
            }
            // 弹窗把状态降级成收集阶段：确认后立刻回到执行阶段，避免期间生成按钮重新可用
            _generationState.value = FlowState.Executing
            Log.d(
                TAG,
                "gen-edit reuse memory=${reuseMemory != null} style=${reuseStyle != null} " +
                    "prevMemory=${previous.memory.length}chars prevStyle=${previous.styleSamples.length}chars"
            )
        }
        val reusedMemory = reuseMemory
        val reusedStyle = reuseStyle

        val memoryRef = when {
            reusedMemory != null -> MemoryRef(text = reusedMemory)
            plan.memoryRetrieval == RetrievalMode.ON -> {
                generationStage.value = "记忆检索中"
                buildMemoryBlock(plan, chapterId, summary, memoryCap, memoryScope, sp)
            }
            else -> MemoryRef()
        }
        if (memoryRef.freshKeys.isNotEmpty()) {
            sp = sp.copy(autoMemoryKeys = sp.autoMemoryKeys + memoryRef.freshKeys)
            chatRepository.updateSessionPrefix(sid, sp.encode())
        }

        // 只有勾选「文风样式」才做梗概标签分析（复用有效关联 / 缺失过期时重新分析）；
        // 未勾选时不分析、不抽样、不注入句子参考块，正文按原流程生成
        val styleProfileId = styleProfileIdOfChapter(chapterId)
        val chapterTags = when {
            !summaryTagsEnabled -> emptyList()
            // 沿用上一轮结果时不触发分析模型，只取仍然有效的已有标签
            reusedStyle != null -> existingSummaryTags(chapterId, summary, styleProfileId)
            else -> {
                generationStage.value = "分析梗概中"
                ensureSummaryTags(chapterId, summary, modelId)
            }
        }
        // 文风样本抽样：未勾选「文风样式」时完全不抽；勾选后只从当前书籍绑定档案里按标签权重随机抽样。
        // 沿用上一轮结果时不再抽样。抽样失败 / 无结果继续正常生成
        val stylePick = if (reusedStyle == null && summaryTagsEnabled && styleProfileId > 0L && summary.isNotBlank()) {
            try {
                // 传入未去重的句段标签：标签出现次数也参与权重计算
                container.styleSampleRetriever.pick(
                    profileId = styleProfileId,
                    chapterTags = chapterTags
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "文风样本抽样失败：${e.message}")
                null
            }
        } else {
            null
        }
        // 本章当前梗概中随机抽样 1～3 个带标签的句段，仅本次请求临时使用
        val sentenceSamples = if (summaryTagsEnabled) {
            sampleSummarySentences(chapterTags, summary, styleProfileId)
        } else {
            emptyList()
        }
        val summarySentenceBlock = buildSummarySentenceTagBlock(sentenceSamples)
        val styleSamplesBlock = reusedStyle ?: buildStyleSampleBlock(stylePick?.hits.orEmpty())
        // 只记录抽样摘要（不打印完整样本与提示词），遵循现有日志规范
        Log.d(
            TAG,
            "style-sample book=$chapterId profile=$styleProfileId summaryHash=${shortHash(summary)} " +
                "tags=${chapterTags.joinToString(",") { "${it.category}:${it.name}" }} " +
                // 句子参考块只记数量与字数，不打印原句
                "sentenceSamples=${sentenceSamples.size}/${sentenceSamples.sumOf { it.text.length }}chars " +
                "chunks=${stylePick?.candidateChunks} matched=${stylePick?.matchedChunks}" +
                " hits=${stylePick?.hits?.map { it.chunkId }}" +
                " injected=${stylePick?.hits?.size ?: 0}" +
                " reused=${reusedStyle != null}" +
                (stylePick?.reason?.takeIf { it.isNotBlank() }?.let { " reason=$it" } ?: "")
        )
        // 正文专用块：文风约束已冻结在会话前缀首块（见 [prepareSessionPrefix]），这里不再重复注入
        val contentTurns = buildContentTurns(
            summary = summary,
            // 生成专用块只带本轮新增的大纲资料；已冻结的会话前缀已在前段共享块里
            outlineSlot = listOf(ref.extra, adaptive).filter { it.isNotBlank() }.joinToString("\n\n"),
            memoryBlock = memoryRef.text,
            userInput = userPrompt,
            range = plan.contentWordRange,
            chapterTitle = chapterTitle,
            summarySentenceBlock = summarySentenceBlock,
            styleSamplesBlock = styleSamplesBlock
        )
        val turns = assembleGenerationTurns(prefix, ref, history, contentTurns)
        // 先落「用户消息」：输入框提示词 + 「生成正文」标记；本轮新增的参考资料随消息落库成为历史的一部分。
        // 放在模型请求之前，回答位置才能就地显示流式输出（「正在生成」）。
        // 若用户点了某条历史提示词的「编辑」后再生成，则改写那一轮并追加新回答版本（见 [openGenerationRound]）
        val round = openGenerationRound(
            sid = sid,
            chapterId = chapterId,
            label = genLabelContent,
            userPrompt = userPrompt,
            contextSnapshot = currentSnapshot().encode(),
            promptExtra = listOf(ref.extra, adaptive, memoryRef.text).filter { it.isNotBlank() }.joinToString("\n\n"),
            editingId = editingId,
            regenerateUserId = regenerateUserId
        )
        // 选定标签的内容已随本轮消息落库，此时记入前缀去重（后续轮次只写「参见大纲『X』」）
        markGenerationOutlines(sid, sp, ref.freshOutlineIds)
        // 提示词即将随请求发出：立刻清空输入框，避免生成期间误以为没发送出去、再次点击重复生成。
        // 「重新生成」不新增用户消息，也不应清空用户当前正在编辑的输入
        if (regenerateUserId == 0L) input.value = ""
        // 「同时生成梗概」时梗概子调用保持静默，只把正文输出实时回显到对话中
        generationStage.value = "生成文本中"
        beginGenerationStreaming(round.userId)
        val result = configRepository.sendToModel(
            modelId,
            AiRequest(turns = turns, model = "", temperature = 0f, maxTokens = 0),
            onDelta = generationDeltaHandler()
        )
        if (generationCancelled) return
        if (!result.success) {
            generationMessage.value = result.error?.userMessage ?: "生成失败"
            _generationState.value = FlowState.Failed
            return
        }
        val raw = result.content.trim()
        if (raw.isBlank()) {
            generationMessage.value = "模型返回空内容"
            _generationState.value = FlowState.Failed
            return
        }
        val enforced = enforceWordRange(raw, plan.contentWordRange)
        // 落库的是截断 / 校正后的正文：把回显内容同步成最终文本，避免完成后内容突变
        if (streamingContent.value.isNotEmpty()) streamingContent.value = enforced.text
        if (enforced.over) {
            generationMessage.value = "正文超出设定上限，已截断到最后一个完整句子"
        } else if (enforced.under) {
            generationMessage.value = "正文略短于设定下限，可继续让 AI 补写"
        }
        // 生成结果作为一条普通 AI 回答进入本会话：不自动写入正文，由用户在编辑器点「写入本章正文」决定
        persistGenerationAnswer(
            sid = sid,
            round = round,
            text = enforced.text,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            cachedTokens = result.cachedTokens,
            chapterId = chapterId,
            promptLog = buildGenerationLog(
                modelId = modelId,
                prefix = prefix,
                rounds = history.rounds,
                historyDialogue = history.text,
                genKind = "",
                summary = summary,
                memory = memoryRef.text,
                autoOutline = listOf(ref.autoOutlineLog, adaptive).filter { it.isNotBlank() }.joinToString("\n\n"),
                userPrompt = userPrompt,
                fullPrompt = userPrompt.trim().ifBlank { CONTENT_FALLBACK_INPUT },
                styleSamples = styleSamplesBlock,
                context = prefix.contextLog
            ).encode()
        )
        _generationState.value = FlowState.Done
    }

    /** 标签多选弹窗「确定」 */
    fun confirmSummaryOutlines(ids: List<Long>) {
        generationOutlinePick?.complete(ids)
        generationOutlinePick = null
    }

    /** 生成路径的记忆检索范围弹窗：确定后返回选中的章节 */
    fun confirmGenerationMemoryScope(ids: Set<Long>) {
        generationMemoryScopeAsk?.complete(ids)
        generationMemoryScopeAsk = null
    }

    /** 梗概失败三选项 */
    fun resolveSummaryFailed(choice: SummaryFailureChoice) {
        generationSummaryFailed?.complete(choice)
        generationSummaryFailed = null
    }

    /**
     * 取消生成：弹窗关闭 / 用户点「取消」/ 点「停止生成」都走这里。
     * 收集阶段取消 → Cancelled 且不发出任何模型请求；执行阶段取消 → 中止在途请求并置 Cancelled。
     */
    fun cancelGeneration() {
        val state = _generationState.value
        // 状态已到终态但协程仍在跑（例如弹窗选择后被降级、旧任务收尾未完成）时也必须能取消，
        // 否则「停止」点不动，界面会一直停在生成中。
        if (state != FlowState.CollectingConfig && state != FlowState.Executing &&
            generationJob?.isActive != true
        ) return
        generationCancelled = true
        generationOutlinePick?.complete(null)
        generationOutlinePick = null
        generationMemoryScopeAsk?.complete(null)
        generationMemoryScopeAsk = null
        generationRefreshAsk?.complete(null)
        generationRefreshAsk = null
        generationSummaryFailed?.complete(SummaryFailureChoice.CANCEL)
        generationSummaryFailed = null
        _generationDialog.value = null
        _generationState.value = FlowState.Cancelled
        // 立即清掉「正在生成」的流式回显，避免被取消的请求稍后才收尾时界面仍停在生成中
        endGenerationStreaming()
        generationJob?.cancel()
        generationJob = null
    }

    /**
     * 启动一次生成任务。
     * 复位统一放在 launch 的 finally 里，并用 [generationSeq] 判断归属：
     * 被停止 / 被新任务接管的旧任务不会覆盖新任务的生成态，从根本上避免「一直正在生成中」。
     */
    private fun launchGeneration(block: suspend (Long) -> Unit) {
        // 判据必须是「任务是否真的还在跑」，而不是只看状态值：状态可能因为弹窗流程 / 旧任务收尾
        // 停在 Executing，此时若直接把点击拦下，界面就会一直停在「生成中」，再点「重新生成」也没反应。
        if (generationJob?.isActive == true) {
            // 真的在执行：明确提示，不再静默忽略
            if (_generationState.value == FlowState.Executing) {
                generationMessage.value = "当前正在生成中，请先点击「停止」后再重新发起"
                return
            }
            // 收集阶段且弹窗仍在等用户选择：让用户先完成或取消，避免两个任务的弹窗互相覆盖
            if (_generationState.value == FlowState.CollectingConfig && _generationDialog.value != null) {
                generationMessage.value = "请先完成或取消当前的生成设置后再试"
                return
            }
            // 收集阶段却没有任何弹窗在等结果 → 这个任务永远等不到结果，直接接管
            cancelGeneration()
        } else {
            // 已无在途任务却残留了生成态 / 弹窗 / 「正在生成」回显：先自愈，否则新一轮会被陈旧状态卡住
            resetGenerationResidue()
        }
        val token = ++generationSeq
        generationCancelled = false
        generationMessage.value = null
        endGenerationStreaming()
        _generationState.value = FlowState.CollectingConfig
        generationJob = viewModelScope.launch {
            try {
                // 切章会重建 ViewModel：等会话恢复完成再取，避免在恢复窗口里误判为「没有会话」
                sessionReady.await()
                val sid = _sessionId.value
                if (sid == null) {
                    _generationDialog.value = null
                    _generationState.value = FlowState.Failed
                    generationMessage.value = "请先在本章侧边对话中建立会话后再生成"
                    return@launch
                }
                block(sid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _generationState.value = FlowState.Failed
                generationMessage.value = e.message ?: "生成失败"
            } finally {
                if (token == generationSeq) {
                    // 任务异常结束（含被取消）却仍停在收集 / 执行阶段时，补一个终态，
                    // 界面不会永久显示「生成中」，也不会留下永远等不到结果的弹窗
                    when (_generationState.value) {
                        FlowState.CollectingConfig, FlowState.Executing ->
                            _generationState.value = FlowState.Cancelled
                        else -> Unit
                    }
                    _generationDialog.value = null
                    endGenerationStreaming()
                    generationJob = null
                }
            }
        }
    }

    /**
     * 清掉上一次生成留下的「陈旧残留」：没有在途任务，却还挂着生成态 / 弹窗 / 等待中的选择 /
     * 「正在生成」回显。不清就会让下一次点击被误判为「正在生成中」而永久发不出去。
     */
    private fun resetGenerationResidue() {
        generationJob = null
        generationOutlinePick?.complete(null)
        generationOutlinePick = null
        generationMemoryScopeAsk?.complete(null)
        generationMemoryScopeAsk = null
        generationRefreshAsk?.complete(null)
        generationRefreshAsk = null
        generationSummaryFailed?.complete(SummaryFailureChoice.CANCEL)
        generationSummaryFailed = null
        _generationDialog.value = null
        _generationPlan.value = null
        _generationState.value = FlowState.Idle
        generationCancelled = false
        endGenerationStreaming()
    }

    /** 把生成请求的流式增量回显到本轮对话的回答位置 */
    private fun beginGenerationStreaming(userId: Long) {
        if (userId <= 0L) return
        generationStreamUserId = userId
        pendingReplyToId.value = userId
        streamingContent.value = ""
        streamingReasoning.value = ""
    }

    /** 结束流式回显：把临时「正在生成」卡片让位给已落库的回答 */
    private fun endGenerationStreaming() {
        val id = generationStreamUserId
        if (id <= 0L) return
        generationStreamUserId = 0L
        if (pendingReplyToId.value == id) {
            pendingReplyToId.value = 0L
            streamingContent.value = ""
            streamingReasoning.value = ""
            generationStage.value = null
        }
    }

    /** 生成请求的流式回调：正文 / 思考增量累积到「正在生成」状态，用于实时回显 */
    private fun generationDeltaHandler(): (String, String) -> Unit {
        val contentBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        return { content, reasoning ->
            if (content.isNotEmpty()) {
                contentBuf.append(content)
                streamingContent.value = contentBuf.toString()
            }
            if (reasoning.isNotEmpty()) {
                reasoningBuf.append(reasoning)
                streamingReasoning.value = reasoningBuf.toString()
            }
        }
    }

    private fun startExecuting() {
        _generationDialog.value = null
        _generationState.value = FlowState.Executing
    }

    /** 纯提示弹窗（点确定 / 关闭即终止流程） */
    private fun noticeThenCancel(text: String) {
        _generationState.value = FlowState.CollectingConfig
        _generationDialog.value = GenerationDialog.Notice(text)
    }

    private suspend fun askSummaryOutlines(): List<Long>? {
        val deferred = CompletableDeferred<List<Long>?>()
        generationOutlinePick = deferred
        _generationState.value = FlowState.CollectingConfig
        _generationDialog.value = GenerationDialog.PickSummaryOutlines
        return try {
            deferred.await()
        } finally {
            // 只清理「自己」登记的弹窗，避免被取消的旧任务收尾时误清新任务正在等待的弹窗
            if (generationOutlinePick === deferred) {
                generationOutlinePick = null
                if (_generationDialog.value === GenerationDialog.PickSummaryOutlines) _generationDialog.value = null
            }
        }
    }

    /** 开启「记忆检索是否限定范围」时，确认启用检索后选择检索章节（返回 null 表示取消） */
    private suspend fun askMemoryScope(pick: MemoryScopePick): Set<Long>? {
        val deferred = CompletableDeferred<Set<Long>?>()
        generationMemoryScopeAsk = deferred
        _generationState.value = FlowState.CollectingConfig
        _generationDialog.value = GenerationDialog.PickMemoryScope(pick)
        return try {
            deferred.await()?.takeIf { it.isNotEmpty() }
        } finally {
            if (generationMemoryScopeAsk === deferred) {
                generationMemoryScopeAsk = null
                if (_generationDialog.value is GenerationDialog.PickMemoryScope) _generationDialog.value = null
            }
        }
    }

    /**
     * 编辑 / 重新生成正文前，询问是否重新做记忆检索与文风样本抽样。
     * 返回 null 表示用户取消本次生成；[RefreshRetrievalChoice.REUSE] 表示沿用上一轮已注入的内容。
     */
    private suspend fun askRefreshRetrieval(memory: Boolean, style: Boolean): RefreshRetrievalChoice? {
        val deferred = CompletableDeferred<RefreshRetrievalChoice?>()
        generationRefreshAsk = deferred
        _generationState.value = FlowState.CollectingConfig
        _generationDialog.value = GenerationDialog.AskRefreshRetrieval(memory, style)
        return try {
            deferred.await()
        } finally {
            if (generationRefreshAsk === deferred) {
                generationRefreshAsk = null
                if (_generationDialog.value is GenerationDialog.AskRefreshRetrieval) _generationDialog.value = null
            }
        }
    }

    /** 编辑 / 重新生成时「是否重新检索」弹窗的应答 */
    fun resolveRefreshRetrieval(choice: RefreshRetrievalChoice) {
        generationRefreshAsk?.complete(choice)
        generationRefreshAsk = null
    }

    private suspend fun askSummaryFailure(hasExisting: Boolean): SummaryFailureChoice? {
        val deferred = CompletableDeferred<SummaryFailureChoice>()
        generationSummaryFailed = deferred
        _generationState.value = FlowState.CollectingConfig
        _generationDialog.value = GenerationDialog.SummaryFailed(hasExisting)
        return try {
            deferred.await()
        } finally {
            if (generationSummaryFailed === deferred) {
                generationSummaryFailed = null
                if (_generationDialog.value is GenerationDialog.SummaryFailed) _generationDialog.value = null
            }
        }
    }

    /**
     * 执行一次梗概生成（独立 messages，不进入正文请求的历史）。
     * messages 由调用方组装（见 [assembleGenerationTurns]）：聊天同构前缀块 +（可选）历史 + 生成专用块。
     */
    private suspend fun runSummaryCall(
        plan: GenerationPlan,
        turns: List<ChatTurn>,
        /** true：单独「生成梗概」时流式回显到对话；false：「同时生成梗概」的子调用保持静默 */
        stream: Boolean = false,
        /** true：本次要求一次结构化输出梗概 + 句子标签 */
        withTags: Boolean = false,
        chapterId: Long = 0L,
        /** 当前绑定文风档案的已有标签；为空表示不自动打标 */
        profileTags: List<StyleTag> = emptyList()
    ): SummaryCall {
        val modelId = activeModelId.value
        if (modelId <= 0L) return SummaryCall.Failed("请先在设置中配置并选择一个模型")
        val result = configRepository.sendToModel(
            modelId,
            AiRequest(turns = turns, model = "", temperature = 0f, maxTokens = 0),
            onDelta = if (stream) generationDeltaHandler() else null
        )
        if (!result.success) return SummaryCall.Failed(result.error?.userMessage ?: "生成梗概失败")
        val raw = result.content.trim()
        if (raw.isBlank()) return SummaryCall.Failed("梗概生成失败：模型返回空内容")
        // 标签解析失败时绝不能丢梗概：解析不出来就把原始文本当梗概
        val structured = if (withTags) SummaryWithTagsCodec.parse(raw) else null
        val synopsis = structured?.synopsis ?: raw
        val enforced = enforceWordRange(synopsis, plan.summaryWordRange)
        // 落库的是截断 / 校正后的梗概：把回显内容同步成最终文本，避免完成后内容突变
        if (stream && streamingContent.value.isNotEmpty()) streamingContent.value = enforced.text
        if (enforced.over) {
            generationMessage.value = "梗概超出设定上限，已截断到最后一个完整句子"
        } else if (enforced.under) {
            generationMessage.value = "梗概略短于设定下限，可直接使用"
        }
        // 打标对象必须是最终保存的梗概文本：按最终文本重新切句并每 3 句合成句段后精确映射，映射失败的丢弃
        val tags = if (structured != null && chapterId > 0L && profileTags.isNotEmpty()) {
            val hash = shortHash(enforced.text)
            runCatching {
                val sentences = SummarySentenceSplitter.split(enforced.text)
                val segments = SummarySentenceSegmenter.segment(enforced.text, sentences)
                val resolved = SummarySentenceTagResolver.resolve(
                    structured.sentences,
                    enforced.text,
                    sentences,
                    segments,
                    profileTags,
                    hash
                )
                container.styleTagRepository.saveSummaryTags(chapterId, hash, resolved, keepManual = true)
                resolved
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
        return SummaryCall.Ok(
            enforced.text,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            cachedTokens = result.cachedTokens,
            tags = tags
        )
    }

    /**
     * 同时生成梗概模式下的梗概子调用：失败时显式三选项（重试 / 使用已有 / 取消）。
     * 静默失败会造成「梗概为空但正文已生成」的数据不一致，因此必须暴露给用户。
     * 返回 null 表示流程终止（取消）。
     */
    private suspend fun awaitSummaryWithRetry(
        plan: GenerationPlan,
        turns: List<ChatTurn>,
        chapterId: Long,
        onSummary: (String) -> Unit,
        withTags: Boolean = false,
        profileTags: List<StyleTag> = emptyList()
    ): String? {
        while (true) {
            when (val r = runSummaryCall(
                plan, turns, stream = false, withTags = withTags,
                chapterId = chapterId, profileTags = profileTags
            )) {
                is SummaryCall.Ok -> {
                    // 生成的梗概静默写回本章梗概（经编辑器本地缓冲，避免被下次整章保存覆盖）
                    onSummary(r.text)
                    return r.text
                }
                is SummaryCall.Failed -> {
                    while (true) {
                        val existing = bookRepository.getChapter(chapterId)?.summary.orEmpty()
                        when (askSummaryFailure(hasExisting = existing.isNotBlank())) {
                            SummaryFailureChoice.RETRY -> {
                                // 弹窗把状态降级成了收集阶段；重试时立即恢复「执行中」，
                                // 否则梗概重试 + 正文生成的整段时间里界面既没有「生成中」也没有停止入口，
                                // 生成按钮反而可用，用户容易误点而打断本次生成。
                                _generationState.value = FlowState.Executing
                                break
                            }
                            SummaryFailureChoice.USE_EXISTING -> {
                                if (existing.isNotBlank()) return existing
                                // 本章没有可用梗概：留在弹窗继续选择
                            }
                            SummaryFailureChoice.CANCEL -> {
                                if (!generationCancelled) generationMessage.value = "已取消：${r.message}"
                                return null
                            }
                            null -> return null
                        }
                    }
                }
            }
        }
    }

    /** 当前章节所属书籍绑定的文风档案 id；未绑定返回 0 */
    private suspend fun styleProfileIdOfChapter(chapterId: Long): Long {
        val bookId = bookRepository.getChapter(chapterId)?.bookId ?: return 0L
        return bookRepository.getBook(bookId)?.styleId ?: 0L
    }

    /** 生成梗概时的打标标签：用户全局标签 + 当前绑定档案标签；开关关闭或两套都为空时返回空 */
    private suspend fun summaryTaggingTags(profileId: Long, withTags: Boolean): List<StyleTag> {
        if (!withTags) return emptyList()
        val globalTags = try {
            container.styleTagRepository.userTags()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        val profileTags = if (profileId <= 0L) {
            emptyList()
        } else {
            try {
                container.styleTagRepository.profileTags(profileId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
        }
        return globalTags + profileTags
    }

    /**
     * 正文生成时的句子抽样：实时读取本章当前梗概与「全局 + 当前档案」标签，
     * 只在本次请求中临时使用，不写入任何长期数据。
     */
    private suspend fun sampleSummarySentences(
        tags: List<ChapterSummaryTag>,
        summary: String,
        profileId: Long
    ): List<SummarySentenceSample> {
        if (summary.isBlank()) return emptyList()
        return try {
            val allowedTags = container.styleTagRepository.userTags() +
                if (profileId > 0L) container.styleTagRepository.profileTags(profileId) else emptyList()
            SummarySentenceSampler.sample(
                tags = tags,
                summary = summary,
                tagIds = allowedTags.map { it.id }.toSet()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * 正文生成前的梗概标签检查（仅勾选「文风样式」时调用）：
     * - 每个句段都已有标签（包括用户手动多选）：直接复用，不请求分析模型；
     * - 关联未过期、带句段定位、且标签仍存在于「全局 + 当前档案」：直接复用；
     * - 关联缺失 / 已过期 / 标签已删除：重新分析当前最终梗概并保存（保留用户手动标签）；
     * - 两套标签库都为空：不分析、不打标，正文照常生成；
     * - 分析失败：最多保留手动标签，绝不阻断正文生成。
     */
    private suspend fun ensureSummaryTags(
        chapterId: Long,
        summary: String,
        modelId: Long
    ): List<ChapterSummaryTag> {
        val text = summary.trim()
        if (text.isBlank() || modelId <= 0L) return emptyList()
        val profileId = styleProfileIdOfChapter(chapterId)
        // 打标可用标签 = 用户全局标签 + 当前绑定档案标签
        val allowedTags = try {
            val globalTags = container.styleTagRepository.userTags()
            val profileTags = if (profileId <= 0L) {
                emptyList()
            } else {
                container.styleTagRepository.profileTags(profileId)
            }
            globalTags + profileTags
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        val existing = try {
            container.styleTagRepository.summaryTags(chapterId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
        // 两套标签库都为空：不自动打标，旧手动标签继续保留
        if (allowedTags.isEmpty()) return existing.filter { it.manual }
        val hash = shortHash(text)
        val tagIds = allowedTags.map { it.id }.toSet()
        // 打标粒度是「每 3 句一段」：旧版逐句关联不算有效，需要按当前粒度重新分析一次
        val segments = SummarySentenceSegmenter.segment(text, SummarySentenceSplitter.split(text))
        // 每个句段都已有标签（自动关联或用户手动多选）→ 不再调用分析模型，直接抓取库中样本
        if (SummaryTagValidity.isCovered(existing, hash, tagIds, segments)) {
            return existing.filter { SummaryTagValidity.isValidSegmentTag(it, hash, tagIds, segments) }
        }
        // 复用只看自动标签：手动标签不参与自动分析结果，其过期 hash 不应触发重复分析。
        // 旧版本自动标签没有句段定位、或标签不属于当前可用标签，都不能复用：需要重新分析一次。
        if (SummaryTagValidity.isReusable(existing, hash, tagIds, segments)) return existing
        val fresh = try {
            when (val outcome = container.styleTaggingService.analyzeSummary(modelId, text, profileId)) {
                is SummaryTaggingOutcome.Success -> {
                    container.styleTagRepository.saveSummaryTags(
                        chapterId,
                        outcome.summaryHash,
                        outcome.tags,
                        keepManual = true
                    )
                    // 确认落库的自动关联与本次最终梗概一致，否则不参与抽样
                    container.styleTagRepository.summaryTags(chapterId).filter {
                        it.manual || SummaryTagValidity.isValidAuto(it, hash, tagIds, segments)
                    }
                }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "梗概标签分析失败：${e.message}")
            null
        }
        return fresh ?: existing.filter { it.manual }
    }

    /**
     * 只取当前梗概已存在且仍有效的句段标签，**不触发分析模型**。
     * 编辑 / 重新生成选择「沿用上次」时使用，让这条路径不再产生额外的模型调用。
     */
    private suspend fun existingSummaryTags(
        chapterId: Long,
        summary: String,
        profileId: Long
    ): List<ChapterSummaryTag> {
        val text = summary.trim()
        if (text.isBlank()) return emptyList()
        return try {
            val allowed = container.styleTagRepository.userTags() +
                if (profileId > 0L) container.styleTagRepository.profileTags(profileId) else emptyList()
            val tagIds = allowed.map { it.id }.toSet()
            val hash = shortHash(text)
            val segments = SummarySentenceSegmenter.segment(text, SummarySentenceSplitter.split(text))
            container.styleTagRepository.summaryTags(chapterId)
                .filter { SummaryTagValidity.isValidSegmentTag(it, hash, tagIds, segments) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * 生成正文前的记忆检索参考块：以本章梗概（+ 选定标签）为 query 做向量检索，
     * 召回后复用 [SessionPrefix.freshMemoryHits] 去重（已提交过的片段不再重复注入）。
     * 只返回本轮新片段的文本与去重键，写回会话前缀由调用方在落库消息之后完成。
     */
    private suspend fun buildMemoryBlock(
        plan: GenerationPlan,
        chapterId: Long,
        summary: String,
        topK: Int,
        scope: String,
        sp: SessionPrefix
    ): MemoryRef {
        if (summary.isBlank()) return MemoryRef()
        val bookId = currentMemoryBookId() ?: return MemoryRef()
        val heads = plan.selectedOutlineIds.mapNotNull { id ->
            _outline.value.firstOrNull { it.id == id }?.let { outlineHeadOf(it) }
        }
        val query = buildRetrievalQuery(summary, heads)
        if (query.isBlank()) return MemoryRef()
        memorySearchState.value = MemorySearchState.SEARCHING
        // 必须用 finally 收尾：中途停止（CancellationException）也要让「搜索中」立即结束，
        // 否则界面会一直停在搜索态；取消异常必须原样抛回，协程才能真正停下来。
        val hits = try {
            container.memoryRepository.retrieveScored(
                bookId = bookId,
                query = query,
                topK = topK.coerceAtLeast(1),
                excludeChapterId = chapterId,
                scope = scope,
                chapterIds = plan.memoryChapterIds
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        } finally {
            memorySearchState.value = MemorySearchState.DONE
        }
        if (hits.isEmpty()) return MemoryRef()
        val fresh = sp.freshMemoryHits(hits)
        if (fresh.isEmpty()) return MemoryRef()
        val text = buildString {
            append("【参考资料·本书相关正文片段】\n")
            fresh.forEachIndexed { i, t -> append("片段${i + 1}：").append(t).append('\n') }
            append("（以上片段来自本书已写正文，仅供写作参考，请勿照抄或复述。）")
        }
        return MemoryRef(text = text, freshKeys = fresh.map { shortHash(it) })
    }

    /** 上一轮生成实际注入的记忆片段 / 文风样本，编辑或重新生成时可原样沿用 */
    private data class PreviousGenExtras(val memory: String = "", val styleSamples: String = "")

    /**
     * 读取某条「生成正文」轮次上一轮回答里实际注入的记忆检索结果与文风样本。
     * 取当前生效的版本（没有生效标记时退回最后一个版本）；旧数据没有日志时返回空。
     */
    private suspend fun previousGenerationExtras(sid: Long, roundUserId: Long): PreviousGenExtras {
        if (roundUserId <= 0L) return PreviousGenExtras()
        val answers = chatRepository.getMessages(sid)
            .filter { it.replyToId == roundUserId && it.role == ChatRole.ASSISTANT }
        if (answers.isEmpty()) return PreviousGenExtras()
        val answer = answers.lastOrNull { it.active } ?: answers.last()
        val log = PromptLog.decode(answer.promptLog) ?: return PreviousGenExtras()
        return PreviousGenExtras(memory = log.memory, styleSamples = log.styleSamples)
    }

    /** 生成路径落库的「用户消息」正文：输入框提示词 + 生成标记，用于在对话里区分生成轮次 */
    private fun generationUserContent(label: String, prompt: String): String =
        if (prompt.isBlank()) label else "$label\n$prompt"

    /**
     * 生成结果要落到哪一个「用户轮次」上。
     * - [variantIndex] 为 0：本轮新建的用户消息（沿用原有生成逻辑）；
     * - [variantIndex] > 0：用户在聊天里点了某条历史提示词的「编辑」后触发生成，
     *   此时改写那一轮的用户消息，并以 [variantIndex] 追加一个新的回答版本；
     * - [staleIds]：编辑态下该轮之后的后续对话，提示词已变故作废删除（与聊天「编辑后发送」一致）。
     */
    private class GenRound(
        val userId: Long,
        val variantIndex: Int,
        val staleIds: List<Long>
    )

    /**
     * 打开生成路径的用户轮次。
     * 未处于编辑态（[editingId] <= 0）时新建一条用户消息；
     * 处于编辑态时复用聊天页「重新生成」的版本机制：改写被编辑的那条消息并追加新版本，
     * 不再多发一条新的生成轮次 —— 即「编辑完提示词后生成 = 按新提示词重新生成，可切分支」。
     */
    private suspend fun openGenerationRound(
        sid: Long,
        chapterId: Long,
        label: String,
        userPrompt: String,
        contextSnapshot: String,
        promptExtra: String,
        editingId: Long,
        regenerateUserId: Long = 0L
    ): GenRound {
        // 「重新生成」生成轮次：复用原用户消息，只追加新的回答版本，不改写提示词与上下文
        if (regenerateUserId > 0L) {
            return GenRound(
                userId = regenerateUserId,
                variantIndex = chatRepository.nextVariantIndex(regenerateUserId),
                staleIds = emptyList()
            )
        }
        val target = editingId.takeIf { it > 0L }?.let { chatRepository.getMessage(it) }
        if (target == null || target.sessionId != sid) {
            val id = chatRepository.addMessage(
                sid, ChatRole.USER, generationUserContent(label, userPrompt),
                contextSnapshot = contextSnapshot,
                promptExtra = promptExtra,
                chapterId = chapterId
            )
            return GenRound(id, 0, emptyList())
        }
        // 编辑态：消费掉编辑状态，改写那条用户消息，并作废其后继对话。
        // 必须保留「生成正文 / 生成梗概」标记：否则这一轮会被当成普通聊天，
        // 后续「重新生成」无法再识别出它该走生成流程（梗概等临时块会丢失）。
        editingUserMessageId.value = 0L
        val staleIds = chatRepository.getMessages(sid)
            .filter { it.id > target.id && !(it.role == ChatRole.ASSISTANT && it.replyToId == target.id) }
            .map { it.id }
        chatRepository.updateMessageContent(target.id, generationUserContent(label, userPrompt))
        return GenRound(target.id, chatRepository.nextVariantIndex(target.id), staleIds)
    }

    /** 落库生成结果：编辑态下先清除旧版本展示标记，再追加新版本，最后丢弃作废的后续对话 */
    private suspend fun persistGenerationAnswer(
        sid: Long,
        round: GenRound,
        text: String,
        promptTokens: Int,
        completionTokens: Int,
        cachedTokens: Int,
        chapterId: Long,
        promptLog: String
    ) {
        if (round.variantIndex > 0) chatRepository.deactivateVariants(round.userId)
        chatRepository.addMessage(
            sid, ChatRole.ASSISTANT, text,
            replyToId = round.userId,
            variantIndex = round.variantIndex,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            cachedTokens = cachedTokens,
            chapterId = chapterId,
            promptLog = promptLog
        )
        if (round.staleIds.isNotEmpty()) chatRepository.deleteMessagesByIds(round.staleIds)
    }

    /**
     * 生成路径的参考资料组装：与聊天请求逐字一致的 [GenerationRef.sharedReference]、
     * 生成专用块里的 [GenerationRef.extra]（同时随本轮用户消息落库）。
     * 选定的「梗概范围」标签（[SessionPrefix.outlineInjected] 判定）：
     * - 已在本会话前缀里的只写「参见大纲『X』」；
     * - 未提交过的取完整内容并入本轮新增参考资料，其 id 由 [freshOutlineIds] 带回，
     *   等消息落库后由 [markGenerationOutlines] 记入前缀去重，后续轮次退化为「参见大纲『X』」。
     */
    private suspend fun buildGenerationReference(
        prefix: PreparedPrefix,
        outlineIds: List<Long>
    ): GenerationRef {
        val sp = prefix.sessionPrefix
        val pickedBlock = if (outlineIds.isEmpty()) {
            ""
        } else {
            // 先把完整渲染文本取出来（读取库是挂起操作），再交给纯函数决定「参见」还是「完整内容」
            val fullText = outlineIds.mapNotNull { id -> renderOutlineEntry(id)?.let { id to it.text } }.toMap()
            buildSummaryReferenceBlock(
                outlineIds = outlineIds,
                injected = { id -> sp.outlineInjected(id, prefix.outlineOn) },
                label = { id -> _outline.value.firstOrNull { it.id == id }?.let { outlineHeadOf(it) } ?: id.toString() },
                renderFull = { id -> fullText[id] }
            )
        }
        // 本轮新增参考资料：新勾选的书籍/章节/大纲 + 「适配性筛选」新增大纲资料 + 选定标签
        val extra = listOf(prefix.newReference, prefix.autoOutlineMaterial, pickedBlock)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return GenerationRef(
            sessionPrefix = sp,
            // 与聊天请求的前置参考资料逐字一致：会话前缀 +（前缀化关闭时）实时上下文
            sharedReference = chatStyleReference(prefix),
            extra = extra,
            autoOutlineLog = listOf(prefix.autoOutlineMaterial, pickedBlock)
                .filter { it.isNotBlank() }
                .joinToString("\n\n"),
            freshOutlineIds = outlineIds.filterNot { sp.outlineInjected(it, prefix.outlineOn) }
        )
    }

    /**
     * 与聊天发送逐字一致的「参考资料」文本：冻结的会话前缀 + 前缀化关闭时的实时上下文
     * （拼装方式与 [runGeneration] 传入 [SendChatRequest.referencePrefix] / selection 后
     * [SendChatMessageUseCase] 的 `reference` 完全相同），用于两条路径共享服务端前缀缓存。
     */
    private suspend fun chatStyleReference(prefix: PreparedPrefix): String {
        val live = liveSelection(prefix.contextOn, prefix.outlineOn)
        val context = chatRepository.buildContext(
            bookIds = live.bookIds,
            chapterIds = live.chapterIds,
            outlineIds = live.outlineIds,
            contentChapterIds = live.contentChapterIds.toSet(),
            summaryChapterIds = live.summaryChapterIds.toSet(),
            volumeSummaryIds = live.volumeSummaryIds
        )
        return listOf(prefix.reference, context)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /**
     * 本轮的「历史对话」块：按本会话已生效的角色开关，从 [cutoffExclusive] 之前的消息里
     * 挑选要注入的内容（[HistoryPrefix.pick] 负责过滤角色 / 生效版本 / 截止位置）。
     *
     * 开关开启时返回日志文本 + 真实轮次；开关关闭时仍然返回真实轮次（受模型
     * 「最大上下文」条数限制），只是不再渲染成 System 文本块 —— 这样每轮请求
     * 都会延长上一轮，供应商的前缀缓存才能连续命中。
     * 消息内容每次发送都按当前数据库状态重算，不冻结成快照。
     */
    private suspend fun buildHistoryDialogue(
        prefix: PreparedPrefix,
        sid: Long,
        cutoffExclusive: Long
    ): HistoryDialogue {
        if (!prefix.historyMessageOn || (!prefix.historyAiOn && !prefix.historyUserOn)) {
            // 历史对话开关关闭时，仍然把已完成的历史轮次作为真实消息发送：
            // 1) 多轮对话才有连续记忆；2) 下一轮请求才能严格「延长」上一轮请求，
            //    供应商的前缀缓存（MiniMax 被动缓存会复用完整前缀）才能连续命中。
            // 条数由模型的「最大上下文（历史消息条数）」控制，0 表示不限。
            val history = chatRepository.getMessages(sid)
                .filter { it.id < cutoffExclusive }
                .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
                .filter { it.role == ChatRole.USER || it.active }
            val max = chatRepository.getSession(sid)?.modelId
                ?.let { configRepository.getModel(it)?.maxContext } ?: 0
            val picked = if (max > 0) history.takeLast(max) else history
            return HistoryDialogue(
                turns = picked.mapNotNull { m ->
                    val extra = m.promptExtra
                    val content = if (extra.isBlank()) m.content else extra + "\n\n" + m.content
                    ChatTurn(m.role, content).takeIf { it.content.isNotBlank() }
                }
            )
        }
        val messages = HistoryPrefix.pick(
            chatRepository.getMessages(sid),
            cutoffExclusive,
            prefix.historyAiOn,
            prefix.historyUserOn
        )
        val body = HistoryPrefix.render(messages)
        if (body.isBlank()) return HistoryDialogue()
        return HistoryDialogue(
            text = HISTORY_DIALOGUE_HEADER + body,
            // 真正发给模型的历史对话：保持真实角色，前缀只会在尾部增长
            turns = messages.mapNotNull { m ->
                val role = when (m.role) {
                    ChatRole.USER -> ChatRole.USER
                    ChatRole.ASSISTANT -> ChatRole.ASSISTANT
                    ChatRole.SYSTEM -> return@mapNotNull null
                }
                ChatTurn(role, m.content.trim()).takeIf { it.content.isNotBlank() }
            },
            rounds = HistoryPrefix.rounds(messages)
        )
    }

    /**
     * 组装生成请求的 messages：聊天同构前缀块 → 历史正文轮次 → 历史梗概条 → 历史对话轮次 → 生成专用块 [tail]。
     * 历史正文还原成「user 要求生成 → assistant 正文」的真实轮次，历史梗概逐章独立成条，
     * 历史对话同样按真实角色逐条追加：上一轮的完整请求因此成为本轮请求的逐字节前缀，
     * 供应商侧前缀缓存可持续命中；若拼成整块文本，内容逐轮变长会让整块缓存失效。
     */
    private fun assembleGenerationTurns(
        prefix: PreparedPrefix,
        ref: GenerationRef,
        history: HistoryDialogue,
        tail: List<ChatTurn>
    ): List<ChatTurn> = buildList {
        addAll(
            buildGenerationPrefixTurns(
                customPrefix = prefix.custom,
                reference = ref.sharedReference,
                styleBlock = prefix.style
            )
        )
        // 命中率优先：历史章节正文还原成真实对话轮次（user 要求生成 → assistant 正文）。
        // 这样第 N 章的完整请求成为第 N+1 章请求的逐字节前缀，跨章命中同一段供应商缓存；
        // 若拼成 system 参考块或整块文本，块内容逐轮变长会让缓存从变化点起全部失效。
        if (prefix.historyContentOn) {
            prefix.historyContentTurns.forEach { turn ->
                add(ChatTurn(ChatRole.USER, "生成正文：《${turn.title}》"))
                add(ChatTurn(ChatRole.ASSISTANT, turn.content))
            }
        }
        // 大纲条目逐条注入，且排在历史正文轮次之后：新追加大纲只影响自身及其后内容，
        // 历史正文的缓存前缀不会被位置漂移打断（整块参考资料放最前时会被整体顶掉）。
        if (prefix.outlineOn) {
            prefix.sessionPrefix.renderOutlineBlocks(prefix.outlineEntries).forEach { block ->
                add(ChatTurn(ChatRole.SYSTEM, block))
            }
        }
        // 历史梗概同样逐章独立成条：整块拼接会在每轮追加新章后让整块缓存全部失效，
        // 拆条后「前 N-1 章梗概」可作为下一轮请求的逐字节前缀继续命中。
        if (prefix.historySummaryOn) {
            prefix.historySummaryBlocks.forEachIndexed { index, block ->
                add(
                    ChatTurn(
                        ChatRole.SYSTEM,
                        if (index == 0) HISTORY_SUMMARY_HEADER + block else block
                    )
                )
            }
        }
        // 历史对话按真实轮次注入（与历史正文轮次同构）：整块 System 文本每轮变长会整块失效，
        // 而真实轮次是逐条追加的，上一轮的完整请求因此成为本轮请求的逐字节前缀，前缀缓存可持续命中。
        // 仅「每轮消息」开关打开时才注入（此时 history.text 非空），关闭时保持不带上历史对话。
        if (history.text.isNotBlank()) addAll(history.turns)
        addAll(tail)
    }

    /** 选定标签已随本轮消息落库，记入会话前缀去重：下一轮只写「参见大纲『X』」 */
    private suspend fun markGenerationOutlines(sid: Long, sp: SessionPrefix, ids: List<Long>) {
        if (ids.isEmpty()) return
        chatRepository.updateSessionPrefix(sid, sp.markOutlinesInjected(ids).encode())
    }

    /**
     * 生成路径落库回答的「提示词日志」：与聊天发送一致的三级结构（预设 / 上下文 / 本轮提示词），
     * 供「查看日志 / 查看本轮提示词」回显。[rounds] / [historyDialogue] 为本次请求实际注入的历史对话，
     * 历史梗概与历史正文直接取 [prefix] 里本次实际注入的块（空内容不显示节点）。
     */
    private suspend fun buildGenerationLog(
        modelId: Long,
        prefix: PreparedPrefix,
        rounds: List<PromptLogRound>,
        genKind: String,
        summary: String,
        memory: String,
        autoOutline: String,
        userPrompt: String,
        fullPrompt: String,
        /** 本轮提交给模型的文风样本：只在当轮日志回显，不做前缀冻结 */
        styleSamples: String = "",
        /** 预设日志回显：当前勾选的章节梗概 / 正文 */
        context: String = "",
        /** 本轮实际注入的历史对话正文（含「【历史对话】」抬头），为空表示未注入 */
        historyDialogue: String = ""
    ): PromptLog = PromptLog(
        // 实际生效的预设提示词：模型配置里的 systemPrompt（真正随请求发出）
        systemPrompt = configRepository.getModel(modelId)?.systemPrompt.orEmpty(),
        customPrefix = prefix.custom,
        outlines = prefix.outlineEntries.map { PromptLogNode(outlineLogTitle(it.text), it.text) },
        context = context,
        rounds = rounds,
        historySummary = if (prefix.historySummaryOn) prefix.historySummaryBlock else "",
        historyContent = if (prefix.historyContentOn) prefix.historyContentBlock else "",
        historyDialogue = historyDialogue,
        summary = summary,
        memory = memory,
        autoOutline = autoOutline,
        userPrompt = userPrompt,
        fullPrompt = fullPrompt,
        genKind = genKind,
        styleSamples = styleSamples
    )

    // ---------------- AI 工具 ----------------

    private val outlineImporter = AiOutlineImporter(prefs, outlineRepository)
    private val chapterImporter = AiChapterImporter(bookRepository)

    /** 「写入大纲工具」是否启用（设置-AI工具页控制；关闭后不在聊天页多选中出现） */
    val outlineToolEnabled: StateFlow<Boolean> = prefs.aiToolOutlineEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 「章节梗概导入工具」是否启用（设置-AI工具页控制；关闭后不在聊天页多选中出现） */
    val chapterToolEnabled: StateFlow<Boolean> = prefs.aiToolChapterEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 聊天页勾选的 AI 工具 id 集合（可多选，持久化） */
    val chatTools: StateFlow<Set<String>> = prefs.aiChatTools
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * AI 回答完成后解析出的待导入内容队列。
     * 多选多个工具且回答里含多种标记块时逐个入队，UI 挨个询问是否导入。
     */
    val pendingImports = MutableStateFlow<List<PendingImport>>(emptyList())

    /** 导入结果提示（Toast 用，展示后由 UI 清空） */
    val importToast = MutableStateFlow<String?>(null)

    fun toggleChatTool(id: String) = viewModelScope.launch {
        val cur = chatTools.value
        prefs.setAiChatTools(if (id in cur) cur - id else cur + id)
    }

    /** 「写入大纲工具」当前是否生效：工具启用且已在聊天页勾选（侧边对话一律不生效） */
    private fun writeOutlineToolOn(): Boolean =
        enableAiTools && outlineToolEnabled.value && AI_TOOL_WRITE_OUTLINE in chatTools.value

    /** 「章节梗概导入工具」当前是否生效：工具启用且已在聊天页勾选（侧边对话一律不生效） */
    private fun chapterImportToolOn(): Boolean =
        enableAiTools && chapterToolEnabled.value && AI_TOOL_IMPORT_CHAPTER in chatTools.value

    /** 某个 AI 工具当前是否真正生效（供 UI 判断是否显示回答上的「导入」按钮） */
    fun isImportToolOn(id: String): Boolean = when (id) {
        AI_TOOL_WRITE_OUTLINE -> writeOutlineToolOn()
        AI_TOOL_IMPORT_CHAPTER -> chapterImportToolOn()
        else -> false
    }

    /** 手动导入的二次解析进行中（UI 显示 loading） */
    val manualImportLoading = MutableStateFlow(false)

    /** 取消当前这一项导入（队列后移，继续询问下一项） */
    fun dismissPendingImport() {
        pendingImports.value = pendingImports.value.drop(1)
    }

    fun clearImportToast() {
        importToast.value = null
    }

    /** 导入的默认目标书籍：唯一勾选的书 > 会话绑定的书 */
    suspend fun importTargetBookId(): Long? =
        selectedBookIds.value.singleOrNull()
            ?: _sessionId.value?.let { chatRepository.getSession(it)?.bookId }

    /**
     * 目标书籍里已存在的条目键（供导入预览标注「将覆盖」）：大纲按「标签\u0000名称」，章节按「分卷\u0000章节名」。
     */
    suspend fun existingImportKeys(bookId: Long, outline: Boolean): Set<String> = buildSet {
        if (outline) {
            val categories = prefs.outlineCategories(bookId) ?: defaultOutlineKeys
            outlineRepository.getOutline(bookId).forEach { item ->
                val name = decodeOutlineFields(item.content).firstOrNull()?.second?.ifBlank { item.title } ?: item.title
                add("${item.category}\u0000$name")
                categories.firstOrNull { it == item.category }?.let { add("${outlineCategoryLabel(it)}\u0000$name") }
            }
        } else {
            val volumes = bookRepository.getVolumes(bookId)
            val firstVolumeTitle = volumes.firstOrNull()?.title?.trim().orEmpty()
            val titles = volumes.associate { it.id to it.title.trim() }
            bookRepository.getChapters(bookId).forEach { chapter ->
                val volumeTitle = titles[chapter.volumeId].orEmpty()
                add("$volumeTitle\u0000${chapter.title.trim()}")
                if (volumeTitle == firstVolumeTitle) add("\u0000${chapter.title.trim()}")
            }
        }
    }

    /** 把待导入的大纲区块写入指定书籍（[selected] 为预览里勾选的条目下标），然后继续队列中的下一项 */
    fun confirmOutlineImport(bookId: Long, selected: Set<Int> = headIndices()) = viewModelScope.launch {
        val head = pendingImports.value.firstOrNull() as? PendingImport.Outline ?: return@launch
        pendingImports.value = pendingImports.value.drop(1)
        val blocks = head.blocks.filterIndexed { i, _ -> i in selected }
        if (blocks.isEmpty()) return@launch
        val result = outlineImporter.import(bookId, blocks)
        importToast.value = buildString {
            append("已写入 ${result.items} 条大纲")
            if (result.newCategories.isNotEmpty()) {
                append("，新建标签：${result.newCategories.joinToString("、")}")
            }
        }
        // 刷新上下文里的大纲列表，让「大纲(n)」立即反映新条目
        loadBookContextNow(selectedBookIds.value.firstOrNull() ?: bookId)
    }

    /** 把待导入的章节梗概写入指定书籍（[selected] 为预览里勾选的条目下标），然后继续队列中的下一项 */
    fun confirmChapterImport(bookId: Long, selected: Set<Int> = headIndices()) = viewModelScope.launch {
        val head = pendingImports.value.firstOrNull() as? PendingImport.Chapters ?: return@launch
        pendingImports.value = pendingImports.value.drop(1)
        val blocks = head.blocks.filterIndexed { i, _ -> i in selected }
        if (blocks.isEmpty()) return@launch
        val result = chapterImporter.import(bookId, blocks)
        importToast.value = buildString {
            append("已导入章节梗概：新建 ${result.created} 章")
            if (result.updated > 0) append("，更新 ${result.updated} 章")
            if (result.newVolumes.isNotEmpty()) {
                append("，新建分卷：${result.newVolumes.joinToString("、")}")
            }
        }
        // 刷新上下文里的章节 / 分卷列表，让「章节(n)」立即反映新章节
        loadBookContextNow(selectedBookIds.value.firstOrNull() ?: bookId)
    }

    private fun headIndices(): Set<Int> = when (val head = pendingImports.value.firstOrNull()) {
        is PendingImport.Outline -> head.blocks.indices.toSet()
        is PendingImport.Chapters -> head.blocks.indices.toSet()
        null -> emptySet()
    }

    /**
     * 手动导入（模型不支持工具调用时的降级路径）：把这条回答发给当前模型，
     * 用旧标记提示词做一次性转换（不进历史），解析结果入队后照常弹预览确认。
     */
    fun manualImport(messageId: Long, toolId: String) = viewModelScope.launch {
        if (manualImportLoading.value) return@launch
        val message = chatRepository.getMessage(messageId)
        if (message == null || message.content.isBlank()) {
            importToast.value = "这条回答没有可导入的内容"
            return@launch
        }
        val modelId = activeModelId.value
        if (modelId <= 0L) {
            importToast.value = "请先选择模型"
            return@launch
        }
        manualImportLoading.value = true
        try {
            // 按回答内容判定类型：只像章节梗概的内容绝不能送进大纲提示词，
            // 否则模型会把「第N章」的梗概当成大纲 blocks 转出来，写进大纲库。
            val kindHint = detectImportKindHint(message.content)
            val outline = toolId == AI_TOOL_WRITE_OUTLINE && !kindHint.chapterOnly
            if (toolId == AI_TOOL_WRITE_OUTLINE && kindHint.chapterOnly) {
                importToast.value = "检测到章节梗概，已按「批量写入章节梗概」解析"
            }

            suspend fun convert(prompt: String): String? {
                val turns = listOf(
                    ChatTurn(ChatRole.SYSTEM, prompt),
                    ChatTurn(ChatRole.USER, "请把下面的内容转换成规定格式，只输出标记块：\n\n" + message.content)
                )
                val result = configRepository.sendToModel(
                    modelId,
                    AiRequest(turns = turns, model = "", temperature = 0f, maxTokens = 0)
                )
                if (!result.success) {
                    importToast.value = result.error?.userMessage ?: "解析失败，请重试"
                    return null
                }
                return result.content
            }

            if (!outline) {
                val text = convert(AI_CHAPTER_TOOL_PROMPT) ?: return@launch
                val blocks = parseAiChapterBlocks(text)
                if (blocks.isEmpty()) {
                    importToast.value = "没有解析到可导入的章节梗概"
                } else {
                    pendingImports.value = pendingImports.value + PendingImport.Chapters(blocks)
                }
                return@launch
            }

            val parsed = parseAiOutlineBlocks(convert(AI_OUTLINE_TOOL_PROMPT) ?: return@launch)
            // 二次解析仍可能把章节梗概塞进大纲：这类条目一律丢弃，绝不写入大纲库
            val blocks = parsed.filterNot { isChapterLikeOutlineBlock(it) }
            val dropped = parsed.size - blocks.size
            if (blocks.isNotEmpty()) pendingImports.value = pendingImports.value + PendingImport.Outline(blocks)
            if (dropped == 0) {
                if (blocks.isEmpty()) importToast.value = "没有解析到可导入的大纲内容"
                return@launch
            }
            // 混合内容（如人设 + 1-10 章梗概）：章节部分再按章节提示词解析一次，单独入队
            val chapters = parseAiChapterBlocks(convert(AI_CHAPTER_TOOL_PROMPT) ?: return@launch)
            if (chapters.isNotEmpty()) {
                pendingImports.value = pendingImports.value + PendingImport.Chapters(chapters)
                importToast.value = "已分别解析：大纲 ${blocks.size} 条、章节梗概 ${chapters.size} 条"
            } else {
                importToast.value = if (blocks.isNotEmpty()) {
                    "已忽略 $dropped 条章节梗概，请用「批量写入章节梗概」导入"
                } else {
                    "这条回答是章节梗概，请选择「批量写入章节梗概」导入"
                }
            }
        } finally {
            manualImportLoading.value = false
        }
    }

    /** 正在编辑的历史用户消息 id（0 表示普通发送，>0 表示发送后将改写该条并重新生成） */
    val editingUserMessageId = MutableStateFlow(0L)

    /** 正在生成回答的用户消息 id：UI 据此把该条回答位置就地切成“正在生成” */
    val pendingReplyToId = MutableStateFlow(0L)

    /** 正在生成的回答正文（流式增量累积），用于实时显示 */
    val streamingContent = MutableStateFlow("")

    /** 正在生成的深度思考内容（流式增量累积） */
    val streamingReasoning = MutableStateFlow("")

    /**
     * 生成正文的阶段提示：生成梗概中 / 分析梗概中 / 记忆检索中（开启记忆检索时）/ 生成文本中。
     * 为空表示没有可展示的阶段（普通对话 / 生成已结束）。
     */
    val generationStage = MutableStateFlow<String?>(null)

    /** 在途生成任务，供「停止响应」取消 */
    private var generateJob: Job? = null

    /** 单调递增的「当前生成轮次」令牌：旧任务收尾时据此判断是否仍属于本轮，避免误清新任务的状态 */
    private var generationToken = 0

    init {
        if (defaultBookId != null) selectedBookIds.value = setOf(defaultBookId)
        // 侧边助手切章会重建 ViewModel：先用进程内缓存填上本书会话，首帧就是原会话，不再闪一下空会话
        sidePanelBookId()?.let { bid -> lastSessionByBook[bid]?.let { _sessionId.value = it } }
        viewModelScope.launch {
            try {
                // 编辑器场景下当前打开的章节不进入章节上下文，由「＋本章梗概」单独加入
                loadBookContextNow(selectedBookIds.value.firstOrNull() ?: defaultBookId)
                // 从章节内进入：按会话归属恢复 / 建立会话，并套用该书上次的上下文选择
                restoreInitialSession()
            } finally {
                // 无论恢复成功与否都要放行，否则发送 / 生成会一直等在恢复信号上
                sessionReady.complete(Unit)
            }
            watchContextChanges()
        }
        // AI 聊天页切换书籍：重新加载该书上下文并套用其上次的选择
        viewModelScope.launch {
            selectedBookIds.drop(1).collect { ids ->
                loadBookContextNow(ids.firstOrNull())
                restoreContextMemory()
            }
        }
    }

    fun loadBookContext(bookId: Long? = null) {
        viewModelScope.launch { loadBookContextNow(bookId) }
    }

    /**
     * 实例销毁时主动取消在途任务：生成协程被取消后 OkHttp 调用会同步中断，
     * 不会留下仍在网络上等待的流式请求，也不会因旧实例残留状态导致界面「正在生成中」。
     */
    override fun onCleared() {
        generationJob?.cancel()
        generationJob = null
        generateJob?.cancel()
        generateJob = null
        super.onCleared()
    }

    private suspend fun loadBookContextNow(bookId: Long? = null) {
        val ids = selectedBookIds.value.toList().ifEmpty { listOfNotNull(bookId) }
        if (ids.isEmpty()) {
            _chapters.value = emptyList()
            _outline.value = emptyList()
            _volumes.value = emptyList()
            _activeVolumeSummaries.value = emptyList()
            _volumeSummaryStale.value = emptyMap()
            selectedVolumeSummaryIds.value = emptySet()
        } else {
            _chapters.value = ids.flatMap { bookRepository.getChapters(it) }
            _outline.value = ids.flatMap { outlineRepository.getOutline(it) }
            _volumes.value = ids.flatMap { bookRepository.getVolumes(it) }
            refreshVolumeSummaryState(ids)
        }
    }

    /** 读取当前书籍的生效卷梗概、过期标记与生效任务，并过滤掉已删除 / 换版的旧选择 */
    private suspend fun refreshVolumeSummaryState(bookIds: List<Long>) {
        val summaries = bookIds.flatMap { volumeSummaryRepository.activeListByBook(it) }
        _activeVolumeSummaries.value = summaries
        _volumeSummaryStale.value = summaries.associate { summary ->
            summary.id to (volumeSummaryRepository.checkFreshness(summary.id)?.stale == true)
        }
        val tasks = mutableMapOf<Long, VolumeCompressionSession>()
        bookIds.forEach { bookId ->
            compressionCoordinator.sessions(bookId)
                .filter { it.status in VolumeCompressionStatus.active }
                .forEach { tasks[it.volumeId] = it }
        }
        _volumeSummaryTasks.value = tasks
        val (kept, dropped) = VolumeSummarySelection.pruneSelected(
            selectedSummaryIds = selectedVolumeSummaryIds.value,
            bookId = bookIds.singleOrNull(),
            activeSummaries = summaries
        )
        if (kept != selectedVolumeSummaryIds.value) {
            selectedVolumeSummaryIds.value = kept
            if (dropped > 0) {
                volumeSummaryMessage.value = "有 $dropped 个整卷梗概已失效或不属于当前书籍，已从选择中移除"
            }
        }
        tasks.values.forEach { watchVolumeSummaryTask(it.id, it.volumeId) }
    }

    /** 会话列表筛选：按书籍加载分卷 / 章节，用于「某卷 / 某章」筛选下拉 */
    fun loadFilterContext(bookId: Long?) {
        viewModelScope.launch {
            if (bookId == null) {
                _filterVolumes.value = emptyList()
                _filterChapters.value = emptyList()
            } else {
                _filterVolumes.value = bookRepository.getVolumes(bookId)
                _filterChapters.value = bookRepository.getChapters(bookId)
            }
        }
    }

    /** 当前上下文记忆归属的书籍：仅单选一本书时有效 */
    private fun memoryBookId(): Long? =
        selectedBookIds.value.singleOrNull() ?: defaultBookId

    /**
     * 进入 AI 面板时决定展示哪个会话：
     * 统一「跟书走」→ 该书最近一次绑定本书的会话（没有则新建）。
     */
    private suspend fun restoreInitialSession() {
        if (fixedChapterId != null) {
            // 统一跟书走：书以进入面板时传入的为准，保证同一本书每次切章都落到同一条会话
            val bookId = sidePanelBookId() ?: memoryBookId()
            if (bookId != null) {
                val existing = chatRepository.getBookSession(bookId)
                val sid = existing?.id
                    ?: createBookSession(bookId, activeModelId.value.takeIf { it > 0 })
                _sessionId.value = sid
                lastSessionByBook[bookId] = sid
                if (existing == null) applyNewBookSessionDefaults(bookId) else restoreContextMemory()
                return
            }
        }
        restoreContextMemory()
    }

    /** 套用「本书上次选中的章节 / 大纲与梗概正文勾选」 */
    private suspend fun restoreContextMemory() {
        val bookId = memoryBookId() ?: return
        val mem = prefs.aiContextMemory(bookId)
        if (mem != null) {
            val chapterIds = _chapters.value.map { it.id }.toSet()
            val outlineIds = _outline.value.map { it.id }.toSet()
            val all = contextChapterIds()
            // 章节是否纳入上下文只看「梗概 / 正文」两列：任一列勾选即自动纳入
            summaryChapterIds.value =
                if (mem.summaryAll) all else mem.summaryChapterIds.filter { it in chapterIds }.toSet()
            contentChapterIds.value =
                if (mem.contentAll) all else mem.contentChapterIds.filter { it in chapterIds }.toSet()
            syncSelectedChapters()
            selectedOutlineIds.value = mem.outlineIds.filter { it in outlineIds }.toSet()
            // 卷梗概是独立资产：只保留仍生效、仍属于本书的版本；失效项提示但不阻塞其余选择
            val (kept, dropped) = VolumeSummarySelection.pruneSelected(
                selectedSummaryIds = mem.volumeSummaryIds.toSet(),
                bookId = bookId,
                activeSummaries = _activeVolumeSummaries.value
            )
            selectedVolumeSummaryIds.value = kept
            if (dropped > 0) {
                volumeSummaryMessage.value = "有 $dropped 个整卷梗概已失效，已从选择中移除"
            }
        }
        refreshAllFlags()
        applyVolumeSummaryRecommendations()
    }

    /**
     * 前序卷推荐：按「分卷 sortOrder,id」取当前创作卷之前、有当前生效卷梗概的分卷，首次进入默认勾选。
     * 用户手动取消过的卷记入按目标卷持久化的排除集，之后不会再被自动勾上；
     * 无法确定当前卷时不擅自推荐。
     */
    private suspend fun applyVolumeSummaryRecommendations() {
        val bookId = memoryBookId() ?: return
        val target = currentTargetVolumeId() ?: return
        val summaries = volumeSummaryRepository.activeListByBook(bookId)
        val excluded = prefs.aiVolumeSummaryReco(bookId)[target].orEmpty()
        val recommended = VolumeSummarySelection.recommendedVolumeIds(
            volumes = _volumes.value.filter { it.bookId == bookId },
            activeSummaries = summaries,
            targetVolumeId = target,
            excludedVolumeIds = excluded
        )
        if (recommended.isEmpty()) return
        val recommendedIds = summaries.filter { it.volumeId in recommended }.map { it.id }.toSet()
        selectedVolumeSummaryIds.value = selectedVolumeSummaryIds.value + recommendedIds
    }

    /** 当前创作卷：固定章节 → 会话绑定章节；无法确定时返回 null（不推荐） */
    private suspend fun currentTargetVolumeId(): Long? {
        val chapterId = fixedChapterId
            ?: _sessionId.value?.let { chatRepository.getSession(it)?.chapterId }
            ?: return null
        return _chapters.value.firstOrNull { it.id == chapterId }?.volumeId
    }

    /** 当前章节上下文的「全部章节」：当前打开的章节同样可以选入上下文 */
    private fun contextChapterIds(): Set<Long> = _chapters.value.map { it.id }.toSet()

    /** 章节纳入上下文由「梗概 / 正文」两列决定：任一列勾选即自动提交该章节 */
    private fun syncSelectedChapters() {
        selectedChapterIds.value = summaryChapterIds.value + contentChapterIds.value
    }

    /** 依据当前选中集合重新判定各列是否仍处于「全选」 */
    private fun refreshAllFlags() {
        val all = contextChapterIds()
        chapterAllSelected.value = all.isNotEmpty() && selectedChapterIds.value == all
        summaryAllSelected.value = all.isNotEmpty() && summaryChapterIds.value.containsAll(all)
        contentAllSelected.value = all.isNotEmpty() && contentChapterIds.value.containsAll(all)
    }

    /** 上下文选择变化时写入「本书记忆」，下次进入同一本书自动套用 */
    private suspend fun watchContextChanges() {
        // combine 最多支持 5 个不同类型的数据源，分两组嵌套后再合并，避免类型推断失败
        val signal = combine(
            combine(selectedChapterIds, selectedOutlineIds, summaryChapterIds, selectedVolumeSummaryIds) { _, _, _, _ -> Unit },
            combine(contentChapterIds, chapterAllSelected, summaryAllSelected, contentAllSelected) { _, _, _, _ -> Unit }
        ) { _, _ -> Unit }
        signal.collect {
            val bookId = memoryBookId() ?: return@collect
            prefs.setAiContextMemory(
                bookId,
                ContextSnapshot(
                    bookIds = selectedBookIds.value.toList(),
                    chapterIds = selectedChapterIds.value.toList(),
                    outlineIds = selectedOutlineIds.value.toList(),
                    summaryChapterIds = summaryChapterIds.value.toList(),
                    contentChapterIds = contentChapterIds.value.toList(),
                    chapterAll = chapterAllSelected.value,
                    summaryAll = summaryAllSelected.value,
                    contentAll = contentAllSelected.value,
                    volumeSummaryIds = selectedVolumeSummaryIds.value.toList()
                )
            )
        }
    }

    /** 单选一本书（选书弹窗改为单选）；再次传同一本时取消选择 */
    fun selectBook(id: Long?) {
        selectedBookIds.value = if (id == null) emptySet() else setOf(id)
        selectedVolumeSummaryIds.value = emptySet()
        volumeSummaryAsk.value = null
        volumeSummaryCandidate.value = null
        loadBookContext()
    }

    /** 快速新建书籍（默认前缀），返回新书 id */
    suspend fun createBook(title: String): Long = bookRepository.createBook(
        title = title.trim(),
        intro = "",
        coverPath = null,
        coverColor = 0xFF3F51B5.toInt(),
        volumePrefix = "第%n卷",
        chapterPrefix = "第%n章"
    )

    /** 完整参数新建书籍（新建书籍表单提交用），返回新书 id；失败返回 0 */
    suspend fun createBookFull(
        title: String,
        intro: String,
        coverPath: String?,
        coverColor: Int,
        volumePrefix: String,
        chapterPrefix: String,
        groupId: Long?,
        styleId: Long = 0
    ): Long = try {
        if (styleId != 0L && styleRepository.getProfile(styleId) == null) {
            retryStyleProfiles()
            0L
        } else {
            bookRepository.createBook(
                title, intro, coverPath, coverColor, volumePrefix, chapterPrefix, groupId, styleId
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        // 事务内档案存在性校验失败：刷新档案列表，由界面提示后重新选择
        retryStyleProfiles()
        0L
    } catch (e: Exception) {
        0L
    }

    /** 复制封面图到应用私有目录，返回本地路径 */
    suspend fun pickCover(uri: android.net.Uri): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.lianwenqi.app.util.FileUtil.copyToInternal(container.context, uri)
        }

    /** 新建分组并返回新分组 id（书籍表单内现场建组用） */
    suspend fun createGroupAndGet(name: String): Long = bookRepository.createGroup(name)

    /** 快速新建书籍并选为当前上下文书籍 */
    fun createBookAndSelect(title: String) = viewModelScope.launch {
        selectBook(createBook(title))
    }

    /** 勾选 / 取消某章是否纳入上下文；取消时同时清掉该章的梗概 / 正文勾选 */
    fun toggleChapter(id: Long) {
        if (id in selectedChapterIds.value) {
            summaryChapterIds.value = summaryChapterIds.value - id
            contentChapterIds.value = contentChapterIds.value - id
        } else {
            // 勾选章节时默认带上「梗概」（与新建跟书会话的默认一致）
            summaryChapterIds.value = summaryChapterIds.value + id
        }
        syncSelectedChapters()
        refreshAllFlags()
    }

    /** 单独勾选 / 取消某章的梗概（勾选即自动提交该章节） */
    fun toggleChapterSummary(id: Long) {
        summaryChapterIds.value = summaryChapterIds.value.toggle(id)
        syncSelectedChapters()
        refreshAllFlags()
    }

    /** 单独勾选 / 取消某章的正文（勾选即自动提交该章节） */
    fun toggleChapterContent(id: Long) {
        contentChapterIds.value = contentChapterIds.value.toggle(id)
        syncSelectedChapters()
        refreshAllFlags()
    }

    fun toggleOutline(id: Long) {
        selectedOutlineIds.value = selectedOutlineIds.value.toggle(id)
    }

    /** 全选 / 取消全选章节：梗概与正文两列都全选（取消时两列都清空） */
    fun selectAllChapters() {
        val all = contextChapterIds()
        val on = !(all.isNotEmpty() &&
            summaryChapterIds.value.containsAll(all) && contentChapterIds.value.containsAll(all))
        summaryChapterIds.value = if (on) all else emptySet()
        contentChapterIds.value = if (on) all else emptySet()
        syncSelectedChapters()
        refreshAllFlags()
    }

    /** 一键全选 / 取消全选「梗概」（对所有章节生效，勾选即自动提交对应章节） */
    fun selectAllSummary() {
        val all = contextChapterIds()
        val on = !(all.isNotEmpty() && summaryChapterIds.value.containsAll(all))
        summaryChapterIds.value = if (on) all else emptySet()
        syncSelectedChapters()
        refreshAllFlags()
    }

    /** 一键全选 / 取消全选「正文」（对所有章节生效，勾选即自动提交对应章节） */
    fun selectAllContent() {
        val all = contextChapterIds()
        val on = !(all.isNotEmpty() && contentChapterIds.value.containsAll(all))
        contentChapterIds.value = if (on) all else emptySet()
        syncSelectedChapters()
        refreshAllFlags()
    }

    /** 整卷勾选 / 取消：梗概列 */
    fun toggleVolumeSummary(volumeId: Long) {
        val ids = volumeChapterIds(volumeId)
        if (ids.isEmpty()) return
        summaryChapterIds.value =
            if (ids.all { it in summaryChapterIds.value }) summaryChapterIds.value - ids
            else summaryChapterIds.value + ids
        syncSelectedChapters()
        refreshAllFlags()
    }

    /** 整卷勾选 / 取消：正文列 */
    fun toggleVolumeContent(volumeId: Long) {
        val ids = volumeChapterIds(volumeId)
        if (ids.isEmpty()) return
        contentChapterIds.value =
            if (ids.all { it in contentChapterIds.value }) contentChapterIds.value - ids
            else contentChapterIds.value + ids
        syncSelectedChapters()
        refreshAllFlags()
    }

    /** 某卷下参与上下文的章节（当前打开的章节也可以勾选） */
    private fun volumeChapterIds(volumeId: Long): Set<Long> =
        _chapters.value.filter { it.volumeId == volumeId }.map { it.id }.toSet()

    /** 一键勾选 / 取消所有已生成的整卷梗概；只处理已生效版本，不会隐式触发压缩 */
    fun toggleAllVolumeSummaryAssets() {
        val all = _activeVolumeSummaries.value.map { it.id }.toSet()
        if (all.isEmpty()) {
            volumeSummaryMessage.value = "当前还没有已生成的整卷梗概"
            return
        }
        val select = !all.all { it in selectedVolumeSummaryIds.value }
        selectedVolumeSummaryIds.value =
            if (select) selectedVolumeSummaryIds.value + all
            else selectedVolumeSummaryIds.value - all
        _activeVolumeSummaries.value.forEach { summary ->
            persistVolumeSummaryManualDecision(summary.volumeId, select)
        }
    }

    // ---------------- F7：整卷梗概勾选 / 推荐 / 自动压缩 ----------------

    /**
     * 勾选 / 取消某卷的「整卷梗概」：只有已生成的生效版本可以勾选，
     * 未生成的卷只提供「生成」入口，勾选不会隐式触发压缩。
     */
    fun toggleVolumeSummaryContext(volumeId: Long) {
        val summary = VolumeSummarySelection.activeByVolume(_activeVolumeSummaries.value)[volumeId]
        if (summary == null) {
            volumeSummaryMessage.value = "该卷尚未生成整卷梗概，请先生成后再勾选"
            return
        }
        val select = summary.id !in selectedVolumeSummaryIds.value
        selectedVolumeSummaryIds.value =
            if (select) selectedVolumeSummaryIds.value + summary.id
            else selectedVolumeSummaryIds.value - summary.id
        persistVolumeSummaryManualDecision(volumeId, select)
    }

    /** 手动生成入口：无论全局自动压缩开关如何，都先让用户选择来源再启动 */
    fun manualCompressVolumeSummary(volumeId: Long) {
        volumeSummaryAsk.value = VolumeSummaryAsk(volumeId, volumeTitle(volumeId))
    }

    /** 来源询问确认：未选择时取消，不创建任务、不改变选择 */
    fun chooseVolumeSummarySource(volumeId: Long, source: String) {
        volumeSummaryAsk.value = null
        viewModelScope.launch { startVolumeSummaryCompression(volumeId, source) }
    }

    fun cancelVolumeSummaryAsk() {
        volumeSummaryAsk.value = null
    }

    fun clearVolumeSummaryMessage() {
        volumeSummaryMessage.value = null
    }

    /**
     * 启动（或关联）整卷压缩：
     * 先预估并拒绝无输入，同卷已有任务时只关联不重复创建；任务运行在应用级协程域，离开面板不取消。
     */
    private suspend fun startVolumeSummaryCompression(volumeId: Long, sourceType: String) {
        val bookId = _volumes.value.firstOrNull { it.id == volumeId }?.bookId
            ?: _activeVolumeSummaries.value.firstOrNull { it.volumeId == volumeId }?.bookId
            ?: selectedBookIds.value.singleOrNull()
            ?: return
        val config = prefs.compressionConfig.first().normalized()
        val modelId = config.compressionModelId
            .takeIf { id -> models.value.any { it.id == id } }
            ?: activeModelId.value.takeIf { id -> models.value.any { it.id == id } }
            ?: models.value.firstOrNull()?.id
            ?: 0L
        if (modelId <= 0L) {
            volumeSummaryMessage.value = "请先在设置中配置并选择一个模型"
            return
        }
        compressionCoordinator.activeSession(volumeId)?.let { existing ->
            watchVolumeSummaryTask(existing.id, volumeId)
            if (existing.status == VolumeCompressionStatus.AWAITING_CONFIRMATION) {
                showVolumeSummaryCandidate(existing)
            } else {
                volumeSummaryMessage.value = "该卷已有压缩任务，已关联进度"
            }
            return
        }
        val request = VolumeCompressionRequest(
            bookId = bookId,
            volumeId = volumeId,
            modelId = modelId,
            sourceType = sourceType,
            maxOutputTokensOverride = config.maxOutputTokens,
            maxInputChars = config.maxCharsPerRequest,
            targetOutputChars = config.outputChars
        )
        when (val estimated = compressionCoordinator.estimate(request)) {
            is CompressionEstimateResult.NoInput -> {
                volumeSummaryMessage.value =
                    "该卷没有可用的${CompressionSourceMode.label(sourceType)}输入，无法生成整卷梗概"
            }

            is CompressionEstimateResult.Failed -> {
                volumeSummaryMessage.value = estimated.failure.message
            }

            is CompressionEstimateResult.Ready -> {
                val scopeSnapshot = if (config.outlineUpdateEnabled) {
                    resolveVolumeOutlineScope(bookId, config)
                } else {
                    ""
                }
                when (
                    val started = compressionCoordinator.start(
                        request = request,
                        configSnapshot = VolumeCompressionConfig.encode(config),
                        outlineScopeSnapshot = scopeSnapshot
                    )
                ) {
                    is CompressionStartResult.Started -> {
                        watchVolumeSummaryTask(started.sessionId, volumeId)
                        val missing = estimated.audit.missingChapterCount
                        val missingTip = if (missing > 0) "，跳过缺失章节 $missing 章" else ""
                        volumeSummaryMessage.value =
                            "已在后台生成整卷梗概（预估输入约 ${estimated.estimate.inputTokens} Token$missingTip）"
                    }

                    is CompressionStartResult.Existing -> {
                        watchVolumeSummaryTask(started.sessionId, volumeId)
                        volumeSummaryMessage.value = "该卷已有压缩任务，已关联进度"
                    }

                    is CompressionStartResult.Rejected ->
                        volumeSummaryMessage.value = started.message
                }
            }
        }
    }

    /** 大纲更新开启时，按最新条目冻结目标 ID 快照；关闭时返回空字符串，不产生大纲请求 */
    private suspend fun resolveVolumeOutlineScope(
        bookId: Long,
        config: VolumeCompressionConfig
    ): String = OutlineScopeResolver.resolve(
        items = outlineRepository.getOutline(bookId),
        scopeMode = config.outlineScopeMode
    ).encodeIds()

    /** 关联任务并观察进度：完成前不加入选择，候选必须由用户确认 */
    private fun watchVolumeSummaryTask(sessionId: Long, volumeId: Long) {
        if (summaryWatchJobs[sessionId]?.isActive == true) return
        summaryWatchJobs[sessionId] = viewModelScope.launch {
            compressionCoordinator.observeSession(sessionId).collect { session ->
                if (session == null) return@collect
                _volumeSummaryTasks.value = if (session.status in VolumeCompressionStatus.active) {
                    _volumeSummaryTasks.value + (volumeId to session)
                } else {
                    _volumeSummaryTasks.value - volumeId
                }
                when (session.status) {
                    VolumeCompressionStatus.AWAITING_CONFIRMATION ->
                        showVolumeSummaryCandidate(session)

                    VolumeCompressionStatus.FAILED -> {
                        summaryWatchJobs.remove(sessionId)
                        volumeSummaryMessage.value =
                            "整卷梗概压缩失败：${session.errorMessage.ifBlank { "未知错误" }}"
                    }

                    VolumeCompressionStatus.CANCELLED,
                    VolumeCompressionStatus.COMPLETED -> summaryWatchJobs.remove(sessionId)

                    else -> Unit
                }
            }
        }
    }

    private suspend fun showVolumeSummaryCandidate(session: VolumeCompressionSession) {
        val candidateId = session.candidateSummaryId ?: return
        if (volumeSummaryCandidate.value?.summaryId == candidateId) return
        val detail = volumeSummaryRepository.getDetail(candidateId) ?: return
        volumeSummaryCandidate.value = VolumeSummaryCandidate(
            sessionId = session.id,
            volumeId = session.volumeId,
            volumeTitle = volumeTitle(session.volumeId),
            summaryId = candidateId,
            content = detail.effectiveContent
        )
    }

    /**
     * 确认候选：「确认使用」才把该卷加入当前选择；
     * 编辑内容在确认生效后写入（候选本身不可编辑）。F6 大纲写入仍由独立授权触发。
     */
    fun confirmVolumeSummaryCandidate(editedContent: String) = viewModelScope.launch {
        val candidate = volumeSummaryCandidate.value ?: return@launch
        val detail = volumeSummaryRepository.getDetail(candidate.summaryId)
        val ok = compressionCoordinator.confirmCandidate(candidate.sessionId)
        if (!ok) {
            volumeSummaryMessage.value = "确认失败，候选可能已失效，请重新生成"
            return@launch
        }
        val active = volumeSummaryRepository.activeOfVolume(candidate.volumeId)
        if (active == null) {
            volumeSummaryMessage.value = "确认后未找到生效卷梗概"
            return@launch
        }
        val trimmed = editedContent.trim()
        if (trimmed.isNotBlank() && trimmed != detail?.modelContent?.trim()) {
            volumeSummaryRepository.saveUserContent(active.id, trimmed)
        }
        volumeSummaryCandidate.value = null
        refreshVolumeSummaryState(
            selectedBookIds.value.toList().ifEmpty { listOf(active.bookId) }
        )
        selectedVolumeSummaryIds.value = selectedVolumeSummaryIds.value + active.id
        volumeSummaryMessage.value = "已使用该卷梗概并加入上下文"
        // F6：卷梗概确认与大纲写入是两次授权，只有配置开启时才在确认后发起分析；
        // 询问模式只生成差异，不自动写入任何条目
        val session = compressionCoordinator.session(candidate.sessionId)
        val config = session?.configSnapshot?.let { VolumeCompressionConfig.decode(it) }
        if (config?.outlineUpdateEnabled == true) {
            container.volumeOutlineUpdateCoordinator.startAsync(candidate.sessionId)
            if (config.outlineUpdateMode ==
                com.lianwenqi.app.data.CompressionOutlineUpdateMode.ASK
            ) {
                volumeSummaryMessage.value =
                    "已加入上下文；大纲差异待确认，可在分卷压缩页查看"
            }
        }
    }

    /** 取消 / 放弃候选：不改变选择，也不发出大纲请求 */
    fun discardVolumeSummaryCandidate() = viewModelScope.launch {
        val candidate = volumeSummaryCandidate.value ?: return@launch
        compressionCoordinator.discardCandidate(candidate.sessionId)
        volumeSummaryCandidate.value = null
        volumeSummaryMessage.value = "已取消，该卷未加入上下文"
    }

    /** 用户手动选择 / 取消：写入按「书 + 目标卷」的记忆，避免下次推荐覆盖手动决定 */
    private fun persistVolumeSummaryManualDecision(volumeId: Long, selected: Boolean) {
        val bookId = memoryBookId() ?: return
        viewModelScope.launch {
            val target = currentTargetVolumeId() ?: return@launch
            val store = prefs.aiVolumeSummaryReco(bookId).toMutableMap()
            store[target] = VolumeSummarySelection.applyManualDecision(
                excluded = store[target].orEmpty(),
                volumeId = volumeId,
                selected = selected
            )
            prefs.setAiVolumeSummaryReco(bookId, store)
        }
    }

    private fun volumeTitle(volumeId: Long): String =
        _volumes.value.firstOrNull { it.id == volumeId }?.title.orEmpty()

    fun selectAllOutline() {
        val all = _outline.value.map { it.id }.toSet()
        selectedOutlineIds.value =
            if (selectedOutlineIds.value.containsAll(all) && all.isNotEmpty()) emptySet() else all
    }

    /** 整组勾选 / 取消勾选给定的大纲条目（大纲选择里的「全选」按当前筛选范围调用） */
    fun toggleOutlines(ids: Set<Long>) {
        if (ids.isEmpty()) return
        selectedOutlineIds.value =
            if (ids.all { it in selectedOutlineIds.value }) selectedOutlineIds.value - ids
            else selectedOutlineIds.value + ids
    }

    /** 大纲中某个分类（人设 / 世界观 / 故事大纲 / 灵感库 / 自定义标签）整组勾选 */
    fun toggleOutlineCategory(category: String) {
        val all = _outline.value.filter { it.category == category }.map { it.id }
        if (all.isEmpty()) return
        selectedOutlineIds.value =
            if (all.all { it in selectedOutlineIds.value }) selectedOutlineIds.value - all.toSet()
            else selectedOutlineIds.value + all.toSet()
    }

    /** 「＋本章梗概」：把当前打开章节的梗概加入 / 移出上下文 */
    fun toggleCurrentSummary() {
        currentSummaryOn.value = !currentSummaryOn.value
    }

    fun appendToInput(text: String) {
        if (text.isBlank()) return
        input.value = if (input.value.isBlank()) text else input.value.trimEnd() + "\n" + text
    }

    /** 一键把作品大纲（人设/故事线/灵感等）加入输入框 */
    fun appendOutline() {
        val text = buildString {
            append("【作品大纲】\n")
            _outline.value.forEach {
                if (it.tag.isNotBlank()) append("[${it.tag}] ")
                append(outlineCategoryLabel(it.category)).append(" - ").append(it.title)
                if (it.content.isNotBlank()) append("：").append(it.content)
                append('\n')
            }
        }
        appendToInput(text.trim())
    }

    /** 一键把章节梗概加入输入框 */
    fun appendSummaries() {
        val text = buildString {
            append("【章节梗概】\n")
            _chapters.value.filter { selectedChapterIds.value.isEmpty() || it.id in selectedChapterIds.value }
                .forEach { append(it.title).append("：").append(it.summary.ifBlank { "（暂无梗概）" }).append('\n') }
        }
        appendToInput(text.trim())
    }

    fun appendPrompt(prompt: Prompt) = appendToInput(prompt.content)

    fun setModel(id: Long) = viewModelScope.launch { prefs.setActiveModelId(id) }

    fun ensureSession(): Long? {
        val existing = _sessionId.value
        if (existing != null) return existing
        return null
    }

    /**
     * 新会话归属的书籍：只有编辑器侧边助手（固定章节）才「跟书走」；
     * 导航栏 AI 里选择的书籍只是上下文，不用于绑定会话，新会话一律未绑定。
     * 已绑定会话不受影响（发送时沿用当前 [sessionId]，不会重新建会话）。
     */
    private fun newSessionBookId(): Long? =
        if (fixedChapterId != null) sidePanelBookId() ?: memoryBookId() else null

    /**
     * 「＋」新开对话：侧边助手新建绑定本书的对话
     * （标题按「书名（现）/（1）…」重排），默认全选章节 + 全选梗概；
     * 导航栏 AI 新建未绑定会话，选择的书籍仍作为上下文保留。
     */
    fun newSession() {
        // 新建会话前先终止上一会话的在途任务：生成态属于整个面板，不清理会让新会话里一直浮着「生成中」
        abortInFlightWork()
        viewModelScope.launch {
            val bookId = newSessionBookId()
            val modelId = activeModelId.value.takeIf { it > 0 }
            if (bookId != null) {
                _sessionId.value = createBookSession(bookId, modelId)
                applyNewBookSessionDefaults(bookId)
            } else {
                _sessionId.value = chatRepository.createSession("新对话", null, modelId, null)
            }
            editingUserMessageId.value = 0L
            currentSummaryOn.value = false
        }
    }

    /** 按当前归属为新会话建立绑定，供发送时兜底建会话使用（导航栏 AI 一律未绑定） */
    private suspend fun createSessionForCurrentMode(modelId: Long): Long {
        val bookId = newSessionBookId()
        return if (bookId != null) createBookSession(bookId, modelId)
        else chatRepository.createSession("新对话", null, modelId, null)
    }

    /** 新建绑定本书的会话，并按「书名（现）/（1）/（2）…」重排该书会话标题 */
    private suspend fun createBookSession(bookId: Long, modelId: Long?): Long {
        val id = chatRepository.createSession("新对话", bookId, modelId, null)
        // 同步进程内缓存：紧接着切章重建 ViewModel 时能直接恢复这条会话，而不是再建一条
        lastSessionByBook[bookId] = id
        val bookTitle = bookRepository.getBook(bookId)?.title.orEmpty().ifBlank { "本书" }
        chatRepository.updateSessions(
            chatRepository.bookSessions(bookId).mapIndexed { index, s ->
                s.copy(title = if (index == 0) "$bookTitle（现）" else "$bookTitle（$index）")
            }
        )
        return id
    }

    /** 新建跟书会话后的默认上下文：章节梗概 / 章节正文 / 大纲按各自的「首轮全选」开关处理 */
    private suspend fun applyNewBookSessionDefaults(bookId: Long) {
        loadBookContextNow(bookId)
        // 首轮全选章节一律排除当前所在章，避免与本轮「本章」内容重复
        val firstRound = _chapters.value
            .filter { it.id != fixedChapterId }
            .map { it.id }
            .toSet()
        summaryChapterIds.value = if (prefs.aiChapterSummaryFirstRound.first()) firstRound else emptySet()
        contentChapterIds.value = if (prefs.aiChapterContentFirstRound.first()) firstRound else emptySet()
        syncSelectedChapters()
        refreshAllFlags()
        if (prefs.aiOutlineFirstRound.first()) {
            // 「首轮全选大纲」：把大纲写进首轮前缀，后续轮次靠前缀复用不再改写。
            // 开启「适配性筛选」时改为只全选「常驻化」的条目，
            // 非常驻化条目改由发送时按本章梗概动态匹配注入。
            val adaptive = prefs.prefixAdaptiveFilterEnabled.first()
            selectedOutlineIds.value = _outline.value
                .filter { !adaptive || it.pinned }
                .map { it.id }
                .toSet()
        } else {
            val remembered = prefs.aiContextMemory(bookId)?.outlineIds.orEmpty().toSet()
            selectedOutlineIds.value = _outline.value.filter { it.id in remembered }.map { it.id }.toSet()
        }
    }

    /** 修改某章标题，并同步到书籍（会话按章节分组、编辑器等均据此刷新） */
    fun renameChapter(chapterId: Long, title: String) = viewModelScope.launch {
        val name = title.trim()
        if (name.isBlank()) return@launch
        val chapter = bookRepository.getChapter(chapterId) ?: return@launch
        bookRepository.saveChapter(chapter.copy(title = name))
        // 重新加载该书章节，让分组卡片标题即时更新
        loadBookContextNow(chapter.bookId)
    }

    fun openSession(id: Long) {
        if (_sessionId.value == id) return
        // 切换会话同样先终止上一会话的在途任务，避免生成态 / 流式卡片跟到新会话
        abortInFlightWork()
        _sessionId.value = id
        volumeSummaryAsk.value = null
        volumeSummaryCandidate.value = null
        viewModelScope.launch {
            chatRepository.getSession(id)?.bookId?.let { bookId ->
                // 侧边助手：记住用户本次选中的本书会话，切章重建后仍回到这一条
                if (bookId == sidePanelBookId()) lastSessionByBook[bookId] = id
                loadBookContextNow(bookId)
                // 目标卷随会话绑定的章节变化：按新目标卷重新计算前序卷推荐（手动排除仍保留）
                applyVolumeSummaryRecommendations()
            }
        }
    }

    fun deleteSession(id: Long) {
        if (_sessionId.value == id) abortInFlightWork()
        // 被删除的会话不能继续留在「书 → 最近会话」缓存里，否则下次切章会先恢复到一条已删除的会话
        lastSessionByBook.entries.removeIf { it.value == id }
        viewModelScope.launch {
            chatRepository.deleteSession(id)
            if (_sessionId.value == id) _sessionId.value = null
        }
    }

    /**
     * 新建 / 切换 / 删除当前会话前，终止上一会话的在途任务并清空跨会话的临时状态。
     * 生成态与「正在生成」回显属于整个面板而不是单个会话，不清理会让新会话一直停在生成中，
     * 或残留上一轮的回答卡片，表现为新会话「未对话却卡在正在生成中」。
     */
    private fun abortInFlightWork() {
        // 切换会话后「本会话首次询问范围」重新计数
        memoryScopeAskedInSession = false
        // 生成梗概 / 生成正文：含收集阶段的弹窗与执行阶段的在途请求
        cancelGeneration()
        generationCancelled = true
        generationJob?.cancel()
        generationJob = null
        generationOutlinePick?.complete(null)
        generationOutlinePick = null
        generationMemoryScopeAsk?.complete(null)
        generationMemoryScopeAsk = null
        generationRefreshAsk?.complete(null)
        generationRefreshAsk = null
        generationSummaryFailed?.complete(SummaryFailureChoice.CANCEL)
        generationSummaryFailed = null
        _generationDialog.value = null
        _generationPlan.value = null
        _generationState.value = FlowState.Idle
        generationMessage.value = null
        endGenerationStreaming()
        // 聊天回答的流式生成
        stopGenerating()
        // 旧会话遗留的待确认弹窗与导入队列
        pendingMemoryAsk.value = null
        pendingImports.value = emptyList()
        importToast.value = null
        editingUserMessageId.value = 0L
    }

    /** 修改会话标题（长按会话触发） */
    fun renameSession(id: Long, title: String) = viewModelScope.launch {
        val t = title.trim()
        if (t.isNotEmpty()) chatRepository.updateSessionTitle(id, t)
    }

    /**
     * 换绑会话的书籍：把会话从原书切到 [newBookId]，并解除章节映射（chapterId 置空）。
     * [keepHistory] 为真保留原有历史对话（继续作为新书上下文的一部分），否则清空历史消息；
     * [keepPreset] 为真保留会话已冻结的预设（此前导入的大纲 / 章节梗概），否则清空预设，
     * 让下次发送按当前（新）书的勾选重新冻结，从而不再夹带原书内容。
     */
    fun rebindSession(sessionId: Long, newBookId: Long, keepHistory: Boolean, keepPreset: Boolean) =
        viewModelScope.launch {
            val session = chatRepository.getSession(sessionId) ?: return@launch
            val old = SessionPrefix.decode(session.prefix)
            val newPrefix = if (keepPreset) {
                // 保留已冻结的书籍 / 大纲 / 章节条目，仅重置增量状态，避免把原书 id 误判成「重新勾选」
                old.copy(
                    checkedBooks = emptyList(),
                    checkedOutlines = emptyList(),
                    checkedChapters = emptyList(),
                    tailBooks = emptyList(),
                    tailOutlines = emptyList(),
                    tailChapters = emptyList(),
                    autoOutlineIds = emptyList(),
                    autoMemoryKeys = emptyList()
                )
            } else {
                // 清空预设并标记为未初始化：下次发送会按当前新书重新冻结，原书内容不再进入请求
                SessionPrefix()
            }
            if (!keepHistory) chatRepository.clearSessionMessages(sessionId)
            chatRepository.updateSession(
                session.copy(
                    bookId = newBookId,
                    chapterId = null,
                    prefix = newPrefix.encode(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (_sessionId.value == sessionId) {
                // 当前打开的会话：上下文选择一并切到新书，清掉旧书残留的章节 / 大纲勾选
                selectedBookIds.value = setOf(newBookId)
                summaryChapterIds.value = emptySet()
                contentChapterIds.value = emptySet()
                syncSelectedChapters()
                selectedOutlineIds.value = emptySet()
                refreshAllFlags()
            }
        }

    /**
     * 修改未绑定章节某轮对话的显示名（长按「第X轮对话」触发）。
     * 只改该轮消息上的名称，不会同步到书籍章节（区别于 [renameChapter]）。
     */
    fun renameRound(userMessageId: Long, name: String) = viewModelScope.launch {
        val n = name.trim()
        if (n.isNotEmpty()) chatRepository.updateMessageRoundName(userMessageId, n)
    }

    /**
     * 启动一次生成任务并记录 Job，便于「停止响应」随时取消。
     * 复位统一放在此处 launch 的 finally 里，并用 [generationToken] 判断归属：
     * 被停止 / 被新任务接管的旧任务不会覆盖新任务的生成态，从根本上避免「一直正在生成中」。
     */
    private fun launchGenerating(block: suspend CoroutineScope.() -> Unit) {
        // 兜底：若已无在途任务但 sending 仍为 true（历史遗留的卡死态），先强制复位再继续
        if (generateJob?.isActive != true && sending.value) resetGeneratingState()
        // 已有生成在途时忽略新请求，避免两个任务互相覆盖生成态
        if (generateJob?.isActive == true || sending.value) return
        val token = ++generationToken
        generateJob = viewModelScope.launch {
            try {
                block()
            } finally {
                if (token == generationToken) resetGeneratingState()
            }
        }
    }

    /** 复位「正在生成」相关的界面状态 */
    private fun resetGeneratingState() {
        sending.value = false
        pendingReplyToId.value = 0L
        streamingContent.value = ""
        streamingReasoning.value = ""
        generationStage.value = null
    }

    /** 停止在途生成：取消协程，进而中断正在等待的 HTTP 请求 */
    fun stopGenerating() {
        val job = generateJob
        generateJob = null
        // 递增令牌：使被取消任务的收尾不会误清新状态（这里已手动复位）
        generationToken++
        job?.cancel()
        // 立即清除生成态，避免被取消的旧任务回调稍后才到、导致界面卡在「正在生成」
        resetGeneratingState()
    }

    /**
     * 「停止响应 / 停止生成」统一入口：根据当前实际在跑的是哪条流程来选择取消方式。
     * 界面不能凭组合期的状态快照判断 —— 快照可能已经过期，走错分支时
     * 「生成正文 / 生成梗概」并不会被取消，界面就会一直停在生成中。
     */
    fun stopCurrentWork() {
        val generationActive = generationJob?.isActive == true ||
            _generationState.value == FlowState.CollectingConfig ||
            _generationState.value == FlowState.Executing
        if (generationActive) cancelGeneration()
        if (generateJob?.isActive == true || sending.value) stopGenerating()
    }

    fun send(overrideText: String? = null, systemPrompt: String = "") = viewModelScope.launch {
        // 消息过长：提示后直接放弃本次发送（不截断、不落库）
        if ((overrideText ?: input.value).trim().length > MAX_CHAT_MESSAGE_CHARS) {
            errorMessage.value = MSG_TOO_LONG
            return@launch
        }
        if (shouldAskMemoryVectorize()) {
            pendingMemoryAsk.value = MemoryVectorizeAsk(overrideText, systemPrompt)
            return@launch
        }
        startSend(overrideText, systemPrompt)
    }

    /** 弹窗应答：是否向量化到最新章节（「是」先续跑，再照常发送） */
    fun resolveMemoryAsk(vectorize: Boolean) = viewModelScope.launch {
        val ask = pendingMemoryAsk.value ?: return@launch
        pendingMemoryAsk.value = null
        if (vectorize) {
            currentMemoryBookId()?.let { bookId ->
                container.memoryRepository.vectorize(bookId) { memoryVectorizing.value = it }
                memoryVectorizing.value = null
            }
        }
        startSend(ask.overrideText, ask.systemPrompt)
    }

    /**
     * 勾选记忆检索时的自动向量化前置处理：
     * A3 开启 → 仅在正文确实比已向量化进度更新时静默续跑，并等增量完成后再发送
     *   （无变化则完全不启动，避免每轮发送都空跑一遍全书向量化、连带刷出一串「正在向量化本书」提示）；
     * A3 关闭 → 若正文比已向量化进度更新，返回 true 交由 UI 弹窗询问。
     */
    private suspend fun shouldAskMemoryVectorize(): Boolean {
        if (!memoryRetrievalEnabled.value) return false
        val bookId = currentMemoryBookId() ?: return false
        if (!hasNewerContentThanVectorized(bookId)) return false
        if (prefs.memoryAutoVectorize.first()) {
            // 静默续跑：不写 memoryVectorizing，界面上不出现任何「正在向量化」提示。
            // 必须等本次增量完成后再进入生成：生成阶段的记忆检索若与向量化并发，
            // 会先读到该章的旧向量块，表现为「改了正文仍召回旧片段 / 新关键词召回不到」。
            var lastError: String? = null
            runCatching {
                container.memoryRepository.vectorize(bookId) { if (it.error != null) lastError = it.error }
            }.onFailure { Log.w(MEMORY_LOG_TAG, "自动向量化失败", it) }
            lastError?.let { Log.w(MEMORY_LOG_TAG, "自动向量化未完成：$it") }
            return false
        }
        return hasNewerContentThanVectorized(bookId)
    }

    /** 本书正文是否比已向量化进度更新（从未向量化也算作有更新） */
    private suspend fun hasNewerContentThanVectorized(bookId: Long): Boolean {
        val latestContent = bookRepository.getChapters(bookId)
            .filter { it.content.isNotBlank() }
            .maxOfOrNull { it.updatedAt } ?: return false
        val vectorized = container.memoryRepository.latestVectorizedUpdatedAt(bookId) ?: return true
        return vectorized < latestContent
    }

    /** 记忆检索作用的书籍：上下文单选的书 → 会话绑定的书 → 默认书 */
    private suspend fun currentMemoryBookId(): Long? =
        selectedBookIds.value.singleOrNull()
            ?: _sessionId.value?.let { chatRepository.getSession(it)?.bookId }
            ?: defaultBookId

    /** 真正开始一次发送（原 [send] 的生成逻辑；弹窗确认后也走这里） */
    private fun startSend(overrideText: String?, systemPrompt: String) = launchGenerating {
        val rawText = (overrideText ?: input.value).trim()
        val hasAttachments = _pendingAttachments.value.isNotEmpty()
        if ((rawText.isBlank() && !hasAttachments) || sending.value) return@launchGenerating
        // 只有附件没有文字时给一句兜底指令，避免空内容落库与空 user 轮次
        val text = rawText.ifBlank { "请查看附件内容" }
        val modelId = activeModelId.value
        if (modelId <= 0L) {
            errorMessage.value = "请先在设置中配置并选择一个模型"
            return@launchGenerating
        }
        // 会话恢复完成前不允许兜底新建：切章瞬间发送会先等恢复，恢复后复用同一条跟书会话
        sessionReady.await()
        val sid = _sessionId.value
            ?: createSessionForCurrentMode(modelId).also { _sessionId.value = it }

        // 编辑历史提示词后发送：改写该条消息，并追加一个新的回答版本
        // （旧回答保留为历史版本，可翻页回看；只丢弃这条之后的后续对话）
        val editingId = editingUserMessageId.value
        if (editingId > 0L) {
            editingUserMessageId.value = 0L
            val target = chatRepository.getMessage(editingId)
            if (target != null && target.sessionId == sid) {
                val next = chatRepository.nextVariantIndex(editingId)
                val staleIds = chatRepository.getMessages(sid)
                    .filter {
                        it.id > editingId &&
                            !(it.role == ChatRole.ASSISTANT && it.replyToId == editingId)
                    }
                    .map { it.id }
                chatRepository.updateMessageContent(editingId, text)
                if (overrideText == null) input.value = ""
                val ok = runGeneration(
                    sid, modelId, systemPrompt,
                    replyToUserId = editingId, historyEndId = editingId, variantIndex = next
                )
                if (ok) chatRepository.deleteMessagesByIds(staleIds)
                return@launchGenerating
            }
        }

        // 「＋本章梗概」与「本轮新勾选的大纲 / 上下文」都作为附加提示词随消息落库（气泡里不显示），
        // 成为历史对话的一部分（挂在消息尾端，不进最前面的参考资料预设块）。
        // 下一轮从数据库重建历史时与上一轮实际发送的内容逐字节一致 → 前缀不被改写 → 缓存命中稳定。
        // 长期记忆检索命中片段同样挂在本轮用户消息尾端落库（进入历史对话的上下文部分最后），
        // 片段文本本身不写会话前缀，只把去重键记进前缀的 autoMemoryKeys。
        val prefix = prepareSessionPrefix(sid)
        // 长期记忆检索：只注入本会话尚未提交过的片段（已提交过的仍在历史对话里，不会丢），
        // 本轮新片段的去重键随即写回会话前缀，后续轮次即使再次召回同一片段也不再重复提交
        val memory = memoryReferenceForText(text, prefix.sessionPrefix)
        if (memory.freshKeys.isNotEmpty()) {
            val sp = prefix.sessionPrefix
            chatRepository.updateSessionPrefix(
                sid, sp.copy(autoMemoryKeys = sp.autoMemoryKeys + memory.freshKeys).encode()
            )
        }
        val memoryRef = memory.text
        val summaryBlock = chapterBlockSuffix()
        // 顺序：新增参考资料 → 本章梗概 → 检索片段（片段参考资料）→ 适配性筛选新增大纲资料 → 用户提示词
        val extra = listOf(prefix.newReference, summaryBlock, memoryRef, prefix.autoOutlineMaterial)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val userId = chatRepository.addMessage(
            sid, ChatRole.USER, text,
            contextSnapshot = currentSnapshot().encode(),
            promptExtra = extra,
            // 记录本轮对话发生在哪一章，供聊天页按章节分组收起历史对话
            chapterId = currentRoundChapterId(),
            // 附件清单随消息落库：图片与文件只存本地路径，重新生成时按路径重新读取
            attachments = attachmentStore.encode(_pendingAttachments.value)
        )
        if (overrideText == null) input.value = ""
        _pendingAttachments.value = emptyList()
        runGeneration(
            sid, modelId, systemPrompt,
            replyToUserId = userId, historyEndId = userId, variantIndex = 0, prefix = prefix,
            logContext = PromptLogContext(
                outlines = prefix.outlineEntries.map { PromptLogNode(outlineLogTitle(it.text), it.text) },
                context = prefix.contextLog,
                summary = summaryBlock,
                memory = memoryRef,
                autoOutline = prefix.autoOutlineMaterial
            )
        )
    }

    /**
     * 本轮对话所属章节：编辑器侧固定章节 → 会话绑定章节 → 会话所属书籍最近打开的章节。
     * 供聊天页把历史对话按章节分组收起；都取不到时返回 0（不参与分组）。
     */
    private suspend fun currentRoundChapterId(): Long {
        fixedChapterId?.let { return it }
        val session = _sessionId.value?.let { chatRepository.getSession(it) }
        session?.chapterId?.let { if (it > 0) return it }
        val bookId = session?.bookId ?: defaultBookId ?: return 0L
        return if (prefs.lastBookId.first() == bookId) prefs.lastChapterId.first() else 0L
    }

    /** 日志里大纲节点的标题：取条目文本的首个非空行 */
    private fun outlineLogTitle(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /** 当前勾选的上下文（书籍/章节/大纲 + 逐章的梗概与正文勾选） */
    fun currentSnapshot(): ContextSnapshot = ContextSnapshot(
        bookIds = selectedBookIds.value.toList(),
        chapterIds = selectedChapterIds.value.toList(),
        outlineIds = selectedOutlineIds.value.toList(),
        summaryChapterIds = summaryChapterIds.value.toList(),
        contentChapterIds = contentChapterIds.value.toList(),
        chapterAll = chapterAllSelected.value,
        summaryAll = summaryAllSelected.value,
        contentAll = contentAllSelected.value,
        volumeSummaryIds = selectedVolumeSummaryIds.value.toList()
    )

    /** 点击编辑某条已发送的消息：恢复它的正文与当时选好的上下文 */
    fun beginEditUserMessage(message: ChatMessage) {
        // 生成轮次：编辑框只放原始提示词，不把「生成正文 / 生成梗概」标记当成提示词的一部分；
        // 生成时由 [openGenerationRound] 按轮次类型补回标记。
        val kind = generationKindOf(message.content)
        input.value = if (kind != null) generationPromptOf(message.content, kind) else message.content
        editingUserMessageId.value = message.id
        val snapshot = decodeContextSnapshot(message.contextSnapshot)
        if (!snapshot.isEmpty) restoreContext(snapshot)
    }

    /** 取消编辑历史提示词，回到普通发送 */
    fun cancelEditUserMessage() {
        editingUserMessageId.value = 0L
        input.value = ""
    }

    /** 把上下文选择恢复成某条消息发送时的状态 */
    fun restoreContext(snapshot: ContextSnapshot) {
        selectedBookIds.value = snapshot.bookIds.toSet()
        selectedOutlineIds.value = snapshot.outlineIds.toSet()
        summaryChapterIds.value = snapshot.summaryChapterIds.toSet()
        contentChapterIds.value = snapshot.contentChapterIds.toSet()
        selectedVolumeSummaryIds.value = snapshot.volumeSummaryIds.toSet()
        syncSelectedChapters()
        viewModelScope.launch {
            loadBookContextNow(snapshot.bookIds.firstOrNull())
            val all = contextChapterIds()
            if (snapshot.chapterAll) {
                if (snapshot.summaryAll) summaryChapterIds.value = all
                if (snapshot.contentAll) contentChapterIds.value = all
            }
            // 历史选择里的卷梗概：只保留仍生效、仍属于该书的版本，避免发送悬空内容
            val (kept, dropped) = VolumeSummarySelection.pruneSelected(
                selectedSummaryIds = snapshot.volumeSummaryIds.toSet(),
                bookId = snapshot.bookIds.singleOrNull(),
                activeSummaries = _activeVolumeSummaries.value
            )
            selectedVolumeSummaryIds.value = kept
            if (dropped > 0) {
                volumeSummaryMessage.value = "有 $dropped 个整卷梗概已失效，已从选择中移除"
            }
            syncSelectedChapters()
            refreshAllFlags()
        }
    }

    /**
     * 重新生成：为某条用户消息追加一个新的回答版本（不重复写入用户消息）。
     * 与 deepseek 一致，可在多个版本间翻阅。
     *
     * 若这条用户消息来自「生成正文 / 生成梗概」，重新生成时复用对应的生成流程，
     * 重新提交本章梗概临时块、记忆检索与字数约束；普通聊天轮次仍走聊天请求。
     */
    fun regenerate(userMessageId: Long, onSummary: (String) -> Unit = {}) = viewModelScope.launch {
        val sid = _sessionId.value ?: return@launch
        val modelId = activeModelId.value
        if (modelId <= 0L) {
            errorMessage.value = "请先在设置中配置并选择一个模型"
            return@launch
        }
        val target = userMessageId.takeIf { it > 0 } ?: return@launch
        val content = chatRepository.getMessage(target)?.content.orEmpty()
        val kind = generationKindOf(content)
        if (kind != null) {
            // 生成轮次：走生成流程，提示词取该轮原始内容（剥掉生成标记），结果作为该轮的新回答版本
            val prompt = generationPromptOf(content, kind)
            launchGeneration { genSid ->
                if (kind == genLabelSummary) {
                    executeGenerateSummary(genSid, prompt, editingId = 0L, regenerateUserId = target)
                } else {
                    executeGenerateContent(
                        genSid, prompt, editingId = 0L, regenerateUserId = target, onSummary = onSummary
                    )
                }
            }
            return@launch
        }
        launchGenerating {
            val next = chatRepository.nextVariantIndex(target)
            runGeneration(sid, modelId, "", replyToUserId = target, historyEndId = target, variantIndex = next)
        }
    }

    /** 保存对话中某条消息的编辑结果 */
    fun updateMessage(id: Long, content: String) = viewModelScope.launch {
        // 编辑后同样不允许超长内容落库：提示后放弃保存，不做截断
        if (content.length > MAX_CHAT_MESSAGE_CHARS) {
            errorMessage.value = MSG_TOO_LONG
            return@launch
        }
        chatRepository.updateMessageContent(id, content)
    }

    /** 保存编辑后的回答，并让 AI 基于修改后的内容继续 */
    fun saveAndContinue(assistantMessageId: Long, content: String, userMessageId: Long) =
        launchGenerating {
            if (content.length > MAX_CHAT_MESSAGE_CHARS) {
                errorMessage.value = MSG_TOO_LONG
                return@launchGenerating
            }
            val sid = _sessionId.value ?: return@launchGenerating
            val modelId = activeModelId.value
            if (modelId <= 0L) {
                errorMessage.value = "请先在设置中配置并选择一个模型"
                return@launchGenerating
            }
            chatRepository.updateMessageContent(assistantMessageId, content)
            val next = chatRepository.nextVariantIndex(userMessageId)
            runGeneration(
                sid, modelId, "",
                replyToUserId = userMessageId,
                historyEndId = assistantMessageId,
                variantIndex = next
            )
        }

    /** 切换到某个回答版本 */
    fun switchVariant(userMessageId: Long, messageId: Long) = viewModelScope.launch {
        chatRepository.setActiveVariant(userMessageId, messageId)
    }

    fun deleteMessage(id: Long) = viewModelScope.launch { chatRepository.deleteMessage(id) }

    /** 收起 / 展开某条回答的正文，状态落库，再次进入会话时保持 */
    fun setContentCollapsed(messageId: Long, collapsed: Boolean) = viewModelScope.launch {
        chatRepository.setContentCollapsed(messageId, collapsed)
    }

    /** 当前上下文（所勾选书籍/章节/大纲）拼装出的文本，用于「文件清单」预览 */
    suspend fun contextPreview(): String = previewFor(currentSnapshot())

    /** 某个上下文快照拼装出的文本，用于已发送消息上的「文件清单」预览 */
    suspend fun previewFor(snapshot: ContextSnapshot): String = chatRepository.buildContext(
        bookIds = snapshot.bookIds,
        chapterIds = snapshot.chapterIds,
        outlineIds = snapshot.outlineIds,
        contentChapterIds = snapshot.contentChapterIds.toSet(),
        summaryChapterIds = snapshot.summaryChapterIds.toSet(),
        volumeSummaryIds = snapshot.volumeSummaryIds
    )

    /**
     * 运行一次生成：把输入打包成 [SendChatRequest] 交给 [SendChatMessageUseCase]，
     * 只负责把流式事件映射到 UI 状态（正文 / 思考增量、工具区块入队、错误）。
     * 返回是否生成成功（供「编辑后发送」决定是否丢弃旧回答）。
     */
    private suspend fun runGeneration(
        sid: Long,
        modelId: Long,
        systemPrompt: String,
        replyToUserId: Long,
        historyEndId: Long,
        variantIndex: Int,
        prefix: PreparedPrefix? = null,
        /** 本轮日志素材（本章梗概 / 检索片段 / 新增大纲资料 / 预设大纲清单）；重新生成时为默认空值 */
        logContext: PromptLogContext = PromptLogContext()
    ): Boolean {
        sending.value = true
        pendingReplyToId.value = replyToUserId
        streamingContent.value = ""
        streamingReasoning.value = ""
        errorMessage.value = null
        var ok = false
        val contentBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        // 会话前缀：正常发送路径已由 [prepareSessionPrefix] 冻结好并传入；
        // 重新生成 / 编辑重发不新增用户消息，这里只读取已冻结的前缀（不做任何写入）。
        // 历史章节块的排除章节取本轮消息所在章节（取不到时按普通聊天的「当前章节」回落）。
        val roundChapterId = chatRepository.getMessage(replyToUserId)?.chapterId?.takeIf { it > 0L }
        val p = prefix ?: readSessionPrefix(sid, excludeChapterId = roundChapterId)
        // 生成态的复位交由 [launchGenerating] 的 finally 统一处理（用 generationToken 判定归属）
        // historyEndId 指向本轮用户消息时把它排除在外；指向助手消息（「编辑回答后继续」）时纳入
        val priorEndId = if (historyEndId == replyToUserId) historyEndId else historyEndId + 1
        // 历史对话块：按本会话已生效的角色开关，从截止位置之前的消息重新生成（不含本轮输入与未完成回复）
        val history = buildHistoryDialogue(p, sid, priorEndId)
        // 本轮附件：图片转 data URL 供多模态直传，文件 / 链接正文作为参考块排在历史之后
        val attachments = attachmentStore.decode(
            chatRepository.getMessage(replyToUserId)?.attachments.orEmpty()
        )
        val imageDataUrls = withContext(Dispatchers.IO) {
            attachments.filter { it.type == "image" }.mapNotNull { attachmentStore.imageDataUrl(it.path) }
        }
        val attachmentBlocks = withContext(Dispatchers.IO) {
            attachments.filter { it.type != "image" }.mapNotNull { att ->
                attachmentStore.textContent(att.path)?.let { text -> "【附件：${att.name}】\n$text" }
            }
        }
        val request = SendChatRequest(
            sessionId = sid,
            modelId = modelId,
            systemPrompt = systemPrompt,
            selection = liveSelection(p.contextOn, p.outlineOn),
            replyToUserId = replyToUserId,
            historyEndId = historyEndId,
            variantIndex = variantIndex,
            writeOutlineTool = writeOutlineToolOn(),
            chapterImportTool = chapterImportToolOn(),
            stylePrefix = p.style,
            prefixText = p.custom,
            referencePrefix = p.reference,
            outlineBlocks = if (p.outlineOn) {
                p.sessionPrefix.renderOutlineBlocks(p.outlineEntries)
            } else {
                emptyList()
            },
            historyPrefix = history.text,
            historyTurns = history.turns,
            images = imageDataUrls,
            attachmentBlocks = attachmentBlocks,
            webSearch = _webSearchEnabled.value,
            historySummary = if (p.historySummaryOn) p.historySummaryBlock else "",
            historyContent = if (p.historyContentOn) p.historyContentBlock else "",
            // 本轮所属章节：沿用该用户消息落库时的章节，重新生成 / 编辑重发不会跳到别的章节
            chapterId = roundChapterId ?: 0L,
            logContext = logContext.copy(rounds = history.rounds)
        )
        sendChatMessage(request).collect { event ->
            when (event) {
                is ChatEvent.Chunk -> {
                    contentBuf.append(event.text)
                    streamingContent.value = contentBuf.toString()
                }

                is ChatEvent.ReasoningChunk -> {
                    reasoningBuf.append(event.text)
                    streamingReasoning.value = reasoningBuf.toString()
                }

                // AI 工具：回答里带可导入标记块时，按勾选的工具挨个入队，UI 逐个询问是否导入
                is ChatEvent.OutlineBlocksReady ->
                    pendingImports.value = pendingImports.value + PendingImport.Outline(event.blocks)

                is ChatEvent.ChapterBlocksReady ->
                    pendingImports.value = pendingImports.value + PendingImport.Chapters(event.blocks)

                is ChatEvent.Error -> errorMessage.value = event.message

                is ChatEvent.ToolFallback -> importToast.value = event.message

                ChatEvent.Done -> ok = true
            }
        }
        return ok
    }

    /**
     * 会话级前缀：区分「一开始的上下文预设」与「后续新勾选内容」的注入位置。
     * - 首轮（[SessionPrefix.initialized] 为 false）：把当时勾选的书籍 / 大纲 / 章节冻结成前置参考资料
     *   预设块，本轮不产生尾部追加文本；同时把当前书籍的文风约束冻结为前缀首块。
     * - 之后每轮：只有「重新勾选」（上次没勾、本次勾）的条目才处理 —— 已在预设内的条目对比内容后
     *   原位替换；从未冻结过的新条目渲染成文本作为「尾部追加」返回，由调用方挂到当轮用户消息尾端
     *   （成为历史对话的一部分），从而不破坏「预设 + 历史对话」的前缀字节结构。
     * 只增不减：取消勾选不移除已冻结内容，也不允许重复追加。
     *
     * @param applyAdaptive 是否顺带执行「适配性筛选」。生成路径传 false：
     *   生成时正文与梗概可能不同步（同时生成梗概时用的是新梗概），故由调用方以实际使用的梗概单独匹配。
     * @param excludeChapterId 本次请求所在的章节：计算「历史梗概 / 历史正文」时排除它；
     *   为空时按普通聊天的「当前章节」回落（没有当前章节则不排除任何已绑定章节）。
     */
    private suspend fun prepareSessionPrefix(
        sid: Long,
        applyAdaptive: Boolean = true,
        excludeChapterId: Long? = null
    ): PreparedPrefix {
        val contextOn = prefs.prefixContextEnabled.first()
        val outlineOn = prefs.prefixOutlineEnabled.first()
        val session = chatRepository.getSession(sid)
        val original = session?.prefix.orEmpty()
        var sp = SessionPrefix.decode(original)
        val firstTime = !sp.initialized
        if (firstTime) {
            // 首次建立前缀时把「自定义前缀」取一次，之后冻结；「历史对话前缀」开关改为每轮实时同步（下方）
            sp = sp.copy(
                custom = prefs.prefixCustomText.first().trim(),
                initialized = true
            )
        }
        // 每轮实时同步「历史对话前缀」五个开关：用户在设置里改了立即生效，
        // 老会话不必重建即可响应；同步后随 updateSessionPrefix 一起持久化。
        sp = sp.copy(
            historySummaryOn = prefs.prefixHistorySummary.first(),
            historyContentOn = prefs.prefixHistoryContent.first(),
            historyMessageOn = prefs.prefixHistoryMessage.first(),
            historyAiOn = prefs.prefixHistoryAi.first(),
            historyUserOn = prefs.prefixHistoryUser.first()
        )
        // 文风块冻结在会话前缀的首块：读到可用文风后不再随档案编辑 / 换绑变化；
        // 为空（未绑定 / 当时读取失败）时继续尝试，避免会话建立后绑定文风却一直不生效
        if (sp.style.isBlank()) {
            val styleBookId = session?.bookId
                ?: fixedChapterId?.let { bookRepository.getChapter(it)?.bookId }
                ?: defaultBookId
                ?: selectedBookIds.value.singleOrNull()
            val styleBlock = container.styleConstraintLoader.apply(styleBookId).block
            if (styleBlock.isNotBlank()) sp = sp.copy(style = styleBlock)
        }

        // 本轮「重新勾选」的新条目（渲染后追加到历史对话尾端，成为历史的一部分）
        val newBooks = mutableListOf<PrefixEntry>()
        val newOutlines = mutableListOf<PrefixEntry>()
        val newVolumeSummaries = mutableListOf<PrefixEntry>()
        val newChapters = mutableListOf<PrefixEntry>()
        var contextLog = ""

        if (contextOn) {
            val bookIds = selectedBookIds.value.toList()
            val volumeSummaryIds = selectedVolumeSummaryIds.value.toList()
            val chapterIds =
                (if (chapterAllSelected.value) contextChapterIds() else selectedChapterIds.value).toList()
            // 已提交过梗概 / 正文的章节：前缀冻结与历史尾部追加都算，
            // 用来判断「本次新勾选的梗概 / 正文」是否还需要再注入（章节本身可能早已在前缀里）。
            val summarySubmitted = sp.summaryChapters.toMutableSet()
            val contentSubmitted = sp.contentChapters.toMutableSet()
            if (firstTime) {
                // 建立前置预设：把首轮勾选的书籍 / 章节一次性冻结进去
                bookIds.forEach { bid ->
                    chatRepository.renderBookEntry(bid)
                        ?.let { sp = sp.copy(books = sp.books.upsert(PrefixEntry(bid, it))) }
                }
                volumeSummaryIds.forEach { sid ->
                    chatRepository.renderVolumeSummaryEntry(sid)
                        ?.let { sp = sp.copy(volumeSummaries = sp.volumeSummaries.upsert(PrefixEntry(sid, it))) }
                }
                chapterIds.forEach { cid ->
                    val summaryOn = chapterSummaryOn(cid)
                    val contentOn = chapterContentOn(cid)
                    chatRepository.renderChapterEntry(cid, summaryOn, contentOn)
                        ?.let { sp = sp.copy(chapters = sp.chapters.upsert(PrefixEntry(cid, it))) }
                    if (summaryOn) summarySubmitted += cid
                    if (contentOn) contentSubmitted += cid
                }
            } else {
                // 旧会话没有记录「已提交过哪些内容」：按已冻结的前缀条目文本推断一次
                if (sp.summaryChapters.isEmpty() && sp.contentChapters.isEmpty()) {
                    sp.chapters.forEach { entry ->
                        if (entry.text.contains("梗概：")) summarySubmitted += entry.id
                        if (entry.text.contains("正文：")) contentSubmitted += entry.id
                    }
                }
                bookIds.filterNot { it in sp.checkedBooks }.forEach { bid ->
                    when {
                        // 已在预设内：对比内容后原位替换（重新勾选才刷新）
                        sp.books.any { it.id == bid } ->
                            chatRepository.renderBookEntry(bid)
                                ?.let { sp = sp.copy(books = sp.books.upsert(PrefixEntry(bid, it))) }
                        // 已追加过尾部：不重复追加
                        bid in sp.tailBooks -> Unit
                        // 新条目：渲染后追加到历史尾端
                        else -> chatRepository.renderBookEntry(bid)?.let { text ->
                            newBooks += PrefixEntry(bid, text)
                            sp = sp.copy(tailBooks = sp.tailBooks + bid)
                        }
                    }
                }
                volumeSummaryIds.filterNot { it in sp.checkedVolumeSummaries }.forEach { sid ->
                    when {
                        // 已冻结：重新勾选后对比内容原位替换（保持位置）
                        sp.volumeSummaries.any { it.id == sid } ->
                            chatRepository.renderVolumeSummaryEntry(sid)
                                ?.let { sp = sp.copy(volumeSummaries = sp.volumeSummaries.upsert(PrefixEntry(sid, it))) }
                        // 已追加过尾部：不重复追加
                        sid in sp.tailVolumeSummaries -> Unit
                        else -> chatRepository.renderVolumeSummaryEntry(sid)?.let { text ->
                            newVolumeSummaries += PrefixEntry(sid, text)
                            sp = sp.copy(tailVolumeSummaries = sp.tailVolumeSummaries + sid)
                        }
                    }
                }
                chapterIds.forEach { cid ->
                    val needSummary = chapterSummaryOn(cid) && cid !in summarySubmitted
                    val needContent = chapterContentOn(cid) && cid !in contentSubmitted
                    if (!needSummary && !needContent) return@forEach
                    // 只渲染本次真正新增的部分：章节已在前缀里时也不改写前缀（保持字节稳定），
                    // 把后补勾选的梗概 / 正文追加到本轮历史尾部。
                    // 渲染失败（章节已被删除）时不记账，下次仍会尝试。
                    val text = chatRepository.renderChapterEntry(cid, needSummary, needContent) ?: return@forEach
                    newChapters += PrefixEntry(cid, text)
                    if (sp.chapters.none { it.id == cid } && cid !in sp.tailChapters) {
                        sp = sp.copy(tailChapters = sp.tailChapters + cid)
                    }
                    if (needSummary) summarySubmitted += cid
                    if (needContent) contentSubmitted += cid
                }
            }
            sp = sp.copy(
                checkedBooks = bookIds,
                checkedChapters = chapterIds,
                checkedVolumeSummaries = volumeSummaryIds,
                summaryChapters = summarySubmitted.toList(),
                contentChapters = contentSubmitted.toList()
            )
            // 日志回显要在首轮清空勾选之前快照：合并「已冻结在前缀里的 + 本轮尾部追加的」卷梗概与章节，
            // 前缀化关闭时直接用当前勾选实时渲染（与请求实际注入的内容同源）
            contextLog = if (contextOn) {
                val volumeSeen = LinkedHashSet<Long>()
                val volumeTexts = mutableListOf<String>()
                (sp.volumeSummaries + newVolumeSummaries).forEach { entry ->
                    if (volumeSeen.add(entry.id)) volumeTexts += entry.text
                }
                val seen = LinkedHashSet<Long>()
                val chapterTexts = mutableListOf<String>()
                (sp.chapters + newChapters).forEach { entry ->
                    if (seen.add(entry.id)) chapterTexts += entry.text
                }
                val rendered = if (chapterTexts.isEmpty()) {
                    listOf(renderCheckedChapters(chapterIds))
                } else {
                    chapterTexts
                }
                (volumeTexts + rendered).filter { it.isNotBlank() }.joinToString("\n\n")
            } else {
                val volumeTexts = renderVolumeSummaryEntries(volumeSummaryIds)
                (listOf(volumeTexts) + listOf(renderCheckedChapters(chapterIds)))
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            }
        }

        if (outlineOn) {
            val outlineIds = selectedOutlineIds.value.toList()
            if (firstTime) {
                outlineIds.forEach { oid ->
                    renderOutlineEntry(oid)?.let { sp = sp.copy(outlines = sp.outlines.upsert(it)) }
                }
            } else {
                outlineIds.filterNot { it in sp.checkedOutlines }.forEach { oid ->
                    when {
                        sp.outlines.any { it.id == oid } ->
                            renderOutlineEntry(oid)?.let { sp = sp.copy(outlines = sp.outlines.upsert(it)) }
                        oid in sp.tailOutlines -> Unit
                        else -> renderOutlineEntry(oid)?.let { entry ->
                            newOutlines += entry
                            sp = sp.copy(tailOutlines = sp.tailOutlines + oid)
                        }
                    }
                }
            }
            sp = sp.copy(checkedOutlines = outlineIds)
        }

        // 「适配性筛选」：非常驻化分类下的条目，用「本章梗概」匹配其「显示标签」，
        // 命中的条目（此前未提交过的）渲染成「新增大纲资料」，随当轮用户消息在检索片段之后、
        // 提示词之前注入；已提交过的 id 记入 [SessionPrefix.autoOutlineIds]，后续不重复提交。
        var autoOutlineMaterial = ""
        // 触发条件只看「适配性筛选」开关本身：不再依赖「大纲前缀化」，
        // 否则关闭大纲前缀化（或没有常驻化分类、首轮无任何大纲被冻结）时筛选完全不生效。
        if (applyAdaptive && prefs.prefixAdaptiveFilterEnabled.first()) {
            // 匹配用梗概：编辑器固定章节 → 会话绑定章节 → 本书最近打开的章节，
            // 与 [currentRoundChapterId] 同一套回落，避免只有固定章节才命中。
            val summaryChapterId = currentRoundChapterId().takeIf { it > 0L }
            val summary = summaryChapterId
                ?.let { bookRepository.getChapter(it)?.summary.orEmpty() }
                .orEmpty()
            val freshIds = adaptiveMatchedOutlineIds(summary).filterNot { it in sp.autoOutlineIds }
            if (freshIds.isNotEmpty()) {
                val freshEntries = freshIds.mapNotNull { renderOutlineEntry(it) }
                sp = sp.markOutlinesInjected(freshIds)
                autoOutlineMaterial = renderAdaptiveOutlineBlock(sp, freshEntries)
            }
        }

        if (session != null && sp.encode() != original) {
            chatRepository.updateSessionPrefix(sid, sp.encode())
        }
        // 「首轮全选大纲 / 章节梗概 / 章节正文」：对应内容已全部冻结进会话前缀，发完这一轮就把对应勾选清掉，
        // 第二轮起默认不勾选（再次勾选才会作为「新增参考资料」追加，不会重复提交前缀里的内容）。
        // 只在对应前缀化开关开启时清空：关闭前缀化时勾选每轮都要实时注入，不能清。
        if (firstTime) {
            if (contextOn) {
                var chaptersChanged = false
                if (prefs.aiChapterSummaryFirstRound.first() && summaryChapterIds.value.isNotEmpty()) {
                    summaryChapterIds.value = emptySet()
                    chaptersChanged = true
                }
                if (prefs.aiChapterContentFirstRound.first() && contentChapterIds.value.isNotEmpty()) {
                    contentChapterIds.value = emptySet()
                    chaptersChanged = true
                }
                if (chaptersChanged) {
                    syncSelectedChapters()
                    refreshAllFlags()
                }
            }
            if (outlineOn && prefs.aiOutlineFirstRound.first()) {
                selectedOutlineIds.value = emptySet()
                refreshAllFlags()
            }
        }
        val appended = sp.renderEntries(newBooks, newOutlines, newChapters, newVolumeSummaries)
        // 历史梗概 / 历史正文：每次发送按本会话已绑定章节重算（不冻结成快照）。
        // 排除本次请求所在章节（生成时为正在生成的章节；普通聊天取当前章节，没有则排除 0）。
        val historyExcludeChapterId = excludeChapterId ?: currentRoundChapterId()
        val historyBlocks = if (sp.historySummaryOn || sp.historyContentOn) {
            buildHistoryChapterBlocks(sid, session, historyExcludeChapterId)
        } else {
            HistoryChapterBlocks()
        }
        return PreparedPrefix(
            custom = sp.custom,
            style = sp.style,
            // 大纲已改为逐条注入（排在历史正文之后），这里不再并入整块参考资料：
            // 否则新追加大纲会让整块前缀变长，连带打断历史正文的缓存
            reference = sp.renderReference(includeChapters = contextOn, includeOutlines = false),
            contextOn = contextOn,
            outlineOn = outlineOn,
            outlineEntries = if (outlineOn) sp.outlines else emptyList(),
            contextLog = contextLog,
            newReference = if (appended.isBlank()) "" else "以下是新增参考资料，请在写作时参考：\n$appended",
            autoOutlineMaterial = autoOutlineMaterial,
            sessionPrefix = sp,
            historySummaryOn = sp.historySummaryOn,
            historyContentOn = sp.historyContentOn,
            historyMessageOn = sp.historyMessageOn,
            historyAiOn = sp.historyAiOn,
            historyUserOn = sp.historyUserOn,
            historySummaryBlock = if (sp.historySummaryOn) historyBlocks.summary else "",
            historySummaryBlocks = if (sp.historySummaryOn) historyBlocks.summaryBlocks else emptyList(),
            historyContentBlock = if (sp.historyContentOn) historyBlocks.content else "",
            historyContentTurns = if (sp.historyContentOn) historyBlocks.contentTurns else emptyList()
        )
    }

    /** 大纲条目的「显示标签」值：内容首项的值，为空时回落条目标题 */
    private fun outlineHeadOf(item: OutlineItem): String {
        val fields = decodeOutlineFields(item.content)
        return fields.firstOrNull()?.second?.ifBlank { item.title } ?: item.title
    }

    /**
     * 「适配性筛选」命中判定（聊天发送与生成正文共用同一套规则）：
     * 未常驻化、「显示标签」被 [summary] 命中的大纲条目 id；梗概为空时返回空。
     */
    private fun adaptiveMatchedOutlineIds(summary: String): List<Long> {
        if (summary.isBlank()) return emptyList()
        return _outline.value
            .filter { !it.pinned }
            .filter { item ->
                val head = outlineHeadOf(item)
                head.isNotBlank() && summary.contains(head)
            }
            .map { it.id }
    }

    /** 把适配性命中的大纲条目渲染成「新增大纲资料」块（措辞与聊天发送路径一致） */
    private fun renderAdaptiveOutlineBlock(sp: SessionPrefix, entries: List<PrefixEntry>): String {
        if (entries.isEmpty()) return ""
        val text = sp.renderEntries(emptyList(), entries, emptyList())
        return if (text.isBlank()) "" else "以下是新增大纲资料，请在写作时参考：\n$text"
    }

    /**
     * 只读取已冻结的会话前缀（重新生成 / 编辑重发 / 生成梗概正文等不新增用户消息的场景），不做任何写入。
     * 历史梗概 / 历史正文与其他历史内容一样按当前数据库状态重算，排除 [excludeChapterId]。
     */
    private suspend fun readSessionPrefix(sid: Long, excludeChapterId: Long? = null): PreparedPrefix {
        val contextOn = prefs.prefixContextEnabled.first()
        val outlineOn = prefs.prefixOutlineEnabled.first()
        val session = chatRepository.getSession(sid)
        val sp = SessionPrefix.decode(session?.prefix.orEmpty())
        val checkedIds =
            (if (chapterAllSelected.value) contextChapterIds() else selectedChapterIds.value).toList()
        val volumeSummaryLog = if (contextOn && sp.volumeSummaries.isNotEmpty()) {
            sp.volumeSummaries.joinToString("\n\n") { it.text }
        } else {
            renderVolumeSummaryEntries(selectedVolumeSummaryIds.value.toList())
        }
        val chapterLog = if (contextOn && sp.chapters.isNotEmpty()) {
            sp.chapters.joinToString("\n\n") { it.text }
        } else {
            renderCheckedChapters(checkedIds)
        }
        val contextLog = listOf(volumeSummaryLog, chapterLog)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val historyBlocks = if (sp.historySummaryOn || sp.historyContentOn) {
            buildHistoryChapterBlocks(sid, session, excludeChapterId ?: currentRoundChapterId())
        } else {
            HistoryChapterBlocks()
        }
        return PreparedPrefix(
            custom = sp.custom,
            style = sp.style,
            // 大纲已改为逐条注入（排在历史正文之后），这里不再并入整块参考资料：
            // 否则新追加大纲会让整块前缀变长，连带打断历史正文的缓存
            reference = sp.renderReference(includeChapters = contextOn, includeOutlines = false),
            contextOn = contextOn,
            outlineOn = outlineOn,
            outlineEntries = if (outlineOn) sp.outlines else emptyList(),
            contextLog = contextLog,
            sessionPrefix = sp,
            historySummaryOn = sp.historySummaryOn,
            historyContentOn = sp.historyContentOn,
            historyMessageOn = sp.historyMessageOn,
            historyAiOn = sp.historyAiOn,
            historyUserOn = sp.historyUserOn,
            historySummaryBlock = if (sp.historySummaryOn) historyBlocks.summary else "",
            historySummaryBlocks = if (sp.historySummaryOn) historyBlocks.summaryBlocks else emptyList(),
            historyContentBlock = if (sp.historyContentOn) historyBlocks.content else "",
            historyContentTurns = if (sp.historyContentOn) historyBlocks.contentTurns else emptyList()
        )
    }

    /**
     * 「历史对话前缀」的历史章节块：本会话绑定过的章节（消息所在章节 + 会话绑定章节），
     * 排除本次请求所在章节 [excludeChapterId]，同一章节去重，按书籍中的章节顺序排列。
     * 逐章读取当前数据库内容（每次发送重算，不冻结成快照）：梗概 / 正文为空的章节直接跳过。
     * 返回的块正文每章都带章节标题，便于模型区分。
     */
    private suspend fun buildHistoryChapterBlocks(
        sid: Long,
        session: ChatSession?,
        excludeChapterId: Long
    ): HistoryChapterBlocks {
        val ids = HistoryChapters.pickIds(
            messageChapterIds = chatRepository.getMessages(sid).map { it.chapterId },
            sessionChapterId = session?.chapterId,
            excludeChapterId = excludeChapterId
        )
        if (ids.isEmpty()) return HistoryChapterBlocks()
        val ordered = HistoryChapters.order(ids.mapNotNull { bookRepository.getChapter(it) })
        if (ordered.isEmpty()) return HistoryChapterBlocks()
        return HistoryChapterBlocks(
            summary = HistoryChapters.renderSummary(ordered),
            summaryBlocks = HistoryChapters.renderSummaryBlocks(ordered),
            content = HistoryChapters.renderContent(ordered),
            contentBlocks = HistoryChapters.renderContentBlocks(ordered),
            contentTurns = HistoryChapters.renderContentTurns(ordered)
        )
    }

    /** 按当前勾选渲染整卷梗概（带真实分卷标题的来源标记），供「提交日志」回显 */
    private suspend fun renderVolumeSummaryEntries(summaryIds: List<Long>): String {
        if (summaryIds.isEmpty()) return ""
        return summaryIds.mapNotNull { chatRepository.renderVolumeSummaryEntry(it)?.trimEnd('\n') }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    /** 按当前勾选渲染章节上下文（梗概 / 正文），供「提交日志」回显 */
    private suspend fun renderCheckedChapters(chapterIds: List<Long>): String {
        val texts = mutableListOf<String>()
        chapterIds.forEach { cid ->
            val summaryOn = chapterSummaryOn(cid)
            val contentOn = chapterContentOn(cid)
            if (summaryOn || contentOn) {
                chatRepository.renderChapterEntry(cid, summaryOn, contentOn)?.let { texts += it }
            }
        }
        return texts.joinToString("\n\n")
    }

    /** 渲染单条大纲条目（文本 + 分类），分类取自当前已加载的大纲列表 */
    private suspend fun renderOutlineEntry(oid: Long): PrefixEntry? {
        val text = chatRepository.renderOutlineEntry(oid) ?: return null
        val category = _outline.value.firstOrNull { it.id == oid }?.category.orEmpty()
        return PrefixEntry(oid, text, category)
    }

    /**
     * 本轮「实时上下文」：前缀化开启的部分不再每轮重算（交由会话前缀承载），
     * 未前缀化时按当前勾选实时拼装。
     */
    private fun liveSelection(contextOn: Boolean, outlineOn: Boolean): ContextSnapshot {
        val snap = currentSnapshot()
        val all = contextChapterIds().toList()
        val chapterIds = when {
            contextOn -> emptyList()
            snap.chapterAll -> all
            else -> snap.chapterIds
        }
        val summaryIds = when {
            contextOn -> emptyList()
            snap.summaryAll -> all
            else -> snap.summaryChapterIds
        }
        val contentIds = when {
            contextOn -> emptyList()
            snap.contentAll -> all
            else -> snap.contentChapterIds
        }
        return snap.copy(
            bookIds = if (contextOn) emptyList() else snap.bookIds,
            chapterIds = chapterIds,
            summaryChapterIds = summaryIds,
            contentChapterIds = contentIds,
            // 卷梗概跟随「章节上下文前缀化」：开启时由冻结前缀承载，实时上下文不再重复注入
            volumeSummaryIds = if (contextOn) emptyList() else snap.volumeSummaryIds,
            outlineIds = if (outlineOn) emptyList() else snap.outlineIds,
            chapterAll = if (contextOn) false else snap.chapterAll
        )
    }

    /** 该章是否纳入「梗概」（用于前缀渲染与文件清单回显） */
    private fun chapterSummaryOn(chapterId: Long): Boolean = chapterId in summaryChapterIds.value

    /** 该章是否纳入「正文」 */
    private fun chapterContentOn(chapterId: Long): Boolean = chapterId in contentChapterIds.value

    /** 会话前缀准备结果：自定义前缀文本 + 前置参考资料 + 两个开关状态 + 本轮尾部追加文本 */
    private data class PreparedPrefix(
        val custom: String,
        /** 已冻结的文风约束块：会话前缀的首块，为空表示本次没有可用文风 */
        val style: String = "",
        val reference: String,
        val contextOn: Boolean,
        val outlineOn: Boolean,
        /** 已冻结进前置预设的大纲条目（供「查看日志」回显选择的大纲清单） */
        val outlineEntries: List<PrefixEntry> = emptyList(),
        /** 「查看日志」回显：本次实际进入请求的章节上下文（已冻结 + 本轮追加） */
        val contextLog: String = "",
        /** 本轮新勾选内容渲染出的文本（挂到当轮用户消息尾端），无新增时为空 */
        val newReference: String = "",
        /** 「适配性筛选」本轮新命中的大纲资料（挂在检索片段之后、用户提示词之前），无新增时为空 */
        val autoOutlineMaterial: String = "",
        /** 会话前缀快照：供长期记忆去重与写回使用（重新生成路径无新消息，用不到） */
        val sessionPrefix: SessionPrefix = SessionPrefix(),
        /** 「历史对话前缀」开关（已冻结在本会话前缀里，修改设置只对新建会话生效） */
        val historySummaryOn: Boolean = false,
        val historyContentOn: Boolean = false,
        val historyMessageOn: Boolean = false,
        val historyAiOn: Boolean = false,
        val historyUserOn: Boolean = false,
        /** 本次实际注入的历史梗概块正文（不含抬头，空串表示不注入） */
        val historySummaryBlock: String = "",
        /** 逐章历史梗概：生成请求按条注入，跨轮保持消息边界稳定以命中前缀缓存 */
        val historySummaryBlocks: List<String> = emptyList(),
        /** 本次实际注入的历史正文块正文（不含抬头，空串表示不注入） */
        val historyContentBlock: String = "",
        /** 逐章历史正文：生成请求还原成「user 要求生成 → assistant 正文」的真实轮次注入 */
        val historyContentTurns: List<HistoryContentTurn> = emptyList()
    )

    /** 长期记忆检索结果：本轮实际注入的参考资料文本 + 其中新片段的去重键（写回会话前缀用） */
    private data class MemoryRef(val text: String = "", val freshKeys: List<String> = emptyList())

    /** 生成路径的参考资料组装结果（见 [buildGenerationReference]） */
    private data class GenerationRef(
        /** 组装时的会话前缀快照（选定标签的「已提交」判定与后续记忆去重写回都基于它） */
        val sessionPrefix: SessionPrefix = SessionPrefix(),
        /** 与聊天请求逐字一致的前置参考资料（会话前缀 + 实时上下文），用于共享服务端前缀缓存 */
        val sharedReference: String = "",
        /** 生成专用块的参考资料：本轮新增（新勾选内容 + 选定标签 + 适配性命中） */
        val extra: String = "",
        /** 「查看日志」用：本轮新增的大纲参考资料 */
        val autoOutlineLog: String = "",
        /** 本轮首次提交的选定标签 id：随消息落库后记入前缀去重 */
        val freshOutlineIds: List<Long> = emptyList()
    )

    /**
     * 本轮实际注入的历史对话：System 块完整文本（含抬头，日志直接回显它）与日志轮次。
     * 消息内容每次发送按数据库现状重算，不落进 [SessionPrefix]。
     */
    private data class HistoryDialogue(
        /** 实际注入的历史对话全文（含「【历史对话】」抬头），仅供日志回显 */
        val text: String = "",
        /** 实际发给模型的真实历史轮次；聊天请求用它替代 System 文本块 */
        val turns: List<ChatTurn> = emptyList(),
        val rounds: List<PromptLogRound> = emptyList()
    )

    /** 历史章节块正文（不含「【历史梗概】/【历史正文】」抬头）：按本会话绑定章节重算 */
    private data class HistoryChapterBlocks(
        val summary: String = "",
        /** 逐章拆开的历史梗概：生成请求按条注入，保证跨轮消息边界稳定、可命中前缀缓存 */
        val summaryBlocks: List<String> = emptyList(),
        val content: String = "",
        /** 逐章拆开的历史正文：生成请求按条注入，保证跨轮消息边界稳定、可命中前缀缓存 */
        val contentBlocks: List<String> = emptyList(),
        /** 逐章拆开的历史正文（结构化）：生成请求还原成 user/assistant 真实轮次注入 */
        val contentTurns: List<HistoryContentTurn> = emptyList()
    )

    /** 生成路径落库的「用户消息」标记：把生成轮次与普通对话区分开 */
    private val genLabelSummary = "生成梗概"
    private val genLabelContent = "生成正文"

    /**
     * 识别一条用户消息属于哪种生成轮次：内容以「生成正文 / 生成梗概」标记开头时返回该标记，
     * 普通聊天返回 null。「重新生成」据此决定是否复用生成流程（含本章梗概临时块、记忆检索、字数约束）。
     */
    private fun generationKindOf(content: String): String? = when {
        content == genLabelContent || content.startsWith("$genLabelContent\n") -> genLabelContent
        content == genLabelSummary || content.startsWith("$genLabelSummary\n") -> genLabelSummary
        else -> null
    }

    /** 从生成轮次的用户消息里取出原始提示词（去掉生成标记） */
    private fun generationPromptOf(content: String, label: String): String =
        content.removePrefix(label).trimStart('\n')

    /**
     * 「＋本章梗概」：勾选时随消息发送但不在气泡显示的附加提示词（含章节名与梗概）。
     * 与 content 一起落库，重建历史时拼回，避免每换一章就改写缓存前缀。
     */
    private suspend fun chapterBlockSuffix(): String {
        if (!currentSummaryOn.value) return ""
        val chapterId = fixedChapterId ?: return ""
        val ch = bookRepository.getChapter(chapterId) ?: return ""
        return buildString {
            append("【本章】").append(ch.title)
            if (ch.summary.isNotBlank()) append('\n').append("梗概：").append(ch.summary)
        }
    }

    /**
     * 长期记忆检索：query = 本章梗概（编辑器场景；本章无梗概则不做检索），
     * 导航栏 AI 对话没有「本章」，回退用本轮提示词；被检索内容 = 本书已向量化的正文块。
     * 命中片段拼成附带提示词，随本轮用户消息一起落库（进入历史对话的上下文部分最后），
     * 从而在后续轮次里也能被模型看到；不写会话前缀，前缀字节结构不变。
     * 去重：本会话已提交过的片段（[SessionPrefix.autoMemoryKeys]）不再重复注入，
     * 只有新召回的片段才进入本轮，避免同一批参考资料在多轮里反复占 token。
     * 未勾选 / 未绑定书 / 未配置 Embedding / 无命中（或命中片段全部已提交过）时一律返回空，不影响正常发送。
     */
    private suspend fun memoryReferenceForText(userText: String, sp: SessionPrefix): MemoryRef {
        if (!memoryRetrievalEnabled.value) return MemoryRef()
        val bookId = currentMemoryBookId() ?: return MemoryRef()
        // 编辑器侧边助手：query 一律取「本章梗概」，本章无梗概则不做检索（勾选时已提示）；
        // 导航栏 AI 对话：没有「本章」，回退用本轮提示词。
        val chapterId = fixedChapterId
        val query = if (chapterId != null) {
            bookRepository.getChapter(chapterId)?.summary.orEmpty().also { if (it.isBlank()) return MemoryRef() }
        } else {
            userText
        }
        if (query.isBlank()) return MemoryRef()
        // 检索耗时（本地工具为 CPU 推理，首次还要加载重排模型）：如实上报状态，界面显示「正在检索中」
        memorySearchState.value = MemorySearchState.SEARCHING
        val hits = try {
            // 区块数（5-20）与范围偏好（随机匹配 / 最新章节优先 / 最早章节优先）来自「记忆检索区块设置」
            val quota = prefs.memoryBlockQuota.first()
            val scope = prefs.memorySearchScope.first()
            // 侧边助手写的是当前这一章，把本章自己的块排除掉，检索才会真的去翻本书其它章节；
            // 开启「记忆检索是否限定范围」时只检索用户勾选的章节；
            // 开启「仅本会话首次询问」后，再自动纳入选定范围之后新向量化 / 更新的章节
            val scopeIds = if (prefs.memoryScopeLimitEnabled.first()) {
                val remembered = memoryScopeSelection.value
                    ?.takeIf { it.bookId == bookId }
                    ?.chapterIds
                if (remembered != null && prefs.memoryScopeAskOncePerSession.first()) {
                    val current = container.memoryRepository.vectorizedTimes(bookId)
                    val refreshed = current.filter { (id, time) ->
                        memoryScopeVectorSnapshot[id] != time
                    }.keys
                    remembered + refreshed
                } else {
                    remembered
                }
            } else {
                null
            }
            container.memoryRepository.retrieve(
                bookId, query, quota,
                excludeChapterId = fixedChapterId,
                scope = scope,
                chapterIds = scopeIds
            )
        } finally {
            memorySearchState.value = MemorySearchState.DONE
        }
        if (hits.isEmpty()) {
            // 检索一无所获：若本书向量还是「上一个向量化工具 / 模型」写入的，
            // 说明换源后没重新向量化（旧向量与当前查询向量算不了余弦），明确提示用户
            if (container.memoryRepository.vectorSourceStale(bookId)) {
                memoryToast.value = "本书向量由其它向量化工具生成，请先重新向量化后再使用记忆检索"
            }
            return MemoryRef()
        }
        // 去重：本会话已提交过的片段不再重复注入（内容仍在历史对话里，不会丢）
        val fresh = sp.freshMemoryHits(hits)
        if (fresh.isEmpty()) return MemoryRef()
        val text = buildString {
            append("【参考资料·本书相关正文片段】\n")
            fresh.forEachIndexed { i, t -> append("片段${i + 1}：").append(t).append('\n') }
            // 结尾再点一次：这些片段是「检索结果」，只供写文模型参考，
            // 必须由写文模型加工后回答用户，不能把片段本身当成回答输出
            append("（以上片段来自本书已写正文，仅供写作参考，请勿照抄或复述；请按用户的具体要求作答。）")
        }
        return MemoryRef(text, fresh.map { shortHash(it) })
    }
}

/** 前缀条目「只增不减」的更新：不存在则追加，内容有变更则原位替换，否则原样返回 */
private fun List<PrefixEntry>.upsert(entry: PrefixEntry): List<PrefixEntry> {
    val index = indexOfFirst { it.id == entry.id }
    if (index < 0) return this + entry
    val old = this[index]
    if (old.text == entry.text && old.category == entry.category) return this
    return toMutableList().also { it[index] = entry }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (contains(id)) this - id else this + id

/** 待导入内容：AI 回答中解析出的可写入数据，逐个弹窗询问用户 */
sealed interface PendingImport {
    /** 写入大纲 */
    data class Outline(val blocks: List<AiOutlineBlock>) : PendingImport

    /** 导入章节梗概 */
    data class Chapters(val blocks: List<AiChapterBlock>) : PendingImport
}

/** 「正文有更新，是否向量化到最新章节？」弹窗的挂起请求（保留原发送参数，应答后继续发送） */
data class MemoryVectorizeAsk(val overrideText: String?, val systemPrompt: String)

/** 勾选未生成卷梗概、需要用户先选择压缩来源的挂起请求 */
data class VolumeSummaryAsk(val volumeId: Long, val volumeTitle: String)

/** 压缩完成、等待用户确认「使用 / 编辑后使用 / 取消」的候选卷梗概 */
data class VolumeSummaryCandidate(
    val sessionId: Long,
    val volumeId: Long,
    val volumeTitle: String,
    val summaryId: Long,
    val content: String
)

/** 记忆检索进行状态：界面据此显示「正在检索中」/「检索完成」 */
enum class MemorySearchState {
    /** 本轮没有检索（未勾选 / 尚未发送 / 已取消勾选） */
    IDLE,

    /** 正在向量化 query 并召回 / 精排候选 */
    SEARCHING,

    /** 本轮检索已结束（无论是否有命中） */
    DONE
}