package com.lianwenqi.app.util

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/** 一键排版 / 智能标点 / 字数统计 */

/** 排版设置，供一键排版与编辑区展示使用 */
data class FormatSettings(
    val lineSpacing: Float = 1.5f,
    val paddingHorizontal: Int = 16,
    val paddingBottom: Int = 24,
    /** 阅读页上下边距（dp），仅阅读器使用 */
    val paddingVertical: Int = 12,
    /** 首行缩进字符数 */
    val indent: Int = 2,
    /** 段落之间空行数 */
    val blankLines: Int = 0,
    /** 快速定位：退出时记录光标 */
    val quickLocate: Boolean = true,
    /** 智能标点：成对标点补全 */
    val autoPair: Boolean = true,
    /** 智能标点：冒号紧随双引号 */
    val colonFollowQuote: Boolean = true
)

data class StyleSettings(
    val fontSizeSp: Float = 17f,
    val fontFamily: FontFamily = FontFamily.Default,
    val fontLabel: String = "默认",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val center: Boolean = false,
    /** 细体：字重比常规更细，与加粗互斥（阅读器专用，编辑页不使用） */
    val thin: Boolean = false,
    val textColor: Int = 0xFF1A1A1A.toInt(),
    val backgroundColor: Int = 0xFFFFFFFF.toInt()
)

object TextTool {

    /** 单个缩进字符（全角空格），缩进量由 FormatSettings.indent 决定 */
    private val indentChar = "　"

    /** 一键排版：合并段中空行，每个段落首行缩进，段落之间空行数由排版设置决定 */
    fun oneKeyFormat(text: String, settings: FormatSettings): String {
        val indent = indentChar.repeat(settings.indent.coerceAtLeast(0))
        val paragraphs = text.replace("\r\n", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { indent + it }
        // 段落之间空几行：0 行就是直接换行，1 行则中间多一个空行
        val gap = "\n".repeat(settings.blankLines.coerceAtLeast(0) + 1)
        return paragraphs.joinToString(gap)
    }

    /** 成对标点补全：补齐未闭合的引号与括号 */
    fun completePairs(text: String): String {
        val pairs = mapOf(
            '“' to '”', '‘' to '’', '（' to '）', '《' to '》', '【' to '】', '「' to '」',
            '"' to '"', '(' to ')', '[' to ']'
        )
        val stack = ArrayDeque<Char>()
        var inStraightQuote = false
        for (ch in text) {
            when {
                ch == '"' -> inStraightQuote = !inStraightQuote
                pairs.containsKey(ch) && ch != '"' -> stack.addLast(pairs.getValue(ch))
                ch == '”' || ch == '’' || ch == '）' || ch == '》' || ch == '】' || ch == '」' -> {
                    if (stack.isNotEmpty() && stack.last() == ch) stack.removeLast()
                }
            }
        }
        val sb = StringBuilder(text)
        if (inStraightQuote) sb.append('"')
        while (stack.isNotEmpty()) sb.append(stack.removeLast())
        return sb.toString()
    }

    /** 冒号紧随双引号：将 "： " 整理为 "：“"，把空格移除 */
    fun colonFollowQuote(text: String): String {
        var r = text.replace("：\"", "：“").replace("\"：", "”：“")
        r = r.replace("：“ ", "：“")
        r = r.replace("，\"", "，“").replace("。\"", "。”")
        return r
    }

    fun smartPunctuation(text: String, settings: FormatSettings): String {
        var r = text
        if (settings.autoPair) r = completePairs(r)
        if (settings.colonFollowQuote) r = colonFollowQuote(r)
        return r
    }

    /** 字数统计：中日韩字符逐字计数，英文按单词计数 */
    fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        var count = 0
        var inLatinWord = false
        for (ch in text) {
            when {
                ch.isWhitespace() -> inLatinWord = false
                isCjk(ch) -> {
                    count++
                    inLatinWord = false
                }
                ch.isLetterOrDigit() -> {
                    if (!inLatinWord) {
                        count++
                        inLatinWord = true
                    }
                }
                else -> inLatinWord = false
            }
        }
        return count
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF ||
            code in 0x3040..0x30FF || code in 0xAC00..0xD7AF
    }

    /** 全文查找：返回高亮后的文本与命中次数 */
    data class HighlightResult(val text: CharSequence, val hits: Int)

    fun highlight(source: String, keyword: String, color: Int): HighlightResult {
        if (keyword.isEmpty()) return HighlightResult(source, 0)
        val sp = SpannableString(source)
        var hits = 0
        var from = 0
        while (true) {
            val idx = source.indexOf(keyword, from, ignoreCase = true)
            if (idx < 0) break
            sp.setSpan(
                ForegroundColorSpan(color), idx, idx + keyword.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sp.setSpan(
                StyleSpan(Typeface.BOLD), idx, idx + keyword.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            hits++
            from = idx + keyword.length
        }
        return HighlightResult(sp, hits)
    }

    fun fontFamilyOf(label: String): FontFamily = when (label) {
        "衬线" -> FontFamily.Serif
        "等宽" -> FontFamily.Monospace
        "手写" -> FontFamily.Cursive
        else -> FontFamily.Default
    }

    fun weightOf(bold: Boolean, thin: Boolean = false): FontWeight = when {
        bold -> FontWeight.Bold
        thin -> FontWeight.Light
        else -> FontWeight.Normal
    }
}