package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.GLOBAL_STYLE_TAG_SCOPE
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagSource
import com.lianwenqi.app.data.entity.SummaryTagSource
import com.lianwenqi.app.util.shortHash
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 中文句子切分：覆盖常见句末标点、换行与空白句 */
class SummarySentenceSplitterTest {
    @Test
    fun `中文句末标点与换行都会切分且位置可还原`() {
        val text = "他停在门口。她问：真的要走？他没有回答！风从窗口灌进来；灯灭了。\n最后一句话"
        val sentences = SummarySentenceSplitter.split(text)
        assertEquals(
            listOf("他停在门口。", "她问：真的要走？", "他没有回答！", "风从窗口灌进来；", "灯灭了。", "最后一句话"),
            sentences.map { it.text }
        )
        sentences.forEach {
            assertEquals(it.text, text.substring(it.start, it.end))
        }
    }

    @Test
    fun `空白句被忽略且序号连续`() {
        val sentences = SummarySentenceSplitter.split("  \n\n第一句。\n\n  \n第二句！\n")
        assertEquals(2, sentences.size)
        assertEquals(listOf(0, 1), sentences.map { it.index })
        assertEquals(listOf("第一句。", "第二句！"), sentences.map { it.text })
    }

    @Test
    fun `连续标点合并为同一个句末`() {
        val sentences = SummarySentenceSplitter.split("真的吗？！走吧……")
        assertEquals(2, sentences.size)
        assertEquals("真的吗？！", sentences[0].text)
        assertEquals("走吧……", sentences[1].text)
    }

    @Test
    fun `空文本返回空列表`() {
        assertTrue(SummarySentenceSplitter.split("   \n ").isEmpty())
    }
}

/** 句段切分：每 3 句一段，位置可还原 */
class SummarySentenceSegmenterTest {
    private val summary = "一。二。三。四。五。"

    @Test
    fun `每三句合并为一段且末段不足三句`() {
        val sentences = SummarySentenceSplitter.split(summary)
        val segments = SummarySentenceSegmenter.segment(summary, sentences)
        assertEquals(2, segments.size)
        assertEquals(listOf(0, 1), segments.map { it.index })
        assertEquals(listOf("一。二。三。", "四。五。"), segments.map { it.text })
        segments.forEach { assertEquals(it.text, summary.substring(it.start, it.end)) }
    }

    @Test
    fun `空梗概返回空句段`() {
        assertTrue(SummarySentenceSegmenter.segment("  ", emptyList()).isEmpty())
    }
}

/** 句段标签解析与档案隔离：只接受当前档案标签，文本必须精确映射到句段 */
class SummarySentenceTagResolverTest {
    private val profileTags = listOf(
        StyleTag(id = 11, styleProfileId = 7L, category = "表达方式", name = "潜台词", source = StyleTagSource.AI),
        StyleTag(id = 12, styleProfileId = 7L, category = "情绪氛围", name = "压抑", source = StyleTagSource.AI)
    )

    private val globalTags = listOf(
        StyleTag(id = 1, styleProfileId = GLOBAL_STYLE_TAG_SCOPE, category = "全局分类", name = "全局标签", source = StyleTagSource.USER)
    )

    private val summary = "他把杯子放下，没有抬头。她终于开口问了一句。风从窗口灌进来，灯灭了。"
    private val sentences = SummarySentenceSplitter.split(summary)
    private val segments = SummarySentenceSegmenter.segment(summary, sentences)

    private fun resolve(
        items: List<SummarySentenceTagItem>,
        tags: List<StyleTag> = profileTags
    ) = SummarySentenceTagResolver.resolve(
        items, summary, sentences, segments, tags, shortHash(summary)
    )

    @Test
    fun `全局标签与当前档案标签都接受且其他档案标签被丢弃`() {
        val items = listOf(
            SummarySentenceTagItem(
                index = 0,
                text = segments[0].text,
                tags = listOf(
                    SummaryTagItem(11, "", ""),
                    SummaryTagItem(1, "", ""),   // 全局用户标签 id
                    SummaryTagItem(21, "", ""),  // 其他档案标签 id
                    SummaryTagItem(null, "", "不存在的标签")
                )
            )
        )
        val result = resolve(items, profileTags + globalTags)
        assertEquals(listOf(11L, 1L), result.map { it.tagId })
        assertEquals(
            listOf(SummaryTagSource.PROFILE, SummaryTagSource.USER_GLOBAL),
            result.map { it.source }
        )
        assertEquals(listOf(0, 0), result.map { it.sentenceIndex })
        assertEquals(segments[0].text, result[0].sentenceText)
        assertEquals(segments[0].start, result[0].sentenceStart)
        assertEquals(segments[0].end, result[0].sentenceEnd)
    }

