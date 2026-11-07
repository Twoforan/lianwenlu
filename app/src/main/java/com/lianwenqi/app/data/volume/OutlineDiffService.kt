package com.lianwenqi.app.data.volume

import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.data.CompressionOutlineUpdateMode
import com.lianwenqi.app.data.ConfigRepository
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.ChatRole

/**
 * 大纲差异分析（独立模型请求，复用现有模型网关）。
 *
 * 约束：
 * - 不写 ChatSession / ChatMessage；
 * - 「关」模式在发请求前直接返回 Skipped，零请求、零扣费；
 * - 真实 usage 原样放进 [OutlineDiffOutcome] 供 F5 的压缩任务统计汇总；
 * - 原始响应保留用于诊断，但永不作为正式写入内容。
 */
class OutlineDiffService(
    private val modelLoader: suspend (Long) -> AiModel?,
    private val chat: suspend (AiModel, AiRequest) -> AiResult
) {

    suspend fun analyze(
        request: OutlineDiffRequest,
        mode: String = CompressionOutlineUpdateMode.ASK
    ): OutlineDiffOutcome {
        if (CompressionOutlineUpdateMode.normalize(mode) == CompressionOutlineUpdateMode.OFF) {
            return OutlineDiffOutcome.Skipped("大纲更新已关闭：未发起任何大纲分析请求")
        }
        if (request.summary.isBlank()) {
            return OutlineDiffOutcome.Failed("本卷梗概为空，无法进行大纲差异分析")
        }
        val model = modelLoader(request.modelId)
            ?: return OutlineDiffOutcome.Failed("未找到所选模型，请先在设置中检查模型配置")

        // 输出上限只取模型设置：不限（<=0）时不携带 max_tokens，
        // 避免推理模型的思考过程占满一个应用侧硬编码的小上限而被截断
        val maxOutput = model.maxTokens.takeIf { it > 0 }?.coerceIn(512, 4096) ?: 0
        val aiRequest = AiRequest(
            turns = listOf(ChatTurn(ChatRole.USER, OutlineDiffPrompt.user(request))),
            model = model.name,
            temperature = DIFF_TEMPERATURE,
            maxTokens = maxOutput,
            systemPrompt = OutlineDiffPrompt.SYSTEM
        )
        val result = chat(model, aiRequest)
        val usage = CompressionUsage().plus(result)
        if (!result.success) {
            return OutlineDiffOutcome.Failed(
                result.error?.userMessage ?: "大纲差异分析请求失败",
                usage,
                result.content
            )
        }
        val text = result.content.trim()
        if (text.isBlank()) {
            return OutlineDiffOutcome.Failed("模型未返回大纲差异建议", usage, result.content)
        }
        return when (val parsed = OutlineDiffCodec.parse(text, request)) {
            is OutlineDiffCodec.Result.Success -> OutlineDiffOutcome.Success(
                suggestions = parsed.suggestions,
                usage = usage,
                rawContent = result.content,
                note = parsed.note
            )

            is OutlineDiffCodec.Result.Failure -> OutlineDiffOutcome.Failed(
                parsed.message,
                usage,
                result.content
            )
        }
    }

    companion object {
        private const val DIFF_TEMPERATURE = 0.2f

        /** 生产环境：复用现有模型配置与 [AiClient]，不新建 API 客户端 */
        fun create(config: ConfigRepository, ai: AiClient): OutlineDiffService =
            OutlineDiffService(
                modelLoader = { modelId -> config.getModel(modelId) },
                chat = { model, request ->
                    val endpoint = config.endpointFor(model.id)
                    if (endpoint == null) {
                        AiResult.fail("模型配置不完整，请先检查供应商与模型设置")
                    } else {
                        ai.chat(
                            endpoint.copy(
                                systemPrompt = request.systemPrompt,
                                temperature = request.temperature,
                                maxTokens = request.maxTokens
                            ),
                            request.turns
                        )
                    }
                }
            )
    }
}
