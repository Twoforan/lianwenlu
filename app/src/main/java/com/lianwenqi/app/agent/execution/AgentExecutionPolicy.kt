package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.model.AgentCapabilityCatalog

/** 执行硬性上限；F4 使用默认值，测试可注入更小上限 */
data class AgentExecutionLimits(
    val maxToolCallsPerTask: Int = 30,
    val maxModelRoundsPerTask: Int = 20,
    val maxIdenticalCalls: Int = 2,
    val maxContextToolResultChars: Int = 32 * 1024,
    val maxStepOutputChars: Int = 8 * 1024,
    val maxModelResponseChars: Int = 64 * 1024,
    val maxTaskDurationMs: Long = 10 * 60 * 1000L
)

/**
 * 执行策略：可执行能力、步骤是否需要真实工具结果。
 *
 * 允许只读检索、分析整理、生成草稿与受控写入；
 * 生成只产出草稿，写入必须经用户确认。
 */
object AgentExecutionPolicy {

    const val MAX_TOOL_CALLS_PER_TASK = 30
    const val MAX_MODEL_ROUNDS_PER_TASK = 20
    const val MAX_IDENTICAL_CALLS = 2
    const val MAX_TOOL_ARGUMENTS_CHARS = 16 * 1024
    const val MAX_TOOL_RESULT_CHARS = 32 * 1024
    const val MAX_MODEL_RESPONSE_CHARS = 64 * 1024
    const val MAX_CONTEXT_TOOL_RESULT_CHARS = 32 * 1024
    const val MAX_STEP_OUTPUT_CHARS = 8 * 1024
    const val MAX_TASK_DURATION_MS = 10 * 60 * 1000L

    /** F4 可执行的读取 / 检索能力 */
    private val READ_CAPABILITIES = setOf(
        "READ_BOOK_INFO",
        "READ_OUTLINE",
        "READ_CHAPTER_SYNOPSIS",
        "READ_CHAPTER_CONTENT",
        "READ_CHAPTER_LIST",
        "SEARCH_CHAPTERS",
        "RETRIEVE_MEMORY",
        "READ_VOLUME_SYNOPSIS",
        "READ_STORY_CONTEXT"
    )

    /** 只做分析 / 整理的能力：允许模型执行，但必须有真实工具结果支撑 */
    private val ANALYSIS_CAPABILITIES = setOf(
        "ANALYZE_CHARACTER_RELATIONSHIPS",
        "ANALYZE_PLOT_CONSISTENCY",
        "GENERATE_REPORT",
        "VALIDATE_CONTENT"
    )

    /** F6 受控写入能力：必须产生真实写入提议 / 执行记录，且经过权限确认 */
    private val WRITE_CAPABILITIES = setOf(
        "WRITE_CHAPTER_SYNOPSIS",
        "WRITE_CHAPTER_CONTENT",
        "WRITE_OUTLINE",
        "CREATE_OUTLINE_NODE",
        "UPDATE_OUTLINE_NODE",
        "DELETE_OUTLINE_NODE",
        "CREATE_CHAPTER",
        "DELETE_CHAPTER",
        "CREATE_VOLUME",
        "DELETE_VOLUME"
    )

    /** 生成草稿能力：调用现有生成流程，结果不直接写入章节 */
    private val GENERATE_CAPABILITIES = setOf(
        "GENERATE_SYNOPSIS",
        "GENERATE_CONTENT"
    )

    /** 明确禁止的重写 / 澄清 / 未实现检索能力 */
    private val BLOCKED_CAPABILITIES = setOf(
        "REWRITE_CONTENT",
        "ASK_USER_CLARIFICATION",
        "SEARCH_CHARACTER",
        "SEARCH_WORLD"
    )

    val supportedCapabilities: Set<String> =
        READ_CAPABILITIES + ANALYSIS_CAPABILITIES + WRITE_CAPABILITIES + GENERATE_CAPABILITIES

    fun isCapabilityExecutable(capabilityKey: String): Boolean =
        capabilityKey in supportedCapabilities

    fun isBlockedCapability(capabilityKey: String): Boolean =
        capabilityKey in BLOCKED_CAPABILITIES

    /** 是否为只产出草稿、不直接写入章节的生成能力 */
    fun isGenerationCapability(capabilityKey: String): Boolean =
        capabilityKey in GENERATE_CAPABILITIES

    /** F6：是否为需要权限确认的写入能力 */
    fun isWriteCapability(capabilityKey: String): Boolean =
        capabilityKey in WRITE_CAPABILITIES

    /** 该步骤是否必须至少调用一次真实只读工具才能完成 */
    fun requiresTool(capabilityKey: String): Boolean = capabilityKey in READ_CAPABILITIES

    /** 该步骤是否需要前置真实工具结果（分析 / 报告类） */
    fun requiresPriorToolResult(capabilityKey: String): Boolean =
        capabilityKey in ANALYSIS_CAPABILITIES

    fun nodeTypeFor(capabilityKey: String): String = when (capabilityKey) {
        "READ_OUTLINE" -> AgentNodeType.SEARCH_OUTLINE
        "READ_CHAPTER_SYNOPSIS", "READ_CHAPTER_CONTENT", "SEARCH_CHAPTERS",
        "READ_STORY_CONTEXT", "READ_CHAPTER_LIST" -> AgentNodeType.SEARCH_CHAPTER
        "RETRIEVE_MEMORY" -> AgentNodeType.SEARCH_MEMORY
        "ANALYZE_CHARACTER_RELATIONSHIPS", "ANALYZE_PLOT_CONSISTENCY",
        "GENERATE_REPORT" -> AgentNodeType.ANALYZE_RESULT
        "VALIDATE_CONTENT" -> AgentNodeType.VALIDATE
        "WRITE_CHAPTER_SYNOPSIS", "WRITE_CHAPTER_CONTENT", "WRITE_OUTLINE",
        "CREATE_OUTLINE_NODE", "UPDATE_OUTLINE_NODE", "DELETE_OUTLINE_NODE",
        "CREATE_CHAPTER", "DELETE_CHAPTER", "CREATE_VOLUME", "DELETE_VOLUME" ->
            AgentNodeType.WRITE_DATA
        "GENERATE_SYNOPSIS" -> AgentNodeType.GENERATE_SYNOPSIS
        "GENERATE_CONTENT" -> AgentNodeType.GENERATE_CONTENT
        else -> AgentNodeType.PLAN_STEP
    }

    fun capabilityDisplayName(key: String): String =
        AgentCapabilityCatalog.find(key)?.displayName ?: key
}
