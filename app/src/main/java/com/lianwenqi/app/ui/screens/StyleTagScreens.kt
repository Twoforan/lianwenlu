package com.lianwenqi.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.StyleTagRules
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.GLOBAL_STYLE_TAG_SCOPE
import com.lianwenqi.app.data.entity.StyleSampleChunk
import com.lianwenqi.app.data.entity.StyleSampleRecord
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagCategory
import com.lianwenqi.app.data.entity.StyleTagSource
import com.lianwenqi.app.data.entity.SummaryTagSource
import com.lianwenqi.app.data.style.StyleExtractResult
import com.lianwenqi.app.data.style.StyleTagAnalysisCodec
import com.lianwenqi.app.data.style.StyleTaggingOutcome
import com.lianwenqi.app.data.style.StyleTextExtractor
import com.lianwenqi.app.data.style.SummarySentence
import com.lianwenqi.app.data.style.SummaryTagValidity
import com.lianwenqi.app.data.style.SummarySentenceSegmenter
import com.lianwenqi.app.data.style.SummarySentenceSplitter
import com.lianwenqi.app.ui.appViewModel
import com.lianwenqi.app.util.shortHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ======================== 标签库（全局 / 档案共用一套界面） ========================

/**
 * 标签库管理：scope = 0 时是全局用户标签库，scope > 0 时是某个文风档案的专属标签库。
 * AI 在档案库中新增的标签会以「AI」来源标记；全局库只接受用户操作。
 */
class StyleTagLibraryViewModel(container: AppContainer, val scope: Long) : ViewModel() {
    private val repository = container.styleTagRepository
    private val styleRepository = container.styleRepository
    val isGlobal: Boolean = scope == GLOBAL_STYLE_TAG_SCOPE

    val categories: StateFlow<List<StyleTagCategory>> = run {
        val flow = if (isGlobal) repository.observeUserCategories() else repository.observeProfileCategories(scope)
        flow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }

    val tags: StateFlow<List<StyleTag>> = run {
        val flow = if (isGlobal) repository.observeUserTags() else repository.observeProfileTags(scope)
        flow.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _usageCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val usageCounts: StateFlow<Map<Long, Int>> = _usageCounts.asStateFlow()

    fun dismissMessage() {
        _message.value = null
    }

    fun refreshUsage(tagIds: List<Long>) {
        if (tagIds.isEmpty()) {
            _usageCounts.value = emptyMap()
            return
        }
        viewModelScope.launch {
            _usageCounts.value = runCatching {
                tagIds.associateWith { repository.tagUsage(it) }
            }.getOrDefault(emptyMap())
        }
    }

    fun addCategory(name: String) = operate { repository.createUserCategory(name) }
    fun renameCategory(from: String, to: String) = operate { repository.renameUserCategory(from, to) }
    fun deleteCategory(name: String) = operate { repository.deleteUserCategory(name) }
    fun addTag(category: String, name: String, description: String) =
        operate { repository.addUserTag(category, name, description) }
    fun updateTag(tagId: Long, category: String, name: String, description: String) =
        operate { repository.updateUserTag(tagId, category, name, description) }
    fun deleteTag(tagId: Long) = operate { repository.deleteUserTag(tagId) }

    fun addProfileCategory(name: String) = operate { repository.createProfileCategory(scope, name) }
    fun renameProfileCategory(from: String, to: String) =
        operate { repository.renameProfileCategory(scope, from, to) }
    fun deleteProfileCategory(name: String) = operate { repository.deleteProfileCategory(scope, name) }
    fun addProfileTag(category: String, name: String, description: String) =
        operate { repository.addProfileTag(scope, category, name, description) }
    fun updateProfileTag(tagId: Long, category: String, name: String, description: String) =
        operate { repository.updateProfileTag(scope, tagId, category, name, description) }
    fun deleteProfileTag(tagId: Long) = operate { repository.deleteProfileTag(scope, tagId) }

    /** 某个标签被哪些片段使用（弹窗展示） */
    suspend fun chunksUsing(tagId: Long): List<StyleSampleChunk> =
        runCatching { repository.chunksUsingTag(tagId) }.getOrDefault(emptyList())

    suspend fun profileName(): String =
        if (isGlobal) "" else runCatching { styleRepository.getProfile(scope)?.name }.getOrNull().orEmpty()

    fun createCategory(name: String) = if (isGlobal) addCategory(name) else addProfileCategory(name)
    fun renameCategoryForScope(from: String, to: String) =
        if (isGlobal) renameCategory(from, to) else renameProfileCategory(from, to)

    fun deleteCategoryForScope(name: String) =
        if (isGlobal) deleteCategory(name) else deleteProfileCategory(name)

    fun addTagForScope(category: String, name: String, description: String) =
        if (isGlobal) addTag(category, name, description) else addProfileTag(category, name, description)

    fun updateTagForScope(tagId: Long, category: String, name: String, description: String) =
        if (isGlobal) updateTag(tagId, category, name, description)
        else updateProfileTag(tagId, category, name, description)

    fun deleteTagForScope(tagId: Long) =
        if (isGlobal) deleteTag(tagId) else deleteProfileTag(tagId)

    private fun operate(block: suspend () -> Any?) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = e.message ?: "操作失败"
            }
        }
    }
}

