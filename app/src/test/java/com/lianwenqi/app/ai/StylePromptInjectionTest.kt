package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagSource
import com.lianwenqi.app.data.style.StyleSampleHit
import com.lianwenqi.app.data.style.SummarySentenceSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 梗概 + 句子标签、正文句子参考块与临时文风样本块的提示词组装 */
class StylePromptInjectionTest {

    private fun profileTag(id: Long, category: String, name: String) = StyleTag(
        id = id,
        styleProfileId = 7L,
        category = category,
        name = name,
        source = StyleTagSource.AI
    )

    @Test
    fun `开启标签时梗概提示词要求句子标签结构化输出`() {
        val turns = buildSummaryTurns(
            "写本章梗概", 400..600, withTags = true,
            profileTags = listOf(profileTag(11, "表达方式", "潜台词"))
        )
        val system = turns.first { it.role == com.lianwenqi.app.data.entity.ChatRole.SYSTEM }.content
        assertTrue(system.contains("synopsis"))
        assertTrue(system.contains("sentences"))
        assertTrue(system.contains("\"id\":12"))
        assertTrue(system.contains("id=11"))
    }

    @Test
    fun `开启标签但档案没有标签时按普通文本输出`() {
        val turns = buildSummaryTurns("写本章梗概", 400..600, withTags = true, profileTags = emptyList())
        val system = turns.first { it.role == com.lianwenqi.app.data.entity.ChatRole.SYSTEM }.content
        assertFalse(system.contains("synopsis"))
        assertTrue(system.contains("只输出最终梗概这一整段文字"))
    }

    @Test
    fun `关闭标签时梗概提示词保持原文本输出要求`() {
        val turns = buildSummaryTurns(
            "写本章梗概", 400..600, withTags = false,
            profileTags = listOf(profileTag(11, "表达方式", "潜台词"))
        )
        val system = turns.first { it.role == com.lianwenqi.app.data.entity.ChatRole.SYSTEM }.content
        assertFalse(system.contains("synopsis"))
        assertTrue(system.contains("只输出最终梗概这一整段文字"))
    }

    @Test
    fun `正文提示词注入句子参考块与临时样本块及使用规则`() {
        val hits = listOf(
            StyleSampleHit(
                chunkId = 1,
                sampleId = 10,
                chunkIndex = 0,
                text = "他把杯子放下，没有抬头。",
                tagLabels = listOf("表达方式：潜台词", "情绪氛围：压抑"),
                weight = 4.5
            )
        )
        val sentenceBlock = buildSummarySentenceTagBlock(
            listOf(SummarySentenceSample("他把杯子放下，没有抬头。", listOf("潜台词", "压抑")))
        )
        val turns = buildContentTurns(
            summary = "本章梗概",
            outlineSlot = "大纲",
            memoryBlock = "",
            userInput = "开始写",
            range = 800..1200,
            summarySentenceBlock = sentenceBlock,
            styleSamplesBlock = buildStyleSampleBlock(hits)
        )
        val instruction = turns.first { it.role == com.lianwenqi.app.data.entity.ChatRole.SYSTEM }.content
        // 参考资料是第二个 SYSTEM 轮；最后一轮是用户输入
        val reference = turns.filter { it.role == com.lianwenqi.app.data.entity.ChatRole.SYSTEM }.last().content

        assertTrue(instruction.contains("文风标签句例"))
        assertTrue(instruction.contains("临时文风样本"))
        assertTrue(instruction.contains("文风优先"))
        assertTrue(reference.contains("第一优先表达依据"))
        assertTrue(reference.contains("【本章梗概·文风标签句例】"))
        assertTrue(reference.contains("标签：潜台词、压抑"))
        assertTrue(reference.contains("原文：他把杯子放下"))
        assertTrue(reference.contains("不是新增剧情设定"))
        assertTrue(reference.contains("【临时文风样本】"))
        assertTrue(reference.contains("【临时文风样本使用规则】"))
        assertTrue(reference.contains("不得复制样本中的人物"))
        // 收尾强调排在所有参考资料之后，紧贴用户输入，避免模型忽略样本
        assertTrue(reference.contains("【文风执行要求】"))
        assertTrue(reference.trimEnd().endsWith("不要输出文风分析过程。"))
    }

    @Test
    fun `没有句子参考与样本时提示词不出现相关区块`() {
        val turns = buildContentTurns(
            summary = "本章梗概",
            outlineSlot = "大纲",
            memoryBlock = "",
            userInput = "开始写",
            range = 800..1200,
            summarySentenceBlock = "",
            styleSamplesBlock = ""
        )
        val all = turns.joinToString("\n") { it.content }
        assertFalse(all.contains("【本章梗概·文风标签句例】"))
        assertFalse(all.contains("【临时文风样本】"))
        assertFalse(all.contains("【临时文风样本使用规则】"))
        assertFalse(all.contains("【文风执行要求】"))
    }

    @Test
    fun `空抽样不生成句子参考块`() {
        assertTrue(buildSummarySentenceTagBlock(emptyList()).isEmpty())
    }
}
