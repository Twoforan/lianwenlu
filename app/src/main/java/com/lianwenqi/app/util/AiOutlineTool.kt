package com.lianwenqi.app.util

import com.lianwenqi.app.ai.AiToolCall
import com.lianwenqi.app.ai.AiToolSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 「写入大纲工具」的固定 id（AI 聊天页多选、工具页开关共用） */
const val AI_TOOL_WRITE_OUTLINE = "write_outline"

/** 「批量写入章节梗概」的固定 id */
const val AI_TOOL_IMPORT_CHAPTER = "import_chapter"

/** 工具调用名称：大纲导入 */
const val AI_TOOL_IMPORT_OUTLINE_NAME = "import_outline"

/** 工具调用名称：章节梗概批量导入 */
const val AI_TOOL_IMPORT_CHAPTER_NAME = "import_chapters"

/** AI 工具展示信息 */
data class AiToolInfo(val id: String, val name: String, val description: String)

/** 目前可用的全部 AI 工具（以后新增工具往这里加） */
val AI_TOOLS: List<AiToolInfo> = listOf(
    AiToolInfo(
        id = AI_TOOL_WRITE_OUTLINE,
        name = "写入大纲工具",
        description = "AI 可在回答时调用工具，把人设 / 世界观等结构化写入作品大纲"
    ),
    AiToolInfo(
        id = AI_TOOL_IMPORT_CHAPTER,
        name = "批量写入章节梗概",
        description = "AI 可在回答时调用工具，把章节名与梗概批量写入书籍分卷与章节"
    )
)

/** 解析结果：[blocks] 为解析出的区块；[malformed] 为真表示存在「标记存在但无法解析」的畸形区块 */
data class AiBlocksResult<T>(val blocks: List<T>, val malformed: Boolean)

/** 章节区块的起止标记：AI 回答 / 粘贴文本中出现该标记即视为可导入的章节梗概 */
const val AI_CHAPTER_BEGIN = "<<<AI_CHAPTER_JSON_V1>>>"
const val AI_CHAPTER_END = "<<<END_AI_CHAPTER_JSON_V1>>>"

/** 「章节梗概导入工具」的固定提示词（勾选工具后随请求自动发给模型） */
const val AI_CHAPTER_TOOL_PROMPT: String = """当你需要生成可导入的章节名称和章节梗概时，输出以下格式，不要额外解释：

<<<AI_CHAPTER_JSON_V1>>>
{
  "chapters": [
    {
      "volume": "分卷名，没有可写空字符串",
      "chapterName": "章节名",
      "summary": "章节梗概",
     "order":"章节在本卷的序列号"
    }
  ]
}
<<<END_AI_CHAPTER_JSON_V1>>>

注意：
- 每章一条，同一分卷也要重复写 volume。
- 不要输出 Markdown 代码块。
- 不要输出 JSON 以外的说明文字。"""

/** 一个可导入的章节梗概区块：分卷名 + 章节名 + 梗概 + 卷内序号 */
data class AiChapterBlock(
    val volume: String,
    val chapterName: String,
    val summary: String,
    val order: Int
)

@Serializable
private data class AiChapterDto(
    val volume: String = "",
    val chapterName: String = "",
    val summary: String = "",
    // AI 可能输出数字或字符串，统一按 JsonElement 接收再解析
    val order: JsonElement? = null
)

@Serializable
private data class AiChapterPayload(val chapters: List<AiChapterDto> = emptyList())

private val aiChapterJson = Json { ignoreUnknownKeys = true }

private fun AiChapterDto.toBlock(): AiChapterBlock? =
    if (chapterName.isBlank()) null
    else AiChapterBlock(
        volume = volume.trim(),
        chapterName = chapterName.trim(),
        summary = summary,
        order = order?.let { el ->
            runCatching { el.jsonPrimitive.intOrNull }.getOrNull()
                ?: runCatching { el.jsonPrimitive.content.trim().toIntOrNull() }.getOrNull()
        } ?: 0
    )

/** 解析工具调用参数（import_chapters 的 arguments JSON）；格式非法时返回 null */
fun parseAiChapterBlocksJson(text: String): List<AiChapterBlock>? {
    val chapters = runCatching {
        aiChapterJson.decodeFromString(AiChapterPayload.serializer(), text).chapters
    }.getOrNull() ?: return null
    return chapters.mapNotNull { it.toBlock() }
}

