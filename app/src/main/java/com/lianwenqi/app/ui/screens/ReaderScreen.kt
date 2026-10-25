package com.lianwenqi.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import com.lianwenqi.app.util.IMPORTED_IMAGE_PREFIX
import com.lianwenqi.app.util.ReaderBackgroundType
import com.lianwenqi.app.util.ReaderImport
import com.lianwenqi.app.util.ReaderPageStyle
import com.lianwenqi.app.util.ReaderSettings
import com.lianwenqi.app.util.StyleSettings
import com.lianwenqi.app.util.TextTool
import com.lianwenqi.app.util.importedName
import com.lianwenqi.app.util.importedPath
import com.lianwenqi.app.util.readerFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ptq.mpga.ptqbookpageview.widget.PTQBookPageView
import ptq.mpga.ptqbookpageview.widget.PTQBookPageViewRequest
import ptq.mpga.ptqbookpageview.widget.PTQBookPageViewState
import ptq.mpga.ptqbookpageview.widget.rememberPTQBookPageViewConfig
import kotlin.math.abs

/** 正文底部为常驻页脚预留的额外间距（dp） */
private const val READER_FOOTER_SPACE = 22

/** 分页模式下触发切章所需的横 / 纵向滑动距离（px / dp） */
private const val CHAPTER_SWIPE_PX = 140f
private const val CHAPTER_SWIPE_DP = 56

/** 仿真 / 覆盖翻页的手势触发阈值（px）与单次翻页动画时长（ms） */
private const val TURN_FLICK_PX = 70f
private const val TURN_ANIM_MS = 420
private const val TURN_CANCEL_MS = 180

/** 进度面板 / 目录里的一个分卷分组；[volumeId] 为 null 表示未分卷章节 */
internal data class ReaderVolumeGroup(
    val volumeId: Long?,
    val title: String,
    val chapters: List<Chapter>
)

