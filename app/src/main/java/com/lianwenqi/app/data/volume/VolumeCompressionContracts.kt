package com.lianwenqi.app.data.volume

import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiResult

/** 一次整卷压缩请求；[sourceType] 决定使用哪个 [ChapterSourceAdapter] */
data class VolumeCompressionRequest(
    val bookId: Long,
    val volumeId: Long,
    val modelId: Long,
    val sourceType: String,
    /** 所属压缩任务（F5 的压缩会话 id）；为空表示不属于任何任务 */
    val compressionSessionId: Long? = null,
    /** 模型真实上下文窗口的临时覆盖值；<= 0 表示不覆盖，仍以模型配置为准 */
    val contextWindowOverride: Int = 0,
    /** 最大输出 Token 的临时覆盖值；<= 0 表示沿用模型配置 */
    val maxOutputTokensOverride: Int = 0,
    /** 单次请求最多提交的字符数（来自分卷压缩设置，默认 12000）；<= 0 使用默认值 */
    val maxInputChars: Int = 0,
    /** 最终卷梗概的目标字数（来自分卷压缩设置）；<= 0 表示沿用复杂度规划结果 */
    val targetOutputChars: Int = 0
)

/**
 * 标准化章节输入：来自哪个章节、在卷内的顺序、标题、所选来源文本与内容指纹。
 *
 * 超长章节按无损切片进入压缩时，[sliceIndex] / [sliceCount] / [sourceStart] / [sourceEnd]
 * 会标记它是原章节的第几片；未切分时保持默认值，禁止把片段伪装成完整章节。
 */
data class CompressionChapter(
    val chapterId: Long,
    val order: Int,
    val title: String,
    val text: String,
    val contentHash: String,
    /** 片段序号，从 1 开始；未切分时为 1 */
    val sliceIndex: Int = 1,
    /** 片段总数；未切分时为 1 */
    val sliceCount: Int = 1,
    /** 片段在原章节文本中的起始字符下标（含） */
    val sourceStart: Int = 0,
    /** 片段在原章节文本中的结束字符下标（不含） */
    val sourceEnd: Int = 0
) {
    val isSlice: Boolean get() = sliceCount > 1

    /** 章节标签；片段必须显式标注，不能冒充整章 */
    val label: String
        get() = if (isSlice) "第${order}章·片段${sliceIndex}/${sliceCount}" else "第${order}章"
}

/** 一段连续的章节范围（用于审计、进度与整合来源标记） */
data class CompressionChapterRange(
    val firstOrder: Int,
    val lastOrder: Int,
    val chapterIds: List<Long>
) {
    val label: String
        get() = if (firstOrder == lastOrder) "第${firstOrder}章" else "第${firstOrder}-${lastOrder}章"
}

/** 由一段有序章节构造范围；调用方保证非空。片段会自动去重 chapterId */
fun compressionRange(chapters: List<CompressionChapter>): CompressionChapterRange {
    require(chapters.isNotEmpty()) { "章节范围不能为空" }
    return CompressionChapterRange(
        firstOrder = chapters.first().order,
        lastOrder = chapters.last().order,
        chapterIds = chapters.map { it.chapterId }.distinct()
    )
}

/**
 * 输入审计：总章节数、实际参与章节、缺失章节都必须保留，
 * 不允许在压缩过程里静默丢掉没有梗概的章节。
 */
data class CompressionAudit(
    val totalChapterCount: Int,
    val includedChapterCount: Int,
    val missingChapterCount: Int,
    val missingChapterIds: List<Long>,
    val includedChapterIds: List<Long>,
    val sourceRevision: String,
    val includedRanges: List<CompressionChapterRange>
)

/** 一个提取批次：输入单元必须连续、按目录顺序排列；同一章节的多个片段可以连续出现 */
data class CompressionBatch(
    val index: Int,
    val chapters: List<CompressionChapter>,
    val estimatedInputTokens: Int
) {
    val range: CompressionChapterRange get() = compressionRange(chapters)
    val chapterIds: List<Long> get() = chapters.map { it.chapterId }.distinct()
    val isSliced: Boolean get() = chapters.any { it.isSlice }
}

/** 压缩计划（供 F5 预估与进度展示；Token 相关字段均为估算值） */
data class CompressionPlan(
    val inputTokenEstimate: Int,
    val estimateMethod: String,
    val totalChapterCount: Int,
    val includedChapterCount: Int,
    val batches: List<CompressionBatch>,
    val batchInputBudgetTokens: Int,
    val mergeInputBudgetTokens: Int,
    val extractionOutputBudgetTokens: Int,
    val outputTokenBudget: Int,
    val plannedMergeRoundCount: Int,
    val plannedRequestCount: Int,
    val contextWindow: Int,
    /** 实际进入批次的输入单元数（含切片），用于说明「章节数 ≠ 请求数」 */
    val inputUnitCount: Int = batches.sumOf { it.chapters.size },
    /** 被无损切分的章节数 */
    val slicedChapterCount: Int = 0
) {
    val batchCount: Int get() = batches.size
}

