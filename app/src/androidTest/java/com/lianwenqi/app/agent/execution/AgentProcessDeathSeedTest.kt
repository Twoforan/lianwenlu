package com.lianwenqi.app.agent.execution

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F7 真实进程死亡验证第 1 步：在真实数据库中制造可恢复的中断任务 */
@RunWith(AndroidJUnit4::class)
class AgentProcessDeathSeedTest {

    @Test
    fun 写入进程死亡前的RUNNING任务() = runBlocking {
        assumeTrue(
            "仅在手动进程死亡验证时运行",
            InstrumentationRegistry.getArguments()
                .getString("f7ProcessDeathVerify") == "true"
        )
        val context: Context = ApplicationProvider.getApplicationContext()
        val taskId = AgentProcessDeathFixture.seed(context)
        assertTrue(taskId > 0)
    }
}
