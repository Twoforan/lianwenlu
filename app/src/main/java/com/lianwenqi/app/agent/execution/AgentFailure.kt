package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.ai.AiError
import java.io.IOException
import java.net.SocketTimeoutException

/** F7 统一失败分类；数据库错误不得伪装成模型错误 */
object AgentFailureKind {
    const val NETWORK = "NETWORK"
    const val TIMEOUT = "TIMEOUT"
    const val RATE_LIMIT = "RATE_LIMIT"
    const val PROVIDER_ERROR = "PROVIDER_ERROR"
    const val MODEL_PARSE = "MODEL_PARSE"
    const val MODEL_UNSUPPORTED = "MODEL_UNSUPPORTED"
    const val TOOL_ARGUMENT = "TOOL_ARGUMENT"
    const val VALIDATION = "VALIDATION"
    const val WRITE_CONFLICT = "WRITE_CONFLICT"
    const val USER_REJECTED = "USER_REJECTED"
    const val USER_CANCELLED = "USER_CANCELLED"
    const val DATABASE = "DATABASE"
    const val BACKGROUND_LIMIT = "BACKGROUND_LIMIT"
    const val LEASE_LOST = "LEASE_LOST"
    const val UNKNOWN = "UNKNOWN"

    val all: List<String> = listOf(
        NETWORK, TIMEOUT, RATE_LIMIT, PROVIDER_ERROR, MODEL_PARSE, MODEL_UNSUPPORTED,
        TOOL_ARGUMENT, VALIDATION, WRITE_CONFLICT, USER_REJECTED, USER_CANCELLED,
        DATABASE, BACKGROUND_LIMIT, LEASE_LOST, UNKNOWN
    )

    fun label(value: String): String = when (value) {
        NETWORK -> "网络连接失败"
        TIMEOUT -> "请求超时"
        RATE_LIMIT -> "供应商限流"
        PROVIDER_ERROR -> "供应商服务错误"
        MODEL_PARSE -> "模型响应解析失败"
        MODEL_UNSUPPORTED -> "模型不支持工具调用"
        TOOL_ARGUMENT -> "工具参数错误"
        VALIDATION -> "业务数据校验失败"
        WRITE_CONFLICT -> "写入冲突"
        USER_REJECTED -> "用户拒绝"
        USER_CANCELLED -> "用户取消"
        DATABASE -> "数据库异常"
        BACKGROUND_LIMIT -> "Android 后台执行限制"
        LEASE_LOST -> "执行租约失效"
        else -> "未知错误"
    }
}

/** 一次重试决策 */
data class AgentRetryDecision(
    val retryable: Boolean,
    val maxAttempts: Int,
    val delayMs: Long
)

/** 所有重试次数与间隔集中在此，禁止各模块自定义不同策略 */
object AgentRetryPolicy {

    const val MAX_TASK_ATTEMPTS = 5
    const val MAX_MODEL_REQUEST_ATTEMPTS = 3

    const val TASK_BACKOFF_BASE_SECONDS = 30L
    const val TASK_BACKOFF_MAX_SECONDS = 15 * 60L

    const val MODEL_RETRY_BASE_DELAY_MS = 1_000L
    const val MODEL_RETRY_MAX_DELAY_MS = 10_000L
    const val RATE_LIMIT_RETRY_DELAY_MS = 5_000L

    fun isTransient(kind: String): Boolean = kind in setOf(
        AgentFailureKind.NETWORK,
        AgentFailureKind.TIMEOUT,
        AgentFailureKind.RATE_LIMIT,
        AgentFailureKind.PROVIDER_ERROR,
        AgentFailureKind.DATABASE,
        AgentFailureKind.BACKGROUND_LIMIT
    )

    fun taskDecision(kind: String, attempt: Int): AgentRetryDecision {
        val max = when (kind) {
            AgentFailureKind.NETWORK,
            AgentFailureKind.TIMEOUT,
            AgentFailureKind.RATE_LIMIT,
            AgentFailureKind.PROVIDER_ERROR,
            AgentFailureKind.BACKGROUND_LIMIT -> MAX_TASK_ATTEMPTS

            AgentFailureKind.DATABASE -> 3

            else -> 1
        }
        return AgentRetryDecision(
            retryable = attempt < max && isTransient(kind),
            maxAttempts = max,
            delayMs = when (kind) {
                AgentFailureKind.RATE_LIMIT -> 15_000L
                else -> TASK_BACKOFF_BASE_SECONDS * 1_000L
            }
        )
    }

