package com.lianwenqi.app.util

/** 切块结果：块序号、块文本、块文本的稳定短哈希（用于增量判定） */
data class TextChunk(val index: Int, val text: String, val hash: String)

/**
 * 章节正文切块：按空行切段，短段落向后聚合成块、超长段落按 [target] 字硬切，
 * 块尾 [overlap] 字并入下一块开头，让相邻块在边界处有上下文重叠。
 * 只处理正文，不涉及梗概 / 大纲。
 *
 * 两个硬约束决定了这里的取值：
 * 1. 本地向量模型上下文固定 512 token，中文约 1 字 = 1 token，块必须留足余量；
 * 2. 块要短小聚焦，命中「5 段参考资料」时覆盖的内容才不糊成一大坨。
 * 因此块长上限被控制在 [target] 字左右（聚合时的超出量不超过一个段落），
 * 而不是像以前那样「聚合到 >= target」后遇到长段落就无限膨胀、超长部分被模型静默截断。
 */
fun chunkChapter(content: String, target: Int = 200, overlap: Int = 30): List<TextChunk> {
    val paragraphs = content.split(Regex("\\n[\\t ]*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return emptyList()

    // 先切出「长度有界」的片段：长段落自己按 target 硬切成多块，短段落则向后聚合成一块
    val pieces = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() {
        if (sb.isNotEmpty()) {
            pieces.add(sb.toString().trim())
            sb.clear()
        }
    }
    for (p in paragraphs) {
        if (p.length > target) {
            // 超长段落不参与聚合，按 target 硬切成若干块，避免单块超过模型上下文
            flush()
            var i = 0
            while (i < p.length) {
                val end = minOf(i + target, p.length)
                pieces.add(p.substring(i, end))
                i = end
            }
        } else {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(p)
            if (sb.length >= target) flush()
        }
    }
    flush()

    // 块尾 overlap 字并入下一块开头
    return pieces.mapIndexed { i, text ->
        val head = if (i == 0 || overlap <= 0) "" else pieces[i - 1].takeLast(overlap)
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
