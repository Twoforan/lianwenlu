package com.lianwenqi.app.data.style

import kotlinx.serialization.Serializable

/** 文风分析结果的当前格式版本；与 Room 数据库版本无关，升级分析协议时递增 */
const val STYLE_ANALYSIS_SCHEMA_VERSION = 2

/**
 * 仍可读取的历史格式版本。
 * v1 只保存 single 档位可信度（high/medium/low/insufficient），没有任何区间信息；
 * 读取时原样保留档位，绝不换算成 0～100 的区间。
 */
val SUPPORTED_STYLE_ANALYSIS_VERSIONS: Set<Int> = setOf(1, STYLE_ANALYSIS_SCHEMA_VERSION)

/**
 * 结构化文风分析结果（写入 [com.lianwenqi.app.data.entity.StyleProfile.analysisJson]）。
 *
 * 十个分析维度统一用字符串条目列表表达：每条应是对样本中具体表现的观察或可执行结论，
 * 而不是空泛评价。某项确实缺乏证据时，约定该维度只放一条以「证据不足：」开头的内容。
 *
 * v2 起，每条具体风格特征都在 [evidenceAndConfidence] 中带自己的可信度区间；
 * 仿写指导的可信度放在 [guidelineConfidence]，与 [writingGuidelines] 的条目按 rule 对应。
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
    /** 关键结论的证据与可信度（v2 每条特征一条；v1 每个维度一条档位） */
    val evidenceAndConfidence: List<StyleEvidence>,
    /** 仿写指导的可信度（v2）；按 rule 与 [writingGuidelines] 对应，v1 为空 */
    val guidelineConfidence: List<StyleGuidelineConfidence> = emptyList()
)

/**
 * 单条特征的可信度信息。
 *
 * [confidence] 仅在 v1 数据中存在；v2 使用 [confidenceInterval]，两者不会被互相换算。
 * [feature] 是具体风格特征本身，不是维度名。
 */
@Serializable
data class StyleEvidence(
    val dimension: String,
    /** v1 档位：high / medium / low / insufficient；v2 为 null */
    val confidence: String? = null,
    val evidence: String,
    /** v2 的具体风格特征；v1 为空 */
    val feature: String = "",
    /** v2 可信度区间；v1 为 null（缺失，不做猜测性换算） */
    val confidenceInterval: StyleConfidenceInterval? = null,
    /** v2 证据质量：strong / moderate / weak / insufficient */
    val evidenceQuality: String? = null,
    /** v2 样本覆盖度：broad / partial / narrow / insufficient */
    val sampleCoverage: String? = null,
    /** 适用场景；没有明显限制时为空 */
    val applicability: String = "",
    /** 判断局限；没有明显局限时为空字符串 */
    val limitations: String = ""
)

/** 单条仿写指导的可信度信息（v2）；[rule] 与 [StyleAnalysis.writingGuidelines] 中的条目一致 */
@Serializable
data class StyleGuidelineConfidence(
    val rule: String,
    val confidenceInterval: StyleConfidenceInterval? = null,
    val evidenceQuality: String? = null,
    val sampleCoverage: String? = null,
    val evidence: String = "",
    val applicability: String = "",
    val limitations: String = ""
)

/** 启发式可信度区间，不是统计学置信区间，也不是经过校准的概率 */
@Serializable
data class StyleConfidenceInterval(val lower: Int, val upper: Int)

/** v1 可信度档位（仅用于读取历史数据） */
object StyleConfidence {
    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"
    const val INSUFFICIENT = "insufficient"

    val ALL: Set<String> = setOf(HIGH, MEDIUM, LOW, INSUFFICIENT)
}

/** v2 证据质量 */
object StyleEvidenceQuality {
    const val STRONG = "strong"
    const val MODERATE = "moderate"
    const val WEAK = "weak"
    const val INSUFFICIENT = "insufficient"

    val ALL: Set<String> = setOf(STRONG, MODERATE, WEAK, INSUFFICIENT)
}

/** v2 样本覆盖度 */
object StyleSampleCoverage {
    const val BROAD = "broad"
    const val PARTIAL = "partial"
    const val NARROW = "narrow"
    const val INSUFFICIENT = "insufficient"

    val ALL: Set<String> = setOf(BROAD, PARTIAL, NARROW, INSUFFICIENT)
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

    /** v2 协议要求 evidenceAndConfidence 至少覆盖这三个维度 */
    val REQUIRED_EVIDENCE_KEYS: Set<String> = setOf("narration", "diction", "sentenceRhythm")
}
