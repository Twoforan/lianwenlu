package com.lianwenqi.app.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentWriteLimits
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.agent.data.entity.AgentWriteTargetType
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F6 写入操作状态机、幂等键、上限、取消与事件 */
@RunWith(AndroidJUnit4::class)
class AgentWriteOperationRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var tasks: AgentTaskRepository
    private lateinit var operations: AgentWriteOperationRepository
    private var sessionId = 0L
    private var taskId = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = AgentSessionRepository(db)
        tasks = AgentTaskRepository(db)
        operations = AgentWriteOperationRepository(db)
        sessionId = sessions.createSession(bookId = 1L)
        taskId = tasks.createTask(sessionId, "修改章节")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun draft(
        key: String,
        risk: String = AgentWriteRiskLevel.NORMAL_WRITE,
        targetId: Long = 1L
    ) = AgentWriteDraft(
        taskId = taskId,
        sessionId = sessionId,
        toolName = "update_chapter_synopsis",
        operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
        targetType = AgentWriteTargetType.CHAPTER,
        targetId = targetId,
        bookId = 1L,
        riskLevel = risk,
        proposedContent = "新梗概",
        operationSummary = "修改梗概",
        idempotencyKey = key
    )

    @Test
    fun 默认策略创建等待确认提议且不重复写入() = runBlocking {
        val first = operations.propose(
            draft("key-1"),
            AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        assertEquals(AgentWriteOperationStatus.WAITING_CONFIRMATION, first.executionStatus)
        assertEquals(AgentWritePermissionStatus.PENDING, first.permissionStatus)

        val second = operations.propose(
            draft("key-1"),
            AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        assertEquals(first.id, second.id)
        assertEquals(1, operations.ofTask(taskId).size)

        val events = db.agentTaskDao().eventsOfTask(taskId).map { it.eventType }
        assertTrue(events.contains(AgentEventType.WRITE_OPERATION_PROPOSED))
        assertTrue(events.contains(AgentEventType.WRITE_OPERATION_WAITING_CONFIRMATION))
    }

    @Test
    fun 一般写入自动授权只对一般写入生效() = runBlocking {
        val normal = operations.propose(
            draft("key-normal"),
            AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
        )
        assertEquals(AgentWriteOperationStatus.APPROVED, normal.executionStatus)
        assertEquals(AgentWritePermissionStatus.AUTO_APPROVED, normal.permissionStatus)

        val high = operations.propose(
            draft("key-high", risk = AgentWriteRiskLevel.HIGH_RISK_WRITE),
            AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
        )
        assertEquals(AgentWriteOperationStatus.WAITING_CONFIRMATION, high.executionStatus)
    }

    @Test
    fun 批准拒绝与幂等状态迁移() = runBlocking {
        val operation = operations.propose(
            draft("key-2"),
            AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        val approved = operations.approve(operation.id)
        assertEquals(AgentWriteOperationStatus.APPROVED, approved.executionStatus)
        assertEquals(AgentWritePermissionStatus.APPROVED, approved.permissionStatus)

        // 已批准后再次批准幂等返回
        assertEquals(
            AgentWriteOperationStatus.APPROVED,
            operations.approve(operation.id).executionStatus
        )

        val rejected = AgentWriteOperationRepository(db).also { fresh ->
            val waiting = fresh.propose(
                draft("key-3"),
                AgentWritePermissionPolicy.ASK_EVERY_TIME
            )
            assertEquals(
                AgentWriteOperationStatus.REJECTED,
                fresh.reject(waiting.id).executionStatus
            )
            // 已拒绝后批准不会覆盖拒绝
            assertEquals(
                AgentWriteOperationStatus.REJECTED,
                fresh.approve(waiting.id).executionStatus
            )
        }
        assertEquals(2, operations.ofTask(taskId).size)
        // 已批准操作不会因拒绝另一条而被改写
        assertEquals(
            AgentWriteOperationStatus.APPROVED,
            operations.get(operation.id)!!.executionStatus
        )
    }

    @Test
    fun 并发开始执行只有一个调用真正迁移到EXECUTING() = runBlocking {
        val operation = operations.propose(
            draft("key-4"),
            AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        operations.approve(operation.id)
        val results = listOf(
            async { operations.beginExecution(operation.id) },
            async { operations.beginExecution(operation.id) }
        ).awaitAll()
        assertEquals(1, results.count { it.started })
        assertTrue(
            results.any { it.operation.executionStatus == AgentWriteOperationStatus.EXECUTING }
        )
    }

    @Test
    fun 成功与撤销状态迁移并写事件() = runBlocking {
        val operation = operations.propose(
            draft("key-5"),
            AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        operations.approve(operation.id)
        assertTrue(operations.beginExecution(operation.id).started)
        val succeeded = operations.markSucceeded(operation.id, """{"summary":"新梗概"}""")
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, succeeded.executionStatus)
        assertTrue(succeeded.executedAt != null)

        val rolledBack = operations.markRolledBack(operation.id)
        assertEquals(AgentWriteOperationStatus.ROLLED_BACK, rolledBack.executionStatus)
        assertTrue(rolledBack.rolledBackAt != null)

        val events = db.agentTaskDao().eventsOfTask(taskId).map { it.eventType }
        assertTrue(events.contains(AgentEventType.WRITE_OPERATION_STARTED))
        assertTrue(events.contains(AgentEventType.WRITE_OPERATION_SUCCEEDED))
        assertTrue(events.contains(AgentEventType.WRITE_OPERATION_ROLLED_BACK))
    }

    @Test
    fun 单任务未结束写入上限为20() = runBlocking {
        (1..AgentWriteLimits.MAX_UNRESOLVED_PER_TASK).forEach {
            operations.propose(
                draft("limit-$it"),
                AgentWritePermissionPolicy.ASK_EVERY_TIME
            )
        }
        try {
            operations.propose(
                draft("limit-over"),
                AgentWritePermissionPolicy.ASK_EVERY_TIME
            )
            fail("超过未结束写入上限时应拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("上限"))
        }
    }

    @Test
    fun 取消任务使未执行提议失效且不触碰EXECUTING() = runBlocking {
        val waiting = operations.propose(
            draft("cancel-waiting"),
            AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        val executing = operations.propose(
            draft("cancel-executing"),
            AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
        )
        operations.beginExecution(executing.id)

        operations.cancelPending(taskId, "用户取消")
        assertEquals(
            AgentWriteOperationStatus.CANCELLED,
            operations.get(waiting.id)!!.executionStatus
        )
        assertEquals(
            AgentWriteOperationStatus.EXECUTING,
            operations.get(executing.id)!!.executionStatus
        )
    }

    @Test
    fun 中断遗留EXECUTING在下次启动前标记失败() = runBlocking {
        val operation = operations.propose(
            draft("stale"),
            AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
        )
        operations.beginExecution(operation.id)
        operations.failStaleExecuting(taskId)
        val failed = operations.get(operation.id)!!
        assertEquals(AgentWriteOperationStatus.FAILED, failed.executionStatus)
        assertTrue(failed.errorMessage.contains("上次执行中断"))
        assertFalse(operations.hasUnresolved(taskId))
    }

    @Test
    fun 工具执行记录可回填到写入操作() = runBlocking {
        val operation = operations.propose(
            draft("attach"),
            AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        operations.attachToolExecution(operation.id, 12345L)
        assertEquals(12345L, operations.get(operation.id)!!.toolExecutionId)
    }
}
