package com.lianwenqi.app.agent.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.ChatRepository
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Agent 会话 / 消息 / 资料引用仓库验收（内存数据库） */
@RunWith(AndroidJUnit4::class)
class AgentRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessions: AgentSessionRepository
    private lateinit var references: AgentReferenceRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessions = AgentSessionRepository(db)
        references = AgentReferenceRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 新建会话默认标题新任务且允许未绑定书籍() = runBlocking {
        val id = sessions.createSession()
        val session = sessions.getSession(id)!!
        assertEquals("新任务", session.title)
        assertNull(session.bookId)
        assertEquals("ACTIVE", session.status)
        assertEquals(0L, session.lastMessageAt)
    }

    @Test
    fun 修改标题与绑定解绑书籍() = runBlocking {
        val id = sessions.createSession()
        sessions.renameSession(id, "  第三卷剧情检查  ")
        sessions.bindBook(id, 7L)
        val bound = sessions.getSession(id)!!
        assertEquals("第三卷剧情检查", bound.title)
        assertEquals(7L, bound.bookId)

        // 空白标题不覆盖原名称
        sessions.renameSession(id, "   ")
        assertEquals("第三卷剧情检查", sessions.getSession(id)!!.title)

        sessions.bindBook(id, null)
        assertNull(sessions.getSession(id)!!.bookId)
    }

    @Test
    fun 按书籍筛选与关键词搜索() = runBlocking {
        val a = sessions.createSession(bookId = 1L)
        val b = sessions.createSession(bookId = 2L)
        sessions.renameSession(a, "第三卷大纲检查")
        sessions.renameSession(b, "人物设定补全")

        assertEquals(
            listOf(a),
            sessions.observeSessions("", 1L).first().map { it.id }
        )
        assertEquals(
            listOf(a),
            sessions.observeSessions("大纲", null).first().map { it.id }
        )
        assertEquals(
            listOf(b),
            sessions.observeSessions("人物", 2L).first().map { it.id }
        )
        assertTrue(sessions.observeSessions("不存在", null).first().isEmpty())
    }

    @Test
    fun 消息按sequence递增且写入后更新会话时间() = runBlocking {
        val id = sessions.createSession()
        sessions.addMessage(
            sessionId = id,
            role = AgentMessageRole.USER,
            kind = AgentMessageKind.USER_INPUT,
            content = "第一条"
        )
        sessions.addMessage(
            sessionId = id,
            role = AgentMessageRole.ASSISTANT,
            kind = AgentMessageKind.FINAL_ANSWER,
            content = "第二条"
        )
        sessions.addMessage(
            sessionId = id,
            role = AgentMessageRole.USER,
            kind = AgentMessageKind.USER_INPUT,
            content = "第三条"
        )

        val messages = sessions.getMessages(id)
        assertEquals(listOf(0L, 1L, 2L), messages.map { it.sequence })
        assertEquals(listOf("第一条", "第二条", "第三条"), messages.map { it.content })
        assertEquals(AgentMessageRole.ASSISTANT, messages[1].role)
        assertEquals(AgentMessageKind.FINAL_ANSWER, messages[1].kind)
        assertTrue(sessions.getSession(id)!!.lastMessageAt > 0L)

        sessions.clearMessages(id)
        assertTrue(sessions.getMessages(id).isEmpty())
        assertEquals(0L, sessions.getSession(id)!!.lastMessageAt)
    }

    @Test
    fun 同一来源重复引用只保留一条并刷新标题() = runBlocking {
        val id = sessions.createSession()
        val first = references.addReference(
            sessionId = id,
            sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
            sourceId = 11L,
            bookId = 1L,
            chapterId = 11L,
            titleSnapshot = "第一章"
        )
        val second = references.addReference(
            sessionId = id,
            sourceType = AgentReferenceSourceType.CHAPTER_SYNOPSIS,
            sourceId = 11L,
            bookId = 1L,
            chapterId = 11L,
            titleSnapshot = "第一章（改名后）"
        )
        assertEquals(first, second)
        val list = references.references(id)
        assertEquals(1, list.size)
        assertEquals("第一章（改名后）", list.single().titleSnapshot)
        assertTrue(list.single().enabled)

        references.setEnabled(first, false)
        assertFalse(references.references(id).single().enabled)
        references.deleteReference(first)
        assertTrue(references.references(id).isEmpty())
    }

    @Test
    fun 删除Agent会话清理自身数据且不影响普通聊天() = runBlocking {
        val chat = ChatRepository(db)
        val chatSessionId = chat.createSession("普通会话", null, null)
        db.chatDao().insertMessage(
            ChatMessage(
                sessionId = chatSessionId,
                role = ChatRole.USER,
                content = "普通消息"
            )
        )

        val agentId = sessions.createSession(bookId = 3L)
        sessions.addMessage(
            sessionId = agentId,
            role = AgentMessageRole.USER,
            kind = AgentMessageKind.USER_INPUT,
            content = "Agent 消息"
        )
        references.addReference(
            sessionId = agentId,
            sourceType = AgentReferenceSourceType.CHAPTER_CONTENT,
            sourceId = 9L,
            bookId = 3L,
            chapterId = 9L,
            titleSnapshot = "第九章"
        )

        sessions.deleteSession(agentId)

        assertNull(sessions.getSession(agentId))
        assertTrue(sessions.getMessages(agentId).isEmpty())
        assertTrue(references.references(agentId).isEmpty())

        // 普通聊天数据保持原样
        assertEquals("普通会话", chat.getSession(chatSessionId)!!.title)
        assertEquals(
            listOf("普通消息"),
            chat.getMessages(chatSessionId).map { it.content }
        )
    }
}
