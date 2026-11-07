package com.lianwenqi.app.data.volume

import android.util.Log
import androidx.room.withTransaction
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.CompressionOutlineScopeMode
import com.lianwenqi.app.data.CompressionOutlineUpdateMode
import com.lianwenqi.app.data.OUTLINE_HEAD_LABEL
import com.lianwenqi.app.data.OutlineRepository
import com.lianwenqi.app.data.OutlineWriteCommand
import com.lianwenqi.app.data.OutlineWriteOutcome
import com.lianwenqi.app.data.Prefs
import com.lianwenqi.app.data.VolumeCompressionConfig
import com.lianwenqi.app.data.VolumeSummaryRepository
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.data.dao.VolumeOutlineDao
import com.lianwenqi.app.data.entity.VolumeOutlineSuggestion
import com.lianwenqi.app.data.entity.VolumeOutlineUpdate
import com.lianwenqi.app.data.entity.defaultOutlineKeys
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.data.volume.OutlineSuggestionStatus
import com.lianwenqi.app.data.volume.OutlineUpdateStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * F6：压缩完成后的大纲差异分析与安全写入协调器。
 *
 * 生命周期：
 * - 只在用户确认卷梗概之后启动（放弃候选不会触发任何大纲请求）；
 * - 模式来自 session 的配置快照，之后修改全局设置不会影响本次任务；
 * - 分析结果和建议持久化，页面 / 进程切换后可恢复；
 * - 「开」只自动写入通过安全校验的 ADD / UPDATE，DELETE 永远留给用户确认。
 */