/** 按分卷把章节分组（未分卷或卷已被删除的章节归入「未分卷」） */
internal fun readerVolumeGroups(chapters: List<Chapter>, volumes: List<Volume>): List<ReaderVolumeGroup> {
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
    val settings: StateFlow<ReaderSettings> =
        prefs.readerSettings.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val immersive: StateFlow<Boolean> =
        prefs.readerImmersive.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun updateStyle(block: (StyleSettings) -> StyleSettings) = viewModelScope.launch {
        prefs.updateReaderStyle(block)
    }

    fun updateFormat(block: (FormatSettings) -> FormatSettings) = viewModelScope.launch {
        prefs.updateReaderFormat(block)
    }

    fun updateSettings(block: (ReaderSettings) -> ReaderSettings) = viewModelScope.launch {
        prefs.updateReaderSettings(block)
    }

    fun addImportedFont(spec: String) = viewModelScope.launch { prefs.addImportedFont(spec) }

    fun addImportedImage(spec: String) = viewModelScope.launch { prefs.addImportedImage(spec) }

    fun removeImportedImage(spec: String) = viewModelScope.launch { prefs.removeImportedImage(spec) }

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
    val rawStyle by vm.style.collectAsState()
    val format by vm.format.collectAsState()
    val settings by vm.settings.collectAsState()
    val immersive by vm.immersive.collectAsState()

    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current

    // 内置楷体 / 宋体与导入字体在这里统一解析为实际可渲染的 FontFamily
    val fontFamily = remember(rawStyle.fontLabel, settings.importedFonts) {
        readerFontFamily(rawStyle.fontLabel, settings.importedFonts)
    }
    val style = remember(rawStyle, fontFamily) { rawStyle.copy(fontFamily = fontFamily) }

    var currentId by remember { mutableStateOf(chapterId) }
    var menuVisible by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    // 目录：沿用原来的左侧抽屉
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var chapterProgress by remember { mutableStateOf(0f) }
    // 连续滚动模式下的外部跳转请求（上一章 / 下一章 / 目录）
    var jumpIndex by remember { mutableIntStateOf(0) }
    var jumpTick by remember { mutableIntStateOf(0) }
    // 音量键翻页：只发信号，具体翻页由各阅读布局处理
    var navTick by remember { mutableIntStateOf(0) }
    var navDir by remember { mutableIntStateOf(1) }
    val focusRequester = remember { FocusRequester() }

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

    // 菜单 / 面板开合后重新拿回按键焦点，保证音量键翻页一直可用
    LaunchedEffect(menuVisible, panel) { focusRequester.requestFocus() }

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

    fun selectChapter(id: Long) {
        jumpTo(chapters.indexOfFirst { it.id == id })
        panel = null
        menuVisible = false
    }

    fun togglePanel(key: String) {
        panel = if (panel == key) null else key
    }

    // 点击正文空白处：收起菜单（连同已展开的面板）
    fun toggleMenu() {
        if (menuVisible) {
            menuVisible = false
            panel = null
        } else {
            menuVisible = true
        }
    }

    // 字体导入 / 背景导入：系统文件选择器 -> 复制到应用私有目录 -> 校验 -> 持久化
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { ReaderImport.importFont(context, uri) }
                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                if (result.ok) {
                    val spec = result.entry!!
                    vm.addImportedFont(spec)
                    vm.updateStyle {
                        it.copy(fontLabel = com.lianwenqi.app.util.IMPORTED_FONT_PREFIX + importedName(spec))
                    }
                }
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { ReaderImport.importImage(context, uri) }
                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                if (result.ok) {
                    val spec = result.entry!!
                    vm.addImportedImage(spec)
                    vm.updateSettings {
                        it.copy(
                            backgroundType = com.lianwenqi.app.util.ReaderBackgroundType.IMAGE,
                            backgroundImage = com.lianwenqi.app.util.IMPORTED_IMAGE_PREFIX +
                                importedName(spec)
                        )
                    }
                }
            }
        }
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
                        selectChapter(id)
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
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (!settings.volumeKeys) return@onPreviewKeyEvent false
                val isVolume = event.key == Key.VolumeUp || event.key == Key.VolumeDown
                if (!isVolume) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    navDir = if (event.key == Key.VolumeUp) 1 else -1
                    navTick++
                }
                // 开关键都消费掉，避免系统音量条弹出
                true
            }
            .focusable()
    ) {
        ReaderBackgroundLayer(style, settings)

        when (settings.pageStyle) {
            ReaderPageStyle.SCROLL -> ScrollReader(
                chapters = chapters,
                initialIndex = initialIndex,
                jumpIndex = jumpIndex,
                jumpTick = jumpTick,
                navTick = navTick,
                navDir = navDir,
                style = style,
                format = format,
                onToggleMenu = { toggleMenu() },
                onCurrentIndex = { idx ->
                    chapters.getOrNull(idx)?.let { currentId = it.id }
                },
                onProgress = { chapterProgress = it }
            )

            else -> PagedReader(
                chapter = chapter,
                style = style,
                format = format,
                reader = settings,
                pageStyle = settings.pageStyle,
                navTick = navTick,
                navDir = navDir,
                previousChapterData = if (index > 0) chapters.getOrNull(index - 1) else null,
                nextChapterData = if (index >= 0 && index < chapters.lastIndex) chapters.getOrNull(index + 1) else null,
                onToggleMenu = { toggleMenu() },
                onPrevChapter = { goChapter(-1) },
                onNextChapter = { goChapter(1) },
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
                    READER_PANEL_SETTINGS -> ReaderSettingsPanel(
                        style = style,
                        format = format,
                        reader = settings,
                        immersive = immersive,
                        onStyle = { vm.updateStyle(it) },
                        onFormat = { vm.updateFormat(it) },
                        onImmersive = { vm.setImmersive(it) },
                        onReader = { vm.updateSettings(it) }
                    )

                    READER_PANEL_STYLE -> ReaderStylePanel(
                        style = style,
                        reader = settings,
                        onStyle = { vm.updateStyle(it) },
                        onReader = { vm.updateSettings(it) },
                        onImportFont = {
                            runCatching {
                                fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/octet-stream"))
                            }.onFailure {
                                Toast.makeText(context, "无法打开字体选择器", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onImportImage = {
                            runCatching { imagePicker.launch(arrayOf("image/*")) }.onFailure {
                                Toast.makeText(context, "无法打开图片选择器", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDeleteImage = { spec ->
                            val name = importedName(spec)
                            val path = importedPath(spec)
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    path.takeIf { it.isNotBlank() }?.let { p ->
                                        runCatching { java.io.File(p).delete() }
                                    }
                                }
                                vm.removeImportedImage(spec)
                                if (settings.backgroundImage == IMPORTED_IMAGE_PREFIX + name) {
                                    vm.updateSettings {
                                        it.copy(
                                            backgroundType = ReaderBackgroundType.COLOR,
                                            backgroundImage = ""
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
                ReaderBottomBar(
                    panel = panel,
                    hasPrev = index > 0,
                    hasNext = index in 0 until chapters.size - 1,
                    onPrevChapter = { goChapter(-1) },
                    onNextChapter = { goChapter(1) },
                    onEntry = { entry ->
                        // 「目录」不再占用底部面板，改为打开左侧抽屉
                        if (entry == READER_PANEL_CATALOG) {
                            menuVisible = false
                            panel = null
                            scope.launch { drawerState.open() }
                        } else {
                            togglePanel(entry)
                        }
                    }
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
    navTick: Int,
    navDir: Int,
    style: StyleSettings,
    format: FormatSettings,
    onToggleMenu: () -> Unit,
    onCurrentIndex: (Int) -> Unit,
    onProgress: (Float) -> Unit
) {
    val listState = rememberLazyListState()
    val textStyle = readerTextStyle(style, format)
    val scope = rememberCoroutineScope()

    // 首次拿到章节数据时定位到进入时的章节
    LaunchedEffect(initialIndex) {
        if (initialIndex in chapters.indices) listState.scrollToItem(initialIndex)
    }

    // 外部跳转（上一章 / 下一章 / 目录）
    LaunchedEffect(jumpTick) {
        if (jumpTick > 0 && jumpIndex in chapters.indices) {
            listState.animateScrollToItem(jumpIndex)
        }
    }

    // 音量键：滚动模式按一屏滚动
    LaunchedEffect(navTick) {
        if (navTick <= 0) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        if (viewport > 0) listState.animateScrollBy(navDir * viewport * 0.9f)
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
                val annotated = remember(ch.id, ch.title, ch.content, style, format) {
                    readerChapterText(ch, style, format)
                }
                Text(text = annotated, style = textStyle)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 一页正文：记录本页在整章文本中的起始偏移，用于排版变化后恢复位置 */
private data class ReaderPage(val startChar: Int, val text: AnnotatedString)

/**
 * 分页阅读：同一套页面数据支持四种翻页动画。
 * [ReaderPageStyle.SLIDE] 纵向滑动；[ReaderPageStyle.SIMULATION] 3D 纸张翻动；
 * [ReaderPageStyle.NONE] 直接切换；[ReaderPageStyle.COVER] 新页固定、旧页滑出覆盖。
 */
@Composable
private fun PagedReader(
    chapter: Chapter?,
    style: StyleSettings,
    format: FormatSettings,
    reader: ReaderSettings,
    pageStyle: String,
    navTick: Int,
    navDir: Int,
    previousChapterData: Chapter?,
    nextChapterData: Chapter?,
    onToggleMenu: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onProgress: (Float) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val textStyle = readerTextStyle(style, format)
    val pageScope = rememberCoroutineScope()

    val full = remember(chapter?.id, chapter?.title, chapter?.content, style, format) {
        readerChapterText(chapter, style, format)
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        val localDensity = LocalDensity.current
        // 页内留白与页脚占位改为在每页内部内缩，页面本身铺满整屏，
        // 这样纸张背景可以和文字一起参与翻页动画。
        val pagePadding = PaddingValues(
            start = format.paddingHorizontal.dp,
            end = format.paddingHorizontal.dp,
            top = format.paddingVertical.dp,
            bottom = (format.paddingVertical + READER_FOOTER_SPACE).dp
        )
        val widthPx = with(localDensity) {
            (maxWidth - (format.paddingHorizontal * 2).dp).roundToPx()
        }
        val heightPx = with(localDensity) {
            (maxHeight - (format.paddingVertical * 2 + READER_FOOTER_SPACE).dp).roundToPx()
        }

        // 已排版过的章节页缓存：跨章翻页动画需要取到上一章末页 / 下一章首页
        val chapterPageCache = remember(widthPx, heightPx, textStyle) {
            mutableMapOf<Long, List<ReaderPage>>()
        }
        val chapterHeadCache = remember(widthPx, heightPx, textStyle) {
            mutableMapOf<Long, ReaderPage>()
        }
        val lineHeightPx = with(localDensity) {
            if (textStyle.lineHeight.value.isNaN()) {
                // lineHeight 未指定时按字号估算，仅用于跨章首页的排版行数上限
                (textStyle.fontSize * 1.6f).toPx()
            } else {
                textStyle.lineHeight.toPx()
            }
        }

        val pages = remember(full, widthPx, heightPx, textStyle) {
            paginate(measurer, full, textStyle, widthPx, heightPx).also { computed ->
                chapter?.id?.let { id ->
                    chapterPageCache[id] = computed
                    if (chapterPageCache.size > 5) {
                        chapterPageCache.keys.firstOrNull { key ->
                            key != id && key != previousChapterData?.id && key != nextChapterData?.id
                        }?.let(chapterPageCache::remove)
                    }
                }
            }
        }
        // 上一章末页：作为左侧跨章页。上一章本次没读过时在这里补排版一次（并写入缓存），
        // 保证「往回翻进上一章」同样有翻页动画，而不是直接跳章
        val previousTail = remember(pages, previousChapterData?.id, widthPx, heightPx, textStyle) {
            previousChapterData?.let { prev ->
                val computed = chapterPageCache[prev.id] ?: paginate(
                    measurer,
                    readerChapterText(prev, style, format),
                    textStyle,
                    widthPx,
                    heightPx
                ).also { chapterPageCache[prev.id] = it }
                computed.lastOrNull()
            }
        }
        // 下一章首页（排版量很小）：作为右侧跨章页，翻到它之后真正切章
        val nextHead = remember(nextChapterData?.id, widthPx, heightPx, textStyle) {
            nextChapterData?.let { next ->
                chapterHeadCache.getOrPut(next.id) {
                    paginateHead(
                        measurer,
                        readerChapterText(next, style, format),
                        textStyle,
                        widthPx,
                        heightPx,
                        lineHeightPx
                    )
                }
            }
        }
        // 仿真 / 覆盖模式用于翻页的页序列（可能包含前后章的跨章页）
        val flipPages = remember(pages, previousTail, nextHead) {
            buildList {
                previousTail?.let { add(it) }
                addAll(pages)
                nextHead?.let { add(it) }
            }
        }
        val pageBase = if (previousTail != null) 1 else 0

        // 切章落点：进入上一章时要停在章末，其余情况停在章首
        var enterAtEnd by remember { mutableStateOf(false) }

        // 排版变化前所在页的起始字符，用于重新分页后回到同一定位
        var anchorChar by remember(chapter?.id) {
            mutableIntStateOf(if (enterAtEnd) pages.lastOrNull()?.startChar ?: 0 else 0)
        }
        var simplePage by remember(chapter?.id) {
            mutableIntStateOf(if (enterAtEnd) pages.lastIndex.coerceAtLeast(0) else 0)
        }
        val pagerState = rememberPagerState(pageCount = { pages.size })

        // 仿真 / 覆盖翻页：手势直接驱动翻页进度，targetPage 为 -1 表示当前没有进行中的翻页
        var turnIndex by remember(chapter?.id) {
            mutableIntStateOf(if (enterAtEnd) pages.lastIndex.coerceAtLeast(0) else 0)
        }
        var targetPage by remember { mutableIntStateOf(-1) }
        var forward by remember { mutableStateOf(true) }
        var turnProgress by remember { mutableFloatStateOf(0f) }
        var turnJob by remember { mutableStateOf<Job?>(null) }

        /** 当前章页在 flipPages 中的下标 */
        fun flipIndex(): Int = (pageBase + turnIndex).coerceIn(0, flipPages.lastIndex)

        /** 翻页落点：跨章页触发切章，普通页直接更新页码 */
        fun commitFlip(destination: Int) {
            when {
                destination < pageBase -> {
                    enterAtEnd = true
                    onPrevChapter()
                }
                destination >= pageBase + pages.size -> {
                    enterAtEnd = false
                    onNextChapter()
                }
                else -> turnIndex = (destination - pageBase).coerceIn(0, pages.lastIndex)
            }
        }

        val toggleMenu by rememberUpdatedState(onToggleMenu)
        val prevChapter by rememberUpdatedState(onPrevChapter)
        val nextChapter by rememberUpdatedState(onNextChapter)
        val reportProgress by rememberUpdatedState(onProgress)

        /** 松手后把翻页进度补到 1（完成翻页）或退回 0（放弃翻页） */
        fun settleTurn(commit: Boolean) {
            val destination = targetPage
            if (destination < 0) return
            turnJob = pageScope.launch {
                try {
                    animate(
                        initialValue = turnProgress,
                        targetValue = if (commit) 1f else 0f,
                        animationSpec = tween(if (commit) TURN_ANIM_MS else TURN_CANCEL_MS)
                    ) { value, _ -> turnProgress = value }
                    if (commit) {
                        commitFlip(destination)
                    }
                } finally {
                    targetPage = -1
                    turnProgress = 0f
                }
            }
        }

        /** 触发一次完整翻页（点击 / 音量键）；越过本章首页 / 末页时切章（仿真模式由翻页组件处理） */
        fun turnPage(next: Boolean) {
            if (pageStyle == ReaderPageStyle.SIMULATION) return
            if (targetPage >= 0) return
            val destination = flipIndex() + if (next) 1 else -1
            if (destination !in flipPages.indices) {
                if (next) {
                    enterAtEnd = false
                    nextChapter()
                } else {
                    enterAtEnd = true
                    prevChapter()
                }
                return
            }
            forward = next
            targetPage = destination
            turnProgress = 0f
            settleTurn(commit = true)
        }

        // 换章后定位到该章首屏；从章首往回翻时定位到上一章末尾
        LaunchedEffect(chapter?.id) {
            val target = if (enterAtEnd) pages.lastIndex.coerceAtLeast(0) else 0
            anchorChar = pages.getOrNull(target)?.startChar ?: 0
            simplePage = target
            turnJob?.cancel()
            turnIndex = target
            targetPage = -1
            turnProgress = 0f
            if (pagerState.currentPage != target) pagerState.scrollToPage(target)
            // 落点已消费，后续再进入其他章节时恢复为章首
            enterAtEnd = false
        }

        // 字号 / 行距 / 边距等变化导致重新分页时，尽量回到原阅读位置
        LaunchedEffect(pages) {
            val target = pages.indexOfLast { it.startChar <= anchorChar }.coerceAtLeast(0)
            when {
                pageStyle == ReaderPageStyle.NONE -> simplePage = target
                pageStyle == ReaderPageStyle.SLIDE ->
                    if (pagerState.currentPage != target) pagerState.scrollToPage(target)
                else -> if (turnIndex != target) turnIndex = target
            }
        }

        if (pageStyle == ReaderPageStyle.NONE) {
            LaunchedEffect(simplePage, pages.size) {
                pages.getOrNull(simplePage)?.let { anchorChar = it.startChar }
                reportProgress(
                    if (pages.size <= 1) 1f else simplePage.toFloat() / (pages.size - 1)
                )
            }
        } else if (pageStyle == ReaderPageStyle.SLIDE) {
            LaunchedEffect(pagerState.currentPage, pages.size) {
                pages.getOrNull(pagerState.currentPage)?.let { anchorChar = it.startChar }
                reportProgress(
                    if (pages.size <= 1) 1f
                    else pagerState.currentPage.toFloat() / (pages.size - 1)
                )
            }
        } else {
            LaunchedEffect(turnIndex, pages.size) {
                pages.getOrNull(turnIndex)?.let { anchorChar = it.startChar }
                reportProgress(
                    if (pages.size <= 1) 1f else turnIndex.toFloat() / (pages.size - 1)
                )
            }
        }

        fun nextPage() {
            when (pageStyle) {
                ReaderPageStyle.NONE ->
                    if (simplePage < pages.lastIndex) simplePage++ else nextChapter()
                ReaderPageStyle.SLIDE ->
                    if (pagerState.currentPage < pages.lastIndex) {
                        pageScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    } else {
                        nextChapter()
                    }
                else -> turnPage(next = true)
            }
        }

        fun prevPage() {
            when (pageStyle) {
                ReaderPageStyle.NONE ->
                    if (simplePage > 0) simplePage-- else prevChapter()
                ReaderPageStyle.SLIDE ->
                    if (pagerState.currentPage > 0) {
                        pageScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    } else {
                        prevChapter()
                    }
                else -> turnPage(next = false)
            }
        }

        // 音量键翻页（方向与系统音量键一致：加键下一页、减键上一页）
        LaunchedEffect(navTick) {
            if (navTick <= 0) return@LaunchedEffect
            // 仿真翻页的动画由翻页组件内部驱动（CurlFlipReader 会处理音量键）
            if (pageStyle == ReaderPageStyle.SIMULATION) return@LaunchedEffect
            if (navDir > 0) nextPage() else prevPage()
        }

        /** 单页内容：纸张背景 + 正文；仿真与覆盖翻页共用同一份页面 */
        @Composable
        fun pageSurface(index: Int) {
            Box(Modifier.fillMaxSize()) {
                // 纸张背景随页面一起翻动，否则只有文字在动
                ReaderBackgroundLayer(style, reader)
                Box(Modifier.fillMaxSize().padding(pagePadding)) {
                    Text(
                        text = flipPages.getOrNull(index)?.text ?: AnnotatedString(""),
                        style = textStyle
                    )
                }
            }
        }

        if (pageStyle == ReaderPageStyle.NONE) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(pages.size) {
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { acc = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                acc += dragAmount
                            },
                            onDragEnd = {
                                val threshold = CHAPTER_SWIPE_DP.dp.toPx()
                                if (abs(acc) > threshold) {
                                    if (acc < 0f) nextPage() else prevPage()
                                }
                            }
                        )
                    }
                    .pointerInput(pages.size) {
                        detectTapGestures { offset ->
                            val third = size.width / 3f
                            when {
                                offset.x < third -> prevPage()
                                offset.x > third * 2 -> nextPage()
                                else -> toggleMenu()
                            }
                        }
                    }
            ) {
                Box(Modifier.fillMaxSize().padding(pagePadding)) {
                    Text(
                        text = pages.getOrNull(simplePage)?.text ?: AnnotatedString(""),
                        style = textStyle
                    )
                }
            }
        } else if (pageStyle == ReaderPageStyle.SLIDE) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    // 纵向越界切章：末页继续上滑进下一章、首页继续下滑回上一章
                    .pointerInput(Unit) {
                        val threshold = CHAPTER_SWIPE_PX
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
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    dx += change.positionChange().x
                                    dy += change.positionChange().y
                                    pressed = change.pressed
                                }
                                if (abs(dy) > threshold && abs(dy) > abs(dx) * 1.5f) {
                                    if (dy < 0f && startPage >= lastPage) nextChapter()
                                    else if (dy > 0f && startPage <= 0) prevChapter()
                                }
                            }
                        }
                    }
            ) { page ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val third = size.height / 3f
                                when {
                                    offset.y < third -> prevPage()
                                    offset.y > third * 2 -> nextPage()
                                    else -> toggleMenu()
                                }
                            }
                        }
                ) {
                    Box(Modifier.fillMaxSize().padding(pagePadding)) {
                        Text(
                            text = pages.getOrNull(page)?.text ?: AnnotatedString(""),
                            style = textStyle
                        )
                    }
                }
            }
        } else if (pageStyle == ReaderPageStyle.SIMULATION) {
            // 仿真翻页交给 PTQFlipper 的纸张卷曲组件：拖动跟手，带网格扭曲、纸背与阴影
            CurlFlipReader(
                pages = flipPages,
                currentPage = flipIndex(),
                pageColor = Color(style.backgroundColor),
                navTick = navTick,
                navDir = navDir,
                onPageChange = { display -> commitFlip(display) },
                onPrevChapter = {
                    enterAtEnd = true
                    prevChapter()
                },
                onNextChapter = {
                    enterAtEnd = false
                    nextChapter()
                },
                onToggleMenu = { toggleMenu() },
                pageContent = { index -> pageSurface(index) }
            )
        } else {
            // 覆盖：旧页不动，新页连同背景从右侧（向后翻时从左侧）滑入
            val pageWidthPx = with(localDensity) { maxWidth.toPx() }
            val animating = targetPage >= 0

            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(flipPages.size) {
                        var acc = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { acc = 0f },
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                acc += delta
                                if (targetPage < 0) {
                                    // 拖动一小段后锁定方向与目标页，随后进度直接跟手
                                    if (abs(acc) > 3f) {
                                        val next = acc < 0f
                                        val destination = flipIndex() + if (next) 1 else -1
                                        if (destination in flipPages.indices) {
                                            forward = next
                                            targetPage = destination
                                        }
                                    }
                                } else {
                                    // 朝翻页方向的位移映射成进度；拖回起点则退回到 0
                                    val signed = if (forward) -acc else acc
                                    turnProgress =
                                        (signed / size.width.toFloat().coerceAtLeast(1f))
                                            .coerceIn(0f, 1f)
                                }
                            },
                            onDragEnd = {
                                when {
                                    targetPage >= 0 -> settleTurn(commit = turnProgress > 0.25f)
                                    // 本章首 / 末页：拖动距离够就直接切章
                                    abs(acc) > TURN_FLICK_PX ->
                                        if (acc < 0f) {
                                            enterAtEnd = false
                                            nextChapter()
                                        } else {
                                            enterAtEnd = true
                                            prevChapter()
                                        }
                                }
                                acc = 0f
                            },
                            onDragCancel = {
                                if (targetPage >= 0) settleTurn(commit = false)
                                acc = 0f
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val third = size.width / 3f
                            when {
                                offset.x < third -> prevPage()
                                offset.x > third * 2 -> nextPage()
                                else -> toggleMenu()
                            }
                        }
                    }
            ) {
                // 旧页留在底部不动，新页从右侧（向后翻时从左侧）滑入
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                ) {
                    pageSurface(flipIndex())
                }
                if (animating) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .zIndex(1f)
                            .graphicsLayer {
                                // 从右侧（向后翻时从左侧）滑入，位移在图层里算，拖动时不触发重组
                                val direction = if (forward) 1f else -1f
                                translationX = direction * pageWidthPx * (1f - turnProgress)
                                shadowElevation = 10.dp.toPx()
                                shape = RectangleShape
                            }
                    ) {
                        pageSurface(targetPage)
                    }
                }
            }
        }
    }
}

