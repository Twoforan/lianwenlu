package com.lianwenqi.app.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentClarification
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.ai.CacheRateCalculator
import com.lianwenqi.app.ai.describe
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import com.lianwenqi.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.planning.AgentPromptLog
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWorkMode
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentRiskLevel
import com.lianwenqi.app.data.ChatAttachment
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.ui.components.ChatToolbar
import com.lianwenqi.app.ui.components.ChatToolbarAction
import com.lianwenqi.app.ui.components.ModelSetupReminder
import com.lianwenqi.app.ui.screens.AttachmentPreviewDialog
import com.lianwenqi.app.ui.screens.BookPickerDialog
import com.lianwenqi.app.ui.screens.ChatComposer
import com.lianwenqi.app.ui.screens.ChapterPickerDialog
import com.lianwenqi.app.ui.screens.OutlinePicker
import com.lianwenqi.app.ui.screens.TEXT_FILE_MIME_TYPES
import com.lianwenqi.app.ui.screens.attachmentIcon
import com.lianwenqi.app.ui.screens.attachmentLabel
import com.lianwenqi.app.ui.screens.displayNameOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 自动化对话：直接执行任务，保留历史计划书及受控写入记录。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    onModeChange: (String) -> Unit
) {
    val vm = appViewModel(key = "agent-main") { AgentChatViewModel(it) }
    val sessions by vm.sessions.collectAsState()
    val currentSession by vm.currentSession.collectAsState()
    val messages by vm.messages.collectAsState()
    val planPublished by vm.planPublished.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val currentTask by vm.currentTask.collectAsState()
    val currentTaskId by vm.currentTaskId.collectAsState()
    val currentPlan by vm.currentPlan.collectAsState()
    val historicalArtifacts by vm.historicalArtifacts.collectAsState()
    val agentTaskModelId by vm.agentTaskModelId.collectAsState()
    val agentContentModelId by vm.agentContentModelId.collectAsState()
    val planning by vm.planning.collectAsState()
    val sendingTask by vm.sendingTask.collectAsState()
    val planningTaskId by vm.planningTaskId.collectAsState()
    val planError by vm.planError.collectAsState()
    val planEditError by vm.planEditError.collectAsState()
    val executionNodes by vm.executionNodes.collectAsState()
    val toolExecutions by vm.toolExecutions.collectAsState()
    val answeredClarifications by vm.answeredClarifications.collectAsState()
    val executingTaskId by vm.executingTaskId.collectAsState()
    val executionError by vm.executionError.collectAsState()
    val writeOperations by vm.writeOperations.collectAsState()
    val writePolicy by vm.writePolicy.collectAsState()
    val writeNotice by vm.writeNotice.collectAsState()
    val pendingClarification by vm.pendingClarification.collectAsState()
    val input by vm.input.collectAsState()
    val collapsedMessageIds by vm.collapsedMessageIds.collectAsState()
    val thinkingExpanded by vm.thinkingExpanded.collectAsState()
    val books by vm.books.collectAsState()
    val models by vm.models.collectAsState()
    val providers by vm.providers.collectAsState()
    val modelId by vm.currentModelId.collectAsState()
    val search by vm.searchQuery.collectAsState()
    val filterBookId by vm.filterBookId.collectAsState()
    val currentSessionId by vm.currentSessionId.collectAsState()
    val inspectedTask by vm.inspectedTask.collectAsState()
    val inspectedSession by vm.inspectedSession.collectAsState()
    val inspectedNodes by vm.inspectedNodes.collectAsState()
    val inspectedWrites by vm.inspectedWrites.collectAsState()
    val inspectedRecoveryLogs by vm.inspectedRecoveryLogs.collectAsState()
    val inspectedModelRequests by vm.inspectedModelRequests.collectAsState()

    val prompts by vm.prompts.collectAsState()
    val pendingAttachments by vm.pendingAttachments.collectAsState()
    val groups by vm.groups.collectAsState()
    val styleLibrary by vm.styleLibrary.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val outline by vm.outline.collectAsState()
    val activeVolumeSummaries by vm.activeVolumeSummaries.collectAsState()
    val selectedChapterSummaryIds by vm.selectedChapterSummaryIds.collectAsState()
    val selectedChapterContentIds by vm.selectedChapterContentIds.collectAsState()
    val selectedVolumeSummaryIds by vm.selectedVolumeSummaryIds.collectAsState()
    val selectedOutlineIds by vm.selectedOutlineIds.collectAsState()
    val contextModes by vm.contextModes.collectAsState()
    val historySelection by vm.historySelection.collectAsState()
    val savedHistoryIds by vm.savedHistoryIds.collectAsState()
    val selectedChapterCount =
        (selectedChapterSummaryIds + selectedChapterContentIds).size
    val scope = rememberCoroutineScope()
    // 聊天区滚动状态提升到页面级：输入框聚焦时也要能滚到最末尾
    val chatScroll = rememberScrollState()

    var showHistory by remember { mutableStateOf(false) }
    var showAgentSettings by remember { mutableStateOf(false) }
    var showBookPicker by remember { mutableStateOf(false) }
    var showChapterPicker by remember { mutableStateOf(false) }
    var showOutlinePicker by remember { mutableStateOf(false) }
    var showBookAgentHistory by remember { mutableStateOf(false) }
    var showReferences by remember { mutableStateOf(false) }
    var showWritePolicy by remember { mutableStateOf(false) }
    var showBatchConfirm by remember { mutableStateOf(false) }
    var previewWriteOperationId by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<AgentSession?>(null) }
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    var messageDraft by remember { mutableStateOf("") }
    var editingReplyId by remember { mutableStateOf<Long?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    // 待确认删除的消息：点按聊天区域其他位置可取消
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    // 查看某条 Agent 回复提交给模型的完整输入
    var logMessage by remember { mutableStateOf<AgentMessage?>(null) }
    var dismissedClarificationId by remember { mutableStateOf<Long?>(null) }
    // 待确认写入改为弹窗；关闭后保留入口，出现新的写入时重新弹出
    var writeDialogDismissed by remember { mutableStateOf(false) }
    val unresolvedWrites = writeOperations.filter { it.unresolved }
    LaunchedEffect(unresolvedWrites.map { it.id }) {
        if (unresolvedWrites.isNotEmpty()) writeDialogDismissed = false
    }
    var planEditing by remember(currentTaskId) { mutableStateOf(false) }
    var planDraft by remember(currentTaskId) { mutableStateOf<AgentPlan?>(null) }

    // 输入栏：快捷提示词 / 工具列表 / 附件 / 链接
    var showPrompts by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var showWritePolicyMenu by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkInput by remember { mutableStateOf("") }
    var previewAttachment by remember { mutableStateOf<ChatAttachment?>(null) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { vm.attachImage(it, displayNameOf(context, it)) }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.attachFile(it, displayNameOf(context, it)) }
    }

    val session = currentSession
    val selectedBookId = session?.bookId ?: filterBookId
    val selectedBookTitle = books.firstOrNull { it.id == selectedBookId }?.title
    val modelSelected = modelId > 0 && models.any { it.id == modelId }
    val executingCurrent = executingTaskId != null && executingTaskId == currentTask?.id

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = AgentPageBackground,
        topBar = {
            AgentPageHeader(
                bookTitle = selectedBookTitle,
                chapterCount = selectedChapterCount,
                outlineCount = selectedOutlineIds.size,
                hasBook = selectedBookId != null,
                onSettings = { showAgentSettings = true },
                onNewSession = { vm.newSession() },
                onHistory = { showHistory = true },
                onExitAgent = { onModeChange(AgentChatMode.NORMAL) },
                onBook = { showBookPicker = true },
                onChapters = { showChapterPicker = true },
                onOutline = { showOutlinePicker = true },
                onTaskHistory = {
                    val bookSessionIds = sessions
                        .filter { it.bookId == selectedBookId }
                        .map { it.id }.toSet()
                    vm.beginHistorySelection(bookSessionIds)
                    showBookAgentHistory = true
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        ) {

            Box(
                Modifier.weight(1f).fillMaxWidth().background(AgentPageBackground)
            ) {
                if (!modelSelected && messages.isEmpty()) {
                    ModelSetupReminder(
                        message = if (models.isEmpty()) {
                            "还没有配置模型，没有办法开始喵"
                        } else {
                            "还没有选择任务模型，暂时没有办法开始喵"
                        },
                        modifier = Modifier.align(Alignment.Center)
                            .padding(horizontal = 24.dp).widthIn(max = 320.dp)
                            .fillMaxWidth()
                    )
                }
                if (session != null) {
                val task = currentTask
                // 同一提问的多个版本：在消息操作栏用 < n/m > 切换，不再单独弹窗展示分支
                val versionTasks = remember(tasks, task) {
                    val rootId = task?.let { it.rootTaskId ?: it.id }
                    if (rootId == null) {
                        emptyList()
                    } else {
                        tasks.filter { it.id == rootId || it.rootTaskId == rootId }
                            .sortedBy { it.createdAt }
                    }
                }
                val versionIndex = versionTasks.indexOfFirst { it.id == task?.id }
                val hasVersions = versionTasks.size > 1

                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            // 点按聊天区域其他位置：取消待确认的删除
                            pendingDeleteId = null
                        }
                ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(chatScroll)
                ) {
                    val autoMode = currentTask?.workMode == AgentWorkMode.AUTO
                    // 用户主动点「结束」：整体显示为任务完成，而不是执行失败
                    val endedByUser = agentTaskEndedByUser(currentTask)
                    // Agent 会话是连续的：展示本会话的历史轮次（分支只保留当前分支链）
                    val visibleMessages = continuousMessages(messages, tasks, currentTask)
                        .filterNot { message ->
                            // 计划消息只用于驱动计划卡展示，本身不再作为一条对话气泡出现
                            message.kind == AgentMessageKind.PLAN
                        }
                        .filterNot { message ->
                            // 计划模式不再生成最终总结；兼容历史数据里遗留的“只读执行结果”消息
                            message.kind == AgentMessageKind.FINAL_ANSWER &&
                                message.taskId != null &&
                                tasks.any {
                                    it.id == message.taskId && it.workMode != AgentWorkMode.AUTO
                                }
                        }
                        .filterNot { message ->
                            // 自动模式不展示系统消息（执行完毕、暂停提示等）
                            autoMode && message.kind == AgentMessageKind.NOTICE
                        }
                        .filterNot { message ->
                            // 节点级执行状态（失败、等待写入、暂停、取消等）统一在执行过程卡片中展示，
                            // 不再逐条变成对话消息；兼容历史数据里已落库的同类系统消息
                            message.kind == AgentMessageKind.NOTICE &&
                                isExecutionLifecycleNotice(message.content)
                        }
                        .filterNot { message ->
                            // 历史数据里由澄清回答自动生成的用户消息，改由思维链展示
                            message.kind == AgentMessageKind.USER_INPUT &&
                                message.content.startsWith(CLARIFICATION_MESSAGE_PREFIX)
                        }
                    val hasPendingWrites = writeOperations.any { it.unresolved }
                    // 思考条只显示统一的「思考中」，不再区分规划下一步、对用户提问等阶段
                    val liveThinkingStatus = if (planning || executingCurrent || hasPendingWrites) {
                        "思考中"
                    } else {
                        null
                    }

                    // 新消息 / 状态变化后自动滚到最下方（等一帧拿到最新内容高度）
                    LaunchedEffect(
                        visibleMessages.size,
                        liveThinkingStatus,
                        executionNodes.size,
                        writeOperations.size,
                        pendingClarification?.id
                    ) {
                        withFrameNanos { }
                        if (chatScroll.maxValue > 0) {
                            chatScroll.animateScrollTo(chatScroll.maxValue)
                        }
                    }

                    // Agent 回复（最后一条非计划消息）下方提供与普通聊天一致的操作入口
                    val lastAgentReplyId = (
                        visibleMessages.lastOrNull {
                            it.role == AgentMessageRole.ASSISTANT &&
                                it.kind == AgentMessageKind.FINAL_ANSWER
                        } ?: visibleMessages.lastOrNull {
                            it.role == AgentMessageRole.ASSISTANT &&
                                it.kind != AgentMessageKind.PLAN &&
                                it.kind != AgentMessageKind.NOTICE
                        }
                        )?.id
                    // 本轮用户消息之后还没有 Agent 回复时，思考条应作为新一轮回复出现在下方，
                    // 而不是挂在上一条 Agent 回复上
                    val lastUserIndex = visibleMessages.indexOfLast {
                        it.role == AgentMessageRole.USER
                    }
                    val awaitingAgentReply = lastUserIndex >= 0 &&
                        visibleMessages.drop(lastUserIndex + 1)
                            .none { it.role == AgentMessageRole.ASSISTANT }
                    val thinkingUnderLastReply =
                        if (awaitingAgentReply) null else lastAgentReplyId
                    val beginPlanEdit: () -> Unit = {
                        currentPlan?.let { pv ->
                            planDraft = pv.plan.copy(
                                steps = pv.plan.steps.map {
                                    it.copy(
                                        riskLevel = AgentRiskLevel.normalize(it.riskLevel)
                                    )
                                }
                            )
                            vm.clearPlanEditError()
                            planEditing = true
                        }
                    }
                    // 历史任务的计划书 / 执行过程按轮次插入到各自用户消息之后，默认收起
                    val historyBlocks = run {
                        val byTask = historicalArtifacts.associateBy { it.task.id }
                        val inserted = mutableSetOf<Long>()
                        val afterMessage = mutableMapOf<Int, AgentTaskArtifacts>()
                        visibleMessages.forEachIndexed { index, message ->
                            val taskId = message.taskId ?: return@forEachIndexed
                            if (taskId in inserted) return@forEachIndexed
                            val item = byTask[taskId] ?: return@forEachIndexed
                            val nextTaskId = visibleMessages.getOrNull(index + 1)?.taskId
                            if (nextTaskId != taskId) {
                                afterMessage[index] = item
                                inserted += taskId
                            }
                        }
                        afterMessage to historicalArtifacts.filter { it.task.id !in inserted }
                    }

                    visibleMessages.forEachIndexed { index, message ->
                        AgentMessageRow(
                            message = message,
                            editable = message.kind == AgentMessageKind.USER_INPUT &&
                                message.content == currentTask?.userGoal &&
                                !planning && !executingCurrent && !sendingTask,
                            editing = message.role != AgentMessageRole.USER &&
                                editingReplyId == message.id,
                            draft = if (message.role == AgentMessageRole.USER) {
                                messageDraft
                            } else {
                                replyDraft
                            },
                            onDraftChange = {
                                if (message.role == AgentMessageRole.USER) {
                                    messageDraft = it
                                } else {
                                    replyDraft = it
                                }
                            },
                            onCopy = { copyToClipboard(context, message.content) },
                            deletePending = pendingDeleteId == message.id,
                            onDeleteMessage = {
                                if (pendingDeleteId == message.id) {
                                    pendingDeleteId = null
                                    vm.deleteMessage(message.id)
                                } else {
                                    pendingDeleteId = message.id
                                }
                            },
                            onStartEdit = {
                                if (message.role == AgentMessageRole.USER) {
                                    // 用户提问回显到输入框编辑，可同时补充图片 / 文件 / 链接
                                    editingMessageId = message.id
                                    messageDraft = message.content
                                    vm.setInput(message.content)
                                } else {
                                    // Agent 回复直接在卡片内编辑，以原文为基础
                                    editingReplyId = message.id
                                    replyDraft = message.content
                                }
                            },
                            onCancelEdit = {
                                if (message.role == AgentMessageRole.USER) {
                                    editingMessageId = null
                                } else {
                                    editingReplyId = null
                                }
                            },
                            onRegenerate = { vm.regenerateUserMessage(message.id) },
                            onSubmitEdit = {
                                if (message.role == AgentMessageRole.USER) {
                                    vm.editUserMessageAndRegenerate(message.id, messageDraft) {
                                        editingMessageId = null
                                    }
                                } else {
                                    vm.updateMessageContent(message.id, replyDraft)
                                    editingReplyId = null
                                }
                            },
                            showAssistantActions = message.id == lastAgentReplyId &&
                                currentTask != null && !planning,
                            canRegenerateAssistant = currentPlan != null && !executingCurrent,
                            canEditPlan = currentPlan != null &&
                                !executingCurrent && !planEditing,
                            onRegenerateAssistant = vm::regeneratePlan,
                            onEditPlan = beginPlanEdit,
                            chatStyle = true,
                            showThinkingHeader = message.id == thinkingUnderLastReply,
                            thinkingStatus = if (message.id == thinkingUnderLastReply) {
                                liveThinkingStatus
                            } else {
                                null
                            },
                            thinkingExpanded = thinkingExpanded,
                            onToggleThinkingExpanded = vm::toggleThinkingExpanded,
                            collapsed = message.id in collapsedMessageIds,
                            onToggleCollapsed = { vm.toggleMessageCollapsed(message.id) },
                            thinkingNodes = executionNodes,
                            thinkingTools = toolExecutions,
                            showVersionSwitcher = hasVersions &&
                                message.taskId == task?.id &&
                                message.kind == AgentMessageKind.USER_INPUT,
                            versionIndex = versionIndex + 1,
                            versionCount = versionTasks.size,
                            onPrevVersion = {
                                versionTasks.getOrNull(versionIndex - 1)?.let { vm.openTask(it.id) }
                            },
                            onNextVersion = {
                                versionTasks.getOrNull(versionIndex + 1)?.let { vm.openTask(it.id) }
                            },
                            onViewLog = { logMessage = message },
                            modelName = if (message.role == AgentMessageRole.ASSISTANT &&
                                message.kind != AgentMessageKind.NOTICE
                            ) {
                                remember(message.promptLog, message.taskId, tasks, models) {
                                    agentMessageModelName(
                                        message,
                                        tasks.firstOrNull { it.id == message.taskId },
                                        models
                                    )
                                }
                            } else {
                                ""
                            }
                        )
                        historyBlocks.first[index]?.let { item ->
                            key(item.task.id) { AgentHistoryTaskBlock(item) }
                        }
                    }

                    // 没有可见消息的历史任务（例如补充信息已并入思维链）统一补在末尾
                    historyBlocks.second.forEach { item ->
                        key(item.task.id) { AgentHistoryTaskBlock(item) }
                    }

                    val pending = pendingClarification
                    if (pending == null && planning) {
                        AgentPlanningCard(
                            refining = currentPlan?.plan?.needsClarification == false,
                            onStop = vm::cancelPlanning
                        )
                    } else if (currentPlan == null) {
                        currentTask?.let { task ->
                            AgentTaskStateCard(
                                task = task,
                                onRetry = vm::retryPlanning,
                                onDelete = { vm.deleteTask(task.id) }
                            )
                        }
                    }

                    if (planError != null) {
                        AgentErrorCard(
                            message = planError ?: "",
                            canRetry = currentTask != null && !planning,
                            onRetry = vm::regeneratePlan,
                            onDismiss = vm::clearPlanError
                        )
                    }

                    val planView = currentPlan
                    val planTask = currentTask
                    if (planView != null && planTask != null &&
                        planTask.workMode != AgentWorkMode.AUTO &&
                        planPublished && pending == null && !planView.plan.needsClarification
                    ) {
                        AgentPlanCard(
                            planView = planView,
                            task = planTask,
                            planning = planning,
                            executing = executingCurrent,
                            hasPendingWrites = writeOperations.any { it.unresolved },
                            branchesAvailable = false,
                            planEditing = planEditing,
                            editingPlan = planDraft,
                            planEditError = planEditError,
                            onPlanChange = { planDraft = it },
                            onStartPlanEdit = beginPlanEdit,
                            onSavePlanEdit = {
                                planDraft?.let { draft ->
                                    vm.saveEditedPlan(draft) { planEditing = false }
                                }
                            },
                            onCancelPlanEdit = {
                                planEditing = false
                                planDraft = null
                                vm.clearPlanEditError()
                            },
                            onOpenBranches = {},
                            onRegenerate = vm::regeneratePlan,
                            onConfirm = vm::confirmPlan,
                            onStartExecution = vm::startReadOnlyExecution,
                            onPauseTask = { vm.pauseTask(planTask.id) },
                            onResumeTask = { vm.resumeTask(planTask.id) },
                            onCancelTask = { vm.cancelTask(planTask.id) },
                            onDeleteTask = { vm.deleteTask(planTask.id) }
                        )
                        // 计划模式没有独立回复气泡：把规划请求的 token / 缓存命中率回显在计划卡下方
                        val planMessage = messages.lastOrNull {
                            it.taskId == planTask.id && it.kind == AgentMessageKind.PLAN
                        }
                        if (planMessage != null) {
                            AgentUsageLine(
                                message = planMessage,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            )
                        }
                    }

                    // 待确认写入改为弹窗展示，执行记录区不再插入提示卡片
                    if (autoMode) {
                        val taskFailed = currentTask?.status == AgentTaskStatus.FAILED
                        // 自动模式：本轮还没有最终回复时，用一张实时卡片回显思考状态
                        if (awaitingAgentReply &&
                            (executingCurrent || planning || hasPendingWrites || taskFailed ||
                                endedByUser)
                        ) {
                            AutoLiveAssistantCard(
                                status = liveThinkingStatus,
                                nodes = executionNodes,
                                tools = toolExecutions,
                                clarifications = answeredClarifications,
                                thinkingExpanded = thinkingExpanded,
                                onToggleThinkingExpanded = vm::toggleThinkingExpanded,
                                error = executionError.orEmpty().ifBlank {
                                    if (taskFailed) currentTask?.errorMessage ?: "" else ""
                                },
                                canRecover = taskFailed && !executingCurrent,
                                endedByUser = endedByUser,
                                onRetry = {
                                    currentTask?.currentNodeId?.let(vm::retryFailedNode)
                                },
                                onEnd = vm::finishFailedTask
                            )
                        }
                    } else {
                        AgentExecutionCard(
                            nodes = executionNodes,
                            toolExecutions = toolExecutions,
                            clarifications = answeredClarifications,
                            executing = executingCurrent,
                            error = executionError,
                            onCancel = vm::cancelExecution,
                            onDismissError = vm::clearExecutionError,
                            onRetryNode = vm::retryFailedNode,
                            onSkipNode = vm::skipFailedNode,
                            onEndTask = vm::finishFailedTask,
                            onContinueWaiting = vm::continueWaitingTask,
                            onEndWaiting = vm::endWaitingTask,
                            recoveryEnabled = currentTask?.status == AgentTaskStatus.FAILED &&
                                !executingCurrent,
                            currentNodeId = currentTask?.currentNodeId,
                            endedByUser = endedByUser
                        )
                    }

                    if (!modelSelected && messages.isNotEmpty()) {
                        ModelSetupReminder(
                            message = if (models.isEmpty()) {
                                "还没有配置模型，没有办法开始喵"
                            } else {
                                "还没有选择任务模型，暂时没有办法开始喵"
                            },
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 24.dp)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (chatScroll.maxValue > 0) {
                    // 右侧滚动条：显示当前阅读位置，可拖动快速翻阅对话
                    BoxWithConstraints(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(12.dp)
                    ) {
                        val viewport = constraints.maxHeight.toFloat()
                        val density = LocalDensity.current
                        // 约束可能是 Infinity（例如父级可滚动），此时不绘制滚动条，避免 NaN 偏移
                        if (viewport.isFinite() && viewport > 0f) {
                            val minThumb = with(density) { 40.dp.toPx() }
                            val contentHeight = viewport + chatScroll.maxValue.toFloat()
                            val thumbHeight = if (contentHeight > 0f) {
                                (viewport * viewport / contentHeight)
                                    .coerceIn(minThumb.coerceAtMost(viewport), viewport)
                            } else {
                                viewport
                            }
                            val progress = chatScroll.value.toFloat() / chatScroll.maxValue
                            val offsetY = (viewport - thumbHeight) * progress
                            if (offsetY.isFinite() && thumbHeight > 0f) {
                                Box(
                                    Modifier
                                        .offset { IntOffset(0, offsetY.roundToInt()) }
                                        .width(4.dp)
                                        .height(with(density) { thumbHeight.toDp() })
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                        .pointerInput(viewport, thumbHeight) {
                                            detectDragGestures { change, drag ->
                                                change.consume()
                                                val maxOffset =
                                                    (viewport - thumbHeight).coerceAtLeast(1f)
                                                val delta =
                                                    drag.y / maxOffset * chatScroll.maxValue
                                                scope.launch {
                                                    chatScroll.scrollTo(
                                                        (chatScroll.value + delta).roundToInt()
                                                            .coerceIn(0, chatScroll.maxValue)
                                                    )
                                                }
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
                AgentTaskPanel(
                    sessionId = session.id,
                    title = session.title,
                    onRename = { renaming = session },
                    onOpenReferences = { showReferences = true }
                )
                }
                }
            }

            // 两种聊天共用功能栏；Agent 额外提供固定在右侧的写入权限入口。
            ChatToolbar(
                trailingContent = {
                    WritePermissionBadge(
                        policy = writePolicy,
                        onClick = { showWritePolicyMenu = true }
                    )
                }
            ) {
                    // 弹窗被关闭后，保留重新打开的入口
                    pendingClarification
                        ?.takeIf { dismissedClarificationId == it.id }
                        ?.let {
                            AssistChip(
                                onClick = { dismissedClarificationId = null },
                                label = { Text("回答问题") }
                            )
                        }
                    if (unresolvedWrites.isNotEmpty() && writeDialogDismissed) {
                        AssistChip(
                            onClick = { writeDialogDismissed = false },
                            label = { Text("${unresolvedWrites.size} 项待确认") }
                        )
                    }
                    ChatToolbarAction(R.drawable.ic_chat_shortcut, "常用提示词", selected = showPrompts) {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        showPrompts = !showPrompts
                    }
                    ChatToolbarAction(R.drawable.ic_chat_tools, "Agent 工具（已全部启用）") {
                        showTools = true
                    }
                    ChatToolbarAction(R.drawable.ic_chat_image, "添加图片") {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    ChatToolbarAction(R.drawable.ic_chat_file, "添加文件") {
                        filePicker.launch(TEXT_FILE_MIME_TYPES)
                    }
                    ChatToolbarAction(R.drawable.ic_chat_link, "添加链接") {
                        linkInput = ""
                        showLinkDialog = true
                    }
            }

            // 快捷提示词面板
            if (showPrompts) {
                HorizontalDivider()
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 200.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (prompts.isEmpty()) {
                        item { Text("暂无常用提示词", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(prompts, key = { it.id }) { prompt ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    showPrompts = false
                                    vm.appendPrompt(prompt)
                                }
                                .padding(12.dp)
                        ) {
                            Text(prompt.title, fontWeight = FontWeight.SemiBold)
                            if (prompt.content.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    prompt.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 待发送附件：图片 / 文件 / 链接
            if (pendingAttachments.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pendingAttachments.forEach { attachment ->
                        AssistChip(
                            onClick = { previewAttachment = attachment },
                            label = { Text(attachmentLabel(attachment)) },
                            leadingIcon = {
                                Icon(attachmentIcon(attachment), null, Modifier.size(16.dp))
                            },
                            trailingIcon = {
                                Box(
                                    Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .clickable { vm.removeAttachment(attachment) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Close, "移除附件", Modifier.size(14.dp))
                                }
                            }
                        )
                    }
                }
            }

            if (editingMessageId != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "正在编辑消息，发送后将生成新分支",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            editingMessageId = null
                            messageDraft = ""
                            vm.setInput("")
                        }
                    ) { Text("取消") }
                }
            }

            // 生成 / 规划 / 执行中：提供「停止响应」图标
            if (planning || sendingTask || executingCurrent) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.clickable {
                            if (planning) vm.cancelPlanning() else vm.cancelExecution()
                        }
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = "停止响应",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "停止响应",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            ChatComposer(
                value = input,
                onValueChange = vm::setInput,
                placeholder = "说点什么吧……",
                sendEnabled = !planning && !sendingTask && !executingCurrent && modelSelected &&
                    (input.isNotBlank() || pendingAttachments.isNotEmpty()),
                onSend = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    val editingId = editingMessageId
                    if (editingId != null) {
                        vm.submitEditedMessage(editingId) {
                            editingMessageId = null
                            messageDraft = ""
                        }
                    } else {
                        vm.sendTask()
                    }
                },
                onFocus = {
                    // 点击输入框时滚到对话最末尾，避免停留在历史轮次
                    scope.launch {
                        withFrameNanos { }
                        if (chatScroll.maxValue > 0) {
                            chatScroll.animateScrollTo(chatScroll.maxValue)
                        }
                        // 软键盘弹出后可视区变矮，再补一次确保停在最末尾
                        delay(250)
                        if (chatScroll.maxValue > 0) {
                            chatScroll.animateScrollTo(chatScroll.maxValue)
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }

    val clarification = pendingClarification
    if (clarification != null && dismissedClarificationId != clarification.id) {
        AgentClarificationSheet(
            clarification = clarification,
            onDismiss = { dismissedClarificationId = clarification.id },
            onSubmit = { vm.answerClarification(it) }
        )
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("添加链接") },
            text = {
                OutlinedTextField(
                    value = linkInput,
                    onValueChange = { linkInput = it },
                    placeholder = { Text("https://…") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = linkInput.isNotBlank(),
                    onClick = {
                        vm.attachLink(linkInput)
                        showLinkDialog = false
                    }
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) { Text("取消") }
            }
        )
    }

    previewAttachment?.let { attachment ->
        AttachmentPreviewDialog(
            attachment = attachment,
            onDismiss = { previewAttachment = null },
            onDelete = {
                vm.removeAttachment(attachment)
                previewAttachment = null
            }
        )
    }

    logMessage?.let { message ->
        AgentPromptLogDialog(
            content = message.promptLog,
            promptTokens = message.promptTokens,
            contextWindow = models.firstOrNull { it.id == modelId }?.contextWindow ?: 0,
            onDismiss = { logMessage = null }
        )
    }

    if (showTools) {
        AgentToolsDialog(tools = vm.agentTools, onDismiss = { showTools = false })
    }

    if (showWritePolicyMenu) {
        WritePermissionDialog(
            current = writePolicy,
            onSelect = {
                vm.setWritePolicy(it)
                showWritePolicyMenu = false
            },
            onDismiss = { showWritePolicyMenu = false }
        )
    }

    if (showHistory) {
        AgentHistoryScreen(
            sessions = sessions,
            books = books,
            groups = groups,
            search = search,
            onSearchChange = vm::setSearchQuery,
            currentSessionId = currentSessionId,
            onNew = {
                vm.newSession()
                showHistory = false
            },
            onOpen = {
                vm.openSession(it)
                showHistory = false
            },
            onRename = vm::renameSession,
            onDelete = vm::deleteSession,
            onDismiss = { showHistory = false }
        )
    }

    if (showAgentSettings) {
        AgentModelSettingsDialog(
            models = models,
            providers = providers,
            taskModelId = agentTaskModelId,
            contentModelId = agentContentModelId,
            onSelectTaskModel = vm::setAgentTaskModel,
            onSelectContentModel = vm::setAgentContentModel,
            onDismiss = { showAgentSettings = false }
        )
    }

    if (inspectedTask != null) {
        val inspectedBookTitle = inspectedSession?.bookId?.let { bookId ->
            books.firstOrNull { it.id == bookId }?.title
        }
        AgentTaskDetailDialog(
            task = inspectedTask,
            session = inspectedSession,
            bookTitle = inspectedBookTitle,
            nodes = inspectedNodes,
            writes = inspectedWrites,
            recoveryLogs = inspectedRecoveryLogs,
            modelRequests = inspectedModelRequests,
            onPause = { inspectedTask?.let { vm.pauseTask(it.id) } },
            onResume = { inspectedTask?.let { vm.resumeTask(it.id) } },
            onCancel = { inspectedTask?.let { vm.cancelTask(it.id) } },
            onDelete = {
                inspectedTask?.let { vm.deleteTask(it.id) }
                vm.closeTaskDetails()
            },
            onOpenInChat = vm::openInspectedTaskInChat,
            onDismiss = vm::closeTaskDetails
        )
    }

    if (showChapterPicker && selectedBookId != null) {
        // 仅显示绑定书籍的上下文选择：分卷 + 章节（梗概 / 正文 / 卷梗概）
        AgentChapterContextDialog(
            chapters = chapters,
            volumes = volumes,
            summarySelected = selectedChapterSummaryIds,
            contentSelected = selectedChapterContentIds,
            activeVolumeSummaries = activeVolumeSummaries,
            selectedVolumeSummaryIds = selectedVolumeSummaryIds,
            onToggleAll = vm::toggleAllChapters,
            onToggleSummary = vm::toggleAllSummary,
            onToggleContent = vm::toggleAllContent,
            onToggleVolumeSummaryAll = vm::toggleAllVolumeSummaries,
            onToggleChapterSummary = vm::toggleChapterSummary,
            onToggleChapterContent = vm::toggleChapterContent,
            onToggleVolumeSummary = vm::toggleVolumeSummary,
            onToggleVolumeContent = vm::toggleVolumeContent,
            onToggleVolumeSummaryAsset = vm::toggleVolumeSummaryAsset,
            onDismiss = { showChapterPicker = false }
        )
    }

    if (showOutlinePicker && selectedBookId != null) {
        // 仅显示绑定书籍的大纲：全部 / 常驻 / 普通 + 分类整组勾选
        AgentOutlineDialog(
            items = outline,
            selected = selectedOutlineIds,
            onToggleAll = { ids -> vm.toggleAllOutlines(ids) },
            onToggle = vm::toggleOutline,
            onToggleCategory = vm::toggleOutlineCategory,
            onDismiss = { showOutlinePicker = false }
        )
    }

    if (showBookAgentHistory && selectedBookId != null) {
        val bookSessions = sessions.filter { it.bookId == selectedBookId }
        AgentHistorySelectDialog(
            sessions = bookSessions,
            selectedIds = historySelection,
            onToggle = vm::toggleHistorySelection,
            onSelectAll = {
                vm.toggleAllHistorySelection(bookSessions.map { it.id }.toSet())
            },
            onSave = { vm.saveHistorySelection() },
            onDismiss = { showBookAgentHistory = false }
        )
    }

    if (showBookPicker) {
        // 复用普通 AI 聊天的选书弹窗：分组筛选 / 搜索 / 新建书籍 / 现场建组
        BookPickerDialog(
            books = books,
            groups = groups,
            selectedId = session?.bookId ?: filterBookId,
            onSelect = { bookId ->
                val sessionId = session?.id
                if (sessionId != null) vm.bindBook(sessionId, bookId) else vm.setFilterBook(bookId)
                showBookPicker = false
            },
            onCreateBook = { result -> vm.createBookFull(result) },
            onPickCover = { uri, callback -> scope.launch { callback(vm.pickCover(uri)) } },
            onCreateGroup = { name -> vm.createGroupAndGet(name) },
            styleLibrary = styleLibrary,
            onRetryStyleProfiles = vm::retryStyleProfiles,
            onDismiss = { showBookPicker = false }
        )
    }

    if (showReferences && session != null) {
        val extraRows = buildList {
            chapters.forEach { chapter ->
                if (chapter.id in selectedChapterSummaryIds) {
                    val key = "cs-${chapter.id}"
                    add(
                        AgentSelectedExtraRow(
                            key = key,
                            group = "已选章节",
                            title = chapter.title,
                            detail = "梗概",
                            mode = contextModes[key] ?: AgentReferenceMode.CONTEXT,
                            content = chapter.summary
                        )
                    )
                }
                if (chapter.id in selectedChapterContentIds) {
                    val key = "cc-${chapter.id}"
                    add(
                        AgentSelectedExtraRow(
                            key = key,
                            group = "已选章节",
                            title = chapter.title,
                            detail = "正文",
                            mode = contextModes[key] ?: AgentReferenceMode.CONTEXT,
                            content = chapter.content
                        )
                    )
                }
            }
            activeVolumeSummaries
                .filter { it.id in selectedVolumeSummaryIds }
                .forEach { summary ->
                    val key = "vs-${summary.id}"
                    add(
                        AgentSelectedExtraRow(
                            key = key,
                            group = "已选卷梗概",
                            title = volumes.firstOrNull { it.id == summary.volumeId }?.title ?: "分卷",
                            detail = "卷梗概",
                            mode = contextModes[key] ?: AgentReferenceMode.CONTEXT,
                            content = summary.effectiveContent
                        )
                    )
                }
            outline
                .filter { it.id in selectedOutlineIds }
                .forEach { item ->
                    val key = "ol-${item.id}"
                    add(
                        AgentSelectedExtraRow(
                            key = key,
                            group = "已选大纲",
                            title = "[${outlineCategoryLabel(item.category)}] ${item.title}",
                            detail = "大纲",
                            mode = contextModes[key] ?: AgentReferenceMode.CONTEXT,
                            content = item.content
                        )
                    )
                }
            sessions
                .filter { it.bookId == selectedBookId && it.id in savedHistoryIds }
                .forEach { historySession ->
                    val key = "hist-${historySession.id}"
                    add(
                        AgentSelectedExtraRow(
                            key = key,
                            group = "历史 Agent",
                            title = historySession.title,
                            detail = "Agent 历史会话",
                            mode = contextModes[key] ?: AgentReferenceMode.CONTEXT,
                            content = "历史 Agent 会话：${historySession.title}\n\n" +
                                "当前仅登记会话来源，会话消息内容不注入上下文。"
                        )
                    )
                }
        }
        AgentReferenceScreen(
            sessionId = session.id,
            currentTaskId = currentTaskId,
            extraRows = extraRows,
            onToggleExtraMode = { row ->
                vm.setContextMode(
                    row.key,
                    if (row.mode == AgentReferenceMode.CONTEXT) {
                        AgentReferenceMode.REFERENCE_ONLY
                    } else {
                        AgentReferenceMode.CONTEXT
                    }
                )
            },
            onRemoveExtra = { row ->
                val id = row.key.substringAfter('-').toLongOrNull()
                if (id != null) {
                    when {
                        row.key.startsWith("cs-") -> vm.removeChapterSummary(id)
                        row.key.startsWith("cc-") -> vm.removeChapterContent(id)
                        row.key.startsWith("vs-") -> vm.removeVolumeSummary(id)
                        row.key.startsWith("ol-") -> vm.removeOutline(id)
                        row.key.startsWith("hist-") -> vm.removeSavedHistory(id)
                    }
                }
            },
            onSave = vm::saveContextSelection,
            onDismiss = { showReferences = false }
        )
    }

    if (showWritePolicy) {
        WritePermissionDialog(
            current = writePolicy,
            onSelect = {
                vm.setWritePolicy(it)
                showWritePolicy = false
            },
            onDismiss = { showWritePolicy = false }
        )
    }

    if (showBatchConfirm) {
        AgentWriteBatchConfirmDialog(
            operations = writeOperations,
            onConfirm = {
                vm.approveAllNormalWrites()
                showBatchConfirm = false
            },
            onDismiss = { showBatchConfirm = false }
        )
    }

    previewWriteOperationId?.let { operationId ->
        writeOperations.firstOrNull { it.id == operationId }?.let { operation ->
            AgentWritePreviewDialog(
                operation = operation,
                onApprove = { vm.approveWrite(operation.id) },
                onReject = { vm.rejectWrite(operation.id) },
                onRollback = { vm.rollbackWrite(operation.id) },
                onDismiss = { previewWriteOperationId = null }
            )
        }
    }

    // 待确认写入：弹窗展示；查看详情时让位给预览弹窗
    if (unresolvedWrites.isNotEmpty() && !writeDialogDismissed && previewWriteOperationId == null) {
        Dialog(onDismissRequest = { writeDialogDismissed = true }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    AgentWriteOperationsCard(
                        operations = unresolvedWrites,
                        notice = writeNotice,
                        policy = writePolicy,
                        onApprove = vm::approveWrite,
                        onReject = vm::rejectWrite,
                        onApproveAllNormal = { showBatchConfirm = true },
                        onRollback = vm::rollbackWrite,
                        onOpen = { previewWriteOperationId = it.id },
                        onOpenPolicy = { showWritePolicy = true },
                        onDismissNotice = vm::clearWriteNotice
                    )
                }
            }
        }
    }

    renaming?.let { target ->
        var title by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("修改任务标题") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("任务标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = title.isNotBlank(),
                    onClick = {
                        vm.renameSession(target.id, title)
                        renaming = null
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消") }
            }
        )
    }
}

/** 历史回复优先使用发送时的模型快照，不跟随当前设置冒充历史模型。 */
private fun agentMessageModelName(
    message: AgentMessage,
    task: AgentTask?,
    models: List<AiModel>
): String {
    val loggedName = runCatching {
        agentJson.decodeFromString(AgentPromptLog.serializer(), message.promptLog).modelName
    }.getOrNull()?.takeIf { it.isNotBlank() }
    if (loggedName != null) return loggedName
    return models.firstOrNull { it.id == task?.modelId }
        ?.let { it.alias.ifBlank { it.name } }
        ?.takeIf { it.isNotBlank() }
        ?: "未知"
}

@Composable
internal fun AgentMessageRow(
    message: AgentMessage,
    editable: Boolean,
    editing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCopy: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onSubmitEdit: () -> Unit,
    showAssistantActions: Boolean = false,
    canRegenerateAssistant: Boolean = true,
    canEditPlan: Boolean = false,
    onRegenerateAssistant: () -> Unit = {},
    onEditPlan: () -> Unit = {},
    chatStyle: Boolean = false,
    showThinkingHeader: Boolean = false,
    thinkingStatus: String? = null,
    thinkingNodes: List<AgentExecutionNode> = emptyList(),
    thinkingTools: List<AgentToolExecution> = emptyList(),
    thinkingExpanded: Boolean = false,
    onToggleThinkingExpanded: () -> Unit = {},
    collapsed: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    deletePending: Boolean = false,
    showVersionSwitcher: Boolean = false,
    versionIndex: Int = 0,
    versionCount: Int = 0,
    onPrevVersion: () -> Unit = {},
    onNextVersion: () -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onViewLog: () -> Unit = {},
    modelName: String = ""
) {
    val isUser = message.role == AgentMessageRole.USER
    val label = when {
        message.kind == AgentMessageKind.NOTICE -> "系统"
        message.kind == AgentMessageKind.PLAN -> "Agent 计划"
        isUser -> "你"
        else -> "Agent"
    }
    // 用户提问与 Agent 回复使用参考稿的整宽卡片，原有操作回调保持不变。
    if (chatStyle) {
        AgentMessageCard(
            ai = !isUser,
            tools = {
                if (!isUser && editing) {
                    TextButton(
                        onClick = onSubmitEdit,
                        enabled = draft.isNotBlank()
                    ) { Text("保存") }
                    TextButton(onClick = onCancelEdit) { Text("取消") }
                } else {
                    if (isUser) {
                        MessageIcon(
                            icon = Icons.Filled.Refresh,
                            description = "重新生成",
                            enabled = editable,
                            onClick = onRegenerate
                        )
                        MessageIcon(
                            icon = Icons.Filled.Edit,
                            description = "编辑",
                            enabled = editable,
                            onClick = onStartEdit
                        )
                    } else if (showAssistantActions) {
                        MessageIcon(
                            icon = Icons.Filled.Refresh,
                            description = "重新生成",
                            enabled = canRegenerateAssistant,
                            onClick = onRegenerateAssistant
                        )
                        MessageIcon(
                            icon = Icons.Filled.Edit,
                            description = "编辑回复",
                            enabled = canRegenerateAssistant,
                            onClick = onStartEdit
                        )
                    }
                    MessageIcon(
                        icon = Icons.Filled.ContentCopy,
                        description = "复制",
                        onClick = onCopy
                    )
                    if (!isUser && message.promptLog.isNotBlank()) {
                        MessageIcon(
                            icon = Icons.AutoMirrored.Filled.ReceiptLong,
                            description = "查看提交日志",
                            onClick = onViewLog
                        )
                    }
                    MessageIcon(
                        icon = if (deletePending) Icons.Filled.Check else Icons.Filled.Delete,
                        description = if (deletePending) "确认删除" else "删除",
                        tint = if (deletePending) MaterialTheme.colorScheme.error else null,
                        onClick = onDeleteMessage
                    )
                    Spacer(Modifier.weight(1f))
                    if (showVersionSwitcher) {
                        IconButton(
                            onClick = onPrevVersion,
                            enabled = versionIndex > 1,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "上一版本",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            "$versionIndex/$versionCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = onNextVersion,
                            enabled = versionIndex < versionCount,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "下一版本",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    MessageIcon(
                        icon = if (collapsed) {
                            Icons.Filled.KeyboardArrowDown
                        } else {
                            Icons.Filled.KeyboardArrowUp
                        },
                        description = if (collapsed) "展开" else "收起",
                        onClick = onToggleCollapsed
                    )
                }
            }
        ) {
            if (!isUser && showThinkingHeader && !collapsed) {
                AutoThinkingHeader(
                    status = thinkingStatus,
                    nodes = thinkingNodes,
                    tools = thinkingTools,
                    expanded = thinkingExpanded,
                    onToggleExpanded = onToggleThinkingExpanded
                )
                Spacer(Modifier.height(8.dp))
            }
            if (!isUser && editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    minLines = 3,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(
                    if (collapsed) message.content.replace('\n', ' ').trim() else message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (collapsed) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis
                )
                // 模型与 token / 缓存统计留在正文下方、操作栏分隔线上方。
                if (!isUser && !collapsed) {
                    if (modelName.isNotBlank()) {
                        Text(
                            "model: $modelName",
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AgentUsageLine(
                        message = message,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(4.dp))
        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = onSubmitEdit,
                    enabled = draft.isNotBlank()
                ) { Text(if (isUser) "重新生成" else "保存") }
                TextButton(onClick = onCancelEdit) { Text("取消") }
            }
            Text(
                if (isUser) {
                    "提交后会创建新分支，原提问与计划保持不变。"
                } else {
                    "保存后直接更新这条回复的内容。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.kind == AgentMessageKind.NOTICE) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (message.kind == AgentMessageKind.USER_INPUT) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCopy) { Text("复制") }
                    if (editable) {
                        TextButton(onClick = onStartEdit) { Text("编辑") }
                        TextButton(onClick = onRegenerate) { Text("重新生成") }
                    }
                }
            } else if (showAssistantActions) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageIcon(
                        icon = Icons.Filled.Refresh,
                        description = "重新生成",
                        enabled = canRegenerateAssistant,
                        onClick = onRegenerateAssistant
                    )
                    MessageIcon(
                        icon = Icons.Filled.Edit,
                        description = "编辑回复",
                        enabled = canRegenerateAssistant,
                        onClick = onStartEdit
                    )
                    TextButton(onClick = onCopy) { Text("复制") }
                }
            }
        }
    }
}

/**
 * 自动化设置：分别配置任务模型与正文生成模型。
 *
 * 任务模型用于计划与执行步骤；正文生成模型只用于生成正文草稿，
 * 未选择时跟随任务模型。
 */
@Composable
private fun AgentModelSettingsDialog(
    models: List<AiModel>,
    providers: List<Provider>,
    taskModelId: Long,
    contentModelId: Long,
    onSelectTaskModel: (Long) -> Unit,
    onSelectContentModel: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val providerNames = remember(providers) {
        providers.associate { it.id to it.name }
    }
    val labelOf: (Long) -> String? = { id ->
        models.firstOrNull { it.id == id }?.let { model ->
            val display = model.alias.ifBlank { model.name }
            providerNames[model.providerId]?.let { "$display · $it" } ?: display
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自动化设置") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("任务模型", style = MaterialTheme.typography.titleSmall)
                Text(
                    "用于生成计划与执行步骤（Agent 的思考与工具调用）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                AgentModelPickerRow(
                    currentLabel = labelOf(taskModelId) ?: "跟随当前会话模型",
                    models = models,
                    providerNames = providerNames,
                    followLabel = null,
                    onSelect = onSelectTaskModel
                )
                Spacer(Modifier.height(16.dp))
                Text("正文生成模型", style = MaterialTheme.typography.titleSmall)
                Text(
                    "执行正文生成步骤时使用；未选择时跟随任务模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                AgentModelPickerRow(
                    currentLabel = labelOf(contentModelId) ?: "跟随任务模型",
                    models = models,
                    providerNames = providerNames,
                    followLabel = "跟随任务模型",
                    onSelect = onSelectContentModel,
                    onFollow = { onSelectContentModel(0L) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 设置弹窗里的模型下拉选择 */
@Composable
private fun AgentModelPickerRow(
    currentLabel: String,
    models: List<AiModel>,
    providerNames: Map<Long, String>,
    followLabel: String?,
    onSelect: (Long) -> Unit,
    onFollow: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                currentLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "选择模型")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp)
        ) {
            if (followLabel != null) {
                DropdownMenuItem(
                    text = { Text(followLabel) },
                    onClick = {
                        onFollow()
                        expanded = false
                    }
                )
            }
            models.forEach { model ->
                val display = model.alias.ifBlank { model.name }
                val provider = providerNames[model.providerId]
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(display)
                            if (provider != null) {
                                Text(
                                    provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(model.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 历史任务的计划书与执行过程。
 *
 * 两张卡片都默认收起、可展开查看；不提供重新生成 / 执行 / 删除等操作。
 */
@Composable
private fun AgentHistoryTaskBlock(item: AgentTaskArtifacts) {
    AgentPlanCard(
        planView = item.planView,
        task = item.task,
        planning = false,
        executing = false,
        readOnly = true,
        onRegenerate = {},
        onConfirm = {},
        onStartExecution = {},
        onPauseTask = {},
        onResumeTask = {},
        onCancelTask = {},
        onDeleteTask = {}
    )
    AgentExecutionCard(
        nodes = item.nodes,
        toolExecutions = item.tools,
        clarifications = item.clarifications,
        executing = false,
        error = null,
        readOnly = true,
        onCancel = {},
        onDismissError = {},
        endedByUser = agentTaskEndedByUser(item.task)
    )
}

/**
 * Agent 会话是连续的：按时间顺序展示同一会话内的历史消息。
 * 顺序产生的根任务全部展示；发生重新生成 / 编辑分支时，只保留当前任务所在的链条，
 * 避免同一次提问的旧版本重复出现。
 */
private fun continuousMessages(
    messages: List<AgentMessage>,
    tasks: List<AgentTask>,
    currentTask: AgentTask?
): List<AgentMessage> {
    if (currentTask == null) return messages.filter { it.taskId == null }
    val parentsWithChildren = tasks.mapNotNull { it.parentTaskId }.toSet()
    val chain = mutableSetOf<Long>()
    var cursor: AgentTask? = currentTask
    while (cursor != null) {
        chain += cursor.id
        val parentId = cursor.parentTaskId
        cursor = if (parentId == null) null else tasks.firstOrNull { it.id == parentId }
    }
    val visibleTaskIds = tasks
        .filter { it.id in chain || (it.parentTaskId == null && it.id !in parentsWithChildren) }
        .map { it.id }
        .toSet()
    return messages.filter { it.taskId == null || it.taskId in visibleTaskIds }
}

@Composable
private fun MessageIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(34.dp)
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = tint ?: if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            }
        )
    }
}

@Composable
private fun AutoThinkingHeader(
    status: String?,
    nodes: List<AgentExecutionNode>,
    tools: List<AgentToolExecution>,
    clarifications: List<AgentClarification> = emptyList(),
    expanded: Boolean = false,
    onToggleExpanded: () -> Unit = {}
) {
    val active = status != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable { onToggleExpanded() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                // 运行中只显示「思考中」，结束后显示「深度思考」
                status ?: "深度思考",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (expanded) "收起" else "展开",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            if (nodes.isEmpty()) {
                Text(
                    "正在准备…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                nodes.takeLast(6).forEach { node ->
                    Text(
                        "${nodeStatusMark(node.status)} ${node.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val recentTools = tools.takeLast(6)
            if (recentTools.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "工具",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                recentTools.forEach { tool ->
                    Text(
                        "· ${tool.toolDisplayName.ifBlank { tool.toolName }}  " +
                            promptToolStatus(tool.status) +
                            (tool.durationMs?.let { " ${it}ms" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 用户对提问的补充信息归入思维链，不再作为用户对话展示
            if (clarifications.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "补充信息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                clarifications.takeLast(6).forEach { clarification ->
                    Text(
                        "· ${clarification.answer.take(200)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoLiveAssistantCard(
    status: String?,
    nodes: List<AgentExecutionNode>,
    tools: List<AgentToolExecution>,
    clarifications: List<AgentClarification> = emptyList(),
    thinkingExpanded: Boolean = false,
    onToggleThinkingExpanded: () -> Unit = {},
    error: String = "",
    canRecover: Boolean = false,
    endedByUser: Boolean = false,
    onRetry: () -> Unit = {},
    onEnd: () -> Unit = {}
) {
    AgentMessageCard(ai = true) {
        AutoThinkingHeader(
            status = if (endedByUser) "任务完成" else status,
            nodes = nodes,
            tools = tools,
            clarifications = clarifications,
            expanded = thinkingExpanded,
            onToggleExpanded = onToggleThinkingExpanded
        )
        Spacer(Modifier.height(8.dp))
        if (endedByUser) {
            // 用户主动结束：按任务完成展示，不再提示失败
            Text(
                AgentTask.USER_ENDED_REASON,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (error.isNotBlank()) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            if (canRecover) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetry) { Text("重试") }
                    TextButton(onClick = onEnd) { Text("结束") }
                }
            }
        } else {
            Text(
                "正在为你处理…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 助手回复的 token 消耗与缓存命中率。
 *
 * 与普通聊天使用同一套 [CacheRateCalculator] 口径；没有可用统计时不占位。
 */
@Composable
private fun AgentUsageLine(
    message: AgentMessage,
    modifier: Modifier = Modifier
) {
    val promptKnown = message.promptTokens >= 0
    val hasStats = message.promptTokens > 0 || message.completionTokens > 0 ||
        message.cachedTokens > 0
    if (!hasStats) return
    val tokens = (if (promptKnown) message.promptTokens else 0) + message.completionTokens
    Text(
        buildString {
            append(
                "消耗 $tokens tokens（输入 ${
                    if (promptKnown) message.promptTokens else "未知"
                } / 输出 ${message.completionTokens}）"
            )
            val rate = CacheRateCalculator.of(message.promptTokens, message.cachedTokens)
            append(" · ").append(
                rate.describe(message.promptTokens, message.cachedTokens)
            )
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth()
    )
}

/** 提交日志标题下的上下文窗口用量说明；模型窗口或输入 token 未知时显示「未知」 */
private fun agentContextUsageSummary(promptTokens: Int, contextWindow: Int): String {
    val used = if (promptTokens >= 0) "%,d".format(promptTokens) else "未知"
    val remain = if (promptTokens >= 0 && contextWindow > 0) {
        "%,d".format((contextWindow - promptTokens).coerceAtLeast(0))
    } else {
        "未知"
    }
    return "上下文窗口占用：$used · 剩余：$remain"
}

/** 查看某条 Agent 回复提交给模型的完整输入；内容较长时可滚动、可选中复制 */
@Composable
private fun AgentPromptLogDialog(
    content: String,
    promptTokens: Int,
    contextWindow: Int,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("提交日志")
                Text(
                    agentContextUsageSummary(promptTokens, contextWindow),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            SelectionContainer {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        content.ifBlank { "本次回复没有记录提交日志" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun nodeStatusMark(status: String): String = when (status) {
    AgentNodeStatus.COMPLETED -> "✓"
    AgentNodeStatus.FAILED -> "×"
    AgentNodeStatus.RUNNING -> "…"
    else -> "·"
}

private fun promptToolStatus(status: String): String = when (status) {
    AgentToolStatus.SUCCEEDED -> "成功"
    AgentToolStatus.FAILED -> "失败"
    AgentToolStatus.RUNNING -> "执行中"
    else -> "等待"
}

/** 节点级执行状态提示；这些内容统一在执行过程卡片中展示，不再作为对话消息出现 */
private val EXECUTION_NOTICE_PREFIXES = listOf(
    "执行失败（",
    "执行已暂停",
    "执行已取消",
    "执行完毕",
    "任务已取消",
    "任务已结束",
    "任务已从后台恢复，但仍有",
    "等待你确认 "
)

private fun isExecutionLifecycleNotice(content: String): Boolean =
    EXECUTION_NOTICE_PREFIXES.any { content.startsWith(it) }

/** 旧版本把澄清回答写成「补充信息：…」的用户消息，现改为思维链展示 */
private const val CLARIFICATION_MESSAGE_PREFIX = "补充信息："

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    manager.setPrimaryClip(ClipData.newPlainText("Agent 提问", text))
}

/** 选择历史 Agent 会话：弹窗下方保存，只有保存后的会话进入已选资料 */
@Composable
private fun AgentHistorySelectDialog(
    sessions: List<AgentSession>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "选择历史 Agent",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }
                HorizontalDivider()
                if (sessions.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无可选会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(sessions, key = { it.id }) { session ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onToggle(session.id) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = session.id in selectedIds,
                                    onCheckedChange = { onToggle(session.id) }
                                )
                                Text(
                                    session.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${selectedIds.size} 个会话",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onSelectAll) { Text("全选") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSave()
                            onDismiss()
                        }
                    ) { Text("保存") }
                }
            }
        }
    }
}

/**
 * Agent 工具列表：执行引擎默认启用全部已注册工具，因此这里全部勾选且只读展示。
 * 不显示数字角标，避免与普通聊天的「已勾选工具数」混淆。
 */
@Composable
private fun AgentToolsDialog(tools: List<AgentToolInfo>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agent 工具") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        "默认启用全部工具（共 ${tools.size} 个）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                items(tools, key = { it.key }) { tool ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = true, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when {
                                    tool.generation -> "生成（仅预览，需另行确认写入）"
                                    tool.readOnly -> "只读"
                                    else -> "写入（需确认）"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** Agent 章节上下文选择弹窗：与普通 AI 聊天共用统一的选择章节 UI。 */
@Composable
private fun AgentChapterContextDialog(
    chapters: List<com.lianwenqi.app.data.entity.Chapter>,
    volumes: List<com.lianwenqi.app.data.entity.Volume>,
    summarySelected: Set<Long>,
    contentSelected: Set<Long>,
    activeVolumeSummaries: List<com.lianwenqi.app.data.entity.VolumeSummary>,
    selectedVolumeSummaryIds: Set<Long>,
    onToggleAll: () -> Unit,
    onToggleSummary: () -> Unit,
    onToggleContent: () -> Unit,
    onToggleVolumeSummaryAll: () -> Unit,
    onToggleChapterSummary: (Long) -> Unit,
    onToggleChapterContent: (Long) -> Unit,
    onToggleVolumeSummary: (List<Long>) -> Unit,
    onToggleVolumeContent: (List<Long>) -> Unit,
    onToggleVolumeSummaryAsset: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ChapterPickerDialog(
        chapters = chapters,
        volumes = volumes,
        summarySelected = summarySelected,
        contentSelected = contentSelected,
        activeVolumeSummaries = activeVolumeSummaries,
        selectedVolumeSummaryIds = selectedVolumeSummaryIds,
        focusVolumeId = volumes.firstOrNull()?.id,
        onToggleAll = onToggleAll,
        onToggleSummary = onToggleSummary,
        onToggleContent = onToggleContent,
        onToggleVolumeSummaryAll = onToggleVolumeSummaryAll,
        onToggleChapterSummary = onToggleChapterSummary,
        onToggleChapterContent = onToggleChapterContent,
        onToggleVolumeSummary = { volumeId ->
            onToggleVolumeSummary(chapters.filter { it.volumeId == volumeId }.map { it.id })
        },
        onToggleVolumeContent = { volumeId ->
            onToggleVolumeContent(chapters.filter { it.volumeId == volumeId }.map { it.id })
        },
        onToggleVolumeSummaryAsset = onToggleVolumeSummaryAsset,
        onDismiss = onDismiss
    )
}

/** Agent 大纲选择弹窗：复用侧边栏的大纲勾选 UI */
@Composable
private fun AgentOutlineDialog(
    items: List<com.lianwenqi.app.data.entity.OutlineItem>,
    selected: Set<Long>,
    onToggleAll: (Set<Long>) -> Unit,
    onToggle: (Long) -> Unit,
    onToggleCategory: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                OutlinePicker(
                    items = items,
                    selected = selected,
                    onToggleAll = onToggleAll,
                    onToggle = onToggle,
                    onToggleCategory = onToggleCategory
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

/** 写入权限入口：沿用设计稿的三档盾牌，始终可在输入前调整。 */
@Composable
private fun WritePermissionBadge(policy: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp)
    ) {
        AgentPermissionIcon(policy, iconSize = 14.dp)
    }
}

/** 写入权限选择弹窗 */
@Composable
internal fun WritePermissionDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("写入权限") },
        text = {
            Column {
                Text(
                    "写入权限只影响 Agent 执行阶段的一般写入；覆盖正文、高风险与破坏性操作始终需要确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                WritePermissionOption(
                    title = "高权限",
                    description = "一般写入（修改梗概、创建 / 修改大纲）自动执行；覆盖正文、高风险与破坏性操作仍需逐次确认。",
                    policy = AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL,
                    selected = current == AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL,
                    onClick = { onSelect(AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL) }
                )
                WritePermissionOption(
                    title = "中权限",
                    description = "一般写入先汇总为待确认列表，由你一次性批量批准；高风险与破坏性操作仍需逐条确认。",
                    policy = AgentWritePermissionPolicy.BATCH_CONFIRM,
                    selected = current == AgentWritePermissionPolicy.BATCH_CONFIRM,
                    onClick = { onSelect(AgentWritePermissionPolicy.BATCH_CONFIRM) }
                )
                WritePermissionOption(
                    title = "低权限",
                    description = "所有写入（包括一般写入）都需要你逐条确认后才执行。",
                    policy = AgentWritePermissionPolicy.ASK_EVERY_TIME,
                    selected = current == AgentWritePermissionPolicy.ASK_EVERY_TIME,
                    onClick = { onSelect(AgentWritePermissionPolicy.ASK_EVERY_TIME) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun WritePermissionOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    policy: String
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0xFFF0EFFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AgentPermissionIcon(policy)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Text("✓", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AgentPlanningCard(refining: Boolean = false, onStop: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (refining) "正在根据补充信息调整计划…" else "正在生成执行计划…",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (refining) {
                    "Agent 会保留已有计划与思考，只调整受影响的部分。"
                } else {
                    "Agent 正在调用模型分析任务，本阶段不会执行任何工具。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onStop) { Text("停止规划") }
        }
    }
}

@Composable
private fun AgentTaskStateCard(
    task: AgentTask,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val message = when (task.status) {
        AgentTaskStatus.FAILED -> task.errorMessage.ifBlank { "规划失败，可重试。" }
        AgentTaskStatus.CANCELLED ->
            if (task.errorMessage == AgentTask.USER_ENDED_REASON) {
                "任务完成，${AgentTask.USER_ENDED_REASON}"
            } else {
                "任务已取消。"
            }
        AgentTaskStatus.PLANNING -> "规划可能已中断（页面关闭或进程被回收），可手动重试。"
        else -> "暂无计划。"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.status == AgentTaskStatus.FAILED ||
                    task.status == AgentTaskStatus.PLANNING
                ) {
                    TextButton(onClick = onRetry) { Text("重试规划") }
                }
                TextButton(onClick = onDelete) { Text("删除任务") }
            }
        }
    }
}

@Composable
private fun AgentErrorCard(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canRetry) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}