/**
 * 从文本中解析全部章节梗概区块，并额外报告是否存在畸形区块。
 * 与 [parseAiChapterBlocks] 的区别：不静默吞掉解析失败，而是通过 [AiBlocksResult.malformed] 暴露。
 */
fun parseAiChapterBlocksResult(text: String): AiBlocksResult<AiChapterBlock> {
    val out = mutableListOf<AiChapterBlock>()
    var malformed = false
    var from = 0
    while (true) {
        val begin = text.indexOf(AI_CHAPTER_BEGIN, from)
        if (begin < 0) break
        val jsonStart = begin + AI_CHAPTER_BEGIN.length
        val end = text.indexOf(AI_CHAPTER_END, jsonStart)
        if (end < 0) {
            // 有开始标记却没有结束标记 → 畸形
            malformed = true
            break
        }
        val parsed = parseAiChapterBlocksJson(text.substring(jsonStart, end).trim())
        if (parsed == null) {
            // 标记存在但 JSON 解析失败 → 畸形
            malformed = true
        } else {
            out.addAll(parsed)
        }
        from = end + AI_CHAPTER_END.length
    }
    return AiBlocksResult(out, malformed)
}

/**
 * 从文本中解析全部章节梗概区块（支持一段文本里含多个标记块）。
 * 解析失败的标记块会被跳过；一个都没有时返回空列表。
 */
fun parseAiChapterBlocks(text: String): List<AiChapterBlock> = parseAiChapterBlocksResult(text).blocks


/** 大纲区块的起止标记：AI 回答 / 粘贴文本中出现该标记即视为可导入内容 */
const val AI_OUTLINE_BEGIN = "<<<AI_OUTLINE_JSON_V1>>>"
const val AI_OUTLINE_END = "<<<END_AI_OUTLINE_JSON_V1>>>"

/** 「复制工具提示词」复制的完整提示词（勾选工具后也会随请求自动发给模型） */
const val AI_OUTLINE_TOOL_PROMPT: String = """当你需要生成可导入的人设或大纲时，输出以下格式：

<<<AI_OUTLINE_JSON_V1>>>
{
  "blocks": [
    {
      "type": "标签",
      "name": "显示名称",
      "fields": [
        {"key": "字段名", "value": "字段值"}
      ]
    }
  ]
}
<<<END_AI_OUTLINE_JSON_V1>>>
"type"指的是所属大类，如人设，主角人设，X家人设，世界观，故事线分类等
"name"指的是该表名称，如姓名，主线等
 "fields": [
        {"key": "字段名", "value": "字段值"}
 ]
是表具体内容
字段名不要包含特殊符号。字段值中如果包含引号或换行，必须按 JSON 规则转义。"""

// ---------- 工具调用定义（function calling） ----------

val AI_IMPORT_OUTLINE_TOOL_SPEC: AiToolSpec = AiToolSpec(
    name = AI_TOOL_IMPORT_OUTLINE_NAME,
    description = "把本轮内容中的人设 / 世界观等结构化数据写入作品大纲。回答请照常输出自然语言，" +
        "不要因为本工具把回答改写成 JSON；仅当本轮内容确实包含可写入的人设 / 世界观时调用。",
    parametersJson = """
        {
          "type": "object",
          "properties": {
            "blocks": {
              "type": "array",
              "description": "要写入作品大纲的区块列表",
              "items": {
                "type": "object",
                "properties": {
                  "type": {"type": "string", "description": "所属大类，如 人设、主角人设、世界观、故事线"},
                  "name": {"type": "string", "description": "条目显示名称，如 姓名、主线"},
                  "fields": {
                    "type": "array",
                    "description": "条目字段",
                    "items": {
                      "type": "object",
                      "properties": {
                        "key": {"type": "string", "description": "字段名，不要包含特殊符号"},
                        "value": {"type": "string", "description": "字段值，引号与换行按 JSON 规则转义"}
                      },
                      "required": ["key", "value"]
                    }
                  }
                },
                "required": ["type", "name", "fields"]
              }
            }
          },
          "required": ["blocks"]
        }
    """.trimIndent()
)