/**
 * 真实 usage 汇总：每次 [AiResult] 的 prompt / completion / cached 原样累加。
 * 没有返回 usage 的请求只计入 [requestCount]，不虚报 Token；
 * [inputTokenEstimate] 是字符启发式估算，永远与真实 usage 分开。
 */
data class CompressionUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val requestCount: Int = 0,
    val requestsWithUsage: Int = 0,
    val inputTokenEstimate: Int = 0,
    val estimateMethod: String = CompressionTokenEstimator.METHOD
) {
    val totalTokens: Int get() = promptTokens + completionTokens

    fun plus(result: AiResult): CompressionUsage {
        val hasUsage =
            result.promptTokens > 0 || result.completionTokens > 0 || result.cachedTokens > 0
        return copy(
            promptTokens = promptTokens + result.promptTokens,
            completionTokens = completionTokens + result.completionTokens,
            cachedTokens = cachedTokens + result.cachedTokens,
            requestCount = requestCount + 1,
            requestsWithUsage = requestsWithUsage + if (hasUsage) 1 else 0
        )
    }

    /** 合并另一段 usage（真实字段逐项相加；预估字段保留非零的一方，绝不混入真实值） */
    fun plus(other: CompressionUsage): CompressionUsage = copy(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        requestCount = requestCount + other.requestCount,
        requestsWithUsage = requestsWithUsage + other.requestsWithUsage,
        inputTokenEstimate = if (other.inputTokenEstimate > 0) other.inputTokenEstimate else inputTokenEstimate,
        estimateMethod = if (other.inputTokenEstimate > 0) other.estimateMethod else estimateMethod
    )
}

/** 输出规模规划所需的复杂度指标；事件 / 人物 / 变化等来自各批次的结构化提取结果 */
data class CompressionComplexity(
    val inputTokens: Int = 0,
    val chapterCount: Int = 0,
    val eventCount: Int = 0,
    val participantCount: Int = 0,
    val changeCount: Int = 0,
    val foreshadowingCount: Int = 0,
    val unresolvedCount: Int = 0,
    val subplotCount: Int = 0,
    val detailCount: Int = 0
) {
    companion object {
        fun of(
            extractions: List<BatchExtraction>,
            inputTokens: Int,
            chapterCount: Int
        ): CompressionComplexity = CompressionComplexity(
            inputTokens = inputTokens,
            chapterCount = chapterCount,
            eventCount = extractions.sumOf { it.events.size },
            participantCount = extractions.flatMap { it.participants }.toSet().size,
            changeCount = extractions.sumOf { it.characterChanges.size + it.relationshipChanges.size },
            foreshadowingCount = extractions.sumOf { it.foreshadowing.size },
            unresolvedCount = extractions.sumOf { it.unresolvedIssues.size },
            subplotCount = extractions.sumOf { it.subplots.size },
            detailCount = extractions.sumOf { it.importantDetails.size }
        )
    }
}

/** 单个事件及其可追溯的章节来源；跨片段 / 跨批事件在整合阶段合并 */
data class ExtractedEvent(
    val text: String,
    val chapterIds: List<Long>
)

/** 单个批次的严格结构化中间结果；只用于内部整合，不直接展示给用户 */
data class BatchExtraction(
    val chapterRange: String,
    val sourceChapterIds: List<Long>,
    val events: List<ExtractedEvent>,
    val participants: List<String>,
    val cause: String,
    val result: String,
    val characterChanges: List<String>,
    val relationshipChanges: List<String>,
    val foreshadowing: List<String>,
    val unresolvedIssues: List<String>,
    val importantDetails: List<String>,
    val subplots: List<String>
)

/**
 * 一个批次的完整结果；[rawContent] 与 [usage] 原样保留，供 F5 持久化与单批重试，
 * [attempts] 记录格式校验的有界重试次数（正常一次通过为 1）。
 */
data class CompressionBatchResult(
    val batch: CompressionBatch,
    val extraction: BatchExtraction,
    val rawContent: String = "",
    val attempts: Int = 1,
    val usage: CompressionUsage = CompressionUsage()
)

/** 整合节点：正文为提取结果的可读渲染或上一轮整合的纯文本，来源覆盖信息独立保留 */
data class CompressionMergeNode(
    val content: String,
    val chapterIds: List<Long>,
    val firstOrder: Int,
    val lastOrder: Int
) {
    val rangeLabel: String
        get() = if (firstOrder == lastOrder) "第${firstOrder}章" else "第${firstOrder}-${lastOrder}章"
}

