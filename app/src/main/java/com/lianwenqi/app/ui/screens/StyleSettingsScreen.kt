package com.lianwenqi.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.StyleProfileStyleStats
import com.lianwenqi.app.data.StyleSampleStyleStats
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.StyleProfile
import com.lianwenqi.app.data.entity.StyleSampleRecord
import com.lianwenqi.app.data.style.StyleAnalysis
import com.lianwenqi.app.data.style.StyleAnalysisDraft
import com.lianwenqi.app.data.style.StyleAnalysisOutcome
import com.lianwenqi.app.data.style.StyleEvidence
import com.lianwenqi.app.data.style.StyleExtractResult
import com.lianwenqi.app.data.style.StyleLibraryState
import com.lianwenqi.app.data.style.StyleProfileRow
import com.lianwenqi.app.data.style.StyleProfileSummaryReader
import com.lianwenqi.app.data.style.StyleSample
import com.lianwenqi.app.data.style.StyleSampleInput
import com.lianwenqi.app.data.style.StyleSampleSampler
import com.lianwenqi.app.data.style.StyleSampleStatus
import com.lianwenqi.app.data.style.StyleSaveResult
import com.lianwenqi.app.data.style.StyleTextExtractor
import com.lianwenqi.app.data.style.detectStyleSampleFormat
import com.lianwenqi.app.data.style.renumbered
import com.lianwenqi.app.data.style.toAnalysisInput
import com.lianwenqi.app.ui.appViewModel
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 文风分析的操作状态：区分未就绪 / 待发起 / 分析中 / 成功待保存 / 分析与保存失败 */
sealed interface StyleAnalysisPhase {
    /** 尚未发起，或样本变更后结果被清空 */
    object Idle : StyleAnalysisPhase
    object Analyzing : StyleAnalysisPhase
    /** 调用失败（网络 / 鉴权 / 超时 / 空响应等） */
    data class Failed(val message: String) : StyleAnalysisPhase
    /** 模型有输出但不符合结构化协议 */
    data class InvalidFormat(val message: String) : StyleAnalysisPhase
    /** 分析成功，等待用户命名并确认保存 */
    object ReadyToSave : StyleAnalysisPhase
    object Saving : StyleAnalysisPhase
    data class SaveFailed(val message: String) : StyleAnalysisPhase
    data class Saved(val name: String) : StyleAnalysisPhase
}

/**
 * 文风样本导入与文风分析：文件选择、逐个解析、状态与预览，以及把样本交给所选模型分析。
 *
 * 样本只保存在页面内存（随 ViewModel 销毁而释放），不落库、不进入书籍正文 / 记忆向量。
 * 只有用户明确点击「保存档案」后，分析结果才会写入 F1 的文风档案数据层。
 * 解析用临时文件在解析结束时立即删除。
 */
class StyleImportViewModel(container: AppContainer) : ViewModel() {

    private val extractor = StyleTextExtractor(container.context)
    private val analysisService = container.styleAnalysisService
    private val profileSaver = container.styleProfileSaver
    private val configRepository = container.configRepository
    private val prefs = container.prefs

    private val _samples = MutableStateFlow<List<StyleSample>>(emptyList())
    val samples: StateFlow<List<StyleSample>> = _samples.asStateFlow()

    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()

    private val _currentFileName = MutableStateFlow<String?>(null)
    val currentFileName: StateFlow<String?> = _currentFileName.asStateFlow()

    /** 可选的文本生成模型（复用项目现有模型配置） */
    val models: StateFlow<List<AiModel>> = configRepository.observeModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val providers: StateFlow<List<Provider>> = configRepository.observeProviders()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedModelId = MutableStateFlow(0L)
    val selectedModelId: StateFlow<Long> = _selectedModelId.asStateFlow()

    private val _analysisPhase = MutableStateFlow<StyleAnalysisPhase>(StyleAnalysisPhase.Idle)
    val analysisPhase: StateFlow<StyleAnalysisPhase> = _analysisPhase.asStateFlow()

    /** 已通过校验、等待保存的分析草稿；保存失败时保留，重试保存无需重新调用模型 */
    private val _analysisDraft = MutableStateFlow<StyleAnalysisDraft?>(null)
    val analysisDraft: StateFlow<StyleAnalysisDraft?> = _analysisDraft.asStateFlow()

    private val _profileName = MutableStateFlow("")
    val profileName: StateFlow<String> = _profileName.asStateFlow()

