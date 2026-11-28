package com.lianwenqi.app.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.agent.data.entity.AgentWorkMode
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.ChatRepository
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Agent 任务 / 节点 / 工具执行 / 事件仓库验收（内存数据库） */
@RunWith(AndroidJUnit4::class)
class AgentTaskRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var tasks: AgentTaskRepository
    private lateinit var nodes: AgentExecutionNodeRepository
    private lateinit var tools: AgentToolExecutionRepository
    private lateinit var events: AgentEventRepository

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

    private suspend fun newSession(): Long = sessions.createSession()

    private suspend fun newTask(
        sessionId: Long? = null,
        goal: String = "检查第三卷大纲"
    ): Long = tasks.createTask(sessionId = sessionId ?: newSession(), userGoal = goal)

    // ---------------- 任务 ----------------

    @Test
    fun 创建任务后更新会话最近任务并写入创建事件() = runBlocking {
        val sessionId = newSession()
        val taskId = tasks.createTask(
            sessionId = sessionId,
            userGoal = "检查第三卷大纲并找出剧情冲突",
            title = "剧情冲突检查"
        )

        val task = tasks.getTask(taskId)!!
        assertEquals(sessionId, task.sessionId)
        assertEquals("剧情冲突检查", task.title)
        assertEquals("检查第三卷大纲并找出剧情冲突", task.userGoal)
        assertEquals(AgentTaskStatus.CREATED, task.status)
        assertEquals(AgentWorkMode.AUTO, task.workMode)
        assertEquals("{}", task.planJson)
        assertEquals(taskId, sessions.getSession(sessionId)!!.lastTaskId)

        val eventList = events.eventsOfTask(taskId)
        assertEquals(1, eventList.size)
        assertEquals(AgentEventType.TASK_CREATED, eventList.single().eventType)
        assertEquals(0L, eventList.single().sequence)
        assertTrue(eventList.single().payloadJson.contains("检查第三卷大纲"))
    }

    @Test
    fun 同一会话可以创建多个任务并切换最近任务() = runBlocking {
        val sessionId = newSession()
        val first = tasks.createTask(sessionId, "任务一")
        val second = tasks.createTask(sessionId, "任务二")

        assertEquals(2, tasks.tasksOfSession(sessionId).size)
        assertEquals(second, sessions.getSession(sessionId)!!.lastTaskId)

        assertTrue(tasks.markAsRecent(first))
        assertEquals(first, sessions.getSession(sessionId)!!.lastTaskId)
        assertEquals(1L, sessions.getSession(sessionId)!!.lastTaskId)
    }

    @Test
    fun 不存在的会话不能创建任务且不留下半成品() = runBlocking {
        val failed = runCatching { tasks.createTask(999L, "孤儿任务") }
        assertTrue(failed.isFailure)
        assertTrue(tasks.unfinishedTasks().isEmpty())
        assertEquals(-1L, events.latestSequence(999L))
    }

    @Test
    fun 任务状态区分失败取消与完成且终态不可改写() = runBlocking {
        val sessionId = newSession()
        val taskId = tasks.createTask(sessionId, "目标")
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        tasks.updateStatus(taskId, AgentTaskStatus.RUNNING)
        assertNotNull(tasks.getTask(taskId)!!.startedAt)
        tasks.updateStatus(taskId, AgentTaskStatus.PAUSED)
        tasks.updateStatus(taskId, AgentTaskStatus.RUNNING)
        tasks.updateStatus(taskId, AgentTaskStatus.COMPLETED)
        assertEquals(AgentTaskStatus.COMPLETED, tasks.getTask(taskId)!!.status)
        assertNotNull(tasks.getTask(taskId)!!.completedAt)

        val eventTypes = events.eventsOfTask(taskId).map { it.eventType }
        assertTrue(eventTypes.contains(AgentEventType.TASK_PLANNING))
        assertTrue(eventTypes.contains(AgentEventType.TASK_STARTED))
        assertTrue(eventTypes.contains(AgentEventType.TASK_PAUSED))
        assertTrue(eventTypes.contains(AgentEventType.TASK_RESUMED))
        assertTrue(eventTypes.contains(AgentEventType.TASK_COMPLETED))

        val failedTask = tasks.createTask(sessionId, "失败任务")
        tasks.updateStatus(failedTask, AgentTaskStatus.RUNNING)
        tasks.updateStatus(failedTask, AgentTaskStatus.FAILED, errorMessage = "模型超时")
        assertEquals(AgentTaskStatus.FAILED, tasks.getTask(failedTask)!!.status)
        assertEquals("模型超时", tasks.getTask(failedTask)!!.errorMessage)
        assertTrue(runCatching {
            tasks.updateStatus(failedTask, AgentTaskStatus.COMPLETED)
        }.isFailure)
        assertEquals(AgentTaskStatus.FAILED, tasks.getTask(failedTask)!!.status)

        val cancelledTask = tasks.createTask(sessionId, "取消任务")
        tasks.updateStatus(cancelledTask, AgentTaskStatus.CANCELLED)
        assertEquals(AgentTaskStatus.CANCELLED, tasks.getTask(cancelledTask)!!.status)
        assertTrue(
            events.eventsOfTask(cancelledTask).any { it.eventType == AgentEventType.TASK_CANCELLED }
        )
    }

    @Test
    fun 等待确认状态与完成状态可区分() = runBlocking {
        val taskId = newTask()
        tasks.updateStatus(taskId, AgentTaskStatus.RUNNING)
        tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
        assertEquals(
            AgentTaskStatus.WAITING_CONFIRMATION,
            tasks.getTask(taskId)!!.status
        )
        assertTrue(
            events.eventsOfTask(taskId).any {
                it.eventType == AgentEventType.WAITING_FOR_CONFIRMATION
            }
        )
        tasks.updateStatus(taskId, AgentTaskStatus.RUNNING)
        tasks.updateStatus(taskId, AgentTaskStatus.COMPLETED)
        val types = events.eventsOfTask(taskId).map { it.eventType }
        assertTrue(types.contains(AgentEventType.TASK_RESUMED))
        assertTrue(types.contains(AgentEventType.TASK_COMPLETED))
    }

    @Test
    fun 更新计划必须为合法JSON且记录计划事件() = runBlocking {
        val taskId = newTask()
        tasks.updatePlan(taskId, """{"steps":[{"type":"READ_OUTLINE"}]}""")
        assertTrue(tasks.getTask(taskId)!!.planJson.contains("READ_OUTLINE"))
        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == AgentEventType.TASK_PLAN_UPDATED }
        )
        assertTrue(runCatching { tasks.updatePlan(taskId, "not-json") }.isFailure)
    }

    // ---------------- 节点 ----------------

    @Test
    fun 节点按sequence稳定排序且状态变化写入事件() = runBlocking {
        val taskId = newTask()
        val second = nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.SEARCH_CHAPTER,
            title = "检索章节",
            sequence = 2
        )
        val first = nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.SEARCH_OUTLINE,
            title = "读取大纲",
            sequence = 0
        )
        val middle = nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.VALIDATE,
            title = "校验",
            sequence = 1
        )

        assertEquals(
            listOf(first, middle, second),
            nodes.nodesOfTask(taskId).map { it.id }
        )
        assertEquals(listOf(0L, 1L, 2L), nodes.nodesOfTask(taskId).map { it.sequence })

        nodes.updateStatus(first, AgentNodeStatus.RUNNING)
        nodes.updateStatus(
            first,
            AgentNodeStatus.COMPLETED,
            outputJson = """{"count":6}"""
        )
        val nodeEvents = events.eventsOfTask(taskId)
            .filter { it.nodeId == first }
        assertEquals(
            listOf(AgentEventType.NODE_STARTED, AgentEventType.NODE_COMPLETED),
            nodeEvents.map { it.eventType }
        )
        assertEquals(1, nodes.completedNodes(taskId).size)
    }

    @Test
    fun 节点支持父子关系且拒绝跨任务父节点() = runBlocking {
        val taskId = newTask()
        val parent = nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.PLANNING,
            title = "规划"
        )
        val child = nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.SEARCH_OUTLINE,
            title = "读取大纲",
            parentNodeId = parent
        )
        assertEquals(parent, nodes.getNode(child)!!.parentNodeId)

        val otherTask = newTask()
        val otherNode = nodes.createNode(
            taskId = otherTask,
            nodeType = AgentNodeType.THINKING,
            title = "其他任务节点"
        )
        assertTrue(runCatching {
            nodes.createNode(
                taskId = taskId,
                nodeType = AgentNodeType.TOOL_CALL,
                title = "非法子节点",
                parentNodeId = otherNode
            )
        }.isFailure)
    }

    @Test
    fun 节点终态不可回退且不能回退到PENDING() = runBlocking {
        val taskId = newTask()
        val nodeId = nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.VALIDATE,
            title = "校验"
        )
        nodes.updateStatus(nodeId, AgentNodeStatus.RUNNING)
        nodes.updateStatus(nodeId, AgentNodeStatus.FAILED, errorMessage = "校验失败")
        assertTrue(runCatching {
            nodes.updateStatus(nodeId, AgentNodeStatus.RUNNING)
        }.isFailure)
        assertEquals(AgentNodeStatus.FAILED, nodes.getNode(nodeId)!!.status)
        assertEquals("校验失败", nodes.getNode(nodeId)!!.errorMessage)
    }

    @Test
    fun 重复节点sequence被唯一索引拒绝() = runBlocking {
        val taskId = newTask()
        nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.THINKING,
            title = "节点一",
            sequence = 0
        )
        assertTrue(runCatching {
            nodes.createNode(
                taskId = taskId,
                nodeType = AgentNodeType.THINKING,
                title = "重复序号",
                sequence = 0
            )
        }.isFailure)
        assertEquals(1, nodes.nodesOfTask(taskId).size)
    }

    // ---------------- 工具执行 ----------------

    @Test
    fun 工具执行的参数结果错误与状态可持久化() = runBlocking {
        val taskId = newTask()
        val nodeId = nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.SEARCH_OUTLINE,
            title = "读取大纲"
        )

        val successId = tools.createExecution(
            taskId = taskId,
            nodeId = nodeId,
            toolName = "get_outline",
            toolDisplayName = "读取大纲",
            argumentsJson = """{"volumeId":3}""",
            idempotencyKey = "k-success"
        )
        tools.start(successId)
        tools.succeed(successId, resultJson = """{"items":6}""")
        val success = tools.getExecution(successId)!!
        assertEquals(AgentToolStatus.SUCCEEDED, success.status)
        assertEquals("""{"volumeId":3}""", success.argumentsJson)
        assertEquals("""{"items":6}""", success.resultJson)
        assertNotNull(success.startedAt)
        assertNotNull(success.completedAt)
        assertNotNull(success.durationMs)
        assertEquals(successId, tools.findByIdempotencyKey("k-success")!!.id)

        val failId = tools.createExecution(
            taskId = taskId,
            nodeId = nodeId,
            toolName = "get_outline",
            argumentsJson = "{}"
        )
        tools.start(failId)
        tools.fail(failId, errorMessage = "读取失败", resultJson = """{"error":"timeout"}""")
        val failed = tools.getExecution(failId)!!
        assertEquals(AgentToolStatus.FAILED, failed.status)
        assertEquals("读取失败", failed.errorMessage)
        assertEquals("""{"error":"timeout"}""", failed.resultJson)
        // 同一节点 / 工具的调用序号递增
        assertEquals(1, success.attempt)
        assertEquals(2, failed.attempt)

        val skipId = tools.createExecution(taskId, "get_chapter_content", argumentsJson = "{}")
        tools.skip(skipId, reason = "范围未命中")
        assertEquals(AgentToolStatus.SKIPPED, tools.getExecution(skipId)!!.status)
        assertEquals("范围未命中", tools.getExecution(skipId)!!.errorMessage)

        val cancelId = tools.createExecution(taskId, "retrieve_memory", argumentsJson = "{}")
        tools.cancel(cancelId, reason = "用户取消")
        assertEquals(AgentToolStatus.CANCELLED, tools.getExecution(cancelId)!!.status)

        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == AgentEventType.TOOL_CALL_STARTED }
        )
        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == AgentEventType.TOOL_CALL_COMPLETED }
        )
        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == AgentEventType.TOOL_CALL_FAILED }
        )
        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == AgentEventType.TOOL_CALL_SKIPPED }
        )
        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == AgentEventType.TOOL_CALL_CANCELLED }
        )
    }

    @Test
    fun 工具执行必须属于任务且参数必须为JSON() = runBlocking {
        val taskId = newTask()
        val otherTask = newTask()
        val otherNode = nodes.createNode(
            taskId = otherTask,
            nodeType = AgentNodeType.TOOL_CALL,
            title = "其他节点"
        )
        assertTrue(runCatching {
            tools.createExecution(
                taskId = taskId,
                nodeId = otherNode,
                toolName = "get_outline"
            )
        }.isFailure)
        assertTrue(runCatching {
            tools.createExecution(taskId, "get_outline", argumentsJson = "bad-json")
        }.isFailure)
    }

    // ---------------- 事件 ----------------

    @Test
    fun 事件严格递增批量连续且增量查询正确() = runBlocking {
        val taskId = newTask()
        val initial = events.eventsOfTask(taskId).size
        assertEquals(1, initial)

        repeat(4) { index ->
            events.append(
                taskId = taskId,
                eventType = AgentEventType.THINKING_SUMMARY,
                payloadJson = """{"index":$index}"""
            )
        }
        events.appendAll(
            taskId = taskId,
            events = listOf(
                AgentEventDraft(AgentEventType.NODE_PROGRESS, payloadJson = """{"step":1}"""),
                AgentEventDraft(AgentEventType.NODE_PROGRESS, payloadJson = """{"step":2}""")
            )
        )
        val all = events.eventsOfTask(taskId)
        assertEquals((0L until all.size.toLong()).toList(), all.map { it.sequence })
        assertEquals(all.last().sequence, events.latestSequence(taskId))
        assertEquals(
            all.filter { it.sequence > 3 }.map { it.sequence },
            events.eventsAfter(taskId, 3).map { it.sequence }
        )
        assertEquals(
            4,
            events.eventsOfType(taskId, AgentEventType.THINKING_SUMMARY).size
        )
        assertTrue(runCatching {
            events.append(taskId, AgentEventType.NODE_PROGRESS, payloadJson = "not-json")
        }.isFailure)
        assertEquals(2, events.eventsOfType(taskId, AgentEventType.NODE_PROGRESS).size)
    }

    @Test
    fun 并发追加事件不会产生重复sequence() = runBlocking {
        val taskId = newTask()
        coroutineScope {
            repeat(20) {
                launch(Dispatchers.IO) {
                    events.append(
                        taskId = taskId,
                        eventType = AgentEventType.NODE_PROGRESS,
                        payloadJson = """{"tick":$it}"""
                    )
                }
            }
        }
        val all = events.eventsOfTask(taskId)
        assertEquals(21, all.size)
        assertEquals(21, all.map { it.sequence }.distinct().size)
        assertEquals((0L..20L).toList(), all.map { it.sequence })
    }

    // ---------------- 恢复查询 ----------------

    @Test
    fun 未完成任务与运行中节点工具可查询() = runBlocking {
        val sessionId = newSession()
        val running = tasks.createTask(sessionId, "运行中")
        val paused = tasks.createTask(sessionId, "暂停")
        val completed = tasks.createTask(sessionId, "已完成")
        tasks.updateStatus(running, AgentTaskStatus.RUNNING)
        tasks.updateStatus(paused, AgentTaskStatus.PAUSED)
        tasks.updateStatus(completed, AgentTaskStatus.RUNNING)
        tasks.updateStatus(completed, AgentTaskStatus.COMPLETED)

        assertEquals(
            setOf(running, paused),
            tasks.unfinishedTasks().map { it.id }.toSet()
        )
        assertEquals(
            setOf(running, paused),
            tasks.unfinishedTasksOfSession(sessionId).map { it.id }.toSet()
        )

        val runningNode = nodes.createNode(
            taskId = running,
            nodeType = AgentNodeType.SEARCH_CHAPTER,
            title = "检索中"
        )
        val doneNode = nodes.createNode(
            taskId = running,
            nodeType = AgentNodeType.SEARCH_OUTLINE,
            title = "已完成节点"
        )
        nodes.updateStatus(runningNode, AgentNodeStatus.RUNNING)
        nodes.updateStatus(doneNode, AgentNodeStatus.RUNNING)
        nodes.updateStatus(doneNode, AgentNodeStatus.COMPLETED)
        assertEquals(listOf(runningNode), nodes.runningNodes(running).map { it.id })
        assertEquals(listOf(doneNode), nodes.completedNodes(running).map { it.id })

        val runningTool = tools.createExecution(running, "search_chapters")
        tools.start(runningTool)
        val doneTool = tools.createExecution(running, "get_outline")
        tools.start(doneTool)
        tools.succeed(doneTool, resultJson = """{"ok":true}""")
        assertEquals(listOf(runningTool), tools.runningExecutions(running).map { it.id })
        assertEquals(listOf(doneTool), tools.succeededExecutions(running).map { it.id })

        assertEquals(
            events.eventsOfTask(running).last().sequence,
            events.latestSequence(running)
        )
        assertNotNull(events.latestEvent(running))
    }

    @Test
    fun 计划书库保留旧计划并纳入AUTO任务中的计划() = runBlocking {
        val bookRepository = BookRepository(db)
        val bookId = bookRepository.createBook("计划书库", "", null, 0, "第%n卷", "第%n章")
        val otherBookId = bookRepository.createBook("其他书", "", null, 0, "第%n卷", "第%n章")
        val legacySessionId = sessions.createSession(bookId = bookId)
        val autoSessionId = sessions.createSession(bookId = bookId)
        val otherSessionId = sessions.createSession(bookId = otherBookId)

        val legacyTaskId = tasks.createTask(legacySessionId, "旧计划任务")
        val legacyTask = tasks.getTask(legacyTaskId)!!
        db.agentTaskDao().updateTask(
            legacyTask.copy(workMode = AgentWorkMode.PLAN, planJson = """{"legacy":true}""")
        )
        val autoTaskId = tasks.createTask(autoSessionId, "自动模式计划任务")
        tasks.updatePlan(autoTaskId, """{"auto":true}""")
        tasks.createTask(autoSessionId, "尚无计划任务")
        val otherBookTaskId = tasks.createTask(otherSessionId, "其他书计划任务")
        tasks.updatePlan(otherBookTaskId, """{"other":true}""")

        assertEquals(
            setOf(legacyTaskId, autoTaskId),
            tasks.tasksWithPlanOfBook(bookId).map { it.id }.toSet()
        )
        assertEquals(AgentWorkMode.AUTO, tasks.getTask(autoTaskId)!!.workMode)
        assertEquals(AgentWorkMode.PLAN, tasks.getTask(legacyTaskId)!!.workMode)
    }

    // ---------------- 删除与事务一致性 ----------------

    @Test
    fun 删除会话清理全部Agent关联数据且不影响普通聊天与小说数据() = runBlocking {
        val bookId = BookRepository(db).createBook("小说", "", null, 0, "第%n卷", "第%n章")
        val chat = ChatRepository(db)
        val chatSessionId = chat.createSession("普通会话", null, null)
        db.chatDao().insertMessage(
            ChatMessage(sessionId = chatSessionId, role = ChatRole.USER, content = "普通消息")
        )

        val sessionA = sessions.createSession(bookId = bookId)
        val sessionB = sessions.createSession(bookId = bookId)
        sessions.addMessage(
            sessionId = sessionA,
            role = AgentMessageRole.USER,
            kind = AgentMessageKind.USER_INPUT,
            content = "A 消息"
        )
        AgentReferenceRepository(db).addReference(
            sessionId = sessionA,
            sourceType = AgentReferenceSourceType.CHAPTER_CONTENT,
            sourceId = 1L,
            bookId = bookId
        )

        val taskA = tasks.createTask(sessionA, "A 任务")
        val nodeA = nodes.createNode(taskA, AgentNodeType.SEARCH_OUTLINE, "A 节点")
        val toolA = tools.createExecution(
            taskId = taskA,
            nodeId = nodeA,
            toolName = "get_outline"
        )
        tools.start(toolA)
        val taskB = tasks.createTask(sessionB, "B 任务")
        val nodeB = nodes.createNode(taskB, AgentNodeType.SEARCH_OUTLINE, "B 节点")

        sessions.deleteSession(sessionA)

        assertNull(sessions.getSession(sessionA))
        assertTrue(sessions.getMessages(sessionA).isEmpty())
        assertTrue(db.agentTaskDao().tasksOfSession(sessionA).isEmpty())
        assertTrue(db.agentTaskDao().nodesOfTask(taskA).isEmpty())
        assertTrue(db.agentTaskDao().toolExecutionsOfTask(taskA).isEmpty())
        assertTrue(db.agentTaskDao().eventsOfTask(taskA).isEmpty())

        // 其他 Agent 会话不受影响
        assertNotNull(sessions.getSession(sessionB))
        assertEquals("B 任务", tasks.getTask(taskB)!!.userGoal)
        assertNotNull(nodes.getNode(nodeB))

        // 普通聊天 / 小说数据不受影响
        assertEquals("小说", db.bookDao().getById(bookId)!!.title)
        assertNotNull(db.bookDao().getById(bookId))
        assertEquals("普通会话", chat.getSession(chatSessionId)!!.title)
        assertEquals("普通消息", chat.getMessages(chatSessionId).single().content)
    }

    @Test
    fun 事务中途失败不会留下节点状态变更() = runBlocking {
        val taskId = newTask()
        val nodeId = nodes.createNode(
            taskId = taskId,
            nodeType = AgentNodeType.SEARCH_OUTLINE,
            title = "读取大纲"
        )
        val before = events.eventsOfTask(taskId).size

        val raw = db.openHelper.writableDatabase
        raw.execSQL(
            "CREATE TRIGGER fail_agent_event BEFORE INSERT ON agent_event " +
                "BEGIN SELECT RAISE(ABORT, 'forced event failure'); END"
        )
        try {
            val result = runCatching { nodes.updateStatus(nodeId, AgentNodeStatus.RUNNING) }
            assertTrue(result.isFailure)
        } finally {
            raw.execSQL("DROP TRIGGER IF EXISTS fail_agent_event")
        }

        assertEquals(AgentNodeStatus.PENDING, nodes.getNode(nodeId)!!.status)
        assertEquals(before, events.eventsOfTask(taskId).size)
    }
}
