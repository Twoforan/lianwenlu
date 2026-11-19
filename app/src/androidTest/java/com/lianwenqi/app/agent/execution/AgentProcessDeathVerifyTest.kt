package com.lianwenqi.app.agent.execution

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.data.entity.AgentRecoveryResult
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.appContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F7 真实进程死亡验证第 2 步：应用重启后，启动扫描 + WorkManager 自动完成任务。
 * 该测试不依赖模型供应商：任务所有步骤都已完成，只剩任务收尾。
 */
@RunWith(AndroidJUnit4::class)
class AgentProcessDeathVerifyTest {

    @Test
    fun 应用重启后恢复中断任务且不重复执行步骤() = runBlocking {
        assumeTrue(
            "仅在手动进程死亡验证时运行",
            InstrumentationRegistry.getArguments()
                .getString("f7ProcessDeathVerify") == "true"
        )
        val context: Context = ApplicationProvider.getApplicationContext()
        val taskId = AgentProcessDeathFixture.taskId(context)
        assertNotNull("未找到进程死亡夹具，请先运行 SeedTest", taskId)
        val container = context.appContainer

        val deadline = System.currentTimeMillis() + 60_000
        var task = container.agentTaskRepository.getTask(taskId!!)
        while (task?.status != AgentTaskStatus.COMPLETED &&
            System.currentTimeMillis() < deadline
        ) {
            delay(500)
            task = container.agentTaskRepository.getTask(taskId)
        }

        assertNotNull(task)
        assertEquals(AgentTaskStatus.COMPLETED, task!!.status)
        assertEquals("", task.executionOwner)
        assertTrue(task.executionAttempt >= 1)
        assertTrue(task.executionCursor >= 1)

        val recoveryLogs = container.agentRecoveryLogRepository.logsOfTask(taskId)
        assertTrue(
            "应存在恢复日志",
            recoveryLogs.isNotEmpty()
        )
        assertTrue(
            "应存在成功恢复记录",
            recoveryLogs.any { it.result == AgentRecoveryResult.SUCCEEDED }
        )

        val eventTypes = container.agentEventRepository.eventsOfTask(taskId).map { it.eventType }
        assertTrue(eventTypes.contains("TASK_RECOVERY_STARTED"))
        assertTrue(eventTypes.contains("TASK_RECOVERY_COMPLETED"))
        assertTrue(eventTypes.contains("TASK_EXECUTION_LEASE_ACQUIRED"))
        assertTrue(eventTypes.contains("TASK_EXECUTION_LEASE_EXPIRED"))

        val nodes = container.agentExecutionNodeRepository.nodesOfTask(taskId)
        assertEquals(
            "任务收尾节点只能有一个，不能重复创建",
            1,
            nodes.count { it.nodeType == AgentNodeType.TASK_COMPLETE }
        )
        assertEquals(
            1,
            nodes.count { it.nodeKey.endsWith("step_001") }
        )
    }
}
