package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.StyleTagRules
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.GLOBAL_STYLE_TAG_SCOPE
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.SummaryTagSource
import com.lianwenqi.app.util.shortHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/** 梗概中的一句原文：[start, end) 是它在梗概文本中的精确位置 */
data class SummarySentence(
    val index: Int,
    val start: Int,
    val end: Int,
    val text: String
)

/**
 * 中文句子切分：在句号 / 问号 / 叹号 / 分号 / 省略号 / 换行处断开，
 * 连续标点合并为一个句末（如「！？」「。\n」），空白句忽略。
 * 返回的 start / end 可直接用 `summary.substring(start, end)` 还原原句。
 */
object SummarySentenceSplitter {
    private const val DELIMITERS = "。！？；…!?;\n\r"

    fun split(text: String): List<SummarySentence> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<SummarySentence>()
        var cursor = 0
        var index = 0
        while (cursor < text.length) {
            var start = cursor
            while (start < text.length && text[start].isWhitespace()) start++
            if (start >= text.length) break

            var stop = start
            while (stop < text.length && text[stop] !in DELIMITERS) stop++
            var terminal = stop
            while (terminal < text.length && text[terminal] in DELIMITERS) terminal++

            var bodyStart = start
            var bodyEnd = terminal
            while (bodyStart < bodyEnd && text[bodyStart].isWhitespace()) bodyStart++
            while (bodyEnd > bodyStart && text[bodyEnd - 1].isWhitespace()) bodyEnd--

            if (bodyStart < bodyEnd) {
                val piece = text.substring(bodyStart, bodyEnd)
                if (piece.any { !it.isWhitespace() && it !in DELIMITERS }) {
                    result += SummarySentence(index++, bodyStart, bodyEnd, piece)
                }
            }
            cursor = terminal
        }
        return result
    }
}

/**
 * 打标粒度：把梗概句子按顺序每 [SEGMENT_SIZE] 句合并为一个「句段」。
 * 标签关联到句段（[SummarySentence.index] 为段序号，起止覆盖整段），
 * 展示与正文抽样都以句段为单位，避免逐句打标过碎。
 */
object SummarySentenceSegmenter {
    const val SEGMENT_SIZE = 3

    fun segment(
        summary: String,
        sentences: List<SummarySentence>,
        size: Int = SEGMENT_SIZE
    ): List<SummarySentence> {
        if (summary.isBlank() || sentences.isEmpty() || size <= 0) return emptyList()
        return sentences.chunked(size).mapIndexed { index, chunk ->
            val start = chunk.first().start
            val end = chunk.last().end
            SummarySentence(index, start, end, summary.substring(start, end))
        }
    }
}

/** 模型给出的「句子 → 标签」条目；[text] 必须能精确映射到最终梗概中的整句 */
data class SummarySentenceTagItem(
    val index: Int?,
    val text: String,
    val tags: List<SummaryTagItem>
)

/** 解析 `{"sentences":[{"text":"原句","tags":[{"id":12}]}]}` */
object SummarySentenceTagCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): List<SummarySentenceTagItem>? {
        val candidate = StyleTagAnalysisCodec.extractJson(raw) ?: return null
        val root = runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull() ?: return null
        val array = root["sentences"] as? JsonArray ?: return null
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            val tags = (obj["tags"] as? JsonArray)?.let { SummaryTagCodec.parseItems(it) }.orEmpty()
            val index = (obj["index"] as? JsonPrimitive)?.let {
                runCatching { it.content.toInt() }.getOrNull()
            }
            SummarySentenceTagItem(index, text, tags)
        }
    }
}

/**
 * 纯逻辑：把模型返回的句子标签收敛为可落库记录。
 *
 * 权限边界：只接受「用户全局标签 + 当前绑定文风档案标签」里真实存在的标签（按 id 或名称匹配），
 * 其他档案标签与模型自造标签一律丢弃；条目文本必须能在最终梗概中
 * 精确映射到一个完整句段，否则整条丢弃。命中全局标签时来源记为 USER_GLOBAL，
 * 命中档案标签时记为 PROFILE。
 *
 * 打标粒度是句段（见 [SummarySentenceSegmenter]）：条目文本可以是整段原文，
 * 也可以是段内若干整句，最终都吸附到它所属的那 3 句段上。
 */
object SummarySentenceTagResolver {
    fun resolve(
        items: List<SummarySentenceTagItem>,
        summary: String,
        sentences: List<SummarySentence>,
        segments: List<SummarySentence>,
        tags: List<StyleTag>,
        summaryHash: String
    ): List<ChapterSummaryTag> {
        if (tags.isEmpty() || segments.isEmpty()) return emptyList()
        val result = mutableListOf<ChapterSummaryTag>()
        val seen = mutableSetOf<String>()
        items.forEach { item ->
            val segment = mapSegment(item, summary, sentences, segments) ?: return@forEach
            item.tags.forEach tagLoop@{ candidate ->
                val tag = matchTag(candidate, tags) ?: return@tagLoop
                val key = segment.index.toString() + "\u0000" + tag.id
                if (!seen.add(key)) return@tagLoop
                result += ChapterSummaryTag(
                    chapterId = 0,
                    category = tag.category,
                    name = tag.name,
                    tagId = tag.id,
                    source = if (tag.styleProfileId == GLOBAL_STYLE_TAG_SCOPE) {
                        SummaryTagSource.USER_GLOBAL
                    } else {
                        SummaryTagSource.PROFILE
                    },
                    summaryHash = summaryHash,
                    sentenceIndex = segment.index,
                    sentenceStart = segment.start,
                    sentenceEnd = segment.end,
                    sentenceText = segment.text
                )
            }
        }
        return result
    }

