package com.lianwenqi.app.agent.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

/** Agent 内部 JSON 工具：只做校验与组装，不参与模型请求 */
internal val agentJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 校验字段是可解析的 JSON 对象，返回原字符串（便于链式使用） */
internal fun requireJsonObject(raw: String, field: String): String {
    val parsed = runCatching { agentJson.parseToJsonElement(raw) }.getOrNull()
    require(parsed is JsonObject) { "$field 必须是可解析的 JSON 对象" }
    return raw
}

/** 组装事件 / 摘要用 JSON 字符串 */
internal fun jsonPayload(build: JsonObjectBuilder.() -> Unit): String =
    buildJsonObject(build).toString()
