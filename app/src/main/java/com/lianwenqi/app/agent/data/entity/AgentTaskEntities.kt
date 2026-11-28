package com.lianwenqi.app.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// ---------------- 任务状态 ----------------

/** Agent 任务状态（字符串存储，便于后续扩展） */
object AgentTaskStatus {
    const val CREATED = "CREATED"
    const val PLANNING = "PLANNING"
    const val RUNNING = "RUNNING"
    const val WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    /** 尚未结束、可能需要在应用重启后判断是否可恢复的任务 */
    val unfinished: List<String> = listOf(
        CREATED, PLANNING, RUNNING, WAITING_CONFIRMATION, PAUSED
    )

    /** 全部合法状态 */
    val all: List<String> = unfinished + listOf(COMPLETED, FAILED, CANCELLED)

    /** 终态：不允许被自动改写成其他状态 */
    val terminal: List<String> = listOf(COMPLETED, FAILED, CANCELLED)

    fun isTerminal(status: String): Boolean = status in terminal
}

/** Agent 执行节点状态 */
object AgentNodeStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val WAITING = "WAITING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
    const val CANCELLED = "CANCELLED"

    val all: List<String> = listOf(PENDING, RUNNING, WAITING, COMPLETED, FAILED, SKIPPED, CANCELLED)

    val terminal: List<String> = listOf(COMPLETED, FAILED, SKIPPED, CANCELLED)

    fun isTerminal(status: String): Boolean = status in terminal
}

/** Agent 工具执行状态 */
object AgentToolStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
    const val CANCELLED = "CANCELLED"

    /**
     * F7：进程在执行过程中死亡，工具可能已经产生副作用但结果未落库。
     * 只读工具可以安全重试；写入工具必须核对 agent_write_operation 的真实状态。
     */
    const val UNCERTAIN = "UNCERTAIN"

    val all: List<String> = listOf(
        PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED, CANCELLED, UNCERTAIN
    )

    val terminal: List<String> = listOf(SUCCEEDED, FAILED, SKIPPED, CANCELLED, UNCERTAIN)

    fun isTerminal(status: String): Boolean = status in terminal
}

/** Agent 任务工作模式；PLAN 仅保留给已有任务的历史兼容与展示。 */
object AgentWorkMode {
    /** 自动模式：Agent 自行判断是否规划，并在条件允许时自动开始执行 */
    const val AUTO = "AUTO"

    /** 历史计划模式：旧任务继续按原语义展示，不用于新建任务。 */
    const val PLAN = "PLAN"

    val all: List<String> = listOf(AUTO, PLAN)

    fun isValid(value: String): Boolean = value in all
}

/** Agent 任务分支来源 */
object AgentBranchSource {
    /** 用户复制 / 编辑原提问后重新生成 */
    const val USER_EDIT = "USER_EDIT"

    /** 用户要求重新生成计划 */
    const val PLAN_REGENERATE = "PLAN_REGENERATE"

    /** 用户直接修改计划并保存 */
    const val PLAN_EDIT = "PLAN_EDIT"
}

/** Agent 执行节点类型 */
object AgentNodeType {
    const val THINKING = "THINKING"
    const val PLANNING = "PLANNING"
    /** 计划步骤的通用节点 */
    const val PLAN_STEP = "PLAN_STEP"
    const val SEARCH_CHAPTER = "SEARCH_CHAPTER"
    const val SEARCH_OUTLINE = "SEARCH_OUTLINE"
    const val SEARCH_MEMORY = "SEARCH_MEMORY"
    const val SEARCH_CHARACTER = "SEARCH_CHARACTER"
    const val SEARCH_WORLD = "SEARCH_WORLD"
    const val TOOL_CALL = "TOOL_CALL"
    /** 对真实工具结果做分析 / 整理 */
    const val ANALYZE_RESULT = "ANALYZE_RESULT"
    const val GENERATE_SYNOPSIS = "GENERATE_SYNOPSIS"
    const val GENERATE_CONTENT = "GENERATE_CONTENT"
    const val VALIDATE = "VALIDATE"
    const val WRITE_DATA = "WRITE_DATA"
    const val WAIT_CONFIRM = "WAIT_CONFIRM"
    const val TASK_COMPLETE = "TASK_COMPLETE"
}

/**
 * Agent 事件类型。
 *
 * 除任务书要求的类型外，额外补充了 `NODE_CANCELLED`、`TOOL_CALL_SKIPPED`、
 * `TOOL_CALL_CANCELLED`，用于让「节点 / 工具被取消」也有明确事件，
 * 避免把它们伪装成 SKIPPED 或 FAILED。
 */
