package com.lianwenqi.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.util.FormatSettings
import com.lianwenqi.app.util.StyleSettings
import com.lianwenqi.app.util.TextTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 翻页方式：上下滚动 */
private const val PAGE_SCROLL = 0

/** 翻页方式：左右翻页 */
private const val PAGE_TURN = 1

/** 正文底部为常驻页脚预留的额外间距（dp） */
private const val READER_FOOTER_SPACE = 22

/** 左右翻页模式下触发切章所需的纵向滑动距离（px） */
private const val CHAPTER_SWIPE_PX = 140f

/** 左右翻页模式下在首/末页继续横滑触发切章的最小距离（dp） */
private const val CHAPTER_SWIPE_DP = 56

/** 进度面板里的一个分卷分组；[volumeId] 为 null 表示未分卷章节 */
private data class ReaderVolumeGroup(
    val volumeId: Long?,
    val title: String,
    val chapters: List<Chapter>
)

/** 按分卷把章节分组（未分卷或卷已被删除的章节归入「未分卷」） */
private fun readerVolumeGroups(chapters: List<Chapter>, volumes: List<Volume>): List<ReaderVolumeGroup> {
    val known = volumes.map { it.id }.toSet()
    val byVolume = chapters.groupBy { it.volumeId }
    val grouped = volumes.mapNotNull { v ->
        byVolume[v.id]?.takeIf { it.isNotEmpty() }?.let { ReaderVolumeGroup(v.id, v.title, it) }
    }
    val orphans = chapters.filter { it.volumeId == null || it.volumeId !in known }
    return if (orphans.isEmpty()) grouped else listOf(ReaderVolumeGroup(null, "未分卷", orphans)) + grouped
}

/**
 * 按「段距」重组正文：正文按自然段切分（丢弃段与段之间已有的空行），
 * 再用「段距」指定的空行数重新拼接，段落之间的距离完全由该设置控制。
 */
private fun applyParagraphSpacing(content: String, blankLines: Int): String {
    val paragraphs = content.replace("\r\n", "\n")
        .split('\n')
        .filter { it.isNotBlank() }
    if (paragraphs.isEmpty()) return content
    val gap = "\n".repeat(blankLines.coerceAtLeast(0) + 1)
    return paragraphs.joinToString(gap)
}

class ReaderViewModel(container: AppContainer, private val bookId: Long) : ViewModel() {
    private val bookRepository = container.bookRepository
    private val prefs = container.prefs

