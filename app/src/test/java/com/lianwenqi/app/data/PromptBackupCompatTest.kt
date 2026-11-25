package com.lianwenqi.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词类型字段的备份兼容验证：
 * 备份用的是 kotlinx.serialization，旧备份没有 type 字段，必须实测它能靠默认值解析成快捷提示词，
 * 不能只依赖「Kotlin 默认参数」的假设。
 */
class PromptBackupCompatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun 旧备份缺少type字段时解析为快捷提示词() {
        val raw = """{"title":"旧提示词","content":"旧正文","createdAt":1000}"""

        val prompt = json.decodeFromString(BackupPrompt.serializer(), raw)

        assertEquals("QUICK", prompt.type)
        assertEquals("旧提示词", prompt.title)
        assertEquals("旧正文", prompt.content)
        assertEquals(1000L, prompt.createdAt)
    }

    @Test
    fun 旧备份整个prompts字段缺失时解析为空列表() {
        val raw = """{"app":"lianwenlu","format":1,"exportedAt":0,"appVersion":"4.8.3"}"""

        val payload = json.decodeFromString(BackupPayload.serializer(), raw)

        assertTrue(payload.prompts.isEmpty())
    }

    @Test
    fun 新备份写入并保留模型提示词类型() {
        val payload = BackupPayload(
            prompts = listOf(
                BackupPrompt(title = "写作规范", content = "系统提示词正文", type = "MODEL", createdAt = 1),
                BackupPrompt(title = "扩写", content = "扩写这一段", type = "QUICK", createdAt = 2)
            )
        )

        val encoded = json.encodeToString(BackupPayload.serializer(), payload)
        val decoded = json.decodeFromString(BackupPayload.serializer(), encoded)

        assertTrue(encoded.contains("\"type\":\"MODEL\""))
        assertEquals(listOf("MODEL", "QUICK"), decoded.prompts.map { it.type })
        assertEquals("系统提示词正文", decoded.prompts.first().content)
    }
}
