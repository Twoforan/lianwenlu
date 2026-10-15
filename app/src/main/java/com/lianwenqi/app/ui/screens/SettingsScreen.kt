package com.lianwenqi.app.ui.screens

import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.ai.AiException
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.ui.appViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val configRepository = container.configRepository
    private val prefs = container.prefs

    val providers: StateFlow<List<Provider>> =
        configRepository.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val models: StateFlow<List<AiModel>> =
        configRepository.observeModels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val prompts: StateFlow<List<Prompt>> =
        configRepository.observePrompts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val nightMode: StateFlow<Boolean> =
        prefs.nightMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val activeModelId: StateFlow<Long> =
        prefs.activeModelId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    val aiFollowBook: StateFlow<Boolean> =
        prefs.aiFollowBook.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val aiOutlineFirstRound: StateFlow<Boolean> =
        prefs.aiOutlineFirstRound.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun saveProvider(provider: Provider) = viewModelScope.launch { configRepository.saveProvider(provider) }
    fun deleteProvider(id: Long) = viewModelScope.launch { configRepository.deleteProvider(id) }

    fun fetchModels(provider: Provider, onResult: (List<String>, String?) -> Unit) =
        viewModelScope.launch {
            // 兜底再包一层：即使底层意外抛出（如非法配置），也只把错误交给 UI，绝不让异常逃出协程崩 App
            runCatching { configRepository.fetchRemoteModels(provider) }
                .getOrElse { Result.failure(it) }
                .onSuccess { onResult(it, null) }
                // 失败时展示按类型区分后的提示（未填 key / 非法字符 / 鉴权 / 超时 / 服务器错误…）
                .onFailure { onResult(emptyList(), (it as? AiException)?.error?.userMessage ?: it.message) }
        }

    fun saveModel(model: AiModel) = viewModelScope.launch { configRepository.saveModel(model) }
    fun deleteModel(id: Long) = viewModelScope.launch { configRepository.deleteModel(id) }
    fun setActiveModel(id: Long) = viewModelScope.launch { prefs.setActiveModelId(id) }

    fun savePrompt(prompt: Prompt) = viewModelScope.launch { configRepository.savePrompt(prompt) }
    fun deletePrompt(id: Long) = viewModelScope.launch { configRepository.deletePrompt(id) }

    fun setNight(v: Boolean) = viewModelScope.launch { prefs.setNightMode(v) }
    fun setAiFollowBook(v: Boolean) = viewModelScope.launch { prefs.setAiFollowBook(v) }
    fun setAiOutlineFirstRound(v: Boolean) = viewModelScope.launch { prefs.setAiOutlineFirstRound(v) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAiTools: () -> Unit = {},
    onOpenPrefix: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenUpdate: () -> Unit = {}
) {
    val vm = appViewModel { SettingsViewModel(it) }
    val providers by vm.providers.collectAsState()
    val models by vm.models.collectAsState()
    val prompts by vm.prompts.collectAsState()
    val night by vm.nightMode.collectAsState()
    val activeModelId by vm.activeModelId.collectAsState()
    val aiFollowBook by vm.aiFollowBook.collectAsState()
    val aiOutlineFirstRound by vm.aiOutlineFirstRound.collectAsState()

    // 默认停在「通用」页：点击底部「设置」以及从 AI 工具 / 前缀词管理 / 检查更新等子页面返回时，
    // 都会回到该页（返回时本页重新组合，记住的 tab 复位为默认值 2）
    var tab by remember { mutableStateOf(2) }
    var editProvider by remember { mutableStateOf<Provider?>(null) }
    var showProviderDialog by remember { mutableStateOf(false) }
    var addModelFor by remember { mutableStateOf<Provider?>(null) }
    var editModel by remember { mutableStateOf<AiModel?>(null) }
    var editPrompt by remember { mutableStateOf<Prompt?>(null) }
    var showPromptDialog by remember { mutableStateOf(false) }

    // 用 Box 承载页面与弹层：InputDialog 是组合内覆盖层，需叠在设置页之上才能拿到输入法 inset
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("设置") }) }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("模型与供应商") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("提示词库") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("通用") })
                }

                when (tab) {
                    0 -> ProvidersTab(
                        providers = providers,
                        models = models,
                        activeModelId = activeModelId,
                        onAddProvider = { editProvider = null; showProviderDialog = true },
                        onEditProvider = { editProvider = it; showProviderDialog = true },
                        onDeleteProvider = { vm.deleteProvider(it.id) },
                        onAddModel = { addModelFor = it },
                        onEditModel = { editModel = it },
                        onDeleteModel = { vm.deleteModel(it.id) },
                        onSetActive = { vm.setActiveModel(it.id) },
                        onFetchModels = { p, cb -> vm.fetchModels(p, cb) }
                    )
                    1 -> PromptsTab(
                        prompts = prompts,
                        onAdd = { editPrompt = null; showPromptDialog = true },
                        onEdit = { editPrompt = it; showPromptDialog = true },
                        onDelete = { vm.deletePrompt(it.id) }
                    )
                    else -> Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("夜间模式", Modifier.weight(1f))
                            Switch(checked = night, onCheckedChange = { vm.setNight(it) })
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("章节内会话跟书走")
                                Text(
                                    if (aiFollowBook) {
                                        "同一本书共用一个侧边 AI 对话，「＋」新建对话并绑定本书"
                                    } else {
                                        "会话跟随章节，「＋」新建对话并绑定本章节"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = aiFollowBook, onCheckedChange = { vm.setAiFollowBook(it) })
                        }
                        if (aiFollowBook) {
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("首轮全选大纲与章节")
                                    Text(
                                        "新建「跟书」会话时默认全选大纲与章节（仅梗概/仅正文按当时勾选）。" +
                                            "两项内容随后由「前缀词管理」在首轮写入会话前缀并保持稳定，" +
                                            "只有重新勾选才改写前缀。「本章梗概」只写进本轮提示词、不进前缀，避免打断缓存。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = aiOutlineFirstRound,
                                    onCheckedChange = { vm.setAiOutlineFirstRound(it) }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenPrefix)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("前缀词管理")
                                Text(
                                    "上下文前缀化、大纲前缀化与自定义前缀",
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
                                .clickable(onClick = onOpenAiTools)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("AI工具")
                                Text(
                                    "写入大纲等 AI 辅助工具",
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
                                .clickable(onClick = onOpenMemory)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("长期记忆（书本向量化）")
                                Text(
                                    "书籍正文向量化后，发送时按本章梗概与提示词语义检索相关片段作为参考资料",
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
                                .clickable(onClick = onOpenUpdate)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("检查更新")
                                Text(
                                    "从 GitHub 获取最新版本，有新版本可直接下载安装",
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
                        Text(
                            "炼文器 v0.1.0 · 基础框架\n" +
                                "数据保存在本机 Room 数据库，导出使用系统分享。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (showProviderDialog) {
            ProviderDialog(
                initial = editProvider,
                onDismiss = { showProviderDialog = false },
                onSave = { vm.saveProvider(it); showProviderDialog = false }
            )
        }

        addModelFor?.let { provider ->
            AddModelDialog(
                provider = provider,
                onDismiss = { addModelFor = null },
                onFetch = { cb -> vm.fetchModels(provider, cb) },
                onSave = { vm.saveModel(it); addModelFor = null }
            )
        }

        editModel?.let { model ->
            ModelDetailDialog(
                model = model,
                onDismiss = { editModel = null },
                onDelete = { vm.deleteModel(model.id); editModel = null },
                onSave = { vm.saveModel(it); editModel = null }
            )
        }

        if (showPromptDialog) {
            PromptDialog(
                initial = editPrompt,
                onDismiss = { showPromptDialog = false },
                onSave = { vm.savePrompt(it); showPromptDialog = false }
            )
        }
    }
}

@Composable
private fun ProvidersTab(
    providers: List<Provider>,
    models: List<AiModel>,
    activeModelId: Long,
    onAddProvider: () -> Unit,
    onEditProvider: (Provider) -> Unit,
    onDeleteProvider: (Provider) -> Unit,
    onAddModel: (Provider) -> Unit,
    onEditModel: (AiModel) -> Unit,
    onDeleteModel: (AiModel) -> Unit,
    onSetActive: (AiModel) -> Unit,
    onFetchModels: (Provider, (List<String>, String?) -> Unit) -> Unit
) {
    val context = LocalContext.current
    // 「测试读取模型」正在测试的供应商 id，与测试结果文案（key = provider id）
    var testingId by remember { mutableStateOf<Long?>(null) }
    val testResults = remember { mutableStateMapOf<Long, String>() }
    val testOk = remember { mutableStateMapOf<Long, Boolean>() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            Button(onClick = onAddProvider) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加供应商")
            }
            Spacer(Modifier.height(8.dp))
        }
        items(providers, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text(p.baseUrl, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("接口：${p.apiType.label}", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onEditProvider(p) }) {
                            Icon(Icons.Filled.Edit, "编辑", Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDeleteProvider(p) }) {
                            Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    val providerModels = models.filter { it.providerId == p.id }
                    providerModels.forEach { m ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onEditModel(m) }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    m.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (m.alias.isNotBlank()) {
                                    Text(
                                        m.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Text(
                                "T=${"%.1f".format(m.temperature)}",
                                style = MaterialTheme.typography.labelSmall
                            )
                            FilterChip(
                                selected = m.id == activeModelId,
                                onClick = { onSetActive(m) },
                                label = { Text(if (m.id == activeModelId) "使用中" else "使用") }
                            )
                            IconButton(onClick = { onEditModel(m) }) {
                                Icon(Icons.Filled.Edit, "编辑", Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onDeleteModel(m) }) {
                                Icon(Icons.Filled.Delete, "删除", Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        OutlinedButton(onClick = { onAddModel(p) }) { Text("添加模型") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = testingId != p.id,
                            onClick = {
                                // 真实请求一次模型列表：能连上并解析成功即视为连通
                                testingId = p.id
                                testResults.remove(p.id)
                                onFetchModels(p) { list, err ->
                                    testingId = null
                                    val ok = err == null
                                    testOk[p.id] = ok
                                    testResults[p.id] = when {
                                        // 直接展示错误原因（未填写 API Key / 含非法字符 / 鉴权失败 / 超时…）
                                        err != null -> err
                                        list.isEmpty() -> "连接成功，但未读取到模型（请检查 apiKey / baseUrl）"
                                        else -> "连接成功，读取到 ${list.size} 个模型"
                                    }
                                    Toast.makeText(context, testResults[p.id], Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) { Text(if (testingId == p.id) "测试中…" else "测试读取模型") }
                    }
                    testResults[p.id]?.let { msg ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (testOk[p.id] == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptsTab(
    prompts: List<Prompt>,
    onAdd: () -> Unit,
    onEdit: (Prompt) -> Unit,
    onDelete: (Prompt) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        item {
            Button(onClick = onAdd) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建提示词")
            }
        }
        items(prompts, key = { it.id }) { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = { onEdit(p) }) {
                            Icon(Icons.Filled.Edit, "编辑", Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(p) }) {
                            Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp))
                        }
                    }
                    Text(
                        p.content,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * 带输入框的弹层：内容区可滚动，并整体随输入法上移。
 * 用于「预设提示词 / 提示词库」等长文本编辑：光标在屏幕下方时也不会被键盘遮住。
 *
 * 这里不使用 [androidx.compose.ui.window.Dialog]，而是直接叠在页面之上：
 * Dialog 窗口在本项目里拿不到输入法 inset，只有 Activity 内的组合才能让 [imePadding] 生效。
 */
@Composable
private fun InputDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                // 内容区最多占满剩余高度并可滚动，输入框获得焦点时自动滚入可视区
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) { content() }
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    dismissButton()
                    Spacer(Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
    }
}

/** 常见供应商预设：选中后自动填入 baseUrl 与接口类型（名称留空时一并填入） */
private data class VendorPreset(val label: String, val baseUrl: String, val apiType: ApiType)

private val vendorPresets = listOf(
    VendorPreset("DeepSeek", "https://api.deepseek.com/v1", ApiType.CHAT_COMPLETIONS),
    VendorPreset("OpenAI（GPT）", "https://api.openai.com/v1", ApiType.CHAT_COMPLETIONS),
    VendorPreset("Claude（Anthropic）", "https://api.anthropic.com/v1", ApiType.ANTHROPIC_MESSAGES),
    VendorPreset("硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", ApiType.CHAT_COMPLETIONS),
    VendorPreset("月之暗面 Kimi", "https://api.moonshot.cn/v1", ApiType.CHAT_COMPLETIONS),
    VendorPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", ApiType.CHAT_COMPLETIONS),
    VendorPreset("通义千问（兼容模式）", "https://dashscope.aliyuncs.com/compatible-mode/v1", ApiType.CHAT_COMPLETIONS),
    VendorPreset("Google Gemini（OpenAI 兼容）", "https://generativelanguage.googleapis.com/v1beta/openai", ApiType.CHAT_COMPLETIONS)
)

@Composable
private fun ProviderDialog(
    initial: Provider?,
    onDismiss: () -> Unit,
    onSave: (Provider) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://api.openai.com/v1") }
    var apiType by remember { mutableStateOf(initial?.apiType ?: ApiType.CHAT_COMPLETIONS) }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var showTypeMenu by remember { mutableStateOf(false) }
    // 选了哪个供应商预设（仅作展示，选中即回填 baseUrl / 接口类型）
    var vendorLabel by remember { mutableStateOf("自定义") }
    var showVendorMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加供应商" else "编辑供应商") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Column {
                    OutlinedButton(onClick = { showVendorMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("供应商：$vendorLabel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = showVendorMenu, onDismissRequest = { showVendorMenu = false }) {
                        vendorPresets.forEach { v ->
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
                                    baseUrl = v.baseUrl
                                    apiType = v.apiType
                                    if (name.isBlank()) name = v.label
                                    showVendorMenu = false
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("自定义") },
                            onClick = { vendorLabel = "自定义"; showVendorMenu = false }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("baseUrl") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Column {
                    OutlinedButton(onClick = { showTypeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("接口：${apiType.label}")
                    }
                    DropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                        ApiType.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label) },
                                onClick = { apiType = t; showTypeMenu = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("apiKey") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: Provider(name = name, baseUrl = baseUrl))
                            .copy(name = name.trim(), baseUrl = baseUrl.trim(), apiType = apiType, apiKey = apiKey.trim())
                    )
                }
            ) { Text("保存供应商") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddModelDialog(
    provider: Provider,
    onDismiss: () -> Unit,
    onFetch: ((List<String>, String?) -> Unit) -> Unit,
    onSave: (AiModel) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    // 是否通过「读取可选模型」从厂商列表中选择（厂商模型标识不可修改）
    var fromRemote by remember { mutableStateOf(false) }
    var temperature by remember { mutableStateOf(0.8f) }
    var maxTokens by remember { mutableStateOf(8192) }
    var unlimited by remember { mutableStateOf(false) }
    var maxContext by remember { mutableStateOf(20) }
    var systemPrompt by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRemote by remember { mutableStateOf(false) }

    InputDialog(
        title = "添加模型 · ${provider.name}",
        onDismiss = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        AiModel(
                            providerId = provider.id,
                            name = name.trim(),
                            alias = alias.trim(),
                            temperature = temperature,
                            maxTokens = if (unlimited) 0 else maxTokens,
                            maxContext = maxContext,
                            systemPrompt = systemPrompt,
                            custom = !fromRemote
                        )
                    )
                }
            ) { Text("保存模型") }
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name, onValueChange = { name = it; fromRemote = false },
                label = { Text("模型（接口名）") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                onFetch { list, err ->
                    remote = list; error = err; showRemote = list.isNotEmpty()
                }
            }) { Text("读取可选模型") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelSmall) }
        if (showRemote) {
            DropdownMenu(expanded = showRemote, onDismissRequest = { showRemote = false }) {
                remote.forEach { r ->
                    DropdownMenuItem(
                        text = { Text(r) },
                        onClick = { name = r; fromRemote = true; showRemote = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = alias, onValueChange = { alias = it },
            label = { Text("显示名称（可选）") },
            placeholder = { Text("用于区分同类模型，如「写作-正文」") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("温度：${"%.2f".format(temperature)}")
        Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f)
        MaxTokensSetting(
            maxTokens = maxTokens,
            unlimited = unlimited,
            onTokensChange = { maxTokens = it },
            onUnlimitedChange = { unlimited = it }
        )
        MaxContextSetting(maxContext = maxContext, onContextChange = { maxContext = it })
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("预设提示词") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 模型详情：可修改模型标识（厂商模型只读）、显示名称、温度、最大 token 与预设提示词 */
@Composable
private fun ModelDetailDialog(
    model: AiModel,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (AiModel) -> Unit
) {
    var name by remember { mutableStateOf(model.name) }
    var alias by remember { mutableStateOf(model.alias) }
    var temperature by remember { mutableStateOf(model.temperature) }
    var maxTokens by remember { mutableStateOf(if (model.maxTokens > 0) model.maxTokens else 8192) }
    var unlimited by remember { mutableStateOf(model.maxTokens <= 0) }
    var maxContext by remember { mutableStateOf(model.maxContext) }
    var systemPrompt by remember { mutableStateOf(model.systemPrompt) }

    InputDialog(
        title = "模型设置",
        onDismiss = onDismiss,
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        model.copy(
                            name = name.trim(),
                            alias = alias.trim(),
                            temperature = temperature,
                            maxTokens = if (unlimited) 0 else maxTokens,
                            maxContext = maxContext,
                            systemPrompt = systemPrompt
                        )
                    )
                }
            ) { Text("保存") }
        }
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            enabled = model.custom,
            label = { Text(if (model.custom) "模型（接口名）" else "模型（厂商提供，不可修改）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = alias,
            onValueChange = { alias = it },
            label = { Text("显示名称") },
            placeholder = { Text("用于区分同类模型") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Text("输出温度：${"%.2f".format(temperature)}")
        Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f)
        MaxTokensSetting(
            maxTokens = maxTokens,
            unlimited = unlimited,
            onTokensChange = { maxTokens = it },
            onUnlimitedChange = { unlimited = it }
        )
        MaxContextSetting(maxContext = maxContext, onContextChange = { maxContext = it })
        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("预设提示词") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PromptDialog(
    initial: Prompt?,
    onDismiss: () -> Unit,
    onSave: (Prompt) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }

    InputDialog(
        title = if (initial == null) "新建提示词" else "编辑提示词",
        onDismiss = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && content.isNotBlank(),
                onClick = {
                    onSave((initial ?: Prompt(title = title, content = content))
                        .copy(title = title.trim(), content = content))
                }
            ) { Text("保存") }
        }
    ) {
        OutlinedTextField(value = title, onValueChange = { title = it },
            label = { Text("提示词标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = content, onValueChange = { content = it },
            label = { Text("提示词内容") }, minLines = 5, modifier = Modifier.fillMaxWidth())
    }
}

/** 最大输出 token：可切换「不限」（不限时不向接口发送 max_tokens） */
@Composable
private fun MaxTokensSetting(
    maxTokens: Int,
    unlimited: Boolean,
    onTokensChange: (Int) -> Unit,
    onUnlimitedChange: (Boolean) -> Unit
) {
    Text("最大输出 token：${if (unlimited) "不限" else maxTokens.toString()}")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = unlimited, onCheckedChange = onUnlimitedChange)
        Spacer(Modifier.width(8.dp))
        Text("不限（不限制输出长度）", style = MaterialTheme.typography.labelMedium)
    }
    if (!unlimited) {
        Slider(
            value = maxTokens.toFloat(),
            onValueChange = { onTokensChange(it.toInt()) },
            valueRange = 256f..32768f
        )
    }
}

/** 最大上下文：携带的历史消息条数；「不限」时存 0，发送全部历史 */
@Composable
private fun MaxContextSetting(maxContext: Int, onContextChange: (Int) -> Unit) {
    val unlimited = maxContext <= 0
    Text("最大上下文：${if (unlimited) "不限" else "$maxContext 条"}")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = unlimited, onCheckedChange = { on -> onContextChange(if (on) 0 else 20) })
        Spacer(Modifier.width(8.dp))
        Text("不限（携带全部历史消息）", style = MaterialTheme.typography.labelMedium)
    }
    if (!unlimited) {
        Slider(
            value = maxContext.toFloat(),
            onValueChange = { onContextChange(it.toInt().coerceAtLeast(1)) },
            valueRange = 1f..30f,
            steps = 28
        )
    }
    Text(
        if (unlimited) "发送时携带全部历史消息（长对话下前缀更稳定，缓存命中率更高）"
        else "发送时最多携带最近 $maxContext 条历史消息",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}