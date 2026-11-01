package com.lianwenqi.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.StyleProfileStyleStats
import com.lianwenqi.app.data.StyleSampleStyleStats
import com.lianwenqi.app.data.entity.StyleProfile
import com.lianwenqi.app.data.entity.StyleSampleChunk
import com.lianwenqi.app.data.entity.StyleSampleRecord
import com.lianwenqi.app.data.entity.GLOBAL_STYLE_TAG_SCOPE
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagSource
import com.lianwenqi.app.data.style.StyleExtractResult
import com.lianwenqi.app.data.style.StyleTaggingOutcome
import com.lianwenqi.app.data.style.StyleTextExtractor
import com.lianwenqi.app.data.style.StyleProfileSummary
import com.lianwenqi.app.data.style.StyleProfileSummaryReader
import com.lianwenqi.app.ui.appViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================== 文风样式统计与共享卡片 ========================

class StyleStylesViewModel(container: AppContainer) : ViewModel() {
    private val repository = container.styleTagRepository
    private val styleRepository = container.styleRepository
    private val extractor = StyleTextExtractor(container.context)
    private val emb = container.memoryEmbeddingGateway

    val profiles: StateFlow<List<StyleProfile>> =
        styleRepository.observeProfiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val samples: StateFlow<List<StyleSampleRecord>> =
        repository.observeAllSamples().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _profileStats = MutableStateFlow<Map<Long, StyleProfileStyleStats>>(emptyMap())
    val profileStats: StateFlow<Map<Long, StyleProfileStyleStats>> = _profileStats.asStateFlow()

    private val _sampleStats = MutableStateFlow<Map<Long, StyleSampleStyleStats>>(emptyMap())
    val sampleStats: StateFlow<Map<Long, StyleSampleStyleStats>> = _sampleStats.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() { _message.value = null }

    /**
     * 导入样本：默认不绑定文风档案，先切片入库并显示在样本列表里，
     * 之后用户可在样本样式页再次绑定到某个档案。
     */
    fun importFromUri(uri: Uri) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val name = extractor.displayName(uri)
                when (val result = extractor.extract(uri, name)) {
                    is StyleExtractResult.Success -> {
                        repository.importSample(
                            profileId = GLOBAL_STYLE_TAG_SCOPE,
                            fileName = name,
                            format = result.format.label,
                            text = result.text
                        ) { texts -> emb.embed(texts) }
                        _message.value = "已导入样本「$name」：当前未绑定文风档案、未分析标签。"
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
                repository.importSample(
                    profileId = GLOBAL_STYLE_TAG_SCOPE,
                    fileName = name,
                    format = "文本",
                    text = text
                ) { texts -> emb.embed(texts) }
                _message.value = "已导入样本「$name」：当前未绑定文风档案、未分析标签。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = "导入失败：${e.message ?: "未知错误"}"
            } finally {
                _busy.value = false
            }
        }
    }

    /** 绑定 / 解绑样本所属档案；null 表示设为未绑定 */
    fun bindSample(sampleId: Long, profileId: Long?) {
        viewModelScope.launch {
            try {
                if (profileId == null || profileId <= 0L) {
                    repository.unbindSample(sampleId)
                } else {
                    repository.bindSample(sampleId, profileId)
                }
                _message.value = if (profileId == null || profileId <= 0L) {
                    "已设为未绑定：原文、片段、向量与标签均保留，但不再参与任何文风检索。"
                } else {
                    "已绑定到所选文风档案。"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _message.value = "绑定失败：${e.message ?: "未知错误"}"
            }
        }
    }

    /** 重新计算真实统计；标签/样本变化后由页面再次调用 */
    fun refresh(profiles: List<StyleProfile>, samples: List<StyleSampleRecord>) {
        viewModelScope.launch {
            _profileStats.value = profiles.associate { profile ->
                profile.id to repository.profileStyleStats(profile.id)
            }
            _sampleStats.value = samples.associate { sample ->
                sample.id to repository.sampleStyleStats(sample.id)
            }
        }
    }
}

