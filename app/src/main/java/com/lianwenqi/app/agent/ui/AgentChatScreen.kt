package com.lianwenqi.app.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.ui.screens.AttachmentPreviewDialog
import com.lianwenqi.app.ui.screens.BookPickerDialog
import com.lianwenqi.app.ui.screens.ChatComposer
import com.lianwenqi.app.ui.screens.ContextPicker
import com.lianwenqi.app.ui.screens.OutlinePicker
import com.lianwenqi.app.ui.screens.ModelSelectorChip
import com.lianwenqi.app.ui.screens.TEXT_FILE_MIME_TYPES
import com.lianwenqi.app.ui.screens.attachmentIcon
import com.lianwenqi.app.ui.screens.attachmentLabel
import com.lianwenqi.app.ui.screens.displayNameOf
import kotlinx.coroutines.launch

/**
 * AI 全权接管模式。
 *
 * F3：接收任务、调用独立 Agent 模型、生成并展示计划，等待用户审核。
 * 不执行计划、不调用工具、不修改小说数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    onModeChange: (String) -> Unit
) {
    val vm = appViewModel(key = "agent-main") { AgentChatViewModel(it) }
    val sessions by vm.sessions.collectAsState()
    val currentSession by vm.currentSession.collectAsState()
    val messages by vm.messages.collectAsState()
    val taskMessages by vm.taskMessages.collectAsState()
    val planPublished by vm.planPublished.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val currentTask by vm.currentTask.collectAsState()
    val currentTaskId by vm.currentTaskId.collectAsState()
    val currentPlan by vm.currentPlan.collectAsState()
    val planning by vm.planning.collectAsState()
    val sendingTask by vm.sendingTask.collectAsState()
    val planningTaskId by vm.planningTaskId.collectAsState()
    val planError by vm.planError.collectAsState()
    val planEditError by vm.planEditError.collectAsState()
    val executionNodes by vm.executionNodes.collectAsState()
    val toolExecutions by vm.toolExecutions.collectAsState()
    val executingTaskId by vm.executingTaskId.collectAsState()
    val executionError by vm.executionError.collectAsState()
    val writeOperations by vm.writeOperations.collectAsState()
    val writePolicy by vm.writePolicy.collectAsState()
    val writeNotice by vm.writeNotice.collectAsState()
    val workMode by vm.workMode.collectAsState()
    val pendingClarification by vm.pendingClarification.collectAsState()
    val branchParent by vm.branchParent.collectAsState()
    val branchSiblings by vm.branchSiblings.collectAsState()
    val branchChildren by vm.branchChildren.collectAsState()
    val input by vm.input.collectAsState()
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

    var showHistory by remember { mutableStateOf(false) }
    var showTaskList by remember { mutableStateOf(false) }
    var showBookPicker by remember { mutableStateOf(false) }
    var showChapterPicker by remember { mutableStateOf(false) }
    var showOutlinePicker by remember { mutableStateOf(false) }
    var showBookAgentHistory by remember { mutableStateOf(false) }
    var showReferences by remember { mutableStateOf(false) }
    var showWritePolicy by remember { mutableStateOf(false) }
    var showWorkMode by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }
    var showBatchConfirm by remember { mutableStateOf(false) }
    var previewWriteOperationId by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<AgentSession?>(null) }
    var editingMessageId by remember { mutableStateOf<Long?>(null) }
    var messageDraft by remember { mutableStateOf("") }
    var dismissedClarificationId by remember { mutableStateOf<Long?>(null) }
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

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // 全权接管模式用淡紫色顶栏区分，开关关闭即回到普通 AI 聊天
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFEDE7F6),
                    titleContentColor = Color(0xFF1D1B20),
                    actionIconContentColor = Color(0xFF1D1B20)
                ),
                title = {
                    // 与普通 AI 聊天一致：标题右侧直接选择模型
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Agent 模式")
                        Spacer(Modifier.width(8.dp))
                        ModelSelectorChip(
                            models = models,
                            providers = providers,
                            activeId = modelId,
                            maxLabelWidth = 110.dp,
                            onSelect = vm::setModel
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newSession() }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建 Agent 会话")
                    }
                    TextButton(onClick = { showTaskList = true }) { Text("任务") }
                    TextButton(onClick = { showHistory = true }) { Text("历史") }
                    // 机器人图标与开关固定在最右侧
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = "Agent 模式",
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF5B4B8A)
                    )
                    Switch(
                        checked = true,
                        onCheckedChange = { on ->
                            if (!on) onModeChange(AgentChatMode.NORMAL)
                        },
                        modifier = Modifier.scale(0.75f)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // 绑定书籍：与普通 AI 聊天的书籍选择同位置（顶部标签行）
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssistChip(
                    onClick = { showBookPicker = true },
                    label = {
                        Text(
                            selectedBookTitle ?: "绑定书籍",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = { showChapterPicker = true },
                    enabled = selectedBookId != null && chapters.isNotEmpty(),
                    label = { Text("章节($selectedChapterCount)") }
                )
                AssistChip(
                    onClick = { showOutlinePicker = true },
                    enabled = selectedBookId != null && outline.isNotEmpty(),
                    label = { Text("大纲(${selectedOutlineIds.size})") }
                )
                AssistChip(
                    onClick = {
                        val bookSessionIds = sessions
                            .filter { it.bookId == selectedBookId }
                            .map { it.id }
                            .toSet()
                        vm.beginHistorySelection(bookSessionIds)
                        showBookAgentHistory = true
                    },
                    enabled = selectedBookId != null,
                    label = { Text("历史 Agent") }
                )
                AssistChip(
                    onClick = { showReferences = true },
                    enabled = session != null,
                    label = { Text("已选资料") }
                )
            }
            HorizontalDivider()

            if (session == null) {
                AgentEmptyState(
                    hasHistory = sessions.isNotEmpty(),
                    onNew = { vm.newSession() },
                    onHistory = { showHistory = true },
                    modifier = Modifier.weight(1f)
                )
            } else {
                AgentSessionHeader(
                    session = session,
                    messageCount = messages.size,
                    onRename = { renaming = session }
                )
                HorizontalDivider()

                val task = currentTask
                if (task != null && (
                    branchParent != null || branchSiblings.isNotEmpty() || branchChildren.isNotEmpty()
                    )
                ) {
                    AgentBranchBar(
                        current = task,
                        parent = branchParent,
                        siblingCount = branchSiblings.size,
                        childCount = branchChildren.size,
                        onOpen = { showBranches = true }
                    )
                }

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    val visibleMessages = taskMessages.ifEmpty {
                        if (currentTask == null) messages.filter { it.taskId == null } else emptyList()
                    }.filterNot { message ->
                        // 澄清草稿不展示计划消息；兼容早期版本留下的空计划消息
                        message.kind == AgentMessageKind.PLAN &&
                            (message.content.isBlank() ||
                                currentPlan?.plan?.needsClarification == true)
                    }
                    visibleMessages.forEach { message ->
                        AgentMessageRow(
                            message = message,
                            editable = message.kind == AgentMessageKind.USER_INPUT &&
                                message.content == currentTask?.userGoal &&
                                !planning && !executingCurrent && !sendingTask,
                            editing = editingMessageId == message.id,
                            draft = messageDraft,
                            onDraftChange = { messageDraft = it },
                            onCopy = { copyToClipboard(context, message.content) },
                            onStartEdit = {
                                editingMessageId = message.id
                                messageDraft = message.content
                            },
                            onCancelEdit = { editingMessageId = null },
                            onRegenerate = { vm.regenerateUserMessage(message.id) },
                            onSubmitEdit = {
                                vm.editUserMessageAndRegenerate(message.id, messageDraft) {
                                    editingMessageId = null
                                }
                            }
                        )
                        HorizontalDivider()
                    }

                    val pending = pendingClarification
                    if (pending != null) {
                        AgentClarificationPendingCard(
                            clarification = pending,
                            onOpen = { dismissedClarificationId = null }
                        )
                    } else if (planning) {
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
                        planPublished && pending == null && !planView.plan.needsClarification
                    ) {
                        AgentPlanCard(
                            planView = planView,
                            task = planTask,
                            planning = planning,
                            executing = executingCurrent,
                            hasPendingWrites = writeOperations.any { it.unresolved },
                            branchesAvailable = branchParent != null ||
                                branchSiblings.isNotEmpty() || branchChildren.isNotEmpty(),
                            planEditing = planEditing,
                            editingPlan = planDraft,
                            planEditError = planEditError,
                            onPlanChange = { planDraft = it },
                            onStartPlanEdit = {
                                planDraft = planView.plan.copy(
                                    steps = planView.plan.steps.map {
                                        it.copy(
                                            riskLevel = AgentRiskLevel.normalize(it.riskLevel)
                                        )
                                    }
                                )
                                vm.clearPlanEditError()
                                planEditing = true
                            },
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
                            onOpenBranches = { showBranches = true },
                            onRegenerate = vm::regeneratePlan,
                            onConfirm = vm::confirmPlan,
                            onStartExecution = vm::startReadOnlyExecution,
                            onPauseTask = { vm.pauseTask(planTask.id) },
                            onResumeTask = { vm.resumeTask(planTask.id) },
                            onCancelTask = { vm.cancelTask(planTask.id) },
                            onDeleteTask = { vm.deleteTask(planTask.id) }
                        )
                    }

                    AgentWriteOperationsCard(
                        operations = writeOperations,
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

                    AgentExecutionCard(
                        nodes = executionNodes,
                        toolExecutions = toolExecutions,
                        executing = executingCurrent,
                        error = executionError,
                        onCancel = vm::cancelExecution,
                        onDismissError = vm::clearExecutionError
                    )

                    if (visibleMessages.isEmpty() &&
                        currentTask == null &&
                        currentPlan == null &&
                        !planning &&
                        planError == null
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (workMode == AgentWorkMode.AUTO) {
                                    "随便说点什么吧"
                                } else {
                                    "输入任务目标，Agent 将分析并生成执行计划。\n" +
                                        "当前阶段不会执行计划或修改小说数据。"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (!modelSelected) {
                Text(
                    "请先选择可用模型，再发送任务。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 输入栏工具条：左侧快捷功能可滚动，写入权限图标固定在最右侧
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        showPrompts = !showPrompts
                    }
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "常用提示词",
                        tint = if (showPrompts) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Agent 默认启用全部已注册工具，因此不加数字角标，图标保持选中色
                IconButton(onClick = { showTools = true }) {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = "Agent 工具（已全部启用）",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = "添加图片",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { filePicker.launch(TEXT_FILE_MIME_TYPES) }) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "添加文件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                    IconButton(
                        onClick = {
                            linkInput = ""
                            showLinkDialog = true
                        }
                    ) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = "添加链接",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 工作模式 + 写入权限：固定在输入栏最右侧
                WorkModeChip(
                    mode = workMode,
                    onClick = { showWorkMode = true }
                )
                WritePermissionBadge(
                    policy = writePolicy,
                    onClick = { showWritePolicyMenu = true }
                )
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

            ChatComposer(
                value = input,
                onValueChange = vm::setInput,
                placeholder = "向 Agent 下达任务目标…",
                sendEnabled = !planning && !sendingTask && modelSelected &&
                    (input.isNotBlank() || pendingAttachments.isNotEmpty()),
                onSend = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    vm.sendTask()
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

    if (showWorkMode) {
        AgentWorkModeDialog(
            current = workMode,
            onSelect = {
                vm.setWorkMode(it)
                showWorkMode = false
            },
            onDismiss = { showWorkMode = false }
        )
    }

    if (showBranches) {
        currentTask?.let { task ->
            AgentBranchDialog(
                current = task,
                parent = branchParent,
                siblings = branchSiblings,
                children = branchChildren,
                onOpen = {
                    vm.openTask(it)
                    showBranches = false
                },
                onDismiss = { showBranches = false }
            )
        }
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

    if (showTaskList) {
        AgentTaskListDialog(
            tasks = tasks,
            currentTaskId = currentTaskId,
            planningTaskId = planningTaskId,
            onOpen = {
                vm.openTask(it)
                showTaskList = false
            },
            onOpenDetails = {
                vm.openTaskDetails(it)
                showTaskList = false
            },
            onDelete = vm::deleteTask,
            onDismiss = { showTaskList = false }
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
        AgentWritePolicyDialog(
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

    renaming?.let { target ->
        var title by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("修改 Agent 会话标题") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("会话标题") },
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

@Composable
private fun AgentEmptyState(
    hasHistory: Boolean,
    onNew: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Agent 模式", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text(
                "你可以在这里向 Agent 下达任务。\n" +
                    "Agent 会分析目标、生成计划；确认后可执行查询、生成草稿与受控写入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onNew) { Text("新建 Agent 任务") }
            if (hasHistory) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onHistory) { Text("查看历史会话") }
            }
        }
    }
}

@Composable
private fun AgentSessionHeader(
    session: AgentSession,
    messageCount: Int,
    onRename: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                session.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$messageCount 条消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = "修改会话标题")
            }
        }
    }
}

@Composable
private fun AgentMessageRow(
    message: AgentMessage,
    editable: Boolean,
    editing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onCopy: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onSubmitEdit: () -> Unit
) {
    val isUser = message.role == AgentMessageRole.USER
    val label = when {
        message.kind == AgentMessageKind.NOTICE -> "系统"
        message.kind == AgentMessageKind.PLAN -> "Agent 计划"
        isUser -> "你"
        else -> "Agent"
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
                ) { Text("重新生成") }
                TextButton(onClick = onCancelEdit) { Text("取消") }
            }
            Text(
                "提交后会创建新分支，原提问与计划保持不变。",
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
            }
        }
    }
}

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

/** Agent 章节上下文选择弹窗：复用侧边栏的上下文勾选 UI */
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
    val allChapterIds = chapters.map { it.id }.toSet()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth(0.94f),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column {
                ContextPicker(
                    chapters = chapters,
                    volumes = volumes,
                    title = "选择章节",
                    selected = summarySelected + contentSelected,
                    summarySelected = summarySelected,
                    contentSelected = contentSelected,
                    activeVolumeSummaries = activeVolumeSummaries,
                    selectedVolumeSummaryIds = selectedVolumeSummaryIds,
                    onToggleVolumeSummaryAsset = onToggleVolumeSummaryAsset,
                    focusVolumeId = volumes.firstOrNull()?.id,
                    allSelected = allChapterIds.isNotEmpty() &&
                        allChapterIds.all { it in summarySelected && it in contentSelected },
                    summaryAll = allChapterIds.isNotEmpty() &&
                        allChapterIds.all { it in summarySelected },
                    contentAll = allChapterIds.isNotEmpty() &&
                        allChapterIds.all { it in contentSelected },
                    volumeSummaryAll = activeVolumeSummaries.isNotEmpty() &&
                        activeVolumeSummaries.all { it.id in selectedVolumeSummaryIds },
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
                    }
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

// 完整权限图标（viewBox 12×12）：d=低、z=中、g=高
private const val PERMISSION_LOW_ICON =
    "M0.0506602 0.5C0.0506602 0.5 1.88554 1.73063 3.05066 1.5C4.16756 1.27892 5.55066 0 5.55066 0V1.5" +
        "C5.55066 1.5 4.10186 2.47385 3.05066 2.5C2.24583 2.52002 1.05066 2 1.05066 2" +
        "C1.05066 2 1.07814 4.21744 1.55066 5.5C1.98289 6.6732 2.30865 7.13643 3.05066 8" +
        "C3.86548 8.94831 5.55066 10 5.55066 10C5.55066 10 7.23584 8.94831 8.05066 8" +
        "C8.79267 7.13643 9.11843 6.6732 9.55066 5.5C10.0232 4.21744 10.0507 2 10.0507 2" +
        "C10.0507 2 8.8555 2.52002 8.05066 2.5C6.99947 2.47385 5.55066 1.5 5.55066 1.5V0" +
        "C5.55066 0 6.93377 1.27892 8.05066 1.5C9.21579 1.73063 11.0507 0.5 11.0507 0.5" +
        "C11.0507 0.5 11.3364 4.21245 10.5507 6C10.0475 7.14459 9.75302 7.60389 9.05066 8.5" +
        "C7.94014 9.91688 5.55066 11.5 5.55066 11.5C5.55066 11.5 3.16119 9.91688 2.05066 8.5" +
        "C1.3483 7.60389 1.05378 7.14459 0.55066 6C-0.235078 4.21245 0.0506602 0.5 0.0506602 0.5Z" +
        "M3 7H8C8 7 7.42289 7.64477 7 8C6.46092 8.45282 5.5 9 5.5 9C5.5 9 4.53908 8.45282 4 8" +
        "C3.57711 7.64477 3 7 3 7Z"

private const val PERMISSION_MID_ICON =
    "M0.0506602 0.5C0.0506602 0.5 1.88554 1.73063 3.05066 1.5C4.16756 1.27892 5.55066 0 5.55066 0V1.5" +
        "C5.55066 1.5 4.10186 2.47385 3.05066 2.5C2.24583 2.52002 1.05066 2 1.05066 2" +
        "C1.05066 2 1.07814 4.21744 1.55066 5.5C1.98289 6.6732 2.30865 7.13643 3.05066 8" +
        "C3.86548 8.94831 5.55066 10 5.55066 10C5.55066 10 7.23584 8.94831 8.05066 8" +
        "C8.79267 7.13643 9.11843 6.6732 9.55066 5.5C10.0232 4.21744 10.0507 2 10.0507 2" +
        "C10.0507 2 8.8555 2.52002 8.05066 2.5C6.99947 2.47385 5.55066 1.5 5.55066 1.5V0" +
        "C5.55066 0 6.93377 1.27892 8.05066 1.5C9.21579 1.73063 11.0507 0.5 11.0507 0.5" +
        "C11.0507 0.5 11.3364 4.21245 10.5507 6C10.0475 7.14459 9.75302 7.60389 9.05066 8.5" +
        "C7.94014 9.91688 5.55066 11.5 5.55066 11.5C5.55066 11.5 3.16119 9.91688 2.05066 8.5" +
        "C1.3483 7.60389 1.05378 7.14459 0.55066 6C-0.235078 4.21245 0.0506602 0.5 0.0506602 0.5Z" +
        "M3 7H8C8 7 7.42289 7.64477 7 8C6.46092 8.45282 5.5 9 5.5 9C5.5 9 4.53908 8.45282 4 8" +
        "C3.57711 7.64477 3 7 3 7Z" +
        "M1.75626 4.5H9.17213C9.17213 4.5 9.12085 5.12261 9.00001 5.5C8.86685 5.91582 8.5 6.5 8.5 6.5" +
        "H2.5C2.5 6.5 2.14837 5.91064 2.00001 5.5C1.86342 5.12196 1.75626 4.5 1.75626 4.5Z"

private const val PERMISSION_HIGH_ICON =
    "M0.0506602 0.5C0.0506602 0.5 1.88554 1.73063 3.05066 1.5C4.16756 1.27892 5.55066 0 5.55066 0V1.5" +
        "C5.55066 1.5 4.10186 2.47385 3.05066 2.5C2.24583 2.52002 1.05066 2 1.05066 2" +
        "C1.05066 2 1.07814 4.21744 1.55066 5.5C1.98289 6.6732 2.30865 7.13643 3.05066 8" +
        "C3.86548 8.94831 5.55066 10 5.55066 10C5.55066 10 7.23584 8.94831 8.05066 8" +
        "C8.79267 7.13643 9.11843 6.6732 9.55066 5.5C10.0232 4.21744 10.0507 2 10.0507 2" +
        "C10.0507 2 8.8555 2.52002 8.05066 2.5C6.99947 2.47385 5.55066 1.5 5.55066 1.5V0" +
        "C5.55066 0 6.93377 1.27892 8.05066 1.5C9.21579 1.73063 11.0507 0.5 11.0507 0.5" +
        "C11.0507 0.5 11.3364 4.21245 10.5507 6C10.0475 7.14459 9.75302 7.60389 9.05066 8.5" +
        "C7.94014 9.91688 5.55066 11.5 5.55066 11.5C5.55066 11.5 3.16119 9.91688 2.05066 8.5" +
        "C1.3483 7.60389 1.05378 7.14459 0.55066 6C-0.235078 4.21245 0.0506602 0.5 0.0506602 0.5Z" +
        "M3 7H8C8 7 7.42289 7.64477 7 8C6.46092 8.45282 5.5 9 5.5 9C5.5 9 4.53908 8.45282 4 8" +
        "C3.57711 7.64477 3 7 3 7Z" +
        "M1.75626 4.5H9.17213C9.17213 4.5 9.12085 5.12261 9.00001 5.5C8.86685 5.91582 8.5 6.5 8.5 6.5" +
        "H2.5C2.5 6.5 2.14837 5.91064 2.00001 5.5C1.86342 5.12196 1.75626 4.5 1.75626 4.5Z" +
        "M1.5 2.5C1.5 2.5 2.69849 3.07582 3.5 3C4.36936 2.91776 5.5 2 5.5 2" +
        "C5.5 2 6.63064 2.91776 7.5 3C8.30151 3.07582 9.5 2.5 9.5 2.5L9.24219 4H1.66406L1.5 2.5Z"

/**
 * 高 / 中 / 低权限完整图标（d / z / g），按当前权限回显。
 */
@Composable
private fun WritePermissionBadge(policy: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val pathData = when (policy) {
        AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL -> PERMISSION_HIGH_ICON
        AgentWritePermissionPolicy.BATCH_CONFIRM -> PERMISSION_MID_ICON
        else -> PERMISSION_LOW_ICON
    }
    val iconPath = remember(policy) { PathParser().parsePathString(pathData).toPath() }
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val scale = kotlin.math.min(size.width, size.height) / 12f
            val dx = (size.width - 12f * scale) / 2f
            val dy = (size.height - 12f * scale) / 2f
            withTransform({
                translate(dx, dy)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawPath(iconPath, color = color)
            }
        }
    }
}

