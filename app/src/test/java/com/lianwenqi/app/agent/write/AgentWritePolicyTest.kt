package com.lianwenqi.app.agent.write

import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F6 权限策略矩阵：只有一般写入 + AUTO_ALLOW_NORMAL 可以自动执行 */
class AgentWritePolicyTest {

    @Test
    fun 默认每次询问不自动执行任何写入() {
        listOf(
            AgentWriteRiskLevel.NORMAL_WRITE,
            AgentWriteRiskLevel.HIGH_RISK_WRITE,
            AgentWriteRiskLevel.DESTRUCTIVE
        ).forEach { risk ->
            assertFalse(
                AgentWritePermissionPolicy.canAutoExecute(
                    risk,
                    AgentWritePermissionPolicy.ASK_EVERY_TIME
                )
            )
        }
    }

    @Test
    fun 一般写入自动授权只影响一般写入() {
        assertTrue(
            AgentWritePermissionPolicy.canAutoExecute(
                AgentWriteRiskLevel.NORMAL_WRITE,
                AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
            )
        )
        assertFalse(
            AgentWritePermissionPolicy.canAutoExecute(
                AgentWriteRiskLevel.HIGH_RISK_WRITE,
                AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
            )
        )
        assertFalse(
            AgentWritePermissionPolicy.canAutoExecute(
                AgentWriteRiskLevel.DESTRUCTIVE,
                AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
            )
        )
    }

    @Test
    fun 批量确认策略不自动执行高风险写入() {
        assertFalse(
            AgentWritePermissionPolicy.canAutoExecute(
                AgentWriteRiskLevel.HIGH_RISK_WRITE,
                AgentWritePermissionPolicy.BATCH_CONFIRM
            )
        )
        assertFalse(
            AgentWritePermissionPolicy.canAutoExecute(
                AgentWriteRiskLevel.NORMAL_WRITE,
                AgentWritePermissionPolicy.BATCH_CONFIRM
            )
        )
    }

    @Test
    fun 策略值校验与标签固定() {
        AgentWritePermissionPolicy.all.forEach {
            assertTrue(AgentWritePermissionPolicy.isValid(it))
            assertTrue(AgentWritePermissionPolicy.label(it).isNotBlank())
        }
        assertFalse(AgentWritePermissionPolicy.isValid("UNKNOWN"))
        assertTrue(AgentWriteRiskLevel.requiresExplicitConfirmation(
            AgentWriteRiskLevel.HIGH_RISK_WRITE
        ))
        assertFalse(AgentWriteRiskLevel.requiresExplicitConfirmation(
            AgentWriteRiskLevel.NORMAL_WRITE
        ))
    }
}
