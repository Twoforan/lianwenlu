package com.lianwenqi.app.data.volume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeCompressionPlanningTest {

    private fun chapter(id: Long, order: Int, text: String) = CompressionChapter(
        chapterId = id,
        order = order,
        title = "第${order}章",
        text = text,
        contentHash = "h$id"
    )

    @Test
    fun `Token 估算明确区分中文与其它字符且标注为估算`() {
        assertEquals("chars-heuristic-v1", CompressionTokenEstimator.METHOD)
        assertEquals(10, CompressionTokenEstimator.estimate("中".repeat(10)))
        assertEquals(1, CompressionTokenEstimator.estimate("abcd"))
        assertEquals(2, CompressionTokenEstimator.estimate("abcdefgh"))
    }

    @Test
    fun `批次规划能容纳时不分批，超出时按章节边界分批且不丢章节`() {
        val chapters = listOf(
            chapter(1L, 1, "甲".repeat(100)),
            chapter(2L, 2, "乙".repeat(100)),
            chapter(3L, 3, "丙".repeat(100))
        )
        val single = TokenBudgetBatcher().plan(chapters, inputBudgetTokens = 1000, perChapterOverheadTokens = 0)
        assertEquals(1, single.size)
        assertEquals(listOf(1L, 2L, 3L), single.single().chapterIds)

        val split = TokenBudgetBatcher().plan(chapters, inputBudgetTokens = 150, perChapterOverheadTokens = 0)
        assertEquals(3, split.size)
        assertEquals(listOf(1L, 2L, 3L), split.flatMap { it.chapterIds })
        assertEquals(listOf(0, 1, 2), split.map { it.index })
    }

    @Test
    fun `输出规模随提取复杂度增长`() {
        val simple = CompressionComplexity(inputTokens = 1000, chapterCount = 3, eventCount = 2)
        val complex = simple.copy(
            eventCount = 40,
            participantCount = 12,
            changeCount = 20,
            foreshadowingCount = 8,
            unresolvedCount = 6,
            subplotCount = 4
        )
        val simpleTarget = DefaultCompressionLengthPlanner.plan(simple, 8192, 32000)
        val complexTarget = DefaultCompressionLengthPlanner.plan(complex, 8192, 32000)
        assertTrue("复杂度更高的卷应获得更大的输出预算", complexTarget > simpleTarget)
        assertTrue(complexTarget >= 512)
    }

    @Test
    fun `输出预算受模型上限与上下文窗口约束`() {
        val complexity = CompressionComplexity(inputTokens = 200000, chapterCount = 500, eventCount = 500)
        val target = DefaultCompressionLengthPlanner.plan(complexity, maxOutputTokens = 1000, contextWindow = 32000)
        assertEquals(744, target)
        assertTrue(target < 1000)
    }

    @Test
    fun `分级整合规划保留所有节点并给出多轮请求数`() {
        val nodes = listOf(
            CompressionMergeNode("壹".repeat(600), listOf(1L), 1, 1),
            CompressionMergeNode("贰".repeat(600), listOf(2L), 2, 2),
            CompressionMergeNode("叁".repeat(600), listOf(3L), 3, 3),
            CompressionMergeNode("肆".repeat(600), listOf(4L), 4, 4)
        )
        val plan = CompressionMergePlanner.plan(
            initialNodes = nodes,
            mergeInputBudgetTokens = 1000,
            intermediateTarget = 200
        )
        assertTrue(plan.rounds >= 2)
        assertTrue(plan.requestCount >= 2)
    }

    @Test
    fun `整合节点分组不改变章节顺序`() {
        val nodes = listOf(
            CompressionMergeNode("壹".repeat(400), listOf(1L), 1, 1),
            CompressionMergeNode("贰".repeat(400), listOf(2L), 2, 2),
            CompressionMergeNode("叁".repeat(400), listOf(3L), 3, 3)
        )
        val groups = CompressionMergePlanner.group(nodes, inputBudgetTokens = 900)
        assertEquals(listOf(1L, 2L, 3L), groups.flatMap { group -> group.flatMap { it.chapterIds } })
        assertEquals(2, groups.size)
    }
}
