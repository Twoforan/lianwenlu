package com.lianwenqi.app.agent.data

import com.lianwenqi.app.agent.data.dao.AgentReferenceChapterMeta
import com.lianwenqi.app.agent.data.dao.AgentReferenceChapterRow
import com.lianwenqi.app.agent.data.dao.AgentMaterialMessageRow
import com.lianwenqi.app.agent.data.dao.AgentToolMaterialRow
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.data.entity.AgentSession
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.data.AppDatabase
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * F5 历史资料来源的只读访问入口。
 *
 * 所有读取都经过现有 DAO；不做任何写入，也不缓存正文。
 * UI 选择列表与 ContextAssembler 共用本仓库，避免两套来源查询逻辑。
 */
class AgentReferenceSourceRepository(private val db: AppDatabase) {

    private val readDao = db.agentReadDao()
    private val agentDao = db.agentDao()
    private val taskDao = db.agentTaskDao()
    private val bookDao = db.bookDao()
    private val volumeDao = db.volumeDao()

    suspend fun chapterMeta(chapterId: Long): AgentReferenceChapterMeta? =
        readDao.referenceChapterMeta(chapterId)

    /** 正文分页读取；[offset] 从 0 开始，内部转换为 SQLite 的 1 基下标 */
    suspend fun chapterContentSlice(chapterId: Long, offset: Int, length: Int): String? =
        readDao.contentSlice(chapterId, offset.coerceAtLeast(0), length.coerceAtLeast(0))

    suspend fun chapterCandidates(
        bookId: Long,
        volumeId: Long?,
        query: String,
        snippet: Int = 200,
        limit: Int = 500
    ): List<AgentReferenceChapterRow> =
        readDao.referenceChapterCandidates(bookId, volumeId, query.trim(), snippet, limit)

    suspend fun bookTitle(bookId: Long): String? = bookDao.getById(bookId)?.title

    suspend fun volumeTitle(volumeId: Long): String? = volumeDao.getById(volumeId)?.title

    suspend fun agentTask(taskId: Long): AgentTask? = taskDao.getTask(taskId)

    suspend fun tasksOfSession(sessionId: Long): List<AgentTask> =
        taskDao.tasksOfSession(sessionId)

    suspend fun agentSession(sessionId: Long): AgentSession? = agentDao.getSession(sessionId)

    suspend fun agentMessage(messageId: Long): AgentMessage? = agentDao.getMessage(messageId)

    suspend fun toolExecution(executionId: Long): AgentToolExecution? =
        taskDao.getToolExecution(executionId)

    suspend fun materialMessages(
        sessionId: Long,
        snippet: Int = 160,
        limit: Int = 500
    ): List<AgentMaterialMessageRow> = agentDao.materialMessages(sessionId, snippet, limit)

    suspend fun materialToolResults(
        taskId: Long,
        snippet: Int = 160,
        limit: Int = 200
    ): List<AgentToolMaterialRow> = taskDao.materialToolResults(taskId, snippet, limit)

    /**
     * 历史任务最终结果的唯一读取口径：
     * 1. 优先取该任务最后一条 FINAL_ANSWER 消息正文；
     * 2. 兜底取 TASK_COMPLETE 节点 outputJson.summary。
     *
     * 只返回正文，不返回 promptLog、thinkingSummary 或任何隐藏推理。
     */
    suspend fun finalAnswerOf(taskId: Long): String? {
        val message = agentDao.lastMessageOfKind(taskId, AgentMessageKind.FINAL_ANSWER)
            ?.content
            ?.trim()
        if (!message.isNullOrBlank()) return message
        val node = taskDao.nodesOfTask(taskId)
            .lastOrNull { it.nodeType == AgentNodeType.TASK_COMPLETE }
            ?: return null
        return runCatching {
            agentJson.parseToJsonElement(node.outputJson)
                .jsonObject["summary"]
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}
