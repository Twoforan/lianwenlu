package com.lianwenqi.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 大纲条目第一项统一使用的标签名（该项只读，不可改名、不可删除） */
const val OUTLINE_HEAD_LABEL = "显示标签"

@Serializable
private data class OutlineFieldDto(val n: String = "", val v: String = "")

private val outlineJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val outlineFieldsSerializer = ListSerializer(OutlineFieldDto.serializer())

/**
 * 把「标签 → 内容」列表编码为可安全存库的字符串。
 * 采用 JSON 存储，内容里的冒号、换行都能原样保留，
 * 解析时不会再被误拆成新的项（旧版「标签：内容」纯文本格式做不到这一点）。
 */
fun encodeOutlineFields(fields: List<Pair<String, String>>): String =
    outlineJson.encodeToString(
        outlineFieldsSerializer,
        fields.filter { it.first.isNotBlank() }.map { OutlineFieldDto(it.first, it.second) }
    )

/** 内容是否为当前的结构化字段（JSON）格式 */
fun isStructuredOutlineContent(content: String): Boolean =
    content.trimStart().startsWith("[")

/**
 * 解析大纲内容为「标签 → 内容」列表。
 * 新数据为 JSON；旧数据为纯文本「标签：内容」，做兼容回落。
 */
fun decodeOutlineFields(content: String): List<Pair<String, String>> {
    if (content.isBlank()) return emptyList()
    val trimmed = content.trim()
    if (trimmed.startsWith("[")) {
        runCatching {
            return outlineJson.decodeFromString(outlineFieldsSerializer, trimmed).map { it.n to it.v }
        }
    }
    return decodeLegacyOutlineFields(content)
}

/**
 * 大纲条目的对外显示名。
 * 结构化内容取首项「显示标签」的值；旧版纯文本内容直接使用条目自身的 title，
 * 不再从正文首行 / 以「-」开头的列表项推测标题。
 */
fun outlineDisplayName(title: String, content: String): String {
    val stored = title.trim()
    if (!isStructuredOutlineContent(content)) return stored
    val fields = decodeOutlineFields(content)
    val head = (fields.firstOrNull { it.first == OUTLINE_HEAD_LABEL } ?: fields.firstOrNull())
        ?.second?.trim().orEmpty()
    return if (head.isBlank() || head == "…" || head == "...") stored else head
}

/**
 * 大纲条目可供上下文 / 工具输出使用的字段列表。
 * 结构化内容按字段解析；旧版纯文本内容整体作为一个「内容」字段，
 * 既不按冒号硬拆，也不把「- xxx」当成标题。
 */
fun outlineFieldsForDisplay(title: String, content: String): List<Pair<String, String>> {
    if (isStructuredOutlineContent(content)) return decodeOutlineFields(content)
    val head = outlineDisplayName(title, content)
    val body = content.trim()
    return buildList {
        if (head.isNotBlank()) add(OUTLINE_HEAD_LABEL to head)
        if (body.isNotBlank()) add("内容" to body)
    }
}

/**
 * Agent 写入大纲时把标题 + 字段文本整理为结构化内容。
 *
 * 首项固定为「显示标签」；`fieldsText` 按「字段名：内容」逐行解析。
 * 没有字段时：若 `content` 本身是「字段名：内容」文本则拆成多个字段，
 * 否则整段作为单个「内容」字段，避免把普通段落误拆成字段名。
 */
fun buildOutlineContentForAgent(
    title: String,
    fieldsText: String = "",
    content: String = ""
): String {
    val fields = LinkedHashMap<String, String>()
    parseOutlineFieldText(fieldsText).forEach { (name, value) ->
        if (name != OUTLINE_HEAD_LABEL && name.isNotBlank()) fields[name] = value
    }
    if (fields.isEmpty()) {
        val body = content.trim()
        if (body.isNotEmpty()) {
            val parsed = decodeLegacyOutlineFields(body)
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            val looksLikeFields = parsed.size >= 2 && parsed.all { it.first.length <= 30 }
            if (looksLikeFields) {
                parsed.forEach { fields[it.first] = it.second }
            } else {
                fields["内容"] = body
            }
        }
    }
    return encodeOutlineFields(
        listOf(OUTLINE_HEAD_LABEL to title.trim()) + fields.map { it.key to it.value }
    )
}

/** 解析「字段名：内容」文本；无冒号的续行并入上一字段，空值字段忽略 */
fun parseOutlineFieldText(text: String): List<Pair<String, String>> {
    if (text.isBlank()) return emptyList()
    return decodeLegacyOutlineFields(text)
        .filter { it.first.isNotBlank() && it.second.isNotBlank() }
}

/** 兼容旧版纯文本格式：含冒号的行开启新项，其余非空行并入上一项内容 */
private fun decodeLegacyOutlineFields(content: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    // 记录项之间尚未归入任何项的空行数，遇到续行时按原样补回
    var blanks = 0
    content.lines().forEach { raw ->
        val line = raw.trimEnd()
        val i = line.indexOfAny(charArrayOf('：', ':'))
        when {
            i > 0 -> {
                out.add(line.substring(0, i).trim() to line.substring(i + 1).trim())
                blanks = 0
            }

            line.isBlank() -> if (out.isNotEmpty()) blanks++
            out.isEmpty() -> out.add(line.trim() to "")
            else -> {
                val last = out.removeAt(out.lastIndex)
                out.add(last.first to (last.second + "\n".repeat(blanks + 1) + line))
                blanks = 0
            }
        }
    }
    return out
}


