package com.lianwenqi.app.ui.screens

import android.net.Uri
import android.widget.Toast

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.R
import com.lianwenqi.app.ui.components.DeleteConfirmDialog
import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiException
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ReasoningLevel
import com.lianwenqi.app.ai.RemoteModelInfo
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.PromptType
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.ProviderKey
import com.lianwenqi.app.data.BackupPayload
import com.lianwenqi.app.data.BackupSections
import com.lianwenqi.app.data.RestoreResult
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.util.ExportUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val configRepository = container.configRepository
    private val prefs = container.prefs
    private val backupRepository = container.backupRepository

    val providers: StateFlow<List<Provider>> =
        configRepository.observeProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val providerKeys: StateFlow<List<ProviderKey>> =
        configRepository.observeProviderKeys().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val models: StateFlow<List<AiModel>> =
        configRepository.observeModels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val prompts: StateFlow<List<Prompt>> =
        configRepository.observePrompts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** 模型配置页「从提示词库选择」只看模型提示词，不会混入快捷提示词 */
    val modelPrompts: StateFlow<List<Prompt>> =
        configRepository.observePrompts(PromptType.MODEL)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val nightMode: StateFlow<Boolean> =
        prefs.nightMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val activeModelId: StateFlow<Long> =
        prefs.activeModelId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    /** 供应商卡片展开状态（默认全部收起） */
    val expandedProviderIds: StateFlow<Set<Long>> =
        prefs.expandedProviderIds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun setProviderExpanded(id: Long, expanded: Boolean) =
        viewModelScope.launch { prefs.setProviderExpanded(id, expanded) }

    fun saveProvider(provider: Provider) = viewModelScope.launch { configRepository.saveProvider(provider) }
    fun deleteProvider(id: Long) = viewModelScope.launch { configRepository.deleteProvider(id) }

    /** 新建供应商，可选同时创建第一个 API 密钥 */
    fun createProvider(provider: Provider, firstKey: ProviderKey?) = viewModelScope.launch {
        val id = configRepository.saveProvider(provider)
        if (firstKey != null && firstKey.apiKey.isNotBlank()) {
            runCatching { configRepository.saveProviderKey(firstKey.copy(providerId = id)) }
        }
    }

    /** 保存密钥；[onResult] 回传错误文案（null 表示成功） */
    fun saveProviderKey(key: ProviderKey, onResult: (String?) -> Unit) = viewModelScope.launch {
        onResult(runCatching { configRepository.saveProviderKey(key) }.exceptionOrNull()
            ?.let { (it as? IllegalArgumentException)?.message ?: it.message ?: "保存失败" })
    }

    fun deleteProviderKey(id: Long) = viewModelScope.launch { configRepository.deleteProviderKey(id) }

    fun fetchModels(provider: Provider, key: ProviderKey, onResult: (List<RemoteModelInfo>, String?) -> Unit) =
        viewModelScope.launch {
            // 兜底再包一层：即使底层意外抛出（如非法配置），也只把错误交给 UI，绝不让异常逃出协程崩 App
            runCatching { configRepository.fetchRemoteModelInfos(provider, key) }
                .getOrElse { Result.failure(it) }
                .onSuccess { infos ->
                    // 只回填当前密钥下仍为「未知」的模型，绝不跨密钥覆盖
                    runCatching { configRepository.syncModelContextWindows(key.id, infos) }
                    onResult(infos, null)
                }
                // 失败时展示按类型区分后的提示（未填 key / 非法字符 / 鉴权 / 超时 / 服务器错误…）
                .onFailure { onResult(emptyList(), (it as? AiException)?.error?.userMessage ?: it.message) }
        }

    /**
     * 真实对话测试：`/models` 能通不代表 `/chat/completions` 能通。
     * 使用该密钥的真实 API Key 与密钥下的模型发一次最小对话，并回传实际请求地址。
     */
    fun testChat(provider: Provider, key: ProviderKey, model: AiModel?, onResult: (Boolean, String) -> Unit) =
        viewModelScope.launch {
            if (model == null) {
                onResult(false, "该密钥下还没有模型：请先点「添加模型」添加模型")
                return@launch
            }
            val url = container.aiClient.chatUrl(provider.baseUrl, provider.apiType)
            val endpoint = com.lianwenqi.app.ai.ModelEndpoint(
                provider = provider,
                apiKey = key.apiKey,
                blockedReason = when {
                    !key.enabled -> "密钥「${key.name}」已禁用，请先启用后再测试"
                    key.apiKey.isBlank() -> "密钥「${key.name}」未填写 API Key，请先补填"
                    else -> null
                },
                modelName = model.name,
                apiType = provider.apiType
            )
            val result = runCatching { container.aiClient.testChat(endpoint) }
                .getOrElse { AiResult.fail((it as? AiException)?.error ?: AiError.fromThrowable(it)) }
            val message = if (result.success) {
                val reply = result.content.trim().replace(Regex("\\s+"), " ").take(40)
                "对话测试成功（${model.name}）" + if (reply.isBlank()) "" else "，模型返回：$reply"
            } else {
                result.error?.userMessage ?: "对话测试失败"
            }
            onResult(result.success, "$message\n请求地址：$url")
        }

    fun saveModel(model: AiModel) = viewModelScope.launch { configRepository.saveModel(model) }

    /** 远程多选批量添加模型 */
    fun saveModels(models: List<AiModel>) = viewModelScope.launch { configRepository.saveModels(models) }

    fun deleteModel(id: Long) = viewModelScope.launch { configRepository.deleteModel(id) }
    fun setActiveModel(id: Long) = viewModelScope.launch { prefs.setActiveModelId(id) }

    fun savePrompt(prompt: Prompt) = viewModelScope.launch { configRepository.savePrompt(prompt) }
    fun deletePrompt(id: Long) = viewModelScope.launch { configRepository.deletePrompt(id) }

    fun setNight(v: Boolean) = viewModelScope.launch { prefs.setNightMode(v) }

    // ---------------- 数据备份 / 数据恢复 ----------------

    fun defaultBackupName(): String = backupRepository.defaultFileName()

    /** 打包勾选的分区到缓存 zip（供分享） */
    fun createBackup(sections: Set<String>, onDone: (Result<File>) -> Unit) = viewModelScope.launch {
        onDone(runCatching { withContext(Dispatchers.IO) { backupRepository.createBackupZip(sections) } })
    }

    /** 打包勾选的分区并写入用户选中的保存位置，回调备份的书籍数量 */
    fun saveBackupTo(uri: Uri, sections: Set<String>, onDone: (Result<Int>) -> Unit) =
        viewModelScope.launch {
            onDone(
                runCatching {
                    withContext(Dispatchers.IO) { backupRepository.writeBackupTo(uri, sections) }
                }
            )
        }

    /** 备份实际包含的分区（旧备份按内容推断） */
    fun sectionsOf(payload: BackupPayload): Set<String> = backupRepository.availableSections(payload)

    /** 读取并校验备份，同时返回与现有书籍重名的书名 */
    fun inspectBackup(uri: Uri, onDone: (Result<Pair<BackupPayload, List<String>>>) -> Unit) =
        viewModelScope.launch {
            onDone(
                runCatching {
                    val payload = withContext(Dispatchers.IO) { backupRepository.readBackup(uri) }
                    payload to backupRepository.conflictTitles(payload)
                }
            )
        }

    /**
     * 恢复备份：[overwrite] 为 true 时整本覆盖同名书籍，否则跳过同名书籍；
     * [sections] 为本次勾选要恢复的分区。
     */
    fun restoreBackup(
        payload: BackupPayload,
        overwrite: Boolean,
        sections: Set<String>,
        onDone: (Result<RestoreResult>) -> Unit
    ) = viewModelScope.launch {
        onDone(runCatching { backupRepository.restore(payload, overwrite, sections) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAiTools: () -> Unit = {},
    onOpenPrefix: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenStyle: () -> Unit = {},
    onOpenManual: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenCompression: () -> Unit = {},
    onOpenPersonalization: () -> Unit = {}
) {
    val vm = appViewModel { SettingsViewModel(it) }
    val providers by vm.providers.collectAsState()
    val providerKeys by vm.providerKeys.collectAsState()
    val models by vm.models.collectAsState()
    val prompts by vm.prompts.collectAsState()
    val modelPrompts by vm.modelPrompts.collectAsState()
    val activeModelId by vm.activeModelId.collectAsState()
    val expandedProviderIds by vm.expandedProviderIds.collectAsState()

    // 默认停在「通用」页：点击底部「设置」以及从 AI 工具 / 前缀词管理 / 检查更新等子页面返回时，
    // 都会回到该页（返回时本页重新组合，记住的 tab 复位为默认值 2）
    var tab by remember { mutableStateOf(2) }
    var editProvider by remember { mutableStateOf<Provider?>(null) }
    var showProviderDialog by remember { mutableStateOf(false) }
    // 密钥弹窗：addKeyFor 非空表示「添加」；editKey 非空表示「编辑」
    var addKeyFor by remember { mutableStateOf<Provider?>(null) }
    var editKey by remember { mutableStateOf<ProviderKey?>(null) }
    var addModelFor by remember { mutableStateOf<Pair<Provider, ProviderKey>?>(null) }
    var editModel by remember { mutableStateOf<AiModel?>(null) }
    var editPrompt by remember { mutableStateOf<Prompt?>(null) }
    var showPromptDialog by remember { mutableStateOf(false) }
    /** 新建提示词时使用的类型（由提示词库当前分类决定） */
    var newPromptType by remember { mutableStateOf(PromptType.QUICK) }
    var deletePromptTarget by remember { mutableStateOf<Prompt?>(null) }

    // 数据备份 / 恢复：showBackupMenu 为备份多选弹窗；busyText 非空时展示不可取消的进度弹窗
    val context = LocalContext.current
    var showBackupMenu by remember { mutableStateOf(false) }
    var busyText by remember { mutableStateOf<String?>(null) }
    var conflicts by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingPayload by remember { mutableStateOf<BackupPayload?>(null) }
    // 备份多选：提示词库 / 系统设置 / 全部书籍内容
    var backupPrompts by remember { mutableStateOf(true) }
    var backupSettings by remember { mutableStateOf(true) }
    var backupBooks by remember { mutableStateOf(true) }
    var pendingBackupSections by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 恢复多选：读取备份后展示该备份实际包含的分区
    var showRestoreOptions by remember { mutableStateOf(false) }
    var restorePayload by remember { mutableStateOf<BackupPayload?>(null) }
    var restoreConflicts by remember { mutableStateOf<List<String>>(emptyList()) }
    var conflictSections by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun restore(payload: BackupPayload, overwrite: Boolean, sections: Set<String>) {
        busyText = if (overwrite) "正在覆盖恢复…" else "正在恢复数据…"
        vm.restoreBackup(payload, overwrite, sections) { result ->
            busyText = null
            result.onSuccess { r ->
                val parts = buildList {
                    if (BackupSections.BOOKS in sections) {
                        add("新建 ${r.created} 本，覆盖 ${r.replaced} 本，跳过 ${r.skipped} 本")
                    }
                    if (r.prompts > 0) add("提示词 ${r.prompts} 条")
                    if (r.settingsRestored > 0) add("系统设置 ${r.settingsRestored} 项")
                }
                val suffix = if (parts.isEmpty()) "" else "：" + parts.joinToString("；")
                Toast.makeText(context, "恢复完成$suffix", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "恢复失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun shareBackup(sections: Set<String>) {
        busyText = "正在打包备份…"
        vm.createBackup(sections) { result ->
            busyText = null
            result.onSuccess { file ->
                runCatching { ExportUtil.shareFile(context, file, "application/zip", "分享备份") }
                    .onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show() }
            }.onFailure {
                Toast.makeText(context, "备份失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val backupSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            busyText = "正在导出备份…"
            vm.saveBackupTo(it, pendingBackupSections) { result ->
                busyText = null
                result.onSuccess {
                    Toast.makeText(context, "备份已保存", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(context, "备份失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val backupOpenLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            busyText = "正在读取备份…"
            vm.inspectBackup(it) { result ->
                busyText = null
                result.onSuccess { (payload, duplicated) ->
                    // 读取后按该备份实际包含的分区让用户勾选要恢复的内容
                    restorePayload = payload
                    restoreConflicts = duplicated
                    showRestoreOptions = true
                }.onFailure {
                    Toast.makeText(context, "恢复失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 用 Box 承载页面与弹层：InputDialog 是组合内覆盖层，需叠在设置页之上才能拿到输入法 inset
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .height(56.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("设置", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
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
                        providerKeys = providerKeys,
                        models = models,
                        activeModelId = activeModelId,
                        expandedProviderIds = expandedProviderIds,
                        onToggleProvider = { id, expanded -> vm.setProviderExpanded(id, expanded) },
                        onAddProvider = { editProvider = null; showProviderDialog = true },
                        onEditProvider = { editProvider = it; showProviderDialog = true },
                        onDeleteProvider = { vm.deleteProvider(it.id) },
                        onAddKey = { addKeyFor = it },
                        onEditKey = { editKey = it },
                        onDeleteKey = { vm.deleteProviderKey(it.id) },
                        onToggleKey = { key, enabled -> vm.saveProviderKey(key.copy(enabled = enabled)) {} },
                        onAddModel = { p, k -> addModelFor = p to k },
                        onEditModel = { editModel = it },
                        onDeleteModel = { vm.deleteModel(it.id) },
                        onSetActive = { vm.setActiveModel(it.id) },
                        onFetchModels = { p, k, cb -> vm.fetchModels(p, k, cb) },
                        onTestChat = { p, k, m, cb -> vm.testChat(p, k, m, cb) }
                    )
                    1 -> PromptsTab(
                        prompts = prompts,
                        onAdd = { type -> newPromptType = type; editPrompt = null; showPromptDialog = true },
                        onEdit = { editPrompt = it; showPromptDialog = true },
                        onDelete = { deletePromptTarget = it }
                    )
                    // 通用设置项较多（含「检查更新」等），必须可纵向滚动，否则小屏看不到底部条目
                    else -> Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onOpenPersonalization)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("个性化设置")
                                Text(
                                    "自定义应用外观",
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
                                .clickable(onClick = onOpenManual)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("使用说明")
                                Text(
                                    "查看内置使用说明书，并可把它交给 AI，提问本应用的用法",
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
                                .clickable { showBackupMenu = true }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("数据备份")
                                Text(
                                    "可选择备份提示词库、系统设置与全部书籍的大纲、正文、梗概和卷梗概",
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
                                .clickable {
                                    backupOpenLauncher.launch(
                                        arrayOf(
                                            "application/zip",
                                            "application/x-zip-compressed",
                                            "application/octet-stream"
                                        )
                                    )
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("数据恢复")
                                Text(
                                    "导入本应用生成的备份 zip，按备份里的分区勾选要恢复的内容",
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
                                .clickable(onClick = onOpenCompression)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("分卷压缩")
                                Text(
                                    "整卷梗概的默认来源、完成后是否更新大纲，以及大纲更新范围",
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
                                    "书籍正文向量化后，发送时按本章梗概与提示词语义检索相关片段，作为参考资料写入本轮对话",
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
                                .clickable(onClick = onOpenStyle)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("文风")
                                Text(
                                    "管理文风档案、样本与标签",
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
                                "数据保存在本机 Room 数据库，可在「数据备份」里打包迁移。",
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
                onSave = { provider, firstKey ->
                    if (provider.id == 0L) vm.createProvider(provider, firstKey) else vm.saveProvider(provider)
                    showProviderDialog = false
                }
            )
        }

        addKeyFor?.let { provider ->
            KeyDialog(
                provider = provider,
                initial = null,
                onDismiss = { addKeyFor = null },
                onSave = { key, onError ->
                    vm.saveProviderKey(key) { err ->
                        if (err == null) addKeyFor = null else onError(err)
                    }
                }
            )
        }

        editKey?.let { key ->
            val provider = providers.firstOrNull { it.id == key.providerId }
            if (provider != null) {
                KeyDialog(
                    provider = provider,
                    initial = key,
                    onDismiss = { editKey = null },
                    onSave = { updated, onError ->
                        vm.saveProviderKey(updated) { err ->
                            if (err == null) editKey = null else onError(err)
                        }
                    }
                )
            }
        }

        addModelFor?.let { (provider, key) ->
            AddModelDialog(
                provider = provider,
                key = key,
                existing = models.filter { it.providerKeyId == key.id },
                modelPrompts = modelPrompts,
                onDismiss = { addModelFor = null },
                onFetch = { cb -> vm.fetchModels(provider, key, cb) },
                onSaveList = { list -> vm.saveModels(list); addModelFor = null }
            )
        }

        editModel?.let { model ->
            ModelDetailDialog(
                model = model,
                modelPrompts = modelPrompts,
                onDismiss = { editModel = null },
                onDelete = { vm.deleteModel(model.id); editModel = null },
                onSave = { vm.saveModel(it); editModel = null }
            )
        }

        if (showPromptDialog) {
            PromptDialog(
                initial = editPrompt,
                // 编辑时用原记录的类型，保证不会因为切换分类而改类型
                type = editPrompt?.type ?: newPromptType,
                onDismiss = { showPromptDialog = false },
                onSave = { vm.savePrompt(it); showPromptDialog = false }
            )
        }

        deletePromptTarget?.let { target ->
            DeleteConfirmDialog(
                type = "提示词",
                name = target.title,
                onDismiss = { deletePromptTarget = null },
                onConfirm = {
                    vm.deletePrompt(target.id)
                    deletePromptTarget = null
                }
            )
        }

        // 备份：多选要导出的分区，再选择保存到文件或分享
        if (showBackupMenu) {
            val sections = buildSet {
                if (backupBooks) add(BackupSections.BOOKS)
                if (backupPrompts) add(BackupSections.PROMPTS)
                if (backupSettings) add(BackupSections.SETTINGS)
            }
            AlertDialog(
                onDismissRequest = { showBackupMenu = false },
                title = { Text("数据备份") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "勾选要备份的内容，再选择保存到本机或分享：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        BackupSectionRow("提示词库", backupPrompts) { backupPrompts = it }
                        BackupSectionRow("系统设置", backupSettings) { backupSettings = it }
                        BackupSectionRow(
                            "全部书籍（大纲 / 正文 / 梗概 / 卷梗概）",
                            backupBooks
                        ) { backupBooks = it }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = sections.isNotEmpty(),
                        onClick = {
                            pendingBackupSections = sections
                            showBackupMenu = false
                            backupSaveLauncher.launch(vm.defaultBackupName())
                        }
                    ) { Text("保存到文件") }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            enabled = sections.isNotEmpty(),
                            onClick = {
                                pendingBackupSections = sections
                                showBackupMenu = false
                                shareBackup(sections)
                            }
                        ) { Text("分享") }
                        TextButton(onClick = { showBackupMenu = false }) { Text("取消") }
                    }
                }
            )
        }

        // 恢复：只列出该备份实际包含的分区，按勾选恢复
        val restoreSource = restorePayload
        if (showRestoreOptions && restoreSource != null) {
            val available = remember(restoreSource) { vm.sectionsOf(restoreSource) }
            var restoreBooks by remember(restoreSource) {
                mutableStateOf(BackupSections.BOOKS in available)
            }
            var restorePrompts by remember(restoreSource) {
                mutableStateOf(BackupSections.PROMPTS in available)
            }
            var restoreSettings by remember(restoreSource) {
                mutableStateOf(BackupSections.SETTINGS in available)
            }
            val selectedSections = buildSet {
                if (restoreBooks) add(BackupSections.BOOKS)
                if (restorePrompts) add(BackupSections.PROMPTS)
                if (restoreSettings) add(BackupSections.SETTINGS)
            }
            AlertDialog(
                onDismissRequest = {
                    showRestoreOptions = false
                    restorePayload = null
                },
                title = { Text("数据恢复") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "备份里包含以下内容，勾选要恢复的部分：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (BackupSections.BOOKS in available) {
                            BackupSectionRow(
                                "全部书籍（大纲 / 正文 / 梗概 / 卷梗概）",
                                restoreBooks
                            ) { restoreBooks = it }
                        }
                        if (BackupSections.PROMPTS in available) {
                            BackupSectionRow("提示词库", restorePrompts) { restorePrompts = it }
                        }
                        if (BackupSections.SETTINGS in available) {
                            BackupSectionRow("系统设置", restoreSettings) { restoreSettings = it }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = selectedSections.isNotEmpty(),
                        onClick = {
                            showRestoreOptions = false
                            restorePayload = null
                            if (BackupSections.BOOKS in selectedSections && restoreConflicts.isNotEmpty()) {
                                pendingPayload = restoreSource
                                conflicts = restoreConflicts
                                conflictSections = selectedSections
                            } else {
                                restore(restoreSource, overwrite = false, sections = selectedSections)
                            }
                        }
                    ) { Text("开始恢复") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showRestoreOptions = false
                        restorePayload = null
                    }) { Text("取消") }
                }
            )
        }

        // 同名书籍：询问覆盖还是跳过
        pendingPayload?.let { payload ->
            AlertDialog(
                onDismissRequest = { pendingPayload = null; conflicts = emptyList() },
                title = { Text("发现同名书籍") },
                text = {
                    Column(
                        Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("备份里以下书籍与书架上现有的书籍同名：")
                        Spacer(Modifier.height(8.dp))
                        conflicts.forEach { Text("· $it") }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "「覆盖」会用备份里的内容整本替换现有书籍；" +
                                "「跳过」则保留现有书籍，只恢复其它书。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingPayload = null
                        conflicts = emptyList()
                        restore(payload, overwrite = true, sections = conflictSections)
                    }) { Text("覆盖") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            pendingPayload = null
                            conflicts = emptyList()
                            restore(payload, overwrite = false, sections = conflictSections)
                        }) { Text("跳过") }
                        TextButton(onClick = {
                            pendingPayload = null
                            conflicts = emptyList()
                            conflictSections = emptySet()
                        }) { Text("取消") }
                    }
                }
            )
        }

        // 打包 / 读取 / 恢复进度：不可取消，避免中途留下半份数据
        busyText?.let { message ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(message)
                    }
                }
            )
        }
    }
}

@Composable
private fun ProvidersTab(
    providers: List<Provider>,
    providerKeys: List<ProviderKey>,
    models: List<AiModel>,
    activeModelId: Long,
    expandedProviderIds: Set<Long>,
    onToggleProvider: (Long, Boolean) -> Unit,
    onAddProvider: () -> Unit,
    onEditProvider: (Provider) -> Unit,
    onDeleteProvider: (Provider) -> Unit,
    onAddKey: (Provider) -> Unit,
    onEditKey: (ProviderKey) -> Unit,
    onDeleteKey: (ProviderKey) -> Unit,
    onToggleKey: (ProviderKey, Boolean) -> Unit,
    onAddModel: (Provider, ProviderKey) -> Unit,
    onEditModel: (AiModel) -> Unit,
    onDeleteModel: (AiModel) -> Unit,
    onSetActive: (AiModel) -> Unit,
    onFetchModels: (Provider, ProviderKey, (List<RemoteModelInfo>, String?) -> Unit) -> Unit,
    onTestChat: (Provider, ProviderKey, AiModel?, (Boolean, String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    // 「测试读取 / 测试对话」正在测试的密钥 id，与测试结果文案（key = 密钥 id）
    var testingKeyId by remember { mutableStateOf<Long?>(null) }
    var testingChatKeyId by remember { mutableStateOf<Long?>(null) }
    val testResults = remember { mutableStateMapOf<Long, String>() }
    val testOk = remember { mutableStateMapOf<Long, Boolean>() }
    // 二次确认删除：值为 "m<模型id>" 或 "k<密钥id>"，点其他任意操作都会清空
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    val scheme = MaterialTheme.colorScheme
    val activeModel = models.firstOrNull { it.id == activeModelId }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // 第一行：供应商与密钥总数，加粗突出
                    Text(
                        "共 ${providers.size} 个供应商 · 共 ${providerKeys.size} 个密钥",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                    // 第二行：当前使用的模型
                    Text(
                        "当前使用：${activeModel?.displayName ?: "未选择模型"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onAddProvider, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加供应商")
                }
            }
        }
        if (providers.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(64.dp).clip(CircleShape).background(scheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Dns, null, Modifier.size(30.dp), tint = scheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("还没有模型供应商", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点击右上角「添加供应商」，填写接口地址与 API 密钥",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
        items(providers, key = { it.id }) { p ->
            val keys = providerKeys.filter { it.providerId == p.id }
            val providerModels = models.filter { it.providerId == p.id }
            val orphans = providerModels.filter { m -> keys.none { it.id == m.providerKeyId } }
            val expanded = p.id in expandedProviderIds
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
                border = BorderStroke(1.dp, scheme.outlineVariant)
            ) {
                Column(
                    Modifier
                        .padding(14.dp)
                        // 点击卡片空白处：取消「待确认删除」状态
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { pendingDelete = null }
                ) {
                    // ---------- 供应商头部（点击整行收起 / 展开） ----------
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { pendingDelete = null; onToggleProvider(p.id, !expanded) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(scheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                p.name.trim().take(1).uppercase().ifBlank { "?" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = scheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${keys.size} 个密钥 · ${providerModels.size} 个模型",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(3.dp))
                            StatusPill(p.apiType.label, scheme.secondaryContainer, scheme.onSecondaryContainer)
                        }
                        IconButton(onClick = { onEditProvider(p) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Edit, "编辑", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                        }
                        IconButton(
                            onClick = {
                                deleteTarget = DeleteTarget("供应商", p.name) { onDeleteProvider(p) }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Outlined.Delete, "删除", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                        }
                        Icon(
                            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) "收起供应商" else "展开供应商",
                            modifier = Modifier.size(22.dp),
                            tint = scheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        p.baseUrl,
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (expanded) {
                    // ---------- 密钥列表 ----------
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Key, null, Modifier.size(16.dp), tint = scheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "API 密钥",
                            style = MaterialTheme.typography.labelLarge,
                            color = scheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { pendingDelete = null; onAddKey(p) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.heightIn(min = 32.dp)
                        ) {
                            Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("添加密钥", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (keys.isEmpty()) {
                        Text(
                            "还没有 API 密钥，请先添加密钥，再为密钥添加模型",
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(scheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    keys.forEach { key ->
                        val keyModels = providerModels.filter { it.providerKeyId == key.id }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = scheme.surfaceVariant.copy(alpha = if (key.enabled) 0.45f else 0.25f)
                        ) {
                            Column(
                                Modifier
                                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { pendingDelete = null }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                key.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            if (key.enabled) {
                                                StatusPill("启用", scheme.primaryContainer, scheme.onPrimaryContainer)
                                            } else {
                                                StatusPill("已禁用", scheme.errorContainer, scheme.onErrorContainer)
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "${key.maskedKey} · ${keyModels.size} 个模型",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = scheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = key.enabled,
                                        onCheckedChange = { pendingDelete = null; onToggleKey(key, it) },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                    IconButton(
                                        onClick = { pendingDelete = null; onEditKey(key) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Outlined.Edit, "编辑密钥", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                                    }
                                    IconButton(
                                        onClick = {
                                            deleteTarget = DeleteTarget(
                                                "API 秘钥",
                                                key.name.ifBlank { key.maskedKey }
                                            ) { onDeleteKey(key) }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Outlined.Delete, "删除密钥", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                                    }
                                }
                                if (keyModels.isNotEmpty()) {
                                    Spacer(Modifier.height(6.dp))
                                    Column(
                                        Modifier.padding(end = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        keyModels.forEach { m ->
                                            ModelRowItem(
                                                model = m,
                                                active = m.id == activeModelId,
                                                confirmDelete = false,
                                                onEdit = { pendingDelete = null; onEditModel(m) },
                                                onSetActive = { pendingDelete = null; onSetActive(m) },
                                                onRequestDelete = {
                                                    pendingDelete = null
                                                    deleteTarget = DeleteTarget(
                                                        "模型",
                                                        m.displayName.ifBlank { m.name }
                                                    ) { onDeleteModel(m) }
                                                },
                                                onDelete = { pendingDelete = null }
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.padding(end = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    KeyActionButton(
                                        text = "添加模型",
                                        icon = Icons.Outlined.CloudDownload,
                                        primary = true,
                                        enabled = true,
                                        modifier = Modifier.weight(1f)
                                    ) { pendingDelete = null; onAddModel(p, key) }
                                    KeyActionButton(
                                        text = if (testingKeyId == key.id) "测试中…" else "测试读取",
                                        icon = Icons.Outlined.NetworkCheck,
                                        primary = false,
                                        enabled = testingKeyId != key.id,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        testingKeyId = key.id
                                        testResults.remove(key.id)
                                        onFetchModels(p, key) { list, err ->
                                            testingKeyId = null
                                            testOk[key.id] = err == null
                                            val hint = if (p.baseUrl.trim().trimEnd('/').endsWith("/v1")) ""
                                            else "；可尝试在 baseUrl 后面添加 /v1 试试看"
                                            testResults[key.id] = when {
                                                err != null -> err + hint
                                                list.isEmpty() ->
                                                    "连接成功，但未读取到模型（请检查 API Key / baseUrl）" + hint
                                                else -> "连接成功，读取到 ${list.size} 个模型"
                                            }
                                            Toast.makeText(context, testResults[key.id], Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    KeyActionButton(
                                        text = if (testingChatKeyId == key.id) "测试中…" else "测试对话",
                                        icon = Icons.AutoMirrored.Outlined.Chat,
                                        primary = false,
                                        enabled = testingChatKeyId != key.id,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        val model = keyModels.firstOrNull { it.id == activeModelId }
                                            ?: keyModels.firstOrNull()
                                        testingChatKeyId = key.id
                                        testResults.remove(key.id)
                                        onTestChat(p, key, model) { ok, msg ->
                                            testingChatKeyId = null
                                            testOk[key.id] = ok
                                            testResults[key.id] = msg
                                            Toast.makeText(
                                                context,
                                                msg.lineSequence().first(),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                                testResults[key.id]?.let { msg ->
                                    val ok = testOk[key.id] == true
                                    Spacer(Modifier.height(8.dp))
                                    Row(
                                        Modifier
                                            .padding(end = 8.dp)
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (ok) scheme.primaryContainer.copy(alpha = 0.6f)
                                                else scheme.errorContainer.copy(alpha = 0.6f)
                                            )
                                            .padding(8.dp)
                                    ) {
                                        Icon(
                                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                            null,
                                            Modifier.size(16.dp),
                                            tint = if (ok) scheme.primary else scheme.error
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            msg,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (ok) scheme.onPrimaryContainer else scheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (orphans.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "未绑定密钥的模型（请编辑后重新保存以绑定）",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error
                        )
                        Spacer(Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            orphans.forEach { m ->
                                ModelRowItem(
                                    model = m,
                                    active = m.id == activeModelId,
                                    confirmDelete = false,
                                    onEdit = { pendingDelete = null; onEditModel(m) },
                                    onSetActive = { pendingDelete = null; onSetActive(m) },
                                    onRequestDelete = {
                                        pendingDelete = null
                                        deleteTarget = DeleteTarget(
                                            "模型",
                                            m.displayName.ifBlank { m.name }
                                        ) { onDeleteModel(m) }
                                    },
                                    onDelete = { pendingDelete = null }
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
    // 统一删除确认弹窗
    deleteTarget?.let { target ->
        DeleteConfirmDialog(
            type = target.type,
            name = target.name,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                target.confirm()
            }
        )
    }
}

/** 删除确认弹窗的目标描述 */
private data class DeleteTarget(
    val type: String,
    val name: String,
    val confirm: () -> Unit
)

/** 小圆角状态标签 */
@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    Text(
        text,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 7.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 密钥卡片底部的紧凑操作按钮 */
@Composable
private fun KeyActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    primary: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val padding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
    if (primary) {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 36.dp),
            contentPadding = padding,
            content = content
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.heightIn(min = 36.dp),
            contentPadding = padding,
            content = content
        )
    }
}

/** 密钥分组下的单个模型行：名称 / 温度 / 使用 / 编辑 / 删除 */
@Composable
private fun ModelRowItem(
    model: AiModel,
    active: Boolean,
    confirmDelete: Boolean,
    onEdit: () -> Unit,
    onSetActive: () -> Unit,
    onRequestDelete: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) scheme.primaryContainer.copy(alpha = 0.7f) else scheme.surface)
            .then(
                if (active) Modifier.border(1.dp, scheme.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                else Modifier
            )
            .clickable(onClick = onEdit)
            .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (active) scheme.primary else scheme.outlineVariant)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    if (model.alias.isNotBlank()) append(model.name).append(" · ")
                    append("温度 ").append("%.1f".format(model.temperature))
                },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (active) {
            StatusPill("使用中", scheme.primary, scheme.onPrimary)
        } else {
            TextButton(
                onClick = onSetActive,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 30.dp)
            ) {
                Text("使用", style = MaterialTheme.typography.labelMedium)
            }
        }
        IconButton(onClick = if (confirmDelete) onDelete else onRequestDelete, modifier = Modifier.size(36.dp)) {
            if (confirmDelete) {
                Icon(Icons.Filled.Check, "确认删除", Modifier.size(18.dp), tint = scheme.error)
            } else {
                Icon(Icons.Outlined.Delete, "删除", Modifier.size(17.dp), tint = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PromptsTab(
    prompts: List<Prompt>,
    onAdd: (PromptType) -> Unit,
    onEdit: (Prompt) -> Unit,
    onDelete: (Prompt) -> Unit
) {
    // 默认打开快捷提示词，保持原有使用习惯
    var selectedType by remember { mutableStateOf(PromptType.QUICK) }
    val scheme = MaterialTheme.colorScheme
    val shown = prompts.filter { it.type == selectedType }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("提示词库", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (selectedType == PromptType.QUICK) "在 AI 对话 / Agent 输入栏点闪电图标追加到输入框"
                    else "在模型设置里选择模板，填充模型的预设提示词",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(
                onClick = { onAdd(selectedType) },
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PromptTypeChip(
                text = "快捷提示词",
                count = prompts.count { it.type == PromptType.QUICK },
                selected = selectedType == PromptType.QUICK
            ) { selectedType = PromptType.QUICK }
            PromptTypeChip(
                text = "模型提示词",
                count = prompts.count { it.type == PromptType.MODEL },
                selected = selectedType == PromptType.MODEL
            ) { selectedType = PromptType.MODEL }
        }
        if (shown.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(scheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (selectedType == PromptType.QUICK) Icons.Outlined.Bolt else Icons.Outlined.Article,
                        null,
                        Modifier.size(30.dp),
                        tint = scheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    if (selectedType == PromptType.QUICK) "还没有快捷提示词" else "还没有模型提示词",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (selectedType == PromptType.QUICK) "点右上角「新建」，保存常用的话术"
                    else "点右上角「新建」，保存可复用的系统提示词模板",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.id }) { p ->
                    PromptCard(prompt = p, onEdit = { onEdit(p) }, onDelete = { onDelete(p) })
                }
            }
        }
    }
}

/** 分类切换：与模型页的标签风格保持一致 */
@Composable
private fun PromptTypeChip(text: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$text $count", style = MaterialTheme.typography.labelLarge) },
        shape = RoundedCornerShape(50)
    )
}

/** 提示词卡片：标题 + 正文预览 + 编辑 / 删除 */
@Composable
private fun PromptCard(prompt: Prompt, onEdit: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    prompt.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Edit, "编辑", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Delete, "删除", Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                }
            }
            Text(
                prompt.content,
                Modifier.padding(end = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
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

private const val CustomVendorLabel = "自定义（手动填写）"

@Composable
private fun ProviderDialog(
    initial: Provider?,
    onDismiss: () -> Unit,
    onSave: (Provider, ProviderKey?) -> Unit
) {
    val editing = initial != null
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiType by remember { mutableStateOf(initial?.apiType ?: ApiType.CHAT_COMPLETIONS) }
    // 新建供应商时可选同时添加第一个密钥（编辑供应商不涉及密钥）
    var firstKeyName by remember { mutableStateOf("") }
    var firstKeyApiKey by remember { mutableStateOf("") }
    // 内置供应商：默认按 baseUrl 反查，匹配不到即视为自定义
    var vendorLabel by remember {
        mutableStateOf(
            initial?.let { p -> vendorPresets.firstOrNull { it.baseUrl == p.baseUrl }?.label }
                ?: if (initial == null) "" else CustomVendorLabel
        )
    }
    var presetOpen by remember { mutableStateOf(false) }
    var typeOpen by remember { mutableStateOf(false) }

    fun chooseVendor(preset: VendorPreset?) {
        presetOpen = false
        if (preset == null) {
            vendorLabel = CustomVendorLabel
            return
        }
        vendorLabel = preset.label
        baseUrl = preset.baseUrl
        apiType = preset.apiType
        if (name.isBlank()) name = preset.label
    }

    val canSave = name.isNotBlank() && baseUrl.isNotBlank()
    fun doSave() {
        if (!canSave) return
        val provider = (initial ?: Provider(name = name, baseUrl = baseUrl))
            .copy(name = name.trim(), baseUrl = baseUrl.trim(), apiType = apiType)
        val firstKey = if (!editing && firstKeyApiKey.isNotBlank()) {
            ProviderKey(
                providerId = provider.id,
                name = firstKeyName.trim().ifBlank { "默认密钥" },
                apiKey = firstKeyApiKey.trim()
            )
        } else null
        onSave(provider, firstKey)
    }

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
                // 编辑时内容较少，按内容自适应高度；新建时保留较高的固定高度
                .then(if (editing) Modifier else Modifier.fillMaxHeight(0.94f))
                .padding(horizontal = 14.dp, vertical = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEBEEF4)),
            shadowElevation = 16.dp
        ) {
            Column(Modifier.fillMaxWidth().then(if (editing) Modifier else Modifier.fillMaxSize())) {
                // ---------- 头部：渐变四角星 + 标题 + 副标题 ----------
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 6.dp, top = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_provider_star),
                        contentDescription = null,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (editing) "编辑供应商" else "添加供应商",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        Text("配置api服务供应商", fontSize = 10.sp, color = Color(0xFF8B93A5))
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭", Modifier.size(18.dp), tint = Color(0xFF9AA1AF))
                    }
                }
                HorizontalDivider(color = Color(0xFFEBEEF4))

                // ---------- 内容区 ----------
                Column(
                    Modifier
                        .then(if (editing) Modifier else Modifier.weight(1f))
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 供应商信息
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DialogSectionTitle("供应商信息")
                        DialogFieldLabel("内置供应商")
                        DialogDropdownField(
                            value = vendorLabel,
                            placeholder = "请选择供应商",
                            expanded = presetOpen,
                            onToggle = { presetOpen = !presetOpen }
                        )
                        if (presetOpen) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White)
                                    .border(1.dp, DialogFieldBorder, RoundedCornerShape(10.dp))
                                    .verticalScroll(rememberScrollState())
                            ) {
                                vendorPresets.forEachIndexed { index, v ->
                                    if (index > 0) HorizontalDivider(color = Color(0xFFEFF1F6))
                                    ProviderPresetRow(
                                        label = v.label,
                                        baseUrl = v.baseUrl,
                                        selected = vendorLabel == v.label
                                    ) { chooseVendor(v) }
                                }
                                HorizontalDivider(color = Color(0xFFEFF1F6))
                                ProviderPresetRow(
                                    label = CustomVendorLabel,
                                    baseUrl = "手动填写名称与 BaseURL",
                                    selected = vendorLabel == CustomVendorLabel
                                ) { chooseVendor(null) }
                            }
                        }
                        DialogFieldLabel("供应商名称")
                        DialogField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "例如：DeepSeek"
                        )
                        DialogFieldLabel("BaseURL")
                        DialogField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            placeholder = "https://api.example.com/v1",
                            keyboardType = KeyboardType.Uri
                        )
                        // 自定义供应商才需要手动指定接口协议
                        if (vendorLabel == CustomVendorLabel) {
                            DialogFieldLabel("接口协议")
                            DialogDropdownField(
                                value = apiType.label,
                                placeholder = "请选择接口协议",
                                expanded = typeOpen,
                                onToggle = { typeOpen = !typeOpen }
                            )
                            if (typeOpen) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White)
                                        .border(1.dp, DialogFieldBorder, RoundedCornerShape(10.dp))
                                ) {
                                    ApiType.entries.forEachIndexed { index, t ->
                                        if (index > 0) HorizontalDivider(color = Color(0xFFEFF1F6))
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { apiType = t; typeOpen = false }
                                                .padding(horizontal = 12.dp, vertical = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                t.label,
                                                Modifier.weight(1f),
                                                fontSize = 12.sp,
                                                color = if (apiType == t) DialogPurple else DialogInkTitle,
                                                fontWeight = if (apiType == t) FontWeight.Medium else FontWeight.Normal
                                            )
                                            if (apiType == t) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    "已选择",
                                                    Modifier.size(14.dp),
                                                    tint = DialogPurple
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 首个 API 密钥（仅新建时提供，可留空）
                    if (!editing) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            DialogSectionTitle("首个api秘钥（可稍后添加）")
                            DialogFieldLabel("秘钥备注")
                            DialogField(
                                value = firstKeyName,
                                onValueChange = { firstKeyName = it },
                                placeholder = "例如：自动化使用"
                            )
                            DialogFieldLabel("API Key")
                            DialogField(
                                value = firstKeyApiKey,
                                onValueChange = { firstKeyApiKey = it },
                                placeholder = "粘贴你的API Key",
                                password = true
                            )
                        }
                    }
                }

                // ---------- 底部按钮 ----------
                HorizontalDivider(color = Color(0xFFEBEEF4))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E8EF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF70788A))
                    ) { Text("取消", fontSize = 12.sp) }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF6658E8), Color(0xFF8376F1)))
                            )
                            .then(
                                if (canSave) Modifier.clickable { doSave() }
                                else Modifier.clickable(enabled = false) { }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (editing) "保存供应商" else "添加供应商",
                                color = if (canSave) Color.White else Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                null,
                                Modifier.size(14.dp),
                                tint = if (canSave) Color.White else Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 内置供应商下拉选项：名称 + BaseURL，选中显示紫色对勾 */
@Composable
private fun ProviderPresetRow(
    label: String,
    baseUrl: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 12.sp,
                color = if (selected) DialogPurple else DialogInkTitle,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                baseUrl,
                fontSize = 9.sp,
                color = DialogInkHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(Icons.Filled.Check, "已选择", Modifier.size(14.dp), tint = DialogPurple)
        }
    }
}

/**
 * 添加 / 编辑 API 密钥。
 *
 * 编辑时 API Key 预填原值（输入框脱敏显示），不修改即保留原值；
 * 保存前校验非空与同供应商下重复，错误回显在弹窗内。
 */
@Composable
private fun KeyDialog(
    provider: Provider,
    initial: ProviderKey?,
    onDismiss: () -> Unit,
    onSave: (ProviderKey, (String) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val canSave = apiKey.isNotBlank() && !saving

    fun doSave() {
        if (!canSave) return
        saving = true
        error = null
        onSave(
            (initial ?: ProviderKey(providerId = provider.id)).copy(
                providerId = provider.id,
                name = name,
                apiKey = apiKey,
                enabled = enabled
            )
        ) { msg ->
            saving = false
            error = msg
        }
    }

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
                .padding(horizontal = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEBEEF4)),
            shadowElevation = 16.dp
        ) {
            Column {
                // 头部：钥匙图标 + 标题 + 供应商
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 15.dp, end = 6.dp, top = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painterResource(R.drawable.ic_key_svg),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (initial == null) "添加API秘钥" else "编辑API秘钥",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = DialogInkTitle
                        )
                        Text(
                            provider.name,
                            fontSize = 10.sp,
                            color = Color(0xFF8B93A5),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭", Modifier.size(18.dp), tint = Color(0xFF9AA1AF))
                    }
                }
                HorizontalDivider(color = Color(0xFFEBEEF4))

                // 内容区
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    DialogSectionTitle("秘钥信息")
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DialogFieldLabel("API备注名称")
                        DialogField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "例如：个人主秘钥，备用秘钥"
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DialogFieldLabel("API Key")
                        DialogField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            placeholder = "粘贴你的API Key",
                            password = true
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .border(1.dp, DialogFieldBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "启用该秘钥",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = DialogInkTitle
                            )
                            Text(
                                "启用后，该秘钥可用于API请求",
                                fontSize = 10.sp,
                                color = DialogInkHint
                            )
                        }
                        DialogSwitchBox(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    if (initial != null) {
                        Text(
                            "修改 API Key 后，建议重新测试该密钥并重新读取模型",
                            fontSize = 10.sp,
                            color = DialogInkHint
                        )
                    }
                    error?.let {
                        Text(it, fontSize = 11.sp, color = Color(0xFFE5484D))
                    }
                }

                // 底部按钮
                HorizontalDivider(color = Color(0xFFEBEEF4))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E8EF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF70788A))
                    ) { Text("取消", fontSize = 12.sp) }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF6658E8), Color(0xFF8376F1)))
                            )
                            .clickable(enabled = canSave) { doSave() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (initial == null) "添加秘钥" else "保存",
                                color = if (canSave) Color.White else Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                null,
                                Modifier.size(14.dp),
                                tint = if (canSave) Color.White else Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddModelDialog(
    provider: Provider,
    key: ProviderKey,
    existing: List<AiModel>,
    modelPrompts: List<Prompt>,
    onDismiss: () -> Unit,
    onFetch: ((List<RemoteModelInfo>, String?) -> Unit) -> Unit,
    onSaveList: (List<AiModel>) -> Unit
) {
    var alias by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf(0.8f) }
    var maxTokens by remember { mutableStateOf(8192) }
    var unlimited by remember { mutableStateOf(false) }
    var maxContext by remember { mutableStateOf(20) }
    var contextWindow by remember { mutableStateOf(0) }
    var systemPrompt by remember { mutableStateOf("") }
    var reasoningEnabled by remember { mutableStateOf(true) }
    var reasoningLevel by remember { mutableStateOf(ReasoningLevel.DEFAULT.storage) }
    var webSearchEnabled by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<List<RemoteModelInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    // 「读取可选模型」下拉框是否展开
    var dropdownOpen by remember { mutableStateOf(false) }
    // 当前密钥下已存在的模型标识：读取列表里标记「已添加」，但不影响同供应商其他密钥
    val existingNames = remember(existing) { existing.map { it.name }.toSet() }

    fun buildModel(modelName: String, ctx: Int): AiModel = AiModel(
        providerId = provider.id,
        providerKeyId = key.id,
        name = modelName,
        alias = alias.trim(),
        temperature = temperature,
        maxTokens = if (unlimited) 0 else maxTokens,
        maxContext = maxContext,
        contextWindow = ctx,
        systemPrompt = systemPrompt,
        reasoningEnabled = reasoningEnabled,
        reasoningLevel = reasoningLevel,
        webSearchEnabled = webSearchEnabled,
        custom = false
    )

    fun doFetch() {
        if (loading) return
        loading = true
        error = null
        onFetch { list, err ->
            loading = false
            remote = list
            error = err
            selected = emptySet()
        }
    }

    fun doSave() {
        if (selected.isEmpty()) return
        onSaveList(
            remote.filter { it.id in selected }
                .map { buildModel(it.id, it.contextWindow) }
        )
    }

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
                .fillMaxHeight(0.92f)
                .padding(horizontal = 14.dp, vertical = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEBEEF4)),
            shadowElevation = 16.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                // ---------- 头部：图标 + 标题 + 厂商·密钥 ----------
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 6.dp, top = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(listOf(Color(0xFFEEE9FF), Color(0xFFE5E2FF))),
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(22.dp), tint = DialogPurple)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("添加模型", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text(
                            "${provider.name} · ${key.name}",
                            fontSize = 10.sp,
                            color = Color(0xFF8B93A5)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭", Modifier.size(18.dp), tint = Color(0xFF9AA1AF))
                    }
                }
                HorizontalDivider(color = Color(0xFFEBEEF4))

                // ---------- 内容区 ----------
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 模型信息
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DialogSectionTitle("模型信息")
                        DialogFieldLabel("读取可选模型")
                        // 纯下拉框：点击展开可选模型列表（多选），不可手动输入
                        DialogDropdownField(
                            value = selected.sorted().joinToString("、"),
                            placeholder = "请选择模型…",
                            expanded = dropdownOpen,
                            onToggle = {
                                val next = !dropdownOpen
                                dropdownOpen = next
                                if (next && remote.isEmpty() && !loading) doFetch()
                            }
                        )
                        if (dropdownOpen) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White)
                                    .border(1.dp, DialogFieldBorder, RoundedCornerShape(10.dp))
                                    .verticalScroll(rememberScrollState())
                            ) {
                                when {
                                    loading -> DropdownHintRow("正在读取可选模型…")
                                    error != null -> DropdownHintRow(error!!, onClick = { doFetch() })
                                    remote.isEmpty() -> DropdownHintRow("没有读取到模型，请检查密钥或稍后重试")
                                    else -> remote.forEachIndexed { index, r ->
                                        if (index > 0) HorizontalDivider(color = Color(0xFFEFF1F6))
                                        DialogDropdownRow(
                                            title = r.id,
                                            contextWindow = r.contextWindow,
                                            selected = r.id in selected,
                                            already = r.id in existingNames,
                                            // 已添加的模型也允许再次勾选，方便为同一个模型建多份不同参数的配置
                                            onClick = {
                                                selected = if (r.id in selected) selected - r.id else selected + r.id
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        DialogFieldLabel("显示名称")
                        DialogField(
                            value = alias,
                            onValueChange = { alias = it },
                            placeholder = "例如：DeepSeekV3.2（写作专用）"
                        )
                    }

                    // 生成参数
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DialogSectionTitle("生成参数")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("温度", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DialogInkLabel)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "%.2f".format(temperature),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = DialogPurple
                            )
                        }
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0f..2f,
                            thumb = {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .background(DialogPurple, CircleShape)
                                )
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = DialogPurple,
                                activeTrackColor = DialogPurple,
                                inactiveTrackColor = Color(0xFFE8EAF1)
                            )
                        )
                        DialogFieldLabel("最大输出 token")
                        DialogField(
                            value = if (unlimited) "" else maxTokens.toString(),
                            onValueChange = { text ->
                                maxTokens = text.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0
                            },
                            keyboardType = KeyboardType.Number,
                            placeholder = if (unlimited) "不限" else "8192",
                            trailing = { DialogUnlimitedToggle(unlimited) { unlimited = it } }
                        )
                        DialogFieldLabel("最大上下文条数")
                        DialogField(
                            value = if (maxContext <= 0) "" else maxContext.toString(),
                            onValueChange = { text ->
                                maxContext = text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                            },
                            keyboardType = KeyboardType.Number,
                            placeholder = if (maxContext <= 0) "不限" else "20",
                            trailing = {
                                DialogUnlimitedToggle(maxContext <= 0) { on ->
                                    maxContext = if (on) 0 else 20
                                }
                            }
                        )
                        DialogFieldLabel("模型最大上下文（tokens）")
                        DialogField(
                            value = if (contextWindow > 0) contextWindow.toString() else "",
                            onValueChange = { text ->
                                contextWindow = text.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
                            },
                            keyboardType = KeyboardType.Number,
                            placeholder = "例如：114514（留空自动读取，若无则取默认值）"
                        )
                    }

                    // 模型能力
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogSectionTitle("模型能力")
                        DialogToggleRow(
                            title = "思考模式",
                            desc = "启用模型的推理/思考能力",
                            checked = reasoningEnabled,
                            onCheckedChange = { reasoningEnabled = it }
                        )
                        if (reasoningEnabled) {
                            Text("思考强度", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DialogInkTitle)
                            DialogEffortSegments(ReasoningLevel.normalize(reasoningLevel)) {
                                reasoningLevel = it.storage
                            }
                        }
                        HorizontalDivider(color = Color(0xFFE8EAF1))
                        DialogToggleRow(
                            title = "联网搜索",
                            desc = "是否开启模型的联网搜索能力",
                            checked = webSearchEnabled,
                            onCheckedChange = { webSearchEnabled = it }
                        )
                    }

                    // 预设提示词
                    DialogSystemPrompt(
                        value = systemPrompt,
                        prompts = modelPrompts,
                        onValueChange = { systemPrompt = it }
                    )
                }

                // ---------- 底部按钮 ----------
                HorizontalDivider(color = Color(0xFFEBEEF4))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E8EF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF70788A))
                    ) { Text("取消", fontSize = 12.sp) }
                    val canSave = selected.isNotEmpty()
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF6658E8), Color(0xFF8376F1)))
                            )
                            .then(
                                if (canSave) Modifier.clickable { doSave() }
                                else Modifier.clickable(enabled = false) { }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (selected.isNotEmpty()) "添加选中 ${selected.size} 个" else "添加模型",
                                color = if (canSave) Color.White else Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                null,
                                Modifier.size(14.dp),
                                tint = if (canSave) Color.White else Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 「添加模型」弹窗配套样式组件（对齐设计稿） ----------------

private val DialogPurple = Color(0xFF6658E8)
private val DialogPurpleSoft = Color(0xFFF0EFFF)
private val DialogPurpleLine = Color(0xFFC8C2FF)
private val DialogInkTitle = Color(0xFF24283A)
private val DialogInkLabel = Color(0xFF70798C)
private val DialogInkHint = Color(0xFFA1A8B6)
private val DialogFieldBorder = Color(0xFFE6E9F0)

/** 分组标题：紫色小竖条 + 标题 */
@Composable
private fun DialogSectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 12.dp)
                .background(DialogPurple, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = DialogInkTitle)
    }
}

