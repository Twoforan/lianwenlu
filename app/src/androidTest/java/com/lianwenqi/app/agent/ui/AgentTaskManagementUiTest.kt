package com.lianwenqi.app.agent.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentModelRequest
import com.lianwenqi.app.agent.data.entity.AgentModelRequestStatus
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentRecoveryLog
import com.lianwenqi.app.agent.data.entity.AgentRecoveryResult
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.agent.data.entity.AgentWriteTargetType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F7 任务管理 UI：恢复状态、恢复日志、待确认写入与暂停 / 恢复入口 */
@RunWith(AndroidJUnit4::class)
class AgentTaskManagementUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun task(
        status: String = AgentTaskStatus.RUNNING,
        attempt: Int = 2
    ) = AgentTask(
        id = 7L,
        sessionId = 3L,
        title = "恢复任务",
        userGoal = "检查第三章与第四章的设定冲突",
        status = status,
        executionAttempt = attempt,
        executionOwner = "owner-1",
        executionLeaseUntil = System.currentTimeMillis() + 60_000,
        lastHeartbeatAt = System.currentTimeMillis(),
        lastExecutionAt = System.currentTimeMillis() - 10_000,
        recoveryCount = 1,
        lastRecoveryAt = System.currentTimeMillis() - 5_000,
        currentNodeId = 2L
    )

    private val session = AgentSession(
        id = 3L,
        title = "第三章检查",
        bookId = 1L,
        createdAt = 1L,
        updatedAt = 2L,
        lastMessageAt = 2L
    )

    private val nodes = listOf(
        AgentExecutionNode(
            id = 1L,
            taskId = 7L,
            nodeKey = "run:step_001",
            nodeType = "SEARCH_CHAPTER",
            title = "读取第三章",
            status = AgentNodeStatus.COMPLETED,
            sequence = 0
        ),
        AgentExecutionNode(
            id = 2L,
            taskId = 7L,
            nodeKey = "run:step_002",
            nodeType = "ANALYZE_RESULT",
            title = "分析冲突",
            status = AgentNodeStatus.RUNNING,
            sequence = 1
        )
    )

    private val writes = listOf(
        AgentWriteOperation(
            id = 11L,
            taskId = 7L,
            sessionId = 3L,
            toolName = "update_chapter_synopsis",
            operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
            targetType = AgentWriteTargetType.CHAPTER,
            targetId = 1L,
            bookId = 1L,
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            executionStatus = AgentWriteOperationStatus.WAITING_CONFIRMATION,
            idempotencyKey = "ui-write-1",
            operationSummary = "修改梗概"
        )
    )

    private val recoveryLogs = listOf(
        AgentRecoveryLog(
            taskId = 7L,
            attempt = 2,
            reason = "STARTUP",
            result = AgentRecoveryResult.SUCCEEDED,
            cursorBefore = 1,
            cursorAfter = 2,
            duplicateRequestPossible = true,
            message = "恢复完成"
        )
    )

    private val requests = listOf(
        AgentModelRequest(
            taskId = 7L,
            requestKey = "run:2:0:1",
            status = AgentModelRequestStatus.UNKNOWN,
            round = 0,
            attempt = 1
        )
    )

    @Test
    fun 详情展示恢复状态日志与待确认写入并提供暂停() {
        var paused = false
        composeRule.setContent {
            AgentTaskDetailDialog(
                task = task(),
                session = session,
                bookTitle = "测试书",
                nodes = nodes,
                writes = writes,
                recoveryLogs = recoveryLogs,
                modelRequests = requests,
                onPause = { paused = true },
                onResume = {},
                onCancel = {},
                onDelete = {},
                onOpenInChat = {},
                onDismiss = {}
            )
        }

        composeRule.onNodeWithText("任务详情").assertIsDisplayed()
        composeRule.onNodeWithText("正在恢复").assertIsDisplayed()
        composeRule.onNodeWithText("检查第三章与第四章的设定冲突").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
        composeRule.onNodeWithText("待确认写入").assertIsDisplayed()
        composeRule.onNodeWithText("1 个").assertIsDisplayed()
        composeRule.onNodeWithText("恢复日志").assertExists()
        composeRule.onNodeWithText("可能重复请求").assertExists()
        composeRule.onNodeWithText(
            "存在状态未知的模型请求：恢复后可能重新发送并产生额外费用。"
        ).assertExists()
        composeRule.onNodeWithText("暂停").performClick()
        assertTrue(paused)
    }

    @Test
    fun 暂停任务提供恢复入口() {
        var resumed = false
        composeRule.setContent {
            AgentTaskDetailDialog(
                task = task(status = AgentTaskStatus.PAUSED, attempt = 1),
                session = session,
                bookTitle = "测试书",
                nodes = nodes,
                writes = emptyList(),
                recoveryLogs = emptyList(),
                modelRequests = emptyList(),
                onPause = {},
                onResume = { resumed = true },
                onCancel = {},
                onDelete = {},
                onOpenInChat = {},
                onDismiss = {}
            )
        }
        composeRule.onNodeWithText("已暂停").assertIsDisplayed()
        composeRule.onNodeWithText("恢复").performClick()
        assertTrue(resumed)
    }
}
