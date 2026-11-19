package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentRecoveryLog
import com.lianwenqi.app.agent.data.entity.AgentRecoveryResult
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow

/** 恢复日志只保存状态与计数，不保存正文、提示词或密钥 */
class AgentRecoveryLogRepository(private val db: AppDatabase) {

    private val dao = db.agentTaskDao()

    suspend fun start(
        taskId: Long,
        attempt: Int,
        reason: String,
        originalStatus: String,
        originalNodeId: Long?,
        originalNodeTitle: String,
        cursorBefore: Int,
        succeededNodeCount: Int,
        failedNodeCount: Int,
        uncertainToolCount: Int,
        unresolvedWriteCount: Int,
        duplicateRequestPossible: Boolean,
        inconsistentDataFound: Boolean
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insertRecoveryLog(
            AgentRecoveryLog(
                taskId = taskId,
                attempt = attempt,
                reason = reason.take(500),
                originalStatus = originalStatus,
                originalNodeId = originalNodeId,
                originalNodeTitle = originalNodeTitle.take(200),
                cursorBefore = cursorBefore,
                cursorAfter = cursorBefore,
                succeededNodeCount = succeededNodeCount,
                failedNodeCount = failedNodeCount,
                uncertainToolCount = uncertainToolCount,
                unresolvedWriteCount = unresolvedWriteCount,
                duplicateRequestPossible = duplicateRequestPossible,
                inconsistentDataFound = inconsistentDataFound,
                result = AgentRecoveryResult.SKIPPED,
                startedAt = now,
                createdAt = now
            )
        )
    }

    suspend fun finish(
        id: Long,
        result: String,
        cursorAfter: Int,
        message: String = ""
    ): AgentRecoveryLog = db.withTransaction {
        require(result in AgentRecoveryResult.all) { "非法恢复结果：$result" }
        val log = dao.getRecoveryLog(id)
            ?: throw IllegalArgumentException("恢复日志不存在：$id")
        val now = System.currentTimeMillis()
        val next = log.copy(
            result = result,
            cursorAfter = cursorAfter,
            message = message.take(1_000),
            completedAt = now
        )
        dao.updateRecoveryLog(next)
        next
    }

    suspend fun logsOfTask(taskId: Long): List<AgentRecoveryLog> =
        dao.recoveryLogsOfTask(taskId)

    fun observeOfTask(taskId: Long): Flow<List<AgentRecoveryLog>> =
        dao.observeRecoveryLogsOfTask(taskId)

    suspend fun latestOfTask(taskId: Long): AgentRecoveryLog? =
        dao.latestRecoveryLog(taskId)

    suspend fun deleteForTask(taskId: Long) = dao.deleteRecoveryLogsForTask(taskId)

    suspend fun deleteForSession(sessionId: Long) =
        dao.deleteRecoveryLogsForSession(sessionId)
}