val AI_IMPORT_CHAPTER_TOOL_SPEC: AiToolSpec = AiToolSpec(
    name = AI_TOOL_IMPORT_CHAPTER_NAME,
    description = "把本轮内容中的章节名与章节梗概批量写入书籍分卷与章节。回答请照常输出自然语言，" +
        "不要因为本工具把回答改写成 JSON；仅当本轮内容确实包含章节名 + 梗概时调用。",
    parametersJson = """
        {
          "type": "object",
          "properties": {
            "chapters": {
              "type": "array",
              "description": "要写入的章节列表",
              "items": {
                "type": "object",
                "properties": {
                  "volume": {"type": "string", "description": "分卷名，没有可写空字符串"},
                  "chapterName": {"type": "string", "description": "章节名"},
                  "summary": {"type": "string", "description": "章节梗概"},
                  "order": {"type": "integer", "description": "章节在本卷的序号"}
                },
                "required": ["chapterName", "summary"]
              }
            }
          },
          "required": ["chapters"]
        }
    """.trimIndent()
)

/** 「复制工具定义」内容：工具名 + 用途 + 参数 Schema，文末附旧标记格式作为兼容提示 */
fun toolDefinitionText(spec: AiToolSpec, legacyPrompt: String): String = buildString {
    appendLine("工具名：${spec.name}")
    appendLine("用途：${spec.description}")
    appendLine("参数（JSON Schema）：")
    appendLine(spec.parametersJson)
    appendLine()
    appendLine("如果你的客户端不支持工具调用，也可按下面的旧标记格式输出：")
    appendLine(legacyPrompt)
}

val AI_OUTLINE_TOOL_DEFINITION_TEXT: String =
    toolDefinitionText(AI_IMPORT_OUTLINE_TOOL_SPEC, AI_OUTLINE_TOOL_PROMPT)

val AI_CHAPTER_TOOL_DEFINITION_TEXT: String =
    toolDefinitionText(AI_IMPORT_CHAPTER_TOOL_SPEC, AI_CHAPTER_TOOL_PROMPT)

/** 一个可导入的大纲区块：标签 + 显示名称 + 字段列表 */
data class AiOutlineBlock(
    val type: String,
    val name: String,
    val fields: List<Pair<String, String>>
)

@Serializable
private data class AiOutlineFieldDto(val key: String = "", val value: String = "")

@Serializable
private data class AiOutlineBlockDto(
    val type: String = "",
    val name: String = "",
    val fields: List<AiOutlineFieldDto> = emptyList()
)

@Serializable
private data class AiOutlinePayload(val blocks: List<AiOutlineBlockDto> = emptyList())

private val aiOutlineJson = Json { ignoreUnknownKeys = true }

private fun AiOutlineBlockDto.toBlock(): AiOutlineBlock? =
    if (type.isBlank() || name.isBlank()) null
    else AiOutlineBlock(
        type = type.trim(),
        name = name.trim(),
        fields = fields.map { it.key.trim() to it.value }.filter { it.first.isNotBlank() }
    )

/** 解析工具调用参数（import_outline 的 arguments JSON）；格式非法时返回 null */
fun parseAiOutlineBlocksJson(text: String): List<AiOutlineBlock>? {
    val blocks = runCatching {
        aiOutlineJson.decodeFromString(AiOutlinePayload.serializer(), text).blocks
    }.getOrNull() ?: return null
    return blocks.mapNotNull { it.toBlock() }
}

/**
 * 从文本中解析全部大纲区块，并额外报告是否存在畸形区块。
 * 与 [parseAiOutlineBlocks] 的区别：不静默吞掉解析失败，而是通过 [AiBlocksResult.malformed] 暴露。
 */
fun parseAiOutlineBlocksResult(text: String): AiBlocksResult<AiOutlineBlock> {
    val out = mutableListOf<AiOutlineBlock>()
    var malformed = false
    var from = 0
    while (true) {
        val begin = text.indexOf(AI_OUTLINE_BEGIN, from)
        if (begin < 0) break
        val jsonStart = begin + AI_OUTLINE_BEGIN.length
        val end = text.indexOf(AI_OUTLINE_END, jsonStart)
        if (end < 0) {
            // 有开始标记却没有结束标记 → 畸形
            malformed = true
            break
        }
        val parsed = parseAiOutlineBlocksJson(text.substring(jsonStart, end).trim())
        if (parsed == null) {
            // 标记存在但 JSON 解析失败 → 畸形
            malformed = true
        } else {
            out.addAll(parsed)
        }
        from = end + AI_OUTLINE_END.length
    }
    return AiBlocksResult(out, malformed)
}

