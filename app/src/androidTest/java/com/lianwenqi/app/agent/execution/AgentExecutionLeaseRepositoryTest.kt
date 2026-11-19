package com.lianwenqi.app.agent.execution

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F7：数据库级执行租约；并发只有一个成功，进程死亡后可重新取得 */
@RunWith(AndroidJUnit4::class)
class AgentExecutionLeaseRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var tasks: AgentTaskRepository
    private lateinit var sessions: AgentSessionRepository
    private lateinit var leases: AgentExecutionLeaseRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tasks = AgentTaskRepository(db)
        sessions = AgentSessionRepository(db)
        leases = AgentExecutionLeaseRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createRunningTask(): Long {
        val sessionId = sessions.createSession(bookId = null, modelId = null)
        val taskId = tasks.createTask(sessionId = sessionId, userGoal = "租约测试")
        tasks.updateStatus(taskId, AgentTaskStatus.RUNNING)
        return taskId
    }

    @Test
    fun 并发取得租约只有一个成功() = runBlocking {
        val taskId = createRunningTask()
        val results = List(8) { index ->
            async { leases.acquire(taskId, "owner-$index") }
        }.awaitAll()
        assertEquals(1, results.count { it != null })
        assertNotNull(tasks.getTask(taskId)!!.executionOwner)
        assertEquals(1, tasks.getTask(taskId)!!.executionAttempt)
    }

    @Test
    fun 同一执行者可以续租其他执行者被拒绝() = runBlocking {
        val taskId = createRunningTask()
        assertNotNull(leases.acquire(taskId, "owner-a"))
        assertNull(leases.acquire(taskId, "owner-b"))
        assertTrue(leases.renew(taskId, "owner-a"))
        assertNull(leases.acquire(taskId, "owner-b"))
    }

    @Test
    fun 释放后其他执行者可以取得租约() = runBlocking {
        val taskId = createRunningTask()
        assertNotNull(leases.acquire(taskId, "owner-a"))
        leases.release(taskId, "owner-a")
        assertNotNull(leases.acquire(taskId, "owner-b"))
        assertEquals("owner-b", tasks.getTask(taskId)!!.executionOwner)
        assertEquals(2, tasks.getTask(taskId)!!.executionAttempt)
    }

    @Test
    fun 租约过期后可以重新取得() = runBlocking {
        val taskId = createRunningTask()
        assertNotNull(leases.acquire(taskId, "owner-a"))
        val task = tasks.getTask(taskId)!!
        db.agentTaskDao().updateTask(
            task.copy(
                executionLeaseUntil = 1L,
                lastHeartbeatAt = 1L
            )
        )
        assertNotNull(leases.acquire(taskId, "owner-b"))
        assertEquals("owner-b", tasks.getTask(taskId)!!.executionOwner)
    }

    @Test
    fun 上一进程心跳早于进程启动时间时立即视为中断() = runBlocking {
        val taskId = createRunningTask()
        assertNotNull(leases.acquire(taskId, "previous-process"))
        val processStartedAt = System.currentTimeMillis() + 10
        val interrupted = leases.interruptedRunningTasks(processStartedAt)
        assertTrue(interrupted.any { it.id == taskId })
    }

    @Test
    fun 等待确认的任务不会被恢复扫描误判为执行超时() = runBlocking {
        val taskId = createRunningTask()
        assertNotNull(leases.acquire(taskId, "owner-a"))
        tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
        val interrupted = leases.interruptedRunningTasks(
            processStartedAt = System.currentTimeMillis() + 10
        )
        assertTrue(interrupted.none { it.id == taskId })
    }
}