/**
 * 仿真翻页容器：内部使用移植自 PTQFlipper（MIT，https://github.com/FantasticPornTaiQiang/PTQFlipper）
 * 的 PTQBookPageView，由它负责纸张卷曲、网格扭曲、纸背与阴影的绘制。
 *
 * - [currentPage] 为外部受控页码（可能落在前后章的跨章页上），翻页完成后通过 [onPageChange] 交回新页码
 * - 本章首页 / 末页继续翻时交给 [onPrevChapter] / [onNextChapter] 切章
 * - 左右三分之一交给翻页器翻页，中间三分之一用于呼出菜单
 * - 音量键通过 [navTick] / [navDir] 触发，复用组件内部的翻页动画
 */
@Composable
private fun CurlFlipReader(
    pages: List<ReaderPage>,
    currentPage: Int,
    pageColor: Color,
    navTick: Int,
    navDir: Int,
    onPageChange: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onToggleMenu: () -> Unit,
    pageContent: @Composable (Int) -> Unit
) {
    val pageCount = pages.size.coerceAtLeast(1)
    val safePage = currentPage.coerceIn(0, pageCount - 1)
    // 页码 / 页数直接派生：换章后与新页面列表在同一帧生效，
    // 若用 LaunchedEffect 延迟写入，会先按旧页码渲染一帧旧内容，表现为翻页闪一下
    val viewState = remember(pageCount, safePage) {
        PTQBookPageViewState(pageCount, safePage)
    }
    val config by rememberPTQBookPageViewConfig(pageColor = pageColor)
    // 程序化翻页（音量键）与位图刷新入口
    val turnRequest = remember { PTQBookPageViewRequest() }
    val latestPageCount by rememberUpdatedState(pageCount)
    val latestOnPageChange by rememberUpdatedState(onPageChange)
    val latestPrevChapter by rememberUpdatedState(onPrevChapter)
    val latestNextChapter by rememberUpdatedState(onNextChapter)
    val latestToggleMenu by rememberUpdatedState(onToggleMenu)

    // 重新分页 / 换章后页面位图需要重拍；不重建组件，避免跨章翻页时闪一下
    LaunchedEffect(pages) {
        turnRequest.refresh()
    }

    // 音量键翻页：复用组件内部的翻页动画
    var handledNavTick by remember { mutableIntStateOf(navTick) }
    LaunchedEffect(navTick) {
        if (navTick == handledNavTick) return@LaunchedEffect
        handledNavTick = navTick
        if (navDir > 0) turnRequest.next() else turnRequest.previous()
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        val start = down.position
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if ((change.position - start).getDistance() > slop) moved = true
                            if (!change.pressed) break
                        }
                        val third = size.width / 3f
                        if (!moved && start.x >= third && start.x <= third * 2f) {
                            latestToggleMenu()
                        }
                    }
                }
            }
    ) {
        PTQBookPageView(
            modifier = Modifier.fillMaxSize(),
            config = config,
            state = viewState,
            request = turnRequest
        ) {
            onTurnPageRequest { page, next, success ->
                if (success) {
                    val destination = page + if (next) 1 else -1
                    latestOnPageChange(destination.coerceIn(0, latestPageCount - 1))
                } else if (next) {
                    latestNextChapter()
                } else {
                    latestPrevChapter()
                }
            }
            tapBehavior { _, rightDown, touch ->
                when {
                    touch.x < rightDown.x / 3f -> false
                    touch.x > rightDown.x * 2f / 3f -> true
                    else -> null
                }
            }
            contents { page, refresh ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(pageColor)
                ) {
                    pageContent(page)
                }
                refresh()
            }
        }
    }
}

