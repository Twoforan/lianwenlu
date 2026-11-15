package com.lianwenqi.app.ai

import com.lianwenqi.app.data.entity.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompt 分段摘要与首差异定位的纯逻辑测试 */
class PromptDiagnosticsTest {

    private fun turn(role: ChatRole, content: String) = ChatTurn(role, content)

    private fun fingerprint(
        systemPrompt: String = "系统提示",
        turns: List<ChatTurn>,
        toolsText: String = "tool-a|tool-b",
        fullRequestBody: String = "body"
    ) = PromptDiagnostics.fingerprint(
        systemPrompt = systemPrompt,
        turns = turns,
        toolsText = toolsText,
        toolCount = if (toolsText.isBlank()) 0 else 2,
        fullRequestBody = fullRequestBody
    )

    @Test
    fun `完全相同请求的首差异为 none`() {
        val turns = listOf(
            turn(ChatRole.SYSTEM, "文风"),
            turn(ChatRole.USER, "写第一章"),
            turn(ChatRole.ASSISTANT, "第一章正文"),
            turn(ChatRole.USER, "继续")
        )
        val first = fingerprint(turns = turns, fullRequestBody = "body-1")
        val second = fingerprint(turns = turns, fullRequestBody = "body-1")
        val diff = PromptDiagnostics.compare(first, second)
        assertEquals("none", diff.firstDifferentSegment)
        assertTrue(diff.intervalMs != null && diff.intervalMs!! >= 0)
        assertEquals(first.systemHash, second.systemHash)
        assertEquals(first.toolsHash, second.toolsHash)
        assertEquals(first.conversationPrefixHash, second.conversationPrefixHash)
        assertEquals(first.fullRequestHash, second.fullRequestHash)
    }

    @Test
    fun `只改最后一条用户消息时首差异为 tail`() {
        val prefix = listOf(
            turn(ChatRole.SYSTEM, "文风"),
            turn(ChatRole.USER, "写第一章"),
            turn(ChatRole.ASSISTANT, "第一章正文")
        )
        val first = fingerprint(turns = prefix + turn(ChatRole.USER, "继续写第二章"), fullRequestBody = "body-1")
        val second = fingerprint(turns = prefix + turn(ChatRole.USER, "继续写第三章"), fullRequestBody = "body-2")
        assertEquals("tail", PromptDiagnostics.compare(first, second).firstDifferentSegment)
        assertEquals(first.systemHash, second.systemHash)
        assertEquals(first.conversationPrefixHash, second.conversationPrefixHash)
        assertNotEquals(first.fullRequestHash, second.fullRequestHash)
    }

    @Test
    fun `修改系统提示时首差异为 system`() {
        val turns = listOf(turn(ChatRole.USER, "写"))
        val first = fingerprint(systemPrompt = "系统提示 A", turns = turns)
        val second = fingerprint(systemPrompt = "系统提示 B", turns = turns)
        assertEquals("system", PromptDiagnostics.compare(first, second).firstDifferentSegment)
        assertNotEquals(first.systemHash, second.systemHash)
    }

    @Test
    fun `修改工具定义时首差异为 tools`() {
        val turns = listOf(turn(ChatRole.USER, "写"))
        val first = fingerprint(turns = turns, toolsText = "tool-a")
        val second = fingerprint(turns = turns, toolsText = "tool-b")
        val diff = PromptDiagnostics.compare(first, second)
        assertEquals("tools", diff.firstDifferentSegment)
        assertNotEquals(first.toolsHash, second.toolsHash)
    }

    @Test
    fun `修改中间历史消息时定位到对应轮次`() {
        val base = listOf(
            turn(ChatRole.SYSTEM, "文风"),
            turn(ChatRole.USER, "第一问"),
            turn(ChatRole.ASSISTANT, "第一答"),
            turn(ChatRole.USER, "第二问"),
            turn(ChatRole.ASSISTANT, "第二答"),
            turn(ChatRole.USER, "当前输入")
        )
        val changed = base.toMutableList().apply {
            this[3] = turn(ChatRole.USER, "第二问（已修改）")
        }
        val first = fingerprint(turns = base)
        val second = fingerprint(turns = changed)
        assertEquals("conversation:2", PromptDiagnostics.compare(first, second).firstDifferentSegment)
    }

    @Test
    fun `历史长度变化时定位到缺失轮次`() {
        val short = listOf(
            turn(ChatRole.SYSTEM, "文风"),
            turn(ChatRole.USER, "第一问"),
            turn(ChatRole.ASSISTANT, "第一答"),
            turn(ChatRole.USER, "当前输入")
        )
        val long = listOf(
            turn(ChatRole.SYSTEM, "文风"),
            turn(ChatRole.USER, "第一问"),
            turn(ChatRole.ASSISTANT, "第一答"),
            turn(ChatRole.USER, "第二问"),
            turn(ChatRole.ASSISTANT, "第二答"),
            turn(ChatRole.USER, "当前输入")
        )
        assertEquals("conversation:2", PromptDiagnostics.compare(fingerprint(turns = short), fingerprint(turns = long)).firstDifferentSegment)
    }

    @Test
    fun `首次请求没有可比较基准且区间为空`() {
        val diff = PromptDiagnostics.compare(null, fingerprint(turns = listOf(turn(ChatRole.USER, "写"))))
        assertEquals("baseline", diff.firstDifferentSegment)
        assertNull(diff.intervalMs)
    }

    @Test
    fun `角色统计与字符数只包含计数不包含正文`() {
        val fp = fingerprint(
            systemPrompt = "系统",
            turns = listOf(
                turn(ChatRole.SYSTEM, "文风块"),
                turn(ChatRole.USER, "历史问"),
                turn(ChatRole.ASSISTANT, "历史答"),
                turn(ChatRole.USER, "当前问")
            )
        )
        assertEquals("系统".length + "文风块".length, fp.systemChars)
        assertEquals("历史问".length + "历史答".length, fp.historyChars)
        assertEquals("当前问".length, fp.tailChars)
        assertTrue(fp.roleSummary.contains("system:1/3chars"))
        assertTrue(fp.roleSummary.contains("user:2/"))
        assertTrue(fp.roleSummary.contains("assistant:1/3chars"))
        assertEquals(4, fp.messageCount)
    }

    @Test
    fun `摘要固定为十六个十六进制字符且同文同摘要`() {
        val a = PromptDiagnostics.hash("同样内容")
        val b = PromptDiagnostics.hash("同样内容")
        assertEquals(a, b)
        assertEquals(16, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertNotEquals(a, PromptDiagnostics.hash("不同内容"))
    }
}
