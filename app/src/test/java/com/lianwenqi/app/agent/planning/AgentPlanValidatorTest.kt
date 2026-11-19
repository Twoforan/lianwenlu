package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanStep
import com.lianwenqi.app.agent.model.AgentRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlanValidatorTest {

    private val validator = AgentPlanValidator()

    private fun plan(
        steps: List<AgentPlanStep> = listOf(step("step_001")),
        needsClarification: Boolean = false,
        questions: List<String> = emptyList()
    ) = AgentPlan(
        schemaVersion = 1,
        taskTitle = "测试计划",
        goalSummary = "测试目标",
        thinkingSummary = "简短说明",
        needsClarification = needsClarification,
        clarificationQuestions = questions,
        steps = steps
    )

    private fun step(
        id: String,
        dependencies: List<String> = emptyList(),
        capability: String = "READ_OUTLINE",
        risk: String = AgentRiskLevel.LOW,
        title: String = "步骤 $id",
        description: String = "说明"
    ) = AgentPlanStep(
        stepId = id,
        title = title,
        description = description,
        capabilityKey = capability,
        expectedOutput = "产出",
        dependencies = dependencies,
        riskLevel = risk
    )

    @Test
    fun 风险等级展示为低中高危且兼容旧CRITICAL() {
        assertEquals(
            listOf(AgentRiskLevel.LOW, AgentRiskLevel.MEDIUM, AgentRiskLevel.HIGH),
            AgentRiskLevel.selectable
        )
        assertEquals("低", AgentRiskLevel.label(AgentRiskLevel.LOW))
        assertEquals("中", AgentRiskLevel.label(AgentRiskLevel.MEDIUM))
        assertEquals("高危", AgentRiskLevel.label(AgentRiskLevel.HIGH))
        assertEquals("高危", AgentRiskLevel.label(AgentRiskLevel.CRITICAL))
        assertEquals(AgentRiskLevel.HIGH, AgentRiskLevel.normalize(AgentRiskLevel.CRITICAL))
        assertTrue(AgentRiskLevel.isValid(AgentRiskLevel.CRITICAL))
    }

    @Test
    fun 合法计划通过校验() {
        val result = validator.validate(
            plan(
                steps = listOf(
                    step("step_001"),
                    step("step_002", dependencies = listOf("step_001"), risk = AgentRiskLevel.HIGH)
                )
            )
        )
        assertTrue(result.isValid)
        assertEquals(2, result.stepCount)
        assertTrue(result.notYetAvailableCapabilities.isEmpty())
    }

    @Test
    fun 生成能力可进入计划而重写能力仍不可执行() {
        val generation = validator.validate(
            plan(steps = listOf(step("step_001", capability = "GENERATE_SYNOPSIS")))
        )
        assertTrue(generation.isValid)
        assertTrue(generation.notYetAvailableCapabilities.isEmpty())

        val rewrite = validator.validate(
            plan(steps = listOf(step("step_001", capability = "REWRITE_CONTENT")))
        )
        assertTrue(rewrite.notYetAvailableCapabilities.contains("REWRITE_CONTENT"))
    }

    @Test
    fun 重复stepId失败() {
        val result = validator.validate(
            plan(steps = listOf(step("step_001"), step("step_001")))
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("重复") })
    }

    @Test
    fun 依赖不存在失败() {
        val result = validator.validate(
            plan(steps = listOf(step("step_001", dependencies = listOf("step_999"))))
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("不存在") })
    }

    @Test
    fun 自身依赖失败() {
        val result = validator.validate(
            plan(steps = listOf(step("step_001", dependencies = listOf("step_001"))))
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("自身") })
    }

    @Test
    fun 循环依赖失败() {
        val result = validator.validate(
            plan(
                steps = listOf(
                    step("step_001", dependencies = listOf("step_002")),
                    step("step_002", dependencies = listOf("step_001"))
                )
            )
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("循环") })
    }

    @Test
    fun 未登记能力只产生警告() {
        val result = validator.validate(
            plan(steps = listOf(step("step_001", capability = "NOT_A_REAL_TOOL")))
        )
        assertTrue(result.isValid)
        assertTrue(result.unknownCapabilities.contains("NOT_A_REAL_TOOL"))
    }

    @Test
    fun 超过步骤上限失败() {
        val steps = (1..31).map { step("step_$it") }
        val result = validator.validate(plan(steps = steps))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("超过") })
    }

    @Test
    fun 标题超长失败() {
        val result = validator.validate(
            plan(steps = listOf(step("step_001", title = "a".repeat(121))))
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("标题超过") })
    }

    @Test
    fun 风险等级非法失败() {
        val result = validator.validate(
            plan(steps = listOf(step("step_001", risk = "EXTREME")))
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("风险等级") })
    }

    @Test
    fun 需要澄清但没有问题失败() {
        val result = validator.validate(plan(steps = emptyList(), needsClarification = true))
        assertFalse(result.isValid)
    }

    @Test
    fun 需要澄清且有问题时允许没有步骤() {
        val result = validator.validate(
            plan(
                steps = emptyList(),
                needsClarification = true,
                questions = listOf("请指定要检查的分卷")
            )
        )
        assertTrue(result.isValid)
    }

    @Test
    fun 不需要澄清且没有步骤失败() {
        val result = validator.validate(plan(steps = emptyList(), needsClarification = false))
        assertFalse(result.isValid)
    }

    @Test
    fun 不支持的计划版本失败() {
        val result = validator.validate(plan().copy(schemaVersion = 99))
        assertFalse(result.isValid)
    }
}
