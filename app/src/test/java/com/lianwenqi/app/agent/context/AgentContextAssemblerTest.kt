package com.lianwenqi.app.agent.context

import com.lianwenqi.app.agent.data.AgentTaskReferenceInjection
import com.lianwenqi.app.agent.data.dao.AgentReferenceChapterMeta
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentTaskReference
import com.lianwenqi.app.agent.planning.AgentPlanParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5 上下文组装：预算、截断、REFERENCE_ONLY、失效来源与注入结果落库字段 */
class AgentContextAssemblerTest {

    private val chapters = mutableMapOf(
        1L to meta(1L, "第一章", "甲".repeat(500)),
        2L to meta(2L, "第二章", "乙".repeat(500)),
        3L to meta(3L, "第三章", "丙".repeat(500))
    )

    private fun meta(id: Long, title: String, summary: String) = AgentReferenceChapterMeta(
        id = id,
        bookId = 1L,
        volumeId = 2L,
        title = title,
        summary = summary,
        contentLength = 0,
        updatedAt = 1_700_000_000_000L
    )

    private fun resolver() = AgentReferenceResolver(
        chapterMetaOf = { id -> chapters[id] },
        chapterContentOf = { _, _, _ -> "" },
        bookTitleOf = { "某书" },
        volumeTitleOf = { "第一卷" },
        agentTaskOf = { null },
        agentSessionOf = { null },
        agentMessageOf = { null },
        toolExecutionOf = { null },
        finalAnswerOf = { null },
        planParser = AgentPlanParser()
    )

    private fun reference(
        id: Long,
        sourceId: Long,
        mode: String = AgentReferenceMode.CONTEXT
    ) = AgentTaskReference(
        id = id,
        taskId = 900L,
        sessionId = 800L,
        sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
        sourceId = sourceId,
        bookId = 1L,
        chapterId = sourceId,
        referenceMode = mode,
        selectedAt = id,
        sourceTitleSnapshot = "快照 $id"
    )

    private fun assembler(
        refs: List<AgentTaskReference>,
        budget: AgentContextBudget,
        recorded: MutableList<AgentTaskReferenceInjection>
    ) = AgentContextAssembler(
        resolver = resolver(),
        freezeSnapshot = { _, _ -> refs },
        recordInjectionResults = { _, results -> recorded += results },
        budget = budget
    )

