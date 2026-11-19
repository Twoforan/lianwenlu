package com.lianwenqi.app.agent.execution

import android.content.Context
import com.lianwenqi.app.agent.data.AgentNodeSpec
import com.lianwenqi.app.agent.data.AgentPlanSaveRequest
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.appContainer
import java.io.File

/**
 * F7 真实进程死亡验证夹具。
 *
 * 在应用真实数据库中创建：
 * - 已确认、状态 RUNNING 的任务；
 * - 唯一计划步骤节点已 COMPLETED；
 * - executionCursor 已推进到步骤末尾；
 * - 一个「上一进程」遗留的执行租约（心跳早于当前进程启动）。
 *
 * 进程被杀后重新启动，恢复器应立即判定旧租约失效，从游标继续并完成任务，
 * 不需要模型请求，也不需要重复执行任何步骤。
 */
object AgentProcessDeathFixture {

    private const val FILE_NAME = "f7_process_death_task_id"
    private const val RUN_ID = "process-death-run"

    fun taskId(context: Context): Long? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return file.readText().trim().toLongOrNull()
    }

    suspend fun seed(context: Context): Long {
        val container = context.appContainer
        val sessionId = container.agentSessionRepository.createSession(
            bookId = null,
            modelId = 1L
        )
        container.agentSessionRepository.renameSession(sessionId, "F7 进程死亡验证")

        val taskId = container.agentTaskRepository.createTaskWithUserMessage(
            sessionId = sessionId,
            userGoal = "验证进程死亡后能否从持久化游标恢复",
            modelId = 1L
        ).first
        container.agentTaskRepository.updateStatus(taskId, AgentTaskStatus.PLANNING)
        container.agentTaskRepository.savePlanAndWaitConfirmation(
            taskId,
            AgentPlanSaveRequest(
                planJson = planJson(),
                taskTitle = "进程死亡恢复",
                goalSummary = "仅验证恢复",
                stepCount = 1,
                thinkingSummary = "单步任务",
                assistantContent = "计划已生成"
            )
        )
        container.agentTaskRepository.markPlanConfirmed(taskId)
        check(container.agentTaskRepository.tryStartExecution(taskId, sessionId))

        val nodeId = container.agentExecutionNodeRepository.createNodes(
            taskId = taskId,
            specs = listOf(
                AgentNodeSpec(
                    nodeKey = "$RUN_ID:step_001",
                    nodeType = AgentNodeType.SEARCH_OUTLINE,
                    title = "读取大纲",
                    inputJson = """{"stepId":"step_001","runId":"$RUN_ID"}"""
                )
            ),
            startSequence = 0
        ).single()
        container.agentExecutionNodeRepository.updateStatus(
            nodeId,
            AgentNodeStatus.COMPLETED,
            outputJson = """{"stepId":"step_001","result":"步骤已完成，等待任务收尾"}"""
        )

        val executionId = container.agentToolExecutionRepository.createExecution(
            taskId = taskId,
            toolName = "get_outline",
            nodeId = nodeId,
            idempotencyKey = "process-death-tool"
        )
        container.agentToolExecutionRepository.start(executionId)
        container.agentToolExecutionRepository.succeed(executionId, """{"items":["已完成"]}""")

        val now = System.currentTimeMillis()
        val current = container.agentTaskRepository.getTask(taskId)!!
        container.database.agentTaskDao().updateTask(
            current.copy(
                executionRunId = RUN_ID,
                executionCursor = 1,
                currentNodeId = null,
                executionOwner = "dead-process-owner",
                executionLeaseUntil = now + 10 * 60 * 1000L,
                lastHeartbeatAt = now - 5 * 60 * 1000L,
                lastExecutionAt = now - 5 * 60 * 1000L
            )
        )
        File(context.filesDir, FILE_NAME).writeText(taskId.toString())
        return taskId
    }

    private fun planJson(): String = """
        {"schemaVersion":1,"taskTitle":"进程死亡恢复","goalSummary":"仅验证恢复",
         "thinkingSummary":"单步任务","needsClarification":false,"clarificationQuestions":[],
         "steps":[{"stepId":"step_001","title":"读取大纲","description":"读取当前书籍大纲",
         "capabilityKey":"READ_OUTLINE","expectedOutput":"大纲","dependencies":[],
         "riskLevel":"LOW"}]}
    """.trimIndent()
}