object AgentEventType {
    const val TASK_CREATED = "TASK_CREATED"
    const val TASK_STARTED = "TASK_STARTED"
    const val TASK_PLANNING = "TASK_PLANNING"
    const val TASK_PLAN_UPDATED = "TASK_PLAN_UPDATED"
    const val THINKING_STARTED = "THINKING_STARTED"
    const val THINKING_SUMMARY = "THINKING_SUMMARY"
    const val NODE_STARTED = "NODE_STARTED"
    const val NODE_PROGRESS = "NODE_PROGRESS"
    const val NODE_COMPLETED = "NODE_COMPLETED"
    const val NODE_FAILED = "NODE_FAILED"
    const val NODE_SKIPPED = "NODE_SKIPPED"
    const val NODE_CANCELLED = "NODE_CANCELLED"
    const val TOOL_CALL_STARTED = "TOOL_CALL_STARTED"
    const val TOOL_CALL_COMPLETED = "TOOL_CALL_COMPLETED"
    const val TOOL_CALL_FAILED = "TOOL_CALL_FAILED"
    const val TOOL_CALL_SKIPPED = "TOOL_CALL_SKIPPED"
    const val TOOL_CALL_CANCELLED = "TOOL_CALL_CANCELLED"
    const val WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION"
    const val CONFIRMATION_RECEIVED = "CONFIRMATION_RECEIVED"
    const val TASK_PAUSED = "TASK_PAUSED"
    const val TASK_RESUMED = "TASK_RESUMED"
    const val TASK_COMPLETED = "TASK_COMPLETED"
    const val TASK_FAILED = "TASK_FAILED"
    const val TASK_CANCELLED = "TASK_CANCELLED"

    // F7：后台执行、执行租约与进程恢复
    const val TASK_RECOVERY_STARTED = "TASK_RECOVERY_STARTED"
    const val TASK_RECOVERY_COMPLETED = "TASK_RECOVERY_COMPLETED"
    const val TASK_RECOVERY_FAILED = "TASK_RECOVERY_FAILED"
    const val TASK_RECOVERY_SKIPPED = "TASK_RECOVERY_SKIPPED"
    const val TASK_EXECUTION_LEASE_ACQUIRED = "TASK_EXECUTION_LEASE_ACQUIRED"
    const val TASK_EXECUTION_LEASE_EXPIRED = "TASK_EXECUTION_LEASE_EXPIRED"
    const val TASK_INTERRUPTED = "TASK_INTERRUPTED"
    const val TASK_RETRY_SCHEDULED = "TASK_RETRY_SCHEDULED"
    const val NODE_RECOVERED = "NODE_RECOVERED"
    const val MODEL_REQUEST_STARTED = "MODEL_REQUEST_STARTED"
    const val MODEL_REQUEST_COMPLETED = "MODEL_REQUEST_COMPLETED"
    const val MODEL_REQUEST_FAILED = "MODEL_REQUEST_FAILED"
    const val MODEL_REQUEST_UNKNOWN = "MODEL_REQUEST_UNKNOWN"

    // F6：受控写入操作事件
    const val WRITE_OPERATION_PROPOSED = "WRITE_OPERATION_PROPOSED"
    const val WRITE_OPERATION_WAITING_CONFIRMATION = "WRITE_OPERATION_WAITING_CONFIRMATION"
    const val WRITE_OPERATION_APPROVED = "WRITE_OPERATION_APPROVED"
    const val WRITE_OPERATION_REJECTED = "WRITE_OPERATION_REJECTED"
    const val WRITE_OPERATION_STARTED = "WRITE_OPERATION_STARTED"
    const val WRITE_OPERATION_SUCCEEDED = "WRITE_OPERATION_SUCCEEDED"
    const val WRITE_OPERATION_FAILED = "WRITE_OPERATION_FAILED"
    const val WRITE_OPERATION_CONFLICT = "WRITE_OPERATION_CONFLICT"
    const val WRITE_OPERATION_ROLLED_BACK = "WRITE_OPERATION_ROLLED_BACK"
    const val WRITE_OPERATION_CANCELLED = "WRITE_OPERATION_CANCELLED"

    // 用户补充信息
    const val CLARIFICATION_REQUESTED = "CLARIFICATION_REQUESTED"
    const val CLARIFICATION_ANSWERED = "CLARIFICATION_ANSWERED"
    const val CLARIFICATION_CANCELLED = "CLARIFICATION_CANCELLED"
}

/**
 * Agent 任务。
 *
 * 一个会话可包含多个任务；[userGoal] 保留用户原始输入，不能被模型改写版本覆盖。
 * 任务状态以本表 [status] 为唯一事实来源，事件表只用于审计与恢复。
 */
