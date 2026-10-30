package com.lianwenqi.app.ui.screens

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
import com.lianwenqi.app.ai.RetrievalMode
import com.lianwenqi.app.ai.SUMMARY_FALLBACK_INPUT
import com.lianwenqi.app.ai.CONTENT_FALLBACK_INPUT
import com.lianwenqi.app.ai.PRIOR_CONTEXT_HEADER
import com.lianwenqi.app.ai.SummaryFailureChoice
import com.lianwenqi.app.ai.buildContentTurns
import com.lianwenqi.app.ai.buildGenerationPrefixTurns
import com.lianwenqi.app.ai.buildRetrievalQuery
import com.lianwenqi.app.ai.buildSummaryReferenceBlock
import com.lianwenqi.app.ai.buildSummaryTurns
import com.lianwenqi.app.ai.contentDialogOrder
import com.lianwenqi.app.ai.enforceWordRange
import com.lianwenqi.app.data.AiChapterImporter
import com.lianwenqi.app.data.AiOutlineImporter
import com.lianwenqi.app.data.ChatEvent
import com.lianwenqi.app.data.GEN_KIND_SUMMARY
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
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.util.AI_CHAPTER_TOOL_PROMPT
import com.lianwenqi.app.util.AI_OUTLINE_TOOL_PROMPT
import com.lianwenqi.app.util.AI_TOOL_IMPORT_CHAPTER
import com.lianwenqi.app.util.AI_TOOL_WRITE_OUTLINE
import com.lianwenqi.app.util.AiChapterBlock
import com.lianwenqi.app.util.AiOutlineBlock
import com.lianwenqi.app.util.detectImportKindHint
import com.lianwenqi.app.util.isChapterLikeOutlineBlock
import com.lianwenqi.app.util.parseAiChapterBlocks
import com.lianwenqi.app.util.parseAiOutlineBlocks
import com.lianwenqi.app.util.shortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    val sessions: StateFlow<List<ChatSession>> =
        chatRepository.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _sessionId = MutableStateFlow<Long?>(null)
    val sessionId: StateFlow<Long?> = _sessionId.asStateFlow()

    val messages: StateFlow<List<ChatMessage>> = _sessionId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeModelId: StateFlow<Long> = prefs.activeModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()

    private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
    val outline: StateFlow<List<OutlineItem>> = _outline.asStateFlow()

    /** 当前书籍被「常驻化」的大纲分类标签集合（供大纲选择筛选与适配性筛选判断） */
    private val _pinnedOutlineCategories = MutableStateFlow<Set<String>>(emptySet())
    val pinnedOutlineCategories: StateFlow<Set<String>> = _pinnedOutlineCategories.asStateFlow()

    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<Volume>> = _volumes.asStateFlow()

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

    /**
     * 切换记忆检索：
     * - 取消勾选：直接关闭持久勾选；
     * - 勾选：必须先有书籍（未选书时提示，不勾选）；本书从未向量化时先弹窗询问是否向量化全书，
     *   向量化完成后再继续；开启「限定范围」时接着让用户从**已向量化的章节**中选择检索范围。
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
        // 本书还没有任何向量化结果：先询问是否向量化，等向量化完成后再选范围 / 勾选
        if (container.memoryRepository.latestVectorizedUpdatedAt(bookId) == null) {
            pendingEmptyVectorizeAsk.value = true
            return@launch
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
            val cachedTokens: Int = 0
        ) : SummaryCall

        data class Failed(val message: String) : SummaryCall
    }

    /**
     * 侧边抽屉「生成梗概」：限定范围开启时先弹标签选择，否则直接执行。
     * 生成结果作为一条普通 AI 回答进入本会话（用户消息 = 输入框提示词 + 「生成梗概」标记），
     * 不再自动写回本章；由用户在回答操作栏点「写入梗概」决定是否写入。
     */
    fun startGenerateSummary() = launchGeneration { sid ->
        // 提示词过长：提示后直接放弃本次生成（不截断、不落库）
        if (input.value.trim().length > MAX_CHAT_MESSAGE_CHARS) {
            generationMessage.value = MSG_TOO_LONG
            return@launchGeneration
        }
        val chapterId = fixedChapterId
        if (chapterId == null) {
            noticeThenCancel("请在章节编辑页使用「生成梗概」")
            return@launchGeneration
        }
        val chapterTitle = bookRepository.getChapter(chapterId)?.title.orEmpty()
        val plan = GenerationPlan(
            mode = GenerationMode.SUMMARY_ONLY,
            summaryWordRange = prefs.genSummaryWordRange.first(),
            contentWordRange = null,
            selectedOutlineIds = if (prefs.genSummaryLimitScope.first()) {
                askSummaryOutlines() ?: return@launchGeneration
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
            return@launchGeneration
        }
        // 生成路径也走 [prepareSessionPrefix]：首次调用把已勾选的大纲 / 上下文冻结成会话前缀，
        // 之后逐轮复用 → 参考资料前缀跨轮逐字节稳定，缓存命中率随轮次提高
        val prefix = prepareSessionPrefix(sid, applyAdaptive = false)
        val ref = buildGenerationReference(prefix, plan.selectedOutlineIds)
        val userPrompt = input.value
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
            editingId = editingUserMessageId.value
        )
        // 提示词已随本轮用户消息落库（相当于发送成功），清空输入框，避免下一轮误重复发送
        input.value = ""
        // 选定标签的内容已随本轮消息落库，此时记入前缀去重（后续轮次只写「参见大纲『X』」）
        markGenerationOutlines(sid, ref.sessionPrefix, ref.freshOutlineIds)
        // 生成请求：聊天同构前缀块 +（开关开启时）「前情提要」参考资料块 + 生成专用块（指令 + 新增参考资料 + 用户输入）
        val history =
            if (prefs.genCarryHistory.first()) generationHistory(sid, round.userId, modelId) else GenerationHistory()
        val turns = assembleGenerationTurns(
            prefix, ref, history, buildSummaryTurns(userPrompt, plan.summaryWordRange, ref.extra, chapterTitle)
        )
        // 生成过程中的逐步输出实时回显在本轮对话的回答位置（显示「正在生成」）
        beginGenerationStreaming(round.userId)
        when (val r = runSummaryCall(plan, turns, stream = true)) {
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
        // 提示词过长：提示后直接放弃本次生成（不截断、不落库）
        if (input.value.trim().length > MAX_CHAT_MESSAGE_CHARS) {
            generationMessage.value = MSG_TOO_LONG
            return@launchGeneration
        }
        val chapterId = fixedChapterId
        if (chapterId == null) {
            noticeThenCancel("请在章节编辑页使用「生成正文」")
            return@launchGeneration
        }
        val chapter = bookRepository.getChapter(chapterId)
        val chapterTitle = chapter?.title.orEmpty()
        // 先固定本轮提示词：后续弹窗 / 生成期间输入框的变化不再影响本轮实际发送的内容
        val userPrompt = input.value
        val withSummary = prefs.genContentWithSummary.first()
        val limitScope = prefs.genSummaryLimitScope.first()
        val mode = if (withSummary) {
            GenerationMode.CONTENT_WITH_SUMMARY
        } else {
            if (bookRepository.getChapter(chapterId)?.summary.orEmpty().isBlank()) {
                noticeThenCancel("请先填写本章梗概，或在设置中开启「同时生成梗概」")
                return@launchGeneration
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
                    picked = askSummaryOutlines() ?: return@launchGeneration
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
                        ids.filter { it in vectorized }.toSet()
                    }
                    ?.takeIf { it.isNotEmpty() }
                if (remembered != null) {
                    memoryChapterIds = remembered
                } else {
                    val pick = buildMemoryScopePick(memoryBookId, onlyVectorized = true)
                    // 没有任何已向量化章节时不弹范围选择，按全书检索继续生成（结果自然为空）
                    if (pick != null) {
                        memoryChapterIds = askMemoryScope(pick) ?: return@launchGeneration
                    }
                }
            }
        }
        if (generationCancelled) return@launchGeneration
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
            return@launchGeneration
        }
        val memoryCap = prefs.memoryBlockQuota.first()
        val memoryScope = prefs.memorySearchScope.first()

        // 生成路径也走 [prepareSessionPrefix]：首次调用把已勾选的大纲 / 上下文冻结成会话前缀
        val prefix = prepareSessionPrefix(sid, applyAdaptive = false)
        // 选定标签只在「会生成梗概」时作为指定大纲参考资料提交：
        // 已在本会话前缀里的只写「参见大纲『X』」；未提交过的取完整内容并入本轮新增参考资料
        val ref = buildGenerationReference(
            prefix,
            if (mode == GenerationMode.CONTENT_ONLY) emptyList() else plan.selectedOutlineIds
        )
        var sp = ref.sessionPrefix

        // 生成请求携带的历史：本轮的用户消息在正文落库前才写入，故此处取到当前全部轮次；
        // 编辑历史提示词后再生成时，历史只取到被编辑的那一轮之前（该轮旧内容不进前情提要）
        val editingId = editingUserMessageId.value
        val history =
            if (prefs.genCarryHistory.first()) {
                generationHistory(sid, editingId.takeIf { it > 0L } ?: Long.MAX_VALUE, modelId)
            } else {
                GenerationHistory()
            }
        val summary = if (mode == GenerationMode.CONTENT_WITH_SUMMARY) {
            awaitSummaryWithRetry(
                plan,
                assembleGenerationTurns(
                    prefix, ref, history, buildSummaryTurns(userPrompt, plan.summaryWordRange, ref.extra, chapterTitle)
                ),
                onSummary
            )
                ?: run {
                    if (!generationCancelled) _generationState.value = FlowState.Failed
                    return@launchGeneration
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

        val memoryRef = if (plan.memoryRetrieval == RetrievalMode.ON) {
            buildMemoryBlock(plan, chapterId, summary, memoryCap, memoryScope, sp)
        } else {
            MemoryRef()
        }
        if (memoryRef.freshKeys.isNotEmpty()) {
            sp = sp.copy(autoMemoryKeys = sp.autoMemoryKeys + memoryRef.freshKeys)
            chatRepository.updateSessionPrefix(sid, sp.encode())
        }

        // 正文专用块：文风约束已冻结在会话前缀首块（见 [prepareSessionPrefix]），这里不再重复注入
        val contentTurns = buildContentTurns(
            summary = summary,
            // 生成专用块只带本轮新增的大纲资料；已冻结的会话前缀已在前段共享块里
            outlineSlot = listOf(ref.extra, adaptive).filter { it.isNotBlank() }.joinToString("\n\n"),
            memoryBlock = memoryRef.text,
            userInput = userPrompt,
            range = plan.contentWordRange,
            chapterTitle = chapterTitle
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
            editingId = editingId
        )
        // 选定标签的内容已随本轮消息落库，此时记入前缀去重（后续轮次只写「参见大纲『X』」）
        markGenerationOutlines(sid, sp, ref.freshOutlineIds)
        // 提示词即将随请求发出：立刻清空输入框，避免生成期间误以为没发送出去、再次点击重复生成
        input.value = ""
        // 「同时生成梗概」时梗概子调用保持静默，只把正文输出实时回显到对话中
        beginGenerationStreaming(round.userId)
        val result = configRepository.sendToModel(
            modelId,
            AiRequest(turns = turns, model = "", temperature = 0f, maxTokens = 0),
            onDelta = generationDeltaHandler()
        )
        if (generationCancelled) return@launchGeneration
        if (!result.success) {
            generationMessage.value = result.error?.userMessage ?: "生成失败"
            _generationState.value = FlowState.Failed
            return@launchGeneration
        }
        val raw = result.content.trim()
        if (raw.isBlank()) {
            generationMessage.value = "模型返回空内容"
            _generationState.value = FlowState.Failed
            return@launchGeneration
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
                genKind = "",
                summary = summary,
                memory = memoryRef.text,
                autoOutline = listOf(ref.autoOutlineLog, adaptive).filter { it.isNotBlank() }.joinToString("\n\n"),
                userPrompt = userPrompt,
                fullPrompt = userPrompt.trim().ifBlank { CONTENT_FALLBACK_INPUT }
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
        if (state != FlowState.CollectingConfig && state != FlowState.Executing) return
        generationCancelled = true
        generationOutlinePick?.complete(null)
        generationOutlinePick = null
        generationMemoryScopeAsk?.complete(null)
        generationMemoryScopeAsk = null
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
        // 执行阶段重复点击：明确提示，不再静默忽略（静默会让人误以为「卡在正在生成中」）
        if (_generationState.value == FlowState.Executing) {
            generationMessage.value = "当前正在生成中，请先点击「停止」后再重新发起"
            return
        }
        if (generationJob?.isActive == true) {
            // 收集阶段但已没有任何弹窗在等结果 → 陈旧任务永远等不到结果，直接接管；
            // 弹窗还在时则让用户先在弹窗里取消，避免两个任务的弹窗互相覆盖
            if (_generationState.value == FlowState.CollectingConfig && _generationDialog.value == null) {
                cancelGeneration()
            } else {
                generationMessage.value = "请先完成或取消当前的生成设置后再试"
                return
            }
        }
        val token = ++generationSeq
        generationCancelled = false
        generationMessage.value = null
        endGenerationStreaming()
        _generationState.value = FlowState.CollectingConfig
        generationJob = viewModelScope.launch {
            val sid = _sessionId.value
            try {
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
        stream: Boolean = false
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
        val enforced = enforceWordRange(raw, plan.summaryWordRange)
        // 落库的是截断 / 校正后的梗概：把回显内容同步成最终文本，避免完成后内容突变
        if (stream && streamingContent.value.isNotEmpty()) streamingContent.value = enforced.text
        if (enforced.over) {
            generationMessage.value = "梗概超出设定上限，已截断到最后一个完整句子"
        } else if (enforced.under) {
            generationMessage.value = "梗概略短于设定下限，可直接使用"
        }
        return SummaryCall.Ok(
            enforced.text,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            cachedTokens = result.cachedTokens
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
        onSummary: (String) -> Unit
    ): String? {
        val chapterId = fixedChapterId ?: return null
        while (true) {
            when (val r = runSummaryCall(plan, turns)) {
                is SummaryCall.Ok -> {
                    // 生成的梗概静默写回本章梗概（经编辑器本地缓冲，避免被下次整章保存覆盖）
                    onSummary(r.text)
                    return r.text
                }
                is SummaryCall.Failed -> {
                    while (true) {
                        val existing = bookRepository.getChapter(chapterId)?.summary.orEmpty()
                        when (askSummaryFailure(hasExisting = existing.isNotBlank())) {
                            SummaryFailureChoice.RETRY -> break
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
        val hits = try {
            container.memoryRepository.retrieveScored(
                bookId = bookId,
                query = query,
                topK = topK.coerceAtLeast(1),
                excludeChapterId = chapterId,
                scope = scope,
                chapterIds = plan.memoryChapterIds
            )
        } catch (e: Throwable) {
            emptyList()
        }
        memorySearchState.value = MemorySearchState.DONE
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
        editingId: Long
    ): GenRound {
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
        // 编辑态：消费掉编辑状态，改写那条用户消息，并作废其后继对话
        editingUserMessageId.value = 0L
        val staleIds = chatRepository.getMessages(sid)
            .filter { it.id > target.id && !(it.role == ChatRole.ASSISTANT && it.replyToId == target.id) }
            .map { it.id }
        chatRepository.updateMessageContent(target.id, userPrompt)
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
            summaryChapterIds = live.summaryChapterIds.toSet()
        )
        return listOf(prefix.reference, context)
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    /**
     * 生成请求可携带的「前情提要」块：取 [beforeId] 之前的本会话轮次
     * （只保留生效版本、按 maxContext 截断），按章节分段（每段前加一行章节标题）渲染成
     * 「用户：… / 助手：…」的文本，
     * 由 [assembleGenerationTurns] 作为 System 参考资料块发出 —— 不作为对话轮次，
     * 避免模型把上一章的成品当成自己「正在续写的回答」而反复复用。
     * [rounds] 供「提交日志」的上下文日志回显，与实际发送内容一致。
     */
    private suspend fun generationHistory(sid: Long, beforeId: Long, modelId: Long): GenerationHistory {
        val maxContext = configRepository.getModel(modelId)?.maxContext ?: 20
        val raw = chatRepository.getMessages(sid)
            .filter { it.id < beforeId }
            .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
            // 同一轮只保留「当前生效」的那条回答（多次重新生成的废弃候选不进上下文）
            .filter { it.role == ChatRole.USER || it.active }
            .let { if (maxContext > 0) it.takeLast(maxContext) else it }
        // 前情提要只取可见的对话内容（隐藏的附加参考资料块不进前情提要，避免与上方参考资料重复）。
        // 按章节分段：章节切换时插入一行章节标题，让模型知道每段对话出自哪一章；
        // 各卷章节会重新从「第1章」编号，因此在卷切换处额外标注卷名（如「第二卷：第4章」），
        // 同卷内后续章节只写章名（如「第5章」），避免不同卷的同号章节混淆。
        val chapterInfo = raw.map { it.chapterId }.distinct()
            .filter { it != 0L }
            .associateWith { id -> bookRepository.getChapter(id) }
        val volumesByBook = chapterInfo.values.filterNotNull().map { it.bookId }.distinct()
            .associateWith { bookId ->
                bookRepository.getVolumes(bookId).associate { it.id to it.title.trim() }
            }
        val prior = buildString {
            var lastChapterId = Long.MIN_VALUE
            var lastVolumeId: Long? = null
            var volumeMarked = false
            raw.forEach { m ->
                if (m.chapterId != lastChapterId) {
                    val chapter = chapterInfo[m.chapterId]
                    if (chapter != null) {
                        if (isNotEmpty()) append('\n')
                        val volumeId = chapter.volumeId
                        val volumeTitle = volumeId?.let { volumesByBook[chapter.bookId]?.get(it) }.orEmpty()
                        if ((volumeId != lastVolumeId || !volumeMarked) && volumeTitle.isNotBlank()) {
                            append(volumeTitle).append('：')
                        }
                        append(chapter.title.trim().ifBlank { "（章节已删除）" }).append('\n')
                        lastVolumeId = volumeId
                        volumeMarked = true
                    } else if (m.chapterId != 0L) {
                        if (isNotEmpty()) append('\n')
                        append("（章节已删除）").append('\n')
                    }
                    lastChapterId = m.chapterId
                }
                append(if (m.role == ChatRole.USER) "用户：" else "助手：")
                    .append(m.content.trim()).append('\n')
            }
        }.trimEnd('\n')
        val rounds = mutableListOf<PromptLogRound>()
        raw.forEach { m ->
            if (m.role == ChatRole.USER) {
                rounds += PromptLogRound(user = m.content)
            } else if (rounds.isEmpty()) {
                rounds += PromptLogRound(assistant = m.content)
            } else {
                rounds[rounds.lastIndex] = rounds.last().copy(assistant = m.content)
            }
        }
        return GenerationHistory(prior, rounds)
    }

    /**
     * 组装生成请求的 messages：聊天同构前缀块 +（开关开启时）「前情提要」参考资料块 + 生成专用块 [tail]。
     * 前缀块与聊天请求逐字节一致，故两条路径能命中同一段供应商侧前缀缓存；
     * 前情提要只作参考资料发出（不是对话轮次），故模型不会把上一章的成品当成正在续写的回答；
     * 生成专用块只在前缀之后追加，不会破坏前缀的字节稳定性。
     */
    private fun assembleGenerationTurns(
        prefix: PreparedPrefix,
        ref: GenerationRef,
        history: GenerationHistory,
        tail: List<ChatTurn>
    ): List<ChatTurn> = buildList {
        addAll(
            buildGenerationPrefixTurns(
                customPrefix = prefix.custom,
                reference = ref.sharedReference,
                styleBlock = prefix.style
            )
        )
        if (history.prior.isNotBlank()) add(ChatTurn(ChatRole.SYSTEM, PRIOR_CONTEXT_HEADER + history.prior))
        addAll(tail)
    }

    /** 选定标签已随本轮消息落库，记入会话前缀去重：下一轮只写「参见大纲『X』」 */
    private suspend fun markGenerationOutlines(sid: Long, sp: SessionPrefix, ids: List<Long>) {
        if (ids.isEmpty()) return
        chatRepository.updateSessionPrefix(sid, sp.markOutlinesInjected(ids).encode())
    }

    /**
     * 生成路径落库回答的「提示词日志」：与聊天发送一致的三级结构（预设 / 上下文 / 本轮提示词），
     * 供「查看日志 / 查看本轮提示词」回显。[rounds] 为本次请求实际携带的历史轮次
     * （「生成工具携带本会话历史消息」关闭时为空）。
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
        fullPrompt: String
    ): PromptLog = PromptLog(
        // 实际生效的预设提示词：模型配置里的 systemPrompt（真正随请求发出）
        systemPrompt = configRepository.getModel(modelId)?.systemPrompt.orEmpty(),
        customPrefix = prefix.custom,
        outlines = prefix.outlineEntries.map { PromptLogNode(outlineLogTitle(it.text), it.text) },
        rounds = rounds,
        summary = summary,
        memory = memory,
        autoOutline = autoOutline,
        userPrompt = userPrompt,
        fullPrompt = fullPrompt,
        genKind = genKind
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

    /** 在途生成任务，供「停止响应」取消 */
    private var generateJob: Job? = null

    /** 单调递增的「当前生成轮次」令牌：旧任务收尾时据此判断是否仍属于本轮，避免误清新任务的状态 */
    private var generationToken = 0

    init {
        if (defaultBookId != null) selectedBookIds.value = setOf(defaultBookId)
        viewModelScope.launch {
            // 编辑器场景下当前打开的章节不进入章节上下文，由「＋本章梗概」单独加入
            loadBookContextNow(selectedBookIds.value.firstOrNull() ?: defaultBookId)
            // 从章节内进入：按会话归属恢复 / 建立会话，并套用该书上次的上下文选择
            restoreInitialSession()
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
            _pinnedOutlineCategories.value = emptySet()
        } else {
            _chapters.value = ids.flatMap { bookRepository.getChapters(it) }
            _outline.value = ids.flatMap { outlineRepository.getOutline(it) }
            _volumes.value = ids.flatMap { bookRepository.getVolumes(it) }
            _pinnedOutlineCategories.value = ids.flatMap { prefs.pinnedOutlineCategories(it) }.toSet()
        }
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
            val bookId = memoryBookId()
            if (bookId != null) {
                val existing = chatRepository.getBookSession(bookId)
                if (existing != null) {
                    _sessionId.value = existing.id
                    restoreContextMemory()
                } else {
                    _sessionId.value = createBookSession(bookId, activeModelId.value.takeIf { it > 0 })
                    applyNewBookSessionDefaults(bookId)
                }
                return
            }
        }
        restoreContextMemory()
    }

    /** 套用「本书上次选中的章节 / 大纲与梗概正文勾选」 */
    private suspend fun restoreContextMemory() {
        val bookId = memoryBookId() ?: return
        val mem = prefs.aiContextMemory(bookId) ?: return
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
        refreshAllFlags()
    }

    /** 当前章节上下文的「全部章节」：编辑器场景排除当前打开的章节 */
    private fun contextChapterIds(): Set<Long> =
        _chapters.value.filter { it.id != fixedChapterId }.map { it.id }.toSet()

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
            combine(selectedChapterIds, selectedOutlineIds, summaryChapterIds) { _, _, _ -> Unit },
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
                    contentAll = contentAllSelected.value
                )
            )
        }
    }

    /** 单选一本书（选书弹窗改为单选）；再次传同一本时取消选择 */
    fun selectBook(id: Long?) {
        selectedBookIds.value = if (id == null) emptySet() else setOf(id)
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

    /** 完整参数新建书籍（新建书籍表单提交用），返回新书 id */
    suspend fun createBookFull(
        title: String,
        intro: String,
        coverPath: String?,
        coverColor: Int,
        volumePrefix: String,
        chapterPrefix: String,
        groupId: Long?
    ): Long = bookRepository.createBook(title, intro, coverPath, coverColor, volumePrefix, chapterPrefix, groupId)

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

    /** 某卷下参与上下文的章节（排除编辑器当前打开的章节） */
    private fun volumeChapterIds(volumeId: Long): Set<Long> =
        _chapters.value.filter { it.volumeId == volumeId && it.id != fixedChapterId }.map { it.id }.toSet()

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
        if (fixedChapterId != null) memoryBookId() else null

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
        val bookTitle = bookRepository.getBook(bookId)?.title.orEmpty().ifBlank { "本书" }
        chatRepository.updateSessions(
            chatRepository.bookSessions(bookId).mapIndexed { index, s ->
                s.copy(title = if (index == 0) "$bookTitle（现）" else "$bookTitle（$index）")
            }
        )
        return id
    }

    /** 新建跟书会话后的默认上下文：章节与大纲分别按各自的「首轮全选」开关处理 */
    private suspend fun applyNewBookSessionDefaults(bookId: Long) {
        loadBookContextNow(bookId)
        val all = contextChapterIds()
        if (prefs.aiChapterFirstRound.first()) {
            // 「首轮全选章节」：全选章节并把梗概全部纳入（正文默认不纳入）
            summaryChapterIds.value = all
            contentChapterIds.value = emptySet()
        } else {
            summaryChapterIds.value = emptySet()
            contentChapterIds.value = emptySet()
        }
        syncSelectedChapters()
        refreshAllFlags()
        if (prefs.aiOutlineFirstRound.first()) {
            // 「首轮全选大纲」：把大纲写进首轮前缀，后续轮次靠前缀复用不再改写。
            // 开启「适配性筛选」时改为只全选「常驻化」分类下的全部内容，
            // 非常驻化分类的条目改由发送时按本章梗概动态匹配注入。
            val adaptive = prefs.prefixAdaptiveFilterEnabled.first()
            val pinned = _pinnedOutlineCategories.value
            selectedOutlineIds.value = _outline.value
                .filter { !adaptive || it.category in pinned }
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
        viewModelScope.launch {
            chatRepository.getSession(id)?.bookId?.let { loadBookContext(it) }
        }
    }

    fun deleteSession(id: Long) {
        if (_sessionId.value == id) abortInFlightWork()
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
        // 生成梗概 / 生成正文：含收集阶段的弹窗与执行阶段的在途请求
        cancelGeneration()
        generationCancelled = true
        generationJob?.cancel()
        generationJob = null
        generationOutlinePick?.complete(null)
        generationOutlinePick = null
        generationMemoryScopeAsk?.complete(null)
        generationMemoryScopeAsk = null
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
        val text = (overrideText ?: input.value).trim()
        if (text.isBlank() || sending.value) return@launchGenerating
        val modelId = activeModelId.value
        if (modelId <= 0L) {
            errorMessage.value = "请先在设置中配置并选择一个模型"
            return@launchGenerating
        }
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
            chapterId = currentRoundChapterId()
        )
        if (overrideText == null) input.value = ""
        runGeneration(
            sid, modelId, systemPrompt,
            replyToUserId = userId, historyEndId = userId, variantIndex = 0, prefix = prefix,
            logContext = PromptLogContext(
                outlines = prefix.outlineEntries.map { PromptLogNode(outlineLogTitle(it.text), it.text) },
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
        contentAll = contentAllSelected.value
    )

    /** 点击编辑某条已发送的消息：恢复它的正文与当时选好的上下文 */
    fun beginEditUserMessage(message: ChatMessage) {
        input.value = message.content
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
        syncSelectedChapters()
        viewModelScope.launch {
            loadBookContextNow(snapshot.bookIds.firstOrNull())
            val all = contextChapterIds()
            if (snapshot.chapterAll) {
                if (snapshot.summaryAll) summaryChapterIds.value = all
                if (snapshot.contentAll) contentChapterIds.value = all
            }
            syncSelectedChapters()
            refreshAllFlags()
        }
    }

    /**
     * 重新生成：为某条用户消息追加一个新的回答版本（不重复写入用户消息）。
     * 与 deepseek 一致，可在多个版本间翻阅。
     */
    fun regenerate(userMessageId: Long) = launchGenerating {
        val sid = _sessionId.value ?: return@launchGenerating
        val modelId = activeModelId.value
        if (modelId <= 0L) {
            errorMessage.value = "请先在设置中配置并选择一个模型"
            return@launchGenerating
        }
        val target = userMessageId.takeIf { it > 0 } ?: return@launchGenerating
        val next = chatRepository.nextVariantIndex(target)
        runGeneration(sid, modelId, "", replyToUserId = target, historyEndId = target, variantIndex = next)
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
        summaryChapterIds = snapshot.summaryChapterIds.toSet()
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
        val p = prefix ?: readSessionPrefix(sid)
        // 生成态的复位交由 [launchGenerating] 的 finally 统一处理（用 generationToken 判定归属）
        // 历史统一按「前情提要」块发送，与生成路径同构，两种请求共享同一段前缀缓存。
        // historyEndId 指向本轮用户消息时把它排除在外；指向助手消息（「编辑回答后继续」）时纳入
        val priorEndId = if (historyEndId == replyToUserId) historyEndId else historyEndId + 1
        val priorContext = generationHistory(sid, priorEndId, modelId).prior
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
            priorContext = priorContext,
            // 本轮所属章节：沿用该用户消息落库时的章节，重新生成 / 编辑重发不会跳到别的章节
            chapterId = chatRepository.getMessage(replyToUserId)?.chapterId ?: 0L,
            logContext = logContext
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
     */
    private suspend fun prepareSessionPrefix(sid: Long, applyAdaptive: Boolean = true): PreparedPrefix {
        val contextOn = prefs.prefixContextEnabled.first()
        val outlineOn = prefs.prefixOutlineEnabled.first()
        val session = chatRepository.getSession(sid)
        val original = session?.prefix.orEmpty()
        var sp = SessionPrefix.decode(original)
        val firstTime = !sp.initialized
        if (firstTime) {
            // 首次建立前缀时把设置里的「自定义前缀」取一次，之后冻结在本会话内
            sp = sp.copy(custom = prefs.prefixCustomText.first().trim(), initialized = true)
        }
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
        val newChapters = mutableListOf<PrefixEntry>()

        if (contextOn) {
            val bookIds = selectedBookIds.value.toList()
            val chapterIds =
                (if (chapterAllSelected.value) contextChapterIds() else selectedChapterIds.value).toList()
            if (firstTime) {
                // 建立前置预设：把首轮勾选的书籍 / 章节一次性冻结进去
                bookIds.forEach { bid ->
                    chatRepository.renderBookEntry(bid)
                        ?.let { sp = sp.copy(books = sp.books.upsert(PrefixEntry(bid, it))) }
                }
                chapterIds.forEach { cid ->
                    chatRepository.renderChapterEntry(cid, chapterSummaryOn(cid), chapterContentOn(cid))
                        ?.let { sp = sp.copy(chapters = sp.chapters.upsert(PrefixEntry(cid, it))) }
                }
            } else {
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
                chapterIds.filterNot { it in sp.checkedChapters }.forEach { cid ->
                    when {
                        sp.chapters.any { it.id == cid } ->
                            chatRepository.renderChapterEntry(cid, chapterSummaryOn(cid), chapterContentOn(cid))
                                ?.let { sp = sp.copy(chapters = sp.chapters.upsert(PrefixEntry(cid, it))) }
                        cid in sp.tailChapters -> Unit
                        else -> chatRepository.renderChapterEntry(cid, chapterSummaryOn(cid), chapterContentOn(cid))
                            ?.let { text ->
                                newChapters += PrefixEntry(cid, text)
                                sp = sp.copy(tailChapters = sp.tailChapters + cid)
                            }
                    }
                }
            }
            sp = sp.copy(checkedBooks = bookIds, checkedChapters = chapterIds)
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
        if (applyAdaptive && outlineOn && prefs.prefixAdaptiveFilterEnabled.first()) {
            val summary = fixedChapterId?.let { bookRepository.getChapter(it)?.summary.orEmpty() }.orEmpty()
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
        // 「首轮全选大纲 / 首轮全选章节」：对应内容已全部冻结进会话前缀，发完这一轮就把对应勾选清掉，
        // 第二轮起默认不勾选（再次勾选才会作为「新增参考资料」追加，不会重复提交前缀里的内容）。
        // 只在对应前缀化开关开启时清空：关闭前缀化时勾选每轮都要实时注入，不能清。
        if (firstTime) {
            if (contextOn && prefs.aiChapterFirstRound.first()) {
                summaryChapterIds.value = emptySet()
                contentChapterIds.value = emptySet()
                syncSelectedChapters()
                refreshAllFlags()
            }
            if (outlineOn && prefs.aiOutlineFirstRound.first()) {
                selectedOutlineIds.value = emptySet()
                refreshAllFlags()
            }
        }
        val appended = sp.renderEntries(newBooks, newOutlines, newChapters)
        return PreparedPrefix(
            custom = sp.custom,
            style = sp.style,
            reference = sp.renderReference(includeChapters = contextOn, includeOutlines = outlineOn),
            contextOn = contextOn,
            outlineOn = outlineOn,
            outlineEntries = if (outlineOn) sp.outlines else emptyList(),
            newReference = if (appended.isBlank()) "" else "以下是新增参考资料，请在写作时参考：\n$appended",
            autoOutlineMaterial = autoOutlineMaterial,
            sessionPrefix = sp
        )
    }

    /** 大纲条目的「显示标签」值：内容首项的值，为空时回落条目标题 */
    private fun outlineHeadOf(item: OutlineItem): String {
        val fields = decodeOutlineFields(item.content)
        return fields.firstOrNull()?.second?.ifBlank { item.title } ?: item.title
    }

    /**
     * 「适配性筛选」命中判定（聊天发送与生成正文共用同一套规则）：
     * 非常驻化分类下、「显示标签」被 [summary] 命中的大纲条目 id；梗概为空时返回空。
     */
    private fun adaptiveMatchedOutlineIds(summary: String): List<Long> {
        if (summary.isBlank()) return emptyList()
        val pinned = _pinnedOutlineCategories.value
        return _outline.value
            .filter { it.category !in pinned }
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

    /** 只读取已冻结的会话前缀（重新生成 / 编辑重发 / 生成梗概正文等不新增用户消息的场景），不做任何写入 */
    private suspend fun readSessionPrefix(sid: Long): PreparedPrefix {
        val contextOn = prefs.prefixContextEnabled.first()
        val outlineOn = prefs.prefixOutlineEnabled.first()
        val sp = SessionPrefix.decode(chatRepository.getSession(sid)?.prefix.orEmpty())
        return PreparedPrefix(
            custom = sp.custom,
            style = sp.style,
            reference = sp.renderReference(includeChapters = contextOn, includeOutlines = outlineOn),
            contextOn = contextOn,
            outlineOn = outlineOn,
            outlineEntries = if (outlineOn) sp.outlines else emptyList(),
            sessionPrefix = sp
        )
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
        /** 本轮新勾选内容渲染出的文本（挂到当轮用户消息尾端），无新增时为空 */
        val newReference: String = "",
        /** 「适配性筛选」本轮新命中的大纲资料（挂在检索片段之后、用户提示词之前），无新增时为空 */
        val autoOutlineMaterial: String = "",
        /** 会话前缀快照：供长期记忆去重与写回使用（重新生成路径无新消息，用不到） */
        val sessionPrefix: SessionPrefix = SessionPrefix()
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
     * 生成请求携带的历史对话：以「前情提要」参考资料文本发出（不再作为对话轮次），
     * [rounds] 仍供「提交日志」的上下文日志回显。
     */
    private data class GenerationHistory(
        val prior: String = "",
        val rounds: List<PromptLogRound> = emptyList()
    )

    /** 生成路径落库的「用户消息」标记：把生成轮次与普通对话区分开 */
    private val genLabelSummary = "生成梗概"
    private val genLabelContent = "生成正文"

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
            // 开启「记忆检索是否限定范围」时只检索用户勾选的章节
            val scopeIds = if (prefs.memoryScopeLimitEnabled.first()) {
                memoryScopeSelection.value?.takeIf { it.bookId == bookId }?.chapterIds
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

/** 记忆检索进行状态：界面据此显示「正在检索中」/「检索完成」 */
enum class MemorySearchState {
    /** 本轮没有检索（未勾选 / 尚未发送 / 已取消勾选） */
    IDLE,

    /** 正在向量化 query 并召回 / 精排候选 */
    SEARCHING,

    /** 本轮检索已结束（无论是否有命中） */
    DONE
}