    /** 优先按整段原文精确匹配；否则要求文本对齐到整句边界，再吸附到所属句段 */
    private fun mapSegment(
        item: SummarySentenceTagItem,
        summary: String,
        sentences: List<SummarySentence>,
        segments: List<SummarySentence>
    ): SummarySentence? {
        val text = item.text.trim()
        if (text.isBlank()) return null
        val indexed = item.index?.let { index -> segments.firstOrNull { it.index == index && it.text == text } }
        if (indexed != null) return indexed
        segments.firstOrNull { it.text == text }?.let { return it }
        // 模型只给了段内部分句子：按整句边界定位后，吸附到该句段
        val startOffset = summary.indexOf(text)
        if (startOffset < 0) return null
        val endOffset = startOffset + text.length
        val first = sentences.firstOrNull { it.start == startOffset } ?: return null
        val last = sentences.firstOrNull { it.end == endOffset } ?: return null
        if (first.index > last.index) return null
        return segments.firstOrNull { it.start <= first.start && last.end <= it.end }
    }

    /** 只允许全局标签或档案内已有标签；id 优先，其次按名称匹配 */
    private fun matchTag(item: SummaryTagItem, tags: List<StyleTag>): StyleTag? {
        item.id?.let { id -> tags.firstOrNull { it.id == id } }?.let { return it }
        val name = item.name.trim()
        if (name.isBlank()) return null
        return tags.firstOrNull { StyleTagRules.sameName(it.name, name) }
    }
}

/**
 * 句段标签的有效性判断（展示、正文复用与「无需再分析」共用同一套规则）：
 * 必须匹配当前最终梗概、带完整句段定位、且标签 id 仍存在于「全局标签 + 当前档案标签」。
 * 自动标签（[SummaryTagSource.PROFILE] / [SummaryTagSource.USER_GLOBAL]）与
 * 用户手动选择的句段标签同样参与；旧版无句段定位的手动标签不参与。
 */
object SummaryTagValidity {
    fun isValidAuto(
        tag: ChapterSummaryTag,
        summaryHash: String,
        profileTagIds: Set<Long>
    ): Boolean = !tag.manual &&
        tag.hasSentence &&
        tag.summaryHash == summaryHash &&
        tag.tagId > 0L &&
        tag.tagId in profileTagIds

    /** 关联的起止位置必须正好覆盖一个完整句段（旧版逐句关联会被视为需重新分析） */
    fun isSegmentAligned(tag: ChapterSummaryTag, segments: List<SummarySentence>): Boolean =
        tag.hasSentence && segments.any { it.start == tag.sentenceStart && it.end == tag.sentenceEnd }

    fun isValidAuto(
        tag: ChapterSummaryTag,
        summaryHash: String,
        profileTagIds: Set<Long>,
        segments: List<SummarySentence>
    ): Boolean = isValidAuto(tag, summaryHash, profileTagIds) && isSegmentAligned(tag, segments)

    /** 已有自动关联全部有效（含句段对齐）时可复用，不必重新调用分析模型 */
    fun isReusable(
        tags: List<ChapterSummaryTag>,
        summaryHash: String,
        profileTagIds: Set<Long>
    ): Boolean {
        val auto = tags.filter { !it.manual }
        return auto.isNotEmpty() && auto.all { isValidAuto(it, summaryHash, profileTagIds) }
    }

    fun isReusable(
        tags: List<ChapterSummaryTag>,
        summaryHash: String,
        profileTagIds: Set<Long>,
        segments: List<SummarySentence>
    ): Boolean {
        val auto = tags.filter { !it.manual }
        return auto.isNotEmpty() && auto.all { isValidAuto(it, summaryHash, profileTagIds, segments) }
    }

    /** 展示与抽样用：手动选择的句段标签与自动关联同权，只要有句段定位且标签仍然存在 */
    fun isValidSegmentTag(
        tag: ChapterSummaryTag,
        summaryHash: String,
        tagIds: Set<Long>
    ): Boolean = tag.hasSentence &&
        tag.summaryHash == summaryHash &&
        tag.tagId > 0L &&
        tag.tagId in tagIds

    fun isValidSegmentTag(
        tag: ChapterSummaryTag,
        summaryHash: String,
        tagIds: Set<Long>,
        segments: List<SummarySentence>
    ): Boolean = isValidSegmentTag(tag, summaryHash, tagIds) && isSegmentAligned(tag, segments)

