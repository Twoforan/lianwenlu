package com.lianwenqi.app.data.volume

import android.util.Log
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.VolumeCompressionConfig
import com.lianwenqi.app.data.VolumeSummaryRepository
import com.lianwenqi.app.data.dao.VolumeCompressionDao
import com.lianwenqi.app.data.entity.VolumeCompressionBatchKind
import com.lianwenqi.app.data.entity.VolumeCompressionBatchRecord
import com.lianwenqi.app.data.entity.VolumeCompressionBatchStatus
import com.lianwenqi.app.data.entity.VolumeCompressionErrorKind
import com.lianwenqi.app.data.entity.VolumeCompressionPhase
import com.lianwenqi.app.data.entity.VolumeCompressionSession
import com.lianwenqi.app.data.entity.VolumeCompressionStatus
import com.lianwenqi.app.data.entity.VolumeSummarySourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

sealed interface CompressionStartResult {
    data class Started(val sessionId: Long) : CompressionStartResult

    /** 同一分卷已有同来源的活跃任务：直接关联，不重复创建、不重复扣费 */
    data class Existing(val sessionId: Long) : CompressionStartResult

    data class Rejected(val message: String) : CompressionStartResult
}

sealed interface CompressionEstimateResult {
    data class Ready(
        val audit: CompressionAudit,
        val estimate: CompressionEstimate
    ) : CompressionEstimateResult

    data class NoInput(val audit: CompressionAudit) : CompressionEstimateResult

    data class Failed(
        val failure: CompressionFailure,
        val audit: CompressionAudit
    ) : CompressionEstimateResult
}

/**
 * 应用级压缩任务协调器（F5 / F4 / F7 共用入口）。
 *
 * 职责：
 * - 同书排队：每本书一个 [Mutex]，同一本书同时只运行一个任务；
 * - 同卷去重：同来源的活跃任务直接返回已有 session；
 * - 逐批落库：提取成功的批次立即持久化，失败只让该批及其后的整合重跑；
 * - 恢复：启动时把进程中断的 RUNNING 标记为失败，已完成且输入指纹一致的批次跳过；
 * - usage：全部来自 `AiResult`，按批次记录汇总，预估字段单独保存；
 * - 取消：只停止后续请求，保留已完成批次，不确认候选。
 *
 * 任务执行使用 [appScope]（应用级 SupervisorJob + IO），不挂在任何 ViewModel / 页面生命周期上。
 */
