package com.lianwenqi.app.util

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
