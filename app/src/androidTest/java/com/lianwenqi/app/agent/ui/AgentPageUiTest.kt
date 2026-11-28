package com.lianwenqi.app.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPageUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowHeaderKeepsEveryActionReachable() {
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    AgentPageHeader(
                        bookTitle = "很长的书籍名称也应保留其他入口", chapterCount = 3,
                        outlineCount = 4, hasBook = true,
                        onSettings = { actions += "settings" }, onNewSession = { actions += "new" },
                        onHistory = { actions += "history" }, onExitAgent = { actions += "exit" },
                        onBook = { actions += "book" }, onChapters = { actions += "chapters" },
                        onOutline = { actions += "outline" },
                        onTaskHistory = { actions += "tasks" }
                    )
                }
            }
        }
        listOf("Agent 设置", "新建 Agent 会话", "历史会话", "返回 AI 聊天").forEach {
            compose.onNodeWithContentDescription(it).assertIsDisplayed().performClick()
        }
        listOf("很长的书籍名称也应保留其他入口", "章节", "大纲", "历史任务").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().performClick()
        }
        compose.onNodeWithText("计划书库").assertDoesNotExist()
        compose.onNodeWithText("计划模式").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("settings", "new", "history", "exit", "book", "chapters", "outline", "tasks"), actions)
        }
    }

    @Test
    fun contextActionsWaitForBookSelection() {
        var booksOpened = 0
        compose.setContent {
            MaterialTheme {
                AgentPageHeader(
                    bookTitle = null, chapterCount = 0, outlineCount = 0, hasBook = false,
                    onSettings = {}, onNewSession = {}, onHistory = {}, onExitAgent = {},
                    onBook = { booksOpened++ }, onChapters = {}, onOutline = {},
                    onTaskHistory = {}
                )
            }
        }
        listOf("章节", "大纲", "历史任务").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
        }
        compose.onNodeWithText("选择书籍").performClick()
        compose.runOnIdle { assertEquals(1, booksOpened) }
    }

    @Test
    fun emptySessionTaskPanelStartsCollapsedAndResetsForAnotherSession() {
        val actions = mutableListOf<String>()
        var sessionId by mutableStateOf(1L)
        var title by mutableStateOf("任务标题")
        compose.setContent {
            MaterialTheme {
                // 没有消息也必须能展开；浮层不依赖消息列表。
                Box(Modifier.width(320.dp).height(360.dp)) {
                    AgentTaskPanel(
                        sessionId = sessionId,
                        title = title,
                        onRename = { actions += "rename"; title = "已修改的任务标题" },
                        onOpenReferences = { actions += "references" }
                    )
                }
            }
        }
        compose.onNodeWithText("已选资料").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开任务标题").assertIsDisplayed().performClick()
        compose.onNodeWithText("任务标题").assertIsDisplayed()
        compose.onNodeWithContentDescription("修改任务标题").performClick()
        compose.onNodeWithText("已修改的任务标题").assertIsDisplayed()
        compose.onNodeWithText("已选资料").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("收起任务标题").performClick()
        compose.onNodeWithText("已选资料").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开任务标题").performClick()
        compose.runOnIdle { sessionId = 2L }
        compose.onNodeWithText("已选资料").assertDoesNotExist()
        compose.onNodeWithContentDescription("展开任务标题").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("rename", "references"), actions) }
    }

    @Test
    fun taskReferencesStayReachableWhenKeyboardLeavesOnlyFortyDpForChat() {
        var referencesOpened = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(40.dp)) {
                    AgentTaskPanel(
                        sessionId = 1L, title = "任务标题",
                        onRename = {}, onOpenReferences = { referencesOpened = true }
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("展开任务标题").performClick()
        compose.onNodeWithText("已选资料").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, referencesOpened) }
        compose.onNodeWithContentDescription("收起任务标题").performClick()
        compose.onNodeWithText("已选资料").assertDoesNotExist()
    }

    @Test
    fun permissionSelectionUpdatesShieldAndClosesDialog() {
        var selected = ""
        compose.setContent {
            MaterialTheme {
                var show by remember { mutableStateOf(true) }
                var policy by remember { mutableStateOf(AgentWritePermissionPolicy.ASK_EVERY_TIME) }
                AgentPermissionIcon(policy)
                if (show) {
                    WritePermissionDialog(
                        current = policy,
                        onSelect = { policy = it; selected = it; show = false },
                        onDismiss = { show = false }
                    )
                }
            }
        }
        compose.onNodeWithText("写入权限").assertIsDisplayed()
        compose.onNodeWithText("中权限").performClick()
        compose.onNodeWithText("写入权限").assertDoesNotExist()
        compose.onNodeWithContentDescription("中权限").assertIsDisplayed()
        compose.runOnIdle { assertEquals(AgentWritePermissionPolicy.BATCH_CONFIRM, selected) }
    }
}