@Composable
private fun DialogFieldLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DialogInkLabel)
}

/** 设计稿风格的输入框：白底、10 圆角、1dp 描边，聚焦无额外描边 */
@Composable
private fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    multilineMinHeight: Dp = 120.dp,
    trailing: (@Composable () -> Unit)? = null
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        readOnly = readOnly,
        textStyle = TextStyle(fontSize = 12.sp, color = DialogInkTitle),
        cursorBrush = SolidColor(DialogPurple),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (singleLine) Modifier.height(39.dp) else Modifier.heightIn(min = multilineMinHeight))
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White)
                    .border(1.dp, DialogFieldBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 10.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
            ) {
                Box(
                    Modifier.weight(1f),
                    // 单行时让文字/光标与外层行同高居中；多行保持顶部对齐
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            fontSize = 11.sp,
                            color = DialogInkHint,
                            // 显式行高：避免继承 Material 默认的 24sp 行高导致占位文字撑高容器、光标贴顶
                            lineHeight = 16.sp,
                            maxLines = if (singleLine) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
                trailing?.invoke()
            }
        }
    )
}

/** 纯下拉框：只展示当前选择，整行点击展开 / 收起列表，不可输入文字 */
@Composable
private fun DialogDropdownField(
    value: String,
    placeholder: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(39.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(1.dp, DialogFieldBorder, RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = value.ifEmpty { placeholder },
            modifier = Modifier.weight(1f),
            fontSize = if (value.isEmpty()) 11.sp else 12.sp,
            color = if (value.isEmpty()) DialogInkHint else DialogInkTitle,
            lineHeight = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            if (expanded) "收起列表" else "展开列表",
            Modifier.size(20.dp),
            tint = DialogPurple
        )
    }
}

