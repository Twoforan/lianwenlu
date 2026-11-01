package com.lianwenqi.app.data

import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.SessionPrefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「历史对话前缀」纯逻辑的单元测试：
 * 旧数据解码兼容、历史梗概 / 正文的空值跳过与完整正文、
 * 章节范围 / 排序 / 去重，以及每轮消息的角色过滤与截止位置。
 */
class HistoryPrefixTest {

    private fun chapter(
        id: Long,
        title: String,
        summary: String = "",
        content: String = "",
        sortOrder: Int = 0
    ) = Chapter(
        id = id, bookId = 1L, title = title, summary = summary, content = content, sortOrder = sortOrder
    )

    private fun message(
        id: Long,
        role: ChatRole,
        content: String = "m$id",
        active: Boolean = true,
        chapterId: Long = 0L
    ) = ChatMessage(
        id = id, sessionId = 1L, role = role, content = content, active = active, chapterId = chapterId
    )

    // 1. 旧会话数据缺少新字段时仍能解码
    @Test
    fun oldSessionPrefix_withoutHistoryFields_decodesToDefaults() {
        val sp = SessionPrefix.decode("""{"custom":"旧前缀","initialized":true}""")
        assertEquals("旧前缀", sp.custom)
        assertTrue(sp.initialized)
        assertFalse(sp.historySummaryOn)
        assertFalse(sp.historyContentOn)
        assertFalse(sp.historyMessageOn)
        assertFalse(sp.historyAiOn)
        assertFalse(sp.historyUserOn)
    }

    // 2. 历史梗概跳过空梗概章节
    @Test
    fun renderSummary_skipsEmptySummary() {
        val chapters = listOf(
            chapter(1, "第1章", summary = "梗概一", sortOrder = 1),
            chapter(2, "第2章", summary = "   ", sortOrder = 2)
        )
        val text = HistoryChapters.renderSummary(chapters)
        assertTrue(text.contains("第1章"))
        assertTrue(text.contains("梗概：梗概一"))
        assertFalse(text.contains("第2章"))
    }

    // 3. 历史正文使用完整正文并跳过空正文章节
    @Test
    fun renderContent_usesFullContent_andSkipsEmptyContent() {
        val full = "正文开头" + "字".repeat(1500) + "正文结尾"
        val chapters = listOf(
            chapter(1, "第1章", content = full, sortOrder = 1),
            chapter(2, "第2章", content = "  ", sortOrder = 2)
        )
        val text = HistoryChapters.renderContent(chapters)
        assertTrue(text.contains(full))
        assertFalse(text.contains("第2章"))
    }

    // 4. 当前章节不会进入历史范围；章节去重并按书籍顺序排列
    @Test
    fun pickIds_excludesCurrentChapter_dedupes_andOrderFollowsBookOrder() {
        val ids = HistoryChapters.pickIds(
            messageChapterIds = listOf(3L, 1L, 1L, 0L, 2L),
            sessionChapterId = 2L,
            excludeChapterId = 3L
        )
        assertEquals(listOf(1L, 2L), ids)

        val ordered = HistoryChapters.order(
            listOf(
                chapter(3, "第3章", sortOrder = 3),
                chapter(1, "第1章", sortOrder = 1),
                chapter(2, "第2章", sortOrder = 2)
            )
        )
        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.id })
    }

    // 5. 仅 AI / 仅用户 / 两者同时开启时的消息过滤
    @Test
    fun pick_filtersByRoleSwitches() {
        val messages = listOf(
            message(1, ChatRole.USER),
            message(2, ChatRole.ASSISTANT),
            message(3, ChatRole.SYSTEM),
            message(4, ChatRole.USER),
            message(5, ChatRole.ASSISTANT)
        )
        assertEquals(listOf(2L, 5L), HistoryPrefix.pick(messages, 6L, aiOn = true, userOn = false).map { it.id })
        assertEquals(listOf(1L, 4L), HistoryPrefix.pick(messages, 6L, aiOn = false, userOn = true).map { it.id })
        assertEquals(
            listOf(1L, 2L, 4L, 5L),
            HistoryPrefix.pick(messages, 6L, aiOn = true, userOn = true).map { it.id }
        )
        assertTrue(HistoryPrefix.pick(messages, 6L, aiOn = false, userOn = false).isEmpty())
    }

    // 6. 每轮消息不包含当前输入与未完成 / 废弃回答
    @Test
    fun pick_excludesCurrentInput_andInactiveAssistant() {
        val messages = listOf(
            message(1, ChatRole.USER),
            message(2, ChatRole.ASSISTANT, active = false),
            message(3, ChatRole.ASSISTANT, active = true),
            message(4, ChatRole.USER, content = "本轮输入")
        )
        // 截止位置 4 为「不包含」：本轮输入不进历史
        val picked = HistoryPrefix.pick(messages, 4L, aiOn = true, userOn = true)
        assertEquals(listOf(1L, 3L), picked.map { it.id })
        assertTrue(picked.none { it.content == "本轮输入" })
    }

    // 7. 编辑重发、重新生成遵守历史截止位置：不含被替换分支及其之后的消息
    @Test
    fun pick_respectsCutoff_forEditAndRegenerate() {
        val messages = listOf(
            message(1, ChatRole.USER, content = "第一轮提问"),
            message(2, ChatRole.ASSISTANT, content = "第一轮回答"),
            message(3, ChatRole.USER, content = "被重新生成的那一轮提问"),
            message(4, ChatRole.ASSISTANT, content = "被替换的旧回答", active = false),
            message(5, ChatRole.USER, content = "被替换分支之后的消息")
        )
        // 对第 3 条用户消息重新生成 / 编辑重发：截止位置为该消息本身（不包含），
        // 因此既不含本轮提问，也不含它之后的旧回答与后续消息
        val picked = HistoryPrefix.pick(messages, cutoffExclusive = 3L, aiOn = true, userOn = true)
        assertEquals(listOf(1L, 2L), picked.map { it.id })
    }

    // 8. 渲染标注角色，两个角色都关闭 / 过滤后为空时不产生内容
    @Test
    fun render_labelsRoles_andEmptyWhenNothingSelected() {
        val messages = listOf(message(1, ChatRole.USER, "你好"), message(2, ChatRole.ASSISTANT, "你好呀"))
        assertEquals("用户：你好\nAI：你好呀", HistoryPrefix.render(messages))
        assertEquals("", HistoryPrefix.render(emptyList()))
    }

    // 9. 日志轮次与实际注入消息一致
    @Test
    fun rounds_matchInjectedMessages() {
        val messages = listOf(
            message(1, ChatRole.USER, "问1"),
            message(2, ChatRole.ASSISTANT, "答1"),
            message(3, ChatRole.USER, "问2")
        )
        assertEquals(
            listOf(PromptLogRound("问1", "答1"), PromptLogRound("问2", "")),
            HistoryPrefix.rounds(messages)
        )
    }
}
