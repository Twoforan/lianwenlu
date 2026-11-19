package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlannerTest {

    private val endpoint = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "测试供应商",
            baseUrl = "https://example.com",
            apiType = ApiType.CHAT_COMPLETIONS,
            apiKey = "sk-test"
        ),
        modelName = "planner-model",
        temperature = 0.8f,
        maxTokens = 4096,
        systemPrompt = "普通聊天系统提示"
    )

    private val validPlan = """
        {
          "schemaVersion": 1,
          "taskTitle": "检查第三卷人物关系连续性",
          "goalSummary": "检查人物关系是否与前文设定冲突",
          "thinkingSummary": "先确认资料来源，再核对事件变化。",
          "needsClarification": false,
          "clarificationQuestions": [],
          "steps": [
            {
              "stepId": "step_001",
              "title": "确定核查范围",
              "description": "明确第三卷涉及的人物及关系",
              "capabilityKey": "READ_STORY_CONTEXT",
              "expectedOutput": "人物与关系清单",
              "dependencies": [],
              "riskLevel": "LOW"
            }
          ]
        }
    """.trimIndent()

    private fun plannerReturning(content: String): AgentPlanner {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok(content) }
        )
        return AgentPlanner(client)
    }

    @Test
    fun 正常生成计划并返回可审计信息() = runBlocking {
        val result = plannerReturning(validPlan).plan(
            modelId = 1L,
            goal = "帮我检查第三卷人物关系",
            sessionTitle = "第三卷检查",
            book = null
        )
        assertTrue(result is AgentPlanningResult.Success)
        val success = result as AgentPlanningResult.Success
        assertEquals("检查第三卷人物关系连续性", success.plan.taskTitle)
        assertEquals(1, success.plan.steps.size)
        assertTrue(success.validation.isValid)
        assertTrue(success.trace.systemPrompt.contains("任务规划 Agent"))
        assertTrue(success.trace.systemPrompt.contains("READ_OUTLINE"))
        assertTrue(success.trace.userPrompt.contains("帮我检查第三卷人物关系"))
        assertFalse(success.trace.systemPrompt.contains("普通聊天系统提示"))
        assertEquals("planner-model", success.trace.modelName)
    }

    @Test
    fun 补充指导时把已有计划作为调整基础() = runBlocking {
        val previous = """{"schemaVersion":1,"taskTitle":"旧计划","goalSummary":"旧目标"}"""
        val result = plannerReturning(validPlan).plan(
            modelId = 1L,
            goal = "帮我检查第三卷人物关系",
            sessionTitle = "第三卷检查",
            book = null,
            clarificationAnswers = listOf("只看第二卷"),
            previousPlanJson = previous
        )
        assertTrue(result is AgentPlanningResult.Success)
        val prompt = (result as AgentPlanningResult.Success).trace.userPrompt
        assertTrue(prompt.contains("当前已有计划"))
        assertTrue(prompt.contains("旧计划"))
        assertTrue(prompt.contains("不要从头重新分析"))
        assertTrue(prompt.contains("只看第二卷"))
    }

    @Test
    fun 非法JSON返回失败而不是把原文当计划() = runBlocking {
        val result = plannerReturning("这不是 JSON").plan(1L, "目标", "", null)
        assertTrue(result is AgentPlanningResult.Failure)
        assertTrue((result as AgentPlanningResult.Failure).message.contains("计划解析失败"))
    }

    @Test
    fun 结构校验失败返回失败() = runBlocking {
        val duplicated = validPlan.replace(
            "\"stepId\": \"step_001\"",
            "\"stepId\": \"step_001\""
        ).let {
            // 复制一个相同 stepId 的步骤
            it.replace(
                "\"steps\": [",
                "\"steps\": [{\"stepId\":\"step_001\",\"title\":\"重复\",\"description\":\"\",\"capabilityKey\":\"READ_OUTLINE\",\"expectedOutput\":\"\",\"dependencies\":[],\"riskLevel\":\"LOW\"},"
            )
        }
        val result = plannerReturning(duplicated).plan(1L, "目标", "", null)
        assertTrue(result is AgentPlanningResult.Failure)
        assertTrue((result as AgentPlanningResult.Failure).message.contains("校验未通过"))
    }

    @Test
    fun 模型失败时返回失败并保留错误() = runBlocking {
        val client = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.fail(AiError.Timeout()) }
        )
        val result = AgentPlanner(client).plan(1L, "目标", "", null)
        assertTrue(result is AgentPlanningResult.Failure)
        assertEquals("请求超时", (result as AgentPlanningResult.Failure).message)
    }
}