/** 下拉框内的一行模型：名称 + token + 已添加标签 + 勾选状态 */
@Composable
private fun DialogDropdownRow(
    title: String,
    contextWindow: Int,
    selected: Boolean,
    already: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 12.sp,
            color = if (selected) DialogPurple else DialogInkTitle,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (contextWindow > 0) {
            Text("${contextWindow} token", fontSize = 9.sp, color = DialogInkHint)
            Spacer(Modifier.width(6.dp))
        }
        if (already) {
            Text(
                "已添加",
                fontSize = 9.sp,
                color = DialogPurple,
                modifier = Modifier
                    .background(DialogPurpleSoft, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(6.dp))
        }
        if (selected) {
            Icon(Icons.Filled.Check, "已选择", Modifier.size(14.dp), tint = DialogPurple)
        }
    }
}

/** 下拉框内的提示行（加载中 / 失败可重试 / 空列表） */
@Composable
private fun DropdownHintRow(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        fontSize = 11.sp,
        color = DialogInkHint
    )
}

/** 「不限」小开关：圆圈图标 + 紫色文字 */
@Composable
private fun DialogUnlimitedToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!on) }
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (on) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            null,
            Modifier.size(14.dp),
            tint = DialogPurple
        )
        Spacer(Modifier.width(4.dp))
        Text("不限", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = DialogPurple)
    }
}

