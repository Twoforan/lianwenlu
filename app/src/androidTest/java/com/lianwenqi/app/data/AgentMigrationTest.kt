package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.AgentReferenceRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F1 回归：v26 → v27 → v28 迁移。
 *
 * 复用 [AgentMigrationFixture] 的真实旧库；v27→v28 的专项验证见
 * [AgentTaskMigrationTest]。
 */
@RunWith(AndroidJUnit4::class)
class AgentMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-v26-v29.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        AgentMigrationFixture.createV26Database(context, dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun v26升级到v29后Agent表与索引存在且旧数据保留() {
        val db = openV29()
        try {
            assertEquals(32, db.openHelper.writableDatabase.version)

            assertTrue(tableExists(db, "agent_session"))
            assertTrue(tableExists(db, "agent_message"))
            assertTrue(tableExists(db, "agent_session_reference"))
            assertTrue(indexExists(db, "index_agent_session_bookId"))
            assertTrue(indexExists(db, "index_agent_session_updatedAt"))
            assertTrue(indexExists(db, "index_agent_message_sessionId_sequence"))
            assertTrue(indexExists(db, "index_agent_message_taskId"))
            assertTrue(indexExists(db, "index_agent_session_reference_sessionId_sourceType_sourceId"))
            assertTrue(indexExists(db, "index_agent_session_reference_sessionId_enabled"))

            runBlocking {
                assertEquals("旧书", db.bookDao().getById(1)!!.title)
                assertEquals("第一卷", db.volumeDao().getById(1)!!.title)
                val chapter = db.chapterDao().getById(1)!!
                assertEquals("第一章", chapter.title)
                assertEquals("旧梗概", chapter.summary)
                assertEquals("旧正文", chapter.content)
                assertEquals("旧人设", db.outlineDao().getById(1)!!.title)

                val session = db.chatDao().getSession(1)
                assertNotNull(session)
                assertEquals("旧会话", session!!.title)
                assertEquals("旧消息", db.chatDao().getMessages(1).single().content)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun 升级后可创建读取删除Agent会话且不影响普通聊天数据() = runBlocking {
        val db = openV29()
        try {
            val sessions = AgentSessionRepository(db)
            val references = AgentReferenceRepository(db)

            val sessionId = sessions.createSession(bookId = 1L)
            val loaded = sessions.getSession(sessionId)!!
            assertEquals("新任务", loaded.title)
            assertEquals(1L, loaded.bookId)

            sessions.addMessage(
                sessionId = sessionId,
                role = AgentMessageRole.USER,
                kind = AgentMessageKind.USER_INPUT,
                content = "帮我检查第三卷大纲"
            )
            assertEquals(
                listOf("帮我检查第三卷大纲"),
                sessions.getMessages(sessionId).map { it.content }
            )
            assertEquals(0L, sessions.getMessages(sessionId).single().sequence)

            references.addReference(
                sessionId = sessionId,
                sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
                sourceId = 1L,
                bookId = 1L,
                chapterId = 1L,
                titleSnapshot = "第一章"
            )
            assertEquals(1, references.references(sessionId).size)

            sessions.deleteSession(sessionId)
            assertNull(sessions.getSession(sessionId))
            assertTrue(sessions.getMessages(sessionId).isEmpty())
            assertTrue(references.references(sessionId).isEmpty())

            assertEquals("旧会话", db.chatDao().getSession(1)!!.title)
            assertEquals("旧消息", db.chatDao().getMessages(1).single().content)
            assertEquals("旧书", db.bookDao().getById(1)!!.title)
        } finally {
            db.close()
        }
    }

    private fun openV29(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_26_27,
                AppDatabase.MIGRATION_27_28,
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
