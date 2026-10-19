package com.lianwenqi.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ai.RetrievalMode
import com.lianwenqi.app.data.AiChapterImporter
import com.lianwenqi.app.data.AiOutlineImporter
import com.lianwenqi.app.data.GEN_CONTENT_WORDS_MAX
import com.lianwenqi.app.data.GEN_SUMMARY_WORDS_MAX
import com.lianwenqi.app.data.SortMode
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.ui.components.BookFormResult
import com.lianwenqi.app.util.AI_CHAPTER_TOOL_PROMPT
import com.lianwenqi.app.util.AI_OUTLINE_TOOL_PROMPT
import com.lianwenqi.app.util.AI_TOOLS
import com.lianwenqi.app.util.AI_TOOL_IMPORT_CHAPTER
import com.lianwenqi.app.util.AI_TOOL_WRITE_OUTLINE
import com.lianwenqi.app.util.FileUtil
import com.lianwenqi.app.util.parseAiChapterBlocks
import com.lianwenqi.app.util.parseAiOutlineBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

class AiToolsViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = container.prefs
    private val bookRepository = container.bookRepository
    private val importer = AiOutlineImporter(container.prefs, container.outlineRepository)
    private val chapterImporter = AiChapterImporter(container.bookRepository)

    val books: StateFlow<List<Book>> =
        bookRepository.observeBooks(SortMode.TIME, false)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 书籍分组（选书弹窗的分组筛选用） */
    val groups: StateFlow<List<BookGroup>> =
        bookRepository.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outlineToolEnabled: StateFlow<Boolean> =
        prefs.aiToolOutlineEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val chapterToolEnabled: StateFlow<Boolean> =
        prefs.aiToolChapterEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setOutlineToolEnabled(v: Boolean) = viewModelScope.launch { prefs.setAiToolOutlineEnabled(v) }

    fun setChapterToolEnabled(v: Boolean) = viewModelScope.launch { prefs.setAiToolChapterEnabled(v) }

    // 「生成梗概 / 生成正文」配置（AI 工具页的「生成设置」子页读写）
    val genSummaryWordRange: StateFlow<IntRange> =
        prefs.genSummaryWordRange.stateIn(viewModelScope, SharingStarted.Eagerly, 0..GEN_SUMMARY_WORDS_MAX)

    val genSummaryLimitScope: StateFlow<Boolean> =
        prefs.genSummaryLimitScope.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val genContentWordRange: StateFlow<IntRange> =
        prefs.genContentWordRange.stateIn(viewModelScope, SharingStarted.Eagerly, 0..GEN_CONTENT_WORDS_MAX)

    val genContentMemoryMode: StateFlow<RetrievalMode> =
        prefs.genContentMemoryMode.stateIn(viewModelScope, SharingStarted.Eagerly, RetrievalMode.ASK)

    val genContentWithSummary: StateFlow<Boolean> =
        prefs.genContentWithSummary.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val genCarryHistory: StateFlow<Boolean> =
        prefs.genCarryHistory.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setGenSummaryWordRange(min: Int, max: Int) =
        viewModelScope.launch { prefs.setGenSummaryWordRange(min, max) }

    fun setGenSummaryLimitScope(v: Boolean) = viewModelScope.launch { prefs.setGenSummaryLimitScope(v) }

    fun setGenContentWordRange(min: Int, max: Int) =
        viewModelScope.launch { prefs.setGenContentWordRange(min, max) }

    fun setGenContentMemoryMode(v: RetrievalMode) = viewModelScope.launch { prefs.setGenContentMemoryMode(v) }

    fun setGenContentWithSummary(v: Boolean) = viewModelScope.launch { prefs.setGenContentWithSummary(v) }

    fun setGenCarryHistory(v: Boolean) = viewModelScope.launch { prefs.setGenCarryHistory(v) }

    /** 完整表单新建书籍，返回新书 id */
    suspend fun createBookFull(r: BookFormResult): Long = bookRepository.createBook(
        title = r.title.trim(),
        intro = r.intro.trim(),
        coverPath = r.coverPath,
        coverColor = r.coverColor,
        volumePrefix = r.volumePrefix,
        chapterPrefix = r.chapterPrefix,
        groupId = r.groupId
    )

    /** 复制封面图到应用私有目录，返回本地路径 */
    suspend fun pickCover(uri: android.net.Uri): String? =
        withContext(Dispatchers.IO) { FileUtil.copyToInternal(container.context, uri) }

    /** 新建分组并返回新分组 id（书籍表单内现场建组用） */
    suspend fun createGroupAndGet(name: String): Long = bookRepository.createGroup(name)

    /** 把文本中的大纲区块导入指定书籍；返回 null 表示文本里没有可解析的内容 */
    suspend fun importOutline(bookId: Long, text: String): String? {
        val blocks = parseAiOutlineBlocks(text)
        if (blocks.isEmpty()) return null
        val result = importer.import(bookId, blocks)
        return buildString {
            append("已写入 ${result.items} 条大纲")
            if (result.newCategories.isNotEmpty()) {
                append("，新建标签：${result.newCategories.joinToString("、")}")
            }
        }
    }

    /** 把文本中的章节梗概区块导入指定书籍；返回 null 表示文本里没有可解析的内容 */
    suspend fun importChapters(bookId: Long, text: String): String? {
        val blocks = parseAiChapterBlocks(text)
        if (blocks.isEmpty()) return null
        val result = chapterImporter.import(bookId, blocks)
        return buildString {
            append("已导入章节梗概：新建 ${result.created} 章")
            if (result.updated > 0) append("，更新 ${result.updated} 章")
            if (result.newVolumes.isNotEmpty()) {
                append("，新建分卷：${result.newVolumes.joinToString("、")}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiToolsScreen(onBack: () -> Unit) {
    val vm = appViewModel { AiToolsViewModel(it) }
    val outlineEnabled by vm.outlineToolEnabled.collectAsState()
    val chapterEnabled by vm.chapterToolEnabled.collectAsState()
    // 当前展开的工具 id 集合（默认全部收起，点标题才展开）
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
    var showImport by remember { mutableStateOf(false) }
    var showChapterImport by remember { mutableStateOf(false) }
    var showGenSettings by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 系统返回键：停在「生成设置」子页面时，先回到 AI 工具页，而不是直接退出到设置页
    BackHandler(enabled = showGenSettings) { showGenSettings = false }

    // 「生成设置」子页面：就地替换本页内容，返回时回到本页
    if (showGenSettings) {
        AiGenerateSettingsScreen(vm, onBack = { showGenSettings = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 工具") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            // 写入大纲工具（以后新增工具按同样结构追加）
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedIds = if (AI_TOOL_WRITE_OUTLINE in expandedIds)
                                    expandedIds - AI_TOOL_WRITE_OUTLINE else expandedIds + AI_TOOL_WRITE_OUTLINE
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("写入大纲工具", fontWeight = FontWeight.SemiBold)
                            Text(
                                "AI 生成的人设 / 世界观等可按固定格式解析并写入作品大纲",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (AI_TOOL_WRITE_OUTLINE in expandedIds) Icons.Filled.KeyboardArrowUp
                            else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                    if (AI_TOOL_WRITE_OUTLINE in expandedIds) {
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("启用该工具")
                                Text(
                                    "启用后可在 AI 聊天页勾选，AI 回答后可一键写入大纲",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = outlineEnabled, onCheckedChange = { vm.setOutlineToolEnabled(it) })
                        }
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString(AI_OUTLINE_TOOL_PROMPT))
                                    Toast.makeText(context, "工具提示词已复制", Toast.LENGTH_SHORT).show()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("复制工具提示词", Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showImport = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("导入大纲")
                                Text(
                                    "粘贴含 <<<AI_OUTLINE_JSON_V1>>> 标记的文本，解析后写入作品大纲",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 章节梗概导入工具
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedIds = if (AI_TOOL_IMPORT_CHAPTER in expandedIds)
                                    expandedIds - AI_TOOL_IMPORT_CHAPTER else expandedIds + AI_TOOL_IMPORT_CHAPTER
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("章节梗概导入工具", fontWeight = FontWeight.SemiBold)
                            Text(
                                "AI 生成的章节名 / 梗概可按固定格式解析，批量写入书籍分卷与章节",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            if (AI_TOOL_IMPORT_CHAPTER in expandedIds) Icons.Filled.KeyboardArrowUp
                            else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null
                        )
                    }
                    if (AI_TOOL_IMPORT_CHAPTER in expandedIds) {
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("启用该工具")
                                Text(
                                    "启用后可在 AI 聊天页勾选，AI 回答后可一键导入章节梗概",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = chapterEnabled, onCheckedChange = { vm.setChapterToolEnabled(it) })
                        }
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString(AI_CHAPTER_TOOL_PROMPT))
                                    Toast.makeText(context, "工具提示词已复制", Toast.LENGTH_SHORT).show()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("复制工具提示词", Modifier.weight(1f))
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { showChapterImport = true }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("导入章节梗概")
                                Text(
                                    "粘贴含 <<<AI_CHAPTER_JSON_V1>>> 标记的文本，解析后写入分卷与章节",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 生成设置：生成梗概 / 生成正文的字数、范围、记忆检索与同时生成梗概
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showGenSettings = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("生成设置", fontWeight = FontWeight.SemiBold)
                        Text(
                            "生成梗概 / 生成正文的字数范围、限定范围、记忆检索与同时生成梗概",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showImport) {
        AiOutlineImportDialog(
            vm = vm,
            onDismiss = { showImport = false }
        )
    }

    if (showChapterImport) {
        AiChapterImportDialog(
            vm = vm,
            onDismiss = { showChapterImport = false }
        )
    }
}

// ── 生成设置页配色（对齐设计稿：奶油底 + 淡紫点缀） ──────────────────────────
private val GenBg = Color(0xFFFAF8F5)
private val GenCardBg = Color(0xFFFFFEFD)
private val GenInk = Color(0xFF29275F)
private val GenMuted = Color(0xFF8587B0)
private val GenPurple = Color(0xFF6259DC)
private val GenPurpleLight = Color(0xFF9189F0)
private val GenBadgeBg = Color(0xFFF0EDFF)
private val GenSoftBg = Color(0xFFF8F6FF)
private val GenPillBg = Color(0xFFF5F2FF)
private val GenPillText = Color(0xFF55558A)
private val GenSelectedBg = Color(0xFFF6F3FF)
private val GenTrackIdle = Color(0xFFE8E4FF)
private val GenCardBorder = Color(0xFFF1EDF1)

/**
 * 生成设置：生成梗概 / 生成正文的字数范围、限定范围、记忆检索与同时生成梗概。
 * 版式对齐设计稿：奶油底 + 大圆角卡片 + 圆形淡紫图标徽标 + 淡紫滑块与数值药丸。
 *
 * 说明：请求的 max_tokens 由模型自身配置决定，这里的字数只写进提示词做硬约束；
 * 超出上限时应用侧会截断到最后一个完整句子并提示，低于下限只提示、不自动重试（避免额外 token）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiGenerateSettingsScreen(vm: AiToolsViewModel, onBack: () -> Unit) {
    val summaryRange by vm.genSummaryWordRange.collectAsState()
    val limitScope by vm.genSummaryLimitScope.collectAsState()
    val contentRange by vm.genContentWordRange.collectAsState()
    val memoryMode by vm.genContentMemoryMode.collectAsState()
    val withSummary by vm.genContentWithSummary.collectAsState()
    val carryHistory by vm.genCarryHistory.collectAsState()

    Scaffold(
        containerColor = GenBg,
        topBar = {
            TopAppBar(
                title = { Text("生成设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GenBg)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // 生成梗概：字数区间 + 限定梗概范围
            GenCard {
                GenSectionHead(SummarizeIcon, "生成梗概")
                GenDesc("限定字数（0–$GEN_SUMMARY_WORDS_MAX）：生成的梗概要落在这个区间内；下限拉到 0 表示不约束下限。")
                WordRangeSlider(summaryRange, GEN_SUMMARY_WORDS_MAX) { min, max ->
                    vm.setGenSummaryWordRange(min, max)
                }
                GenSubSetting(
                    icon = Icons.AutoMirrored.Filled.Article,
                    title = "限定梗概范围",
                    desc = "开启后点「生成梗概」会先让你按大纲条目多选，只按选中的设定生成；" +
                        "已在本会话上下文中的标签只写「参见大纲」，不重复提交内容",
                    checked = limitScope,
                    onCheckedChange = { vm.setGenSummaryLimitScope(it) }
                )
            }

            // 生成正文：字数区间 + 记忆检索
            GenCard {
                GenSectionHead(Icons.AutoMirrored.Filled.Article, "生成正文")
                GenDesc("限定字数（0–$GEN_CONTENT_WORDS_MAX）：生成的正文要落在这个区间内；下限拉到 0 表示不约束下限。")
                WordRangeSlider(contentRange, GEN_CONTENT_WORDS_MAX) { min, max ->
                    vm.setGenContentWordRange(min, max)
                }

                Spacer(Modifier.height(10.dp))
                GenSectionHead(Icons.Filled.Search, "记忆检索")
                GenDesc("生成正文前是否按本章梗概检索本书已写正文，命中片段作为参考资料一并生成（检索段数沿用长期记忆里的区块设置）。")
                genMemoryOptions.forEach { option ->
                    GenRadioOption(
                        label = option.label,
                        hint = option.hint,
                        selected = memoryMode == option.value,
                        onClick = { vm.setGenContentMemoryMode(option.value) }
                    )
                }
            }

            // 生成正文的同时生成梗概
            GenToggleCard(
                icon = Icons.AutoMirrored.Filled.Article,
                title = "生成正文的同时生成梗概",
                desc = "开启后点「生成正文」会先按生成梗概的配置生成梗概并静默写入本章梗概，再据此生成正文；" +
                    "关闭时若本章没有梗概会提示先填写",
                checked = withSummary,
                onCheckedChange = { vm.setGenContentWithSummary(it) }
            )

            // 生成工具携带本会话历史消息
            GenToggleCard(
                icon = Icons.Filled.History,
                title = "生成工具携带本会话历史消息",
                desc = "开启后「生成梗概 / 生成正文」的请求会带上本会话的历史对话，生成内容会受历史对话影响，" +
                    "但前缀缓存命中随轮次累积；关闭则只复用与聊天一致的前置块，命中长度恒定",
                checked = carryHistory,
                onCheckedChange = { vm.setGenCarryHistory(it) }
            )
        }
    }
}

/** 生成设置页的圆角卡片容器（白底 + 细描边 + 大圆角） */
@Composable
private fun GenCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .background(GenCardBg, RoundedCornerShape(22.dp))
            .border(1.dp, GenCardBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        content = content
    )
}

/** 圆形淡紫图标徽标 + 小节标题 */
@Composable
private fun GenSectionHead(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .background(GenBadgeBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = GenPurple, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(title, color = GenInk, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

/** 小节说明文字 */
@Composable
private fun GenDesc(text: String) {
    Text(
        text,
        color = GenMuted,
        style = MaterialTheme.typography.bodySmall,
        lineHeight = 20.sp,
        modifier = Modifier.padding(top = 10.dp, bottom = 16.dp)
    )
}

/** 淡紫底的子设置块：小徽标 + 标题/说明 + 开关 */
@Composable
private fun GenSubSetting(
    icon: ImageVector,
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(GenSoftBg, RoundedCornerShape(17.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(36.dp)
                .background(GenBadgeBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = GenPurple, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = GenInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(desc, color = GenMuted, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 记忆检索的单选项：选中的整块淡紫底 */
@Composable
private fun GenRadioOption(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) GenSelectedBg else Color.Transparent, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = GenInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(hint, color = GenMuted, fontSize = 12.sp, lineHeight = 19.sp)
        }
    }
}

/** 整块可切换的开关卡：徽标 + 标题/说明 + 开关 */
@Composable
private fun GenToggleCard(
    icon: ImageVector,
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .background(GenCardBg, RoundedCornerShape(22.dp))
            .border(1.dp, GenCardBorder, RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(44.dp)
                .background(GenBadgeBg, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = GenPurple, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = GenInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(desc, color = GenMuted, style = MaterialTheme.typography.bodySmall, lineHeight = 20.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 字数区间滑条：左侧当前下限 + 淡紫双滑块 + 右侧区间药丸 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordRangeSlider(range: IntRange, cap: Int, onChange: (Int, Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${range.first}",
            color = GenPillText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp)
        )
        RangeSlider(
            value = range.first.toFloat()..range.last.toFloat(),
            onValueChange = { onChange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
            valueRange = 0f..cap.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = GenPurple,
                activeTrackColor = GenPurpleLight,
                inactiveTrackColor = GenTrackIdle,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "${range.first}–${range.last} 字",
            color = GenPillText,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .background(GenPillBg, RoundedCornerShape(22.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}

private data class GenMemoryOption(val value: RetrievalMode, val label: String, val hint: String)

private val genMemoryOptions = listOf(
    GenMemoryOption(RetrievalMode.ON, "开启", "每次生成正文前自动按本章梗概检索本书正文片段并注入参考资料"),
    GenMemoryOption(RetrievalMode.OFF, "关闭", "不检索，只用本章梗概与本会话前缀生成"),
    GenMemoryOption(RetrievalMode.ASK, "询问", "每次生成正文前弹窗询问是否启用（默认）")
)

/** 导入大纲弹窗：选书（可现场新建）+ 粘贴文本 + 解析导入 */
@Composable
private fun AiOutlineImportDialog(vm: AiToolsViewModel, onDismiss: () -> Unit) {
    val books by vm.books.collectAsState()
    val groups by vm.groups.collectAsState()
    var text by remember { mutableStateOf("") }
    var targetBookId by remember { mutableStateOf<Long?>(null) }
    var showBookPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val blockCount = remember(text) { parseAiOutlineBlocks(text).size }
    val targetBook = books.firstOrNull { it.id == targetBookId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入大纲") },
        text = {
            // 文本框固定高度并内部滚动，避免长文本时输入框顶到键盘下方遮住光标
            Column {
                OutlinedButton(onClick = { showBookPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        targetBook?.let { "写入到：《${it.title}》" } ?: "选择要写入的书籍",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("粘贴包含大纲标记的文本") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (blockCount > 0) "已识别 $blockCount 个大纲区块" else "未识别到大纲区块",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (blockCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !importing && targetBookId != null && blockCount > 0,
                onClick = {
                    val bookId = targetBookId ?: return@Button
                    importing = true
                    scope.launch {
                        val msg = vm.importOutline(bookId, text)
                        importing = false
                        if (msg == null) {
                            error = "没有解析到可导入的大纲内容"
                        } else {
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                    }
                }
            ) { Text(if (importing) "导入中…" else "导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showBookPicker) {
        BookPickerDialog(
            books = books,
            groups = groups,
            selectedId = targetBookId,
            onSelect = { targetBookId = it },
            onCreateBook = { vm.createBookFull(it) },
            onPickCover = { uri, cb -> scope.launch { cb(vm.pickCover(uri)) } },
            onCreateGroup = { vm.createGroupAndGet(it) },
            onDismiss = { showBookPicker = false }
        )
    }
}

/** 导入章节梗概弹窗：选书（可现场新建）+ 粘贴文本 + 解析导入 */
@Composable
private fun AiChapterImportDialog(vm: AiToolsViewModel, onDismiss: () -> Unit) {
    val books by vm.books.collectAsState()
    val groups by vm.groups.collectAsState()
    var text by remember { mutableStateOf("") }
    var targetBookId by remember { mutableStateOf<Long?>(null) }
    var showBookPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val blockCount = remember(text) { parseAiChapterBlocks(text).size }
    val targetBook = books.firstOrNull { it.id == targetBookId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入章节梗概") },
        text = {
            // 文本框固定高度并内部滚动，避免长文本时输入框顶到键盘下方遮住光标
            Column {
                OutlinedButton(onClick = { showBookPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        targetBook?.let { "写入到：《${it.title}》" } ?: "选择要写入的书籍",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("粘贴包含章节梗概标记的文本") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (blockCount > 0) "已识别 $blockCount 个章节区块" else "未识别到章节区块",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (blockCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !importing && targetBookId != null && blockCount > 0,
                onClick = {
                    val bookId = targetBookId ?: return@Button
                    importing = true
                    scope.launch {
                        val msg = vm.importChapters(bookId, text)
                        importing = false
                        if (msg == null) {
                            error = "没有解析到可导入的章节梗概内容"
                        } else {
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            onDismiss()
                        }
                    }
                }
            ) { Text(if (importing) "导入中…" else "导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showBookPicker) {
        BookPickerDialog(
            books = books,
            groups = groups,
            selectedId = targetBookId,
            onSelect = { targetBookId = it },
            onCreateBook = { vm.createBookFull(it) },
            onPickCover = { uri, cb -> scope.launch { cb(vm.pickCover(uri)) } },
            onCreateGroup = { vm.createGroupAndGet(it) },
            onDismiss = { showBookPicker = false }
        )
    }
}
