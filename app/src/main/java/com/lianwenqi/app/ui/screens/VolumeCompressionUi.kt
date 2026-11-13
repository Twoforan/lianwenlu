package com.lianwenqi.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lianwenqi.app.AppContainer
import com.lianwenqi.app.data.CompressionOutlineScopeMode
import com.lianwenqi.app.data.CompressionOutlineUpdateMode
import com.lianwenqi.app.data.CompressionSourceMode
import com.lianwenqi.app.data.VolumeCompressionConfig
import com.lianwenqi.app.data.VolumeSummaryFreshness
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeOutlineSuggestion
import com.lianwenqi.app.data.entity.VolumeOutlineUpdate
import com.lianwenqi.app.data.entity.VolumeCompressionErrorKind
import com.lianwenqi.app.data.entity.VolumeCompressionPhase
import com.lianwenqi.app.data.entity.VolumeCompressionSession
import com.lianwenqi.app.data.entity.VolumeCompressionStatus
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.entity.VolumeSummarySourceType
import com.lianwenqi.app.data.entity.VolumeSummaryStatus
import com.lianwenqi.app.data.volume.CompressionAudit
import com.lianwenqi.app.data.volume.CompressionEstimate
import com.lianwenqi.app.data.volume.CompressionFailure
import com.lianwenqi.app.data.volume.CompressionStartResult
import com.lianwenqi.app.data.volume.CompressionEstimateResult
import com.lianwenqi.app.data.volume.OutlineApplySummary
import com.lianwenqi.app.data.volume.OutlineScopeResolved
import com.lianwenqi.app.data.volume.OutlineUpdateStatus
import com.lianwenqi.app.data.volume.OutlineScopeResolver
import com.lianwenqi.app.data.volume.VolumeCompressionInputHash
import com.lianwenqi.app.data.volume.VolumeCompressionRequest
import com.lianwenqi.app.data.volume.hasOpenSuggestions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** BookDetail 的分卷压缩状态机；只保存派生状态，真相来自 F1 / F5 数据层 */
class VolumeCompressionViewModel(
    private val container: AppContainer,
    private val bookId: Long
) : ViewModel() {
    private val coordinator = container.volumeCompressionCoordinator
    private val summaries = container.volumeSummaryRepository
    private val prefs = container.prefs
    private val configRepository = container.configRepository

    val config: StateFlow<VolumeCompressionConfig> = prefs.compressionConfig.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        VolumeCompressionConfig.DEFAULT
    )

    val models: StateFlow<List<AiModel>> = configRepository.observeModels().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    val activeSummaries: StateFlow<List<VolumeSummary>> =
        summaries.observeActiveByBook(bookId).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    val sessions: StateFlow<List<VolumeCompressionSession>> =
        coordinator.observeSessions(bookId).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    /** F6：后台大纲差异分析状态；分卷入口据此提示差异处理中 */
    val outlineUpdates: StateFlow<List<VolumeOutlineUpdate>> =
        container.database.volumeOutlineDao().observeUpdatesByBook(bookId).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    /** F6：书籍维度的大纲建议；分卷入口据此区分「查看梗概」与「查看差异」 */
    val outlineSuggestionsByBook: StateFlow<List<VolumeOutlineSuggestion>> =
        container.database.volumeOutlineDao().observeSuggestionsByBook(bookId).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList()
        )

    private val _freshness = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val freshness: StateFlow<Map<Long, Boolean>> = _freshness

    /** F6：范围选择需要的真实大纲数据 */
    private val outlineRepository = container.outlineRepository
    private val outlineCoordinator = container.volumeOutlineUpdateCoordinator
    val outlineItems: StateFlow<List<OutlineItem>> = outlineRepository.observeOutline(bookId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    init {
        // 定期检查生效版是否过期；这里只做状态展示，绝不自动触发重压缩
        viewModelScope.launch {
            summaries.observeActiveByBook(bookId).collect { list ->
                val map = mutableMapOf<Long, Boolean>()
                list.forEach { summary ->
                    map[summary.id] = summaries.checkFreshness(summary.id)?.stale ?: true
                }
                _freshness.value = map
            }
        }
    }

    fun observeSession(id: Long): Flow<VolumeCompressionSession?> = coordinator.observeSession(id)

    suspend fun initialSeed(volumeId: Long): CompressionUiSeed {
        val active = coordinator.activeSession(volumeId)
        val summary = summaries.activeOfVolume(volumeId)
        val latest = coordinator.sessions(bookId)
            .filter { it.volumeId == volumeId }
            .maxByOrNull { it.createdAt }
        val outlineUpdate = (latest ?: active)?.let { outlineCoordinator.updateOf(it.id) }
        return CompressionUiSeed(active, summary, latest, outlineUpdate)
    }

    suspend fun outlineUpdateEnabled(sessionId: Long): Boolean {
        val session = coordinator.session(sessionId) ?: return false
        return VolumeCompressionConfig.decode(session.configSnapshot).outlineUpdateEnabled
    }

    /** 任务开始前按最新条目解析并冻结目标 ID 快照 */
    suspend fun resolveOutlineScope(snapshot: VolumeCompressionConfig): OutlineScopeResolved =
        OutlineScopeResolver.resolve(
            items = outlineRepository.getOutline(bookId),
            scopeMode = snapshot.outlineScopeMode
        )

    fun startOutlineUpdate(sessionId: Long) = outlineCoordinator.startAsync(sessionId)

    fun observeOutlineUpdate(sessionId: Long): Flow<VolumeOutlineUpdate?> =
        outlineCoordinator.observeUpdate(sessionId)

    fun observeOutlineSuggestions(sessionId: Long): Flow<List<VolumeOutlineSuggestion>> =
        container.database.volumeOutlineDao().observeSuggestionsBySession(sessionId)

    suspend fun outlineUpdateOf(sessionId: Long): VolumeOutlineUpdate? =
        outlineCoordinator.updateOf(sessionId)

    suspend fun outlineSuggestions(sessionId: Long): List<VolumeOutlineSuggestion> =
        outlineCoordinator.suggestionsOf(sessionId)

    /** 分卷压缩实际使用的模型：优先使用设置里的默认压缩模型，缺失时回退第一个可用模型 */
    fun resolveCompressionModelId(): Long =
        config.value.compressionModelId
            .takeIf { id -> models.value.any { it.id == id } }
            ?: models.value.firstOrNull()?.id
            ?: 0L

    suspend fun applyOutlineSuggestions(
        sessionId: Long,
        suggestionIds: List<Long>
    ): OutlineApplySummary = outlineCoordinator.applySelected(sessionId, suggestionIds)

    suspend fun dismissOutlineSuggestions(sessionId: Long) =
        outlineCoordinator.dismiss(sessionId)

    /** 预览中手动改写某条建议的新内容 */
    suspend fun updateOutlineSuggestionContent(
        sessionId: Long,
        suggestionId: Long,
        newContent: String
    ) = outlineCoordinator.updateSuggestionContent(sessionId, suggestionId, newContent)

    suspend fun estimate(
        volumeId: Long,
        source: String,
        modelId: Long,
        maxOutputTokens: Int,
        maxInputChars: Int,
        outputChars: Int
    ): CompressionEstimateResult =
        coordinator.estimate(
            VolumeCompressionRequest(
                bookId = bookId,
                volumeId = volumeId,
                modelId = modelId,
                sourceType = source,
                maxOutputTokensOverride = maxOutputTokens,
                maxInputChars = maxInputChars,
                targetOutputChars = outputChars
            )
        )

    suspend fun start(
        volumeId: Long,
        source: String,
        modelId: Long,
        snapshot: VolumeCompressionConfig,
        previousSessionId: Long?
    ): CompressionStartResult {
        // 大纲更新关闭时快照为空：任务期间零大纲请求，也不解析范围
        val scopeSnapshot = if (snapshot.outlineUpdateEnabled) {
            resolveOutlineScope(snapshot).encodeIds()
        } else {
            ""
        }
        return coordinator.start(
            request = VolumeCompressionRequest(
                bookId = bookId,
                volumeId = volumeId,
                modelId = modelId,
                sourceType = source,
                maxOutputTokensOverride = snapshot.normalized().maxOutputTokens,
                maxInputChars = snapshot.normalized().maxCharsPerRequest,
                targetOutputChars = snapshot.normalized().outputChars
            ),
            configSnapshot = VolumeCompressionConfig.encode(snapshot),
            previousSessionId = previousSessionId,
            outlineScopeSnapshot = scopeSnapshot
        )
    }

    suspend fun cancel(sessionId: Long) = coordinator.cancel(sessionId)

    /** 重试：直接复用原任务的来源、模型与配置快照重新排队，不再弹出配置向导 */
    suspend fun retry(sessionId: Long): Long? {
        val session = coordinator.session(sessionId) ?: return null
        // 旧任务还停在「待确认」时必须先丢弃，否则同卷去重会直接返回旧候选，导致未压缩就弹窗
        if (session.statusEnumValue == VolumeCompressionStatus.AWAITING_CONFIRMATION) {
            coordinator.discardCandidate(sessionId)
        }
        val snapshot = VolumeCompressionConfig.decode(session.configSnapshot)
        return when (val result = start(
            volumeId = session.volumeId,
            source = session.sourceType,
            modelId = session.modelId,
            snapshot = snapshot,
            previousSessionId = session.id
        )) {
            is CompressionStartResult.Started -> result.sessionId
            is CompressionStartResult.Existing -> result.sessionId
            is CompressionStartResult.Rejected -> null
        }
    }

    suspend fun resume(sessionId: Long) = coordinator.resume(sessionId)

    suspend fun confirm(sessionId: Long): Boolean = coordinator.confirmCandidate(sessionId)

    suspend fun discard(sessionId: Long): Boolean = coordinator.discardCandidate(sessionId)

    /** 候选不可直接编辑：先确认生效，再把用户编辑写到生效版；空文本恢复模型原文 */
    suspend fun confirmWithEdit(sessionId: Long, edited: String): Boolean {
        val session = coordinator.session(sessionId) ?: return false
        val candidateId = session.candidateSummaryId ?: return false
        val candidate = summaries.getDetail(candidateId) ?: return false
        if (!coordinator.confirmCandidate(sessionId)) return false
        val trimmed = edited.trim()
        val value = if (trimmed == candidate.modelContent.trim()) "" else edited
        if (value != candidate.userContent) summaries.saveUserContent(candidateId, value)
        return true
    }

    suspend fun summaryDetail(id: Long): VolumeSummary? = summaries.getDetail(id)

    suspend fun versions(volumeId: Long): List<VolumeSummary> = summaries.listVersions(volumeId)

    suspend fun freshnessOf(id: Long): VolumeSummaryFreshness? = summaries.checkFreshness(id)

    suspend fun sessionOf(id: Long?): VolumeCompressionSession? =
        if (id == null) null else coordinator.session(id)

    suspend fun saveUserContent(id: Long, text: String): Boolean {
        val summary = summaries.getDetail(id) ?: return false
        val value = if (text.trim() == summary.modelContent.trim()) "" else text
        return summaries.saveUserContent(id, value)
    }

    suspend fun restoreVersion(id: Long): Boolean = summaries.restoreVersion(id)

    suspend fun saveDefaultConfig(config: VolumeCompressionConfig) {
        prefs.setCompressionConfig(config.normalized())
    }
}

