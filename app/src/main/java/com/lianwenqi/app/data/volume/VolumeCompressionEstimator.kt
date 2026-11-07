package com.lianwenqi.app.data.volume

import com.lianwenqi.app.data.VolumeSourceRevision
import com.lianwenqi.app.util.shortHash

/**
 * 启动前的 Token / 请求规模预估（全部是可测试的启发式，界面必须标注「预计」）。
 *
 * 与真实 usage 的关系：这里的所有数值只依赖章节输入、批次计划与容量规划，
 * 不读取也不写入任何 `AiResult` 的 prompt / completion / cached Tokens。
 */
data class CompressionEstimate(
    val plan: CompressionPlan,
    val inputTokens: Int,
    val outputTokens: Int,
    val extractionInputTokens: Int,
    val mergeInputTokens: Int,
    val extractionOutputTokens: Int,
    val mergeOutputTokens: Int,
    val batchCount: Int,
    val requestCount: Int,
    val method: String = CompressionTokenEstimator.METHOD
)

object VolumeCompressionEstimator {

    /**
     * 预估值按四段累加：
     * 1. 提取输入 = 章节输入 + 每批系统提示词与请求包装开销；
     * 2. 提取输出 = 批次数 × 单批输出预算（保守上限）；
     * 3. 整合输入 / 输出 = 以「每个提取结果占用其输出预算」作为节点规模模拟多轮整合；
     * 4. 最终全卷整合单列一次请求（模拟已包含）。
     */
    fun estimate(
        audit: CompressionAudit,
        batches: List<CompressionBatch>,
        inputTokens: Int,
        contextWindow: Int,
        mergeInputBudgetTokens: Int = 0,
        maxOutputTokens: Int,
        targetOutputChars: Int = 0,
        extraction: CapacityPlan.Ready,
        capacityPlanner: CompressionCapacityPlanner,
        extractionSystemPromptTokens: Int,
        mergeSystemPromptTokens: Int
    ): CompressionEstimate {
        val complexity = CompressionComplexity(
            inputTokens = inputTokens,
            chapterCount = audit.includedChapterCount
        )
        val merge = when (
            val planned = capacityPlanner.mergeForInputBudget(
                inputBudgetTokens = if (mergeInputBudgetTokens > 0) {
                    mergeInputBudgetTokens
                } else {
                    extraction.inputBudgetTokens
                },
                maxOutputTokens = maxOutputTokens,
                complexity = complexity
            )
        ) {
            is CapacityPlan.Ready -> planned
            // 预算偏小时仍给出可读的预估：沿用提取预算，实际运行会由引擎再校验
            is CapacityPlan.TooSmall -> extraction
        }
        val finalTarget = capacityPlanner.finalTargetForChars(
            targetOutputChars = targetOutputChars,
            maxOutputTokens = maxOutputTokens,
            planned = merge.outputTokens
        )
        val intermediate = capacityPlanner.intermediateTarget(finalTarget)
        val nodeTokens = List(batches.size) { extraction.outputTokens }
        val mergePlan = simulateMerge(
            initialNodeTokens = nodeTokens,
            mergeInputBudgetTokens = merge.inputBudgetTokens,
            intermediateTarget = intermediate,
            finalTargetTokens = finalTarget,
            mergeSystemPromptTokens = mergeSystemPromptTokens
        )

        val perBatchOverhead = extractionSystemPromptTokens +
            CompressionCapacityPlanner.CHAPTER_OVERHEAD_TOKENS +
            CompressionCapacityPlanner.PER_REQUEST_OVERHEAD_TOKENS
        val extractionInput = inputTokens + batches.size * perBatchOverhead
        val extractionOutput = batches.size * extraction.outputTokens
        val mergeInput = mergePlan.inputTokens
        val mergeOutput = mergePlan.outputTokens

        val plan = CompressionPlan(
            inputTokenEstimate = inputTokens,
            estimateMethod = CompressionTokenEstimator.METHOD,
            totalChapterCount = audit.totalChapterCount,
            includedChapterCount = audit.includedChapterCount,
            batches = batches,
            batchInputBudgetTokens = extraction.inputBudgetTokens,
            mergeInputBudgetTokens = merge.inputBudgetTokens,
            extractionOutputBudgetTokens = extraction.outputTokens,
            outputTokenBudget = finalTarget,
            plannedMergeRoundCount = mergePlan.rounds,
            plannedRequestCount = batches.size + mergePlan.requestCount,
            contextWindow = contextWindow,
            slicedChapterCount = batches.flatMap { it.chapters }
                .filter { it.isSlice }
                .map { it.chapterId }
                .distinct()
                .size
        )
        return CompressionEstimate(
            plan = plan,
            inputTokens = extractionInput + mergeInput,
            outputTokens = extractionOutput + mergeOutput,
            extractionInputTokens = extractionInput,
            mergeInputTokens = mergeInput,
            extractionOutputTokens = extractionOutput,
            mergeOutputTokens = mergeOutput,
            batchCount = batches.size,
            requestCount = plan.plannedRequestCount
        )
    }

