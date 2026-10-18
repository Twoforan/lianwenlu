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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ReceiptLong
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
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
import com.lianwenqi.app.ai.GenerationDialog
import com.lianwenqi.app.ai.SummaryFailureChoice
import com.lianwenqi.app.data.PromptLog
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
    val pinnedCats by vm.pinnedOutlineCategories.collectAsState()
    val selBooks by vm.selectedBookIds.collectAsState()
    val selChapters by vm.selectedChapterIds.collectAsState()
    val selSummary by vm.summaryChapterIds.collectAsState()
    val selContent by vm.contentChapterIds.collectAsState()
    val selOutline by vm.selectedOutlineIds.collectAsState()
    val summaryAll by vm.summaryAllSelected.collectAsState()
    val contentAll by vm.contentAllSelected.collectAsState()
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
    // 长按会话弹出的操作菜单（改名 / 换绑书籍）
    var sessionMenu by remember { mutableStateOf<ChatSession?>(null) }
    // 换绑书籍对话框的目标会话
    var rebindingSession by remember { mutableStateOf<ChatSession?>(null) }
    var renamingChapterId by remember { mutableStateOf<Long?>(null) }
    // 长按未绑定章节的「第X轮对话」分组标题后改名：目标消息 id + 当前显示名
    var renamingRound by remember { mutableStateOf<Pair<Long, String>?>(null) }
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
                    selected = summaryAll,
                    onClick = { vm.selectAllSummary() },
                    enabled = selBooks.isNotEmpty(),
                    label = { Text("仅梗概") }
                )
                FilterChip(
                    selected = contentAll,
                    onClick = { vm.selectAllContent() },
                    enabled = selBooks.isNotEmpty(),
                    label = { Text("仅正文") }
                )
            }

            HorizontalDivider()

            val entries = remember(messages) { buildChatEntries(messages) }
            // 按「消息所属章节」连续分组：每一组都整组包裹（含最后一组，这样最后一章的对话也有章节抬头、可收起）。
            // 未绑定章节的对话则按「一轮对话」各自成块（标题为「第X轮对话」，可长按改名）。
            val chapterGroups = remember(entries) { buildChapterGroups(entries) }
            // 折叠状态（键为分组 key）：默认只展开「焦点分组」（最后一组），其余收起
            val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
            val focusKey = chapterGroups.lastOrNull()?.key
            // 「查看日志 / 查看本轮提示词」的目标消息（为空表示弹窗关闭）
            var logTarget by remember { mutableStateOf<ChatMessage?>(null) }
            var promptTarget by remember { mutableStateOf<ChatMessage?>(null) }
            // 一条对话的渲染：被章节分组包裹与不包裹都复用同一份
            val renderEntry: @Composable (ChatEntry) -> Unit = { entry ->
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
                    onViewLog = { logTarget = it },
                    onViewPrompt = { promptTarget = it },
                    onStop = { vm.stopGenerating() }
                )
            }
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
                    items(chapterGroups.size, key = { chapterGroups[it].key }) { gi ->
                        val group = chapterGroups[gi]
                        // 每组都整组包裹：绑定章节的组显示章节名抬头（最后一组默认展开，其余默认收起），
                        // 未绑定章节的组包成「第X轮对话」卡片（避免导航栏 AI 里最后一章的对话缺少抬头、无法收起）。
                        val collapsed = collapsedGroups[group.key] ?: (group.key != focusKey)
                        val bound = groupBoundToChapter(group, chapters)
                        val title = groupTitle(group, chapters)
                        ChapterGroupCard(
                            title = title,
                            // 组内「对话」条数（一问一答算一条）与回答字数
                            rounds = group.entries.count { it is ChatEntry.User },
                            words = group.entries.sumOf { e ->
                                // 回答作为「用户消息的版本」随用户条目展示，字数从当前展示的版本取
                                when (e) {
                                    is ChatEntry.User ->
                                        e.variants.getOrNull(e.activeIndex)?.content?.length ?: 0
                                    is ChatEntry.Assistant -> e.msg.content.length
                                }
                            },
                            preview = groupPreview(group),
                            collapsed = collapsed,
                            onToggle = { collapsedGroups[group.key] = !collapsed },
                            // 章节已删除时视为「解除映射」，长按改为改「本轮对话」名（只改消息，不同步书籍）
                            onLongPressTitle = if (bound) {
                                { renamingChapterId = group.chapterId }
                            } else {
                                { renamingRound = group.anchorMessageId() to title }
                            }
                        ) {
                            if (!collapsed) group.entries.forEach { renderEntry(it) }
                        }
                    }
                }
            }

            // 「查看日志」：分「预设 / 上下文 / 本轮」三部分回显本轮发送时的提示词（默认收起，逐层展开）
            logTarget?.let { msg ->
                PromptLogDialog(log = PromptLog.decode(msg.promptLog), onClose = { logTarget = null })
            }
            // 「查看本轮提示词」：回显本轮实际发送的完整提示词
            promptTarget?.let { msg ->
                PromptTextDialog(
                    log = PromptLog.decode(msg.promptLog),
                    onCopy = { clipboard.setText(AnnotatedString(it)) },
                    onClose = { promptTarget = null }
                )
            }

            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp))
            }

            // 当前上下文快照，用于底部「文件清单」回显
            val manifestChapters = selChapters.map { id ->
                ManifestChapterRow(
                    title = chapters.firstOrNull { it.id == id }?.title ?: "（章节已删除）",
                    summary = summaryAll || id in selSummary,
                    content = contentAll || id in selContent
                )
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

            ChatComposer(
                value = input,
                onValueChange = { vm.input.value = it },
                placeholder = "说点什么…",
                sendEnabled = !sending && input.isNotBlank(),
                onSend = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    vm.send()
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
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
            pinnedCategories = pinnedCats,
            onToggle = { vm.toggleOutline(it) },
            onToggleAll = { vm.toggleOutlines(it) },
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
                                            onLongPress = { sessionMenu = s }
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
                        "长按会话可修改名称或切换绑定书籍",
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
    // 长按会话：选择「修改名称 / 切换绑定书籍」
    sessionMenu?.let { target ->
        AlertDialog(
            onDismissRequest = { sessionMenu = null },
            title = { Text(target.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(
                        onClick = { renamingSession = target; sessionMenu = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("修改名称") }
                    TextButton(
                        onClick = { rebindingSession = target; sessionMenu = null },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("切换绑定书籍") }
                }
            },
            confirmButton = { TextButton(onClick = { sessionMenu = null }) { Text("取消") } }
        )
    }
    rebindingSession?.let { target ->
        SessionRebindDialog(
            session = target,
            books = books,
            onDismiss = { rebindingSession = null },
            onConfirm = { bookId, keepHistory, keepPreset ->
                vm.rebindSession(target.id, bookId, keepHistory, keepPreset)
                rebindingSession = null
            }
        )
    }
    renamingChapterId?.let { target ->
        ChapterRenameDialog(
            chapterId = target,
            chapters = chapters,
            onConfirm = { vm.renameChapter(target, it); renamingChapterId = null },
            onDismiss = { renamingChapterId = null }
        )
    }
    renamingRound?.let { (targetId, current) ->
        RoundRenameDialog(
            initialName = current,
            onConfirm = { vm.renameRound(targetId, it); renamingRound = null },
            onDismiss = { renamingRound = null }
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
    /** 把「生成梗概」产出的梗概写回当前章节的梗概（经编辑器本地缓冲，避免被整章保存覆盖） */
    onGeneratedSummary: (String) -> Unit,
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
            AiAssistantPanel(bookId, chapterId, onDismiss, onWriteToChapter, onGeneratedSummary, onNewChapter)
        }
    }
}

@Composable
private fun AiAssistantPanel(
    bookId: Long,
    chapterId: Long,
    onDismiss: () -> Unit,
    onWriteToChapter: (String) -> Unit,
    onGeneratedSummary: (String) -> Unit,
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
    val selSummary by vm.summaryChapterIds.collectAsState()
    val selContent by vm.contentChapterIds.collectAsState()
    val selOutline by vm.selectedOutlineIds.collectAsState()
    val pinnedCats by vm.pinnedOutlineCategories.collectAsState()
    val summaryOn by vm.currentSummaryOn.collectAsState()
    val chapterAll by vm.chapterAllSelected.collectAsState()
    val summaryAll by vm.summaryAllSelected.collectAsState()
    val contentAll by vm.contentAllSelected.collectAsState()
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
    val generationDialog by vm.generationDialog.collectAsState()
    val generationRunning by vm.generationRunning.collectAsState()
    val generationMessage by vm.generationMessage.collectAsState()

    // 当前打开的章节不进入上下文
    val contextChapters = chapters.filter { it.id != chapterId }
    val modelName = models.firstOrNull { it.id == activeModelId }?.displayName ?: "未选择模型"

    // 底部按钮对应的展开面板：context / outline / prompt
    var openPanel by remember { mutableStateOf<String?>(null) }
    // 文件清单的展开状态（0 收起 / 1 表格 / 2 txt）；与上方展开面板互斥
    var manifestState by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val ui = remember { MessageUiState() }
    // 「查看日志 / 查看本轮提示词」的目标消息（为空表示弹窗关闭）
    var logTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var promptTarget by remember { mutableStateOf<ChatMessage?>(null) }
    // 长按章节分组标题后可编辑章节名（为空表示弹窗关闭）
    var renamingChapterId by remember { mutableStateOf<Long?>(null) }
    // 长按未绑定章节的「第X轮对话」分组标题后可改名（目标消息 id + 当前显示名）
    var renamingRound by remember { mutableStateOf<Pair<Long, String>?>(null) }

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
            // 侧边栏按「所属章节」分组收起：未绑定章节的对话同样按「一轮对话」成块。
            val chapterGroups = remember(entries) { buildChapterGroups(entries) }
            val collapsedGroups = remember { mutableStateMapOf<String, Boolean>() }
            // 焦点分组：优先「当前绑定章节」对应的对话（打开抽屉即滚到并展开它），否则取最后一组
            val focusGroup = chapterGroups.lastOrNull { it.chapterId == chapterId }
                ?: chapterGroups.lastOrNull()
            val focusKey = focusGroup?.key
            val focusIndex = chapterGroups.indexOf(focusGroup)
            val focusIsBound = (focusGroup?.chapterId ?: 0L) > 0
            val listState = rememberLazyListState()
            // 打开抽屉 / 切换章节 / 新增消息后：绑定章节则滚到该章节对话处，否则滚到最后一条对话的末端
            LaunchedEffect(chapterId, entries.size) {
                if (entries.isEmpty()) return@LaunchedEffect
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                if (focusIsBound && focusIndex >= 0) listState.scrollToItem(focusIndex)
                else listState.scrollToBottom()
            }
            // 一条对话的渲染（侧边栏专属：回答可一键写入当前章节）
            val renderEntry: @Composable (ChatEntry) -> Unit = { entry ->
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
                    onViewLog = { logTarget = it },
                    onViewPrompt = { promptTarget = it },
                    onStop = { vm.stopGenerating() },
                    onWriteToChapter = onWriteToChapter
                )
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
                    items(chapterGroups.size, key = { chapterGroups[it].key }) { gi ->
                        val group = chapterGroups[gi]
                        val collapsed = collapsedGroups[group.key] ?: (group.key != focusKey)
                        val bound = groupBoundToChapter(group, chapters)
                        val title = groupTitle(group, chapters)
                        ChapterGroupCard(
                            title = title,
                            rounds = group.entries.count { it is ChatEntry.User },
                            words = group.entries.sumOf { e ->
                                when (e) {
                                    is ChatEntry.User ->
                                        e.variants.getOrNull(e.activeIndex)?.content?.length ?: 0
                                    is ChatEntry.Assistant -> e.msg.content.length
                                }
                            },
                            preview = groupPreview(group),
                            collapsed = collapsed,
                            onToggle = { collapsedGroups[group.key] = !collapsed },
                            // 章节已删除时视为「解除映射」，长按改为改「本轮对话」名（只改消息，不同步书籍）
                            onLongPressTitle = if (bound) {
                                { renamingChapterId = group.chapterId }
                            } else {
                                { renamingRound = group.anchorMessageId() to title }
                            }
                        ) {
                            if (!collapsed) group.entries.forEach { renderEntry(it) }
                        }
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
                    summarySelected = selSummary,
                    contentSelected = selContent,
                    allSelected = chapterAll,
                    summaryAll = summaryAll,
                    contentAll = contentAll,
                    onToggleAll = { vm.selectAllChapters() },
                    onToggleSummary = { vm.selectAllSummary() },
                    onToggleContent = { vm.selectAllContent() },
                    onToggleChapter = { vm.toggleChapter(it) },
                    onToggleChapterSummary = { vm.toggleChapterSummary(it) },
                    onToggleChapterContent = { vm.toggleChapterContent(it) },
                    onToggleVolume = { vm.toggleVolume(it) },
                    onToggleVolumeSummary = { vm.toggleVolumeSummary(it) },
                    onToggleVolumeContent = { vm.toggleVolumeContent(it) }
                )

                "outline" -> OutlinePicker(
                    items = outline,
                    selected = selOutline,
                    pinnedCategories = pinnedCats,
                    onToggleAll = { vm.toggleOutlines(it) },
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
            ChatComposer(
                value = input,
                onValueChange = { vm.input.value = it },
                placeholder = "向ai提问",
                sendEnabled = !sending && input.isNotBlank(),
                onSend = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    vm.send()
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // 所选文件的反馈：文件清单 → 表格 → 生成的 txt
            ContextManifest(
                snapshot = ContextSnapshot(
                    chapterIds = selChapters.toList(),
                    outlineIds = selOutline.toList(),
                    summaryChapterIds = selSummary.toList(),
                    contentChapterIds = selContent.toList(),
                    summaryAll = summaryAll,
                    contentAll = contentAll
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

            // 「＋本章梗概」「快捷提示词」「生成梗概」「生成正文」快捷按钮行
            //（发送按钮已并入上方输入卡片）；抽屉较窄时横向滚动，保证末尾的生成按钮可见可点
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
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
                // 「生成梗概」：按「生成梗概」配置（是否限定范围 → 是否先弹标签选择）直接生成并写回本章梗概
                FilterChip(
                    selected = false,
                    enabled = !generationRunning && !sending,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        openPanel = null
                        manifestState = 0
                        vm.startGenerateSummary(onGeneratedSummary)
                    },
                    modifier = Modifier.height(32.dp),
                    label = {
                        Text(
                            "生成梗概",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
                // 「生成正文」：生成前先一次性弹完所有决策弹窗，产出作为一条 AI 回答展示，由用户手动写入正文
                FilterChip(
                    selected = false,
                    enabled = !generationRunning && !sending,
                    onClick = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        openPanel = null
                        manifestState = 0
                        vm.startGenerateContent(onGeneratedSummary)
                    },
                    modifier = Modifier.height(32.dp),
                    label = {
                        Text(
                            "生成正文",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
                if (generationRunning) {
                    FilterChip(
                        selected = true,
                        onClick = { vm.cancelGeneration() },
                        modifier = Modifier.height(32.dp),
                        label = {
                            Text(
                                "停止生成",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    )
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

    // 生成相关提示（已生成 / 超上限已截断 / 略短 / 失败等）
    LaunchedEffect(generationMessage) {
        generationMessage?.let {
            Toast.makeText(panelContext, it, Toast.LENGTH_SHORT).show()
            vm.clearGenerationMessage()
        }
    }

    // 生成流程的选择型弹窗：全部在收集阶段弹完，任一取消（含点遮罩 / 返回键）即终止整个流程
    when (val dialog = generationDialog) {
        GenerationDialog.PickSummaryOutlines -> {
            var picked by remember { mutableStateOf(emptySet<Long>()) }
            OutlinePickerDialog(
                items = outline,
                selected = picked,
                pinnedCategories = pinnedCats,
                onToggle = { id -> picked = if (id in picked) picked - id else picked + id },
                onToggleAll = { ids -> picked = if (ids.all { it in picked }) picked - ids else picked + ids },
                onDismiss = { vm.cancelGeneration() },
                confirmText = "确定",
                onConfirm = { vm.confirmSummaryOutlines(picked.toList()) },
                showCancel = true
            )
        }

        is GenerationDialog.AskMemoryRetrieval -> AlertDialog(
            onDismissRequest = { vm.cancelGeneration() },
            title = { Text("是否启用记忆检索") },
            text = {
                Text(
                    "本次将以本章梗概作为检索词：\n\n${dialog.queryDigest}\n\n" +
                        "开启：命中的本书正文片段作为参考资料一并生成；\n" +
                        "关闭：仅按本章梗概与本会话前缀生成。"
                )
            },
            confirmButton = { TextButton(onClick = { vm.chooseMemoryRetrieval(true) }) { Text("开启") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.chooseMemoryRetrieval(false) }) { Text("关闭") }
                    TextButton(onClick = { vm.cancelGeneration() }) { Text("取消") }
                }
            }
        )

        is GenerationDialog.SummaryFailed -> AlertDialog(
            onDismissRequest = { vm.resolveSummaryFailed(SummaryFailureChoice.CANCEL) },
            title = { Text("梗概生成失败") },
            text = {
                Text(
                    if (dialog.hasExisting) {
                        "本章梗概未能生成。可以重试，或直接使用本章已有的梗概继续生成正文。"
                    } else {
                        "本章梗概未能生成，且本章暂无可用梗概。请重试，或取消本次生成。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.resolveSummaryFailed(SummaryFailureChoice.RETRY) }) { Text("重试梗概") }
            },
            dismissButton = {
                Row {
                    if (dialog.hasExisting) {
                        TextButton(onClick = {
                            vm.resolveSummaryFailed(SummaryFailureChoice.USE_EXISTING)
                        }) { Text("使用已有梗概") }
                    }
                    TextButton(onClick = { vm.resolveSummaryFailed(SummaryFailureChoice.CANCEL) }) { Text("取消") }
                }
            }
        )

        is GenerationDialog.Notice -> AlertDialog(
            onDismissRequest = { vm.cancelGeneration() },
            title = { Text("无法生成") },
            text = { Text(dialog.text) },
            confirmButton = { TextButton(onClick = { vm.cancelGeneration() }) { Text("确定") } }
        )

        null -> Unit
    }

    // 「查看日志」：分「预设 / 上下文 / 本轮」三部分回显本轮发送时的提示词（默认收起，逐层展开）
    logTarget?.let { msg ->
        PromptLogDialog(log = PromptLog.decode(msg.promptLog), onClose = { logTarget = null })
    }
    // 「查看本轮提示词」：回显本轮实际发送的完整提示词
    promptTarget?.let { msg ->
        PromptTextDialog(
            log = PromptLog.decode(msg.promptLog),
            onCopy = { clipboard.setText(AnnotatedString(it)) },
            onClose = { promptTarget = null }
        )
    }
    // 长按章节分组标题 → 修改章节名（保存后同步到书籍）
    renamingChapterId?.let { target ->
        ChapterRenameDialog(
            chapterId = target,
            chapters = chapters,
            onConfirm = { vm.renameChapter(target, it); renamingChapterId = null },
            onDismiss = { renamingChapterId = null }
        )
    }
    // 长按「第X轮对话」分组标题 → 改本轮显示名（只改界面，不同步书籍）
    renamingRound?.let { (targetId, current) ->
        RoundRenameDialog(
            initialName = current,
            onConfirm = { vm.renameRound(targetId, it); renamingRound = null },
            onDismiss = { renamingRound = null }
        )
    }
}

/** 长按章节分组标题后弹出的「修改章节名」弹窗：保存后写回书籍中的章节标题 */
@Composable
private fun ChapterRenameDialog(
    chapterId: Long,
    chapters: List<Chapter>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(chapterId) {
        mutableStateOf(chapters.firstOrNull { it.id == chapterId }?.title.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改章节名") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("章节名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onConfirm(title) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 长按未绑定章节的「第X轮对话」分组标题后弹出的「修改本轮对话名称」弹窗。
 * 只改这条对话在界面的显示名，不会推送到书籍章节。
 */
@Composable
private fun RoundRenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改本轮对话名称") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("本轮对话名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 「切换绑定书籍」弹窗：选目标书籍 + 两个勾选框决定是否把历史带入新书上下文。
 * - 历史对话：原有对话消息继续作为新书上下文；
 * - 历史预设：此前导入的大纲与章节梗概保留在会话预设中；
 * 都不勾选则清空预设，下次发送按新书重新建立上下文（章节映射同时解除）。
 */
@Composable
private fun SessionRebindDialog(
    session: ChatSession,
    books: List<Book>,
    onDismiss: () -> Unit,
    onConfirm: (bookId: Long, keepHistory: Boolean, keepPreset: Boolean) -> Unit
) {
    var targetId by remember(session.id) { mutableStateOf(session.bookId) }
    var keepHistory by remember(session.id) { mutableStateOf(true) }
    var keepPreset by remember(session.id) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换绑定书籍") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("选择要绑定的书籍：", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            books.firstOrNull { it.id == targetId }?.title ?: "选择书籍",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        books.forEach { b ->
                            DropdownMenuItem(
                                text = { Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                onClick = { targetId = b.id; expanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepHistory, onCheckedChange = { keepHistory = it })
                    Text("历史对话", modifier = Modifier.clickable { keepHistory = !keepHistory })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keepPreset, onCheckedChange = { keepPreset = it })
                    Text("历史预设", modifier = Modifier.clickable { keepPreset = !keepPreset })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "勾选「历史对话」：原有历史对话带入新书上下文；勾选「历史预设」：此前导入的大纲与章节梗概保留在预设中。都不勾选则按新书重新建立上下文（章节映射同时解除）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = targetId != null && targetId != session.bookId,
                onClick = { targetId?.let { onConfirm(it, keepHistory, keepPreset) } }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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

/** 「梗概 / 正文」两列的固定宽度，保证分卷行与章节行左右对齐 */
private val ContextColumnWidth = 56.dp

/** 上下文选择：全选 / 梗概 / 正文 + 分卷章节树（三列：纳入上下文 / 梗概 / 正文） */
@Composable
private fun ContextPicker(
    chapters: List<Chapter>,
    volumes: List<Volume>,
    selected: Set<Long>,
    summarySelected: Set<Long>,
    contentSelected: Set<Long>,
    allSelected: Boolean,
    summaryAll: Boolean,
    contentAll: Boolean,
    onToggleAll: () -> Unit,
    onToggleSummary: () -> Unit,
    onToggleContent: () -> Unit,
    onToggleChapter: (Long) -> Unit,
    onToggleChapterSummary: (Long) -> Unit,
    onToggleChapterContent: (Long) -> Unit,
    onToggleVolume: (Long) -> Unit,
    onToggleVolumeSummary: (Long) -> Unit,
    onToggleVolumeContent: (Long) -> Unit
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
                SmallChip("梗概", summaryAll, onToggleSummary)
                SmallChip("正文", contentAll, onToggleContent)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // 三列含义表头：纳入上下文 / 梗概 / 正文
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "章节",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.width(ContextColumnWidth), contentAlignment = Alignment.Center) {
                    Text(
                        "梗概",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box(Modifier.width(ContextColumnWidth), contentAlignment = Alignment.Center) {
                    Text(
                        "正文",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
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
                            onToggleExpand = { collapsed[v.id] = expanded },
                            summarySelected = list.all { it.id in summarySelected },
                            contentSelected = list.all { it.id in contentSelected },
                            onToggleSummary = { onToggleVolumeSummary(v.id) },
                            onToggleContent = { onToggleVolumeContent(v.id) }
                        ) { onToggleVolume(v.id) }
                        if (expanded) {
                            list.forEach { ch ->
                                ChapterSelectionRow(
                                    label = ch.title,
                                    selected = ch.id in selected,
                                    summary = ch.id in summarySelected,
                                    content = ch.id in contentSelected,
                                    startPadding = 26.dp,
                                    onToggle = { onToggleChapter(ch.id) },
                                    onToggleSummary = { onToggleChapterSummary(ch.id) },
                                    onToggleContent = { onToggleChapterContent(ch.id) }
                                )
                            }
                        }
                    }
                }
                chapters.filter { it.volumeId == null }.forEach { ch ->
                    ChapterSelectionRow(
                        label = ch.title,
                        selected = ch.id in selected,
                        summary = ch.id in summarySelected,
                        content = ch.id in contentSelected,
                        startPadding = 6.dp,
                        onToggle = { onToggleChapter(ch.id) },
                        onToggleSummary = { onToggleChapterSummary(ch.id) },
                        onToggleContent = { onToggleChapterContent(ch.id) }
                    )
                }
            }
        }
    }
}

/** 「梗概 / 正文」列的复选框：固定宽度、居中，保证跨行对齐 */
@Composable
private fun ContextColumnCheckbox(checked: Boolean, onToggle: () -> Unit) {
    Box(Modifier.width(ContextColumnWidth), contentAlignment = Alignment.Center) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** 上下文选择里的章节行：章节是否纳入 + 梗概 + 正文三列 */
@Composable
private fun ChapterSelectionRow(
    label: String,
    selected: Boolean,
    summary: Boolean,
    content: Boolean,
    startPadding: Dp,
    onToggle: () -> Unit,
    onToggleSummary: () -> Unit,
    onToggleContent: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = startPadding, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        ContextColumnCheckbox(summary, onToggleSummary)
        ContextColumnCheckbox(content, onToggleContent)
    }
}

/**
 * 分卷行：整卷选中 + 已选数量；[onToggleExpand] 非空时左侧显示展开/收起箭头。
 * [summarySelected] / [contentSelected] 非空时，右侧追加「梗概 / 正文」两列复选框。
 */
@Composable
private fun VolumeRow(
    label: String,
    allSelected: Boolean,
    picked: Int,
    total: Int,
    expanded: Boolean = true,
    onToggleExpand: (() -> Unit)? = null,
    summarySelected: Boolean? = null,
    contentSelected: Boolean? = null,
    onToggleSummary: (() -> Unit)? = null,
    onToggleContent: (() -> Unit)? = null,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onToggle)
            .padding(start = 2.dp, end = 8.dp),
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
            "$label  $picked/$total",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (summarySelected != null && onToggleSummary != null) {
            ContextColumnCheckbox(summarySelected, onToggleSummary)
        }
        if (contentSelected != null && onToggleContent != null) {
            ContextColumnCheckbox(contentSelected, onToggleContent)
        }
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
    pinnedCategories: Set<String>,
    onToggleAll: (Set<Long>) -> Unit,
    onToggle: (Long) -> Unit,
    onToggleCategory: (String) -> Unit
) {
    // 各分类（人设 / 世界观 / 故事大纲 / 灵感库 / 自定义标签）的收起状态（默认展开）
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    // 常驻 / 普通大纲筛选：0=全部，1=常驻，2=普通
    var scopeFilter by remember { mutableIntStateOf(0) }
    val visible = items.filter {
        when (scopeFilter) {
            1 -> it.category in pinnedCategories
            2 -> it.category !in pinnedCategories
            else -> true
        }
    }
    val visibleIds = visible.map { it.id }.toSet()
    val visibleAllSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selected }
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
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallChip("全选", visibleAllSelected, { onToggleAll(visibleIds) })
                SmallChip("全部大纲", scopeFilter == 0, { scopeFilter = 0 })
                SmallChip("常驻大纲", scopeFilter == 1, { scopeFilter = 1 })
                SmallChip("普通大纲", scopeFilter == 2, { scopeFilter = 2 })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                if (visible.isEmpty()) {
                    Text(
                        "暂无可选大纲",
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                visible.groupBy { it.category }.forEach { (category, list) ->
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

/** 对话展示项所属的消息 id（用于生成稳定分组键） */
private fun messageIdOf(entry: ChatEntry): Long = when (entry) {
    is ChatEntry.User -> entry.msg.id
    is ChatEntry.Assistant -> entry.msg.id
}

/** 对话展示项发生时所在的章节 id（0 表示未绑定章节） */
private fun chapterIdOf(entry: ChatEntry): Long = when (entry) {
    is ChatEntry.User -> entry.msg.chapterId
    is ChatEntry.Assistant -> entry.msg.chapterId
}

/** 对话展示项上记录的自定义轮次名（仅未绑定章节的对话会用到） */
private fun roundNameOf(entry: ChatEntry): String = when (entry) {
    is ChatEntry.User -> entry.msg.roundName
    is ChatEntry.Assistant -> entry.msg.roundName
}

/**
 * 对话列表分组后的一个块。[chapterId] 为 0 表示未绑定章节，此时每一轮对话各自成组。
 * [key] 是折叠状态用的稳定键，可保证同章节反复出现时也不重复（取首条消息 id）。
 */
private class ChatChapterGroup(
    val chapterId: Long,
    val entries: List<ChatEntry>,
    val key: String,
    /** 未绑定章节时：本轮序号（从 1 开始）；绑定章节时为 0 */
    val roundIndex: Int = 0
)

/**
 * 把展示项分组：
 * - 绑定章节的对话：同一章节内连续的一问一答归入同一组（章节变化即开新组）；
 * - 未绑定章节（chapterId = 0）的对话：每一轮（一问一答）各自成组，供「第X轮对话」成块收起。
 */
private fun buildChapterGroups(entries: List<ChatEntry>): List<ChatChapterGroup> {
    val groups = mutableListOf<ChatChapterGroup>()
    var roundIndex = 0
    entries.forEach { e ->
        val id = chapterIdOf(e)
        if (id > 0) {
            val last = groups.lastOrNull()
            if (last != null && last.chapterId == id) {
                groups[groups.lastIndex] =
                    ChatChapterGroup(id, last.entries + e, last.key)
            } else {
                groups += ChatChapterGroup(id, listOf(e), "c:$id:${messageIdOf(e)}")
            }
        } else {
            roundIndex += 1
            groups += ChatChapterGroup(0, listOf(e), "r:${messageIdOf(e)}", roundIndex)
        }
    }
    return groups
}

/** 未绑定章节分组的标题：优先用自定义轮次名，否则按序号显示「第X轮对话」 */
private fun ChatChapterGroup.roundTitle(): String =
    roundNameOf(entries.first()).ifBlank { "第${roundIndex}轮对话" }

/** 未绑定章节分组的长按改名目标（该组首条消息的 id） */
private fun ChatChapterGroup.anchorMessageId(): Long = messageIdOf(entries.first())

/**
 * 分组抬头：绑定且章节仍存在的显示章节名；绑定章节已被删除的视为「解除映射」，
 * 改显示该组自定义名（无则回落「（章节已删除）」）；未绑定章节的显示「第X轮对话」。
 */
private fun groupTitle(group: ChatChapterGroup, chapters: List<Chapter>): String {
    val chapter = chapters.firstOrNull { it.id == group.chapterId }
    return when {
        group.chapterId > 0 && chapter != null -> chapter.title
        group.chapterId > 0 ->
            roundNameOf(group.entries.first()).ifBlank { "（章节已删除）" }
        else -> group.roundTitle()
    }
}

/** 该分组是否仍「绑定」在现存章节上：为真时抬头改名同步书籍章节，否则只改本轮对话名 */
private fun groupBoundToChapter(group: ChatChapterGroup, chapters: List<Chapter>): Boolean =
    group.chapterId > 0 && chapters.any { it.id == group.chapterId }

/** 分组预览：收起时展示本组首条内容摘要（单行省略），让折叠态也能看出这轮聊了什么 */
private fun groupPreview(group: ChatChapterGroup): String {
    val text = when (val first = group.entries.firstOrNull()) {
        is ChatEntry.User -> first.msg.content
        is ChatEntry.Assistant -> first.msg.content
        else -> ""
    }
    return text.replace('\n', ' ').trim()
}

/**
 * 章节对话分组卡片：头部为「章节号徽标 + 章节名 + 本条统计」，点击整组展开 / 收起。
 * 收起时额外露出一行内容预览，展开时在卡片内渲染 [content]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterGroupCard(
    title: String,
    rounds: Int,
    words: Int,
    preview: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onLongPressTitle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    // 圆角不再由整张卡片统一 clip：超长分组的整体圆角裁剪会生成一块巨大的图形层，
    // 超出平台图层尺寸上限后，卡片下半部分（含尾部收起栏）会不绘制、也点不到 ——
    // 表现为「对话过长时尾部收起栏收起不了」。改为由「头部区块 / 尾条」各自绘制圆角。
    val topShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    val bottomShape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
    val headShape = if (collapsed) RoundedCornerShape(18.dp) else topShape
    val accent = MaterialTheme.colorScheme.primary
    // 参照原型：头部淡紫→纸白渐变、内容区纸白底、尾条淡紫底，逐块拼出「一张卡片包住这一章」
    val headBrush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.surface
        )
    )
    Column(Modifier.fillMaxWidth()) {
        // 头部区块：收起时四角圆角，展开时只有上两角圆角
        Column(
            Modifier
                .fillMaxWidth()
                .clip(headShape)
                .background(headBrush, headShape)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onToggle() }, onLongClick = onLongPressTitle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "共 $rounds 条对话 · 约 $words 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    if (collapsed) Icons.Filled.KeyboardArrowDown
                    else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (collapsed) "展开" else "收起",
                    modifier = Modifier.size(20.dp),
                    tint = accent
                )
            }
            if (collapsed && preview.isNotBlank()) {
                Text(
                    preview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 56.dp, end = 12.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!collapsed) {
            // 原型：分隔线为淡紫（#a6a1e9），并加重与头部的分界
            HorizontalDivider(color = accent.copy(alpha = 0.45f))
            Column(
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(top = 10.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
            // 尾部手柄：整组「头 — 对话 — 尾」中的尾块，点击即把整块向上收起（与头部点击等效）
            // 满宽贴底、41dp 高、淡紫底、大号 ∧；只保留顶部分隔线，底两角 10dp 圆角
            HorizontalDivider(color = accent.copy(alpha = 0.45f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(41.dp)
                    .clip(bottomShape)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        bottomShape
                    )
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = "收起",
                    modifier = Modifier.size(26.dp),
                    tint = accent
                )
            }
        }
    }
}

/** 日志弹窗中的分组标题（预设日志 / 上下文日志 / 本轮提示词日志） */
@Composable
private fun LogSectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * 日志弹窗中的一个可展开节点（默认收起）。
 * 头部为「箭头 + 标题」，展开后在其下方缩进渲染 [content]（子节点 / 纯文本）。
 */
@Composable
private fun LogNode(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
        if (expanded) {
            Column(
                Modifier.padding(start = 20.dp, bottom = 4.dp),
                content = content
            )
        }
    }
}

/** 日志弹窗中的纯文本节点：标题可展开，展开后显示 [text]（为空时显示占位） */
@Composable
private fun LogTextNode(title: String, text: String) {
    LogNode(title) {
        Text(
            text.ifBlank { "（空）" },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 「查看日志」弹窗（标题「提交日志」）：按「预设日志 → 上下文日志 → 本轮提示词日志」
 * 三层回显本轮发送时的提示词，所有节点默认收起、逐层展开。
 */
@Composable
private fun PromptLogDialog(log: PromptLog?, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("提交日志") },
        text = {
            if (log == null) {
                Text("本轮没有记录提示词日志（旧消息或未发送成功）")
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 一、预设日志：会话建立时冻结、每轮复用
                    LogSectionTitle("预设日志")
                    LogTextNode("预设提示词", log.systemPrompt)
                    LogTextNode("自定义前缀", log.customPrefix)
                    LogNode("大纲内容") {
                        if (log.outlines.isEmpty()) {
                            Text(
                                "（未勾选大纲）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            log.outlines.forEach { node ->
                                LogTextNode(node.title.ifBlank { "大纲条目" }, node.text)
                            }
                        }
                    }

                    // 二、上下文日志：历史上已发送过的轮次
                    LogSectionTitle("上下文日志")
                    if (log.rounds.isEmpty()) {
                        Text(
                            "（无历史轮次）",
                            modifier = Modifier.padding(vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        log.rounds.forEachIndexed { i, round ->
                            LogNode("第${i + 1}轮") {
                                Text(
                                    "用户：${round.user.ifBlank { "（空）" }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "助手：${round.assistant.ifBlank { "（空）" }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // 三、本轮提示词日志：本轮新拼入的部分
                    LogSectionTitle("本轮提示词日志")
                    LogTextNode("本章梗概", log.summary)
                    if (log.memory.isNotBlank()) LogTextNode("记忆检索参考资料", log.memory)
                    if (log.autoOutline.isNotBlank()) LogTextNode("新增大纲参考资料", log.autoOutline)
                    LogTextNode("用户提示词部分", log.userPrompt)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )
}

/** 「查看本轮提示词」弹窗：只回显本轮实际发送的完整提示词，可整段复制 */
@Composable
private fun PromptTextDialog(log: PromptLog?, onCopy: (String) -> Unit, onClose: () -> Unit) {
    val text = log?.fullPrompt.orEmpty()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("本轮提示词") },
        text = {
            if (text.isBlank()) {
                Text("本轮没有记录提示词（旧消息或未发送成功）")
            } else {
                key(text) {
                    SelectionContainer {
                        Text(
                            text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 440.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (text.isNotBlank()) {
                TextButton(onClick = { onCopy(text) }) { Text("复制") }
            }
            TextButton(onClick = onClose) { Text("关闭") }
        }
    )
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
    /** 「查看日志」：弹出本轮提示词日志（预设 / 上下文 / 本轮三部分） */
    onViewLog: (ChatMessage) -> Unit,
    /** 「查看本轮提示词」：弹出本轮实际发送的完整提示词 */
    onViewPrompt: (ChatMessage) -> Unit,
    onStop: () -> Unit,
    /** 侧边栏 AI 助手专属：把这条回答的正文写入当前章节；为空则不显示该操作（如导航栏 AI 对话页） */
    onWriteToChapter: ((String) -> Unit)? = null
) {
    when (entry) {
        is ChatEntry.User -> {
            val msg = entry.msg
            val generating = pendingReplyToId == msg.id
            // 「用户气泡 → 文件清单 → AI 回答」收进同一条块，块内间距收紧；
            // 外层 14dp 的分组间距只负责分隔不同轮次，避免清单与回答之间被撑出大片空白
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MessageCard(
                    ai = false,
                    tools = {
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
                ) {
                    // 长按可选中并复制消息内容；key 随 selectionReset 变化时重建以取消选中
                    key(ui.selectionReset) {
                        SelectionContainer {
                            Text(
                                text = msg.content,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
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
                if (generating) {
                    RespondingBlock(
                        content = streamingContent,
                        reasoning = streamingReasoning,
                        modelName = modelName,
                        onStop = onStop
                    )
                } else {
                    entry.variants.getOrNull(entry.activeIndex)?.let { active ->
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
                            onViewLog = onViewLog,
                            onViewPrompt = onViewPrompt,
                            onWriteToChapter = onWriteToChapter
                        )
                    }
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
            onViewLog = onViewLog,
            onViewPrompt = onViewPrompt,
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
    onViewLog: (ChatMessage) -> Unit,
    onViewPrompt: (ChatMessage) -> Unit,
    onWriteToChapter: ((String) -> Unit)? = null
) {
    if (ui.editingId == msg.id) {
        MessageCard(
            ai = true,
            tools = {
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
        ) {
            OutlinedTextField(
                value = ui.editingText,
                onValueChange = { ui.editingText = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                maxLines = 12
            )
        }
    } else {
        MessageCard(
            ai = true,
            tools = {
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
                // 「查看日志」：分预设 / 上下文 / 本轮三部分回显本次发送的提示词
                MessageAction(Icons.Filled.ReceiptLong, "查看日志") {
                    ui.pendingDeleteId = null
                    onViewLog(msg)
                }
                // 「查看本轮提示词」：只看本轮真正发给 AI 的提示词
                MessageAction(Icons.Filled.Article, "查看本轮提示词") {
                    ui.pendingDeleteId = null
                    onViewPrompt(msg)
                }
                // 侧边栏专属：把本条回答正文写入当前章节（覆盖 + 一键排版）
                onWriteToChapter?.let { write ->
                    MessageAction(Icons.Filled.EditNote, "写入当前章节") {
                        ui.pendingDeleteId = null
                        write(msg.content)
                    }
                }
                DeleteAction(msg.id, ui, onDelete)
            }
        ) {
            if (msg.reasoning.isNotBlank()) {
                ThinkingBlock(msg.reasoning)
                Spacer(Modifier.height(6.dp))
            }
            // 回答正文不再单独收起：历史章节的对话由所属章节分组统一收起（见 ChapterGroupCard）
            AnswerBlock(
                content = msg.content,
                resetKey = ui.selectionReset
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
        }
    }
}

/**
 * 对话气泡卡片：正文 + 分隔线 + 操作栏。
 * 用户消息为纸白底，AI 消息为淡紫底；操作栏与正文之间用一条细分隔线分开（参照原型）。
 * 抬头不再显示时间与「分卷 · 章节名」——章节归属由分组卡片标题体现。
 */
@Composable
private fun MessageCard(
    ai: Boolean,
    tools: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val accent = MaterialTheme.colorScheme.primary
    val background = if (ai) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
    else MaterialTheme.colorScheme.surface
    val borderColor = if (ai) accent.copy(alpha = 0.20f) else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            // 不整卡 clip：长回答的圆角裁剪会生成一块超大图形层，超出平台图层尺寸上限后
            // 卡片下半部分（AI 操作行）既不绘制也点不到 —— 表现为「AI 功能区点按没反应」。
            // 背景/描边自带圆角，无需裁剪即可正确显示圆角。
            .background(background, shape)
            .border(1.dp, borderColor, shape)
    ) {
        Column(
            Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            content = content
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = tools
        )
    }
}

/**
 * 统一的对话输入区（参照原型 composer）：外侧圆角描边卡片，内嵌无边框输入框与圆形发送按钮。
 * 输入行随内容高度自适应，最多 5 行。
 */
@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            Box(
                Modifier
                    .padding(end = 2.dp, bottom = 5.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (sendEnabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable(enabled = sendEnabled, onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    modifier = Modifier.size(18.dp),
                    tint = if (sendEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
 * 回答正文：与灰色「深度思考」块区分开，始终是纯文本（保持旧版外观）。
 * 正文不再单独收起：历史对话由所属章节分组统一收起（见 [ChapterGroupCard]）。
 */
@Composable
private fun AnswerBlock(content: String, resetKey: Int) {
    // 长按可选中并复制回答内容；key 随 resetKey 变化时重建以取消选中
    key(resetKey) {
        SelectionContainer {
            Text(content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** 正在响应中：就地显示在目标回答的位置，实时流式显示内容，操作行可随时「停止响应」 */
@Composable
private fun RespondingBlock(
    content: String,
    reasoning: String,
    modelName: String,
    onStop: () -> Unit
) {
    var dots by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            dots = if (dots >= 3) 1 else dots + 1
        }
    }
    // 与生成完成后的 AssistantBlock 使用同一张 MessageCard 外壳（淡紫底 + 分隔线 + 操作行），
    // 生成中与生成完的 UI 保持一致，只有最后一行换成「正在生成…」提示。
    MessageCard(
        ai = true,
        tools = {
            TextButton(onClick = onStop) {
                Text("停止响应", style = MaterialTheme.typography.labelMedium)
            }
        }
    ) {
        if (reasoning.isNotBlank()) {
            ThinkingBlock(reasoning)
            Spacer(Modifier.height(6.dp))
        }
        if (content.isNotBlank()) {
            AnswerBlock(content = content, resetKey = 0)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "model: $modelName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                "正在生成" + "…".repeat(dots),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        ManifestChapterRow(
            title = chapters.firstOrNull { it.id == id }?.title ?: "（章节已删除）",
            summary = snapshot.summaryAll || id in snapshot.summaryChapterIds,
            content = snapshot.contentAll || id in snapshot.contentChapterIds
        )
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
            chapterRows = chapterRows,
            outlineRows = outlineRows,
            preview = preview
        )
    }
}

/** 文件清单里的一个章节行：标题 + 是否纳入梗概 / 正文 */
private data class ManifestChapterRow(val title: String, val summary: Boolean, val content: Boolean)

/** 文件清单内容区：state=1 显示清单表格，state=2 显示生成的 txt，其余不渲染 */
@Composable
private fun ContextManifestContent(
    state: Int,
    chapterRows: List<ManifestChapterRow>,
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
                    chapterRows.forEach { row ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                row.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (row.summary) "✓" else "—",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(52.dp)
                            )
                            Text(
                                if (row.content) "✓" else "—",
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
                LaunchedEffect(chapterRows, outlineRows) { text = preview() }
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

/** 大纲多选弹窗：按标签分类筛选 + 搜索标题 / 内容；支持常驻 / 普通大纲筛选 */
@Composable
private fun OutlinePickerDialog(
    items: List<OutlineItem>,
    selected: Set<Long>,
    pinnedCategories: Set<String>,
    onToggle: (Long) -> Unit,
    onToggleAll: (Set<Long>) -> Unit,
    onDismiss: () -> Unit,
    /** 「确定」按钮文案；与 [onConfirm] 一起用于需要「取消 / 确定」语义的场景（如生成梗概的范围选择） */
    confirmText: String = "完成",
    onConfirm: () -> Unit = onDismiss,
    /** 显示「取消」按钮（关闭 / 点击遮罩仍走 [onDismiss]，语义为取消） */
    showCancel: Boolean = false
) {
    var search by remember { mutableStateOf("") }
    // 标签筛选：null=全部，否则为分类存储值
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    // 常驻筛选：0=全部，1=常驻，2=普通
    var scopeFilter by remember { mutableIntStateOf(0) }
    val categories = items.map { it.category }.distinct()

    val filtered = items.filter { item ->
        val matchCategory = categoryFilter == null || item.category == categoryFilter
        val matchScope = when (scopeFilter) {
            1 -> item.category in pinnedCategories
            2 -> item.category !in pinnedCategories
            else -> true
        }
        val q = search.trim()
        val matchSearch = q.isBlank() ||
            item.title.contains(q, ignoreCase = true) ||
            item.content.contains(q, ignoreCase = true)
        matchCategory && matchScope && matchSearch
    }
    val filteredIds = filtered.map { it.id }.toSet()
    val filteredAllSelected = filteredIds.isNotEmpty() && filteredIds.all { it in selected }

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
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(selected = scopeFilter == 0, onClick = { scopeFilter = 0 },
                        label = { Text("全部大纲") })
                    FilterChip(selected = scopeFilter == 1, onClick = { scopeFilter = 1 },
                        label = { Text("常驻大纲") })
                    FilterChip(selected = scopeFilter == 2, onClick = { scopeFilter = 2 },
                        label = { Text("普通大纲") })
                    FilterChip(selected = filteredAllSelected, onClick = { onToggleAll(filteredIds) },
                        label = { Text("全选/取消") }, enabled = filteredIds.isNotEmpty())
                }
                Spacer(Modifier.height(4.dp))
                if (categories.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(selected = categoryFilter == null, onClick = { categoryFilter = null },
                            label = { Text("全部标签") })
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
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = if (showCancel) {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        } else null
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