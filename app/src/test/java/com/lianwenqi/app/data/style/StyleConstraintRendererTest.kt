package com.lianwenqi.app.data.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleConstraintRendererTest {

    @Test
    fun render_usesRealStructuredFields() {
        val block = StyleConstraintRenderer.render(testAnalysis("甲"))
        assertTrue(block.startsWith(StyleConstraintRenderer.HEADER))
        // 十个维度 + 整体风格 + 仿写指导 + 应避免，全部来自档案字段
        assertTrue(block.contains("整体风格：甲书"))
        assertTrue(block.contains("可执行的仿写指导"))
        assertTrue(block.contains("应避免的写法"))
        assertTrue(block.contains("叙述方式"))
        assertTrue(block.contains("句式与节奏"))
        assertTrue(block.contains("词汇与表达"))
        assertTrue(block.contains("描写方法"))
        assertTrue(block.contains("段落组织"))
        assertTrue(block.contains("对话特征"))
        assertTrue(block.contains("情绪表达"))
        assertTrue(block.contains("场景与叙事衔接"))
        assertTrue(block.contains("甲书仿写时先写可见动作"))
        assertTrue(block.contains("甲书避免华丽排比"))
    }

    @Test
    fun render_statesStyleOnlyControlsExpression() {
        val block = StyleConstraintRenderer.render(testAnalysis())
        // 明确优先级：文风不覆盖剧情事实与用户要求
        assertTrue(block.contains("仅用于约束语言表达"))
        assertTrue(block.contains("以剧情事实与用户要求为准"))
        assertFalse(block.contains("override"))
    }

    @Test
    fun render_skipsDimensionWithInsufficientEvidence() {
        val analysis = testAnalysis().copy(
            narration = listOf("证据不足：样本中未观察到稳定的叙述视角"),
            evidenceAndConfidence = listOf(
                StyleEvidence("narration", StyleConfidence.INSUFFICIENT, "样本篇幅过短，无法判断视角")
            )
        )
        val block = StyleConstraintRenderer.render(analysis)
        // 证据不足的维度整节省略，不把它改写成强制规则（说明文字里的「叙述方式」不算）
        assertFalse(block.contains("叙述方式："))
        assertFalse(block.contains("证据不足"))
        // 其它维度仍正常输出
        assertTrue(block.contains("句式与节奏"))
    }

    @Test
    fun render_filtersInsufficientItemsInsideDimension() {
        val analysis = testAnalysis().copy(
            diction = listOf(
                "证据不足：样本词汇量有限",
                "甲书多用具体名词与动词，很少堆叠形容词"
            )
        )
        val block = StyleConstraintRenderer.render(analysis)
        assertTrue(block.contains("词汇与表达"))
        assertFalse(block.contains("样本词汇量有限"))
        assertTrue(block.contains("甲书多用具体名词与动词"))
    }

    @Test
    fun render_marksLowConfidenceSection() {
        val analysis = testAnalysis().copy(
            evidenceAndConfidence = listOf(
                StyleEvidence("diction", StyleConfidence.LOW, "样本中词汇特征出现次数较少")
            )
        )
        val block = StyleConstraintRenderer.render(analysis)
        assertTrue(block.contains("词汇与表达（可信度低，仅作参考）"))
    }

    @Test
    fun render_doesNotInventFieldsThatAreEmpty() {
        val analysis = testAnalysis().copy(
            sentenceRhythm = emptyList(),
            diction = emptyList(),
            dialogue = emptyList(),
            evidenceAndConfidence = emptyList()
        )
        val block = StyleConstraintRenderer.render(analysis)
        assertFalse(block.contains("句式与节奏"))
        assertFalse(block.contains("词汇与表达"))
        assertFalse(block.contains("对话特征"))
        // 未缺失的字段仍存在，说明没有整体退回固定模板
        assertTrue(block.contains("叙述方式"))
    }

    @Test
    fun render_returnsEmptyWhenNothingUsable() {
        val analysis = testAnalysis().copy(
            overview = "证据不足：样本文本过短",
            narration = listOf("证据不足：无法判断"),
            sentenceRhythm = emptyList(),
            diction = emptyList(),
            description = emptyList(),
            paragraphStructure = emptyList(),
            dialogue = emptyList(),
            emotion = emptyList(),
            transition = emptyList(),
            writingGuidelines = emptyList(),
            avoidances = emptyList(),
            evidenceAndConfidence = emptyList()
        )
        assertEquals("", StyleConstraintRenderer.render(analysis))
    }

    @Test
    fun render_keepsBlockWithinLengthLimit() {
        val longItem = "甲".repeat(399)
        val analysis = testAnalysis().copy(
            narration = List(12) { longItem.dropLast(it) },
            sentenceRhythm = List(12) { longItem },
            diction = List(12) { longItem },
            description = List(12) { longItem },
            paragraphStructure = List(12) { longItem },
            dialogue = List(12) { longItem },
            emotion = List(12) { longItem },
            transition = List(12) { longItem }
        )
        val block = StyleConstraintRenderer.render(analysis)
        assertTrue(block.length <= 2400)
        assertTrue(block.contains("以剧情事实与用户要求为准"))
    }

    @Test
    fun render_capsItemsPerSection() {
        val analysis = testAnalysis().copy(
            narration = (1..10).map { "甲书叙述特征第${it}条，保持第三人称限知视角" }
        )
        val block = StyleConstraintRenderer.render(analysis)
        assertTrue(block.contains("1. 甲书叙述特征第1条"))
        assertTrue(block.contains("6. 甲书叙述特征第6条"))
        assertFalse(block.contains("甲书叙述特征第7条"))
    }
}
