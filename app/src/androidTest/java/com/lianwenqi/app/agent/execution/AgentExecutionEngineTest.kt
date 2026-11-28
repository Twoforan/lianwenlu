package com.lianwenqi.app.agent.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentPlanSaveRequest
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentToolExecutionRepository
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.agent.data.entity.AgentWorkMode
import com.lianwenqi.app.agent.planning.AgentModelClient
import com.lianwenqi.app.agent.planning.AgentPlanParser
import com.lianwenqi.app.agent.planning.AgentPlanValidator
import com.lianwenqi.app.agent.tool.AgentTool
import com.lianwenqi.app.agent.tool.AgentToolExecutor
import com.lianwenqi.app.agent.tool.AgentToolRegistry
import com.lianwenqi.app.agent.tool.AgentToolResult
import com.lianwenqi.app.agent.tool.AgentToolValidator
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.AiToolCall
import com.lianwenqi.app.ai.CacheUsage
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** F4 只读执行引擎验收：真实 Repository + Fake 模型 + Fake 只读工具 */
@RunWith(AndroidJUnit4::class)
class AgentExecutionEngineTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var tasks: AgentTaskRepository
    private lateinit var nodes: AgentExecutionNodeRepository
    private lateinit var tools: AgentToolExecutionRepository
    private lateinit var events: AgentEventRepository

    private val endpoint = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "测试供应商",
            baseUrl = "https://example.com",
            apiType = ApiType.CHAT_COMPLETIONS,
            apiKey = "sk-test"
        ),
        modelName = "fake-tool-model",
        temperature = 0.8f,
        maxTokens = 4096,
        systemPrompt = "普通聊天系统提示"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = AgentSessionRepository(db)
        tasks = AgentTaskRepository(db)
        nodes = AgentExecutionNodeRepository(db)
        tools = AgentToolExecutionRepository(db)
        events = AgentEventRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun planJson(capability: String = "READ_OUTLINE"): String = """
        {"schemaVersion":1,"taskTitle":"检查纲","goalSummary":"检查大纲",
         "thinkingSummary":"读取大纲","needsClarification":false,"clarificationQuestions":[],
         "steps":[{"stepId":"step_001","title":"读取大纲","description":"读取当前书籍大纲",
         "capabilityKey":"$capability","expectedOutput":"大纲内容","dependencies":[],"riskLevel":"LOW"}]}
    """.trimIndent()

    private suspend fun createConfirmedTask(
        plan: String = planJson(),
        legacyPlan: Boolean = true
    ): Pair<Long, Long> {
        val sessionId = sessions.createSession(bookId = 1L, modelId = 1L)
        val taskId = tasks.createTaskWithUserMessage(sessionId, "检查第三卷大纲").first
        if (legacyPlan) {
            // 历史计划仍按原语义执行；新建任务入口始终创建 AUTO。
            val task = tasks.getTask(taskId)!!
            db.agentTaskDao().updateTask(task.copy(workMode = AgentWorkMode.PLAN))
        }
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        tasks.savePlanAndWaitConfirmation(
            taskId,
            AgentPlanSaveRequest(
                planJson = plan,
                taskTitle = "检查纲",
                goalSummary = "检查大纲",
                stepCount = 1,
                thinkingSummary = "读取大纲",
                assistantContent = "已生成计划"
            )
        )
        tasks.markPlanConfirmed(taskId)
        return sessionId to taskId
    }

    private fun registryWithTool(
        toolResult: () -> AgentToolResult,
        counter: AtomicInteger = AtomicInteger(0)
    ): AgentToolRegistry {
        val registry = AgentToolRegistry()
        registry.register(
            AgentTool(
                toolKey = "get_outline",
                name = "get_outline",
                description = "读取大纲",
                capabilityKey = "READ_OUTLINE",
                execute = { _, _ ->
                    counter.incrementAndGet()
                    toolResult()
                }
            )
        )
        return registry
    }

    private fun engine(
        registry: AgentToolRegistry,
        script: MutableList<AiResult>,
        limits: AgentExecutionLimits = AgentExecutionLimits()
    ): AgentExecutionEngine {
        val modelClient = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("unused") },
            invokeWithTools = { _, _, _ ->
                when {
                    script.size > 1 -> script.removeAt(0)
                    script.isNotEmpty() -> script.first()
                    else -> AiResult(
                        success = true,
                        content = "默认结果",
                        cacheUsage = CacheUsage()
                    )
                }
            }
        )
        val registryInstance = registry
        val executor = AgentToolExecutor(
            registry = registryInstance,
            validator = AgentToolValidator(),
            tools = tools,
            tasks = tasks
        )
        return AgentExecutionEngine(
            tasks = tasks,
            nodes = nodes,
            toolExecutions = tools,
            events = events,
            sessions = sessions,
            modelClient = modelClient,
            registry = registryInstance,
            toolExecutor = executor,
            planParser = AgentPlanParser(),
            planValidator = AgentPlanValidator(),
            limits = limits
        )
    }

    private fun toolCall(id: String, name: String = "get_outline", args: String = "{}") =
        AiResult(
            success = true,
            content = "",
            toolCalls = listOf(AiToolCall(id = id, name = name, arguments = args)),
            cacheUsage = CacheUsage()
        )

    private fun finalText(text: String) = AiResult(
        success = true,
        content = text,
        cacheUsage = CacheUsage()
    )

    @Test
    fun 只读执行成功并持久化节点工具与事件() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask()
        val counter = AtomicInteger(0)
        val registry = registryWithTool(
            { AgentToolResult.ok("""{"items":["第三卷大纲"]}""") },
            counter
        )
        val expectedConclusion = "第三卷大纲显示存在两条冲突线。"
        val script = mutableListOf(
            toolCall("call_1"),
            finalText(expectedConclusion)
        )
        val result = engine(registry, script).execute(taskId, sessionId)
        assertTrue(result is AgentExecutionResult.Completed)
        assertEquals(1, counter.get())

        assertEquals(AgentTaskStatus.COMPLETED, tasks.getTask(taskId)!!.status)
        val nodeList = nodes.nodesOfTask(taskId)
        assertTrue(nodeList.any { it.status == AgentNodeStatus.COMPLETED && it.nodeKey.contains("step_001") })
        assertTrue(nodeList.any { it.nodeType == "TASK_COMPLETE" })

        val executions = tools.executionsOfTask(taskId)
        assertEquals(1, executions.size)
        assertEquals(AgentToolStatus.SUCCEEDED, executions.single().status)
        assertTrue(executions.single().resultJson.contains("第三卷大纲"))

        val eventList = events.eventsOfTask(taskId)
        assertEquals(
            (0L until eventList.size.toLong()).toList(),
            eventList.map { it.sequence }
        )
        assertTrue(eventList.any { it.eventType == "TOOL_CALL_STARTED" })
        assertTrue(eventList.any { it.eventType == "TOOL_CALL_COMPLETED" })
        assertTrue(eventList.any { it.eventType == "TASK_COMPLETED" })
        val completed = result as AgentExecutionResult.Completed
        assertTrue("实际执行摘要：${completed.summary}", completed.summary.contains(expectedConclusion))
        val stepNode = nodeList.single { it.nodeKey.endsWith(":step_001") }
        assertTrue("持久化步骤结果：${stepNode.outputJson}", stepNode.outputJson.contains(expectedConclusion))
        assertTrue(
            sessions.getMessages(sessionId).none { it.kind == AgentMessageKind.FINAL_ANSWER }
        )
    }

    @Test
    fun 新建自动任务允许不调用工具直接回答() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask(
            plan = planJson("GENERATE_REPORT"),
            legacyPlan = false
        )
        val registry = registryWithTool({ AgentToolResult.ok("{}") })
        val result = engine(
            registry,
            mutableListOf(finalText("这是直接回答。"))
        ).execute(taskId, sessionId)

        assertTrue(result is AgentExecutionResult.Completed)
        assertEquals(AgentWorkMode.AUTO, tasks.getTask(taskId)!!.workMode)
        assertEquals(AgentTaskStatus.COMPLETED, tasks.getTask(taskId)!!.status)
        assertTrue(tools.executionsOfTask(taskId).isEmpty())
        assertEquals(
            "这是直接回答。",
            sessions.getMessages(sessionId).single {
                it.kind == AgentMessageKind.FINAL_ANSWER
            }.content
        )
    }

    @Test
    fun 模型未调用工具时读取步骤不能完成() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask()
        val registry = registryWithTool({ AgentToolResult.ok("{}") })
        val result = engine(
            registry,
            mutableListOf(finalText("我已经查询了全部大纲。"))
        ).execute(taskId, sessionId)

        assertTrue(result is AgentExecutionResult.Failed)
        assertEquals(AgentTaskStatus.FAILED, tasks.getTask(taskId)!!.status)
        assertTrue(tools.executionsOfTask(taskId).isEmpty())
        assertTrue(
            nodes.nodesOfTask(taskId).any { it.status == AgentNodeStatus.FAILED }
        )
        assertTrue(
            sessions.getMessages(sessionId).any { it.content.contains("未产生真实工具结果") }
        )
    }

    @Test
    fun 未知工具被记录为失败且不能完成读取步骤() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask()
        val registry = registryWithTool({ AgentToolResult.ok("{}") })
        val script = mutableListOf(
            toolCall("call_unknown", name = "write_outline"),
            finalText("已经写入大纲。")
        )
        val result = engine(registry, script).execute(taskId, sessionId)

        assertTrue(result is AgentExecutionResult.Failed)
        val execution = tools.executionsOfTask(taskId).single()
        assertEquals("write_outline", execution.toolName)
        assertEquals(AgentToolStatus.FAILED, execution.status)
        assertTrue(execution.errorMessage.contains("未注册工具"))
        assertEquals(AgentTaskStatus.FAILED, tasks.getTask(taskId)!!.status)
    }

    @Test
    fun 写入能力步骤没有真实写入提议时失败而不是假装完成() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask(planJson("WRITE_OUTLINE"))
        val registry = registryWithTool({ AgentToolResult.ok("{}") })
        val result = engine(registry, mutableListOf(finalText("x"))).execute(taskId, sessionId)

        assertTrue(result is AgentExecutionResult.Failed)
        assertTrue(
            (result as AgentExecutionResult.Failed).message.contains("没有产生真实写入提议")
        )
        assertEquals(AgentTaskStatus.FAILED, tasks.getTask(taskId)!!.status)
    }

    @Test
    fun 同一任务第二次启动被原子拒绝() = runBlocking {
        val (_, taskId) = createConfirmedTask()
        assertTrue(tasks.tryStartExecution(taskId, tasks.getTask(taskId)!!.sessionId))
        assertTrue(!tasks.tryStartExecution(taskId, tasks.getTask(taskId)!!.sessionId))
        assertEquals(AgentTaskStatus.RUNNING, tasks.getTask(taskId)!!.status)
    }

    @Test
    fun 相同工具调用ID只执行一次() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask()
        val counter = AtomicInteger(0)
        val registry = registryWithTool(
            { AgentToolResult.ok("""{"items":["大纲"]}""") },
            counter
        )
        val script = mutableListOf(
            AiResult(
                success = true,
                content = "",
                toolCalls = listOf(
                    AiToolCall("same_call", "get_outline", "{}"),
                    AiToolCall("same_call", "get_outline", "{}")
                ),
                cacheUsage = CacheUsage()
            ),
            finalText("根据大纲分析完成。")
        )
        val result = engine(registry, script).execute(taskId, sessionId)
        assertTrue(result is AgentExecutionResult.Completed)
        assertEquals(1, counter.get())
        assertEquals(1, tools.executionsOfTask(taskId).size)
    }

    @Test
    fun 工具调用次数上限会停止执行() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask()
        val registry = registryWithTool({ AgentToolResult.ok("{}") })
        val script = mutableListOf(
            toolCall("call_1"),
            toolCall("call_2"),
            toolCall("call_3")
        )
        val result = engine(
            registry = registry,
            script = script,
            limits = AgentExecutionLimits(
                maxToolCallsPerTask = 2,
                maxModelRoundsPerTask = 10
            )
        ).execute(taskId, sessionId)

        assertTrue(result is AgentExecutionResult.Failed)
        assertTrue((result as AgentExecutionResult.Failed).message.contains("上限"))
        assertEquals(AgentTaskStatus.FAILED, tasks.getTask(taskId)!!.status)
        assertEquals(2, tools.executionsOfTask(taskId).size)
    }

    @Test
    fun 页面协程取消后任务保持RUNNING等待恢复() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask()
        val registry = registryWithTool({ AgentToolResult.ok("{}") })
        val modelClient = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("unused") },
            invokeWithTools = { _, _, _ -> awaitCancellation() }
        )
        val executor = AgentToolExecutor(registry, AgentToolValidator(), tools, tasks)
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
            planValidator = AgentPlanValidator()
        )
        val job = launch { engine.execute(taskId, sessionId) }
        var running = false
        var attempts = 0
        while (!running && attempts < 100) {
            delay(20)
            running = nodes.nodesOfTask(taskId).any { it.status == AgentNodeStatus.RUNNING }
            attempts++
        }
        assertTrue("节点应进入 RUNNING", running)
        job.cancelAndJoin()

        // F7：页面销毁 / 协程停止不等于用户取消；任务保留 RUNNING 与执行位置，等待恢复。
        assertEquals(AgentTaskStatus.RUNNING, tasks.getTask(taskId)!!.status)
        assertTrue(
            nodes.nodesOfTask(taskId).any { it.status == AgentNodeStatus.WAITING }
        )
        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == "TASK_INTERRUPTED" }
        )
        assertTrue(
            events.eventsOfTask(taskId).none { it.eventType == "TASK_CANCELLED" }
        )
    }

    @Test
    fun 用户请求取消后协程取消落为CANCELLED() = runBlocking {
        val (sessionId, taskId) = createConfirmedTask()
        val registry = registryWithTool({ AgentToolResult.ok("{}") })
        val modelClient = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("unused") },
            invokeWithTools = { _, _, _ -> awaitCancellation() }
        )
        val executor = AgentToolExecutor(registry, AgentToolValidator(), tools, tasks)
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
            planValidator = AgentPlanValidator()
        )
        val job = launch { engine.execute(taskId, sessionId) }
        var running = false
        var attempts = 0
        while (!running && attempts < 100) {
            delay(20)
            running = nodes.nodesOfTask(taskId).any { it.status == AgentNodeStatus.RUNNING }
            attempts++
        }
        assertTrue("节点应进入 RUNNING", running)
        tasks.setCancelRequested(taskId, true)
        job.cancelAndJoin()

        assertEquals(AgentTaskStatus.CANCELLED, tasks.getTask(taskId)!!.status)
        assertTrue(
            nodes.nodesOfTask(taskId).any { it.status == AgentNodeStatus.CANCELLED }
        )
        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == "TASK_CANCELLED" }
        )
    }
}
