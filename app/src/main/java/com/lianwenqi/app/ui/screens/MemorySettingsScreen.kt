package com.lianwenqi.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    // 输入框用本地状态，避免每次写入 DataStore 后回传造成光标跳动
    var editBaseUrl by remember { mutableStateOf<String?>(null) }
    var editApiKey by remember { mutableStateOf<String?>(null) }
    var editModel by remember { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }

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
                OutlinedTextField(
                    value = editBaseUrl ?: savedBaseUrl,
                    onValueChange = { editBaseUrl = it; vm.setBaseUrl(it) },
                    label = { Text("baseUrl") },
                    placeholder = { Text("例如 https://api.openai.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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
                OutlinedTextField(
                    value = editModel ?: savedModel,
                    onValueChange = { editModel = it; vm.setModel(it) },
                    label = { Text("向量模型名") },
                    placeholder = { Text("例如 text-embedding-3-small") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                        "开启后对话持续勾选记忆检索，并在本书正文有更新时自动向量化本书；" +
                            "关闭则每次由用户手动勾选。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = defaultEnabled, onCheckedChange = { vm.setDefaultEnabled(it) })
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("勾选向量提示词时自动向量化")
                    Text(
                        "开启后，勾选记忆检索时按数据库中本书的最新进度自动续跑向量化；" +
                            "关闭则正文有更新时每次弹窗询问是否向量化到最新章节 —— " +
                            "选「是」用最新进度，选「否」沿用已有进度。两种结果都只影响本轮参考资料，用户不可见。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoVectorize, onCheckedChange = { vm.setAutoVectorize(it) })
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
}

private val EMBEDDING_HELP_TEXT = """
向量化原理
· 向量模型会把一段文本转成一串高维小数（向量）。语义越接近的文本，向量方向也越接近。
· 发送消息时，把「本章梗概 + 当前提示词」也向量化，再与本书已向量化的正文块算余弦相似度，取最相近的若干块作为参考资料随请求发出。
· 这些参考资料只在本轮生效：不写进会话前缀、不落库，因此不会破坏原有前缀的缓存命中。

各厂商计价（示例，实际价格以官网为准）
· OpenAI text-embedding-3-small：约 $0.02 / 百万 token，1536 维。
· 通义 text-embedding-v3：按量计费，中文效果较好，维度可选。
· 智谱 embedding-3：按量计费，兼容 OpenAI 接口格式。
· 硅基流动等平台也提供同构的 /embeddings 接口，可自行填写 baseUrl。

本地工具（本轮占位）
· 使用开源模型在手机本地推理，免 API 费用，但需要下载模型（约 15 MB ~ 209 MB），并占用存储与电量。
""".trimIndent()
