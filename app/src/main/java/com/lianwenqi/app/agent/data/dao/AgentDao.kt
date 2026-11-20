package com.lianwenqi.app.agent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentSessionReference
import kotlinx.coroutines.flow.Flow

/** F5：历史 Agent 消息选择列表的轻量投影；不加载 promptLog / thinkingSummary */
data class AgentMaterialMessageRow(
    val id: Long,
    val sessionId: Long,
    val taskId: Long?,
    val role: String,
    val kind: String,
    val sequence: Long,
    val contentSnippet: String,
    val contentLength: Int,
    val createdAt: Long
)

/**
 * Agent 独立数据访问。
 *
 * 只操作 `agent_*` 表，不触碰 `chat_session` / `chat_message` 或任何小说业务表。
 */
@Dao
interface AgentDao {

    // ---------------- 会话 ----------------

    @Query("SELECT * FROM agent_session ORDER BY updatedAt DESC, id DESC")
    fun observeAllSessions(): Flow<List<AgentSession>>

    @Query(
        "SELECT * FROM agent_session " +
            "WHERE (:bookId IS NULL OR bookId = :bookId) " +
            "AND (:query = '' OR title LIKE '%' || :query || '%') " +
            "ORDER BY updatedAt DESC, id DESC"
    )
    fun observeSessions(query: String, bookId: Long?): Flow<List<AgentSession>>

    @Query("SELECT * FROM agent_session WHERE id = :id")
    fun observeSession(id: Long): Flow<AgentSession?>

    @Query("SELECT * FROM agent_session WHERE id = :id")
    suspend fun getSession(id: Long): AgentSession?

    @Insert
    suspend fun insertSession(session: AgentSession): Long

    @Update
    suspend fun updateSession(session: AgentSession)

    @Query("UPDATE agent_session SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameSession(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE agent_session SET bookId = :bookId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun bindBook(id: Long, bookId: Long?, updatedAt: Long)

    @Query("UPDATE agent_session SET updatedAt = :updatedAt, lastMessageAt = :lastMessageAt WHERE id = :id")
    suspend fun touchSession(id: Long, updatedAt: Long, lastMessageAt: Long)

    /** 维护最近任务；创建任务或切换最近任务时调用 */
    @Query("UPDATE agent_session SET lastTaskId = :taskId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setLastTaskId(id: Long, taskId: Long?, updatedAt: Long)

    /** 保存 Agent 会话独立模型选择；不得影响普通聊天的 activeModelId */
    @Query("UPDATE agent_session SET modelId = :modelId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setModel(id: Long, modelId: Long?, updatedAt: Long)

    @Query("DELETE FROM agent_session WHERE id = :id")
    suspend fun deleteSession(id: Long)

    // ---------------- 消息 ----------------

    @Query("SELECT * FROM agent_message WHERE sessionId = :sessionId ORDER BY sequence ASC, id ASC")
    fun observeMessages(sessionId: Long): Flow<List<AgentMessage>>

    @Query("SELECT * FROM agent_message WHERE sessionId = :sessionId ORDER BY sequence ASC, id ASC")
    suspend fun getMessages(sessionId: Long): List<AgentMessage>

    @Query("SELECT * FROM agent_message WHERE id = :id")
    suspend fun getMessage(id: Long): AgentMessage?

    /**
     * 某个任务的最后一条指定类型消息；用于 F5 读取历史任务最终结果。
     * 只取 kind，不区分 role（调用方仍需校验为 ASSISTANT）。
     */
    @Query(
        "SELECT * FROM agent_message WHERE taskId = :taskId AND kind = :kind " +
            "ORDER BY sequence DESC, id DESC LIMIT 1"
    )
    suspend fun lastMessageOfKind(taskId: Long, kind: String): AgentMessage?

    /** F5：历史 Agent 消息候选；只取正文片段，避免加载 promptLog 与整段长文本 */
    @Query(
        "SELECT id, sessionId, taskId, role, kind, sequence, " +
            "substr(content, 1, :snippet) AS contentSnippet, " +
            "length(content) AS contentLength, createdAt " +
            "FROM agent_message WHERE sessionId = :sessionId " +
            "AND kind IN ('USER_INPUT', 'PLAN', 'FINAL_ANSWER') " +
            "ORDER BY sequence ASC, id ASC LIMIT :limit"
    )
    suspend fun materialMessages(
        sessionId: Long,
        snippet: Int,
        limit: Int
    ): List<AgentMaterialMessageRow>


    @Query("SELECT COALESCE(MAX(sequence), -1) FROM agent_message WHERE sessionId = :sessionId")
    suspend fun maxSequence(sessionId: Long): Long

    @Insert
    suspend fun insertMessage(message: AgentMessage): Long

    @Query("DELETE FROM agent_message WHERE sessionId = :sessionId")
    suspend fun clearMessages(sessionId: Long)

    /** 删除某个任务的对话消息（删除任务时使用），会话级消息（taskId IS NULL）不受影响 */
    @Query("DELETE FROM agent_message WHERE sessionId = :sessionId AND taskId = :taskId")
    suspend fun deleteMessagesForTask(sessionId: Long, taskId: Long)

    /** 删除任务中某一类消息（例如尚未完成澄清时清掉计划消息） */
    @Query("DELETE FROM agent_message WHERE taskId = :taskId AND kind = :kind")
    suspend fun deleteMessagesOfTaskKind(taskId: Long, kind: String)

    /** 删除单条消息（聊天卡片上的删除图标） */
    @Query("DELETE FROM agent_message WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    /** 直接编辑 Agent 回复内容 */
    @Query("UPDATE agent_message SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    // ---------------- 资料引用 ----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReference(reference: AgentSessionReference): Long

    @Update
    suspend fun updateReference(reference: AgentSessionReference)

    @Query(
        "SELECT * FROM agent_session_reference WHERE sessionId = :sessionId " +
            "ORDER BY selectedAt ASC, id ASC"
    )
    suspend fun references(sessionId: Long): List<AgentSessionReference>

    /** F5：引用列表实时刷新（新增 / 删除 / 切换模式后无需手动 reload） */
    @Query(
        "SELECT * FROM agent_session_reference WHERE sessionId = :sessionId " +
            "ORDER BY selectedAt ASC, id ASC"
    )
    fun observeReferences(sessionId: Long): Flow<List<AgentSessionReference>>

    @Query(
        "SELECT * FROM agent_session_reference " +
            "WHERE sessionId = :sessionId AND sourceType = :sourceType AND sourceId = :sourceId " +
            "LIMIT 1"
    )
    suspend fun findReference(
        sessionId: Long,
        sourceType: String,
        sourceId: Long
    ): AgentSessionReference?

    @Query("UPDATE agent_session_reference SET enabled = :enabled WHERE id = :id")
    suspend fun setReferenceEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE agent_session_reference SET referenceMode = :mode WHERE id = :id")
    suspend fun setReferenceMode(id: Long, mode: String)

    @Query(
        "SELECT COUNT(*) FROM agent_session_reference " +
            "WHERE sessionId = :sessionId AND sourceType = :sourceType AND enabled = 1"
    )
    suspend fun enabledReferenceCount(sessionId: Long, sourceType: String): Int

    @Query("DELETE FROM agent_session_reference WHERE id = :id")
    suspend fun deleteReference(id: Long)

    @Query("DELETE FROM agent_session_reference WHERE sessionId = :sessionId")
    suspend fun deleteReferencesForSession(sessionId: Long)
}
