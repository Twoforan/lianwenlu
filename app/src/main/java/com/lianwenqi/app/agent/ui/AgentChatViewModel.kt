package com.lianwenqi.app.agent.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.agent.model.AgentCapabilityCatalog
import com.lianwenqi.app.agent.data.AgentPlanSaveRequest
import com.lianwenqi.app.agent.data.entity.AgentBranchSource
import com.lianwenqi.app.agent.data.entity.AgentClarification
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentModelRequest
import com.lianwenqi.app.agent.data.entity.AgentRecoveryLog
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.agent.data.entity.AgentWorkMode
import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanParseResult
import com.lianwenqi.app.agent.model.AgentPlanStep
import com.lianwenqi.app.agent.model.AgentPlanValidationResult
import com.lianwenqi.app.agent.model.AgentRiskLevel
import com.lianwenqi.app.agent.planning.AgentPlanningResult
import com.lianwenqi.app.agent.planning.AgentPlanningTrace
import com.lianwenqi.app.agent.planning.AgentPromptLog
import com.lianwenqi.app.agent.tool.AgentToolPermission
import com.lianwenqi.app.ai.PromptDiagnostics
import com.lianwenqi.app.data.AttachmentStore
import com.lianwenqi.app.data.ChatAttachment
import com.lianwenqi.app.data.SortMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.data.style.StyleLibraryState
import com.lianwenqi.app.data.style.StyleProfileRow
import com.lianwenqi.app.data.style.StyleProfileSummaryReader
import com.lianwenqi.app.ui.components.BookFormResult
import com.lianwenqi.app.util.FileUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前计划的展示模型 */
data class AgentPlanView(
    val plan: AgentPlan,
    val validation: AgentPlanValidationResult,
    val confirmed: Boolean
)

/** 计划书库条目：任务 + 解析后的计划 */
data class AgentPlanLibraryItem(
    val task: AgentTask,
    val plan: AgentPlan
)

/**
 * 历史任务的产出物：计划书 + 执行记录。
 *
 * 会话内新任务开始时，旧任务的计划书与执行过程仍要保留在聊天流里（默认收起）。
 */
data class AgentTaskArtifacts(
    val task: AgentTask,
    val planView: AgentPlanView,
    val nodes: List<AgentExecutionNode>,
    val tools: List<AgentToolExecution>,
    val clarifications: List<AgentClarification>
)

/**
 * Agent 输入栏工具信息。
 * 执行引擎默认使用全部已注册工具，因此这里只做展示（全部为选中状态）。
 */
data class AgentToolInfo(
    val key: String,
    val name: String,
    val description: String,
    val readOnly: Boolean,
    /** 生成工具：只产出草稿，不直接写入章节 */
    val generation: Boolean
)

/** 底部澄清选择框的展示模型 */
data class AgentClarificationView(
    val id: Long,
    val question: String,
    val options: List<String>
)

/** 一次任务发送时冻结的附件上下文：文件 / 链接正文 + 图片 data URL */
private data class AgentAttachmentContext(
    val text: String = "",
    val imageDataUrls: List<String> = emptyList()
)

