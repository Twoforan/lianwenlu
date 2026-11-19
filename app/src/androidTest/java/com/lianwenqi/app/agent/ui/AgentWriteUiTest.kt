package com.lianwenqi.app.agent.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.agent.data.entity.AgentWriteTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F6 写入预览 UI：待确认、风险区分、批量确认、日志、撤销与差异 */
@RunWith(AndroidJUnit4::class)
class AgentWriteUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun operation(
        id: Long,
        type: String,
        risk: String,
        status: String = AgentWriteOperationStatus.WAITING_CONFIRMATION,
        before: String = """{"summary":"旧梗概"}""",
        proposed: String = "新梗概",
        reversible: Boolean = true
    ) = AgentWriteOperation(
        id = id,
        taskId = 1L,
        sessionId = 1L,
        toolName = "test_tool",
        operationType = type,
        targetType = AgentWriteTargetType.CHAPTER,
        targetId = 1L,
        bookId = 1L,
        targetTitleSnapshot = "第一章",
        bookTitleSnapshot = "测试书",
        riskLevel = risk,
        beforeSnapshot = before,
        proposedContent = proposed,
        operationSummary = "修改第一章资料",
        executionStatus = status,
        idempotencyKey = "key-$id",
        reversible = reversible
    )

    @Test
    fun 待确认列表区分风险并提供确认拒绝与批量入口() {
        var approved: Long? = null
        var rejected: Long? = null
        var rolledBack: Long? = null
        var batch = false
        val synopsis = operation(
            1L,
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
            AgentWriteRiskLevel.NORMAL_WRITE
        )
        val outline = operation(
            2L,
            AgentWriteOperationType.CREATE_OUTLINE_NODE,
            AgentWriteRiskLevel.NORMAL_WRITE
        )
        val content = operation(
            3L,
            AgentWriteOperationType.UPDATE_CHAPTER_CONTENT,
            AgentWriteRiskLevel.HIGH_RISK_WRITE
        )
        val succeeded = operation(
            4L,
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
            AgentWriteRiskLevel.NORMAL_WRITE,
            status = AgentWriteOperationStatus.SUCCEEDED
        )
        composeRule.setContent {
            AgentWriteOperationsCard(
                operations = listOf(synopsis, outline, content, succeeded),
                notice = null,
                policy = AgentWritePermissionPolicy.BATCH_CONFIRM,
                onApprove = { approved = it },
                onReject = { rejected = it },
                onApproveAllNormal = { batch = true },
                onRollback = { rolledBack = it },
                onOpen = {},
                onOpenPolicy = {},
                onDismissNotice = {}
            )
        }

        composeRule.onAllNodesWithText("一般写入")[0].assertIsDisplayed()
        composeRule.onNodeWithText("高风险").assertIsDisplayed()
        composeRule.onNodeWithText("确认覆盖正文").assertIsDisplayed()
        composeRule.onNodeWithText("批量确认一般写入（2）").assertIsDisplayed()
        composeRule.onNodeWithText("撤销").assertIsDisplayed()
        composeRule.onNodeWithText("撤销").performClick()
        assertEquals(4L, rolledBack)

        composeRule.onNodeWithText("确认修改梗概").performClick()
        assertEquals(1L, approved)
        composeRule.onAllNodesWithText("拒绝")[0].performClick()
        assertEquals(1L, rejected)
        composeRule.onNodeWithText("批量确认一般写入（2）").performClick()
        assertTrue(batch)
    }

    @Test
    fun 预览展示修改前后差异并支持确认() {
        var approved = false
        val op = operation(
            1L,
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
            AgentWriteRiskLevel.NORMAL_WRITE
        )
        composeRule.setContent {
            AgentWritePreviewDialog(
                operation = op,
                onApprove = { approved = true },
                onReject = {},
                onRollback = {},
                onDismiss = {}
            )
        }
        composeRule.onNodeWithText("修改前后差异").assertIsDisplayed()
        composeRule.onNodeWithText("- 旧梗概").assertIsDisplayed()
        composeRule.onNodeWithText("+ 新梗概").assertIsDisplayed()
        composeRule.onNodeWithText("确认修改梗概").performClick()
        assertTrue(approved)
    }

    @Test
    fun 权限策略弹窗展示三种策略并可切换() {
        var selected: String? = null
        composeRule.setContent {
            AgentWritePolicyDialog(
                current = AgentWritePermissionPolicy.ASK_EVERY_TIME,
                onSelect = { selected = it },
                onDismiss = {}
            )
        }
        composeRule.onNodeWithText("每次操作都询问").assertIsDisplayed()
        composeRule.onNodeWithText("一般写入批量确认").assertIsDisplayed()
        composeRule.onNodeWithText("一般写入自动执行").performClick()
        assertEquals(AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL, selected)
    }

    @Test
    fun 操作日志展示失败与冲突原因() {
        val failed = operation(
            1L,
            AgentWriteOperationType.UPDATE_CHAPTER_CONTENT,
            AgentWriteRiskLevel.HIGH_RISK_WRITE,
            status = AgentWriteOperationStatus.FAILED
        ).copy(errorMessage = "目标章节已删除")
        val conflict = operation(
            2L,
            AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
            AgentWriteRiskLevel.NORMAL_WRITE,
            status = AgentWriteOperationStatus.CONFLICT
        ).copy(errorMessage = "章节梗概已在预览后发生变化")
        composeRule.setContent {
            AgentWriteOperationsCard(
                operations = listOf(failed, conflict),
                notice = "写入失败",
                policy = AgentWritePermissionPolicy.ASK_EVERY_TIME,
                onApprove = {},
                onReject = {},
                onApproveAllNormal = {},
                onRollback = {},
                onOpen = {},
                onOpenPolicy = {},
                onDismissNotice = {}
            )
        }
        composeRule.onNodeWithText("目标章节已删除").assertIsDisplayed()
        composeRule.onNodeWithText("章节梗概已在预览后发生变化").assertIsDisplayed()
        composeRule.onAllNodesWithText("写入失败")[0].assertIsDisplayed()
    }
}