@Composable
internal fun StyleStylesEntryCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(24.dp)) { icon() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun StyleStylesEmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
internal fun StyleProfileStyleCard(
    profile: StyleProfile,
    stats: StyleProfileStyleStats?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (stats == null) "统计读取中…"
                    else "专属标签 ${stats.tagCount} 个 · 样本 ${stats.sampleCount} 个 · 已标记片段 ${stats.markedChunkCount} 段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看档案样式",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun StyleSampleStyleCard(
    sample: StyleSampleRecord,
    profileName: String?,
    stats: StyleSampleStyleStats?,
    onClick: () -> Unit,
    onBindClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    sample.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (stats == null) "${sample.format} · ${sample.charCount} 字符 · 统计读取中…"
                    else "${sample.format} · ${sample.charCount} 字符 · ${stats.chunkCount} 个片段 · 已标记 ${stats.markedChunkCount} · 标签 ${stats.tagCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (stats != null) {
                    Text(
                        if (stats.markedChunkCount == 0) "标签分析状态：未分析"
                        else "标签分析状态：已分析 ${stats.markedChunkCount}/${stats.chunkCount} 段",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (stats.markedChunkCount == 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "所属档案：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    StyleBindingBadge(
                        text = profileName ?: "未绑定",
                        bound = profileName != null,
                        onClick = onBindClick
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "查看样本样式",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 所属档案徽标；传入 [onClick] 时整块可点，并显示下拉箭头提示可选择档案 */
@Composable
private fun StyleBindingBadge(
    text: String,
    bound: Boolean,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (bound) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = if (onClick != null) 4.dp else 8.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (onClick != null) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "选择所属档案",
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ======================== 文风档案样式详情 ========================

class StyleProfileStyleViewModel(container: AppContainer, private val profileId: Long) : ViewModel() {
    private val repository = container.styleTagRepository
    private val styleRepository = container.styleRepository

    val tags: StateFlow<List<StyleTag>> =
        repository.observeProfileTags(profileId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val samples: StateFlow<List<StyleSampleRecord>> =
        repository.observeSamples(profileId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _profile = MutableStateFlow<StyleProfile?>(null)
    val profile: StateFlow<StyleProfile?> = _profile.asStateFlow()

    private val _stats = MutableStateFlow<StyleProfileStyleStats?>(null)
    val stats: StateFlow<StyleProfileStyleStats?> = _stats.asStateFlow()

    private val _usage = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val usage: StateFlow<Map<Long, Int>> = _usage.asStateFlow()

    private val _sampleStats = MutableStateFlow<Map<Long, StyleSampleStyleStats>>(emptyMap())
    val sampleStats: StateFlow<Map<Long, StyleSampleStyleStats>> = _sampleStats.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _profile.value = styleRepository.getProfile(profileId)
            _stats.value = repository.profileStyleStats(profileId)
            _usage.value = repository.profileTagUsage(profileId)
        }
    }

    fun refreshSampleStats(samples: List<StyleSampleRecord>) {
        viewModelScope.launch {
            _sampleStats.value = samples.associate { sample ->
                sample.id to repository.sampleStyleStats(sample.id)
            }
        }
    }
}

/** 文风档案样式详情：只保留专属标签库管理入口 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleProfileStyleScreen(
    profileId: Long,
    onBack: () -> Unit,
    onOpenTags: (Long) -> Unit
) {
    val vm = appViewModel(key = "style-profile-style-$profileId") {
        StyleProfileStyleViewModel(it, profileId)
    }
    val profile by vm.profile.collectAsState()
    val stats by vm.stats.collectAsState()

    LaunchedEffect(profileId) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        profile?.name ?: "档案样式",
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            StyleStylesEntryCard(
                icon = {
                    Icon(
                        Icons.AutoMirrored.Filled.Label,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                title = "管理专属标签库",
                subtitle = stats?.let { "本档案有 ${it.tagCount} 个专属标签，可新增、编辑或删除" }
                    ?: "新增、编辑、删除本档案的标签",
                onClick = { onOpenTags(profileId) }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ======================== 文风样本样式详情 ========================

data class StyleSampleStyleDetail(
    val sample: StyleSampleRecord,
    val profileName: String?,
    val chunks: List<StyleSampleChunk>,
    val tagsByChunk: Map<Long, List<StyleTag>>
)

class StyleSampleStyleViewModel(container: AppContainer, private val sampleId: Long) : ViewModel() {
    private val repository = container.styleTagRepository
    private val styleRepository = container.styleRepository
    private val taggingService = container.styleTaggingService

    val profiles: StateFlow<List<StyleProfile>> =
        styleRepository.observeProfiles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val models = container.configRepository.observeModels()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val activeModelId: StateFlow<Long> = container.prefs.activeModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _detail = MutableStateFlow<StyleSampleStyleDetail?>(null)
    val detail: StateFlow<StyleSampleStyleDetail?> = _detail.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() { _message.value = null }

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                val sample = repository.sample(sampleId)
                    ?: throw IllegalStateException("样本不存在或已删除")
                val profileName = sample.styleProfileId
                    ?.let { styleRepository.getProfile(it)?.name }
                StyleSampleStyleDetail(
                    sample = sample,
                    profileName = profileName,
                    chunks = repository.chunksOfSample(sampleId),
                    tagsByChunk = repository.chunkTagsOfSample(sampleId)
                )
            }.onSuccess {
                _detail.value = it
            }.onFailure {
                _error.value = it.message ?: "读取样本样式失败"
            }
            _loading.value = false
        }
    }

    /** null 表示设为未绑定 */
    fun bindTo(profileId: Long?) {
        viewModelScope.launch {
            runCatching {
                if (profileId == null || profileId <= 0L) {
                    repository.unbindSample(sampleId)
                } else {
                    repository.bindSample(sampleId, profileId)
                }
            }.onSuccess {
                _message.value = if (profileId == null || profileId <= 0L) {
                    "已设为未绑定：原文、片段、向量与标签均保留，但不再参与任何文风检索。"
                } else {
                    "已绑定到所选文风档案，可以开始标签分析。"
                }
                refresh()
            }.onFailure {
                _message.value = "绑定失败：${it.message ?: "未知错误"}"
            }
        }
    }

    /** 用当前模型（未设置时取第一个模型）分析样本片段标签，完成后刷新状态 */
    fun analyze() {
        if (_busy.value) return
        viewModelScope.launch {
            val modelId = activeModelId.value.takeIf { it > 0L }
                ?: models.value.firstOrNull()?.id
                ?: 0L
            if (modelId <= 0L) {
                _message.value = "请先在 设置 → 模型 中添加并选择文本生成模型。"
                return@launch
            }
            _busy.value = true
            runCatching { taggingService.analyzeSample(modelId, sampleId) }
                .onSuccess { outcome ->
                    _message.value = when (outcome) {
                        is StyleTaggingOutcome.Success -> buildString {
                            append("分析完成：已标记 ${outcome.analyzedChunks} 段")
                            if (outcome.createdTagIds.isNotEmpty()) {
                                append("，新增 ${outcome.createdTagIds.size} 个标签")
                            }
                        }
                        is StyleTaggingOutcome.Failed -> outcome.message
                        is StyleTaggingOutcome.InvalidResult -> "分析结果无法解析：${outcome.message}"
                    }
                    refresh()
                }
                .onFailure {
                    _message.value = "分析失败：${it.message ?: "未知错误"}"
                }
            _busy.value = false
        }
    }
}

/** 文风样本样式详情：样本信息、整体标签概览、片段级真实标签 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StyleSampleStyleScreen(
    sampleId: Long,
    onBack: () -> Unit,
    onOpenSamples: (Long) -> Unit
) {
    val vm = appViewModel(key = "style-sample-style-$sampleId") {
        StyleSampleStyleViewModel(it, sampleId)
    }
    val detail by vm.detail.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val profiles by vm.profiles.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()

    LaunchedEffect(sampleId) { vm.refresh() }

    var expandedChunks by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedTagId by remember(sampleId) { mutableStateOf<Long?>(null) }
    var showBindDialog by remember(sampleId) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        detail?.sample?.fileName ?: "样本样式",
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when {
                loading && detail == null -> Box(
                    Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.size(24.dp)) }

                error != null && detail == null -> Text(
                    error ?: "读取失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )

                detail != null -> {
                    val current = detail!!
                    val sample = current.sample
                    val usageTags = current.tagsByChunk.values.flatten()
                        .distinctBy { it.id }
                        .sortedBy { it.name }
                    val markedCount = current.tagsByChunk.count { it.value.isNotEmpty() }
                    // 每个标签实际命中的片段数，用于筛选行展示
                    val tagMatchCounts = usageTags.associate { tag ->
                        tag.id to current.tagsByChunk.count { (_, tags) -> tags.any { it.id == tag.id } }
                    }
                    val visibleChunks = if (selectedTagId == null) {
                        current.chunks
                    } else {
                        current.chunks.filter { chunk ->
                            current.tagsByChunk[chunk.id].orEmpty().any { it.id == selectedTagId }
                        }
                    }

                    // A. 样本基本信息
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(sample.fileName, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (markedCount == 0) {
                                    "标签分析状态：未分析"
                                } else {
                                    "标签分析状态：已分析 $markedCount / ${current.chunks.size} 段"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (markedCount == 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Text(
                                "已标记 $markedCount / ${current.chunks.size} 段 · 使用标签 ${usageTags.size} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${sample.format} · ${sample.charCount} 字符 · ${current.chunks.size} 个切片",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "所属档案：",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                StyleBindingBadge(
                                    text = current.profileName ?: "未绑定",
                                    bound = current.profileName != null,
                                    onClick = { showBindDialog = true }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "状态 ${if (sample.status == "SUCCESS") "解析成功" else sample.status} · " +
                                        "导入于 ${formatTime(sample.createdAt)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (current.profileName == null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "点击所属档案可绑定；未绑定样本不参与任何文风档案的 RAG 检索，原文、片段、向量与标签均保留。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    // B. 未分析时的分析入口；已分析时状态显示在上方信息卡
                    if (markedCount == 0) {
                        Spacer(Modifier.height(10.dp))
                        if (current.profileName == null) {
                            Text(
                                "绑定文风档案后才能开始标签分析。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Button(
                                onClick = vm::analyze,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("正在分析…")
                                } else {
                                    Text("开始分析")
                                }
                            }
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Text("样本整体标签概览", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "仅展示该样本片段实际使用过的标签。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (usageTags.isEmpty()) {
                        StyleStylesEmptyHint("该样本尚未绑定任何标签。")
                    } else {
                        Column {
                            usageTags.forEach { tag ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        tag.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    StyleSourceBadge(source = tag.source)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Text("文本片段与标签", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "标签来自片段与标签库的真实关联；一个片段可绑定多个标签。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))

                    if (usageTags.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedTagId == null,
                                onClick = { selectedTagId = null },
                                label = { Text("全部 ${current.chunks.size}") }
                            )
                            usageTags.forEach { tag ->
                                FilterChip(
                                    selected = selectedTagId == tag.id,
                                    onClick = {
                                        selectedTagId = if (selectedTagId == tag.id) null else tag.id
                                    },
                                    label = { Text("${tag.name} ${tagMatchCounts[tag.id] ?: 0}") }
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (selectedTagId == null) {
                                "当前显示全部 ${current.chunks.size} 段原文。"
                            } else {
                                "筛选「${usageTags.firstOrNull { it.id == selectedTagId }?.name ?: ""}」：" +
                                    "${visibleChunks.size} 段原文。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    if (current.chunks.isEmpty()) {
                        StyleStylesEmptyHint("该样本没有文本片段。")
                    } else if (visibleChunks.isEmpty()) {
                        StyleStylesEmptyHint("当前标签下没有片段。")
                    } else {
                        visibleChunks.forEach { chunk ->
                            val tags = current.tagsByChunk[chunk.id].orEmpty()
                            val expanded = chunk.id in expandedChunks
                            StyleChunkTagCard(
                                chunk = chunk,
                                tags = tags,
                                expanded = expanded,
                                onToggleExpand = {
                                    expandedChunks = if (expanded) expandedChunks - chunk.id
                                    else expandedChunks + chunk.id
                                }
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                    }

                    if (current.sample.styleProfileId != null && current.profileName != null) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { onOpenSamples(current.sample.styleProfileId) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("在样本管理中分析标签") }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showBindDialog) {
        StyleBindProfileDialog(
            profiles = profiles,
            currentProfileId = detail?.sample?.styleProfileId,
            onPick = { profileId ->
                showBindDialog = false
                vm.bindTo(profileId)
            },
            onDismiss = { showBindDialog = false }
        )
    }
}

/**
 * 选择样本所属档案：可选「未绑定」或任意现有档案。
 * 文风样本页的样本卡片与样本详情页共用此弹窗。
 */
@Composable
internal fun StyleBindProfileDialog(
    profiles: List<StyleProfile>,
    currentProfileId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择所属档案") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                StyleBindOptionRow(
                    title = "未绑定",
                    subtitle = "保留原文、片段、向量与标签，但不参与任何文风检索",
                    selected = currentProfileId == null,
                    onClick = { onPick(null) }
                )
                HorizontalDivider()
                if (profiles.isEmpty()) {
                    StyleStylesEmptyHint("还没有文风档案，请先在「档案」页签新建。")
                } else {
                    profiles.forEach { profile ->
                        StyleBindOptionRow(
                            title = profile.name,
                            subtitle = "绑定后可使用该档案的标签与样本检索",
                            selected = currentProfileId == profile.id,
                            onClick = { onPick(profile.id) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun StyleBindOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "当前所属档案",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun StyleChunkTagCard(
    chunk: StyleSampleChunk,
    tags: List<StyleTag>,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val previewLimit = 80
    val text = chunk.text.trim()
    val preview = if (text.length <= previewLimit) text else text.take(previewLimit) + "…"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 ${chunk.chunkIndex + 1} 段",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (tags.isEmpty()) "尚未标记" else "标签 ${tags.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tags.isEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (expanded) text else preview,
                style = MaterialTheme.typography.bodySmall
            )
            if (text.length > previewLimit) {
                TextButton(
                    onClick = onToggleExpand,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) { Text(if (expanded) "收起" else "查看全文") }
            }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                tags.forEach { tag ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            tag.name,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        StyleSourceBadge(source = tag.source)
                    }
                }
            }
        }
    }
}

/** 标签来源徽标：AI 创建 / 用户创建 */
@Composable
internal fun StyleSourceBadge(source: String) {
    val ai = source == StyleTagSource.AI
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (ai) MaterialTheme.colorScheme.tertiaryContainer
        else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            if (ai) "AI" else "用户",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