data class CompressionUiSeed(
    val activeSession: VolumeCompressionSession?,
    val activeSummary: VolumeSummary?,
    val latestSession: VolumeCompressionSession?,
    /** F6：最近一次大纲差异分析；用于恢复未确认的预览 */
    val outlineUpdate: VolumeOutlineUpdate? = null,
    /** 该差异是否还有未处理建议；决定入口是「查看大纲差异化」还是「查看整卷梗概」 */
    val outlineHasOpenSuggestions: Boolean = false
)

private sealed interface CompressionMode {
    object Loading : CompressionMode
    object Config : CompressionMode
    data class Progress(val sessionId: Long) : CompressionMode
    data class Candidate(val sessionId: Long) : CompressionMode
    data class Summary(val summaryId: Long) : CompressionMode
    data class Recover(val sessionId: Long) : CompressionMode
    data class Outline(val sessionId: Long) : CompressionMode
}

private sealed interface EstimateUi {
    object Loading : EstimateUi
    data class Ready(val estimate: CompressionEstimate, val audit: CompressionAudit) : EstimateUi
    data class NoInput(val audit: CompressionAudit) : EstimateUi
    data class Failed(val message: String, val audit: CompressionAudit) : EstimateUi
}

/** 分卷长按入口的标签；由真实任务 / 生效版 / 过期状态映射，不猜测 */
fun volumeCompressionLabel(
    seed: CompressionUiSeed?,
    stale: Boolean?
): String {
    val active = seed?.activeSession
    if (active != null) {
        return when (active.statusEnumValue) {
            VolumeCompressionStatus.AWAITING_CONFIRMATION -> "压缩本卷 · 待确认"
            VolumeCompressionStatus.RUNNING -> "压缩本卷 · 压缩中"
            VolumeCompressionStatus.PENDING -> "压缩本卷 · 排队中"
            else -> "压缩本卷"
        }
    }
    val summary = seed?.activeSummary
    if (summary != null) {
        // 分析中提示回来确认；分析结束且仍有未处理建议时直接进入差异，否则先看梗概
        when (seed?.outlineUpdate?.status) {
            OutlineUpdateStatus.ANALYZING -> return "确认大纲差异化中"
            OutlineUpdateStatus.READY ->
                if (seed?.outlineHasOpenSuggestions == true) return "查看大纲差异化"
        }
        return if (stale == true) "查看整卷梗概 · 已过期" else "查看整卷梗概"
    }
    val latest = seed?.latestSession
    if (latest != null && latest.statusEnumValue in listOf(
            VolumeCompressionStatus.FAILED,
            VolumeCompressionStatus.CANCELLED
        )
    ) {
        return "压缩本卷 · 上次失败"
    }
    return "压缩本卷"
}

