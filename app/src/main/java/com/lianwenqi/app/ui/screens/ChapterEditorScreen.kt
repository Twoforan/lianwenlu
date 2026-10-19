package com.lianwenqi.app.ui.screens

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.util.ExportUtil
import com.lianwenqi.app.util.NameGenerator
import com.lianwenqi.app.util.TextTool
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditorScreen(
    bookId: Long,
    chapterId: Long,
    onBack: () -> Unit,
    onPreview: (Long, Long) -> Unit,
    onOpenBookSearch: (Long) -> Unit
) {
    val vm = appViewModel(key = "editor-$bookId-$chapterId") {
        ChapterEditorViewModel(it, bookId, chapterId)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current

    val book by vm.book.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val format by vm.format.collectAsState()
    val style by vm.style.collectAsState()
    val keepScreenOn by vm.keepScreenOn.collectAsState()
    val outline by vm.outline.collectAsState()

    var currentId by remember { mutableStateOf(chapterId) }
    var title by remember(currentId) { mutableStateOf("") }
    var summary by remember(currentId) { mutableStateOf("") }
    var content by remember(currentId) { mutableStateOf(TextFieldValue("")) }
    val undoStack = remember(currentId) { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember(currentId) { mutableStateListOf<TextFieldValue>() }

    var panel by remember { mutableStateOf<String?>(null) }
    var findVisible by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var summaryExpanded by remember(currentId) { mutableStateOf(false) }
    var editingTitle by remember { mutableStateOf(false) }
    val summaryFocus = remember { FocusRequester() }
    var aiOpen by remember { mutableStateOf(false) }
    // 正文是否拿到了焦点：梗概输入框聚焦时不能按正文光标去「顶起」，否则会把视野拽到正文
    var bodyFocused by remember { mutableStateOf(false) }

    LaunchedEffect(summaryExpanded) {
        if (summaryExpanded) summaryFocus.requestFocus()
    }

    // 输入法高度（动画中每帧变化），用于输入法弹出时收起功能区
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)

    // 正文滚动状态：输入法弹出压缩可视区时，把光标行「顶」到键盘上方
    val editorScroll = rememberScrollState()
    var editorLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var editorFieldTop by remember { mutableStateOf(0f) }
    val density = LocalDensity.current

    // 输入法弹出会把下方工具栏顶起、同时压缩正文可视区，此时光标若落在可视区下方就会被键盘遮住。
    // 这里只做「顶起」：向上滚动到光标行正好贴在键盘上方（留一点余量），而不是把光标居中——
    // 居中方式在「进入章节后第一次点正文」时会因补首行缩进而改变正文，导致校正失效。
    // 以 imeBottomPx / viewportSize / maxValue 为 key，可在输入法动画的每一帧持续校正直至稳定。
    LaunchedEffect(bodyFocused, content.selection, imeBottomPx, editorLayout, editorScroll.viewportSize, editorScroll.maxValue) {
        if (imeBottomPx <= 0) return@LaunchedEffect // 键盘收起时不动，避免干扰「恢复上次光标」
        if (!bodyFocused) return@LaunchedEffect // 编辑梗概等其它输入框时不要按正文光标滚动
        val layout = editorLayout ?: return@LaunchedEffect
        val viewport = editorScroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val rect = layout.getCursorRect(content.selection.start.coerceIn(0, content.text.length))
        val cursorTop = editorFieldTop + rect.top
        val cursorBottom = editorFieldTop + rect.bottom
        val margin = with(density) { 12.dp.toPx() }
        val visibleTop = editorScroll.value.toFloat()
        val visibleBottom = visibleTop + viewport
        when {
            // 光标被键盘挡住：向上滚动，让光标行落在键盘上方
            cursorBottom > visibleBottom - margin -> editorScroll.scrollTo(
                (cursorBottom + margin - viewport).toInt().coerceIn(0, editorScroll.maxValue)
            )
            // 光标被顶到可视区上方之外：带回可视区
            cursorTop < visibleTop + margin -> editorScroll.scrollTo(
                (cursorTop - margin).toInt().coerceIn(0, editorScroll.maxValue)
            )
        }
    }

    // 待恢复的光标位置：进入章节时把上次退出的光标滚到可视区（快速定位），-1 表示无需处理
    var restoreCursor by remember { mutableStateOf(-1) }

    LaunchedEffect(restoreCursor, editorLayout, editorScroll.viewportSize, editorScroll.maxValue) {
        val target = restoreCursor
        if (target < 0) return@LaunchedEffect
        val layout = editorLayout ?: return@LaunchedEffect
        // 布局结果必须已经是当前章节的正文，否则是上一章残留的布局
        if (layout.layoutInput.text.text != content.text) return@LaunchedEffect
        val viewport = editorScroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        restoreCursor = -1
        val rect = layout.getCursorRect(target.coerceIn(0, content.text.length))
        val cursorTop = editorFieldTop + rect.top
        // 落在可视区上部 1/3 处，便于接着往下写
        editorScroll.scrollTo((cursorTop - viewport / 3f).toInt().coerceIn(0, editorScroll.maxValue))
    }

    // 返回键优先收起面板，而不是直接退出编辑页
    BackHandler(enabled = aiOpen || findVisible || panel != null) {
        when {
            aiOpen -> aiOpen = false
            findVisible -> findVisible = false
            else -> panel = null
        }
    }

    // 输入法弹出时收起功能区，两者不共存；工具栏上方的查找条与 AI 输入面板不受影响
    val imeVisible = imeBottomPx > 0
    LaunchedEffect(imeVisible) {
        if (imeVisible && (panel == "style" || panel == "format" ||
                panel == "view" || panel == "toolbox")
        ) {
            panel = null
        }
    }

    LaunchedEffect(currentId) {
        vm.switchChapter(currentId)
        val c = vm.loadChapter(currentId)
        if (c != null) {
            title = c.title
            summary = c.summary
            val cursor = if (format.quickLocate) c.lastCursor.coerceIn(0, c.content.length) else 0
            content = TextFieldValue(c.content, TextRange(cursor))
            // 记住上次退出的光标：进入章节后滚到该处
            restoreCursor = if (cursor > 0) cursor else -1
            undoStack.clear(); redoStack.clear()
        }
    }

    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // 切换 / 新建章节时，把这一刻仍属于「当前章节」的缓冲区写回它自己的 id
    DisposableEffect(currentId) {
        val editingId = currentId
        onDispose {
            vm.saveChapter(editingId, title, summary, content.text, content.selection.start)
        }
    }

    fun pushChange(nv: TextFieldValue) {
        if (nv.text != content.text) {
            undoStack.add(content)
            if (undoStack.size > 200) undoStack.removeAt(0)
            redoStack.clear()
        }
        content = nv
        vm.onChange(title, summary, nv.text, nv.selection.start)
    }

    // 「写入当前章节」待确认的 AI 正文：本章已有正文时先弹窗询问，确认后再覆盖写入
    var pendingAiWrite by remember { mutableStateOf<String?>(null) }

    // 把「生成梗概」产出的梗概写回本章：更新本地缓冲后立即落库。
    // 必须改本地缓冲，否则切章 / 退出编辑页时的整章保存会用旧缓冲把新梗概覆盖回去。
    fun applyGeneratedSummary(text: String) {
        val next = text.trim()
        summary = next
        summaryExpanded = true
        vm.saveNow(title, next, content.text, content.selection.start)
    }

    // 把 AI 回答正文覆盖写入本章正文，并一键排版后落库
    fun writeAiToChapter(text: String) {
        keyboard?.hide()
        val formatted = TextTool.oneKeyFormat(text, format)
        pushChange(TextFieldValue(formatted, TextRange(formatted.length)))
        vm.saveNow(title, summary, formatted, formatted.length)
    }

    val indentUnit = "　".repeat(format.indent.coerceAtLeast(0))

    /**
     * 智能标点：打完冒号立即跟随成对双引号「“”」，光标落在两个引号之间。
     * 仅处理全角冒号「：」，以及中文（CJK）之后的半角冒号，避免误伤时间、英文等场景。
     * 只在「刚插入一个字符」时触发，删除引号（长度 -1）时不再补回，保证可正常删除。
     */
    fun withColonQuote(tv: TextFieldValue): TextFieldValue {
        if (!format.colonFollowQuote || tv.composition != null) return tv
        if (tv.text.length - content.text.length != 1) return tv
        if (!tv.selection.collapsed) return tv
        val pos = tv.selection.start
        if (pos <= 0 || pos > tv.text.length) return tv
        val text = tv.text
        val prev = text[pos - 1]
        val isFullColon = prev == '：'
        val isCjkColon = prev == ':' && pos >= 2 && run {
            val c = text[pos - 2].code
            c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x3040..0x30FF || c in 0xAC00..0xD7AF
        }
        if (!isFullColon && !isCjkColon) return tv
        // 冒号后面已经是引号时不重复插入
        if (pos < text.length && text[pos] in COLON_FOLLOW_SKIP) return tv
        return if (isCjkColon) {
            // 中文后的半角冒号：替换为全角冒号并补成对引号，光标落在两引号之间
            val cut = pos - 1
            val newText = text.substring(0, cut) + "：“”" + text.substring(pos)
            tv.copy(text = newText, selection = TextRange(cut + 2))
        } else {
            val newText = text.substring(0, pos) + "“”" + text.substring(pos)
            tv.copy(text = newText, selection = TextRange(pos + 1))
        }
    }

    fun paragraphStartOf(text: String, pos: Int): Int {
        if (pos <= 0) return 0
        val i = text.lastIndexOf('\n', pos - 1)
        return if (i < 0) 0 else i + 1
    }

    fun hasIndentAt(text: String, lineStart: Int): Boolean =
        indentUnit.isNotEmpty() && text.startsWith(indentUnit, lineStart)

    /** 光标在段首且该段尚未缩进时，立即补两格缩进（IME 组合中不打断） */
    fun ensureIndent(tv: TextFieldValue): TextFieldValue {
        if (indentUnit.isEmpty() || tv.composition != null) return tv
        val pos = tv.selection.start
        val lineStart = paragraphStartOf(tv.text, pos)
        if (pos != lineStart || hasIndentAt(tv.text, lineStart)) return tv
        return tv.copy(
            text = tv.text.substring(0, lineStart) + indentUnit + tv.text.substring(lineStart),
            selection = TextRange(pos + indentUnit.length)
        )
    }

    /** 输入处理：回车后新行立即带缩进；段首直接输入字符时兜底补缩进 */
    fun withAutoIndent(nv: TextFieldValue): TextFieldValue {
        if (indentUnit.isEmpty() || nv.composition != null) return nv
        val added = nv.text.length - content.text.length
        if (added <= 0) return nv
        val start = nv.selection.start - added
        if (start < 0 || start >= nv.text.length) return nv
        val inserted = nv.text.substring(start, nv.selection.start)
        if (!inserted.contains('\n')) return ensureIndent(nv)
        var text = nv.text
        var cursor = nv.selection.start
        var idx = inserted.lastIndexOf('\n')
        while (idx >= 0) {
            val abs = start + idx + 1
            if (!hasIndentAt(text, abs)) {
                text = text.substring(0, abs) + indentUnit + text.substring(abs)
                if (abs <= cursor) cursor += indentUnit.length
            }
            idx = inserted.lastIndexOf('\n', idx - 1)
        }
        return ensureIndent(TextFieldValue(text, TextRange(cursor)))
    }

    fun findRanges(find: String): List<IntRange> =
        if (find.isEmpty()) emptyList()
        else Regex(Regex.escape(find)).findAll(content.text).map { it.range }.toList()

    // 上一个/下一个匹配
    fun jumpToMatch(find: String, forward: Boolean) {
        val ranges = findRanges(find)
        if (ranges.isEmpty()) return
        val cur = content.selection.start
        val r = if (forward) ranges.firstOrNull { it.first > cur } ?: ranges.first()
        else ranges.lastOrNull { it.first < cur } ?: ranges.last()
        content = content.copy(selection = TextRange(r.first, r.last + 1))
    }

    // 替换当前（光标之后的第一处）
    fun replaceMatch(find: String, replace: String) {
        val ranges = findRanges(find)
        if (ranges.isEmpty()) return
        val cur = content.selection.start
        val r = ranges.firstOrNull { it.first >= cur } ?: ranges.first()
        val text = content.text.replaceRange(r.first, r.last + 1, replace)
        pushChange(TextFieldValue(text, TextRange((r.first + replace.length).coerceAtMost(text.length))))
    }

    fun replaceAllMatches(find: String, replace: String) {
        if (find.isEmpty()) return
        val text = content.text.replace(find, replace)
        pushChange(TextFieldValue(text, TextRange(text.length)))
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 直接返回书籍内部界面（不再展开目录 / 大纲抽屉）
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            findVisible = false
                            panel = null
                            aiOpen = false
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "返回书籍")
                        }
                        IconButton(onClick = {
                            vm.createChapter(null) { newId ->
                                currentId = newId
                                aiOpen = true
                            }
                        }) {
                            Icon(Icons.Filled.Add, contentDescription = "新建章节")
                        }
                        IconButton(
                            enabled = undoStack.isNotEmpty(),
                            onClick = {
                                val prev = undoStack.removeLast()
                                redoStack.add(content)
                                content = prev
                                vm.onChange(title, summary, prev.text, prev.selection.start)
                            }
                        ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "撤回") }
                        IconButton(
                            enabled = redoStack.isNotEmpty(),
                            onClick = {
                                val next = redoStack.removeLast()
                                undoStack.add(content)
                                content = next
                                vm.onChange(title, summary, next.text, next.selection.start)
                            }
                        ) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "还原") }
                        // 章节标题占据左右按钮之间的剩余空间，超出省略，点击可修改
                        Text(
                            text = title.ifBlank { "未命名章节" },
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { editingTitle = true }
                                .padding(vertical = 6.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium
                        )
                        IconButton(onClick = { onPreview(bookId, currentId) }) {
                            Icon(Icons.Filled.Visibility, contentDescription = "预览")
                        }
                        IconButton(onClick = {
                            vm.saveNow(title, summary, content.text, content.selection.start)
                            msg = "已保存"
                        }) { Icon(Icons.Filled.Done, contentDescription = "保存") }
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            findVisible = false
                            panel = null
                            aiOpen = true
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "AI")
                        }
                    }
                }
                HorizontalDivider()
            }
        },
        bottomBar = {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            ) {
                if (findVisible) {
                    HorizontalDivider()
                    FindReplaceBar(
                        content = content.text,
                        onJump = { f, forward -> jumpToMatch(f, forward) },
                        onReplaceCurrent = { f, r -> replaceMatch(f, r) },
                        onReplaceAll = { f, r -> replaceAllMatches(f, r) },
                        onDismiss = { findVisible = false }
                    )
                }
                EditorBottomBar(
                    onOneKeyFormat = {
                        keyboard?.hide()
                        val formatted = TextTool.oneKeyFormat(content.text, format)
                        pushChange(TextFieldValue(formatted, TextRange(formatted.length)))
                        vm.saveNow(title, summary, formatted, formatted.length)
                    },
                    onOpen = { key -> focusManager.clearFocus(); keyboard?.hide(); findVisible = false; panel = key }
                )
                when (panel) {
                    "style" -> {
                        HorizontalDivider()
                        StylePanel(
                            style = style,
                            onStyle = { vm.updateStyle(it) },
                            onDismiss = { panel = null }
                        )
                    }
                    "format" -> {
                        HorizontalDivider()
                        FormatPanel(
                            format = format,
                            onFormat = { vm.updateFormat(it) },
                            onDismiss = { panel = null }
                        )
                    }
                    "view" -> {
                        HorizontalDivider()
                        ViewGridPanel(
                            wordCount = vm.wordCount(content.text),
                            onFindReplace = { findVisible = true },
                            onBookSearch = {
                                keyboard?.hide()
                                panel = null
                                onOpenBookSearch(bookId)
                            },
                            onPaste = { clipboard.getText()?.text?.let { t -> pushChange(TextFieldValue(content.text + t, TextRange(content.text.length + t.length))) } },
                            onSelectAll = { content = content.copy(selection = TextRange(0, content.text.length)) },
                            onClear = { pushChange(TextFieldValue("", TextRange(0))) }
                        )
                    }
                    "toolbox" -> {
                        HorizontalDivider()
                        ToolboxPanel(
                            keepScreenOn = keepScreenOn,
                            onKeepScreenOn = { vm.setKeepScreenOn(it) },
                            onShareImage = {
                                val b = book; if (b != null) scope.launch {
                                    val ch = vm.loadChapter(currentId) ?: return@launch
                                    val f = ExportUtil.chapterToImage(context, b, ch.copy(title = title, content = content.text))
                                    ExportUtil.shareFile(context, f, "image/png", "分享章节图片")
                                }
                            },
                            onShareTxt = {
                                val b = book; if (b != null) scope.launch {
                                    val ch = vm.loadChapter(currentId) ?: return@launch
                                    val f = ExportUtil.chapterToTxt(context, b, ch.copy(title = title, summary = summary, content = content.text))
                                    ExportUtil.shareFile(context, f, "text/plain", "分享章节")
                                }
                            },
                            onShareWord = {
                                val b = book; if (b != null) scope.launch {
                                    val ch = vm.loadChapter(currentId) ?: return@launch
                                    val f = ExportUtil.chapterToWord(context, b, ch.copy(title = title, summary = summary, content = content.text))
                                    ExportUtil.shareFile(context, f, "application/msword", "分享章节")
                                }
                            },
                            onLocalPerson = { count, gender -> NameGenerator.personNames(count, gender) },
                            onLocalPlace = { count -> NameGenerator.placeNames(count) },
                            onAiGenerate = { prompt, cb -> vm.aiGenerate(prompt, cb) },
                            onInsert = { text -> pushChange(TextFieldValue(content.text + text, TextRange(content.text.length + text.length))) },
                            onDismiss = { panel = null }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(style.backgroundColor))
                .verticalScroll(editorScroll)
                .padding(horizontal = format.paddingHorizontal.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // 章节梗概：默认收起、居中一行；展开后灰字，下方一条浅色横线
            if (!summaryExpanded) {
                Text(
                    summary.ifBlank { "添加章节梗概" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { summaryExpanded = true }
                        .padding(vertical = 10.dp),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                BasicTextField(
                    value = summary,
                    onValueChange = {
                        summary = it
                        vm.onChange(title, summary, content.text, content.selection.start)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(summaryFocus),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Column {
                            if (summary.isEmpty()) {
                                Text(
                                    "章节梗概",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            inner()
                            Spacer(Modifier.height(6.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = content,
                onValueChange = { pushChange(withColonQuote(withAutoIndent(it))) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp)
                    .onGloballyPositioned { editorFieldTop = it.positionInParent().y }
                    .onFocusChanged { focus ->
                        bodyFocused = focus.isFocused
                        if (focus.isFocused) {
                            summaryExpanded = false
                            // 点击进入正文时立即补齐首行缩进
                            val indented = ensureIndent(content)
                            if (indented.text != content.text) pushChange(indented)
                        }
                    },
                onTextLayout = { editorLayout = it },
                textStyle = TextStyle(
                    fontSize = style.fontSizeSp.sp,
                    fontFamily = style.fontFamily,
                    fontWeight = TextTool.weightOf(style.bold),
                    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
                    textAlign = if (style.center) TextAlign.Center else TextAlign.Start,
                    color = Color(style.textColor),
                    lineHeight = (style.fontSizeSp * format.lineSpacing).sp
                ),
                cursorBrush = SolidColor(Color(style.textColor))
            )
            // 键盘弹出时在正文下方补一点余量，保证最后一行能滚到键盘上方（顶起），而不是贴住键盘边缘
            val bottomSpace = if (imeBottomPx > 0) format.paddingBottom.dp + 12.dp else format.paddingBottom.dp
            Spacer(Modifier.height(bottomSpace))
        }
    }

        AiAssistantDrawer(
            visible = aiOpen,
            bookId = bookId,
            chapterId = currentId,
            onDismiss = { aiOpen = false },
            onWriteToChapter = { text ->
                if (content.text.isBlank()) writeAiToChapter(text) else pendingAiWrite = text
            },
            onGeneratedSummary = { applyGeneratedSummary(it) },
            onNextChapter = {
                // 跳转本卷下一章节；没有下一章时先新建一章（落在本卷）再跳过去，并保持侧边 AI 对话栏打开
                val cur = chapters.firstOrNull { it.id == currentId }
                val ordered = chapters
                    .filter { it.volumeId == cur?.volumeId }
                    .sortedBy { it.sortOrder }
                val idx = ordered.indexOfFirst { it.id == currentId }
                val next = if (idx >= 0) ordered.getOrNull(idx + 1) else null
                if (next != null) {
                    currentId = next.id
                    aiOpen = true
                } else {
                    vm.createChapter(cur?.volumeId) { newId ->
                        currentId = newId
                        aiOpen = true
                    }
                }
            },
            onChapterRenamed = { id, name ->
                // 抽屉里改了本章名：同步本地缓冲，避免退出编辑页时用旧标题覆盖回数据库
                if (id == currentId) title = name
            }
        )
    }

    // 本章已有正文时，写入前先确认覆盖
    pendingAiWrite?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingAiWrite = null },
            title = { Text("覆盖本章正文") },
            text = { Text("本章已有正文，写入后将用这条 AI 回答覆盖整章正文并一键排版，是否继续？") },
            confirmButton = {
                TextButton(onClick = {
                    writeAiToChapter(text)
                    pendingAiWrite = null
                }) { Text("覆盖并排版") }
            },
            dismissButton = {
                TextButton(onClick = { pendingAiWrite = null }) { Text("取消") }
            }
        )
    }

    // 点击顶栏标题修改章节标题
    if (editingTitle) {
        var draft by remember { mutableStateOf(title) }
        AlertDialog(
            onDismissRequest = { editingTitle = false },
            title = { Text("修改章节标题") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("章节标题") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    title = draft
                    vm.onChange(title, summary, content.text, content.selection.start)
                    editingTitle = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editingTitle = false }) { Text("取消") }
            }
        )
    }

    msg?.let {
        AlertDialog(
            onDismissRequest = { msg = null },
            title = { Text("提示") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { msg = null }) { Text("好的") } }
        )
    }
}

/** 冒号后面已经是这些引号时不再自动补双引号 */
private val COLON_FOLLOW_SKIP = setOf('“', '”', '"', '「')

@Composable
private fun EditorBottomBar(onOneKeyFormat: () -> Unit, onOpen: (String) -> Unit) {
    Column {
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            BottomItem("一键排版", Icons.Filled.AutoFixHigh, Modifier.weight(1f), onOneKeyFormat)
            BottomItem("样式", Icons.Filled.Brush, Modifier.weight(1f)) { onOpen("style") }
            BottomItem("排版", Icons.Filled.Settings, Modifier.weight(1f)) { onOpen("format") }
            BottomItem("查看", Icons.Filled.Search, Modifier.weight(1f)) { onOpen("view") }
            BottomItem("工具箱", Icons.Filled.Handyman, Modifier.weight(1f)) { onOpen("toolbox") }
        }
    }
}

@Composable
private fun BottomItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}