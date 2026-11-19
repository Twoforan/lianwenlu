package com.lianwenqi.app.agent.write

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentWriteDraft
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.agent.data.entity.AgentWriteTargetType
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.OutlineRepository
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F6 写入执行服务：提议不落库、确认后写入、冲突、撤销、失败与并发 */
@RunWith(AndroidJUnit4::class)
class AgentWriteExecutionServiceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var books: BookRepository
    private lateinit var outlines: OutlineRepository
    private lateinit var operations: AgentWriteOperationRepository
    private lateinit var writer: RoomAgentWriteBusinessWriter
    private var policy = AgentWritePermissionPolicy.ASK_EVERY_TIME
    private var bookId = 0L
    private var chapterId = 0L
    private var outlineId = 0L
    private var taskId = 0L
    private var sessionId = 0L

    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        books = BookRepository(db)
        outlines = OutlineRepository(db)
        operations = AgentWriteOperationRepository(db)
        writer = RoomAgentWriteBusinessWriter(db, books, outlines)

        bookId = books.createBook(
            title = "测试书",
            intro = "",
            coverPath = null,
            coverColor = 0,
            volumePrefix = "第%n卷",
            chapterPrefix = "第%n章"
        )
        chapterId = books.createChapter(bookId, null)
        books.updateChapterSynopsis(chapterId, "旧梗概")
        books.updateChapterContent(chapterId, "旧正文")
        outlineId = outlines.insertOutline(
            OutlineItem(
                bookId = bookId,
                category = "CHARACTER",
                title = "张三",
                content = "旧人物设定",
                sortOrder = 0
            )
        )
        sessionId = db.agentDao().insertSession(
            com.lianwenqi.app.agent.data.entity.AgentSession(
                title = "写入测试",
                bookId = bookId
            )
        )
        taskId = db.agentTaskDao().insertTask(
            com.lianwenqi.app.agent.data.entity.AgentTask(
                sessionId = sessionId,
                title = "写入测试任务",
                userGoal = "修改资料"
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun service(
        businessWriter: AgentWriteBusinessWriter = writer
    ) = AgentWriteExecutionService(
        operations = operations,
        writer = businessWriter,
        policyProvider = { policy },
        transactionRunner = RoomAgentWriteTransactionRunner(db)
    )

    private fun synopsisDraft(key: String = "synopsis-1", summary: String = "新梗概") =
        AgentWriteDraft(
            taskId = taskId,
            sessionId = sessionId,
            nodeId = 1L,
            toolName = "update_chapter_synopsis",
            operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
            targetType = AgentWriteTargetType.CHAPTER,
            targetId = chapterId,
            bookId = bookId,
            targetTitleSnapshot = "第一章",
            bookTitleSnapshot = "测试书",
            riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
            beforeSnapshot = json.encodeToString(
                AgentChapterSnapshot.serializer(),
                AgentChapterSnapshot(
                    id = chapterId,
                    bookId = bookId,
                    title = "第一章",
                    summary = "旧梗概"
                )
            ),
            proposedContent = summary,
            operationSummary = "修改梗概",
            idempotencyKey = key
        )

    private fun contentDraft(key: String = "content-1", content: String = "新正文") =
        AgentWriteDraft(
            taskId = taskId,
            sessionId = sessionId,
            nodeId = 1L,
            toolName = "update_chapter_content",
            operationType = AgentWriteOperationType.UPDATE_CHAPTER_CONTENT,
            targetType = AgentWriteTargetType.CHAPTER,
            targetId = chapterId,
            bookId = bookId,
            targetTitleSnapshot = "第一章",
            bookTitleSnapshot = "测试书",
            riskLevel = AgentWriteRiskLevel.HIGH_RISK_WRITE,
            beforeSnapshot = json.encodeToString(
                AgentChapterSnapshot.serializer(),
                AgentChapterSnapshot(
                    id = chapterId,
                    bookId = bookId,
                    title = "第一章",
                    summary = "旧梗概",
                    content = "旧正文",
                    contentIncluded = true,
                    contentLength = 3
                )
            ),
            proposedContent = content,
            operationSummary = "覆盖正文",
            idempotencyKey = key
        )

    private fun outlineCreateDraft(key: String = "outline-create") = AgentWriteDraft(
        taskId = taskId,
        sessionId = sessionId,
        toolName = "create_outline_node",
        operationType = AgentWriteOperationType.CREATE_OUTLINE_NODE,
        targetType = AgentWriteTargetType.OUTLINE,
        targetId = 0L,
        bookId = bookId,
        targetTitleSnapshot = "李四",
        bookTitleSnapshot = "测试书",
        riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
        argumentsJson = json.encodeToString(
            AgentOutlineCreateArgs.serializer(),
            AgentOutlineCreateArgs("CHARACTER", "", "李四", "新人物设定")
        ),
        beforeSnapshot = "{}",
        proposedContent = "新人物设定",
        operationSummary = "创建大纲节点",
        idempotencyKey = key
    )

    private fun outlineUpdateDraft(key: String = "outline-update") = AgentWriteDraft(
        taskId = taskId,
        sessionId = sessionId,
        toolName = "update_outline_node",
        operationType = AgentWriteOperationType.UPDATE_OUTLINE_NODE,
        targetType = AgentWriteTargetType.OUTLINE,
        targetId = outlineId,
        bookId = bookId,
        targetTitleSnapshot = "张三",
        bookTitleSnapshot = "测试书",
        riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
        argumentsJson = json.encodeToString(
            AgentOutlineUpdateArgs.serializer(),
            AgentOutlineUpdateArgs(title = "张三丰", content = "新人物设定")
        ),
        beforeSnapshot = json.encodeToString(
            AgentOutlineSnapshot.serializer(),
            AgentOutlineSnapshot(
                id = outlineId,
                bookId = bookId,
                category = "CHARACTER",
                tag = "",
                title = "张三",
                content = "旧人物设定",
                sortOrder = 0,
                pinned = false,
                updatable = false,
                updatedAt = 0
            )
        ),
        proposedContent = "新人物设定",
        operationSummary = "修改大纲节点",
        idempotencyKey = key
    )

    @Test
    fun 提议阶段不修改数据且确认后才写入并可撤销() = runBlocking {
        val proposal = service().propose(synopsisDraft())
        assertTrue(proposal.requiresConfirmation)
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)

        val executed = service().approveAndExecute(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, executed.executionStatus)
        assertEquals("新梗概", db.chapterDao().getById(chapterId)!!.summary)

        val rolledBack = service().rollback(executed.id)
        assertEquals(AgentWriteOperationStatus.ROLLED_BACK, rolledBack.executionStatus)
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
    }

    @Test
    fun 高风险正文即使自动策略也必须逐次确认() = runBlocking {
        policy = AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
        val proposal = service().propose(contentDraft())
        assertTrue(proposal.requiresConfirmation)
        assertEquals("旧正文", db.chapterDao().getById(chapterId)!!.content)

        val executed = service().approveAndExecute(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, executed.executionStatus)
        assertEquals("新正文", db.chapterDao().getById(chapterId)!!.content)

        service().rollback(executed.id)
        assertEquals("旧正文", db.chapterDao().getById(chapterId)!!.content)
    }

    @Test
    fun 一般写入自动执行时直接成功() = runBlocking {
        policy = AgentWritePermissionPolicy.AUTO_ALLOW_NORMAL
        val result = service().propose(synopsisDraft())
        assertFalse(result.requiresConfirmation)
        assertTrue(result.autoExecuted)
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, result.operation.executionStatus)
        assertEquals("新梗概", db.chapterDao().getById(chapterId)!!.summary)
    }

    @Test
    fun 预览后目标变化时拒绝覆盖并标记冲突() = runBlocking {
        val proposal = service().propose(synopsisDraft())
        books.updateChapterSynopsis(chapterId, "外部修改")

        val executed = service().approveAndExecute(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.CONFLICT, executed.executionStatus)
        assertEquals("外部修改", db.chapterDao().getById(chapterId)!!.summary)
    }

    @Test
    fun 用户拒绝后不再写入且后续批准无效() = runBlocking {
        val proposal = service().propose(synopsisDraft())
        val rejected = service().reject(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.REJECTED, rejected.executionStatus)

        val afterApprove = service().approveAndExecute(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.REJECTED, afterApprove.executionStatus)
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
    }

    @Test
    fun 新建大纲可撤销且撤销后节点被删除() = runBlocking {
        val proposal = service().propose(outlineCreateDraft())
        val executed = service().approveAndExecute(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, executed.executionStatus)
        val createdId = executed.createdTargetId
        assertNotNull(createdId)
        assertEquals("新人物设定", outlines.getById(createdId!!)!!.content)

        val rolledBack = service().rollback(executed.id)
        assertEquals(AgentWriteOperationStatus.ROLLED_BACK, rolledBack.executionStatus)
        assertEquals(null, outlines.getById(createdId))
    }

    @Test
    fun 修改大纲可撤销且外部修改后拒绝撤销() = runBlocking {
        val proposal = service().propose(outlineUpdateDraft())
        val executed = service().approveAndExecute(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, executed.executionStatus)
        assertEquals("张三丰", outlines.getById(outlineId)!!.title)

        // 外部修改后再撤销：拒绝覆盖新内容
        outlines.updateOutline(
            outlines.getById(outlineId)!!.copy(title = "外部改名")
        )
        val conflicted = service().rollback(executed.id)
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, conflicted.executionStatus)
        assertTrue(conflicted.rollbackMessage.contains("拒绝自动撤销"))
        assertEquals("外部改名", outlines.getById(outlineId)!!.title)
    }

    @Test
    fun 同一幂等键重复提议只保留一条且已成功不重复写入() = runBlocking {
        val first = service().propose(synopsisDraft("same-key"))
        val second = service().propose(synopsisDraft("same-key"))
        assertEquals(first.operation.id, second.operation.id)
        assertEquals(1, operations.ofTask(taskId).size)

        service().approveAndExecute(first.operation.id)
        val third = service().propose(synopsisDraft("same-key"))
        assertEquals(first.operation.id, third.operation.id)
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, third.operation.executionStatus)
        assertEquals(1, operations.ofTask(taskId).size)
    }

    @Test
    fun 并发批准只执行一次写入() = runBlocking {
        var writes = 0
        val countingWriter = object : AgentWriteBusinessWriter by writer {
            override suspend fun updateChapterSynopsis(chapterId: Long, summary: String) {
                writes++
                writer.updateChapterSynopsis(chapterId, summary)
            }
        }
        val testService = service(countingWriter)
        val proposal = testService.propose(synopsisDraft())
        val results = listOf(
            async { testService.approveAndExecute(proposal.operation.id) },
            async { testService.approveAndExecute(proposal.operation.id) }
        ).awaitAll()
        assertTrue(results.all { it.executionStatus == AgentWriteOperationStatus.SUCCEEDED })
        assertEquals(1, writes)
    }

    @Test
    fun 重复撤销保持已撤销状态且不重复写() = runBlocking {
        val proposal = service().propose(synopsisDraft())
        val executed = service().approveAndExecute(proposal.operation.id)
        val first = service().rollback(executed.id)
        assertEquals(AgentWriteOperationStatus.ROLLED_BACK, first.executionStatus)
        val second = service().rollback(executed.id)
        assertEquals(AgentWriteOperationStatus.ROLLED_BACK, second.executionStatus)
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
    }

    @Test
    fun 标记为不可撤销的操作会明确拒绝撤销() = runBlocking {
        val draft = synopsisDraft("not-reversible").copy(reversible = false)
        val proposal = service().propose(draft)
        val executed = service().approveAndExecute(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, executed.executionStatus)
        val result = runCatching { service().rollback(executed.id) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("不支持撤销"))
    }

    @Test
    fun 写入异常时事务回滚并标记失败() = runBlocking {
        val failingWriter = object : AgentWriteBusinessWriter by writer {
            override suspend fun updateChapterSynopsis(chapterId: Long, summary: String) {
                throw IllegalStateException("模拟写入失败")
            }
        }
        val testService = service(failingWriter)
        val proposal = testService.propose(synopsisDraft())
        val executed = testService.approveAndExecute(proposal.operation.id)
        assertEquals(AgentWriteOperationStatus.FAILED, executed.executionStatus)
        assertTrue(executed.errorMessage.contains("模拟写入失败"))
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
    }
}
