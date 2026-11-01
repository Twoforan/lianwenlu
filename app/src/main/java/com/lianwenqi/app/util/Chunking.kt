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

/**
 * 文风样本切片：按自然段优先，段落过长时再按句子聚合到约 [target] 字。
 *
 * 与 [chunkChapter] 的区别：
 * - 不做块尾重叠，切片是互不重复的原文素材；
 * - 长段落优先在句号 / 问号 / 感叹号等句末标点处切开，尽量保持句子完整；
 * - 只有单句本身超过 [target] 时才退化为在逗号等次级标点处拆分；
 * - 不改写、不润色、不总结原文，仅在切片边界做 trim。
 */
fun chunkStyleSample(content: String, target: Int = 100): List<TextChunk> {
    val safeTarget = target.coerceAtLeast(1)
    val paragraphs = content
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split(Regex("\\n[\\t ]*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (paragraphs.isEmpty()) return emptyList()

    val pieces = ArrayList<String>()
    paragraphs.forEach { paragraph ->
        if (paragraph.length <= safeTarget) {
            pieces.add(paragraph)
            return@forEach
        }
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                pieces.add(buffer.toString().trim())
                buffer.clear()
            }
        }
        splitStyleSentences(paragraph).forEach { sentence ->
            when {
                sentence.length > safeTarget -> {
                    flush()
                    splitLongStyleSentence(sentence, safeTarget, pieces)
                }
                buffer.isNotEmpty() && buffer.length + sentence.length > safeTarget -> {
                    flush()
                    buffer.append(sentence)
                }
                else -> buffer.append(sentence)
            }
        }
        flush()
    }

    return pieces
        .filter { it.isNotBlank() }
        .mapIndexed { index, text -> TextChunk(index, text, shortHash(text)) }
}

private val STYLE_SENTENCE_ENDINGS = setOf('。', '！', '？', '!', '?', '…', '；', ';')
private val STYLE_SENTENCE_CLOSERS = setOf('”', '’', '」', '』', '）', ')', '"', '】', ']')
private val STYLE_SECONDARY_BREAKS = setOf('，', ',', '、', '；', ';', '：', ':')

/** 按句末标点 / 换行切成句子，保留标点与紧随其后的引号、括号 */
private fun splitStyleSentences(text: String): List<String> {
    val result = ArrayList<String>()
    val buffer = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\n') {
            if (buffer.isNotEmpty()) {
                result.add(buffer.toString().trim())
                buffer.clear()
            }
            i++
            continue
        }
        buffer.append(c)
        if (c in STYLE_SENTENCE_ENDINGS) {
            while (i + 1 < text.length && text[i + 1] in STYLE_SENTENCE_CLOSERS) {
                i++
                buffer.append(text[i])
            }
            if (buffer.isNotEmpty()) {
                result.add(buffer.toString().trim())
                buffer.clear()
            }
        }
        i++
    }
    if (buffer.isNotEmpty()) result.add(buffer.toString().trim())
    return result.filter { it.isNotBlank() }
}

/** 单句超过目标长度时的兜底拆分：优先在目标附近的逗号等次级标点处断开 */
private fun splitLongStyleSentence(sentence: String, target: Int, out: MutableList<String>) {
    var rest = sentence
    while (rest.length > target) {
        var cut = target
        var bestBreak = -1
        STYLE_SECONDARY_BREAKS.forEach { mark ->
            val index = rest.lastIndexOf(mark, target - 1)
            if (index > target / 2 && index > bestBreak) bestBreak = index
        }
        if (bestBreak >= 0) cut = bestBreak + 1
        out.add(rest.substring(0, cut).trim())
        rest = rest.substring(cut)
    }
    if (rest.isNotBlank()) out.add(rest.trim())
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

/**
 * 把「向量化源签名」（向量化工具 + 模型）并进块哈希前缀。
 *
 * 不同向量化工具 / 不同模型产出的向量互相之间算不了余弦（维度可能不同，
 * 即使维度相同也不在同一向量空间），所以只要换了源，已存向量就必须整本重跑。
 * 并进哈希前缀后，旧哈希与新哈希自然失配，[chunkChapter] 的增量判定就会自动重跑，
 * 不依赖任何网络探测，也不会因为库里的 dim 字段缺失而误判「无需重跑」。
 */
fun stampChunkHash(source: String, hash: String): String = "$source|$hash"

/** 已存块哈希是否由 [source] 这个向量化源写入 */
fun stampedBy(hash: String, source: String): Boolean = hash.startsWith("$source|")