/**
 * Agent 模式 ViewModel。
 *
 * F3 职责：独立模型选择、任务创建、规划请求、计划展示 / 修改 / 重新生成 / 确认。
 * 不执行工具、不调用小说业务接口、不实现执行循环。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentChatViewModel(private val container: AppContainer) : ViewModel() {

    private val sessionRepository = container.agentSessionRepository
    private val taskRepository = container.agentTaskRepository
    private val eventRepository = container.agentEventRepository
    private val bookRepository = container.bookRepository
    private val configRepository = container.configRepository
    private val planner = container.agentPlanner
    private val planParser = container.agentPlanParser
    private val planValidator = container.agentPlanValidator
    private val taskController = container.agentTaskController
    private val writeOperationsRepository = container.agentWriteOperationRepository
    private val writeExecutionService = container.agentWriteExecutionService
    private val writeConfirmationGate = container.agentWriteConfirmationGate
    private val recoveryLogRepository = container.agentRecoveryLogRepository
    private val modelRequestRepository = container.agentModelRequestRepository
    private val styleRepository = container.styleRepository
    private val outlineRepository = container.outlineRepository
    private val volumeSummaryRepository = container.volumeSummaryRepository
    private val prefs = container.prefs

    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    private val _currentTaskId = MutableStateFlow<Long?>(null)
    val currentTaskId: StateFlow<Long?> = _currentTaskId.asStateFlow()

    val searchQuery = MutableStateFlow("")
    val filterBookId = MutableStateFlow<Long?>(null)
    val input = MutableStateFlow("")

    /** 已收起正文的消息 id：按消息记录，切换会话 / 模式或重组后仍保持 */
    private val _collapsedMessageIds = MutableStateFlow<Set<Long>>(emptySet())
    val collapsedMessageIds: StateFlow<Set<Long>> = _collapsedMessageIds.asStateFlow()

    fun toggleMessageCollapsed(messageId: Long) {
        _collapsedMessageIds.value = _collapsedMessageIds.value.toggle(messageId)
    }

    /** 「深度思考」面板展开状态：同一时刻只有最新一轮展示，因此用单一状态记录 */
    private val _thinkingExpanded = MutableStateFlow(false)
    val thinkingExpanded: StateFlow<Boolean> = _thinkingExpanded.asStateFlow()

    fun toggleThinkingExpanded() {
        _thinkingExpanded.value = !_thinkingExpanded.value
    }

    /** 快捷提示词：与普通 AI 聊天共用同一份提示词库 */
    val prompts: StateFlow<List<Prompt>> = configRepository.observePrompts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Agent 已注册工具；执行时默认全部启用，因此 UI 一律显示为选中 */
    val agentTools: List<AgentToolInfo> = container.agentToolRegistry.all()
        .filter { it.visibleToUser }
        .map { tool ->
        val capability = AgentCapabilityCatalog.find(tool.capabilityKey)
        AgentToolInfo(
            key = tool.toolKey,
            name = capability?.displayName ?: tool.name,
            description = capability?.description ?: tool.description,
            readOnly = tool.permissionLevel == AgentToolPermission.READ_ONLY,
            generation = tool.permissionLevel == AgentToolPermission.GENERATE
        )
    }.sortedWith(
        compareBy(
            { if (it.readOnly) 0 else if (it.generation) 1 else 2 },
            { it.name }
        )
    )

    private val attachmentStore = AttachmentStore(container.context)

    /** 待发送附件（图片 / 文件 / 链接），发送后清空 */
    private val _pendingAttachments = MutableStateFlow<List<ChatAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<ChatAttachment>> = _pendingAttachments.asStateFlow()

    /** 每个任务发送时冻结的附件上下文，供重新规划复用 */
    private val taskAttachmentContexts = mutableMapOf<Long, AgentAttachmentContext>()

    private val pendingModelId = MutableStateFlow<Long?>(null)
    private val confirmedTaskIds = MutableStateFlow<Set<Long>>(emptySet())

    private val _planning = MutableStateFlow(false)
    val planning: StateFlow<Boolean> = _planning.asStateFlow()

    /** 正在创建任务（防止连续点击产生重复任务） */
    private val _sendingTask = MutableStateFlow(false)
    val sendingTask: StateFlow<Boolean> = _sendingTask.asStateFlow()

    private val _planningTaskId = MutableStateFlow<Long?>(null)
    val planningTaskId: StateFlow<Long?> = _planningTaskId.asStateFlow()

    private val _planError = MutableStateFlow<String?>(null)
    val planError: StateFlow<String?> = _planError.asStateFlow()

    private val _planEditError = MutableStateFlow<String?>(null)
    val planEditError: StateFlow<String?> = _planEditError.asStateFlow()

    private val _executingTaskId = MutableStateFlow<Long?>(null)
    val executingTaskId: StateFlow<Long?> = _executingTaskId.asStateFlow()

    private val _executionError = MutableStateFlow<String?>(null)
    val executionError: StateFlow<String?> = _executionError.asStateFlow()

    /** 当前任务的执行节点（按 sequence） */
    val executionNodes: StateFlow<List<AgentExecutionNode>> = _currentTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else container.agentExecutionNodeRepository.observeNodes(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前任务的工具执行记录 */
    val toolExecutions: StateFlow<List<AgentToolExecution>> = _currentTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else container.agentToolExecutionRepository.observeExecutions(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前任务已回答的补充信息：作为思维链的一部分展示，不再生成用户对话消息 */
    val answeredClarifications: StateFlow<List<AgentClarification>> = _currentTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else taskRepository.observeAnsweredClarifications(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** F6：当前任务的写入操作（来自 Room，UI 不依赖内存状态） */
    val writeOperations: StateFlow<List<AgentWriteOperation>> = _currentTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else writeOperationsRepository.observeOfTask(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** F6：写入权限策略；默认每次询问 */
    val writePolicy: StateFlow<String> = prefs.agentWritePolicy
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AgentWritePermissionPolicy.ASK_EVERY_TIME
        )

    /** 新任务工作模式；只影响后续创建的任务，已创建任务读取自身 workMode */
    val workMode: StateFlow<String> = prefs.agentWorkMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentWorkMode.PLAN)

    /** 当前任务待回答的澄清请求；回答或取消后自动消失 */
    val pendingClarification: StateFlow<AgentClarificationView?> = _currentTaskId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<AgentClarificationView?>(null)
            } else {
                taskRepository.observePendingClarification(id).map { clarification ->
                    clarification?.let {
                        AgentClarificationView(
                            id = it.id,
                            question = it.question,
                            options = taskRepository.clarificationOptions(it.optionsJson)
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _writeNotice = MutableStateFlow<String?>(null)
    val writeNotice: StateFlow<String?> = _writeNotice.asStateFlow()

    /** Agent 独立模型列表；不影响普通聊天的 activeModelId */
    val models: StateFlow<List<AiModel>> =
        configRepository.observeModels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val providers: StateFlow<List<Provider>> =
        configRepository.observeProviders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val globalModelId: StateFlow<Long> = prefs.activeModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** Agent 会话列表（按标题关键词与书籍筛选，独立于普通聊天会话） */
    val sessions: StateFlow<List<AgentSession>> =
        combine(searchQuery, filterBookId) { query, bookId -> query to bookId }
            .flatMapLatest { (query, bookId) -> sessionRepository.observeSessions(query, bookId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentSession: StateFlow<AgentSession?> = _currentSessionId
        .flatMapLatest { id -> if (id == null) flowOf(null) else sessionRepository.observeSession(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<AgentMessage>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else sessionRepository.observeMessages(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前任务的消息；任务消息必须带 taskId，不与会话级消息混淆 */
    val taskMessages: StateFlow<List<AgentMessage>> =
        combine(messages, _currentTaskId) { list, taskId ->
            if (taskId == null) list.filter { it.taskId == null }
            else list.filter { it.taskId == taskId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前任务是否已经正式发布计划；澄清草稿不生成计划消息，因此对用户不可见 */
    val planPublished: StateFlow<Boolean> = taskMessages
        .map { list ->
            list.any { it.kind == AgentMessageKind.PLAN && it.content.isNotBlank() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val tasks: StateFlow<List<AgentTask>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else taskRepository.observeTasksOfSession(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currentTask: StateFlow<AgentTask?> = _currentTaskId
        .flatMapLatest { id -> if (id == null) flowOf(null) else taskRepository.observeTask(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前任务的父分支；根任务为 null */
    val branchParent: StateFlow<AgentTask?> = currentTask
        .flatMapLatest { task ->
            val parentId = task?.parentTaskId
            if (parentId == null) flowOf(null) else taskRepository.observeTask(parentId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 与当前任务同父的其他分支；根任务没有同级分支 */
    val branchSiblings: StateFlow<List<AgentTask>> =
        combine(currentTask, tasks) { task, all ->
            val parentId = task?.parentTaskId
            if (task == null || parentId == null) {
                emptyList()
            } else {
                all.filter { it.id != task.id && it.parentTaskId == parentId }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前任务的直接子分支 */
    val branchChildren: StateFlow<List<AgentTask>> = _currentTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else taskRepository.observeChildTasks(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------------- F7：任务管理详情 ----------------

    private val _inspectedTaskId = MutableStateFlow<Long?>(null)
    val inspectedTaskId: StateFlow<Long?> = _inspectedTaskId.asStateFlow()

    val inspectedTask: StateFlow<AgentTask?> = _inspectedTaskId
        .flatMapLatest { id -> if (id == null) flowOf(null) else taskRepository.observeTask(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val inspectedNodes: StateFlow<List<AgentExecutionNode>> = _inspectedTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else container.agentExecutionNodeRepository.observeNodes(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inspectedWrites: StateFlow<List<AgentWriteOperation>> = _inspectedTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else writeOperationsRepository.observeOfTask(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inspectedRecoveryLogs: StateFlow<List<AgentRecoveryLog>> = _inspectedTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else recoveryLogRepository.observeOfTask(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inspectedModelRequests: StateFlow<List<AgentModelRequest>> = _inspectedTaskId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else modelRequestRepository.observeOfTask(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inspectedSession: StateFlow<AgentSession?> = inspectedTask
        .flatMapLatest { task ->
            if (task == null) flowOf(null)
            else sessionRepository.observeSession(task.sessionId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 当前计划（解析自 agent_task.planJson，并附带结构校验与确认状态） */
    val currentPlan: StateFlow<AgentPlanView?> =
        combine(currentTask, confirmedTaskIds) { task, confirmed ->
            val json = task?.planJson
            if (json.isNullOrBlank() || json == "{}") return@combine null
            when (val parsed = planParser.parse(json)) {
                is AgentPlanParseResult.Failure -> null
                is AgentPlanParseResult.Success -> AgentPlanView(
                    plan = parsed.plan,
                    validation = planValidator.validate(parsed.plan),
                    confirmed = task.id in confirmed
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 会话内可见任务：顺序产生的根任务全部可见；
     * 存在分支时只保留当前任务所在的链条，避免同一提问的旧版本重复出现。
     */
    private fun visibleTaskIds(tasks: List<AgentTask>, current: AgentTask?): Set<Long> {
        if (current == null) return emptySet()
        val parentsWithChildren = tasks.mapNotNull { it.parentTaskId }.toSet()
        val chain = mutableSetOf<Long>()
        var cursor: AgentTask? = current
        while (cursor != null) {
            chain += cursor.id
            val parentId = cursor.parentTaskId
            cursor = if (parentId == null) null else tasks.firstOrNull { it.id == parentId }
        }
        return tasks
            .filter { it.id in chain || (it.parentTaskId == null && it.id !in parentsWithChildren) }
            .map { it.id }
            .toSet()
    }

    /**
     * 会话内历史任务（不含当前任务）的计划书与执行记录。
     *
     * 可见范围与聊天消息保持一致：顺序产生的根任务全部展示，分支只保留当前任务所在的链条。
     */
    val historicalArtifacts: StateFlow<List<AgentTaskArtifacts>> =
        combine(tasks, currentTask) { all, current -> all to current }
            .flatMapLatest { (all, current) ->
                val visible = visibleTaskIds(all, current)
                val prepared = all
                    .filter { task ->
                        task.id in visible && task.id != current?.id &&
                            task.workMode == AgentWorkMode.PLAN
                    }
                    .sortedBy { it.createdAt }
                    .mapNotNull { task ->
                        val json = task.planJson
                        if (json.isNullOrBlank() || json == "{}") return@mapNotNull null
                        val parsed = planParser.parse(json)
                        if (parsed !is AgentPlanParseResult.Success) return@mapNotNull null
                        if (parsed.plan.needsClarification) return@mapNotNull null
                        task to parsed.plan
                    }
                if (prepared.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(
                        prepared.map { (task, plan) ->
                            combine(
                                container.agentExecutionNodeRepository.observeNodes(task.id),
                                container.agentToolExecutionRepository.observeExecutions(task.id),
                                taskRepository.observeAnsweredClarifications(task.id)
                            ) { nodes, tools, clarifications ->
                                AgentTaskArtifacts(
                                    task = task,
                                    planView = AgentPlanView(
                                        plan = plan,
                                        validation = planValidator.validate(plan),
                                        confirmed = task.id in confirmedTaskIds.value
                                    ),
                                    nodes = nodes,
                                    tools = tools,
                                    clarifications = clarifications
                                )
                            }
                        }
                    ) { it.toList() }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 设置里配置的 Agent 任务模型；0 表示跟随会话 / 全局模型 */
    val agentTaskModelId: StateFlow<Long> = prefs.agentTaskModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 设置里配置的正文生成模型；0 表示跟随任务模型 */
    val agentContentModelId: StateFlow<Long> = prefs.agentContentModelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 当前生效模型：会话模型 > 本次手动选择 > 设置中的任务模型 > 普通聊天当前模型 */
    val currentModelId: StateFlow<Long> =
        combine(
            currentSession,
            pendingModelId,
            agentTaskModelId,
            globalModelId
        ) { session, pending, taskModel, global ->
            session?.modelId?.takeIf { it > 0 }
                ?: pending?.takeIf { it > 0 }
                ?: taskModel.takeIf { it > 0 }
                ?: global
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** 会话绑定 / 筛选可选的书籍列表 */
    val books: StateFlow<List<Book>> =
        bookRepository.observeBooks(SortMode.TIME, false)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 书籍分组：绑定书籍弹窗与历史筛选共用 */
    val groups: StateFlow<List<BookGroup>> =
        bookRepository.observeGroups()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // 新建书籍弹窗里的文风档案：与普通 AI 聊天复用同一套读取逻辑
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StyleLibraryState())

    fun retryStyleProfiles() {
        styleRetry.value++
    }

    /** 当前绑定的书籍：优先会话绑定，其次新建任务时的筛选书籍 */
    private val selectedBookId: StateFlow<Long?> =
        combine(currentSession, filterBookId) { session, filter ->
            session?.bookId ?: filter
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 绑定书籍的章节 / 分卷 / 大纲：仅用于 Agent 上下文选择弹窗 */
    val chapters: StateFlow<List<Chapter>> = selectedBookId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else bookRepository.observeChapters(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val volumes: StateFlow<List<Volume>> = selectedBookId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else bookRepository.observeVolumes(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outline: StateFlow<List<OutlineItem>> = selectedBookId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else outlineRepository.observeOutline(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 绑定书籍当前生效的整卷梗概（每卷最多一条） */
    val activeVolumeSummaries: StateFlow<List<VolumeSummary>> = selectedBookId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else volumeSummaryRepository.observeActiveByBook(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedChapterSummaryIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedChapterSummaryIds: StateFlow<Set<Long>> = _selectedChapterSummaryIds.asStateFlow()

    private val _selectedChapterContentIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedChapterContentIds: StateFlow<Set<Long>> = _selectedChapterContentIds.asStateFlow()

    private val _selectedVolumeSummaryIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedVolumeSummaryIds: StateFlow<Set<Long>> = _selectedVolumeSummaryIds.asStateFlow()

    private val _selectedOutlineIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedOutlineIds: StateFlow<Set<Long>> = _selectedOutlineIds.asStateFlow()

    /** 已选资料的模式标注：key -> CONTEXT / REFERENCE_ONLY（保存后生效） */
    private val _contextModes = MutableStateFlow<Map<String, String>>(emptyMap())
    val contextModes: StateFlow<Map<String, String>> = _contextModes.asStateFlow()

    /** 保存后的模式快照，规划上下文只读取这里；未保存时默认 CONTEXT */
    private val savedContextModes = mutableMapOf<String, String>()

    /** 历史 Agent 勾选：工作集与保存集，只有保存后的会话才进入已选资料 */
    private val _historySelection = MutableStateFlow<Set<Long>>(emptySet())
    val historySelection: StateFlow<Set<Long>> = _historySelection.asStateFlow()

    private val _savedHistoryIds = MutableStateFlow<Set<Long>>(emptySet())
    val savedHistoryIds: StateFlow<Set<Long>> = _savedHistoryIds.asStateFlow()

    fun contextMode(key: String): String =
        _contextModes.value[key] ?: AgentReferenceMode.CONTEXT

    private fun effectiveContextMode(key: String): String =
        savedContextModes[key] ?: AgentReferenceMode.CONTEXT

    fun setContextMode(key: String, mode: String) {
        _contextModes.value = _contextModes.value + (key to mode)
    }

    /** 保存已选资料的模式标注；保存后规划上下文按最新标注取舍 */
    fun saveContextSelection() {
        savedContextModes.clear()
        savedContextModes.putAll(_contextModes.value)
    }

    fun beginHistorySelection(validIds: Set<Long>) {
        _historySelection.value = _savedHistoryIds.value intersect validIds
    }

    fun toggleHistorySelection(id: Long) {
        _historySelection.value = _historySelection.value.toggle(id)
    }

    fun toggleAllHistorySelection(ids: Set<Long>) {
        _historySelection.value =
            if (ids.isNotEmpty() && ids.all { it in _historySelection.value }) {
                _historySelection.value - ids
            } else {
                _historySelection.value + ids
            }
    }

    fun saveHistorySelection() {
        _savedHistoryIds.value = _historySelection.value
    }

    fun removeSavedHistory(id: Long) {
        _savedHistoryIds.value = _savedHistoryIds.value - id
        _contextModes.value = _contextModes.value - "hist-$id"
    }

    fun removeChapterSummary(id: Long) {
        _selectedChapterSummaryIds.value = _selectedChapterSummaryIds.value - id
        _contextModes.value = _contextModes.value - "cs-$id"
    }

    fun removeChapterContent(id: Long) {
        _selectedChapterContentIds.value = _selectedChapterContentIds.value - id
        _contextModes.value = _contextModes.value - "cc-$id"
    }

    fun removeVolumeSummary(id: Long) {
        _selectedVolumeSummaryIds.value = _selectedVolumeSummaryIds.value - id
        _contextModes.value = _contextModes.value - "vs-$id"
    }

    fun removeOutline(id: Long) {
        _selectedOutlineIds.value = _selectedOutlineIds.value - id
        _contextModes.value = _contextModes.value - "ol-$id"
    }

    fun toggleChapterSummary(id: Long) {
        _selectedChapterSummaryIds.value = _selectedChapterSummaryIds.value.toggle(id)
    }

    fun toggleChapterContent(id: Long) {
        _selectedChapterContentIds.value = _selectedChapterContentIds.value.toggle(id)
    }

    fun toggleAllChapters() {
        val all = chapters.value.map { it.id }.toSet()
        val allOn = all.isNotEmpty() &&
            all.all { it in _selectedChapterSummaryIds.value && it in _selectedChapterContentIds.value }
        _selectedChapterSummaryIds.value = if (allOn) emptySet() else all
        _selectedChapterContentIds.value = if (allOn) emptySet() else all
    }

    fun toggleAllSummary() {
        val all = chapters.value.map { it.id }.toSet()
        _selectedChapterSummaryIds.value =
            if (all.isNotEmpty() && all.all { it in _selectedChapterSummaryIds.value }) emptySet() else all
    }

    fun toggleAllContent() {
        val all = chapters.value.map { it.id }.toSet()
        _selectedChapterContentIds.value =
            if (all.isNotEmpty() && all.all { it in _selectedChapterContentIds.value }) emptySet() else all
    }

    fun toggleVolumeSummary(chapterIds: List<Long>) {
        val ids = chapterIds.toSet()
        _selectedChapterSummaryIds.value =
            if (ids.isNotEmpty() && ids.all { it in _selectedChapterSummaryIds.value }) {
                _selectedChapterSummaryIds.value - ids
            } else {
                _selectedChapterSummaryIds.value + ids
            }
    }

    fun toggleVolumeContent(chapterIds: List<Long>) {
        val ids = chapterIds.toSet()
        _selectedChapterContentIds.value =
            if (ids.isNotEmpty() && ids.all { it in _selectedChapterContentIds.value }) {
                _selectedChapterContentIds.value - ids
            } else {
                _selectedChapterContentIds.value + ids
            }
    }

    fun toggleVolumeSummaryAsset(volumeId: Long) {
        val summary = activeVolumeSummaries.value.firstOrNull { it.volumeId == volumeId } ?: return
        _selectedVolumeSummaryIds.value = _selectedVolumeSummaryIds.value.toggle(summary.id)
    }

    fun toggleAllVolumeSummaries() {
        val all = activeVolumeSummaries.value.map { it.id }.toSet()
        _selectedVolumeSummaryIds.value =
            if (all.isNotEmpty() && all.all { it in _selectedVolumeSummaryIds.value }) emptySet() else all
    }

    fun toggleOutline(id: Long) {
        _selectedOutlineIds.value = _selectedOutlineIds.value.toggle(id)
    }

    fun toggleAllOutlines(ids: Set<Long> = outline.value.map { it.id }.toSet()) {
        _selectedOutlineIds.value =
            if (ids.isNotEmpty() && ids.all { it in _selectedOutlineIds.value }) {
                _selectedOutlineIds.value - ids
            } else {
                _selectedOutlineIds.value + ids
            }
    }

    fun toggleOutlineCategory(category: String) {
        val ids = outline.value.filter { it.category == category }.map { it.id }.toSet()
        toggleAllOutlines(ids)
    }

    private fun clearContextSelection() {
        _selectedChapterSummaryIds.value = emptySet()
        _selectedChapterContentIds.value = emptySet()
        _selectedVolumeSummaryIds.value = emptySet()
        _selectedOutlineIds.value = emptySet()
        _contextModes.value = emptyMap()
        savedContextModes.clear()
        _historySelection.value = emptySet()
        _savedHistoryIds.value = emptySet()
    }

    private var planJob: Job? = null

    init {
        // 恢复上次打开的 Agent 会话；不存在（已被删除）则保持空态。
        // 若用户在恢复完成前已经手动新建 / 打开了会话，不覆盖用户当前选择。
        viewModelScope.launch {
            val last = prefs.agentLastSessionId.first()
            if (last > 0L && _currentSessionId.value == null) {
                val session = sessionRepository.getSession(last) ?: return@launch
                _currentSessionId.value = session.id
                restoreTask(session)
            }
        }
        // F7：执行状态以数据库租约为准；页面只观察，不持有后台执行协程。
        viewModelScope.launch {
            currentTask.collect { task ->
                _executingTaskId.value = if (
                    task != null &&
                    task.status == AgentTaskStatus.RUNNING &&
                    hasActiveLease(task)
                ) {
                    task.id
                } else {
                    null
                }
            }
        }
    }

    override fun onCleared() {
        // 页面销毁只取消规划请求；Agent 任务由 WorkManager 继续执行，不在这里取消。
        planJob?.cancel()
        planJob = null
        super.onCleared()
    }

    // ---------------- 会话 ----------------

    fun newSession(bookId: Long? = filterBookId.value) = viewModelScope.launch {
        createNewSession(bookId, pendingModelId.value?.takeIf { it > 0 })
    }

    /** 真正创建并切换到新会话；模型沿用调用方给出的值，避免切换书籍后丢失模型选择 */
    private suspend fun createNewSession(bookId: Long?, modelId: Long?) {
        cancelPlanJobQuietly()
        cancelRunningTask(reportError = false)
        _executionError.value = null
        clearContextSelection()
        val id = sessionRepository.createSession(
            bookId = bookId,
            modelId = modelId
        )
        // 书籍已经写入会话，清空临时筛选项，避免历史列表被它限制
        filterBookId.value = null
        _currentSessionId.value = id
        _currentTaskId.value = null
        _planError.value = null
        _planEditError.value = null
        prefs.setAgentLastSessionId(id)
    }

    fun openSession(id: Long) = viewModelScope.launch {
        val session = sessionRepository.getSession(id) ?: return@launch
        cancelPlanJobQuietly()
        cancelRunningTask(reportError = false)
        _executionError.value = null
        clearContextSelection()
        filterBookId.value = null
        _currentSessionId.value = id
        pendingModelId.value = null
        _planError.value = null
        _planEditError.value = null
        prefs.setAgentLastSessionId(id)
        restoreTask(session)
    }

    private suspend fun restoreTask(session: AgentSession) {
        val lastTaskId = session.lastTaskId
        if (lastTaskId != null && taskRepository.getTask(lastTaskId) != null) {
            _currentTaskId.value = lastTaskId
            loadConfirmation(lastTaskId)
            maybeResumeClarificationPlanning(lastTaskId)
            maybeAutoStart(lastTaskId)
        } else {
            _currentTaskId.value = null
        }
    }

    fun renameSession(id: Long, title: String) = viewModelScope.launch {
        sessionRepository.renameSession(id, title)
    }

    fun deleteSession(id: Long) = viewModelScope.launch {
        if (_currentSessionId.value == id) {
            cancelPlanJobQuietly()
            cancelRunningTask(reportError = false)
            _executionError.value = null
        }
        sessionRepository.deleteSession(id)
        if (_currentSessionId.value == id) {
            _currentSessionId.value = null
            _currentTaskId.value = null
            prefs.setAgentLastSessionId(0L)
        }
    }

    /** 直接编辑 Agent 回复：以原文为基础保存，不触发重新生成 */
    fun updateMessageContent(messageId: Long, content: String) = viewModelScope.launch {
        val text = content.trim()
        if (text.isBlank()) {
            _planError.value = "回复内容不能为空"
            return@launch
        }
        if (_executingTaskId.value != null) {
            _planError.value = "任务执行中不能编辑回复"
            return@launch
        }
        sessionRepository.updateMessageContent(messageId, text)
    }

    /** 删除单条聊天消息；执行中禁止删除，避免删除当前任务的上下文 */
    fun deleteMessage(messageId: Long) = viewModelScope.launch {
        if (_executingTaskId.value != null) {
            _planError.value = "任务执行中不能删除消息"
            return@launch
        }
        sessionRepository.deleteMessage(messageId)
    }

    fun bindBook(sessionId: Long, bookId: Long?) = viewModelScope.launch {
        val session = sessionRepository.getSession(sessionId) ?: return@launch
        if (session.bookId == bookId) return@launch
        // 执行中的任务依赖会话绑定的书籍，换绑会让后续工具访问到错误的数据
        val lastTask = session.lastTaskId?.let { taskRepository.getTask(it) }
        if (lastTask != null && lastTask.status in AgentTaskStatus.unfinished) {
            _planError.value = "任务执行中不能更换书籍，请先结束当前任务"
            return@launch
        }
        if (session.bookId == null) {
            // 首次绑定：直接绑定当前会话
            sessionRepository.bindBook(sessionId, bookId)
            clearContextSelection()
        } else {
            // 换绑其他书籍：开始一个新会话，避免同一会话跨书混用
            createNewSession(bookId, currentModelId.value.takeIf { it > 0 })
        }
    }

    /** 完整表单新建书籍（复用普通 AI 聊天的选书弹窗），返回新书 id；失败返回 0 */
    suspend fun createBookFull(r: BookFormResult): Long = try {
        if (r.styleId != 0L && styleRepository.getProfile(r.styleId) == null) {
            retryStyleProfiles()
            0L
        } else {
            bookRepository.createBook(
                title = r.title.trim(),
                intro = r.intro.trim(),
                coverPath = r.coverPath,
                coverColor = r.coverColor,
                volumePrefix = r.volumePrefix,
                chapterPrefix = r.chapterPrefix,
                groupId = r.groupId,
                styleId = r.styleId
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        retryStyleProfiles()
        0L
    } catch (e: Exception) {
        0L
    }

    /** 复制封面图到应用私有目录，返回本地路径 */
    suspend fun pickCover(uri: Uri): String? =
        withContext(Dispatchers.IO) { FileUtil.copyToInternal(container.context, uri) }

    /** 新建分组并返回新分组 id（书籍表单内现场建组用） */
    suspend fun createGroupAndGet(name: String): Long = bookRepository.createGroup(name)

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setFilterBook(bookId: Long?) {
        filterBookId.value = bookId
        clearContextSelection()
    }

    // ---------------- 模型 ----------------

    /** 只修改 Agent 会话模型；不触碰普通聊天的 activeModelId */
    fun setModel(modelId: Long) = viewModelScope.launch {
        pendingModelId.value = modelId
        _currentSessionId.value?.let { sessionRepository.setModel(it, modelId) }
        _planError.value = null
    }

    /** 保存设置里的任务模型，并让当前会话立即生效 */
    fun setAgentTaskModel(modelId: Long) = viewModelScope.launch {
        prefs.setAgentTaskModelId(modelId)
        pendingModelId.value = modelId
        _currentSessionId.value?.let { sessionRepository.setModel(it, modelId) }
        _planError.value = null
    }

    /** 保存设置里的正文生成模型；0 表示跟随任务模型 */
    fun setAgentContentModel(modelId: Long) = viewModelScope.launch {
        prefs.setAgentContentModelId(modelId)
    }

    // ---------------- 任务与规划 ----------------

    fun setInput(text: String) {
        input.value = text
    }

    /** 修改新任务工作模式；不改变已创建任务固化的模式 */
    fun setWorkMode(mode: String) = viewModelScope.launch {
        if (!AgentWorkMode.isValid(mode)) return@launch
        prefs.setAgentWorkMode(mode)
    }

    /** 快捷提示词：追加到任务目标输入框 */
    fun appendPrompt(prompt: Prompt) {
        if (prompt.content.isBlank()) return
        input.value = if (input.value.isBlank()) prompt.content
        else input.value.trimEnd() + "\n" + prompt.content
    }

    /** 添加图片附件：解码压缩后落盘，失败时给出提示 */
    fun attachImage(uri: Uri, name: String) = viewModelScope.launch {
        val attachment = withContext(Dispatchers.IO) { attachmentStore.saveImage(uri, name) }
        if (attachment == null) {
            _planError.value = "图片读取失败，请换一张试试"
        } else {
            _pendingAttachments.value = _pendingAttachments.value + attachment
        }
    }

    /** 添加文本文件附件 */
    fun attachFile(uri: Uri, name: String) = viewModelScope.launch {
        val attachment = withContext(Dispatchers.IO) { attachmentStore.saveTextFile(uri, name) }
        if (attachment == null) {
            _planError.value = "文件读取失败：只支持纯文本类文件，且内容不能为空"
        } else {
            _pendingAttachments.value = _pendingAttachments.value + attachment
        }
    }

    /** 添加链接附件：抓取网页正文 */
    fun attachLink(url: String) = viewModelScope.launch {
        if (url.isBlank()) return@launch
        _pendingAttachments.value = _pendingAttachments.value + attachmentStore.fetchLink(url.trim())
    }

    /** 移除待发送附件（同时清理本地缓存文件） */
    fun removeAttachment(attachment: ChatAttachment) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it == attachment }
        attachmentStore.deleteFiles(listOf(attachment))
    }

    fun clearPlanError() {
        _planError.value = null
    }

    fun clearPlanEditError() {
        _planEditError.value = null
    }

    fun openTask(taskId: Long) = viewModelScope.launch {
        if (taskRepository.getTask(taskId) == null) return@launch
        _currentTaskId.value = taskId
        _planError.value = null
        _planEditError.value = null
        loadConfirmation(taskId)
        maybeResumeClarificationPlanning(taskId)
        maybeAutoStart(taskId)
    }

    fun closeTask() {
        _currentTaskId.value = null
        _planError.value = null
        _planEditError.value = null
    }

    /**
     * 发送任务：创建任务 + 用户消息 → 状态 PLANNING → 调用 AgentPlanner。
     * 模型未配置时不会创建任务，输入内容保留。
     */
    fun sendTask() = viewModelScope.launch {
        if (_planning.value || _sendingTask.value) return@launch
        _sendingTask.value = true
        try {
            createAndPlanTask()
        } finally {
            _sendingTask.value = false
        }
    }

    private suspend fun createAndPlanTask() {
        val typedGoal = input.value.trim()
        val attachments = _pendingAttachments.value
        when {
            typedGoal.isBlank() && attachments.isEmpty() -> {
                _planError.value = "请输入任务目标"
                return
            }
            typedGoal.length > MAX_AGENT_TASK_GOAL_CHARS -> {
                _planError.value = "任务目标过长（上限 $MAX_AGENT_TASK_GOAL_CHARS 字）"
                return
            }
        }
        // 只有附件没有文字时给一句兜底目标，保证任务可创建
        val goal = typedGoal.ifBlank { ATTACHMENT_ONLY_GOAL }
        val modelId = currentModelId.value
        if (modelId <= 0 || configRepository.getModel(modelId) == null) {
            _planError.value = "请先选择可用的模型"
            return
        }
        // Agent 任务的所有工具都依赖会话绑定书籍，未绑定书籍不允许开始
        val boundBookId = _currentSessionId.value
            ?.let { sessionRepository.getSession(it)?.bookId }
            ?: filterBookId.value
        if (boundBookId == null) {
            _planError.value = "请先绑定书籍后再开始 Agent 任务"
            return
        }
        var sessionId = _currentSessionId.value
        if (sessionId == null) {
            sessionId = sessionRepository.createSession(
                bookId = boundBookId,
                modelId = modelId
            )
            filterBookId.value = null
            _currentSessionId.value = sessionId
            prefs.setAgentLastSessionId(sessionId)
        } else {
            val session = sessionRepository.getSession(sessionId)
            if (session?.bookId == null) sessionRepository.bindBook(sessionId, boundBookId)
            if (session?.modelId?.takeIf { it > 0 } == null) {
                sessionRepository.setModel(sessionId, modelId)
            }
        }
        // 附件与已选章节 / 大纲在发起任务时冻结，供规划模型使用
        val attachmentContext = withContext(Dispatchers.IO) { buildAttachmentContext(attachments) }
        // Agent 会话连续：把此前轮次的历史对话一并注入（与「前缀词管理」设置无关）
        val historyContext = runCatching {
            buildAgentHistoryText(sessionId)
        }.getOrDefault("")
        val mergedContext = attachmentContext.copy(
            text = listOf(buildSelectedContextText(), attachmentContext.text, historyContext)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .take(MAX_ATTACHMENT_CONTEXT_CHARS)
        )
        val taskId = try {
            taskRepository.createTaskWithUserMessage(
                sessionId = sessionId,
                userGoal = goal,
                modelId = modelId,
                workMode = workMode.value,
                contextSnapshot = mergedContext.text,
                attachmentsJson = attachmentStore.encode(attachments)
            ).first
        } catch (e: Throwable) {
            _planError.value = e.message ?: "创建任务失败"
            return
        }
        taskAttachmentContexts[taskId] = mergedContext
        _currentTaskId.value = taskId
        input.value = ""
        _pendingAttachments.value = emptyList()
        _planError.value = null
        _planEditError.value = null
        confirmedTaskIds.value = confirmedTaskIds.value - taskId
        if (workMode.value == AgentWorkMode.AUTO) {
            // 自动模式不生成任务规划：直接用一个隐式步骤开始思考并调用工具
            startAutoExecution(taskId = taskId, modelId = modelId, goal = goal)
        } else {
            startPlanning(
                taskId = taskId,
                modelId = modelId,
                previousSummary = "",
                attachmentContext = mergedContext
            )
        }
    }

    /**
     * Agent 会话是连续的：把本会话此前轮次的问答整理为历史上下文。
     * 与「前缀词管理」设置无关，只要是同一 Agent 会话就会进入上下文。
     */
    private suspend fun buildAgentHistoryText(sessionId: Long): String {
        val messages = sessionRepository.getMessages(sessionId)
        val lines = messages.asSequence()
            .filter {
                it.kind == AgentMessageKind.USER_INPUT ||
                    it.kind == AgentMessageKind.FINAL_ANSWER
            }
            .mapNotNull { message ->
                val text = message.content.trim()
                if (text.isBlank()) {
                    null
                } else {
                    val who = if (message.role == AgentMessageRole.USER) "用户" else "Agent"
                    "$who：$text"
                }
            }
            .toList()
        if (lines.isEmpty()) return ""
        val joined = lines.joinToString("\n")
        return if (joined.length <= AGENT_HISTORY_CHARS) {
            joined
        } else {
            "（更早的对话已省略）\n" + joined.takeLast(AGENT_HISTORY_CHARS)
        }
    }

    /**
     * 自动模式：跳过规划模型，构造一个不可见的单步骤计划并立即开始执行。
     *
     * 该步骤允许模型自由决定是否调用工具；回答本身即最终结果，不展示计划卡。
     */
    private suspend fun startAutoExecution(taskId: Long, modelId: Long, goal: String) {
        val plan = AgentPlan(
            taskTitle = goal.lineSequence().firstOrNull()?.take(40).orEmpty().ifBlank { "自动执行" },
            goalSummary = goal.take(200),
            thinkingSummary = "自动模式：直接分析目标，按需调用工具后回答",
            steps = listOf(
                AgentPlanStep(
                    stepId = "step_001",
                    title = "处理你的请求",
                    description = goal,
                    capabilityKey = "GENERATE_REPORT",
                    expectedOutput = "直接解决或回答用户的问题",
                    riskLevel = AgentRiskLevel.LOW
                )
            )
        )
        val saved = runCatching {
            // 自动模式跳过规划模型，但仍需经过 PLANNING 状态写入内置单步骤计划
            taskRepository.updateStatus(taskId, AgentTaskStatus.PLANNING)
            taskRepository.savePlanAndWaitConfirmation(
                taskId = taskId,
                request = AgentPlanSaveRequest(
                    planJson = planParser.encode(plan),
                    taskTitle = plan.taskTitle,
                    goalSummary = plan.goalSummary,
                    stepCount = 1,
                    thinkingSummary = plan.thinkingSummary,
                    assistantContent = ""
                ),
                // 自动模式不展示计划消息
                emitPlanMessage = false
            )
        }
        if (saved.isFailure) {
            _planError.value = "自动模式启动失败：${saved.exceptionOrNull()?.message ?: "未知错误"}"
            return
        }
        val confirmed = runCatching { taskRepository.markPlanConfirmed(taskId) }
            .getOrDefault(false)
        if (!confirmed) {
            _planError.value = "自动模式启动失败：无法确认执行"
            return
        }
        confirmedTaskIds.value = confirmedTaskIds.value + taskId
        taskController.requestExecution(taskId, "AUTO_MODE")
    }

    /** 已选章节 / 卷梗概 / 大纲 → 规划 Prompt 的上下文文本 */
    private fun buildSelectedContextText(): String = buildString {
        val chapterIds = _selectedChapterSummaryIds.value + _selectedChapterContentIds.value
        val selectedChapters = chapters.value.filter { it.id in chapterIds }
        if (selectedChapters.isNotEmpty()) {
            appendLine("【已选章节】")
            selectedChapters.forEach { chapter ->
                appendLine("《${chapter.title}》")
                if (chapter.id in _selectedChapterSummaryIds.value &&
                    effectiveContextMode("cs-${chapter.id}") == AgentReferenceMode.CONTEXT &&
                    chapter.summary.isNotBlank()
                ) {
                    appendLine("梗概：${chapter.summary.take(600)}")
                }
                if (chapter.id in _selectedChapterContentIds.value &&
                    effectiveContextMode("cc-${chapter.id}") == AgentReferenceMode.CONTEXT &&
                    chapter.content.isNotBlank()
                ) {
                    appendLine("正文节选：${chapter.content.take(1_200)}")
                }
            }
        }
        val selectedVolumeSummaries = activeVolumeSummaries.value
            .filter {
                it.id in _selectedVolumeSummaryIds.value &&
                    effectiveContextMode("vs-${it.id}") == AgentReferenceMode.CONTEXT
            }
        if (selectedVolumeSummaries.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("【已选卷梗概】")
            selectedVolumeSummaries.forEach { summary ->
                val title = volumes.value.firstOrNull { it.id == summary.volumeId }?.title ?: "分卷"
                appendLine("《$title》")
                if (summary.effectiveContent.isNotBlank()) {
                    appendLine(summary.effectiveContent.take(1_200))
                }
            }
        }
        val selectedOutline = outline.value.filter {
            it.id in _selectedOutlineIds.value &&
                effectiveContextMode("ol-${it.id}") == AgentReferenceMode.CONTEXT
        }
        if (selectedOutline.isNotEmpty()) {
            if (isNotEmpty()) appendLine()
            appendLine("【已选大纲】")
            selectedOutline.forEach { item ->
                appendLine("[${outlineCategoryLabel(item.category)}] ${item.title}")
                if (item.content.isNotBlank()) appendLine(item.content.take(800))
            }
        }
    }.trimEnd()

    private fun buildAttachmentContext(attachments: List<ChatAttachment>): AgentAttachmentContext {
        val text = attachments
            .filter { it.type != "image" }
            .mapNotNull { attachment ->
                attachmentStore.textContent(attachment.path)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "【附件：${attachment.name}】\n$it" }
            }
            .joinToString("\n\n")
            .take(MAX_ATTACHMENT_CONTEXT_CHARS)
        val images = attachments
            .filter { it.type == "image" }
            .mapNotNull { attachmentStore.imageDataUrl(it.path) }
        return AgentAttachmentContext(text = text, imageDataUrls = images)
    }

    /** 失败或中断后的原地重试；不创建分支，与「重新生成」区分 */
    fun retryPlanning() = viewModelScope.launch {
        if (_planning.value) return@launch
        val task = currentTask.value ?: return@launch
        val modelId = requireUsableModel() ?: return@launch
        try {
            taskRepository.restartPlanning(task.id)
        } catch (e: Throwable) {
            _planError.value = e.message ?: "无法重试规划"
            return@launch
        }
        startPlanning(
            taskId = task.id,
            modelId = modelId,
            previousSummary = "",
            attachmentContext = branchContext(task)
        )
    }

    /** 重新生成计划：创建新分支，父分支的计划与执行结果保持不变 */
    fun regeneratePlan() = viewModelScope.launch {
        if (_planning.value) return@launch
        val task = currentTask.value ?: return@launch
        if (task.status == AgentTaskStatus.RUNNING) {
            _planError.value = "任务执行中，不能重新生成计划"
            return@launch
        }
        if (writeOperations.value.any { it.unresolved }) {
            _planError.value = "请先处理待确认的写入操作"
            return@launch
        }
        val modelId = requireUsableModel() ?: return@launch
        val oldVersion = eventRepository.eventsOfType(
            task.id,
            AgentEventType.TASK_PLAN_UPDATED
        ).size
        val oldPlan = currentPlan.value?.plan
        val previousSummary = if (oldPlan != null) {
            "v$oldVersion · ${oldPlan.taskTitle} · ${oldPlan.steps.size} 步"
        } else {
            ""
        }
        val answers = taskRepository.answeredClarifications(task.id).map { it.answer }
        createBranchAndPlan(
            parent = task,
            userGoal = task.userGoal,
            branchSource = AgentBranchSource.PLAN_REGENERATE,
            modelId = modelId,
            previousSummary = previousSummary,
            clarificationAnswers = answers
        )
    }

    /** 对某条用户提问重新生成：创建新分支并从该提问重新规划 */
    fun regenerateUserMessage(messageId: Long) = viewModelScope.launch {
        if (_planning.value) return@launch
        val task = currentTask.value ?: return@launch
        if (task.status == AgentTaskStatus.RUNNING) {
            _planError.value = "任务执行中，不能重新生成"
            return@launch
        }
        val message = currentTaskUserMessage(messageId) ?: return@launch
        val modelId = requireUsableModel() ?: return@launch
        createBranchAndPlan(
            parent = task,
            userGoal = message.content,
            branchSource = AgentBranchSource.USER_EDIT,
            modelId = modelId,
            previousSummary = "",
            contextOverride = contextFromMessage(message),
            attachmentsJsonOverride = message.attachments
        )
    }

    /** 编辑用户提问并重新生成：原提问与计划保留在被编辑任务中，新文本进入子分支 */
    fun editUserMessageAndRegenerate(
        messageId: Long,
        newGoal: String,
        onCreated: () -> Unit = {}
    ) = viewModelScope.launch {
        if (_planning.value) return@launch
        val task = currentTask.value ?: return@launch
        if (task.status == AgentTaskStatus.RUNNING) {
            _planError.value = "任务执行中，不能编辑提问"
            return@launch
        }
        val goal = newGoal.trim()
        when {
            goal.isBlank() -> {
                _planError.value = "任务目标不能为空"
                return@launch
            }
            goal.length > MAX_AGENT_TASK_GOAL_CHARS -> {
                _planError.value = "任务目标过长（上限 $MAX_AGENT_TASK_GOAL_CHARS 字）"
                return@launch
            }
        }
        val message = currentTaskUserMessage(messageId) ?: return@launch
        val modelId = requireUsableModel() ?: return@launch
        val childId = createBranchAndPlan(
            parent = task,
            userGoal = goal,
            branchSource = AgentBranchSource.USER_EDIT,
            modelId = modelId,
            previousSummary = "",
            contextOverride = contextFromMessage(message),
            attachmentsJsonOverride = message.attachments
        )
        if (childId != null) onCreated()
    }

    /**
     * 从输入框提交编辑：文本与当前待发送附件一起进入新分支。
     *
     * 编辑入口把原消息回显到输入框，因此这里需要同时收集附件（图片 / 文件 / 链接）。
     */
    fun submitEditedMessage(messageId: Long, onDone: () -> Unit = {}) = viewModelScope.launch {
        if (_planning.value) return@launch
        val task = currentTask.value ?: return@launch
        if (task.status == AgentTaskStatus.RUNNING) {
            _planError.value = "任务执行中，不能编辑提问"
            return@launch
        }
        val goal = input.value.trim()
        when {
            goal.isBlank() -> {
                _planError.value = "任务目标不能为空"
                return@launch
            }
            goal.length > MAX_AGENT_TASK_GOAL_CHARS -> {
                _planError.value = "任务目标过长（上限 $MAX_AGENT_TASK_GOAL_CHARS 字）"
                return@launch
            }
        }
        val message = currentTaskUserMessage(messageId) ?: return@launch
        val modelId = requireUsableModel() ?: return@launch
        val attachments = _pendingAttachments.value
        val attachmentContext = withContext(Dispatchers.IO) { buildAttachmentContext(attachments) }
        val attachmentsJson = if (attachments.isEmpty()) {
            message.attachments
        } else {
            attachmentStore.encode(attachments)
        }
        val baseContext = contextFromMessage(message)
        val context = baseContext.copy(
            text = listOf(baseContext.text, attachmentContext.text)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .take(MAX_ATTACHMENT_CONTEXT_CHARS)
        )
        val childId = createBranchAndPlan(
            parent = task,
            userGoal = goal,
            branchSource = AgentBranchSource.USER_EDIT,
            modelId = modelId,
            previousSummary = "",
            contextOverride = context,
            attachmentsJsonOverride = attachmentsJson
        )
        if (childId != null) {
            input.value = ""
            _pendingAttachments.value = emptyList()
            _planError.value = null
            onDone()
        }
    }

    /** 用户手动修改计划：直接覆盖当前任务的计划，不新建分支；保存不等于确认执行 */
    fun saveEditedPlan(plan: AgentPlan, onSuccess: () -> Unit = {}) = viewModelScope.launch {
        val task = currentTask.value ?: return@launch
        val validation = planValidator.validate(plan)
        if (!validation.isValid) {
            _planEditError.value = validation.errors.take(3).joinToString("；")
            return@launch
        }
        if (plan.needsClarification) {
            _planEditError.value = "计划仍要求补充信息，请先回答 Agent 的问题"
            return@launch
        }
        try {
            taskRepository.updatePlan(task.id, planParser.encode(plan))
            // 计划已变化，需要用户重新确认后才能执行
            confirmedTaskIds.value = confirmedTaskIds.value - task.id
            _planEditError.value = null
            onSuccess()
        } catch (e: Throwable) {
            _planEditError.value = e.message ?: "保存计划失败"
        }
    }

    // ---------------- 计划书库 ----------------

    private val _planLibrary = MutableStateFlow<List<AgentPlanLibraryItem>>(emptyList())
    val planLibrary: StateFlow<List<AgentPlanLibraryItem>> = _planLibrary.asStateFlow()

    private val _planLibraryNotice = MutableStateFlow<String?>(null)
    val planLibraryNotice: StateFlow<String?> = _planLibraryNotice.asStateFlow()

    private var planLibraryBookId: Long? = null

    /** 加载当前书籍下所有已生成的计划书 */
    fun loadPlanLibrary(bookId: Long) = viewModelScope.launch {
        planLibraryBookId = bookId
        _planLibrary.value = runCatching {
            taskRepository.tasksWithPlanOfBook(bookId).mapNotNull { task ->
                val parsed = planParser.parse(task.planJson)
                if (parsed is AgentPlanParseResult.Success) {
                    AgentPlanLibraryItem(task, parsed.plan)
                } else {
                    null
                }
            }
        }.getOrDefault(emptyList())
    }

    fun clearPlanLibraryNotice() {
        _planLibraryNotice.value = null
    }

    /** 在计划书库中修改某个任务的计划：直接更新该任务，不新建分支 */
    fun saveLibraryPlan(task: AgentTask, plan: AgentPlan) = viewModelScope.launch {
        if (AgentTaskStatus.isTerminal(task.status)) {
            _planLibraryNotice.value = "任务已结束，计划仅供查看"
            return@launch
        }
        val validation = planValidator.validate(plan)
        if (!validation.isValid) {
            _planLibraryNotice.value = validation.errors.take(3).joinToString("；")
            return@launch
        }
        if (plan.needsClarification) {
            _planLibraryNotice.value = "计划仍要求补充信息，请先完善计划"
            return@launch
        }
        runCatching {
            taskRepository.updatePlan(task.id, planParser.encode(plan))
            taskRepository.renameTask(task.id, plan.taskTitle)
        }.onSuccess {
            _planLibraryNotice.value = "计划已保存"
            planLibraryBookId?.let { loadPlanLibrary(it) }
        }.onFailure {
            _planLibraryNotice.value = it.message ?: "保存计划失败"
        }
    }

    /** 把计划书加入当前会话的参考资料 */
    fun addPlanToReferences(task: AgentTask) {
        addPlansToReferences(listOf(task))
    }

    /** 批量把计划书加入当前会话的参考资料 */
    fun addPlansToReferences(tasks: List<AgentTask>) {
        if (tasks.isEmpty()) return
        viewModelScope.launch {
            val session = currentSession.value ?: return@launch
            var added = 0
            tasks.forEach { task ->
                runCatching {
                    container.agentReferenceRepository.addReference(
                        sessionId = session.id,
                        sourceType = AgentReferenceSourceType.AGENT_TASK_PLAN,
                        sourceId = task.id,
                        bookId = session.bookId,
                        titleSnapshot = "任务计划：${task.title}",
                        referenceMode = AgentReferenceMode.CONTEXT
                    )
                }.onSuccess { added++ }
            }
            _planLibraryNotice.value = if (added > 0) {
                "已添加 $added 份计划到参考资料"
            } else {
                "添加到参考资料失败"
            }
        }
    }

    /** 记录用户已确认计划；不授权执行 */
    fun confirmPlan() = viewModelScope.launch {
        val taskId = _currentTaskId.value ?: return@launch
        val plan = currentPlan.value?.plan
        if (plan == null) {
            _planError.value = "当前没有可确认的计划"
            return@launch
        }
        if (plan.needsClarification) {
            _planError.value = "请先回答 Agent 的补充问题"
            return@launch
        }
        val ok = runCatching { taskRepository.markPlanConfirmed(taskId) }.getOrDefault(false)
        if (ok) {
            confirmedTaskIds.value = confirmedTaskIds.value + taskId
            val task = taskRepository.getTask(taskId)
            if (task?.workMode == AgentWorkMode.AUTO &&
                !writeOperations.value.any { it.unresolved }
            ) {
                taskController.requestExecution(taskId, "AUTO_MODE_CONFIRM")
            }
        }
    }

    /** 提交澄清回答：答案落库后在同一任务内继续规划 */
    fun answerClarification(answer: String) = viewModelScope.launch {
        if (_planning.value) return@launch
        val task = currentTask.value ?: return@launch
        val pending = taskRepository.pendingClarification(task.id) ?: return@launch
        val text = answer.trim()
        if (text.isBlank()) {
            _planError.value = "请输入补充信息"
            return@launch
        }
        val modelId = requireUsableModel() ?: return@launch
        val attachmentContext = branchContext(task)
        try {
            taskRepository.answerClarification(pending.id, text) ?: return@launch
            // 补充信息不生成用户对话消息，改由思维链展示；执行时会从澄清记录注入上下文
            // 执行过程中由 ask_user 发起的提问：回答后继续同一任务，不重新规划
            if (task.status == AgentTaskStatus.WAITING_CONFIRMATION) {
                val resumed = taskController.continueAfterWriteDecision(task.id)
                if (resumed) {
                    _executingTaskId.value = task.id
                } else {
                    _planError.value = "回答已保存，但任务当前不能继续执行"
                }
                return@launch
            }
            taskRepository.restartPlanning(task.id)
        } catch (e: Throwable) {
            _planError.value = e.message ?: "提交补充信息失败"
            return@launch
        }
        val answers = taskRepository.answeredClarifications(task.id).map { it.answer }
        startPlanning(
            taskId = task.id,
            modelId = modelId,
            previousSummary = "",
            attachmentContext = attachmentContext,
            clarificationAnswers = answers,
            previousPlanJson = task.planJson
        )
    }

    private suspend fun requireUsableModel(): Long? {
        val modelId = currentModelId.value
        if (modelId <= 0 || configRepository.getModel(modelId) == null) {
            _planError.value = "请先选择可用的模型"
            return null
        }
        return modelId
    }

    private suspend fun currentTaskUserMessage(messageId: Long): AgentMessage? {
        val task = currentTask.value ?: return null
        return sessionRepository.getMessages(task.sessionId)
            .firstOrNull { it.id == messageId }
            ?.takeIf { it.taskId == task.id && it.kind == AgentMessageKind.USER_INPUT }
    }

    private suspend fun branchContext(task: AgentTask): AgentAttachmentContext {
        taskAttachmentContexts[task.id]?.let { return it }
        val message = taskRepository.lastUserMessage(task.id) ?: return AgentAttachmentContext()
        return contextFromMessage(message)
    }

    private fun contextFromMessage(message: AgentMessage): AgentAttachmentContext {
        val images = attachmentStore.decode(message.attachments)
            .filter { it.type == "image" }
            .mapNotNull { attachmentStore.imageDataUrl(it.path) }
        return AgentAttachmentContext(
            text = message.contextSnapshot,
            imageDataUrls = images
        )
    }

    /** 基于父任务创建分支并开始规划；成功后切换到新分支 */
    private suspend fun createBranchAndPlan(
        parent: AgentTask,
        userGoal: String,
        branchSource: String,
        modelId: Long,
        previousSummary: String,
        clarificationAnswers: List<String> = emptyList(),
        contextOverride: AgentAttachmentContext? = null,
        attachmentsJsonOverride: String? = null
    ): Long? {
        val context = contextOverride ?: branchContext(parent)
        val attachmentsJson = attachmentsJsonOverride
            ?: taskRepository.lastUserMessage(parent.id)?.attachments.orEmpty()
        val childId = try {
            taskRepository.createBranchTask(
                parentTaskId = parent.id,
                userGoal = userGoal,
                branchSource = branchSource,
                contextSnapshot = context.text,
                attachmentsJson = attachmentsJson,
                modelId = modelId
            ).first
        } catch (e: Throwable) {
            _planError.value = e.message ?: "创建分支失败"
            return null
        }
        taskAttachmentContexts[childId] = context
        _currentTaskId.value = childId
        _planError.value = null
        _planEditError.value = null
        confirmedTaskIds.value = confirmedTaskIds.value - childId
        // 自动模式本就不生成计划：编辑 / 重新生成提问时也应直接重新执行并生成回复，
        // 不能走规划流程，否则会自动模式里凭空多出一份计划。
        if (parent.workMode == AgentWorkMode.AUTO) {
            startAutoExecution(taskId = childId, modelId = modelId, goal = userGoal)
        } else {
            startPlanning(
                taskId = childId,
                modelId = modelId,
                previousSummary = previousSummary,
                attachmentContext = context,
                clarificationAnswers = clarificationAnswers
            )
        }
        return childId
    }

    /** 停止当前规划请求，并把任务标记为 CANCELLED */
    fun cancelPlanning() {
        val taskId = _planningTaskId.value ?: return
        planJob?.cancel()
        planJob = null
        _planning.value = false
        _planningTaskId.value = null
        viewModelScope.launch {
            runCatching { taskRepository.cancelTask(taskId) }
        }
    }

    /** 取消任务；执行中的任务先写持久化取消标记，再由唯一执行器落为 CANCELLED */
    fun cancelTask(taskId: Long) = viewModelScope.launch {
        if (_planningTaskId.value == taskId) {
            cancelPlanning()
            return@launch
        }
        runCatching { taskRepository.cancelPendingClarification(taskId) }
        writeOperationsRepository.unresolvedOfTask(taskId).forEach {
            writeConfirmationGate.signal(it.id, false)
        }
        val ok = runCatching { taskController.cancel(taskId) }
            .onFailure { _planError.value = it.message ?: "取消失败" }
            .getOrDefault(false)
        if (!ok) _planError.value = "任务当前状态不能取消"
    }

    /** 删除尚未执行的任务；规划中的任务必须先停止 */
    fun deleteTask(taskId: Long) = viewModelScope.launch {
        if (_planningTaskId.value == taskId) {
            _planError.value = "请先停止规划再删除任务"
            return@launch
        }
        val ok = runCatching { taskRepository.deleteTask(taskId) }
            .onFailure { _planError.value = it.message ?: "删除失败" }
            .getOrDefault(false)
        if (ok && _currentTaskId.value == taskId) {
            _currentTaskId.value = null
        }
    }

    // ---------------- 执行与受控写入 ----------------

    /** 用户单独点击「开始执行」；F3 的计划确认不等于执行授权，也不等于写入授权 */
    fun startReadOnlyExecution() = viewModelScope.launch {
        val taskId = _currentTaskId.value ?: return@launch
        val plan = currentPlan.value
        if (plan == null || !plan.confirmed) {
            _executionError.value = "请先确认计划，再开始执行"
            return@launch
        }
        if (plan.plan.needsClarification) {
            _executionError.value = "请先回答 Agent 的补充问题"
            return@launch
        }
        if (taskRepository.pendingClarification(taskId) != null) {
            _executionError.value = "请先回答 Agent 的补充问题"
            return@launch
        }
        if (writeOperations.value.any { it.unresolved }) {
            _executionError.value = "请先处理待确认的写入操作"
            return@launch
        }
        val blockedSteps = plan.plan.steps.filterNot {
            com.lianwenqi.app.agent.execution.AgentExecutionPolicy
                .isCapabilityExecutable(it.capabilityKey)
        }
        if (blockedSteps.isNotEmpty()) {
            _executionError.value = "存在当前阶段不可执行的步骤：" +
                blockedSteps.joinToString("、") { it.title } +
                "。请先修改计划或重新生成。"
            return@launch
        }
        _executionError.value = null
        // 清理历史遗留的暂停标记，避免 Worker 启动后立即进入暂停
        taskRepository.setPauseRequested(taskId, false)
        if (taskController.requestExecution(taskId, "UI_START")) {
            // 乐观显示；真正的执行状态由 Room 中的租约与任务状态决定
            _executingTaskId.value = taskId
        } else {
            _executionError.value = "任务当前状态不能开始执行"
        }
    }

    /** 暂停当前任务；执行器在安全边界保存游标后进入 PAUSED */
    fun pauseTask(taskId: Long) = viewModelScope.launch {
        _executionError.value = null
        if (!taskController.pause(taskId)) {
            _executionError.value = "任务当前状态不能暂停"
        }
    }

    /** 恢复已暂停的任务 */
    fun resumeTask(taskId: Long) = viewModelScope.launch {
        _executionError.value = null
        if (!taskController.resume(taskId)) {
            _executionError.value = "任务当前状态不能恢复"
        }
    }

    /** 取消当前执行；未执行的写入提议同步失效 */
    fun cancelExecution() = viewModelScope.launch {
        cancelRunningTask(reportError = true)
    }

    /**
     * 切换会话 / 新建会话时的“尽力取消”：当前任务不在可取消状态时不提示错误。
     */
    private suspend fun cancelRunningTask(reportError: Boolean) {
        val taskId = _currentTaskId.value ?: return
        writeOperationsRepository.unresolvedOfTask(taskId).forEach {
            writeConfirmationGate.signal(it.id, false)
        }
        if (!taskController.cancel(taskId) && reportError) {
            _executionError.value = "任务当前状态不能取消"
        }
    }

    fun clearExecutionError() {
        _executionError.value = null
    }

    /** 从失败节点重试：重开该节点并重新入队，同参数工具调用计数随新一轮执行重置 */
    fun retryFailedNode(nodeId: Long) = viewModelScope.launch {
        val taskId = _currentTaskId.value ?: return@launch
        _executionError.value = null
        if (!taskController.retryFromNode(taskId, nodeId)) {
            _executionError.value = "当前节点无法重试"
            return@launch
        }
        _executingTaskId.value = taskId
    }

    /** 跳过失败节点并继续：该节点标记为已跳过，后续步骤照常执行 */
    fun skipFailedNode(nodeId: Long) = viewModelScope.launch {
        val taskId = _currentTaskId.value ?: return@launch
        _executionError.value = null
        if (!taskController.skipAndContinue(taskId, nodeId)) {
            _executionError.value = "当前节点无法跳过"
            return@launch
        }
        _executingTaskId.value = taskId
    }

    /** 结束失败任务：保留已完成步骤与记录，任务落为已取消 */
    fun finishFailedTask() = viewModelScope.launch {
        val taskId = _currentTaskId.value ?: return@launch
        _executionError.value = null
        if (!taskController.finishFailedTask(taskId)) {
            _executionError.value = "任务当前状态不能结束"
        }
    }

    /**
     * 等待中的节点继续执行。
     *
     * 仍有待确认写入或待回答提问时必须先处理，否则任务会立刻回到等待状态。
     */
    fun continueWaitingTask() = viewModelScope.launch {
        val taskId = _currentTaskId.value ?: return@launch
        _executionError.value = null
        if (writeOperationsRepository.unresolvedOfTask(taskId).isNotEmpty()) {
            _executionError.value = "请先处理完待确认的写入"
            return@launch
        }
        if (taskRepository.pendingClarification(taskId) != null) {
            _executionError.value = "请先回答 Agent 的问题"
            return@launch
        }
        if (!taskController.resume(taskId) &&
            !taskController.requestExecution(taskId, "USER_CONTINUE")
        ) {
            _executionError.value = "任务当前状态不能继续"
            return@launch
        }
        _executingTaskId.value = taskId
    }

    /** 结束等待中的任务：保留已完成步骤，任务落为已取消 */
    fun endWaitingTask() = viewModelScope.launch {
        val taskId = _currentTaskId.value ?: return@launch
        _executionError.value = null
        // 用户主动结束：任务落为 CANCELLED，并记录「任务完成」的结束原因
        if (!taskController.cancel(taskId, AgentTask.USER_ENDED_REASON)) {
            _executionError.value = "任务当前状态不能结束"
        }
    }

    fun setWritePolicy(policy: String) = viewModelScope.launch {
        if (!AgentWritePermissionPolicy.isValid(policy)) return@launch
        prefs.setAgentWritePolicy(policy)
    }

    fun clearWriteNotice() {
        _writeNotice.value = null
    }

    /** 用户批准一项写入；执行成功后通知等待中的引擎继续 */
    fun approveWrite(operationId: Long) = viewModelScope.launch {
        val operation = runCatching { writeExecutionService.approveAndExecute(operationId) }
            .getOrElse {
                _writeNotice.value = it.message ?: "批准写入失败"
                return@launch
            }
        writeConfirmationGate.signal(operationId, true)
        when (operation.executionStatus) {
            AgentWriteOperationStatus.SUCCEEDED ->
                _writeNotice.value = "写入成功：${operation.operationSummary}"

            AgentWriteOperationStatus.CONFLICT ->
                _writeNotice.value = operation.errorMessage.ifBlank { "目标数据已变化，请重新预览" }

            AgentWriteOperationStatus.FAILED ->
                _writeNotice.value = operation.errorMessage.ifBlank { "写入失败" }

            AgentWriteOperationStatus.REJECTED ->
                _writeNotice.value = "该操作已被拒绝"

            else -> Unit
        }
        resumeAfterWriteDecision(operation.taskId)
    }

    /** 用户拒绝一项写入；拒绝结果会返回模型 */
    fun rejectWrite(operationId: Long) = viewModelScope.launch {
        val operation = runCatching { writeExecutionService.reject(operationId) }
            .getOrElse {
                _writeNotice.value = it.message ?: "拒绝写入失败"
                return@launch
            }
        writeConfirmationGate.signal(operationId, false)
        _writeNotice.value = "已拒绝：${operation.operationSummary}"
        resumeAfterWriteDecision(operation.taskId)
    }

    /** BATCH_CONFIRM：只批量批准一般写入，高风险操作必须单独确认 */
    fun approveAllNormalWrites() = viewModelScope.launch {
        val pending = writeOperations.value.filter {
            it.riskLevel == AgentWriteRiskLevel.NORMAL_WRITE &&
                (it.executionStatus == AgentWriteOperationStatus.WAITING_CONFIRMATION ||
                    it.executionStatus == AgentWriteOperationStatus.PROPOSED)
        }
        if (pending.isEmpty()) {
            _writeNotice.value = "没有可批量确认的一般写入"
            return@launch
        }
        var succeeded = 0
        var failed = 0
        pending.forEach { operation ->
            val result = runCatching {
                writeExecutionService.approveAndExecute(operation.id)
            }.getOrNull()
            writeConfirmationGate.signal(operation.id, true)
            if (result?.executionStatus == AgentWriteOperationStatus.SUCCEEDED) {
                succeeded++
            } else {
                failed++
            }
        }
        _writeNotice.value = buildString {
            append("批量确认完成：成功 $succeeded 条")
            if (failed > 0) append("，失败或冲突 $failed 条")
        }
        resumeAfterWriteDecision(pending.first().taskId)
    }

    /** 撤销已成功写入；数据已被再次修改时拒绝覆盖 */
    fun rollbackWrite(operationId: Long) = viewModelScope.launch {
        val operation = runCatching { writeExecutionService.rollback(operationId) }
            .getOrElse {
                _writeNotice.value = it.message ?: "撤销失败"
                return@launch
            }
        _writeNotice.value = when (operation.executionStatus) {
            AgentWriteOperationStatus.ROLLED_BACK ->
                "已撤销：${operation.operationSummary}"

            else -> operation.rollbackMessage.ifBlank { "撤销未完成" }
        }
    }

    /**
     * 用户处理完待确认写入后，由应用级调度器继续任务。
     * 仍有未解决操作时不继续；恢复后仍不自动确认任何新的写入。
     */
    private suspend fun resumeAfterWriteDecision(taskId: Long) {
        if (writeOperationsRepository.hasUnresolved(taskId)) return
        taskController.continueAfterWriteDecision(taskId)
    }

    // ---------------- F7：任务详情 ----------------

    fun openTaskDetails(taskId: Long) {
        _inspectedTaskId.value = taskId
    }

    fun closeTaskDetails() {
        _inspectedTaskId.value = null
    }

    fun openInspectedTaskInChat() {
        val taskId = _inspectedTaskId.value ?: return
        openTask(taskId)
        _inspectedTaskId.value = null
    }

    private fun hasActiveLease(task: AgentTask): Boolean {
        val now = System.currentTimeMillis()
        val heartbeatFresh = (task.lastHeartbeatAt ?: 0L) >
            (now - com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository.STALE_HEARTBEAT_MS)
        return task.executionOwner.isNotBlank() &&
            (task.executionLeaseUntil ?: 0L) > now &&
            heartbeatFresh
    }

    // ---------------- 规划执行 ----------------

    private fun startPlanning(
        taskId: Long,
        modelId: Long,
        previousSummary: String,
        attachmentContext: AgentAttachmentContext = AgentAttachmentContext(),
        clarificationAnswers: List<String> = emptyList(),
        previousPlanJson: String = ""
    ) {
        if (planJob?.isActive == true) return
        _planning.value = true
        _planningTaskId.value = taskId
        _planError.value = null
        planJob = viewModelScope.launch {
            var sessionId = 0L
            try {
                val task = taskRepository.getTask(taskId) ?: error("任务不存在")
                sessionId = task.sessionId
                if (task.status == AgentTaskStatus.CREATED) {
                    taskRepository.updateStatus(taskId, AgentTaskStatus.PLANNING)
                }
                val session = sessionRepository.getSession(sessionId)
                val book = session?.bookId?.let { bookRepository.getBook(it) }
                val result = planner.plan(
                    modelId = modelId,
                    goal = task.userGoal,
                    sessionTitle = session?.title.orEmpty(),
                    book = book,
                    attachmentText = attachmentContext.text,
                    imageDataUrls = attachmentContext.imageDataUrls,
                    clarificationAnswers = clarificationAnswers,
                    previousPlanJson = previousPlanJson
                )
                when (result) {
                    is AgentPlanningResult.Success -> {
                        val log = encodePromptLog(
                            sessionId = sessionId,
                            taskId = taskId,
                            trace = result.trace,
                            thinkingSummary = result.plan.thinkingSummary,
                            error = ""
                        )
                        val needsClarification = result.plan.needsClarification &&
                            result.plan.clarificationQuestions.isNotEmpty()
                        taskRepository.savePlanAndWaitConfirmation(
                            taskId = taskId,
                            request = AgentPlanSaveRequest(
                                planJson = result.normalizedJson,
                                taskTitle = result.plan.taskTitle,
                                goalSummary = result.plan.goalSummary,
                                stepCount = result.plan.steps.size,
                                thinkingSummary = result.plan.thinkingSummary,
                                previousSummary = previousSummary,
                                assistantContent = "已生成执行计划：${result.plan.taskTitle}",
                                promptTokens = result.trace.usage.promptTokens,
                                completionTokens = result.trace.usage.completionTokens,
                                cachedTokens = result.trace.usage.cachedTokens,
                                promptLog = log
                            ),
                            emitPlanMessage = !needsClarification
                        )
                        if (needsClarification) {
                            taskRepository.createClarification(
                                taskId = taskId,
                                question = result.plan.clarificationQuestions.joinToString("\n"),
                                options = result.plan.clarificationOptions
                            )
                        } else {
                            taskRepository.cancelPendingClarification(taskId)
                            maybeAutoStart(taskId)
                        }
                    }
                    is AgentPlanningResult.Failure -> {
                        val log = encodePromptLog(
                            sessionId = sessionId,
                            taskId = taskId,
                            trace = result.trace,
                            thinkingSummary = "",
                            error = result.message
                        )
                        taskRepository.failPlanning(
                            taskId = taskId,
                            errorMessage = result.message,
                            promptLog = log,
                            promptTokens = result.trace.usage.promptTokens,
                            completionTokens = result.trace.usage.completionTokens,
                            cachedTokens = result.trace.usage.cachedTokens
                        )
                        _planError.value = result.message
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val message = e.message ?: "规划失败"
                // 任务可能已被用户取消 / 删除；失败写入失败时不覆盖状态
                runCatching { taskRepository.failPlanning(taskId, message) }
                _planError.value = message
            } finally {
                if (_planningTaskId.value == taskId) {
                    _planning.value = false
                    _planningTaskId.value = null
                }
                planJob = null
            }
        }
    }

    /**
     * 兼容中断的澄清流程：已有回答但计划仍是澄清草稿时继续规划；
     * 没有回答时按草稿里的问题重新建立澄清请求。
     */
    private suspend fun maybeResumeClarificationPlanning(taskId: Long) {
        val task = taskRepository.getTask(taskId) ?: return
        if (task.status != AgentTaskStatus.WAITING_CONFIRMATION) return
        if (taskRepository.pendingClarification(taskId) != null) return
        val plan = (planParser.parse(task.planJson) as? AgentPlanParseResult.Success)?.plan ?: return
        if (!plan.needsClarification) return
        val answers = taskRepository.answeredClarifications(taskId).map { it.answer }
        if (answers.isEmpty()) {
            if (plan.clarificationQuestions.isNotEmpty()) {
                taskRepository.createClarification(
                    taskId = taskId,
                    question = plan.clarificationQuestions.joinToString("\n"),
                    options = plan.clarificationOptions
                )
            }
            return
        }
        val modelId = currentModelId.value
        if (modelId <= 0 || configRepository.getModel(modelId) == null) return
        try {
            taskRepository.restartPlanning(taskId)
        } catch (e: Throwable) {
            return
        }
        startPlanning(
            taskId = taskId,
            modelId = modelId,
            previousSummary = "",
            attachmentContext = branchContext(task),
            clarificationAnswers = answers,
            previousPlanJson = task.planJson
        )
    }

    /**
     * 自动模式：计划可用且没有待澄清问题时，自动确认计划并开始执行。
     * 写入仍由现有权限策略与确认门控制；计划编辑产生的分支必须等待用户确认。
     */
    private suspend fun maybeAutoStart(taskId: Long) {
        val task = taskRepository.getTask(taskId) ?: return
        if (task.workMode != AgentWorkMode.AUTO) return
        if (task.branchSource == AgentBranchSource.PLAN_EDIT) return
        if (task.status != AgentTaskStatus.WAITING_CONFIRMATION) return
        if (task.planJson.isBlank() || task.planJson == "{}") return
        if (taskRepository.pendingClarification(taskId) != null) return
        val plan = (planParser.parse(task.planJson) as? AgentPlanParseResult.Success)?.plan ?: return
        if (plan.needsClarification) return
        val alreadyConfirmed = eventRepository.eventsOfType(
            taskId,
            AgentEventType.CONFIRMATION_RECEIVED
        ).isNotEmpty()
        if (!alreadyConfirmed) {
            val confirmed = runCatching { taskRepository.markPlanConfirmed(taskId) }
                .getOrDefault(false)
            if (!confirmed) return
            confirmedTaskIds.value = confirmedTaskIds.value + taskId
        }
        if (writeOperationsRepository.hasUnresolved(taskId)) return
        taskController.requestExecution(taskId, "AUTO_MODE")
    }

    private suspend fun loadConfirmation(taskId: Long) {
        val confirmed = eventRepository.eventsOfType(
            taskId,
            AgentEventType.CONFIRMATION_RECEIVED
        ).isNotEmpty()
        confirmedTaskIds.value = if (confirmed) {
            confirmedTaskIds.value + taskId
        } else {
            confirmedTaskIds.value - taskId
        }
        // 兼容旧数据：仍有待回答的补充信息时，不展示此前直接生成的计划消息
        if (taskRepository.pendingClarification(taskId) != null) {
            taskRepository.clearPlanMessages(taskId)
        }
    }

    private fun cancelPlanJobQuietly() {
        planJob?.cancel()
        planJob = null
        _planning.value = false
        _planningTaskId.value = null
    }

    private fun encodePromptLog(
        sessionId: Long,
        taskId: Long,
        trace: AgentPlanningTrace,
        thinkingSummary: String,
        error: String
    ): String = AgentPromptLog(
        sessionId = sessionId,
        taskId = taskId,
        modelId = trace.modelId,
        modelName = trace.modelName,
        providerName = trace.providerName,
        apiType = trace.apiType,
        temperature = trace.temperature,
        systemPromptHash = PromptDiagnostics.hash(trace.systemPrompt),
        userPromptHash = PromptDiagnostics.hash(trace.userPrompt),
        userPrompt = trace.userPrompt.take(4_000),
        rawResponse = trace.rawResponse.take(2_000),
        thinkingSummary = thinkingSummary.take(1_000),
        error = error.take(500),
        promptTokens = trace.usage.promptTokens,
        completionTokens = trace.usage.completionTokens,
        cachedTokens = trace.usage.cachedTokens,
        latencyMs = trace.latencyMs
    ).encode()

    companion object {
        /** Agent 任务目标上限；完整输入在此范围内原样落库 */
        const val MAX_AGENT_TASK_GOAL_CHARS = 20_000

        /** 只添加附件、没有输入文字时使用的兜底任务目标 */
        private const val ATTACHMENT_ONLY_GOAL = "请根据附件内容分析并生成执行计划"

        /** 附件正文注入规划 Prompt 的上限，避免把任务目标挤出上下文 */
        private const val MAX_ATTACHMENT_CONTEXT_CHARS = 6_000
        /** 规划阶段注入的历史对话字符上限（执行阶段另有独立上限） */
        private const val AGENT_HISTORY_CHARS = 4_000
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id
