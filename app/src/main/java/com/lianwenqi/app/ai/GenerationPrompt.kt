package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.style.StyleSampleHit
import com.lianwenqi.app.data.style.SummarySentenceSample
import com.lianwenqi.app.util.TextTool

/** 字数兜底结果：[over] 表示被截断（原输出超上限），[under] 表示原输出低于下限（不重试，仅提示） */
data class WordRangeResult(
    val text: String,
    val over: Boolean = false,
    val under: Boolean = false
)

/** 句末标点 / 换行：截断时只切在这些位置，保证留下的都是完整句子 */
private val SENTENCE_ENDINGS = "。！？…\n!?.;；"

/** 用户输入留空时的兜底指令（构建请求与「本轮提示词」日志共用，保证二者逐字一致） */
const val SUMMARY_FALLBACK_INPUT = "请根据以上资料生成本章梗概。"
const val CONTENT_FALLBACK_INPUT = "请开始撰写本章正文。"

/** 「参考资料」块的固定抬头（聊天与生成两条路径共用同一字面量，保证共享前缀逐字节一致） */
const val REFERENCE_BLOCK_HEADER = "以下是参考资料，请在写作时参考：\n"

/**
 * 「历史对话前缀」各块抬头：由「前缀词管理 → 历史对话前缀」按开关渲染，
 * 排在稳定前缀之后、本轮用户输入之前，避免把历史当成正在续写的回答。
 */
const val HISTORY_SUMMARY_HEADER = "【历史梗概】\n以下为本会话历史章节的梗概，请作为前情参考：\n"
const val HISTORY_CONTENT_HEADER = "【历史正文】\n以下为本会话历史章节的正文，请作为前情参考：\n"
const val HISTORY_DIALOGUE_HEADER = "【历史对话】\n以下为本会话的历史消息，请作为前情参考：\n"

/**
 * 生成任务的开头：有章名时点明「现在撰写的是『第2章』」，无章名时退回「为当前章节」。
 */
private fun taskHead(chapterTitle: String, action: String): String =
    if (chapterTitle.isBlank()) "你的任务：$action。\n"
    else "你的任务：现在撰写的是「${chapterTitle.trim()}」，请$action。\n"

/**
 * 生成请求与聊天请求共用的前段块：文风约束（会话内冻结）→ 自定义前缀 → 「以下是参考资料」。
 * 顺序与 [com.lianwenqi.app.data.SendChatMessageUseCase] 组装聊天请求时完全一致，
 * 用来让两边命中同一段供应商侧前缀缓存；生成专用块（[buildSummaryTurns] / [buildContentTurns]）
 * 在本函数结果之后追加（需要携带历史对话前缀时，历史块插在两者中间）。
 *
 * 注：AI 工具已改为原生工具调用（tools 参数），不再把格式提示词塞进前缀。
 */
fun buildGenerationPrefixTurns(
    customPrefix: String,
    reference: String,
    styleBlock: String = ""
): List<ChatTurn> = buildList {
    // 文风块最优先：冻结在会话前缀的最前面，先于自定义前缀与参考资料载入
    if (styleBlock.isNotBlank()) add(ChatTurn(ChatRole.SYSTEM, styleBlock))
    if (customPrefix.isNotBlank()) add(ChatTurn(ChatRole.SYSTEM, customPrefix))
    if (reference.isNotBlank()) add(ChatTurn(ChatRole.SYSTEM, REFERENCE_BLOCK_HEADER + reference))
}

/** 正文段落分隔的块标题（与 [buildContentTurns] 一一对应） */
private const val HEAD_SUMMARY = "【本章梗概】"
private const val HEAD_OUTLINE = "【大纲 / 人物设定】"
private const val HEAD_MEMORY = "【记忆检索参考资料】"
private const val HEAD_USER = "【用户要求】"
private const val HEAD_SENTENCE_TAGS = "【本章梗概·文风标签句例】"
private const val HEAD_STYLE_SAMPLES = "【临时文风样本】"

