package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ChatRole
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次请求的 Prompt 指纹：只保存摘要、数量和角色统计，绝不保存正文。
 *
 * 分段计算是为了定位「前缀从哪一段开始变化」：
 * - [systemHash]：模型预设系统提示 + 开头连续的 system 消息（文风 / 自定义前缀 / 参考资料）；
 * - [toolsHash]：工具定义（含顺序）；
 * - [conversationPrefixHash]：开头 system 段之后、最后一条用户输入之前的全部消息
 *   （历史正文 / 历史梗概 / 历史对话 / 本章参考资料）；
 * - [fullRequestHash]：最终序列化请求体的整体摘要。
 */
data class PromptFingerprint(
    val systemHash: String,
    val toolsHash: String,
    val conversationPrefixHash: String,
    val fullRequestHash: String,
    val conversationTurnHashes: List<String>,
    /** 形如 system:4/12000chars,user:12/8000chars,assistant:11/90000chars（只含角色、条数、字符数） */
    val roleSummary: String,
    val systemChars: Int,
    val historyChars: Int,
    val tailChars: Int,
    val toolCount: Int,
    val messageCount: Int,
    val createdAtMs: Long = System.currentTimeMillis()
)

/** 相邻两次请求的对照结果；[firstDifferentSegment] 为 null 表示没有可比对象（首次请求） */
data class PromptDiff(
    val firstDifferentSegment: String?,
    val intervalMs: Long?
)

/**
 * Prompt 前缀诊断：按消息边界做摘要，并与同一端点的上一次请求对比，定位首个差异段。
 *
 * 不记录任何 Prompt 正文；[remember] 只在进程内保留摘要，随进程结束释放。
 */
object PromptDiagnostics {
    private const val HEX = "0123456789abcdef"
    private const val HASH_HEX_CHARS = 16

    private val previousByEndpoint = ConcurrentHashMap<String, PromptFingerprint>()

    /** 端点维度：同一供应商 / 模型 / 接口 / 流式模式的请求才互相比较 */
    fun endpointKey(endpoint: ModelEndpoint, streaming: Boolean): String =
        "${endpoint.provider.id}|${endpoint.provider.baseUrl}|${endpoint.apiType}|${endpoint.modelName}|$streaming"

    fun fingerprint(
        systemPrompt: String,
        turns: List<ChatTurn>,
        toolsText: String,
        toolCount: Int,
        fullRequestBody: String
    ): PromptFingerprint {
        val leadingSystemTurns = turns.takeWhile { it.role == ChatRole.SYSTEM }
        val afterLeading = turns.drop(leadingSystemTurns.size)
        val lastUserIndex = afterLeading.indexOfLast { it.role == ChatRole.USER }
        val conversationTurns = afterLeading.filterIndexed { index, _ -> index != lastUserIndex }
        val tailTurn = afterLeading.getOrNull(lastUserIndex)
        val conversationHashes = conversationTurns.map { hash("${it.role.name}\u0001${it.content}") }
        val roleSummary = turns.groupBy { it.role.name.lowercase() }
            .entries
            .joinToString(",") { (role, list) -> "$role:${list.size}/${list.sumOf { it.content.length }}chars" }

        return PromptFingerprint(
            systemHash = hash(
                buildString {
                    append(systemPrompt)
                    leadingSystemTurns.forEach {
                        append('\u0000')
                        append(it.content)
                    }
                }
            ),
            toolsHash = hash(toolsText),
            conversationPrefixHash = hash(conversationHashes.joinToString("\u0002")),
            fullRequestHash = hash(fullRequestBody),
            conversationTurnHashes = conversationHashes,
            roleSummary = roleSummary,
            systemChars = systemPrompt.length + leadingSystemTurns.sumOf { it.content.length },
            historyChars = conversationTurns.sumOf { it.content.length },
            tailChars = tailTurn?.content?.length ?: 0,
            toolCount = toolCount,
            messageCount = turns.size
        )
    }

    fun compare(previous: PromptFingerprint?, current: PromptFingerprint): PromptDiff {
        if (previous == null) return PromptDiff(firstDifferentSegment = "baseline", intervalMs = null)
        val interval = (current.createdAtMs - previous.createdAtMs).coerceAtLeast(0)
        if (previous.systemHash != current.systemHash) return PromptDiff("system", interval)
        if (previous.toolsHash != current.toolsHash) return PromptDiff("tools", interval)
        val max = maxOf(previous.conversationTurnHashes.size, current.conversationTurnHashes.size)
        for (index in 0 until max) {
            if (previous.conversationTurnHashes.getOrNull(index) != current.conversationTurnHashes.getOrNull(index)) {
                return PromptDiff("conversation:$index", interval)
            }
        }
        if (previous.fullRequestHash != current.fullRequestHash) return PromptDiff("tail", interval)
        return PromptDiff("none", interval)
    }

    /** 与上一次同端点请求比较，并把当前指纹记为新的基准 */
    fun remember(endpointKey: String, current: PromptFingerprint): PromptDiff {
        val previous = previousByEndpoint.put(endpointKey, current)
        return compare(previous, current)
    }

    /** 只用于摘要：取 SHA-256 前 8 字节（16 个十六进制字符），不输出原文 */
    fun hash(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(HASH_HEX_CHARS)
        for (i in 0 until 8) {
            val value = bytes[i].toInt() and 0xFF
            sb.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return sb.toString()
    }
}
