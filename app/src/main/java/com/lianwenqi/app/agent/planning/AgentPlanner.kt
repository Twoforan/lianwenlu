package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanParseResult
import com.lianwenqi.app.agent.model.AgentPlanValidationResult
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.ChatRole

/** 一次规划调用的可审计信息（不含 sessionId / taskId，由调用方补充） */
data class AgentPlanningTrace(
    val modelId: Long,
    val modelName: String,
    val providerName: String,
    val apiType: String,
    val temperature: Float,
    val systemPrompt: String,
    val userPrompt: String,
    val rawResponse: String,
    val latencyMs: Long,
    val usage: AgentModelUsage,
    val errorMessage: String
)

sealed interface AgentPlanningResult {
    val trace: AgentPlanningTrace

    data class Success(
        val plan: AgentPlan,
        val normalizedJson: String,
        val validation: AgentPlanValidationResult,
        override val trace: AgentPlanningTrace
    ) : AgentPlanningResult

    data class Failure(
        val message: String,
        override val trace: AgentPlanningTrace
    ) : AgentPlanningResult
}

/**
 * AgentPlanner：只做「分析目标 → 生成计划」，不执行工具。
 *
 * 调用顺序：构造独立请求 → 模型非流式返回 → 严格解析 → 结构校验。
 */
class AgentPlanner(
    private val modelClient: AgentModelClient,
    private val requestBuilder: AgentRequestBuilder = AgentRequestBuilder(),
    private val parser: AgentPlanParser = AgentPlanParser(),
    private val validator: AgentPlanValidator = AgentPlanValidator()
) {

    suspend fun plan(
        modelId: Long,
        goal: String,
        sessionTitle: String,
        book: Book?,
        /** 文件 / 链接附件正文（已格式化，带附件名） */
        attachmentText: String = "",
        /** 图片附件 data URL，随用户消息以多模态形式发送 */
        imageDataUrls: List<String> = emptyList(),
        /** 用户对上一轮澄清问题的补充回答 */
        clarificationAnswers: List<String> = emptyList(),
        /** 上一次的计划 JSON；补充指导时作为“在此基础上调整”的输入 */
        previousPlanJson: String = ""
    ): AgentPlanningResult {
        val request = requestBuilder.build(
            goal,
            sessionTitle,
            book,
            attachmentText,
            imageDataUrls,
            clarificationAnswers,
            previousPlanJson
        )
        val response = modelClient.complete(modelId, request.turns)
        if (!response.success) {
            return AgentPlanningResult.Failure(
                message = response.errorMessage.ifBlank { "模型规划请求失败" },
                trace = response.traceOf(request)
            )
        }
        val first = parseAndValidate(response, request)
        if (first.isValidPlan()) return first

        // 一次自动修复：模型可能返回空计划 / 非 JSON 说明文字，要求它只输出完整 JSON
        val repairRequest = AgentPlanningRequest(
            turns = request.turns + listOf(
                ChatTurn(ChatRole.ASSISTANT, response.content.take(4_000)),
                ChatTurn(
                    ChatRole.USER,
                    "上面的输出不是可用的执行计划 JSON。请只输出一个完整的计划 JSON 对象，" +
                        "必须包含非空的 taskTitle、goalSummary 和至少一个 steps；" +
                        "不要输出解释文字或 Markdown 代码围栏。"
                )
            ),
            systemPrompt = request.systemPrompt,
            userPrompt = request.userPrompt + "\n\n【修复要求】只输出完整计划 JSON。"
        )
        val retry = modelClient.complete(modelId, repairRequest.turns)
        return if (retry.success) {
            parseAndValidate(retry, repairRequest)
        } else {
            AgentPlanningResult.Failure(
                message = retry.errorMessage.ifBlank { "模型规划请求失败" },
                trace = retry.traceOf(repairRequest)
            )
        }
    }

    private fun parseAndValidate(
        response: AgentModelResponse,
        request: AgentPlanningRequest
    ): AgentPlanningResult {
        val trace = response.traceOf(request)
        return when (val parsed = parser.parse(response.content)) {
            is AgentPlanParseResult.Failure -> AgentPlanningResult.Failure(
                message = "计划解析失败：${parsed.message}",
                trace = trace
            )
            is AgentPlanParseResult.Success -> {
                val validation = validator.validate(parsed.plan)
                if (!validation.isValid) {
                    AgentPlanningResult.Failure(
                        message = "计划校验未通过：" + validation.errors.take(3).joinToString("；"),
                        trace = trace
                    )
                } else {
                    AgentPlanningResult.Success(
                        plan = parsed.plan,
                        normalizedJson = parsed.normalizedJson,
                        validation = validation,
                        trace = trace
                    )
                }
            }
        }
    }

    private fun AgentPlanningResult.isValidPlan(): Boolean = this is AgentPlanningResult.Success

    private fun AgentModelResponse.traceOf(request: AgentPlanningRequest) = AgentPlanningTrace(
        modelId = modelId,
        modelName = modelName,
        providerName = providerName,
        apiType = apiType,
        temperature = temperature,
        systemPrompt = request.systemPrompt,
        userPrompt = request.userPrompt,
        rawResponse = content,
        latencyMs = latencyMs,
        usage = usage,
        errorMessage = errorMessage
    )
}