    @Test
    fun 无引用资料时返回空上下文且不产生提示块() = runBlocking {
        val recorded = mutableListOf<AgentTaskReferenceInjection>()
        val result = assembler(emptyList(), AgentContextBudget.DEFAULT, recorded)
            .assemble(taskId = 900L, sessionId = 800L)
        assertTrue(result.items.isEmpty())
        assertEquals("", result.promptBlock)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun 正常注入并带完整来源标记() = runBlocking {
        val recorded = mutableListOf<AgentTaskReferenceInjection>()
        val result = assembler(
            listOf(reference(1L, 1L)),
            AgentContextBudget(maxReferenceChars = 10_000, perReferenceChars = 2_000),
            recorded
        ).assemble(900L, 800L)

        assertEquals(1, result.items.size)
        assertEquals(AgentContextItemStatus.INJECTED, result.items.single().status)
        assertTrue(result.items.single().injected)
        assertTrue(result.promptBlock.contains("【用户授权参考资料】"))
        assertTrue(result.promptBlock.contains("历史章节梗概"))
        assertTrue(result.promptBlock.contains("来源书籍：某书"))
        assertTrue(result.promptBlock.contains("来源章节：第一章"))
        assertTrue(result.promptBlock.contains("内容完整性：完整"))
        assertFalse(result.promptBlock.contains("【任务目标】"))

        val saved = recorded.single()
        assertEquals(1L, saved.referenceId)
        assertTrue(saved.valid)
        assertTrue(saved.injected)
        assertFalse(saved.truncated)
        assertEquals(500, saved.contentChars)
        assertEquals(1_700_000_000_000L, saved.sourceUpdatedAt)
    }

    @Test
    fun REFERENCE_ONLY只登记不进入上下文() = runBlocking {
        val recorded = mutableListOf<AgentTaskReferenceInjection>()
        val result = assembler(
            listOf(reference(1L, 1L, mode = AgentReferenceMode.REFERENCE_ONLY)),
            AgentContextBudget(maxReferenceChars = 10_000, perReferenceChars = 2_000),
            recorded
        ).assemble(900L, 800L)

        val item = result.items.single()
        assertEquals(AgentContextItemStatus.SKIPPED_REFERENCE_ONLY, item.status)
        assertFalse(item.injected)
        assertFalse(result.promptBlock.contains("甲甲甲甲甲"))
        assertFalse(recorded.single().injected)
        assertTrue(recorded.single().valid)
    }

    @Test
    fun 单条超限时截断并标记完整性() = runBlocking {
        val recorded = mutableListOf<AgentTaskReferenceInjection>()
        val result = assembler(
            listOf(reference(1L, 1L)),
            AgentContextBudget(
                maxReferenceChars = 10_000,
                perReferenceChars = 100,
                minUsefulChars = 10
            ),
            recorded
        ).assemble(900L, 800L)

        val item = result.items.single()
        assertEquals(AgentContextItemStatus.TRUNCATED, item.status)
        assertEquals(100, item.contentChars)
        assertTrue(item.truncated)
        assertTrue(result.promptBlock.contains("内容完整性：不完整"))
        assertTrue(recorded.single().truncated)
    }

    @Test
    fun 总预算不足时后续资料不注入且保留来源信息() = runBlocking {
        val recorded = mutableListOf<AgentTaskReferenceInjection>()
        val result = assembler(
            listOf(reference(1L, 1L), reference(2L, 2L)),
            AgentContextBudget(
                maxReferenceChars = 120,
                perReferenceChars = 100,
                minUsefulChars = 50
            ),
            recorded
        ).assemble(900L, 800L)

        assertEquals(AgentContextItemStatus.TRUNCATED, result.items[0].status)
        assertEquals(AgentContextItemStatus.SKIPPED_BUDGET, result.items[1].status)
        assertFalse(result.items[1].injected)
        assertEquals("第二章", result.items[1].chapterTitle)
        assertTrue(result.promptBlock.contains("未注入"))
        assertEquals(2, recorded.size)
    }

    @Test
    fun 单条来源失效不影响其他资料() = runBlocking {
        val recorded = mutableListOf<AgentTaskReferenceInjection>()
        val result = assembler(
            listOf(reference(1L, 404L), reference(2L, 2L)),
            AgentContextBudget(maxReferenceChars = 10_000, perReferenceChars = 2_000),
            recorded
        ).assemble(900L, 800L)

        assertEquals(AgentContextItemStatus.INVALID, result.items[0].status)
        assertEquals(AgentContextItemStatus.INJECTED, result.items[1].status)
        assertEquals(1, result.injectedCount)
        assertEquals(1, result.invalidCount)
        assertFalse(recorded[0].valid)
        assertTrue(recorded[0].invalidReason.isNotBlank())
        assertTrue(recorded[1].valid)
    }

    @Test
    fun 上下文预算摘要可用于执行提示() = runBlocking {
        val result = assembler(
            listOf(reference(1L, 1L), reference(2L, 404L)),
            AgentContextBudget.DEFAULT,
            mutableListOf()
        ).assemble(900L, 800L)
        assertTrue(result.summaryLine().contains("注入 1 条"))
        assertTrue(result.summaryLine().contains("失效 1 条"))
        assertEquals(AgentContextBudget.DEFAULT.maxReferenceChars, result.budgetChars)
        assertTrue(result.estimatedTokens > 0)
    }
}
