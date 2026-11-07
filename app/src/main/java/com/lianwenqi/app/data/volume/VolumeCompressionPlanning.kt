package com.lianwenqi.app.data.volume

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Token 估算（字符启发式）。
 *
 * 中文常用字按约 1 字 ≈ 1 Token，其它字符按约 4 字符 ≈ 1 Token 估算。
 * 这不是分词器精确值，所有展示都必须标明这是估算，不能冒充服务商 usage。
 */
object CompressionTokenEstimator {
    const val METHOD = "chars-heuristic-v1"

    private const val OTHER_CHARS_PER_TOKEN = 4.0

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + ceil(other / OTHER_CHARS_PER_TOKEN).toInt()
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || // 中日韩统一表意文字
            code in 0x3400..0x4DBF || // 扩展 A
            code in 0x3000..0x303F || // 中文标点
            code in 0x3040..0x30FF || // 假名
            code in 0xFF00..0xFFEF // 全角字符
    }
}

/** 输出规模规划：只依据输入规模与提取出的复杂度，不按「每章固定多少字」拍脑袋 */
interface CompressionLengthPlanner {
    /**
     * 规划最终卷梗概的输出 Token 预算。
     * [maxOutputTokens] <= 0 表示模型不限输出，此时由 [contextWindow] 推导上限。
     */
    fun plan(complexity: CompressionComplexity, maxOutputTokens: Int, contextWindow: Int): Int

    /** 分级整合时每份中间结果的输出预算，保证下一轮输入确实会收缩 */
    fun intermediateTarget(finalTarget: Int): Int
}

object DefaultCompressionLengthPlanner : CompressionLengthPlanner {
    private const val MIN_OUTPUT_TOKENS = 512
    private const val MAX_SAFETY_MARGIN = 256
    private const val INPUT_SHARE = 0.06

    override fun plan(
        complexity: CompressionComplexity,
        maxOutputTokens: Int,
        contextWindow: Int
    ): Int {
        val byInput = (complexity.inputTokens * INPUT_SHARE).roundToInt()
        val byComplexity = complexity.eventCount * 120 +
            complexity.participantCount * 60 +
            complexity.changeCount * 90 +
            complexity.foreshadowingCount * 65 +
            complexity.unresolvedCount * 70 +
            complexity.subplotCount * 110 +
            complexity.detailCount * 30
        val raw = maxOf(byInput, byComplexity) + 384

        val configuredCap =
            if (maxOutputTokens > 0) maxOutputTokens else contextWindow / 2
        val windowCap = contextWindow / 2
        val cap = maxOf(
            MIN_OUTPUT_TOKENS / 2,
            minOf(configuredCap, windowCap) - MAX_SAFETY_MARGIN
        )
        val lower = minOf(MIN_OUTPUT_TOKENS, cap)
        return raw.coerceIn(lower, cap)
    }

    override fun intermediateTarget(finalTarget: Int): Int =
        maxOf(MIN_OUTPUT_TOKENS / 2, (finalTarget * 2) / 5)
}

/** 批次规划：F3 可以替换成按正文块切分的实现，引擎其余流程不变 */
interface CompressionBatcher {
    fun plan(
        chapters: List<CompressionChapter>,
        inputBudgetTokens: Int,
        perChapterOverheadTokens: Int
    ): List<CompressionBatch>
}

/** 按章节边界贪心分批；绝不为了凑批次而拆分章节，也绝不截断章节文本 */
class TokenBudgetBatcher : CompressionBatcher {
    override fun plan(
        chapters: List<CompressionChapter>,
        inputBudgetTokens: Int,
        perChapterOverheadTokens: Int
    ): List<CompressionBatch> {
        if (chapters.isEmpty()) return emptyList()
        val budget = maxOf(1, inputBudgetTokens)
        val groups = mutableListOf<List<CompressionChapter>>()
        var current = mutableListOf<CompressionChapter>()
        var currentCost = 0

        fun flush() {
            if (current.isNotEmpty()) {
                groups += current
                current = mutableListOf()
                currentCost = 0
            }
        }

        chapters.forEach { chapter ->
            val cost = chapterCost(chapter, perChapterOverheadTokens)
            if (current.isNotEmpty() && currentCost + cost > budget) flush()
            current += chapter
            currentCost += cost
            // 单章自己就超预算：单独成批，由引擎明确失败而不是截断
            if (current.size == 1 && cost > budget) flush()
        }
        flush()

        return groups.mapIndexed { index, group ->
            CompressionBatch(
                index = index,
                chapters = group,
                estimatedInputTokens = group.sumOf {
                    CompressionTokenEstimator.estimate(it.title) +
                        CompressionTokenEstimator.estimate(it.text)
                }
            )
        }
    }

