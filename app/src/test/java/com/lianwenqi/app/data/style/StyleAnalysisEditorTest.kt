package com.lianwenqi.app.data.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结构化编辑表单：往返转换、空字段规则与协议校验 */
class StyleAnalysisEditorTest {

    private val analysis: StyleAnalysis =
        (StyleAnalysisCodec.parse(StyleAnalysisFixtures.validJson())
            as StyleAnalysisParseResult.Success).analysis

    @Test
    fun 表单往返保留分析内容与证据() {
        val form = StyleAnalysisEditor.toForm(analysis)
        val restored = StyleAnalysisEditor.toAnalysis(form, analysis)

        assertEquals(analysis, restored)
        assertEquals(analysis.evidenceAndConfidence, restored.evidenceAndConfidence)
    }

    @Test
    fun 空行被丢弃且空字段不会被补全() {
        val form = StyleAnalysisEditor.toForm(analysis).copy(
            diction = "  新的一条词汇观察  \n\n  \n另一条词汇观察"
        )
        val edited = StyleAnalysisEditor.toAnalysis(form, analysis)

        assertEquals(listOf("新的一条词汇观察", "另一条词汇观察"), edited.diction)
    }

    @Test
    fun 清空必填维度后校验失败() {
        val form = StyleAnalysisEditor.toForm(analysis).copy(transition = "   ")
        val edited = StyleAnalysisEditor.toAnalysis(form, analysis)

        assertTrue(edited.transition.isEmpty())
        val error = StyleAnalysisCodec.validate(edited)
        assertNotNull(error)
        assertTrue(error!!.contains("transition") || error.contains("场景与叙事衔接"))
    }

    @Test
    fun 空泛评价会被协议拒绝() {
        val form = StyleAnalysisEditor.toForm(analysis).copy(narration = "文风优美")
        val edited = StyleAnalysisEditor.toAnalysis(form, analysis)

        val error = StyleAnalysisCodec.validate(edited)
        assertNotNull(error)
        assertTrue(error!!.contains("空泛"))
    }

    @Test
    fun 证据不足条目可保存且不会被补造结论() {
        val form = StyleAnalysisEditor.toForm(analysis)
        assertEquals("证据不足：样本对话较少，无法判断对话长度与口吻", form.dialogue.lines().single())

        val edited = StyleAnalysisEditor.toAnalysis(form, analysis)
        assertNull(StyleAnalysisCodec.validate(edited))
        assertEquals("证据不足：样本对话较少，无法判断对话长度与口吻", edited.dialogue.single())
    }

    @Test
    fun 按字段更新只改对应内容() {
        val form = StyleAnalysisEditor.toForm(analysis)
        val updated = StyleAnalysisEditor.update(
            form,
            StyleAnalysisField.OVERVIEW,
            "更新后的整体风格描述内容"
        )
        assertEquals("更新后的整体风格描述内容", updated.overview)
        assertEquals(form.narration, updated.narration)
        assertEquals(form.avoidances, updated.avoidances)
    }
}
