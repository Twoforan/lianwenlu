package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.agent.data.jsonPayload
import kotlinx.serialization.json.put

/**
 * 面向用户的交互工具。
 *
 * `ask_user` 只对模型可见：调用后向用户抛出一个问题并暂停任务，
 * 用户回答后任务自动继续；界面上不展示工具本身，只展示问题输入框。
 */
class AgentInteractionTools(
    private val createClarification: suspend (
        taskId: Long,
        question: String,
        options: List<String>
    ) -> Long
) {

    fun create(): List<AgentTool> = listOf(askUser())

    private fun askUser(): AgentTool = AgentTool(
        toolKey = "ask_user",
        name = "ask_user",
        description = "需要用户补充关键信息或在若干方案中做选择时调用：把问题发给用户并暂停当前任务，" +
            "用户回答后自动继续。给出选项时用户可直接点选，也可自行输入回答。",
        capabilityKey = "ASK_USER_CLARIFICATION",
        permissionLevel = AgentToolPermission.READ_ONLY,
        maxArgumentsChars = 4 * 1024,
        visibleToUser = false,
        parameters = listOf(
            AgentToolParam(
                name = "question",
                type = AgentToolParamType.STRING,
                description = "要问用户的问题，需具体、可直接回答",
                required = true,
                minLength = 1,
                maxLength = 2_000
            ),
            AgentToolParam(
                name = "options",
                type = AgentToolParamType.STRING,
                description = "可选答案，每行一个（最多 4 个）；用户可直接点选，也可自行输入其它回答",
                required = false,
                maxLength = 1_000
            )
        )
    ) { args, context ->
        val question = args.string("question").trim()
        if (question.isEmpty()) {
            return@AgentTool AgentToolResult.fail("question 不能为空")
        }
        val options = parseOptions(args.string("options"))
        runCatching {
            createClarification(context.taskId, question, options)
        }.fold(
            onSuccess = {
                AgentToolResult.ok(
                    contentJson = jsonPayload {
                        put("status", "waiting_for_user")
                        put("question", question.take(2_000))
                        put("optionCount", options.size)
                    },
                    summary = if (options.isEmpty()) {
                        "已向用户提问，等待回答后继续"
                    } else {
                        "已向用户提问（${options.size} 个可选项），等待回答后继续"
                    },
                    awaitingUserInput = true
                )
            },
            onFailure = { AgentToolResult.fail(it.message ?: "向用户提问失败") }
        )
    }

    /** 兼容「每行一个」与「用竖线分隔」两种写法，最多保留 4 个不重复选项 */
    private fun parseOptions(raw: String): List<String> = raw
        .split('\n', '|')
        .map { it.trim().removePrefix("- ").removePrefix("• ").trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(4)
}
