package com.lianwenqi.app.agent.context

import com.lianwenqi.app.ai.ChatTurn

/**
 * 上下文 Token 估算。
 *
 * 没有可用的分词器时按字符类型做保守估算：中日韩字符约 1 字 = 1 token，
 * 其他字符约 4 字符 = 1 token，并给每条消息加固定开销。
 * 估算只用于判断是否接近上限，不能替代服务端返回的真实 usage。
 */
object ContextEstimator {

    /** 每条消息的角色标记、分隔符等固定开销 */
    private const val MESSAGE_OVERHEAD_TOKENS = 6

    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var narrow = 0
        for (ch in text) {
            if (isWide(ch)) wide++ else narrow++
        }
        return wide + (narrow + 3) / 4
    }

    fun estimateTokens(turns: List<ChatTurn>): Int =
        turns.sumOf { turn ->
            estimateTokens(turn.content) + MESSAGE_OVERHEAD_TOKENS + turn.images.size * IMAGE_TOKENS
        }

    /** 单张图片按固定预算估算，避免 base64 长度把估算值放大到失真 */
    private const val IMAGE_TOKENS = 1_000

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in 0x2E80..0x9FFF || // 中日韩统一表意文字及部首
            code in 0xF900..0xFAFF || // 兼容表意文字
            code in 0xFF00..0xFFEF || // 全角标点与字母
            code in 0x3000..0x303F || // 中文标点
            code in 0xAC00..0xD7AF // 韩文
    }
}
