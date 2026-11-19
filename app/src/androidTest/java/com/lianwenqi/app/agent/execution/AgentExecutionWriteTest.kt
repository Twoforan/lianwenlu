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
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.planning.AgentModelClient
import com.lianwenqi.app.agent.planning.AgentPlanParser
import com.lianwenqi.app.agent.planning.AgentPlanValidator
import com.lianwenqi.app.agent.tool.AgentToolExecutor
import com.lianwenqi.app.agent.tool.AgentToolRegistry
import com.lianwenqi.app.agent.tool.AgentToolValidator
import com.lianwenqi.app.agent.write.AgentWriteConfirmationGate
import com.lianwenqi.app.agent.write.AgentWriteExecutionService
import com.lianwenqi.app.agent.write.AgentWriteTools
import com.lianwenqi.app.agent.write.RoomAgentWriteBusinessWriter
import com.lianwenqi.app.agent.write.RoomAgentWriteTransactionRunner
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.AiToolCall
import com.lianwenqi.app.ai.CacheUsage
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.OutlineRepository
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F6 执行引擎：提议暂停、确认继续、拒绝返回模型、取消后保持待确认状态 */
@RunWith(AndroidJUnit4::class)
class AgentExecutionWriteTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var tasks: AgentTaskRepository
    private lateinit var nodes: AgentExecutionNodeRepository
    private lateinit var tools: AgentToolExecutionRepository
    private lateinit var events: AgentEventRepository
    private lateinit var operations: AgentWriteOperationRepository
    private lateinit var books: BookRepository
    private lateinit var outlines: OutlineRepository
    private lateinit var businessWriter: RoomAgentWriteBusinessWriter
    private lateinit var writeService: AgentWriteExecutionService
    private lateinit var gate: AgentWriteConfirmationGate

    private var bookId = 0L
    private var chapterId = 0L
    private var policy = AgentWritePermissionPolicy.ASK_EVERY_TIME

    private val endpoint = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "测试供应商",
            baseUrl = "https://example.com",
            apiType = ApiType.CHAT_COMPLETIONS,
            apiKey = "sk-test"
        ),
        modelName = "fake-write-model",
        temperature = 0.8f,
        maxTokens = 4096
    )

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = AgentSessionRepository(db)
        tasks = AgentTaskRepository(db)
        nodes = AgentExecutionNodeRepository(db)
        tools = AgentToolExecutionRepository(db)
        events = AgentEventRepository(db)
        operations = AgentWriteOperationRepository(db)
        books = BookRepository(db)
        outlines = OutlineRepository(db)
        businessWriter = RoomAgentWriteBusinessWriter(db, books, outlines)
        writeService = AgentWriteExecutionService(
            operations = operations,
            writer = businessWriter,
            policyProvider = { policy },
            transactionRunner = RoomAgentWriteTransactionRunner(db)
        )
        gate = AgentWriteConfirmationGate()

        bookId = books.createBook(
            title = "测试书",
            intro = "",
            coverPath = null,
            coverColor = 0,
            volumePrefix = "第%n卷",
            chapterPrefix = "第%n章"
        )
        chapterId = books.createChapter(bookId, null)
        books.updateChapterSynopsis(chapterId, "旧梗概")
        books.updateChapterContent(chapterId, "旧正文")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun planJson(capability: String): String = """
        {"schemaVersion":1,"taskTitle":"写入资料","goalSummary":"更新资料",
         "thinkingSummary":"写入","needsClarification":false,"clarificationQuestions":[],
         "steps":[{"stepId":"step_001","title":"更新资料","description":"更新章节资料",
         "capabilityKey":"$capability","expectedOutput":"完成","dependencies":[],
         "riskLevel":"MEDIUM"}]}
    """.trimIndent()

    private suspend fun createConfirmedTask(capability: String): Long {
        val sessionId = sessions.createSession(bookId = bookId, modelId = 1L)
        val taskId = tasks.createTaskWithUserMessage(sessionId, "更新章节资料").first
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        tasks.savePlanAndWaitConfirmation(
            taskId,
            AgentPlanSaveRequest(
                planJson = planJson(capability),
                taskTitle = "写入资料",
                goalSummary = "更新资料",
                stepCount = 1,
                thinkingSummary = "写入",
                assistantContent = "已生成计划"
            )
        )
        tasks.markPlanConfirmed(taskId)
        return taskId
    }

    private fun registry(): AgentToolRegistry = AgentToolRegistry().apply {
        registerAll(
            AgentWriteTools(
                bookOf = { books.getBook(it) },
                chapterMetaOf = { businessWriter.chapterMeta(it) },
                chapterContentOf = { businessWriter.chapterContent(it) },
                outlineOf = { businessWriter.outline(it) },
                writeService = writeService
            ).create()
        )
    }

    private fun engine(
        captured: MutableList<List<ChatTurn>>,
        toolName: String,
        arguments: String
    ): AgentExecutionEngine {
        val registry = registry()
        val executor = AgentToolExecutor(
            registry = registry,
            validator = AgentToolValidator(),
            tools = tools,
            tasks = tasks,
            writeOperations = operations
        )
        var round = 0
        val modelClient = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("unused") },
            invokeWithTools = { _, turns, _ ->
                captured += turns.toList()
                round++
                if (round == 1) {
                    AiResult(
                        success = true,
                        content = "",
                        toolCalls = listOf(AiToolCall("call-1", toolName, arguments)),
                        cacheUsage = CacheUsage()
                    )
                } else {
                    AiResult.ok("处理完成", cacheUsage = CacheUsage())
                }
            }
        )
        return AgentExecutionEngine(
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
            writeOperations = operations,
            writeService = writeService,
            writeGate = gate
        )
    }

    private fun synopsisArgs() =
        """{"chapterId":$chapterId,"synopsis":"新梗概","reason":"测试"}"""

    private suspend fun awaitWaitingOperation(taskId: Long) =
        withTimeout(10_000) {
            while (true) {
                val pending = operations.unresolvedOfTask(taskId)
                if (pending.isNotEmpty()) return@withTimeout pending.first()
                delay(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private suspend fun awaitTaskStatus(taskId: Long, status: String) {
        withTimeout(10_000) {
            while (tasks.getTask(taskId)?.status != status) {
                delay(20)
            }
        }
    }

    private fun userPrompts(captured: List<List<ChatTurn>>): String = captured.joinToString("\n") {
        it.filter { turn -> turn.role == com.lianwenqi.app.data.entity.ChatRole.USER }
            .joinToString("\n") { turn -> turn.content }
    }

    @Test
    fun 写入工具提议后暂停等待确认且确认后继续执行() = runBlocking {
        val taskId = createConfirmedTask("WRITE_CHAPTER_SYNOPSIS")
        val sessionId = tasks.getTask(taskId)!!.sessionId
        val captured = mutableListOf<List<ChatTurn>>()
        var result: AgentExecutionResult? = null
        val job = launch {
            result = engine(captured, "update_chapter_synopsis", synopsisArgs())
                .execute(taskId, sessionId)
        }

        val operation = awaitWaitingOperation(taskId)
        awaitTaskStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
        assertEquals(AgentWriteOperationStatus.WAITING_CONFIRMATION, operation.executionStatus)
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
        assertEquals(AgentTaskStatus.WAITING_CONFIRMATION, tasks.getTask(taskId)!!.status)

        writeService.approveAndExecute(operation.id)
        gate.signal(operation.id, true)
        job.join()

        assertTrue(result is AgentExecutionResult.Completed)
        assertEquals("新梗概", db.chapterDao().getById(chapterId)!!.summary)
        assertEquals(
            AgentWriteOperationStatus.SUCCEEDED,
            operations.get(operation.id)!!.executionStatus
        )
        assertEquals(AgentTaskStatus.COMPLETED, tasks.getTask(taskId)!!.status)
        assertTrue(userPrompts(captured).contains("业务数据已写入"))
    }

    @Test
    fun 用户拒绝后不写入且拒绝结果返回模型() = runBlocking {
        val taskId = createConfirmedTask("WRITE_CHAPTER_SYNOPSIS")
        val sessionId = tasks.getTask(taskId)!!.sessionId
        val captured = mutableListOf<List<ChatTurn>>()
        var result: AgentExecutionResult? = null
        val job = launch {
            result = engine(captured, "update_chapter_synopsis", synopsisArgs())
                .execute(taskId, sessionId)
        }

        val operation = awaitWaitingOperation(taskId)
        writeService.reject(operation.id)
        gate.signal(operation.id, false)
        job.join()

        assertTrue(result is AgentExecutionResult.Completed)
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
        assertEquals(
            AgentWriteOperationStatus.REJECTED,
            operations.get(operation.id)!!.executionStatus
        )
        assertTrue(userPrompts(captured).contains("用户已拒绝"))
    }

    @Test
    fun 一般写入自动授权时执行引擎不暂停() = runBlocking {
        policy = AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
        val taskId = createConfirmedTask("WRITE_CHAPTER_SYNOPSIS")
        val sessionId = tasks.getTask(taskId)!!.sessionId
        val captured = mutableListOf<List<ChatTurn>>()
        val result = engine(captured, "update_chapter_synopsis", synopsisArgs())
            .execute(taskId, sessionId)

        assertTrue(result is AgentExecutionResult.Completed)
        assertEquals("新梗概", db.chapterDao().getById(chapterId)!!.summary)
        assertTrue(operations.ofTask(taskId).single().executionStatus ==
            AgentWriteOperationStatus.SUCCEEDED)
    }

    @Test
    fun 高风险正文即使自动策略仍暂停等待确认() = runBlocking {
        policy = AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
        val taskId = createConfirmedTask("WRITE_CHAPTER_CONTENT")
        val sessionId = tasks.getTask(taskId)!!.sessionId
        val captured = mutableListOf<List<ChatTurn>>()
        val job = launch {
            engine(
                captured,
                "update_chapter_content",
                """{"chapterId":$chapterId,"content":"新正文","reason":"测试"}"""
            ).execute(taskId, sessionId)
        }

        val operation = awaitWaitingOperation(taskId)
        awaitTaskStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
        assertEquals(AgentWriteOperationStatus.WAITING_CONFIRMATION, operation.executionStatus)
        assertEquals("旧正文", db.chapterDao().getById(chapterId)!!.content)

        writeService.approveAndExecute(operation.id)
        gate.signal(operation.id, true)
        job.join()
        assertEquals("新正文", db.chapterDao().getById(chapterId)!!.content)
    }

    @Test
    fun 页面取消等待后任务保持WAITING_CONFIRMATION且提议仍然有效() = runBlocking {
        val taskId = createConfirmedTask("WRITE_CHAPTER_SYNOPSIS")
        val sessionId = tasks.getTask(taskId)!!.sessionId
        val captured = mutableListOf<List<ChatTurn>>()
        val job = launch {
            engine(captured, "update_chapter_synopsis", synopsisArgs())
                .execute(taskId, sessionId)
        }

        val operation = awaitWaitingOperation(taskId)
        job.cancelAndJoin()

        assertEquals(AgentTaskStatus.WAITING_CONFIRMATION, tasks.getTask(taskId)!!.status)
        assertEquals(
            AgentWriteOperationStatus.WAITING_CONFIRMATION,
            operations.get(operation.id)!!.executionStatus
        )
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
        assertFalse(db.agentTaskDao().eventsOfTask(taskId).any {
            it.eventType == "TASK_CANCELLED"
        })
    }
}