    private var worker: Job? = null
    private var analysisJob: Job? = null

    /** 任务代次：取消 / 重新发起后，旧协程的结果不得覆盖新状态 */
    private var analysisGeneration = 0

    init {
        // 清理上次异常退出可能遗留的解析临时文件
        viewModelScope.launch(Dispatchers.IO) { extractor.cleanStaleTemp() }
        // 默认选中当前正在使用的模型；没有则用第一个模型
        viewModelScope.launch {
            val active = prefs.activeModelId.first()
            val all = configRepository.getModels()
            _selectedModelId.value =
                all.firstOrNull { it.id == active }?.id ?: all.firstOrNull()?.id ?: 0L
        }
    }

    /** 追加选择的文件；选择器返回的顺序即处理顺序（列表内连续编号） */
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // 样本集合变化后，之前的分析结果不再对应这批样本
        clearAnalysis()
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val name = extractor.displayName(uri)
                    StyleSample(
                        key = UUID.randomUUID().toString(),
                        uri = uri.toString(),
                        fileName = name,
                        format = detectStyleSampleFormat(name, extractor.mimeType(uri)),
                        order = 0
                    )
                }
            }
            _samples.update { current -> (current + prepared).renumbered() }
            startWorker()
        }
    }

    /** 移除样本（失败 / 过短 / 不需要的都可在重新导入前移除） */
    fun remove(key: String) {
        clearAnalysis()
        _samples.update { list -> list.filterNot { it.key == key }.renumbered() }
    }

    /** 交给 F4 的输入边界：按处理顺序排列、仅包含可用样本 */
    fun analysisInput(): List<StyleSampleInput> = _samples.value.toAnalysisInput()

    // ---------------- 文风分析 ----------------

    fun setModel(id: Long) {
        if (_analysisPhase.value == StyleAnalysisPhase.Analyzing) return
        _selectedModelId.value = id
    }

    fun updateProfileName(value: String) {
        _profileName.value = value
    }

    /** 发起分析：失败时保留样本；分析中重复点击不会重复发起请求 */
    fun startAnalysis() {
        val phase = _analysisPhase.value
        if (phase == StyleAnalysisPhase.Analyzing || phase == StyleAnalysisPhase.Saving) return
        if (_processing.value) {
            _analysisPhase.value = StyleAnalysisPhase.Failed("样本仍在解析中，请稍候再试")
            return
        }
        val input = analysisInput()
        if (input.isEmpty()) {
            _analysisPhase.value = StyleAnalysisPhase.Failed("没有可用的样本，请先导入并等待解析完成")
            return
        }
        val modelId = _selectedModelId.value
        if (modelId <= 0L) {
            _analysisPhase.value = StyleAnalysisPhase.Failed("请先选择用于分析的文本生成模型")
            return
        }

        val generation = ++analysisGeneration
        _analysisDraft.value = null
        _profileName.value = ""
        _analysisPhase.value = StyleAnalysisPhase.Analyzing
        analysisJob = viewModelScope.launch {
            try {
                when (val outcome = analysisService.analyze(modelId, input)) {
                    is StyleAnalysisOutcome.Success -> {
                        if (generation != analysisGeneration) return@launch
                        _analysisDraft.value = outcome.draft
                        _profileName.value = defaultProfileName()
                        _analysisPhase.value = StyleAnalysisPhase.ReadyToSave
                    }
                    is StyleAnalysisOutcome.Failed -> {
                        if (generation != analysisGeneration) return@launch
                        _analysisPhase.value = StyleAnalysisPhase.Failed(outcome.message)
                    }
                    is StyleAnalysisOutcome.InvalidResult -> {
                        if (generation != analysisGeneration) return@launch
                        _analysisPhase.value = StyleAnalysisPhase.InvalidFormat(outcome.message)
                    }
                }
            } catch (e: CancellationException) {
                if (generation == analysisGeneration) {
                    _analysisPhase.value = StyleAnalysisPhase.Idle
                }
            } catch (e: Exception) {
                if (generation == analysisGeneration) {
                    _analysisPhase.value =
                        StyleAnalysisPhase.Failed("分析失败：${e.message ?: "未知错误"}")
                }
            } finally {
                if (generation == analysisGeneration) analysisJob = null
            }
        }
    }

    /** 取消分析中请求；已成功的草稿与样本保持不变 */
    fun cancelAnalysis() {
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        if (_analysisPhase.value == StyleAnalysisPhase.Analyzing) {
            _analysisPhase.value = StyleAnalysisPhase.Idle
        }
    }

    /** 保存分析草稿：只调用已有 Repository 写入；重试保存不会重新调用模型 */
    fun saveProfile() {
        val draft = _analysisDraft.value ?: return
        if (_analysisPhase.value == StyleAnalysisPhase.Saving) return
        val name = _profileName.value.trim()
        if (name.isEmpty()) {
            _analysisPhase.value = StyleAnalysisPhase.SaveFailed("请填写文风档案名称")
            return
        }
        _analysisPhase.value = StyleAnalysisPhase.Saving
        viewModelScope.launch {
            when (val result = profileSaver.save(name, draft)) {
                is StyleSaveResult.Success -> {
                    _analysisPhase.value = StyleAnalysisPhase.Saved(result.profile.name)
                }
                StyleSaveResult.DuplicateName -> {
                    _analysisPhase.value =
                        StyleAnalysisPhase.SaveFailed("已存在同名文风档案，请修改名称后重试")
                }
                is StyleSaveResult.Failure -> {
                    _analysisPhase.value = StyleAnalysisPhase.SaveFailed(result.message)
                }
            }
        }
    }

    /** 清空分析结果（样本保留），用于「重新分析 / 继续分析」 */
    fun resetAnalysis() = clearAnalysis()

    private fun clearAnalysis() {
        analysisGeneration++
        analysisJob?.cancel()
        analysisJob = null
        _analysisDraft.value = null
        _profileName.value = ""
        _analysisPhase.value = StyleAnalysisPhase.Idle
    }

    private fun defaultProfileName(): String {
        val base = _samples.value.firstOrNull()?.fileName?.substringBeforeLast('.')?.trim().orEmpty()
        return if (base.isBlank()) {
            "文风档案"
        } else {
            "${base.take((StyleProfile.MAX_NAME_LENGTH - 3).coerceAtLeast(1))}·文风"
        }
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = viewModelScope.launch {
            _processing.value = true
            try {
                while (true) {
                    // 每轮重新读取，兼容处理过程中继续追加的样本
                    val next = _samples.value.firstOrNull { it.status == StyleSampleStatus.PENDING }
                        ?: break
                    markParsing(next.key)
                    _currentFileName.value = next.fileName
                    applyResult(next.key, extractor.extract(Uri.parse(next.uri), next.fileName))
                }
            } finally {
                _currentFileName.value = null
                _processing.value = false
            }
        }
    }

    private fun markParsing(key: String) {
        _samples.update { list ->
            list.map { if (it.key == key) it.copy(status = StyleSampleStatus.PARSING, error = null) else it }
        }
    }

    private fun applyResult(key: String, result: StyleExtractResult) {
        _samples.update { list ->
            // 解析期间被移除的样本不再回写
            if (list.none { it.key == key }) return@update list
            list.map { sample ->
                if (sample.key != key) sample
                else when (result) {
                    is StyleExtractResult.Success -> sample.copy(
                        status = StyleSampleStatus.SUCCESS,
                        text = result.text,
                        charCount = result.text.length,
                        error = null
                    )
                    is StyleExtractResult.Failure -> sample.copy(
                        status = StyleSampleStatus.FAILED,
                        text = "",
                        charCount = 0,
                        error = result.message
                    )
                }
            }
        }
    }
}

