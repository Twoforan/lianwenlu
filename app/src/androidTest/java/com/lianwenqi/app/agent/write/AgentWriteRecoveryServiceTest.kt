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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F7：EXECUTING 写入中断核对；已写入补记成功，未写入标记失败，不自动重放 */
@RunWith(AndroidJUnit4::class)
class AgentWriteRecoveryServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var books: BookRepository
    private lateinit var operations: AgentWriteOperationRepository
    private lateinit var recovery: AgentWriteRecoveryService
    private lateinit var writer: RoomAgentWriteBusinessWriter

    private var bookId = 0L
    private var chapterId = 0L

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        books = BookRepository(db)
        val outlines = OutlineRepository(db)
        writer = RoomAgentWriteBusinessWriter(db, books, outlines)
        operations = AgentWriteOperationRepository(db)
        recovery = AgentWriteRecoveryService(operations, writer)
        bookId = books.createBook(
            title = "恢复书",
            intro = "",
            coverPath = null,
            coverColor = 0,
            volumePrefix = "第%n卷",
            chapterPrefix = "第%n章"
        )
        chapterId = books.createChapter(bookId, null)
        books.updateChapterSynopsis(chapterId, "旧梗概")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun createExecutingOperation(key: String, proposed: String): Long {
        val operation = operations.propose(
            AgentWriteDraft(
                taskId = 1L,
                sessionId = 1L,
                toolName = "update_chapter_synopsis",
                operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
                targetType = AgentWriteTargetType.CHAPTER,
                targetId = chapterId,
                bookId = bookId,
                targetTitleSnapshot = "第一章",
                bookTitleSnapshot = "恢复书",
                riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
                beforeSnapshot = """{"id":$chapterId,"bookId":$bookId,"title":"第一章","summary":"旧梗概","content":"","contentIncluded":false,"contentLength":0,"updatedAt":0}""",
                proposedContent = proposed,
                operationSummary = "修改梗概",
                idempotencyKey = key
            ),
            policy = AgentWritePermissionPolicy.ASK_EVERY_TIME
        )
        operations.approve(operation.id)
        operations.beginExecution(operation.id)
        return operation.id
    }

    @Test
    fun 已写入但状态未更新时补记成功且不重复写入() = runBlocking {
        val operationId = createExecutingOperation("recovery-written", "新梗概")
        books.updateChapterSynopsis(chapterId, "新梗概")

        val report = recovery.reconcileInterrupted(1L)
        assertEquals(1, report.recoveredSucceeded)
        assertEquals(0, report.markedFailed)

        val operation = operations.get(operationId)!!
        assertEquals(AgentWriteOperationStatus.SUCCEEDED, operation.executionStatus)
        assertEquals("新梗概", db.chapterDao().getById(chapterId)!!.summary)
    }

    @Test
    fun 未写入的EXECUTING标记失败且不自动重试() = runBlocking {
        val operationId = createExecutingOperation("recovery-not-written", "新梗概")

        val report = recovery.reconcileInterrupted(1L)
        assertEquals(0, report.recoveredSucceeded)
        assertEquals(1, report.markedFailed)

        val operation = operations.get(operationId)!!
        assertEquals(AgentWriteOperationStatus.FAILED, operation.executionStatus)
        assertTrue(operation.errorMessage.contains("未自动重试"))
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
    }

    @Test
    fun 等待确认的操作不会被恢复核对自动确认() = runBlocking {
        val operation = operations.propose(
            AgentWriteDraft(
                taskId = 1L,
                sessionId = 1L,
                toolName = "update_chapter_synopsis",
                operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
                targetType = AgentWriteTargetType.CHAPTER,
                targetId = chapterId,
                bookId = bookId,
                targetTitleSnapshot = "第一章",
                bookTitleSnapshot = "恢复书",
                riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
                beforeSnapshot = """{"id":$chapterId,"bookId":$bookId,"title":"第一章","summary":"旧梗概","content":"","contentIncluded":false,"contentLength":0,"updatedAt":0}""",
                proposedContent = "待确认梗概",
                operationSummary = "修改梗概",
                idempotencyKey = "recovery-waiting"
            ),
            policy = AgentWritePermissionPolicy.ASK_EVERY_TIME
        )

        val report = recovery.reconcileInterrupted(1L)
        assertEquals(0, report.recoveredSucceeded)
        assertEquals(0, report.markedFailed)

        val current = operations.get(operation.id)!!
        assertEquals(AgentWriteOperationStatus.WAITING_CONFIRMATION, current.executionStatus)
        assertEquals("旧梗概", db.chapterDao().getById(chapterId)!!.summary)
    }
}
