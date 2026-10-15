package com.lianwenqi.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.lianwenqi.app.ui.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 向量化工具开关的两种取值：api = 厂商 Embedding API；off = 关闭 */
private const val MODE_API = "api"
private const val MODE_OFF = "off"

class MemorySettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val prefs = container.prefs

    /** 向量化工具：开启厂商 Embedding API（本地工具本轮为占位，不可开启） */
    val apiEnabled: StateFlow<Boolean> =
        prefs.memoryToolMode.map { it == MODE_API }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val baseUrl: StateFlow<String> =
        prefs.memoryEmbeddingBaseUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val apiKey: StateFlow<String> =
        prefs.memoryEmbeddingApiKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val model: StateFlow<String> =
        prefs.memoryEmbeddingModel.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 默认勾选记忆检索（开启后对话持续勾选，并在正文更新时自动向量化本书） */
    val defaultEnabled: StateFlow<Boolean> =
        prefs.memoryDefaultEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 勾选记忆检索时自动向量化（关闭则正文有更新时每次弹窗询问） */
    val autoVectorize: StateFlow<Boolean> =
        prefs.memoryAutoVectorize.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 「选择向量模型」：从 baseUrl 读取到的模型列表（名称像向量模型的排在前面） */
    val modelOptions = MutableStateFlow<List<String>>(emptyList())
    val loadingModels = MutableStateFlow(false)
    val modelError = MutableStateFlow<String?>(null)

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
        prefs.setMemoryToolMode(if (v) MODE_API else MODE_OFF)
    }

    fun setBaseUrl(v: String) = viewModelScope.launch { prefs.setMemoryEmbeddingBaseUrl(v) }
    fun setApiKey(v: String) = viewModelScope.launch { prefs.setMemoryEmbeddingApiKey(v) }
    fun setModel(v: String) = viewModelScope.launch { prefs.setMemoryEmbeddingModel(v) }
    fun setDefaultEnabled(v: Boolean) = viewModelScope.launch { prefs.setMemoryDefaultEnabled(v) }
    fun setAutoVectorize(v: Boolean) = viewModelScope.launch { prefs.setMemoryAutoVectorize(v) }
}

/**
 * 长期记忆（书本向量化）设置：
 * 向量化工具（厂商 Embedding API / 本地工具占位）、记忆检索默认开关、自动向量化开关与进度入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySettingsScreen(
    onBack: () -> Unit,
    onOpenProgress: () -> Unit = {}
) {
    val vm = appViewModel { MemorySettingsViewModel(it) }
    val apiEnabled by vm.apiEnabled.collectAsState()
    val savedBaseUrl by vm.baseUrl.collectAsState()
    val savedApiKey by vm.apiKey.collectAsState()
    val savedModel by vm.model.collectAsState()
    val defaultEnabled by vm.defaultEnabled.collectAsState()
    val autoVectorize by vm.autoVectorize.collectAsState()
    val modelOptions by vm.modelOptions.collectAsState()
    val loadingModels by vm.loadingModels.collectAsState()
    val modelError by vm.modelError.collectAsState()

    // 输入框用本地状态，避免每次写入 DataStore 后回传造成光标跳动
    var editBaseUrl by remember { mutableStateOf<String?>(null) }
    var editApiKey by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    // 供应商预设（仅作展示，选中即回填 baseUrl）
    var vendorLabel by remember { mutableStateOf("自定义") }
    var showVendorMenu by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

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
                        "需先下载开源模型：BGE small zh v1.5 Q4_K_M（约 15 MB）、" +
                            "BGE reranker base Q4_K_M（约 209 MB）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "本轮暂未提供模型下载与本地推理，此处为占位",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 本地工具与厂商 API 互斥；模型尚未提供下载，故保持禁用
                Switch(checked = false, enabled = false, onCheckedChange = null)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("默认勾选记忆检索")
                    Text(
                        "开启后对已向量化的书籍持续勾选记忆检索，并在本书正文有更新时自动向量化本书；" +
                            "未选择书籍或本书尚未向量化时不勾选。关闭则取消勾选，改为每次手动勾选。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = defaultEnabled, onCheckedChange = { vm.setDefaultEnabled(it) })
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // 「默认勾选记忆检索」开启时强制开启该项，开关置灰不可操作
            val autoVectorizeToggle: ((Boolean) -> Unit)? =
                if (defaultEnabled) null else { v -> vm.setAutoVectorize(v) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("勾选记忆检索时自动向量化")
                    Text(
                        if (defaultEnabled) {
                            "已由「默认勾选记忆检索」强制开启，暂不可关闭；关闭上一项后可自行控制。"
                        } else {
                            "开启后，勾选记忆检索时按数据库中本书的最新进度自动续跑向量化；" +
                                "关闭则本书正文有更新时每次弹窗询问是否向量化到最新章节。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = defaultEnabled || autoVectorize,
                    enabled = !defaultEnabled,
                    onCheckedChange = autoVectorizeToggle
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

本地工具（本轮占位）
· 使用开源模型在手机本地推理，免 API 费用，但需要下载模型（约 15 MB ~ 209 MB），并占用存储与电量。
""".trimIndent()
