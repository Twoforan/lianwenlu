package com.lianwenqi.app.data.style

/** 文风样本的文件格式 */
enum class StyleSampleFormat(val label: String) {
    TXT("TXT"),
    EPUB("EPUB"),
    DOCX("DOCX"),
    UNKNOWN("未知")
}

/**
 * 按扩展名判定格式，扩展名缺失时用系统给出的 MIME 兜底。
 * 判定不了就返回 [StyleSampleFormat.UNKNOWN]，交给上层明确提示「暂不支持」，不做静默跳过。
 */
fun detectStyleSampleFormat(fileName: String, mimeType: String? = null): StyleSampleFormat {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "txt", "text" -> StyleSampleFormat.TXT
        "epub" -> StyleSampleFormat.EPUB
        "docx" -> StyleSampleFormat.DOCX
        else -> when (mimeType?.lowercase()) {
            "text/plain" -> StyleSampleFormat.TXT
            "application/epub+zip" -> StyleSampleFormat.EPUB
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                StyleSampleFormat.DOCX
            else -> StyleSampleFormat.UNKNOWN
        }
    }
}

/** 样本处理状态 */
enum class StyleSampleStatus { PENDING, PARSING, SUCCESS, FAILED }

/**
 * 一次导入中的单个样本：只存在于页面内存，不落库、不进入书籍正文 / 记忆向量。
 * [text] 为提取后的正文，[key] 为本次导入会话内的稳定临时标识。
 */
data class StyleSample(
    val key: String,
    val uri: String,
    val fileName: String,
    val format: StyleSampleFormat,
    /** 处理顺序，从 1 开始；列表顺序即该顺序 */
    val order: Int,
    val status: StyleSampleStatus = StyleSampleStatus.PENDING,
    val text: String = "",
    val charCount: Int = 0,
    val error: String? = null
) {
    /** 是否满足了后续分析的最低要求（成功且正文不短于 [MIN_ANALYSIS_CHARS]） */
    val isUsable: Boolean
        get() = status == StyleSampleStatus.SUCCESS && charCount >= MIN_ANALYSIS_CHARS

    /** 提取成功但内容过短（需要提示用户） */
    val isTooShort: Boolean
        get() = status == StyleSampleStatus.SUCCESS && charCount < MIN_ANALYSIS_CHARS

    companion object {
        /** 可用于分析的最低正文长度（字符） */
        const val MIN_ANALYSIS_CHARS = 100
        /** 单文件正文上限（字符），超出时明确报错，避免把超长文本全部读进内存 */
        const val MAX_SAMPLE_CHARS = 2_000_000
    }
}

/**
 * F4（文风分析）的输入边界：按顺序排列、只包含可用样本的纯文本。
 * 分析流程直接消费它即可，不需要重新解析文件。
 */
data class StyleSampleInput(
    val fileName: String,
    val format: StyleSampleFormat,
    val order: Int,
    val charCount: Int,
    val text: String
)

/** 按处理顺序整理可用样本；解析失败 / 过短的样本不会进入分析输入 */
fun List<StyleSample>.toAnalysisInput(): List<StyleSampleInput> =
    asSequence()
        .sortedBy { it.order }
        .filter { it.isUsable }
        .map { StyleSampleInput(it.fileName, it.format, it.order, it.charCount, it.text) }
        .toList()

/** 重新编号：列表顺序即处理顺序，移除样本后序号保持连续 */
fun List<StyleSample>.renumbered(): List<StyleSample> =
    mapIndexed { index, sample -> sample.copy(order = index + 1) }
