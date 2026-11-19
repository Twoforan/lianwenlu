package com.lianwenqi.app.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.agent.context.AgentReferenceResolver
import com.lianwenqi.app.agent.context.AgentReferenceTarget
import com.lianwenqi.app.agent.context.ResolvedAgentReference
import com.lianwenqi.app.agent.data.dao.AgentMaterialMessageRow
import com.lianwenqi.app.agent.data.dao.AgentReferenceChapterRow
import com.lianwenqi.app.agent.data.dao.AgentToolMaterialRow
import com.lianwenqi.app.agent.data.entity.AgentMaterialCandidate
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentSessionReference
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskReference
import com.lianwenqi.app.data.SortMode
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Volume
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 历史 Agent 任务下的可选资料对话框 */
data class AgentMaterialDialogState(
    val task: AgentTask,
    val messages: List<AgentMaterialMessageRow> = emptyList(),
    val tools: List<AgentToolMaterialRow> = emptyList()
)

/**
 * 参考资料选择与管理 ViewModel（F5）。
 *
 * 只调用本地 Repository / Resolver；不发起模型请求、不写入小说数据。
 * 所有引用上限由 Repository 最终校验，这里只负责提前提示。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentReferenceViewModel(
    container: AppContainer,
    private val sessionId: Long
) : ViewModel() {

    private val sessionRepository = container.agentSessionRepository
    private val referenceRepository = container.agentReferenceRepository
    private val taskReferenceRepository = container.agentTaskReferenceRepository
    private val sourceRepository = container.agentReferenceSourceRepository
    private val bookRepository = container.bookRepository
    private val resolver: AgentReferenceResolver = container.agentReferenceResolver

    val books: StateFlow<List<Book>> = bookRepository.observeBooks(SortMode.TIME, false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _references = MutableStateFlow<List<AgentSessionReference>>(emptyList())
    val references: StateFlow<List<AgentSessionReference>> = _references.asStateFlow()

    private val _statusMap = MutableStateFlow<Map<Long, ResolvedAgentReference>>(emptyMap())
    val statusMap: StateFlow<Map<Long, ResolvedAgentReference>> = _statusMap.asStateFlow()

    private val _currentTaskId = MutableStateFlow<Long?>(null)
    val taskReferences: StateFlow<List<AgentTaskReference>> = _currentTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else taskReferenceRepository.observeReferencesOfTask(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------------- 选择页状态 ----------------

    private val _tab = MutableStateFlow(REFERENCE_TAB_SELECTED)
    val tab: StateFlow<Int> = _tab.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _filterBookId = MutableStateFlow<Long?>(null)
    val filterBookId: StateFlow<Long?> = _filterBookId.asStateFlow()

    private val _filterVolumeId = MutableStateFlow<Long?>(null)
    val filterVolumeId: StateFlow<Long?> = _filterVolumeId.asStateFlow()

    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<Volume>> = _volumes.asStateFlow()

    private val _chapters = MutableStateFlow<List<AgentReferenceChapterRow>>(emptyList())
    val chapters: StateFlow<List<AgentReferenceChapterRow>> = _chapters.asStateFlow()

    private val _selectedChapterIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedChapterIds: StateFlow<Set<Long>> = _selectedChapterIds.asStateFlow()

    private val _loadingChapters = MutableStateFlow(false)
    val loadingChapters: StateFlow<Boolean> = _loadingChapters.asStateFlow()

    private val _agentSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    val agentSessions: StateFlow<List<AgentSession>> = _agentSessions.asStateFlow()

    private val _expandedSessionId = MutableStateFlow<Long?>(null)
    val expandedSessionId: StateFlow<Long?> = _expandedSessionId.asStateFlow()

    private val _agentTasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val agentTasks: StateFlow<List<AgentTask>> = _agentTasks.asStateFlow()

    private val _agentSearch = MutableStateFlow("")
    val agentSearch: StateFlow<String> = _agentSearch.asStateFlow()

    private val _agentFilterBookId = MutableStateFlow<Long?>(null)
    val agentFilterBookId: StateFlow<Long?> = _agentFilterBookId.asStateFlow()

    private val _selectedMaterials =
        MutableStateFlow<Map<String, AgentMaterialCandidate>>(emptyMap())
    val selectedMaterials: StateFlow<Map<String, AgentMaterialCandidate>> =
        _selectedMaterials.asStateFlow()

    private val _materialDialog = MutableStateFlow<AgentMaterialDialogState?>(null)
    val materialDialog: StateFlow<AgentMaterialDialogState?> = _materialDialog.asStateFlow()

    private val _preview = MutableStateFlow<ResolvedAgentReference?>(null)
    val preview: StateFlow<ResolvedAgentReference?> = _preview.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var chapterJob: Job? = null
    private var statusJob: Job? = null

    init {
        viewModelScope.launch {
            sessionRepository.observeSession(sessionId).collect { session ->
                // 会话切换不影响本页面；这里只用于默认书籍筛选
                if (_filterBookId.value == null) {
                    val sessionBookId = session?.bookId
                    val all = books.first()
                    val initial = sessionBookId?.takeIf { id -> all.any { it.id == id } }
                        ?: all.firstOrNull()?.id
                    if (initial != null) {
                        _filterBookId.value = initial
                        loadVolumes(initial)
                        scheduleChapterLoad(immediate = true)
                    }
                }
            }
        }
        viewModelScope.launch {
            books.collect { all ->
                // 会话未绑定书籍时，等书籍列表加载后再选第一本，避免章节页空白
                if (_filterBookId.value == null && all.isNotEmpty()) {
                    val first = all.first().id
                    _filterBookId.value = first
                    loadVolumes(first)
                    scheduleChapterLoad(immediate = true)
                }
            }
        }
        viewModelScope.launch {
            referenceRepository.observeReferences(sessionId).collect { refs ->
                _references.value = refs
                refreshStatuses(refs)
            }
        }
        viewModelScope.launch {
            sessionRepository.observeAllSessions().collect { all ->
                _agentSessions.value = all.filter { it.id != sessionId }
            }
        }
    }

    fun setCurrentTaskId(taskId: Long?) {
        _currentTaskId.value = taskId
    }

    // ---------------- 选择页 ----------------

    fun setTab(index: Int) {
        if (index != _tab.value) _selectedChapterIds.value = emptySet()
        _tab.value = index
        if (index == REFERENCE_TAB_AGENT) {
            _expandedSessionId.value = null
            _agentTasks.value = emptyList()
        }
    }

    fun setSearch(text: String) {
        _search.value = text
        _selectedChapterIds.value = emptySet()
        scheduleChapterLoad()
    }

    fun setFilterBook(bookId: Long?) {
        if (_filterBookId.value == bookId) return
        _filterBookId.value = bookId
        _filterVolumeId.value = null
        _selectedChapterIds.value = emptySet()
        loadVolumes(bookId)
        scheduleChapterLoad(immediate = true)
    }

    fun setFilterVolume(volumeId: Long?) {
        if (_filterVolumeId.value == volumeId) return
        _filterVolumeId.value = volumeId
        _selectedChapterIds.value = emptySet()
        scheduleChapterLoad(immediate = true)
    }

    fun toggleChapter(chapterId: Long) {
        val current = _selectedChapterIds.value
        _selectedChapterIds.value = if (chapterId in current) {
            current - chapterId
        } else {
            current + chapterId
        }
    }

    /** 添加当前选中的章节梗概或正文 */
    fun addSelectedChapters(sourceType: String) = viewModelScope.launch {
        val ids = _selectedChapterIds.value
        if (ids.isEmpty()) {
            _notice.value = "请先选择章节"
            return@launch
        }
        val rows = _chapters.value.filter { it.id in ids }
        var added = 0
        var failed = 0
        rows.forEach { row ->
            val ok = runCatching {
                referenceRepository.addReference(
                    sessionId = sessionId,
                    sourceType = sourceType,
                    sourceId = row.id,
                    bookId = row.bookId,
                    chapterId = row.id,
                    titleSnapshot = row.title,
                    referenceMode = AgentReferenceMode.CONTEXT
                )
            }.isSuccess
            if (ok) added++ else failed++
        }
        _selectedChapterIds.value = emptySet()
        _notice.value = buildString {
            append("已添加 $added 条参考章节")
            if (failed > 0) append("，$failed 条因引用上限或来源问题未添加")
        }
    }

    // ---------------- 历史 Agent 资料 ----------------

    fun setAgentSearch(text: String) {
        _agentSearch.value = text
    }

    fun setAgentFilterBook(bookId: Long?) {
        _agentFilterBookId.value = bookId
    }

    fun toggleAgentSession(sessionId: Long) = viewModelScope.launch {
        if (_expandedSessionId.value == sessionId) {
            _expandedSessionId.value = null
            _agentTasks.value = emptyList()
            return@launch
        }
        _expandedSessionId.value = sessionId
        _agentTasks.value = sourceRepository.tasksOfSession(sessionId)
    }

    fun openMaterialDialog(task: AgentTask) = viewModelScope.launch {
        val messages = sourceRepository.materialMessages(task.sessionId)
            .filter { it.taskId == null || it.taskId == task.id }
        val tools = sourceRepository.materialToolResults(task.id)
        _materialDialog.value = AgentMaterialDialogState(task, messages, tools)
    }

    fun closeMaterialDialog() {
        _materialDialog.value = null
    }

    fun toggleMaterial(candidate: AgentMaterialCandidate) {
        val current = _selectedMaterials.value
        _selectedMaterials.value = if (candidate.key in current) {
            current - candidate.key
        } else {
            if (candidate.sourceType == AgentReferenceSourceType.AGENT_MESSAGE) {
                val existingCount = _references.value.count {
                    it.sourceType == AgentReferenceSourceType.AGENT_MESSAGE
                }
                val pendingCount = current.values.count {
                    it.sourceType == AgentReferenceSourceType.AGENT_MESSAGE
                }
                if (existingCount + pendingCount >= MAX_MATERIAL_MESSAGES) {
                    _notice.value = "单次最多选择 $MAX_MATERIAL_MESSAGES 条历史消息"
                    return
                }
            }
            current + (candidate.key to candidate)
        }
    }

    fun toggleTaskPlan(task: AgentTask) {
        toggleMaterial(
            AgentMaterialCandidate(
                key = AgentReferenceSourceType.AGENT_TASK_PLAN + ":" + task.id,
                sourceType = AgentReferenceSourceType.AGENT_TASK_PLAN,
                sourceId = task.id,
                title = "任务计划：${task.title}",
                bookId = _agentSessions.value.firstOrNull { it.id == task.sessionId }?.bookId
            )
        )
    }

    fun toggleTaskResult(task: AgentTask) {
        toggleMaterial(
            AgentMaterialCandidate(
                key = AgentReferenceSourceType.AGENT_TASK_RESULT + ":" + task.id,
                sourceType = AgentReferenceSourceType.AGENT_TASK_RESULT,
                sourceId = task.id,
                title = "任务结果：${task.title}",
                bookId = _agentSessions.value.firstOrNull { it.id == task.sessionId }?.bookId
            )
        )
    }

    fun addSelectedMaterials() = viewModelScope.launch {
        val selected = _selectedMaterials.value.values.toList()
        if (selected.isEmpty()) {
            _notice.value = "请先选择历史资料"
            return@launch
        }
        var added = 0
        var failed = 0
        selected.forEach { candidate ->
            val ok = runCatching {
                referenceRepository.addReference(
                    sessionId = sessionId,
                    sourceType = candidate.sourceType,
                    sourceId = candidate.sourceId,
                    bookId = candidate.bookId,
                    titleSnapshot = candidate.title,
                    referenceMode = AgentReferenceMode.CONTEXT
                )
            }.isSuccess
            if (ok) added++ else failed++
        }
        _selectedMaterials.value = emptyMap()
        _materialDialog.value = null
        _notice.value = buildString {
            append("已添加 $added 条历史 Agent 资料")
            if (failed > 0) append("，$failed 条因引用上限或来源问题未添加")
        }
    }

    // ---------------- 已选引用管理 ----------------

    fun toggleReferenceMode(reference: AgentSessionReference) = viewModelScope.launch {
        val next = if (reference.referenceMode == AgentReferenceMode.CONTEXT) {
            AgentReferenceMode.REFERENCE_ONLY
        } else {
            AgentReferenceMode.CONTEXT
        }
        runCatching { referenceRepository.setReferenceMode(reference.id, next) }
            .onFailure { _notice.value = it.message ?: "修改引用模式失败" }
    }

    fun removeReference(reference: AgentSessionReference) = viewModelScope.launch {
        runCatching { referenceRepository.deleteReference(reference.id) }
            .onFailure { _notice.value = it.message ?: "移除引用失败" }
    }

    fun preview(reference: AgentSessionReference) = viewModelScope.launch {
        _notice.value = null
        _preview.value = runCatching {
            resolver.resolve(reference.toTarget(), contentMaxChars = 2_000)
        }.getOrElse {
            ResolvedAgentReference(
                target = reference.toTarget(),
                valid = false,
                invalidReason = it.message ?: "读取来源失败",
                title = reference.titleSnapshot
            )
        }
    }

    fun dismissPreview() {
        _preview.value = null
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun refreshStatusesNow() {
        refreshStatuses(_references.value)
    }

    // ---------------- 内部 ----------------

    private fun loadVolumes(bookId: Long?) = viewModelScope.launch {
        _volumes.value = if (bookId == null) emptyList() else bookRepository.getVolumes(bookId)
    }

    private fun scheduleChapterLoad(immediate: Boolean = false) {
        chapterJob?.cancel()
        chapterJob = viewModelScope.launch {
            if (!immediate) delay(250)
            val bookId = _filterBookId.value
            if (bookId == null) {
                _chapters.value = emptyList()
                return@launch
            }
            _loadingChapters.value = true
            try {
                _chapters.value = sourceRepository.chapterCandidates(
                    bookId = bookId,
                    volumeId = _filterVolumeId.value,
                    query = _search.value,
                    snippet = 160,
                    limit = MAX_CHAPTER_CANDIDATES
                )
            } catch (e: Throwable) {
                _notice.value = e.message ?: "读取章节失败"
            } finally {
                _loadingChapters.value = false
            }
        }
    }

    private fun refreshStatuses(refs: List<AgentSessionReference>) {
        statusJob?.cancel()
        statusJob = viewModelScope.launch {
            val map = mutableMapOf<Long, ResolvedAgentReference>()
            refs.take(50).forEach { reference ->
                val resolved = runCatching {
                    resolver.resolve(
                        target = reference.toTarget(),
                        contentMaxChars = 0,
                        excludedTaskId = _currentTaskId.value ?: 0L
                    )
                }.getOrElse {
                    ResolvedAgentReference(
                        target = reference.toTarget(),
                        valid = false,
                        invalidReason = it.message ?: "校验来源失败",
                        title = reference.titleSnapshot
                    )
                }
                map[reference.id] = resolved
            }
            _statusMap.value = map
        }
    }

    private fun AgentSessionReference.toTarget(): AgentReferenceTarget = AgentReferenceTarget(
        referenceId = id,
        sourceType = sourceType,
        sourceId = sourceId,
        bookId = bookId,
        chapterId = chapterId,
        titleSnapshot = titleSnapshot,
        referenceMode = referenceMode
    )

    companion object {
        const val REFERENCE_TAB_SELECTED = 0
        const val REFERENCE_TAB_SYNOPSIS = 1
        const val REFERENCE_TAB_CONTENT = 2
        const val REFERENCE_TAB_AGENT = 3

        const val MAX_CHAPTER_CANDIDATES = 500
        const val MAX_MATERIAL_MESSAGES = 20
    }
}
