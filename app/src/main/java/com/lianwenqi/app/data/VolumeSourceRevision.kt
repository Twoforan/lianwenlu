package com.lianwenqi.app.data

import com.lianwenqi.app.util.shortHash

/**
 * 卷梗概源指纹：把「输入来源 + 参与章节集合与内容」压成一个确定性的短哈希。
 *
 * 规则固定（改动等于让所有旧梗概一次性过期，必须带版本号）：
 * 1. 先做文本归一化 [normalize]：CRLF / CR → LF、逐行去行尾空白、连续空行压成一行、去首尾空行；
 * 2. 每章内容取归一化后的 [contentHash]；
 * 3. 输入串为 `v1|sourceType|chapterId:hash;chapterId:hash...`，
 *    章节顺序必须是目录顺序（sortOrder,id），因此调序 / 增删 / 移入移出都会改变结果；
 * 4. 章节已不存在时用 [MISSING_HASH] 占位，保证「章节被删」同样能被识别。
 *
 * 这里只依赖内容与章节 id，不依赖 updatedAt 这类容易漏更新的时间戳。
 */
object VolumeSourceRevision {
    /** 指纹算法版本；归一化或拼接规则变化时必须升级 */
    const val VERSION = "v1"

    /** 章节已不存在（或不属于该卷）时的内容占位 */
    const val MISSING_HASH = "missing"

    /** 参与压缩的一章：章节 id + 该章所选内容的指纹 */
    data class Entry(val chapterId: Long, val contentHash: String)

    /** 内容归一化：消除换行符与行尾空白差异，连续空行只保留一个 */
    fun normalize(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = StringBuilder()
        var lastBlank = false
        for (line in lines) {
            val trimmed = line.trimEnd()
            val blank = trimmed.isEmpty()
            if (blank && lastBlank) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(trimmed)
            lastBlank = blank
        }
        return out.toString().trim('\n')
    }

    /** 单章内容的稳定指纹 */
    fun contentHash(text: String): String = shortHash(normalize(text))

    /** 拼出参与哈希的输入串（对测试与排查可见，便于定位过期原因） */
    fun payload(sourceType: String, entries: List<Entry>): String = buildString {
        append(VERSION).append('|').append(sourceType).append('|')
        entries.forEachIndexed { index, entry ->
            if (index > 0) append(';')
            append(entry.chapterId).append(':').append(entry.contentHash)
        }
    }

    /** 计算源指纹；[entries] 必须按目录顺序传入 */
    fun revision(sourceType: String, entries: List<Entry>): String = shortHash(payload(sourceType, entries))
}
