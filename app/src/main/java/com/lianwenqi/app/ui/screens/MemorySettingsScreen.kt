package com.lianwenqi.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ai.AiException
import com.lianwenqi.app.ai.LocalModelStore
import com.lianwenqi.app.data.MEMORY_BLOCK_QUOTA_MAX
import com.lianwenqi.app.data.MEMORY_BLOCK_QUOTA_MIN
import com.lianwenqi.app.data.MemorySearchScope
import com.lianwenqi.app.data.MemoryToolMode
import com.lianwenqi.app.ui.appViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 本地模型下载进度（一次性下载两个模型，逐个串行） */
data class LocalDownloadState(
    val running: Boolean = false,
    /** 正在下载的模型名；空闲 / 完成时为 null */
    val label: String? = null,
    val done: Long = 0L,
    val total: Long = 0L,
    val error: String? = null,
    val finished: Boolean = false
)

class MemorySettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = container.prefs
    private val store = container.localModelStore

    /** 向量化工具三选一：关闭 / 厂商 Embedding API / 本地工具 */
    val apiEnabled: StateFlow<Boolean> =
        prefs.memoryToolMode.map { it == MemoryToolMode.API }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val localEnabled: StateFlow<Boolean> =
        prefs.memoryToolMode.map { it == MemoryToolMode.LOCAL }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 两个本地模型是否都已下载就绪；就绪后才允许勾选本地工具 */
    val localReady = MutableStateFlow(false)

    val localDownload = MutableStateFlow(LocalDownloadState())

    val baseUrl: StateFlow<String> =
        prefs.memoryEmbeddingBaseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val apiKey: StateFlow<String> =
        prefs.memoryEmbeddingApiKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val model: StateFlow<String> =
        prefs.memoryEmbeddingModel.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 记忆检索是否限定范围（开启后每次勾选都会先选章节范围） */
    val scopeLimitEnabled: StateFlow<Boolean> =
        prefs.memoryScopeLimitEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 向量化工具是否已配置可用（API 模式需填齐 baseUrl / apiKey / 向量模型名；本地模式需已启用）。
     * 未配置时「记忆检索」按钮不可用。
     */
    val vectorizationReady: StateFlow<Boolean> =
        prefs.memoryVectorizationReady.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 勾选记忆检索时自动向量化（关闭则正文有更新时每次弹窗询问） */
    val autoVectorize: StateFlow<Boolean> =
        prefs.memoryAutoVectorize.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 记忆检索区块设置：注入参考资料的区块数（5-20） */
    val blockQuota: StateFlow<Int> =
        prefs.memoryBlockQuota.stateIn(
            viewModelScope, SharingStarted.Eagerly, MEMORY_BLOCK_QUOTA_MIN
        )

    /** 记忆检索区块设置：范围偏好（随机匹配 / 最新章节优先 / 最早章节优先） */
    val searchScope: StateFlow<String> =
        prefs.memorySearchScope.stateIn(
            viewModelScope, SharingStarted.Eagerly, MemorySearchScope.RANDOM
        )

    /** 「选择向量模型」：从 baseUrl 读取到的模型列表（名称像向量模型的排在前面） */
    val modelOptions = MutableStateFlow<List<String>>(emptyList())
    val loadingModels = MutableStateFlow(false)
    val modelError = MutableStateFlow<String?>(null)

    init {
        // 模型文件可能在别处被删/被下载，进入设置页时重新核对一次
        viewModelScope.launch { localReady.value = store.allReady() }
    }

    fun fetchModels() = viewModelScope.launch {
        loadingModels.value = true
        modelError.value = null
        val base = prefs.memoryEmbeddingBaseUrl.first()
        val key = prefs.memoryEmbeddingApiKey.first()
        container.aiClient.listModels(base, key)
            .onSuccess { ids ->
                modelOptions.value = ids.sortedWith(compareBy({ if (it.isEmbeddingLike()) 0 else 1 }, { it }))
                if (ids.isEmpty()) modelError.value = "未读取到模型，请检查供应商与 apiKey"
            }
            .onFailure { e ->
                modelError.value = (e as? AiException)?.error?.userMessage ?: e.message ?: "读取模型失败"
            }
        loadingModels.value = false
    }

    fun setApiEnabled(v: Boolean) = viewModelScope.launch {
        prefs.setMemoryToolMode(if (v) MemoryToolMode.API else MemoryToolMode.OFF)
    }

    /** 勾选本地工具：模型未就绪时由界面先引导下载，这里只负责切换设置 */
    fun setLocalEnabled(v: Boolean) = viewModelScope.launch {
        if (v && !store.allReady()) {
            localReady.value = false
            return@launch
        }
        prefs.setMemoryToolMode(if (v) MemoryToolMode.LOCAL else MemoryToolMode.OFF)
    }

    /** 下载尚未就绪的模型；全部就绪后自动把向量化工具切到本地 */
    fun downloadMissingModels() = viewModelScope.launch {
        val missing = LocalModelStore.SPECS.filter { !store.isReady(it) }
        if (missing.isEmpty()) {
            localReady.value = true
            localDownload.value = LocalDownloadState(finished = true)
            prefs.setMemoryToolMode(MemoryToolMode.LOCAL)
            return@launch
        }
        for (spec in missing) {
            localDownload.value = LocalDownloadState(running = true, label = spec.label)
            val result = store.download(spec) { done, total ->
                localDownload.value =
                    LocalDownloadState(running = true, label = spec.label, done = done, total = total)
            }
            if (result.isFailure) {
                localDownload.value = LocalDownloadState(
                    error = result.exceptionOrNull()?.message ?: "模型下载失败，请检查网络后重试"
                )
                return@launch
            }
        }
        // 新模型需要重新加载引擎
        container.localEngineHolder.invalidate()
        localReady.value = store.allReady()
        localDownload.value = LocalDownloadState(finished = true)
        prefs.setMemoryToolMode(MemoryToolMode.LOCAL)
    }

    fun setBaseUrl(v: String) = viewModelScope.launch { prefs.setMemoryEmbeddingBaseUrl(v) }
    fun setApiKey(v: String) = viewModelScope.launch { prefs.setMemoryEmbeddingApiKey(v) }
    fun setModel(v: String) = viewModelScope.launch { prefs.setMemoryEmbeddingModel(v) }
    fun setScopeLimitEnabled(v: Boolean) = viewModelScope.launch { prefs.setMemoryScopeLimitEnabled(v) }
    fun setAutoVectorize(v: Boolean) = viewModelScope.launch { prefs.setMemoryAutoVectorize(v) }
    fun setBlockQuota(v: Int) = viewModelScope.launch { prefs.setMemoryBlockQuota(v) }
    fun setSearchScope(v: String) = viewModelScope.launch { prefs.setMemorySearchScope(v) }
}

