package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentReferenceLimits
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentReferenceSourceType
import com.lianwenqi.app.agent.data.entity.AgentSessionReference
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Agent 会话级资料引用仓库。
 *
 * 只保存「引用了哪个来源」与标题快照，不读取、不拼接、不复制来源正文；
 * 删除引用不会删除来源书籍 / 章节 / 梗概 / 会话。
 *
 * F5 增加：
 * - 会话引用数量上限（50）；
 * - 历史 Agent 消息引用上限（20）；
 * - 引用模式切换；
 * - 引用 Flow 与来源类型校验。
 */
class AgentReferenceRepository(private val db: AppDatabase) {

    private val dao = db.agentDao()

    /**
     * 新增引用；同一 (sessionId, sourceType, sourceId) 已存在时复用原记录并刷新标题 / 启用状态，
     * 不产生重复行（数据库唯一索引兜底）。
     *
     * 数量上限只在「新增来源」时校验，重新启用旧引用不占新名额。
     */
    suspend fun addReference(
        sessionId: Long,
        sourceType: String,
        sourceId: Long,
        bookId: Long? = null,
        chapterId: Long? = null,
        volumeId: Long? = null,
        rangeStart: Int? = null,
        rangeEnd: Int? = null,
        titleSnapshot: String = "",
        referenceMode: String = AgentReferenceMode.CONTEXT
    ): Long = db.withTransaction {
        require(sessionId > 0) { "sessionId 不合法：$sessionId" }
        require(sourceType.isNotBlank()) { "sourceType 不能为空" }
        require(sourceId > 0) { "sourceId 不合法：$sourceId" }
        require(sourceType in AgentReferenceSourceType.selectable) {
            "不支持该引用来源类型：$sourceType"
        }
        require(referenceMode in VALID_MODES) { "非法引用模式：$referenceMode" }
        val title = titleSnapshot.take(AgentReferenceLimits.MAX_TITLE_SNAPSHOT_CHARS)
        val now = System.currentTimeMillis()
        val existing = dao.findReference(sessionId, sourceType, sourceId)
        if (existing == null) {
            val all = dao.references(sessionId)
            require(all.size < AgentReferenceLimits.MAX_SESSION_REFERENCES) {
                "会话引用数量已达上限（${AgentReferenceLimits.MAX_SESSION_REFERENCES}）"
            }
            if (sourceType == AgentReferenceSourceType.AGENT_MESSAGE) {
                val messageCount = all.count {
                    it.sourceType == AgentReferenceSourceType.AGENT_MESSAGE
                }
                require(messageCount < AgentReferenceLimits.MAX_AGENT_MESSAGE_REFERENCES) {
                    "历史会话消息引用数量已达上限" +
                        "（${AgentReferenceLimits.MAX_AGENT_MESSAGE_REFERENCES}）"
                }
            }
        }
        val candidate = AgentSessionReference(
            sessionId = sessionId,
            sourceType = sourceType,
            sourceId = sourceId,
            bookId = bookId,
            chapterId = chapterId,
            volumeId = volumeId,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            titleSnapshot = title,
            referenceMode = referenceMode,
            enabled = true,
            selectedAt = now
        )
        val inserted = dao.insertReference(candidate)
        if (inserted > 0) return@withTransaction inserted
        // 唯一索引冲突：复用原记录，刷新展示信息并重新启用
        val row = dao.findReference(sessionId, sourceType, sourceId)
            ?: error("引用唯一约束冲突但未找到原记录")
        dao.updateReference(
            row.copy(
                bookId = bookId,
                chapterId = chapterId,
                volumeId = volumeId,
                rangeStart = rangeStart,
                rangeEnd = rangeEnd,
                titleSnapshot = title,
                referenceMode = referenceMode,
                enabled = true,
                selectedAt = now
            )
        )
        row.id
    }

    suspend fun references(sessionId: Long): List<AgentSessionReference> = dao.references(sessionId)

    fun observeReferences(sessionId: Long): Flow<List<AgentSessionReference>> =
        dao.observeReferences(sessionId)

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setReferenceEnabled(id, enabled)

    /** 切换 CONTEXT / REFERENCE_ONLY；只影响后续任务，不改变已冻结的任务快照 */
    suspend fun setReferenceMode(id: Long, referenceMode: String) {
        require(referenceMode in VALID_MODES) { "非法引用模式：$referenceMode" }
        dao.setReferenceMode(id, referenceMode)
    }

    /** 移除引用只解除授权关系，不删除来源数据 */
    suspend fun deleteReference(id: Long) = dao.deleteReference(id)

    /** 删除会话的全部引用；只删引用行，不影响来源数据 */
    suspend fun deleteForSession(sessionId: Long) = dao.deleteReferencesForSession(sessionId)

    companion object {
        private val VALID_MODES = listOf(
            AgentReferenceMode.CONTEXT,
            AgentReferenceMode.REFERENCE_ONLY
        )
    }
}
