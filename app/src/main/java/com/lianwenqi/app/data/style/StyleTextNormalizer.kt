package com.lianwenqi.app.data.style

/**
 * 提取正文的统一清洗：保留段落结构，去掉控制字符与多余空白，不改写文字内容。
 * 纯 Kotlin 实现，便于单元测试。
 */
object StyleTextNormalizer {

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        // 统一换行符：先处理 CRLF，避免一个换行被拆成两个空行
        val unified = raw.replace("\r\n", "\n").replace('\r', '\n')

        val cleaned = StringBuilder(unified.length)
        for (c in unified) {
            when {
                c == '\n' || c == '\t' -> cleaned.append(c)
                // 零宽字符 / BOM：不可见但会干扰分析
                c == '\uFEFF' || c == '\u200B' || c == '\u200C' || c == '\u200D' -> Unit
                // 其它控制字符（含 \u0000）直接丢弃
                c.code < 0x20 || c.code == 0x7F -> Unit
                else -> cleaned.append(c)
            }
        }

        val out = StringBuilder(cleaned.length)
        var blankLines = 0
        for (line in cleaned.split('\n')) {
            val text = line.trimEnd()
            if (text.isBlank()) {
                blankLines++
                // 最多保留一个空行（即连续两个换行）
                if (blankLines > 1) continue
            } else {
                blankLines = 0
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(text)
        }
        return out.toString().trim()
    }
}