/** 成功压缩后交给 F1 Repository 的候选写入数据 */
data class CompressionCandidate(
    val bookId: Long,
    val volumeId: Long,
    val sourceType: String,
    val modelContent: String,
    val modelId: Long,
    val modelName: String,
    val compressionSessionId: Long?,
    val chapterIds: List<Long>
)

enum class CompressionStage(val label: String) {
    EXTRACTION("分批提取"),
    MERGE("全卷整合")
}

/** 可区分的压缩失败；不用一段通用字符串把不同问题压平 */
sealed class CompressionFailure(val message: String) {
    object EmptyInput : CompressionFailure("没有可参与压缩的章节内容")

    object ModelConfigMissing : CompressionFailure("未找到所选模型，请先在设置中检查模型配置")

    object UnknownContextWindow : CompressionFailure(
        "无法确定压缩容量；请检查分卷压缩的单次最大提交字符数与最大输出 tokens 设置"
    )

    object CandidateSaveRejected : CompressionFailure("压缩结果未能写入候选版本，请检查分卷与章节归属")

    object MergeNotConverging : CompressionFailure("分级整合未能收敛，已保留中间结果，可稍后重试")

    data class SourceTypeUnsupported(val sourceType: String) :
        CompressionFailure("当前压缩引擎不支持来源类型：$sourceType")

    data class ContextWindowTooSmall(val contextWindow: Int, val detail: String) :
        CompressionFailure("模型上下文窗口过小（$contextWindow Token）：$detail")

    data class InputBudgetTooSmall(val chars: Int, val detail: String) :
        CompressionFailure("单次最大提交字符数过小（$chars 字）：$detail")

    data class InputTooLarge(val chapterId: Long, val chapterOrder: Int) :
        CompressionFailure("第${chapterOrder}章（id=$chapterId）单独超过单批容量，无法在不截断的前提下处理")

    data class RequestFailed(val error: AiError?) :
        CompressionFailure(error?.userMessage ?: "模型调用失败")

    data class EmptyOutput(val stage: CompressionStage) :
        CompressionFailure("${stage.label}返回为空")

    data class TruncatedOutput(val stage: CompressionStage) :
        CompressionFailure("${stage.label}输出被截断")

    data class InvalidJson(val stage: CompressionStage, val detail: String) :
        CompressionFailure("${stage.label}结构化结果格式错误：$detail")

    data class InvalidFinalContent(val detail: String) :
        CompressionFailure("最终卷梗概未通过校验：$detail")

    data class InvalidMergeContent(val detail: String) :
        CompressionFailure("分级整合结果未通过校验：$detail")

    /** 所有有效章节必须被成功批次完整覆盖，任何缺失都不能产生完整候选 */
    data class CoverageIncomplete(val missingChapterIds: List<Long>) :
        CompressionFailure("压缩结果未覆盖全部有效章节：缺少 ${missingChapterIds.joinToString("、")}")

    /** 内部不变量：切片的并集必须逐字还原原章节，禁止丢尾或重复 */
    data class SliceCoverageBroken(val chapterId: Long, val chapterOrder: Int) :
        CompressionFailure("第${chapterOrder}章（id=$chapterId）的切片未完整覆盖原文")
}

sealed interface VolumeCompressionOutcome {
    data class Success(
        val candidateId: Long,
        val content: String,
        val audit: CompressionAudit,
        val plan: CompressionPlan,
        val usage: CompressionUsage,
        val complexity: CompressionComplexity,
        val batchResults: List<CompressionBatchResult>,
        val mergeNodes: List<CompressionMergeNode>
    ) : VolumeCompressionOutcome

    /** 没有任何有效输入：不发起任何网络请求，也不生成空卷梗概 */
    data class NoInput(val audit: CompressionAudit) : VolumeCompressionOutcome

    data class Failed(
        val failure: CompressionFailure,
        val audit: CompressionAudit,
        val plan: CompressionPlan?,
        val usage: CompressionUsage,
        val batchResults: List<CompressionBatchResult> = emptyList(),
        val mergeNodes: List<CompressionMergeNode> = emptyList()
    ) : VolumeCompressionOutcome
}

/** 阶段进度：供 F4 展示、F5 持久化；取消也会单独通知，不伪装成失败 */
sealed interface CompressionProgress {
    data class Reading(val totalChapterCount: Int) : CompressionProgress

    data class Planned(val plan: CompressionPlan) : CompressionProgress

    data class Extracting(
        val batchIndex: Int,
        val batchCount: Int,
        val range: CompressionChapterRange
    ) : CompressionProgress

    data class Merging(
        val round: Int,
        val finalRound: Boolean,
        val nodeCount: Int,
        val coveredChapterCount: Int
    ) : CompressionProgress

    object WaitingConfirmation : CompressionProgress

    data class Completed(val candidateId: Long) : CompressionProgress

    data class Failed(val failure: CompressionFailure) : CompressionProgress

    object Cancelled : CompressionProgress
}
