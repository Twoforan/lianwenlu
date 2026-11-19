package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentReferenceLimits
import com.lianwenqi.app.agent.data.entity.AgentReferenceMode
import com.lianwenqi.app.agent.data.entity.AgentTaskReference
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow

/** 一次任务引用注入结果的落库字段；由 AgentContextAssembler 提供 */
data class AgentTaskReferenceInjection(
    val referenceId: Long,
    val validated: Boolean = true,
    val valid: Boolean,
    val invalidReason: String = "",
    val injected: Boolean = false,
    val truncated: Boolean = false,
    val contentStart: Int? = null,
    val contentChars: Int = 0,
    val contentTotalChars: Int = 0,
    val estimatedTokens: Int = 0,
    val sourceUpdatedAt: Long? = null
)

/**
 * 任务级引用快照仓库（F5）。
 *
 * 快照在任务第一次开始执行时冻结，此后任务只读快照；
 * 会话引用的新增 / 删除 / 模式修改不会改变已经冻结的任务快照。
 */
class AgentTaskReferenceRepository(private val db: AppDatabase) {

    private val dao = db.agentTaskDao()
    private val sessionDao = db.agentDao()

    /**
     * 冻结任务引用快照；已有快照时直接返回，不重复写入。
     *
     * 只复制启用的会话引用，最多 [AgentReferenceLimits.MAX_TASK_REFERENCES] 条；
     * CONTEXT 优先于 REFERENCE_ONLY，同模式按选择时间排序。
     */
    suspend fun freezeSnapshot(taskId: Long, sessionId: Long): List<AgentTaskReference> =
        db.withTransaction {
            val existing = dao.taskReferences(taskId)
            if (existing.isNotEmpty()) return@withTransaction existing
            val task = dao.getTask(taskId)
                ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
            require(task.sessionId == sessionId) { "任务不属于当前 Agent 会话" }
            val enabled = sessionDao.references(sessionId)
                .filter { it.enabled }
                .sortedWith(
                    compareBy(
                        { if (it.referenceMode == AgentReferenceMode.CONTEXT) 0 else 1 },
                        { it.selectedAt },
                        { it.id }
                    )
                )
                .take(AgentReferenceLimits.MAX_TASK_REFERENCES)
            enabled.forEach { reference ->
                dao.insertTaskReference(
                    AgentTaskReference(
                        taskId = taskId,
                        sessionId = sessionId,
                        sourceType = reference.sourceType,
                        sourceId = reference.sourceId,
                        bookId = reference.bookId,
                        chapterId = reference.chapterId,
                        referenceMode = reference.referenceMode,
                        selectedAt = reference.selectedAt,
                        sourceTitleSnapshot = reference.titleSnapshot
                            .take(AgentReferenceLimits.MAX_TITLE_SNAPSHOT_CHARS),
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            dao.taskReferences(taskId)
        }

    suspend fun referencesOfTask(taskId: Long): List<AgentTaskReference> =
        dao.taskReferences(taskId)

    fun observeReferencesOfTask(taskId: Long): Flow<List<AgentTaskReference>> =
        dao.observeTaskReferences(taskId)

    /** 写入本次实际注入结果；快照内容与授权范围本身不更新 */
    suspend fun recordInjectionResults(
        taskId: Long,
        results: List<AgentTaskReferenceInjection>
    ) = db.withTransaction {
        if (results.isEmpty()) return@withTransaction
        val current = dao.taskReferences(taskId).associateBy { it.id }
        results.forEach { result ->
            val reference = current[result.referenceId] ?: return@forEach
            dao.updateTaskReference(
                reference.copy(
                    validated = result.validated,
                    valid = result.valid,
                    invalidReason = result.invalidReason.take(500),
                    injected = result.injected,
                    truncated = result.truncated,
                    contentStart = result.contentStart,
                    contentChars = result.contentChars,
                    contentTotalChars = result.contentTotalChars,
                    estimatedTokens = result.estimatedTokens,
                    sourceUpdatedAt = result.sourceUpdatedAt ?: reference.sourceUpdatedAt
                )
            )
        }
    }

    /** 删除任务快照；不删除来源数据 */
    suspend fun deleteForTask(taskId: Long) = dao.deleteTaskReferencesForTask(taskId)

    suspend fun deleteForSession(sessionId: Long) =
        dao.deleteTaskReferencesForSession(sessionId)
}