/**
 * 文风库列表：数据来自 F1 的持久化档案；Room 数据变化（新建 / 编辑 / 删除）后自动刷新。
 * 读取失败时降级为错误状态，可手动重试。
 */
class StyleLibraryViewModel(container: AppContainer) : ViewModel() {

    private val styleRepository = container.styleRepository
    private val libraryRetry = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val library: StateFlow<StyleLibraryState> = libraryRetry
        .flatMapLatest {
            styleRepository.observeProfiles()
                .map { profiles ->
                    StyleLibraryState(
                        loading = false,
                        rows = profiles.map { profile ->
                            StyleProfileRow(profile, StyleProfileSummaryReader.read(profile))
                        }
                    )
                }
                // 放在内层：读取失败不终止外层重试流，点「重新读取」仍会重新查询数据库
                .catch { error ->
                    emit(
                        StyleLibraryState(
                            loading = false,
                            error = "读取文风档案失败：${error.message ?: "未知错误"}"
                        )
                    )
                }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, StyleLibraryState())

    /** 重新订阅档案列表（读取失败时由用户手动重试） */
    fun retry() {
        libraryRetry.value++
    }
}

/** 文风中心的三个一级页签 */
private enum class StyleTab(val label: String) {
    Profiles("档案"),
    Samples("样本"),
    Tags("标签")
}

