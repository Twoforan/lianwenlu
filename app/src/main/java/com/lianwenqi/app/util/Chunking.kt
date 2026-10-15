package com.lianwenqi.app.util

/** 切块结果：块序号、块文本、块文本的稳定短哈希（用于增量判定） */
data class TextChunk(val index: Int, val text: String, val hash: String)

/**
 * 章节正文切块：按空行切段，逐段聚合到 >= [target] 字成一块，
 * 块尾 [overlap] 字并入下一块开头，让相邻块在边界处有上下文重叠。
 * 只处理正文，不涉及梗概 / 大纲。
 */
fun chunkChapter(content: String, target: Int = 400, overlap: Int = 60): List<TextChunk> {
    val paragraphs = content.split(Regex("\\n[\\t ]*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return emptyList()

    // 先按段落聚合出原始块
    val raw = ArrayList<String>()
    val sb = StringBuilder()
    for (p in paragraphs) {
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(p)
        if (sb.length >= target) {
            raw.add(sb.toString())
            sb.clear()
        }
    }
    if (sb.isNotEmpty()) raw.add(sb.toString())

    // 块尾 overlap 字并入下一块开头
    return raw.mapIndexed { i, text ->
        val head = if (i == 0 || overlap <= 0) "" else raw[i - 1].takeLast(overlap)
        val full = (head + text).trim()
        TextChunk(i, full, shortHash(full))
    }
}

/** 块文本的稳定短哈希（FNV-1a over UTF-8），同文本必得同值，便于增量比对 */
fun shortHash(text: String): String {
    var hash = -3750763034362895579L // FNV-1a 64 位 offset basis
    for (b in text.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toLong() and 0xff)
        hash *= 1099511628211L // FNV 质数
    }
    return hash.toULong().toString(16)
}