    @Test
    fun `无法精确映射的文本整条丢弃`() {
        val items = listOf(
            // 半句：没有对齐到句首 / 句末，整条丢弃
            SummarySentenceTagItem(index = 0, text = "他把杯子放下", tags = listOf(SummaryTagItem(11, "", ""))),
            SummarySentenceTagItem(index = 0, text = "不存在的句子。", tags = listOf(SummaryTagItem(12, "", ""))),
            // 段内完整第二句：吸附到它所属的句段
            SummarySentenceTagItem(index = 1, text = sentences[1].text, tags = listOf(SummaryTagItem(12, "", "")))
        )
        val result = resolve(items)
        assertEquals(listOf(12L), result.map { it.tagId })
        assertEquals(0, result.single().sentenceIndex)
        assertEquals(segments[0].text, result.single().sentenceText)
    }

    @Test
    fun `一句段多标签与一个标签多句段都保留并去重`() {
        val items = listOf(
            SummarySentenceTagItem(0, segments[0].text, listOf(SummaryTagItem(11, "", ""), SummaryTagItem(12, "", ""))),
            SummarySentenceTagItem(0, segments[0].text, listOf(SummaryTagItem(11, "", ""))),
            SummarySentenceTagItem(0, sentences[2].text, listOf(SummaryTagItem(11, "", "")))
        )
        val result = resolve(items)
        // 三句同属一个句段：同一段同一标签只保留一次
        assertEquals(2, result.size)
        assertEquals(setOf(0), result.map { it.sentenceIndex }.toSet())
        assertEquals(listOf(11L, 12L), result.map { it.tagId })
    }

    @Test
    fun `没有任何可用标签时不产生任何关联`() {
        val items = listOf(
            SummarySentenceTagItem(0, segments[0].text, listOf(SummaryTagItem(1, "", ""), SummaryTagItem(11, "", "")))
        )
        assertTrue(resolve(items, emptyList()).isEmpty())
    }

    @Test
    fun `句子标签 JSON 解析`() {
        val raw = """{"sentences":[{"index":0,"text":"他把杯子放下，没有抬头。","tags":[{"id":11}]},{"text":"她终于开口问了一句。","tags":[{"name":"压抑"}]}]}"""
        val parsed = SummarySentenceTagCodec.parse(raw)
        assertEquals(2, parsed!!.size)
        assertEquals(0, parsed[0].index)
        assertEquals("他把杯子放下，没有抬头。", parsed[0].text)
        assertEquals(11L, parsed[0].tags.single().id)
        assertEquals("压抑", parsed[1].tags.single().name)
    }
}

/** 正文抽样：过期 / 跨档案 / 手动标签过滤与数量、字数上限 */
class SummarySentenceSamplerTest {
    private val summary = "他把杯子放下，没有抬头。她终于开口问了一句。风从窗口灌进来，灯灭了。"
    private val sentences = SummarySentenceSplitter.split(summary)
    private val hash = shortHash(summary.trim())

    private fun tag(
        sentence: SummarySentence,
        tagId: Long,
        name: String,
        hashValue: String = hash,
        manual: Boolean = false,
        source: String = SummaryTagSource.PROFILE
    ) = ChapterSummaryTag(
        chapterId = 1,
        category = "表达方式",
        name = name,
        tagId = tagId,
        source = source,
        summaryHash = hashValue,
        manual = manual,
        sentenceIndex = sentence.index,
        sentenceStart = sentence.start,
        sentenceEnd = sentence.end,
        sentenceText = sentence.text
    )

    @Test
    fun `只抽样当前哈希且属于当前档案的句子`() {
        val tags = listOf(
            tag(sentences[0], 11, "潜台词"),
            tag(sentences[1], 99, "其他档案或已删除标签"),
            tag(sentences[2], 11, "潜台词", hashValue = "stale")
        )
        val samples = SummarySentenceSampler.sample(tags, summary, setOf(11L), Random(0))
        assertEquals(listOf(sentences[0].text), samples.map { it.text })
        assertEquals(listOf(listOf("潜台词")), samples.map { it.tagNames })
    }

