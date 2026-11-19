package com.lianwenqi.app.agent.context

import com.lianwenqi.app.agent.data.dao.AgentReferenceChapterMeta
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.agent.planning.AgentPlanParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F5 历史资料解析：来源存在性、跨书、预算、消息类型与当前任务隔离 */
class AgentReferenceResolverTest {

    private val chapter = AgentReferenceChapterMeta(
        id = 11L,
        bookId = 1L,
        volumeId = 2L,
        title = "第三章 夜袭",
        summary = "主角夜袭敌营，发现内奸。".repeat(20),
        contentLength = 10_000,
        updatedAt = 1_700_000_000_000L
    )
    private val content = "正文".repeat(5_000)

    private val session = AgentSession(
        id = 100L,
        title = "旧任务会话",
        bookId = 1L
    )
    private val task = AgentTask(
        id = 200L,
        sessionId = 100L,
        title = "检查第三卷冲突",
        userGoal = "找出第三卷的剧情冲突",
        status = AgentTaskStatus.COMPLETED,
        planJson = """
            {"schemaVersion":1,"taskTitle":"检查第三卷冲突","goalSummary":"检查冲突",
             "steps":[{"stepId":"s1","title":"读取大纲","description":"读取当前书籍大纲",
             "capabilityKey":"READ_OUTLINE","expectedOutput":"大纲","dependencies":[],"riskLevel":"LOW"}]}
        """.trimIndent()
    )
    private val message = AgentMessage(
        id = 300L,
        sessionId = 100L,
        taskId = 200L,
        role = AgentMessageRole.ASSISTANT,
        kind = AgentMessageKind.FINAL_ANSWER,
        content = "历史最终回答正文",
        thinkingSummary = "隐藏思考摘要",
        sequence = 3L,
        promptLog = "SHOULD_NOT_LEAK"
    )
    private val notice = AgentMessage(
        id = 301L,
        sessionId = 100L,
        taskId = 200L,
        role = AgentMessageRole.ASSISTANT,
        kind = AgentMessageKind.NOTICE,
        content = "系统提示不应被引用",
        sequence = 4L
    )
    private val tool = AgentToolExecution(
        id = 400L,
        taskId = 200L,
        toolName = "get_outline",
        toolDisplayName = "读取大纲",
        status = AgentToolStatus.SUCCEEDED,
        resultJson = """{"items":["冲突点"]}"""
    )
    private val failedTool = tool.copy(id = 401L, status = AgentToolStatus.FAILED)

    private fun resolver() = AgentReferenceResolver(
        chapterMetaOf = { id -> chapter.takeIf { it.id == id } },
        chapterContentOf = { id, offset, length ->
            if (id == chapter.id) content.substring(offset, (offset + length).coerceAtMost(content.length))
            else null
        },
        bookTitleOf = { id -> if (id == 1L) "某书" else null },
        volumeTitleOf = { id -> if (id == 2L) "第一卷" else null },
        agentTaskOf = { id -> task.takeIf { it.id == id } },
        agentSessionOf = { id -> session.takeIf { it.id == id } },
        agentMessageOf = { id ->
            when (id) {
                message.id -> message
                notice.id -> notice
                else -> null
            }
        },
        toolExecutionOf = { id ->
            when (id) {
                tool.id -> tool
                failedTool.id -> failedTool
                else -> null
            }
        },
        finalAnswerOf = { id -> if (id == task.id) "历史任务最终结果" else null },
        planParser = AgentPlanParser()
    )

    private fun target(
        type: String,
        sourceId: Long,
        bookId: Long? = 1L,
        chapterId: Long? = null
    ) = AgentReferenceTarget(
        referenceId = 1L,
        sourceType = type,
        sourceId = sourceId,
        bookId = bookId,
        chapterId = chapterId,
        titleSnapshot = "快照标题",
        referenceMode = AgentReferenceMode.CONTEXT
    )

    @Test
    fun 读取章节梗概按预算截断并保留来源信息() = runBlocking {
        val resolved = resolver().resolve(
            target(AgentReferenceSourceType.CHAPTER_SYNOPSIS, 11L, chapterId = 11L),
            contentMaxChars = 100
        )
        assertTrue(resolved.valid)
        assertEquals("第三章 夜袭", resolved.chapterTitle)
        assertEquals("某书", resolved.bookTitle)
        assertEquals("第一卷", resolved.volumeTitle)
        assertEquals(100, resolved.content.length)
        assertTrue(resolved.truncated)
        assertEquals(chapter.summary.length, resolved.contentTotalChars)
    }