@Entity(
    tableName = "agent_task",
    indices = [
        Index("sessionId"),
        Index("status"),
        Index("updatedAt"),
        Index("parentTaskId"),
        Index("rootTaskId")
    ]
)
data class AgentTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 任务标题（展示用，可后续由用户或 Agent 修改） */
    val title: String = DEFAULT_TITLE,
    /** 用户原始目标，必须原样保留 */
    val userGoal: String,
    /** 任务状态，见 [AgentTaskStatus] */
    val status: String = AgentTaskStatus.CREATED,
    /** 结构化执行计划（JSON）。F2 只存储，不生成 */
    val planJson: String = "{}",
    /** 当前执行节点；可空 */
    val currentNodeId: Long? = null,
    /** 本任务使用的模型；可空 */
    val modelId: Long? = null,
    /** 创建时固化的模式；旧记录仍可保留 [AgentWorkMode.PLAN]。 */
    val workMode: String = AgentWorkMode.AUTO,
    /** 父任务 id；null 表示根任务 */
    val parentTaskId: Long? = null,
    /** 根任务 id；根任务为 null，分支任务记录所属根任务 */
    val rootTaskId: Long? = null,
    /** 分支创建来源，见 [AgentBranchSource]；根任务为空 */
    val branchSource: String = "",
    /** 任务级错误信息 */
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,

    // ---------------- F7：执行租约与持久化游标 ----------------

    /** 当前执行者标识；空表示没有执行者持有租约 */
    val executionOwner: String = "",
    /** 租约到期时间；null 表示没有租约 */
    val executionLeaseUntil: Long? = null,
    /** 任务级执行尝试次数（含恢复），每次取得租约 +1 */
    val executionAttempt: Int = 0,
    val lastHeartbeatAt: Long? = null,
    /** 最近一次真正开始执行的时间 */
    val lastExecutionAt: Long? = null,
    /** 当前执行批次 id；用于把节点 / 工具记录归入同一次执行 */
    val executionRunId: String = "",
    /** 下一个待执行步骤索引（0 基），已完成步骤不再重跑 */
    val executionCursor: Int = 0,
    /** 用户请求暂停；执行器在安全边界读取并落为 PAUSED */
    val pauseRequested: Boolean = false,
    /** 用户请求取消；优先级高于暂停 */
    val cancelRequested: Boolean = false,
    val recoveryCount: Int = 0,
    val lastRecoveryAt: Long? = null,
    /** F7 失败分类；空表示没有记录 */
    val lastErrorKind: String = ""
) {
    companion object {
        const val DEFAULT_TITLE = "新任务"

        /** 用户主动点「结束」时写入的原因；界面据此展示「任务完成」而不是失败 */
        const val USER_ENDED_REASON = "用户主动选择结束"
    }
}

/**
 * Agent 执行节点。
 *
 * [sequence] 在任务内稳定排序，数据库唯一索引兜底；
 * [nodeKey] 是执行引擎侧使用的稳定标识，不强制唯一（允许重试产生同名节点）。
 */
@Entity(
    tableName = "agent_execution_node",
    indices = [
        Index(value = ["taskId", "sequence"], unique = true),
        Index(value = ["taskId", "status"]),
        Index(value = ["taskId", "nodeKey"]),
        Index("parentNodeId")
    ]
)
data class AgentExecutionNode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** 节点稳定标识（执行引擎使用，便于按业务键定位） */
    val nodeKey: String = "",
    /** 父节点；F2 不实现并行执行，仅保留层级 */
    val parentNodeId: Long? = null,
    /** 节点类型，见 [AgentNodeType] */
    val nodeType: String,
    val title: String = "",
    val description: String = "",
    /** 节点状态，见 [AgentNodeStatus] */
    val status: String = AgentNodeStatus.PENDING,
    /** 任务内排序序号，从 0 开始 */
    val sequence: Long,
    /** 节点输入摘要（JSON） */
    val inputJson: String = "{}",
    /** 节点输出摘要（JSON） */
    val outputJson: String = "{}",
    val errorMessage: String = "",
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Agent 工具执行记录。
 *
 * 每次工具调用独立一行；[idempotencyKey] 仅提供查询能力，F2 不做自动去重或重放。
 * 参数 / 结果使用结构化 JSON 字符串，不写入普通聊天消息表。
 */
