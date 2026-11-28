package com.lianwenqi.app.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentBranchSource
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWorkMode
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F3 规划相关 Repository 验收：任务消息、计划保存、重试、确认、取消与删除 */
@RunWith(AndroidJUnit4::class)
class AgentTaskPlanningRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var tasks: AgentTaskRepository
    private lateinit var nodes: AgentExecutionNodeRepository
    private lateinit var events: AgentEventRepository

    private val planV1 = """
        {"schemaVersion":1,"taskTitle":"计划一","goalSummary":"目标一","thinkingSummary":"说明",
         "needsClarification":false,"clarificationQuestions":[],"steps":[]}
    """.trimIndent()
    private val planV2 = """
        {"schemaVersion":1,"taskTitle":"计划二","goalSummary":"目标二","thinkingSummary":"说明二",
         "needsClarification":false,"clarificationQuestions":[],"steps":[]}
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = AgentSessionRepository(db)
        tasks = AgentTaskRepository(db)
        nodes = AgentExecutionNodeRepository(db)
        events = AgentEventRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newTask(
        goal: String = "检查第三卷大纲",
        sessionId: Long? = null
    ): Long {
        val sid = sessionId ?: sessions.createSession()
        return tasks.createTaskWithUserMessage(sid, goal).first
    }

    private suspend fun savePlan(
        taskId: Long,
        planJson: String = planV1,
        title: String = "计划一",
        previousSummary: String = ""
    ): Int = tasks.savePlanAndWaitConfirmation(
        taskId,
        AgentPlanSaveRequest(
            planJson = planJson,
            taskTitle = title,
            goalSummary = "目标",
            stepCount = 1,
            thinkingSummary = "简短说明",
            previousSummary = previousSummary,
            assistantContent = "已生成执行计划：$title",
            promptTokens = 100,
            completionTokens = 20,
            cachedTokens = 10,
            promptLog = """{"sessionId":1,"taskId":$taskId}"""
        )
    )

    @Test
    fun 创建任务同时保存用户消息并关联taskId() = runBlocking {
        val sessionId = sessions.createSession()
        val taskId = tasks.createTaskWithUserMessage(
            sessionId = sessionId,
            userGoal = "检查第三卷大纲并找出剧情冲突"
        ).first

        val task = tasks.getTask(taskId)!!
        assertEquals("检查第三卷大纲并找出剧情冲突", task.userGoal)
        assertEquals(AgentTaskStatus.CREATED, task.status)
        assertEquals(AgentWorkMode.AUTO, task.workMode)
        assertEquals(taskId, sessions.getSession(sessionId)!!.lastTaskId)

        val message = sessions.getMessages(sessionId).single()
        assertEquals(taskId, message.taskId)
        assertEquals(AgentMessageRole.USER, message.role)
        assertEquals(AgentMessageKind.USER_INPUT, message.kind)
        assertEquals("检查第三卷大纲并找出剧情冲突", message.content)
        assertEquals(0L, message.sequence)
    }

    @Test
    fun 保存计划后等待确认且计划消息与Token落库() = runBlocking {
        val sessionId = sessions.createSession()
        val taskId = newTask(sessionId = sessionId)
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        val version = savePlan(taskId)

        assertEquals(1, version)
        val task = tasks.getTask(taskId)!!
        assertEquals(AgentTaskStatus.WAITING_CONFIRMATION, task.status)
        assertEquals("计划一", task.title)
        assertEquals(planV1, task.planJson)

        val planMessage = sessions.getMessages(sessionId)
            .single { it.kind == AgentMessageKind.PLAN }
        assertEquals(taskId, planMessage.taskId)
        assertEquals("简短说明", planMessage.thinkingSummary)
        assertEquals(100, planMessage.promptTokens)
        assertEquals(20, planMessage.completionTokens)
        assertEquals(10, planMessage.cachedTokens)
        assertTrue(planMessage.promptLog.contains("\"taskId\":$taskId"))

        val types = events.eventsOfTask(taskId).map { it.eventType }
        assertTrue(types.contains(AgentEventType.TASK_PLAN_UPDATED))
        assertTrue(types.contains(AgentEventType.WAITING_FOR_CONFIRMATION))

        assertTrue(tasks.markPlanConfirmed(taskId))
        assertEquals(AgentTaskStatus.WAITING_CONFIRMATION, tasks.getTask(taskId)!!.status)
        assertEquals(
            1,
            events.eventsOfType(taskId, AgentEventType.CONFIRMATION_RECEIVED).size
        )
        // 重复确认不重复写事件
        assertTrue(tasks.markPlanConfirmed(taskId))
        assertEquals(
            1,
            events.eventsOfType(taskId, AgentEventType.CONFIRMATION_RECEIVED).size
        )
    }

    @Test
    fun 重新生成保留旧计划摘要并递增版本() = runBlocking {
        val sessionId = sessions.createSession()
        val taskId = newTask(sessionId = sessionId)
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        savePlan(taskId)

        assertEquals(1, tasks.restartPlanning(taskId))
        assertEquals(AgentTaskStatus.PLANNING, tasks.getTask(taskId)!!.status)
        val version = savePlan(
            taskId = taskId,
            planJson = planV2,
            title = "计划二",
            previousSummary = "v1 · 计划一 · 1 步"
        )
        assertEquals(2, version)
        assertEquals(planV2, tasks.getTask(taskId)!!.planJson)

        val planEvents = events.eventsOfType(taskId, AgentEventType.TASK_PLAN_UPDATED)
        assertEquals(2, planEvents.size)
        assertTrue(planEvents.last().payloadJson.contains("\"planVersion\":2"))
        assertTrue(planEvents.last().payloadJson.contains("previousPlanSummary"))
        assertEquals(
            2,
            sessions.getMessages(sessionId).count { it.kind == AgentMessageKind.PLAN }
        )
    }

    @Test
    fun 编辑计划只允许等待确认并写入用户编辑事件() = runBlocking {
        val taskId = newTask()
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        assertTrue(runCatching {
            tasks.saveEditedPlan(taskId, planV2, "计划二", "目标二", 1)
        }.isFailure)

        savePlan(taskId)
        val version = tasks.saveEditedPlan(
            taskId = taskId,
            planJson = planV2,
            taskTitle = "计划二",
            goalSummary = "目标二",
            stepCount = 1
        )
        assertEquals(2, version)
        assertEquals(planV2, tasks.getTask(taskId)!!.planJson)
        val lastPlanEvent = events.eventsOfType(taskId, AgentEventType.TASK_PLAN_UPDATED).last()
        assertTrue(lastPlanEvent.payloadJson.contains("USER_EDIT"))
    }

    @Test
    fun 规划失败保存错误与提示消息并可显式重试() = runBlocking {
        val taskId = newTask()
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        tasks.failPlanning(
            taskId = taskId,
            errorMessage = "网络连接失败",
            promptTokens = -1,
            cachedTokens = -1
        )
        val failed = tasks.getTask(taskId)!!
        assertEquals(AgentTaskStatus.FAILED, failed.status)
        assertEquals("网络连接失败", failed.errorMessage)

        val notice = sessions.getMessages(failed.sessionId)
            .single { it.kind == AgentMessageKind.NOTICE }
        assertEquals("规划失败：网络连接失败", notice.content)
        assertTrue(
            events.eventsOfTask(taskId).any { it.eventType == AgentEventType.TASK_FAILED }
        )
        // 失败后不能直接保存计划
        assertTrue(runCatching { savePlan(taskId) }.isFailure)
        // F2 的通用状态更新仍禁止终态改写
        assertTrue(runCatching {
            tasks.updateStatus(taskId, AgentTaskStatus.COMPLETED)
        }.isFailure)

        assertEquals(0, tasks.restartPlanning(taskId))
        assertEquals(AgentTaskStatus.PLANNING, tasks.getTask(taskId)!!.status)
    }

    @Test
    fun 取消任务后不可重新规划() = runBlocking {
        val taskId = newTask()
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        tasks.cancelTask(taskId)
        assertEquals(AgentTaskStatus.CANCELLED, tasks.getTask(taskId)!!.status)
        assertTrue(runCatching { tasks.restartPlanning(taskId) }.isFailure)
    }

    @Test
    fun 删除任务清理任务消息事件并修正最近任务() = runBlocking {
        val sessionId = sessions.createSession()
        val first = newTask(goal = "任务一", sessionId = sessionId)
        val second = newTask(goal = "任务二", sessionId = sessionId)
        sessions.addMessage(
            sessionId = sessionId,
            role = AgentMessageRole.USER,
            kind = AgentMessageKind.NOTICE,
            content = "会话级消息"
        )
        assertEquals(second, sessions.getSession(sessionId)!!.lastTaskId)

        assertTrue(tasks.deleteTask(second))
        assertNull(tasks.getTask(second))
        assertTrue(sessions.getMessages(sessionId).none { it.taskId == second })
        assertTrue(events.eventsOfTask(second).isEmpty())
        assertEquals(first, sessions.getSession(sessionId)!!.lastTaskId)

        // 删除后仍保留另一个任务与会话级消息
        assertEquals("任务一", tasks.getTask(first)!!.userGoal)
        assertTrue(sessions.getMessages(sessionId).any { it.taskId == null })
    }

    @Test
    fun 不同任务消息互不混淆() = runBlocking {
        val sessionId = sessions.createSession()
        val first = newTask(goal = "任务一", sessionId = sessionId)
        val second = newTask(goal = "任务二", sessionId = sessionId)
        val messages = sessions.getMessages(sessionId)
        assertEquals(
            listOf("任务一"),
            messages.filter { it.taskId == first }.map { it.content }
        )
        assertEquals(
            listOf("任务二"),
            messages.filter { it.taskId == second }.map { it.content }
        )
    }

    @Test
    fun 有执行节点的任务不能删除() = runBlocking {
        val taskId = newTask()
        nodes.createNode(taskId, AgentNodeType.SEARCH_OUTLINE, "读取大纲")
        assertTrue(runCatching { tasks.deleteTask(taskId) }.isFailure)
        assertTrue(tasks.getTask(taskId) != null)
    }

    @Test
    fun 保存计划要求任务处于规划中() = runBlocking {
        val taskId = newTask()
        assertFalse(runCatching { savePlan(taskId) }.isSuccess)
    }

    @Test
    fun 需要澄清的计划不生成计划消息() = runBlocking {
        val sessionId = sessions.createSession()
        val taskId = tasks.createTaskWithUserMessage(sessionId, "原目标").first
        tasks.updateStatus(taskId, AgentTaskStatus.PLANNING)
        tasks.savePlanAndWaitConfirmation(
            taskId = taskId,
            request = AgentPlanSaveRequest(
                planJson = planV1,
                taskTitle = "需要补充信息",
                goalSummary = "目标",
                stepCount = 0,
                thinkingSummary = "说明",
                assistantContent = "已生成执行计划：需要补充信息"
            ),
            emitPlanMessage = false
        )
        tasks.createClarification(taskId, "请补充范围", listOf("第一卷"))

        assertTrue(
            sessions.getMessages(sessionId).none {
                it.taskId == taskId && it.kind == AgentMessageKind.PLAN
            }
        )
        assertEquals(
            1,
            sessions.getMessages(sessionId).count {
                it.taskId == taskId && it.kind == AgentMessageKind.USER_INPUT
            }
        )
    }

    @Test
    fun 编辑提问创建子分支且父分支保持不变() = runBlocking {
        val sessionId = sessions.createSession()
        val parentId = tasks.createTaskWithUserMessage(
            sessionId = sessionId,
            userGoal = "原目标"
        ).first
        tasks.updateStatus(parentId, AgentTaskStatus.PLANNING)
        savePlan(parentId)

        val (childId, _) = tasks.createBranchTask(
            parentTaskId = parentId,
            userGoal = "新目标",
            branchSource = AgentBranchSource.USER_EDIT,
            contextSnapshot = "上下文快照"
        )

        val parent = tasks.getTask(parentId)!!
        assertEquals(AgentTaskStatus.WAITING_CONFIRMATION, parent.status)
        assertEquals("原目标", parent.userGoal)
        assertTrue(parent.planJson.contains("计划一"))
        assertEquals(AgentWorkMode.AUTO, parent.workMode)
        assertNull(parent.parentTaskId)

        val child = tasks.getTask(childId)!!
        assertEquals("新目标", child.userGoal)
        assertEquals(parentId, child.parentTaskId)
        assertEquals(parentId, child.rootTaskId)
        assertEquals(AgentWorkMode.AUTO, child.workMode)
        assertEquals(AgentBranchSource.USER_EDIT, child.branchSource)
        assertEquals(AgentTaskStatus.CREATED, child.status)
        assertEquals("{}", child.planJson)
        assertEquals(
            "上下文快照",
            sessions.getMessages(sessionId).single { it.taskId == childId }.contextSnapshot
        )
    }

    @Test
    fun 旧计划任务派生的重发与重新生成分支固定为自动模式且不改写旧计划() = runBlocking {
        val sessionId = sessions.createSession()
        val parentId = newTask(sessionId = sessionId)
        tasks.updateStatus(parentId, AgentTaskStatus.PLANNING)
        savePlan(parentId)

        val legacyParent = tasks.getTask(parentId)!!
        db.agentTaskDao().updateTask(legacyParent.copy(workMode = AgentWorkMode.PLAN))

        val (editedId, _) = tasks.createBranchTask(
            parentTaskId = parentId,
            userGoal = "修改后的目标",
            branchSource = AgentBranchSource.USER_EDIT
        )
        val (regeneratedId, _) = tasks.createBranchTask(
            parentTaskId = parentId,
            userGoal = "原目标",
            branchSource = AgentBranchSource.PLAN_REGENERATE
        )

        val unchangedParent = tasks.getTask(parentId)!!
        assertEquals(AgentWorkMode.PLAN, unchangedParent.workMode)
        assertEquals(planV1, unchangedParent.planJson)
        listOf(editedId, regeneratedId).forEach { childId ->
            val child = tasks.getTask(childId)!!
            assertEquals(AgentWorkMode.AUTO, child.workMode)
            assertEquals(AgentTaskStatus.CREATED, child.status)
            assertEquals("{}", child.planJson)
        }
    }

    @Test
    fun 修改计划创建等待确认分支且澄清问题可回答() = runBlocking {
        val sessionId = sessions.createSession()
        val parentId = tasks.createTaskWithUserMessage(sessionId, "原目标").first
        tasks.updateStatus(parentId, AgentTaskStatus.PLANNING)
        savePlan(parentId)

        val (childId, _) = tasks.createBranchTask(
            parentTaskId = parentId,
            userGoal = "原目标",
            branchSource = AgentBranchSource.PLAN_EDIT,
            title = "计划二",
            planJson = planV2,
            assistantPlanContent = "已保存修改后的计划：计划二"
        )

        val child = tasks.getTask(childId)!!
        assertEquals(AgentTaskStatus.WAITING_CONFIRMATION, child.status)
        assertEquals(AgentWorkMode.AUTO, child.workMode)
        assertEquals(planV2, child.planJson)
        assertTrue(
            events.eventsOfTask(childId).any { it.eventType == AgentEventType.TASK_PLAN_UPDATED }
        )

        val clarificationId = tasks.createClarification(
            taskId = childId,
            question = "要检查哪一卷？",
            options = listOf("第一卷", "第二卷")
        )
        val pending = tasks.pendingClarification(childId)!!
        assertEquals("要检查哪一卷？", pending.question)
        assertEquals(
            listOf("第一卷", "第二卷"),
            tasks.clarificationOptions(pending.optionsJson)
        )

        val answered = tasks.answerClarification(clarificationId, "第二卷")!!
        assertEquals("第二卷", answered.answer)
        assertNull(tasks.pendingClarification(childId))
        assertEquals(
            listOf("第二卷"),
            tasks.answeredClarifications(childId).map { it.answer }
        )
    }
}
