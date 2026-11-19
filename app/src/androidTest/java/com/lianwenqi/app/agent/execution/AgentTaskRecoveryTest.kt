package com.lianwenqi.app.agent.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentModelRequestRepository
import com.lianwenqi.app.agent.data.AgentPlanSaveRequest
import com.lianwenqi.app.agent.data.AgentRecoveryLogRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentToolExecutionRepository
import com.lianwenqi.app.agent.data.AgentNodeSpec
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentModelRequestStatus
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.data.entity.AgentRecoveryResult
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.agent.planning.AgentModelClient
import com.lianwenqi.app.agent.planning.AgentPlanParser
import com.lianwenqi.app.agent.planning.AgentPlanValidator
import com.lianwenqi.app.agent.tool.AgentTool
import com.lianwenqi.app.agent.tool.AgentToolExecutor
import com.lianwenqi.app.agent.tool.AgentToolRegistry
import com.lianwenqi.app.agent.tool.AgentToolResult
import com.lianwenqi.app.agent.tool.AgentToolValidator
import com.lianwenqi.app.agent.write.AgentWriteExecutionService
import com.lianwenqi.app.agent.write.AgentWriteRecoveryService
import com.lianwenqi.app.agent.write.RoomAgentWriteBusinessWriter
import com.lianwenqi.app.agent.write.RoomAgentWriteTransactionRunner
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.CacheUsage
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.OutlineRepository
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * F7 进程死亡恢复核心场景：
 * 第一步已完成、第二步停在 RUNNING、存在 STARTED 模型请求与 RUNNING 工具记录。
 * 恢复后只执行第二步，不重跑第一步，不重复已成功工具，不把任务误判为失败。
 */
@RunWith(AndroidJUnit4::class)
class AgentTaskRecoveryTest {

    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var tasks: AgentTaskRepository
    private lateinit var nodes: AgentExecutionNodeRepository
    private lateinit var tools: AgentToolExecutionRepository
    private lateinit var events: AgentEventRepository
    private lateinit var leases: AgentExecutionLeaseRepository
    private lateinit var modelRequests: AgentModelRequestRepository
    private lateinit var recoveryLogs: AgentRecoveryLogRepository
    private lateinit var writeOperations: AgentWriteOperationRepository

    private val modelCalls = AtomicInteger(0)