/**
 * 从文本中解析全部大纲区块（支持一段文本里含多个标记块）。
 * 解析失败的标记块会被跳过；一个都没有时返回空列表。
 */
fun parseAiOutlineBlocks(text: String): List<AiOutlineBlock> = parseAiOutlineBlocksResult(text).blocks

// ---------- 工具调用被写成正文（文本泄漏）时的还原 ----------

/** [extractLeakedToolCalls] 的结果 */
data class LeakedToolCalls(
    /** 从正文还原出来的工具调用 */
    val calls: List<AiToolCall> = emptyList(),
    /** 剔除泄漏片段后的正文 */
    val cleanedContent: String = "",
    /** 正文里是否出现了工具调用语法（即使没能还原也要让用户知情） */
    val syntaxDetected: Boolean = false
)

private val LEAKED_FUNCTION_TAG =
    Regex("""<\s*function\s*[=:]\s*([A-Za-z0-9_.\-]+)\s*>""", RegexOption.IGNORE_CASE)
private val LEAKED_FUNCTION_END = Regex("""</\s*function\s*>""", RegexOption.IGNORE_CASE)
private val LEAKED_NAME_FIELD = Regex("""["']?(?:name|tool)["']?\s*:\s*["']([A-Za-z0-9_.\-]+)["']""")
private val LEAKED_ARGUMENTS_FIELD = Regex("""["']?arguments["']?\s*:\s*""")

/**
 * 部分供应商（含部分 DeepSeek 兼容实现）会把工具调用写进正文而不是返回 `tool_calls`，
 * 常见形式：
 *  - `<function=import_chapters>{"chapters":[...]}</function>`
 *  - `[{"name":"import_chapters","arguments":{...}}]`
 * 这里把能识别的调用还原成 [AiToolCall]，并把泄漏片段从正文中剔除，避免用户看到 `</function>` 之类的残渣。
 * [allowedNames] 只接受本轮真正提供给模型的工具名，避免误匹配正文里的普通 JSON。
 */
fun extractLeakedToolCalls(content: String, allowedNames: Set<String>): LeakedToolCalls {
    if (content.isBlank() || allowedNames.isEmpty()) {
        return LeakedToolCalls(cleanedContent = content)
    }
    val spans = mutableListOf<IntRange>()
    val calls = mutableListOf<AiToolCall>()

    // 1) <function=name>{...}</function>
    var from = 0
    while (true) {
        val tag = LEAKED_FUNCTION_TAG.find(content, from) ?: break
        val name = tag.groupValues[1]
        val bodyStart = tag.range.last + 1
        val endTag = LEAKED_FUNCTION_END.find(content, bodyStart)
        val bodyEnd = endTag?.range?.first ?: content.length
        val spanEnd = endTag?.range?.last ?: (content.length - 1)
        from = spanEnd + 1
        if (name !in allowedNames) continue
        val range = firstJsonRange(content, bodyStart, bodyEnd)
        if (range != null) {
            calls += AiToolCall(
                id = "leaked_${calls.size + 1}",
                name = name,
                arguments = content.substring(range)
            )
            // 有结束标签时删到结束标签；没有时只删到参数末尾，避免误删后面的正文
            spans += tag.range.first..(if (endTag != null) spanEnd else range.last)
        }
    }

    // 2) 纯 JSON 形式：{"name":"import_chapters","arguments":{...}}
    val masked = content.toCharArray()
    spans.forEach { range -> for (i in range) { if (i in masked.indices) masked[i] = ' ' } }
    val scan = String(masked)
    var scanFrom = 0
    while (true) {
        val nameMatch = LEAKED_NAME_FIELD.find(scan, scanFrom) ?: break
        scanFrom = nameMatch.range.last + 1
        val name = nameMatch.groupValues[1]
        if (name !in allowedNames) continue
        val argsStart = LEAKED_ARGUMENTS_FIELD.find(scan, nameMatch.range.last + 1) ?: continue
        if (argsStart.range.first - nameMatch.range.last > 200) continue
        val valueRange = firstJsonRange(scan, argsStart.range.last + 1) ?: continue
        val rawValue = scan.substring(valueRange)
        val arguments = if (rawValue.startsWith("\"")) {
            runCatching { aiOutlineJson.parseToJsonElement(rawValue).jsonPrimitive.content }
                .getOrElse { rawValue }
        } else rawValue
        val wrapperStart = scan.lastIndexOf('{', nameMatch.range.first).takeIf { it >= 0 } ?: nameMatch.range.first
        calls += AiToolCall(
            id = "leaked_${calls.size + 1}",
            name = name,
            arguments = arguments
        )
        spans += wrapperStart..valueRange.last
        scanFrom = valueRange.last + 1
    }

    val cleaned = removeSpans(content, spans)
    val syntaxDetected = content.contains("<function", ignoreCase = true) ||
        content.contains("</function>", ignoreCase = true) ||
        (content.contains("\"arguments\"", ignoreCase = true) &&
            content.contains("\"name\"", ignoreCase = true))
    return LeakedToolCalls(calls, cleaned, syntaxDetected)
}

