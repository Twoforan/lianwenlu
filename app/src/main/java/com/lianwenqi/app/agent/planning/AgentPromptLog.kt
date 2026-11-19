package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.data.agentJson
import kotlinx.serialization.Serializable

/**
 * Agent 模型请求日志快照。
 *
 * 只保存可审计的摘要：不保存完整 System Prompt、不保存 API Key；
 * userPrompt 与 rawResponse 均有长度上限。
 */
@Serializable
data class AgentPromptLog(
    val sessionId: Long,
    val taskId: Long,
    val modelId: Long,
    val modelName: String = "",
    val providerName: String = "",
    val apiType: String = "",
    val temperature: Float = 0f,
    val systemPromptHash: String = "",
    val userPromptHash: String = "",
    val userPrompt: String = "",
    val rawResponse: String = "",
    val thinkingSummary: String = "",
    val error: String = "",
    val promptTokens: Int = -1,
    val completionTokens: Int = 0,
    val cachedTokens: Int = -1,
    val latencyMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun encode(): String = agentJson.encodeToString(serializer(), this)
}
