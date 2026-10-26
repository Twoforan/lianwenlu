package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 模型输出的解析结果：成功（含规范化后的 JSON）或明确失败 */
sealed interface StyleAnalysisParseResult {
    data class Success(val analysis: StyleAnalysis, val json: String) : StyleAnalysisParseResult
    data class Failure(val message: String) : StyleAnalysisParseResult
}

/**
 * 文风分析 JSON 的解析、校验与规范化。
 *
 * 解析规则（明确、可测试，不做「删掉点字符就当成功」的兜底）：
 * 1. 输出中若出现 Markdown 代码围栏，只取第一个完整围栏内的内容；
 * 2. 否则取第一个 `{` 到与之配对的 `}` 之间的内容，忽略前后的解释文字；
 * 3. 必须是 JSON 对象，必填字段齐全、类型正确、内容非空且不空泛；
 * 4. 允许模型输出未知字段，但写库前会丢弃（只保留 [StyleAnalysis] 定义的字段）；
 * 5. 规范化后的 JSON 若超过档案字段上限，直接判为失败，交由用户重试而不是静默截断。
 */
object StyleAnalysisCodec {

    private val encodeJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private val parseJson = Json { ignoreUnknownKeys = true }

    private const val MAX_ITEMS_PER_SECTION = 12
    private const val MIN_ITEM_CHARS = 6
    private const val MAX_ITEM_CHARS = 400
    private const val MIN_OVERVIEW_CHARS = 10
    private const val MAX_OVERVIEW_CHARS = 800
    private const val MIN_EVIDENCE_CHARS = 4
    private const val MAX_EVIDENCE_CHARS = 300
    private const val MAX_EVIDENCE_ITEMS = 20

    /** 只给这些空泛评价、没有可操作解释的条目会被拒绝 */
    private val GENERIC_PHRASES = setOf(
        "文风优美", "文笔优美", "语言细腻", "文字细腻", "描写生动", "描写细腻",
        "情感丰富", "节奏流畅", "叙述流畅", "语言流畅", "风格独特", "文采斐然"
    )

    private val SECTION_LABELS = listOf(
        "narration" to "叙述方式",
        "sentenceRhythm" to "句式与节奏",
        "diction" to "词汇与表达",
        "description" to "描写方法",
        "paragraphStructure" to "段落组织",
        "dialogue" to "对话特征",
        "emotion" to "情绪表达",
        "transition" to "场景与叙事衔接"
    )

    /** 解析模型原始输出；任何结构问题都返回 [StyleAnalysisParseResult.Failure] */
    fun parse(raw: String): StyleAnalysisParseResult {
        val text = raw.trim()
        if (text.isEmpty()) return StyleAnalysisParseResult.Failure("模型返回为空")
        val candidate = extractJsonCandidate(text)
            ?: return StyleAnalysisParseResult.Failure("未能从输出中定位到完整的 JSON 对象")
        val root = runCatching { parseJson.parseToJsonElement(candidate) }.getOrNull()
            ?: return StyleAnalysisParseResult.Failure("分析结果不是合法的 JSON")
        val obj = root as? JsonObject
            ?: return StyleAnalysisParseResult.Failure("分析结果必须是 JSON 对象")
        return try {
            val analysis = toAnalysis(obj)
            val json = encode(analysis)
            val bytes = json.toByteArray(Charsets.UTF_8).size
            if (bytes > StyleProfile.MAX_ANALYSIS_JSON_BYTES) {
                throw Invalid(
                    "分析结果超过 ${StyleProfile.MAX_ANALYSIS_JSON_BYTES / 1024}KB 上限（当前 $bytes 字节），请重试"
                )
            }
            StyleAnalysisParseResult.Success(analysis, json)
        } catch (e: Invalid) {
            StyleAnalysisParseResult.Failure(e.message ?: "分析结果格式不合法")
        }
    }

    /** 把已校验的分析结果编码为写库用的紧凑 JSON */
    fun encode(analysis: StyleAnalysis): String =
        encodeJson.encodeToString(StyleAnalysis.serializer(), analysis)

    /**
     * 校验一个已构造的分析结果：返回 null 表示符合协议，否则返回具体错误说明。
     * 走与模型输出相同的解析路径，确保手工编辑后的结果与 F4 协议完全一致。
     */
    fun validate(analysis: StyleAnalysis): String? =
        when (val result = parse(encode(analysis))) {
            is StyleAnalysisParseResult.Success -> null
            is StyleAnalysisParseResult.Failure -> result.message
        }

