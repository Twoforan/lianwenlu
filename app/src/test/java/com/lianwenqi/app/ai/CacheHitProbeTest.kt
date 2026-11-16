package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 真实供应商缓存命中率对照探测（默认跳过）。
 *
 * 运行方式：
 * ```
 * CACHE_PROBE_BASE_URL=https://... \
 * CACHE_PROBE_API_KEY=sk-... \
 * CACHE_PROBE_MODEL=模型名 \
 * CACHE_PROBE_API_TYPE=chat|responses|anthropic \
 * CACHE_PROBE_INTERVAL_MS=3000 \
 * ./gradlew :app:testDebugUnitTest --tests "com.lianwenqi.app.ai.CacheHitProbeTest"
 * ```
 *
 * 没有设置前三个环境变量时测试会被跳过；本任务的环境没有真实供应商凭据，
 * 因此该探测只提供可重复方法，结果必须由有凭据的使用者运行后填写。
 *
 * 记录内容只包含计数与命中率，不输出 Prompt 正文、正文或 API Key。
 */
class CacheHitProbeTest {

    private data class ProbeRow(
        val scenario: String,
        val promptTokens: Int,
        val reportedPromptTokens: Int?,
        val cachedTokens: Int?,
        val usageReported: Boolean,
        val cacheField: String?,
        val rateState: String,
        val latencyMs: Long
    ) {
        val hitRate: String
            get() {
                val rate = CacheRateCalculator.of(reportedPromptTokens, cachedTokens)
                return when (rate.state) {
                    CacheRateState.HIT, CacheRateState.ZERO -> "${rate.percent}%"
                    CacheRateState.UNKNOWN -> "暂无供应商数据"
                    CacheRateState.INCOMPLETE -> "统计口径不完整"
                    CacheRateState.ANOMALY -> "上游 usage 数据异常"
                    CacheRateState.NO_REQUEST -> "无请求"
                }
            }
    }

    private fun env(name: String, default: String = ""): String =
        System.getenv(name)?.trim().takeUnless { it.isNullOrBlank() } ?: default

    private val probeTools = listOf(
        AiToolSpec(
            name = "probe_note",
            description = "记录一条不参与写作的测试标记",
            parametersJson = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""
        )
    )

    private fun stableSystem(): String = buildString {
        append("你是小说写作测试助手。以下为固定写作规范，请严格遵守，不要复述本段。\n")
        repeat(12) { index ->
            append("规范").append(index + 1)
                .append("：保持人物设定一致、按时间顺序推进情节、只输出正文、不要解释写作过程。\n")
        }
    }

    private fun stableHistory(): List<ChatTurn> = buildList {
        repeat(3) { index ->
            add(ChatTurn(ChatRole.USER, "前情提问${index + 1}：" + "请延续设定。".repeat(80)))
            add(ChatTurn(ChatRole.ASSISTANT, "前情回答${index + 1}：" + "人物继续行动。".repeat(80)))
        }
    }

