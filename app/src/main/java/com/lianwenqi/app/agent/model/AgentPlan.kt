package com.lianwenqi.app.agent.model

import kotlinx.serialization.Serializable

/** 计划风险等级 */
object AgentRiskLevel {
    const val LOW = "LOW"
    const val MEDIUM = "MEDIUM"
    const val HIGH = "HIGH"

    /** 旧版最高风险等级，仅用于兼容历史计划；新计划统一使用 [HIGH] */
    const val CRITICAL = "CRITICAL"

    /** 新计划和编辑器可选的三个等级：低 / 中 / 高危 */
    val selectable: List<String> = listOf(LOW, MEDIUM, HIGH)

    /** 历史数据仍可能包含 CRITICAL，保持合法 */
    val all: List<String> = selectable + CRITICAL

    fun isValid(value: String): Boolean = value in all

    /** 展示文案：低 / 中 / 高危 */
    fun label(value: String): String = when (value) {
        LOW -> "低"
        MEDIUM -> "中"
        HIGH, CRITICAL -> "高危"
        else -> value
    }

    /** 把历史 CRITICAL 归一为高危 */
    fun normalize(value: String): String = if (value == CRITICAL) HIGH else value
}

/**
 * 结构化执行计划（F3 只生成、校验、展示，不执行）。
 *
 * 该结构同时用于模型 JSON 解析与 `agent_task.planJson` 持久化。
 */
@Serializable
data class AgentPlan(
    val schemaVersion: Int = 1,
    val taskTitle: String = "",
    val goalSummary: String = "",
    val thinkingSummary: String = "",
    val needsClarification: Boolean = false,
    val clarificationQuestions: List<String> = emptyList(),
    /** 需要澄清时可展示的候选回答；用户也可以自行输入 */
    val clarificationOptions: List<String> = emptyList(),
    val steps: List<AgentPlanStep> = emptyList()
)

@Serializable
data class AgentPlanStep(
    val stepId: String = "",
    val title: String = "",
    val description: String = "",
    val capabilityKey: String = "",
    val expectedOutput: String = "",
    val dependencies: List<String> = emptyList(),
    val riskLevel: String = AgentRiskLevel.LOW
)

/** 计划解析结果 */
sealed interface AgentPlanParseResult {
    data class Success(val plan: AgentPlan, val normalizedJson: String) : AgentPlanParseResult
    data class Failure(val message: String) : AgentPlanParseResult
}

/** 计划校验结果；结构错误使 [isValid] 为 false，能力缺失只产生警告 */
data class AgentPlanValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** 代码中未登记的能力 */
    val unknownCapabilities: List<String> = emptyList(),
    /** 已登记但 F3 尚不可执行的能力 */
    val notYetAvailableCapabilities: List<String> = emptyList(),
    /** 单步疑似批量处理超过上限的同类对象，建议拆分步骤；不阻塞执行 */
    val splitSuggestions: List<String> = emptyList(),
    val stepCount: Int = 0
)