/** 临时文风样本的使用规则：作为本次正文的第一优先表达依据，不复制内容，也不写入任何长期数据 */
private val STYLE_SAMPLES_RULES = """
【临时文风样本使用规则】
临时文风样本是本轮正文的第一优先表达依据，不是普通背景资料，也不是可看可不看的附加材料。
动笔前必须先通读样本、归纳其写法，并在写作过程中持续对照；正文从第一句到最后一句都要保持同一套写法。
必须主动分析并运用样本中体现的表达习惯，保持以下特征的一致性：
句式习惯：长短句比例、句子结构、复句使用频率、句子之间的衔接方式。
叙述节奏：叙述速度、场景展开速度、动作与心理描写的切换频率。
描写习惯：环境、人物外貌、动作、神态、心理活动的描写方式及侧重点。
语言习惯：用词偏好、修辞倾向、语气、表达的含蓄程度、语言密度。
对话习惯：对话长度、人物说话方式、对话与动作及神态描写的穿插方式。
叙述视角：叙述距离、信息呈现方式、人物内心与外部叙述的结合方式。
不要只在个别句子中模仿样本，而应使这些风格特征贯穿本次生成的整个正文。
样本只提供写法：与「本章梗概」「大纲 / 人物设定」「记忆检索参考资料」的事实、剧情或设定冲突时，一律以后者为准，但表达方式仍以样本为准。
不得复制样本中的人物、剧情、设定、地点、专有名词和具体台词。不得为了模仿文风而改变剧情事实、人物性格或事件发展。""".trim()

/** 参考资料末尾的收尾强调：紧跟用户输入，利用最近位置权重确保文风样本被执行 */
private val STYLE_SAMPLES_REMINDER = """
【文风执行要求】
本轮的句式、节奏、描写、用词、对话与叙述视角必须与上文【临时文风样本】保持一致；样本中的人物、剧情、设定、地点和台词不得复制；事实与剧情以「本章梗概」「大纲 / 人物设定」为准。
只输出正文，不要解释如何模仿文风，也不要输出文风分析过程。""".trim()

/** 字数上限是否有效：区间为空或上限 <= 0 都视作「不约束」 */
private fun upperBound(range: IntRange?): Int? =
    range?.last?.takeIf { it > 0 }

/** 是否校验下限：下限 <= 0 表示只约束上限（用户把滑条拉到 0 即不设下限） */
private fun hasLowerBound(range: IntRange?): Boolean =
    range != null && range.first > 0 && range.first <= (upperBound(range) ?: Int.MAX_VALUE)

/**
 * 生成梗概的 messages：System 指令（含字数硬约束与 4 步自检流程）+ 参考资料块 + 用户即时输入。
 * [input] 为空时给一句兜底指令，避免空 user 轮次。
 *
 * [withTags] 为 true 且 [profileTags] 非空时，一次结构化输出 synopsis + 句段标签
 * （每 3 句为一段）：[profileTags] 传入「用户全局标签 + 当前绑定档案标签」，
 * 模型只能引用这些 id，并且必须原样复制句段作为 text。
 */
