package com.lianwenqi.app.data.volume

import com.lianwenqi.app.data.entity.AiModel

/**
 * 可以独立调用的压缩阶段（F5 任务协调器按阶段落库与恢复）。
 *
 * [VolumeCompressionService.compress] 仍然是一次跑完的组合门面；
 * 协调器则按 [prepare] → [extractBatch] → [merge] → [finalizeCandidate] 逐步调用，
 * 每个阶段都可单独失败、单独重试，不重复扣费已成功的批次。
 */
data class CompressionPreparation(
    val request: VolumeCompressionRequest,
    val model: AiModel,
    val audit: CompressionAudit,
    val included: List<CompressionChapter>,
    val inputTokens: Int,
    val contextWindow: Int,
    /** 单次请求最多提交的字符数（来源：分卷压缩设置） */
    val maxInputChars: Int = 0,
    /** 最终卷梗概的目标字数（来源：分卷压缩设置）；<= 0 表示沿用复杂度规划 */
    val targetOutputChars: Int = 0,
    val maxOutputTokens: Int,
    val extractionCapacity: CapacityPlan.Ready,
    val batches: List<CompressionBatch>
)

sealed interface PreparationOutcome {
    data class Ready(val preparation: CompressionPreparation) : PreparationOutcome

    /** 没有任何有效输入：不发起请求，也不生成空卷梗概 */
    data class NoInput(val audit: CompressionAudit) : PreparationOutcome

    data class Failed(
        val failure: CompressionFailure,
        val audit: CompressionAudit,
        val usage: CompressionUsage
    ) : PreparationOutcome
}

sealed interface BatchExtractionOutcome {
    data class Success(val result: CompressionBatchResult) : BatchExtractionOutcome

    data class Failed(
        val failure: CompressionFailure,
        /** 本次失败尝试的真实 usage（含格式重试） */
        val usage: CompressionUsage,
        val rawContent: String,
        val attempts: Int
    ) : BatchExtractionOutcome
}

/** 一次整合请求（分级整合或最终整合）的落库快照；[usage] 只来自本次请求的 AiResult */
data class MergeRequestSnapshot(
    val index: Int,
    val round: Int,
    val finalRound: Boolean,
    val rangeLabel: String,
    val chapterIds: List<Long>,
    val rawContent: String,
    val usage: CompressionUsage,
    val failure: CompressionFailure? = null
) {
    val success: Boolean get() = failure == null
}

sealed interface MergeOutcome {
    data class Success(
        val content: String,
        val plan: CompressionPlan,
        val complexity: CompressionComplexity,
        val mergeNodes: List<CompressionMergeNode>,
        /** 包含调用方传入的基础 usage */
        val usage: CompressionUsage,
        /** 仅整合阶段的 usage（提取阶段之外的真实消耗） */
        val mergeUsage: CompressionUsage
    ) : MergeOutcome

    data class Failed(
        val failure: CompressionFailure,
        /** 未能生成计划时为 null */
        val plan: CompressionPlan?,
        val mergeNodes: List<CompressionMergeNode>,
        val usage: CompressionUsage,
        val mergeUsage: CompressionUsage
    ) : MergeOutcome
}

sealed interface CandidateFinalizeOutcome {
    data class Saved(val candidateId: Long) : CandidateFinalizeOutcome

    data class Rejected(val failure: CompressionFailure) : CandidateFinalizeOutcome
}
