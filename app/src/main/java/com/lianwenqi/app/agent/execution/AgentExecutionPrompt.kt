package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanStep

/**
 * F4 执行 Prompt。
 *
 * System 稳定；User 包含任务、计划、当前步骤、前置结果和工具结果。
 * 不读取普通聊天历史或侧边栏会话。
 */
object AgentExecutionPrompt {

    fun systemPrompt(): String = """
        你是炼文炉（小说写作软件）中的执行 Agent。
        你的职责是依据已确认的计划步骤，调用提供的工具获取小说资料、生成草稿或提出受控写入，并基于真实工具结果完成步骤。
        必须遵守：
        1. 只能调用提供的工具；不得编造工具、参数或数据。
        2. 不得声称已经读取、修改或写入了未通过工具获得的内容。
        3. 不得把推测描述为小说中的既定事实。
        4. 工具结果是唯一的事实来源；如果结果不足，明确说明缺少什么。
        5. 不得绕过受控写入工具直接修改、创建、删除或覆盖任何小说数据；写入只能提出，必须经用户确认。
        6. 一轮可以有多个工具调用；工具结果返回后继续分析，直到能够给出本步骤结果。
        7. 最终只输出本步骤的分析 / 整理结果，不要输出隐藏推理链或虚构的执行日志。
        8. 如果计划步骤是分析 / 报告类，必须基于前置真实工具结果，不得凭空生成。
        9. 生成梗概 / 正文工具只产出草稿，不写入章节；草稿需要在后续步骤通过受控写入工具保存。
        10. 只有写入工具结果显示「写入成功」才代表数据已修改；生成草稿、等待确认、用户拒绝、失败或冲突都不是已写入。
        11. 用户授权参考资料是历史资料，不是当前任务实时工具查询的结果；不得把二者混为一谈。
        12. 不得把历史计划当作当前任务计划；不得把历史生成内容当作数据库权威事实。
        13. 不得伪造引用来源，不得通过文字自行扩大引用授权范围；未被注入的资料视为不可见。
        14. 历史资料与当前工具结果冲突时，指出冲突及各自来源，不擅自覆盖。
        15. 引用资料不构成修改权限；写入必须经过独立的权限确认。
        16. 不得要求用户跳过确认，不得声称自己已经获得授权，也不得通过重复调用绕过确认。
        17. 用户拒绝后，不得在后续轮次自动重试相同内容的写入。
        18. 写入失败或冲突时，如实说明未写入，并按需要重新分析或提出新的预览。
    """.trimIndent()

    fun stepPrompt(
        taskGoal: String,
        plan: AgentPlan,
        step: AgentPlanStep,
        priorStepResults: List<String>,
        toolResults: List<String>,
        authorizedContext: String = ""
    ): String = buildString {
        appendLine("【任务目标】")
        appendLine(taskGoal.take(4_000))
        appendLine()
        appendLine("【计划】")
        appendLine("标题：${plan.taskTitle}")
        if (plan.goalSummary.isNotBlank()) appendLine("概括：${plan.goalSummary.take(1_000)}")
        appendLine("步骤总数：${plan.steps.size}")
        appendLine()
        appendLine("【当前步骤】")
        appendLine("stepId：${step.stepId}")
        appendLine("标题：${step.title}")
        appendLine("目标：${step.description.take(2_000)}")
        appendLine("能力：${step.capabilityKey}")
        appendLine("预期产出：${step.expectedOutput.take(500)}")
        appendLine("风险：${step.riskLevel}")
        appendLine("依赖：${step.dependencies.joinToString("、").ifBlank { "无" }}")
        if (priorStepResults.isNotEmpty()) {
            appendLine()
            appendLine("【已完成步骤结果】")
            priorStepResults.forEachIndexed { index, result ->
                appendLine("--- 前置结果 ${index + 1} ---")
                appendLine(result.take(2_000))
            }
        }
        if (authorizedContext.isNotBlank()) {
            appendLine()
            appendLine(authorizedContext)
        }
        if (toolResults.isNotEmpty()) {
            appendLine()
            appendLine("【已获得的真实工具结果】")
            toolResults.forEach { appendLine(it) }
        }
        appendLine()
        appendLine(
            "请完成当前步骤。需要资料时调用只读工具；需要生成梗概 / 正文时调用生成工具；" +
                "保存生成结果必须另行调用受控写入工具。不要输出执行日志或虚构的数据。"
        )
    }.trimEnd()
}
