package com.lianwenqi.app.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F6 写入预览差异：公共前后缀 + 行级 LCS + 截断提示 */
class AgentWriteDiffTest {

    @Test
    fun 相同内容只返回一行相同文本() {
        val diff = buildAgentWriteDiff("第一行\n第二行", "第一行\n第二行")
        assertEquals(1, diff.size)
        assertEquals(DiffKind.SAME, diff.single().kind)
    }

    @Test
    fun 能标记新增与删除行() {
        val diff = buildAgentWriteDiff(
            "第一行\n旧内容\n第三行",
            "第一行\n新内容\n第三行"
        )
        assertTrue(diff.any { it.kind == DiffKind.REMOVED && it.text == "旧内容" })
        assertTrue(diff.any { it.kind == DiffKind.ADDED && it.text == "新内容" })
        assertTrue(diff.any { it.kind == DiffKind.SAME && it.text == "第一行" })
    }

    @Test
    fun 空原文视为全部新增() {
        val diff = buildAgentWriteDiff("", "新章节正文\n第二行")
        assertEquals(2, diff.size)
        assertTrue(diff.all { it.kind == DiffKind.ADDED })
    }

    @Test
    fun 超大差异窗口会明确截断而不是静默丢失() {
        val before = (1..400).joinToString("\n") { "旧 $it" }
        val after = (1..400).joinToString("\n") { "新 $it" }
        val diff = buildAgentWriteDiff(before, after, maxWindowLines = 50)
        assertTrue(diff.any { it.text.contains("差异预览已截断") })
    }
}
