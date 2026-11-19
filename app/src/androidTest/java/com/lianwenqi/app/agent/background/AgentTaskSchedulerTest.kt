package com.lianwenqi.app.agent.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F7：同一任务只有一个唯一 Work，重复入队不会产生第二个 Worker */
@RunWith(AndroidJUnit4::class)
class AgentTaskSchedulerTest {

    private lateinit var context: Context
    private lateinit var scheduler: WorkManagerAgentTaskScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Application 启动时已经初始化 WorkManager；这里直接验证真实唯一调度行为。
        scheduler = WorkManagerAgentTaskScheduler(context)
    }

    @Test
    fun 重复入队同一任务只保留一个Work() = runBlocking {
        scheduler.enqueue(101L, "FIRST")
        scheduler.enqueue(101L, "SECOND")
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerAgentTaskScheduler.workName(101L))
            .get()
        assertEquals(1, infos.size)
    }

    @Test
    fun 不同任务使用不同唯一Work() = runBlocking {
        scheduler.enqueue(201L, "A")
        scheduler.enqueue(202L, "B")
        assertEquals(
            1,
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WorkManagerAgentTaskScheduler.workName(201L))
                .get()
                .size
        )
        assertEquals(
            1,
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WorkManagerAgentTaskScheduler.workName(202L))
                .get()
                .size
        )
    }

    @Test
    fun 周期恢复任务重复注册只保留一个() = runBlocking {
        scheduler.ensurePeriodicRecovery()
        scheduler.ensurePeriodicRecovery()
        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(WorkManagerAgentTaskScheduler.PERIODIC_RECOVERY_WORK)
            .get()
        assertEquals(1, infos.size)
    }
}