/** 开关行：左侧标题 + 说明，右侧紫色开关 */
@Composable
private fun DialogToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DialogInkTitle)
            Text(desc, fontSize = 10.sp, color = DialogInkHint)
        }
        DialogSwitchBox(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 设计稿风格开关：40×25 圆角轨道 + 白色圆点 */
@Composable
private fun DialogSwitchBox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(width = 40.dp, height = 25.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (checked) DialogPurple else Color(0xFFDFE3EB))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(17.dp).background(Color.White, CircleShape))
    }
}

/** 思考强度分段：低 / 中 / 高 / 极 */
@Composable
private fun DialogEffortSegments(current: ReasoningLevel, onPick: (ReasoningLevel) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReasoningLevel.ordered.forEach { lv ->
            val selected = lv == current
            Box(
                Modifier
                    .weight(1f)
                    .height(33.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) DialogPurpleSoft else Color.White)
                    .border(
                        1.dp,
                        if (selected) DialogPurpleLine else Color(0xFFE8EAF1),
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onPick(lv) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    lv.label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) DialogPurple else DialogInkHint
                )
            }
        }
    }
}

/** 预设提示词：标题行 + 提示词库入口 + 多行输入；逻辑与 SystemPromptEditor 一致 */
@Composable
private fun DialogSystemPrompt(
    value: String,
    prompts: List<Prompt>,
    onValueChange: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    var pendingTemplate by remember { mutableStateOf<Prompt?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        DialogSectionTitle("预设提示词")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("从提示词库中选填", fontSize = 11.sp, color = DialogInkHint)
            Spacer(Modifier.weight(1f))
            Text(
                "浏览提示词库",
                fontSize = 11.sp,
                color = DialogPurple,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showPicker = true }
                    .padding(vertical = 2.dp)
            )
        }
        DialogField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "留空则不注入系统提示词",
            singleLine = false
        )
    }

    if (showPicker) {
        PromptPickerDialog(
            prompts = prompts,
            onDismiss = { showPicker = false },
            onPick = { picked ->
                showPicker = false
                if (value.isBlank()) onValueChange(picked.content) else pendingTemplate = picked
            }
        )
    }

    pendingTemplate?.let { picked ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("已有预设提示词") },
            text = { Text("当前已填写 ${value.length} 个字，请选择如何处理「${picked.title}」：") },
            confirmButton = {
                Button(onClick = {
                    onValueChange(picked.content)
                    pendingTemplate = null
                }) { Text("替换") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingTemplate = null }) { Text("取消") }
                    TextButton(onClick = {
                        // 用空行分隔，避免原内容与模板直接粘连
                        onValueChange(value.trimEnd() + "\n\n" + picked.content)
                        pendingTemplate = null
                    }) { Text("追加") }
                }
            }
        )
    }
}

