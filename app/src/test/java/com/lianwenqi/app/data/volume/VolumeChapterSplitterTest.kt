package com.lianwenqi.app.data.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeChapterSplitterTest {

    private fun chapter(text: String, id: Long = 1L, order: Int = 1) = CompressionChapter(
        chapterId = id,
        order = order,
        title = "第${order}章",
        text = text,
        contentHash = "hash$id"
    )

    private fun assertLossless(original: String, slices: List<CompressionChapter>) {
        assertTrue(slices.isNotEmpty())
        assertEquals(slices.size, slices.first().sliceCount)
        slices.forEachIndexed { index, slice ->
            assertEquals(index + 1, slice.sliceIndex)
            assertEquals(original.substring(slice.sourceStart, slice.sourceEnd), slice.text)
        }
        assertEquals(original, slices.joinToString("") { it.text })
        slices.zipWithNext().forEach { (left, right) ->
            assertEquals("片段必须首尾相接", left.sourceEnd, right.sourceStart)
        }
    }

    @Test
    fun `优先按自然段切分且无损覆盖原文`() {
        val paragraph = "这是一段用于测试切分的中文正文，需要保证每一段都完整进入某个片段。"
        val text = (1..6).joinToString("\n") { "第${it}段：$paragraph" }
        val slices = BoundaryChapterSplitter().split(chapter(text), sliceTokenBudget = 90)
        assertTrue(slices != null)
        assertLossless(text, slices!!)
        assertTrue(slices.all { it.text.isNotBlank() })
    }

    @Test
    fun `没有段落时按句末标点切分`() {
        val text = (1..12).joinToString("") { "这是第${it}句完整的剧情描述。" }
        val slices = BoundaryChapterSplitter().split(chapter(text), sliceTokenBudget = 50)!!
        assertLossless(text, slices)
        assertTrue(slices.dropLast(1).all { it.text.endsWith("。") })
    }

    @Test
    fun `单个自然段超限时在段内按句末标点继续切分`() {
        val text = (1..30).joinToString("") { "这是第${it}句剧情描写，人物在场景中做出选择。" }
        val slices = BoundaryChapterSplitter().split(chapter(text), sliceTokenBudget = 60)!!
        assertLossless(text, slices)
        assertTrue(slices.dropLast(1).all { it.text.endsWith("。") })
        assertTrue(slices.size > 3)
    }

    @Test
    fun `单个句子超限时在次级标点或硬切处收尾`() {
        val text = "主角沿着长街一路向前，".repeat(40)
        val slices = BoundaryChapterSplitter().split(chapter(text), sliceTokenBudget = 50)!!
        assertLossless(text, slices)
        assertTrue(slices.dropLast(1).all { it.text.endsWith("，") })
    }

    @Test
    fun `没有可用标点时硬切且不丢任何字符`() {
        val text = "甲".repeat(240)
        val slices = BoundaryChapterSplitter().split(chapter(text), sliceTokenBudget = 40)!!
        assertLossless(text, slices)
        assertTrue(slices.size >= 2)
        assertTrue(slices.all { it.text.length <= 40 })
    }

    @Test
    fun `片段自身无法容纳时返回空而不是无限细分`() {
        assertNull(BoundaryChapterSplitter().split(chapter("正文"), sliceTokenBudget = 0))
        assertNull(BoundaryChapterSplitter().split(chapter(""), sliceTokenBudget = 10))
    }

    @Test
    fun `自动分批保持顺序并只切片超预算章节`() {
        val chapters = listOf(
            chapter("甲".repeat(30), id = 1L, order = 1),
            chapter("乙".repeat(500), id = 2L, order = 2),
            chapter("丙".repeat(30), id = 3L, order = 3)
        )
        val batches = SlicingBudgetBatcher()
            .plan(chapters, inputBudgetTokens = 120, perChapterOverheadTokens = 0)

        val units = batches.flatMap { it.chapters }
        assertEquals(listOf(1L, 2L, 3L), units.map { it.chapterId }.distinct())
        assertTrue(units.map { it.order }.zipWithNext().all { (a, b) -> a <= b })

        val sliced = units.filter { it.chapterId == 2L }
        assertTrue(sliced.first().isSlice)
        assertEquals(chapters[1].text, sliced.joinToString("") { it.text })
        assertEquals(chapters[0].text, units.first().text)
        assertEquals(chapters[2].text, units.last().text)
    }

    @Test
    fun `整章放得下时不切片`() {
        val chapters = listOf(chapter("短正文".repeat(10), id = 1L, order = 1))
        val batches = SlicingBudgetBatcher().plan(chapters, inputBudgetTokens = 500, perChapterOverheadTokens = 0)
        assertEquals(1, batches.size)
        assertEquals(1, batches.single().chapters.size)
        assertEquals(false, batches.single().chapters.single().isSlice)
    }
}