class VolumeCompressionCoordinator(
    private val db: AppDatabase,
    private val summaries: VolumeSummaryRepository,
    private val synopsisService: VolumeCompressionService,
    private val contentService: VolumeCompressionService,
    private val scope: CoroutineScope
) {
    private val dao: VolumeCompressionDao = db.volumeCompressionDao()

    /** 启动 / 恢复等结构性变更串行化，避免同卷重复创建 */
    private val actionMutex = Mutex()

    /** 每本书一把锁：先进先出，后到任务在锁上等待 */
    private val bookLocks = ConcurrentHashMap<Long, Mutex>()

    /** 进程内正在执行的任务；取消 / 去重时使用 */
    private val jobs = ConcurrentHashMap<Long, Job>()

    fun observeSession(sessionId: Long): Flow<VolumeCompressionSession?> = dao.observeSession(sessionId)

    fun observeSessions(bookId: Long): Flow<List<VolumeCompressionSession>> = dao.observeByBook(bookId)

    /** 某分卷的活跃任务（等待中 / 运行中 / 等待确认） */
    fun observeActive(volumeId: Long): Flow<VolumeCompressionSession?> = dao.observeActiveByVolume(volumeId)

    suspend fun session(sessionId: Long): VolumeCompressionSession? = dao.sessionById(sessionId)

    suspend fun activeSession(volumeId: Long): VolumeCompressionSession? = dao.activeByVolume(volumeId)

    suspend fun batches(sessionId: Long): List<VolumeCompressionBatchRecord> = dao.batches(sessionId)

    fun observeBatches(sessionId: Long): Flow<List<VolumeCompressionBatchRecord>> = dao.observeBatches(sessionId)

    suspend fun sessions(bookId: Long): List<VolumeCompressionSession> = dao.sessionsByBook(bookId)

    /** 只做预估与审计，不创建任务；F4 配置弹窗使用 */
    suspend fun estimate(request: VolumeCompressionRequest): CompressionEstimateResult {
        val service = serviceFor(request.sourceType)
            ?: return CompressionEstimateResult.Failed(
                CompressionFailure.SourceTypeUnsupported(request.sourceType),
                CompressionAudit(0, 0, 0, emptyList(), emptyList(), "", emptyList())
            )
        return when (val prepared = service.prepare(request)) {
            is PreparationOutcome.Ready -> CompressionEstimateResult.Ready(
                audit = prepared.preparation.audit,
                estimate = service.estimate(prepared.preparation)
            )

            is PreparationOutcome.NoInput -> CompressionEstimateResult.NoInput(prepared.audit)
            is PreparationOutcome.Failed -> CompressionEstimateResult.Failed(
                prepared.failure,
                prepared.audit
            )
        }
    }

    /**
     * 创建并排队一个压缩任务。
     * [configSnapshot] 由 F4 传入本次不可变配置；[previousSessionId] 用于重新压缩关联旧任务。
     */
    suspend fun start(
        request: VolumeCompressionRequest,
        configSnapshot: String = "",
        previousSessionId: Long? = null,
        /** F6：创建任务时解析好的大纲目标 ID 快照（逗号分隔） */
        outlineScopeSnapshot: String = ""
    ): CompressionStartResult = actionMutex.withLock {
        val existing = dao.activeByVolume(request.volumeId)
        if (existing != null) {
            return@withLock if (existing.sourceType == request.sourceType) {
                CompressionStartResult.Existing(existing.id)
            } else {
                CompressionStartResult.Rejected(
                    "该分卷已有来源为 ${existing.sourceType} 的进行中任务，请先完成或取消后再切换来源"
                )
            }
        }
        val now = System.currentTimeMillis()
        val position = dao.inFlightByBook(request.bookId).size + 1
        val sessionId = dao.insertSession(
            VolumeCompressionSession(
                bookId = request.bookId,
                volumeId = request.volumeId,
                sourceType = request.sourceType,
                modelId = request.modelId,
                status = VolumeCompressionStatus.PENDING,
                phase = VolumeCompressionPhase.QUEUED,
                configSnapshot = configSnapshot,
                outlineScopeSnapshot = outlineScopeSnapshot,
                previousSessionId = previousSessionId,
                queuePosition = position,
                createdAt = now,
                updatedAt = now
            )
        )
        enqueue(sessionId)
        CompressionStartResult.Started(sessionId)
    }

    /** 恢复失败 / 已取消的任务；已完成且指纹一致的批次不会再次请求 */
    suspend fun resume(sessionId: Long): CompressionStartResult = actionMutex.withLock {
        val session = dao.sessionById(sessionId)
            ?: return@withLock CompressionStartResult.Rejected("任务不存在")
        if (session.status != VolumeCompressionStatus.FAILED &&
            session.status != VolumeCompressionStatus.CANCELLED
        ) {
            return@withLock CompressionStartResult.Rejected("只有失败或已取消的任务可以恢复")
        }
        val other = dao.activeByVolume(session.volumeId)
        if (other != null && other.id != session.id) {
            return@withLock CompressionStartResult.Rejected("该分卷已有进行中的任务")
        }
        val now = System.currentTimeMillis()
        val position = dao.inFlightByBook(session.bookId).size + 1
        dao.updateSession(
            session.copy(
                status = VolumeCompressionStatus.PENDING,
                phase = VolumeCompressionPhase.QUEUED,
                errorKind = null,
                errorMessage = "",
                completedAt = null,
                queuePosition = position,
                updatedAt = now
            )
        )
        enqueue(session.id)
        CompressionStartResult.Started(session.id)
    }

    /**
     * 取消等待中 / 运行中的任务：立即停止启动新请求，已完成批次保留用于诊断或恢复。
     * 等待确认的任务请使用 [discardCandidate] 放弃候选，避免误删已生成的候选。
     */
    suspend fun cancel(sessionId: Long): Boolean {
        val session = dao.sessionById(sessionId) ?: return false
        if (session.status != VolumeCompressionStatus.PENDING &&
            session.status != VolumeCompressionStatus.RUNNING
        ) {
            return false
        }
        val now = System.currentTimeMillis()
        dao.markSessionBatchesCancelled(sessionId, now)
        dao.updateSession(
            session.copy(
                status = VolumeCompressionStatus.CANCELLED,
                phase = VolumeCompressionPhase.DONE,
                errorKind = null,
                errorMessage = "任务已取消，已完成批次已保留",
                completedAt = now,
                queuePosition = 0,
                updatedAt = now
            )
        )
        jobs.remove(sessionId)?.cancel()
        recalculateQueuePositions(session.bookId)
        return true
    }

    /** 确认候选：只有这里会把候选原子替换为当前生效版本 */
    suspend fun confirmCandidate(sessionId: Long): Boolean {
        val session = dao.sessionById(sessionId) ?: return false
        if (session.status != VolumeCompressionStatus.AWAITING_CONFIRMATION) return false
        val candidateId = session.candidateSummaryId ?: return false
        if (!summaries.confirmCandidate(candidateId)) return false
        val now = System.currentTimeMillis()
        dao.updateSession(
            session.copy(
                status = VolumeCompressionStatus.COMPLETED,
                phase = VolumeCompressionPhase.DONE,
                completedAt = now,
                updatedAt = now
            )
        )
        return true
    }

    /** 放弃候选：旧生效版本保持不变，任务以完成态收尾（候选已删除） */
    suspend fun discardCandidate(sessionId: Long): Boolean {
        val session = dao.sessionById(sessionId) ?: return false
        if (session.status != VolumeCompressionStatus.AWAITING_CONFIRMATION) return false
        val candidateId = session.candidateSummaryId ?: return false
        if (!summaries.discardCandidate(candidateId)) return false
        val now = System.currentTimeMillis()
        dao.updateSession(
            session.copy(
                status = VolumeCompressionStatus.COMPLETED,
                phase = VolumeCompressionPhase.DONE,
                candidateSummaryId = null,
                completedAt = now,
                updatedAt = now
            )
        )
        return true
    }

    /**
     * 应用启动时调用：
     * - RUNNING 是进程死亡遗留，统一标记为 FAILED + INTERRUPTED，由用户决定是否恢复；
     * - RUNNING 批次同样标记中断，绝不把半截 JSON 当成功；
     * - PENDING（之前已在排队、从未发起请求）自动重新入队。
     */
    suspend fun recoverInterrupted() {
        actionMutex.withLock {
            val now = System.currentTimeMillis()
            dao.markRunningBatchesInterrupted(now)
            val unfinished = dao.unfinishedSessions()
            unfinished.filter { it.status == VolumeCompressionStatus.RUNNING }.forEach { session ->
                dao.updateSession(
                    session.copy(
                        status = VolumeCompressionStatus.FAILED,
                        errorKind = VolumeCompressionErrorKind.INTERRUPTED,
                        errorMessage = "上次压缩被应用中断，可重试继续（已完成批次不会重复请求）",
                        completedAt = now,
                        queuePosition = 0,
                        updatedAt = now
                    )
                )
            }
            unfinished.filter { it.status == VolumeCompressionStatus.PENDING }.forEach { session ->
                enqueue(session.id)
            }
            unfinished.map { it.bookId }.distinct().forEach { recalculateQueuePositions(it) }
        }
    }

    /** 删除书籍前调用：先取消在途任务，再清理任务与批次记录 */
    suspend fun cancelAndDeleteForBook(bookId: Long) {
        val inFlight = dao.inFlightByBook(bookId)
        inFlight.forEach { cancel(it.id) }
        val outlineDao = db.volumeOutlineDao()
        outlineDao.deleteSuggestionsByBook(bookId)
        outlineDao.deleteUpdatesByBook(bookId)
        dao.deleteBatchesByBook(bookId)
        dao.deleteSessionsByBook(bookId)
    }

    /** 删除分卷前调用：先取消该卷任务，再清理任务与批次记录 */
    suspend fun cancelAndDeleteForVolume(bookId: Long, volumeId: Long) {
        dao.activeByVolume(volumeId)?.let { cancel(it.id) }
        val outlineDao = db.volumeOutlineDao()
        outlineDao.deleteSuggestionsByVolume(volumeId)
        outlineDao.deleteUpdatesByVolume(volumeId)
        dao.deleteBatchesByVolume(volumeId)
        dao.deleteSessionsByVolume(volumeId)
        recalculateQueuePositions(bookId)
    }

    /**
     * F6：把一次大纲差异分析的 usage 作为独立批次并入当前任务统计。
     * 与提取 / 整合记录共用 `(sessionId, kind, batchIndex)` 唯一索引，
     * 重新分析同一下标会覆盖旧值，不会重复累计。
     */
    suspend fun recordOutlineUsage(
        sessionId: Long,
        usage: CompressionUsage,
        success: Boolean,
        message: String = ""
    ) {
        if (usage.requestCount <= 0) return
        val session = dao.sessionById(sessionId) ?: return
        val now = System.currentTimeMillis()
        val existing = dao.batch(sessionId, VolumeCompressionBatchKind.OUTLINE, 0)
        dao.upsertBatch(
            VolumeCompressionBatchRecord(
                sessionId = sessionId,
                kind = VolumeCompressionBatchKind.OUTLINE,
                batchIndex = 0,
                rangeLabel = "大纲差异分析",
                status = if (success) {
                    VolumeCompressionBatchStatus.COMPLETED
                } else {
                    VolumeCompressionBatchStatus.FAILED
                },
                attempts = 1,
                requestCount = usage.requestCount,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                cachedTokens = usage.cachedTokens,
                requestsWithUsage = usage.requestsWithUsage,
                errorKind = if (success) null else VolumeCompressionErrorKind.REQUEST_FAILED,
                errorMessage = if (success) "" else message,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
        refreshUsage(sessionId)
        Log.d("VolumeOutline", "大纲分析 usage 已并入任务 ${session.bookId}/${session.id}")
    }

    // ---------------- 执行 ----------------

    private fun enqueue(sessionId: Long) {
        if (jobs[sessionId]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var bookId = 0L
            try {
                val session = dao.sessionById(sessionId) ?: return@launch
                bookId = session.bookId
                lockFor(bookId).withLock { runSession(sessionId) }
            } catch (e: CancellationException) {
                markCancelledIfRunning(sessionId)
                throw e
            } catch (t: Throwable) {
                val session = dao.sessionById(sessionId)
                if (session != null && session.status in VolumeCompressionStatus.inFlight) {
                    failSession(
                        sessionId,
                        VolumeCompressionErrorKind.UNKNOWN,
                        t.message ?: "压缩任务异常终止"
                    )
                }
            } finally {
                jobs.remove(sessionId)
                if (bookId != 0L) recalculateQueuePositions(bookId)
            }
        }
        jobs[sessionId] = job
        job.start()
    }

    private suspend fun runSession(sessionId: Long) {
        val session = dao.sessionById(sessionId) ?: return
        if (session.status != VolumeCompressionStatus.PENDING) return
        val service = serviceFor(session.sourceType)
        if (service == null) {
            failSession(
                sessionId,
                VolumeCompressionErrorKind.CONFIG,
                CompressionFailure.SourceTypeUnsupported(session.sourceType).message
            )
            return
        }

        val now = System.currentTimeMillis()
        dao.updateSession(
            session.copy(
                status = VolumeCompressionStatus.RUNNING,
                phase = VolumeCompressionPhase.READING,
                errorKind = null,
                errorMessage = "",
                startedAt = now,
                queuePosition = 0,
                updatedAt = now
            )
        )
        recalculateQueuePositions(session.bookId)

        val preparation = when (val prepared = service.prepare(requestOf(session))) {
            is PreparationOutcome.Ready -> prepared.preparation
            is PreparationOutcome.NoInput -> {
                failSession(
                    sessionId,
                    VolumeCompressionErrorKind.EMPTY_INPUT,
                    CompressionFailure.EmptyInput.message
                )
                return
            }

            is PreparationOutcome.Failed -> {
                failSession(sessionId, errorKindOf(prepared.failure), prepared.failure.message)
                return
            }
        }

        val estimate = service.estimate(preparation)
        dao.updatePlan(
            id = sessionId,
            sourceRevision = preparation.audit.sourceRevision,
            plannedBatchCount = preparation.batches.size,
            plannedRequestCount = estimate.requestCount,
            estimatedInputTokens = estimate.inputTokens,
            estimatedOutputTokens = estimate.outputTokens,
            time = System.currentTimeMillis()
        )
        dao.updatePhase(sessionId, VolumeCompressionPhase.EXTRACTING, System.currentTimeMillis())

        val results = mutableListOf<CompressionBatchResult>()
        for (batch in preparation.batches) {
            ensureRunning(sessionId)
            val inputHash = VolumeCompressionInputHash.of(preparation.request.sourceType, batch)
            val existing = dao.batch(sessionId, VolumeCompressionBatchKind.EXTRACTION, batch.index)
            val restored = restoreBatch(existing, inputHash, batch)
            if (restored != null) {
                results += restored
                continue
            }

            val batchNow = System.currentTimeMillis()
            dao.upsertBatch(
                VolumeCompressionBatchRecord(
                    sessionId = sessionId,
                    kind = VolumeCompressionBatchKind.EXTRACTION,
                    batchIndex = batch.index,
                    rangeLabel = batch.range.label,
                    chapterIds = batch.chapterIds.joinToString(","),
                    inputHash = inputHash,
                    status = VolumeCompressionBatchStatus.RUNNING,
                    createdAt = existing?.createdAt ?: batchNow,
                    updatedAt = batchNow
                )
            )

            when (val outcome = service.extractBatch(preparation, batch)) {
                is BatchExtractionOutcome.Success -> {
                    val result = outcome.result
                    dao.upsertBatch(
                        VolumeCompressionBatchRecord(
                            sessionId = sessionId,
                            kind = VolumeCompressionBatchKind.EXTRACTION,
                            batchIndex = batch.index,
                            rangeLabel = batch.range.label,
                            chapterIds = batch.chapterIds.joinToString(","),
                            inputHash = inputHash,
                            status = VolumeCompressionBatchStatus.COMPLETED,
                            attempts = result.attempts,
                            requestCount = result.usage.requestCount,
                            promptTokens = result.usage.promptTokens,
                            completionTokens = result.usage.completionTokens,
                            cachedTokens = result.usage.cachedTokens,
                            requestsWithUsage = result.usage.requestsWithUsage,
                            extractionJson = VolumeCompressionCodec.encodeExtraction(result.extraction),
                            rawContent = result.rawContent,
                            createdAt = existing?.createdAt ?: batchNow,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    results += result
                    refreshUsage(sessionId)
                }

                is BatchExtractionOutcome.Failed -> {
                    dao.upsertBatch(
                        VolumeCompressionBatchRecord(
                            sessionId = sessionId,
                            kind = VolumeCompressionBatchKind.EXTRACTION,
                            batchIndex = batch.index,
                            rangeLabel = batch.range.label,
                            chapterIds = batch.chapterIds.joinToString(","),
                            inputHash = inputHash,
                            status = VolumeCompressionBatchStatus.FAILED,
                            attempts = outcome.attempts,
                            requestCount = outcome.usage.requestCount,
                            promptTokens = outcome.usage.promptTokens,
                            completionTokens = outcome.usage.completionTokens,
                            cachedTokens = outcome.usage.cachedTokens,
                            requestsWithUsage = outcome.usage.requestsWithUsage,
                            rawContent = outcome.rawContent,
                            errorKind = errorKindOf(outcome.failure),
                            errorMessage = outcome.failure.message,
                            createdAt = existing?.createdAt ?: batchNow,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    refreshUsage(sessionId)
                    failSession(sessionId, errorKindOf(outcome.failure), outcome.failure.message)
                    return
                }
            }
        }

        ensureRunning(sessionId)
        dao.updatePhase(sessionId, VolumeCompressionPhase.MERGING, System.currentTimeMillis())
        // 恢复 / 重跑时整合会重新执行：先清掉旧整合记录，再逐条落库，避免重复累计 usage
        dao.deleteBatchesByKind(sessionId, VolumeCompressionBatchKind.MERGE)
        refreshUsage(sessionId)

        val baseUsage = CompressionUsage(inputTokenEstimate = preparation.inputTokens)
            .plus(results.fold(CompressionUsage()) { acc, result -> acc.plus(result.usage) })
        val mergeSnapshots = mutableListOf<MergeRequestSnapshot>()
        val mergeOutcome = try {
            service.merge(
                preparation = preparation,
                batchResults = results,
                baseUsage = baseUsage,
                onMergeRequest = { mergeSnapshots += it }
            )
        } finally {
            persistMergeBatches(sessionId, mergeSnapshots)
            refreshUsage(sessionId)
        }

        when (mergeOutcome) {
            is MergeOutcome.Failed -> {
                failSession(sessionId, errorKindOf(mergeOutcome.failure), mergeOutcome.failure.message)
                return
            }

            is MergeOutcome.Success -> {
                ensureRunning(sessionId)
                when (val finalized = service.finalizeCandidate(preparation, mergeOutcome.content)) {
                    is CandidateFinalizeOutcome.Rejected -> failSession(
                        sessionId,
                        errorKindOf(finalized.failure),
                        finalized.failure.message
                    )

                    is CandidateFinalizeOutcome.Saved -> {
                        val doneAt = System.currentTimeMillis()
                        val latest = dao.sessionById(sessionId) ?: return
                        if (latest.status != VolumeCompressionStatus.RUNNING) return
                        dao.updateSession(
                            latest.copy(
                                status = VolumeCompressionStatus.AWAITING_CONFIRMATION,
                                phase = VolumeCompressionPhase.AWAITING_CONFIRMATION,
                                candidateSummaryId = finalized.candidateId,
                                completedAt = doneAt,
                                queuePosition = 0,
                                updatedAt = doneAt
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun restoreBatch(
        record: VolumeCompressionBatchRecord?,
        inputHash: String,
        batch: CompressionBatch
    ): CompressionBatchResult? {
        if (record == null) return null
        if (record.status != VolumeCompressionBatchStatus.COMPLETED) return null
        if (record.inputHash != inputHash) return null
        val extraction = VolumeCompressionCodec.decodeExtraction(record.extractionJson, batch)
            ?: if (record.rawContent.isNotBlank()) {
                VolumeCompressionCodec.decodeExtraction(record.rawContent, batch)
            } else {
                null
            }
            ?: return null
        return CompressionBatchResult(
            batch = batch,
            extraction = extraction,
            rawContent = record.rawContent,
            attempts = record.attempts,
            usage = CompressionUsage(
                promptTokens = record.promptTokens,
                completionTokens = record.completionTokens,
                cachedTokens = record.cachedTokens,
                requestCount = record.requestCount,
                requestsWithUsage = record.requestsWithUsage
            )
        )
    }

    private suspend fun persistMergeBatches(
        sessionId: Long,
        snapshots: List<MergeRequestSnapshot>
    ) {
        val now = System.currentTimeMillis()
        snapshots.forEach { snapshot ->
            dao.upsertBatch(
                VolumeCompressionBatchRecord(
                    sessionId = sessionId,
                    kind = VolumeCompressionBatchKind.MERGE,
                    batchIndex = snapshot.index,
                    rangeLabel = snapshot.rangeLabel,
                    chapterIds = snapshot.chapterIds.joinToString(","),
                    status = if (snapshot.success) {
                        VolumeCompressionBatchStatus.COMPLETED
                    } else {
                        VolumeCompressionBatchStatus.FAILED
                    },
                    attempts = 1,
                    requestCount = snapshot.usage.requestCount,
                    promptTokens = snapshot.usage.promptTokens,
                    completionTokens = snapshot.usage.completionTokens,
                    cachedTokens = snapshot.usage.cachedTokens,
                    requestsWithUsage = snapshot.usage.requestsWithUsage,
                    rawContent = snapshot.rawContent,
                    errorKind = snapshot.failure?.let { errorKindOf(it) },
                    errorMessage = snapshot.failure?.message ?: "",
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    /** usage 只从批次记录汇总：恢复 / 重试覆盖同一下标后不会重复累计 */
    private suspend fun refreshUsage(sessionId: Long) {
        val records = dao.batches(sessionId)
        val completed = records.count {
            it.kind == VolumeCompressionBatchKind.EXTRACTION &&
                it.status == VolumeCompressionBatchStatus.COMPLETED
        }
        dao.updateUsage(
            id = sessionId,
            completedBatchCount = completed,
            actualRequestCount = records.sumOf { it.requestCount },
            promptTokens = records.sumOf { it.promptTokens },
            completionTokens = records.sumOf { it.completionTokens },
            cachedTokens = records.sumOf { it.cachedTokens },
            requestsWithUsage = records.sumOf { it.requestsWithUsage },
            time = System.currentTimeMillis()
        )
    }

    private suspend fun ensureRunning(sessionId: Long) {
        val session = dao.sessionById(sessionId)
            ?: throw CancellationException("压缩任务已被删除")
        if (session.status != VolumeCompressionStatus.RUNNING) {
            throw CancellationException("压缩任务已停止")
        }
    }

    private suspend fun failSession(sessionId: Long, errorKind: String, message: String) {
        val session = dao.sessionById(sessionId) ?: return
        if (session.status in VolumeCompressionStatus.terminal) return
        val now = System.currentTimeMillis()
        dao.updateSession(
            session.copy(
                status = VolumeCompressionStatus.FAILED,
                errorKind = errorKind,
                errorMessage = message,
                completedAt = now,
                queuePosition = 0,
                updatedAt = now
            )
        )
        recalculateQueuePositions(session.bookId)
    }

    private suspend fun markCancelledIfRunning(sessionId: Long) {
        val session = dao.sessionById(sessionId) ?: return
        if (session.status != VolumeCompressionStatus.PENDING &&
            session.status != VolumeCompressionStatus.RUNNING
        ) {
            return
        }
        val now = System.currentTimeMillis()
        dao.markSessionBatchesCancelled(sessionId, now)
        dao.updateSession(
            session.copy(
                status = VolumeCompressionStatus.CANCELLED,
                phase = VolumeCompressionPhase.DONE,
                errorMessage = "任务已取消，已完成批次已保留",
                completedAt = now,
                queuePosition = 0,
                updatedAt = now
            )
        )
    }

    private suspend fun recalculateQueuePositions(bookId: Long) {
        if (bookId == 0L) return
        val inFlight = dao.inFlightByBook(bookId)
        var waiting = 1
        val now = System.currentTimeMillis()
        inFlight.forEach { session ->
            val position = if (session.status == VolumeCompressionStatus.RUNNING) 0 else waiting++
            if (session.queuePosition != position) {
                dao.updateSession(session.copy(queuePosition = position, updatedAt = now))
            }
        }
    }

    private fun lockFor(bookId: Long): Mutex =
        bookLocks.computeIfAbsent(bookId) { Mutex() }

    private fun serviceFor(sourceType: String): VolumeCompressionService? = when {
        sourceType.equals(VolumeSummarySourceType.SYNOPSIS, ignoreCase = true) -> synopsisService
        sourceType.equals(VolumeSummarySourceType.CONTENT, ignoreCase = true) -> contentService
        else -> null
    }

    private fun requestOf(session: VolumeCompressionSession): VolumeCompressionRequest {
        val config = VolumeCompressionConfig.decode(session.configSnapshot).normalized()
        return VolumeCompressionRequest(
            bookId = session.bookId,
            volumeId = session.volumeId,
            modelId = session.modelId,
            sourceType = session.sourceType,
            compressionSessionId = session.id,
            maxOutputTokensOverride = config.maxOutputTokens,
            maxInputChars = config.maxCharsPerRequest,
            targetOutputChars = config.outputChars
        )
    }

    private fun errorKindOf(failure: CompressionFailure): String = when (failure) {
        CompressionFailure.EmptyInput -> VolumeCompressionErrorKind.EMPTY_INPUT
        CompressionFailure.ModelConfigMissing,
        CompressionFailure.CandidateSaveRejected -> VolumeCompressionErrorKind.CONFIG
        CompressionFailure.UnknownContextWindow,
        is CompressionFailure.ContextWindowTooSmall,
        is CompressionFailure.InputBudgetTooSmall -> VolumeCompressionErrorKind.CAPACITY
        is CompressionFailure.InputTooLarge -> VolumeCompressionErrorKind.INPUT_TOO_LARGE
        is CompressionFailure.RequestFailed -> VolumeCompressionErrorKind.REQUEST_FAILED
        is CompressionFailure.EmptyOutput,
        is CompressionFailure.TruncatedOutput,
        is CompressionFailure.InvalidJson,
        is CompressionFailure.InvalidFinalContent,
        is CompressionFailure.InvalidMergeContent,
        is CompressionFailure.CoverageIncomplete -> VolumeCompressionErrorKind.INVALID_OUTPUT
        is CompressionFailure.SourceTypeUnsupported -> VolumeCompressionErrorKind.CONFIG
        CompressionFailure.MergeNotConverging,
        is CompressionFailure.SliceCoverageBroken -> VolumeCompressionErrorKind.UNKNOWN
    }
}