    @Test
    fun `手动标签与无句子定位的旧标签不参与抽样`() {
        val manual = ChapterSummaryTag(
            chapterId = 1, category = "未分类", name = "手动标签", tagId = 11,
            source = SummaryTagSource.MANUAL, summaryHash = hash, manual = true
        )
        val legacy = ChapterSummaryTag(
            chapterId = 1, category = "表达方式", name = "潜台词", tagId = 11,
            source = SummaryTagSource.PROFILE, summaryHash = hash
        )
        val samples = SummarySentenceSampler.sample(listOf(manual, legacy), summary, setOf(11L), Random(0))
        assertTrue(samples.isEmpty())
    }

    @Test
    fun `同一句多标签合并为一条样本`() {
        val tags = listOf(
            tag(sentences[0], 11, "潜台词"),
            tag(sentences[0], 12, "压抑")
        )
        val samples = SummarySentenceSampler.sample(tags, summary, setOf(11L, 12L), Random(0))
        assertEquals(1, samples.size)
        assertEquals(listOf("潜台词", "压抑"), samples.single().tagNames)
    }

    @Test
    fun `句段关联按整段抽样且更换后的档案标签同样参与`() {
        val segments = SummarySentenceSegmenter.segment(summary, sentences)
        val segment = segments.single()
        val rows = listOf(
            tag(segment, 11, "潜台词"),
            tag(segment, 12, "压抑")
        )
        val samples = SummarySentenceSampler.sample(rows, summary, setOf(11L, 12L), Random(0))
        assertEquals(listOf(segment.text), samples.map { it.text })
        assertEquals(listOf(listOf("潜台词", "压抑")), samples.map { it.tagNames })
    }

    @Test
    fun `数量与总字数都有上限`() {
        val longSummary = "第一句很长很长很长很长很长很长很长很长很长很长。第二句。第三句。第四句。"
        val longSentences = SummarySentenceSplitter.split(longSummary)
        val longHash = shortHash(longSummary.trim())
        val rows = longSentences.map { sentence ->
            ChapterSummaryTag(
                chapterId = 1, category = "表达方式", name = "标签${sentence.index}", tagId = 11,
                source = SummaryTagSource.PROFILE, summaryHash = longHash,
                sentenceIndex = sentence.index, sentenceStart = sentence.start,
                sentenceEnd = sentence.end, sentenceText = sentence.text
            )
        }
        val samples = SummarySentenceSampler.sample(
            rows, longSummary, setOf(11L), Random(0), maxSentences = 3, maxChars = 8
        )
        assertEquals(2, samples.size)
        assertTrue(samples.sumOf { it.text.length } <= 8)
    }

    @Test
    fun `原句定位与梗概不一致时丢弃`() {
        val broken = tag(sentences[0], 11, "潜台词").copy(sentenceText = "被篡改的句子。")
        assertTrue(SummarySentenceSampler.sample(listOf(broken), summary, setOf(11L), Random(0)).isEmpty())
    }

    @Test
    fun `没有候选时返回空`() {
        assertTrue(SummarySentenceSampler.sample(emptyList(), summary, setOf(11L), Random(0)).isEmpty())
        assertTrue(SummarySentenceSampler.sample(emptyList(), summary, emptySet(), Random(0)).isEmpty())
    }

    @Test
    fun `默认数量上限为 3 句`() {
        val longSummary = "第一句。第二句。第三句。第四句。第五句。第六句。"
        val longSentences = SummarySentenceSplitter.split(longSummary)
        val longHash = shortHash(longSummary.trim())
        val rows = longSentences.map { sentence ->
            ChapterSummaryTag(
                chapterId = 1, category = "表达方式", name = "标签${sentence.index}", tagId = 11,
                source = SummaryTagSource.PROFILE, summaryHash = longHash,
                sentenceIndex = sentence.index, sentenceStart = sentence.start,
                sentenceEnd = sentence.end, sentenceText = sentence.text
            )
        }
        val samples = SummarySentenceSampler.sample(rows, longSummary, setOf(11L), Random(0))
        assertEquals(3, samples.size)
        assertTrue(samples.map { it.text }.distinct().size == samples.size)
    }

    @Test
    fun `默认总字数上限为 150 字`() {
        val longText = "甲".repeat(100) + "。"
        val longSummary = longText + "乙".repeat(100) + "。"
        val longSentences = SummarySentenceSplitter.split(longSummary)
        val longHash = shortHash(longSummary.trim())
        val rows = longSentences.map { sentence ->
            ChapterSummaryTag(
                chapterId = 1, category = "表达方式", name = "标签${sentence.index}", tagId = 11,
                source = SummaryTagSource.PROFILE, summaryHash = longHash,
                sentenceIndex = sentence.index, sentenceStart = sentence.start,
                sentenceEnd = sentence.end, sentenceText = sentence.text
            )
        }
        val samples = SummarySentenceSampler.sample(rows, longSummary, setOf(11L), Random(0))
        assertEquals(1, samples.size)
        assertTrue(samples.sumOf { it.text.length } <= SummarySentenceSampler.MAX_CHARS)
    }
}

