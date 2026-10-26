package com.lianwenqi.app.data.style

/** 抽样策略，会写入档案 sourceMeta，便于追溯本次分析实际看了多少原文 */
enum class StyleSamplingStrategy(val label: String) {
    /** 全部样本都在预算内，按全文提交 */
    FULL("完整样本"),

    /** 样本都在提交范围内，但每个样本按开头 / 中段 / 结尾节选 */
    EVEN_SEGMENTS("按样本均匀节选"),

    /** 样本数量过多，跨样本均匀抽选一部分再节选 */
    EVEN_SELECTION("跨样本抽选节选")
}

/** 单个样本实际提交给模型的片段 */
data class StyleSampleBlock(
    val order: Int,
    val fileName: String,
    val format: StyleSampleFormat,
    /** 该样本原文总字符数 */
    val totalChars: Int,
    /** 实际提交的正文片段 */
    val text: String,
    /** 片段说明：全文 / 开头、中段、结尾节选 */
    val mode: String
)

/**
 * 一次分析实际提交给模型的样本计划。
 * [includedChars] 与 [totalChars] 用于在界面和档案元信息中如实说明「实际分析了多少原文」。
 */
data class StyleSamplePlan(
    val blocks: List<StyleSampleBlock>,
    val strategy: StyleSamplingStrategy,
    val budgetChars: Int,
    val totalSampleCount: Int,
    val totalChars: Int,
    val includedChars: Int
) {
    val isSampled: Boolean get() = strategy != StyleSamplingStrategy.FULL
    val analyzedSampleCount: Int get() = blocks.size
    val skippedSampleCount: Int get() = totalSampleCount - analyzedSampleCount
}

/**
 * 样本长文本策略（纯 Kotlin，便于单元测试）。
 *
 * 不对整本书做无条件全量拼接，也不静默只取开头：
 * - 总量在预算内：全部按全文提交；
 * - 超出预算且样本数不多：每个样本按「开头 / 中段 / 结尾」三段均匀节选；
 * - 样本数过多：先跨顺序均匀抽选至多 [MAX_SELECTED_SAMPLES] 个样本，再各自三段节选。
 *
 * 该策略只做单次请求内的抽样，不做多轮分段汇总；实际策略与字符数会写入档案元信息。
 */
object StyleSampleSampler {

    /** 单次分析提交的样本文本上限（字符）。保守取值，兼容上下文较小的文本模型 */
    const val DEFAULT_BUDGET_CHARS = 12_000

    /** 抽选样本时最多保留的样本数 */
    const val MAX_SELECTED_SAMPLES = 8

    /** 每个样本尽量保留的最少字符数 */
    private const val MIN_BLOCK_CHARS = 400

    private const val EXCERPT_MODE = "开头、中段、结尾节选"
    private const val FULL_MODE = "全文"

    /** 两处 "\n……\n" 分隔符的总长度 */
    private const val SEPARATOR_OVERHEAD = 8

    fun plan(
        samples: List<StyleSampleInput>,
        budgetChars: Int = DEFAULT_BUDGET_CHARS
    ): StyleSamplePlan {
        val usable = samples.asSequence()
            .filter { it.text.isNotBlank() }
            .sortedBy { it.order }
            .toList()
        if (usable.isEmpty()) {
            return StyleSamplePlan(
                blocks = emptyList(),
                strategy = StyleSamplingStrategy.FULL,
                budgetChars = budgetChars,
                totalSampleCount = samples.size,
                totalChars = 0,
                includedChars = 0
            )
        }

        val totalChars = usable.sumOf { it.text.length }
        if (totalChars <= budgetChars) {
            val blocks = usable.map { sample ->
                StyleSampleBlock(
                    order = sample.order,
                    fileName = sample.fileName,
                    format = sample.format,
                    totalChars = sample.text.length,
                    text = sample.text,
                    mode = FULL_MODE
                )
            }
            return StyleSamplePlan(
                blocks = blocks,
                strategy = StyleSamplingStrategy.FULL,
                budgetChars = budgetChars,
                totalSampleCount = samples.size,
                totalChars = totalChars,
                includedChars = totalChars
            )
        }

        val selected = if (usable.size <= MAX_SELECTED_SAMPLES) usable else pickEvenly(usable)
        val strategy = if (selected.size == usable.size) {
            StyleSamplingStrategy.EVEN_SEGMENTS
        } else {
            StyleSamplingStrategy.EVEN_SELECTION
        }
        val shares = allocate(selected.map { it.text.length }, budgetChars)
        val blocks = selected.mapIndexed { index, sample ->
            val (text, mode) = excerpt(sample.text, shares[index])
            StyleSampleBlock(
                order = sample.order,
                fileName = sample.fileName,
                format = sample.format,
                totalChars = sample.text.length,
                text = text,
                mode = mode
            )
        }
        return StyleSamplePlan(
            blocks = blocks,
            strategy = strategy,
            budgetChars = budgetChars,
            totalSampleCount = samples.size,
            totalChars = totalChars,
            includedChars = blocks.sumOf { it.text.length }
        )
    }

    /** 跨顺序均匀抽选（含首尾），保证覆盖不同章节 / 不同阶段 */
    private fun pickEvenly(samples: List<StyleSampleInput>): List<StyleSampleInput> {
        val count = MAX_SELECTED_SAMPLES
        val last = samples.size - 1
        return (0 until count)
            .map { index -> (index.toLong() * last / (count - 1)).toInt() }
            .distinct()
            .map { samples[it] }
    }

    /** 按原文长度比例分配字符预算；每个样本尽量不少于 [MIN_BLOCK_CHARS] */
    private fun allocate(lengths: List<Int>, budget: Int): List<Int> {
        val total = lengths.sum().coerceAtLeast(1)
        val proportional = lengths.map { length -> budget.toLong() * length / total }
        val withFloor = proportional.mapIndexed { index, share ->
            minOf(lengths[index].toLong(), maxOf(share, MIN_BLOCK_CHARS.toLong()))
        }
        val floorSum = withFloor.sum()
        if (floorSum <= budget) {
            return withFloor.map { it.toInt().coerceAtLeast(1) }
        }
        // 加上保底值后超出预算时退回纯比例分配（可能少于保底值，但绝不超出预算）
        return proportional.map { it.toInt().coerceAtLeast(1) }
    }

    /** 取开头 / 中段 / 结尾；长度不足时退回前 [share] 个字符 */
    private fun excerpt(text: String, share: Int): Pair<String, String> {
        if (text.length <= share) return text to FULL_MODE
        // 两处「……」分隔符占用少量字符，从片段预算中扣除，保证实际提交不超过分配额度
        val segment = (share - SEPARATOR_OVERHEAD) / 3
        if (segment < 20) return text.substring(0, share.coerceAtMost(text.length)) to "开头节选"
        val head = text.substring(0, segment)
        val middleStart = (text.length - segment) / 2
        val middle = text.substring(middleStart, middleStart + segment)
        val tail = text.substring(text.length - segment)
        val joined = buildString {
            append(head.trim())
            append("\n……\n")
            append(middle.trim())
            append("\n……\n")
            append(tail.trim())
        }
        return joined to EXCERPT_MODE
    }
}