/**
 * 长按分卷后的压缩入口宿主：配置 → 进度 → 候选 → 梗概页。
 * 所有耗时操作都在任务协调器 / 仓库里执行，本组件只负责展示与把用户决定转成调用。
 */
@Composable
fun VolumeCompressionHost(
    volume: Volume,
    chapters: List<Chapter>,
    vm: VolumeCompressionViewModel,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val config by vm.config.collectAsState()
    val models by vm.models.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val activeSummaries by vm.activeSummaries.collectAsState()
    val freshness by vm.freshness.collectAsState()

    var mode by remember { mutableStateOf<CompressionMode>(CompressionMode.Loading) }
    var seed by remember { mutableStateOf<CompressionUiSeed?>(null) }
    var previousSessionId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(volume.id, sessions.size, activeSummaries.size) {
        if (seed == null) {
            seed = vm.initialSeed(volume.id)
        }
        if (mode == CompressionMode.Loading) {
            val current = seed ?: vm.initialSeed(volume.id)
            val outline = current.outlineUpdate
            val outlineSessionId = outline?.sessionId
                ?: current.latestSession?.takeIf { it.statusEnumValue == VolumeCompressionStatus.COMPLETED }?.id
            mode = when {
                current.activeSession?.statusEnumValue ==
                    VolumeCompressionStatus.AWAITING_CONFIRMATION ->
                    CompressionMode.Candidate(current.activeSession.id)
                current.activeSession != null -> CompressionMode.Progress(current.activeSession.id)
                // F6：有未处理建议时直接看差异；否则先进梗概，确认后再跳转差异
                outlineSessionId != null && outline?.status == "ANALYZING" ->
                    CompressionMode.Outline(outlineSessionId)
                outlineSessionId != null && outline?.status == "FAILED" ->
                    CompressionMode.Outline(outlineSessionId)
                outlineSessionId != null && outline?.status == "READY" &&
                    vm.outlineSuggestions(outlineSessionId).hasOpenSuggestions() ->
                    CompressionMode.Outline(outlineSessionId)
                current.activeSummary != null -> CompressionMode.Summary(current.activeSummary.id)
                current.latestSession != null && current.latestSession.statusEnumValue in listOf(
                    VolumeCompressionStatus.FAILED,
                    VolumeCompressionStatus.CANCELLED
                ) -> CompressionMode.Recover(current.latestSession.id)
                // 手动生成入口始终先展示配置；「勾选卷梗概时自动压缩」只作用于上下文勾选
                else -> CompressionMode.Config
            }
        }
    }

    when (val current = mode) {
        CompressionMode.Loading -> Unit

        CompressionMode.Config -> CompressionConfigDialog(
            volume = volume,
            chapters = chapters,
            config = config,
            modelId = models.firstOrNull { it.id == config.compressionModelId }?.id
                ?: models.firstOrNull()?.id
                ?: 0L,
            previousSessionId = previousSessionId,
            onDismiss = onDismiss,
            onStarted = { sessionId ->
                previousSessionId = null
                seed = null
                mode = CompressionMode.Progress(sessionId)
            },
            estimate = { source, modelId ->
                vm.estimate(
                    volume.id,
                    source,
                    modelId,
                    config.normalized().maxOutputTokens,
                    config.normalized().maxCharsPerRequest,
                    config.normalized().outputChars
                )
            },
            start = { source, modelId, snapshot ->
                vm.start(volume.id, source, modelId, snapshot, previousSessionId)
            }
        )

        is CompressionMode.Progress -> {
            val context = LocalContext.current
            LaunchedEffect(current.sessionId) {
                Toast.makeText(context, "正在压缩分卷中…", Toast.LENGTH_SHORT).show()
            }
            CompressionProgressDialog(
                sessionId = current.sessionId,
                observeSession = { vm.observeSession(it) },
                onDismiss = onDismiss,
                onCancel = { scope.launch { vm.cancel(current.sessionId) } },
                onResume = { scope.launch { vm.resume(current.sessionId) } },
                onOpenCandidate = { mode = CompressionMode.Candidate(current.sessionId) },
                onWaitingConfirmation = { mode = CompressionMode.Candidate(current.sessionId) }
            )
        }

        is CompressionMode.Candidate -> CompressionCandidateDialog(
            session = sessions.firstOrNull { it.id == current.sessionId }
                ?: seed?.activeSession?.takeIf { it.id == current.sessionId },
            loadSession = { vm.sessionOf(it) },
            loadSummary = { vm.summaryDetail(it) },
            onDismiss = onDismiss,
            onConfirm = { sessionId, edited ->
                scope.launch {
                    if (vm.confirmWithEdit(sessionId, edited)) {
                        seed = null
                        // 确认卷梗概后才可能触发大纲分析；关闭模式不会产生任何请求
                        if (vm.outlineUpdateEnabled(sessionId)) {
                            vm.startOutlineUpdate(sessionId)
                            mode = CompressionMode.Outline(sessionId)
                        } else {
                            onDismiss()
                        }
                    }
                }
            },
            onDiscard = { sessionId ->
                scope.launch {
                    vm.discard(sessionId)
                    seed = null
                    onDismiss()
                }
            },
            onRecompress = { sessionId ->
                scope.launch {
                    val newId = vm.retry(sessionId)
                    if (newId != null) {
                        seed = null
                        previousSessionId = null
                        mode = CompressionMode.Progress(newId)
                    }
                }
            }
        )

        is CompressionMode.Summary -> CompressionSummaryDialog(
            summaryId = current.summaryId,
            volume = volume,
            loadSummary = { vm.summaryDetail(it) },
            loadFreshness = { vm.freshnessOf(it) },
            loadVersions = { vm.versions(volume.id) },
            loadSession = { vm.sessionOf(it) },
            onDismiss = onDismiss,
            onConfirmed = { sessionId ->
                scope.launch {
                    // 大纲差异与梗概同属一次压缩任务，确认梗概后默认落到对应差异
                    val update = sessionId?.let { vm.outlineUpdateOf(it) }
                    if (update != null) {
                        seed = null
                        mode = CompressionMode.Outline(update.sessionId)
                    } else {
                        onDismiss()
                    }
                }
            },
            onSave = { id, text -> vm.saveUserContent(id, text) },
            onRestore = { id -> vm.restoreVersion(id) },
            onRecompress = { sessionId ->
                previousSessionId = sessionId
                seed = null
                mode = CompressionMode.Config
            }
        )

        is CompressionMode.Outline -> {
            var update by remember(current.sessionId) { mutableStateOf<VolumeOutlineUpdate?>(null) }
            var suggestions by remember(current.sessionId) {
                mutableStateOf<List<VolumeOutlineSuggestion>>(emptyList())
            }
            LaunchedEffect(current.sessionId) {
                launch {
                    vm.observeOutlineUpdate(current.sessionId).collect { update = it }
                }
                launch {
                    vm.observeOutlineSuggestions(current.sessionId).collect { suggestions = it }
                }
            }
            OutlineDiffDialog(
                sessionId = current.sessionId,
                update = update,
                suggestions = suggestions,
                onApply = { ids -> vm.applyOutlineSuggestions(current.sessionId, ids) },
                onEditContent = { id, content ->
                    vm.updateOutlineSuggestionContent(current.sessionId, id, content)
                },
                onRetry = {
                    update = update?.copy(status = "ANALYZING")
                    vm.startOutlineUpdate(current.sessionId)
                },
                onLater = onDismiss,
                onDiscard = { vm.dismissOutlineSuggestions(current.sessionId) }
            )
        }

        is CompressionMode.Recover -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("上次整卷压缩未完成") },
            text = {
                val latest = seed?.latestSession
                Text(
                    latest?.errorMessage?.ifBlank { "任务已停止" }
                        ?: "任务已停止，可恢复继续；已完成批次不会重复请求。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = current.sessionId
                    previousSessionId = id
                    seed = null
                    mode = CompressionMode.Config
                }) { Text("重新开始") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            vm.resume(current.sessionId)
                            seed = null
                            mode = CompressionMode.Progress(current.sessionId)
                        }
                    }) { Text("恢复继续") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        )
    }
}

