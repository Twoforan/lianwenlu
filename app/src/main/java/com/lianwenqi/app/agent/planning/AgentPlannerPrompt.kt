package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.execution.AgentExecutionPolicy
import com.lianwenqi.app.agent.model.AgentCapabilityCatalog
import com.lianwenqi.app.data.entity.Book

/**
 * Agent 规划 Prompt。
 *
 * 独立维护，不读取也不覆盖普通聊天的 System Prompt。
 * 能力目录与规则放在系统消息中，保持跨任务稳定；用户目标作为最后一条消息。
 */
object AgentPlannerPrompt {

    const val SCHEMA_VERSION = 1
    const val MAX_GOAL_CHARS = 8_000
    const val MAX_BOOK_INTRO_CHARS = 500
    const val MAX_SESSION_TITLE_CHARS = 100
    /** 附件正文注入上限；超出部分不进入上下文，避免挤占任务目标 */
    const val MAX_ATTACHMENT_CHARS = 6_000

    fun systemPrompt(): String = buildString {
        appendLine("你是炼文炉（小说写作软件）中的任务规划 Agent。")
        appendLine("你的职责是理解用户目标，并拆分出可检查、可审核的执行计划。")
        appendLine()
        appendLine("必须遵守：")
        appendLine("1. 当前只负责规划，不执行任何工具。")
        appendLine("2. 不得声称已经查询、修改、写入或删除任何数据。")
        appendLine("3. 不得伪造已经完成的操作。")
        appendLine("4. 不得把推测内容描述为小说中的既定事实。")
        appendLine("5. 信息确实不足时才标注 needsClarification，并把所有需要确认的问题一次性写进 clarificationQuestions，一次问清；能合理推断的内容直接按最合理方案规划，不要为了确认而反复追问。")
        appendLine("6. 需要澄清时，可在 clarificationOptions 中提供最多 4 个简短候选回答；用户也可以自行输入。")
        appendLine("7. 复杂任务拆分为可检查的步骤，每步说明目标、所需能力与预期产出。")
        appendLine("8. 涉及正文覆盖、删除、批量写入等高风险操作时，必须标注 HIGH（高危）。")
        appendLine("9. 不要假设不存在的工具能力；只能使用下方能力目录中的 capabilityKey。")
        appendLine("10. 只规划当前阶段可执行的步骤；标注为「当前阶段不可执行」的能力不能出现在 steps 中，待定事项写入 thinkingSummary 或 goalSummary。")
        appendLine("11. thinkingSummary 必须是简短、可展示的规划说明，不输出隐藏推理链。")
        appendLine("12. 不得输出执行日志、工具调用结果或任何虚构的数据查询结果。")
        appendLine("13. 计划只是建议，最终是否执行由用户和后续阶段决定。")
        appendLine("14. 只输出一个 JSON 对象，不要输出 Markdown 代码围栏或解释文字。")
        appendLine("15. 生成梗概 / 正文只产出草稿；如需保存，必须另行安排 WRITE_* 受控写入步骤，生成步骤本身不写入数据。")
        appendLine("16. 计划的步骤只服务于完成目标，不要安排“向用户征求建议 / 等待用户追加需求 / 提议下一步”的步骤。")
        appendLine("17. 同一类对象需要创建或生成超过 5 个时（例如人设1、人设2……，或第一章梗概、第二章正文……），必须拆分成多个步骤，每步最多处理 5 个对象；步骤标题写明范围（如「创建人设1-5」），禁止把全部对象塞进一个步骤。")
        appendLine()
        appendLine("【能力目录】")
        AgentCapabilityCatalog.all.forEach { capability ->
            val availability = when {
                AgentExecutionPolicy.isWriteCapability(capability.key) ->
                    "可执行；写入必须经用户确认或权限策略授权"

                AgentExecutionPolicy.isGenerationCapability(capability.key) ->
                    "可执行；只生成草稿，保存必须另安排受控写入步骤"

                AgentExecutionPolicy.isCapabilityExecutable(capability.key) ->
                    "可执行（只读）"

                else -> "当前阶段不可执行"
            }
            appendLine(
                "- ${capability.key}：${capability.displayName}；${capability.description}；" +
                    availability
            )
        }
        appendLine()
        appendLine("【输出 JSON 结构】")
        appendLine(
            """{
  "schemaVersion": 1,
  "taskTitle": "任务标题",
  "goalSummary": "对用户目标的简要概括",
  "thinkingSummary": "简短、可展示的规划说明",
  "needsClarification": false,
  "clarificationQuestions": [],
  "clarificationOptions": [],
  "steps": [
    {
      "stepId": "step_001",
      "title": "步骤标题",
      "description": "步骤目标与做法",
      "capabilityKey": "READ_OUTLINE",
      "expectedOutput": "预期产出",
      "dependencies": [],
      "riskLevel": "LOW"
    }
  ]
}"""
        )
        appendLine()
        appendLine("【限制】")
        appendLine("- 最多 30 个步骤；stepId 只能包含字母、数字、下划线或短横线，且不能重复。")
        appendLine("- dependencies 只能引用其他步骤的 stepId，不能依赖自身，不能形成循环。")
        appendLine("- riskLevel 只能是 LOW / MEDIUM / HIGH（低 / 中 / 高危）。")
        appendLine("- 任务标题最多 200 字符；步骤标题最多 120；步骤描述最多 2000；预期产出最多 500。")
        appendLine("- 如果不需要澄清，steps 必须至少包含一个步骤。")
        appendLine("- clarificationOptions 最多 4 个，每个不超过 100 字符；不需要澄清时留空。")
        appendLine("- 如果 capabilities 中没有合适能力，选择一个最接近的登记能力，并在描述中说明需要后续能力；不要编造新的 key。")
    }.trimEnd()

