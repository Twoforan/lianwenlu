package com.lianwenqi.app.agent.context

import com.lianwenqi.app.agent.planning.AgentModelClient
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.data.entity.ChatRole

/**
 * Agent 上下文自动压缩。
 *
 * 上下文接近模型窗口上限时，把已有对话压缩成结构化摘要，再与最近的消息重新拼装，
 * 保证任务目标、计划状态、已完成工作、工具结果与待确认操作不丢失。
 *
 * 只负责“估算 + 生成摘要 + 重建消息”；何时触发、是否需要用户确认由调用方决定。
 */
class AgentContextCompressor(
    private val modelClient: AgentModelClient,
    private val triggerRatio: Float = DEFAULT_TRIGGER_RATIO,
    private val maxSummaryChars: Int = DEFAULT_MAX_SUMMARY_CHARS,
    private val defaultContextWindow: Int = DEFAULT_CONTEXT_WINDOW
) {

    sealed interface Result {
        data class Success(
            val summary: String,
            val beforeTokens: Int,
            val afterTokens: Int
        ) : Result

        data class Failure(val message: String) : Result
    }

    /** 模型未配置真实窗口时使用保守默认值，避免功能完全不生效 */
    fun windowTokens(configuredWindow: Int): Int =
        if (configuredWindow > 0) configuredWindow else defaultContextWindow

    fun shouldCompress(
        turns: List<ChatTurn>,
        configuredWindow: Int,
        actualPromptTokens: Int = -1
    ): Boolean {
        val window = windowTokens(configuredWindow)
        if (window < MIN_COMPRESSIBLE_WINDOW) return false
        val threshold = (window * triggerRatio).toInt()
        // 服务端返回的真实用量优先于字符估算
        if (actualPromptTokens > 0) return actualPromptTokens >= threshold
        return ContextEstimator.estimateTokens(turns) >= threshold
    }

    suspend fun compress(
        modelId: Long,
        turns: List<ChatTurn>,
        configuredWindow: Int,
        /** 是否原样保留首条任务指令；自动模式不截断资料时，首条指令可能远超窗口，需随对话一起压缩 */
        preserveFirstUser: Boolean = true
    ): Result {
        val window = windowTokens(configuredWindow)
        if (window < MIN_COMPRESSIBLE_WINDOW) {
            return Result.Failure("模型上下文窗口过小，无法进行自动压缩")
        }
        val beforeTokens = ContextEstimator.estimateTokens(turns)
        val conversation = renderConversation(turns, window)
        if (conversation.isBlank()) {
            return Result.Failure("当前没有可压缩的对话内容")
        }
        val request = listOf(
            ChatTurn(ChatRole.SYSTEM, COMPRESSION_SYSTEM_PROMPT),
            ChatTurn(
                ChatRole.USER,
                buildString {
                    appendLine("请把下面的 Agent 对话压缩成一份结构化摘要，供后续继续执行时使用。")
                    appendLine("必须包含：")
                    appendLine("1. 用户目标与最终要求；")
                    appendLine("2. 重要约束、偏好与禁止项；")
                    appendLine("3. 已确认的事实与决策；")
                    appendLine("4. 计划及每个步骤的状态（已完成 / 进行中 / 未开始 / 已跳过 / 失败）；")
                    appendLine("5. 已完成的工作与关键产出；")
                    appendLine("6. 当前步骤与继续执行的位置；")
                    appendLine("7. 关键工具调用结果（保留书名、章节号、节点 id 等可定位信息）；")
                    appendLine("8. 未解决问题与待确认的写入操作（未确认的必须标注“待确认”，不得写成已完成）。")
                    appendLine("只输出摘要正文，不要执行任务、不要输出工具调用，控制在 $maxSummaryChars 字以内。")
                    appendLine()
                    appendLine("【对话内容】")
                    append(conversation)
                }
            )
        )
        val response = modelClient.complete(modelId, request)
        if (!response.success) {
            return Result.Failure(
                response.errorMessage.ifBlank { "上下文压缩请求失败" }
            )
        }
        val summary = response.content.trim().take(maxSummaryChars)
        if (summary.isBlank()) {
            return Result.Failure("上下文压缩返回为空")
        }
        val rebuilt = rebuildTurns(turns, summary, window, preserveFirstUser)
        return Result.Success(
            summary = summary,
            beforeTokens = beforeTokens,
            afterTokens = ContextEstimator.estimateTokens(rebuilt)
        )
    }

    /**
     * 压缩后的消息结构：保留系统提示与首条任务指令，插入摘要，再保留最近的消息。
     * 首条任务指令是当前步骤的执行依据，计划模式下不能被摘要替代；
     * 自动模式因保留完整资料，首条指令可能本身就超出窗口，此时由摘要承载目标与资料要点。
     */
    fun rebuildTurns(
        turns: List<ChatTurn>,
        summary: String,
        configuredWindow: Int,
        preserveFirstUser: Boolean = true
    ): List<ChatTurn> {
        val window = windowTokens(configuredWindow)
        val systemTurns = turns.filter { it.role == ChatRole.SYSTEM }
        val originalFirstUser = turns.firstOrNull { it.role == ChatRole.USER }
        val firstUser = if (preserveFirstUser) originalFirstUser else null

        val tailBudget = (window * TAIL_RATIO).toInt()
        val tail = mutableListOf<ChatTurn>()
        var used = 0
        turns.asReversed().forEach { turn ->
            if (turn.role == ChatRole.SYSTEM || turn === originalFirstUser) return@forEach
            val cost = ContextEstimator.estimateTokens(listOf(turn))
            if (used + cost > tailBudget) return@forEach
            used += cost
            tail += turn
        }
        tail.reverse()

        val rebuilt = mutableListOf<ChatTurn>()
        rebuilt += systemTurns
        firstUser?.let { rebuilt += it }
        rebuilt += ChatTurn(
            ChatRole.USER,
            "【上下文摘要（此前对话已压缩）】\n$summary"
        )
        rebuilt += tail
        return rebuilt
    }

    /** 渲染对话文本；超出预算时保留开头目标与最近内容，中间部分省略 */
    private fun renderConversation(turns: List<ChatTurn>, window: Int): String {
        val budget = (window * INPUT_RATIO).toInt()
        val rendered = turns
            .filter { it.role != ChatRole.SYSTEM }
            .map {
                val role = when (it.role) {
                    ChatRole.USER -> "用户"
                    ChatRole.ASSISTANT -> "助手"
                    ChatRole.SYSTEM -> "系统"
                }
                "$role：${it.content}"
            }
        if (rendered.isEmpty()) return ""
        val total = rendered.sumOf { ContextEstimator.estimateTokens(it) + 1 }
        if (total <= budget) return rendered.joinToString("\n")

        val headBudget = (budget * HEAD_RATIO).toInt()
        val tailBudget = (budget - headBudget).coerceAtLeast(0)
        val head = mutableListOf<String>()
        var headUsed = 0
        var index = 0
        while (index < rendered.size && headUsed < headBudget) {
            val cost = ContextEstimator.estimateTokens(rendered[index]) + 1
            if (headUsed + cost > headBudget) break
            headUsed += cost
            head += rendered[index]
            index++
        }
        val tail = mutableListOf<String>()
        var tailUsed = 0
        var tailIndex = rendered.lastIndex
        while (tailIndex >= index && tailUsed < tailBudget) {
            val cost = ContextEstimator.estimateTokens(rendered[tailIndex]) + 1
            if (tailUsed + cost > tailBudget) break
            tailUsed += cost
            tail.add(0, rendered[tailIndex])
            tailIndex--
        }
        return buildString {
            if (head.isNotEmpty()) append(head.joinToString("\n"))
            if (head.isNotEmpty() && tail.isNotEmpty()) appendLine("\n……（中间对话已省略）……")
            if (tail.isNotEmpty()) append(tail.joinToString("\n"))
        }
    }

    companion object {
        /** 达到模型窗口的该比例时触发压缩，给压缩请求与输出预留空间 */
        const val DEFAULT_TRIGGER_RATIO = 0.8f

        /** 压缩后重新保留的最近消息预算占窗口比例 */
        private const val TAIL_RATIO = 0.25f

        /** 压缩请求输入占窗口比例 */
        private const val INPUT_RATIO = 0.6f

        /** 截断时开头保留比例，其余留给最近内容 */
        private const val HEAD_RATIO = 0.3f

        const val DEFAULT_MAX_SUMMARY_CHARS = 2_500

        /** 模型未配置真实窗口时的默认值：500K token */
        const val DEFAULT_CONTEXT_WINDOW = 500 * 1024

        /** 小于该窗口时不做自动压缩，避免压缩本身放不下 */
        const val MIN_COMPRESSIBLE_WINDOW = 4_096

        /** 自动模式下向用户确认压缩的问题；按完整文本识别，避免与本轮业务问答混淆 */
        const val QUESTION_TEXT = "上下文即将达到上限，是否压缩后继续？"

        /** 压缩请求的系统提示：只做摘要，不执行任务 */
        private const val COMPRESSION_SYSTEM_PROMPT =
            "你是 Agent 上下文压缩器。你的唯一任务是把给定对话压缩成结构化摘要，" +
                "必须保留任务目标、约束、已确认决策、计划与步骤状态、已完成工作、当前进度、" +
                "可定位的工具结果和未解决问题。未确认的写入操作必须标注为待确认。" +
                "只输出摘要正文，不要执行任何任务，不要输出工具调用。"

        /** 用户回答是否同意压缩；只有明确的否定才拒绝 */
        fun isAffirmative(answer: String): Boolean {
            val text = answer.trim().lowercase()
            if (text.isEmpty()) return false
            val negative = listOf("不压缩", "不用", "不要", "不需要", "否", "取消", "新开", "停止", "no")
            if (negative.any { text.contains(it) }) return false
            return true
        }
    }
}
