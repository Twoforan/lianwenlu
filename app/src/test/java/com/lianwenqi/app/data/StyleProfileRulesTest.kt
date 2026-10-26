package com.lianwenqi.app.data

import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文风档案的数据约束：名称 / 来源类型 / JSON 对象 / 32KB 上限（纯 JVM 测试） */
class StyleProfileRulesTest {

    @Test
    fun `名称去空白并保留默认值`() {
        val normalized = StyleProfileRules.normalize(StyleProfile(name = "  冷峻叙事  "))
        assertEquals("冷峻叙事", normalized.name)
        assertEquals("MANUAL", normalized.sourceType)
        assertEquals("{}", normalized.analysisJson)
        assertEquals("{}", normalized.sourceMeta)
        assertEquals(1, normalized.analysisVersion)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空名称被拒绝`() {
        StyleProfileRules.normalize(StyleProfile(name = "   "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `超长名称被拒绝`() {
        StyleProfileRules.normalize(StyleProfile(name = "名".repeat(StyleProfile.MAX_NAME_LENGTH + 1)))
    }

    @Test
    fun `未知来源类型回落到手动创建`() {
        val normalized = StyleProfileRules.normalize(
            StyleProfile(name = "测试", sourceType = "UNKNOWN_FUTURE_TYPE")
        )
        assertEquals("MANUAL", normalized.sourceType)
    }

    @Test
    fun `合法的结构化分析 JSON 通过`() {
        val json = """{"narrative":{"pov":"第三人称","tense":"过去时"},"diction":["冷峻","克制"]}"""
        val normalized = StyleProfileRules.normalize(StyleProfile(name = "测试", analysisJson = json))
        assertEquals(json, normalized.analysisJson)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `纯文本摘要不是合法 JSON 对象`() {
        StyleProfileRules.normalize(StyleProfile(name = "测试", analysisJson = "语言冷峻、节奏克制"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `JSON 数组不被接受`() {
        StyleProfileRules.normalize(StyleProfile(name = "测试", analysisJson = """["冷峻"]"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `analysisJson 超过 32KB 被明确拒绝而非静默截断`() {
        val big = """{"pad":"""" + "字".repeat(StyleProfile.MAX_ANALYSIS_JSON_BYTES) + """"}"""
        StyleProfileRules.normalize(StyleProfile(name = "测试", analysisJson = big))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `analysisVersion 非正数被拒绝`() {
        StyleProfileRules.normalize(StyleProfile(name = "测试", analysisVersion = 0))
    }

    @Test
    fun `旧版备份缺少文风字段时按未绑定与空列表解析`() {
        val old = """
            {"app":"lianwenlu","format":1,"exportedAt":1,"appVersion":"4.5.2",
             "books":[{"title":"旧书","intro":"","coverColor":0,
                       "volumes":[{"title":"第一卷","sortOrder":0}],
                       "chapters":[{"title":"第一章","content":"正文","sortOrder":0}]}]}
        """.trimIndent()
        val payload = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.decodeFromString<BackupPayload>(old)
        assertEquals(0L, payload.books.single().styleId)
        assertTrue(payload.styleProfiles.isEmpty())
        assertEquals("旧书", payload.books.single().title)
        assertEquals("正文", payload.books.single().chapters.single().content)
    }
}