    fun userPrompt(
        goal: String,
        sessionTitle: String,
        book: Book?,
        attachmentText: String = "",
        imageCount: Int = 0,
        clarificationAnswers: List<String> = emptyList(),
        previousPlanJson: String = ""
    ): String = buildString {
        appendLine("【当前 Agent 会话】")
        appendLine(sessionTitle.take(MAX_SESSION_TITLE_CHARS).ifBlank { "未命名会话" })
        if (book != null) {
            appendLine()
            appendLine("【绑定书籍】")
            appendLine("《${book.title.take(200)}》")
            if (book.intro.isNotBlank()) {
                appendLine("简介：${book.intro.take(MAX_BOOK_INTRO_CHARS)}")
            }
        }
        appendLine()
        appendLine("【用户目标】")
        if (goal.length > MAX_GOAL_CHARS) {
            appendLine(goal.take(MAX_GOAL_CHARS))
            appendLine("（任务目标过长，以上为前 $MAX_GOAL_CHARS 字，完整目标已保存）")
        } else {
            appendLine(goal)
        }
        if (imageCount > 0) {
            appendLine()
            appendLine("【图片附件】")
            appendLine("共 $imageCount 张，已随本条消息一并提供，请结合图片内容理解任务目标。")
        }
        if (clarificationAnswers.isNotEmpty()) {
            appendLine()
            appendLine("【用户已补充的信息】")
            clarificationAnswers.forEachIndexed { index, answer ->
                appendLine("${index + 1}. ${answer.take(1_000)}")
            }
            appendLine("请把这些补充信息视为任务目标的一部分，不要重复询问。")
        }
        if (previousPlanJson.isNotBlank() && previousPlanJson != "{}") {
            appendLine()
            appendLine("【当前已有计划：请在此基础上调整】")
            appendLine("用户刚刚补充了指导信息。保留已有关键结论、步骤顺序和不需要变动的部分，")
            appendLine("只修改受补充信息影响的内容；不要从头重新分析，也不要推翻没有冲突的既有步骤。")
            appendLine(previousPlanJson.take(6_000))
        }
        if (attachmentText.isNotBlank()) {
            appendLine()
            appendLine("【任务附件资料】")
            if (attachmentText.length > MAX_ATTACHMENT_CHARS) {
                appendLine(attachmentText.take(MAX_ATTACHMENT_CHARS))
                appendLine("（附件内容过长，以上为前 $MAX_ATTACHMENT_CHARS 字）")
            } else {
                appendLine(attachmentText)
            }
        }
        appendLine()
        appendLine("请根据以上信息生成执行计划 JSON。")
    }.trimEnd()
}

/** 一次规划请求的完整消息与可记录文本 */
data class AgentPlanningRequest(
    val turns: List<com.lianwenqi.app.ai.ChatTurn>,
    val systemPrompt: String,
    val userPrompt: String
)

/** 组装 Agent 规划请求；不读取普通聊天历史 */
class AgentRequestBuilder {

    fun build(
        goal: String,
        sessionTitle: String,
        book: Book?,
        attachmentText: String = "",
        imageDataUrls: List<String> = emptyList(),
        clarificationAnswers: List<String> = emptyList(),
        previousPlanJson: String = ""
    ): AgentPlanningRequest {
        val system = AgentPlannerPrompt.systemPrompt()
        val user = AgentPlannerPrompt.userPrompt(
            goal = goal,
            sessionTitle = sessionTitle,
            book = book,
            attachmentText = attachmentText,
            imageCount = imageDataUrls.size,
            clarificationAnswers = clarificationAnswers,
            previousPlanJson = previousPlanJson
        )
        return AgentPlanningRequest(
            turns = listOf(
                com.lianwenqi.app.ai.ChatTurn(com.lianwenqi.app.data.entity.ChatRole.SYSTEM, system),
                com.lianwenqi.app.ai.ChatTurn(
                    com.lianwenqi.app.data.entity.ChatRole.USER,
                    user,
                    images = imageDataUrls
                )
            ),
            systemPrompt = system,
            userPrompt = user
        )
    }
}
