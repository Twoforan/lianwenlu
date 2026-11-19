package com.lianwenqi.app.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentReferenceLimits
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** F5 会话引用与任务级引用快照的 Repository 验收 */
@RunWith(AndroidJUnit4::class)
class AgentReferenceRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var references: AgentReferenceRepository
    private lateinit var snapshots: AgentTaskReferenceRepository
    private lateinit var tasks: AgentTaskRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = AgentSessionRepository(db)
        references = AgentReferenceRepository(db)
        snapshots = AgentTaskReferenceRepository(db)
        tasks = AgentTaskRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun addSynopsis(sessionId: Long, chapterId: Long): Long =
        references.addReference(
            sessionId = sessionId,
            sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
            sourceId = chapterId,
            bookId = 1L,
            chapterId = chapterId,
            titleSnapshot = "章节 $chapterId"
        )

    @Test
    fun 会话引用上限为50且重复引用不占新名额() = runBlocking {
        val sessionId = sessions.createSession(bookId = 1L)
        (1L..50L).forEach { addSynopsis(sessionId, it) }
        assertEquals(50, references.references(sessionId).size)

        try {
            addSynopsis(sessionId, 51L)
            fail("超过会话引用上限时应拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("上限"))
        }

        // 重复引用复用原行，不新增名额
        addSynopsis(sessionId, 1L)
        assertEquals(50, references.references(sessionId).size)
    }

    @Test
    fun 历史消息引用上限为20() = runBlocking {
        val sessionId = sessions.createSession()
        (1L..20L).forEach { messageId ->
            references.addReference(
                sessionId = sessionId,
                sourceType = AgentReferenceSourceType.AGENT_MESSAGE,
                sourceId = messageId,
                titleSnapshot = "消息 $messageId"
            )
        }
        try {
            references.addReference(
                sessionId = sessionId,
                sourceType = AgentReferenceSourceType.AGENT_MESSAGE,
                sourceId = 21L,
                titleSnapshot = "消息 21"
            )
            fail("超过历史消息引用上限时应拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("消息引用"))
        }
        // 重复引用旧消息不占新名额
        references.addReference(
            sessionId = sessionId,
            sourceType = AgentReferenceSourceType.AGENT_MESSAGE,
            sourceId = 1L,
            titleSnapshot = "消息 1"
        )
        assertEquals(20, references.references(sessionId).size)
    }

    @Test
    fun 整段历史会话引用被拒绝() = runBlocking {
        val sessionId = sessions.createSession()
        try {
            references.addReference(
                sessionId = sessionId,
                sourceType = AgentReferenceSourceType.AGENT_SESSION,
                sourceId = 1L
            )
            fail("整段会话引用不应被允许")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不支持"))
        }
    }

    @Test
    fun 引用模式可切换且不影响来源数据() = runBlocking {
        val sessionId = sessions.createSession(bookId = 1L)
        val referenceId = addSynopsis(sessionId, 1L)
        references.setReferenceMode(referenceId, AgentReferenceMode.REFERENCE_ONLY)
        assertEquals(
            AgentReferenceMode.REFERENCE_ONLY,
            references.references(sessionId).single().referenceMode
        )
        references.deleteReference(referenceId)
        assertTrue(references.references(sessionId).isEmpty())
        // 只是删引用行，不触碰章节本身（本测试没有创建章节，能查引用已删即可）
    }

    @Test
    fun 任务快照最多20条且CONTEXT优先() = runBlocking {
        val sessionId = sessions.createSession(bookId = 1L)
        val taskId = tasks.createTask(sessionId, "检查资料")
        (1L..15L).forEach { addSynopsis(sessionId, it) }
        (16L..25L).forEach { chapterId ->
            references.addReference(
                sessionId = sessionId,
                sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
                sourceId = chapterId,
                bookId = 1L,
                chapterId = chapterId,
                titleSnapshot = "章节 $chapterId",
                referenceMode = AgentReferenceMode.REFERENCE_ONLY
            )
        }
        val frozen = snapshots.freezeSnapshot(taskId, sessionId)
        assertEquals(AgentReferenceLimits.MAX_TASK_REFERENCES, frozen.size)
        assertEquals(15, frozen.count { it.referenceMode == AgentReferenceMode.CONTEXT })
        assertTrue(
            frozen.take(15).all { it.referenceMode == AgentReferenceMode.CONTEXT }
        )
    }

    @Test
    fun 会话引用变化不影响已冻结的任务快照() = runBlocking {
        val sessionId = sessions.createSession(bookId = 1L)
        val taskId = tasks.createTask(sessionId, "检查资料")
        addSynopsis(sessionId, 1L)
        val first = snapshots.freezeSnapshot(taskId, sessionId)
        assertEquals(1, first.size)
        assertEquals(1L, first.single().sourceId)

        // 删除旧引用、增加新引用
        references.deleteForSession(sessionId)
        addSynopsis(sessionId, 2L)

        val again = snapshots.freezeSnapshot(taskId, sessionId)
        assertEquals(first.map { it.id }, again.map { it.id })
        assertEquals(1L, again.single().sourceId)
        assertEquals(1, references.references(sessionId).size)
    }

    @Test
    fun 注入结果落库且来源更新时间被记录() = runBlocking {
        val sessionId = sessions.createSession(bookId = 1L)
        val taskId = tasks.createTask(sessionId, "检查资料")
        addSynopsis(sessionId, 1L)
        val frozen = snapshots.freezeSnapshot(taskId, sessionId).single()
        assertFalse(frozen.validated)

        snapshots.recordInjectionResults(
            taskId = taskId,
            results = listOf(
                AgentTaskReferenceInjection(
                    referenceId = frozen.id,
                    valid = true,
                    injected = true,
                    truncated = true,
                    contentStart = 0,
                    contentChars = 300,
                    contentTotalChars = 900,
                    estimatedTokens = 300,
                    sourceUpdatedAt = 123L
                )
            )
        )
        val updated = snapshots.referencesOfTask(taskId).single()
        assertTrue(updated.validated)
        assertTrue(updated.valid)
        assertTrue(updated.injected)
        assertTrue(updated.truncated)
        assertEquals(300, updated.contentChars)
        assertEquals(900, updated.contentTotalChars)
        assertEquals(123L, updated.sourceUpdatedAt)
    }

    @Test
    fun 删除会话清理引用与快照但不删除来源数据() = runBlocking {
        val bookId = db.bookDao().insert(
            com.lianwenqi.app.data.entity.Book(title = "来源书")
        )
        val chapterId = db.chapterDao().insert(
            com.lianwenqi.app.data.entity.Chapter(
                bookId = bookId,
                title = "第一章",
                summary = "梗概",
                content = "正文"
            )
        )
        val sessionId = sessions.createSession(bookId = bookId)
        val taskId = tasks.createTask(sessionId, "检查资料")
        addSynopsis(sessionId, chapterId)
        snapshots.freezeSnapshot(taskId, sessionId)

        sessions.deleteSession(sessionId)

        assertTrue(references.references(sessionId).isEmpty())
        assertTrue(snapshots.referencesOfTask(taskId).isEmpty())
        assertNotNull(db.bookDao().getById(bookId))
        assertEquals("正文", db.chapterDao().getById(chapterId)!!.content)
    }
}