    fun modelDecision(kind: String, attempt: Int): AgentRetryDecision {
        val max = when (kind) {
            AgentFailureKind.NETWORK,
            AgentFailureKind.TIMEOUT,
            AgentFailureKind.PROVIDER_ERROR,
            AgentFailureKind.RATE_LIMIT -> MAX_MODEL_REQUEST_ATTEMPTS

            else -> 1
        }
        val delay = when (kind) {
            AgentFailureKind.RATE_LIMIT -> RATE_LIMIT_RETRY_DELAY_MS
            AgentFailureKind.NETWORK, AgentFailureKind.TIMEOUT ->
                (MODEL_RETRY_BASE_DELAY_MS * attempt).coerceAtMost(MODEL_RETRY_MAX_DELAY_MS)

            else -> 0L
        }
        return AgentRetryDecision(
            retryable = attempt < max && isTransient(kind),
            maxAttempts = max,
            delayMs = delay
        )
    }

    /** WorkManager 退避秒数；真正调度仍由 WorkManager 的 BackoffPolicy 执行 */
    fun workBackoffSeconds(kind: String): Long = when (kind) {
        AgentFailureKind.RATE_LIMIT -> 60L
        AgentFailureKind.DATABASE -> 60L
        else -> TASK_BACKOFF_BASE_SECONDS
    }
}

/** 失败分类器：异常、错误消息、模型错误统一映射 */
object AgentFailureClassifier {

    fun fromThrowable(error: Throwable): String = when (error) {
        is SocketTimeoutException -> AgentFailureKind.TIMEOUT
        is IOException -> AgentFailureKind.NETWORK
        is android.database.sqlite.SQLiteException -> AgentFailureKind.DATABASE
        is IllegalArgumentException -> AgentFailureKind.TOOL_ARGUMENT
        else -> fromMessage(error.message.orEmpty())
    }

    fun fromAiError(error: AiError?): String = when (error) {
        is AiError.Network -> AgentFailureKind.NETWORK
        is AiError.Timeout -> AgentFailureKind.TIMEOUT
        is AiError.Server -> when {
            error.code == 429 -> AgentFailureKind.RATE_LIMIT
            error.code in 500..599 -> AgentFailureKind.PROVIDER_ERROR
            else -> AgentFailureKind.UNKNOWN
        }
        is AiError.Parse -> AgentFailureKind.MODEL_PARSE
        is AiError.InvalidConfig -> AgentFailureKind.VALIDATION
        is AiError.Unauthorized -> AgentFailureKind.VALIDATION
        is AiError.LocalEngine -> AgentFailureKind.UNKNOWN
        is AiError.Unknown -> AgentFailureKind.UNKNOWN
        null -> AgentFailureKind.UNKNOWN
    }

    /** 仅用于没有结构化错误码的异常 / 文本兼容 */
    fun fromMessage(message: String): String {
        val text = message.lowercase()
        return when {
            text.contains("超时") || text.contains("timeout") -> AgentFailureKind.TIMEOUT
            text.contains("限流") || text.contains("429") ||
                text.contains("rate limit") || text.contains("too many requests") ->
                AgentFailureKind.RATE_LIMIT

            text.contains("网络") || text.contains("connect") ||
                text.contains("socket") || text.contains("unreachable") ->
                AgentFailureKind.NETWORK

            text.contains("数据库") || text.contains("sqlite") ||
                text.contains("room") -> AgentFailureKind.DATABASE

            text.contains("解析") || text.contains("parse") ||
                text.contains("json") -> AgentFailureKind.MODEL_PARSE

            text.contains("不支持工具") || text.contains("tools unsupported") ->
                AgentFailureKind.MODEL_UNSUPPORTED

            text.contains("参数") -> AgentFailureKind.TOOL_ARGUMENT
            text.contains("冲突") || text.contains("conflict") ->
                AgentFailureKind.WRITE_CONFLICT

            text.contains("拒绝") -> AgentFailureKind.USER_REJECTED
            text.contains("取消") -> AgentFailureKind.USER_CANCELLED
            text.contains("后台") || text.contains("worker") ->
                AgentFailureKind.BACKGROUND_LIMIT

            else -> AgentFailureKind.UNKNOWN
        }
    }
}

/** 可被任务级重试的失败；Runner 会转换为 WorkManager 的 Result.retry() */
class AgentRetryableFailureException(
    val kind: String,
    message: String
) : Exception(message)

/** 暂停 / 取消 / 租约丢失 / 等待确认都是控制流，不是失败 */
class AgentExecutionPausedException(message: String = "用户暂停") : Exception(message)

class AgentExecutionCancelledException(message: String = "用户取消") : Exception(message)

class AgentExecutionLeaseLostException(message: String = "执行租约已失效") : Exception(message)

class AgentExecutionWaitingConfirmationException(
    val pendingCount: Int,
    message: String = "等待用户确认写入操作"
) : Exception(message)