fun buildSummaryTurns(
    input: String,
    range: IntRange?,
    referenceBlock: String = "",
    chapterTitle: String = "",
    /** true：一次结构化输出梗概 + 梗概句子标签；字数约束只作用于 synopsis */
    withTags: Boolean = false,
    /** 可用的打标标签（用户全局标签 + 当前绑定档案标签）；为空时不要求打标 */
    profileTags: List<StyleTag> = emptyList()
): List<ChatTurn> {
    val tagging = withTags && profileTags.isNotEmpty()
    val max = upperBound(range)
    val lower = if (hasLowerBound(range)) range!!.first else null
    val instruction = buildString {
        append(taskHead(chapterTitle, "为本章生成梗概"))
        when {
            max == null -> Unit
            lower == null -> append("字数硬约束：最终输出不要超过 ").append(max).append(" 字（含标点）。\n")
            else -> append("字数硬约束：最终输出必须在 ").append(lower).append("–").append(max)
                .append(" 字之间（含标点）。\n")
        }
        append("\n请在草稿里完成下列自检，它们只作为你的内部思考，严禁出现在输出中：\n")
        append("1. 列出本章必须覆盖的 3–5 个核心事件。\n")
        append("2. 按事件顺序写梗概草稿。\n")
        append("3. 数一下草稿字数：\n")
        if (lower != null) {
            append("   - 低于 ").append(lower).append("：补充一个次要情节或人物反应的细节；\n")
        }
        if (max != null) {
            append("   - 高于 ").append(max).append("：删除修饰性描写，合并相邻事件；\n")
        }
        append("   - 在范围内：输出。\n")
        if (tagging) {
            append("4. 把最终梗概按顺序每 3 句合并成一个句段（最后不足 3 句按实际句数），标出符合文风标签的句段，只输出一个 JSON 对象。\n")
            append("\n句段标签要求：\n")
            append("- synopsis 必须是上面字数约束下的最终梗概整段文字；\n")
            append("- sentences 只收录需要打标的句段，text 必须从 synopsis 中原样复制整个句段（连续的 1～3 句，含标点，不要拆分或改写）；\n")
            append("- 每个句段的 tags 只能引用下面【可用标签（用户全局标签 + 当前文风档案标签）】中的 id，不得发明新标签；\n")
            append("- 一个句段可以对应多个标签，一个标签也可以对应多个句段；\n")
            append("- 没有符合标签含义的句段时输出空数组，不要为了凑标签而强行标注。\n")
            append("\n【可用标签（用户全局标签 + 当前文风档案标签）】\n")
            profileTags.forEach { tag ->
                append("- id=").append(tag.id)
                    .append(" 分类=").append(tag.category)
                    .append(" 名称=").append(tag.name)
                if (tag.description.isNotBlank()) append(" 说明=").append(tag.description)
                append('\n')
            }
            append("\n输出格式（只输出该 JSON，不要 markdown 围栏）：\n")
            append("{\"synopsis\":\"最终梗概正文\",\"sentences\":[{\"text\":\"句段原文\",\"tags\":[{\"id\":12}]}]}\n")
            append("\n输出禁令：除该 JSON 对象外，不得输出任何其他内容 —— 包括核心事件清单、步骤说明、字数统计、标题、章节名、列表符号或任何解释。")
        } else {
            append("4. 只输出最终梗概这一整段文字。\n")
            append("\n输出禁令：除最终梗概本身外，不得输出任何其他内容 —— 包括核心事件清单、步骤说明、字数统计、字数区间、标题、章节名、列表符号、markdown 标记或任何解释。")
        }
    }
    return buildList {
        add(ChatTurn(ChatRole.SYSTEM, instruction))
        if (referenceBlock.isNotBlank()) add(ChatTurn(ChatRole.SYSTEM, referenceBlock))
        add(ChatTurn(ChatRole.USER, input.trim().ifBlank { SUMMARY_FALLBACK_INPUT }))
    }
}

/**
 * 生成正文的 messages，块顺序固定为：
 * 系统写作指令 → 本章梗概槽位 → 大纲 / 人物设定槽位 → 记忆检索参考资料块（仅启用时）→ 用户即时输入。
 * （调用方可在本块之前插入「历史梗概 / 历史正文 / 历史对话」块，见 [HISTORY_SUMMARY_HEADER] 等。）
 *
 * 文风约束块不在这里：它已冻结进会话前缀，由 [buildGenerationPrefixTurns] 排在最前载入。
 *
 * 参考资料各块独立成段、顺序稳定：同一章重新生成正文时只有最后一句用户输入会变，
 * 前面部分逐字节一致，供应商侧前缀缓存可持续命中。
 * 梗概生成那一轮的 assistant 输出**不得**出现在这里，梗概只以文本变量形式注入。
 */
