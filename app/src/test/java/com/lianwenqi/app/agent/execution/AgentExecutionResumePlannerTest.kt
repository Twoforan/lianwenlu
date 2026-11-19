package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.model.AgentPlanStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F7：从持久化节点推导执行游标，不从第一步重跑 */
class AgentExecutionResumePlannerTest {

    private fun step(id: String) = AgentPlanStep(stepId = id, title = id)

    private fun node(
        id: Long,
        stepId: String,
        status: String,
        result: String? = null
    ) = AgentExecutionNode(
        id = id,
        taskId = 1,
        nodeKey = "run:$stepId",
        nodeType = "PLAN_STEP",
        title = stepId,
        status = status,
        sequence = id,
        inputJson = """{"stepId":"$stepId","runId":"run"}""",
        outputJson = if (result == null) "{}" else """{"result":"$result"}"""
    )

    @Test
    fun `all completed steps are reused and cursor is at end`() {
        val plan = AgentExecutionResumePlanner.plan(
            orderedSteps = listOf(step("a"), step("b")),
            runNodes = listOf(
                node(1, "a", AgentNodeStatus.COMPLETED, "A"),
                node(2, "b", AgentNodeStatus.COMPLETED, "B")
            )
        )
        assertEquals(2, plan.startIndex)
        assertEquals(listOf("A", "B"), plan.stepResults)
        assertNull(plan.reopenNodeId)
        assertFalse(plan.inconsistentCompletedNode)
    }

    @Test
    fun `running node is reopened and completed prefix is restored`() {
        val plan = AgentExecutionResumePlanner.plan(
            orderedSteps = listOf(step("a"), step("b"), step("c")),
            runNodes = listOf(
                node(1, "a", AgentNodeStatus.COMPLETED, "A"),
                node(2, "b", AgentNodeStatus.RUNNING),
                node(3, "c", AgentNodeStatus.PENDING)
            )
        )
        assertEquals(1, plan.startIndex)
        assertEquals(listOf("A"), plan.stepResults)
        assertEquals(2L, plan.reopenNodeId)
    }

    @Test
    fun `waiting node from write confirmation is reopened`() {
        val plan = AgentExecutionResumePlanner.plan(
            orderedSteps = listOf(step("a")),
            runNodes = listOf(node(1, "a", AgentNodeStatus.WAITING))
        )
        assertEquals(0, plan.startIndex)
        assertEquals(1L, plan.reopenNodeId)
    }

    @Test
    fun `completed node without result is treated as inconsistent`() {
        val plan = AgentExecutionResumePlanner.plan(
            orderedSteps = listOf(step("a"), step("b")),
            runNodes = listOf(
                node(1, "a", AgentNodeStatus.COMPLETED, result = null),
                node(2, "b", AgentNodeStatus.PENDING)
            )
        )
        assertEquals(0, plan.startIndex)
        assertTrue(plan.inconsistentCompletedNode)
        assertTrue(plan.stepResults.isEmpty())
    }

    @Test
    fun `failed node blocks automatic recovery`() {
        val plan = AgentExecutionResumePlanner.plan(
            orderedSteps = listOf(step("a")),
            runNodes = listOf(node(1, "a", AgentNodeStatus.FAILED))
        )
        assertEquals(0, plan.startIndex)
        assertEquals("a", plan.blockedNodeTitle)
        assertNull(plan.reopenNodeId)
    }

    @Test
    fun `missing node is reported instead of guessing`() {
        val plan = AgentExecutionResumePlanner.plan(
            orderedSteps = listOf(step("a")),
            runNodes = emptyList()
        )
        assertEquals(0, plan.startIndex)
        assertEquals("a", plan.missingNodeStepId)
    }
}
