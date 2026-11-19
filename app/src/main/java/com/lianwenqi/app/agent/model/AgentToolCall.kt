package com.lianwenqi.app.agent.model

/** 模型返回的一次工具调用（参数为原始 JSON 字符串） */
data class AgentToolCallInfo(
    val id: String,
    val name: String,
    val arguments: String
)