    /** 每个句段都至少有一个可用标签（自动或用户手动选择）时，正文生成前无需再分析 */
    fun isCovered(
        tags: List<ChapterSummaryTag>,
        summaryHash: String,
        tagIds: Set<Long>,
        segments: List<SummarySentence>
    ): Boolean {
        if (segments.isEmpty()) return false
        return segments.all { segment ->
            tags.any {
                it.summaryHash == summaryHash &&
                    it.sentenceStart == segment.start &&
                    it.sentenceEnd == segment.end &&
                    isValidSegmentTag(it, summaryHash, tagIds)
            }
        }
    }
}

/** 梗概句段打标提示词：只允许引用「用户全局标签 + 当前绑定文风档案标签」中已有的标签 */
object SummarySentenceTagPrompt {
    val SYSTEM: String = """
你是章节梗概句段标注助手。输入中的梗概已按顺序每 3 句合并为一个句段。请只使用【用户全局标签】与【当前文风档案标签】中已有的标签，判断哪些句段符合标签含义。

规则：
1. 只能引用【用户全局标签】或【当前文风档案标签】里列出的 id，不得输出新标签，也不得使用其他档案的标签。
2. 一个句段可以对应多个标签，一个标签也可以对应多个句段。
3. 只给真正符合标签含义的句段打标；没有合适的句段或标签时输出空数组，禁止强行凑标签。
4. text 必须从【本章梗概句段】中原样复制整段（含段内标点与换行，不要拆分或改写）；无法确认的句段不要输出。
5. 只输出 JSON，不要输出解释或 markdown 围栏。

输出格式：
{"sentences":[{"text":"句段原文","tags":[{"id":12}]}]}
""".trim()

    fun userContent(
        segments: List<SummarySentence>,
        userTags: List<StyleTag>,
        profileTags: List<StyleTag>
    ): String =
        buildString {
            append("【本章梗概句段】\n")
            segments.forEach { segment ->
                append(segment.index).append(". ").append(segment.text).append('\n')
            }
            append("\n【用户全局标签】\n")
            appendTagLines(userTags)
            append("\n【当前文风档案标签】\n")
            appendTagLines(profileTags)
            append("\n请输出 JSON。")
        }

    private fun StringBuilder.appendTagLines(tags: List<StyleTag>) {
        if (tags.isEmpty()) {
            append("（暂无）\n")
            return
        }
        tags.forEach { tag ->
            append("- id=").append(tag.id)
                .append(" 分类=").append(tag.category)
                .append(" 名称=").append(tag.name)
            if (tag.description.isNotBlank()) append(" 说明=").append(tag.description)
            append('\n')
        }
    }
}

/** 本次正文请求临时抽中的一句梗概原文及其命中的标签名 */
data class SummarySentenceSample(val text: String, val tagNames: List<String>)

/**
 * 正文生成前的句子抽样：
 * - 只取「当前梗概哈希一致、带句段定位、标签属于全局标签或当前绑定档案」的关联；
 * - 用户手动选择的句段标签与自动关联同权参与抽样；
 * - 每次最多 [MAX_SENTENCES] 句，总字数不超过 [MAX_CHARS]；
 * - 同一句只出现一次，无句段定位的旧标签不参与；
 * - 纯函数，随机源可注入，抽样结果不会持久化。
 */
object SummarySentenceSampler {
    const val MAX_SENTENCES = 3
    const val MAX_CHARS = 150

    fun sample(
        tags: List<ChapterSummaryTag>,
        summary: String,
        tagIds: Set<Long>,
        random: Random = Random.Default,
        maxSentences: Int = MAX_SENTENCES,
        maxChars: Int = MAX_CHARS
    ): List<SummarySentenceSample> {
        if (summary.isBlank() || tagIds.isEmpty() || maxSentences <= 0 || maxChars <= 0) {
            return emptyList()
        }
        val hash = shortHash(summary.trim())
        val candidates = tags
            .asSequence()
            .filter { it.summaryHash == hash }
            .filter { it.source != SummaryTagSource.CHAPTER_AI && it.tagId > 0L && it.tagId in tagIds }
            .filter { it.hasSentence }
            .filter {
                val start = it.sentenceStart
                val end = it.sentenceEnd
                end <= summary.length && summary.substring(start, end) == it.sentenceText
            }
            .groupBy { it.sentenceIndex }
            .mapNotNull { (_, rows) ->
                val first = rows.first()
                SummarySentenceSample(
                    text = first.sentenceText,
                    tagNames = rows.map { it.name }.distinct()
                )
            }

        val picked = mutableListOf<SummarySentenceSample>()
        var totalChars = 0
        var seenText = mutableSetOf<String>()
        candidates.shuffled(random).forEach { sample ->
            if (picked.size >= maxSentences) return@forEach
            if (!seenText.add(sample.text)) return@forEach
            if (totalChars + sample.text.length > maxChars) return@forEach
            picked += sample
            totalChars += sample.text.length
        }
        return picked
    }
}
