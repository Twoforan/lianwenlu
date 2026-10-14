package com.lianwenqi.app.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.TxtBookImporter
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.ui.components.CoverColors
import com.lianwenqi.app.util.ParsedTxt
import com.lianwenqi.app.util.TextTool
import com.lianwenqi.app.util.TxtImport
import com.lianwenqi.app.util.TxtParseMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导入流程状态 */
sealed interface TxtImportState {
    /** 尚未选择文件 */
    data object Idle : TxtImportState
    /** 正在读取文件 */
    data object Reading : TxtImportState
    /** 读取失败（文件不存在 / 无权限） */
    data class Error(val message: String) : TxtImportState
    /** 解析完成，等待确认 */
    data class Preview(val parsed: ParsedTxt) : TxtImportState
    /** 正在写库 */
    data object Importing : TxtImportState
    /** 导入完成 */
    data class Done(val bookId: Long, val chapterCount: Int) : TxtImportState
}

class TxtImportViewModel(private val container: AppContainer) : ViewModel() {

    private val bookRepository = container.bookRepository
    private val importer = TxtBookImporter(container.bookRepository)

    val groups: StateFlow<List<BookGroup>> =
        bookRepository.observeGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val state = MutableStateFlow<TxtImportState>(TxtImportState.Idle)
    val mode = MutableStateFlow(TxtParseMode.AUTO)
    val message = MutableStateFlow<String?>(null)

    /** 原始字节与文件名缓存，切换识别模式时无需重新读文件 */
    private var rawBytes: ByteArray? = null
    private var fileName: String = "导入文本.txt"

    /** 书名（默认取自文件名，可编辑） */
    val bookTitle = MutableStateFlow("")
    val bookIntro = MutableStateFlow("")
    val coverColor = MutableStateFlow(CoverColors.first())

    fun clearMessage() {
        message.value = null
    }

