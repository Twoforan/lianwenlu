package com.lianwenqi.app.agent.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lianwenqi.app.agent.data.entity.AgentClarification
import com.lianwenqi.app.agent.data.entity.AgentEvent
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentModelRequest
import com.lianwenqi.app.agent.data.entity.AgentRecoveryLog
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskReference
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import kotlinx.coroutines.flow.Flow

/** F5：历史工具结果选择列表的轻量投影；不加载完整 resultJson */
data class AgentToolMaterialRow(
    val id: Long,
    val taskId: Long,
    val toolName: String,
    val toolDisplayName: String,
    val status: String,
    val resultSnippet: String,
    val resultLength: Int,
    val createdAt: Long
)

/**
 * Agent 任务 / 节点 / 工具执行 / 事件的数据访问。
 *
 * 只负责数据库访问，不写业务状态规则；事务与事件序号生成由 Repository 负责。
 */
@Dao
interface AgentTaskDao {

    // ---------------- 任务 ----------------

    @Insert
    suspend fun insertTask(task: AgentTask): Long

    @Update
    suspend fun updateTask(task: AgentTask)

    @Query("SELECT * FROM agent_task WHERE id = :id")
    suspend fun getTask(id: Long): AgentTask?

    @Query("SELECT * FROM agent_task WHERE id = :id")
    fun observeTask(id: Long): Flow<AgentTask?>

    @Query("SELECT * FROM agent_task WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC")
    suspend fun tasksOfSession(sessionId: Long): List<AgentTask>

    @Query("SELECT * FROM agent_task WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC")
    fun observeTasksOfSession(sessionId: Long): Flow<List<AgentTask>>

    /** 当前书籍下所有保存过计划书的任务（跨会话），用于计划书库 */
    @Query(
        "SELECT t.* FROM agent_task t INNER JOIN agent_session s ON t.sessionId = s.id " +
            "WHERE s.bookId = :bookId " +
            "AND t.planJson != '' AND t.planJson != '{}' " +
            "ORDER BY t.createdAt DESC, t.id DESC"
    )
    suspend fun tasksWithPlanOfBook(bookId: Long): List<AgentTask>

    /** 当前任务的所有直接子分支 */
    @Query("SELECT * FROM agent_task WHERE parentTaskId = :parentId ORDER BY createdAt ASC, id ASC")
    suspend fun childTasks(parentId: Long): List<AgentTask>

    @Query("SELECT * FROM agent_task WHERE parentTaskId = :parentId ORDER BY createdAt ASC, id ASC")
    fun observeChildTasks(parentId: Long): Flow<List<AgentTask>>

    @Query(
        "SELECT * FROM agent_task WHERE status IN (:statuses) " +
            "ORDER BY updatedAt DESC, id DESC"
    )
    suspend fun tasksWithStatuses(statuses: List<String>): List<AgentTask>

    @Query(
        "SELECT * FROM agent_task WHERE sessionId = :sessionId AND status IN (:statuses) " +
            "ORDER BY updatedAt DESC, id DESC"
    )
    suspend fun tasksOfSessionWithStatuses(
        sessionId: Long,
        statuses: List<String>
    ): List<AgentTask>