    @Test
    fun 读取正文分页不加载整章并标记截断() = runBlocking {
        val resolved = resolver().resolve(
            target(AgentReferenceSourceType.CHAPTER_CONTENT, 11L, chapterId = 11L),
            contentMaxChars = 300
        )
        assertTrue(resolved.valid)
        assertEquals(300, resolved.content.length)
        assertTrue(resolved.truncated)
        assertEquals(10_000, resolved.contentTotalChars)
        assertEquals(0, resolved.contentStart)
    }

    @Test
    fun 跨书引用书籍不一致时拒绝() = runBlocking {
        val resolved = resolver().resolve(
            target(
                AgentReferenceSourceType.CHAPTER_SYNOPSIS,
                sourceId = 11L,
                bookId = 99L,
                chapterId = 11L
            ),
            contentMaxChars = 100
        )
        assertFalse(resolved.valid)
        assertTrue(resolved.invalidReason.contains("来源书籍"))
    }

    @Test
    fun 读取历史任务计划与最终结果() = runBlocking {
        val plan = resolver().resolve(
            target(AgentReferenceSourceType.AGENT_TASK_PLAN, 200L),
            contentMaxChars = 2_000
        )
        assertTrue(plan.valid)
        assertTrue(plan.content.contains("找出第三卷的剧情冲突"))
        assertTrue(plan.content.contains("读取大纲"))
        assertFalse(plan.content.contains("历史任务最终结果"))

        val result = resolver().resolve(
            target(AgentReferenceSourceType.AGENT_TASK_RESULT, 200L),
            contentMaxChars = 2_000
        )
        assertTrue(result.valid)
        assertEquals("历史任务最终结果", result.content)
        assertEquals("任务结果：检查第三卷冲突", result.title)
    }

    @Test
    fun 历史消息只读正文不泄露提示词日志与思考摘要() = runBlocking {
        val resolved = resolver().resolve(
            target(AgentReferenceSourceType.AGENT_MESSAGE, 300L),
            contentMaxChars = 1_000
        )
        assertTrue(resolved.valid)
        assertEquals("历史最终回答正文", resolved.content)
        assertFalse(resolved.content.contains("SHOULD_NOT_LEAK"))
        assertFalse(resolved.content.contains("隐藏思考摘要"))
    }

    @Test
    fun 系统提示类消息不提供引用入口() = runBlocking {
        val resolved = resolver().resolve(
            target(AgentReferenceSourceType.AGENT_MESSAGE, 301L),
            contentMaxChars = 1_000
        )
        assertFalse(resolved.valid)
        assertTrue(resolved.invalidReason.contains("消息类型"))
    }

    @Test
    fun 历史工具结果只有成功记录可引用() = runBlocking {
        val ok = resolver().resolve(
            target(AgentReferenceSourceType.AGENT_TOOL_RESULT, 400L),
            contentMaxChars = 1_000
        )
        assertTrue(ok.valid)
        assertTrue(ok.title.contains("历史工具结果"))
        assertTrue(ok.content.contains("冲突点"))

        val failed = resolver().resolve(
            target(AgentReferenceSourceType.AGENT_TOOL_RESULT, 401L),
            contentMaxChars = 1_000
        )
        assertFalse(failed.valid)
        assertTrue(failed.invalidReason.contains("只有成功结果"))
    }

    @Test
    fun 不能引用当前正在执行的任务资料() = runBlocking {
        val resolved = resolver().resolve(
            target(AgentReferenceSourceType.AGENT_TASK_PLAN, 200L),
            contentMaxChars = 1_000,
            excludedTaskId = 200L
        )
        assertFalse(resolved.valid)
        assertTrue(resolved.invalidReason.contains("当前正在执行"))
    }

    @Test
    fun 整段历史会话引用被明确拒绝() = runBlocking {
        val resolved = resolver().resolve(
            target(AgentReferenceSourceType.AGENT_SESSION, 100L),
            contentMaxChars = 1_000
        )
        assertFalse(resolved.valid)
        assertTrue(resolved.invalidReason.contains("不支持整段"))
    }

    @Test
    fun 来源删除后返回失效而不是静默替换() = runBlocking {
        val resolved = resolver().resolve(
            target(AgentReferenceSourceType.CHAPTER_SYNOPSIS, 999L, chapterId = 999L),
            contentMaxChars = 1_000
        )
        assertFalse(resolved.valid)
        assertTrue(resolved.invalidReason.contains("不存在"))
    }
}
