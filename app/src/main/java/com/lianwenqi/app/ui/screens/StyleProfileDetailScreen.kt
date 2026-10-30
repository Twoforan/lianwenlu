package com.lianwenqi.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.entity.StyleProfile
import com.lianwenqi.app.data.style.StyleAnalysis
import com.lianwenqi.app.data.style.StyleAnalysisCodec
import com.lianwenqi.app.data.style.StyleAnalysisEditor
import com.lianwenqi.app.data.style.StyleAnalysisField
import com.lianwenqi.app.data.style.StyleAnalysisForm
import com.lianwenqi.app.data.style.StyleAnalysisParseResult
import com.lianwenqi.app.data.style.StyleProfileSummary
import com.lianwenqi.app.data.style.StyleProfileSummaryReader
import com.lianwenqi.app.data.style.StyleUpdateResult
import com.lianwenqi.app.data.style.SUPPORTED_STYLE_ANALYSIS_VERSIONS
import com.lianwenqi.app.ui.appViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 详情页读取状态 */
sealed interface StyleProfileDetailState {
    data object Loading : StyleProfileDetailState
    data class Loaded(
        val profile: StyleProfile,
        val summary: StyleProfileSummary,
        /** 能被当前协议版本完整解析时给出；否则只能按原始数据只读展示 */
        val analysis: StyleAnalysis?,
        val boundBookCount: Int
    ) : StyleProfileDetailState

    data object NotFound : StyleProfileDetailState
    data class Failed(val message: String) : StyleProfileDetailState
}

/** 保存状态：失败时界面保持编辑内容，允许原地重试 */
sealed interface StyleProfileSavePhase {
    data object Idle : StyleProfileSavePhase
    data object Saving : StyleProfileSavePhase
    data class Failed(val message: String) : StyleProfileSavePhase
    data object Saved : StyleProfileSavePhase
}

/** 删除状态：成功后才返回列表，失败时保留当前档案并显示错误 */
sealed interface StyleProfileDeletePhase {
    data object Idle : StyleProfileDeletePhase
    data object Deleting : StyleProfileDeletePhase
    data class Failed(val message: String) : StyleProfileDeletePhase
    data object Deleted : StyleProfileDeletePhase
}

/**
 * 文风档案详情：读取真实档案、结构化编辑与删除。
 *
 * 只通过 [com.lianwenqi.app.data.StyleRepository] 读写；打开页面不会重新调用模型分析，
 * 也不会改写已保存内容。分析结果版本无法识别时仅允许修改名称，原始 JSON 原样保留。
 */
