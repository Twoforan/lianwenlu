package com.lianwenqi.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.ChatSession
import com.lianwenqi.app.data.entity.ContextSnapshot
import com.lianwenqi.app.data.entity.decodeContextSnapshot
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.ui.components.BookFormResult
import com.lianwenqi.app.ui.components.BookFormDialog
import com.lianwenqi.app.util.AI_TOOLS
import com.lianwenqi.app.util.AI_TOOL_IMPORT_CHAPTER
import com.lianwenqi.app.util.AI_TOOL_WRITE_OUTLINE
import com.lianwenqi.app.util.AiToolInfo
import com.lianwenqi.app.util.TextTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen(initialBookId: Long?) {
    val vm = appViewModel(key = "ai-main") { AiChatViewModel(it, initialBookId) }
    val models by vm.models.collectAsState()
    val providers by vm.providers.collectAsState()
    val books by vm.books.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val messages by vm.messages.collectAsState()
    val sessionId by vm.sessionId.collectAsState()
    val input by vm.input.collectAsState()
    val sending by vm.sending.collectAsState()
    val activeModelId by vm.activeModelId.collectAsState()
    val prompts by vm.prompts.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val outline by vm.outline.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val groups by vm.groups.collectAsState()
    val selBooks by vm.selectedBookIds.collectAsState()
    val selChapters by vm.selectedChapterIds.collectAsState()
    val selOutline by vm.selectedOutlineIds.collectAsState()
    val incContent by vm.includeContent.collectAsState()
    val incSummary by vm.includeSummary.collectAsState()
    val errorMsg by vm.errorMessage.collectAsState()
    val editingUserMsgId by vm.editingUserMessageId.collectAsState()
    val pendingReplyTo by vm.pendingReplyToId.collectAsState()
    val streamingContent by vm.streamingContent.collectAsState()
    val streamingReasoning by vm.streamingReasoning.collectAsState()
    val chatTools by vm.chatTools.collectAsState()
    val outlineToolEnabled by vm.outlineToolEnabled.collectAsState()
    val chapterToolEnabled by vm.chapterToolEnabled.collectAsState()
    val pendingImports by vm.pendingImports.collectAsState()
    val importToast by vm.importToast.collectAsState()
    val memoryRetrieval by vm.memoryRetrievalEnabled.collectAsState()
    val memorySearch by vm.memorySearchState.collectAsState()
    val memoryAsk by vm.pendingMemoryAsk.collectAsState()
    val memoryVectorizing by vm.memoryVectorizing.collectAsState()
    val memoryEmptyAsk by vm.pendingEmptyVectorizeAsk.collectAsState()
    val memoryToastMsg by vm.memoryToast.collectAsState()
    val memoryToolReady by vm.memoryToolConfigured.collectAsState()

    var showBooks by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showAiTools by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var renamingSession by remember { mutableStateOf<ChatSession?>(null) }
    var showPrompts by remember { mutableStateOf(false) }
    var manifestState by remember { mutableIntStateOf(0) }

    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val ui = remember { MessageUiState() }
    val modelName = models.firstOrNull { it.id == activeModelId }?.displayName ?: "未选择模型"
    BackHandler(enabled = ui.editingId != null) { ui.editingId = null }

    Scaffold(
        // 顶部内边距交给 TopAppBar，底部由 union(navigationBars, ime) 统一处理
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    // 模型选择移到标题右侧，与「AI 聊天」同排
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI 聊天")
                        Spacer(Modifier.width(8.dp))
                        ModelSelectorChip(
                            models = models,
                            providers = providers,
                            activeId = activeModelId,
                            maxLabelWidth = 110.dp,
                            onSelect = { vm.setModel(it) }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newSession() }) {
                        Icon(Icons.Filled.Add, contentDescription = "新对话")
                    }
                    TextButton(onClick = { showSessions = true }) { Text("会话") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // 底部取「导航栏 / 输入法」较大者，避免两者叠加产生空白
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
        ) {
            // 上下文选择（未选书籍时不能选择章节 / 大纲 / 仅梗概 / 仅正文）
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val selectedBook = selBooks.singleOrNull()?.let { id -> books.firstOrNull { it.id == id } }
                AssistChip(
                    onClick = { showBooks = true },
                    label = {
                        Text(
                            selectedBook?.title ?: "选择书籍",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 140.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = { if (selBooks.isNotEmpty()) showChapters = true },
                    enabled = selBooks.isNotEmpty(),
                    label = { Text("章节(${selChapters.size})") }
                )
                AssistChip(
                    onClick = { if (selBooks.isNotEmpty()) showOutline = true },
                    enabled = selBooks.isNotEmpty(),
                    label = { Text("大纲(${selOutline.size})") }
                )
                FilterChip(
                    selected = incSummary,
                    onClick = { vm.includeSummary.value = !incSummary },
                    enabled = selBooks.isNotEmpty(),
                    label = { Text("仅梗概") }
                )
                FilterChip(
                    selected = incContent,
                    onClick = { vm.includeContent.value = !incContent },
                    enabled = selBooks.isNotEmpty(),
                    label = { Text("仅正文") }
                )
            }

            HorizontalDivider()

            val entries = remember(messages) { buildChatEntries(messages) }
            val listState = rememberLazyListState()
            // 进入会话 / 切换会话 / 新增消息后，自动停在最新对话处（滚到真正的底部，而不是最后一条的开头）。
            // 必须先等 LazyColumn 完成一次布局（layoutInfo 有内容）再滚动：
            // 首次进入时消息可能已就绪、列表却尚未测量，此时滚动会被忽略而停在最上方。
            LaunchedEffect(sessionId, entries.size) {
                if (entries.isEmpty()) return@LaunchedEffect
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                listState.scrollToBottom()
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        ui.pendingDeleteId = null
                        // 点按别处：取消文本选中状态（重建所有 SelectionContainer）
                        ui.selectionReset++
                    }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(entries) { entry ->
                        ChatMessageItem(
                            entry = entry,
                            modelName = modelName,
                            sending = sending,
                            pendingReplyToId = pendingReplyTo,
                            streamingContent = streamingContent,
                            streamingReasoning = streamingReasoning,
                            ui = ui,
                            chapters = chapters,
                            outline = outline,
                            previewFor = { vm.previewFor(it) },
                            onEditUser = { vm.beginEditUserMessage(it) },
                            onRegenerate = { vm.regenerate(it) },
                            onCopy = { clipboard.setText(AnnotatedString(it)) },
                            onDelete = { vm.deleteMessage(it) },
                            onSaveEdit = { id, text -> vm.updateMessage(id, text) },
                            onSaveAndSend = { id, text, uid -> vm.saveAndContinue(id, text, uid) },
                            onSwitchVariant = { uid, mid -> vm.switchVariant(uid, mid) },
                            onToggleContent = { id, collapsed -> vm.setContentCollapsed(id, collapsed) },
                            onStop = { vm.stopGenerating() }
                        )
                    }
                }
            }

            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp))
            }

            // 当前上下文快照，用于底部「文件清单」回显
            val currentSnapshot = ContextSnapshot(
                bookIds = selBooks.toList(),
                chapterIds = selChapters.toList(),
                outlineIds = selOutline.toList(),
                includeSummary = incSummary,
                includeContent = incContent
            )
            val manifestChapters = selChapters.map { id ->
                chapters.firstOrNull { it.id == id }?.title ?: "（章节已删除）"
            }
            val manifestOutline = selOutline.map { id ->
                outline.firstOrNull { it.id == id }
                    ?.let { "${outlineCategoryLabel(it.category)}·${it.title}" } ?: "（大纲已删除）"
            }
            val manifestCount = manifestChapters.size + manifestOutline.size

            // 底部工具条：常用提示词 / AI工具，右侧文件清单；超出宽度可横向滑动
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = showPrompts,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        showPrompts = !showPrompts
                        if (showPrompts) manifestState = 0
                    },
                    label = { Text("常用提示词") }
                )
                // 长期记忆检索：勾选后发送时按「本章梗概 + 提示词」检索本书正文片段注入参考资料
                // 未配置向量化工具（未选向量模型 / 未启用本地工具）时不可勾选
                FilterChip(
                    selected = memoryRetrieval,
                    enabled = memoryToolReady,
                    onClick = { vm.toggleMemoryRetrieval() },
                    label = { Text("记忆检索") }
                )
                MemorySearchIndicator(memorySearch, memoryRetrieval)
                // AI 工具多选（被工具页关闭的工具不出现在列表里）
                if (outlineToolEnabled || chapterToolEnabled) {
                    AssistChip(
                        onClick = { showAiTools = true },
                        label = { Text("AI工具(${chatTools.size})") }
                    )
                }
                if (manifestCount > 0) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                manifestState = if (manifestState == 0) 1 else 0
                                if (manifestState != 0) showPrompts = false
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("文件清单（$manifestCount）", style = MaterialTheme.typography.bodySmall)
                        Icon(
                            if (manifestState == 0) Icons.Filled.KeyboardArrowDown
                            else Icons.Filled.KeyboardArrowUp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 文件清单内容（复用清单表格 / txt 渲染）
            if (manifestState != 0) {
                ContextManifestContent(
                    state = manifestState,
                    snapshot = currentSnapshot,
                    chapterRows = manifestChapters,
                    outlineRows = manifestOutline,
                    preview = { vm.contextPreview() },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 「查看txt」旁边：一键复制整份 txt
                    if (manifestState == 1) {
                        CopyContextAction(text = { vm.contextPreview() })
                    }
                    TextButton(onClick = { manifestState = if (manifestState == 2) 1 else 2 }) {
                        Text(if (manifestState == 2) "返回清单" else "查看txt")
                    }
                }
            }

            // 常用提示词面板
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
                    items(prompts, key = { it.id }) { p ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    showPrompts = false
                                    vm.appendPrompt(p)
                                }
                                .padding(12.dp)
                        ) {
                            Text(p.title, fontWeight = FontWeight.SemiBold)
                            if (p.content.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    p.content,
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

            if (editingUserMsgId > 0L) {
                EditPromptBanner(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onCancel = { vm.cancelEditUserMessage() }
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { vm.input.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么…") },
                    maxLines = 5
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = !sending && input.isNotBlank(),
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        vm.send()
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }

    if (showBooks) {
        BookPickerDialog(
            books = books,
            groups = groups,
            selectedId = selBooks.singleOrNull(),
            onSelect = { vm.selectBook(it) },
            onCreateBook = { r ->
                vm.createBookFull(r.title, r.intro, r.coverPath, r.coverColor, r.volumePrefix, r.chapterPrefix, r.groupId)
            },
            onPickCover = { uri, cb -> scope.launch { cb(vm.pickCover(uri)) } },
            onCreateGroup = { vm.createGroupAndGet(it) },
            onDismiss = { showBooks = false }
        )
    }
    if (showChapters) {
        ChapterPickerDialog(
            chapters = chapters,
            volumes = volumes,
            selected = selChapters,
            onToggle = { vm.toggleChapter(it) },
            onToggleAll = { vm.selectAllChapters() },
            onDismiss = { showChapters = false }
        )
    }
    if (showOutline) {
        OutlinePickerDialog(
            items = outline,
            selected = selOutline,
            onToggle = { vm.toggleOutline(it) },
            onDismiss = { showOutline = false }
        )
    }
    if (showAiTools) {
        AiToolsPickerDialog(
            tools = AI_TOOLS.filter {
                (it.id == AI_TOOL_WRITE_OUTLINE && outlineToolEnabled) ||
                    (it.id == AI_TOOL_IMPORT_CHAPTER && chapterToolEnabled)
            },
            selected = chatTools,
            onToggle = { vm.toggleChatTool(it) },
            onDismiss = { showAiTools = false }
        )
    }
    // AI 工具：回答里解析出可导入内容时，挨个询问是否导入（大纲 / 章节梗概）
    pendingImports.firstOrNull()?.let { head ->
        key(head) {
            when (head) {
                is PendingImport.Outline -> ImportConfirmDialog(
                    title = "写入大纲",
                    message = "AI 生成了 ${head.blocks.size} 个大纲区块（${
                        head.blocks.map { it.name }.joinToString("、")
                    }），是否写入作品大纲？",
                    confirmText = "写入大纲",
                    books = books,
                    groups = groups,
                    resolveTarget = { vm.importTargetBookId() },
                    onCreateBook = { r ->
                        vm.createBookFull(r.title, r.intro, r.coverPath, r.coverColor, r.volumePrefix, r.chapterPrefix, r.groupId)
                    },
                    onPickCover = { uri, cb -> scope.launch { cb(vm.pickCover(uri)) } },
                    onCreateGroup = { vm.createGroupAndGet(it) },
                    onConfirm = { vm.confirmOutlineImport(it) },
                    onDismiss = { vm.dismissPendingImport() }
                )
                is PendingImport.Chapters -> ImportConfirmDialog(
                    title = "导入章节梗概",
                    message = "AI 生成了 ${head.blocks.size} 条章节梗概（${
                        head.blocks.map { it.chapterName }.joinToString("、")
                    }），是否写入章节？同名章节将覆盖梗概。",
                    confirmText = "导入",
                    books = books,
                    groups = groups,
                    resolveTarget = { vm.importTargetBookId() },
                    onCreateBook = { r ->
                        vm.createBookFull(r.title, r.intro, r.coverPath, r.coverColor, r.volumePrefix, r.chapterPrefix, r.groupId)
                    },
                    onPickCover = { uri, cb -> scope.launch { cb(vm.pickCover(uri)) } },
                    onCreateGroup = { vm.createGroupAndGet(it) },
                    onConfirm = { vm.confirmChapterImport(it) },
                    onDismiss = { vm.dismissPendingImport() }
                )
            }
        }
    }
    // 导入结果 Toast
    val toastContext = LocalContext.current
    LaunchedEffect(importToast) {
        importToast?.let {
            Toast.makeText(toastContext, it, Toast.LENGTH_LONG).show()
            vm.clearImportToast()
        }
    }
    // A3 关闭时：正文有更新，发送前询问是否向量化到最新章节
    memoryAsk?.let {
        AlertDialog(
            onDismissRequest = { vm.resolveMemoryAsk(false) },
            title = { Text("正文有更新") },
            text = {
                Text(
                    "本书正文比已向量化的进度更新，是否向量化到最新章节？\n\n" +
                        "选「是」：先续跑向量化，再用最新进度检索参考资料；\n" +
                        "选「否」：只用之前已向量化的进度检索。\n\n" +
                        "检索结果只作为本轮临时参考资料，不写入会话前缀、不在对话中显示。"
                )
            },
            confirmButton = { TextButton(onClick = { vm.resolveMemoryAsk(true) }) { Text("是") } },
            dismissButton = { TextButton(onClick = { vm.resolveMemoryAsk(false) }) { Text("否") } }
        )
    }
    // 向量化进行中的轻提示（避免等待时界面看起来卡住）。
    // 只以书籍为键：一轮向量化只提示一次，不再随每章进度重复弹出一串 toast
    LaunchedEffect(memoryVectorizing?.bookId) {
        memoryVectorizing?.let { p ->
            val where = listOf(p.volumeTitle, p.chapterTitle).filter { it.isNotBlank() }.joinToString(" ")
            Toast.makeText(
                toastContext,
                if (where.isBlank()) "正在向量化本书…" else "正在向量化本书：$where（${p.done}/${p.total}）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    // 本书从未向量化时勾选记忆检索：先问是否向量化全书，再决定是否勾选
    if (memoryEmptyAsk) {
        AlertDialog(
            onDismissRequest = { vm.resolveEmptyVectorizeAsk(false) },
            title = { Text("当前书本没有向量化") },
            text = {
                Text(
                    "当前书本还没有向量化，是否开启向量化？\n\n" +
                        "选「可以」：开始向量化全书，并勾选记忆检索；\n" +
                        "选「不可以」：取消记忆检索勾选。"
                )
            },
            confirmButton = { TextButton(onClick = { vm.resolveEmptyVectorizeAsk(true) }) { Text("可以") } },
            dismissButton = { TextButton(onClick = { vm.resolveEmptyVectorizeAsk(false) }) { Text("不可以") } }
        )
    }
    // 记忆检索相关提示（如未选择书籍）
    LaunchedEffect(memoryToastMsg) {
        memoryToastMsg?.let {
            Toast.makeText(toastContext, it, Toast.LENGTH_SHORT).show()
            vm.clearMemoryToast()
        }
    }
    if (showSessions) {
        // 0 全部 / 1 跟书 / 2 跟章节 / 3 未绑定
        var kind by remember { mutableIntStateOf(0) }
        var search by remember { mutableStateOf("") }
        // 分组筛选：null=全部，-1=未分组，其余为分组 id
        var filterGroupId by remember { mutableStateOf<Long?>(null) }
        // 书籍筛选：null=全部，其余为书籍 id
        var filterBookId by remember { mutableStateOf<Long?>(null) }
        var filterVolumeId by remember { mutableStateOf<Long?>(null) }
        var filterChapterId by remember { mutableStateOf<Long?>(null) }
        val filterVolumes by vm.filterVolumes.collectAsState()
        val filterChapters by vm.filterChapters.collectAsState()

        // 当前分组筛选下可见的书籍
        val groupBooks = books.filter { b ->
            when (filterGroupId) {
                null -> true
                -1L -> b.groupId == null
                else -> b.groupId == filterGroupId
            }
        }

        val filtered = remember(sessions, kind, search, filterGroupId, filterBookId, filterVolumeId, filterChapterId, filterChapters, books) {
            sessions.filter { s ->
                val matchKind = when (kind) {
                    1 -> s.chapterId == null && s.bookId != null
                    2 -> s.chapterId != null
                    3 -> s.bookId == null
                    else -> true
                }
                val matchBook = filterBookId == null || s.bookId == filterBookId
                val matchGroup = when (filterGroupId) {
                    null -> true
                    -1L -> books.firstOrNull { it.id == s.bookId }?.groupId == null && s.bookId != null
                    else -> books.firstOrNull { it.id == s.bookId }?.groupId == filterGroupId
                }
                val matchVolume = filterVolumeId == null ||
                    filterChapters.firstOrNull { it.id == s.chapterId }?.volumeId == filterVolumeId
                val matchChapter = filterChapterId == null || s.chapterId == filterChapterId
                val matchSearch = search.isBlank() ||
                    s.title.contains(search.trim(), ignoreCase = true)
                matchKind && matchBook && matchGroup && matchVolume && matchChapter && matchSearch
            }
        }

        AlertDialog(
            onDismissRequest = { showSessions = false },
            title = { Text("会话列表") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = { Text("搜索对话") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    // 类型筛选：窄屏时横向滚动，标签强制单行，避免「未绑定」被挤成多行
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = kind == 0, onClick = { kind = 0 },
                            label = { Text("全部", maxLines = 1, softWrap = false) })
                        FilterChip(selected = kind == 1, onClick = { kind = 1 },
                            label = { Text("跟书", maxLines = 1, softWrap = false) })
                        FilterChip(selected = kind == 2, onClick = { kind = 2 },
                            label = { Text("跟章节", maxLines = 1, softWrap = false) })
                        FilterChip(selected = kind == 3, onClick = { kind = 3 },
                            label = { Text("未绑定", maxLines = 1, softWrap = false) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterDropdown(
                            label = "全部分组",
                            options = listOf<Pair<Long?, String>>(null to "全部分组", -1L to "未分组") +
                                groups.map { it.id to it.name },
                            selectedId = filterGroupId,
                            onSelect = { gid ->
                                filterGroupId = gid
                                // 当前选中书籍不在新分组内时清空书籍筛选
                                val cur = filterBookId
                                if (cur != null && cur != -1L && cur !in groupBooks.map { it.id }) {
                                    filterBookId = null
                                    filterVolumeId = null
                                    filterChapterId = null
                                }
                            }
                        )
                        FilterDropdown(
                            label = "全部书籍",
                            options = listOf<Pair<Long?, String>>(null to "全部书籍") +
                                groupBooks.map { it.id to it.title },
                            selectedId = filterBookId,
                            onSelect = {
                                filterBookId = it
                                filterVolumeId = null
                                filterChapterId = null
                                if (it != null) vm.loadFilterContext(it)
                                else vm.loadFilterContext(null)
                            }
                        )
                    }
                    if (kind == 2 && filterBookId != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterDropdown(
                                label = "全部分卷",
                                options = listOf<Pair<Long?, String>>(null to "全部分卷") +
                                    filterVolumes.map { it.id to it.title },
                                selectedId = filterVolumeId,
                                onSelect = { filterVolumeId = it; filterChapterId = null }
                            )
                            FilterDropdown(
                                label = "全部章节",
                                options = listOf<Pair<Long?, String>>(null to "全部章节") +
                                    filterChapters
                                        .filter { filterVolumeId == null || it.volumeId == filterVolumeId }
                                        .map { it.id to it.title },
                                selectedId = filterChapterId,
                                onSelect = { filterChapterId = it }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    if (filtered.isEmpty()) {
                        Text("暂无匹配的会话", Modifier.padding(vertical = 12.dp))
                    }
                    filtered.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                Modifier
                                    .weight(1f)
                                    .pointerInput(s.id) {
                                        detectTapGestures(
                                            onTap = { vm.openSession(s.id); showSessions = false },
                                            onLongPress = { renamingSession = s }
                                        )
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(s.title)
                                Text(
                                    buildString {
                                        append(
                                            when {
                                                s.chapterId != null -> "跟章节"
                                                s.bookId != null -> "跟书"
                                                else -> "未绑定"
                                            }
                                        )
                                        books.firstOrNull { it.id == s.bookId }?.let {
                                            append(" · ").append(it.title)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { vm.deleteSession(s.id) }) { Text("删除") }
                        }
                    }
                    Text(
                        "长按会话标题可修改名称",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showSessions = false }) { Text("关闭") } }
        )
    }
    renamingSession?.let { target ->
        var newTitle by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text("修改会话标题") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    label = { Text("会话标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank(),
                    onClick = {
                        vm.renameSession(target.id, newTitle)
                        renamingSession = null
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renamingSession = null }) { Text("取消") } }
        )
    }
}

/** 编辑器内的 AI 助手：从右侧滑出的抽屉 */
@Composable
fun AiAssistantDrawer(
    visible: Boolean,
    bookId: Long,
    chapterId: Long,
    onDismiss: () -> Unit,
    /** 把某条 AI 回答的正文写入当前章节（覆盖 + 一键排版） */
    onWriteToChapter: (String) -> Unit,
    /** 在当前书新建一个章节并跳转过去 */
    onNewChapter: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            AiAssistantPanel(bookId, chapterId, onDismiss, onWriteToChapter, onNewChapter)
        }
    }
}

@Composable
private fun AiAssistantPanel(
    bookId: Long,
    chapterId: Long,
    onDismiss: () -> Unit,
    onWriteToChapter: (String) -> Unit,
    onNewChapter: () -> Unit
) {
    val vm = appViewModel(key = "ai-quick-$bookId-$chapterId") {
        // 侧边 AI 助手：不启用 AI 工具（工具提示词不随请求发送，也不解析工具区块）
        AiChatViewModel(it, bookId, chapterId, enableAiTools = false)
    }
    val messages by vm.messages.collectAsState()
    val input by vm.input.collectAsState()
    val sending by vm.sending.collectAsState()
    val models by vm.models.collectAsState()
    val providers by vm.providers.collectAsState()
    val activeModelId by vm.activeModelId.collectAsState()
    val prompts by vm.prompts.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val outline by vm.outline.collectAsState()
    val selChapters by vm.selectedChapterIds.collectAsState()
    val selOutline by vm.selectedOutlineIds.collectAsState()
    val summaryOn by vm.currentSummaryOn.collectAsState()
    val incContent by vm.includeContent.collectAsState()
    val incSummary by vm.includeSummary.collectAsState()
    val errorMsg by vm.errorMessage.collectAsState()
    val editingUserMsgId by vm.editingUserMessageId.collectAsState()
    val pendingReplyTo by vm.pendingReplyToId.collectAsState()
    val streamingContent by vm.streamingContent.collectAsState()
    val streamingReasoning by vm.streamingReasoning.collectAsState()
    val memoryRetrieval by vm.memoryRetrievalEnabled.collectAsState()
    val memoryEmptyAsk by vm.pendingEmptyVectorizeAsk.collectAsState()
    val memoryToastMsg by vm.memoryToast.collectAsState()
    val memoryVectorizing by vm.memoryVectorizing.collectAsState()
    val memorySearch by vm.memorySearchState.collectAsState()
    val memoryToolReady by vm.memoryToolConfigured.collectAsState()

    // 当前打开的章节不进入上下文
    val contextChapters = chapters.filter { it.id != chapterId }
    val modelName = models.firstOrNull { it.id == activeModelId }?.displayName ?: "未选择模型"
    val allSelected = contextChapters.isNotEmpty() && selChapters.containsAll(contextChapters.map { it.id })
    val allOutlineSelected = outline.isNotEmpty() && selOutline.containsAll(outline.map { it.id })

    // 底部按钮对应的展开面板：context / outline / prompt
    var openPanel by remember { mutableStateOf<String?>(null) }
    // 文件清单的展开状态（0 收起 / 1 表格 / 2 txt）；与上方展开面板互斥
    var manifestState by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val ui = remember { MessageUiState() }

    // 编辑回答时，返回键直接取消编辑
    BackHandler(enabled = ui.editingId != null) { ui.editingId = null }

    Surface(
        // 底部让出系统导航栏 / 输入法高度，避免抽屉与导航栏重合
        modifier = Modifier
            .fillMaxHeight()
            .width(340.dp)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("AI 助手", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                // 模型选择移到标题右侧
                ModelSelectorChip(
                    models = models,
                    providers = providers,
                    activeId = activeModelId,
                    maxLabelWidth = 96.dp,
                    onSelect = { vm.setModel(it) }
                )
                Spacer(Modifier.weight(1f))
                // 新建对话移到关闭按钮左侧
                IconButton(
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        openPanel = null
                        manifestState = 0
                        vm.newSession()
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "新开对话")
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            HorizontalDivider()

            // 上方只显示与 AI 的聊天内容；点击空白处取消删除确认
            val entries = remember(messages) { buildChatEntries(messages) }
            val listState = rememberLazyListState()
            // 打开抽屉 / 切换章节 / 新增消息后，自动停在最新对话处（同：先等列表完成布局，再滚到底部）
            LaunchedEffect(chapterId, entries.size) {
                if (entries.isEmpty()) return@LaunchedEffect
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                listState.scrollToBottom()
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        ui.pendingDeleteId = null
                        // 点按别处：取消文本选中状态（重建所有 SelectionContainer）
                        ui.selectionReset++
                    }
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (messages.isEmpty() && !sending) {
                        item {
                            Text(
                                "向 AI 提问，或点击「快捷提示词」选择模板",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(entries) { entry ->
                        ChatMessageItem(
                            entry = entry,
                            modelName = modelName,
                            sending = sending,
                            pendingReplyToId = pendingReplyTo,
                            streamingContent = streamingContent,
                            streamingReasoning = streamingReasoning,
                            ui = ui,
                            chapters = chapters,
                            outline = outline,
                            previewFor = { vm.previewFor(it) },
                            onEditUser = { vm.beginEditUserMessage(it) },
                            onRegenerate = { vm.regenerate(it) },
                            onCopy = { clipboard.setText(AnnotatedString(it)) },
                            onDelete = { vm.deleteMessage(it) },
                            onSaveEdit = { id, text -> vm.updateMessage(id, text) },
                            onSaveAndSend = { id, text, uid -> vm.saveAndContinue(id, text, uid) },
                            onSwitchVariant = { uid, mid -> vm.switchVariant(uid, mid) },
                            onToggleContent = { id, collapsed -> vm.setContentCollapsed(id, collapsed) },
                            onStop = { vm.stopGenerating() },
                            onWriteToChapter = onWriteToChapter
                        )
                    }
                }
            }

            errorMsg?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 展开面板出现在按钮行上方
            when (openPanel) {
                "context" -> ContextPicker(
                    chapters = contextChapters,
                    volumes = volumes,
                    selected = selChapters,
                    allSelected = allSelected,
                    includeSummary = incSummary,
                    includeContent = incContent,
                    onToggleAll = { vm.selectAllChapters() },
                    onToggleSummary = { vm.includeSummary.value = !incSummary },
                    onToggleContent = { vm.includeContent.value = !incContent },
                    onToggleChapter = { vm.toggleChapter(it) },
                    onToggleVolume = { vm.toggleVolume(it) }
                )

                "outline" -> OutlinePicker(
                    items = outline,
                    selected = selOutline,
                    allSelected = allOutlineSelected,
                    onToggleAll = { vm.selectAllOutline() },
                    onToggle = { vm.toggleOutline(it) },
                    onToggleCategory = { vm.toggleOutlineCategory(it) }
                )
            }

            // 按钮行
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectorButton("上下文", openPanel == "context") {
                    openPanel = if (openPanel == "context") null else "context"
                    manifestState = 0
                }
                SelectorButton("大纲", openPanel == "outline") {
                    openPanel = if (openPanel == "outline") null else "outline"
                    manifestState = 0
                }
                // 长期记忆检索（与「上下文 / 大纲」同排的勾选框）；未配置向量化工具时不可勾选
                SmallChip("记忆检索", memoryRetrieval, { vm.toggleMemoryRetrieval() }, enabled = memoryToolReady)
                MemorySearchIndicator(memorySearch, memoryRetrieval)
                // 在当前书新建章节并直接跳转过去
                SmallChip("新建章节", false, { onNewChapter() })
            }

            if (editingUserMsgId > 0L) {
                EditPromptBanner(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    onCancel = { vm.cancelEditUserMessage() }
                )
            }

            // 输入框常驻
            OutlinedTextField(
                value = input,
                onValueChange = { vm.input.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("向ai提问") },
                shape = RoundedCornerShape(12.dp),
                maxLines = 5
            )

            // 所选文件的反馈：文件清单 → 表格 → 生成的 txt
            ContextManifest(
                snapshot = ContextSnapshot(
                    chapterIds = selChapters.toList(),
                    outlineIds = selOutline.toList(),
                    includeSummary = incSummary,
                    includeContent = incContent
                ),
                chapters = chapters,
                outline = outline,
                modifier = Modifier.padding(horizontal = 16.dp),
                expandedState = manifestState,
                onExpandedChange = { v ->
                    manifestState = v
                    // 展开文件清单时收起上方面板，避免把聊天区压缩得过小
                    if (v != 0) openPanel = null
                },
                preview = { vm.contextPreview() }
            )

            // 「＋本章梗概」「快捷提示词」与「发送」同一水平线
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = summaryOn,
                    onClick = { vm.toggleCurrentSummary() },
                    modifier = Modifier.height(32.dp),
                    label = {
                        Text(
                            "＋本章梗概",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
                FilterChip(
                    selected = openPanel == "prompt",
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        openPanel = if (openPanel == "prompt") null else "prompt"
                        manifestState = 0
                    },
                    modifier = Modifier.height(32.dp),
                    label = {
                        Text(
                            "快捷提示词",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                        Icon(
                            if (openPanel == "prompt") Icons.Filled.KeyboardArrowDown
                            else Icons.Filled.KeyboardArrowUp,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = !sending && input.isNotBlank(),
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        vm.send()
                    },
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("发送", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }

            // 快捷提示词面板：在对话框下方展开
            if (openPanel == "prompt") {
                HorizontalDivider()
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (prompts.isEmpty()) {
                        item { Text("暂无快捷提示词", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(prompts, key = { it.id }) { p ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                    openPanel = null
                                    vm.appendPrompt(p)
                                }
                                .padding(12.dp)
                        ) {
                            Text(p.title, fontWeight = FontWeight.SemiBold)
                            if (p.content.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    p.content,
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
            Spacer(Modifier.height(8.dp))
        }
    }

    // 本书从未向量化时勾选记忆检索：先问是否向量化全书，再决定是否勾选
    val panelContext = LocalContext.current
    if (memoryEmptyAsk) {
        AlertDialog(
            onDismissRequest = { vm.resolveEmptyVectorizeAsk(false) },
            title = { Text("当前书本没有向量化") },
            text = {
                Text(
                    "当前书本还没有向量化，是否开启向量化？\n\n" +
                        "选「可以」：开始向量化全书，并勾选记忆检索；\n" +
                        "选「不可以」：取消记忆检索勾选。"
                )
            },
            confirmButton = { TextButton(onClick = { vm.resolveEmptyVectorizeAsk(true) }) { Text("可以") } },
            dismissButton = { TextButton(onClick = { vm.resolveEmptyVectorizeAsk(false) }) { Text("不可以") } }
        )
    }
    // 记忆检索相关提示（如未选择书籍）
    LaunchedEffect(memoryToastMsg) {
        memoryToastMsg?.let {
            Toast.makeText(panelContext, it, Toast.LENGTH_SHORT).show()
            vm.clearMemoryToast()
        }
    }
    // 向量化进行中的轻提示（只以书籍为键，一轮只提示一次）
    LaunchedEffect(memoryVectorizing?.bookId) {
        memoryVectorizing?.let { p ->
            val where = listOf(p.volumeTitle, p.chapterTitle).filter { it.isNotBlank() }.joinToString(" ")
            Toast.makeText(
                panelContext,
                if (where.isBlank()) "正在向量化本书…" else "正在向量化本书：$where（${p.done}/${p.total}）",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

/** 底部选择按钮，如 [上下文 ∧] [大纲 ∨] [模型选择 ∨] */
@Composable
private fun SelectorButton(label: String, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(4.dp))
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 会话筛选用的下拉选择器：id 为 null 表示「全部」 */
@Composable
private fun FilterDropdown(
    label: String,
    options: List<Pair<Long?, String>>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                options.firstOrNull { it.first == selectedId }?.second ?: label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { onSelect(id); expanded = false }
                )
            }
        }
    }
}

/** 面板顶部的紧凑筛选标签 */
@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.height(30.dp),
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false
            )
        }
    )
}

/**
 * 「记忆检索」旁的状态指示：本地向量化工具走本机 CPU 推理，
 * 一轮检索要向量化 query 再重排候选（首次还会加载重排模型），耗时较长。
 * 这里如实显示检索的真实生命周期（正在检索中 → 检索完成），避免界面看起来像卡死。
 */
@Composable
private fun MemorySearchIndicator(state: MemorySearchState, enabled: Boolean) {
    if (!enabled || state == MemorySearchState.IDLE) return
    val searching = state == MemorySearchState.SEARCHING
    Row(
        Modifier.height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (searching) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            if (searching) "正在检索中" else "检索完成",
            style = MaterialTheme.typography.labelMedium,
            color = if (searching) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** 带标题栏的圆角面板容器 */
@Composable
private fun PickerBox(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

/** 上下文选择：全选 / 梗概 / 正文 + 分卷章节树 */
@Composable
private fun ContextPicker(
    chapters: List<Chapter>,
    volumes: List<Volume>,
    selected: Set<Long>,
    allSelected: Boolean,
    includeSummary: Boolean,
    includeContent: Boolean,
    onToggleAll: () -> Unit,
    onToggleSummary: () -> Unit,
    onToggleContent: () -> Unit,
    onToggleChapter: (Long) -> Unit,
    onToggleVolume: (Long) -> Unit
) {
    // 各分卷的收起状态（默认展开）
    val collapsed = remember { mutableStateMapOf<Long, Boolean>() }
    Column(Modifier.padding(bottom = 8.dp)) {
        PickerBox(
            title = "选择上下文",
            trailing = {
                Text(
                    "已选 ${selected.size} 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallChip("全选", allSelected, onToggleAll)
                SmallChip("梗概", includeSummary, onToggleSummary)
                SmallChip("正文", includeContent, onToggleContent)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                if (chapters.isEmpty()) {
                    Text(
                        "暂无可选章节",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                volumes.forEach { v ->
                    val list = chapters.filter { it.volumeId == v.id }
                    if (list.isNotEmpty()) {
                        val expanded = collapsed[v.id] != true
                        VolumeRow(
                            label = v.title,
                            allSelected = list.all { it.id in selected },
                            picked = list.count { it.id in selected },
                            total = list.size,
                            expanded = expanded,
                            onToggleExpand = { collapsed[v.id] = expanded }
                        ) { onToggleVolume(v.id) }
                        if (expanded) {
                            list.forEach { ch ->
                                ChapterRow(ch.title, ch.id in selected, 26.dp, useCheckbox = true) {
                                    onToggleChapter(ch.id)
                                }
                            }
                        }
                    }
                }
                chapters.filter { it.volumeId == null }.forEach { ch ->
                    ChapterRow(ch.title, ch.id in selected, 6.dp, useCheckbox = true) {
                        onToggleChapter(ch.id)
                    }
                }
            }
        }
    }
}

/** 分卷行：整卷选中 + 已选数量；[onToggleExpand] 非空时左侧显示展开/收起箭头 */
@Composable
private fun VolumeRow(
    label: String,
    allSelected: Boolean,
    picked: Int,
    total: Int,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onToggle)
            .padding(start = 2.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onToggleExpand != null) {
            IconButton(onClick = onToggleExpand, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Checkbox(checked = allSelected, onCheckedChange = { onToggle() })
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "$picked/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChapterRow(
    label: String,
    selected: Boolean,
    startPadding: Dp,
    useCheckbox: Boolean = false,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = startPadding, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (useCheckbox) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        } else {
            RadioButton(selected = selected, onClick = onToggle, modifier = Modifier.size(28.dp))
        }
        Text(
            label,
            style = if (useCheckbox) MaterialTheme.typography.bodySmall
            else MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 大纲内容选择：按分类（人设 / 世界观 / 故事大纲 / 灵感库 / 自定义标签）分组整组勾选 */
@Composable
private fun OutlinePicker(
    items: List<OutlineItem>,
    selected: Set<Long>,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onToggle: (Long) -> Unit,
    onToggleCategory: (String) -> Unit
) {
    // 各分类（人设 / 世界观 / 故事大纲 / 灵感库 / 自定义标签）的收起状态（默认展开）
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    Column(Modifier.padding(bottom = 8.dp)) {
        PickerBox(
            title = "选择大纲",
            trailing = {
                Text(
                    "已选 ${selected.size} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallChip("全选", allSelected, onToggleAll)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                if (items.isEmpty()) {
                    Text(
                        "暂无可选大纲",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items.groupBy { it.category }.forEach { (category, list) ->
                    val expanded = collapsed[category] != true
                    VolumeRow(
                        label = outlineCategoryLabel(category),
                        allSelected = list.all { it.id in selected },
                        picked = list.count { it.id in selected },
                        total = list.size,
                        expanded = expanded,
                        onToggleExpand = { collapsed[category] = expanded }
                    ) { onToggleCategory(category) }
                    if (expanded) {
                        list.forEach { item ->
                            ChapterRow(
                                label = item.title,
                                selected = item.id in selected,
                                startPadding = 26.dp,
                                useCheckbox = true
                            ) { onToggle(item.id) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 顶部紧凑的模型选择入口：显示当前模型名（超长省略），点击弹出下拉。
 * 下拉里展示全称「备注（显示名）+ 供应商」，与主界面模型下拉一致。
 */
@Composable
private fun ModelSelectorChip(
    models: List<AiModel>,
    providers: List<Provider>,
    activeId: Long,
    maxLabelWidth: Dp = 120.dp,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = {
                Text(
                    models.firstOrNull { it.id == activeId }?.displayName ?: "选择模型",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = maxLabelWidth)
                )
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("请先在设置中添加模型") },
                    onClick = { expanded = false }
                )
            }
            models.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text("${m.displayName}  (${providers.firstOrNull { it.id == m.providerId }?.name ?: ""})")
                    },
                    onClick = { onSelect(m.id); expanded = false }
                )
            }
        }
    }
}

/** 对话列表中的一项：用户消息（含其回答的多个版本）或独立的助手消息 */
private sealed interface ChatEntry {
    data class User(
        val msg: ChatMessage,
        val variants: List<ChatMessage>,
        val activeIndex: Int
    ) : ChatEntry

    data class Assistant(val msg: ChatMessage, val replyToUserId: Long) : ChatEntry
}

/**
 * 滚动到列表最底部（最后一条消息的末尾）。
 * 直接 scrollToItem(最后一条) 只会把最后一条顶到视口顶部，长回答时看到的是它的开头而非结尾，
 * 这里再往上滚满，落到真正的底部。
 */
private suspend fun LazyListState.scrollToBottom() {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    scrollToItem(last)
    scrollBy(Float.MAX_VALUE)
}

/** 把扁平的消息列表折叠成「用户消息 + 其回答版本」的展示列表 */
private fun buildChatEntries(messages: List<ChatMessage>): List<ChatEntry> {
    val variants = messages
        .filter { it.role == ChatRole.ASSISTANT && it.replyToId != 0L }
        .groupBy { it.replyToId }
    val entries = mutableListOf<ChatEntry>()
    var lastUserId = 0L
    messages.forEach { m ->
        when {
            m.role == ChatRole.USER -> {
                lastUserId = m.id
                val vs = variants[m.id].orEmpty().sortedBy { it.variantIndex }
                val idx = vs.indexOfFirst { it.active }.let { if (it < 0) 0 else it }
                entries += ChatEntry.User(m, vs, idx)
            }
            m.role == ChatRole.ASSISTANT && m.replyToId == 0L ->
                entries += ChatEntry.Assistant(m, lastUserId)
            // 其它助手消息作为某个用户消息的版本随该用户消息一起展示
        }
    }
    return entries
}

/** 对话项上的临时 UI 状态：待确认删除的消息、正在内联编辑的回答 */
@Stable
private class MessageUiState {
    var pendingDeleteId by mutableStateOf<Long?>(null)
    var editingId by mutableStateOf<Long?>(null)
    var editingText by mutableStateOf("")
    /**
     * 文本选择重置计数：点击消息区空白处时 +1，
     * 通过 key() 重建 SelectionContainer 来取消已选中的文本。
     */
    var selectionReset by mutableIntStateOf(0)
}

/** 正在编辑历史提示词的提示条：发送后将从该条起重新生成 */
@Composable
private fun EditPromptBanner(modifier: Modifier = Modifier, onCancel: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "正在编辑历史提示词，发送后将重新生成",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

/** 聊天消息：用户为描边方框 + 操作行（含版本翻页），助手为纯文本 + 模型信息 */
@Composable
private fun ChatMessageItem(
    entry: ChatEntry,
    modelName: String,
    sending: Boolean,
    /** 正在生成回答的用户消息 id：命中时把该条的回答区就地换成“正在生成” */
    pendingReplyToId: Long,
    /** 正在流式生成的正文 / 思考内容（实时显示） */
    streamingContent: String,
    streamingReasoning: String,
    ui: MessageUiState,
    chapters: List<Chapter>,
    outline: List<OutlineItem>,
    previewFor: suspend (ContextSnapshot) -> String,
    onEditUser: (ChatMessage) -> Unit,
    onRegenerate: (Long) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onSaveEdit: (Long, String) -> Unit,
    onSaveAndSend: (Long, String, Long) -> Unit,
    onSwitchVariant: (Long, Long) -> Unit,
    onToggleContent: (Long, Boolean) -> Unit,
    onStop: () -> Unit,
    /** 侧边栏 AI 助手专属：把这条回答的正文写入当前章节；为空则不显示该操作（如导航栏 AI 对话页） */
    onWriteToChapter: ((String) -> Unit)? = null
) {
    when (entry) {
        is ChatEntry.User -> {
            val msg = entry.msg
            val generating = pendingReplyToId == msg.id
            // 长按可选中并复制消息内容；key 随 selectionReset 变化时重建以取消选中
            key(ui.selectionReset) {
                SelectionContainer {
                    Text(
                        text = msg.content,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            // 这条消息发送时选好的上下文（文件清单）
            val snapshot = remember(msg.contextSnapshot) { decodeContextSnapshot(msg.contextSnapshot) }
            ContextManifest(
                snapshot = snapshot,
                chapters = chapters,
                outline = outline,
                preview = { previewFor(snapshot) }
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MessageAction(Icons.Filled.Refresh, "重新生成", enabled = !sending) {
                    ui.pendingDeleteId = null
                    onRegenerate(msg.id)
                }
                MessageAction(Icons.Filled.Edit, "编辑") {
                    ui.pendingDeleteId = null
                    onEditUser(msg)
                }
                MessageAction(Icons.Filled.ContentCopy, "复制") {
                    ui.pendingDeleteId = null
                    onCopy(msg.content)
                }
                DeleteAction(msg.id, ui, onDelete)
                Spacer(Modifier.weight(1f))
                // 生成中把翻页器翻到“新的一页”，生成完成后即可在历史版本间回看
                val total = entry.variants.size + if (generating) 1 else 0
                if (total > 1) {
                    VariantPager(
                        index = if (generating) entry.variants.size else entry.activeIndex,
                        total = total,
                        enabled = !generating,
                        onPrev = {
                            entry.variants.getOrNull(entry.activeIndex - 1)
                                ?.let { onSwitchVariant(msg.id, it.id) }
                        },
                        onNext = {
                            entry.variants.getOrNull(entry.activeIndex + 1)
                                ?.let { onSwitchVariant(msg.id, it.id) }
                        }
                    )
                }
            }
            if (generating) {
                Spacer(Modifier.height(10.dp))
                RespondingBlock(
                    content = streamingContent,
                    reasoning = streamingReasoning,
                    onStop = onStop
                )
            } else {
                entry.variants.getOrNull(entry.activeIndex)?.let { active ->
                    Spacer(Modifier.height(10.dp))
                    AssistantBlock(
                        msg = active,
                        userMessageId = msg.id,
                        modelName = modelName,
                        sending = sending,
                        ui = ui,
                        onRegenerate = onRegenerate,
                        onCopy = onCopy,
                        onDelete = onDelete,
                        onSaveEdit = onSaveEdit,
                        onSaveAndSend = onSaveAndSend,
                        onToggleContent = onToggleContent,
                        onWriteToChapter = onWriteToChapter
                    )
                }
            }
        }

        is ChatEntry.Assistant -> AssistantBlock(
            msg = entry.msg,
            userMessageId = entry.replyToUserId,
            modelName = modelName,
            sending = sending,
            ui = ui,
            onRegenerate = onRegenerate,
            onCopy = onCopy,
            onDelete = onDelete,
            onSaveEdit = onSaveEdit,
            onSaveAndSend = onSaveAndSend,
            onToggleContent = onToggleContent,
            onWriteToChapter = onWriteToChapter
        )
    }
}

/** 助手回答：深度思考（可选）+ 正文 + 操作行；编辑态下变为输入框 + 保存类按钮 */
@Composable
private fun AssistantBlock(
    msg: ChatMessage,
    userMessageId: Long,
    modelName: String,
    sending: Boolean,
    ui: MessageUiState,
    onRegenerate: (Long) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (Long) -> Unit,
    onSaveEdit: (Long, String) -> Unit,
    onSaveAndSend: (Long, String, Long) -> Unit,
    onToggleContent: (Long, Boolean) -> Unit,
    onWriteToChapter: ((String) -> Unit)? = null
) {
    if (msg.reasoning.isNotBlank()) {
        ThinkingBlock(msg.reasoning)
        Spacer(Modifier.height(6.dp))
    }
    if (ui.editingId == msg.id) {
        OutlinedTextField(
            value = ui.editingText,
            onValueChange = { ui.editingText = it },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            maxLines = 12
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                onSaveEdit(msg.id, ui.editingText)
                ui.editingId = null
            }) { Text("保存") }
            TextButton(onClick = {
                onSaveAndSend(msg.id, ui.editingText, userMessageId)
                ui.editingId = null
            }) { Text("保存并发送") }
            TextButton(onClick = { ui.editingId = null }) { Text("取消") }
        }
    } else {
        AnswerBlock(
            content = msg.content,
            collapsed = msg.contentCollapsed,
            resetKey = ui.selectionReset,
            onToggle = { onToggleContent(msg.id, !msg.contentCollapsed) }
        )
        Spacer(Modifier.height(6.dp))
        // 模型与本次消耗的 token 同行显示，位于操作按钮行上方
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "model: $modelName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val tokens = msg.promptTokens + msg.completionTokens
            if (tokens > 0) {
                Spacer(Modifier.weight(1f))
                // 缓存命中率 = 命中缓存的输入 token / 总输入 token
                val hit = if (msg.promptTokens > 0) msg.cachedTokens * 100 / msg.promptTokens else 0
                Text(
                    buildString {
                        append("消耗 $tokens tokens（输入 ${msg.promptTokens} / 输出 ${msg.completionTokens}）")
                        // 只要记录了输入 token 就展示命中率：首轮没有缓存命中时显示 0%，
                        // 避免「第一次生成看不到缓存命中率」的观感问题
                        if (msg.promptTokens > 0) append(" · 缓存命中 $hit%")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MessageAction(
                Icons.Filled.Refresh, "重新生成",
                enabled = !sending && userMessageId > 0
            ) {
                ui.pendingDeleteId = null
                onRegenerate(userMessageId)
            }
            MessageAction(Icons.Filled.Edit, "编辑") {
                ui.pendingDeleteId = null
                ui.editingText = msg.content
                ui.editingId = msg.id
            }
            MessageAction(Icons.Filled.ContentCopy, "复制") {
                ui.pendingDeleteId = null
                onCopy(msg.content)
            }
            // 侧边栏专属：把本条回答正文写入当前章节（覆盖 + 一键排版）
            onWriteToChapter?.let { write ->
                MessageAction(Icons.Filled.EditNote, "写入当前章节") {
                    ui.pendingDeleteId = null
                    write(msg.content)
                }
            }
            MessageAction(
                if (msg.contentCollapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                if (msg.contentCollapsed) "展开回答" else "收起回答"
            ) {
                ui.pendingDeleteId = null
                onToggleContent(msg.id, !msg.contentCollapsed)
            }
            DeleteAction(msg.id, ui, onDelete)
        }
    }
}

/** 删除按钮：首次点击变为红色对勾，再次点击才真正删除 */
@Composable
private fun DeleteAction(id: Long, ui: MessageUiState, onDelete: (Long) -> Unit) {
    val pending = ui.pendingDeleteId == id
    IconButton(
        onClick = {
            if (pending) {
                ui.pendingDeleteId = null
                onDelete(id)
            } else {
                ui.pendingDeleteId = id
            }
        },
        modifier = Modifier.size(30.dp)
    ) {
        Icon(
            if (pending) Icons.Filled.Check else Icons.Filled.Delete,
            contentDescription = if (pending) "确认删除" else "删除",
            modifier = Modifier.size(16.dp),
            tint = if (pending) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary
        )
    }
}

/** 回答版本翻页：< 2/3 > */
@Composable
private fun VariantPager(
    index: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    enabled: Boolean = true
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val canPrev = enabled && index > 0
        IconButton(onClick = onPrev, enabled = canPrev, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一个版本",
                modifier = Modifier.size(18.dp),
                tint = if (canPrev) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        Text(
            "${index + 1}/$total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val canNext = enabled && index < total - 1
        IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(26.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一个版本",
                modifier = Modifier.size(18.dp),
                tint = if (canNext) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun MessageAction(
    icon: ImageVector,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(30.dp)) {
        Icon(
            icon,
            contentDescription = desc,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
    }
}

/** 深度思考块：只有模型返回了思考内容时才展示 */
@Composable
private fun ThinkingBlock(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "深度思考",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 回答正文：与灰色「深度思考」块区分开，展开时仍是纯文本（保持旧版外观）。
 * 收起后只留一行提示，点击即可展开；折叠状态随消息持久化（[ChatMessage.contentCollapsed]）。
 */
@Composable
private fun AnswerBlock(content: String, collapsed: Boolean, resetKey: Int, onToggle: () -> Unit) {
    if (collapsed) {
        Text(
            "回答已收起，点击展开",
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        // 长按可选中并复制回答内容；key 随 resetKey 变化时重建以取消选中
        key(resetKey) {
            SelectionContainer {
                Text(content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** 正在响应中：就地显示在目标回答的位置，实时流式显示内容，右侧可随时「停止响应」 */
@Composable
private fun RespondingBlock(content: String, reasoning: String, onStop: () -> Unit) {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            dots = if (dots >= 3) 1 else dots + 1
        }
    }
    // 与生成完成后的排版保持一致：深度思考单独一个折叠框，正文是普通文本，不共用背景
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "正在生成" + "…".repeat(dots),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onStop) {
                Text("停止响应", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (reasoning.isNotBlank()) {
            ThinkingBlock(reasoning)
            Spacer(Modifier.height(6.dp))
        }
        if (content.isNotBlank()) {
            Text(
                content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * 「复制」小按钮：把整份生成的上下文 txt 复制到剪贴板。
 * [text] 为惰性求值（生成 txt 需要查库），点击后再取。
 */
@Composable
private fun CopyContextAction(text: suspend () -> String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Text(
        "复制",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable {
                scope.launch {
                    val t = text()
                    if (t.isBlank()) {
                        Toast.makeText(context, "暂无可复制的内容", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.setText(AnnotatedString(t))
                        Toast.makeText(context, "已复制全部内容", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * 文件清单：对「这条消息用了哪些上下文」的回显。
 * 点击清单行：展开清单表格（含是否仅梗概 / 是否仅正文），再次点击合上；
 * 只有点击「查看txt」才会显示生成的上下文文本。表格与 txt 都可以滑动查看。
 */
@Composable
private fun ContextManifest(
    snapshot: ContextSnapshot,
    chapters: List<Chapter>,
    outline: List<OutlineItem>,
    modifier: Modifier = Modifier,
    /** 外部受控的展开状态（0 收起 / 1 表格 / 2 txt）；为空时组件自管 */
    expandedState: Int? = null,
    onExpandedChange: ((Int) -> Unit)? = null,
    preview: suspend () -> String
) {
    val chapterRows = snapshot.chapterIds.map { id ->
        chapters.firstOrNull { it.id == id }?.title ?: "（章节已删除）"
    }
    val outlineRows = snapshot.outlineIds.map { id ->
        outline.firstOrNull { it.id == id }
            ?.let { "${outlineCategoryLabel(it.category)}·${it.title}" } ?: "（大纲已删除）"
    }
    val total = chapterRows.size + outlineRows.size
    var innerState by remember { mutableIntStateOf(0) }
    val state = expandedState ?: innerState
    val setState: (Int) -> Unit = { v ->
        if (onExpandedChange != null) onExpandedChange(v) else innerState = v
    }
    if (total == 0) return

    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { setState(if (state == 0) 1 else 0) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "文件清单（$total）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            Text(
                when (state) {
                    0 -> "展开"
                    1 -> "查看txt"
                    else -> "收起"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 只有「查看txt」自身可点击；点击清单行只会展开/合上清单
                modifier = if (state == 1) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { setState(2) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else {
                    Modifier
                }
            )
            // 「查看txt」旁边：一键复制整份 txt
            if (state == 1) {
                Spacer(Modifier.width(6.dp))
                CopyContextAction(text = preview)
            }
            Icon(
                if (state == 0) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ContextManifestContent(
            state = state,
            snapshot = snapshot,
            chapterRows = chapterRows,
            outlineRows = outlineRows,
            preview = preview
        )
    }
}

/** 文件清单内容区：state=1 显示清单表格，state=2 显示生成的 txt，其余不渲染 */
@Composable
private fun ContextManifestContent(
    state: Int,
    snapshot: ContextSnapshot,
    chapterRows: List<String>,
    outlineRows: List<String>,
    preview: suspend () -> String,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        when (state) {
            1 -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (chapterRows.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("章节", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("仅梗概", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
                        Text("仅正文", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
                    }
                    chapterRows.forEach { title ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (snapshot.includeSummary) "✓" else "—",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(52.dp)
                            )
                            Text(
                                if (snapshot.includeContent) "✓" else "—",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(52.dp)
                            )
                        }
                    }
                }
                if (outlineRows.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("大纲", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold)
                    }
                    outlineRows.forEach { label ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            2 -> {
                var text by remember { mutableStateOf("") }
                LaunchedEffect(snapshot) { text = preview() }
                Text(
                    if (text.isBlank()) "（暂无可生成的上下文）" else text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp)
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

/**
 * 通用单选书籍弹窗：分组筛选 + 搜索 + 单选框 + 新建书籍。
 * AI 聊天页选上下文、导入大纲选目标书共用。新建完成后自动选中新书并关闭。
 */
@Composable
internal fun BookPickerDialog(
    books: List<Book>,
    groups: List<BookGroup>,
    selectedId: Long?,
    /** 单选：选中新书时回调新书 id；再次点击已选中的书时回调 null（取消选择） */
    onSelect: (Long?) -> Unit,
    /** 完整新建书籍表单提交，返回新书 id（创建后自动选中） */
    onCreateBook: suspend (BookFormResult) -> Long,
    onPickCover: (android.net.Uri, (String?) -> Unit) -> Unit,
    onCreateGroup: suspend (String) -> Long,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    // 分组筛选：null=全部，-1=未分组，其余为分组 id
    var groupFilter by remember { mutableStateOf<Long?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val filtered = books.filter { b ->
        val matchGroup = when (groupFilter) {
            null -> true
            -1L -> b.groupId == null
            else -> b.groupId == groupFilter
        }
        val matchSearch = search.isBlank() || b.title.contains(search.trim(), ignoreCase = true)
        matchGroup && matchSearch
    }

    // 点击书籍行：未选中则选中并关闭；已选中则取消选择（停留弹窗便于改选其他书）
    fun onBookClick(b: Book) {
        if (b.id == selectedId) onSelect(null)
        else {
            onSelect(b.id)
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择书籍") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("搜索书籍") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // 分组筛选（无分组时只留「全部 / 未分组」也无意义，直接隐藏）
                if (groups.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = groupFilter == null, onClick = { groupFilter = null },
                            label = { Text("全部") })
                        FilterChip(selected = groupFilter == -1L, onClick = { groupFilter = -1L },
                            label = { Text("未分组") })
                        groups.forEach { g ->
                            FilterChip(selected = groupFilter == g.id, onClick = { groupFilter = g.id },
                                label = { Text(g.name) })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                // 新建书籍
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showCreate = true }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("新建书籍", color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "暂无匹配书籍",
                                Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { b ->
                        val groupName = groups.firstOrNull { it.id == b.groupId }?.name
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onBookClick(b) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = b.id == selectedId,
                                onClick = { onBookClick(b) }
                            )
                            Column {
                                Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (groupName != null) {
                                    Text(
                                        groupName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )

    if (showCreate) {
        // 新建书籍：调用与书架一致的完整创建窗口（书名 / 简介 / 封面 / 分组 / 前缀）
        BookFormDialog(
            dialogTitle = "新建书籍",
            initial = null,
            groups = groups,
            onPickCover = onPickCover,
            onCreateGroup = onCreateGroup,
            onConfirm = { r ->
                scope.launch {
                    val id = onCreateBook(r)
                    showCreate = false
                    onSelect(id)
                    onDismiss()
                }
            },
            onDismiss = { showCreate = false }
        )
    }
}

/** 章节多选弹窗：按分卷筛选 + 搜索标题 / 内容 / 梗概 */
@Composable
private fun ChapterPickerDialog(
    chapters: List<Chapter>,
    volumes: List<Volume>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onToggleAll: () -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    // 分卷筛选：null=全部，-1=未分卷
    var volumeFilter by remember { mutableStateOf<Long?>(null) }

    val filtered = chapters.filter { ch ->
        val matchVolume = when (volumeFilter) {
            null -> true
            -1L -> ch.volumeId == null
            else -> ch.volumeId == volumeFilter
        }
        val q = search.trim()
        val matchSearch = q.isBlank() ||
            ch.title.contains(q, ignoreCase = true) ||
            ch.summary.contains(q, ignoreCase = true) ||
            ch.content.contains(q, ignoreCase = true)
        matchVolume && matchSearch
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择章节（已选 ${selected.size}）") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("搜索标题 / 内容 / 梗概") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (volumes.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = volumeFilter == null, onClick = { volumeFilter = null },
                            label = { Text("全部") })
                        volumes.forEach { v ->
                            FilterChip(selected = volumeFilter == v.id, onClick = { volumeFilter = v.id },
                                label = { Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                        if (chapters.any { it.volumeId == null }) {
                            FilterChip(selected = volumeFilter == -1L, onClick = { volumeFilter = -1L },
                                label = { Text("未分卷") })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "暂无匹配章节",
                                Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { ch ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(ch.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = ch.id in selected, onCheckedChange = { onToggle(ch.id) })
                            Column {
                                Text(ch.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                volumes.firstOrNull { it.id == ch.volumeId }?.let {
                                    Text(
                                        it.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onToggleAll) { Text("全选/取消") }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        }
    )
}

/** 大纲多选弹窗：按标签分类筛选 + 搜索标题 / 内容 */
@Composable
private fun OutlinePickerDialog(
    items: List<OutlineItem>,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    // 标签筛选：null=全部，否则为分类存储值
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    val categories = items.map { it.category }.distinct()

    val filtered = items.filter { item ->
        val matchCategory = categoryFilter == null || item.category == categoryFilter
        val q = search.trim()
        val matchSearch = q.isBlank() ||
            item.title.contains(q, ignoreCase = true) ||
            item.content.contains(q, ignoreCase = true)
        matchCategory && matchSearch
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择大纲（已选 ${selected.size}）") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("搜索大纲标题 / 内容") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (categories.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = categoryFilter == null, onClick = { categoryFilter = null },
                            label = { Text("全部") })
                        categories.forEach { c ->
                            FilterChip(selected = categoryFilter == c, onClick = { categoryFilter = c },
                                label = { Text(outlineCategoryLabel(c)) })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                HorizontalDivider()
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "暂无匹配大纲",
                                Modifier.padding(vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    items(filtered, key = { it.id }) { item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(item.id) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = item.id in selected, onCheckedChange = { onToggle(item.id) })
                            Column {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    outlineCategoryLabel(item.category),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/** AI 工具多选弹窗（目前仅「写入大纲工具」，以后新增工具自动出现在列表里） */
@Composable
private fun AiToolsPickerDialog(
    tools: List<AiToolInfo>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 工具（已选 ${selected.size}）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (tools.isEmpty()) Text("暂无可用工具")
                tools.forEach { tool ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(tool.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = tool.id in selected, onCheckedChange = { onToggle(tool.id) })
                        Column {
                            Text(tool.name)
                            Text(
                                tool.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/** 通用导入确认弹窗（写入大纲 / 导入章节梗概共用）：默认写入唯一勾选 / 会话绑定的书；可换书，也可现场新建书籍 */
@Composable
private fun ImportConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    books: List<Book>,
    groups: List<BookGroup>,
    resolveTarget: suspend () -> Long?,
    onCreateBook: suspend (BookFormResult) -> Long,
    onPickCover: (android.net.Uri, (String?) -> Unit) -> Unit,
    onCreateGroup: suspend (String) -> Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    var targetBookId by remember { mutableStateOf<Long?>(null) }
    var showBookPicker by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { targetBookId = resolveTarget() }
    val targetBook = books.firstOrNull { it.id == targetBookId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    message,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showBookPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        targetBook?.let { "写入到：《${it.title}》" } ?: "选择要写入的书籍",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = targetBookId != null,
                onClick = { targetBookId?.let(onConfirm) }
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showBookPicker) {
        BookPickerDialog(
            books = books,
            groups = groups,
            selectedId = targetBookId,
            onSelect = { targetBookId = it },
            onCreateBook = onCreateBook,
            onPickCover = onPickCover,
            onCreateGroup = onCreateGroup,
            onDismiss = { showBookPicker = false }
        )
    }
}