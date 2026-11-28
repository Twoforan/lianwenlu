package com.lianwenqi.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.PromptType
import com.lianwenqi.app.ui.theme.LianWenQiTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 内存中的提示词组件测试，不读取或修改用户数据库。 */
@RunWith(AndroidJUnit4::class)
class PromptLibraryUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val polishing = Prompt(
        id = 701L,
        title = "润色正文",
        content = "请帮我润色下面的小说正文。在不改变原文剧情、人物设定和核心表达的前提下，优化语言流畅度、描写细节和段落节奏。避免过度华丽的辞藻，保持自然、有画面感的叙述风格。",
        type = PromptType.QUICK,
        createdAt = 0L
    )
    private val character = polishing.copy(
        id = 702L,
        title = "人物塑造",
        content = "请围绕 HERO 的目标、动机和冲突，完善人物的心理与动作描写。"
    )
    private val model = polishing.copy(
        id = 703L,
        title = "小说写作助手",
        content = "你是一位小说写作助手，擅长梳理情节、塑造人物和完善世界观。请遵循作者设定，保持叙述风格一致，提供具体、清晰的写作建议。",
        type = PromptType.MODEL
    )

    @Test
    fun searchesTitleAndContentWithinSelectedTypeAndAddsToThatType() {
        val additions = mutableListOf<PromptType>()
        showPage(listOf(polishing, character, model), onAdd = { additions += it })
        composeRule.onNodeWithText("共2条").assertIsDisplayed()
        composeRule.onNodeWithText(model.title).assertDoesNotExist()
        composeRule.onNodeWithText("添加提示词").performClick()

        val search = composeRule.onNodeWithContentDescription("搜索提示词标题或内容")
        search.performTextInput(" 润色 ")
        search.performImeAction()
        composeRule.onNodeWithText(polishing.title).assertIsDisplayed()
        composeRule.onNodeWithText(character.title).assertDoesNotExist()
        composeRule.onNodeWithText("共1条").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("清除搜索").performClick()
        search.performTextInput("hero")
        search.performImeAction()
        composeRule.onNodeWithText(character.title).assertIsDisplayed()
        composeRule.onNodeWithText(polishing.title).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("清除搜索").performClick()

        selectType("模型提示词")
        composeRule.onNodeWithText(model.title).assertIsDisplayed()
        composeRule.onNodeWithText(polishing.title).assertDoesNotExist()
        composeRule.onNodeWithText("共1条").assertIsDisplayed()
        composeRule.onNodeWithText("添加提示词").performClick()
        composeRule.runOnIdle { assertEquals(listOf(PromptType.QUICK, PromptType.MODEL), additions) }
    }

    @Test
    fun expandsLongContentAndDispatchesCardActionsWithFullClipboardText() {
        val longPrompt = polishing.copy(content = polishing.content.repeat(3))
        val edited = mutableListOf<Prompt>()
        val deleted = mutableListOf<Prompt>()
        showPage(listOf(longPrompt), onEdit = { edited += it }, onDelete = { deleted += it })
        val content = composeRule.onNodeWithText(longPrompt.content)
        val collapsedBounds = content.getUnclippedBoundsInRoot()
        val collapsedHeight = collapsedBounds.bottom - collapsedBounds.top
        composeRule.onNodeWithText("展开内容").performClick()
        composeRule.onNodeWithText("收起内容").performScrollTo().assertIsDisplayed()
        val expandedBounds = content.getUnclippedBoundsInRoot()
        assertTrue(expandedBounds.bottom - expandedBounds.top > collapsedHeight)
        composeRule.onNodeWithText("收起内容").performClick()
        composeRule.onNodeWithText("编辑").performScrollTo().performClick()
        composeRule.onNodeWithText("复制").performClick()
        composeRule.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(longPrompt.content, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(listOf(longPrompt), edited)
            assertTrue(deleted.isEmpty())
        }
        composeRule.onNodeWithText("删除").performClick()
        composeRule.runOnIdle { assertEquals(listOf(longPrompt), deleted) }
    }

    @Test
    fun emptyCategoryAndNoSearchResultsHaveDifferentMessages() {
        showPage(listOf(polishing))
        selectType("模型提示词")
        composeRule.onNodeWithText("还没有模型提示词").assertIsDisplayed()
        composeRule.onNodeWithText("共0条").assertIsDisplayed()
        selectType("快捷提示词")
        val search = composeRule.onNodeWithContentDescription("搜索提示词标题或内容")
        search.performTextInput("不存在的内容")
        search.performImeAction()
        composeRule.onNodeWithText("没有找到匹配的提示词").assertIsDisplayed()
        composeRule.onNodeWithText("还没有快捷提示词").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("清除搜索").performClick()
        composeRule.onNodeWithText(polishing.title).assertIsDisplayed()
    }

    @Test
    fun capturesLightDarkAndNarrowLargeFontLayouts() {
        var variant by mutableStateOf(Variant())
        showPage(listOf(polishing, character, model), variant = { variant })
        capture("light-quick")
        selectType("模型提示词")
        composeRule.onNodeWithText(model.title).assertIsDisplayed()
        capture("light-model")
        composeRule.runOnIdle { variant = Variant(dark = true) }
        composeRule.onNodeWithText(model.title).assertIsDisplayed()
        capture("dark-model")
        composeRule.runOnIdle { variant = Variant(width = 320.dp, fontScale = 1.3f) }
        composeRule.onNodeWithTag(PAGE_TAG).assertWidthIsEqualTo(320.dp)
        composeRule.onNodeWithText("添加提示词").assertIsDisplayed()
        composeRule.onNodeWithText("删除").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("复制").assertIsDisplayed()
        composeRule.onNodeWithText("编辑").assertIsDisplayed()
        capture("narrow-font130")
        composeRule.onAllNodesWithText("收起内容").assertCountEquals(0)
    }

    private fun selectType(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction()).performClick().assertIsSelected()
    }

    private fun showPage(
        prompts: List<Prompt>,
        onAdd: (PromptType) -> Unit = {},
        onEdit: (Prompt) -> Unit = {},
        onDelete: (Prompt) -> Unit = {},
        variant: () -> Variant = { Variant() }
    ) {
        composeRule.setContent { Page(prompts, onAdd, onEdit, onDelete, variant()) }
    }

    @Composable
    private fun Page(
        prompts: List<Prompt>,
        onAdd: (PromptType) -> Unit,
        onEdit: (Prompt) -> Unit,
        onDelete: (Prompt) -> Unit,
        variant: Variant
    ) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, variant.fontScale)) {
            LianWenQiTheme(darkTheme = variant.dark) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.width(variant.width).fillMaxHeight()
                            .background(settingsPageBackground(variant.dark)).testTag(PAGE_TAG)
                    ) {
                        SettingsPageHeader(1, variant.dark, {}, {}, {})
                        Box(Modifier.weight(1f)) { PromptsTab(prompts, onAdd, onEdit, onDelete) }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir(null))
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = composeRule.onNodeWithTag(PAGE_TAG).captureToImage().asAndroidBitmap()
        File(directory, "prompt-library-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private data class Variant(val dark: Boolean = false, val width: Dp = 400.dp, val fontScale: Float = 1f)

    private companion object {
        const val PAGE_TAG = "prompt-library-fixture"
    }
}