/** 模型详情：布局与「添加模型」保持一致，可修改显示名称、生成参数、能力与预设提示词 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDetailDialog(
    model: AiModel,
    modelPrompts: List<Prompt>,
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
    var contextWindow by remember { mutableStateOf(model.contextWindow) }
    var systemPrompt by remember { mutableStateOf(model.systemPrompt) }
    var reasoningEnabled by remember { mutableStateOf(model.reasoningEnabled) }
    var reasoningLevel by remember { mutableStateOf(model.reasoningLevel) }
    var webSearchEnabled by remember { mutableStateOf(model.webSearchEnabled) }
    val canSave = name.isNotBlank()

    fun doSave() {
        if (!canSave) return
        onSave(
            model.copy(
                name = name.trim(),
                alias = alias.trim(),
                temperature = temperature,
                maxTokens = if (unlimited) 0 else maxTokens,
                maxContext = maxContext,
                contextWindow = contextWindow,
                systemPrompt = systemPrompt,
                reasoningEnabled = reasoningEnabled,
                reasoningLevel = reasoningLevel,
                webSearchEnabled = webSearchEnabled
            )
        )
    }

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
                .fillMaxHeight(0.92f)
                .padding(horizontal = 14.dp, vertical = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEBEEF4)),
            shadowElevation = 16.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                // 头部：与「添加模型」一致的图标 + 标题 + 副标题
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 6.dp, top = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(
                                Brush.linearGradient(listOf(Color(0xFFEEE9FF), Color(0xFFE5E2FF))),
                                RoundedCornerShape(14.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(22.dp), tint = DialogPurple)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("模型设置", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        Text(
                            "${model.name} · ${if (model.custom) "自定义模型" else "厂商模型"}",
                            fontSize = 10.sp,
                            color = Color(0xFF8B93A5),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "关闭", Modifier.size(18.dp), tint = Color(0xFF9AA1AF))
                    }
                }
                HorizontalDivider(color = Color(0xFFEBEEF4))

                // 内容区
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // 模型信息
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DialogSectionTitle("模型信息")
                        DialogFieldLabel(if (model.custom) "模型（接口名）" else "模型（厂商提供，不可修改）")
                        DialogField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "模型接口名",
                            readOnly = !model.custom
                        )
                        DialogFieldLabel("显示名称")
                        DialogField(
                            value = alias,
                            onValueChange = { alias = it },
                            placeholder = "用于区分同类模型"
                        )
                    }

                    // 生成参数
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogSectionTitle("生成参数")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "输出温度",
                                Modifier.weight(1f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = DialogInkLabel
                            )
                            Text(
                                "%.2f".format(temperature),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = DialogPurple
                            )
                        }
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0f..2f,
                            thumb = {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .background(DialogPurple, CircleShape)
                                )
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = DialogPurple,
                                activeTrackColor = DialogPurple,
                                inactiveTrackColor = Color(0xFFE8EAF1)
                            )
                        )
                        DialogFieldLabel("最大输出 token")
                        DialogField(
                            value = if (unlimited) "" else maxTokens.toString(),
                            onValueChange = { text ->
                                maxTokens = text.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0
                            },
                            keyboardType = KeyboardType.Number,
                            placeholder = if (unlimited) "不限" else "8192",
                            trailing = { DialogUnlimitedToggle(unlimited) { unlimited = it } }
                        )
                        DialogFieldLabel("最大上下文条数")
                        DialogField(
                            value = if (maxContext <= 0) "" else maxContext.toString(),
                            onValueChange = { text ->
                                maxContext = text.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0
                            },
                            keyboardType = KeyboardType.Number,
                            placeholder = if (maxContext <= 0) "不限" else "20",
                            trailing = {
                                DialogUnlimitedToggle(maxContext <= 0) { on ->
                                    maxContext = if (on) 0 else 20
                                }
                            }
                        )
                        DialogFieldLabel("模型最大上下文（tokens）")
                        DialogField(
                            value = if (contextWindow > 0) contextWindow.toString() else "",
                            onValueChange = { text ->
                                contextWindow = text.filter { it.isDigit() }.take(9).toIntOrNull() ?: 0
                            },
                            keyboardType = KeyboardType.Number,
                            placeholder = "例如：114514（留空自动读取，若无则取默认值）"
                        )
                    }

                    // 模型能力
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DialogSectionTitle("模型能力")
                        DialogToggleRow(
                            title = "思考模式",
                            desc = "启用模型的推理/思考能力",
                            checked = reasoningEnabled,
                            onCheckedChange = { reasoningEnabled = it }
                        )
                        if (reasoningEnabled) {
                            Text("思考强度", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = DialogInkTitle)
                            DialogEffortSegments(ReasoningLevel.normalize(reasoningLevel)) {
                                reasoningLevel = it.storage
                            }
                        }
                        HorizontalDivider(color = Color(0xFFE8EAF1))
                        DialogToggleRow(
                            title = "联网搜索",
                            desc = "是否开启模型的联网搜索能力",
                            checked = webSearchEnabled,
                            onCheckedChange = { webSearchEnabled = it }
                        )
                    }

                    // 预设提示词
                    DialogSystemPrompt(
                        value = systemPrompt,
                        prompts = modelPrompts,
                        onValueChange = { systemPrompt = it }
                    )
                }

                // 底部按钮
                HorizontalDivider(color = Color(0xFFEBEEF4))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E8EF)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E8EF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF70788A))
                    ) { Text("取消", fontSize = 12.sp) }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF6658E8), Color(0xFF8376F1)))
                            )
                            .clickable(enabled = canSave) { doSave() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "保存",
                                color = if (canSave) Color.White else Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                null,
                                Modifier.size(14.dp),
                                tint = if (canSave) Color.White else Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 模型表单里的「预设提示词」编辑区：保留手写能力，额外提供模型提示词库入口。
 * 选择模板只是把内容写进表单状态，最终仍由模型自身的保存按钮落库。
 */
