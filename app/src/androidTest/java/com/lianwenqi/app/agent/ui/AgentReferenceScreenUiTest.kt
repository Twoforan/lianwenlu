package com.lianwenqi.app.agent.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.context.AgentReferenceTarget
import com.lianwenqi.app.agent.context.ResolvedAgentReference
import com.lianwenqi.app.agent.data.dao.AgentReferenceChapterRow
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentSessionReference
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskReference
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** F5 参考资料选择 UI：三类资料、多选、失效状态与任务资料入口 */
@RunWith(AndroidJUnit4::class)
class AgentReferenceScreenUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun chapter(id: Long, title: String) = AgentReferenceChapterRow(
        id = id,
        bookId = 1L,
        volumeId = 2L,
        title = title,
        synopsisSnippet = "梗概 $title",
        synopsisLength = 100,
        contentLength = 2_000,
        updatedAt = 1L
    )

    private fun reference(
        id: Long = 1L,
        mode: String = AgentReferenceMode.CONTEXT
    ) = AgentSessionReference(
        id = id,
        sessionId = 10L,
        sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
        sourceId = 1L,
        bookId = 1L,
        chapterId = 1L,
        titleSnapshot = "第一章",
        referenceMode = mode,
        selectedAt = 1L
    )

    @Test
    fun 章节选择支持多选并提交对应来源类型() {
        var selected by mutableStateOf(setOf<Long>())
        var added: String? = null
        composeRule.setContent {
            ChapterPickerTab(
                sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
                books = listOf(Book(id = 1L, title = "某书")),
                volumes = listOf(Volume(id = 2L, bookId = 1L, title = "第一卷")),
                chapters = listOf(chapter(1L, "第一章"), chapter(2L, "第二章")),
                filterBookId = 1L,
                filterVolumeId = null,
                search = "",
                selectedIds = selected,
                loading = false,
                onSearchChange = {},
                onFilterBook = {},
                onFilterVolume = {},
                onToggle = { id ->
                    selected = if (id in selected) selected - id else selected + id
                },
                onAdd = { added = it }
            )
        }

        composeRule.onNodeWithText("第一章").performClick()
        composeRule.onNodeWithText("第二章").performClick()
        composeRule.onNodeWithText("已选 2 章").assertIsDisplayed()
        composeRule.onNodeWithText("添加引用").performClick()
        assertEquals(AgentReferenceSourceType.CHAPTER_SYNOPSIS, added)
    }

    @Test
    fun 已选列表显示失效状态并支持切换模式与移除() {
        var toggled = false
        var removed = false
        val invalid = ResolvedAgentReference(
            target = AgentReferenceTarget(
                referenceId = 1L,
                sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
                sourceId = 1L
            ),
            valid = false,
            invalidReason = "章节已删除或不存在",
            title = "第一章"
        )
        composeRule.setContent {
            SelectedTab(
                references = listOf(reference()),
                statusMap = mapOf(1L to invalid),
                taskReferences = emptyList(),
                books = listOf(Book(id = 1L, title = "某书")),
                onToggleMode = { toggled = true },
                onPreview = {},
                onRemove = { removed = true }
            )
        }

        composeRule.onNodeWithText("已失效：章节已删除或不存在").assertIsDisplayed()
        composeRule.onNodeWithText("CONTEXT").performClick()
        assertEquals(true, toggled)
        composeRule.onNodeWithText("移除").performClick()
        assertEquals(true, removed)
    }

    @Test
    fun 当前任务实际使用显示注入截断与失效状态() {
        val injected = AgentTaskReference(
            id = 1L,
            taskId = 9L,
            sessionId = 10L,
            sourceType = AgentReferenceSourceType.CHAPTER_CONTENT,
            sourceId = 1L,
            bookId = 1L,
            chapterId = 1L,
            referenceMode = AgentReferenceMode.CONTEXT,
            selectedAt = 1L,
            sourceTitleSnapshot = "第一章",
            validated = true,
            valid = true,
            injected = true,
            truncated = true,
            contentChars = 100,
            contentTotalChars = 500
        )
        val invalid = AgentTaskReference(
            id = 2L,
            taskId = 9L,
            sessionId = 10L,
            sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
            sourceId = 2L,
            bookId = 1L,
            chapterId = 2L,
            referenceMode = AgentReferenceMode.CONTEXT,
            selectedAt = 2L,
            sourceTitleSnapshot = "第二章",
            validated = true,
            valid = false,
            invalidReason = "来源已删除"
        )
        composeRule.setContent {
            SelectedTab(
                references = emptyList(),
                statusMap = emptyMap(),
                taskReferences = listOf(injected, invalid),
                books = listOf(Book(id = 1L, title = "某书")),
                onToggleMode = {},
                onPreview = {},
                onRemove = {}
            )
        }

        composeRule.onNodeWithText("已注入（截断 100/500 字）").assertIsDisplayed()
        composeRule.onNodeWithText("已失效：来源已删除").assertIsDisplayed()
    }

    @Test
    fun Agent历史按会话展开并显示任务资料入口() {
        var planTask: AgentTask? = null
        var materialTask: AgentTask? = null
        val session = AgentSession(id = 20L, title = "旧会话", bookId = 1L)
        val task = AgentTask(
            id = 30L,
            sessionId = 20L,
            title = "旧任务",
            userGoal = "检查旧任务目标",
            status = AgentTaskStatus.COMPLETED
        )
        composeRule.setContent {
            AgentHistoryTab(
                sessions = listOf(session),
                books = listOf(Book(id = 1L, title = "某书")),
                search = "",
                filterBookId = null,
                expandedSessionId = 20L,
                tasks = listOf(task),
                selectedMaterials = emptyMap(),
                onSearchChange = {},
                onFilterBook = {},
                onToggleSession = {},
                onTogglePlan = { planTask = it },
                onToggleResult = {},
                onOpenMaterial = { materialTask = it },
                onAdd = {}
            )
        }

        composeRule.onNodeWithText("旧会话").assertIsDisplayed()
        composeRule.onNodeWithText("旧任务").assertIsDisplayed()
        composeRule.onNodeWithText("最终结果").assertIsDisplayed()
        composeRule.onNodeWithText("任务计划").performClick()
        assertNotNull(planTask)
        composeRule.onNodeWithText("消息 / 工具结果").performClick()
        assertNotNull(materialTask)
    }
}