fun buildContentTurns(
    summary: String,
    outlineSlot: String,
    memoryBlock: String,
    userInput: String,
    range: IntRange?,
    chapterTitle: String = "",
    /** 【本章梗概·文风标签句例】块，为空表示本次没有可抽样的句子 */
    summarySentenceBlock: String = "",
    /** 【临时文风样本】+ 使用规则，为空表示本次没有检索到样本 */
    styleSamplesBlock: String = ""
): List<ChatTurn> {
    val max = upperBound(range)
    val lower = if (hasLowerBound(range)) range!!.first else null
    val instruction = buildString {
        append(taskHead(chapterTitle, "依据下方「本章梗概」撰写本章正文"))
        when {
            max == null -> Unit
            lower == null -> append("字数硬约束：最终正文不要超过 ").append(max).append(" 字（含标点）。\n")
            else -> append("字数硬约束：最终正文必须在 ").append(lower).append("–").append(max)
                .append(" 字之间（含标点）。\n")
        }
        append("要求：\n")
        if (styleSamplesBlock.isNotBlank()) {
            append("- 【文风优先】本轮已提供「临时文风样本」：动笔前先对照样本归纳本章要沿用的句式、节奏、描写、用词、对话与叙述视角（不要把这部分写进正文），再让整篇正文保持这些特征；样本只提供写法，不得复制其中的人物、剧情、设定、地点或台词，与梗概、大纲的事实冲突时以后者为准；\n")
        }
        append("- 严格依据「本章梗概」展开，覆盖其中所有核心事件；\n")
        append("- 参考「大纲 / 人物设定」，保持人物、世界观与情节走向一致；\n")
        append("- 「记忆检索参考资料」来自本书已写正文，仅供写作参考，请勿照抄或复述；\n")
        if (summarySentenceBlock.isNotBlank()) {
            append("- 「本章梗概·文风标签句例」是本章梗概中与指定文风标签对应的表达示例，仅参考措辞、节奏和表达方式；不得当作新增剧情设定，不要求照抄；不要在回答中解释标签或输出抽样过程；\n")
        }
        append("- 只输出正文内容，不要输出标题、章节名、说明、字数统计或任何解释。")
    }
    val reference = buildString {
        if (summary.isNotBlank()) {
            append(HEAD_SUMMARY).append('\n').append(summary.trim()).append('\n')
        }
        if (summarySentenceBlock.isNotBlank()) {
            append(HEAD_SENTENCE_TAGS).append('\n').append(summarySentenceBlock.trim()).append('\n')
        }
        if (outlineSlot.isNotBlank()) {
            append(HEAD_OUTLINE).append('\n').append(outlineSlot.trim()).append('\n')
        }
        if (styleSamplesBlock.isNotBlank()) {
            append(HEAD_STYLE_SAMPLES).append('\n').append(styleSamplesBlock.trim()).append('\n')
            append(STYLE_SAMPLES_RULES).append('\n')
        }
        if (memoryBlock.isNotBlank()) {
            append(HEAD_MEMORY).append('\n').append(memoryBlock.trim()).append('\n')
        }
        // 收尾强调必须放在所有参考资料之后、紧贴用户输入，保证「文风样本」是模型最后看到的写作要求
        if (styleSamplesBlock.isNotBlank()) {
            append(STYLE_SAMPLES_REMINDER).append('\n')
        }
    }.trimEnd('\n')
    return buildList {
        add(ChatTurn(ChatRole.SYSTEM, instruction))
        if (reference.isNotBlank()) add(ChatTurn(ChatRole.SYSTEM, reference))
        add(ChatTurn(ChatRole.USER, userInput.trim().ifBlank { CONTENT_FALLBACK_INPUT }))
    }
}

/**
 * 限定梗概范围时的参考资料块：
 * 已在本会话上下文中可用的标签只写「参见大纲『标签名』」，未提交过的追加其完整内容。
 * 由调用方通过 [injected] 判定是否已注入（复用现有大纲条目去重集合），并负责把新注入的 id 记回集合。
 */
