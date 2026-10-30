package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.StyleProfile

/**
 * 文风相关 JVM 测试的公共夹具：构造通过 [StyleAnalysisCodec] 校验的结构化分析结果。
 * [tag] 用来区分不同书籍 / 档案（如「甲」「乙」），便于断言请求里注入的是哪一份。
 */
internal fun testAnalysis(tag: String = "甲"): StyleAnalysis = StyleAnalysis(
    schemaVersion = STYLE_ANALYSIS_SCHEMA_VERSION,
    overview = "${tag}书整体风格：冷静克制，以动作与环境细节推进叙事",
    narration = listOf("${tag}书采用第三人称限知视角，叙述距离贴近主角"),
    sentenceRhythm = listOf("${tag}书以短句为主，长句只用于情绪递进处"),
    diction = listOf("${tag}书多用具体名词与动词，很少堆叠形容词"),
    description = listOf("${tag}书环境描写点到为止，优先写人物动作"),
    paragraphStructure = listOf("${tag}书段落短小，每段聚焦一个动作或反应"),
    dialogue = listOf("${tag}书对话短促，常用动作代替提示语"),
    emotion = listOf("${tag}书情绪通过动作细节外化，少直接抒情"),
    transition = listOf("${tag}书场景转换用时间或动作自然承接"),
    writingGuidelines = listOf(
        "${tag}书仿写时先写可见动作，再写人物内心",
        "${tag}书仿写时用短句收束段落，避免长段议论"
    ),
    avoidances = listOf("${tag}书避免华丽排比与长篇抒情独白"),
    evidenceAndConfidence = listOf(
        StyleEvidence(
            dimension = "narration",
            evidence = "${tag}书样本前 3 章均保持同一视角",
            feature = "${tag}书叙述距离贴近主角",
            confidenceInterval = StyleConfidenceInterval(75, 92),
            evidenceQuality = StyleEvidenceQuality.STRONG,
            sampleCoverage = StyleSampleCoverage.BROAD,
            applicability = "适用于人物交流与心理描写场景",
            limitations = ""
        ),
        StyleEvidence(
            dimension = "diction",
            evidence = "${tag}书样本动作动词密集而形容词较少",
            feature = "${tag}书动词密度高于形容词",
            confidenceInterval = StyleConfidenceInterval(60, 85),
            evidenceQuality = StyleEvidenceQuality.MODERATE,
            sampleCoverage = StyleSampleCoverage.PARTIAL,
            applicability = "适用于动作与对话场景",
            limitations = "抒情段落证据有限"
        ),
        StyleEvidence(
            dimension = "sentenceRhythm",
            evidence = "${tag}书样本平均句长明显偏短",
            feature = "${tag}书短句为主",
            confidenceInterval = StyleConfidenceInterval(70, 90),
            evidenceQuality = StyleEvidenceQuality.STRONG,
            sampleCoverage = StyleSampleCoverage.BROAD,
            applicability = "适用于紧张与转折场景",
            limitations = ""
        )
    ),
    guidelineConfidence = listOf(
        StyleGuidelineConfidence(
            rule = "${tag}书仿写时先写可见动作，再写人物内心",
            confidenceInterval = StyleConfidenceInterval(70, 90),
            evidenceQuality = StyleEvidenceQuality.MODERATE,
            sampleCoverage = StyleSampleCoverage.PARTIAL,
            evidence = "${tag}书多个片段先写动作再写反应",
            applicability = "适用于人物交流场景",
            limitations = ""
        ),
        StyleGuidelineConfidence(
            rule = "${tag}书仿写时用短句收束段落，避免长段议论",
            confidenceInterval = StyleConfidenceInterval(65, 88),
            evidenceQuality = StyleEvidenceQuality.MODERATE,
            sampleCoverage = StyleSampleCoverage.PARTIAL,
            evidence = "${tag}书段落多以短句收尾",
            applicability = "适用于段落收束",
            limitations = ""
        )
    )
)

/** 构造一份可被 [StyleConstraintLoader] 正常解析的档案 */
internal fun testProfile(id: Long, tag: String = "甲"): StyleProfile = StyleProfile(
    id = id,
    name = "${tag}风档案",
    analysisJson = StyleAnalysisCodec.encode(testAnalysis(tag))
)

/** 手写 fake：模拟 Room 的「书籍 styleId」与「档案」两张表，并允许注入读取异常 */
internal class FakeStyleSource {
    val bookStyles = mutableMapOf<Long, Long>()
    val profiles = mutableMapOf<Long, StyleProfile>()
    var failBookRead = false
    var failProfileRead = false

    suspend fun styleIdOf(bookId: Long): Long? {
        if (failBookRead) error("book read failed")
        return bookStyles[bookId]
    }

    suspend fun profileOf(id: Long): StyleProfile? {
        if (failProfileRead) error("profile read failed")
        return profiles[id]
    }
}

internal fun loaderOf(source: FakeStyleSource): StyleConstraintLoader = StyleConstraintLoader(
    styleIdOf = { source.styleIdOf(it) },
    profileOf = { source.profileOf(it) }
)
