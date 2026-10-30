package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.StyleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ---------- v2：每条特征各自的可信度区间 ----------

    @Test
    fun `合法 v2 JSON 解析成功且每条特征各有区间`() {
        val success = parseSuccess(StyleAnalysisFixtures.validV2Json())
        val analysis = success.analysis
        assertEquals(2, analysis.schemaVersion)
        assertTrue(analysis.evidenceAndConfidence.isNotEmpty())
        analysis.evidenceAndConfidence.forEach { entry ->
            assertNotNull(entry.confidenceInterval)
            assertNull(entry.confidence)
            assertTrue(entry.feature.isNotBlank())
            assertTrue(entry.evidenceQuality in StyleEvidenceQuality.ALL)
            assertTrue(entry.sampleCoverage in StyleSampleCoverage.ALL)
        }
        // 仿写指导的可信度按 rule 一一对应，没有丢失
        assertEquals(analysis.writingGuidelines.size, analysis.guidelineConfidence.size)
        assertTrue(analysis.writingGuidelines.containsAll(analysis.guidelineConfidence.map { it.rule }))
        assertTrue(success.json.contains("\"schemaVersion\":2"))
    }

    @Test
    fun `同一维度多个特征不会被合并成一条`() {
        val analysis = parseSuccess(StyleAnalysisFixtures.validV2Json()).analysis
        val narration = analysis.evidenceAndConfidence.filter { it.dimension == "narration" }
        assertEquals(2, narration.size)
        // 两个特征保留各自区间，不取平均、不合并为单一结论
        assertEquals(setOf(80 to 95, 35 to 65), narration.mapNotNull { it.confidenceInterval }
            .map { it.lower to it.upper }.toSet())
        assertTrue(narration.any { it.evidenceQuality == StyleEvidenceQuality.STRONG })
        assertTrue(narration.any { it.evidenceQuality == StyleEvidenceQuality.WEAK })
    }

    @Test
    fun `证据不足特征可以保留为高度不确定的区间`() {
        val analysis = parseSuccess(StyleAnalysisFixtures.validV2Json()).analysis
        val dialogue = analysis.evidenceAndConfidence.single { it.dimension == "dialogue" }
        assertEquals(StyleEvidenceQuality.INSUFFICIENT, dialogue.evidenceQuality)
        assertEquals(StyleSampleCoverage.INSUFFICIENT, dialogue.sampleCoverage)
        assertEquals(StyleConfidenceInterval(0, 100), dialogue.confidenceInterval)
    }

    @Test
    fun `可信度区间上下界非法时被拒绝`() {
        val cases = mapOf(
            "\"lower\":90,\"upper\":60" to "可信度区间",
            "\"lower\":80,\"upper\":120" to "可信度区间",
            "\"lower\":-5,\"upper\":95" to "可信度区间",
            "\"lower\":80.5,\"upper\":95" to "必须是整数"
        )
        cases.forEach { (bad, expected) ->
            val raw = StyleAnalysisFixtures.validV2Json()
                .replaceFirst("\"lower\":80,\"upper\":95", bad)
            val message = parseFailure(raw)
            assertTrue("$bad -> $message", message.contains(expected))
        }
    }

    @Test
    fun `缺少可信度区间时被拒绝`() {
        val raw = StyleAnalysisFixtures.validV2Json()
            .replaceFirst("\"confidenceInterval\":{\"lower\":80,\"upper\":95},", "")
        val message = parseFailure(raw)
        assertTrue(message.contains("confidenceInterval"))
    }

    @Test
    fun `证据质量或覆盖度取值非法时被拒绝`() {
        val badQuality = StyleAnalysisFixtures.validV2Json()
            .replaceFirst("\"evidenceQuality\":\"strong\"", "\"evidenceQuality\":\"certain\"")
        assertTrue(parseFailure(badQuality).contains("evidenceQuality"))

        val badCoverage = StyleAnalysisFixtures.validV2Json()
            .replaceFirst("\"sampleCoverage\":\"broad\"", "\"sampleCoverage\":\"unknown\"")
        assertTrue(parseFailure(badCoverage).contains("sampleCoverage"))
    }

    @Test
    fun `v2 缺少必备证据维度时被拒绝`() {
        val raw = StyleAnalysisFixtures.validV2Json(
            evidence = """[{"dimension":"narration","feature":"叙述距离贴近主角","confidenceInterval":{"lower":80,"upper":95},"evidenceQuality":"strong","sampleCoverage":"broad","evidence":"多个片段先写动作停顿"},{"dimension":"diction","feature":"动词密度高于形容词","confidenceInterval":{"lower":70,"upper":90},"evidenceQuality":"moderate","sampleCoverage":"partial","evidence":"动作动词密集而形容词较少"}]"""
        )
        val message = parseFailure(raw)
        assertTrue(message.contains("sentenceRhythm"))
    }

    @Test
    fun `v2 仿写指导使用字符串数组时仍可解析且无区间信息`() {
        val raw = StyleAnalysisFixtures.validV2Json(
            writingGuidelines = """["写迟疑时先写动作停顿，再用短句收尾","场景转换前用一个具体物件作为落点"]"""
        )
        val analysis = parseSuccess(raw).analysis
        assertEquals(2, analysis.writingGuidelines.size)
        assertTrue(analysis.guidelineConfidence.isEmpty())
    }

    @Test
    fun `v2 规范化编码后再次解析保留指导可信度`() {
        val first = parseSuccess(StyleAnalysisFixtures.validV2Json()).analysis
        val again = parseSuccess(StyleAnalysisCodec.encode(first)).analysis
        assertEquals(first, again)
        assertEquals(first.guidelineConfidence, again.guidelineConfidence)
    }

    @Test
    fun `v1 旧数据保留原始档位且不换算成区间`() {
        val analysis = parseSuccess(StyleAnalysisFixtures.validJson()).analysis
        assertEquals(1, analysis.schemaVersion)
        assertEquals("high", analysis.evidenceAndConfidence.first().confidence)
        analysis.evidenceAndConfidence.forEach { entry ->
            assertNull(entry.confidenceInterval)
            assertEquals("", entry.feature)
            assertNull(entry.evidenceQuality)
            assertNull(entry.sampleCoverage)
        }
        assertTrue(analysis.guidelineConfidence.isEmpty())
    }
}