fun buildSummaryReferenceBlock(
    outlineIds: List<Long>,
    injected: (Long) -> Boolean,
    label: (Long) -> String,
    renderFull: (Long) -> String?
): String {
    val ids = outlineIds.distinct()
    if (ids.isEmpty()) return ""
    val sb = StringBuilder("【指定大纲参考资料】\n以下大纲设定必须在梗概中体现：\n")
    ids.forEach { id ->
        if (injected(id)) {
            sb.append("参见大纲『").append(label(id)).append("』\n")
        } else {
            renderFull(id)?.let { sb.append(it.trimEnd()).append('\n') }
        }
    }
    return sb.toString().trimEnd('\n')
}

/**
 * 【本章梗概·文风标签句例】块：只放本次随机抽中的梗概句段（每段 1～3 句）及其标签。
 * 该文本只进入本次请求，不写入正文、梗概、样本库或任何长期数据。
 */
fun buildSummarySentenceTagBlock(samples: List<SummarySentenceSample>): String {
    if (samples.isEmpty()) return ""
    val rules = "以下句段摘自本章梗概，是与指定文风标签对应的表达示例；" +
        "仅用于参考措辞、节奏和表达方式，不是新增剧情设定，不要求照抄；" +
        "不要在回答中解释标签或输出抽样过程。"
    return buildString {
        append(rules).append('\n')
        samples.forEachIndexed { index, sample ->
            append(index + 1).append(". 标签：").append(sample.tagNames.joinToString("、"))
                .append("｜原文：").append(sample.text.trim()).append('\n')
        }
    }.trimEnd('\n')
}

/**
 * 【临时文风样本】块：只放本次检索到的 1～2 个片段与来源 / 标签说明。
 * 该文本只进入本次请求，不写入任何长期数据；使用规则由 [buildContentTurns] 统一追加。
 */
fun buildStyleSampleBlock(hits: List<StyleSampleHit>): String {
    if (hits.isEmpty()) return ""
    return hits.mapIndexed { index, hit ->
        val label = if (hit.tagLabels.isEmpty()) "" else "（标签：${hit.tagLabels.joinToString("、")}）"
        buildString {
            append("样本").append(index + 1).append(label).append('\n')
            append(hit.text.trim())
        }
    }.joinToString("\n\n")
}

/**
 * 记忆检索的 query：本章梗概为主，选中标签的「显示标签」为辅（辅助词更贴近本书设定）。
 */
fun buildRetrievalQuery(summary: String, tagHeads: List<String> = emptyList()): String =
    (listOf(summary.trim()) + tagHeads.map { it.trim() }.filter { it.isNotEmpty() })
        .filter { it.isNotEmpty() }
        .joinToString("\n")

/**
 * 字数兜底：超上限时截到不超过上限的最后一个完整句末标点 / 换行；
 * 低于下限时只标记 [WordRangeResult.under]，不自动重试（避免额外 token）。
 */
fun enforceWordRange(text: String, range: IntRange?): WordRangeResult {
    val t = text.trim()
    val max = upperBound(range)
    if (max == null) return WordRangeResult(t)
    val count = TextTool.countWords(t)
    if (count > max) {
        val trimmed = trimToLastSentenceWithin(t, max)
        return WordRangeResult(trimmed ?: t, over = true)
    }
    if (hasLowerBound(range) && count < range!!.first) return WordRangeResult(t, under = true)
    return WordRangeResult(t)
}

/** 从后往前找「以句末标点 / 换行结尾且字数不超过 [max]」的最大前缀；找不到返回 null */
private fun trimToLastSentenceWithin(text: String, max: Int): String? {
    for (i in text.indices.reversed()) {
        if (text[i] in SENTENCE_ENDINGS) {
            val prefix = text.substring(0, i + 1).trimEnd()
            if (TextTool.countWords(prefix) <= max) return prefix
        }
    }
    return null
}
