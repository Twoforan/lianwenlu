package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.model.AgentToolCallInfo
import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.AiToolSpec
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.entity.ApiType
import kotlinx.coroutines.CancellationException

/** Agent 模型请求的 Token 统计；未知沿用项目 -1 口径 */
data class AgentModelUsage(
    val promptTokens: Int = -1,
    val completionTokens: Int = 0,
    val cachedTokens: Int = -1
)

/** 一次 Agent 模型请求的结果（不包含普通聊天状态） */
data class AgentModelResponse(
    val success: Boolean,
    val content: String = "",
    val errorMessage: String = "",
    val usage: AgentModelUsage = AgentModelUsage(),
    val modelId: Long = 0,
    val modelName: String = "",
    val providerName: String = "",
    val apiType: String = ApiType.CHAT_COMPLETIONS.name,
    val temperature: Float = AgentModelClient.AGENT_TEMPERATURE,
    val latencyMs: Long = 0,
    /** 模型发起的工具调用（F4） */
    val toolCalls: List<AgentToolCallInfo> = emptyList(),
    /** 供应商拒绝 tools 字段并已自动降级（F4 执行时视为不可用） */
    val toolsUnsupported: Boolean = false,
    /** F7：结构化失败原因；引擎据此做失败分类，不能只按文本猜测 */
    val error: AiError? = null
)

/**
 * Agent 独立模型调用入口。
 *
 * 复用现有 `ConfigRepository.endpointFor()` 与 `AiClient.chat()`，
 * 但只复制端点并做 Agent 专属覆盖：
 * - 清空模型预设 System Prompt（避免把普通聊天 Prompt 带进 Agent）；
 * - 固定较低温度；
 * - 不携带普通聊天历史。
 *
 * [invoke] 用于无工具的规划请求；[invokeWithTools] 用于 F4 只读工具调用。
 * 通过注入 lambda 便于单元测试使用 Fake，不依赖真实网络。
 */
class AgentModelClient(
    private val endpointFor: suspend (Long) -> ModelEndpoint?,
    private val invoke: suspend (ModelEndpoint, List<ChatTurn>) -> AiResult,
    private val invokeWithTools: (suspend (ModelEndpoint, List<ChatTurn>, List<AiToolSpec>) -> AiResult)? = null
) {

    /** F3 规划请求：不携带工具 */
    suspend fun complete(modelId: Long, turns: List<ChatTurn>): AgentModelResponse =
        runRequest(modelId, turns, tools = null)

    /** F4 工具调用请求：携带只读工具定义，允许模型返回 tool_calls */
    suspend fun completeWithTools(
        modelId: Long,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec>
    ): AgentModelResponse =
        runRequest(modelId, turns, tools = tools)

    /** 当前模型单次最大输出 token；<= 0 表示不限输出 */
    suspend fun outputTokenLimit(modelId: Long): Int =
        runCatching { endpointFor(modelId)?.maxTokens ?: 0 }.getOrDefault(0)

    /** 当前模型真实 Token 上下文窗口；0 表示未知 */
    suspend fun contextWindow(modelId: Long): Int =
        runCatching { endpointFor(modelId)?.contextWindow ?: 0 }.getOrDefault(0)

    private suspend fun runRequest(
        modelId: Long,
        turns: List<ChatTurn>,
        tools: List<AiToolSpec>?
    ): AgentModelResponse {
        val base = endpointFor(modelId)
            ?: return AgentModelResponse(
                success = false,
                errorMessage = "未找到模型配置，请先在设置中添加并选择模型",
                modelId = modelId
            )
        val endpoint = base.copy(
            systemPrompt = "",
            temperature = AGENT_TEMPERATURE
        )
        if (tools != null && invokeWithTools == null) {
            return AgentModelResponse(
                success = false,
                errorMessage = "Agent 工具调用入口未配置",
                modelId = modelId,
                modelName = endpoint.modelName,
                providerName = endpoint.provider.name,
                apiType = endpoint.apiType.name
            )
        }
        val started = System.nanoTime()
        val result = try {
            if (tools == null) invoke(endpoint, turns)
            else invokeWithTools!!.invoke(endpoint, turns, tools)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AiResult.fail(AiError.fromThrowable(e))
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        val usage = AgentModelUsage(
            promptTokens = result.storedPromptTokens,
            completionTokens = result.completionTokens,
            cachedTokens = result.cachedTokens ?: -1
        )
        val calls = result.toolCalls.map {
            AgentToolCallInfo(id = it.id, name = it.name, arguments = it.arguments)
        }
        val baseInfo = AgentModelResponse(
            success = result.success,
            content = result.content,
            errorMessage = result.error?.userMessage ?: "",
            usage = usage,
            modelId = modelId,
            modelName = endpoint.modelName,
            providerName = endpoint.provider.name,
            apiType = endpoint.apiType.name,
            temperature = endpoint.temperature,
            latencyMs = latency,
            toolCalls = calls,
            toolsUnsupported = result.toolsUnsupported,
            error = result.error
        )
        return when {
            !result.success -> baseInfo
            result.content.isBlank() && calls.isEmpty() ->
                baseInfo.copy(success = false, errorMessage = "模型返回为空")
            else -> baseInfo
        }
    }

    companion object {
        /** 规划 / 执行请求温度：低于普通写作，减少随机性 */
        const val AGENT_TEMPERATURE = 0.2f
    }
}
