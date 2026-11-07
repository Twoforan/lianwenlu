package com.lianwenqi.app.data.volume

import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.ConfigRepository
import com.lianwenqi.app.data.VolumeCompressionConfig
import com.lianwenqi.app.data.VolumeSourceRevision
import com.lianwenqi.app.data.VolumeSummaryRepository
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.ChatRole
import kotlinx.coroutines.CancellationException

/**
 * 统一的整卷压缩引擎（梗概与正文共用）。
 *
 * 章节来源、批次策略、长度规划与容量规划都是可替换依赖；提取、重试、整合、
 * 覆盖校验、usage 汇总、错误处理与候选提交只有这一套实现。
 *
 * F5 把一次压缩拆成可独立调用、可逐段落库的阶段：
 * [prepare] → [extractBatch] → [merge] → [finalizeCandidate]；
 * [compress] 仍是这些阶段的组合门面，供 F2 / F3 的单测与简单调用方使用。
 *
 * 约束：
 * - 只通过现有模型配置与 `AiClient.chat` 发起请求，不新建网络客户端；
 * - 不写 `ChatSession` / `ChatMessage`，压缩过程与普通聊天完全隔离；
 * - 成功也只写入 F1 的候选版本，绝不自动确认生效；
 * - 协程取消会单独通知进度并原样抛出，不当普通失败吞掉，也不提交任何结果。
 */
