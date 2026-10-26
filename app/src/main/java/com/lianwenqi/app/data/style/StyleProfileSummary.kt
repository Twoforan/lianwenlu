package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 档案在列表 / 详情中使用的只读摘要。
 *
 * 只读取已保存的数据，不调用模型、不生成新的概述：
 * - [overview] 只在分析结果与当前协议版本一致且内容完整时给出，否则为 null（界面显示「暂无概述」）；
 * - [recognizable] 表示分析结果能被当前版本完整识别（可进入结构化编辑）；
 * - [unknownFields] 记录当前版本不认识的顶层字段，保存编辑内容前需用户确认，避免静默丢弃。
 */
data class StyleProfileSummary(
    val overview: String?,
    val schemaVersion: Int?,
    val recognizable: Boolean,
    val unknownFields: List<String>,
    val sourceLabel: String,
    val modelName: String,
    val sampleNames: List<String>,
    val analyzedSamples: Int?,
    val includedChars: Int?,
    val strategyLabel: String?
)

/** 列表中的一行：档案实体 + 从它派生出的只读摘要 */
data class StyleProfileRow(val profile: StyleProfile, val summary: StyleProfileSummary)

/** 文风库列表状态：区分读取中 / 有数据 / 无数据 / 读取失败 */
data class StyleLibraryState(
    val loading: Boolean = true,
    val rows: List<StyleProfileRow> = emptyList(),
    val error: String? = null
)

/**
 * 宽松读取档案摘要（纯 Kotlin，便于单元测试）。
 * 解析失败、版本不认识、字段缺失都只降级为「暂无概述 / 不可编辑」，绝不抛异常影响列表。
 */
object StyleProfileSummaryReader {

    private val json = Json { ignoreUnknownKeys = true }

    /** [StyleAnalysis] 的顶层字段；其余字段视为当前版本不认识的内容 */
    private val KNOWN_FIELDS = setOf(
        "schemaVersion",
        "overview",
        "narration",
        "sentenceRhythm",
        "diction",
        "description",
        "paragraphStructure",
        "dialogue",
        "emotion",
        "transition",
        "writingGuidelines",
        "avoidances",
        "evidenceAndConfidence"
    )

    private const val MAX_SAMPLE_NAMES = 5

    fun read(profile: StyleProfile): StyleProfileSummary {
        val analysisObj = runCatching {
            json.parseToJsonElement(profile.analysisJson) as? JsonObject
        }.getOrNull()
        val version = (analysisObj?.get("schemaVersion") as? JsonPrimitive)?.intOrNull
        val recognized = version == STYLE_ANALYSIS_SCHEMA_VERSION &&
            StyleAnalysisCodec.parse(profile.analysisJson) is StyleAnalysisParseResult.Success

        val overview = if (recognized) {
            (analysisObj?.get("overview") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        val sourceMeta = runCatching {
            json.parseToJsonElement(profile.sourceMeta) as? JsonObject
        }.getOrNull()
        val sampling = sourceMeta?.get("sampling") as? JsonObject
        val metaModel = sourceMeta?.get("model") as? JsonObject

        return StyleProfileSummary(
            overview = overview,
            schemaVersion = version,
            recognizable = recognized,
            unknownFields = analysisObj?.keys
                ?.filterNot { it in KNOWN_FIELDS }
                ?.sorted()
                .orEmpty(),
            sourceLabel = profile.sourceTypeEnum.label,
            modelName = profile.modelName.trim().ifBlank {
                metaModel?.string("displayName") ?: metaModel?.string("identifier") ?: ""
            },
            sampleNames = (sourceMeta?.get("samples") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.string("name") }
                ?.take(MAX_SAMPLE_NAMES)
                .orEmpty(),
            analyzedSamples = (sampling?.get("analyzedSamples") as? JsonPrimitive)?.intOrNull,
            includedChars = (sampling?.get("includedChars") as? JsonPrimitive)?.intOrNull,
            strategyLabel = (sampling?.get("strategy") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.let { key -> StyleSamplingStrategy.entries.firstOrNull { it.name == key }?.label }
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
}
