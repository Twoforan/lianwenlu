package com.lianwenqi.app.agent.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 实际 Agent 消息组件的回归：窄屏、长回复、折叠、编辑与旧操作入口。 */
@RunWith(AndroidJUnit4::class)
class AgentConversationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val user = AgentMessage(
        id = 1, sessionId = 1, role = AgentMessageRole.USER,
        kind = AgentMessageKind.USER_INPUT, sequence = 0,
        content = "帮我分析一下第三章的剧情，看看人物的行为逻辑是否合理，以及有没有明显的剧情漏洞。特别关注主角在面对陌生人邀请时的决定是否符合前文人设。"
    )
    private val assistant = AgentMessage(
        id = 2, sessionId = 1, role = AgentMessageRole.ASSISTANT,
        kind = AgentMessageKind.FINAL_ANSWER, sequence = 1,
        content = "已经帮你整理好了。\n主角的选择与前文建立的谨慎性格一致，建议补充他接受邀请前观察周围环境的细节，让动机更加自然。",
        promptTokens = 1000, completionTokens = 200, cachedTokens = 690,
        promptLog = "本轮提交日志"
    )

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = requireNotNull(context.getExternalFilesDir("agent-message-review"))
        folder.mkdirs()
        File(folder, "$name.png").outputStream().use {
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Composable
    private fun Message(
        message: AgentMessage = assistant,
        collapsed: Boolean = false,
        deletePending: Boolean = false,
        editing: Boolean = false,
        draft: String = message.content,
        versions: Boolean = false,
        onDraft: (String) -> Unit = {},
        onAction: (String) -> Unit = {}
    ) {
        AgentMessageRow(
            message = message,
            editable = true,
            editing = editing,
            draft = draft,
            onDraftChange = onDraft,
            onCopy = { onAction("copy") },
            onStartEdit = { onAction("edit") },
            onCancelEdit = { onAction("cancel") },
            onRegenerate = { onAction("regenerate") },
            onSubmitEdit = { onAction("save") },
            showAssistantActions = true,
            onRegenerateAssistant = { onAction("regenerate") },
            chatStyle = true,
            collapsed = collapsed,
            onToggleCollapsed = { onAction("collapse") },
            deletePending = deletePending,
            showVersionSwitcher = versions,
            versionIndex = 2,
            versionCount = 3,
            onPrevVersion = { onAction("previous") },
            onNextVersion = { onAction("next") },
            onDeleteMessage = { onAction("delete") },
            onViewLog = { onAction("log") },
            modelName = if (message.role == AgentMessageRole.ASSISTANT) "deepseek" else ""
        )
    }

    @Test
    fun conversationRetainsMetadataAndOriginalActions() {
        val actions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                var collapsed by remember { mutableStateOf(false) }
                var deletePending by remember { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxSize().background(Color(0xFFF5F6FA))
                        .verticalScroll(rememberScrollState()).padding(vertical = 16.dp)
                ) {
                    Message(message = user)
                    Message(collapsed = collapsed, deletePending = deletePending) {
                        actions += it
                        if (it == "collapse") collapsed = !collapsed
                        if (it == "delete") deletePending = !deletePending
                    }
                }
            }
        }
        composeRule.onNodeWithText("model: deepseek").assertIsDisplayed()
        composeRule.onNodeWithText("缓存命中率：69%", substring = true).assertIsDisplayed()
        screenshot("conversation")
        composeRule.onAllNodesWithContentDescription("复制").onLast().performClick()
        composeRule.onNodeWithContentDescription("查看提交日志").performClick()
        composeRule.onAllNodesWithContentDescription("重新生成").onLast().performClick()
        composeRule.onNodeWithContentDescription("编辑回复").performClick()
        composeRule.onAllNodesWithContentDescription("删除").onLast().performClick()
        composeRule.onNodeWithContentDescription("确认删除").assertIsDisplayed().performClick()
        composeRule.onAllNodesWithContentDescription("收起").onLast().performClick()
        composeRule.onNodeWithText("model: deepseek").assertDoesNotExist()
        composeRule.onNodeWithText("缓存命中率：69%", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("展开").performClick()
        composeRule.onNodeWithText("model: deepseek").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf("copy", "log", "regenerate", "edit", "delete", "delete", "collapse", "collapse"), actions)
        }
    }

    @Test
    fun narrowUserToolbarKeepsVersionsAndLastActionReachable() {
        val actions = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.width(240.dp).background(Color(0xFFF5F6FA))) {
                    Message(message = user, versions = true, onAction = { actions += it })
                }
            }
        }
        composeRule.onNodeWithContentDescription("上一版本").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("下一版本").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("收起").performScrollTo().assertIsDisplayed().performClick()
        screenshot("narrow-toolbar")
        composeRule.runOnIdle { assertEquals(listOf("previous", "next", "collapse"), actions) }
    }

    @Test
    fun veryLongReplyStillDrawsAndClicksItsFooter() {
        val actions = mutableListOf<String>()
        val scroll = ScrollState(0)
        val longMessage = assistant.copy(content = "这一段用于检查长回复的绘制与底部交互。\n".repeat(1200))
        composeRule.setContent {
            MaterialTheme {
                var collapsed by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().background(Color(0xFFF5F6FA)).verticalScroll(scroll)) {
                    Message(message = longMessage, collapsed = collapsed) {
                        actions += it
                        if (it == "collapse") collapsed = !collapsed
                    }
                }
            }
        }
        // 与页面接收回复后的自动滚动一致。操作栏自身可横滚，performScrollTo
        // 只会命中最近的横向容器，需要先把外层纵向会话滚到底部。
        composeRule.runOnIdle {
            runBlocking { scroll.scrollTo(scroll.maxValue) }
        }
        composeRule.onNodeWithContentDescription("复制").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("收起").assertIsDisplayed()
        screenshot("long-reply-footer")
        composeRule.onNodeWithContentDescription("收起").performClick()
        composeRule.onNodeWithContentDescription("展开").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("copy", "collapse"), actions) }
    }

    @Test
    fun replyEditorKeepsSaveAndCancelInTheFooter() {
        val actions = mutableListOf<String>()
        var saved = ""
        composeRule.setContent {
            MaterialTheme {
                var editing by remember { mutableStateOf(true) }
                var draft by remember { mutableStateOf("原回复") }
                Column(Modifier.fillMaxSize()) {
                    Message(editing = editing, draft = draft, onDraft = { draft = it }) {
                        actions += it
                        if (it == "save") { saved = draft; editing = false }
                        if (it == "edit") editing = true
                        if (it == "cancel") editing = false
                    }
                }
            }
        }
        composeRule.onNodeWithText("原回复").performTextReplacement("修改后的回复")
        composeRule.onNodeWithText("保存").performClick()
        composeRule.onNodeWithContentDescription("编辑回复").performClick()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithContentDescription("复制").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("修改后的回复", saved)
            assertEquals(listOf("save", "edit", "cancel"), actions)
        }
    }
}