/** 工作模式入口：与写入权限并排，只影响后续新建任务 */
@Composable
private fun WorkModeChip(mode: String, onClick: () -> Unit) {
    val auto = mode == AgentWorkMode.AUTO
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (auto) Icons.Filled.PlayArrow else Icons.AutoMirrored.Filled.List,
            contentDescription = "工作模式：${agentWorkModeLabel(mode)}",
            modifier = Modifier.size(20.dp),
            tint = if (auto) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (auto) "自动" else "计划",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 工作模式选择弹窗 */
@Composable
private fun AgentWorkModeDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("任务模式") },
        text = {
            Column {
                Text(
                    "模式在创建任务时固化；已创建任务保持其创建时的模式。" +
                        "写入操作仍需按写入权限确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                WritePermissionOption(
                    title = "自动模式",
                    description = "Agent 自行判断是否需要规划或澄清；计划就绪后自动开始执行。",
                    selected = current == AgentWorkMode.AUTO,
                    onClick = { onSelect(AgentWorkMode.AUTO) }
                )
                WritePermissionOption(
                    title = "计划模式",
                    description = "先生成计划并等待你确认，确认后才允许开始执行。",
                    selected = current == AgentWorkMode.PLAN,
                    onClick = { onSelect(AgentWorkMode.PLAN) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 写入权限选择弹窗 */
@Composable
private fun WritePermissionDialog(
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
                    selected = current == AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL,
                    onClick = { onSelect(AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL) }
                )
                WritePermissionOption(
                    title = "中权限",
                    description = "一般写入先汇总为待确认列表，由你一次性批量批准；高风险与破坏性操作仍需逐条确认。",
                    selected = current == AgentWritePermissionPolicy.BATCH_CONFIRM,
                    onClick = { onSelect(AgentWritePermissionPolicy.BATCH_CONFIRM) }
                )
                WritePermissionOption(
                    title = "低权限",
                    description = "所有写入（包括一般写入）都需要你逐条确认后才执行。",
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
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        AgentTaskStatus.CANCELLED -> "任务已取消。"
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


