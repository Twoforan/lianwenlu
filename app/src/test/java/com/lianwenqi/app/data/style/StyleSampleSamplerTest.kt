package com.lianwenqi.app.data.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长文本抽样策略（纯 JVM） */
class StyleSampleSamplerTest {

    private fun sample(
        order: Int,
        text: String,
        name: String = "样本$order.txt",
        format: StyleSampleFormat = StyleSampleFormat.TXT
    ) = StyleSampleInput(
        fileName = name,
        format = format,
        order = order,
        charCount = text.length,
        text = text
    )

    @Test
    fun `总量在预算内时全部按全文提交`() {
        val plan = StyleSampleSampler.plan(
            listOf(sample(1, "甲".repeat(800)), sample(2, "乙".repeat(1200))),
            budgetChars = 5_000
        )
        assertEquals(StyleSamplingStrategy.FULL, plan.strategy)
        assertEquals(2, plan.analyzedSampleCount)
        assertEquals(2_000, plan.includedChars)
        assertEquals(800, plan.blocks[0].text.length)
        assertTrue(plan.blocks.none { it.text.contains("……") })
    }

    @Test
    fun `超预算时每个样本取开头中段结尾且不超预算`() {
        val text = "开头标记" + "甲".repeat(50_000) + "乙".repeat(50_000) + "结尾标记"
        val plan = StyleSampleSampler.plan(
            listOf(sample(1, text), sample(2, text)),
            budgetChars = 6_000
        )
        assertEquals(StyleSamplingStrategy.EVEN_SEGMENTS, plan.strategy)
        assertEquals(2, plan.analyzedSampleCount)
        assertEquals(0, plan.skippedSampleCount)
        assertTrue(plan.includedChars <= 6_000)
        plan.blocks.forEach { block ->
            assertTrue(block.text.startsWith("开头标记"))
            assertTrue(block.text.endsWith("结尾标记"))
            assertTrue(block.text.contains("……"))
            assertEquals("开头、中段、结尾节选", block.mode)
        }
    }

    @Test
    fun `样本过多时跨顺序抽选并保留首尾`() {
        val samples = (1..12).map { order -> sample(order, "第${order}个样本开头" + "文".repeat(5_000)) }
        val plan = StyleSampleSampler.plan(samples, budgetChars = 12_000)
        assertEquals(StyleSamplingStrategy.EVEN_SELECTION, plan.strategy)
        assertEquals(StyleSampleSampler.MAX_SELECTED_SAMPLES, plan.analyzedSampleCount)
        assertEquals(12, plan.totalSampleCount)
        assertEquals(4, plan.skippedSampleCount)
        assertEquals(1, plan.blocks.first().order)
        assertEquals(12, plan.blocks.last().order)
        assertTrue(plan.includedChars <= 12_000)
        assertTrue(plan.blocks.all { it.text.contains("……") })
    }

    @Test
    fun `没有可用样本时返回空计划`() {
        val plan = StyleSampleSampler.plan(emptyList())
        assertTrue(plan.blocks.isEmpty())
        assertEquals(0, plan.includedChars)
        assertEquals(0, plan.totalChars)
        assertTrue(!plan.isSampled)
    }

    @Test
    fun `空白文本不参与抽样`() {
        val plan = StyleSampleSampler.plan(
            listOf(sample(1, "   "), sample(2, "有效文本".repeat(50))),
            budgetChars = 5_000
        )
        assertEquals(1, plan.analyzedSampleCount)
        assertEquals(2, plan.totalSampleCount)
    }

    @Test
    fun `抽样时会记录原文与提交字符数`() {
        val text = "甲".repeat(30_000)
        val plan = StyleSampleSampler.plan(listOf(sample(3, text)), budgetChars = 3_000)
        assertEquals(30_000, plan.totalChars)
        assertTrue(plan.includedChars in 2_900..3_000)
        assertEquals(30_000, plan.blocks.single().totalChars)
    }
}
