package com.lianwenqi.app.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 写入风险等级；高风险与破坏性操作不受一般写入自动授权影响 */
object AgentWriteRiskLevel {
    const val NORMAL_WRITE = "NORMAL_WRITE"
    const val HIGH_RISK_WRITE = "HIGH_RISK_WRITE"
    const val DESTRUCTIVE = "DESTRUCTIVE"

    val all: List<String> = listOf(NORMAL_WRITE, HIGH_RISK_WRITE, DESTRUCTIVE)

    fun requiresExplicitConfirmation(risk: String): Boolean = risk != NORMAL_WRITE
}

/** Agent 写入权限策略；默认每次询问 */
object AgentWritePermissionPolicy {
    /**
     * 低权限：每次操作都询问。
     * 任何写入（含一般写入）都必须由用户逐条确认后才执行。
     */
    const val ASK_EVERY_TIME = "ASK_EVERY_TIME"

    /**
     * 高权限：一般写入自动执行。
     * 修改梗概、创建 / 修改大纲等一般写入无需确认直接执行；
     * 覆盖正文、高风险写入与破坏性操作仍逐次确认。
     */
    const val AUTO_ALLOW_NORMAL = "AUTO_ALLOW_NORMAL"

    /**
     * 中权限：一般写入批量确认。
     * 一般写入先汇总为待确认列表，由用户一次性批量批准；
     * 高风险写入仍需逐条确认。
     */
    const val BATCH_CONFIRM = "BATCH_CONFIRM"

    val all: List<String> = listOf(ASK_EVERY_TIME, AUTO_ALLOW_NORMAL, BATCH_CONFIRM)

    fun isValid(value: String): Boolean = value in all

    fun label(value: String): String = when (value) {
        AUTO_ALLOW_NORMAL -> "一般写入自动执行"
        BATCH_CONFIRM -> "一般写入批量确认"
        else -> "每次操作都询问"
    }

    /** 只有一般写入 + AUTO_ALLOW_NORMAL 可以自动执行 */
    fun canAutoExecute(risk: String, policy: String): Boolean =
        risk == AgentWriteRiskLevel.NORMAL_WRITE && policy == AUTO_ALLOW_NORMAL
}

/** 写入操作类型 */
object AgentWriteOperationType {
    const val UPDATE_CHAPTER_SYNOPSIS = "UPDATE_CHAPTER_SYNOPSIS"
    const val UPDATE_CHAPTER_CONTENT = "UPDATE_CHAPTER_CONTENT"
    const val CREATE_OUTLINE_NODE = "CREATE_OUTLINE_NODE"
    const val UPDATE_OUTLINE_NODE = "UPDATE_OUTLINE_NODE"
    const val DELETE_OUTLINE_NODE = "DELETE_OUTLINE_NODE"
    const val CREATE_OUTLINE_CATEGORY = "CREATE_OUTLINE_CATEGORY"
    const val DELETE_OUTLINE_CATEGORY = "DELETE_OUTLINE_CATEGORY"
    const val MOVE_OUTLINE_NODE = "MOVE_OUTLINE_NODE"
    const val CREATE_CHAPTER = "CREATE_CHAPTER"
    const val DELETE_CHAPTER = "DELETE_CHAPTER"
    const val CREATE_VOLUME = "CREATE_VOLUME"
    const val DELETE_VOLUME = "DELETE_VOLUME"

    fun label(value: String): String = when (value) {
        UPDATE_CHAPTER_SYNOPSIS -> "修改章节梗概"
        UPDATE_CHAPTER_CONTENT -> "覆盖章节正文"
        CREATE_OUTLINE_NODE -> "创建大纲节点"
        UPDATE_OUTLINE_NODE -> "修改大纲节点"
        DELETE_OUTLINE_NODE -> "删除大纲节点"
        CREATE_OUTLINE_CATEGORY -> "新增大纲分类"
        DELETE_OUTLINE_CATEGORY -> "删除大纲分类"
        MOVE_OUTLINE_NODE -> "移动大纲节点"
        CREATE_CHAPTER -> "新建章节"
        DELETE_CHAPTER -> "删除章节"
        CREATE_VOLUME -> "新建分卷"
        DELETE_VOLUME -> "删除分卷"
        else -> value
    }
}

/** 写入目标类型 */
object AgentWriteTargetType {
    const val CHAPTER = "CHAPTER"
    const val OUTLINE = "OUTLINE"
    const val VOLUME = "VOLUME"
}

/** 权限状态（由本地权限系统写入，模型不可声明） */
object AgentWritePermissionStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val AUTO_APPROVED = "AUTO_APPROVED"
    const val REJECTED = "REJECTED"
}

