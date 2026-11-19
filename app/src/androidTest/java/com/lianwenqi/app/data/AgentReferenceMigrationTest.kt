package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentReferenceRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskReferenceRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentTaskReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F5 迁移验证：v28 真实旧库升级到 v29。
 *
 * 只新增 agent_task_reference；F1 会话 / 消息 / 引用与 F2 任务数据必须完整保留。
 */
@RunWith(AndroidJUnit4::class)
class AgentReferenceMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-v28-v29.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        AgentMigrationFixture.createV28Database(context, dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun v28升级到v29后新表存在且旧Agent数据完整() = runBlocking {
        val db = openV29()
        try {
            assertEquals(32, db.openHelper.writableDatabase.version)
            assertTrue(tableExists(db, "agent_task_reference"))
            assertTrue(indexExists(db, "index_agent_task_reference_taskId_sourceType_sourceId"))
            assertTrue(indexExists(db, "index_agent_task_reference_taskId"))
            assertTrue(indexExists(db, "index_agent_task_reference_sessionId"))

            // F1 / F2 旧数据完整
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
    fun 升级后任务快照可读写且删除会话会清理快照() = runBlocking {
        val db = openV29()
        try {
            val sessions = AgentSessionRepository(db)
            val references = AgentReferenceRepository(db)
            val snapshot = AgentTaskReferenceRepository(db)
            val tasks = AgentTaskRepository(db)

            references.addReference(
                sessionId = 1L,
                sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
                sourceId = 1L,
                bookId = 1L,
                chapterId = 1L,
                titleSnapshot = "第一章"
            )
            val frozen = snapshot.freezeSnapshot(taskId = 1L, sessionId = 1L)
            assertEquals(1, frozen.size)
            assertEquals(AgentReferenceSourceType.CHAPTER_SYNOPSIS, frozen.single().sourceType)

            snapshot.recordInjectionResults(
                taskId = 1L,
                results = listOf(
                    com.lianwenqi.app.agent.data.AgentTaskReferenceInjection(
                        referenceId = frozen.single().id,
                        valid = true,
                        injected = true,
                        contentChars = 12,
                        contentTotalChars = 12
                    )
                )
            )
            val updated = snapshot.referencesOfTask(1L).single()
            assertTrue(updated.validated)
            assertTrue(updated.injected)
            assertEquals(12, updated.contentChars)

            // 唯一索引兜底：同一任务同一来源不会重复
            db.agentTaskDao().insertTaskReference(
                AgentTaskReference(
                    taskId = 1L,
                    sessionId = 1L,
                    sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
                    sourceId = 1L,
                    referenceMode = "CONTEXT",
                    selectedAt = 1L
                )
            )
            assertEquals(1, snapshot.referencesOfTask(1L).size)

            sessions.deleteSession(1L)
            assertTrue(snapshot.referencesOfTask(1L).isEmpty())
            assertTrue(tasks.tasksOfSession(1L).isEmpty())
            assertEquals("旧书", db.bookDao().getById(1)!!.title)
        } finally {
            db.close()
        }
    }

    private fun openV29(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_28_29,
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
