package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.put

/** 批量创建执行节点的输入 */
data class AgentNodeSpec(
    val nodeKey: String,
    val nodeType: String,
    val title: String,
    val description: String = "",
    val parentNodeId: Long? = null,
    val inputJson: String = "{}"
)

/**
 * Agent 执行节点仓库。
 *
 * 节点按 `(taskId, sequence)` 稳定排序；状态变化与对应事件在同一事务内完成。
 * F2 不实现并行执行，父子关系只作为数据保留。
 */
class AgentExecutionNodeRepository(private val db: AppDatabase) {

    private val dao = db.agentTaskDao()
    private val events = AgentEventWriter(dao)

    /**
     * 创建节点（初始状态固定为 PENDING，不追加事件）。
     * [sequence] 为空时取任务内最大值 +1；显式传入时由数据库唯一索引兜底。
     */
    suspend fun createNode(
        taskId: Long,
        nodeType: String,
        title: String,
        nodeKey: String = "",
        description: String = "",
        parentNodeId: Long? = null,
        sequence: Long? = null,
        inputJson: String = "{}"
    ): Long {
        require(nodeType.isNotBlank()) { "nodeType 不能为空" }
        require(title.isNotBlank()) { "title 不能为空" }
        requireJsonObject(inputJson, "inputJson")
        return db.withTransaction {
            require(dao.getTask(taskId) != null) { "Agent 任务不存在：$taskId" }
            if (parentNodeId != null) {
                val parent = dao.getNode(parentNodeId)
                    ?: throw IllegalArgumentException("父节点不存在：$parentNodeId")
                require(parent.taskId == taskId) { "父节点 $parentNodeId 不属于任务 $taskId" }
            }
            val seq = sequence ?: (dao.maxNodeSequence(taskId) + 1)
            require(seq >= 0) { "sequence 不能为负数" }
            val now = System.currentTimeMillis()
            dao.insertNode(
                AgentExecutionNode(
                    taskId = taskId,
                    nodeKey = nodeKey,
                    parentNodeId = parentNodeId,
                    nodeType = nodeType,
                    title = title,
                    description = description,
                    status = AgentNodeStatus.PENDING,
                    sequence = seq,
                    inputJson = inputJson,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun getNode(id: Long): AgentExecutionNode? = dao.getNode(id)

    /**
     * 批量创建节点：同一事务内按 [startSequence] 起连续分配 sequence。
     * 用于 F4 执行前一次性建立全部计划步骤节点；任一条失败整体回滚。
     */
    suspend fun createNodes(
        taskId: Long,
        specs: List<AgentNodeSpec>,
        startSequence: Long = -1L
    ): List<Long> {
        if (specs.isEmpty()) return emptyList()
        specs.forEach {
            require(it.nodeType.isNotBlank()) { "nodeType 不能为空" }
            require(it.title.isNotBlank()) { "title 不能为空" }
            requireJsonObject(it.inputJson, "inputJson")
        }
        return db.withTransaction {
            require(dao.getTask(taskId) != null) { "Agent 任务不存在：$taskId" }
            var sequence = if (startSequence >= 0) startSequence else dao.maxNodeSequence(taskId) + 1
            specs.map { spec ->
                if (spec.parentNodeId != null) {
                    val parent = dao.getNode(spec.parentNodeId)
                        ?: throw IllegalArgumentException("父节点不存在：${spec.parentNodeId}")
                    require(parent.taskId == taskId) {
                        "父节点 ${spec.parentNodeId} 不属于任务 $taskId"
                    }
                }
                val now = System.currentTimeMillis()
                dao.insertNode(
                    AgentExecutionNode(
                        taskId = taskId,
                        nodeKey = spec.nodeKey,
                        parentNodeId = spec.parentNodeId,
                        nodeType = spec.nodeType,
                        title = spec.title,
                        description = spec.description,
                        status = AgentNodeStatus.PENDING,
                        sequence = sequence++,
                        inputJson = spec.inputJson,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }
    }

    suspend fun nodesOfTask(taskId: Long): List<AgentExecutionNode> = dao.nodesOfTask(taskId)

    fun observeNodes(taskId: Long): Flow<List<AgentExecutionNode>> = dao.observeNodesOfTask(taskId)

    suspend fun nodesWithStatus(taskId: Long, status: String): List<AgentExecutionNode> =
        dao.nodesOfTaskWithStatuses(taskId, listOf(status))

    suspend fun nodesWithStatuses(
        taskId: Long,
        statuses: List<String>
    ): List<AgentExecutionNode> = dao.nodesOfTaskWithStatuses(taskId, statuses)

    /** 中断后用于判断哪些节点停在执行中 */
    suspend fun runningNodes(taskId: Long): List<AgentExecutionNode> =
        nodesWithStatus(taskId, AgentNodeStatus.RUNNING)

    suspend fun completedNodes(taskId: Long): List<AgentExecutionNode> =
        nodesWithStatus(taskId, AgentNodeStatus.COMPLETED)

    suspend fun countCompleted(taskId: Long): Int =
        dao.countNodesWithStatus(taskId, AgentNodeStatus.COMPLETED)

    suspend fun countFailed(taskId: Long): Int =
        dao.countNodesWithStatus(taskId, AgentNodeStatus.FAILED)

    suspend fun interruptedNodes(taskId: Long): List<AgentExecutionNode> =
        dao.interruptedNodes(taskId)

    /**
     * F7：把中断遗留的 RUNNING / WAITING 节点重新打开为 PENDING。
     *
     * 只允许持有执行租约的恢复流程调用；不用于正常执行路径。
     */
    suspend fun reopenForRecovery(nodeId: Long, reason: String): Boolean = db.withTransaction {
        val node = dao.getNode(nodeId) ?: return@withTransaction false
        if (node.status != AgentNodeStatus.RUNNING && node.status != AgentNodeStatus.WAITING) {
            return@withTransaction false
        }
        val now = System.currentTimeMillis()
        val affected = dao.reopenNodeForRecovery(nodeId, reason, now)
        if (affected != 1) return@withTransaction false
        events.append(
            taskId = node.taskId,
            eventType = AgentEventType.NODE_RECOVERED,
            nodeId = nodeId,
            payloadJson = jsonPayload {
                put("from", node.status)
                put("to", AgentNodeStatus.PENDING)
                put("reason", reason.take(500))
            }
        )
        true
    }

    /**
     * 用户手动重试失败节点：仅允许 FAILED → PENDING。
     *
     * 重新执行时引擎会创建新的同参数调用计数，因此重复调用上限也随之重置。
     */
    suspend fun reopenFailedForRetry(nodeId: Long, reason: String): Boolean = db.withTransaction {
        val node = dao.getNode(nodeId) ?: return@withTransaction false
        // 未开始的当前节点无需重开，直接从它继续
        if (node.status == AgentNodeStatus.PENDING) return@withTransaction true
        if (node.status != AgentNodeStatus.FAILED) return@withTransaction false
        val now = System.currentTimeMillis()
        val affected = dao.reopenFailedNodeForRetry(nodeId, now)
        if (affected != 1) return@withTransaction false
        events.append(
            taskId = node.taskId,
            eventType = AgentEventType.NODE_RECOVERED,
            nodeId = nodeId,
            payloadJson = jsonPayload {
                put("from", node.status)
                put("to", AgentNodeStatus.PENDING)
                put("reason", reason.take(500))
            }
        )
        true
    }

    suspend fun maxSequence(taskId: Long): Long = dao.maxNodeSequence(taskId)

    /** 更新节点展示信息与结构化输入 / 输出；不改变状态，不追加事件 */
    suspend fun updateContent(
        nodeId: Long,
        title: String? = null,
        description: String? = null,
        inputJson: String? = null,
        outputJson: String? = null,
        errorMessage: String? = null
    ) = db.withTransaction {
        val node = dao.getNode(nodeId) ?: throw IllegalArgumentException("执行节点不存在：$nodeId")
        require(!AgentNodeStatus.isTerminal(node.status)) { "节点已结束，不能修改内容" }
        inputJson?.let { requireJsonObject(it, "inputJson") }
        outputJson?.let { requireJsonObject(it, "outputJson") }
        dao.updateNode(
            node.copy(
                title = title?.takeIf { it.isNotBlank() } ?: node.title,
                description = description ?: node.description,
                inputJson = inputJson ?: node.inputJson,
                outputJson = outputJson ?: node.outputJson,
                errorMessage = errorMessage ?: node.errorMessage,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 更新节点状态，并在同一事务内追加对应事件。
     *
     * 终态节点不可再改写；不允许把节点回退到 PENDING。
     */
    suspend fun updateStatus(
        nodeId: Long,
        status: String,
        outputJson: String? = null,
        errorMessage: String? = null
    ): Unit = db.withTransaction {
        require(status in AgentNodeStatus.all) { "非法节点状态：$status" }
        val node = dao.getNode(nodeId) ?: throw IllegalArgumentException("执行节点不存在：$nodeId")
        require(!AgentNodeStatus.isTerminal(node.status) || status == node.status) {
            "节点已处于终态 ${node.status}，不能再改为 $status"
        }
        if (status != node.status) {
            require(status != AgentNodeStatus.PENDING) { "节点不能回退到 PENDING" }
        }
        val now = System.currentTimeMillis()
        if (node.status == status) {
            if (errorMessage != null && errorMessage != node.errorMessage) {
                dao.updateNode(node.copy(errorMessage = errorMessage, updatedAt = now))
            }
            return@withTransaction
        }
        outputJson?.let { requireJsonObject(it, "outputJson") }
        val next = node.copy(
            status = status,
            outputJson = outputJson ?: node.outputJson,
            // 完成后清除历史错误，避免重试成功的节点仍显示红色失败信息
            errorMessage = errorMessage
                ?: if (status == AgentNodeStatus.COMPLETED) "" else node.errorMessage,
            startedAt = if (status == AgentNodeStatus.RUNNING) node.startedAt ?: now else node.startedAt,
            completedAt = if (AgentNodeStatus.isTerminal(status)) node.completedAt ?: now else node.completedAt,
            updatedAt = now
        )
        dao.updateNode(next)
        events.append(
            taskId = node.taskId,
            eventType = eventTypeFor(status),
            nodeId = nodeId,
            payloadJson = jsonPayload {
                put("from", node.status)
                put("to", status)
                if (next.errorMessage.isNotBlank()) put("errorMessage", next.errorMessage)
            }
        )
        Unit
    }

    private fun eventTypeFor(status: String): String = when (status) {
        AgentNodeStatus.RUNNING -> AgentEventType.NODE_STARTED
        AgentNodeStatus.WAITING -> AgentEventType.NODE_PROGRESS
        AgentNodeStatus.COMPLETED -> AgentEventType.NODE_COMPLETED
        AgentNodeStatus.FAILED -> AgentEventType.NODE_FAILED
        AgentNodeStatus.SKIPPED -> AgentEventType.NODE_SKIPPED
        AgentNodeStatus.CANCELLED -> AgentEventType.NODE_CANCELLED
        else -> AgentEventType.NODE_PROGRESS
    }
}