/**
 * 长期记忆（书本向量化）设置：
 * 向量化工具（厂商 Embedding API / 本地工具，互斥）、记忆检索范围限定、自动向量化开关与进度入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySettingsScreen(
    onBack: () -> Unit,
    onOpenProgress: () -> Unit = {}
) {
    val vm = appViewModel { MemorySettingsViewModel(it) }
    val apiEnabled by vm.apiEnabled.collectAsState()
    val localEnabled by vm.localEnabled.collectAsState()
    val localReady by vm.localReady.collectAsState()
    val download by vm.localDownload.collectAsState()
    val savedBaseUrl by vm.baseUrl.collectAsState()
    val savedApiKey by vm.apiKey.collectAsState()
    val savedModel by vm.model.collectAsState()
    val scopeLimitEnabled by vm.scopeLimitEnabled.collectAsState()
    val vectorizationReady by vm.vectorizationReady.collectAsState()
    val autoVectorize by vm.autoVectorize.collectAsState()
    val modelOptions by vm.modelOptions.collectAsState()
    val loadingModels by vm.loadingModels.collectAsState()
    val modelError by vm.modelError.collectAsState()
    val blockQuota by vm.blockQuota.collectAsState()
    val searchScope by vm.searchScope.collectAsState()

    // 输入框用本地状态，避免每次写入 DataStore 后回传造成光标跳动
    var editBaseUrl by remember { mutableStateOf<String?>(null) }
    var editApiKey by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    // 供应商预设（仅作展示，选中即回填 baseUrl）
    var vendorLabel by remember { mutableStateOf("自定义") }
    var showVendorMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showBlockSettings by remember { mutableStateOf(false) }

    // 系统返回键：停在「记忆检索区块设置」子页面时，先回到长期记忆配置页，而不是直接退出到设置页
    BackHandler(enabled = showBlockSettings) { showBlockSettings = false }

    // 「记忆检索区块设置」子页面：就地替换本页内容，返回时回到本页
    if (showBlockSettings) {
        MemoryBlockSettingsScreen(
            quota = blockQuota,
            scope = searchScope,
            onQuotaChange = { vm.setBlockQuota(it) },
            onScopeChange = { vm.setSearchScope(it) },
            onBack = { showBlockSettings = false }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("长期记忆（书本向量化）") },
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
                .padding(16.dp)
        ) {
            Text("向量化工具", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "把书籍正文切块后交给向量模型，得到可做语义检索的「长期记忆」。两个工具互斥，只能开启一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("厂商 Embedding API")
                    Text(
                        "使用厂商提供的向量接口，只在向量化时调用，不参与对话请求",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showHelp = true }) {
                    Text("?", style = MaterialTheme.typography.titleMedium)
                }
                Switch(checked = apiEnabled, onCheckedChange = { vm.setApiEnabled(it) })
            }
            if (apiEnabled) {
                Spacer(Modifier.height(8.dp))
                // 供应商预设（只列提供 OpenAI 兼容 /embeddings 接口的厂商）：选中即回填 baseUrl
                OutlinedButton(onClick = { showVendorMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("供应商：$vendorLabel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = showVendorMenu, onDismissRequest = { showVendorMenu = false }) {
                    embeddingVendorPresets.forEach { v ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(v.label)
                                    Text(
                                        v.baseUrl,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                vendorLabel = v.label
                                editBaseUrl = v.baseUrl
                                vm.setBaseUrl(v.baseUrl)
                                showVendorMenu = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("自定义") },
                        onClick = { vendorLabel = "自定义"; showVendorMenu = false }
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editBaseUrl ?: savedBaseUrl,
                    onValueChange = { editBaseUrl = it; vm.setBaseUrl(it) },
                    label = { Text("baseUrl") },
                    placeholder = { Text("例如 https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "接口地址：${embeddingEndpoint(savedBaseUrl)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editApiKey ?: savedApiKey,
                    onValueChange = { editApiKey = it; vm.setApiKey(it) },
                    label = { Text("apiKey") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showModelPicker = true; vm.fetchModels() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (savedModel.isBlank()) "选择向量模型" else "向量模型：$savedModel",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本地工具")
                    Text(
                        "使用开源模型在手机本地推理，免 API 费用。需先下载：${modelLine()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (localReady) "模型已就绪，勾选后本书将改用本地向量模型" else "模型未下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (localReady) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Switch(
                    checked = localEnabled,
                    onCheckedChange = { on ->
                        // 未下载时先提醒下载，下载完成后再启用
                        if (on && !localReady) showDownloadDialog = true else vm.setLocalEnabled(on)
                    }
                )
            }
            if (!localReady) {
                TextButton(onClick = { showDownloadDialog = true }) { Text("下载本地模型") }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("记忆检索是否限定范围")
                    Text(
                        if (scopeLimitEnabled) {
                            "已开启：每次勾选记忆检索会先弹窗选择检索范围（可全选全部章节，或按分卷整卷勾选、单选章节）。"
                        } else {
                            "关闭时每次检索都覆盖全书章节。开启后每次勾选记忆检索都会先选择章节范围（默认全部章节）。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = scopeLimitEnabled,
                    onCheckedChange = { vm.setScopeLimitEnabled(it) }
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("勾选记忆检索时自动向量化")
                    Text(
                        "开启后，勾选记忆检索时按数据库中本书的最新进度自动续跑向量化；" +
                            "关闭则本书正文有更新时每次弹窗询问是否向量化到最新章节。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoVectorize,
                    onCheckedChange = { vm.setAutoVectorize(it) }
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showBlockSettings = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("记忆检索区块设置")
                    Text(
                        "区块配额 $blockQuota 段 · 范围 ${scopeLabel(searchScope)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenProgress)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("查看向量化进度")
                    Text(
                        "按书查看已向量化到第几卷第几章（仅统计正文）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("向量化（Embedding）原理与计价") },
            text = { Text(EMBEDDING_HELP_TEXT) },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } }
        )
    }

    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { if (!download.running) showDownloadDialog = false },
            title = { Text("下载本地向量化模型") },
            text = {
                Column {
                    LocalModelStore.SPECS.forEach { spec ->
                        Text("· ${spec.label}（约 ${ceilMb(spec.sizeBytes)} MB）")
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        download.error != null -> Text(
                            download.error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        download.finished -> Text(
                            "下载完成，已启用本地工具",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        download.running -> {
                            val fraction =
                                if (download.total > 0) (download.done.toFloat() / download.total).coerceIn(0f, 1f)
                                else 0f
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "正在下载 ${download.label}：${mb(download.done)} / " +
                                    if (download.total > 0) "${mb(download.total)} MB" else "未知大小",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        else -> Text(
                            "下载后可勾选本地工具，改用本地向量模型。支持断点续传，中途退出可下次继续。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (download.finished) {
                    TextButton(onClick = { showDownloadDialog = false }) { Text("完成") }
                } else {
                    TextButton(
                        onClick = { vm.downloadMissingModels() },
                        enabled = !download.running
                    ) { Text(if (download.error != null) "重试" else "开始下载") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDownloadDialog = false },
                    enabled = !download.running
                ) { Text("取消") }
            }
        )
    }

    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("选择向量模型") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    when {
                        loadingModels -> Text("正在读取模型…")
                        modelError != null -> Text(
                            modelError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        modelOptions.isEmpty() -> Text("未读取到模型，请先填写 baseUrl 与 apiKey 后点「刷新」")
                        else -> modelOptions.forEach { name ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { vm.setModel(name); showModelPicker = false }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.fetchModels() }) { Text("刷新") } },
            dismissButton = { TextButton(onClick = { showModelPicker = false }) { Text("取消") } }
        )
    }
}

/** 两个本地模型的一行说明文本 */
private fun modelLine(): String =
    LocalModelStore.SPECS.joinToString("、") { "${it.label}（约 ${ceilMb(it.sizeBytes)} MB）" }