/** 有效关联复用规则：过期 / 换档案 / 无句子定位时重新分析，手动标签不参与判断 */
class SummaryTagValidityTest {
    private val summary = "他把杯子放下，没有抬头。她终于开口问了一句。"
    private val sentences = SummarySentenceSplitter.split(summary)
    private val hash = shortHash(summary.trim())
    private val profileTagIds = setOf(11L, 12L)

    private fun auto(
        sentence: SummarySentence? = sentences[0],
        tagId: Long = 11L,
        hashValue: String = hash
    ) = ChapterSummaryTag(
        chapterId = 1,
        category = "表达方式",
        name = "潜台词",
        tagId = tagId,
        source = SummaryTagSource.PROFILE,
        summaryHash = hashValue,
        sentenceIndex = sentence?.index ?: -1,
        sentenceStart = sentence?.start ?: -1,
        sentenceEnd = sentence?.end ?: -1,
        sentenceText = sentence?.text.orEmpty()
    )

    private fun manual(hashValue: String = hash) = ChapterSummaryTag(
        chapterId = 1, category = "未分类", name = "手动标签", tagId = 0,
        source = SummaryTagSource.MANUAL, summaryHash = hashValue, manual = true
    )

    @Test
    fun `未过期且属于当前档案的自动关联可直接复用`() {
        val tags = listOf(auto(), auto(sentences[1], 12L))
        assertTrue(SummaryTagValidity.isReusable(tags, hash, profileTagIds))
        assertTrue(tags.all { SummaryTagValidity.isValidAuto(it, hash, profileTagIds) })
    }

    @Test
    fun `梗概已修改的旧关联不可复用也不展示`() {
        val stale = auto(hashValue = "old")
        assertTrue(SummaryTagValidity.isReusable(listOf(stale), "old", profileTagIds))
        assertFalse(SummaryTagValidity.isReusable(listOf(stale), hash, profileTagIds))
        assertFalse(SummaryTagValidity.isValidAuto(stale, hash, profileTagIds))
    }

    @Test
    fun `更换档案后旧档案标签不可复用`() {
        val otherProfile = auto(tagId = 99L)
        assertFalse(SummaryTagValidity.isReusable(listOf(otherProfile), hash, profileTagIds))
        assertFalse(SummaryTagValidity.isValidAuto(otherProfile, hash, setOf(98L)))
    }

    @Test
    fun `手动标签不阻塞复用且不被视为有效自动关联`() {
        val tags = listOf(auto(), manual(hashValue = "old"))
        assertTrue(SummaryTagValidity.isReusable(tags, hash, profileTagIds))
        val manualTag = tags.last()
        assertFalse(SummaryTagValidity.isValidAuto(manualTag, hash, profileTagIds))
    }

    @Test
    fun `没有句子定位或没有自动标签时不可复用`() {
        assertFalse(SummaryTagValidity.isReusable(listOf(auto(sentence = null)), hash, profileTagIds))
        assertFalse(SummaryTagValidity.isReusable(listOf(manual()), hash, profileTagIds))
        assertFalse(SummaryTagValidity.isReusable(emptyList(), hash, profileTagIds))
    }

    @Test
    fun `旧版逐句关联不算句段对齐且会触发重新分析`() {
        val segments = SummarySentenceSegmenter.segment(summary, sentences)
        val legacy = auto(sentences[0])
        assertFalse(SummaryTagValidity.isSegmentAligned(legacy, segments))
        assertFalse(SummaryTagValidity.isValidAuto(legacy, hash, profileTagIds, segments))
        assertFalse(SummaryTagValidity.isReusable(listOf(legacy), hash, profileTagIds, segments))
    }

    @Test
    fun `句段对齐的自动关联有效且可复用`() {
        val segments = SummarySentenceSegmenter.segment(summary, sentences)
        val segment = segments.single()
        val tags = listOf(auto(segment), auto(segment, 12L))
        assertTrue(tags.all { SummaryTagValidity.isSegmentAligned(it, segments) })
        assertTrue(tags.all { SummaryTagValidity.isValidAuto(it, hash, profileTagIds, segments) })
        assertTrue(SummaryTagValidity.isReusable(tags, hash, profileTagIds, segments))
    }
}