/** 把整章文本按可视高度切成若干页，切分点落在行边界上，并记录每页起始偏移 */
private fun paginate(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int
): List<ReaderPage> {
    if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return listOf(ReaderPage(0, text))
    val layout = measurer.measure(
        text = text,
        style = style,
        constraints = Constraints(maxWidth = widthPx)
    )
    if (layout.lineCount == 0) return listOf(ReaderPage(0, text))
    val limit = heightPx.toFloat()
    val pages = mutableListOf<ReaderPage>()
    var line = 0
    while (line < layout.lineCount) {
        val top = layout.getLineTop(line)
        var end = line
        while (end < layout.lineCount && layout.getLineBottom(end) - top <= limit) end++
        if (end == line) end = line + 1
        val startChar = layout.getLineStart(line)
        val endChar = if (end >= layout.lineCount) text.length else layout.getLineStart(end)
        pages.add(ReaderPage(startChar, text.subSequence(startChar, endChar)))
        line = end
    }
    return pages.ifEmpty { listOf(ReaderPage(0, text)) }
}

/**
 * 只排版下一页的首页：跨章翻页动画需要把它作为右侧跨章页，
 * 通过限制 maxLines 把排版量压在一两页内，避免为动画排版整章。
 */
private fun paginateHead(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    widthPx: Int,
    heightPx: Int,
    lineHeightPx: Float
): ReaderPage {
    if (text.isEmpty() || widthPx <= 0 || heightPx <= 0) return ReaderPage(0, text)
    val maxLines = if (lineHeightPx > 0f) {
        ((heightPx / lineHeightPx).toInt() * 2 + 4).coerceAtLeast(8)
    } else {
        40
    }
    val layout = measurer.measure(
        text = text,
        style = style,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = widthPx)
    )
    if (layout.lineCount == 0) return ReaderPage(0, text)
    val limit = heightPx.toFloat()
    val top = layout.getLineTop(0)
    var end = 0
    while (end < layout.lineCount && layout.getLineBottom(end) - top <= limit) end++
    if (end == 0) end = 1
    val endChar = if (end >= layout.lineCount) text.length else layout.getLineStart(end)
    return ReaderPage(0, text.subSequence(0, endChar))
}