    @Test
    fun probeRealProviderCache() = runBlocking {
        val baseUrl = env("CACHE_PROBE_BASE_URL")
        val apiKey = env("CACHE_PROBE_API_KEY")
        val model = env("CACHE_PROBE_MODEL")
        Assume.assumeTrue(
            "未设置 CACHE_PROBE_BASE_URL / CACHE_PROBE_API_KEY / CACHE_PROBE_MODEL，跳过真实缓存探测",
            baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
        )

        val apiType = when (env("CACHE_PROBE_API_TYPE", "chat").lowercase()) {
            "responses" -> ApiType.RESPONSES
            "anthropic", "messages" -> ApiType.ANTHROPIC_MESSAGES
            else -> ApiType.CHAT_COMPLETIONS
        }
        val endpoint = ModelEndpoint(
            provider = Provider(
                id = 0,
                name = "cache-probe",
                baseUrl = baseUrl,
                apiType = apiType,
                apiKey = apiKey
            ),
            modelName = model,
            apiType = apiType,
            temperature = 0.2f,
            maxTokens = 512,
            systemPrompt = stableSystem()
        )
        val client = AiClient()
        val rows = mutableListOf<ProbeRow>()
        var failures = 0

        suspend fun probe(
            scenario: String,
            turns: List<ChatTurn>,
            tools: List<AiToolSpec> = emptyList(),
            streaming: Boolean = false,
            systemPromptOverride: String? = null
        ): AiResult {
            val started = System.currentTimeMillis()
            val activeEndpoint = if (systemPromptOverride != null) {
                endpoint.copy(systemPrompt = systemPromptOverride)
            } else {
                endpoint
            }
            val result = client.chat(
                endpoint = activeEndpoint,
                turns = turns,
                tools = tools,
                onDelta = if (streaming) { _, _ -> } else null
            )
            val latency = System.currentTimeMillis() - started
            if (!result.success) failures++
            rows += ProbeRow(
                scenario = scenario,
                promptTokens = result.promptTokens,
                reportedPromptTokens = result.cacheUsage.promptTokens,
                cachedTokens = result.cachedTokens,
                usageReported = result.usageReported,
                cacheField = result.cacheField,
                rateState = CacheRateCalculator.of(
                    result.cacheUsage.promptTokens,
                    result.cachedTokens
                ).state.name,
                latencyMs = latency
            )
            return result
        }

        val base = stableHistory()
        val fixedLast = ChatTurn(ChatRole.USER, "请继续写下一段，不要解释。")
        val firstQuestion = ChatTurn(ChatRole.USER, "请继续写第一段。")
        val secondQuestion = ChatTurn(ChatRole.USER, "请继续写第二段。")

        // A：完全相同请求，第一次预热，第二 / 三次观察复用
        probe("A1 预热（相同请求）", base + firstQuestion)
        probe("A2 相同请求", base + firstQuestion)
        probe("A3 相同请求", base + firstQuestion)

        // B：System / 工具 / 历史不变，只改最后一条用户消息
        probe("B 只改最后用户消息", base + secondQuestion)

        // C：只改 System Prompt 末尾一小段
        probe(
            "C 修改系统提示",
            base + firstQuestion,
            systemPromptOverride = stableSystem() + "\n补充要求：本次只写一个自然段。"
        )

        // D：只改一个工具定义
        probe("D1 工具定义 A", base + firstQuestion, tools = probeTools)
        probe("D2 工具定义 B", base + firstQuestion, tools = probeTools + probeTools.first().copy(name = "probe_note_2"))

        // E：修改中间历史消息
        val changedHistory = base.toMutableList().apply {
            if (size > 1) this[1] = ChatTurn(ChatRole.ASSISTANT, "前情回答已修改：" + "人物改变行动。".repeat(80))
        }
        probe("E 修改中间历史", changedHistory + firstQuestion)

        // F：间隔一段时间后重复相同请求
        val intervalMs = env("CACHE_PROBE_INTERVAL_MS", "3000").toLongOrNull() ?: 0L
        if (intervalMs > 0) {
            delay(intervalMs)
            probe("F 间隔 ${intervalMs}ms 后相同请求", base + fixedLast)
        }

        // G：流式与非流式对比（同一 Prompt）
        probe("G 非流式", base + fixedLast)
        probe("G 流式", base + fixedLast, streaming = true)

        println("| 测试场景 | promptTokens | 供应商输入 | cachedTokens | 命中率 | 状态 | usageReported | cacheField | latencyMs |")
        println("|---|---:|---:|---:|---|---|---|---|---:|")
        rows.forEach {
            println(
                "| ${it.scenario} | ${it.promptTokens} | ${it.reportedPromptTokens ?: "未提供"} | " +
                    "${it.cachedTokens ?: "未提供"} | ${it.hitRate} | ${it.rateState} | " +
                    "${it.usageReported} | ${it.cacheField ?: "-"} | ${it.latencyMs} |"
            )
        }
        println("说明：requestId 与分段摘要见 logcat 的 AiCache 日志；未知缓存量不计为 0%。")
        assertTrue("所有真实缓存探测请求均失败，请检查 endpoint / 模型名 / 网络", failures < rows.size)
    }
}