/** 样本导入与管理共用同一个档案选择弹窗 */
private enum class StyleProfileAction { Manage }

/**
 * 文风中心：档案 / 样本 / 标签三个页签。
 *
 * 仅聚合管理与导航入口；新建档案进入独立页面 [StyleNewProfileScreen]，
 * 档案详情、档案样式、样本样式、标签库与样本管理继续复用各自原有页面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSettingsScreen(
    onBack: () -> Unit,
    onCreateProfile: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onOpenProfileStyle: (Long) -> Unit,
    onOpenSampleStyle: (Long) -> Unit,
    onOpenGlobalTags: () -> Unit,
    onOpenSamples: (Long) -> Unit
) {
    val libraryVm = appViewModel { StyleLibraryViewModel(it) }
    val stylesVm = appViewModel { StyleStylesViewModel(it) }
    val library by libraryVm.library.collectAsState()
    val profiles by stylesVm.profiles.collectAsState()
    val samples by stylesVm.samples.collectAsState()
    val profileStats by stylesVm.profileStats.collectAsState()
    val sampleStats by stylesVm.sampleStats.collectAsState()
    val busy by stylesVm.busy.collectAsState()
    val message by stylesVm.message.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(StyleTab.Profiles.ordinal) }
    var profileAction by remember { mutableStateOf<StyleProfileAction?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var bindingSample by remember { mutableStateOf<StyleSampleRecord?>(null) }

    // 导入样本不再强制先选档案：直接选文件或粘贴，导入后默认未绑定
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) stylesVm.importFromUri(uri)
    }

    LaunchedEffect(profiles, samples) { stylesVm.refresh(profiles, samples) }

    // 选择文件导入成功后自动关闭导入弹窗（粘贴路径在点击时已关闭），失败时保留弹窗方便重试
    LaunchedEffect(message) {
        if (message?.startsWith("已导入样本") == true) showImport = false
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("文风") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                )
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    StyleTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(tab.label) }
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (StyleTab.entries.getOrElse(selectedTab) { StyleTab.Profiles }) {
                StyleTab.Profiles -> StyleProfilesTab(
                    library = library,
                    onCreate = onCreateProfile,
                    onRetry = libraryVm::retry,
                    onOpenProfile = onOpenProfile,
                    onOpenProfileStyle = onOpenProfileStyle
                )

                StyleTab.Samples -> StyleSamplesTab(
                    profiles = profiles,
                    samples = samples,
                    sampleStats = sampleStats,
                    onImport = { showImport = true },
                    onManage = { profileAction = StyleProfileAction.Manage },
                    onOpenSampleStyle = onOpenSampleStyle,
                    onBindSample = { bindingSample = it }
                )

                StyleTab.Tags -> StyleTagsTab(
                    profiles = profiles,
                    profileStats = profileStats,
                    onOpenGlobalTags = onOpenGlobalTags,
                    onOpenProfileStyle = onOpenProfileStyle
                )
            }
        }
    }

    if (showImport) {
        ImportSampleDialog(
            busy = busy,
            onPickFile = { picker.launch(arrayOf("*/*")) },
            onPaste = { name, text -> stylesVm.importText(name, text) },
            onDismiss = { showImport = false }
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = stylesVm::dismissMessage,
            title = { Text("操作提示") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = stylesVm::dismissMessage) { Text("知道了") } }
        )
    }

    profileAction?.let { action ->
        StyleProfileActionDialog(
            title = "管理档案样本",
            subtitle = "进入所选档案的样本管理：导入、绑定 / 解绑、标签分析与片段预览。",
            profiles = profiles,
            onPick = { profileId ->
                profileAction = null
                onOpenSamples(profileId)
            },
            onDismiss = { profileAction = null }
        )
    }

    bindingSample?.let { sample ->
        StyleBindProfileDialog(
            profiles = profiles,
            currentProfileId = sample.styleProfileId,
            onPick = { profileId ->
                bindingSample = null
                stylesVm.bindSample(sample.id, profileId)
            },
            onDismiss = { bindingSample = null }
        )
    }
}

