package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.model.AgentPlanParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanParserTest {

    private val parser = AgentPlanParser()

    private val validJson = """
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
            },
            {
              "stepId": "step_002",
              "title": "核对关系变化",
              "description": "检查关系变化是否有事件依据",
              "capabilityKey": "ANALYZE_CHARACTER_RELATIONSHIPS",
              "expectedOutput": "核对结果",
              "dependencies": ["step_001"],
              "riskLevel": "MEDIUM"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun 解析澄清问题与候选回答() {
        val json = """
            {
              "schemaVersion": 1,
              "taskTitle": "需要确认范围",
              "goalSummary": "确认要检查哪一卷",
              "thinkingSummary": "缺少目标卷",
              "needsClarification": true,
              "clarificationQuestions": ["要检查哪一卷？"],
              "clarificationOptions": ["第一卷", "第二卷"],
              "steps": []
            }
        """.trimIndent()
        val result = parser.parse(json)
        assertTrue(result is AgentPlanParseResult.Success)
        val plan = (result as AgentPlanParseResult.Success).plan
        assertTrue(plan.needsClarification)
        assertEquals(listOf("第一卷", "第二卷"), plan.clarificationOptions)
    }

    @Test
    fun 旧计划没有候选回答字段时使用空列表() {
        val result = parser.parse(validJson)
        assertTrue(result is AgentPlanParseResult.Success)
        assertEquals(
            emptyList<String>(),
            (result as AgentPlanParseResult.Success).plan.clarificationOptions
        )
    }

    @Test
    fun 归一化补齐缺失的步骤字段并映射别名() {
        val loose = """
            {
              "title": "整理大纲",
              "summary": "把散乱设定整理进大纲",
              "steps": [
                {"id": "s1", "name": "读取现有大纲", "type": "read_outline"},
                {"name": "写入新大纲", "type": "write_outline", "dependencies": ["s1"], "risk": "high"}
              ]
            }
        """.trimIndent()
        val result = parser.parse(loose)
        assertTrue(result is AgentPlanParseResult.Success)
        val plan = (result as AgentPlanParseResult.Success).plan
        assertEquals("整理大纲", plan.taskTitle)
        assertEquals("把散乱设定整理进大纲", plan.goalSummary)
        assertEquals(listOf("s1", "step_002"), plan.steps.map { it.stepId })
        assertEquals("READ_OUTLINE", plan.steps[0].capabilityKey)
        assertEquals("WRITE_OUTLINE", plan.steps[1].capabilityKey)
        assertEquals(listOf("s1"), plan.steps[1].dependencies)
        assertEquals("HIGH", plan.steps[1].riskLevel)
    }

    @Test
    fun 缺失stepId与能力时自动推断() {
        val loose = """
            {
              "steps": [
                {"title": "读取大纲"},
                {"title": "写大纲"}
              ]
            }
        """.trimIndent()
        val result = parser.parse(loose)
        assertTrue(result is AgentPlanParseResult.Success)
        val plan = (result as AgentPlanParseResult.Success).plan
        assertEquals(listOf("step_001", "step_002"), plan.steps.map { it.stepId })
        assertEquals("READ_OUTLINE", plan.steps[0].capabilityKey)
        assertEquals("WRITE_OUTLINE", plan.steps[1].capabilityKey)
        assertTrue(plan.taskTitle.isNotBlank())
        assertTrue(plan.goalSummary.isNotBlank())
    }

    @Test
    fun 解析纯JSON计划() {
        val result = parser.parse(validJson)
        assertTrue(result is AgentPlanParseResult.Success)
        val success = result as AgentPlanParseResult.Success
        assertEquals("检查第三卷人物关系连续性", success.plan.taskTitle)
        assertEquals(2, success.plan.steps.size)
        assertEquals(listOf("step_001"), success.plan.steps[1].dependencies)
        assertTrue(success.normalizedJson.startsWith("{"))
    }

    @Test
    fun 提取Markdown代码围栏中的JSON() {
        val raw = """
            下面是计划：
            ```json
            $validJson
            ```
            请审核。
        """.trimIndent()
        val result = parser.parse(raw)
        assertTrue(result is AgentPlanParseResult.Success)
        assertEquals(2, (result as AgentPlanParseResult.Success).plan.steps.size)
    }

    @Test
    fun 提取正文前后夹带的JSON() {
        val result = parser.parse("说明文字\n$validJson\n结束文字")
        assertTrue(result is AgentPlanParseResult.Success)
    }

    @Test
    fun 非法JSON返回失败() {
        val result = parser.parse("这不是 JSON")
        assertTrue(result is AgentPlanParseResult.Failure)
    }

    @Test
    fun 结构错误返回失败() {
        val broken = validJson.replace("\"step_001\"", "\"step_001")
        val result = parser.parse(broken)
        assertTrue(result is AgentPlanParseResult.Failure)
    }

    @Test
    fun 顶层数组返回失败() {
        val result = parser.parse("""[{"schemaVersion":1}]""")
        assertTrue(result is AgentPlanParseResult.Failure)
    }

    @Test
    fun 超过JSON大小上限返回失败() {
        val oversized = """{"schemaVersion":1,"taskTitle":"""" + "a".repeat(70_000) + """"}"""
        val result = parser.parse(oversized)
        assertTrue(result is AgentPlanParseResult.Failure)
        assertTrue((result as AgentPlanParseResult.Failure).message.contains("过大"))
    }
}