@Entity(
    tableName = "agent_tool_execution",
    indices = [
        Index("taskId"),
        Index("nodeId"),
        Index(value = ["taskId", "status"]),
        Index("idempotencyKey")
    ]
)
data class AgentToolExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** 所属节点；可空（任务级工具调用） */
    val nodeId: Long? = null,
    val toolName: String,
    val toolDisplayName: String = "",
    /** 执行状态，见 [AgentToolStatus] */
    val status: String = AgentToolStatus.PENDING,
    /** 调用参数（JSON） */
    val argumentsJson: String = "{}",
    /** 执行结果（JSON） */
    val resultJson: String = "{}",
    val errorMessage: String = "",
    /** 幂等标识；F2 只存不自动使用 */
    val idempotencyKey: String? = null,
    /** 同一任务 / 节点 / 工具的调用序号，从 1 开始 */
    val attempt: Int = 1,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val durationMs: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Agent 任务事件（追加写入，不更新旧事件）。
 *
 * `(taskId, sequence)` 唯一；sequence 由 Repository 在事务内基于当前最大值 +1 生成。
 * 任务当前状态仍以 [AgentTask.status] 为准，事件只用于审计、实时 UI 与恢复判断。
 */
@Entity(
    tableName = "agent_event",
    indices = [
        Index(value = ["taskId", "sequence"], unique = true),
        Index(value = ["taskId", "eventType"]),
        Index("nodeId"),
        Index("toolExecutionId")
    ]
)
data class AgentEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** 任务内严格递增序号，从 0 开始 */
    val sequence: Long,
    /** 事件类型，见 [AgentEventType] */
    val eventType: String,
    /** 关联节点；可空 */
    val nodeId: Long? = null,
    /** 关联工具执行记录；可空 */
    val toolExecutionId: Long? = null,
    /** 事件结构化内容（必须是可解析 JSON） */
    val payloadJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 任务级引用快照（F5）。
 *
 * 冻结时机：任务第一次开始只读执行时，把当前会话中已启用的引用复制一份。
 * 执行期间只读本表，不再读 `agent_session_reference`；
 * 用户后续修改会话引用只影响下一次任务。
 *
 * 删除引用快照不会删除来源书籍 / 章节 / 梗概 / 会话；
 * 本表只保存来源定位、标题快照与本次实际注入结果，不复制来源正文。
 */
@Entity(
    tableName = "agent_task_reference",
    indices = [
        Index(value = ["taskId", "sourceType", "sourceId"], unique = true),
        Index("taskId"),
        Index("sessionId")
    ]
)
data class AgentTaskReference(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val sessionId: Long,
    /** 来源类型，见 [AgentReferenceSourceType] */
    val sourceType: String,
    /** 来源 id（章节 / 任务 / 消息 / 工具执行） */
    val sourceId: Long,
    val bookId: Long? = null,
    val chapterId: Long? = null,
    /** 引用模式，见 [AgentReferenceMode]；冻结后不随会话引用变化 */
    val referenceMode: String,
    val selectedAt: Long,
    val sourceTitleSnapshot: String = "",
    /** 来源最后更新时间（章节 / 任务）；不支持时为空 */
    val sourceUpdatedAt: Long? = null,
    /** 是否已经过 Assembler 校验；false 表示尚未开始执行 */
    val validated: Boolean = false,
    val valid: Boolean = false,
    val invalidReason: String = "",
    /** 是否真正进入模型上下文 */
    val injected: Boolean = false,
    val truncated: Boolean = false,
    /** 实际注入内容的起始偏移（正文分页引用） */
    val contentStart: Int? = null,
    /** 实际注入字符数 */
    val contentChars: Int = 0,
    /** 来源完整内容字符数；0 表示未知 */
    val contentTotalChars: Int = 0,
    /** 注入内容的 Token 估算值，不是供应商 usage */
    val estimatedTokens: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** Agent 澄清请求状态 */
object AgentClarificationStatus {
    const val PENDING = "PENDING"
    const val ANSWERED = "ANSWERED"
    const val CANCELLED = "CANCELLED"

    val all: List<String> = listOf(PENDING, ANSWERED, CANCELLED)
}

/**
 * Agent 澄清请求。
 *
 * 规划结果声明 needsClarification 时写入一条待回答记录；
 * 用户提交的答案只有一条，回答后规划在同一任务内继续，不创建任务分支。
 */
@Entity(
    tableName = "agent_clarification",
    indices = [
        Index("taskId"),
        Index(value = ["taskId", "status"])
    ]
)
data class AgentClarification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val taskId: Long,
    /** 需要用户补充的问题正文 */
    val question: String,
    /** 候选回答（JSON 字符串数组）；为空表示只允许自由输入 */
    val optionsJson: String = "[]",
    /** 见 [AgentClarificationStatus] */
    val status: String = AgentClarificationStatus.PENDING,
    /** 用户提交的补充信息 */
    val answer: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val answeredAt: Long? = null
)

