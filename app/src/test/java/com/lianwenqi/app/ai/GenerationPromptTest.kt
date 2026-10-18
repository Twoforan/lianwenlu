package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPromptTest {

    @Test
    fun buildSummaryTurns_systemInstructionCarriesWordRange() {
        val turns = buildSummaryTurns("写一段开场", 100..150)
        assertEquals(ChatRole.SYSTEM, turns[0].role)
        assertTrue(turns[0].content.contains("100–150"))
        assertTrue(turns[0].content.contains("执行步骤"))
        assertEquals(ChatRole.USER, turns.last().role)
        assertEquals("写一段开场", turns.last().content)
    }

    @Test
    fun buildSummaryTurns_noLowerBoundOnlyLimitsUpper() {
        val turns = buildSummaryTurns("", 0..500)
        val sys = turns[0].content
        assertTrue(sys.contains("不要超过 500 字"))
        // 下限为 0 时不再给「必须在 X–Y 字之间」的区间约束
        assertFalse(sys.contains("字之间"))
        // 空输入时给一句兜底，避免空 user 轮次
        assertTrue(turns.last().content.isNotBlank())
    }

    @Test
    fun buildSummaryTurns_referenceBlockBecomesSeparateSystemTurn() {
        val turns = buildSummaryTurns("梗概", null, "【本会话参考资料】\n书A")
        assertEquals(3, turns.size)
        assertEquals(ChatRole.SYSTEM, turns[1].role)
        assertTrue(turns[1].content.contains("书A"))
    }

    @Test
    fun buildContentTurns_blocksAreOrderedAndNoAssistantTurn() {
        val turns = buildContentTurns(
            summary = "本章梗概",
            outlineSlot = "人物设定",
            memoryBlock = "【参考资料·本书相关正文片段】\n片段1：旧正文",
            userInput = "开写",
            range = 1000..6000
        )
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.SYSTEM, ChatRole.USER), turns.map { it.role })
        // 验收 #3：正文请求里不能出现梗概生成那一轮的 assistant 消息，梗概只以文本变量注入
        assertTrue(turns.none { it.role == ChatRole.ASSISTANT })
        val ref = turns[1].content
        assertTrue(ref.indexOf("【本章梗概】") < ref.indexOf("【大纲 / 人物设定】"))
        assertTrue(ref.indexOf("【大纲 / 人物设定】") < ref.indexOf("【记忆检索参考资料】"))
        assertTrue(ref.contains("本章梗概"))
        assertTrue(turns[0].content.contains("1000–6000"))
        assertEquals("开写", turns.last().content)
    }

    @Test
    fun buildContentTurns_omitsMemoryBlockWhenRetrievalOff() {
        val turns = buildContentTurns("梗概", "大纲", "", "写", null)
        assertEquals(3, turns.size)
        assertFalse(turns[1].content.contains("【记忆检索参考资料】"))
    }

    @Test
    fun buildSummaryReferenceBlock_injectedTagsOnlyShowCrossReference() {
        val block = buildSummaryReferenceBlock(
            outlineIds = listOf(1L, 2L),
            injected = { it == 1L },
            label = { "标题$it" },
            renderFull = { "｛标题$it｝\n内容$it" }
        )
        assertTrue(block.contains("参见大纲『标题1』"))
        assertFalse(block.contains("内容1"))
        assertTrue(block.contains("内容2"))
    }

    @Test
    fun buildSummaryReferenceBlock_dedupesIdsAndHandlesEmpty() {
        assertEquals("", buildSummaryReferenceBlock(emptyList(), { false }, { "$it" }, { "$it" }))
        val block = buildSummaryReferenceBlock(listOf(7L, 7L), { false }, { "七" }, { "｛七｝\n正文" })
        assertEquals(1, Regex("正文").findAll(block).count())
    }

    @Test
    fun buildRetrievalQuery_summaryThenTagHeads() {
        assertEquals("本章梗概\n主角\n反派", buildRetrievalQuery("本章梗概", listOf("主角", "反派")))
        assertEquals("本章梗概", buildRetrievalQuery("本章梗概"))
    }

    @Test
    fun enforceWordRange_withinRangeKeepsText() {
        val r = enforceWordRange("一二三。四五六。", 0..10)
        assertEquals("一二三。四五六。", r.text)
        assertFalse(r.over)
        assertFalse(r.under)
    }

    @Test
    fun enforceWordRange_overUpperTrimsToLastCompleteSentence() {
        // 6 个字（标点不计）超出上限 4 → 截到不超过 4 字的最后一个句末标点
        val r = enforceWordRange("一二三。四五六。", 0..4)
        assertEquals("一二三。", r.text)
        assertTrue(r.over)
    }

    @Test
    fun enforceWordRange_underLowerOnlyFlagsWithoutRetry() {
        val r = enforceWordRange("一二三。", 100..150)
        assertEquals("一二三。", r.text)
        assertTrue(r.under)
        assertFalse(r.over)
    }

    @Test
    fun enforceWordRange_noConstraintKeepsText() {
        val r = enforceWordRange("一二三。", 0..0)
        assertEquals("一二三。", r.text)
        assertFalse(r.over)
        assertFalse(r.under)
    }
}
