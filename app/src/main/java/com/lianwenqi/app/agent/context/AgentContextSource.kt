package com.lianwenqi.app.agent.context

import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType

/**
 * 引用来源的展示名与分类。
 *
 * 上下文中的每份资料都必须带明确来源标签，不能混成一段无法追溯的文字。
 */
object AgentContextSource {

    fun label(sourceType: String): String = when (sourceType) {
        AgentReferenceSourceType.CHAPTER_SYNOPSIS -> "历史章节梗概"
        AgentReferenceSourceType.CHAPTER_CONTENT -> "历史章节正文"
        AgentReferenceSourceType.AGENT_TASK_PLAN -> "历史 Agent 任务计划"
        AgentReferenceSourceType.AGENT_TASK_RESULT -> "历史 Agent 任务结果"
        AgentReferenceSourceType.AGENT_MESSAGE -> "历史 Agent 消息"
        AgentReferenceSourceType.AGENT_TOOL_RESULT -> "历史 Agent 工具结果"
        AgentReferenceSourceType.VOLUME_SYNOPSIS -> "卷梗概"
        AgentReferenceSourceType.AGENT_SESSION -> "历史 Agent 会话"
        AgentReferenceSourceType.CHAT_SESSION -> "普通聊天会话"
        else -> "未知来源"
    }

    fun modeLabel(mode: String): String = when (mode) {
        AgentReferenceMode.CONTEXT -> "CONTEXT（注入上下文）"
        AgentReferenceMode.REFERENCE_ONLY -> "REFERENCE_ONLY（仅登记，不注入）"
        else -> mode
    }

    fun messageKindLabel(kind: String): String = when (kind) {
        AgentMessageKind.USER_INPUT -> "用户消息"
        AgentMessageKind.FINAL_ANSWER -> "Agent 最终回答"
        AgentMessageKind.PLAN -> "Agent 计划消息"
        AgentMessageKind.NOTICE -> "系统提示"
        AgentMessageKind.SUMMARY -> "任务摘要"
        else -> kind
    }

    /** 可信度提示；历史生成内容不能当作数据库权威事实 */
    fun historicalNote(sourceType: String): String = when (sourceType) {
        AgentReferenceSourceType.CHAPTER_SYNOPSIS,
        AgentReferenceSourceType.CHAPTER_CONTENT -> "历史章节资料，不代表当前设定一定仍然有效。"

        AgentReferenceSourceType.AGENT_TASK_PLAN ->
            "历史 Agent 计划，不是当前任务的计划，也不代表用户已授权执行。"

        AgentReferenceSourceType.AGENT_TASK_RESULT ->
            "历史 Agent 最终结果，属于历史生成内容，不是数据库中的权威事实。"

        AgentReferenceSourceType.AGENT_MESSAGE ->
            "历史会话消息；若为 Agent 生成内容，不代表数据库权威事实。"

        AgentReferenceSourceType.AGENT_TOOL_RESULT ->
            "历史工具执行结果，不是当前任务刚刚执行的结果。"

        else -> "历史资料。"
    }

    fun isAgentHistory(sourceType: String): Boolean = sourceType in setOf(
        AgentReferenceSourceType.AGENT_TASK_PLAN,
        AgentReferenceSourceType.AGENT_TASK_RESULT,
        AgentReferenceSourceType.AGENT_MESSAGE,
        AgentReferenceSourceType.AGENT_TOOL_RESULT
    )
}