/** 从 [start] 起找第一个完整的 JSON 对象 / 数组 / 字符串，返回其闭区间下标 */
private fun firstJsonRange(text: String, start: Int, limit: Int = text.length): IntRange? {
    var i = start
    while (i < limit && text[i].isWhitespace()) i++
    if (i >= limit) return null
    val begin = i
    when (text[i]) {
        '{', '[' -> {
            var depth = 0
            var inString = false
            var escaped = false
            while (i < limit) {
                val c = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return begin..i
                    }
                }
                i++
            }
            return null
        }

        '"' -> {
            var escaped = false
            i++
            while (i < limit) {
                val c = text[i]
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> return begin..i
                }
                i++
            }
            return null
        }

        else -> return null
    }
}

/** 删除命中区间，并清掉删除后残留的孤行标点（如 `</function>` 被删后剩下的 `}]`） */
private fun removeSpans(text: String, spans: List<IntRange>): String {
    if (spans.isEmpty()) return text
    val merged = spans.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { acc, range ->
        val last = acc.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            acc[acc.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            acc += range
        }
        acc
    }
    val sb = StringBuilder(text)
    merged.asReversed().forEach { range ->
        if (range.first in 0..sb.lastIndex) {
            sb.delete(range.first, minOf(range.last, sb.lastIndex) + 1)
        }
    }
    val lines = sb.toString().replace("\r\n", "\n").split("\n")
        .filterNot { line -> line.trim().let { it.isNotEmpty() && it.all { c -> c in "[]{}<>/\"',:" } } }
    var out = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    // 正文末尾遗留的 `}]` / `}` 之类残渣一并去掉
    out = out.trimEnd(' ', '\t', '\n', ']', '}', ',', ':')
    return if (out.all { it in "[]{}<>/\"',: \n\t" }) "" else out
}

// ---------- 手动导入的类型倾向判断 ----------

/** 回答内容的导入类型倾向 */
data class ImportKindHint(val chapterLike: Boolean, val outlineLike: Boolean) {
    /** 只像章节梗概（没检测到人设 / 设定内容）：手动导入时只应提供「章节梗概」 */
    val chapterOnly: Boolean get() = chapterLike && !outlineLike
}

private val CHAPTER_TITLE_PATTERN =
    Regex("""第\s*[0-9０-９一二三四五六七八九十百千零两]+\s*[章节回]""")
private val OUTLINE_WORDS = listOf(
    "人设", "设定", "世界观", "人物", "角色", "主角", "姓名", "性别", "身份", "性格", "背景", "大纲", "势力", "能力"
)

/** 判断一条回答更像「章节梗概」还是「大纲设定」，供手动导入时决定展示哪些入口 */
fun detectImportKindHint(content: String): ImportKindHint {
    val chapterHits = CHAPTER_TITLE_PATTERN.findAll(content).count()
    val chapterLike = content.contains(AI_CHAPTER_BEGIN) || chapterHits >= 2 ||
        (chapterHits >= 1 && (content.contains("梗概") || content.contains("摘要")))
    val outlineLike = content.contains(AI_OUTLINE_BEGIN) || OUTLINE_WORDS.any { content.contains(it) }
    return ImportKindHint(chapterLike = chapterLike, outlineLike = outlineLike)
}

/** 这条「大纲」条目其实是不是章节梗概（手动导入时禁止把章节梗概写成大纲） */
fun isChapterLikeOutlineBlock(block: AiOutlineBlock): Boolean =
    CHAPTER_TITLE_PATTERN.containsMatchIn(block.name) || block.type.contains("章节")

