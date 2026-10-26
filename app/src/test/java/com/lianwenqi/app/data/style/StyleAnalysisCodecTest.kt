package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.StyleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 结构化分析结果的解析与校验（纯 JVM） */
class StyleAnalysisCodecTest {

    private fun parseFailure(raw: String): String {
        val result = StyleAnalysisCodec.parse(raw)
        assertTrue("应当解析失败：$raw", result is StyleAnalysisParseResult.Failure)
        return (result as StyleAnalysisParseResult.Failure).message
    }

    private fun parseSuccess(raw: String): StyleAnalysisParseResult.Success {
        val result = StyleAnalysisCodec.parse(raw)
        assertTrue("应当解析成功：$result", result is StyleAnalysisParseResult.Success)
        return result as StyleAnalysisParseResult.Success
    }

    @Test
    fun `合法 JSON 解析成功且字段完整`() {
        val success = parseSuccess(StyleAnalysisFixtures.validJson())
        assertEquals(1, success.analysis.schemaVersion)
        assertEquals(1, success.analysis.narration.size)
        assertTrue(success.analysis.writingGuidelines.size >= 2)
        assertEquals(3, success.analysis.evidenceAndConfidence.size)
        assertTrue(success.json.startsWith("{"))
        assertTrue(success.json.contains("\"schemaVersion\":1"))
    }

    @Test
    fun `缺失必填字段解析失败`() {
        val raw = StyleAnalysisFixtures.validJson().replace("\"emotion\": [\"情绪表达克制，很少直接使用情绪标签，多由停顿与视线变化承载\"],", "")
        val message = parseFailure(raw)
        assertTrue(message.contains("emotion"))
    }

    @Test
    fun `字段类型错误解析失败`() {
        val raw = StyleAnalysisFixtures.validJson(narration = "\"多采用第三人称限制视角\"")
        val message = parseFailure(raw)
        assertTrue(message.contains("narration"))
    }

    @Test
    fun `空响应解析失败`() {
        assertEquals("模型返回为空", parseFailure("   "))
    }

    @Test
    fun `代码围栏包裹时取围栏内容`() {
        val raw = "下面是分析结果：\n```json\n" + StyleAnalysisFixtures.validJson() + "\n```\n希望有帮助。"
        val success = parseSuccess(raw)
        assertEquals(1, success.analysis.schemaVersion)
    }

    @Test
    fun `无围栏时忽略前后说明文字`() {
        val raw = "分析如下。\n" + StyleAnalysisFixtures.validJson() + "\n以上是全部内容。"
        assertTrue(parseSuccess(raw).analysis.overview.isNotBlank())
    }

    @Test
    fun `无法定位 JSON 文本时失败`() {
        val message = parseFailure("语言冷峻，节奏克制，没有 JSON。")
        assertTrue(message.contains("JSON"))
    }

    @Test
    fun `非法 JSON 解析失败`() {
        val raw = StyleAnalysisFixtures.validJson().replaceFirst("{", "{,")
        val message = parseFailure(raw)
        assertTrue(message.contains("JSON"))
    }

    @Test
    fun `空泛评价条目被拒绝`() {
        val raw = StyleAnalysisFixtures.validJson(diction = """["语言细腻"]""")
        val message = parseFailure(raw)
        assertTrue(message.contains("空泛"))
    }

    @Test
    fun `不支持的结果格式版本被拒绝`() {
        val raw = StyleAnalysisFixtures.validJson(schemaVersion = "99")
        val message = parseFailure(raw)
        assertTrue(message.contains("版本"))
    }

    @Test
    fun `证据维度不合法被拒绝`() {
        val raw = StyleAnalysisFixtures.validJson(
            evidence = """[{"dimension":"unknownDimension","confidence":"high","evidence":"某处观察"}]"""
        )
        val message = parseFailure(raw)
        assertTrue(message.contains("dimension"))
    }

    @Test
    fun `仿写指导少于两条被拒绝`() {
        val raw = StyleAnalysisFixtures.validJson(
            writingGuidelines = """["写迟疑时先写动作停顿，再用一句短对白交代态度"]"""
        )
        val message = parseFailure(raw)
        assertTrue(message.contains("writingGuidelines"))
    }

    @Test
    fun `未知字段被忽略且不写入规范化 JSON`() {
        val raw = StyleAnalysisFixtures.validJson()
            .replace("\"overview\":", "\"extra\":\"不应保留\",\"overview\":")
        val success = parseSuccess(raw)
        assertTrue(!success.json.contains("extra"))
    }

    @Test
    fun `规范化 JSON 不超过档案字段上限`() {
        val success = parseSuccess(StyleAnalysisFixtures.validJson())
        val bytes = success.json.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes <= StyleProfile.MAX_ANALYSIS_JSON_BYTES)
    }
}
