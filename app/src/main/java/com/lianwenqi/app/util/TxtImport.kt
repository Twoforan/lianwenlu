package com.lianwenqi.app.util

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** TXT 导入解析结果 */
data class ParsedChapter(val title: String, val content: String)

data class ParsedVolume(val title: String, val chapters: List<ParsedChapter>)

data class ParsedTxt(
    val suggestedTitle: String,
    val volumes: List<ParsedVolume>,
    val totalChars: Int
) {
    val chapterCount: Int get() = volumes.sumOf { it.chapters.size }
    val volumeCount: Int get() = volumes.size
}

/** 章节识别模式 */
enum class TxtParseMode(val label: String) {
    AUTO("自动识别"),
    CHINESE("中文章节（第N章/节/回）"),
    ENGLISH("英文章节（Chapter N）"),
    NUMBERED("数字编号（1、开端）"),
    NONE("无章节（整本一章）");

    companion object {
        fun fromName(name: String?): TxtParseMode =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * TXT 文件导入解析：编码检测 + 分卷分章切分。
 * 纯 Kotlin 无 Android 依赖，便于单元测试。
 */
object TxtImport {

    // ---------------- 编码检测 ----------------

    /**
     * 字节流转文本：BOM 识别 → UTF-8 严格校验 → GB18030 回退。
     * 中文小说 txt 以 GBK/GB18030 居多，直接按 UTF-8 读会出现乱码；
     * 严格校验能可靠区分两者（GBK 中文文本几乎必然不是合法 UTF-8）。
     */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        val isUtf8 = runCatching {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        }.getOrDefault(false)
        return if (isUtf8) String(bytes, StandardCharsets.UTF_8)
        else String(bytes, charset("GB18030"))
    }

    // ---------------- 章节解析 ----------------

    // 与 ChineseNumber.parse 的能力对齐（支持到 999，不含千万两）
    private const val CN_NUM = "[0-9零一二三四五六七八九十百]{1,9}"

    /** 分卷标题：第N卷/部（带可选副标题） */
    private val volumeRegex = Regex("^[\\s　]*第\\s*($CN_NUM)\\s*([卷部])[\\s　]*(.*)$")

    /** 中文章节：第N章/节/回（分隔可空，兼容「第一章试炼」） */
    private val chapterCnRegex = Regex("^[\\s　]*第\\s*($CN_NUM)\\s*([章节回])[\\s　]*(.*)$")

    /** 特殊章节名：序章/楔子/尾声/番外等（可带编号如「番外二」） */
    private val specialChapterRegex =
        Regex("^[\\s　]*(序章|序言|楔子|引子|前言|尾声|终章|后记|番外[0-9零一二三四五六七八九十百]{0,4})[\\s　]*(.*)$")

    /** 英文章节：Chapter N */
    private val chapterEnRegex =
        Regex("^\\s*Chapter\\s+([0-9]{1,5})\\s*[.．:：\\-–—]?\\s*(.*)$", RegexOption.IGNORE_CASE)

    /** 数字编号：1、开端 / 1. 开端 / 01 - 开端 */
    private val numberedRegex = Regex("^\\s*([0-9]{1,5})\\s*[、.．,，:：\\-–—]\\s*(.+)$")

    /** 卷与章节标题的中间结构 */
    private data class Heading(
        val lineIndex: Int,
        val isVolume: Boolean,
        val title: String
    )

    /** 前言保留阈值：孤儿文本不足三行时视为书名/作者等元信息，直接丢弃 */
    private const val PREFACE_MIN_LINES = 3

    /**
     * 解析全文。mode=AUTO 时依次按中文章节、英文、数字编号匹配，
     * 取命中章节数最多的规则（至少 2 章，或唯一标题位于文件开头）；
     * 全部无命中则整本作为一章。
     */
    fun parse(text: String, fileName: String, mode: TxtParseMode): ParsedTxt {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split("\n")
        val headings = detectHeadings(lines, mode)
        val volumes = buildVolumes(lines, headings)
        return ParsedTxt(
            suggestedTitle = cleanFileName(fileName),
            volumes = volumes,
            totalChars = volumes.sumOf { v -> v.chapters.sumOf { TextTool.countWords(it.content) } }
        )
    }

    private fun detectHeadings(lines: List<String>, mode: TxtParseMode): List<Heading> {
        fun detect(matcher: (String) -> Heading?): List<Heading> =
            lines.mapIndexedNotNull { i, line -> if (line.length <= 60) matcher(line)?.copy(lineIndex = i) else null }

        val chinese = detect { matchChineseHeading(it) }
        val english = detect { matchEnglishHeading(it) }
        val numbered = detect { matchNumberedHeading(it) }

        return when (mode) {
            TxtParseMode.CHINESE -> chinese
            TxtParseMode.ENGLISH -> english
            TxtParseMode.NUMBERED -> numbered
            TxtParseMode.NONE -> emptyList()
            TxtParseMode.AUTO -> {
                val chapterCount = { list: List<Heading> -> list.count { !it.isVolume } }
                // 命中 ≥2 章的规则里取最多者
                listOf(chinese, english, numbered)
                    .filter { chapterCount(it) >= 2 }
                    .maxByOrNull { chapterCount(it) }
                    ?.let { return it }
                // 仅 1 个标题且位于文件开头：视为单章结构（仅信任中/英文规则）
                listOf(chinese, english).firstOrNull { list ->
                    chapterCount(list) == 1 && list.firstOrNull()?.lineIndex?.let { it <= 3 } == true
                } ?: emptyList()
            }
        }
    }

    /** 中文标题匹配：卷 → 章 → 特殊章节。以句末标点收尾的行视为正文，不判标题 */
    private fun matchChineseHeading(line: String): Heading? {
        if (line.endsWith("。") || line.endsWith("！") || line.endsWith("？") || line.endsWith("…") ||
            line.endsWith("；") || line.endsWith("，") || line.endsWith(",")
        ) return null
        volumeRegex.matchEntire(line)?.let { m ->
            val num = parseNumber(m.groupValues[1])
            val unit = m.groupValues[2]
            val suffix = m.groupValues[3].trim()
            // 「第一部分」类标题整体保留原文，避免拆成「第1卷 分…」
            if (unit == "部" && suffix.startsWith("分")) return Heading(0, true, line.trim())
            return Heading(0, true, buildTitle("第%n$unit", num) + suffixSpace(suffix))
        }
        chapterCnRegex.matchEntire(line)?.let { m ->
            val num = parseNumber(m.groupValues[1])
            val unit = m.groupValues[2]
            val suffix = m.groupValues[3].trim()
            return Heading(0, false, buildTitle("第%n$unit", num) + suffixSpace(suffix))
        }
        specialChapterRegex.matchEntire(line)?.let { m ->
            val suffix = m.groupValues[2].trim()
            return Heading(0, false, m.groupValues[1] + suffixSpace(suffix))
        }
        return null
    }

    private fun matchEnglishHeading(line: String): Heading? {
        chapterEnRegex.matchEntire(line)?.let { m ->
            val num = m.groupValues[1].toLongOrNull() ?: 0L
            val suffix = m.groupValues[2].trim()
            return Heading(0, false, "Chapter $num" + suffixSpace(suffix))
        }
        return null
    }

    private fun matchNumberedHeading(line: String): Heading? {
        numberedRegex.matchEntire(line)?.let { m ->
            val num = m.groupValues[1]
            val suffix = m.groupValues[2].trim()
            return Heading(0, false, "$num" + suffixSpace(suffix, separator = "、"))
        }
        return null
    }

    private fun suffixSpace(suffix: String, separator: String = " "): String =
        if (suffix.isEmpty()) "" else "$separator$suffix"

    /** 中文/阿拉伯数字串转 Int（「一百二十三」→123，「123」→123，失败→0） */
    private fun parseNumber(raw: String): Int = when {
        raw.isEmpty() -> 0
        raw.all { it in '0'..'9' } -> raw.toIntOrNull() ?: 0
        else -> ChineseNumber.parse(raw) ?: 0
    }

    /**
     * 按标题行把全文切成卷与章节。
     * 第一个标题之前的散落文本（书名/作者行等）作为首个卷的「前言」章节；
     * 卷标题与首个章节标题之间的散落文本同样作为该卷「前言」，保证不丢内容。
     */
    private fun buildVolumes(lines: List<String>, headings: List<Heading>): List<ParsedVolume> {
        if (headings.isEmpty()) {
            val content = lines.joinToString("\n").trim()
            return if (content.isEmpty()) emptyList()
            else listOf(ParsedVolume("正文", listOf(ParsedChapter("全文", content))))
        }

        val volumes = mutableListOf<ParsedVolume>()
        var current = MutableVolume("正文")
        var currentTitle: String? = null
        var currentBody = StringBuilder()
        var orphan = StringBuilder()

        fun flushChapter() {
            val title = currentTitle ?: return
            current.chapters.add(ParsedChapter(title, currentBody.toString().trim()))
            currentTitle = null
            currentBody = StringBuilder()
        }

        /** 孤儿文本落进当前卷作「前言」章节；仅一两行书名/作者类元信息直接丢弃 */
        fun flushOrphan() {
            val text = orphan.toString().trim()
            if (text.isNotEmpty() && text.lines().count { it.isNotBlank() } >= PREFACE_MIN_LINES) {
                current.chapters.add(ParsedChapter("前言", text))
            }
            orphan = StringBuilder()
        }

        val headingLines = headings.associateBy { it.lineIndex }
        lines.forEachIndexed { i, line ->
            val heading = headingLines[i]
            when {
                heading == null -> when {
                    currentTitle != null -> currentBody.append(line).append('\n')
                    line.isNotBlank() -> orphan.append(line).append('\n')
                }
                heading.isVolume -> {
                    // 结算当前卷；孤儿文本不结算，保留给新卷作「前言」
                    flushChapter()
                    if (current.chapters.isNotEmpty()) volumes.add(current.toVolume())
                    current = MutableVolume(heading.title)
                }
                else -> {
                    flushOrphan()
                    flushChapter()
                    currentTitle = heading.title
                }
            }
        }
        flushChapter()
        flushOrphan()
        if (current.chapters.isNotEmpty()) volumes.add(current.toVolume())
        return volumes
    }

    private class MutableVolume(val title: String) {
        val chapters = mutableListOf<ParsedChapter>()
        fun toVolume() = ParsedVolume(title, chapters.toList())
    }

    private fun cleanFileName(name: String): String {
        val cleaned = name.substringBeforeLast('.').trim()
        return cleaned.ifEmpty { "导入文本" }
    }
}
