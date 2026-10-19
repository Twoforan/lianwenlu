package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.util.AI_CHAPTER_TOOL_PROMPT
import com.lianwenqi.app.util.AI_OUTLINE_TOOL_PROMPT
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
 * 「前情提要」块抬头：生成请求携带的本会话历史不再作为「对话轮次」发出，
 * 而是渲染成这一段参考资料，避免模型把它当成自己「正在续写的回答」而反复复用上一轮成品。
 */
const val PRIOR_CONTEXT_HEADER = "【前情提要】\n以下为历史对话，请作为本章的前情提要参考：\n"

/**
 * 生成任务的开头：有章名时点明「现在撰写的是『第2章』」，无章名时退回「为当前章节」。
 */
private fun taskHead(chapterTitle: String, action: String): String =
    if (chapterTitle.isBlank()) "你的任务：$action。\n"
    else "你的任务：现在撰写的是「${chapterTitle.trim()}」，请$action。\n"

/**
 * 生成请求与聊天请求共用的前段块：自定义前缀 → 工具块（勾选时）→ 「以下是参考资料」。
 * 顺序与 [com.lianwenqi.app.data.SendChatMessageUseCase] 组装聊天请求时完全一致，
 * 用来让两边命中同一段供应商侧前缀缓存；生成专用块（[buildSummaryTurns] / [buildContentTurns]）
 * 在本函数结果之后追加（需要携带历史对话时，历史插在两者中间）。
 */
fun buildGenerationPrefixTurns(
    customPrefix: String,
    writeOutlineTool: Boolean,
    chapterImportTool: Boolean,
    reference: String
): List<ChatTurn> = buildList {
    if (customPrefix.isNotBlank()) add(ChatTurn(ChatRole.SYSTEM, customPrefix))
    if (writeOutlineTool) add(ChatTurn(ChatRole.SYSTEM, AI_OUTLINE_TOOL_PROMPT))
    if (chapterImportTool) add(ChatTurn(ChatRole.SYSTEM, AI_CHAPTER_TOOL_PROMPT))
    if (reference.isNotBlank()) add(ChatTurn(ChatRole.SYSTEM, REFERENCE_BLOCK_HEADER + reference))
}

/** 正文段落分隔的块标题（与 [buildContentTurns] 一一对应） */
private const val HEAD_SUMMARY = "【本章梗概】"
private const val HEAD_OUTLINE = "【大纲 / 人物设定】"
private const val HEAD_MEMORY = "【记忆检索参考资料】"
private const val HEAD_USER = "【用户要求】"

/** 字数上限是否有效：区间为空或上限 <= 0 都视作「不约束」 */
private fun upperBound(range: IntRange?): Int? =
    range?.last?.takeIf { it > 0 }

/** 是否校验下限：下限 <= 0 表示只约束上限（用户把滑条拉到 0 即不设下限） */
private fun hasLowerBound(range: IntRange?): Boolean =
    range != null && range.first > 0 && range.first <= (upperBound(range) ?: Int.MAX_VALUE)

/**
 * 生成梗概的 messages：System 指令（含字数硬约束与 4 步自检流程）+ 参考资料块 + 用户即时输入。
 * [input] 为空时给一句兜底指令，避免空 user 轮次。
 */
fun buildSummaryTurns(
    input: String,
    range: IntRange?,
    referenceBlock: String = "",
    chapterTitle: String = ""
): List<ChatTurn> {
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
        append("\n执行步骤：\n")
        append("1. 先用一句话列出本章必须覆盖的 3–5 个核心事件。\n")
        append("2. 按事件顺序写梗概草稿。\n")
        append("3. 数一下草稿字数：\n")
        if (lower != null) {
            append("   - 低于 ").append(lower).append("：补充一个次要情节或人物反应的细节；\n")
        }
        if (max != null) {
            append("   - 高于 ").append(max).append("：删除修饰性描写，合并相邻事件；\n")
        }
        append("   - 在范围内：输出。\n")
        append("4. 只输出最终梗概，不输出计数过程。")
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
 * （调用方可在本块之前插入「前情提要」参考资料块，见 [PRIOR_CONTEXT_HEADER]。）
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
    chapterTitle: String = ""
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
        append("- 严格依据「本章梗概」展开，覆盖其中所有核心事件；\n")
        append("- 参考「大纲 / 人物设定」，保持人物、世界观与情节走向一致；\n")
        append("- 「记忆检索参考资料」来自本书已写正文，仅供写作参考，请勿照抄或复述；\n")
        append("- 只输出正文内容，不要输出标题、章节名、说明、字数统计或任何解释。")
    }
    val reference = buildString {
        if (summary.isNotBlank()) {
            append(HEAD_SUMMARY).append('\n').append(summary.trim()).append('\n')
        }
        if (outlineSlot.isNotBlank()) {
            append(HEAD_OUTLINE).append('\n').append(outlineSlot.trim()).append('\n')
        }
        if (memoryBlock.isNotBlank()) {
            append(HEAD_MEMORY).append('\n').append(memoryBlock.trim()).append('\n')
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