    @Query("UPDATE agent_task SET currentNodeId = :nodeId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCurrentNode(id: Long, nodeId: Long?, updatedAt: Long)

    // ---------------- F7：执行租约 ----------------

    /**
     * 原子取得执行租约。
     *
     * 只有原来没有执行者、租约已过期、或心跳长时间未更新（设备时间回拨兜底）时才能成功；
     * 返回受影响行数，1 表示本次真正取得执行权。
     */
    @Query(
        "UPDATE agent_task SET executionOwner = :owner, executionLeaseUntil = :leaseUntil, " +
            "executionAttempt = executionAttempt + 1, lastHeartbeatAt = :now, " +
            "lastExecutionAt = :now, updatedAt = :now " +
            "WHERE id = :id AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
            "AND (executionOwner = '' OR executionLeaseUntil IS NULL " +
            "OR executionLeaseUntil < :now OR lastHeartbeatAt IS NULL " +
            "OR lastHeartbeatAt < :staleBefore)"
    )
    suspend fun tryAcquireExecutionLease(
        id: Long,
        owner: String,
        leaseUntil: Long,
        now: Long,
        staleBefore: Long
    ): Int

    @Query(
        "UPDATE agent_task SET executionLeaseUntil = :leaseUntil, lastHeartbeatAt = :now, " +
            "updatedAt = :now WHERE id = :id AND executionOwner = :owner " +
            "AND executionLeaseUntil >= :now"
    )
    suspend fun renewExecutionLease(
        id: Long,
        owner: String,
        leaseUntil: Long,
        now: Long
    ): Int

    @Query(
        "UPDATE agent_task SET executionOwner = '', executionLeaseUntil = NULL, " +
            "lastHeartbeatAt = :now, updatedAt = :now " +
            "WHERE id = :id AND executionOwner = :owner"
    )
    suspend fun releaseExecutionLease(id: Long, owner: String, now: Long): Int

    @Query(
        "SELECT * FROM agent_task WHERE id = :id AND executionOwner = :owner " +
            "AND executionLeaseUntil IS NOT NULL AND executionLeaseUntil >= :now"
    )
    suspend fun taskHeldByOwner(id: Long, owner: String, now: Long): AgentTask?

    /** 租约已过期 / 心跳过旧的 RUNNING 任务；应用启动与周期扫描用 */
    @Query(
        "SELECT * FROM agent_task WHERE status = 'RUNNING' " +
            "AND (executionOwner = '' OR executionLeaseUntil IS NULL " +
            "OR executionLeaseUntil < :now OR lastHeartbeatAt IS NULL " +
            "OR lastHeartbeatAt < :staleBefore) ORDER BY updatedAt ASC, id ASC"
    )
    suspend fun interruptedRunningTasks(now: Long, staleBefore: Long): List<AgentTask>

    // ---------------- F7：执行游标与暂停 / 取消标记 ----------------

    @Query(
        "UPDATE agent_task SET executionRunId = :runId, executionCursor = :cursor, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setExecutionRun(id: Long, runId: String, cursor: Int, updatedAt: Long)

    @Query(
        "UPDATE agent_task SET executionCursor = :cursor, currentNodeId = :nodeId, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setExecutionCursor(
        id: Long,
        cursor: Int,
        nodeId: Long?,
        updatedAt: Long
    )

    @Query("UPDATE agent_task SET pauseRequested = :requested, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPauseRequested(id: Long, requested: Boolean, updatedAt: Long)

    @Query("UPDATE agent_task SET cancelRequested = :requested, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setCancelRequested(id: Long, requested: Boolean, updatedAt: Long)

    @Query(
        "UPDATE agent_task SET recoveryCount = recoveryCount + 1, lastRecoveryAt = :now, " +
            "lastErrorKind = :kind, updatedAt = :now WHERE id = :id"
    )
    suspend fun recordRecoveryAttempt(id: Long, kind: String, now: Long)

    @Query("UPDATE agent_task SET lastErrorKind = :kind, updatedAt = :now WHERE id = :id")
    suspend fun setLastErrorKind(id: Long, kind: String, now: Long)

    @Query("DELETE FROM agent_task WHERE sessionId = :sessionId")
    suspend fun deleteTasksForSession(sessionId: Long)

    @Query("DELETE FROM agent_task WHERE id = :id")
    suspend fun deleteTask(id: Long)

    // ---------------- 执行节点 ----------------

    @Insert
    suspend fun insertNode(node: AgentExecutionNode): Long

    @Update
    suspend fun updateNode(node: AgentExecutionNode)

    @Query("SELECT * FROM agent_execution_node WHERE id = :id")
    suspend fun getNode(id: Long): AgentExecutionNode?

    @Query(
        "SELECT * FROM agent_execution_node WHERE taskId = :taskId " +
            "ORDER BY sequence ASC, id ASC"
    )
    suspend fun nodesOfTask(taskId: Long): List<AgentExecutionNode>

    @Query(
        "SELECT * FROM agent_execution_node WHERE taskId = :taskId " +
            "ORDER BY sequence ASC, id ASC"
    )
    fun observeNodesOfTask(taskId: Long): Flow<List<AgentExecutionNode>>

    @Query(
        "SELECT * FROM agent_execution_node WHERE taskId = :taskId AND status IN (:statuses) " +
            "ORDER BY sequence ASC, id ASC"
    )
    suspend fun nodesOfTaskWithStatuses(
        taskId: Long,
        statuses: List<String>
    ): List<AgentExecutionNode>

    @Query("SELECT COALESCE(MAX(sequence), -1) FROM agent_execution_node WHERE taskId = :taskId")
    suspend fun maxNodeSequence(taskId: Long): Long

    @Query("SELECT COUNT(*) FROM agent_execution_node WHERE taskId = :taskId AND status = :status")
    suspend fun countNodesWithStatus(taskId: Long, status: String): Int

    /** F7：把中断遗留的 RUNNING / WAITING 节点重新打开为 PENDING；只用于持有租约的恢复流程 */
    @Query(
        "UPDATE agent_execution_node SET status = 'PENDING', errorMessage = :message, " +
            "completedAt = NULL, updatedAt = :now " +
            "WHERE id = :id AND status IN ('RUNNING', 'WAITING')"
    )
    suspend fun reopenNodeForRecovery(id: Long, message: String, now: Long): Int

    /** 用户手动重试：把失败节点重新打开为 PENDING，从该节点继续执行 */
    @Query(
        "UPDATE agent_execution_node SET status = 'PENDING', errorMessage = '', " +
            "startedAt = NULL, completedAt = NULL, updatedAt = :now " +
            "WHERE id = :id AND status = 'FAILED'"
    )
    suspend fun reopenFailedNodeForRetry(id: Long, now: Long): Int

    /** 用户跳过失败节点：FAILED → SKIPPED，保留失败原因供追溯 */
    @Query(
        "UPDATE agent_execution_node SET status = 'SKIPPED', " +
            "completedAt = :now, updatedAt = :now " +
            "WHERE id = :id AND status = 'FAILED'"
    )
    suspend fun skipFailedNode(id: Long, now: Long): Int

    @Query(
        "SELECT * FROM agent_execution_node WHERE taskId = :taskId " +
            "AND status IN ('RUNNING', 'WAITING') ORDER BY sequence ASC, id ASC"
    )
    suspend fun interruptedNodes(taskId: Long): List<AgentExecutionNode>

    @Query(
        "DELETE FROM agent_execution_node WHERE taskId IN " +
            "(SELECT id FROM agent_task WHERE sessionId = :sessionId)"
    )
    suspend fun deleteNodesForSession(sessionId: Long)

    @Query("DELETE FROM agent_execution_node WHERE taskId = :taskId")
    suspend fun deleteNodesForTask(taskId: Long)

    // ---------------- 工具执行记录 ----------------

    @Insert
    suspend fun insertToolExecution(execution: AgentToolExecution): Long

    @Update
    suspend fun updateToolExecution(execution: AgentToolExecution)

    @Query("SELECT * FROM agent_tool_execution WHERE id = :id")
    suspend fun getToolExecution(id: Long): AgentToolExecution?

    @Query(
        "SELECT * FROM agent_tool_execution WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun toolExecutionsOfTask(taskId: Long): List<AgentToolExecution>

    @Query(
        "SELECT * FROM agent_tool_execution WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeToolExecutionsOfTask(taskId: Long): Flow<List<AgentToolExecution>>

    @Query(
        "SELECT * FROM agent_tool_execution WHERE taskId = :taskId AND nodeId = :nodeId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun toolExecutionsOfNode(taskId: Long, nodeId: Long): List<AgentToolExecution>

    @Query(
        "SELECT * FROM agent_tool_execution WHERE taskId = :taskId AND status IN (:statuses) " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun toolExecutionsWithStatuses(
        taskId: Long,
        statuses: List<String>
    ): List<AgentToolExecution>

    @Query(
        "SELECT COALESCE(MAX(attempt), 0) FROM agent_tool_execution " +
            "WHERE taskId = :taskId AND nodeId = :nodeId AND toolName = :toolName"
    )
    suspend fun maxAttemptForNode(taskId: Long, nodeId: Long, toolName: String): Int

    @Query(
        "SELECT COALESCE(MAX(attempt), 0) FROM agent_tool_execution " +
            "WHERE taskId = :taskId AND nodeId IS NULL AND toolName = :toolName"
    )
    suspend fun maxAttemptForTask(taskId: Long, toolName: String): Int

    @Query("SELECT * FROM agent_tool_execution WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): AgentToolExecution?

    /** F7：进程中断后仍停在 RUNNING 的工具执行记录 */
    @Query(
        "SELECT * FROM agent_tool_execution WHERE taskId = :taskId AND status = 'RUNNING' " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun runningToolExecutions(taskId: Long): List<AgentToolExecution>

    @Query(
        "UPDATE agent_tool_execution SET status = 'UNCERTAIN', errorMessage = :message, " +
            "completedAt = :now WHERE taskId = :taskId AND status = 'RUNNING'"
    )
    suspend fun markRunningToolExecutionsUncertain(
        taskId: Long,
        message: String,
        now: Long
    ): Int

    /** F5：历史工具结果候选；只返回成功记录，正文只取片段 */
    @Query(
        "SELECT id, taskId, toolName, toolDisplayName, status, " +
            "substr(resultJson, 1, :snippet) AS resultSnippet, " +
            "length(resultJson) AS resultLength, createdAt " +
            "FROM agent_tool_execution WHERE taskId = :taskId AND status = 'SUCCEEDED' " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit"
    )
    suspend fun materialToolResults(
        taskId: Long,
        snippet: Int,
        limit: Int
    ): List<AgentToolMaterialRow>

    @Query(
        "DELETE FROM agent_tool_execution WHERE taskId IN " +
            "(SELECT id FROM agent_task WHERE sessionId = :sessionId)"
    )
    suspend fun deleteToolExecutionsForSession(sessionId: Long)

    @Query("DELETE FROM agent_tool_execution WHERE taskId = :taskId")
    suspend fun deleteToolExecutionsForTask(taskId: Long)

    // ---------------- 任务级引用快照 ----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskReference(reference: AgentTaskReference): Long

    @Update
    suspend fun updateTaskReference(reference: AgentTaskReference)

    @Query(
        "SELECT * FROM agent_task_reference WHERE taskId = :taskId " +
            "ORDER BY selectedAt ASC, id ASC"
    )
    suspend fun taskReferences(taskId: Long): List<AgentTaskReference>

    @Query(
        "SELECT * FROM agent_task_reference WHERE taskId = :taskId " +
            "ORDER BY selectedAt ASC, id ASC"
    )
    fun observeTaskReferences(taskId: Long): Flow<List<AgentTaskReference>>

    @Query("DELETE FROM agent_task_reference WHERE taskId = :taskId")
    suspend fun deleteTaskReferencesForTask(taskId: Long)

    @Query(
        "DELETE FROM agent_task_reference WHERE taskId IN " +
            "(SELECT id FROM agent_task WHERE sessionId = :sessionId)"
    )
    suspend fun deleteTaskReferencesForSession(sessionId: Long)

    // ---------------- 受控写入操作 ----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWriteOperation(operation: AgentWriteOperation): Long

    @Update
    suspend fun updateWriteOperation(operation: AgentWriteOperation)

    @Query("SELECT * FROM agent_write_operation WHERE id = :id")
    suspend fun getWriteOperation(id: Long): AgentWriteOperation?

    @Query("SELECT * FROM agent_write_operation WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findWriteOperationByKey(key: String): AgentWriteOperation?

    @Query(
        "SELECT * FROM agent_write_operation WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun writeOperationsOfTask(taskId: Long): List<AgentWriteOperation>

    @Query(
        "SELECT * FROM agent_write_operation WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeWriteOperationsOfTask(taskId: Long): Flow<List<AgentWriteOperation>>

    @Query(
        "SELECT * FROM agent_write_operation WHERE taskId = :taskId " +
            "AND executionStatus IN (:statuses) ORDER BY createdAt ASC, id ASC"
    )
    suspend fun writeOperationsOfTaskWithStatuses(
        taskId: Long,
        statuses: List<String>
    ): List<AgentWriteOperation>

    @Query(
        "SELECT COUNT(*) FROM agent_write_operation WHERE taskId = :taskId " +
            "AND executionStatus IN (:statuses)"
    )
    suspend fun countWriteOperationsWithStatuses(
        taskId: Long,
        statuses: List<String>
    ): Int

    @Query(
        "SELECT COUNT(*) FROM agent_write_operation WHERE taskId = :taskId " +
            "AND nodeId = :nodeId"
    )
    suspend fun countWriteOperationsForNode(taskId: Long, nodeId: Long): Int

    @Query("DELETE FROM agent_write_operation WHERE taskId = :taskId")
    suspend fun deleteWriteOperationsForTask(taskId: Long)

    @Query(
        "DELETE FROM agent_write_operation WHERE taskId IN " +
            "(SELECT id FROM agent_task WHERE sessionId = :sessionId)"
    )
    suspend fun deleteWriteOperationsForSession(sessionId: Long)

    // ---------------- F7：模型请求记录 ----------------

    @Insert
    suspend fun insertModelRequest(request: AgentModelRequest): Long

    @Update
    suspend fun updateModelRequest(request: AgentModelRequest)

    @Query("SELECT * FROM agent_model_request WHERE id = :id")
    suspend fun getModelRequest(id: Long): AgentModelRequest?

    @Query("SELECT * FROM agent_model_request WHERE requestKey = :key LIMIT 1")
    suspend fun findModelRequestByKey(key: String): AgentModelRequest?

    @Query(
        "SELECT COALESCE(MAX(attempt), 0) FROM agent_model_request " +
            "WHERE taskId = :taskId AND nodeId = :nodeId AND round = :round"
    )
    suspend fun maxModelRequestAttempt(taskId: Long, nodeId: Long, round: Int): Int

    @Query(
        "SELECT * FROM agent_model_request WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun modelRequestsOfTask(taskId: Long): List<AgentModelRequest>

    @Query(
        "SELECT * FROM agent_model_request WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeModelRequestsOfTask(taskId: Long): Flow<List<AgentModelRequest>>

    @Query(
        "SELECT * FROM agent_model_request WHERE taskId = :taskId AND status = 'STARTED' " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun startedModelRequests(taskId: Long): List<AgentModelRequest>

    @Query(
        "UPDATE agent_model_request SET status = 'UNKNOWN', errorMessage = :message, " +
            "completedAt = :now, updatedAt = :now " +
            "WHERE taskId = :taskId AND status = 'STARTED'"
    )
    suspend fun markStartedModelRequestsUnknown(
        taskId: Long,
        message: String,
        now: Long
    ): Int

    @Query(
        "SELECT COUNT(*) FROM agent_model_request WHERE taskId = :taskId " +
            "AND status = 'SUCCEEDED' AND applied = 0"
    )
    suspend fun countUnappliedSucceededRequests(taskId: Long): Int

    @Query(
        "UPDATE agent_model_request SET status = 'UNKNOWN', errorMessage = :message, " +
            "updatedAt = :now WHERE taskId = :taskId AND status = 'SUCCEEDED' AND applied = 0"
    )
    suspend fun markUnappliedSucceededRequestsUnknown(
        taskId: Long,
        message: String,
        now: Long
    ): Int

    @Query("DELETE FROM agent_model_request WHERE taskId = :taskId")
    suspend fun deleteModelRequestsForTask(taskId: Long)

    @Query(
        "DELETE FROM agent_model_request WHERE taskId IN " +
            "(SELECT id FROM agent_task WHERE sessionId = :sessionId)"
    )
    suspend fun deleteModelRequestsForSession(sessionId: Long)

    // ---------------- F7：恢复日志 ----------------

    @Insert
    suspend fun insertRecoveryLog(log: AgentRecoveryLog): Long

    @Update
    suspend fun updateRecoveryLog(log: AgentRecoveryLog)

    @Query("SELECT * FROM agent_recovery_log WHERE id = :id")
    suspend fun getRecoveryLog(id: Long): AgentRecoveryLog?

    @Query(
        "SELECT * FROM agent_recovery_log WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun recoveryLogsOfTask(taskId: Long): List<AgentRecoveryLog>

    @Query(
        "SELECT * FROM agent_recovery_log WHERE taskId = :taskId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observeRecoveryLogsOfTask(taskId: Long): Flow<List<AgentRecoveryLog>>

    @Query(
        "SELECT * FROM agent_recovery_log WHERE taskId = :taskId " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1"
    )
    suspend fun latestRecoveryLog(taskId: Long): AgentRecoveryLog?

    @Query("DELETE FROM agent_recovery_log WHERE taskId = :taskId")
    suspend fun deleteRecoveryLogsForTask(taskId: Long)

    @Query(
        "DELETE FROM agent_recovery_log WHERE taskId IN " +
            "(SELECT id FROM agent_task WHERE sessionId = :sessionId)"
    )
    suspend fun deleteRecoveryLogsForSession(sessionId: Long)

    // ---------------- 澄清请求 ----------------

    @Insert
    suspend fun insertClarification(clarification: AgentClarification): Long

    @Update
    suspend fun updateClarification(clarification: AgentClarification)

    @Query("SELECT * FROM agent_clarification WHERE id = :id")
    suspend fun getClarification(id: Long): AgentClarification?

    @Query(
        "SELECT * FROM agent_clarification WHERE taskId = :taskId AND status = 'PENDING' " +
            "ORDER BY id DESC LIMIT 1"
    )
    suspend fun pendingClarification(taskId: Long): AgentClarification?

    @Query(
        "SELECT * FROM agent_clarification WHERE taskId = :taskId AND status = 'PENDING' " +
            "ORDER BY id DESC LIMIT 1"
    )
    fun observePendingClarifications(taskId: Long): Flow<List<AgentClarification>>

    @Query(
        "SELECT * FROM agent_clarification WHERE taskId = :taskId AND status = 'ANSWERED' " +
            "ORDER BY id ASC"
    )
    suspend fun answeredClarifications(taskId: Long): List<AgentClarification>

    @Query(
        "SELECT * FROM agent_clarification WHERE taskId = :taskId AND status = 'ANSWERED' " +
            "ORDER BY id ASC"
    )
    fun observeAnsweredClarifications(taskId: Long): Flow<List<AgentClarification>>

    @Query(
        "UPDATE agent_clarification SET status = 'CANCELLED', answeredAt = :now " +
            "WHERE taskId = :taskId AND status = 'PENDING'"
    )
    suspend fun cancelPendingClarifications(taskId: Long, now: Long): Int

    @Query("DELETE FROM agent_clarification WHERE taskId = :taskId")
    suspend fun deleteClarificationsForTask(taskId: Long)

    @Query(
        "DELETE FROM agent_clarification WHERE taskId IN " +
            "(SELECT id FROM agent_task WHERE sessionId = :sessionId)"
    )
    suspend fun deleteClarificationsForSession(sessionId: Long)

    // ---------------- 事件 ----------------

    @Insert
    suspend fun insertEvent(event: AgentEvent): Long

    @Query("SELECT * FROM agent_event WHERE taskId = :taskId ORDER BY sequence ASC, id ASC")
    suspend fun eventsOfTask(taskId: Long): List<AgentEvent>

    @Query("SELECT * FROM agent_event WHERE taskId = :taskId ORDER BY sequence ASC, id ASC")
    fun observeEventsOfTask(taskId: Long): Flow<List<AgentEvent>>

    @Query(
        "SELECT * FROM agent_event WHERE taskId = :taskId AND sequence > :sequence " +
            "ORDER BY sequence ASC, id ASC"
    )
    suspend fun eventsAfter(taskId: Long, sequence: Long): List<AgentEvent>

    @Query("SELECT * FROM agent_event WHERE taskId = :taskId ORDER BY sequence DESC LIMIT 1")
    suspend fun latestEvent(taskId: Long): AgentEvent?

    @Query("SELECT COALESCE(MAX(sequence), -1) FROM agent_event WHERE taskId = :taskId")
    suspend fun latestEventSequence(taskId: Long): Long

    @Query(
        "SELECT * FROM agent_event WHERE taskId = :taskId AND eventType = :eventType " +
            "ORDER BY sequence ASC, id ASC"
    )
    suspend fun eventsOfType(taskId: Long, eventType: String): List<AgentEvent>

    @Query(
        "DELETE FROM agent_event WHERE taskId IN " +
            "(SELECT id FROM agent_task WHERE sessionId = :sessionId)"
    )
    suspend fun deleteEventsForSession(sessionId: Long)

    @Query("DELETE FROM agent_event WHERE taskId = :taskId")
    suspend fun deleteEventsForTask(taskId: Long)
}