    /** 与 [CompressionMergePlanner] 同构的预算模拟；只用于预估，不发起请求 */
    private fun simulateMerge(
        initialNodeTokens: List<Int>,
        mergeInputBudgetTokens: Int,
        intermediateTarget: Int,
        finalTargetTokens: Int,
        mergeSystemPromptTokens: Int
    ): MergeSimulation {
        val budget = maxOf(1, mergeInputBudgetTokens)
        var nodes = initialNodeTokens.toMutableList()
        var input = 0
        var output = 0
        var requests = 0
        var rounds = 0
        val overhead = mergeSystemPromptTokens + CompressionCapacityPlanner.PER_REQUEST_OVERHEAD_TOKENS

        while (nodes.sum() > budget && rounds < MAX_SIMULATED_ROUNDS && nodes.isNotEmpty()) {
            var groups = groupIndices(nodes, budget)
            val allSingle = groups.all { it.size == 1 && nodes[it.first()] <= budget }
            if (allSingle && nodes.size >= 2) {
                groups = listOf(groups[0] + groups[1]) + groups.drop(2)
            }
            val next = mutableListOf<Int>()
            groups.forEach { group ->
                val groupTokens = group.sumOf { nodes[it] }
                if (group.size == 1 && groupTokens <= budget) {
                    next += groupTokens
                } else {
                    input += groupTokens + overhead
                    output += intermediateTarget
                    requests++
                    next += intermediateTarget + CompressionMergePlanner.NODE_HEADER_TOKENS
                }
            }
            if (next.sum() >= nodes.sum()) break
            nodes = next
            rounds++
        }

        if (nodes.isNotEmpty()) {
            input += nodes.sum() + overhead
            output += finalTargetTokens
            requests++
        }
        return MergeSimulation(
            rounds = rounds + 1,
            requestCount = requests,
            inputTokens = input,
            outputTokens = output
        )
    }

    private fun groupIndices(nodeTokens: List<Int>, budget: Int): List<List<Int>> {
        val groups = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentTokens = 0
        fun flush() {
            if (current.isNotEmpty()) {
                groups += current
                current = mutableListOf()
                currentTokens = 0
            }
        }
        nodeTokens.forEachIndexed { index, tokens ->
            if (current.isNotEmpty() && currentTokens + tokens > budget) flush()
            current += index
            currentTokens += tokens
            if (current.size == 1 && tokens > budget) flush()
        }
        flush()
        return groups
    }

    private data class MergeSimulation(
        val rounds: Int,
        val requestCount: Int,
        val inputTokens: Int,
        val outputTokens: Int
    )

    private const val MAX_SIMULATED_ROUNDS = 12
}

/**
 * 批次输入指纹：源类型 + 章节 id + 章节内容指纹 + 片段定位。
 *
 * 恢复时只有「已完成且指纹完全一致」的批次才允许跳过；源内容、章节顺序、
 * 片段切分方式任意一项变化都会产生不同指纹，从而强制重跑。
 */
object VolumeCompressionInputHash {
    fun of(sourceType: String, batch: CompressionBatch): String {
        val entries = batch.chapters.map {
            VolumeSourceRevision.Entry(it.chapterId, it.contentHash)
        }
        return shortHash(
            buildString {
                append(VolumeSourceRevision.payload(sourceType, entries))
                append('|').append(batch.index)
                batch.chapters.forEach { chapter ->
                    append('|').append(chapter.chapterId)
                        .append(':').append(chapter.sliceIndex).append('/').append(chapter.sliceCount)
                        .append(':').append(chapter.sourceStart).append('-').append(chapter.sourceEnd)
                }
            }
        )
    }
}
