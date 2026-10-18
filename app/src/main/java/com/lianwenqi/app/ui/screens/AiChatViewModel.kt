package com.lianwenqi.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.FlowState
import com.lianwenqi.app.ai.GenerationDialog
import com.lianwenqi.app.ai.GenerationDialogKind
import com.lianwenqi.app.ai.GenerationMode
import com.lianwenqi.app.ai.GenerationPlan
import com.lianwenqi.app.ai.RetrievalMode
import com.lianwenqi.app.ai.SummaryFailureChoice
import com.lianwenqi.app.ai.buildContentTurns
import com.lianwenqi.app.ai.buildRetrievalQuery
import com.lianwenqi.app.ai.buildSummaryReferenceBlock
import com.lianwenqi.app.ai.buildSummaryTurns
import com.lianwenqi.app.ai.contentDialogOrder
import com.lianwenqi.app.ai.enforceWordRange
import com.lianwenqi.app.data.AiChapterImporter
import com.lianwenqi.app.data.AiOutlineImporter
import com.lianwenqi.app.data.ChatEvent
import com.lianwenqi.app.data.MemoryGateway
import com.lianwenqi.app.data.PromptLogContext
import com.lianwenqi.app.data.PromptLogNode
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
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.util.AI_TOOL_IMPORT_CHAPTER
import com.lianwenqi.app.util.AI_TOOL_WRITE_OUTLINE
import com.lianwenqi.app.util.AiChapterBlock
import com.lianwenqi.app.util.AiOutlineBlock
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
     * 勾选状态：开启「默认勾选记忆检索」时，仅当当前有书**且该书已向量化**才勾选
     * （未选择书籍 / 尚未向量化的书籍一律不勾选）；否则取用户手动勾选的持久值。
     *
     * 默认勾选开启期间用户仍可取消（[memoryRoundCancelled]）：取消只作用于当前这一轮，
     * 下一轮 / 新一轮对话开始时自动恢复勾选。
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

    /** 默认勾选开启期间，「本轮被用户手动取消」的标记（一轮结束或换会话时复位） */
    private val memoryRoundCancelled = MutableStateFlow(false)

    /**
     * 向量化工具是否已配置可用（API 模式需填齐 baseUrl / apiKey / 向量模型名；本地模式需已勾选）。
     * 未配置时「记忆检索」不可勾选，「默认勾选记忆检索」也不生效。
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
        prefs.memoryRetrievalEnabled, prefs.memoryDefaultEnabled,
        memoryRoundCancelled, memoryToolConfigured, memoryAvailability
    ) { manual, defaultOn, cancelledThisRound, toolReady, avail ->
        when {
            // 未配置向量化工具，或本章没有梗概（无从匹配）时，一律不生效
            !toolReady || !avail.summaryReady -> false
            defaultOn -> avail.bookId != null && avail.vectorized && !cancelledThisRound
            else -> manual
        }
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

    /**
     * 切换记忆检索：
     * - 取消勾选：「默认勾选记忆检索」开启时也允许取消，但只作用于当前这一轮
     *   （下一轮 / 新一轮对话自动恢复勾选）；未开启该设置时直接关闭持久勾选；
     * - 勾选：必须先有书籍（未选书时提示，不勾选）；本书从未向量化时弹窗询问是否向量化全书，
     *   选「可以」则开始向量化全书并勾选，选「不可以」则不勾选。
     */
    fun toggleMemoryRetrieval() = viewModelScope.launch {
        if (memoryRetrievalEnabled.value) {
            // 取消勾选后不再检索，旧的「检索完成」标记同步清掉，避免误导
            memorySearchState.value = MemorySearchState.IDLE
            if (prefs.memoryDefaultEnabled.first()) {
                memoryRoundCancelled.value = true
                memoryToast.value = "已取消本轮记忆检索，下一轮将自动恢复勾选"
                return@launch
            }
            prefs.setMemoryRetrievalEnabled(false)
            return@launch
        }
        memoryRoundCancelled.value = false
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
        if (container.memoryRepository.latestVectorizedUpdatedAt(bookId) == null) {
            // 本书还没有任何向量化结果：先问用户是否开启向量化
            pendingEmptyVectorizeAsk.value = true
        } else {
            prefs.setMemoryRetrievalEnabled(true)
        }
    }

    /** 「当前书本没有向量化」弹窗应答：可以 → 勾选并向量化全书；不可以 → 保持不勾选 */
    fun resolveEmptyVectorizeAsk(confirm: Boolean) = viewModelScope.launch {
        pendingEmptyVectorizeAsk.value = false
        if (!confirm) return@launch
        val bookId = currentMemoryBookId() ?: return@launch
        prefs.setMemoryRetrievalEnabled(true)
        container.memoryRepository.vectorize(bookId) { memoryVectorizing.value = it }
        memoryVectorizing.value = null
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

    private var generationJob: Job? = null
    private var generationCancelled = false
    private var generationOutlinePick: CompletableDeferred<List<Long>?>? = null
    private var generationMemoryAsk: CompletableDeferred<Boolean?>? = null
    private var generationSummaryFailed: CompletableDeferred<SummaryFailureChoice>? = null

    /** 梗概子调用结果 */
    private sealed interface SummaryCall {
        data class Ok(val text: String) : SummaryCall
        data class Failed(val message: String) : SummaryCall
    }

    /** 侧边抽屉「生成梗概」：限定范围开启时先弹标签选择，否则直接执行；生成的梗概交给 [onSummary] 写回编辑器 */
    fun startGenerateSummary(onSummary: (String) -> Unit) = launchGeneration { sid ->
        if (fixedChapterId == null) {
            noticeThenCancel("请在章节编辑页使用「生成梗概」")
            return@launchGeneration
        }
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
        when (val r = runSummaryCall(sid, plan)) {
            is SummaryCall.Ok -> {
                onSummary(r.text)
                _generationState.value = FlowState.Done
            }
            is SummaryCall.Failed -> {
                generationMessage.value = r.message
                _generationState.value = FlowState.Failed
            }
        }
    }

    /**
     * 侧边抽屉「生成正文」：按固定顺序一次性弹完所有决策弹窗（梗概标签多选 → 记忆检索询问），
     * 之后进入 Executing 只读执行；任一弹窗取消即整体终止。
     */
    fun startGenerateContent(onSummary: (String) -> Unit) = launchGeneration { sid ->
        val chapterId = fixedChapterId
        if (chapterId == null) {
            noticeThenCancel("请在章节编辑页使用「生成正文」")
            return@launchGeneration
        }
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
        val memoryCfg = prefs.genContentMemoryMode.first()
        var picked = emptyList<Long>()
        var memory = if (memoryCfg == RetrievalMode.ON) RetrievalMode.ON else RetrievalMode.OFF
        // ① 选梗概参考标签 ② 询问记忆检索：顺序固定，前一个确定后才弹下一个；任一取消即整体终止
        for (kind in contentDialogOrder(mode, limitScope, memoryCfg == RetrievalMode.ASK)) {
            when (kind) {
                GenerationDialogKind.PICK_SUMMARY_OUTLINES ->
                    picked = askSummaryOutlines() ?: return@launchGeneration
                GenerationDialogKind.ASK_MEMORY ->
                    memory = if (askMemoryRetrieval(retrievalQueryDigest(chapterId, picked)) == true) {
                        RetrievalMode.ON
                    } else {
                        RetrievalMode.OFF
                    }
            }
        }
        if (generationCancelled) return@launchGeneration
        val plan = GenerationPlan(
            mode = mode,
            summaryWordRange = prefs.genSummaryWordRange.first(),
            contentWordRange = prefs.genContentWordRange.first(),
            selectedOutlineIds = picked,
            memoryRetrieval = memory
        )
        _generationPlan.value = plan
        startExecuting()

        val memoryCap = prefs.memoryBlockQuota.first()
        val memoryScope = prefs.memorySearchScope.first()
        val summary = if (mode == GenerationMode.CONTENT_WITH_SUMMARY) {
            awaitSummaryWithRetry(sid, plan, onSummary)
                ?: run {
                    if (!generationCancelled) _generationState.value = FlowState.Failed
                    return@launchGeneration
                }
        } else {
            bookRepository.getChapter(chapterId)?.summary.orEmpty()
        }

        val memoryBlock = if (plan.memoryRetrieval == RetrievalMode.ON) {
            buildMemoryBlock(sid, plan, chapterId, summary, memoryCap, memoryScope)
        } else {
            ""
        }

        val modelId = activeModelId.value
        if (modelId <= 0L) {
            generationMessage.value = "请先在设置中配置并选择一个模型"
            _generationState.value = FlowState.Failed
            return@launchGeneration
        }
        val prefix = readSessionPrefix(sid)
        // 「适配性筛选」开启时，把「本章梗概」命中的非常驻化大纲条目一并并入正文参考资料。
        // 生成正文是一次性无历史请求、不落库，故只注入、不记回 autoOutlineIds，
        // 否则会让后续聊天轮次误以为这些条目已在历史里而跳过提交。
        val outlineSlot = if (prefs.prefixAdaptiveFilterEnabled.first()) {
            val matched = adaptiveMatchedOutlineIds(summary).mapNotNull { renderOutlineEntry(it) }
            listOf(prefix.reference, renderAdaptiveOutlineBlock(prefix.sessionPrefix, matched))
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } else {
            prefix.reference
        }
        val turns = buildContentTurns(
            summary = summary,
            outlineSlot = outlineSlot,
            memoryBlock = memoryBlock,
            userInput = input.value,
            range = plan.contentWordRange
        )
        val result = configRepository.sendToModel(
            modelId, AiRequest(turns = turns, model = "", temperature = 0f, maxTokens = 0)
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
        if (enforced.over) {
            generationMessage.value = "正文超出设定上限，已截断到最后一个完整句子"
        } else if (enforced.under) {
            generationMessage.value = "正文略短于设定下限，可继续让 AI 补写"
        }
        // 生成结果作为一条普通 AI 回答进入本会话：不自动写入正文，由用户在编辑器点「写入本章正文」决定
        chatRepository.addMessage(
            sid, ChatRole.ASSISTANT, enforced.text,
            promptTokens = result.promptTokens,
            completionTokens = result.completionTokens,
            cachedTokens = result.cachedTokens,
            chapterId = chapterId
        )
        _generationState.value = FlowState.Done
    }

    /** 标签多选弹窗「确定」 */
    fun confirmSummaryOutlines(ids: List<Long>) {
        generationOutlinePick?.complete(ids)
        generationOutlinePick = null
    }

    /** 记忆检索询问弹窗：开启 / 关闭；取消（含 onDismiss）走 [cancelGeneration] */
    fun chooseMemoryRetrieval(enable: Boolean) {
        generationMemoryAsk?.complete(enable)
        generationMemoryAsk = null
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
        generationMemoryAsk?.complete(null)
        generationMemoryAsk = null
        generationSummaryFailed?.complete(SummaryFailureChoice.CANCEL)
        generationSummaryFailed = null
        _generationDialog.value = null
        _generationState.value = FlowState.Cancelled
        generationJob?.cancel()
        generationJob = null
    }

    private fun launchGeneration(block: suspend (Long) -> Unit) {
        if (_generationState.value == FlowState.Executing) return
        generationJob?.cancel()
        generationCancelled = false
        generationMessage.value = null
        _generationState.value = FlowState.CollectingConfig
        generationJob = viewModelScope.launch {
            val sid = _sessionId.value
            if (sid == null) {
                _generationDialog.value = null
                _generationState.value = FlowState.Failed
                generationMessage.value = "请先在本章侧边对话中建立会话后再生成"
                return@launch
            }
            try {
                block(sid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _generationState.value = FlowState.Failed
                generationMessage.value = e.message ?: "生成失败"
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
            generationOutlinePick = null
            if (_generationDialog.value === GenerationDialog.PickSummaryOutlines) _generationDialog.value = null
        }
    }

    private suspend fun askMemoryRetrieval(digest: String): Boolean? {
        val deferred = CompletableDeferred<Boolean?>()
        generationMemoryAsk = deferred
        _generationState.value = FlowState.CollectingConfig
        _generationDialog.value = GenerationDialog.AskMemoryRetrieval(digest)
        return try {
            deferred.await()
        } finally {
            generationMemoryAsk = null
            if (_generationDialog.value is GenerationDialog.AskMemoryRetrieval) _generationDialog.value = null
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
            generationSummaryFailed = null
            if (_generationDialog.value is GenerationDialog.SummaryFailed) _generationDialog.value = null
        }
    }

    /**
     * 执行一次梗概生成（独立 messages，不进入正文请求的历史）。
     * 参考资料 = 本会话常规前缀 + 用户选定标签的内容（已提交过的只写「参见大纲『X』」）。
     */
    private suspend fun runSummaryCall(sid: Long, plan: GenerationPlan): SummaryCall {
        val modelId = activeModelId.value
        if (modelId <= 0L) return SummaryCall.Failed("请先在设置中配置并选择一个模型")
        val prefix = readSessionPrefix(sid)
        val pickedBlock = if (plan.selectedOutlineIds.isEmpty()) {
            ""
        } else {
            // 先把完整渲染文本取出来（读取库是挂起操作），再交给纯函数决定「参见」还是「完整内容」
            val fullText = plan.selectedOutlineIds
                .mapNotNull { id -> renderOutlineEntry(id)?.let { id to it.text } }
                .toMap()
            buildSummaryReferenceBlock(
                outlineIds = plan.selectedOutlineIds,
                injected = { id -> prefix.sessionPrefix.outlineInjected(id, prefix.outlineOn) },
                label = { id -> _outline.value.firstOrNull { it.id == id }?.let { outlineHeadOf(it) } ?: id.toString() },
                renderFull = { id -> fullText[id] }
            )
        }
        // 注意：这里【不要】把选中的大纲记入 autoOutlineIds。
        // autoOutlineIds 的语义是「内容已随某轮消息历史持久化，后续只需写『参见大纲』」，
        // 而生成流程的参考资料块是一次性请求、不落库也不进历史；一旦标记，
        // 下一轮就会退化成「参见大纲『X』」，模型反而拿不到内容。
        // 因而只读 outlineInjected（判断是否真在会话前缀/历史里），不做写入，
        // 顺带让同一章的多次重生成保持参考资料块逐字节一致，利于命中前缀缓存。
        val reference = buildString {
            if (prefix.reference.isNotBlank()) append("【本会话参考资料】\n").append(prefix.reference).append('\n')
            if (pickedBlock.isNotBlank()) append(pickedBlock).append('\n')
        }.trim()
        val turns = buildSummaryTurns(input.value, plan.summaryWordRange, reference)
        val result = configRepository.sendToModel(
            modelId, AiRequest(turns = turns, model = "", temperature = 0f, maxTokens = 0)
        )
        if (!result.success) return SummaryCall.Failed(result.error?.userMessage ?: "生成梗概失败")
        val raw = result.content.trim()
        if (raw.isBlank()) return SummaryCall.Failed("梗概生成失败：模型返回空内容")
        val enforced = enforceWordRange(raw, plan.summaryWordRange)
        if (enforced.over) {
            generationMessage.value = "梗概超出设定上限，已截断到最后一个完整句子"
        } else if (enforced.under) {
            generationMessage.value = "梗概略短于设定下限，可直接使用"
        }
        return SummaryCall.Ok(enforced.text)
    }

    /**
     * 同时生成梗概模式下的梗概子调用：失败时显式三选项（重试 / 使用已有 / 取消）。
     * 静默失败会造成「梗概为空但正文已生成」的数据不一致，因此必须暴露给用户。
     * 返回 null 表示流程终止（取消）。
     */
    private suspend fun awaitSummaryWithRetry(
        sid: Long,
        plan: GenerationPlan,
        onSummary: (String) -> Unit
    ): String? {
        val chapterId = fixedChapterId ?: return null
        while (true) {
            when (val r = runSummaryCall(sid, plan)) {
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
     * 召回后复用 [SessionPrefix.freshMemoryHits] 去重（已提交过的片段不再重复注入），
     * 新的去重键写回会话前缀。
     */
    private suspend fun buildMemoryBlock(
        sid: Long,
        plan: GenerationPlan,
        chapterId: Long,
        summary: String,
        topK: Int,
        scope: String
    ): String {
        if (summary.isBlank()) return ""
        val bookId = currentMemoryBookId() ?: return ""
        val heads = plan.selectedOutlineIds.mapNotNull { id ->
            _outline.value.firstOrNull { it.id == id }?.let { outlineHeadOf(it) }
        }
        val query = buildRetrievalQuery(summary, heads)
        if (query.isBlank()) return ""
        memorySearchState.value = MemorySearchState.SEARCHING
        val hits = try {
            container.memoryRepository.retrieveScored(
                bookId = bookId,
                query = query,
                topK = topK.coerceAtLeast(1),
                excludeChapterId = chapterId,
                scope = scope
            )
        } catch (e: Throwable) {
            emptyList()
        }
        memorySearchState.value = MemorySearchState.DONE
        if (hits.isEmpty()) return ""
        val sp = readSessionPrefix(sid).sessionPrefix
        val fresh = sp.freshMemoryHits(hits)
        if (fresh.isEmpty()) return ""
        chatRepository.updateSessionPrefix(
            sid, sp.copy(autoMemoryKeys = sp.autoMemoryKeys + fresh.map { shortHash(it) }).encode()
        )
        return buildString {
            append("【参考资料·本书相关正文片段】\n")
            fresh.forEachIndexed { i, t -> append("片段${i + 1}：").append(t).append('\n') }
            append("（以上片段来自本书已写正文，仅供写作参考，请勿照抄或复述。）")
        }
    }

    /** 记忆检索询问弹窗里展示的检索词摘要：以本章梗概（+ 选定标签）构造，超长截断 */
    private suspend fun retrievalQueryDigest(chapterId: Long, outlineIds: List<Long>): String {
        val summary = bookRepository.getChapter(chapterId)?.summary.orEmpty().trim()
        if (summary.isBlank()) return "（本章尚无梗概，将改用你在输入框中填写的内容作为检索词）"
        val heads = outlineIds.mapNotNull { id ->
            _outline.value.firstOrNull { it.id == id }?.let { outlineHeadOf(it) }
        }
        val q = buildRetrievalQuery(summary, heads).replace('\n', ' ')
        return if (q.length > 120) q.take(120) + "…" else q
    }

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

    /** 把待导入的大纲区块写入指定书籍，然后继续队列中的下一项 */
    fun confirmOutlineImport(bookId: Long) = viewModelScope.launch {
        val head = pendingImports.value.firstOrNull() as? PendingImport.Outline ?: return@launch
        pendingImports.value = pendingImports.value.drop(1)
        val result = outlineImporter.import(bookId, head.blocks)
        importToast.value = buildString {
            append("已写入 ${result.items} 条大纲")
            if (result.newCategories.isNotEmpty()) {
                append("，新建标签：${result.newCategories.joinToString("、")}")
            }
        }
        // 刷新上下文里的大纲列表，让「大纲(n)」立即反映新条目
        loadBookContextNow(selectedBookIds.value.firstOrNull() ?: bookId)
    }

    /** 把待导入的章节梗概写入指定书籍，然后继续队列中的下一项 */
    fun confirmChapterImport(bookId: Long) = viewModelScope.launch {
        val head = pendingImports.value.firstOrNull() as? PendingImport.Chapters ?: return@launch
        pendingImports.value = pendingImports.value.drop(1)
        val result = chapterImporter.import(bookId, head.blocks)
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
        if (mem.chapterAll) {
            // 上次是「全选」：按当前章节重新展开，切章节后依然保持全选
            selectedChapterIds.value = all
        } else {
            selectedChapterIds.value = mem.chapterIds.filter { it in chapterIds }.toSet()
        }
        summaryChapterIds.value =
            if (mem.summaryAll) all else mem.summaryChapterIds.filter { it in chapterIds }.toSet()
        contentChapterIds.value =
            if (mem.contentAll) all else mem.contentChapterIds.filter { it in chapterIds }.toSet()
        selectedOutlineIds.value = mem.outlineIds.filter { it in outlineIds }.toSet()
        refreshAllFlags()
    }

    /** 当前章节上下文的「全部章节」：编辑器场景排除当前打开的章节 */
    private fun contextChapterIds(): Set<Long> =
        _chapters.value.filter { it.id != fixedChapterId }.map { it.id }.toSet()

    /** 依据当前选中集合重新判定三列是否仍处于「全选」 */
    private fun refreshAllFlags() {
        val all = contextChapterIds()
        chapterAllSelected.value = all.isNotEmpty() && selectedChapterIds.value == all
        val sel = selectedChapterIds.value
        summaryAllSelected.value = sel.isNotEmpty() && summaryChapterIds.value.containsAll(sel)
        contentAllSelected.value = sel.isNotEmpty() && contentChapterIds.value.containsAll(sel)
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
        val nowChecked = id !in selectedChapterIds.value
        selectedChapterIds.value =
            if (nowChecked) selectedChapterIds.value + id else selectedChapterIds.value - id
        if (nowChecked) {
            // 勾选章节时默认带上「梗概」（与新建跟书会话的默认一致）
            summaryChapterIds.value = summaryChapterIds.value + id
        } else {
            summaryChapterIds.value = summaryChapterIds.value - id
            contentChapterIds.value = contentChapterIds.value - id
        }
        refreshAllFlags()
    }

    /** 单独勾选 / 取消某章的梗概 */
    fun toggleChapterSummary(id: Long) {
        summaryChapterIds.value = summaryChapterIds.value.toggle(id)
        refreshAllFlags()
    }

    /** 单独勾选 / 取消某章的正文 */
    fun toggleChapterContent(id: Long) {
        contentChapterIds.value = contentChapterIds.value.toggle(id)
        refreshAllFlags()
    }

    fun toggleOutline(id: Long) {
        selectedOutlineIds.value = selectedOutlineIds.value.toggle(id)
    }

    /** 全选 / 取消全选章节 */
    fun selectAllChapters() {
        val all = contextChapterIds()
        val on = !(selectedChapterIds.value.containsAll(all) && all.isNotEmpty())
        selectedChapterIds.value = if (on) all else emptySet()
        // 勾选章节时默认带上「梗概」；取消全选时一并清掉梗概与正文
        summaryChapterIds.value = if (on) all else emptySet()
        if (!on) contentChapterIds.value = emptySet()
        refreshAllFlags()
    }

    /** 一键全选 / 取消全选「梗概」（对所有已纳入上下文的章节生效） */
    fun selectAllSummary() {
        val sel = selectedChapterIds.value
        val on = !(sel.isNotEmpty() && summaryChapterIds.value.containsAll(sel))
        summaryChapterIds.value = if (on) summaryChapterIds.value + sel else summaryChapterIds.value - sel
        refreshAllFlags()
    }

    /** 一键全选 / 取消全选「正文」（对所有已纳入上下文的章节生效） */
    fun selectAllContent() {
        val sel = selectedChapterIds.value
        val on = !(sel.isNotEmpty() && contentChapterIds.value.containsAll(sel))
        contentChapterIds.value = if (on) contentChapterIds.value + sel else contentChapterIds.value - sel
        refreshAllFlags()
    }

    /** 整卷勾选 / 取消：章节列 */
    fun toggleVolume(volumeId: Long) {
        val ids = volumeChapterIds(volumeId)
        if (ids.isEmpty()) return
        val on = !ids.all { it in selectedChapterIds.value }
        selectedChapterIds.value = if (on) selectedChapterIds.value + ids else selectedChapterIds.value - ids
        if (on) summaryChapterIds.value = summaryChapterIds.value + ids
        else {
            summaryChapterIds.value = summaryChapterIds.value - ids
            contentChapterIds.value = contentChapterIds.value - ids
        }
        refreshAllFlags()
    }

    /** 整卷勾选 / 取消：梗概列 */
    fun toggleVolumeSummary(volumeId: Long) {
        val ids = volumeChapterIds(volumeId)
        if (ids.isEmpty()) return
        summaryChapterIds.value =
            if (ids.all { it in summaryChapterIds.value }) summaryChapterIds.value - ids
            else summaryChapterIds.value + ids
        refreshAllFlags()
    }

    /** 整卷勾选 / 取消：正文列 */
    fun toggleVolumeContent(volumeId: Long) {
        val ids = volumeChapterIds(volumeId)
        if (ids.isEmpty()) return
        contentChapterIds.value =
            if (ids.all { it in contentChapterIds.value }) contentChapterIds.value - ids
            else contentChapterIds.value + ids
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
     * 「＋」新开对话：统一「跟书走」→ 新建绑定本书的对话
     * （标题按「书名（现）/（1）…」重排），默认全选章节 + 全选梗概。
     */
    fun newSession() {
        viewModelScope.launch {
            val bookId = selectedBookIds.value.firstOrNull() ?: defaultBookId
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

    /** 按当前归属（跟书走）为新会话建立绑定，供发送时兜底建会话使用 */
    private suspend fun createSessionForCurrentMode(modelId: Long): Long {
        val bookId = selectedBookIds.value.firstOrNull() ?: defaultBookId
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

    /** 新建跟书会话后的默认上下文：全选章节 + 全选梗概、不含正文；大纲按「首轮全选」开关处理 */
    private suspend fun applyNewBookSessionDefaults(bookId: Long) {
        loadBookContextNow(bookId)
        val all = contextChapterIds()
        selectedChapterIds.value = all
        summaryChapterIds.value = all
        contentChapterIds.value = emptySet()
        chapterAllSelected.value = all.isNotEmpty()
        summaryAllSelected.value = all.isNotEmpty()
        contentAllSelected.value = false
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
        _sessionId.value = id
        // 进入（新一轮）对话：本轮取消标记复位，默认勾选重新生效
        memoryRoundCancelled.value = false
        viewModelScope.launch {
            chatRepository.getSession(id)?.bookId?.let { loadBookContext(it) }
        }
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        chatRepository.deleteSession(id)
        if (_sessionId.value == id) _sessionId.value = null
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
                selectedChapterIds.value = emptySet()
                summaryChapterIds.value = emptySet()
                contentChapterIds.value = emptySet()
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
        // 一轮结束：手动的「本轮取消」失效，下一轮按「默认勾选记忆检索」重新勾上
        memoryRoundCancelled.value = false
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
     * A3 开启 → 仅在正文确实比已向量化进度更新时才后台静默续跑（无变化则完全不启动，
     *   避免每轮发送都空跑一遍全书向量化、连带刷出一串「正在向量化本书」提示）；
     * A3 关闭 → 若正文比已向量化进度更新，返回 true 交由 UI 弹窗询问。
     */
    private suspend fun shouldAskMemoryVectorize(): Boolean {
        if (!memoryRetrievalEnabled.value) return false
        val bookId = currentMemoryBookId() ?: return false
        if (!hasNewerContentThanVectorized(bookId)) return false
        if (prefs.memoryAutoVectorize.first()) {
            // 后台静默续跑：不写 memoryVectorizing，界面上不出现任何「正在向量化」提示
            // （约定：向量化 / 检索 / 组装参考资料对用户全部不可见，用户只看得到写文模型的回答）
            container.appScope.launch { container.memoryRepository.vectorize(bookId) { } }
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
        selectedChapterIds.value = snapshot.chapterIds.toSet()
        summaryChapterIds.value = snapshot.summaryChapterIds.toSet()
        contentChapterIds.value = snapshot.contentChapterIds.toSet()
        viewModelScope.launch {
            loadBookContextNow(snapshot.bookIds.firstOrNull())
            val all = contextChapterIds()
            if (snapshot.chapterAll) {
                selectedChapterIds.value = all
                if (snapshot.summaryAll) summaryChapterIds.value = all
                if (snapshot.contentAll) contentChapterIds.value = all
            }
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
        chatRepository.updateMessageContent(id, content)
    }

    /** 保存编辑后的回答，并让 AI 基于修改后的内容继续 */
    fun saveAndContinue(assistantMessageId: Long, content: String, userMessageId: Long) =
        launchGenerating {
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
            prefixText = p.custom,
            referencePrefix = p.reference,
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

                ChatEvent.Done -> ok = true
            }
        }
        return ok
    }

    /**
     * 会话级前缀：区分「一开始的上下文预设」与「后续新勾选内容」的注入位置。
     * - 首轮（[SessionPrefix.initialized] 为 false）：把当时勾选的书籍 / 大纲 / 章节冻结成前置参考资料
     *   预设块，本轮不产生尾部追加文本。
     * - 之后每轮：只有「重新勾选」（上次没勾、本次勾）的条目才处理 —— 已在预设内的条目对比内容后
     *   原位替换；从未冻结过的新条目渲染成文本作为「尾部追加」返回，由调用方挂到当轮用户消息尾端
     *   （成为历史对话的一部分），从而不破坏「预设 + 历史对话」的前缀字节结构。
     * 只增不减：取消勾选不移除已冻结内容，也不允许重复追加。
     */
    private suspend fun prepareSessionPrefix(sid: Long): PreparedPrefix {
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
        if (outlineOn && prefs.prefixAdaptiveFilterEnabled.first()) {
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
        val appended = sp.renderEntries(newBooks, newOutlines, newChapters)
        return PreparedPrefix(
            custom = sp.custom,
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
            // 侧边助手写的是当前这一章，把本章自己的块排除掉，检索才会真的去翻本书其它章节
            container.memoryRepository.retrieve(
                bookId, query, quota, excludeChapterId = fixedChapterId, scope = scope
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