class VolumeOutlineUpdateCoordinator(
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val outlines: OutlineRepository,
    private val summaries: VolumeSummaryRepository,
    private val diffService: OutlineDiffService,
    private val compression: VolumeCompressionCoordinator,
    private val scope: CoroutineScope
) {
    private val dao: VolumeOutlineDao = db.volumeOutlineDao()

    private val jobs = ConcurrentHashMap<Long, Job>()

    /** 同一 session 的启动 / 重试串行化，避免重复请求和重复扣费 */
    private val actionMutex = Mutex()

    fun observeUpdate(sessionId: Long): Flow<VolumeOutlineUpdate?> = dao.observeUpdate(sessionId)

    fun observeSuggestions(updateId: Long): Flow<List<VolumeOutlineSuggestion>> =
        dao.observeSuggestions(updateId)

    suspend fun updateOf(sessionId: Long): VolumeOutlineUpdate? = dao.updateBySession(sessionId)

    suspend fun suggestionsOf(sessionId: Long): List<VolumeOutlineSuggestion> =
        dao.suggestionsBySession(sessionId)

    /** 异步启动分析；页面关闭不影响任务（使用应用级 scope） */
    fun startAsync(sessionId: Long) {
        if (jobs[sessionId]?.isActive == true) return
        val job = scope.launch {
            try {
                analyze(sessionId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("VolumeOutline", "大纲差异分析异常", t)
                markFailed(sessionId, t.message ?: "大纲差异分析异常终止")
            } finally {
                jobs.remove(sessionId)
            }
        }
        jobs[sessionId] = job
    }

    /** 应用启动：进程中断后遗留的 ANALYZING 标记为失败，可重新发起 */
    suspend fun recoverInterrupted() {
        dao.markAnalyzingInterrupted(System.currentTimeMillis())
    }

    private suspend fun analyze(sessionId: Long) = actionMutex.withLock {
        val session = compression.session(sessionId) ?: return@withLock
        val config = VolumeCompressionConfig.decode(session.configSnapshot)
        if (!config.outlineUpdateEnabled) return@withLock
        if (session.candidateSummaryId == null) return@withLock

        val existing = dao.updateBySession(sessionId)
        if (existing != null &&
            (existing.status == OutlineUpdateStatus.ANALYZING ||
                existing.status == OutlineUpdateStatus.READY)
        ) {
            return@withLock
        }

        val now = System.currentTimeMillis()
        val resolved = resolveScopeSnapshot(session.outlineScopeSnapshot, session.bookId, config)
        val scopeIds = resolved.itemIds
        val updateId = if (existing == null) {
            dao.insertUpdate(
                VolumeOutlineUpdate(
                    sessionId = sessionId,
                    bookId = session.bookId,
                    volumeId = session.volumeId,
                    mode = CompressionOutlineUpdateMode.normalize(config.outlineUpdateMode),
                    status = OutlineUpdateStatus.ANALYZING,
                    scopeIds = scopeIds.joinToString(","),
                    scopeLabel = scopeLabel(config, scopeIds.size),
                    summaryId = session.candidateSummaryId,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            dao.deleteSuggestions(existing.id)
            val reset = existing.copy(
                mode = CompressionOutlineUpdateMode.normalize(config.outlineUpdateMode),
                status = OutlineUpdateStatus.ANALYZING,
                scopeIds = scopeIds.joinToString(","),
                scopeLabel = scopeLabel(config, scopeIds.size),
                summaryId = session.candidateSummaryId,
                promptTokens = 0,
                completionTokens = 0,
                cachedTokens = 0,
                requestCount = 0,
                requestsWithUsage = 0,
                rawResponse = "",
                errorMessage = "",
                note = "",
                updatedAt = now
            )
            dao.updateUpdate(reset)
            reset.id
        }

        // 范围为空：不产生任何模型请求，明确告知用户
        if (scopeIds.isEmpty()) {
            saveReady(
                updateId,
                "本次范围没有可同步的大纲条目",
                "",
                CompressionUsage()
            )
            return@withLock
        }

        val summary = summaries.getDetail(session.candidateSummaryId) ?: run {
            markFailed(sessionId, "找不到已确认的卷梗概，无法进行大纲差异分析")
            return@withLock
        }
        val book = db.bookDao().getById(session.bookId)
        val volume = db.volumeDao().getById(session.volumeId)
        val allItems = outlines.getByIds(scopeIds).filter { it.bookId == session.bookId }
        val scopedItems = scopeIds.mapNotNull { id -> allItems.firstOrNull { it.id == id } }
        if (scopedItems.isEmpty()) {
            saveReady(
                updateId,
                "目标大纲条目已全部删除",
                "",
                CompressionUsage()
            )
            return@withLock
        }

        val categories = loadCategories(session.bookId, scopedItems.map { it.category })
        val request = OutlineDiffRequest(
            bookId = session.bookId,
            bookTitle = book?.title.orEmpty(),
            bookIntro = book?.intro.orEmpty(),
            volumeTitle = volume?.title.orEmpty(),
            summary = summary.effectiveContent,
            items = scopedItems.map { item ->
                OutlineDiffItem(
                    id = item.id,
                    category = item.category,
                    title = item.title,
                    content = item.content,
                    updatedAt = item.updatedAt
                )
            },
            categories = categories,
            modelId = session.modelId
        )

        when (val outcome = diffService.analyze(request, config.outlineUpdateMode)) {
            is OutlineDiffOutcome.Skipped -> {
                saveReady(updateId, outcome.note, "", CompressionUsage())
            }

            is OutlineDiffOutcome.Failed -> {
                compression.recordOutlineUsage(sessionId, outcome.usage, false, outcome.message)
                markFailed(sessionId, outcome.message, outcome.usage, outcome.rawContent)
            }

            is OutlineDiffOutcome.Success -> {
                compression.recordOutlineUsage(sessionId, outcome.usage, true)
                val notes = mutableListOf(outcome.note)
                val inserted = persistSuggestions(updateId, sessionId, outcome.suggestions)
                val mode = CompressionOutlineUpdateMode.normalize(config.outlineUpdateMode)
                if (mode == CompressionOutlineUpdateMode.ON) {
                    notes += autoApply(session.bookId, inserted)
                }
                val counts = suggestionCounts(updateId)
                notes += "共 ${counts.total} 条建议"
                saveReady(
                    updateId = updateId,
                    note = notes.filter { it.isNotBlank() }.joinToString("；"),
                    raw = outcome.rawContent,
                    usage = outcome.usage
                )
            }
        }
    }

    /**
     * 用户确认预览里的勾选项：在一个事务内重新校验并写入。
     * DELETE 只有在界面上被明确勾选（并且二次确认）后才会出现在 [suggestionIds]。
     */
    suspend fun applySelected(sessionId: Long, suggestionIds: List<Long>): OutlineApplySummary {
        val update = dao.updateBySession(sessionId)
            ?: return OutlineApplySummary(failed = 1, messages = listOf("找不到本次大纲分析记录"))
        val rows = if (suggestionIds.isEmpty()) emptyList() else {
            dao.suggestionsByIds(suggestionIds)
                .filter { it.updateId == update.id && it.status in OutlineSuggestionStatus.selectable }
        }
        if (rows.isEmpty()) {
            return OutlineApplySummary(messages = listOf("没有可写入的已选建议"))
        }
        val commands = rows.map { record ->
            OutlineWriteCommand(
                suggestionId = record.id,
                operation = record.operation,
                targetOutlineItemId = record.targetOutlineItemId,
                category = record.category,
                title = record.title,
                newContent = record.newContent,
                expectedUpdatedAt = record.expectedUpdatedAt,
                expectedFingerprint = record.contentFingerprint
            )
        }
        val messages = mutableListOf<String>()
        val now = System.currentTimeMillis()
        // 大纲写入与建议状态在同一事务中提交：进程中断不会出现「写了条目但仍待确认」
        val counters = db.withTransaction {
            val outcomes = outlines.applyOutlineWrites(update.bookId, commands)
            var applied = 0
            var conflicts = 0
            var invalid = 0
            var failed = 0
            outcomes.forEach { outcome ->
                val record = rows.firstOrNull { it.id == outcome.suggestionId } ?: return@forEach
                when (outcome) {
                    is OutlineWriteOutcome.Applied -> {
                        applied++
                        dao.updateSuggestion(
                            record.copy(
                                status = OutlineSuggestionStatus.APPLIED,
                                selected = false,
                                statusMessage = "已写入",
                                updatedAt = now
                            )
                        )
                    }

                    is OutlineWriteOutcome.Conflict -> {
                        conflicts++
                        messages += "「${record.title}」写入冲突：${outcome.message}"
                        dao.updateSuggestion(
                            record.copy(
                                status = OutlineSuggestionStatus.CONFLICT,
                                selected = false,
                                statusMessage = outcome.message,
                                oldContent = outcome.currentContent,
                                updatedAt = now
                            )
                        )
                    }

                    is OutlineWriteOutcome.Invalid -> {
                        invalid++
                        messages += "「${record.title}」无效：${outcome.message}"
                        dao.updateSuggestion(
                            record.copy(
                                status = OutlineSuggestionStatus.INVALID,
                                selected = false,
                                statusMessage = outcome.message,
                                updatedAt = now
                            )
                        )
                    }

                    is OutlineWriteOutcome.Failed -> {
                        failed++
                        messages += "「${record.title}」写入失败：${outcome.message}"
                        dao.updateSuggestion(
                            record.copy(
                                status = OutlineSuggestionStatus.FAILED,
                                selected = false,
                                statusMessage = outcome.message,
                                updatedAt = now
                            )
                        )
                    }
                }
            }
            intArrayOf(applied, conflicts, invalid, failed)
        }
        val applied = counters[0]
        val conflicts = counters[1]
        val invalid = counters[2]
        val failed = counters[3]
        val note = "本次确认：写入 $applied 条，冲突 $conflicts 条，无效 $invalid 条，失败 $failed 条"
        dao.updateUpdate(
            dao.updateBySession(sessionId)?.copy(note = note, updatedAt = now)
                ?: return OutlineApplySummary(applied, conflicts, invalid, failed, messages)
        )
        return OutlineApplySummary(applied, conflicts, invalid, failed, messages)
    }

    /**
     * 用户在差异预览里手动改写某条建议的新内容。
     * 只允许仍可勾选的建议；写入时仍会按原基线做冲突校验。
     */
    suspend fun updateSuggestionContent(
        sessionId: Long,
        suggestionId: Long,
        newContent: String
    ) {
        if (newContent.isBlank()) return
        val update = dao.updateBySession(sessionId) ?: return
        val record = dao.suggestionsByIds(listOf(suggestionId))
            .firstOrNull { it.updateId == update.id } ?: return
        if (record.status !in OutlineSuggestionStatus.selectable) return
        if (record.newContent == newContent) return
        val headTitle = if (outlineContentIsFieldJson(newContent)) {
            runCatching { decodeOutlineFields(newContent) }.getOrNull()
                ?.firstOrNull { it.first == OUTLINE_HEAD_LABEL }
                ?.second
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        dao.updateSuggestion(
            record.copy(
                newContent = newContent,
                title = headTitle ?: record.title,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 放弃：只标记建议，不动大纲；之后仍可从历史记录查看结果 */
    suspend fun dismiss(sessionId: Long) {
        val update = dao.updateBySession(sessionId) ?: return
        val now = System.currentTimeMillis()
        dao.suggestions(update.id)
            .filter { it.status in OutlineSuggestionStatus.open }
            .forEach { record ->
                dao.updateSuggestion(
                    record.copy(
                        status = OutlineSuggestionStatus.DISMISSED,
                        selected = false,
                        updatedAt = now
                    )
                )
            }
        dao.updateUpdate(update.copy(note = "已放弃本次大纲更新", updatedAt = now))
    }

    // ---------------- 内部 ----------------

    private suspend fun resolveScopeSnapshot(
        snapshot: String,
        bookId: Long,
        config: VolumeCompressionConfig
    ): OutlineScopeResolved {
        val ids = OutlineScopeResolved.decodeIds(snapshot)
        if (ids.isNotEmpty()) return OutlineScopeResolved(itemIds = ids)
        // 迁移前的旧任务 / 旧快照：按当前配置即时解析一次作为后备
        return OutlineScopeResolver.resolve(
            items = outlines.getOutline(bookId),
            scopeMode = config.outlineScopeMode
        )
    }

    private suspend fun loadCategories(
        bookId: Long,
        itemCategories: List<String>
    ): List<OutlineCategoryTemplate> {
        val keys = ((prefs.outlineCategories(bookId) ?: defaultOutlineKeys) + itemCategories)
            .filter { it.isNotBlank() }
            .distinct()
        return keys.map { key ->
            OutlineCategoryTemplate(
                key = key,
                label = outlineCategoryLabel(key),
                fieldNames = prefs.outlineFields(bookId, key).orEmpty()
            )
        }
    }

    private suspend fun persistSuggestions(
        updateId: Long,
        sessionId: Long,
        drafts: List<OutlineSuggestionDraft>
    ): List<VolumeOutlineSuggestion> {
        val now = System.currentTimeMillis()
        dao.insertSuggestions(
            drafts.map { draft ->
                VolumeOutlineSuggestion(
                    updateId = updateId,
                    sessionId = sessionId,
                    suggestionIndex = draft.index,
                    operation = draft.operation,
                    targetOutlineItemId = draft.targetOutlineItemId,
                    category = draft.category,
                    title = draft.title,
                    oldContent = draft.oldContent,
                    newContent = draft.newContent,
                    reason = draft.reason,
                    confidence = draft.confidence,
                    relatedEvents = draft.relatedEvents.joinToString("\n"),
                    expectedUpdatedAt = draft.expectedUpdatedAt,
                    contentFingerprint = draft.contentFingerprint,
                    status = draft.status,
                    statusMessage = draft.statusMessage,
                    selected = OutlineUpdatePolicy.initiallySelected(draft),
                    createdAt = now,
                    updatedAt = now
                )
            }
        )
        return dao.suggestions(updateId)
    }

    /** 「开」模式：只自动写安全的 ADD / UPDATE；其余留在预览中等待确认 */
    private suspend fun autoApply(
        bookId: Long,
        records: List<VolumeOutlineSuggestion>
    ): String {
        val auto = records.filter { record ->
            record.status == OutlineSuggestionStatus.PENDING &&
                record.operation != OutlineOperation.DELETE &&
                record.operation != OutlineOperation.UNKNOWN &&
                record.confidence >= OutlineUpdatePolicy.MIN_AUTO_CONFIDENCE
        }
        if (auto.isEmpty()) return "没有可自动写入的安全建议"
        val commands = auto.map { record ->
            OutlineWriteCommand(
                suggestionId = record.id,
                operation = record.operation,
                targetOutlineItemId = record.targetOutlineItemId,
                category = record.category,
                title = record.title,
                newContent = record.newContent,
                expectedUpdatedAt = record.expectedUpdatedAt,
                expectedFingerprint = record.contentFingerprint
            )
        }
        val now = System.currentTimeMillis()
        val counters = db.withTransaction {
            val outcomes = outlines.applyOutlineWrites(bookId, commands)
            var applied = 0
            var conflicts = 0
            var failed = 0
            outcomes.forEach { outcome ->
                val record = auto.firstOrNull { it.id == outcome.suggestionId } ?: return@forEach
                when (outcome) {
                    is OutlineWriteOutcome.Applied -> {
                        applied++
                        dao.updateSuggestion(
                            record.copy(
                                status = OutlineSuggestionStatus.AUTO_APPLIED,
                                selected = false,
                                statusMessage = "已按配置自动写入",
                                updatedAt = now
                            )
                        )
                    }

                    is OutlineWriteOutcome.Conflict -> {
                        conflicts++
                        dao.updateSuggestion(
                            record.copy(
                                status = OutlineSuggestionStatus.CONFLICT,
                                selected = false,
                                statusMessage = outcome.message,
                                oldContent = outcome.currentContent,
                                updatedAt = now
                            )
                        )
                    }

                    is OutlineWriteOutcome.Invalid -> {
                        failed++
                        dao.updateSuggestion(
                            record.copy(
                                status = OutlineSuggestionStatus.INVALID,
                                selected = false,
                                statusMessage = outcome.message,
                                updatedAt = now
                            )
                        )
                    }

                    is OutlineWriteOutcome.Failed -> {
                        failed++
                        dao.updateSuggestion(
                            record.copy(
                                status = OutlineSuggestionStatus.FAILED,
                                selected = false,
                                statusMessage = outcome.message,
                                updatedAt = now
                            )
                        )
                    }
                }
            }
            intArrayOf(applied, conflicts, failed)
        }
        val applied = counters[0]
        val conflicts = counters[1]
        val failed = counters[2]
        val message = buildString {
            append("已自动写入 ").append(applied).append(" 条")
            if (conflicts > 0) append("，").append(conflicts).append(" 条冲突待确认")
            if (failed > 0) append("，").append(failed).append(" 条失败")
        }
        return message
    }

    private suspend fun suggestionCounts(updateId: Long): Counts {
        val records = dao.suggestions(updateId)
        return Counts(
            total = records.size,
            applied = records.count {
                it.status == OutlineSuggestionStatus.APPLIED ||
                    it.status == OutlineSuggestionStatus.AUTO_APPLIED
            },
            pending = records.count { it.status in OutlineSuggestionStatus.selectable }
        )
    }

    private suspend fun saveReady(
        updateId: Long,
        note: String,
        raw: String,
        usage: CompressionUsage,
        error: String = ""
    ) {
        val current = dao.updateById(updateId) ?: return
        dao.updateUpdate(
            current.copy(
                status = OutlineUpdateStatus.READY,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                cachedTokens = usage.cachedTokens,
                requestCount = usage.requestCount,
                requestsWithUsage = usage.requestsWithUsage,
                rawResponse = raw.take(MAX_RAW_DIAGNOSTIC),
                errorMessage = error,
                note = note,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun markFailed(
        sessionId: Long,
        message: String,
        usage: CompressionUsage = CompressionUsage(),
        raw: String = ""
    ) {
        val update = dao.updateBySession(sessionId) ?: return
        dao.updateUpdate(
            update.copy(
                status = OutlineUpdateStatus.FAILED,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                cachedTokens = usage.cachedTokens,
                requestCount = usage.requestCount,
                requestsWithUsage = usage.requestsWithUsage,
                rawResponse = raw.take(MAX_RAW_DIAGNOSTIC),
                errorMessage = message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun scopeLabel(config: VolumeCompressionConfig, count: Int): String =
        if (CompressionOutlineScopeMode.normalize(config.outlineScopeMode) ==
            CompressionOutlineScopeMode.UPDATABLE
        ) {
            "仅可更新大纲（$count 条）"
        } else {
            "全部大纲（$count 条）"
        }

    private data class Counts(val total: Int, val applied: Int, val pending: Int)

    companion object {
        /** 诊断用原始响应上限，避免撑爆 CursorWindow；正式内容从不取用原始响应 */
        private const val MAX_RAW_DIAGNOSTIC = 8_000
    }
}

/** 是否还有需要用户看到的建议（自动写入完成的项不重复打扰） */
fun List<VolumeOutlineSuggestion>.hasOpenSuggestions(): Boolean =
    any {
        it.status == OutlineSuggestionStatus.PENDING ||
            it.status == OutlineSuggestionStatus.LOW_CONFIDENCE ||
            it.status == OutlineSuggestionStatus.CONFLICT ||
            it.status == OutlineSuggestionStatus.INVALID
    }