    /** 读取并解析所选文件 */
    fun load(uri: Uri, context: Context) = viewModelScope.launch {
        state.value = TxtImportState.Reading
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取所选文件")
                val name = queryDisplayName(context, uri) ?: "导入文本.txt"
                bytes to name
            }
        }
        result.fold(
            onSuccess = { (bytes, name) ->
                rawBytes = bytes
                fileName = name
                bookTitle.value = name.substringBeforeLast('.').trim().ifEmpty { "导入文本" }
                bookIntro.value = ""
                mode.value = TxtParseMode.AUTO
                reparse()
            },
            onFailure = {
                state.value = TxtImportState.Error(it.message ?: "读取失败")
            }
        )
    }

    /** 按当前识别模式重新解析（切换模式 / 首次加载共用） */
    fun reparse() {
        val bytes = rawBytes ?: return
        val current = mode.value
        viewModelScope.launch(Dispatchers.Default) {
            val text = withContext(Dispatchers.IO) { TxtImport.decodeText(bytes) }
            val parsed = TxtImport.parse(text, fileName, current)
            state.value = TxtImportState.Preview(parsed)
        }
    }

    fun setMode(newMode: TxtParseMode) {
        if (newMode == mode.value) return
        mode.value = newMode
        reparse()
    }

    fun pickAnother() {
        rawBytes = null
        state.value = TxtImportState.Idle
    }

    /** 确认导入：写库并进入完成态 */
    fun confirmImport(groupId: Long?) = viewModelScope.launch {
        val parsed = (state.value as? TxtImportState.Preview)?.parsed ?: return@launch
        if (parsed.chapterCount == 0) {
            message.value = "没有识别到任何章节，请更换识别模式"
            return@launch
        }
        state.value = TxtImportState.Importing
        val result = runCatching {
            importer.import(
                parsed = parsed.copy(suggestedTitle = bookTitle.value.trim().ifEmpty { parsed.suggestedTitle }),
                intro = bookIntro.value.trim(),
                coverColor = coverColor.value,
                groupId = groupId
            )
        }
        result.fold(
            onSuccess = { state.value = TxtImportState.Done(it.bookId, it.chapterCount) },
            onFailure = {
                state.value = TxtImportState.Preview(parsed)
                message.value = "导入失败：${it.message}"
            }
        )
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxtImportScreen(
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit
) {
    val vm = appViewModel { TxtImportViewModel(it) }
    val state by vm.state.collectAsState()
    val mode by vm.mode.collectAsState()
    val groups by vm.groups.collectAsState()
    val title by vm.bookTitle.collectAsState()
    val intro by vm.bookIntro.collectAsState()
    val color by vm.coverColor.collectAsState()
    val message by vm.message.collectAsState()

    // 选文件：text/plain 为主；不少文件管理器把 txt 报成 application/octet-stream，一并放行
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.load(uri, context)
    }

    // 进入页面即打开文件选择器
    LaunchedEffect(Unit) {
        if (state is TxtImportState.Idle) picker.launch(arrayOf("text/plain", "application/octet-stream"))
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("导入 TXT") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (val s = state) {
                    TxtImportState.Idle -> IdleContent(onPick = {
                        picker.launch(arrayOf("text/plain", "application/octet-stream"))
                    })

                    TxtImportState.Reading -> LoadingContent("正在读取文件…")

                    is TxtImportState.Error -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("读取失败", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onBack) { Text("返回") }
                    }

                    is TxtImportState.Preview -> PreviewContent(
                        parsed = s.parsed,
                        mode = mode,
                        title = title,
                        intro = intro,
                        color = color,
                        groups = groups,
                        onTitle = { vm.bookTitle.value = it },
                        onIntro = { vm.bookIntro.value = it },
                        onColor = { vm.coverColor.value = it },
                        onMode = { vm.setMode(it) },
                        onImport = { vm.confirmImport(it) }
                    )

                    TxtImportState.Importing -> LoadingContent("正在导入…")

                    is TxtImportState.Done -> LaunchedEffect(s.bookId) {
                        onOpenBook(s.bookId)
                    }
                }
            }
        }

        // 一次性提示（Toast 风格，悬浮底部）
        message?.let { msg ->
            LaunchedEffect(msg) {
                kotlinx.coroutines.delay(2500)
                vm.clearMessage()
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp
            ) {
                Text(
                    msg,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun IdleContent(onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CloudUpload,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text("选择一个 TXT 文件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "支持 GBK / UTF-8 编码，自动识别章节结构",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPick) { Text("选择文件") }
    }
}

@Composable
private fun LoadingContent(text: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreviewContent(
    parsed: ParsedTxt,
    mode: TxtParseMode,
    title: String,
    intro: String,
    color: Int,
    groups: List<BookGroup>,
    onTitle: (String) -> Unit,
    onIntro: (String) -> Unit,
    onColor: (Int) -> Unit,
    onMode: (TxtParseMode) -> Unit,
    onImport: (Long?) -> Unit
) {
    var selectedGroupId by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // 统计卡片
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("${parsed.volumeCount}", "分卷")
                StatItem("${parsed.chapterCount}", "章节")
                StatItem(formatCount(parsed.totalChars), "总字数")
            }
        }
        Spacer(Modifier.height(16.dp))

        // 识别模式
        ModeSelector(mode = mode, onMode = onMode)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text("书名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = intro,
            onValueChange = onIntro,
            label = { Text("简介（可选）") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        // 封面颜色
        Text("封面颜色", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CoverColors.take(8).forEach { c ->
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .clickable { onColor(c) },
                    contentAlignment = Alignment.Center
                ) {
                    if (c == color) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "已选",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 分组
        if (groups.isNotEmpty()) {
            GroupSelector(groups = groups, selected = selectedGroupId, onSelect = { selectedGroupId = it })
            Spacer(Modifier.height(12.dp))
        }

        // 章节预览
        Text(
            "结构预览",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (parsed.chapterCount == 0) {
                Text(
                    "未识别到章节，请尝试更换识别模式",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Column(Modifier.padding(vertical = 8.dp)) {
                    parsed.volumes.forEach { volume ->
                        Text(
                            "卷 ${volume.title}（${volume.chapters.size} 章）",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        volume.chapters.take(3).forEach { ch ->
                            Text(
                                "　${ch.title} · ${formatCount(TextTool.countWords(ch.content))} 字",
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (volume.chapters.size > 3) {
                            Text(
                                "　…共 ${volume.chapters.size} 章",
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // 操作
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onImport(selectedGroupId) },
                enabled = parsed.chapterCount > 0,
                modifier = Modifier.weight(1f)
            ) { Text("确认导入") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 字数友好显示：10000 以上显示「x.x万」 */
private fun formatCount(n: Int): String =
    if (n >= 10000) "${"%.1f".format(n / 10000.0)}万" else n.toString()

@Composable
private fun StatItem(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModeSelector(mode: TxtParseMode, onMode: (TxtParseMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("识别模式：${mode.label}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TxtParseMode.entries.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.label) },
                    onClick = {
                        onMode(m)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun GroupSelector(groups: List<BookGroup>, selected: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = groups.firstOrNull { it.id == selected }
    Box {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "分组：${current?.name ?: "不分组"}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("不分组") },
                onClick = { onSelect(null); expanded = false }
            )
            groups.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.name) },
                    onClick = { onSelect(g.id); expanded = false }
                )
            }
        }
    }
}
