package com.lianwenqi.app.data.style

/**
 * 结构化编辑表单：每个分析维度用多行文本表达，一行一条。
 * 只承载用户可编辑的文本内容；证据与可信度、版本号等仍沿用原档案。
 */
data class StyleAnalysisForm(
    val overview: String = "",
    val narration: String = "",
    val sentenceRhythm: String = "",
    val diction: String = "",
    val description: String = "",
    val paragraphStructure: String = "",
    val dialogue: String = "",
    val emotion: String = "",
    val transition: String = "",
    val writingGuidelines: String = "",
    val avoidances: String = ""
)

/** 表单字段标识，供界面按字段更新，避免为每个字段各写一套回调 */
enum class StyleAnalysisField {
    OVERVIEW,
    NARRATION,
    SENTENCE_RHYTHM,
    DICTION,
    DESCRIPTION,
    PARAGRAPH_STRUCTURE,
    DIALOGUE,
    EMOTION,
    TRANSITION,
    GUIDELINES,
    AVOIDANCES
}

/**
 * 结构化分析结果与编辑表单之间的转换（纯 Kotlin）。
 *
 * 空行会被丢弃；空字段保持为空，不会自动补任何通用文风描述。
 * 是否满足 F4 协议由 [StyleAnalysisCodec.validate] 判断，本类不做额外放行。
 */
object StyleAnalysisEditor {

    /** 表单输入说明 */
    const val ITEMS_HINT = "每行一条，空行会被忽略"

    fun toForm(analysis: StyleAnalysis): StyleAnalysisForm = StyleAnalysisForm(
        overview = analysis.overview,
        narration = analysis.narration.joinToString("\n"),
        sentenceRhythm = analysis.sentenceRhythm.joinToString("\n"),
        diction = analysis.diction.joinToString("\n"),
        description = analysis.description.joinToString("\n"),
        paragraphStructure = analysis.paragraphStructure.joinToString("\n"),
        dialogue = analysis.dialogue.joinToString("\n"),
        emotion = analysis.emotion.joinToString("\n"),
        transition = analysis.transition.joinToString("\n"),
        writingGuidelines = analysis.writingGuidelines.joinToString("\n"),
        avoidances = analysis.avoidances.joinToString("\n")
    )

    /** 用原档案的证据与版本构造编辑后的分析结果；证据不做修改，避免凭空改写结论依据 */
    fun toAnalysis(form: StyleAnalysisForm, template: StyleAnalysis): StyleAnalysis =
        StyleAnalysis(
            schemaVersion = template.schemaVersion,
            overview = form.overview.trim(),
            narration = items(form.narration),
            sentenceRhythm = items(form.sentenceRhythm),
            diction = items(form.diction),
            description = items(form.description),
            paragraphStructure = items(form.paragraphStructure),
            dialogue = items(form.dialogue),
            emotion = items(form.emotion),
            transition = items(form.transition),
            writingGuidelines = items(form.writingGuidelines),
            avoidances = items(form.avoidances),
            evidenceAndConfidence = template.evidenceAndConfidence
        )

    fun update(form: StyleAnalysisForm, field: StyleAnalysisField, value: String): StyleAnalysisForm =
        when (field) {
            StyleAnalysisField.OVERVIEW -> form.copy(overview = value)
            StyleAnalysisField.NARRATION -> form.copy(narration = value)
            StyleAnalysisField.SENTENCE_RHYTHM -> form.copy(sentenceRhythm = value)
            StyleAnalysisField.DICTION -> form.copy(diction = value)
            StyleAnalysisField.DESCRIPTION -> form.copy(description = value)
            StyleAnalysisField.PARAGRAPH_STRUCTURE -> form.copy(paragraphStructure = value)
            StyleAnalysisField.DIALOGUE -> form.copy(dialogue = value)
            StyleAnalysisField.EMOTION -> form.copy(emotion = value)
            StyleAnalysisField.TRANSITION -> form.copy(transition = value)
            StyleAnalysisField.GUIDELINES -> form.copy(writingGuidelines = value)
            StyleAnalysisField.AVOIDANCES -> form.copy(avoidances = value)
        }

    private fun items(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
