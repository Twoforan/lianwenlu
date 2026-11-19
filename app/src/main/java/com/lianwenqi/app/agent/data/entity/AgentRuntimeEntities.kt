package com.lianwenqi.app.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 模型请求状态（F7） */
object AgentModelRequestStatus {
    const val STARTED = "STARTED"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"

    /**
     * 请求已发出，但进程在收到响应前死亡。
     * 本地无法判断供应商是否已经处理，恢复时可以重新请求，但必须记录重复请求风险。
     */
    const val UNKNOWN = "UNKNOWN"
    const val CANCELLED = "CANCELLED"

    val all: List<String> = listOf(STARTED, SUCCEEDED, FAILED, UNKNOWN, CANCELLED)
    val terminal: List<String> = listOf(SUCCEEDED, FAILED, UNKNOWN, CANCELLED)

    fun isTerminal(value: String): Boolean = value in terminal
}

/**
 * 一次模型请求记录。
 *
 * 供应商不支持幂等请求，本表只能提供“请求是否开始 / 是否结束”的本地证据，
 * 不能保证重复请求不会产生额外费用。
 */
@Entity(
    tableName = "agent_model_request",
    indices = [
        Index(value = ["requestKey"], unique = true),
        Index("taskId"),
        Index(value = ["taskId", "status"]),
        Index("nodeId")
    ]
)
data class AgentModelRequest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val nodeId: Long? = null,
    val runId: String = "",
    /** 当前节点内的模型轮次，从 0 开始 */
    val round: Int = 0,
    /** 同一轮内的请求尝试次数，从 1 开始 */
    val attempt: Int = 1,
    /** `runId:nodeId:round:attempt`，唯一索引 */
    val requestKey: String,
    /** 见 [AgentModelRequestStatus] */
    val status: String = AgentModelRequestStatus.STARTED,
    val modelId: Long = 0,
    val modelName: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val latencyMs: Long? = null,
    /** F7 失败分类；成功时为空 */
    val failureKind: String = "",
    val errorMessage: String = "",
    val responseChars: Int = 0,
    val toolCallCount: Int = 0,
    /**
     * 响应是否已经被执行引擎消费（工具结果已落库 / 最终文本已写入节点）。
     * SUCCEEDED 但 applied=false 表示模型已返回、进程在保存处理结果前死亡，
     * 恢复后重新请求可能产生额外费用。
     */
    val applied: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 恢复结果 */
object AgentRecoveryResult {
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
    const val WAITING_CONFIRMATION = "WAITING_CONFIRMATION"

    val all: List<String> = listOf(SUCCEEDED, FAILED, SKIPPED, WAITING_CONFIRMATION)
}

/**
 * 恢复诊断日志。
 *
 * 只保存状态、计数和原因，不保存小说正文、提示词或 API Key。
 */
@Entity(
    tableName = "agent_recovery_log",
    indices = [
        Index("taskId"),
        Index(value = ["taskId", "createdAt"]),
        Index(value = ["taskId", "result"])
    ]
)
data class AgentRecoveryLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** 第几次任务级执行尝试 */
    val attempt: Int = 0,
    val reason: String = "",
    val originalStatus: String = "",
    val originalNodeId: Long? = null,
    val originalNodeTitle: String = "",
    val cursorBefore: Int = 0,
    val cursorAfter: Int = 0,
    val succeededNodeCount: Int = 0,
    val failedNodeCount: Int = 0,
    val uncertainToolCount: Int = 0,
    val unresolvedWriteCount: Int = 0,
    val duplicateRequestPossible: Boolean = false,
    val inconsistentDataFound: Boolean = false,
    /** 见 [AgentRecoveryResult] */
    val result: String = AgentRecoveryResult.SKIPPED,
    val message: String = "",
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