/**
 * 记忆检索区块设置：注入参考资料的区块数（5-20）与范围偏好。
 * 区块数决定「每次发送带几段参考资料」；范围决定「优先从本书的哪一段里找」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryBlockSettingsScreen(
    quota: Int,
    scope: String,
    onQuotaChange: (Int) -> Unit,
    onScopeChange: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆检索区块设置") },
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
                .padding(16.dp)
        ) {
            Text("区块配额", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "每次发送时注入提示词的参考资料段数（$MEMORY_BLOCK_QUOTA_MIN-$MEMORY_BLOCK_QUOTA_MAX 段）：" +
                    "段数越多可参考的背景越全，提示词也越长。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = quota.toFloat(),
                    onValueChange = { onQuotaChange(it.roundToInt()) },
                    valueRange = MEMORY_BLOCK_QUOTA_MIN.toFloat()..MEMORY_BLOCK_QUOTA_MAX.toFloat(),
                    steps = MEMORY_BLOCK_QUOTA_MAX - MEMORY_BLOCK_QUOTA_MIN - 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                Text("$quota 段", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text("范围", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "决定优先从本书的哪一段里找参考资料：靠后的新章节还是靠前的老章节。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            memoryScopeOptions.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onScopeChange(option.value) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = scope == option.value,
                        onClick = { onScopeChange(option.value) }
                    )
                    Column(Modifier.weight(1f)) {
                        Text(option.label)
                        Text(
                            option.hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 范围偏好的中文名（设置页摘要展示） */
