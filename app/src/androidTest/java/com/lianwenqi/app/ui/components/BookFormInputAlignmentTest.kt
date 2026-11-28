package com.lianwenqi.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.ui.theme.AppTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 直接渲染共用表单，不创建、修改或保存用户的书籍。 */
@RunWith(AndroidJUnit4::class)
class BookFormInputAlignmentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var focusManager: FocusManager

    @Test
    fun createFormAlignsPlaceholderCaretAndLongText() {
        showForm(initial = null)
        checkSingleLineInputs()
        checkMultilineIntro()
    }

    @Test
    fun editFormAlignsPlaceholderCaretAndLongText() {
        showForm(
            initial = Book(
                title = "内存中的编辑示例",
                intro = "已有简介\n第二行内容",
                coverEnglish = "EXAMPLE"
            )
        )
        checkSingleLineInputs()
        checkMultilineIntro()
    }

    private fun showForm(initial: Book?) {
        composeRule.setContent {
            // 保留应用的 24sp bodyLarge 行高，防止提示文字再次意外继承它。
            MaterialTheme(typography = AppTypography) {
                focusManager = LocalFocusManager.current
                BookFormDialog(
                    dialogTitle = if (initial == null) "添加书籍" else "编辑书籍",
                    initial = initial,
                    groups = emptyList(),
                    onPickCover = { _, _ -> },
                    onCreateGroup = { 0L },
                    onConfirm = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithText("展开封面设计").performClick()
    }

    private fun input(index: Int): SemanticsNodeInteraction =
        composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)[index]

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            action(layouts)
        }
        return layouts.single()
    }

    private fun checkSingleLineInputs() {
        // 展开封面设计后的原生输入顺序：封面英文、书名、简介、分卷、章节。
        val placeholders = listOf(0 to "Lianwenlu", 1 to "请输入书籍名", 3 to "第%n卷", 4 to "第%n章")
        for ((index, placeholder) in placeholders) {
            val field = input(index)
            field.performScrollTo().performTextReplacement("")
            composeRule.runOnIdle { focusManager.clearFocus(force = true) }
            field.performScrollTo()

            val hint = composeRule.onNodeWithText(placeholder, useUnmergedTree = true)
            val hintLayout = textLayout(hint)
            val emptyLayout = textLayout(field)
            val emptyBounds = field.fetchSemanticsNode().boundsInRoot
            val hintBounds = hint.fetchSemanticsNode().boundsInRoot
            assertEquals("$placeholder: empty line height", hintLayout.size.height, emptyLayout.size.height)
            assertEquals("$placeholder: font size", hintLayout.layoutInput.style.fontSize, emptyLayout.layoutInput.style.fontSize)
            assertEquals("$placeholder: line height", hintLayout.layoutInput.style.lineHeight, emptyLayout.layoutInput.style.lineHeight)
            assertEquals("$placeholder: centered hint", emptyBounds.center.y, hintBounds.center.y, 1.5f)
            checkCaretCenter(emptyLayout, 0, placeholder)

            field.performClick()
            assertEquals("$placeholder: focus preserves line height", emptyLayout.size.height, textLayout(field).size.height)
            field.performTextReplacement(placeholder)
            val filledLayout = textLayout(field)
            assertEquals("$placeholder: matching baseline", hintLayout.firstBaseline, filledLayout.firstBaseline, 1.5f)
            assertEquals("$placeholder: filled line height", hintLayout.size.height, filledLayout.size.height)

            val longText = placeholder.repeat(30)
            field.performTextReplacement(longText)
            val longLayout = textLayout(field)
            assertEquals("$placeholder: remains one line", 1, longLayout.lineCount)
            assertEquals("$placeholder: stable baseline", filledLayout.firstBaseline, longLayout.firstBaseline, 1.5f)
            assertEquals("$placeholder: stable text height", filledLayout.size.height, longLayout.size.height)
            assertEquals("$placeholder: stable field height", emptyBounds.height, field.fetchSemanticsNode().boundsInRoot.height, 1.5f)
            checkCaretCenter(longLayout, longText.length, placeholder)
        }
    }

    private fun checkMultilineIntro() {
        val field = input(2)
        field.performScrollTo().performTextReplacement("")
        composeRule.runOnIdle { focusManager.clearFocus(force = true) }
        field.performScrollTo()
        val hintLayout = textLayout(composeRule.onNodeWithText("请输入本书简介", useUnmergedTree = true))
        val value = "请输入本书简介\n第二行保留正常换行\n第三行内容\n第四行内容\n第五行内容"
        field.performTextReplacement(value)
        val layout = textLayout(field)
        assertTrue("简介仍支持多行", layout.lineCount >= 5)
        assertEquals("简介首行与提示使用相同基线", hintLayout.firstBaseline, layout.firstBaseline, 1.5f)
        assertEquals("简介与提示使用相同行高", hintLayout.layoutInput.style.lineHeight, layout.layoutInput.style.lineHeight)
        checkCaretCenter(layout, value.length, "简介最后一行")
    }

    private fun checkCaretCenter(layout: TextLayoutResult, offset: Int, label: String) {
        val line = layout.getLineForOffset(offset)
        val lineCenter = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
        assertEquals("$label: caret centered in text line", lineCenter, layout.getCursorRect(offset).center.y, 1.5f)
    }
}
