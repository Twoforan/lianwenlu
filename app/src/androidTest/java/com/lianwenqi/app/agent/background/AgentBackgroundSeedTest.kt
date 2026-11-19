package com.lianwenqi.app.agent.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lianwenqi.app.agent.execution.AgentProcessDeathFixture
import com.lianwenqi.app.appContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F7 应用后台执行验证第 1 步：
 * 创建一个“所有步骤已完成、只差任务收尾”的真实任务并立即入队。
 * 测试结束后将应用切到后台，观察 Worker 是否在后台完成任务。
 */
@RunWith(AndroidJUnit4::class)
class AgentBackgroundSeedTest {

    @Test
    fun 创建任务并加入后台调度() = runBlocking {
        assumeTrue(
            "仅在手动后台执行验证时运行",
            InstrumentationRegistry.getArguments()
                .getString("f7BackgroundVerify") == "true"
        )
        val context: Context = ApplicationProvider.getApplicationContext()
        val taskId = AgentProcessDeathFixture.seed(context)
        assertTrue(
            context.appContainer.agentTaskController.requestExecution(
                taskId,
                "BACKGROUND_VERIFY"
            )
        )
    }
}
