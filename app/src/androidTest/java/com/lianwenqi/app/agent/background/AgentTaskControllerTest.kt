package com.lianwenqi.app.agent.background

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentWriteDraft
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.agent.data.entity.AgentWriteTargetType
import com.lianwenqi.app.agent.write.AgentWriteExecutionService
import com.lianwenqi.app.agent.write.RoomAgentWriteBusinessWriter
import com.lianwenqi.app.agent.write.RoomAgentWriteTransactionRunner
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.OutlineRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** F7：暂停 / 恢复 / 取消状态与调度入队 */
@RunWith(AndroidJUnit4::class)
class AgentTaskControllerTest {

    private class RecordingScheduler : AgentTaskScheduler {
        val enqueued = CopyOnWriteArrayList<Long>()
        val cancelled = CopyOnWriteArrayList<Long>()

        override suspend fun enqueue(taskId: Long, reason: String) {
            enqueued += taskId
        }

        override suspend fun cancelScheduled(taskId: Long) {
            cancelled += taskId
        }

        override suspend fun enqueueRecoveryScan(reason: String) = Unit
        override suspend fun ensurePeriodicRecovery() = Unit
    }

    private lateinit var db: AppDatabase
    private lateinit var tasks: AgentTaskRepository
    private lateinit var sessions: AgentSessionRepository
    private lateinit var writes: AgentWriteOperationRepository
    private lateinit var controller: AgentTaskController
    private lateinit var scheduler: RecordingScheduler

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tasks = AgentTaskRepository(db)
        sessions = AgentSessionRepository(db)
        writes = AgentWriteOperationRepository(db)
        val books = BookRepository(db)
        val outlines = OutlineRepository(db)
        val writer = RoomAgentWriteBusinessWriter(db, books, outlines)
        val writeService = AgentWriteExecutionService(
            operations = writes,
            writer = writer,
            policyProvider = { AgentWritePermissionPolicy.ASK_EVERY_TIME },
            transactionRunner = RoomAgentWriteTransactionRunner(db)
        )
        scheduler = RecordingScheduler()
        controller = AgentTaskController(
            tasks = tasks,
            nodes = AgentExecutionNodeRepository(db),
            sessions = sessions,
            events = AgentEventRepository(db),
            writes = writes,
            writeService = writeService,
            leases = AgentExecutionLeaseRepository(db),
            scheduler = scheduler
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createTask(status: String): Long {
        val sessionId = sessions.createSession(bookId = null, modelId = null)
        val taskId = tasks.createTask(sessionId, "生命周期测试")
        tasks.updateStatus(taskId, status)
        return taskId
    }

    @Test
    fun 没有执行者的RUNNING任务可以立即暂停并恢复() = runBlocking {
        val taskId = createTask(AgentTaskStatus.RUNNING)
        assertTrue(controller.pause(taskId))
        assertEquals(AgentTaskStatus.PAUSED, tasks.getTask(taskId)!!.status)
        assertTrue(controller.resume(taskId))
        assertEquals(AgentTaskStatus.RUNNING, tasks.getTask(taskId)!!.status)
        assertTrue(scheduler.enqueued.contains(taskId))
    }

    @Test
    fun 取消等待确认任务会取消未执行写入() = runBlocking {
        val taskId = createTask(AgentTaskStatus.RUNNING)
        val operation = writes.propose(
            AgentWriteDraft(
                taskId = taskId,
                sessionId = 1L,
                toolName = "update_chapter_synopsis",
                operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
                targetType = AgentWriteTargetType.CHAPTER,
                targetId = 1L,
                bookId = 1L,
                riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
                idempotencyKey = "controller-cancel-1"
            ),
            policy = AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)

        assertTrue(controller.cancel(taskId))
        assertEquals(AgentTaskStatus.CANCELLED, tasks.getTask(taskId)!!.status)
        assertEquals(
            AgentWriteOperationStatus.CANCELLED,
            writes.get(operation.id)!!.executionStatus
        )
    }

    @Test
    fun 等待确认且仍有未解决写入时不继续执行() = runBlocking {
        val taskId = createTask(AgentTaskStatus.RUNNING)
        writes.propose(
            AgentWriteDraft(
                taskId = taskId,
                sessionId = 1L,
                toolName = "update_chapter_synopsis",
                operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
                targetType = AgentWriteTargetType.CHAPTER,
                targetId = 1L,
                bookId = 1L,
                riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
                idempotencyKey = "controller-wait-1"
            ),
            policy = AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
        assertTrue(!controller.continueAfterWriteDecision(taskId))
    }
}
