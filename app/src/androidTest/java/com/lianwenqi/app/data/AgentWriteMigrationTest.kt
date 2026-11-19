package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentWriteDraft
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionPolicy
import com.lianwenqi.app.agent.data.entity.AgentWriteRiskLevel
import com.lianwenqi.app.agent.data.entity.AgentWriteTargetType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F6 迁移：v29 真实旧库升级到 v30，只新增 agent_write_operation */
@RunWith(AndroidJUnit4::class)
class AgentWriteMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-v29-v30.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        AgentMigrationFixture.createV29Database(context, dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun v29升级到v30后写入表存在且旧数据完整() = runBlocking {
        val db = openV30()
        try {
            assertEquals(32, db.openHelper.writableDatabase.version)
            assertTrue(tableExists(db, "agent_write_operation"))
            assertTrue(indexExists(db, "index_agent_write_operation_idempotencyKey"))
            assertTrue(indexExists(db, "index_agent_write_operation_taskId_executionStatus"))
            assertTrue(indexExists(db, "index_agent_write_operation_taskId"))

            assertEquals("旧 Agent 会话", db.agentDao().getSession(1)!!.title)
            assertEquals("旧 Agent 消息", db.agentDao().getMessages(1).single().content)
            assertEquals(1, db.agentDao().references(1).size)
            assertEquals("旧任务", db.agentTaskDao().getTask(1)!!.title)
            assertEquals("旧书", db.bookDao().getById(1)!!.title)
            assertEquals("旧正文", db.chapterDao().getById(1)!!.content)
            assertEquals("旧消息", db.chatDao().getMessages(1).single().content)
        } finally {
            db.close()
        }
    }

    @Test
    fun 升级后可创建提议并执行状态迁移且删除会话清理记录() = runBlocking {
        val db = openV30()
        try {
            val operations = AgentWriteOperationRepository(db)
            val operation = operations.propose(
                AgentWriteDraft(
                    taskId = 1L,
                    sessionId = 1L,
                    toolName = "update_chapter_synopsis",
                    operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
                    targetType = AgentWriteTargetType.CHAPTER,
                    targetId = 1L,
                    bookId = 1L,
                    riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
                    proposedContent = "新梗概",
                    operationSummary = "修改梗概",
                    idempotencyKey = "migration-write-1"
                ),
                policy = AgentWritePermissionPolicy.ASK_EVERY_TIME
            )
            assertEquals(AgentWriteOperationStatus.WAITING_CONFIRMATION, operation.executionStatus)
            assertFalse(operation.executionStatus == AgentWriteOperationStatus.SUCCEEDED)

            val approved = operations.approve(operation.id)
            assertEquals(AgentWriteOperationStatus.APPROVED, approved.executionStatus)
            val started = operations.beginExecution(operation.id)
            assertTrue(started.started)
            assertEquals(AgentWriteOperationStatus.EXECUTING, started.operation.executionStatus)
            val succeeded = operations.markSucceeded(
                operation.id,
                afterSnapshot = """{"summary":"新梗概"}"""
            )
            assertEquals(AgentWriteOperationStatus.SUCCEEDED, succeeded.executionStatus)

            // 唯一幂等键：重复提议返回原记录
            val duplicate = operations.propose(
                AgentWriteDraft(
                    taskId = 1L,
                    sessionId = 1L,
                    toolName = "update_chapter_synopsis",
                    operationType = AgentWriteOperationType.UPDATE_CHAPTER_SYNOPSIS,
                    targetType = AgentWriteTargetType.CHAPTER,
                    targetId = 1L,
                    bookId = 1L,
                    riskLevel = AgentWriteRiskLevel.NORMAL_WRITE,
                    proposedContent = "新梗概",
                    operationSummary = "修改梗概",
                    idempotencyKey = "migration-write-1"
                ),
                policy = AgentWritePermissionPolicy.ASK_EVERY_TIME
            )
            assertEquals(operation.id, duplicate.id)

            AgentSessionRepository(db).deleteSession(1L)
            assertTrue(operations.ofTask(1L).isEmpty())
            assertEquals("旧书", db.bookDao().getById(1)!!.title)
            assertEquals("旧正文", db.chapterDao().getById(1)!!.content)
        } finally {
            db.close()
        }
    }

    private fun openV30(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_29_30,
                AppDatabase.MIGRATION_30_31,
                AppDatabase.MIGRATION_31_32
            )
            .allowMainThreadQueries()
            .build()

    private fun tableExists(db: AppDatabase, name: String): Boolean =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(name)
        ).use { it.moveToFirst() }

    private fun indexExists(db: AppDatabase, name: String): Boolean =
        db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?",
            arrayOf(name)
        ).use { it.moveToFirst() }
}