/** 执行状态机 */
object AgentWriteOperationStatus {
    const val PROPOSED = "PROPOSED"
    const val WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
    const val EXECUTING = "EXECUTING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val CONFLICT = "CONFLICT"
    const val CANCELLED = "CANCELLED"
    const val ROLLED_BACK = "ROLLED_BACK"

    /** 尚未结束、会阻止任务重新启动的状态 */
    val unresolved: List<String> = listOf(
        PROPOSED, WAITING_CONFIRMATION, APPROVED, EXECUTING
    )

    /** 可以安全取消的状态（不包含正在执行） */
    val cancellable: List<String> = listOf(PROPOSED, WAITING_CONFIRMATION, APPROVED)

    val terminal: List<String> = listOf(
        REJECTED, SUCCEEDED, FAILED, CONFLICT, CANCELLED, ROLLED_BACK
    )

    fun isTerminal(value: String): Boolean = value in terminal

    fun label(value: String): String = when (value) {
        PROPOSED -> "已提议"
        WAITING_CONFIRMATION -> "等待确认"
        APPROVED -> "已批准"
        REJECTED -> "已拒绝"
        EXECUTING -> "正在写入"
        SUCCEEDED -> "写入成功"
        FAILED -> "写入失败"
        CONFLICT -> "数据冲突"
        CANCELLED -> "已取消"
        ROLLED_BACK -> "已撤销"
        else -> value
    }
}

/** F6 写入限制；Repository 与工具层共同执行 */
object AgentWriteLimits {
    const val MAX_UNRESOLVED_PER_TASK = 20
    const val MAX_SYNOPSIS_CHARS = 20_000
    /** 单次正文覆盖上限；同时受模型输出与数据库单行体积限制 */
    const val MAX_CONTENT_CHARS = 50_000
    const val MAX_OUTLINE_CONTENT_CHARS = 20_000
    const val MAX_OUTLINE_TITLE_CHARS = 200
    const val MAX_SUMMARY_CHARS = 500
    const val MAX_REASON_CHARS = 2_000

    /** 快照 / 结果单列上限，避免单行超过 SQLite CursorWindow */
    const val MAX_SNAPSHOT_CHARS = 200_000
}

/**
 * Agent 受控写入操作（F6）。
 *
 * 提议阶段只写本表，不写业务数据；确认后由执行服务重新校验并执行。
 * `idempotencyKey` 唯一索引是重复写入的数据库级硬保障。
 */
@Entity(
    tableName = "agent_write_operation",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["taskId", "executionStatus"]),
        Index("taskId")
    ]
)
data class AgentWriteOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val sessionId: Long,
    /** 提出该操作的 tool execution 记录；由 Executor 回填 */
    val toolExecutionId: Long? = null,
    /** 执行节点；用于步骤完成校验 */
    val nodeId: Long? = null,
    val toolName: String,
    /** 见 [AgentWriteOperationType] */
    val operationType: String,
    /** 见 [AgentWriteTargetType] */
    val targetType: String,
    /** 章节 id / 大纲 id；新建大纲时为 0，成功后回填 createdTargetId */
    val targetId: Long,
    val bookId: Long,
    /** 展示用目标名称快照 */
    val targetTitleSnapshot: String = "",
    val bookTitleSnapshot: String = "",
    /** 见 [AgentWriteRiskLevel] */
    val riskLevel: String,
    val argumentsJson: String = "{}",
    /** 执行前目标快照（JSON） */
    val beforeSnapshot: String = "{}",
    /** 执行后目标快照（JSON）；用于撤销冲突检测 */
    val afterSnapshot: String = "{}",
    /** 准备写入的正文 / 梗概 / 大纲内容（展示用） */
    val proposedContent: String = "",
    val operationSummary: String = "",
    /** 见 [AgentWritePermissionStatus] */
    val permissionStatus: String = AgentWritePermissionStatus.PENDING,
    /** 见 [AgentWriteOperationStatus] */
    val executionStatus: String = AgentWriteOperationStatus.PROPOSED,
    val idempotencyKey: String,
    /** 是否支持撤销；预览阶段必须如实展示 */
    val reversible: Boolean = true,
    /** 新建大纲成功后生成的目标 id */
    val createdTargetId: Long? = null,
    val errorMessage: String = "",
    /** 撤销冲突 / 撤销失败提示；不改变已成功的执行状态 */
    val rollbackMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val confirmedAt: Long? = null,
    val executedAt: Long? = null,
    val rolledBackAt: Long? = null
) {
    val unresolved: Boolean
        get() = executionStatus in AgentWriteOperationStatus.unresolved
}
