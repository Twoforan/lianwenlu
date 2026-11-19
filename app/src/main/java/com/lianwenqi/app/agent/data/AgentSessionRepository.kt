package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Agent 会话与消息仓库。
 *
 * 只读写 `agent_session` / `agent_message` / `agent_session_reference`；
 * 删除会话时会一并清理 F2 的 `agent_task` / `agent_execution_node` /
 * `agent_tool_execution` / `agent_event`，但不依赖 Compose、ViewModel、
 * [com.lianwenqi.app.data.ChatRepository]，也不会修改普通聊天与章节侧边栏的数据。
 */
class AgentSessionRepository(private val db: AppDatabase) {

    private val dao = db.agentDao()
    private val taskDao = db.agentTaskDao()

    // ---------------- 会话 ----------------

    fun observeAllSessions(): Flow<List<AgentSession>> = dao.observeAllSessions()

    /** 按标题关键词与书籍筛选；[bookId] 为空表示全部书籍 */
    fun observeSessions(query: String, bookId: Long?): Flow<List<AgentSession>> =
        dao.observeSessions(query.trim(), bookId)

    fun observeSession(id: Long): Flow<AgentSession?> = dao.observeSession(id)

    suspend fun getSession(id: Long): AgentSession? = dao.getSession(id)

    /** 新建会话；[bookId] 为空表示未绑定书籍 */
    suspend fun createSession(
        bookId: Long? = null,
        modelId: Long? = null,
        title: String = AgentSession.DEFAULT_TITLE
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insertSession(
            AgentSession(
                title = title.trim().ifBlank { AgentSession.DEFAULT_TITLE },
                bookId = bookId,
                modelId = modelId,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /** 修改标题；空白标题忽略，避免会话无法辨认 */
    suspend fun renameSession(id: Long, title: String) {
        val name = title.trim()
        if (name.isEmpty()) return
        dao.renameSession(id, name, System.currentTimeMillis())
    }

    /** 绑定 / 解绑书籍；[bookId] 为空表示解绑 */
    suspend fun bindBook(id: Long, bookId: Long?) =
        dao.bindBook(id, bookId, System.currentTimeMillis())

    /** 保存 Agent 会话独立的模型选择；不影响普通聊天的 activeModelId */
    suspend fun setModel(id: Long, modelId: Long?) =
        dao.setModel(id, modelId, System.currentTimeMillis())

    /**
     * 删除 Agent 会话：在同一事务中清理自身消息、资料引用、任务、节点、工具执行与事件，再删除会话。
     * 只操作 `agent_*` 表，绝不会删除普通会话、书籍、章节、大纲或文风数据。
     */
    suspend fun deleteSession(id: Long) = db.withTransaction {
        dao.deleteReferencesForSession(id)
        dao.clearMessages(id)
        taskDao.deleteClarificationsForSession(id)
        taskDao.deleteTaskReferencesForSession(id)
        taskDao.deleteWriteOperationsForSession(id)
        taskDao.deleteModelRequestsForSession(id)
        taskDao.deleteRecoveryLogsForSession(id)
        taskDao.deleteEventsForSession(id)
        taskDao.deleteToolExecutionsForSession(id)
        taskDao.deleteNodesForSession(id)
        taskDao.deleteTasksForSession(id)
        dao.deleteSession(id)
    }

    // ---------------- 消息 ----------------

    fun observeMessages(sessionId: Long): Flow<List<AgentMessage>> = dao.observeMessages(sessionId)

    suspend fun getMessages(sessionId: Long): List<AgentMessage> = dao.getMessages(sessionId)

    /**
     * 追加一条会话消息；[sequence] 在事务内取当前最大值 +1，保证同一会话内严格递增。
     * F1 不调用模型，[AgentMessageKind.USER_INPUT] / [AgentMessageKind.NOTICE] 可直接落库。
     */
    suspend fun addMessage(
        sessionId: Long,
        role: String = AgentMessageRole.USER,
        kind: String = AgentMessageKind.USER_INPUT,
        content: String,
        taskId: Long? = null,
        thinkingSummary: String = "",
        promptTokens: Int = AgentMessage.PROMPT_TOKENS_UNKNOWN,
        completionTokens: Int = 0,
        cachedTokens: Int = AgentMessage.CACHE_TOKENS_UNKNOWN,
        promptLog: String = "",
        attachments: String = "",
        contextSnapshot: String = ""
    ): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val sequence = dao.maxSequence(sessionId) + 1
        val id = dao.insertMessage(
            AgentMessage(
                sessionId = sessionId,
                taskId = taskId,
                role = role,
                kind = kind,
                content = content,
                thinkingSummary = thinkingSummary,
                sequence = sequence,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = cachedTokens,
                promptLog = promptLog,
                attachments = attachments,
                contextSnapshot = contextSnapshot,
                createdAt = now
            )
        )
        dao.touchSession(sessionId, now, now)
        id
    }

    /** 清空会话消息，但保留会话本身 */
    suspend fun clearMessages(sessionId: Long) = db.withTransaction {
        dao.clearMessages(sessionId)
        dao.touchSession(sessionId, System.currentTimeMillis(), 0)
    }
}