@Composable
private fun SystemPromptEditor(
    value: String,
    prompts: List<Prompt>,
    onValueChange: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    /** 已有内容时待确认的模板（替换 / 追加） */
    var pendingTemplate by remember { mutableStateOf<Prompt?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "预设提示词",
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge
        )
        TextButton(onClick = { showPicker = true }) {
            Icon(Icons.Outlined.Article, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("从提示词库选择")
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("留空则不注入系统提示词") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth()
    )

    if (showPicker) {
        PromptPickerDialog(
            prompts = prompts,
            onDismiss = { showPicker = false },
            onPick = { picked ->
                showPicker = false
                if (value.isBlank()) {
                    onValueChange(picked.content)
                } else {
                    pendingTemplate = picked
                }
            }
        )
    }

    pendingTemplate?.let { picked ->
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("已有预设提示词") },
            text = { Text("当前已填写 ${value.length} 个字，请选择如何处理「${picked.title}」：") },
            confirmButton = {
                Button(onClick = {
                    onValueChange(picked.content)
                    pendingTemplate = null
                }) { Text("替换") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingTemplate = null }) { Text("取消") }
                    TextButton(onClick = {
                        // 用空行分隔，避免原内容与模板直接粘连
                        onValueChange(value.trimEnd() + "\n\n" + picked.content)
                        pendingTemplate = null
                    }) { Text("追加") }
                }
            }
        )
    }
}

