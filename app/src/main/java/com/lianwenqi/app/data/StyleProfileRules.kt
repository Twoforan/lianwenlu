package com.lianwenqi.app.data

import com.lianwenqi.app.data.entity.StyleProfile
import com.lianwenqi.app.data.entity.StyleSourceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 文风档案的数据约束（纯 Kotlin，不依赖 Room / Android，便于单元测试）。
 * 名称非空且有长度上限；来源类型归一到已知枚举；两个 JSON 字段必须是 JSON 对象且不超过大小上限。
 * 校验失败直接抛 [IllegalArgumentException]，不做静默截断。
 */
object StyleProfileRules {
    private val json = Json { ignoreUnknownKeys = true }

    /** 规整并校验待保存的档案，返回名称去空白、来源类型归一后的副本 */
    fun normalize(profile: StyleProfile): StyleProfile {
        val name = profile.name.trim()
        require(name.isNotEmpty()) { "文风档案名称不能为空" }
        require(name.length <= StyleProfile.MAX_NAME_LENGTH) {
            "文风档案名称不能超过 ${StyleProfile.MAX_NAME_LENGTH} 字"
        }
        val sourceMeta = profile.sourceMeta.ifBlank { "{}" }
        val analysisJson = profile.analysisJson.ifBlank { "{}" }
        requireJsonObject("sourceMeta", sourceMeta, StyleProfile.MAX_SOURCE_META_BYTES)
        requireJsonObject("analysisJson", analysisJson, StyleProfile.MAX_ANALYSIS_JSON_BYTES)
        require(profile.analysisVersion > 0) { "analysisVersion 必须大于 0" }
        return profile.copy(
            name = name,
            sourceType = StyleSourceType.fromKey(profile.sourceType).name,
            sourceMeta = sourceMeta,
            analysisJson = analysisJson
        )
    }

    /** 校验某个 JSON 字段：必须是 JSON 对象，且 UTF-8 字节数不超过 [maxBytes] */
    fun requireJsonObject(field: String, raw: String, maxBytes: Int) {
        val bytes = raw.toByteArray(Charsets.UTF_8).size
        require(bytes <= maxBytes) {
            "$field 超过 ${maxBytes / 1024}KB 上限（当前 $bytes 字节），请精简内容后重试"
        }
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        require(element is JsonObject) { "$field 必须是 JSON 对象" }
    }
}