private fun scopeLabel(scope: String): String =
    memoryScopeOptions.firstOrNull { it.value == scope }?.label ?: "随机匹配"

private data class MemoryScopeOption(val value: String, val label: String, val hint: String)

private val memoryScopeOptions = listOf(
    MemoryScopeOption(
        MemorySearchScope.NEWEST,
        "最新章节优先",
        "越靠后的章节越容易被选中，适合接着最近的剧情往下写"
    ),
    MemoryScopeOption(
        MemorySearchScope.OLDEST,
        "最早章节优先",
        "越靠前的章节越容易被选中，适合找回早期设定与伏笔"
    ),
    MemoryScopeOption(
        MemorySearchScope.RANDOM,
        "随机匹配",
        "不看章节位置，纯按相关度匹配（默认）"
    )
)

private fun ceilMb(bytes: Long): Long = (bytes + 1024L * 1024L - 1) / (1024L * 1024L)

private fun mb(bytes: Long): String = "%.1f".format(bytes / 1024.0 / 1024.0)

/** 提供 OpenAI 兼容 /embeddings 接口的常见供应商（选中后自动填入 baseUrl） */
private data class EmbeddingVendorPreset(val label: String, val baseUrl: String)

private val embeddingVendorPresets = listOf(
    EmbeddingVendorPreset("OpenAI", "https://api.openai.com/v1"),
    EmbeddingVendorPreset("通义千问（兼容模式）", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    EmbeddingVendorPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
    EmbeddingVendorPreset("硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1")
)

/** 实际请求的接口地址：baseUrl + /embeddings（已以 /embeddings 结尾时不重复追加） */
private fun embeddingEndpoint(baseUrl: String): String {
    val base = baseUrl.trim().trimEnd('/')
    if (base.isBlank()) return "（请先填写 baseUrl）"
    return if (base.endsWith("/embeddings")) base else "$base/embeddings"
}

/** 名称像向量模型的（选择列表里优先展示） */
private fun String.isEmbeddingLike(): Boolean {
    val s = lowercase()
    return listOf("embedding", "embed", "bge", "m3", "gte", "vector").any { s.contains(it) }
}

private val EMBEDDING_HELP_TEXT = """
向量化原理
· 向量模型会把一段文本转成一串高维小数（向量）。语义越接近的文本，向量方向也越接近。
· 发送消息时，把「本章梗概 + 当前提示词」也向量化，再与本书已向量化的正文块算余弦相似度，取最相近的若干块作为参考资料随请求发出。
· 检索到的参考资料会随本轮消息一起写入历史对话（不写入会话前缀，因此前缀字节结构不变），下一轮及以后可作为历史被模型看到。

各厂商计价（按字数估算，示例价格，实际以官网为准）
· 为便于估算，以下按「1 token ≈ 1 个汉字」把官网的 token 单价换算成「每千字」价格。
· OpenAI text-embedding-3-small：约 ¥0.0001 / 千字（官网 $0.02 / 百万 token），1536 维。
· 通义 text-embedding-v3：约 ¥0.0005 / 千字，中文效果较好，维度可选。
· 智谱 embedding-3：约 ¥0.0005 / 千字，兼容 OpenAI 接口格式。
· 硅基流动等平台的 bge-m3 等模型：约 ¥0.0001 ~ ¥0.001 / 千字，可自行填写 baseUrl。
· 一本书按 100 万字估算，全部向量化一次通常只需几分钱。

本地工具
· 使用开源模型（BGE small zh v1.5 Q4_K_M + BGE reranker base Q4_K_M）在手机本地推理，免 API 费用。
· 首次使用需下载约 15 MB + 209 MB 模型，占用存储与电量；向量化与检索都在本机完成，正文不会发往任何第三方。
· 本地检索会用向量模型粗排候选，再用重排模型精排，命中质量更好。
""".trimIndent()
