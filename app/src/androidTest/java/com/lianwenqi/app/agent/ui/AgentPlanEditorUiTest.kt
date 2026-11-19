package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanStep
import com.lianwenqi.app.agent.model.AgentPlanValidationResult
import com.lianwenqi.app.agent.model.AgentRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 计划编辑：保持计划卡样式，按模块编辑，不直接暴露 JSON */
@RunWith(AndroidJUnit4::class)
class AgentPlanEditorUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val plan = AgentPlan(
        taskTitle = "检查第三卷人物关系",
        goalSummary = "核对人物关系是否冲突",
        thinkingSummary = "先看大纲，再核对正文",
        steps = listOf(
            AgentPlanStep(
                stepId = "step_001",
                title = "读取大纲",
                description = "读取第三卷大纲",
                capabilityKey = "READ_OUTLINE",
                expectedOutput = "大纲摘要",
                riskLevel = AgentRiskLevel.LOW
            )
        )
    )

    private val task = AgentTask(
        id = 1L,
        sessionId = 1L,
        title = plan.taskTitle,
        userGoal = "检查第三卷人物关系",
        status = "WAITING_CONFIRMATION",
        planJson = "{}"
    )

    @Test
    fun 计划编辑展示结构化字段且不显示JSON() {
        var latest: AgentPlan? = null
        composeRule.setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AgentPlanCard(
                        planView = AgentPlanView(
                            plan = plan,
                            validation = AgentPlanValidationResult(isValid = true, stepCount = 1),
                            confirmed = false
                        ),
                        task = task,
                        planning = false,
                        executing = false,
                        planEditing = true,
                        editingPlan = plan,
                        onPlanChange = { latest = it },
                        onRegenerate = {},
                        onConfirm = {},
                        onStartExecution = {},
                        onPauseTask = {},
                        onResumeTask = {},
                        onCancelTask = {},
                        onDeleteTask = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("任务标题").assertIsDisplayed()
        composeRule.onNodeWithText("目标概括").assertIsDisplayed()
        composeRule.onNodeWithText("步骤标题").assertIsDisplayed()
        composeRule.onNodeWithText("步骤说明").assertIsDisplayed()
        composeRule.onNodeWithText("预期产出").assertIsDisplayed()
        composeRule.onNodeWithText("计划 JSON").assertDoesNotExist()

        composeRule.onNodeWithText("+ 添加步骤").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(2, latest?.steps?.size)
        }
    }

    @Test
    fun 含不可执行步骤时禁用开始执行并给出提示() {
        val blockedPlan = plan.copy(
            steps = plan.steps + AgentPlanStep(
                stepId = "step_002",
                title = "生成第一章正文",
                description = "",
                capabilityKey = "REWRITE_CONTENT",
                expectedOutput = "",
                riskLevel = AgentRiskLevel.MEDIUM
            )
        )
        composeRule.setContent {
            MaterialTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    AgentPlanCard(
                        planView = AgentPlanView(
                            plan = blockedPlan,
                            validation = AgentPlanValidationResult(isValid = true, stepCount = 2),
                            confirmed = true
                        ),
                        task = task.copy(status = "WAITING_CONFIRMATION"),
                        planning = false,
                        executing = false,
                        onRegenerate = {},
                        onConfirm = {},
                        onStartExecution = {},
                        onPauseTask = {},
                        onResumeTask = {},
                        onCancelTask = {},
                        onDeleteTask = {}
                    )
                }
            }
        }

        composeRule.onNodeWithText("存在当前阶段不可执行的步骤：生成第一章正文")
            .assertIsDisplayed()
        composeRule.onNodeWithText("开始执行")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun 澄清弹窗内容过长时底部按钮仍可见() {
        composeRule.setContent {
            MaterialTheme {
                AgentClarificationSheet(
                    clarification = AgentClarificationView(
                        id = 1L,
                        question = "这是一个很长的补充信息问题。".repeat(30),
                        options = listOf(
                            "短篇，约5章，先出设定与分章大纲",
                            "中篇，约15章，先出设定与分章大纲",
                            "长篇，30章以上，先出设定与分章大纲",
                            "先只做世界观与人物设定，章节数稍后再定"
                        )
                    ),
                    onDismiss = {},
                    onSubmit = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("提交并继续").assertIsDisplayed()
        composeRule.onNodeWithText("稍后再说").assertIsDisplayed()
    }
}