    val book: StateFlow<Book?> =
        bookRepository.observeBook(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val chapters: StateFlow<List<Chapter>> =
        bookRepository.observeChapters(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val volumes: StateFlow<List<Volume>> =
        bookRepository.observeVolumes(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    // 阅读页样式与排版独立于编辑页，互不影响
    val style: StateFlow<StyleSettings> =
        prefs.readerStyle.stateIn(viewModelScope, SharingStarted.Eagerly, StyleSettings())
    val format: StateFlow<FormatSettings> =
        prefs.readerFormat.stateIn(viewModelScope, SharingStarted.Eagerly, FormatSettings())
    val pageMode: StateFlow<Int> =
        prefs.readerPageMode.stateIn(viewModelScope, SharingStarted.Eagerly, PAGE_SCROLL)
    val immersive: StateFlow<Boolean> =
        prefs.readerImmersive.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun updateStyle(block: (StyleSettings) -> StyleSettings) = viewModelScope.launch {
        prefs.updateReaderStyle(block)
    }

    fun updateFormat(block: (FormatSettings) -> FormatSettings) = viewModelScope.launch {
        prefs.updateReaderFormat(block)
    }

    fun setPageMode(mode: Int) = viewModelScope.launch { prefs.setReaderPageMode(mode) }

    fun setImmersive(on: Boolean) = viewModelScope.launch { prefs.setReaderImmersive(on) }

    /** 记住读到哪一章，便于下次继续 */
    fun rememberProgress(chapterId: Long) = viewModelScope.launch {
        prefs.setLastOpened(bookId, chapterId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: Long, chapterId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "reader-$bookId") { ReaderViewModel(it, bookId) }
    val book by vm.book.collectAsState()
    val chapters by vm.chapters.collectAsState()
    val volumes by vm.volumes.collectAsState()
    val style by vm.style.collectAsState()
    val format by vm.format.collectAsState()
    val pageMode by vm.pageMode.collectAsState()
    val immersive by vm.immersive.collectAsState()

    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var currentId by remember { mutableStateOf(chapterId) }
    var menuVisible by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    var chapterProgress by remember { mutableStateOf(0f) }
    // 连续滚动模式下的外部跳转请求（上一章 / 下一章 / 目录 / 进度）
    var jumpIndex by remember { mutableStateOf(0) }
    var jumpTick by remember { mutableStateOf(0) }

    val chapter = chapters.firstOrNull { it.id == currentId }
    val index = chapters.indexOfFirst { it.id == currentId }
    // 进入阅读器时要定位到的章节
    val initialIndex = chapters.indexOfFirst { it.id == chapterId }

    // 全屏阅读：隐藏系统状态栏与导航栏，退出时恢复
    DisposableEffect(immersive) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    LaunchedEffect(currentId) {
        chapterProgress = 0f
        vm.rememberProgress(currentId)
    }

    BackHandler(enabled = menuVisible || panel != null || drawerState.isOpen) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            panel != null -> panel = null
            else -> menuVisible = false
        }
    }

    /** 跳转到指定章节：更新当前章节并请求滚动列表定位 */
    fun jumpTo(target: Int) {
        if (target in chapters.indices) {
            jumpIndex = target
            jumpTick++
            currentId = chapters[target].id
        }
    }

    fun goChapter(delta: Int) {
        val from = if (index >= 0) index else initialIndex
        jumpTo(from + delta)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(300.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                windowInsets = WindowInsets.statusBars
            ) {
                CatalogPanel(
                    chapters = chapters,
                    volumes = volumes,
                    currentId = currentId,
                    wordCount = { TextTool.countWords(it) },
                    onSelect = { id ->
                        jumpTo(chapters.indexOfFirst { it.id == id })
                        scope.launch { drawerState.close() }
                    },
                    onNew = {},
                    showNew = false
                )
            }
        }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(style.backgroundColor))
        ) {
            when (pageMode) {
                PAGE_TURN -> PagedReader(
                    chapter = chapter,
                    style = style,
                    format = format,
                    onToggleMenu = { menuVisible = !menuVisible },
                    onPrevPage = { goChapter(-1) },
                    onNextPage = { goChapter(1) },
                    onProgress = { chapterProgress = it }
                )

                else -> ScrollReader(
                    chapters = chapters,
                    initialIndex = initialIndex,
                    jumpIndex = jumpIndex,
                    jumpTick = jumpTick,
                    style = style,
                    format = format,
                    onToggleMenu = { menuVisible = !menuVisible },
                    onCurrentIndex = { idx ->
                        chapters.getOrNull(idx)?.let { currentId = it.id }
                    },
                    onProgress = { chapterProgress = it }
                )
            }

            // 常驻页脚：章节进度与全书进度
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = format.paddingHorizontal.dp,
                        end = format.paddingHorizontal.dp,
                        bottom = 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 常驻页脚：分卷进度与全书进度
                val groups = remember(chapters, volumes) { readerVolumeGroups(chapters, volumes) }
                val groupIndex = groups.indexOfFirst { g -> g.chapters.any { it.id == currentId } }
                // 只有真正归入某个分卷时才按卷显示；未分卷的书仍显示全书进度
                val group = groups.getOrNull(groupIndex)?.takeIf { it.volumeId != null }
                val inGroupIndex = group?.chapters?.indexOfFirst { it.id == currentId } ?: -1
                val volumeChapters = group?.chapters ?: chapters
                val progress = if (group != null && inGroupIndex >= 0) {
                    (inGroupIndex + chapterProgress.coerceIn(0f, 1f)) / volumeChapters.size
                } else if (chapters.isEmpty() || index < 0) 0f else {
                    (index + chapterProgress.coerceIn(0f, 1f)) / chapters.size
                }
                val positionText = if (group != null && inGroupIndex >= 0) {
                    "${group.title} · 第 ${inGroupIndex + 1}/${volumeChapters.size} 章"
                } else {
                    "第 ${index + 1}/${chapters.size} 章"
                }
                Text(
                    positionText,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(style.textColor).copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${(progress * 100).toInt()}% · ${book?.title.orEmpty()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(style.textColor).copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            AnimatedVisibility(
                visible = menuVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ReaderTopBar(
                    bookTitle = book?.title.orEmpty(),
                    chapterTitle = chapter?.title.orEmpty(),
                    onBack = onBack
                )
            }

            AnimatedVisibility(
                visible = menuVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(Modifier.fillMaxWidth()) {
                    when (panel) {
                        "style" -> ReaderStylePanel(
                            style = style,
                            format = format,
                            pageMode = pageMode,
                            immersive = immersive,
                            onStyle = { vm.updateStyle(it) },
                            onFormat = { vm.updateFormat(it) },
                            onPageMode = { vm.setPageMode(it) },
                            onImmersive = { vm.setImmersive(it) }
                        )

                        "progress" -> ProgressPanel(
                            chapters = chapters,
                            volumes = volumes,
                            currentId = currentId,
                            onJump = { id -> jumpTo(chapters.indexOfFirst { it.id == id }) },
                            onDismiss = { panel = null }
                        )
                    }
                    ReaderBottomBar(
                        hasPrev = index > 0,
                        hasNext = index in 0 until chapters.size - 1,
                        panel = panel,
                        onPrev = { goChapter(-1) },
                        onNext = { goChapter(1) },
                        onCatalog = {
                            menuVisible = false
                            panel = null
                            scope.launch { drawerState.open() }
                        },
                        onTogglePanel = { panel = if (panel == it) null else it }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(bookTitle: String, chapterTitle: String, onBack: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    chapterTitle.ifBlank { "阅读" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (bookTitle.isNotBlank()) {
                    Text(
                        bookTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    hasPrev: Boolean,
    hasNext: Boolean,
    panel: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCatalog: () -> Unit,
    onTogglePanel: (String) -> Unit
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = false,
                    enabled = hasPrev,
                    onClick = onPrev,
                    modifier = Modifier.height(34.dp),
                    label = { Text("上一章", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCatalog) { Text("目录") }
                TextButton(onClick = { onTogglePanel("progress") }) {
                    Text("进度", color = if (panel == "progress") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface)
                }
                TextButton(onClick = { onTogglePanel("style") }) {
                    Text("样式", color = if (panel == "style") MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = false,
                    enabled = hasNext,
                    onClick = onNext,
                    modifier = Modifier.height(34.dp),
                    label = { Text("下一章", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                )
            }
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

/** 阅读样式面板：字号、字体、行距、段距、缩进、边距、背景、翻页方式 */
@Composable
private fun ReaderStylePanel(
    style: StyleSettings,
    format: FormatSettings,
    pageMode: Int,
    immersive: Boolean,
    onStyle: (((StyleSettings) -> StyleSettings)) -> Unit,
    onFormat: (((FormatSettings) -> FormatSettings)) -> Unit,
    onPageMode: (Int) -> Unit,
    onImmersive: (Boolean) -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 330.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("背景", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BgColors.forEach { c ->
                    Box(
                        Modifier
                            .size(if (c == style.backgroundColor) 32.dp else 28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(c))
                            .clickable {
                                // 深色背景自动切换为浅色文字，避免看不清
                                val dark = Color(c).luminance() < 0.4f
                                onStyle {
                                    it.copy(
                                        backgroundColor = c,
                                        textColor = if (dark) 0xFFB0B0B0.toInt()
                                        else if (Color(it.textColor).luminance() > 0.6f) 0xFF1A1A1A.toInt()
                                        else it.textColor
                                    )
                                }
                            }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("字号：${style.fontSizeSp.toInt()} sp")
            Slider(
                value = style.fontSizeSp,
                onValueChange = { v -> onStyle { it.copy(fontSizeSp = v) } },
                valueRange = 12f..40f
            )
            Text("字体")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("默认", "衬线", "等宽", "手写").forEach { label ->
                    FilterChip(
                        selected = style.fontLabel == label,
                        onClick = {
                            onStyle { it.copy(fontLabel = label, fontFamily = TextTool.fontFamilyOf(label)) }
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = style.bold,
                    onClick = { onStyle { it.copy(bold = !it.bold) } },
                    label = { Text("加粗", style = MaterialTheme.typography.labelMedium) }
                )
                FilterChip(
                    selected = style.italic,
                    onClick = { onStyle { it.copy(italic = !it.italic) } },
                    label = { Text("斜体", style = MaterialTheme.typography.labelMedium) }
                )
                ReaderTextColorsRow(style.textColor) { c -> onStyle { it.copy(textColor = c) } }
            }
            Spacer(Modifier.height(8.dp))
            Text("行距：${"%.1f".format(format.lineSpacing)}")
            Slider(
                value = format.lineSpacing,
                onValueChange = { v -> onFormat { it.copy(lineSpacing = v) } },
                valueRange = 1.0f..3.0f
            )
            Text("段距：${format.blankLines} 行")
            Slider(
                value = format.blankLines.toFloat(),
                onValueChange = { v -> onFormat { it.copy(blankLines = v.toInt()) } },
                valueRange = 0f..2f,
                steps = 1
            )
            Text("首行缩进：${format.indent} 字符")
            Slider(
                value = format.indent.toFloat(),
                onValueChange = { v -> onFormat { it.copy(indent = v.toInt()) } },
                valueRange = 0f..4f
            )
            Text("左右边距：${format.paddingHorizontal} dp")
            Slider(
                value = format.paddingHorizontal.toFloat(),
                onValueChange = { v -> onFormat { it.copy(paddingHorizontal = v.toInt()) } },
                valueRange = 0f..64f
            )
            Text("上下边距：${format.paddingVertical} dp")
            Slider(
                value = format.paddingVertical.toFloat(),
                onValueChange = { v -> onFormat { it.copy(paddingVertical = v.toInt()) } },
                valueRange = 0f..64f
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("翻页方式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = pageMode == PAGE_SCROLL,
                    onClick = { onPageMode(PAGE_SCROLL) },
                    label = { Text("上下滚动", style = MaterialTheme.typography.labelMedium) }
                )
                FilterChip(
                    selected = pageMode == PAGE_TURN,
                    onClick = { onPageMode(PAGE_TURN) },
                    label = { Text("左右翻页", style = MaterialTheme.typography.labelMedium) }
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("全屏阅读（隐藏系统栏）", modifier = Modifier.weight(1f))
                Switch(checked = immersive, onCheckedChange = onImmersive)
            }
        }
    }
}

/** 文字颜色预设（与编辑页共用同一套色板） */
@Composable
private fun RowScope.ReaderTextColorsRow(selected: Int, onPick: (Int) -> Unit) {
    TextColors.forEach { c ->
        Box(
            Modifier
                .size(if (c == selected) 30.dp else 26.dp)
                .align(Alignment.CenterVertically)
                .clip(RoundedCornerShape(13.dp))
                .background(Color(c))
                .clickable { onPick(c) }
        )
    }
}

@Composable
private fun ProgressPanel(
    chapters: List<Chapter>,
    volumes: List<Volume>,
    currentId: Long,
    onJump: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val groups = remember(chapters, volumes) { readerVolumeGroups(chapters, volumes) }
    // 默认定位到当前章节所在的分卷
    var volumeIndex by remember(groups) {
        mutableStateOf(
            groups.indexOfFirst { g -> g.chapters.any { it.id == currentId } }.coerceAtLeast(0)
        )
    }
    val group = groups.getOrNull(volumeIndex)
    val scoped = group?.chapters?.takeIf { it.isNotEmpty() } ?: chapters
    // 切换分卷后，滑块定位到该卷内当前章节（不在该卷则回到第一页）
    var sliderValue by remember(group) {
        val idx = scoped.indexOfFirst { it.id == currentId }
        mutableStateOf((if (idx >= 0) idx else 0).toFloat())
    }
    val safeIndex = sliderValue.toInt().coerceIn(0, (scoped.size - 1).coerceAtLeast(0))
    val bookIndex = scoped.getOrNull(safeIndex)?.let { c -> chapters.indexOfFirst { it.id == c.id } } ?: -1

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (group?.volumeId != null) "${group.title} · 第 ${safeIndex + 1} / ${scoped.size} 章"
                        else "第 ${safeIndex + 1} / ${scoped.size} 章",
                        fontWeight = FontWeight.SemiBold
                    )
                    if (group?.volumeId != null && bookIndex >= 0) {
                        Text(
                            "全书第 ${bookIndex + 1} / ${chapters.size} 章",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "收起")
                }
            }
            // 分卷切换：多卷时可按卷查看（也用于在卷间跳转）
            if (groups.size > 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groups.forEachIndexed { i, g ->
                        FilterChip(
                            selected = i == volumeIndex,
                            onClick = { volumeIndex = i },
                            label = {
                                Text(
                                    g.title.ifBlank { "未分卷" },
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (scoped.isNotEmpty()) {
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    valueRange = 0f..(scoped.size - 1).coerceAtLeast(1).toFloat(),
                    steps = (scoped.size - 2).coerceAtLeast(0)
                )
                Text(
                    scoped[safeIndex].title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scoped.getOrNull(safeIndex)?.let { onJump(it.id) }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("跳转") }
            }
        }
    }
}

/**
 * 上下滚动模式：把整本书按章节依次渲染成一条连续列表，
 * 读完一章继续滑动，下一章正文自然接在下方；反向滑动即可回到上一章。
 */
@Composable
private fun ScrollReader(
    chapters: List<Chapter>,
    initialIndex: Int,
    jumpIndex: Int,
    jumpTick: Int,
    style: StyleSettings,
    format: FormatSettings,
    onToggleMenu: () -> Unit,
    onCurrentIndex: (Int) -> Unit,
    onProgress: (Float) -> Unit
) {
    val listState = rememberLazyListState()
    val textStyle = readerTextStyle(style, format)

    // 首次拿到章节数据时定位到进入时的章节
    LaunchedEffect(initialIndex) {
        if (initialIndex in chapters.indices) listState.scrollToItem(initialIndex)
    }

    // 外部跳转（上一章 / 下一章 / 目录 / 进度）
    LaunchedEffect(jumpTick) {
        if (jumpTick > 0 && jumpIndex in chapters.indices) {
            listState.animateScrollToItem(jumpIndex)
        }
    }

    // 当前章节 = 可视区中线所在的章节项
    val centerIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) -1 else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                items.firstOrNull { center >= it.offset && center < it.offset + it.size }?.index
                    ?: items.minByOrNull { abs(center - (it.offset + it.size / 2)) }?.index
                    ?: -1
            }
        }
    }

    // 当前章节内的阅读进度
    val progress by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val items = info.visibleItemsInfo
            if (items.isEmpty()) 0f else {
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                val item = items.firstOrNull { center >= it.offset && center < it.offset + it.size }
                    ?: items.last()
                ((center - item.offset).toFloat() / item.size.coerceAtLeast(1))
                    .coerceIn(0f, 1f)
            }
        }
    }

    val currentIndex by rememberUpdatedState(onCurrentIndex)
    val currentProgress by rememberUpdatedState(onProgress)
    LaunchedEffect(centerIndex) { if (centerIndex >= 0) currentIndex(centerIndex) }
    LaunchedEffect(progress) { currentProgress(progress) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onToggleMenu() } }
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(
            start = format.paddingHorizontal.dp,
            end = format.paddingHorizontal.dp,
            top = format.paddingVertical.dp,
            bottom = (format.paddingVertical + READER_FOOTER_SPACE).dp
        )
    ) {
        itemsIndexed(chapters, key = { _, ch -> ch.id }) { i, ch ->
            Column(Modifier.fillMaxWidth()) {
                if (i > 0) Spacer(Modifier.height(28.dp))
                if (ch.title.isNotBlank()) {
                    Text(
                        ch.title,
                        style = textStyle.copy(
                            fontSize = (style.fontSizeSp + 4).sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = applyParagraphSpacing(
                        ch.content.ifBlank { "（本章暂无正文）" },
                        format.blankLines
                    ),
                    style = textStyle
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 左右翻页模式：按可视区域对整章文本分页 */
@Composable
private fun PagedReader(
    chapter: Chapter?,
    style: StyleSettings,
    format: FormatSettings,
    onToggleMenu: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onProgress: (Float) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val textStyle = readerTextStyle(style, format)
    val pageScope = rememberCoroutineScope()

    val full: AnnotatedString = remember(
        chapter?.id, chapter?.title, chapter?.content, style, format.blankLines
    ) {
        buildAnnotatedString {
            if (!chapter?.title.isNullOrBlank()) {
                withStyle(SpanStyle(fontSize = (style.fontSizeSp + 4).sp, fontWeight = FontWeight.Bold)) {
                    append(chapter?.title.orEmpty())
                }
                append("\n\n")
            }
            append(
                applyParagraphSpacing(
                    chapter?.content.orEmpty().ifBlank { "（本章暂无正文）" },
                    format.blankLines
                )
            )
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(
                start = format.paddingHorizontal.dp,
                end = format.paddingHorizontal.dp,
                top = format.paddingVertical.dp,
                bottom = (format.paddingVertical + READER_FOOTER_SPACE).dp
            )
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }

        val pages = remember(full, widthPx, heightPx, textStyle) {
            paginate(measurer, full, textStyle, widthPx, heightPx)
        }
        val pagerState = rememberPagerState(pageCount = { pages.size })

        // pointerInput 只启动一次，回调与页数必须用最新值，否则翻页分区会用到旧章节的数据
        val toggleMenu by rememberUpdatedState(onToggleMenu)
        val prevChapter by rememberUpdatedState(onPrevPage)
        val nextChapter by rememberUpdatedState(onNextPage)

        // 换章后回到该章第一页
        LaunchedEffect(chapter?.id) { pagerState.scrollToPage(0) }

        LaunchedEffect(pagerState.currentPage, pages.size) {
            onProgress(
                if (pages.size <= 1) 1f
                else pagerState.currentPage.toFloat() / (pages.size - 1)
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                // 左右滑动切章：在末页继续左滑进下一章、在首页继续右滑回上一章。
                // 这里只在 Initial 阶段旁听手势、不消费事件，不影响 Pager 自身的翻页。
                .pointerInput(Unit) {
                    val threshold = CHAPTER_SWIPE_DP.dp.toPx()
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val startPage = pagerState.currentPage
                            val lastPage = pagerState.pageCount - 1
                            var dx = 0f
                            var dy = 0f
                            var pressed = true
                            while (pressed) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) break
                                dx += change.positionChange().x
                                dy += change.positionChange().y
                                pressed = change.pressed
                            }
                            // 横向意图明显且已经停在边界页，才切章
                            if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.5f) {
                                if (dx < 0f && startPage >= lastPage) nextChapter()
                                else if (dx > 0f && startPage <= 0) prevChapter()
                            }
                        }
                    }
                }
        ) { page ->
            Box(
                Modifier
                    .fillMaxSize()
                    // 上下滑动切章：继续往下滑（手指向上）进下一章，往上滑回上一章
                    .pointerInput(Unit) {
                        var acc = 0f
                        var switched = false
                        detectVerticalDragGestures(
                            onDragStart = {
                                acc = 0f
                                switched = false
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (!switched) {
                                    acc += dragAmount
                                    if (abs(acc) > CHAPTER_SWIPE_PX) {
                                        switched = true
                                        if (acc < 0f) nextChapter() else prevChapter()
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val third = size.width / 3f
                            val last = pagerState.pageCount - 1
                            when {
                                offset.x < third ->
                                    if (pagerState.currentPage > 0) {
                                        pageScope.animateTo(pagerState, pagerState.currentPage - 1)
                                    } else prevChapter()

                                offset.x > third * 2 ->
                                    if (pagerState.currentPage < last) {
                                        pageScope.animateTo(pagerState, pagerState.currentPage + 1)
                                    } else nextChapter()

                                else -> toggleMenu()
                            }
                        }
                    }
            ) {
                Text(
                    text = pages.getOrElse(page) { AnnotatedString("") },
                    style = textStyle
                )
            }
        }
    }
}

/** 翻页动画：横向平移到目标页 */
private fun CoroutineScope.animateTo(state: PagerState, page: Int) {
    launch { state.animateScrollToPage(page) }
}

/** 把整章文本按可视高度切成若干页，切分点落在行边界上 */
private fun paginate(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int
): List<AnnotatedString> {
    if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return listOf(text)
    val layout = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = widthPx)
    )
    if (layout.lineCount == 0) return listOf(text)
    val limit = heightPx.toFloat()
    val pages = mutableListOf<AnnotatedString>()
    var line = 0
    while (line < layout.lineCount) {
        val top = layout.getLineTop(line)
        var end = line
        while (end < layout.lineCount && layout.getLineBottom(end) - top <= limit) end++
        if (end == line) end = line + 1
        val startChar = layout.getLineStart(line)
        val endChar = if (end >= layout.lineCount) text.length else layout.getLineStart(end)
        pages.add(text.subSequence(startChar, endChar))
        line = end
    }
    return pages.ifEmpty { listOf(text) }
}

private fun readerTextStyle(style: StyleSettings, format: FormatSettings): TextStyle = TextStyle(
    fontSize = style.fontSizeSp.sp,
    fontFamily = style.fontFamily,
    fontWeight = TextTool.weightOf(style.bold),
    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
    textAlign = if (style.center) TextAlign.Center else TextAlign.Start,
    color = Color(style.textColor),
    lineHeight = (style.fontSizeSp * format.lineSpacing).sp
)

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
