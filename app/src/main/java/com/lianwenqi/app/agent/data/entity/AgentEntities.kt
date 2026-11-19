package com.lianwenqi.app.agent.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Agent 会话状态（字符串存储，便于后续扩展） */
object AgentSessionStatus {
    const val ACTIVE = "ACTIVE"
    const val ARCHIVED = "ARCHIVED"
}

/** Agent 消息角色 */
object AgentMessageRole {
    const val USER = "USER"
    const val ASSISTANT = "ASSISTANT"
}

/**
 * Agent 消息类型：
 * - [USER_INPUT]：用户下达任务 / 追问；
 * - [FINAL_ANSWER]：Agent 最终回答；
 * - [NOTICE]：系统提示（例如任务中断、来源失效），不参与模型上下文；
 * - [SUMMARY]：保留给后续任务摘要使用。
 */
object AgentMessageKind {
    const val USER_INPUT = "USER_INPUT"
    const val FINAL_ANSWER = "FINAL_ANSWER"
    const val NOTICE = "NOTICE"
    const val SUMMARY = "SUMMARY"
    /** F3：Agent 生成的执行计划说明（计划正文从 agent_task.planJson 读取） */
    const val PLAN = "PLAN"
}

/** 资料引用模式 */
object AgentReferenceMode {
    /** 进入上下文 */
    const val CONTEXT = "CONTEXT"

    /** 只在引用列表展示，不进入上下文 */
    const val REFERENCE_ONLY = "REFERENCE_ONLY"
}

/**
 * 资料引用来源类型。
 *
 * F1 只存引用元数据；F5 增加历史 Agent 资料的细分类型：
 * 任务计划、任务最终结果、具体消息、历史工具结果各自独立，
 * 避免把「整个会话」当成一个不可追溯的引用单位。
 *
 * [AGENT_SESSION] 仅保留常量，F5 明确拒绝整段会话引用；
 * [VOLUME_SYNOPSIS] / [CHAT_SESSION] 暂不提供引用入口。
 */
object AgentReferenceSourceType {
    const val CHAPTER_SYNOPSIS = "CHAPTER_SYNOPSIS"
    const val VOLUME_SYNOPSIS = "VOLUME_SYNOPSIS"
    const val CHAPTER_CONTENT = "CHAPTER_CONTENT"
    const val AGENT_SESSION = "AGENT_SESSION"
    const val CHAT_SESSION = "CHAT_SESSION"

    /** F5：历史任务的目标与计划（sourceId = agent_task.id） */
    const val AGENT_TASK_PLAN = "AGENT_TASK_PLAN"

    /** F5：历史任务的最终结果（sourceId = agent_task.id） */
    const val AGENT_TASK_RESULT = "AGENT_TASK_RESULT"

    /** F5：历史会话中的具体消息（sourceId = agent_message.id） */
    const val AGENT_MESSAGE = "AGENT_MESSAGE"

    /** F5：历史工具执行结果（sourceId = agent_tool_execution.id） */
    const val AGENT_TOOL_RESULT = "AGENT_TOOL_RESULT"

    /** F5 允许用户选择的来源类型 */
    val selectable: List<String> = listOf(
        CHAPTER_SYNOPSIS,
        CHAPTER_CONTENT,
        AGENT_TASK_PLAN,
        AGENT_TASK_RESULT,
        AGENT_MESSAGE,
        AGENT_TOOL_RESULT
    )

    /** F5 可以提供读取实现的来源类型 */
    val supportedByResolver: List<String> = selectable
}

/** 引用数量 / 元信息上限；Repository 与 Assembler 必须执行，不能只靠 UI */
object AgentReferenceLimits {
    /** 单个 Agent 会话引用资料数 */
    const val MAX_SESSION_REFERENCES = 50

    /** 单个任务实际使用的资料数（任务快照上限） */
    const val MAX_TASK_REFERENCES = 20

    /** 历史会话单次选择消息数 */
    const val MAX_AGENT_MESSAGE_REFERENCES = 20

    /** 单条资料标题快照长度 */
    const val MAX_TITLE_SNAPSHOT_CHARS = 200

    /** 单条资料元信息上限（字节） */
    const val MAX_METADATA_BYTES = 4 * 1024
}

/**
 * Agent 独立会话。
 *
 * 与普通聊天 [com.lianwenqi.app.data.entity.ChatSession] 完全分离：
 * 不共享表、不共享 Repository，避免 Agent 数据混入普通 AI 聊天。
 */
