package com.lianwenqi.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.ContextSnapshot
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatConversationUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = requireNotNull(context.getExternalFilesDir("chat-fold-review"))
        folder.mkdirs()
        File(folder, "$name.png").outputStream().use { stream ->
            composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    @Test
    fun conversationHeaderAndFooterToggleTheSameContent() {
        composeRule.setContent {
            MaterialTheme {
                var collapsed by remember { mutableStateOf(true) }
                Box(Modifier.fillMaxSize().background(Color(0xFFF5F6FA)).padding(16.dp)) {
                    Column(Modifier.width(399.dp).verticalScroll(rememberScrollState())) {
                        ChapterGroupCard(
                            title = "第1章 落日余晖", rounds = 1, words = 5335,
                            preview = "生成正文", collapsed = collapsed,
                            onToggle = { collapsed = !collapsed }
                        ) {
                            MessageCard(ai = false, tools = { Text("复制 · 编辑") }) {
                                Text("帮我分析第三章的剧情，检查人物行为逻辑和剧情漏洞。")
                            }
                            MessageCard(ai = true, tools = { Text("复制 · 查看日志 · 收起") }) {
                                Text("沈观澜被派到藏经楼下做粗活，扫阶、搬经、晒书。")
                                Text("model: deepseek · 缓存命中率：69%")
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("共1条对话·约5335字").assertIsDisplayed()
        composeRule.onNodeWithText("生成正文").assertIsDisplayed()
        composeRule.onNodeWithText("model: deepseek · 缓存命中率：69%").assertDoesNotExist()
        saveScreenshot("component-collapsed")
        composeRule.onNodeWithText("第1章 落日余晖").performClick()
        composeRule.onNodeWithText("model: deepseek · 缓存命中率：69%").assertIsDisplayed()
        composeRule.onNodeWithText("生成正文").assertDoesNotExist()
        saveScreenshot("component-expanded")
        composeRule.onAllNodesWithContentDescription("收起会话").onLast().performScrollTo().performClick()
        composeRule.onNodeWithText("生成正文").assertIsDisplayed()
        composeRule.onNodeWithText("model: deepseek · 缓存命中率：69%").assertDoesNotExist()
    }

    @Test
    fun veryLongConversationKeepsTheFooterClickable() {
        composeRule.setContent {
            MaterialTheme {
                var collapsed by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    ChapterGroupCard(
                        title = "长章节", rounds = 1, words = 24000,
                        preview = "长回答预览", collapsed = collapsed,
                        onToggle = { collapsed = !collapsed }
                    ) {
                        MessageCard(ai = true, tools = { Text("长回答操作栏") }) {
                            Text("这一段用于检查长回答的绘制与底部交互。\n".repeat(1200))
                        }
                    }
                }
            }
        }
        composeRule.onAllNodesWithContentDescription("收起会话").onLast()
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("长回答操作栏").assertIsDisplayed()
        saveScreenshot("component-long-footer")
        composeRule.onAllNodesWithContentDescription("收起会话").onLast().performClick()
        composeRule.onNodeWithText("长回答预览").assertIsDisplayed()
        composeRule.onNodeWithText("长回答操作栏").assertDoesNotExist()
    }

    @Test
    fun manifestCanOpenTableAndTxtDirectlyAndCopyWithoutClosing() {
        val previews = AtomicInteger()
        val previewText = "沈观澜被派到藏经楼下做粗活，扫阶、搬经、晒书。楼前青石阶被历代弟子踩出深浅不一的凹坑，执事只交代“扫干净”，别无他话。楼下常蹲着个邋遢老者，剥豆喂雀，弟子们背地说他是修不成的废人。沈观澜替他归拢散落经卷，翻见一本残卷上满是炭笔批注。"
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.width(280.dp).padding(8.dp)) {
                    ContextManifest(
                        snapshot = ContextSnapshot(
                            chapterIds = listOf(1, 2, 3),
                            summaryChapterIds = listOf(1, 2, 3),
                            contentChapterIds = listOf(1, 2),
                            summaryAll = false,
                            contentAll = false
                        ),
                        chapters = listOf(
                            Chapter(id = 1, bookId = 1, title = "第一章"),
                            Chapter(id = 2, bookId = 1, title = "第二章"),
                            Chapter(id = 3, bookId = 1, title = "第三章")
                        ),
                        outline = emptyList(),
                        preview = { previews.incrementAndGet(); previewText }
                    )
                }
            }
        }
        composeRule.onNodeWithText("3项").assertIsDisplayed()
        composeRule.onNodeWithText("查看TXT").assertIsDisplayed()
        composeRule.onNodeWithText("复制").assertIsDisplayed()
        composeRule.onNodeWithText("展开").performClick()
        composeRule.onNodeWithText("仅梗概").assertIsDisplayed()
        composeRule.onNodeWithText("第三章").assertIsDisplayed()
        composeRule.onAllNodesWithText("✔").assertCountEquals(5)
        composeRule.onNodeWithText("—").assertIsDisplayed()
        saveScreenshot("component-manifest-table")
        composeRule.onNodeWithText("查看TXT").performClick()
        composeRule.onNodeWithText(previewText).assertIsDisplayed()
        composeRule.onNodeWithText("仅梗概").assertDoesNotExist()
        composeRule.onNodeWithText("复制").performClick()
        composeRule.runOnIdle { assertEquals(2, previews.get()) }
        composeRule.onNodeWithText(previewText).assertIsDisplayed()
        composeRule.onNodeWithText("收起").performClick()
        composeRule.onNodeWithText(previewText).assertDoesNotExist()
        composeRule.onNodeWithText("查看TXT").performClick()
        composeRule.onNodeWithText(previewText).assertIsDisplayed()
        saveScreenshot("component-manifest-txt")
    }

    @Test
    fun narrowMessageToolbarScrollsToItsLastAction() {
        var clicked = false
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(240.dp).padding(8.dp)) {
                    MessageCard(ai = true, tools = {
                        repeat(12) { index ->
                            Box(Modifier.size(30.dp).testTag("action-$index").clickable { clicked = true }) {
                                Text(index.toString())
                            }
                        }
                    }) { Text("侧边栏窄宽度测试") }
                }
            }
        }
        composeRule.onNodeWithTag("action-11").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(true, clicked) }
    }
}