@Composable
private fun CompressionConfigDialog(
    volume: Volume,
    chapters: List<Chapter>,
    config: VolumeCompressionConfig,
    modelId: Long,
    previousSessionId: Long?,
    estimate: suspend (String, Long) -> CompressionEstimateResult,
    start: suspend (String, Long, VolumeCompressionConfig) -> CompressionStartResult,
    onStarted: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val sourceFixed = CompressionSourceMode.normalize(config.source) != CompressionSourceMode.ASK
    var source by remember {
        mutableStateOf(
            if (sourceFixed) config.source else VolumeSummarySourceType.SYNOPSIS
        )
    }
    var estimateState by remember { mutableStateOf<EstimateUi>(EstimateUi.Loading) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(source, modelId, config.maxOutputTokens, config.maxCharsPerRequest) {
        if (modelId == 0L) {
            estimateState = EstimateUi.Failed(
                "请先在设置中配置模型",
                CompressionAudit(0, 0, 0, emptyList(), emptyList(), "", emptyList())
            )
            return@LaunchedEffect
        }
        estimateState = EstimateUi.Loading
        estimateState = when (val result = estimate(source, modelId)) {
            is CompressionEstimateResult.Ready -> EstimateUi.Ready(result.estimate, result.audit)
            is CompressionEstimateResult.NoInput -> EstimateUi.NoInput(result.audit)
            is CompressionEstimateResult.Failed -> EstimateUi.Failed(result.failure.message, result.audit)
        }
    }

    val ready = estimateState as? EstimateUi.Ready
    val audit = when (val state = estimateState) {
        is EstimateUi.Ready -> state.audit
        is EstimateUi.NoInput -> state.audit
        is EstimateUi.Failed -> state.audit
        EstimateUi.Loading -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("压缩 ${volume.title}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                val includedNow = audit?.includedChapterCount ?: 0
                val missingNow = audit?.missingChapterCount ?: 0
                val missingIds = audit?.missingChapterIds.orEmpty().toSet()
                val missingTitles = chapters.filter { it.id in missingIds }
                    .map { it.title }
                val coverageText = buildString {
                    append("共 ").append(chapters.size).append(" 章；有效内容 ")
                    append(includedNow).append(" 章")
                    if (missingNow > 0) {
                        append("，缺失 ").append(missingNow).append(" 章（将跳过）：")
                        append(
                            if (missingTitles.isEmpty()) {
                                missingIds.take(5).joinToString("、") { "id=$it" }
                            } else {
                                missingTitles.take(6).joinToString("、") +
                                    if (missingTitles.size > 6) " 等" else ""
                            }
                        )
                    }
                }
                Text(
                    coverageText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!sourceFixed) {
                    Spacer(Modifier.height(12.dp))
                    Text("本次来源", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        VolumeSummarySourceType.SYNOPSIS,
                        VolumeSummarySourceType.CONTENT
                    ).forEach { value ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = source == value, onClick = { source = value })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = source == value, onClick = { source = value })
                            Text(CompressionSourceMode.label(value))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("预计规模（字符启发式，不是真实计费）", style = MaterialTheme.typography.titleSmall)
                when (val state = estimateState) {
                    EstimateUi.Loading -> Text("正在估算…", style = MaterialTheme.typography.bodySmall)
                    is EstimateUi.NoInput -> Text(
                        "没有可参与压缩的有效内容，无法开始",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    is EstimateUi.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    is EstimateUi.Ready -> {
                        Text(
                            "预计输入 ${state.estimate.inputTokens} Token · 预计输出 ${state.estimate.outputTokens} Token",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "最终梗概目标约 ${state.estimate.plan.outputTokenBudget} 字",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "提取 ${state.estimate.batchCount} 批 · 预计共 ${state.estimate.requestCount} 次请求 · " +
                                "整合 ${state.estimate.plan.plannedMergeRoundCount} 轮",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.estimate.plan.slicedChapterCount > 0) {
                            Text(
                                "${state.estimate.plan.slicedChapterCount} 个超长章节会被无损切片，片段数不等于章节数",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (error.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = ready != null && !starting && modelId > 0,
                onClick = {
                    starting = true
                    error = ""
                    val snapshot = config.copy(source = source).normalized()
                    scope.launch {
                        when (val result = start(source, modelId, snapshot)) {
                            is CompressionStartResult.Started -> onStarted(result.sessionId)
                            is CompressionStartResult.Existing -> onStarted(result.sessionId)
                            is CompressionStartResult.Rejected -> {
                                starting = false
                                error = result.message
                            }
                        }
                    }
                }
            ) { Text(if (starting) "正在开始…" else "开始压缩") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun CompressionProgressDialog(
    sessionId: Long,
    observeSession: (Long) -> Flow<VolumeCompressionSession?>,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onOpenCandidate: () -> Unit,
    onWaitingConfirmation: () -> Unit
) {
    var session by remember { mutableStateOf<VolumeCompressionSession?>(null) }
    LaunchedEffect(sessionId) {
        observeSession(sessionId).collect { value ->
            session = value
            if (value?.statusEnumValue == VolumeCompressionStatus.AWAITING_CONFIRMATION) {
                onWaitingConfirmation()
            }
        }
    }
    val current = session
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (current?.statusEnumValue) {
                    VolumeCompressionStatus.PENDING -> "整卷压缩 · 排队中"
                    VolumeCompressionStatus.RUNNING -> "整卷压缩 · 进行中"
                    VolumeCompressionStatus.FAILED -> "整卷压缩 · 失败"
                    VolumeCompressionStatus.CANCELLED -> "整卷压缩 · 已取消"
                    else -> "整卷压缩"
                }
            )
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (current == null) {
                    Text("正在读取任务…")
                } else {
                    Text(phaseLabel(current), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "提取批次 ${current.completedBatchCount}/${current.plannedBatchCount}" +
                            if (current.queuePosition > 0) " · 队列第 ${current.queuePosition} 位" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "预计请求 ${current.plannedRequestCount} 次 · 实际请求 ${current.actualRequestCount} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (current.statusEnumValue == VolumeCompressionStatus.RUNNING ||
                        current.statusEnumValue == VolumeCompressionStatus.PENDING
                    ) {
                        Spacer(Modifier.height(8.dp))
                        if (current.plannedBatchCount > 0) {
                            val fraction =
                                (current.completedBatchCount.toFloat() / current.plannedBatchCount)
                                    .coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("真实用量（来自服务商返回）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "输入 ${current.promptTokens} · 输出 ${current.completionTokens} · 缓存 ${current.cachedTokens}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (current.requestsWithUsage < current.actualRequestCount) {
                        Text(
                            "其中 ${current.actualRequestCount - current.requestsWithUsage} 次未返回用量，未虚报 Token",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "预计输入 ${current.estimatedInputTokens} · 预计输出 ${current.estimatedOutputTokens}（仅估算）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (current.errorMessage.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            current.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (current?.statusEnumValue) {
                VolumeCompressionStatus.FAILED,
                VolumeCompressionStatus.CANCELLED -> TextButton(onClick = onResume) { Text("恢复继续") }
                VolumeCompressionStatus.AWAITING_CONFIRMATION -> TextButton(onClick = onOpenCandidate) {
                    Text("查看结果")
                }
                else -> TextButton(onDismiss) { Text("后台运行") }
            }
        },
        dismissButton = {
            Row {
                if (current?.statusEnumValue == VolumeCompressionStatus.RUNNING ||
                    current?.statusEnumValue == VolumeCompressionStatus.PENDING
                ) {
                    TextButton(onClick = onCancel) { Text("取消任务") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun CompressionCandidateDialog(
    session: VolumeCompressionSession?,
    loadSession: suspend (Long) -> VolumeCompressionSession?,
    loadSummary: suspend (Long) -> VolumeSummary?,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit,
    onDiscard: (Long) -> Unit,
    onRecompress: (Long) -> Unit
) {
    var latest by remember { mutableStateOf(session) }
    var candidate by remember { mutableStateOf<VolumeSummary?>(null) }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val sessionId = session?.id

    LaunchedEffect(session?.id, session?.candidateSummaryId) {
        val id = session?.id ?: return@LaunchedEffect
        val fresh = loadSession(id)
        if (fresh != null) latest = fresh
        val candidateId = fresh?.candidateSummaryId
        if (candidateId != null) {
            candidate = loadSummary(candidateId)
            text = candidate?.effectiveContent ?: ""
        }
    }

    val current = latest ?: session
    if (current == null) {
        onDismiss()
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("压缩完成 · 待确认", modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        },
        text = {
            Column {
                Text(
                    "只有确认后才会替换当前生效的整卷梗概；放弃不会影响旧版本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (candidate == null) {
                    Text("正在读取候选版本…")
                } else {
                    Text(
                        "来源：${CompressionSourceMode.label(candidate!!.sourceType)} · " +
                            "模型：${candidate!!.modelName.ifBlank { "未知" }}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "参与 ${candidate!!.processedChapterCount}/${candidate!!.sourceChapterCount} 章 · " +
                            "生成于 ${formatTime(candidate!!.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    val hitRate = if (current.promptTokens > 0) {
                        "${(current.cachedTokens * 100 / current.promptTokens)}%"
                    } else {
                        "—"
                    }
                    val usageLine = "真实用量：输入 ${current.promptTokens} · 输出 ${current.completionTokens} · " +
                        "缓存命中率 $hitRate · 请求 ${current.actualRequestCount} 次"
                    if (editing) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            label = { Text("确认前可编辑（仅影响生效内容，不改模型原文）") }
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            usageLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // 固定高度预览，内容在自己的框内滚动，用量附在梗概末尾
                        Surface(
                            Modifier.fillMaxWidth().height(220.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text.ifBlank { candidate!!.effectiveContent },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.height(12.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    usageLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editing) {
                // 编辑态只保留「完成」与「放弃」，确认需退回预览后再操作
                Button(onClick = { editing = false }) { Text("完成") }
            } else {
                Button(
                    enabled = candidate != null,
                    onClick = { onConfirm(current.id, text) }
                ) { Text("确认") }
            }
        },
        dismissButton = {
            Row {
                if (editing) {
                    TextButton(onClick = {
                        text = candidate?.effectiveContent ?: ""
                        editing = false
                    }) { Text("放弃") }
                } else {
                    TextButton(onClick = { onDiscard(current.id) }) { Text("放弃") }
                    TextButton(onClick = { editing = true }) { Text("编辑") }
                    TextButton(onClick = { onRecompress(current.id) }) { Text("重试") }
                }
            }
        }
    )
}

@Composable
private fun CompressionSummaryDialog(
    summaryId: Long,
    volume: Volume,
    loadSummary: suspend (Long) -> VolumeSummary?,
    loadFreshness: suspend (Long) -> VolumeSummaryFreshness?,
    loadVersions: suspend (Long) -> List<VolumeSummary>,
    loadSession: suspend (Long?) -> VolumeCompressionSession?,
    onDismiss: () -> Unit,
    onConfirmed: (Long?) -> Unit,
    onSave: suspend (Long, String) -> Boolean,
    onRestore: suspend (Long) -> Boolean,
    onRecompress: (Long?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<VolumeSummary?>(null) }
    var freshness by remember { mutableStateOf<VolumeSummaryFreshness?>(null) }
    var versions by remember { mutableStateOf<List<VolumeSummary>>(emptyList()) }
    var session by remember { mutableStateOf<VolumeCompressionSession?>(null) }
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var unsavedPrompt by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<VolumeSummary?>(null) }

    LaunchedEffect(summaryId) {
        summary = loadSummary(summaryId)
        freshness = loadFreshness(summaryId)
        session = loadSession(summary?.compressionSessionId)
        versions = loadVersions(volume.id)
        text = summary?.effectiveContent ?: ""
        loaded = true
    }

    val current = summary ?: return
    val dirty = loaded && text != current.effectiveContent
    val missing = freshness?.missingChapterCount ?: 0
    val stale = freshness?.stale ?: false
    val usage = session
    val hitRate = if ((usage?.promptTokens ?: 0) > 0) {
        "${usage!!.cachedTokens * 100 / usage!!.promptTokens}%"
    } else {
        "—"
    }
    val usageLine = if (usage == null) {
        "真实用量：无记录"
    } else {
        "真实用量：输入 ${usage.promptTokens} · 输出 ${usage.completionTokens} · " +
            "缓存命中率 $hitRate · 请求 ${usage.actualRequestCount} 次"
    }

    fun closeWithGuard() {
        if (editing && dirty) unsavedPrompt = true else onDismiss()
    }

    fun saveAndExitEdit() {
        scope.launch {
            if (!dirty || onSave(current.id, text)) {
                summary = loadSummary(current.id)
                text = summary?.effectiveContent ?: text
                versions = loadVersions(volume.id)
                editing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { closeWithGuard() },
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "整卷梗概 · ${volume.title}",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { closeWithGuard() }) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        },
        text = {
            Column {
                Text(
                    "来源：${CompressionSourceMode.label(current.sourceType)} · " +
                        "模型：${current.modelName.ifBlank { "未知" }}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "参与 ${current.processedChapterCount}/${current.sourceChapterCount} 章 · " +
                        "生成于 ${formatTime(current.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (current.userContent.isNotBlank()) "当前展示：你编辑的内容" else "当前展示：模型原文",
                    style = MaterialTheme.typography.bodySmall
                )
                if (stale || missing > 0) {
                    Text(
                        buildString {
                            append("已过期：章节内容或目录已变化")
                            if (missing > 0) append("；缺失 $missing 章")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (editing) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        label = { Text("直接编辑生效内容") }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        usageLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // 与候选确认弹窗一致：固定高度预览，内容在框内滚动，用量附在梗概末尾
                    Surface(
                        Modifier.fillMaxWidth().height(220.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            Text(current.effectiveContent, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                            Text(
                                usageLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (editing) {
                Button(onClick = { saveAndExitEdit() }) { Text("完成") }
            } else {
                Button(onClick = {
                    val sessionId = current.compressionSessionId
                    if (dirty) {
                        scope.launch { if (onSave(current.id, text)) onConfirmed(sessionId) }
                    } else {
                        onConfirmed(sessionId)
                    }
                }) { Text("确认") }
            }
        },
        dismissButton = {
            Row {
                if (editing) {
                    TextButton(onClick = {
                        text = current.effectiveContent
                        editing = false
                    }) { Text("放弃") }
                } else {
                    TextButton(onClick = { showHistory = true }) { Text("历史") }
                    TextButton(onClick = {
                        text = current.effectiveContent
                        editing = true
                    }) { Text("编辑") }
                    TextButton(onClick = { onRecompress(current.compressionSessionId) }) { Text("重试") }
                }
            }
        }
    )

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("历史版本") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (versions.isEmpty()) {
                        Text("暂无历史版本", style = MaterialTheme.typography.bodySmall)
                    } else {
                        versions.forEach { version ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        if (version.status == VolumeSummaryStatus.ACTIVE) "当前生效" else "归档版本",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "${formatTime(version.createdAt)} · " +
                                            "${version.processedChapterCount} 章 · " +
                                            (if (version.userContent.isNotBlank()) "含用户编辑" else "模型原文"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (version.status == VolumeSummaryStatus.ARCHIVED) {
                                    TextButton(onClick = {
                                        showHistory = false
                                        restoreTarget = version
                                    }) { Text("恢复") }
                                } else {
                                    Text("生效中", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistory = false }) { Text("关闭") }
            }
        )
    }

    if (restoreTarget != null) {
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("恢复这个历史版本？") },
            text = { Text("当前生效版本会转为归档；如果本次有未保存的编辑，将先提示保存。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = restoreTarget
                    restoreTarget = null
                    scope.launch {
                        if (dirty) {
                            onSave(current.id, text)
                        }
                        if (target != null && onRestore(target.id)) {
                            summary = loadSummary(target.id)
                            freshness = loadFreshness(target.id)
                            versions = loadVersions(volume.id)
                            text = summary?.effectiveContent ?: ""
                        }
                    }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { restoreTarget = null }) { Text("取消") } }
        )
    }

    if (unsavedPrompt) {
        AlertDialog(
            onDismissRequest = { unsavedPrompt = false },
            title = { Text("有未保存的编辑") },
            text = { Text("直接关闭将丢失本次编辑，是否先保存？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        onSave(current.id, text)
                        unsavedPrompt = false
                        onDismiss()
                    }
                }) { Text("保存并关闭") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        unsavedPrompt = false
                        onDismiss()
                    }) { Text("放弃编辑") }
                    TextButton(onClick = { unsavedPrompt = false }) { Text("继续编辑") }
                }
            }
        )
    }
}

private fun phaseLabel(session: VolumeCompressionSession): String = when (session.phaseEnumValue) {
    VolumeCompressionPhase.QUEUED -> "排队等待中"
    VolumeCompressionPhase.READING -> "正在读取章节"
    VolumeCompressionPhase.EXTRACTING -> "正在分批提取"
    VolumeCompressionPhase.MERGING -> "正在整合全卷"
    VolumeCompressionPhase.AWAITING_CONFIRMATION -> "等待确认候选"
    VolumeCompressionPhase.DONE -> when (session.statusEnumValue) {
        VolumeCompressionStatus.COMPLETED -> "已完成"
        VolumeCompressionStatus.CANCELLED -> "已取消"
        VolumeCompressionStatus.FAILED -> interruptedLabel(session)
        else -> "已结束"
    }
    else -> "进行中"
}

private fun interruptedLabel(session: VolumeCompressionSession): String =
    if (session.errorKind == VolumeCompressionErrorKind.INTERRUPTED) "已中断，可恢复" else "已失败"

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

/** 配置弹窗里的单选项行；与来源选项保持同一种交互 */
@Composable
private fun ConfigRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