    private val endpoint = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "测试供应商",
            baseUrl = "https://example.com",
            apiType = ApiType.CHAT_COMPLETIONS,
            apiKey = "sk-test"
        ),
        modelName = "fake-recovery-model",
        temperature = 0.2f,
        maxTokens = 4096
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = AgentSessionRepository(db)
        tasks = AgentTaskRepository(db)
        nodes = AgentExecutionNodeRepository(db)
        tools = AgentToolExecutionRepository(db)
        events = AgentEventRepository(db)
        leases = AgentExecutionLeaseRepository(db)
        modelRequests = AgentModelRequestRepository(db)
        recoveryLogs = AgentRecoveryLogRepository(db)
        writeOperations = AgentWriteOperationRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun planJson(): String = """
        {"schemaVersion":1,"taskTitle":"恢复测试","goalSummary":"恢复执行",
         "thinkingSummary":"两步","needsClarification":false,"clarificationQuestions":[],
         "steps":[
          {"stepId":"step_001","title":"读取大纲","description":"读取",
           "capabilityKey":"READ_OUTLINE","expectedOutput":"大纲","dependencies":[],
           "riskLevel":"LOW"},
          {"stepId":"step_002","title":"生成报告","description":"分析",
           "capabilityKey":"GENERATE_REPORT","expectedOutput":"报告",
           "dependencies":["step_001"],"riskLevel":"LOW"}
         ]}
    """.trimIndent()

    private fun registry(): AgentToolRegistry = AgentToolRegistry().apply {
        register(
            AgentTool(
                toolKey = "get_outline",
                name = "get_outline",
                description = "读取大纲",
                capabilityKey = "READ_OUTLINE",
                execute = { _, _ -> AgentToolResult.ok("""{"items":[]}""") }
            )
        )
    }

    private fun buildRunner(registry: AgentToolRegistry): AgentTaskRunner {
        val modelClient = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("unused") },
            invokeWithTools = { _, _, _ ->
                modelCalls.incrementAndGet()
                AiResult.ok("第二步报告完成", cacheUsage = CacheUsage())
            }
        )
        val executor = AgentToolExecutor(
            registry = registry,
            validator = AgentToolValidator(),
            tools = tools,
            tasks = tasks,
            writeOperations = writeOperations
        )
        val engine = AgentExecutionEngine(
            tasks = tasks,
            nodes = nodes,
            toolExecutions = tools,
            events = events,
            sessions = sessions,
            modelClient = modelClient,
            registry = registry,
            toolExecutor = executor,
            planParser = AgentPlanParser(),
            planValidator = AgentPlanValidator(),
            writeOperations = writeOperations,
            writeService = null,
            writeGate = null,
            leases = leases,
            modelRequests = modelRequests
        )
        val books = BookRepository(db)
        val outlines = OutlineRepository(db)
        val writer = RoomAgentWriteBusinessWriter(db, books, outlines)
        val writeService = AgentWriteExecutionService(
            operations = writeOperations,
            writer = writer,
            policyProvider = { "ASK_EVERY_TIME" },
            transactionRunner = RoomAgentWriteTransactionRunner(db)
        )
        val writeRecovery = AgentWriteRecoveryService(writeOperations, writer)
        return AgentTaskRunner(
            tasks = tasks,
            nodes = nodes,
            tools = tools,
            events = events,
            sessions = sessions,
            leases = leases,
            modelRequests = modelRequests,
            recoveryLogs = recoveryLogs,
            writeOperations = writeOperations,
            writeService = writeService,
            writeRecovery = writeRecovery,
            engine = engine
        )
    }

    @Test
    fun 从持久化游标恢复且不重跑已完成步骤() = runBlocking {
        val sessionId = sessions.createSession(bookId = null, modelId = 1L)
        val taskId = tasks.createTaskWithUserMessage(sessionId, "恢复测试").first
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        tasks.savePlanAndWaitConfirmation(
            taskId,
            AgentPlanSaveRequest(
                planJson = planJson(),
                taskTitle = "恢复测试",
                goalSummary = "恢复执行",
                stepCount = 2,
                thinkingSummary = "两步",
                assistantContent = "计划"
            )
        )
        tasks.markPlanConfirmed(taskId)
        assertTrue(tasks.tryStartExecution(taskId, sessionId))

        val nodeIds = nodes.createNodes(
            taskId = taskId,
            specs = listOf(
                AgentNodeSpec(
                    nodeKey = "run-100:step_001",
                    nodeType = AgentNodeType.SEARCH_OUTLINE,
                    title = "读取大纲",
                    inputJson = """{"stepId":"step_001","runId":"run-100"}"""
                ),
                AgentNodeSpec(
                    nodeKey = "run-100:step_002",
                    nodeType = AgentNodeType.ANALYZE_RESULT,
                    title = "生成报告",
                    inputJson = """{"stepId":"step_002","runId":"run-100"}"""
                )
            ),
            startSequence = 0
        )
        nodes.updateStatus(
            nodeIds[0],
            AgentNodeStatus.COMPLETED,
            outputJson = """{"stepId":"step_001","result":"第一步已完成"}"""
        )
        nodes.updateStatus(nodeIds[1], AgentNodeStatus.RUNNING)

        val completedTool = tools.createExecution(
            taskId = taskId,
            toolName = "get_outline",
            nodeId = nodeIds[0],
            idempotencyKey = "read:1"
        )
        tools.start(completedTool)
        tools.succeed(completedTool, """{"items":["旧结果"]}""")

        val runningTool = tools.createExecution(
            taskId = taskId,
            toolName = "get_outline",
            nodeId = nodeIds[1],
            idempotencyKey = "read:2"
        )
        tools.start(runningTool)
        modelRequests.begin(
            taskId = taskId,
            nodeId = nodeIds[1],
            runId = "run-100",
            round = 0,
            modelId = 1L,
            modelName = "fake-recovery-model"
        )
        // 模拟「模型响应已返回，但进程在保存处理结果前死亡」
        val unapplied = modelRequests.begin(
            taskId = taskId,
            nodeId = nodeIds[1],
            runId = "run-100",
            round = 0,
            modelId = 1L,
            modelName = "fake-recovery-model"
        )
        modelRequests.succeed(
            id = unapplied.id,
            latencyMs = 100,
            responseChars = 20,
            toolCallCount = 0
        )

        db.agentTaskDao().updateTask(
            tasks.getTask(taskId)!!.copy(
                executionRunId = "run-100",
                executionCursor = 0,
                currentNodeId = nodeIds[1],
                updatedAt = System.currentTimeMillis()
            )
        )

        val outcome = buildRunner(registry()).run(taskId, "recovery-owner", "TEST")
        assertEquals(AgentRunOutcome.COMPLETED, outcome.type)
        assertEquals(1, modelCalls.get())

        val task = tasks.getTask(taskId)!!
        assertEquals(AgentTaskStatus.COMPLETED, task.status)
        assertEquals(2, task.executionCursor)
        assertEquals("", task.executionOwner)
        assertEquals(1, task.executionAttempt)

        val nodeList = nodes.nodesOfTask(taskId)
        val first = nodeList.first { it.id == nodeIds[0] }
        assertEquals(AgentNodeStatus.COMPLETED, first.status)
        assertTrue(first.outputJson.contains("第一步已完成"))
        assertEquals(AgentNodeStatus.COMPLETED, nodeList.first { it.id == nodeIds[1] }.status)

        val toolList = tools.executionsOfTask(taskId)
        assertEquals(AgentToolStatus.SUCCEEDED, toolList.first { it.id == completedTool }.status)
        assertEquals(AgentToolStatus.UNCERTAIN, toolList.first { it.id == runningTool }.status)

        val requests = modelRequests.requestsOfTask(taskId)
        assertEquals(AgentModelRequestStatus.UNKNOWN, requests.first().status)
        assertEquals(2, requests.count { it.status == AgentModelRequestStatus.UNKNOWN })
        assertTrue(requests.any { it.status == AgentModelRequestStatus.SUCCEEDED })

        val log = recoveryLogs.latestOfTask(taskId)!!
        assertEquals(AgentRecoveryResult.SUCCEEDED, log.result)
        assertTrue(log.duplicateRequestPossible)
        assertEquals(2, log.cursorAfter)

        val eventTypes = events.eventsOfTask(taskId).map { it.eventType }
        assertTrue(eventTypes.contains("TASK_RECOVERY_STARTED"))
        assertTrue(eventTypes.contains("TASK_RECOVERY_COMPLETED"))
        assertTrue(eventTypes.contains("TASK_EXECUTION_LEASE_ACQUIRED"))
        assertTrue(eventTypes.contains("MODEL_REQUEST_UNKNOWN"))
        assertFalse(eventTypes.contains("TASK_FAILED"))
    }
}