/** 新建文风档案：只承载样本导入与文风分析，保存成功后返回档案列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleNewProfileScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val vm = appViewModel { StyleImportViewModel(it) }
    val samples by vm.samples.collectAsState()
    val processing by vm.processing.collectAsState()
    val currentName by vm.currentFileName.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addUris(uris) }

    var previewKey by remember { mutableStateOf<String?>(null) }
    val previewSample = samples.firstOrNull { it.key == previewKey }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建文风档案") },
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
            StyleAnalysisSection(
                vm = vm,
                samples = samples,
                processing = processing,
                currentName = currentName,
                onImport = { picker.launch(arrayOf("*/*")) },
                onPreview = { previewKey = it },
                onRemove = vm::remove,
                onBackToList = onDone
            )
        }
    }

    previewSample?.let { sample ->
        StylePreviewDialog(sample = sample, onDismiss = { previewKey = null })
    }
}

/** 档案页签：新建入口 + 真实档案列表（主体进详情，样式概览进样式页） */
@Composable
private fun StyleProfilesTab(
    library: StyleLibraryState,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
    onOpenProfile: (Long) -> Unit,
    onOpenProfileStyle: (Long) -> Unit
) {
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("新建文风档案")
    }
    Spacer(Modifier.height(14.dp))

    when {
        library.loading -> StyleLoadingRow("正在读取文风档案…")

        library.error != null -> {
            Text(
                library.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) { Text("重新读取") }
        }

        library.rows.isEmpty() -> StyleLibraryEmptyState(onCreate = onCreate)

        else -> library.rows.forEach { row ->
            StyleProfileCard(
                row = row,
                onClick = { onOpenProfile(row.profile.id) },
                onOpenStyle = { onOpenProfileStyle(row.profile.id) }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 样本页签：导入入口 + 全部真实样本列表 + 按档案管理入口 */
@Composable
private fun StyleSamplesTab(
    profiles: List<StyleProfile>,
    samples: List<StyleSampleRecord>,
    sampleStats: Map<Long, StyleSampleStyleStats>,
    onImport: () -> Unit,
    onManage: () -> Unit,
    onOpenSampleStyle: (Long) -> Unit,
    onBindSample: (StyleSampleRecord) -> Unit
) {
    val profileNames = remember(profiles) { profiles.associate { it.id to it.name } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("文风样本", style = MaterialTheme.typography.titleMedium)
            Text(
                "${samples.size} 个样本 · 未绑定样本不参与检索",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(onClick = onImport) { Text("导入样本") }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "导入时不强制绑定文风档案：选择 txt 或直接粘贴即可，导入后显示为「未绑定 / 未分析」。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    if (samples.isEmpty()) {
        StyleStylesEmptyHint("暂无文风样本。导入样本后会显示在这里。")
    } else {
        samples.forEach { sample ->
            StyleSampleStyleCard(
                sample = sample,
                profileName = sample.styleProfileId?.let { profileNames[it] },
                stats = sampleStats[sample.id],
                onClick = { onOpenSampleStyle(sample.id) },
                onBindClick = { onBindSample(sample) }
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    Spacer(Modifier.height(16.dp))
    StyleStylesEntryCard(
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        title = "管理档案样本",
        subtitle = "导入、绑定 / 解绑、标签分析与片段预览",
        onClick = onManage
    )
}

/** 标签页签：全局标签库入口 + 档案专属标签与样本样式概览 */
@Composable
private fun StyleTagsTab(
    profiles: List<StyleProfile>,
    profileStats: Map<Long, StyleProfileStyleStats>,
    onOpenGlobalTags: () -> Unit,
    onOpenProfileStyle: (Long) -> Unit
) {
    Text("全局标签库", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    StyleStylesEntryCard(
        icon = {
            Icon(
                Icons.AutoMirrored.Filled.Label,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        title = "查看全局标签库",
        subtitle = "标签由你维护，AI 只读取使用",
        onClick = onOpenGlobalTags
    )

    Spacer(Modifier.height(24.dp))
    HorizontalDivider()
    Spacer(Modifier.height(16.dp))

    Text("档案标签与样式", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "查看各档案的专属标签体系，以及这些标签在文风样本中的使用情况。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(10.dp))

    if (profiles.isEmpty()) {
        StyleStylesEmptyHint("暂无文风档案。请先在「档案」页签新建档案。")
    } else {
        profiles.forEach { profile ->
            StyleProfileStyleCard(
                profile = profile,
                stats = profileStats[profile.id],
                onClick = { onOpenProfileStyle(profile.id) }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StyleLoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 导入 / 管理样本前的档案选择弹窗；沿用原有交互，不新增流程 */
@Composable
private fun StyleProfileActionDialog(
    title: String,
    subtitle: String,
    profiles: List<StyleProfile>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                profiles.forEach { profile ->
                    TextButton(
                        onClick = { onPick(profile.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            profile.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 单个档案条目：名称、概述、来源与更新时间；无法识别的分析结果只显示「暂无概述」 */
@Composable
private fun StyleProfileCard(
    row: StyleProfileRow,
    onClick: () -> Unit,
    onOpenStyle: () -> Unit
) {
    val summary = row.summary
    val profile = row.profile
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                profile.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                summary.overview ?: "暂无概述",
                style = MaterialTheme.typography.bodySmall,
                color = if (summary.overview == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            val meta = listOfNotNull(
                summary.sourceLabel,
                summary.modelName.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "更新于 ${formatStyleTimestamp(profile.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!summary.recognizable) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "分析结果无法识别，仅可查看原始数据",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onOpenStyle,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp
                    )
                ) {
                    Text("样式概览", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** 文风库空状态：入口直接复用样本导入 + 文风分析流程 */
@Composable
private fun StyleLibraryEmptyState(onCreate: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("还没有文风档案", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "导入书籍或章节样本，完成文风分析并保存后，档案会显示在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入样本并分析")
            }
        }
    }
}

/** 文风样本空状态：可直接打开系统文件选择器导入样本 */
@Composable
private fun StyleSampleEmptyState(onImport: () -> Unit) {
    Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("导入样本")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "支持 TXT、EPUB、DOCX；解析结果仅用于预览，不会上传",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** 文风分析：样本导入 / 解析 / 预览，模型选择、发起 / 取消、结果预览、命名与确认保存 */
@Composable
private fun StyleAnalysisSection(
    vm: StyleImportViewModel,
    samples: List<StyleSample>,
    processing: Boolean,
    currentName: String?,
    onImport: () -> Unit,
    onPreview: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBackToList: () -> Unit
) {
    val models by vm.models.collectAsState()
    val providers by vm.providers.collectAsState()
    val selectedModelId by vm.selectedModelId.collectAsState()
    val phase by vm.analysisPhase.collectAsState()
    val draft by vm.analysisDraft.collectAsState()
    val profileName by vm.profileName.collectAsState()
    var showDetail by remember { mutableStateOf(false) }

    val usable = samples.filter { it.isUsable }
    val usableChars = usable.sumOf { it.charCount }

    Text("文风分析", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "选择文本生成模型，分析已导入的样本并生成结构化文风档案。样本文本只在本次请求中提交，不会写入档案。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "分析模型",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        ModelSelectorChip(
            models = models,
            providers = providers,
            activeId = selectedModelId,
            maxLabelWidth = 150.dp,
            onSelect = vm::setModel
        )
    }
    if (models.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "尚未添加文本生成模型，请先在 设置 → 模型 中添加并填写 API Key。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(
        "可用样本 ${usable.size} 个 · 共 $usableChars 字符；单次最多提交 " +
            "${StyleSampleSampler.DEFAULT_BUDGET_CHARS} 字符，超出时跨样本均匀节选开头 / 中段 / 结尾。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    if (samples.isEmpty()) {
        StyleSampleEmptyState(onImport = onImport)
    } else {
        StyleSampleSummary(
            samples = samples,
            processing = processing,
            currentName = currentName
        )
        Spacer(Modifier.height(10.dp))
        samples.forEach { sample ->
            StyleSampleCard(
                sample = sample,
                onPreview = { onPreview(sample.key) },
                onRemove = { onRemove(sample.key) }
            )
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = onImport,
            enabled = !processing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (processing) "正在解析，请稍候" else "添加文件")
        }
    }

    Spacer(Modifier.height(20.dp))

    when (phase) {
        StyleAnalysisPhase.Idle -> {
            StartAnalysisButton(
                enabled = usable.isNotEmpty() && selectedModelId > 0 && !processing,
                label = "开始分析",
                onClick = vm::startAnalysis
            )
        }

        is StyleAnalysisPhase.Failed -> {
            val current = phase as StyleAnalysisPhase.Failed
            ErrorText(current.message)
            StartAnalysisButton(
                enabled = usable.isNotEmpty() && selectedModelId > 0 && !processing,
                label = "重新分析",
                onClick = vm::startAnalysis
            )
        }

        is StyleAnalysisPhase.InvalidFormat -> {
            val current = phase as StyleAnalysisPhase.InvalidFormat
            ErrorText("分析结果格式不合法：${current.message}")
            StartAnalysisButton(
                enabled = usable.isNotEmpty() && selectedModelId > 0 && !processing,
                label = "重新分析",
                onClick = vm::startAnalysis
            )
        }

        StyleAnalysisPhase.Analyzing -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "正在分析，请稍候…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = vm::cancelAnalysis) { Text("取消") }
            }
        }

        StyleAnalysisPhase.ReadyToSave,
        StyleAnalysisPhase.Saving,
        is StyleAnalysisPhase.SaveFailed -> {
            draft?.let { currentDraft ->
                val saving = phase == StyleAnalysisPhase.Saving
                StyleAnalysisResultSummary(
                    draft = currentDraft,
                    onShowDetail = { showDetail = true }
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = profileName,
                    onValueChange = vm::updateProfileName,
                    label = { Text("档案名称") },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth()
                )
                if (phase is StyleAnalysisPhase.SaveFailed) {
                    Spacer(Modifier.height(4.dp))
                    ErrorText((phase as StyleAnalysisPhase.SaveFailed).message)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::resetAnalysis, enabled = !saving) {
                        Text("重新分析")
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = vm::saveProfile, enabled = !saving) {
                        Text(
                            when (phase) {
                                StyleAnalysisPhase.Saving -> "正在保存…"
                                is StyleAnalysisPhase.SaveFailed -> "重试保存"
                                else -> "保存档案"
                            }
                        )
                    }
                }
                Text(
                    "确认保存后才会写入文风档案；未保存的分析结果只存在于当前页面。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        is StyleAnalysisPhase.Saved -> {
            val current = phase as StyleAnalysisPhase.Saved
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("已保存文风档案", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "「${current.name}」已写入文风档案，可在「档案」页签中查看、编辑或删除。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = vm::resetAnalysis) { Text("继续分析") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onBackToList) { Text("返回档案列表") }
            }
        }
    }

    if (showDetail) {
        draft?.let { currentDraft ->
            StyleAnalysisDetailDialog(
                analysis = currentDraft.analysis,
                onDismiss = { showDetail = false }
            )
        }
    }
}

@Composable
private fun StartAnalysisButton(enabled: Boolean, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(Modifier.height(8.dp))
}

/** 分析成功后的摘要卡片：整体风格 + 实际分析范围 + 完整结果入口 */
@Composable
private fun StyleAnalysisResultSummary(draft: StyleAnalysisDraft, onShowDetail: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                "分析完成 · ${draft.modelName}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                draft.analysis.overview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "本次分析 ${draft.sampling.analyzedSampleCount} 个样本 / " +
                    "${draft.sampling.includedChars} 字符（${draft.sampling.strategy.label}）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(
                onClick = onShowDetail,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp, vertical = 0.dp
                )
            ) {
                Text("查看完整分析", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 完整分析结果：十个维度 + 证据与可信度 */
@Composable
private fun StyleAnalysisDetailDialog(analysis: StyleAnalysis, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文风分析结果") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("整体风格", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(analysis.overview, style = MaterialTheme.typography.bodySmall)
                analysisSections(analysis).forEach { section ->
                    Spacer(Modifier.height(10.dp))
                    Text(section.label, style = MaterialTheme.typography.titleSmall)
                    section.items.forEach { item ->
                        Text("· $item", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("证据与可信度", style = MaterialTheme.typography.titleSmall)
                analysis.evidenceAndConfidence.forEach { evidence ->
                    Text(
                        "· ${analysisDimensionLabel(evidence.dimension)}｜" +
                            "${analysisEvidenceConfidenceLabel(evidence)}：${evidence.evidence}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

internal data class AnalysisSection(val label: String, val items: List<String>)

internal fun analysisSections(analysis: StyleAnalysis): List<AnalysisSection> = listOf(
    AnalysisSection("叙述方式", analysis.narration),
    AnalysisSection("句式与节奏", analysis.sentenceRhythm),
    AnalysisSection("词汇与表达", analysis.diction),
    AnalysisSection("描写方法", analysis.description),
    AnalysisSection("段落组织", analysis.paragraphStructure),
    AnalysisSection("对话特征", analysis.dialogue),
    AnalysisSection("情绪表达", analysis.emotion),
    AnalysisSection("场景与叙事衔接", analysis.transition),
    AnalysisSection("可执行的仿写指导", analysis.writingGuidelines),
    AnalysisSection("应避免的写法", analysis.avoidances)
)

internal fun analysisDimensionLabel(dimension: String): String = when (dimension) {
    "narration" -> "叙述方式"
    "sentenceRhythm" -> "句式与节奏"
    "diction" -> "词汇与表达"
    "description" -> "描写方法"
    "paragraphStructure" -> "段落组织"
    "dialogue" -> "对话特征"
    "emotion" -> "情绪表达"
    "transition" -> "场景与叙事衔接"
    else -> dimension
}

internal fun analysisConfidenceLabel(confidence: String): String = when (confidence) {
    "high" -> "高可信"
    "medium" -> "中等可信"
    "low" -> "低可信"
    "insufficient" -> "证据不足"
    else -> confidence
}

/** v2 展示可信度区间与证据质量；v1 只展示原档位，不把档位换算成区间 */
internal fun analysisEvidenceConfidenceLabel(evidence: StyleEvidence): String {
    val tier = evidence.confidence?.let { analysisConfidenceLabel(it) }
    val interval = evidence.confidenceInterval
        ?.let { "${it.lower}–${it.upper}%" }
    val quality = evidence.evidenceQuality?.let { quality ->
        when (quality) {
            "strong" -> "证据强"
            "moderate" -> "证据中等"
            "weak" -> "证据弱"
            "insufficient" -> "证据不足"
            else -> quality
        }
    }
    return when {
        tier != null -> "$tier（旧版档位，无区间）"
        interval != null && quality != null -> "$interval（$quality）"
        interval != null -> interval
        quality != null -> quality
        else -> "可信度信息缺失"
    }
}

/** 顶部统计与解析进度 */
@Composable
private fun StyleSampleSummary(
    samples: List<StyleSample>,
    processing: Boolean,
    currentName: String?
) {
    val success = samples.count { it.status == StyleSampleStatus.SUCCESS }
    val failed = samples.count { it.status == StyleSampleStatus.FAILED }
    val usableCount = samples.count { it.isUsable }
    val usableChars = samples.filter { it.isUsable }.sumOf { it.charCount }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "共 ${samples.size} 个文件 · 成功 $success · 失败 $failed · 可用 $usableCount 个（$usableChars 字符）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (processing) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                "正在解析：${currentName ?: "…"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StyleSampleCard(
    sample: StyleSample,
    onPreview: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                OrderBadge(sample.order)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        sample.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${sample.format.label} · ${sampleStatusText(sample)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = sampleStatusColor(sample)
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (sample.isTooShort) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "内容过短（少于 ${StyleSample.MIN_ANALYSIS_CHARS} 字符），无法用于文风分析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (sample.status == StyleSampleStatus.FAILED) {
                Spacer(Modifier.height(4.dp))
                Text(
                    sample.error ?: "解析失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (sample.status == StyleSampleStatus.SUCCESS && sample.text.isNotBlank()) {
                TextButton(
                    onClick = onPreview,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp, vertical = 0.dp
                    )
                ) {
                    Icon(
                        Icons.Filled.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("预览正文", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun OrderBadge(order: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            Text(
                "$order",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private fun sampleStatusText(sample: StyleSample): String = when (sample.status) {
    StyleSampleStatus.PENDING -> "等待处理"
    StyleSampleStatus.PARSING -> "正在解析…"
    StyleSampleStatus.SUCCESS -> "已提取 ${sample.charCount} 字符"
    StyleSampleStatus.FAILED -> "解析失败"
}

@Composable
private fun sampleStatusColor(sample: StyleSample): Color = when {
    sample.status == StyleSampleStatus.FAILED -> MaterialTheme.colorScheme.error
    sample.isTooShort -> MaterialTheme.colorScheme.error
    sample.status == StyleSampleStatus.SUCCESS -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 正文预览：只读展示提取结果，不修改原始文件 */
@Composable
private fun StylePreviewDialog(sample: StyleSample, onDismiss: () -> Unit) {
    val limit = PREVIEW_CHARS
    val preview = remember(sample.key, sample.text) {
        if (sample.text.length > limit) sample.text.substring(0, limit) else sample.text
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("正文预览", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    sample.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${sample.format.label} · 共 ${sample.charCount} 字符",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(preview, style = MaterialTheme.typography.bodySmall)
                if (sample.charCount > limit) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "（仅显示前 $limit 字符）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 预览最多展示的字符数 */
private const val PREVIEW_CHARS = 2000