/**
 * 组装一章的正文：标题不缩进，正文每个自然段通过 ParagraphStyle 的首行缩进真正生效，
 * 而不是在整篇开头塞空格。
 */
private fun readerChapterText(
    chapter: Chapter?,
    style: StyleSettings,
    format: FormatSettings
): AnnotatedString = buildAnnotatedString {
    val title = chapter?.title.orEmpty()
    if (title.isNotBlank()) {
        pushStyle(
            SpanStyle(
                fontSize = (style.fontSizeSp + 4).sp,
                fontWeight = FontWeight.Bold
            )
        )
        append(title)
        pop()
        append("\n\n")
    }
    val body = applyParagraphSpacing(
        chapter?.content.orEmpty().ifBlank { "（本章暂无正文）" },
        format.blankLines
    )
    val bodyStart = length
    append(body)
    val indent = format.indent.coerceAtLeast(0)
    if (indent > 0 && bodyStart < length) {
        addStyle(
            ParagraphStyle(
                textIndent = TextIndent(firstLine = (style.fontSizeSp * indent).sp)
            ),
            bodyStart,
            length
        )
    }
}

private fun readerTextStyle(style: StyleSettings, format: FormatSettings): TextStyle = TextStyle(
    fontSize = style.fontSizeSp.sp,
    fontFamily = style.fontFamily,
    fontWeight = TextTool.weightOf(style.bold, style.thin),
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
