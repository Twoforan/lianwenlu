package com.lianwenqi.app.agent.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.context.AgentContextAssembler
import com.lianwenqi.app.agent.context.AgentReferenceResolver
import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentPlanSaveRequest
import com.lianwenqi.app.agent.data.AgentReferenceRepository
import com.lianwenqi.app.agent.data.AgentReferenceSourceRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskReferenceRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentToolExecutionRepository
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
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
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.entity.ApiType
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.Volume
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F5 执行引擎集成：授权参考资料进入 Prompt、任务快照稳定、失效来源不静默替换。
 * 使用 Fake 模型与真实 Room 数据层。
 */
@RunWith(AndroidJUnit4::class)
class AgentExecutionReferenceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var tasks: AgentTaskRepository
    private lateinit var nodes: AgentExecutionNodeRepository
    private lateinit var tools: AgentToolExecutionRepository
    private lateinit var events: AgentEventRepository
    private lateinit var references: AgentReferenceRepository
    private lateinit var snapshots: AgentTaskReferenceRepository
    private lateinit var sourceRepository: AgentReferenceSourceRepository

    private var bookId = 0L
    private var chapterId = 0L

    private val endpoint = ModelEndpoint(
        provider = Provider(
            id = 1,
            name = "测试供应商",
            baseUrl = "https://example.com",
            apiType = ApiType.CHAT_COMPLETIONS,
            apiKey = "sk-test"
        ),
        modelName = "fake-reference-model",
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
        references = AgentReferenceRepository(db)
        snapshots = AgentTaskReferenceRepository(db)
        sourceRepository = AgentReferenceSourceRepository(db)

        bookId = db.bookDao().insert(Book(title = "某书"))
        val volumeId = db.volumeDao().insert(Volume(bookId = bookId, title = "第一卷"))
        chapterId = db.chapterDao().insert(
            Chapter(
                bookId = bookId,
                volumeId = volumeId,
                title = "第三章",
                summary = "主角夜袭敌营，发现内奸。",
                content = "正文内容".repeat(1_000)
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun planJson(): String = """
        {"schemaVersion":1,"taskTitle":"检查资料","goalSummary":"检查资料",
         "thinkingSummary":"读取章节","needsClarification":false,"clarificationQuestions":[],
         "steps":[{"stepId":"step_001","title":"读取章节梗概","description":"读取授权资料",
         "capabilityKey":"READ_CHAPTER_SYNOPSIS","expectedOutput":"分析结果","dependencies":[],
         "riskLevel":"LOW"}]}
    """.trimIndent()

    private suspend fun createConfirmedTask(sessionId: Long): Long {
        val taskId = tasks.createTaskWithUserMessage(sessionId, "检查授权资料").first
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        tasks.savePlanAndWaitConfirmation(
            taskId,
            AgentPlanSaveRequest(
                planJson = planJson(),
                taskTitle = "检查资料",
                goalSummary = "检查资料",
                stepCount = 1,
                thinkingSummary = "读取章节",
                assistantContent = "已生成计划"
            )
        )
        tasks.markPlanConfirmed(taskId)
        return taskId
    }

    private fun resolver() = AgentReferenceResolver(
        chapterMetaOf = { id -> sourceRepository.chapterMeta(id) },
        chapterContentOf = { id, offset, length ->
            sourceRepository.chapterContentSlice(id, offset, length)
        },
        bookTitleOf = { id -> sourceRepository.bookTitle(id) },
        volumeTitleOf = { id -> sourceRepository.volumeTitle(id) },
        agentTaskOf = { id -> sourceRepository.agentTask(id) },
        agentSessionOf = { id -> sourceRepository.agentSession(id) },
        agentMessageOf = { id -> sourceRepository.agentMessage(id) },
        toolExecutionOf = { id -> sourceRepository.toolExecution(id) },
        finalAnswerOf = { id -> sourceRepository.finalAnswerOf(id) },
        planParser = AgentPlanParser()
    )

    private fun assembler() = AgentContextAssembler(
        resolver = resolver(),
        freezeSnapshot = { taskId, sessionId -> snapshots.freezeSnapshot(taskId, sessionId) },
        recordInjectionResults = { taskId, results ->
            snapshots.recordInjectionResults(taskId, results)
        }
    )

    private fun engine(captured: MutableList<List<ChatTurn>>): AgentExecutionEngine {
        val registry = AgentToolRegistry().apply {
            register(
                AgentTool(
                    toolKey = "get_chapter_synopsis",
                    name = "get_chapter_synopsis",
                    description = "读取章节梗概",
                    capabilityKey = "READ_CHAPTER_SYNOPSIS",
                    execute = { _, _ ->
                        AgentToolResult.ok("""{"items":["主角夜袭敌营"]}""")
                    }
                )
            )
        }
        val executor = AgentToolExecutor(
            registry = registry,
            validator = AgentToolValidator(),
            tools = tools,
            tasks = tasks
        )
        val modelClient = AgentModelClient(
            endpointFor = { endpoint },
            invoke = { _, _ -> AiResult.ok("unused") },
            invokeWithTools = { _, turns, _ ->
                captured += turns.toList()
                if (captured.size == 1) {
                    AiResult(
                        success = true,
                        content = "",
                        toolCalls = listOf(
                            AiToolCall(id = "call-1", name = "get_chapter_synopsis", arguments = "{}")
                        ),
                        cacheUsage = CacheUsage()
                    )
                } else {
                    AiResult.ok("完成资料检查", cacheUsage = CacheUsage())
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
            contextAssembler = assembler()
        )
    }

    private fun userPrompt(captured: List<List<ChatTurn>>, round: Int = 0): String =
        captured[round].first { it.role == com.lianwenqi.app.data.entity.ChatRole.USER }.content

    @Test
    fun 章节梗概引用进入执行Prompt并记录注入结果() = runBlocking {
        val sessionId = sessions.createSession(bookId = bookId, modelId = 1L)
        val taskId = createConfirmedTask(sessionId)
        references.addReference(
            sessionId = sessionId,
            sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
            sourceId = chapterId,
            bookId = bookId,
            chapterId = chapterId,
            titleSnapshot = "第三章"
        )

        val captured = mutableListOf<List<ChatTurn>>()
        val result = engine(captured).execute(taskId, sessionId)
        assertTrue(result is AgentExecutionResult.Completed)

        val first = userPrompt(captured, 0)
        assertTrue(first.contains("【用户授权参考资料】"))
        assertTrue(first.contains("历史章节梗概"))
        assertTrue(first.contains("来源书籍：某书"))
        assertTrue(first.contains("主角夜袭敌营"))
        // 当前任务目标 / 计划必须排在授权资料之前，不能被历史资料挤出
        assertTrue(first.indexOf("【任务目标】") < first.indexOf("【用户授权参考资料】"))
        assertTrue(first.indexOf("【当前步骤】") < first.indexOf("【用户授权参考资料】"))
        // 历史资料区段必须排在当前工具结果之前
        val second = userPrompt(captured, captured.size - 1)
        val historyIndex = second.indexOf("【用户授权参考资料】")
        val toolIndex = second.indexOf("【已获得的真实工具结果】")
        assertTrue(historyIndex >= 0)
        assertTrue(toolIndex < 0 || historyIndex < toolIndex)

        val snapshot = snapshots.referencesOfTask(taskId).single()
        assertTrue(snapshot.validated)
        assertTrue(snapshot.valid)
        assertTrue(snapshot.injected)
        assertFalse(snapshot.truncated)
        assertTrue(snapshot.contentChars > 0)
    }

    @Test
    fun 已冻结快照不随会话引用删除而变化() = runBlocking {
        val sessionId = sessions.createSession(bookId = bookId, modelId = 1L)
        val taskId = createConfirmedTask(sessionId)
        references.addReference(
            sessionId = sessionId,
            sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
            sourceId = chapterId,
            bookId = bookId,
            chapterId = chapterId,
            titleSnapshot = "第三章"
        )
        // 模拟执行开始前已冻结
        snapshots.freezeSnapshot(taskId, sessionId)
        references.deleteForSession(sessionId)

        val captured = mutableListOf<List<ChatTurn>>()
        val result = engine(captured).execute(taskId, sessionId)
        assertTrue(result is AgentExecutionResult.Completed)
        assertTrue(userPrompt(captured, 0).contains("主角夜袭敌营"))
        assertTrue(snapshots.referencesOfTask(taskId).single().injected)
        assertTrue(references.references(sessionId).isEmpty())
    }

    @Test
    fun 来源失效时记录失效状态并继续执行() = runBlocking {
        val sessionId = sessions.createSession(bookId = bookId, modelId = 1L)
        val taskId = createConfirmedTask(sessionId)
        references.addReference(
            sessionId = sessionId,
            sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
            sourceId = chapterId,
            bookId = bookId,
            chapterId = chapterId,
            titleSnapshot = "第三章"
        )
        db.chapterDao().deleteById(chapterId)

        val captured = mutableListOf<List<ChatTurn>>()
        val result = engine(captured).execute(taskId, sessionId)
        assertTrue(result is AgentExecutionResult.Completed)

        val snapshot = snapshots.referencesOfTask(taskId).single()
        assertTrue(snapshot.validated)
        assertFalse(snapshot.valid)
        assertFalse(snapshot.injected)
        assertTrue(snapshot.invalidReason.contains("不存在"))

        val notice = sessions.getMessages(sessionId)
            .firstOrNull { it.kind == AgentMessageKind.NOTICE && it.content.contains("参考资料状态") }
        assertTrue(notice != null)
    }

    @Test
    fun 无引用资料时执行Prompt不出现授权资料区段() = runBlocking {
        val sessionId = sessions.createSession(bookId = bookId, modelId = 1L)
        val taskId = createConfirmedTask(sessionId)

        val captured = mutableListOf<List<ChatTurn>>()
        val result = engine(captured).execute(taskId, sessionId)
        assertTrue(result is AgentExecutionResult.Completed)
        assertFalse(userPrompt(captured, 0).contains("【用户授权参考资料】"))
        assertTrue(snapshots.referencesOfTask(taskId).isEmpty())
    }

    @Test
    fun 混合引用历史任务结果与历史工具结果时分别标记() = runBlocking {
        // 历史会话 / 任务 / 最终回答 / 成功工具结果
        val historySessionId = sessions.createSession(bookId = bookId)
        val historyTaskId = tasks.createTask(historySessionId, "旧的检查任务")
        tasks.updateStatus(historyTaskId, AgentTaskStatus.PLANNING)
        tasks.savePlanAndWaitConfirmation(
            historyTaskId,
            AgentPlanSaveRequest(
                planJson = planJson(),
                taskTitle = "旧的检查任务",
                goalSummary = "旧目标",
                stepCount = 1,
                thinkingSummary = "",
                assistantContent = "旧计划"
            )
        )
        tasks.markPlanConfirmed(historyTaskId)
        tasks.tryStartExecution(historyTaskId, historySessionId)
        sessions.addMessage(
            sessionId = historySessionId,
            taskId = historyTaskId,
            role = AgentMessageRole.ASSISTANT,
            kind = AgentMessageKind.FINAL_ANSWER,
            content = "旧的最终分析结果"
        )
        val executionId = tools.createExecution(
            taskId = historyTaskId,
            nodeId = null,
            toolName = "get_chapter_synopsis",
            toolDisplayName = "读取章节梗概",
            argumentsJson = "{}",
            idempotencyKey = "history-tool-1"
        )
        tools.start(executionId)
        tools.succeed(executionId, resultJson = """{"items":["旧工具结果"]}""")

        val sessionId = sessions.createSession(bookId = bookId, modelId = 1L)
        val taskId = createConfirmedTask(sessionId)
        references.addReference(
            sessionId = sessionId,
            sourceType = AgentReferenceSourceType.AGENT_TASK_RESULT,
            sourceId = historyTaskId,
            bookId = bookId,
            titleSnapshot = "旧的检查任务"
        )
        references.addReference(
            sessionId = sessionId,
            sourceType = AgentReferenceSourceType.AGENT_TOOL_RESULT,
            sourceId = executionId,
            bookId = bookId,
            titleSnapshot = "读取章节梗概"
        )

        val captured = mutableListOf<List<ChatTurn>>()
        val result = engine(captured).execute(taskId, sessionId)
        assertTrue(result is AgentExecutionResult.Completed)
        val prompt = userPrompt(captured, 0)
        assertTrue(prompt.contains("历史 Agent 任务结果"))
        assertTrue(prompt.contains("旧的最终分析结果"))
        assertTrue(prompt.contains("历史 Agent 工具结果"))
        assertTrue(prompt.contains("历史工具执行结果，不是当前任务刚刚执行的结果"))
        assertTrue(prompt.contains("旧工具结果"))
    }
}
