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
 *
 * 版本兼容：v1（档位可信度）与 v2（每特征可信度区间）都可读取。
 * v1 的 high/medium/low 只会保留在 [StyleEvidence.confidence]，绝不换算成区间。
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
    private const val MAX_EVIDENCE_ITEMS = 40
    private const val MAX_FEATURE_CHARS = 200
    private const val MAX_NOTE_CHARS = 300

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
     * 走与模型输出相同的解析路径，确保手工编辑后的结果与协议完全一致。
     */
    fun validate(analysis: StyleAnalysis): String? =
        when (val result = parse(encode(analysis))) {
            is StyleAnalysisParseResult.Success -> null
            is StyleAnalysisParseResult.Failure -> result.message
        }

    private fun toAnalysis(obj: JsonObject): StyleAnalysis {
        val version = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
            ?: throw Invalid("缺少字段 schemaVersion 或类型不是整数")
        if (version !in SUPPORTED_STYLE_ANALYSIS_VERSIONS) {
            throw Invalid(
                "不支持的分析结果格式版本：$version（支持 ${SUPPORTED_STYLE_ANALYSIS_VERSIONS.sorted()}）"
            )
        }

        val overview = obj.string("overview", "整体风格")
        if (overview.length < MIN_OVERVIEW_CHARS) {
            throw Invalid("字段 overview（整体风格）过于简略，请重试")
        }
        if (overview.length > MAX_OVERVIEW_CHARS) {
            throw Invalid("字段 overview（整体风格）超过 $MAX_OVERVIEW_CHARS 字")
        }

        val sections = SECTION_LABELS.associate { (key, label) -> key to readSection(obj, key, label) }
        val avoidances = readSection(obj, "avoidances", "应避免的写法", minItems = 1)

        return if (version == 1) {
            legacyAnalysis(obj, version, overview, sections, avoidances)
        } else {
            v2Analysis(obj, version, overview, sections, avoidances)
        }
    }

    // ---------- v1：档位可信度，仅保留原始等级 ----------

    private fun legacyAnalysis(
        obj: JsonObject,
        version: Int,
        overview: String,
        sections: Map<String, List<String>>,
        avoidances: List<String>
    ): StyleAnalysis {
        val guidelines = readSection(obj, "writingGuidelines", "可执行的仿写指导", minItems = 2)
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
            evidenceAndConfidence = readLegacyEvidence(obj),
            guidelineConfidence = emptyList()
        )
    }

    private fun readLegacyEvidence(obj: JsonObject): List<StyleEvidence> {
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
            StyleEvidence(
                dimension = dimension,
                confidence = confidence,
                evidence = evidence
            )
        }
    }

    // ---------- v2：每条特征一条可信度区间 ----------

    private fun v2Analysis(
        obj: JsonObject,
        version: Int,
        overview: String,
        sections: Map<String, List<String>>,
        avoidances: List<String>
    ): StyleAnalysis {
        val (guidelines, guidelineConfidence) = readV2Guidelines(obj)
        val evidence = readV2Evidence(obj)
        val covered = evidence.map { it.dimension }.toSet()
        val missing = StyleAnalysisDimensions.REQUIRED_EVIDENCE_KEYS - covered
        if (missing.isNotEmpty()) {
            throw Invalid(
                "evidenceAndConfidence 必须覆盖 ${missing.sorted().joinToString("、")} 三个维度"
            )
        }
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
            evidenceAndConfidence = evidence,
            guidelineConfidence = guidelineConfidence
        )
    }

    /**
     * v2 的写作指导：既接受「字符串数组」（手工编辑 / 旧结构），
     * 也接受提示词约定的「对象数组」（rule + 可信度字段）。
     */
    private fun readV2Guidelines(
        obj: JsonObject
    ): Pair<List<String>, List<StyleGuidelineConfidence>> {
        val label = "可执行的仿写指导"
        val element = obj["writingGuidelines"] ?: throw Invalid("缺少字段 writingGuidelines（$label）")
        val array = element as? JsonArray ?: throw Invalid("字段 writingGuidelines（$label）必须是数组")
        if (array.size < 2) throw Invalid("字段 writingGuidelines（$label）至少需要 2 条")
        if (array.size > MAX_ITEMS_PER_SECTION) {
            throw Invalid("字段 writingGuidelines（$label）条目过多（最多 $MAX_ITEMS_PER_SECTION 条）")
        }
        val rules = mutableListOf<String>()
        val confidences = mutableListOf<StyleGuidelineConfidence>()
        array.forEachIndexed { index, item ->
            val prefix = "writingGuidelines（$label）第 ${index + 1} 条"
            when (item) {
                is JsonPrimitive -> {
                    val rule = item.takeIf { it.isString }?.content?.trim()
                        ?: throw Invalid("$prefix 必须是字符串或对象")
                    rules += validateItemText(rule, prefix)
                }
                is JsonObject -> {
                    val rule = validateItemText(item.string("rule", "$prefix 规则"), prefix)
                    val detail = readConfidenceDetail(item, prefix)
                    rules += rule
                    confidences += StyleGuidelineConfidence(
                        rule = rule,
                        confidenceInterval = detail.interval,
                        evidenceQuality = detail.quality,
                        sampleCoverage = detail.coverage,
                        evidence = detail.evidence,
                        applicability = detail.applicability,
                        limitations = detail.limitations
                    )
                }
                else -> throw Invalid("$prefix 必须是字符串或对象")
            }
        }
        return rules to (confidences + readStoredGuidelineConfidence(obj, rules))
            .distinctBy { it.rule }
    }

    /** 读取本应用规范化后额外保存的 guidelineConfidence（兼容旧版手工编辑产生的字符串数组） */
    private fun readStoredGuidelineConfidence(
        obj: JsonObject,
        rules: List<String>
    ): List<StyleGuidelineConfidence> {
        val array = obj["guidelineConfidence"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val rule = (entry["rule"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it in rules } ?: return@mapNotNull null
            val detail = readConfidenceDetail(entry, "guidelineConfidence（$rule）")
            StyleGuidelineConfidence(
                rule = rule,
                confidenceInterval = detail.interval,
                evidenceQuality = detail.quality,
                sampleCoverage = detail.coverage,
                evidence = detail.evidence,
                applicability = detail.applicability,
                limitations = detail.limitations
            )
        }
    }

    private fun readV2Evidence(obj: JsonObject): List<StyleEvidence> {
        val element = obj["evidenceAndConfidence"]
            ?: throw Invalid("缺少字段 evidenceAndConfidence（证据与可信度）")
        val array = element as? JsonArray
            ?: throw Invalid("字段 evidenceAndConfidence（证据与可信度）必须是数组")
        if (array.isEmpty()) throw Invalid("字段 evidenceAndConfidence（证据与可信度）不能为空")
        if (array.size > MAX_EVIDENCE_ITEMS) {
            throw Invalid("字段 evidenceAndConfidence（证据与可信度）条目过多")
        }
        return array.mapIndexed { index, item ->
            val prefix = "evidenceAndConfidence 第 ${index + 1} 条"
            val entry = item as? JsonObject ?: throw Invalid("$prefix 必须是对象")
            val dimension = entry.string("dimension", "$prefix 维度")
            if (dimension !in StyleAnalysisDimensions.EVIDENCE_KEYS) {
                throw Invalid("$prefix 的 dimension 不合法：$dimension")
            }
            val feature = entry.string("feature", "$prefix 风格特征").let { raw ->
                if (raw.length < MIN_ITEM_CHARS) throw Invalid("$prefix 的 feature 过于简略")
                if (raw.length > MAX_FEATURE_CHARS) throw Invalid("$prefix 的 feature 超过 $MAX_FEATURE_CHARS 字")
                raw
            }
            val detail = readConfidenceDetail(entry, prefix)
            StyleEvidence(
                dimension = dimension,
                confidence = null,
                evidence = detail.evidence,
                feature = feature,
                confidenceInterval = detail.interval,
                evidenceQuality = detail.quality,
                sampleCoverage = detail.coverage,
                applicability = detail.applicability,
                limitations = detail.limitations
            )
        }
    }

    private data class Detail(
        val interval: StyleConfidenceInterval,
        val quality: String,
        val coverage: String,
        val evidence: String,
        val applicability: String,
        val limitations: String
    )

    /** 读取 v2 必备的可信度字段：区间、证据质量、样本覆盖度、证据；适用场景 / 局限可为空 */
    private fun readConfidenceDetail(entry: JsonObject, prefix: String): Detail {
        val intervalObj = entry["confidenceInterval"] as? JsonObject
            ?: throw Invalid("$prefix 缺少 confidenceInterval（可信度区间）")
        val lower = (intervalObj["lower"] as? JsonPrimitive)?.intOrNull
            ?: throw Invalid("$prefix 的 confidenceInterval.lower 必须是整数")
        val upper = (intervalObj["upper"] as? JsonPrimitive)?.intOrNull
            ?: throw Invalid("$prefix 的 confidenceInterval.upper 必须是整数")
        if (lower < 0 || upper > 100 || lower > upper) {
            throw Invalid("$prefix 的可信度区间必须满足 0 ≤ lower ≤ upper ≤ 100（当前 $lower～$upper）")
        }
        val quality = entry.string("evidenceQuality", "$prefix 证据质量")
        if (quality !in StyleEvidenceQuality.ALL) {
            throw Invalid("$prefix 的 evidenceQuality 不合法：$quality")
        }
        val coverage = entry.string("sampleCoverage", "$prefix 样本覆盖度")
        if (coverage !in StyleSampleCoverage.ALL) {
            throw Invalid("$prefix 的 sampleCoverage 不合法：$coverage")
        }
        val evidence = entry.string("evidence", "$prefix 证据")
        if (evidence.length < MIN_EVIDENCE_CHARS) throw Invalid("$prefix 的证据过于简略")
        if (evidence.length > MAX_EVIDENCE_CHARS) throw Invalid("$prefix 的证据超过 $MAX_EVIDENCE_CHARS 字")
        return Detail(
            interval = StyleConfidenceInterval(lower, upper),
            quality = quality,
            coverage = coverage,
            evidence = evidence,
            applicability = optionalNote(entry, "applicability", prefix, "适用场景"),
            limitations = optionalNote(entry, "limitations", prefix, "局限")
        )
    }

    private fun optionalNote(entry: JsonObject, key: String, prefix: String, label: String): String {
        val primitive = entry[key] as? JsonPrimitive
            ?: return ""
        if (!primitive.isString) throw Invalid("$prefix 的 $key（$label）必须是字符串")
        val value = primitive.content.trim()
        if (value.length > MAX_NOTE_CHARS) throw Invalid("$prefix 的 $key（$label）超过 $MAX_NOTE_CHARS 字")
        return value
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
            validateItemText(value, "字段 $key（$label）第 ${index + 1} 条")
        }
    }

    /** 条目文本的通用校验：非空泛、长度受控、不是空字符串 */
    private fun validateItemText(value: String, prefix: String): String {
        if (value.trimEnd('。', '；', ';', '.') in GENERIC_PHRASES) {
            throw Invalid("$prefix 只有空泛评价：$value")
        }
        if (value.length < MIN_ITEM_CHARS) throw Invalid("$prefix 过于简略：$value")
        if (value.length > MAX_ITEM_CHARS) throw Invalid("$prefix 超过 $MAX_ITEM_CHARS 字")
        return value
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