/** 标签库页面：[scope] = 0 为全局用户标签库，> 0 为文风档案专属标签库 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleTagLibraryScreen(
    scope: Long,
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    val vm = appViewModel(key = "style-tag-lib-$scope") { StyleTagLibraryViewModel(it, scope) }
    val tags by vm.tags.collectAsState()
    val message by vm.message.collectAsState()
    val usage by vm.usageCounts.collectAsState()

    var query by remember { mutableStateOf("") }
    var addingTag by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<StyleTag?>(null) }
    var deletingTag by remember { mutableStateOf<StyleTag?>(null) }
    var usageTag by remember { mutableStateOf<StyleTag?>(null) }
    var profileName by remember { mutableStateOf("") }

    LaunchedEffect(scope) { profileName = vm.profileName() }
    LaunchedEffect(tags) { vm.refreshUsage(tags.map { it.id }) }

    val filtered = remember(tags, query) {
        tags.filter { tag ->
            query.isBlank() || tag.name.contains(query.trim(), ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (scope > 0L && profileName.isNotBlank()) "$title · $profileName" else title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索标签") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "共 ${tags.size} 个标签",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { addingTag = true }) { Text("新增标签") }
            }
            HorizontalDivider()
            if (filtered.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (tags.isEmpty()) "还没有标签" else "没有匹配的标签",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { addingTag = true }) { Text("新增标签") }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filtered, key = { it.id }) { tag ->
                        TagRow(
                            tag = tag,
                            usage = usage[tag.id] ?: 0,
                            onEdit = { editingTag = tag },
                            onDelete = { deletingTag = tag },
                            onOpenUsage = { usageTag = tag }
                        )
                    }
                }
            }
        }
    }

    if (addingTag) {
        TagEditDialog(
            title = "新增标签",
            initial = TagDraft(category = StyleTagAnalysisCodec.DEFAULT_NEW_TAG_CATEGORY),
            isGlobal = vm.isGlobal,
            onDismiss = { addingTag = false },
            onConfirm = { value ->
                addingTag = false
                vm.addTagForScope(value.category, value.name, value.description)
            }
        )
    }

    editingTag?.let { tag ->
        TagEditDialog(
            title = "编辑标签",
            initial = TagDraft(tag.category, tag.name, tag.description),
            isGlobal = vm.isGlobal,
            onDismiss = { editingTag = null },
            onConfirm = { value ->
                editingTag = null
                vm.updateTagForScope(tag.id, value.category, value.name, value.description)
            }
        )
    }

    deletingTag?.let { tag ->
        ConfirmDialog(
            title = "删除标签",
            text = "删除「${tag.name}」？只解除片段与该标签的关联，样本文本和向量不会被删除。",
            confirmLabel = "删除",
            onDismiss = { deletingTag = null },
            onConfirm = {
                deletingTag = null
                vm.deleteTagForScope(tag.id)
            }
        )
    }

    usageTag?.let { tag ->
        var chunks by remember(tag.id) { mutableStateOf<List<StyleSampleChunk>?>(null) }
        LaunchedEffect(tag.id) { chunks = vm.chunksUsing(tag.id) }
        AlertDialog(
            onDismissRequest = { usageTag = null },
            title = { Text("使用「${tag.name}」的片段") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    val list = chunks
                    if (list == null) {
                        Text("读取中…", style = MaterialTheme.typography.bodySmall)
                    } else if (list.isEmpty()) {
                        Text("还没有片段使用该标签。", style = MaterialTheme.typography.bodySmall)
                    } else {
                        list.forEachIndexed { index, chunk ->
                            Text(
                                "#${index + 1}（第 ${chunk.chunkIndex + 1} 段）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                chunk.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { usageTag = null }) { Text("关闭") } }
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("操作提示") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("知道了") } }
        )
    }
}

private data class TagDraft(val category: String, val name: String = "", val description: String = "")

private fun tagSourceLabel(source: String): String =
    if (source == StyleTagSource.AI) "AI 创建" else "用户创建"

@Composable
private fun TagRow(
    tag: StyleTag,
    usage: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenUsage: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenUsage)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tag.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(6.dp))
                SourceBadge(tag.source)
            }
            Text(
                if (tag.description.isBlank()) "被 $usage 个片段使用" else "${tag.description} · 被 $usage 个片段使用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑 ${tag.name}")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除 ${tag.name}")
        }
    }
}

@Composable
private fun SourceBadge(source: String) {
    val ai = source == StyleTagSource.AI
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (ai) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            if (ai) "AI" else "用户",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun TagEditDialog(
    title: String,
    initial: TagDraft,
    isGlobal: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (TagDraft) -> Unit
) {
    val category = initial.category
    var name by remember { mutableStateOf(initial.name) }
    var description by remember { mutableStateOf(initial.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("标签名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("定义 / 说明（可选）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isGlobal) "全局标签库只由你维护，AI 只会读取和使用，不会新增或修改这里的标签。"
                    else "档案标签库可由你维护；AI 分析样本时只能新增，不会修改或删除已有标签。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        TagDraft(
                            category = category.trim(),
                            name = StyleTagRules.normalizeName(name),
                            description = description.trim()
                        )
                    )
                },
                enabled = name.trim().isNotEmpty()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ======================== 文风样本管理 + 分析 ========================

/** 一次分析用到的输入状态（让界面在弹窗内维护，提交时交给 ViewModel） */
class StyleSampleManageViewModel(
    container: AppContainer,
    val profileId: Long
) : ViewModel() {
    private val repository = container.styleTagRepository
    private val styleRepository = container.styleRepository
    private val taggingService = container.styleTaggingService
    private val extractor = StyleTextExtractor(container.context)
    private val emb = container.memoryEmbeddingGateway

    private val _profileName = MutableStateFlow("")
    val profileName: StateFlow<String> = _profileName.asStateFlow()

    init {
        viewModelScope.launch {
            _profileName.value = runCatching { styleRepository.getProfile(profileId)?.name }
                .getOrNull().orEmpty()
        }
    }

    val samples: StateFlow<List<StyleSampleRecord>> =
        repository.observeSamples(profileId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val unbound: StateFlow<List<StyleSampleRecord>> =
        repository.observeUnboundSamples().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val profileTags: StateFlow<List<StyleTag>> =
        repository.observeProfileTags(profileId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val userTags: StateFlow<List<StyleTag>> =
        repository.observeUserTags().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val models = container.configRepository.observeModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeModelId: StateFlow<Long> =
        container.prefs.activeModelId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _analysis = MutableStateFlow<StyleTaggingOutcome?>(null)
    val analysis: StateFlow<StyleTaggingOutcome?> = _analysis.asStateFlow()

    /** 刚导入的样本：用于自动打开切片预览，确认切片后再手动触发标签分析 */
    private val _lastImportedSampleId = MutableStateFlow<Long?>(null)
    val lastImportedSampleId: StateFlow<Long?> = _lastImportedSampleId.asStateFlow()

    fun dismissMessage() { _message.value = null }
    fun dismissAnalysis() { _analysis.value = null }
    fun consumeLastImportedSample() { _lastImportedSampleId.value = null }

    fun importFromUri(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val name = extractor.displayName(uri)
                when (val result = extractor.extract(uri, name)) {
                    is StyleExtractResult.Success -> {
                        val sampleId = repository.importSample(
                            profileId = profileId,
                            fileName = name,
                            format = result.format.label,
                            text = result.text
                        ) { texts -> emb.embed(texts) }
                        _lastImportedSampleId.value = sampleId
                        _message.value = "已导入样本「$name」，请先确认切片，再开始标签分析"
                    }
                    is StyleExtractResult.Failure -> _message.value = result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = "导入失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun importText(fileName: String, text: String) {
        if (_busy.value) return
        val name = fileName.trim().ifBlank { "粘贴文本" }
        viewModelScope.launch {
            _busy.value = true
            try {
                val sampleId = repository.importSample(
                    profileId = profileId,
                    fileName = name,
                    format = "文本",
                    text = text
                ) { texts -> emb.embed(texts) }
                _lastImportedSampleId.value = sampleId
                _message.value = "已导入样本「$name」，请先确认切片，再开始标签分析"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = "导入失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun unbind(sampleId: Long) {
        viewModelScope.launch {
            try {
                repository.unbindSample(sampleId)
                _message.value = "已解绑；原文、片段、向量与标签关联均保留，但不再参与任何文风检索。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = "解绑失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun bind(sampleId: Long) {
        viewModelScope.launch {
            try {
                repository.bindSample(sampleId, profileId)
                _message.value = "已绑定到当前文风档案。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = "绑定失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun delete(sampleId: Long) {
        viewModelScope.launch {
            try {
                repository.deleteSample(sampleId)
                _message.value = "样本已删除（仅删除本样本的片段与关联）。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = "删除失败：${e.message ?: "未知错误"}"
            }
        }
    }

    fun analyze(sampleId: Long, modelId: Long, selectedUserTagIds: Set<Long>) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _analysis.value = null
            try {
                _analysis.value = taggingService.analyzeSample(modelId, sampleId, selectedUserTagIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _analysis.value = StyleTaggingOutcome.Failed(e.message ?: "标签分析失败")
            } finally {
                _busy.value = false
            }
        }
    }

    suspend fun chunkCounts(sampleIds: List<Long>): Map<Long, Int> = runCatching {
        sampleIds.associateWith { repository.chunksOfSample(it).size }
    }.getOrDefault(emptyMap())

    suspend fun detail(sampleId: Long): StyleSampleUiDetail? = runCatching {
        val sample = repository.sample(sampleId) ?: return@runCatching null
        val chunks = repository.chunksOfSample(sampleId)
        val tagsByChunk = repository.chunkTagsOfSample(sampleId)
        StyleSampleUiDetail(sample, chunks, tagsByChunk)
    }.getOrNull()
}

data class StyleSampleUiDetail(
    val sample: StyleSampleRecord,
    val chunks: List<StyleSampleChunk>,
    val tagsByChunk: Map<Long, List<StyleTag>>
)

/** 文风样本页面：绑定 / 解绑 / 重新绑定、导入、样本片段标签分析与结果展示 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSampleManageScreen(
    profileId: Long,
    onBack: () -> Unit,
    autoOpenImport: Boolean = false
) {
    val vm = appViewModel(key = "style-samples-$profileId") { StyleSampleManageViewModel(it, profileId) }
    val profileName by vm.profileName.collectAsState()
    val samples by vm.samples.collectAsState()
    val unbound by vm.unbound.collectAsState()
    val profileTags by vm.profileTags.collectAsState()
    val userTags by vm.userTags.collectAsState()
    val models by vm.models.collectAsState()
    val activeModelId by vm.activeModelId.collectAsState()
    val message by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()
    val analysis by vm.analysis.collectAsState()
    val lastImportedSampleId by vm.lastImportedSampleId.collectAsState()

    var showImport by remember { mutableStateOf(autoOpenImport) }
    var analyzeSampleId by remember { mutableStateOf<Long?>(null) }
    var detailSampleId by remember { mutableStateOf<Long?>(null) }
    var unbinding by remember { mutableStateOf<StyleSampleRecord?>(null) }
    var deleting by remember { mutableStateOf<StyleSampleRecord?>(null) }
    var chunkCounts by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFromUri(uri)
    }

    LaunchedEffect(samples) { chunkCounts = vm.chunkCounts(samples.map { it.id }) }

    // 导入完成后先展示切片预览；标签分析仍由用户手动触发
    LaunchedEffect(lastImportedSampleId) {
        lastImportedSampleId?.let {
            detailSampleId = it
            vm.consumeLastImportedSample()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (profileName.isBlank()) "文风样本" else "文风样本 · $profileName",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { showImport = true }, enabled = !busy) { Text("导入样本") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Text(
                "样本可以绑定某个文风档案；未绑定或解绑后保留原文、片段、向量与标签，但不参与任何文风检索。本页导入的样本会自动绑定到当前档案。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("本档案已有标签 ${profileTags.size} 个", style = MaterialTheme.typography.titleSmall)
                    if (profileTags.isNotEmpty()) {
                        Text(
                            profileTags.joinToString("、") { it.name },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "分析片段时会优先复用这些标签；没有合适标签时才由 AI 新增到本档案。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader("已绑定样本（${samples.size}）")
            if (samples.isEmpty()) {
                EmptyHint("还没有已绑定样本。导入样本时会自动归属到当前档案。")
            } else {
                samples.forEach { sample ->
                    SampleCard(
                        sample = sample,
                        chunkCount = chunkCounts[sample.id] ?: 0,
                        bound = true,
                        onAnalyze = { analyzeSampleId = sample.id },
                        onDetail = { detailSampleId = sample.id },
                        onUnbind = { unbinding = sample },
                        onDelete = { deleting = sample }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SectionHeader("未绑定样本（${unbound.size}）")
            Text(
                "未绑定样本不参与任何文风档案的检索；重新绑定时会清理原档案的专属标签关联，全局标签关联保留。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            if (unbound.isEmpty()) {
                EmptyHint("没有被解绑的样本。")
            } else {
                unbound.forEach { sample ->
                    SampleCard(
                        sample = sample,
                        chunkCount = 0,
                        bound = false,
                        onAnalyze = { analyzeSampleId = sample.id },
                        onDetail = { detailSampleId = sample.id },
                        onBind = { vm.bind(sample.id) },
                        onDelete = { deleting = sample }
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showImport) {
        ImportSampleDialog(
            busy = busy,
            onPickFile = { picker.launch(arrayOf("*/*")) },
            onPaste = { name, text -> vm.importText(name, text) },
            onDismiss = { showImport = false }
        )
    }

    analyzeSampleId?.let { sampleId ->
        val sample = (samples + unbound).firstOrNull { it.id == sampleId }
        if (sample?.styleProfileId == null) {
            AlertDialog(
                onDismissRequest = { analyzeSampleId = null },
                title = { Text("无法分析") },
                text = { Text("该样本未绑定文风档案。请先绑定到当前档案，再进行标签分析。") },
                confirmButton = { TextButton(onClick = { analyzeSampleId = null }) { Text("知道了") } }
            )
        } else {
            AnalyzeSampleDialog(
                userTags = userTags,
                profileTags = profileTags,
                models = models.map { it.id to it.displayName },
                defaultModelId = activeModelId,
                busy = busy,
                onDismiss = { analyzeSampleId = null },
                onStart = { modelId, selected ->
                    vm.analyze(sampleId, modelId, selected)
                }
            )
        }
    }

    analysis?.let { outcome ->
        AnalysisResultDialog(outcome = outcome, onDismiss = vm::dismissAnalysis)
    }

    detailSampleId?.let { id ->
        SampleDetailDialog(
            sampleId = id,
            load = { vm.detail(id) },
            onDismiss = { detailSampleId = null }
        )
    }

    unbinding?.let { sample ->
        ConfirmDialog(
            title = "解绑样本",
            text = "解绑后「${sample.fileName}」不再参与任何文风档案的正文生成检索；" +
                "原文、片段、向量和标签关联都会保留，之后可以重新绑定。",
            confirmLabel = "解绑",
            onDismiss = { unbinding = null },
            onConfirm = {
                unbinding = null
                vm.unbind(sample.id)
            }
        )
    }

    deleting?.let { sample ->
        ConfirmDialog(
            title = "删除样本",
            text = "删除「${sample.fileName}」及其片段与标签关联？此操作不可恢复；不影响文风档案和其他样本。",
            confirmLabel = "删除",
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                vm.delete(sample.id)
            }
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = vm::dismissMessage,
            title = { Text("操作提示") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = vm::dismissMessage) { Text("知道了") } }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun SampleCard(
    sample: StyleSampleRecord,
    chunkCount: Int,
    bound: Boolean,
    onAnalyze: () -> Unit,
    onDetail: () -> Unit,
    onUnbind: (() -> Unit)? = null,
    onBind: (() -> Unit)? = null,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sample.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (bound) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        if (bound) "已绑定" else "未绑定",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                buildString {
                    append(sample.format)
                    append(" · ").append(sample.charCount).append(" 字符")
                    if (bound && chunkCount > 0) append(" · ").append(chunkCount).append(" 个片段")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = onDetail) { Text("片段与标签") }
                TextButton(onClick = onAnalyze) { Text("分析标签") }
                if (bound && onUnbind != null) TextButton(onClick = onUnbind) { Text("解绑") }
                if (!bound && onBind != null) TextButton(onClick = onBind) { Text("绑定到本档案") }
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
internal fun ImportSampleDialog(
    busy: Boolean,
    onPickFile: () -> Unit,
    onPaste: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("导入文风样本") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "选择 txt 文件或直接粘贴文本即可，不强制绑定文风档案。" +
                        "样本会按约 200 字、优先句末标点的规则切片并生成片段向量，" +
                        "导入后显示为「未绑定 / 未分析」，可稍后再绑定档案。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "正在处理…" else "选择文件（txt / docx / epub 等）")
                }
                Spacer(Modifier.height(12.dp))
                Text("或直接粘贴文本：", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("样本名称（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("样本文本") },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onPaste(name, text)
                    onDismiss()
                },
                enabled = !busy && text.isNotBlank()
            ) { Text("导入粘贴文本") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") } }
    )
}

@Composable
private fun AnalyzeSampleDialog(
    userTags: List<StyleTag>,
    profileTags: List<StyleTag>,
    models: List<Pair<Long, String>>,
    defaultModelId: Long,
    busy: Boolean,
    onDismiss: () -> Unit,
    onStart: (Long, Set<Long>) -> Unit
) {
    var modelId by remember { mutableStateOf(if (defaultModelId > 0) defaultModelId else models.firstOrNull()?.first ?: 0L) }
    var showModelMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val modelLabel = models.firstOrNull { it.first == modelId }?.second ?: "请选择模型"

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("分析样本标签") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "默认优先复用全部全局标签，其次复用本档案已有标签；" +
                        "两者都不合适时，AI 才会新增标签到当前档案（绝不会改动全局标签库）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Box {
                    OutlinedButton(onClick = { showModelMenu = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("分析模型：$modelLabel")
                    }
                    DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                        models.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    modelId = id
                                    showModelMenu = false
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "本次优先使用的全局标签（不选则默认使用全部全局标签，可多选收窄范围）",
                    style = MaterialTheme.typography.titleSmall
                )
                if (userTags.isEmpty()) {
                    Text(
                        "全局标签库为空，可直接依赖档案标签。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    userTags.forEach { tag ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = tag.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + tag.id else selected - tag.id
                                }
                            )
                            Text(tag.name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("本档案已有标签（AI 会优先复用）", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (profileTags.isEmpty()) "暂无"
                    else profileTags.joinToString("\n") { "· ${it.name}${if (it.source == StyleTagSource.AI) "（AI）" else ""}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(modelId, selected) },
                enabled = !busy && modelId > 0L
            ) { Text(if (busy) "正在分析…" else "开始分析") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }
    )
}

@Composable
private fun AnalysisResultDialog(outcome: StyleTaggingOutcome, onDismiss: () -> Unit) {
    val title: String
    val text: String
    when (outcome) {
        is StyleTaggingOutcome.Success -> {
            title = "分析完成"
            text = buildString {
                append("已分析 ${outcome.analyzedChunks} 个文本片段。")
                if (outcome.createdTagIds.isEmpty()) {
                    append("\n本次没有新增档案标签，全部复用了已有标签。")
                } else {
                    append("\n本次新增 / 复用 ${outcome.createdTagIds.size} 个档案标签（只写入当前档案，不影响全局标签库）。")
                }
                append("\n可以在样本的「片段与标签」中查看每个片段的标注结果。")
            }
        }
        is StyleTaggingOutcome.InvalidResult -> {
            title = "分析结果不符合格式"
            text = outcome.message
        }
        is StyleTaggingOutcome.Failed -> {
            title = "分析失败"
            text = outcome.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } }
    )
}

@Composable
private fun SampleDetailDialog(
    sampleId: Long,
    load: suspend () -> StyleSampleUiDetail?,
    onDismiss: () -> Unit
) {
    var detail by remember(sampleId) { mutableStateOf<StyleSampleUiDetail?>(null) }
    var loading by remember(sampleId) { mutableStateOf(true) }
    LaunchedEffect(sampleId) {
        detail = load()
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("片段与标签") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                val current = detail
                when {
                    loading -> CircularProgressIndicator(Modifier.size(20.dp))
                    current == null -> Text("读取失败", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(
                            "${current.sample.fileName} · ${current.chunks.size} 个片段",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        current.chunks.forEachIndexed { index, chunk ->
                            val tags = current.tagsByChunk[chunk.id].orEmpty()
                            Text(
                                "第 ${chunk.chunkIndex + 1} 段" +
                                    if (tags.isEmpty()) "（未标注）" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                tags.forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (tag.source == StyleTagSource.AI) {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        }
                                    ) {
                                        Text(
                                            tag.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                chunk.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                fontFamily = FontFamily.Default
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

// ======================== 章节梗概标签 ========================

/**
 * 编辑器里的章节标签区：展示与手动编辑，不在梗概正文里拼接标签。
 *
 * 整块可单独收起（默认收起，状态由调用方持久化），收起后只看梗概、不看标签。
 * 梗概按「每 3 句一个句段」切片，每个句段都平铺展示：点击句段文字即可多选标签，
 * 保存后该句段的自动关联与旧选择一起被本次多选替换（带完整句段定位）。
 * 候选标签包含用户全局标签与当前绑定文风档案标签；两者同权参与展示与正文抽样。
 * 旧版没有句段定位的手动标签单列展示，只保留与删除，不参与抽样。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChapterSummaryTagsSection(
    tags: List<ChapterSummaryTag>,
    summary: String,
    candidates: List<StyleTag>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDelete: (id: Long) -> Unit,
    onSaveSegmentTags: (String, SummarySentence, List<StyleTag>) -> Unit
) {
    var editing by remember { mutableStateOf<SummarySentence?>(null) }
    val currentHash = remember(summary) { shortHash(summary.trim()) }
    val candidateIds = remember(candidates) { candidates.map { it.id }.toSet() }
    // 打标粒度是每 3 句一段：按当前梗概重新切片，逐段展示（没有标签的段也显示）
    val segments = remember(summary) {
        val text = summary.trim()
        SummarySentenceSegmenter.segment(text, SummarySentenceSplitter.split(text))
    }
    // 有效句段标签：哈希一致 + 带句段定位 + 标签仍存在（自动关联与手动多选同权）
    val segmentTags = remember(tags, currentHash, candidateIds, segments) {
        tags.filter { SummaryTagValidity.isValidSegmentTag(it, currentHash, candidateIds, segments) }
            .groupBy { it.sentenceIndex }
    }
    // 旧版无句段定位的手动标签：单独展示，只保留与删除，不参与抽样
    val legacyManualTags = remember(tags) { tags.filter { it.manual && !it.hasSentence } }
    // 梗概已修改 / 标签已删除的旧关联：不展示、不参与正文参考，仅提示失效
    val hasStaleAuto = remember(tags, currentHash, candidateIds, segments) {
        tags.any { !it.manual && !SummaryTagValidity.isValidSegmentTag(it, currentHash, candidateIds, segments) }
    }
    val taggedSegments = segments.count { segmentTags[it.index]?.isNotEmpty() == true }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { onToggleExpanded() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起本章标签" else "展开本章标签",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("本章标签", style = MaterialTheme.typography.labelLarge)
            if (!expanded && segments.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "$taggedSegments/${segments.size} 段已标注",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (expanded) {
            if (segments.isEmpty()) {
                Text(
                    "梗概为空，暂无可标注的句段。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    if (taggedSegments == segments.size) {
                        "已为全部 ${segments.size} 个句段选择标签；生成正文时不再重新分析标签。"
                    } else {
                        "点击任意句段文字，为它多选标签（每 3 句一段）。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 每个句段平铺展示：点击文字打开多选标签弹窗
            segments.forEach { segment ->
                val rows = segmentTags[segment.index].orEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = segment }
                        .padding(vertical = 4.dp)
                ) {
                    Text(segment.text, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(2.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (rows.isEmpty()) {
                            Text(
                                "未选择标签",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            rows.distinctBy { it.name }.forEach { row ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(
                                        row.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (legacyManualTags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "旧版手动标签",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                legacyManualTags.forEach { tag ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                tagSourceLabel(tag),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(tag.name, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onDelete(tag.id) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            if (hasStaleAuto) {
                Text(
                    "梗概已修改或标签已删除，旧句段关联已失效：不再展示，也不会作为正文参考。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    editing?.let { segment ->
        SummarySegmentTagDialog(
            segment = segment,
            candidates = candidates,
            selectedTagIds = segmentTags[segment.index]?.map { it.tagId }?.toSet().orEmpty(),
            onDismiss = { editing = null },
            onSave = { picked ->
                editing = null
                onSaveSegmentTags(currentHash, segment, picked)
            }
        )
    }
}

/**
 * 点击句段文字后的多选标签弹窗：候选包含全局标签与当前档案标签。
 * 「保存」把当前勾选结果整体写回该句段（不选任何标签即清空该段）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummarySegmentTagDialog(
    segment: SummarySentence,
    candidates: List<StyleTag>,
    selectedTagIds: Set<Long>,
    onDismiss: () -> Unit,
    onSave: (List<StyleTag>) -> Unit
) {
    var selected by remember(segment.index, selectedTagIds) { mutableStateOf(selectedTagIds) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择标签") },
        text = {
            Column {
                Text(
                    segment.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text(
                        "全局标签库与当前文风档案都没有标签，请先在文风设置里创建标签。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val globalTags = candidates.filter { it.styleProfileId == GLOBAL_STYLE_TAG_SCOPE }
                    val profileTags = candidates.filter { it.styleProfileId != GLOBAL_STYLE_TAG_SCOPE }
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        TagChipGroup(
                            label = "全局标签",
                            tags = globalTags,
                            selectedIds = selected,
                            onToggle = { id ->
                                selected = if (id in selected) selected - id else selected + id
                            }
                        )
                        if (globalTags.isNotEmpty() && profileTags.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                        }
                        TagChipGroup(
                            label = "档案标签",
                            tags = profileTags,
                            selectedIds = selected,
                            onToggle = { id ->
                                selected = if (id in selected) selected - id else selected + id
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(candidates.filter { it.id in selected }) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 标签选择用的胶囊按钮组：选中实心、未选中空心描边，横排自动换行 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipGroup(
    label: String,
    tags: List<StyleTag>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit
) {
    if (tags.isEmpty()) return
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.forEach { tag ->
            val selected = tag.id in selectedIds
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.clickable { onToggle(tag.id) }
            ) {
                Text(
                    tag.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

private fun tagSourceLabel(tag: ChapterSummaryTag): String = when (tag.source) {
    SummaryTagSource.USER_GLOBAL -> "用户标签"
    SummaryTagSource.PROFILE -> "档案标签"
    SummaryTagSource.MANUAL -> "手动"
    else -> "AI 本章"
}