class StyleProfileDetailViewModel(
    container: AppContainer,
    private val profileId: Long
) : ViewModel() {

    private val repository = container.styleRepository
    private val updater = container.styleProfileUpdater

    private val _state = MutableStateFlow<StyleProfileDetailState>(StyleProfileDetailState.Loading)
    val state: StateFlow<StyleProfileDetailState> = _state.asStateFlow()

    private val _editing = MutableStateFlow(false)
    val editing: StateFlow<Boolean> = _editing.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _form = MutableStateFlow(StyleAnalysisForm())
    val form: StateFlow<StyleAnalysisForm> = _form.asStateFlow()

    private val _savePhase = MutableStateFlow<StyleProfileSavePhase>(StyleProfileSavePhase.Idle)
    val savePhase: StateFlow<StyleProfileSavePhase> = _savePhase.asStateFlow()

    private val _showDeleteConfirm = MutableStateFlow(false)
    val showDeleteConfirm: StateFlow<Boolean> = _showDeleteConfirm.asStateFlow()

    private val _deletePhase = MutableStateFlow<StyleProfileDeletePhase>(StyleProfileDeletePhase.Idle)
    val deletePhase: StateFlow<StyleProfileDeletePhase> = _deletePhase.asStateFlow()

    private val _unknownFieldAck = MutableStateFlow(false)
    val unknownFieldAck: StateFlow<Boolean> = _unknownFieldAck.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = StyleProfileDetailState.Loading
        viewModelScope.launch {
            try {
                val profile = repository.getProfile(profileId)
                if (profile == null) {
                    _state.value = StyleProfileDetailState.NotFound
                    return@launch
                }
                _state.value = StyleProfileDetailState.Loaded(
                    profile = profile,
                    summary = StyleProfileSummaryReader.read(profile),
                    analysis = decodeAnalysis(profile),
                    boundBookCount = repository.boundBookCount(profile.id)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = StyleProfileDetailState.Failed(
                    "读取文风档案失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    fun startEditing() {
        val loaded = _state.value as? StyleProfileDetailState.Loaded ?: return
        _name.value = loaded.profile.name
        _form.value = loaded.analysis?.let { StyleAnalysisEditor.toForm(it) } ?: StyleAnalysisForm()
        _unknownFieldAck.value = false
        _savePhase.value = StyleProfileSavePhase.Idle
        _editing.value = true
    }

    fun cancelEditing() {
        val loaded = _state.value as? StyleProfileDetailState.Loaded ?: return
        _editing.value = false
        _savePhase.value = StyleProfileSavePhase.Idle
        _name.value = loaded.profile.name
        _form.value = loaded.analysis?.let { StyleAnalysisEditor.toForm(it) } ?: StyleAnalysisForm()
        _unknownFieldAck.value = false
    }

    fun updateName(value: String) {
        _name.value = value
    }

    fun updateField(field: StyleAnalysisField, value: String) {
        _form.value = StyleAnalysisEditor.update(_form.value, field, value)
    }

    fun setUnknownFieldAck(value: Boolean) {
        _unknownFieldAck.value = value
    }

    /** 保存：分析结果无法识别时只改名称，原始 analysisJson 保持不动 */
    fun save() {
        val loaded = _state.value as? StyleProfileDetailState.Loaded ?: return
        if (_savePhase.value == StyleProfileSavePhase.Saving) return
        if (_savePhase.value == StyleProfileSavePhase.Saved && !_editing.value) return
        val analysis = loaded.analysis?.let {
            StyleAnalysisEditor.toAnalysis(_form.value, it)
        }
        _savePhase.value = StyleProfileSavePhase.Saving
        viewModelScope.launch {
            when (
                val result = updater.update(
                    profile = loaded.profile,
                    name = _name.value,
                    analysis = analysis,
                    confirmUnknownFieldRemoval = _unknownFieldAck.value
                )
            ) {
                is StyleUpdateResult.Success -> {
                    val saved = result.profile
                    val freshAnalysis = decodeAnalysis(saved)
                    _state.value = StyleProfileDetailState.Loaded(
                        profile = saved,
                        summary = StyleProfileSummaryReader.read(saved),
                        analysis = freshAnalysis,
                        boundBookCount = loaded.boundBookCount
                    )
                    _name.value = saved.name
                    _form.value = freshAnalysis?.let { StyleAnalysisEditor.toForm(it) }
                        ?: StyleAnalysisForm()
                    _unknownFieldAck.value = false
                    _editing.value = false
                    _savePhase.value = StyleProfileSavePhase.Saved
                }
                StyleUpdateResult.DuplicateName -> {
                    _savePhase.value = StyleProfileSavePhase.Failed("已存在同名文风档案，请修改名称后重试")
                }
                is StyleUpdateResult.Invalid -> {
                    _savePhase.value = StyleProfileSavePhase.Failed(result.message)
                }
                is StyleUpdateResult.Failure -> {
                    _savePhase.value = StyleProfileSavePhase.Failed(result.message)
                }
            }
        }
    }

    fun requestDelete() {
        _deletePhase.value = StyleProfileDeletePhase.Idle
        _showDeleteConfirm.value = true
    }

    fun dismissDelete() {
        if (_deletePhase.value == StyleProfileDeletePhase.Deleting) return
        _showDeleteConfirm.value = false
        _deletePhase.value = StyleProfileDeletePhase.Idle
    }

    fun confirmDelete() {
        if (_deletePhase.value == StyleProfileDeletePhase.Deleting) return
        _deletePhase.value = StyleProfileDeletePhase.Deleting
        viewModelScope.launch {
            try {
                // Repository 在同一事务内先解绑书籍（styleId = 0）再删除档案
                repository.deleteProfile(profileId)
                _showDeleteConfirm.value = false
                _deletePhase.value = StyleProfileDeletePhase.Deleted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _deletePhase.value = StyleProfileDeletePhase.Failed(
                    "删除失败：${e.message ?: "未知错误"}"
                )
            }
        }
    }

    private fun decodeAnalysis(profile: StyleProfile): StyleAnalysis? =
        (StyleAnalysisCodec.parse(profile.analysisJson) as? StyleAnalysisParseResult.Success)?.analysis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleProfileDetailScreen(profileId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "style-profile-$profileId") {
        StyleProfileDetailViewModel(it, profileId)
    }
    val state by vm.state.collectAsState()
    val editing by vm.editing.collectAsState()
    val name by vm.name.collectAsState()
    val form by vm.form.collectAsState()
    val savePhase by vm.savePhase.collectAsState()
    val deletePhase by vm.deletePhase.collectAsState()
    val showDelete by vm.showDeleteConfirm.collectAsState()
    val unknownAck by vm.unknownFieldAck.collectAsState()

    LaunchedEffect(deletePhase) {
        if (deletePhase == StyleProfileDeletePhase.Deleted) onBack()
    }

    val loaded = state as? StyleProfileDetailState.Loaded
    val title = loaded?.profile?.name ?: "文风档案"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (loaded != null) {
                        if (editing) {
                            TextButton(onClick = vm::cancelEditing) { Text("取消") }
                        } else {
                            IconButton(onClick = vm::startEditing) {
                                Icon(Icons.Filled.Edit, contentDescription = "编辑")
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val current = state) {
                StyleProfileDetailState.Loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                StyleProfileDetailState.NotFound -> {
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("文风档案不存在或已被删除", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onBack) { Text("返回文风库") }
                    }
                }

                is StyleProfileDetailState.Failed -> {
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            current.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = vm::load) { Text("重新读取") }
                    }
                }

                is StyleProfileDetailState.Loaded -> {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        StyleProfileHeader(
                            loaded = current,
                            editing = editing,
                            name = name,
                            onNameChange = vm::updateName,
                            saved = savePhase == StyleProfileSavePhase.Saved
                        )

                        if (!current.summary.recognizable) {
                            Spacer(Modifier.height(12.dp))
                            StyleProfileCompatibilityNotice(summary = current.summary)
                        }

                        Spacer(Modifier.height(16.dp))

                        if (editing && current.analysis != null) {
                            StyleAnalysisEditForm(
                                form = form,
                                onFieldChange = vm::updateField,
                                unknownFields = current.summary.unknownFields,
                                unknownAck = unknownAck,
                                onUnknownAckChange = vm::setUnknownFieldAck
                            )
                        } else {
                            StyleProfileReadonlyContent(loaded = current)
                            if (editing) {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "当前档案的分析结果版本无法识别，仅支持修改名称；分析内容保持原样。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (editing) {
                            val failure = savePhase as? StyleProfileSavePhase.Failed
                            if (failure != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    failure.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = vm::cancelEditing,
                                    enabled = savePhase != StyleProfileSavePhase.Saving
                                ) { Text("取消") }
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = vm::save,
                                    enabled = savePhase != StyleProfileSavePhase.Saving
                                ) {
                                    Text(
                                        if (savePhase == StyleProfileSavePhase.Saving) "正在保存…"
                                        else "保存"
                                    )
                                }
                            }
                            Text(
                                "保存只更新本档案，不会重新调用模型，也不会影响已有生成内容。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(Modifier.height(24.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = vm::requestDelete,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("删除档案", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDelete && loaded != null) {
        StyleProfileDeleteDialog(
            profileName = loaded.profile.name,
            boundBookCount = loaded.boundBookCount,
            deleting = deletePhase == StyleProfileDeletePhase.Deleting,
            error = (deletePhase as? StyleProfileDeletePhase.Failed)?.message,
            onConfirm = vm::confirmDelete,
            onDismiss = vm::dismissDelete
        )
    }
}

@Composable
private fun StyleProfileHeader(
    loaded: StyleProfileDetailState.Loaded,
    editing: Boolean,
    name: String,
    onNameChange: (String) -> Unit,
    saved: Boolean
) {
    val summary = loaded.summary
    val profile = loaded.profile

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("档案名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                metaLine(summary),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(2.dp))
            Text(
                timeLine(profile),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (loaded.boundBookCount > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "已绑定 ${loaded.boundBookCount} 本书",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (saved && !editing) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "已保存",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 分析结果无法识别 / 版本不支持时的提示：保留原始数据，不做猜测性改写 */
@Composable
private fun StyleProfileCompatibilityNotice(summary: StyleProfileSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(
                when {
                    summary.schemaVersion == null -> "无法识别该档案的分析结果"
                    summary.schemaVersion !in SUPPORTED_STYLE_ANALYSIS_VERSIONS ->
                        "分析结果版本 ${summary.schemaVersion} 暂不支持"
                    else -> "分析结果不完整或格式不受支持"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "部分内容可能无法显示或编辑。原始分析数据会原样保留，不会被自动改写。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** 只读展示：名称以外不修改任何已保存内容 */
@Composable
private fun StyleProfileReadonlyContent(loaded: StyleProfileDetailState.Loaded) {
    val analysis = loaded.analysis
    var showRaw by remember(loaded.profile.id) { mutableStateOf(false) }

    if (analysis == null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("整体风格", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    loaded.summary.overview ?: "暂无概述",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loaded.summary.overview == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { showRaw = !showRaw }) {
            Text(if (showRaw) "收起原始分析数据" else "查看原始分析数据")
        }
        if (showRaw) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                SelectionContainer {
                    Text(
                        loaded.profile.analysisJson,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
            }
        }
        return
    }

    Text("整体风格", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(analysis.overview, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(16.dp))

    analysisSections(analysis).forEach { section ->
        StyleDetailSection(title = section.label, items = section.items)
        Spacer(Modifier.height(14.dp))
    }

    StyleDetailSection(
        title = "证据与可信度",
        items = analysis.evidenceAndConfidence.map { evidence ->
            "${analysisDimensionLabel(evidence.dimension)}｜" +
                "${analysisEvidenceConfidenceLabel(evidence)}：${evidence.evidence}"
        }
    )

    if (analysis.evidenceAndConfidence.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "该档案没有保存证据说明。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StyleDetailSection(title: String, items: List<String>) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (items.isEmpty()) {
            Text(
                "暂无内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            items.forEach { item ->
                Text("· $item", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** 结构化编辑：只编辑可与 F4 协议对应的文本字段，证据与版本保持不变 */
@Composable
private fun StyleAnalysisEditForm(
    form: StyleAnalysisForm,
    onFieldChange: (StyleAnalysisField, String) -> Unit,
    unknownFields: List<String>,
    unknownAck: Boolean,
    onUnknownAckChange: (Boolean) -> Unit
) {
    Text(
        "编辑分析内容",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "结构化字段按条编辑，${StyleAnalysisEditor.ITEMS_HINT}。保存前会按当前分析协议校验。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    FormTextField("整体风格", form.overview, minLines = 3) {
        onFieldChange(StyleAnalysisField.OVERVIEW, it)
    }
    FormTextField("叙述方式", form.narration) { onFieldChange(StyleAnalysisField.NARRATION, it) }
    FormTextField("句式与节奏", form.sentenceRhythm) {
        onFieldChange(StyleAnalysisField.SENTENCE_RHYTHM, it)
    }
    FormTextField("词汇与表达", form.diction) { onFieldChange(StyleAnalysisField.DICTION, it) }
    FormTextField("描写方法", form.description) { onFieldChange(StyleAnalysisField.DESCRIPTION, it) }
    FormTextField("段落组织", form.paragraphStructure) {
        onFieldChange(StyleAnalysisField.PARAGRAPH_STRUCTURE, it)
    }
    FormTextField("对话特征", form.dialogue) { onFieldChange(StyleAnalysisField.DIALOGUE, it) }
    FormTextField("情绪表达", form.emotion) { onFieldChange(StyleAnalysisField.EMOTION, it) }
    FormTextField("场景与叙事衔接", form.transition) {
        onFieldChange(StyleAnalysisField.TRANSITION, it)
    }
    FormTextField("可执行的仿写指导", form.writingGuidelines) {
        onFieldChange(StyleAnalysisField.GUIDELINES, it)
    }
    FormTextField("应避免的写法", form.avoidances) {
        onFieldChange(StyleAnalysisField.AVOIDANCES, it)
    }

    Text(
        "证据与可信度沿用原档案，不会被本次编辑修改。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (unknownFields.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    "档案包含当前版本未识别的字段：${unknownFields.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onUnknownAckChange(!unknownAck) }
                ) {
                    Checkbox(checked = unknownAck, onCheckedChange = onUnknownAckChange)
                    Text(
                        "我已了解保存分析内容会移除上述字段",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun FormTextField(
    label: String,
    value: String,
    minLines: Int = 2,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        minLines = minLines,
        maxLines = 12,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    )
}

@Composable
private fun StyleProfileDeleteDialog(
    profileName: String,
    boundBookCount: Int,
    deleting: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("删除文风档案") },
        text = {
            Column {
                Text("确定删除「$profileName」？删除后无法恢复。")
                if (boundBookCount > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "有 $boundBookCount 本书绑定了该档案，删除后将一并解除绑定（不会删除书籍或改动其他数据）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(
                    if (deleting) "正在删除…" else "删除",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) { Text("取消") }
        }
    )
}

private fun metaLine(summary: StyleProfileSummary): String {
    val parts = mutableListOf<String>()
    parts += summary.sourceLabel
    if (summary.modelName.isNotBlank()) parts += summary.modelName
    if (summary.analyzedSamples != null && summary.includedChars != null) {
        val strategy = summary.strategyLabel?.let { "（$it）" } ?: ""
        parts += "分析 ${summary.analyzedSamples} 个样本 / ${summary.includedChars} 字符$strategy"
    }
    return parts.joinToString(" · ")
}

private fun timeLine(profile: StyleProfile): String {
    val updated = formatStyleTimestamp(profile.updatedAt)
    return if (profile.updatedAt > profile.createdAt) {
        "创建于 ${formatStyleTimestamp(profile.createdAt)} · 更新于 $updated"
    } else {
        "创建于 $updated"
    }
}

/** 档案时间展示格式（与项目其他页面一致） */
internal fun formatStyleTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