    private fun chapterCost(chapter: CompressionChapter, overhead: Int): Int =
        CompressionTokenEstimator.estimate(chapter.title) +
            CompressionTokenEstimator.estimate(chapter.text) +
            maxOf(0, overhead)
}

/** 分级整合的计划模拟结果（真实轮次可能因模型输出长度略有变化） */
data class MergeRoundPlan(
    val rounds: Int,
    val requestCount: Int
)

/**
 * 整合规划：先把批次提取结果的可读渲染作为节点，若一次整合放不下，
 * 按顺序分组做多轮整合（每组输出会被进一步压缩），最后再做一次最终整合。
 * 所有节点始终按顺序保留，绝不丢弃早期章节。
 */
object CompressionMergePlanner {
    const val NODE_HEADER_TOKENS = 32
    private const val MAX_SIMULATED_ROUNDS = 12

    fun nodeTokens(node: CompressionMergeNode): Int =
        CompressionTokenEstimator.estimate(node.content) + NODE_HEADER_TOKENS

    fun totalTokens(nodes: List<CompressionMergeNode>): Int = nodes.sumOf { nodeTokens(it) }

    fun group(nodes: List<CompressionMergeNode>, inputBudgetTokens: Int): List<List<CompressionMergeNode>> {
        val indices = groupIndices(nodes.map { nodeTokens(it) }, inputBudgetTokens)
        return indices.map { group -> group.map { nodes[it] } }
    }

    /** 按节点 Token 数分组，返回原始下标；单个节点超预算时单独成组 */
    fun groupIndices(nodeTokenCounts: List<Int>, inputBudgetTokens: Int): List<List<Int>> {
        if (nodeTokenCounts.isEmpty()) return emptyList()
        val budget = maxOf(1, inputBudgetTokens)
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
        nodeTokenCounts.forEachIndexed { index, tokens ->
            if (current.isNotEmpty() && currentTokens + tokens > budget) flush()
            current += index
            currentTokens += tokens
            // 单个节点自己就超过预算：单独成组，下一轮要求它自我压缩
            if (current.size == 1 && tokens > budget) flush()
        }
        flush()
        return groups
    }

    fun plan(
        initialNodes: List<CompressionMergeNode>,
        mergeInputBudgetTokens: Int,
        intermediateTarget: Int
    ): MergeRoundPlan {
        var tokens = totalTokens(initialNodes)
        if (tokens <= mergeInputBudgetTokens) return MergeRoundPlan(rounds = 1, requestCount = 1)

        var simulatedTokens = initialNodes.map { nodeTokens(it) }.toMutableList()
        var requests = 0
        var reductionRounds = 0
        val budget = maxOf(1, mergeInputBudgetTokens)
        while (simulatedTokens.sum() > budget && reductionRounds < MAX_SIMULATED_ROUNDS) {
            var groups = mutableListOf<List<Int>>()
            var current = mutableListOf<Int>()
            var currentTokens = 0
            simulatedTokens.indices.forEach { i ->
                val t = simulatedTokens[i]
                if (current.isNotEmpty() && currentTokens + t > budget) {
                    groups += current
                    current = mutableListOf()
                    currentTokens = 0
                }
                current += i
                currentTokens += t
                if (current.size == 1 && t > budget) {
                    groups += current
                    current = mutableListOf()
                    currentTokens = 0
                }
            }
            if (current.isNotEmpty()) groups += current

            // 与引擎一致：每个节点单独放得下但合起来超预算时，强制合并前两个节点
            val allSingle = groups.all { it.size == 1 && simulatedTokens[it.first()] <= budget }
            if (allSingle && simulatedTokens.size >= 2) {
                val pair = groups[0] + groups[1]
                val rest = groups.drop(2)
                groups = mutableListOf(pair)
                groups.addAll(rest)
            }

            var changed = false
            val next = mutableListOf<Int>()
            groups.forEach { group ->
                val groupTokens = group.sumOf { simulatedTokens[it] }
                if (group.size == 1 && groupTokens <= budget) {
                    next += groupTokens
                } else {
                    requests++
                    changed = true
                    next += intermediateTarget + NODE_HEADER_TOKENS
                }
            }
            if (!changed) break
            simulatedTokens = next
            reductionRounds++
        }
        // 最后一轮永远是全卷整合
        return MergeRoundPlan(rounds = reductionRounds + 1, requestCount = requests + 1)
    }
}
