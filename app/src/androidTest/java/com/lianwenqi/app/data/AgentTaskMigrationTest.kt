package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentToolExecutionRepository
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F2 迁移验证：v27 真实旧库（含 F1 Agent 数据）升级到 v28。
 *
 * 验证四张新表与索引、F1 数据完整、任务 / 节点 / 工具 / 事件可读写，
 * 以及删除 Agent 会话后所有 Agent 关联数据被清理而普通数据不变。
 */
@RunWith(AndroidJUnit4::class)
class AgentTaskMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-v27-v29.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        AgentMigrationFixture.createV27Database(context, dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun v27升级到v29后新表存在且F1数据完整() = runBlocking {
        val db = openV29()
        try {
            assertEquals(32, db.openHelper.writableDatabase.version)

            assertTrue(tableExists(db, "agent_task"))
            assertTrue(tableExists(db, "agent_execution_node"))
            assertTrue(tableExists(db, "agent_tool_execution"))
            assertTrue(tableExists(db, "agent_event"))

            assertTrue(indexExists(db, "index_agent_task_sessionId"))
            assertTrue(indexExists(db, "index_agent_task_status"))
            assertTrue(indexExists(db, "index_agent_task_updatedAt"))
            assertTrue(indexExists(db, "index_agent_execution_node_taskId_sequence"))
            assertTrue(indexExists(db, "index_agent_execution_node_taskId_status"))
            assertTrue(indexExists(db, "index_agent_execution_node_taskId_nodeKey"))
            assertTrue(indexExists(db, "index_agent_execution_node_parentNodeId"))
            assertTrue(indexExists(db, "index_agent_tool_execution_taskId"))
            assertTrue(indexExists(db, "index_agent_tool_execution_nodeId"))
            assertTrue(indexExists(db, "index_agent_tool_execution_taskId_status"))
            assertTrue(indexExists(db, "index_agent_tool_execution_idempotencyKey"))
            assertTrue(indexExists(db, "index_agent_event_taskId_sequence"))
            assertTrue(indexExists(db, "index_agent_event_taskId_eventType"))
            assertTrue(indexExists(db, "index_agent_event_nodeId"))
            assertTrue(indexExists(db, "index_agent_event_toolExecutionId"))

            // F1 数据完整
            val session = db.agentDao().getSession(1)!!
            assertEquals("旧 Agent 会话", session.title)
            assertEquals("旧 Agent 消息", db.agentDao().getMessages(1).single().content)
            assertEquals(1, db.agentDao().references(1).size)

            // 普通数据完整
            assertEquals("旧书", db.bookDao().getById(1)!!.title)
            assertEquals("旧正文", db.chapterDao().getById(1)!!.content)
            assertEquals("旧人设", db.outlineDao().getById(1)!!.title)
            assertEquals("旧会话", db.chatDao().getSession(1)!!.title)
            assertEquals("旧消息", db.chatDao().getMessages(1).single().content)
        } finally {
            db.close()
        }
    }

    @Test
    fun 升级后任务节点工具事件可读写且删除会话清理全部Agent数据() = runBlocking {
        val db = openV29()
        try {
            val sessions = AgentSessionRepository(db)
            val tasks = AgentTaskRepository(db)
            val nodes = AgentExecutionNodeRepository(db)
            val tools = AgentToolExecutionRepository(db)
            val events = AgentEventRepository(db)

            val taskId = tasks.createTask(
                sessionId = 1L,
                userGoal = "检查第三卷大纲并找出剧情冲突",
                title = "剧情冲突检查"
            )
            var task = tasks.getTask(taskId)!!
            assertEquals("检查第三卷大纲并找出剧情冲突", task.userGoal)
            assertEquals(AgentTaskStatus.CREATED, task.status)
            assertEquals(0L, events.latestSequence(taskId))
            assertEquals(1L, db.agentDao().getSession(1)!!.lastTaskId)

            tasks.updateStatus(taskId, AgentTaskStatus.RUNNING)
            task = tasks.getTask(taskId)!!
            assertEquals(AgentTaskStatus.RUNNING, task.status)
            assertNotNull(task.startedAt)

            val nodeId = nodes.createNode(
                taskId = taskId,
                nodeType = AgentNodeType.SEARCH_OUTLINE,
                title = "读取第三卷大纲",
                nodeKey = "read_outline_v3"
            )
            nodes.updateStatus(nodeId, AgentNodeStatus.RUNNING)
            nodes.updateStatus(
                nodeId,
                AgentNodeStatus.COMPLETED,
                outputJson = """{"count":6}"""
            )
            assertEquals(AgentNodeStatus.COMPLETED, nodes.getNode(nodeId)!!.status)
            assertEquals(listOf(nodeId), nodes.completedNodes(taskId).map { it.id })

            val executionId = tools.createExecution(
                taskId = taskId,
                nodeId = nodeId,
                toolName = "get_outline",
                toolDisplayName = "读取大纲",
                argumentsJson = """{"volumeId":3}""",
                idempotencyKey = "task-$taskId-node-$nodeId-outline"
            )
            assertEquals(AgentToolStatus.PENDING, tools.getExecution(executionId)!!.status)
            tools.start(executionId)
            tools.succeed(executionId, resultJson = """{"items":6}""")
            val execution = tools.getExecution(executionId)!!
            assertEquals(AgentToolStatus.SUCCEEDED, execution.status)
            assertEquals("""{"items":6}""", execution.resultJson)
            assertNotNull(execution.completedAt)
            assertNotNull(execution.durationMs)
            assertEquals(executionId, tools.findByIdempotencyKey(execution.idempotencyKey!!)!!.id)
            assertEquals(1, tools.succeededExecutions(taskId).size)

            // 事件严格递增，且节点 / 工具事件带关联 id
            val eventList = events.eventsOfTask(taskId)
            assertEquals((0L until eventList.size.toLong()).toList(), eventList.map { it.sequence })
            assertNotNull(eventList.firstOrNull { it.eventType == "NODE_COMPLETED" }!!.nodeId)
            assertEquals(
                executionId,
                eventList.firstOrNull { it.eventType == "TOOL_CALL_COMPLETED" }!!.toolExecutionId
            )
            assertEquals(eventList.last().sequence, events.latestSequence(taskId))

            tasks.updateStatus(taskId, AgentTaskStatus.COMPLETED)
            assertEquals(AgentTaskStatus.COMPLETED, tasks.getTask(taskId)!!.status)
            assertTrue(tasks.unfinishedTasks().isEmpty())

            // 删除会话：所有 Agent 数据清理，普通数据不变
            sessions.deleteSession(1L)
            assertNull(db.agentDao().getSession(1))
            assertTrue(db.agentDao().getMessages(1).isEmpty())
            assertTrue(db.agentDao().references(1).isEmpty())
            assertTrue(db.agentTaskDao().tasksOfSession(1).isEmpty())
            assertTrue(db.agentTaskDao().nodesOfTask(taskId).isEmpty())
            assertTrue(db.agentTaskDao().toolExecutionsOfTask(taskId).isEmpty())
            assertTrue(db.agentTaskDao().eventsOfTask(taskId).isEmpty())

            assertEquals("旧书", db.bookDao().getById(1)!!.title)
            assertEquals("旧正文", db.chapterDao().getById(1)!!.content)
            assertEquals("旧人设", db.outlineDao().getById(1)!!.title)
            assertEquals("旧会话", db.chatDao().getSession(1)!!.title)
            assertEquals("旧消息", db.chatDao().getMessages(1).single().content)
        } finally {
            db.close()
        }
    }

    private fun openV29(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_27_28,
                AppDatabase.MIGRATION_28_29,
                AppDatabase.MIGRATION_29_30,
                AppDatabase.MIGRATION_30_31,
                AppDatabase.MIGRATION_31_32
            )
            .allowMainThreadQueries()
            .build()

    private fun tableExists(db: AppDatabase, name: String): Boolean =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name)
        ).use { it.moveToFirst() }

    private fun indexExists(db: AppDatabase, name: String): Boolean =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name)
        ).use { it.moveToFirst() }
}
