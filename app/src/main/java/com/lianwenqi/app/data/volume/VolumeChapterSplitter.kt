package com.lianwenqi.app.data.volume

/**
 * 超长章节的无损切片器。
 *
 * 切分优先级：自然段 → 句末标点 → 次级标点 → 硬切。
 * 所有片段在原文上首尾相接、互不重叠，覆盖 `[0, text.length)` 全部区间；
 * 单个片段无论如何都放不下时返回 null，由上层给出明确配置错误，绝不无限细分。
 */
interface OversizedChapterSplitter {
    /**
     * @param sliceTokenBudget 单个片段可用的正文字符估算 Token 预算（已扣除标题与附加开销）
     * @return 无损片段列表；无法切出任何可容纳片段时返回 null
     */
    fun split(chapter: CompressionChapter, sliceTokenBudget: Int): List<CompressionChapter>?
}

class BoundaryChapterSplitter : OversizedChapterSplitter {

    override fun split(
        chapter: CompressionChapter,
        sliceTokenBudget: Int
    ): List<CompressionChapter>? {
        if (sliceTokenBudget <= 0) return null
        val text = chapter.text
        if (text.isEmpty()) return null

        val bounds = mutableListOf<Int>()
        var start = 0
        while (start < text.length) {
            val maxEnd = maxEndFitting(text, start, sliceTokenBudget)
            if (maxEnd <= start) return null
            val end = if (maxEnd >= text.length) text.length else chooseBoundary(text, start, maxEnd)
            val actualEnd = if (end <= start) maxEnd else end
            bounds += actualEnd
            start = actualEnd
        }

        val total = bounds.size
        var from = 0
        return bounds.mapIndexed { index, to ->
            val slice = chapter.copy(
                text = text.substring(from, to),
                sliceIndex = index + 1,
                sliceCount = total,
                sourceStart = from,
                sourceEnd = to
            )
            from = to
            slice
        }
    }

    /** 二分查找 [start] 起、估算 Token 不超过预算的最大结束下标（不含） */
    private fun maxEndFitting(text: String, start: Int, budget: Int): Int {
        var low = start + 1
        var high = text.length
        var best = start
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (CompressionTokenEstimator.estimate(text.substring(start, mid)) <= budget) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /** 在 [start, maxEnd) 内从后往前找最靠后的边界，找不到则硬切到 maxEnd */
    private fun chooseBoundary(text: String, start: Int, maxEnd: Int): Int {
        findLastOf(text, start, maxEnd, PARAGRAPH_BREAKS)?.let { return it }
        findLastOf(text, start, maxEnd, SENTENCE_ENDS)?.let { return it }
        findLastOf(text, start, maxEnd, SECONDARY_PUNCTUATION)?.let { return it }
        return maxEnd
    }

    private fun findLastOf(text: String, start: Int, maxEnd: Int, marks: CharArray): Int? {
        for (index in maxEnd - 1 downTo start) {
            val ch = text[index]
            if (marks.any { it == ch }) return index + 1
        }
        return null
    }

    companion object {
        private val PARAGRAPH_BREAKS = charArrayOf('\n', '\r')
        private val SENTENCE_ENDS = charArrayOf('。', '！', '？', '…', '!', '?', '.', ';', '；')
        private val SECONDARY_PUNCTUATION = charArrayOf('，', '、', '：', '—', '～', ',', ':')
    }
}

/** 自动分批：章节能放就整章放，单章超预算才切片；始终按原顺序、按完整边界聚合 */
class SlicingBudgetBatcher(
    private val splitter: OversizedChapterSplitter = BoundaryChapterSplitter()
) : CompressionBatcher {

    override fun plan(
        chapters: List<CompressionChapter>,
        inputBudgetTokens: Int,
        perChapterOverheadTokens: Int
    ): List<CompressionBatch> {
        if (chapters.isEmpty()) return emptyList()
        val budget = maxOf(1, inputBudgetTokens)
        val overhead = maxOf(0, perChapterOverheadTokens)

        val units = chapters.flatMap { chapter ->
            val titleTokens = CompressionTokenEstimator.estimate(chapter.title)
            val cost = titleTokens + CompressionTokenEstimator.estimate(chapter.text) + overhead
            if (cost <= budget) {
                listOf(chapter)
            } else {
                // 片段预算 = 整批预算 - 附加开销 - 标题；无法切片时保留整章，交给引擎明确失败
                splitter.split(chapter, budget - overhead - titleTokens) ?: listOf(chapter)
            }
        }

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

        units.forEach { unit ->
            val cost = CompressionTokenEstimator.estimate(unit.title) +
                CompressionTokenEstimator.estimate(unit.text) + overhead
            if (current.isNotEmpty() && currentCost + cost > budget) flush()
            current += unit
            currentCost += cost
            // 单个输入单元自己就超预算：单独成批，由引擎返回 InputTooLarge，而不是继续堆叠
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
}
