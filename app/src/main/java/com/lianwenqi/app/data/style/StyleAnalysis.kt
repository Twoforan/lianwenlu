package com.lianwenqi.app.data.style

import kotlinx.serialization.Serializable

/** 文风分析结果的格式版本；与 Room 数据库版本无关，升级分析维度时递增 */
const val STYLE_ANALYSIS_SCHEMA_VERSION = 1

/**
 * 结构化文风分析结果（写入 [com.lianwenqi.app.data.entity.StyleProfile.analysisJson]）。
 *
 * 十个分析维度统一用字符串条目列表表达：每条应是对样本中具体表现的观察或可执行结论，
 * 而不是空泛评价。某项确实缺乏证据时，约定该维度只放一条以「证据不足：」开头的内容。
 */
@Serializable
data class StyleAnalysis(
    val schemaVersion: Int = STYLE_ANALYSIS_SCHEMA_VERSION,
    /** 整体风格特征 */
    val overview: String,
    /** 叙述方式：视角、人称、叙述距离、叙述者介入程度 */
    val narration: List<String>,
    /** 句式与节奏 */
    val sentenceRhythm: List<String>,
    /** 词汇与表达 */
    val diction: List<String>,
    /** 描写方法 */
    val description: List<String>,
    /** 段落组织与信息密度 */
    val paragraphStructure: List<String>,
    /** 对话特征 */
    val dialogue: List<String>,
    /** 情绪表达 */
    val emotion: List<String>,
    /** 场景与叙事衔接 */
    val transition: List<String>,
    /** 可执行的仿写指导 */
    val writingGuidelines: List<String>,
    /** 应避免的写法 */
    val avoidances: List<String>,
    /** 关键结论的证据与可信度 */
    val evidenceAndConfidence: List<StyleEvidence>
)

/** 单条证据说明：[dimension] 与分析字段名一致，[confidence] 取 [StyleConfidence] 中的值 */
@Serializable
data class StyleEvidence(
    val dimension: String,
    val confidence: String,
    val evidence: String
)

/** 可信度档位（与提示词约定一致，存英文键便于后续稳定解析） */
object StyleConfidence {
    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"
    const val INSUFFICIENT = "insufficient"

    val ALL: Set<String> = setOf(HIGH, MEDIUM, LOW, INSUFFICIENT)
}

/** 可参与证据标注的分析维度字段名（overview 为总结，不要求单列证据） */
object StyleAnalysisDimensions {
    val EVIDENCE_KEYS: Set<String> = setOf(
        "narration",
        "sentenceRhythm",
        "diction",
        "description",
        "paragraphStructure",
        "dialogue",
        "emotion",
        "transition"
    )
}