/** 模型提示词选择器：只展示 MODEL 类型，点选即回传；用独立窗口保证浮在模型弹窗之上 */
@Composable
private fun PromptPickerDialog(
    prompts: List<Prompt>,
    onDismiss: () -> Unit,
    onPick: (Prompt) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("从提示词库选择", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                if (prompts.isEmpty()) {
                    Text(
                        "还没有模型提示词。可到「设置 → 提示词库 → 模型提示词」新建。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        prompts.forEach { p ->
                            Surface(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(p) },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        p.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        p.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun PromptDialog(
    initial: Prompt?,
    type: PromptType,
    onDismiss: () -> Unit,
    onSave: (Prompt) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    val typeLabel = if (type == PromptType.MODEL) "模型提示词" else "快捷提示词"
    val canSave = title.isNotBlank() && content.isNotBlank()

    fun doSave() {
        if (!canSave) return
        // 新建按当前分类落类型；编辑沿用原记录的类型，绝不因入口变化而改类型
        onSave(
            (initial ?: Prompt(title = title, content = content, type = type))
                .copy(title = title.trim(), content = content)
        )
    }

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
                .padding(horizontal = 16.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { },
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Color(0xFFEBEEF4)),
            shadowElevation = 16.dp
        ) {
            Column {
                // 头部：提示词图标 + 标题 + 说明
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 15.dp, end = 16.dp, top = 16.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painterResource(R.drawable.ic_prompt_pen),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (initial == null) "新建$typeLabel" else "编辑$typeLabel",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = DialogInkTitle
                        )
                        Text(
                            if (initial == null) "创建一条可重复使用的提示词内容" else "修改这条提示词的标题与内容",
                            fontSize = 10.sp,
                            color = Color(0xFF8B93A5)
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFFEBEEF4))

                // 内容区
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 17.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 设计稿中的分组标题：紫色竖条 +「提示词内容」
                    DialogSectionTitle("提示词内容")
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DialogFieldLabel("提示词标题")
                        DialogField(
                            value = title,
                            onValueChange = { title = it },
                            placeholder = "例如：心理描写"
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        DialogFieldLabel("提示词内容")
                        DialogField(
                            value = content,
                            onValueChange = { content = it },
                            placeholder = "例如：要求仔细描写人物心理与动作",
                            singleLine = false,
                            multilineMinHeight = 193.dp
                        )
                    }
                }

                // 底部按钮
                HorizontalDivider(color = Color(0xFFEBEEF4))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(13.dp),
                        border = BorderStroke(1.dp, Color(0xFFE5E8EF)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF70788A))
                    ) { Text("取消", fontSize = 12.sp) }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF6658E8), Color(0xFF8376F1)))
                            )
                            .clickable(enabled = canSave) { doSave() },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (initial == null) "添加提示词" else "保存",
                                color = if (canSave) Color.White else Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                null,
                                Modifier.size(14.dp),
                                tint = if (canSave) Color.White else Color.White.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }
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

/** 备份 / 恢复弹窗里的一行分区复选项 */
@Composable
private fun BackupSectionRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 思考模式：开关 + 思考强度档位（低 / 中 / 高 / 极）；关闭即关闭模型的思考/推理 */
@Composable
private fun ReasoningSetting(
    enabled: Boolean,
    level: String,
    onEnabledChange: (Boolean) -> Unit,
    onLevelChange: (String) -> Unit
) {
    val current = ReasoningLevel.normalize(level)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("思考模式", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
    Text(
        if (enabled) "已开启：模型先思考再作答，可调思考强度"
        else "已关闭：模型直接作答，不做思考/推理",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (enabled) {
        Spacer(Modifier.height(8.dp))
        Text("思考强度：${current.label}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReasoningLevel.ordered.forEach { lv ->
                FilterChip(
                    selected = lv == current,
                    onClick = { onLevelChange(lv.storage) },
                    label = { Text(lv.label) }
                )
            }
        }
        Text(
            "低 → 极：数值越大，模型思考越充分（不同模型支持的最高强度不同，超出部分会自动取该模型的最大值）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
