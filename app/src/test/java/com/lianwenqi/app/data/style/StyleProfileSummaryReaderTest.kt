package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.StyleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 档案只读摘要：正常读取、缺失字段与无法解析时的降级行为 */
class StyleProfileSummaryReaderTest {

    private fun profile(
        analysisJson: String,
        sourceMeta: String = """{"sampling":{"strategy":"FULL","analyzedSamples":2,"includedChars":5000},"model":{"displayName":"测试模型"},"samples":[{"name":"样本A.txt"},{"name":"样本B.txt"}]}"""
    ) = StyleProfile(
        id = 1,
        name = "冷峻叙事",
        sourceType = "CHAPTERS",
        sourceMeta = sourceMeta,
        analysisJson = analysisJson,
        analysisVersion = 1,
        modelName = ""
    )

    @Test
    fun 合法档案给出概述与来源元信息() {
        val summary = StyleProfileSummaryReader.read(profile(StyleAnalysisFixtures.validJson()))

        val expected = (StyleAnalysisCodec.parse(StyleAnalysisFixtures.validJson())
            as StyleAnalysisParseResult.Success).analysis.overview
        assertEquals(expected, summary.overview)
        assertEquals(1, summary.schemaVersion)
        assertTrue(summary.recognizable)
        assertTrue(summary.unknownFields.isEmpty())
        assertEquals("多个章节", summary.sourceLabel)
        assertEquals("测试模型", summary.modelName)
        assertEquals(listOf("样本A.txt", "样本B.txt"), summary.sampleNames)
        assertEquals(2, summary.analyzedSamples)
        assertEquals(5000, summary.includedChars)
        assertEquals("完整样本", summary.strategyLabel)
    }

    @Test
    fun 档案自身模型名优先于来源元信息() {
        val summary = StyleProfileSummaryReader.read(
            profile(StyleAnalysisFixtures.validJson()).copy(modelName = "深度求索")
        )
        assertEquals("深度求索", summary.modelName)
    }

    @Test
    fun 无法解析的分析结果降级为暂无概述() {
        val summary = StyleProfileSummaryReader.read(profile("""{"broken":"""))

        assertNull(summary.overview)
        assertNull(summary.schemaVersion)
        assertFalse(summary.recognizable)
        assertTrue(summary.unknownFields.isEmpty())
    }

    @Test
    fun 旧版档位与新版区间档案都能识别() {
        val legacy = StyleProfileSummaryReader.read(profile(StyleAnalysisFixtures.validJson()))
        assertEquals(1, legacy.schemaVersion)
        assertTrue(legacy.recognizable)

        val v2 = StyleProfileSummaryReader.read(profile(StyleAnalysisFixtures.validV2Json()))
        assertEquals(2, v2.schemaVersion)
        assertTrue(v2.recognizable)
        assertTrue(v2.overview!!.isNotBlank())
    }

    @Test
    fun 未知版本降级为暂无概述并标记不可识别() {
        val summary = StyleProfileSummaryReader.read(
            profile(StyleAnalysisFixtures.validJson(schemaVersion = "3"))
        )

        assertNull(summary.overview)
        assertEquals(3, summary.schemaVersion)
        assertFalse(summary.recognizable)
    }

    @Test
    fun 多余顶层字段被记录以便编辑前确认() {
        val raw = StyleAnalysisFixtures.validJson().let {
            it.substringBeforeLast('}') + ""","extraNote":"历史遗留字段"}"""
        }
        val summary = StyleProfileSummaryReader.read(profile(raw))

        assertTrue(summary.recognizable)
        assertEquals(listOf("extraNote"), summary.unknownFields)
    }

    @Test
    fun 来源元信息损坏不影响档案本身读取() {
        val summary = StyleProfileSummaryReader.read(
            profile(StyleAnalysisFixtures.validJson(), sourceMeta = "not-json")
        )

        assertTrue(summary.recognizable)
        assertEquals("", summary.modelName)
        assertTrue(summary.sampleNames.isEmpty())
        assertNull(summary.analyzedSamples)
        assertNull(summary.strategyLabel)
    }
}