@Entity(
    tableName = "agent_session",
    indices = [Index("bookId"), Index("updatedAt")]
)
data class AgentSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 会话标题；新建默认「新任务」 */
    val title: String = DEFAULT_TITLE,
    /** 绑定书籍；为空表示未绑定（允许跨书讨论，写入前必须绑定） */
    val bookId: Long? = null,
    /** 会话默认模型 id；为空表示使用全局当前模型 */
    val modelId: Long? = null,
    /** 会话状态，见 [AgentSessionStatus] */
    val status: String = AgentSessionStatus.ACTIVE,
    /**
     * Agent 专用稳定前缀（JSON）。F1 不写入内容，F2 由 AgentContextAssembler 使用。
     * 与普通聊天的 [com.lianwenqi.app.data.entity.SessionPrefix] 无关。
     */
    val contextPrefix: String = "",
    /** 最近一次任务 id；F1 不创建任务，保留给 F2 */
    val lastTaskId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 最近一条消息时间；0 表示尚无消息 */
    val lastMessageAt: Long = 0
) {
    companion object {
        const val DEFAULT_TITLE = "新任务"
    }
}

/**
 * Agent 会话消息。
 *
 * 与 [com.lianwenqi.app.data.entity.ChatMessage] 分开存储：
 * - [sequence] 保证同一会话内顺序唯一（数据库唯一索引兜底）；
 * - [taskId] 使用可空字段表达「无任务消息」，不使用 0 作为保留值，
 *   避免与未来 F2 的真实任务 id 产生语义冲突；
 * - F1 只落库本地消息，不发送模型请求，因此 token 默认是「未知」而不是 0。
 */
@Entity(
    tableName = "agent_message",
    indices = [
        Index(value = ["sessionId", "sequence"], unique = true),
        Index("taskId")
    ]
)
data class AgentMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 所属任务；null 表示会话级消息（F1 全部为 null） */
    val taskId: Long? = null,
    /** 消息角色，见 [AgentMessageRole] */
    val role: String,
    /** 消息类型，见 [AgentMessageKind] */
    val kind: String,
    val content: String,
    /** 该轮可展示的思考摘要（F3 使用；F1 为空） */
    val thinkingSummary: String = "",
    /** 会话内展示顺序，从 0 开始递增 */
    val sequence: Long,
    /** 输入 token；[PROMPT_TOKENS_UNKNOWN] 表示尚未请求模型 / 服务端未提供 */
    val promptTokens: Int = PROMPT_TOKENS_UNKNOWN,
    /** 输出 token；0 表示未记录 */
    val completionTokens: Int = 0,
    /** 缓存命中 token；[CACHE_TOKENS_UNKNOWN] 表示未知，不能用 0 冒充「未命中」 */
    val cachedTokens: Int = CACHE_TOKENS_UNKNOWN,
    /** 本轮提示词日志（JSON，F2 使用；有长度上限） */
    val promptLog: String = "",
    /** 附件清单（JSON，复用普通聊天附件格式；F1 不写入） */
    val attachments: String = "",
    /** 发送时冻结的上下文文本快照（已选章节 / 大纲 / 附件正文）；供分支重规划恢复 */
    val contextSnapshot: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val PROMPT_TOKENS_UNKNOWN = -1
        const val CACHE_TOKENS_UNKNOWN = -1
    }
}

/**
 * Agent 会话的资料引用（F1 只建立引用元数据，不读取、不拼接内容）。
 *
 * 唯一约束 (sessionId, sourceType, sourceId) 保证同一会话内同一来源只引用一次；
 * 删除引用不会删除来源书籍 / 章节 / 梗概 / 会话。
 */
@Entity(
    tableName = "agent_session_reference",
    indices = [
        Index(value = ["sessionId", "sourceType", "sourceId"], unique = true),
        Index(value = ["sessionId", "enabled"])
    ]
)
data class AgentSessionReference(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属 Agent 会话 */
    val sessionId: Long,
    /** 来源类型，见 [AgentReferenceSourceType] */
    val sourceType: String,
    /** 来源 id（章节 id / 卷梗概 id / 会话 id） */
    val sourceId: Long,
    /** 来源书籍 id；可空，来源不存在时仍可展示标题快照 */
    val bookId: Long? = null,
    /** 章节类资料的章节 id */
    val chapterId: Long? = null,
    /** 卷梗概类资料的分卷 id */
    val volumeId: Long? = null,
    /** 会话类资料的起始轮次（按消息顺序，从 0 开始）；null 表示不限 */
    val rangeStart: Int? = null,
    /** 会话类资料的结束轮次；null 表示不限 */
    val rangeEnd: Int? = null,
    /** 选择当时的标题快照，来源改名 / 删除后仍可展示来源标识 */
    val titleSnapshot: String = "",
    /** 可选的内容指纹，用于后续检测来源内容是否变化 */
    val contentHash: String = "",
    /** 引用模式，见 [AgentReferenceMode] */
    val referenceMode: String = AgentReferenceMode.CONTEXT,
    /** 是否启用；停用后保留引用记录但不进入上下文 */
    val enabled: Boolean = true,
    val selectedAt: Long = System.currentTimeMillis()
)