    private fun toAnalysis(obj: JsonObject): StyleAnalysis {
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw Invalid("缺少字段 schemaVersion 或类型不是整数")
        if (version != STYLE_ANALYSIS_SCHEMA_VERSION) {
            throw Invalid("不支持的分析结果格式版本：$version（当前支持 $STYLE_ANALYSIS_SCHEMA_VERSION）")
        }

        val overview = obj.string("overview", "整体风格")
        if (overview.length < MIN_OVERVIEW_CHARS) {
            throw Invalid("字段 overview（整体风格）过于简略，请重试")
        }
        if (overview.length > MAX_OVERVIEW_CHARS) {
            throw Invalid("字段 overview（整体风格）超过 $MAX_OVERVIEW_CHARS 字")
        }

        val sections = SECTION_LABELS.associate { (key, label) -> key to readSection(obj, key, label) }

        val guidelines = readSection(obj, "writingGuidelines", "可执行的仿写指导", minItems = 2)
        val avoidances = readSection(obj, "avoidances", "应避免的写法", minItems = 1)
        val evidence = readEvidence(obj)

        return StyleAnalysis(
            schemaVersion = version,
            overview = overview,
            narration = sections.getValue("narration"),
            sentenceRhythm = sections.getValue("sentenceRhythm"),
            diction = sections.getValue("diction"),
            description = sections.getValue("description"),
            paragraphStructure = sections.getValue("paragraphStructure"),
            dialogue = sections.getValue("dialogue"),
            emotion = sections.getValue("emotion"),
            transition = sections.getValue("transition"),
            writingGuidelines = guidelines,
            avoidances = avoidances,
            evidenceAndConfidence = evidence
        )
    }

    private fun readSection(
        obj: JsonObject,
        key: String,
        label: String,
        minItems: Int = 1
    ): List<String> {
        val element = obj[key] ?: throw Invalid("缺少字段 $key（$label）")
        val array = element as? JsonArray ?: throw Invalid("字段 $key（$label）必须是字符串数组")
        if (array.isEmpty()) throw Invalid("字段 $key（$label）不能为空")
        if (array.size < minItems) throw Invalid("字段 $key（$label）至少需要 $minItems 条")
        if (array.size > MAX_ITEMS_PER_SECTION) {
            throw Invalid("字段 $key（$label）条目过多（最多 $MAX_ITEMS_PER_SECTION 条）")
        }
        return array.mapIndexed { index, item ->
            val value = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?: throw Invalid("字段 $key（$label）第 ${index + 1} 条必须是字符串")
            if (value.trimEnd('。', '；', ';', '.') in GENERIC_PHRASES) {
                throw Invalid("字段 $key（$label）第 ${index + 1} 条只有空泛评价：$value")
            }
            if (value.length < MIN_ITEM_CHARS) {
                throw Invalid("字段 $key（$label）第 ${index + 1} 条过于简略：$value")
            }
            if (value.length > MAX_ITEM_CHARS) {
                throw Invalid("字段 $key（$label）第 ${index + 1} 条超过 $MAX_ITEM_CHARS 字")
            }
            value
        }
    }

    private fun readEvidence(obj: JsonObject): List<StyleEvidence> {
        val element = obj["evidenceAndConfidence"]
            ?: throw Invalid("缺少字段 evidenceAndConfidence（证据与可信度）")
        val array = element as? JsonArray
            ?: throw Invalid("字段 evidenceAndConfidence（证据与可信度）必须是数组")
        if (array.isEmpty()) throw Invalid("字段 evidenceAndConfidence（证据与可信度）不能为空")
        if (array.size > MAX_EVIDENCE_ITEMS) {
            throw Invalid("字段 evidenceAndConfidence（证据与可信度）条目过多")
        }
        return array.mapIndexed { index, item ->
            val entry = item as? JsonObject
                ?: throw Invalid("evidenceAndConfidence 第 ${index + 1} 条必须是对象")
            val dimension = entry.string("dimension", "证据维度")
            if (dimension !in StyleAnalysisDimensions.EVIDENCE_KEYS) {
                throw Invalid("evidenceAndConfidence 第 ${index + 1} 条的 dimension 不合法：$dimension")
            }
            val confidence = entry.string("confidence", "可信度")
            if (confidence !in StyleConfidence.ALL) {
                throw Invalid("evidenceAndConfidence 第 ${index + 1} 条的 confidence 不合法：$confidence")
            }
            val evidence = entry.string("evidence", "证据")
            if (evidence.length < MIN_EVIDENCE_CHARS) {
                throw Invalid("evidenceAndConfidence 第 ${index + 1} 条证据过于简略")
            }
            if (evidence.length > MAX_EVIDENCE_CHARS) {
                throw Invalid("evidenceAndConfidence 第 ${index + 1} 条证据超过 $MAX_EVIDENCE_CHARS 字")
            }
            StyleEvidence(dimension = dimension, confidence = confidence, evidence = evidence)
        }
    }

    private fun JsonObject.string(key: String, label: String): String {
        val value = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?: throw Invalid("缺少字段 $key（$label）或类型不是字符串")
        if (value.isEmpty()) throw Invalid("字段 $key（$label）不能为空")
        return value
    }

    /**
     * 定位待解析的 JSON 文本：优先取第一个完整 Markdown 代码围栏的内容，
     * 没有围栏时取第一个花括号到配对花括号的片段（忽略前后解释文字）。
     */
    private fun extractJsonCandidate(text: String): String? {
        val fenceStart = text.indexOf("```")
        if (fenceStart >= 0) {
            val contentStart = text.indexOf('\n', fenceStart + 3)
            if (contentStart < 0) return null
            val fenceEnd = text.indexOf("```", contentStart + 1)
            if (fenceEnd < 0) return null
            return text.substring(contentStart + 1, fenceEnd).trim().ifEmpty { null }
        }
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private class Invalid(message: String) : Exception(message)
}