class VolumeCompressionService(
    private val chapterSource: ChapterSourceAdapter,
    private val modelLoader: suspend (Long) -> AiModel?,
    private val chat: suspend (AiModel, AiRequest) -> AiResult,
    private val saveCandidate: suspend (CompressionCandidate) -> Long?,
    private val batcher: CompressionBatcher = SlicingBudgetBatcher(),
    lengthPlanner: CompressionLengthPlanner = DefaultCompressionLengthPlanner,
    private val capacityPlanner: CompressionCapacityPlanner = CompressionCapacityPlanner(lengthPlanner),
    /** 格式错误时的最大尝试次数（含首次）；只对可重试的格式失败生效 */
    private val maxExtractionAttempts: Int = 2
) {

    /** 纯计算预估；不发起请求、不读写数据库 */
    fun estimate(preparation: CompressionPreparation): CompressionEstimate =
        VolumeCompressionEstimator.estimate(
            audit = preparation.audit,
            batches = preparation.batches,
            inputTokens = preparation.inputTokens,
            contextWindow = preparation.contextWindow,
            mergeInputBudgetTokens = preparation.maxInputChars,
            maxOutputTokens = preparation.maxOutputTokens,
            targetOutputChars = preparation.targetOutputChars,
            extraction = preparation.extractionCapacity,
            capacityPlanner = capacityPlanner,
            extractionSystemPromptTokens = CompressionTokenEstimator.estimate(
                VolumeCompressionPrompt.EXTRACTION_SYSTEM
            ),
            mergeSystemPromptTokens = CompressionTokenEstimator.estimate(
                VolumeCompressionPrompt.MERGE_SYSTEM
            )
        )

    /** 读取章节、校验模型与容量并规划批次；不发任何模型请求 */
    suspend fun prepare(
        request: VolumeCompressionRequest,
        onProgress: (CompressionProgress) -> Unit = {}
    ): PreparationOutcome {
        val emptyAudit = CompressionAudit(0, 0, 0, emptyList(), emptyList(), "", emptyList())
        if (request.sourceType.trim().uppercase() != chapterSource.sourceType.trim().uppercase()) {
            return PreparationOutcome.Failed(
                CompressionFailure.SourceTypeUnsupported(request.sourceType),
                emptyAudit,
                CompressionUsage()
            )
        }

        val chapters = chapterSource.load(request.bookId, request.volumeId)
        onProgress(CompressionProgress.Reading(chapters.size))
        val audit = buildAudit(chapters, chapterSource.sourceType)
        if (audit.includedChapterCount == 0) {
            return PreparationOutcome.NoInput(audit)
        }
        val included = chapters.filter { it.text.isNotBlank() }

        val model = modelLoader(request.modelId)
            ?: return PreparationOutcome.Failed(
                CompressionFailure.ModelConfigMissing,
                audit,
                CompressionUsage()
            )

        val maxInputChars = when {
            request.maxInputChars > 0 -> request.maxInputChars
            request.contextWindowOverride > 0 -> request.contextWindowOverride
            else -> VolumeCompressionConfig.DEFAULT_MAX_CHARS_PER_REQUEST
        }

        val inputTokens = included.sumOf {
            CompressionTokenEstimator.estimate(it.title) + CompressionTokenEstimator.estimate(it.text)
        }
        val usage = CompressionUsage(inputTokenEstimate = inputTokens)
        val maxOutputTokens = if (request.maxOutputTokensOverride > 0) {
            request.maxOutputTokensOverride
        } else {
            model.maxTokens
        }

        val extractionCapacity = when (
            val plan = capacityPlanner.extractionForInputBudget(
                inputBudgetTokens = maxInputChars,
                maxOutputTokens = maxOutputTokens,
                complexity = CompressionComplexity(
                    inputTokens = inputTokens,
                    chapterCount = included.size
                )
            )
        ) {
            is CapacityPlan.TooSmall -> return PreparationOutcome.Failed(plan.failure, audit, usage)
            is CapacityPlan.Ready -> plan
        }
        // 仅用于展示 / 预估；实际分批以单次提交字符数为准，不再依赖模型上下文窗口
        val contextWindow = maxInputChars + extractionCapacity.outputTokens +
            CompressionTokenEstimator.estimate(VolumeCompressionPrompt.EXTRACTION_SYSTEM) +
            CompressionCapacityPlanner.SAFETY_MARGIN_TOKENS +
            CompressionCapacityPlanner.PER_REQUEST_OVERHEAD_TOKENS

        val batches = batcher.plan(
            included,
            extractionCapacity.inputBudgetTokens,
            CompressionCapacityPlanner.CHAPTER_OVERHEAD_TOKENS
        )
        val oversized = batches.firstOrNull { batch ->
            batch.chapters.size == 1 &&
                batch.estimatedInputTokens + CompressionCapacityPlanner.CHAPTER_OVERHEAD_TOKENS >
                extractionCapacity.inputBudgetTokens
        }
        if (oversized != null) {
            val unit = oversized.chapters.first()
            return PreparationOutcome.Failed(
                CompressionFailure.InputTooLarge(unit.chapterId, unit.order),
                audit,
                usage
            )
        }
        verifySliceCoverage(batches, included)?.let { failure ->
            return PreparationOutcome.Failed(failure, audit, usage)
        }

        return PreparationOutcome.Ready(
            CompressionPreparation(
                request = request,
                model = model,
                audit = audit,
                included = included,
                inputTokens = inputTokens,
                contextWindow = contextWindow,
                maxInputChars = maxInputChars,
                targetOutputChars = request.targetOutputChars.coerceAtLeast(0),
                maxOutputTokens = maxOutputTokens,
                extractionCapacity = extractionCapacity,
                batches = batches
            )
        )
    }

    /** 执行单个提取批次（含格式校验的有界重试）；不做覆盖校验，由 [merge] 统一检查 */
    suspend fun extractBatch(
        preparation: CompressionPreparation,
        batch: CompressionBatch
    ): BatchExtractionOutcome {
        val model = preparation.model
        val sentMaxTokens = sentMaxTokens(preparation)
        val requestAi = AiRequest(
            turns = listOf(
                ChatTurn(
                    ChatRole.USER,
                    VolumeCompressionPrompt.extractionUser(batch, preparation.batches.size)
                )
            ),
            model = model.name,
            temperature = COMPRESSION_TEMPERATURE,
            maxTokens = sentMaxTokens,
            systemPrompt = VolumeCompressionPrompt.EXTRACTION_SYSTEM
        )

        var batchUsage = CompressionUsage()
        var attempts = 0
        var rawContent = ""
        var parsed: BatchExtraction? = null
        var lastFailure: CompressionFailure? = null
        val maxAttempts = maxExtractionAttempts.coerceAtLeast(1)
        while (attempts < maxAttempts && parsed == null) {
            attempts++
            val result = chat(model, requestAi)
            batchUsage = batchUsage.plus(result)
            if (!result.success) {
                lastFailure = CompressionFailure.RequestFailed(result.error)
                break
            }
            rawContent = result.content
            val hitOutputLimit = sentMaxTokens > 0 && result.completionTokens >= sentMaxTokens
            when (
                val parse = VolumeCompressionCodec.parseExtraction(
                    result.content,
                    batch,
                    hitOutputLimit
                )
            ) {
                is ExtractionParseResult.Success -> parsed = parse.extraction
                is ExtractionParseResult.Failure -> {
                    lastFailure = parse.failure
                    if (!isRetryable(parse.failure)) break
                }
            }
        }
        if (parsed == null) {
            return BatchExtractionOutcome.Failed(
                failure = lastFailure ?: CompressionFailure.EmptyOutput(CompressionStage.EXTRACTION),
                usage = batchUsage,
                rawContent = rawContent,
                attempts = attempts
            )
        }
        return BatchExtractionOutcome.Success(
            CompressionBatchResult(
                batch = batch,
                extraction = parsed,
                rawContent = rawContent,
                attempts = attempts,
                usage = batchUsage
            )
        )
    }

    /**
     * 整合全部成功批次并生成最终全卷梗概正文。
     *
     * [baseUsage] 传入时返回的 usage 已包含它（协调器用于跨阶段累计）；
     * [onMergeRequest] 在每次整合请求结束后同步回调，协调器据此逐条落库真实 usage。
     */
    suspend fun merge(
        preparation: CompressionPreparation,
        batchResults: List<CompressionBatchResult>,
        baseUsage: CompressionUsage = CompressionUsage(),
        onProgress: (CompressionProgress) -> Unit = {},
        onMergeRequest: (MergeRequestSnapshot) -> Unit = {}
    ): MergeOutcome {
        var usage = baseUsage
        var mergeUsage = CompressionUsage()
        var mergeRequestIndex = 0

        coverageFailure(
            covered = batchResults.flatMap { it.batch.chapterIds },
            expected = preparation.audit.includedChapterIds
        )?.let { failure ->
            return MergeOutcome.Failed(failure, null, emptyList(), usage, mergeUsage)
        }

        val included = preparation.included
        val inputTokens = preparation.inputTokens
        val complexity = CompressionComplexity.of(
            extractions = batchResults.map { it.extraction },
            inputTokens = inputTokens,
            chapterCount = included.size
        )
        val mergeCapacity = when (
            val plan = capacityPlanner.mergeForInputBudget(
                inputBudgetTokens = preparation.maxInputChars,
                maxOutputTokens = preparation.maxOutputTokens,
                complexity = complexity
            )
        ) {
            is CapacityPlan.TooSmall -> return MergeOutcome.Failed(
                plan.failure, null, emptyList(), usage, mergeUsage
            )

            is CapacityPlan.Ready -> plan
        }
        val finalTarget = capacityPlanner.finalTargetForChars(
            targetOutputChars = preparation.targetOutputChars,
            maxOutputTokens = preparation.maxOutputTokens,
            planned = mergeCapacity.outputTokens
        )
        val mergeInputBudget = mergeCapacity.inputBudgetTokens

        var mergeNodes = batchResults.map { result ->
            CompressionMergeNode(
                content = VolumeCompressionPrompt.renderExtraction(result),
                chapterIds = result.batch.chapterIds,
                firstOrder = result.batch.range.firstOrder,
                lastOrder = result.batch.range.lastOrder
            )
        }
        val intermediateTarget = capacityPlanner.intermediateTarget(finalTarget)
        val sentMaxTokens = sentMaxTokens(preparation)
        val mergeRoundPlan = CompressionMergePlanner.plan(
            initialNodes = mergeNodes,
            mergeInputBudgetTokens = mergeInputBudget,
            intermediateTarget = intermediateTarget
        )
        val plan = CompressionPlan(
            inputTokenEstimate = inputTokens,
            estimateMethod = CompressionTokenEstimator.METHOD,
            totalChapterCount = preparation.audit.totalChapterCount,
            includedChapterCount = preparation.audit.includedChapterCount,
            batches = preparation.batches,
            batchInputBudgetTokens = preparation.extractionCapacity.inputBudgetTokens,
            mergeInputBudgetTokens = mergeInputBudget,
            extractionOutputBudgetTokens = preparation.extractionCapacity.outputTokens,
            outputTokenBudget = finalTarget,
            plannedMergeRoundCount = mergeRoundPlan.rounds,
            plannedRequestCount = preparation.batches.size + mergeRoundPlan.requestCount,
            contextWindow = preparation.contextWindow,
            slicedChapterCount = preparation.batches.flatMap { it.chapters }
                .filter { it.isSlice }
                .map { it.chapterId }
                .distinct()
                .size
        )
        onProgress(CompressionProgress.Planned(plan))

        var round = 0
        val hardInputLimit = mergeInputBudget +
            mergeCapacity.safetyMarginTokens +
            CompressionCapacityPlanner.PER_REQUEST_OVERHEAD_TOKENS
        while (CompressionMergePlanner.totalTokens(mergeNodes) > mergeInputBudget) {
            round++
            if (round > MAX_MERGE_ROUNDS) {
                return MergeOutcome.Failed(
                    CompressionFailure.MergeNotConverging,
                    plan, mergeNodes, usage, mergeUsage
                )
            }
            val beforeTokens = CompressionMergePlanner.totalTokens(mergeNodes)
            val nodeTokens = mergeNodes.map { CompressionMergePlanner.nodeTokens(it) }
            var groups = CompressionMergePlanner.groupIndices(nodeTokens, mergeInputBudget)
            // 每个节点单独都放得下、但合起来超预算时，主动把前两个节点合成一次请求；
            // 这类组合只允许在安全余量内少量超出计划预算，仍受上下文硬上限约束。
            val allSingle = groups.all { it.size == 1 && nodeTokens[it.first()] <= mergeInputBudget }
            if (allSingle && mergeNodes.size >= 2) {
                val pair = groups.take(2).flatMap { it }
                if (nodeTokens[pair[0]] + nodeTokens[pair[1]] > hardInputLimit) {
                    return MergeOutcome.Failed(
                        CompressionFailure.MergeNotConverging,
                        plan, mergeNodes, usage, mergeUsage
                    )
                }
                groups = listOf(pair) + groups.drop(2)
            }
            val next = mutableListOf<CompressionMergeNode>()
            groups.forEachIndexed { groupIndex, group ->
                val groupNodes = group.map { mergeNodes[it] }
                val groupTokens = group.sumOf { nodeTokens[it] }
                val forcedMerge = allSingle && groupIndex == 0
                if (group.size == 1 && groupTokens <= mergeInputBudget && !forcedMerge) {
                    next += groupNodes.first()
                    return@forEachIndexed
                }
                onProgress(
                    CompressionProgress.Merging(
                        round = round,
                        finalRound = false,
                        nodeCount = groupNodes.size,
                        coveredChapterCount = groupNodes.flatMap { it.chapterIds }.distinct().size
                    )
                )
                val requestAi = AiRequest(
                    turns = listOf(
                        ChatTurn(
                            ChatRole.USER,
                            VolumeCompressionPrompt.mergeUser(
                                groupNodes,
                                finalRound = false,
                                targetTokens = intermediateTarget
                            )
                        )
                    ),
                    model = preparation.model.name,
                    temperature = COMPRESSION_TEMPERATURE,
                    maxTokens = sentMaxTokens,
                    systemPrompt = VolumeCompressionPrompt.MERGE_SYSTEM
                )
                val result = chat(preparation.model, requestAi)
                val requestUsage = CompressionUsage().plus(result)
                usage = usage.plus(result)
                mergeUsage = mergeUsage.plus(result)
                onMergeRequest(
                    MergeRequestSnapshot(
                        index = mergeRequestIndex++,
                        round = round,
                        finalRound = false,
                        rangeLabel = if (groupNodes.size > 1) {
                            "第${groupNodes.first().firstOrder}-${groupNodes.last().lastOrder}章"
                        } else {
                            groupNodes.first().rangeLabel
                        },
                        chapterIds = groupNodes.flatMap { it.chapterIds }.distinct(),
                        rawContent = result.content,
                        usage = requestUsage,
                        failure = if (result.success) null else CompressionFailure.RequestFailed(result.error)
                    )
                )
                if (!result.success) {
                    return MergeOutcome.Failed(
                        CompressionFailure.RequestFailed(result.error),
                        plan, mergeNodes, usage, mergeUsage
                    )
                }
                val hitOutputLimit = sentMaxTokens > 0 && result.completionTokens >= sentMaxTokens
                when (
                    val merged = VolumeCompressionCodec.validateMergedText(
                        result.content,
                        hitOutputLimit,
                        finalRound = false
                    )
                ) {
                    is MergedTextResult.Invalid -> return MergeOutcome.Failed(
                        merged.failure, plan, mergeNodes, usage, mergeUsage
                    )

                    is MergedTextResult.Valid -> next += CompressionMergeNode(
                        content = merged.content,
                        chapterIds = groupNodes.flatMap { it.chapterIds }.distinct(),
                        firstOrder = groupNodes.first().firstOrder,
                        lastOrder = groupNodes.last().lastOrder
                    )
                }
            }
            if (CompressionMergePlanner.totalTokens(next) >= beforeTokens) {
                return MergeOutcome.Failed(
                    CompressionFailure.MergeNotConverging,
                    plan, mergeNodes, usage, mergeUsage
                )
            }
            mergeNodes = next
        }

        coverageFailure(
            covered = mergeNodes.flatMap { it.chapterIds },
            expected = preparation.audit.includedChapterIds
        )?.let { failure ->
            return MergeOutcome.Failed(failure, plan, mergeNodes, usage, mergeUsage)
        }

        round++
        onProgress(
            CompressionProgress.Merging(
                round = round,
                finalRound = true,
                nodeCount = mergeNodes.size,
                coveredChapterCount = mergeNodes.flatMap { it.chapterIds }.distinct().size
            )
        )
        val finalRequest = AiRequest(
            turns = listOf(
                ChatTurn(
                    ChatRole.USER,
                    VolumeCompressionPrompt.mergeUser(mergeNodes, finalRound = true, targetTokens = finalTarget)
                )
            ),
            model = preparation.model.name,
            temperature = COMPRESSION_TEMPERATURE,
            maxTokens = sentMaxTokens,
            systemPrompt = VolumeCompressionPrompt.MERGE_SYSTEM
        )
        val finalResult = chat(preparation.model, finalRequest)
        val finalUsage = CompressionUsage().plus(finalResult)
        usage = usage.plus(finalResult)
        mergeUsage = mergeUsage.plus(finalResult)
        onMergeRequest(
            MergeRequestSnapshot(
                index = mergeRequestIndex,
                round = round,
                finalRound = true,
                rangeLabel = if (mergeNodes.isEmpty()) "" else
                    "第${mergeNodes.first().firstOrder}-${mergeNodes.last().lastOrder}章",
                chapterIds = mergeNodes.flatMap { it.chapterIds }.distinct(),
                rawContent = finalResult.content,
                usage = finalUsage,
                failure = if (finalResult.success) null else CompressionFailure.RequestFailed(finalResult.error)
            )
        )
        if (!finalResult.success) {
            return MergeOutcome.Failed(
                CompressionFailure.RequestFailed(finalResult.error),
                plan, mergeNodes, usage, mergeUsage
            )
        }
        val finalHitLimit = sentMaxTokens > 0 && finalResult.completionTokens >= sentMaxTokens
        val finalContent = when (
            val validated = VolumeCompressionCodec.validateMergedText(
                finalResult.content,
                finalHitLimit,
                finalRound = true
            )
        ) {
            is MergedTextResult.Invalid -> return MergeOutcome.Failed(
                validated.failure, plan, mergeNodes, usage, mergeUsage
            )

            is MergedTextResult.Valid -> validated.content
        }

        return MergeOutcome.Success(
            content = finalContent,
            plan = plan,
            complexity = complexity,
            mergeNodes = mergeNodes,
            usage = usage,
            mergeUsage = mergeUsage
        )
    }

    /**
     * 压缩请求实际发送的输出上限：只取模型自身的「最大输出 token」设置。
     * <= 0 表示不限，请求不携带 max_tokens；容量规划值只用于分批与预估，
     * 不能当作硬截断，否则推理模型的思考过程会占满由估算得出的小上限。
     */
    private fun sentMaxTokens(preparation: CompressionPreparation): Int =
        preparation.maxOutputTokens.coerceAtLeast(0)

    /** 只写候选版本，绝不确认生效；书 / 卷 / 章节归属由 F1 Repository 校验 */
    suspend fun finalizeCandidate(
        preparation: CompressionPreparation,
        content: String
    ): CandidateFinalizeOutcome {
        val candidateId = saveCandidate(
            CompressionCandidate(
                bookId = preparation.request.bookId,
                volumeId = preparation.request.volumeId,
                sourceType = chapterSource.sourceType,
                modelContent = content,
                modelId = preparation.model.id,
                modelName = preparation.model.displayName,
                compressionSessionId = preparation.request.compressionSessionId,
                chapterIds = preparation.audit.includedChapterIds
            )
        ) ?: return CandidateFinalizeOutcome.Rejected(CompressionFailure.CandidateSaveRejected)
        return CandidateFinalizeOutcome.Saved(candidateId)
    }

    /** 组合门面：一次跑完准备、提取、整合与候选写入（F2 / F3 的既有入口） */
    suspend fun compress(
        request: VolumeCompressionRequest,
        onProgress: (CompressionProgress) -> Unit = {}
    ): VolumeCompressionOutcome {
        try {
            return runCompression(request, onProgress)
        } catch (e: CancellationException) {
            onProgress(CompressionProgress.Cancelled)
            throw e
        }
    }

    private suspend fun runCompression(
        request: VolumeCompressionRequest,
        onProgress: (CompressionProgress) -> Unit
    ): VolumeCompressionOutcome {
        val preparation = when (val prepared = prepare(request, onProgress)) {
            is PreparationOutcome.Ready -> prepared.preparation
            is PreparationOutcome.NoInput -> {
                onProgress(CompressionProgress.Failed(CompressionFailure.EmptyInput))
                return VolumeCompressionOutcome.NoInput(prepared.audit)
            }

            is PreparationOutcome.Failed -> {
                onProgress(CompressionProgress.Failed(prepared.failure))
                return VolumeCompressionOutcome.Failed(
                    failure = prepared.failure,
                    audit = prepared.audit,
                    plan = null,
                    usage = prepared.usage
                )
            }
        }

        val batchResults = mutableListOf<CompressionBatchResult>()
        for (batch in preparation.batches) {
            onProgress(
                CompressionProgress.Extracting(
                    batchIndex = batch.index + 1,
                    batchCount = preparation.batches.size,
                    range = batch.range
                )
            )
            when (val extracted = extractBatch(preparation, batch)) {
                is BatchExtractionOutcome.Success -> batchResults += extracted.result
                is BatchExtractionOutcome.Failed -> {
                    onProgress(CompressionProgress.Failed(extracted.failure))
                    val usage = CompressionUsage(inputTokenEstimate = preparation.inputTokens)
                        .plus(batchResults.fold(CompressionUsage()) { acc, result -> acc.plus(result.usage) })
                        .plus(extracted.usage)
                    return VolumeCompressionOutcome.Failed(
                        failure = extracted.failure,
                        audit = preparation.audit,
                        plan = null,
                        usage = usage,
                        batchResults = batchResults
                    )
                }
            }
        }

        val baseUsage = CompressionUsage(inputTokenEstimate = preparation.inputTokens)
            .plus(batchResults.fold(CompressionUsage()) { acc, result -> acc.plus(result.usage) })
        return when (val merged = merge(preparation, batchResults, baseUsage, onProgress)) {
            is MergeOutcome.Failed -> {
                onProgress(CompressionProgress.Failed(merged.failure))
                VolumeCompressionOutcome.Failed(
                    failure = merged.failure,
                    audit = preparation.audit,
                    plan = merged.plan,
                    usage = merged.usage,
                    batchResults = batchResults,
                    mergeNodes = merged.mergeNodes
                )
            }

            is MergeOutcome.Success -> when (
                val finalized = finalizeCandidate(preparation, merged.content)
            ) {
                is CandidateFinalizeOutcome.Rejected -> {
                    onProgress(CompressionProgress.Failed(finalized.failure))
                    VolumeCompressionOutcome.Failed(
                        failure = finalized.failure,
                        audit = preparation.audit,
                        plan = merged.plan,
                        usage = merged.usage,
                        batchResults = batchResults,
                        mergeNodes = merged.mergeNodes
                    )
                }

                is CandidateFinalizeOutcome.Saved -> {
                    onProgress(CompressionProgress.WaitingConfirmation)
                    onProgress(CompressionProgress.Completed(finalized.candidateId))
                    VolumeCompressionOutcome.Success(
                        candidateId = finalized.candidateId,
                        content = merged.content,
                        audit = preparation.audit,
                        plan = merged.plan,
                        usage = merged.usage,
                        complexity = merged.complexity,
                        batchResults = batchResults,
                        mergeNodes = merged.mergeNodes
                    )
                }
            }
        }
    }

    private fun buildAudit(
        chapters: List<CompressionChapter>,
        sourceType: String
    ): CompressionAudit {
        val included = chapters.filter { it.text.isNotBlank() }
        val missing = chapters.filter { it.text.isBlank() }
        val entries = included.map { chapter ->
            VolumeSourceRevision.Entry(
                chapterId = chapter.chapterId,
                contentHash = VolumeSourceRevision.contentHash(chapter.text)
            )
        }
        val ranges = mutableListOf<CompressionChapterRange>()
        var run = mutableListOf<CompressionChapter>()
        included.forEach { chapter ->
            if (run.isNotEmpty() && chapter.order != run.last().order + 1) {
                ranges += compressionRange(run)
                run = mutableListOf()
            }
            run += chapter
        }
        if (run.isNotEmpty()) ranges += compressionRange(run)
        return CompressionAudit(
            totalChapterCount = chapters.size,
            includedChapterCount = included.size,
            missingChapterCount = missing.size,
            missingChapterIds = missing.map { it.chapterId },
            includedChapterIds = included.map { it.chapterId },
            sourceRevision = VolumeSourceRevision.revision(sourceType, entries),
            includedRanges = ranges
        )
    }

    /** 只对格式类失败做有界重试；截断、请求失败与取消不重试 */
    private fun isRetryable(failure: CompressionFailure): Boolean =
        failure is CompressionFailure.InvalidJson || failure is CompressionFailure.EmptyOutput

    /**
     * 校验切片无损：同一章节的所有片段按顺序拼接后必须逐字等于原章节正文。
     * 这样才能保证「完整覆盖原文、无尾部遗漏、无重复原文」不是靠约定，而是被引擎检查。
     */
    private fun verifySliceCoverage(
        batches: List<CompressionBatch>,
        originals: List<CompressionChapter>
    ): CompressionFailure? {
        val byId = originals.associateBy { it.chapterId }
        val unitsByChapter = batches.flatMap { it.chapters }.groupBy { it.chapterId }
        unitsByChapter.forEach { (chapterId, units) ->
            if (units.size <= 1 && units.none { it.isSlice }) return@forEach
            val original = byId[chapterId] ?: return CompressionFailure.SliceCoverageBroken(
                chapterId,
                units.first().order
            )
            val joined = units.sortedBy { it.sliceIndex }.joinToString("") { it.text }
            if (joined != original.text) {
                return CompressionFailure.SliceCoverageBroken(chapterId, original.order)
            }
        }
        return null
    }

    private fun coverageFailure(
        covered: List<Long>,
        expected: List<Long>
    ): CompressionFailure? {
        val coveredSet = covered.toSet()
        val missing = expected.filterNot { it in coveredSet }
        return if (missing.isEmpty()) null else CompressionFailure.CoverageIncomplete(missing)
    }

    companion object {
        private const val COMPRESSION_TEMPERATURE = 0.3f
        private const val MAX_MERGE_ROUNDS = 12

        /** 生产环境：梗概来源 + 现有模型配置 / AiClient + F1 候选仓库 */
        fun forSynopsis(
            db: AppDatabase,
            config: ConfigRepository,
            ai: AiClient,
            summaries: VolumeSummaryRepository
        ): VolumeCompressionService = create(
            chapterSource = SynopsisChapterSourceAdapter.from(db),
            config = config,
            ai = ai,
            summaries = summaries
        )

        /** 生产环境：章节正文来源（F3），其余链路与梗概压缩完全一致 */
        fun forContent(
            db: AppDatabase,
            config: ConfigRepository,
            ai: AiClient,
            summaries: VolumeSummaryRepository
        ): VolumeCompressionService = create(
            chapterSource = ContentChapterSourceAdapter.from(db),
            config = config,
            ai = ai,
            summaries = summaries
        )

        private fun create(
            chapterSource: ChapterSourceAdapter,
            config: ConfigRepository,
            ai: AiClient,
            summaries: VolumeSummaryRepository
        ): VolumeCompressionService = VolumeCompressionService(
            chapterSource = chapterSource,
            modelLoader = { modelId -> config.getModel(modelId) },
            chat = { model, request ->
                val endpoint = config.endpointFor(model.id)
                if (endpoint == null) {
                    AiResult.fail("模型配置不完整，请先检查供应商与模型设置")
                } else {
                    ai.chat(
                        endpoint.copy(
                            // 压缩任务使用专用系统前缀，不沿用聊天预设提示词
                            systemPrompt = request.systemPrompt,
                            temperature = request.temperature,
                            maxTokens = request.maxTokens
                        ),
                        request.turns
                    )
                }
            },
            saveCandidate = { candidate ->
                summaries.createCandidate(
                    bookId = candidate.bookId,
                    volumeId = candidate.volumeId,
                    sourceType = candidate.sourceType,
                    modelContent = candidate.modelContent,
                    modelId = candidate.modelId,
                    modelName = candidate.modelName,
                    compressionSessionId = candidate.compressionSessionId,
                    chapterIds = candidate.chapterIds
                )
            }
        )
    }
}
