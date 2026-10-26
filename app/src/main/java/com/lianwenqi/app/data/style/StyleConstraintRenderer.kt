package com.lianwenqi.app.data.style

/**
 * 把已校验的结构化文风分析结果（[StyleAnalysis]）转换成注入写作请求的「文风约束」块。
 *
 * 只表达语言表达层面的写作规律，不携带任何剧情事实，也不改写用户任务：
 * - 空字段、空条目、以「证据不足」开头的条目一律省略，绝不补写通用结论；
 * - 某维度的证据全部为 insufficient 时整节省略；最好证据只有 low 时标注「可信度低」；
 * - 按优先级拼装并受 [MAX_BLOCK_CHARS] 限制，避免提示词过长或重复。
 */
object StyleConstraintRenderer {

    /** 注入块抬头（调用方与测试断言的固定字面量） */
    const val HEADER = "【文风约束】"

    private const val OVERVIEW_LABEL = "整体风格"
    private const val MAX_ITEMS_PER_SECTION = 6
    private const val MAX_BLOCK_CHARS = 2400
    private const val INSUFFICIENT_PREFIX = "证据不足"

    private const val INTRO =
        "以下为本书绑定文风档案的结构化分析结论，仅用于约束语言表达、叙述方式与写作习惯；" +
            "不得据此改动本章梗概、大纲、人物设定或用户明确要求的剧情内容。"
    private const val PRIORITY =
        "以上约束只作用于表达方式：与本章梗概、大纲、人物设定或用户要求冲突时，一律以剧情事实与用户要求为准。"

    private val CONFIDENCE_ORDER = listOf(
        StyleConfidence.HIGH,
        StyleConfidence.MEDIUM,
        StyleConfidence.LOW,
        StyleConfidence.INSUFFICIENT
    )

    private val WHITESPACE = Regex("\\s+")

    /** 一个可注入的字段：维度名、条目、条目对应的证据维度（overview / guidelines / avoidances 为 null） */
    private data class Section(
        val label: String,
        val items: List<String>,
        val dimension: String? = null,
        val numbered: Boolean = true
    )

    /**
     * 生成注入块；没有任何可用内容时返回空串（调用方据此判定「本次无法应用文风」）。
     */
    fun render(analysis: StyleAnalysis): String {
        val confidence = confidenceByDimension(analysis.evidenceAndConfidence)
        // 顺序即优先级：先给可执行的指导与整体判断，再给各维度细节
        val sections = listOf(
            Section(OVERVIEW_LABEL, listOf(analysis.overview), numbered = false),
            Section("可执行的仿写指导", analysis.writingGuidelines),
            Section("应避免的写法", analysis.avoidances),
            Section("叙述方式", analysis.narration, "narration"),
            Section("句式与节奏", analysis.sentenceRhythm, "sentenceRhythm"),
            Section("词汇与表达", analysis.diction, "diction"),
            Section("描写方法", analysis.description, "description"),
            Section("段落组织", analysis.paragraphStructure, "paragraphStructure"),
            Section("对话特征", analysis.dialogue, "dialogue"),
            Section("情绪表达", analysis.emotion, "emotion"),
            Section("场景与叙事衔接", analysis.transition, "transition")
        )
        val limit = MAX_BLOCK_CHARS - PRIORITY.length - 2
        val body = StringBuilder()
        body.append(HEADER).append('\n').append(INTRO).append('\n')
        for (section in sections) {
            appendSection(body, section, confidence[section.dimension], limit)
        }
        if (body.length <= HEADER.length + INTRO.length + 2) return ""
        body.append(PRIORITY)
        return body.toString().trimEnd('\n')
    }

    /** 逐个字段追加；放不下就停止，保证总长度可控 */
    private fun appendSection(
        body: StringBuilder,
        section: Section,
        confidence: String?,
        limit: Int
    ) {
        if (confidence == StyleConfidence.INSUFFICIENT) return
        val items = usableItems(section.items, confidence)
        if (items.isEmpty()) return
        val header = buildString {
            append(section.label)
            if (section.dimension != null && confidence == StyleConfidence.LOW) {
                append("（可信度低，仅作参考）")
            }
            append(if (section.numbered) "：\n" else "：")
        }
        if (!section.numbered) {
            val line = header + items.first() + "\n"
            if (body.length + line.length <= limit) body.append(line)
            return
        }
        // 至少能放下一行才写标题，避免留下空标题
        if (body.length + header.length + numberedLine(items.first(), 1).length > limit) return
        body.append(header)
        var added = 0
        for (item in items) {
            val line = numberedLine(item, added + 1)
            if (body.length + line.length > limit) break
            body.append(line)
            added++
        }
        if (added > 0) body.append('\n')
    }

    private fun numberedLine(item: String, index: Int): String = "$index. $item\n"

    /** 过滤空条目与「证据不足」条目，并限制条数 */
    private fun usableItems(items: List<String>, confidence: String?): List<String> {
        if (confidence == StyleConfidence.INSUFFICIENT) return emptyList()
        return items.asSequence()
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() && !it.startsWith(INSUFFICIENT_PREFIX) }
            .take(MAX_ITEMS_PER_SECTION)
            .toList()
    }

    /** 每个维度取最好的一条证据可信度；未标注证据的维度返回 null */
    private fun confidenceByDimension(evidence: List<StyleEvidence>): Map<String, String> {
        if (evidence.isEmpty()) return emptyMap()
        return evidence.groupBy { it.dimension }.mapValues { (_, entries) ->
            entries.minByOrNull { confidenceRank(it.confidence) }?.confidence ?: StyleConfidence.INSUFFICIENT
        }
    }

    private fun confidenceRank(confidence: String): Int =
        CONFIDENCE_ORDER.indexOf(confidence).takeIf { it >= 0 } ?: CONFIDENCE_ORDER.size
}
