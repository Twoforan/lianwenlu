package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.ai.AiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

/** F7：失败分类与重试策略矩阵 */
class AgentFailurePolicyTest {

    @Test
    fun `classifies throwables`() {
        assertEquals(
            AgentFailureKind.TIMEOUT,
            AgentFailureClassifier.fromThrowable(SocketTimeoutException("timeout"))
        )
        assertEquals(
            AgentFailureKind.NETWORK,
            AgentFailureClassifier.fromThrowable(java.io.IOException("disconnect"))
        )
        assertEquals(
            AgentFailureKind.TOOL_ARGUMENT,
            AgentFailureClassifier.fromThrowable(IllegalArgumentException("bad args"))
        )
    }

    @Test
    fun `classifies ai errors`() {
        assertEquals(
            AgentFailureKind.RATE_LIMIT,
            AgentFailureClassifier.fromAiError(AiError.Server(429, "too many"))
        )
        assertEquals(
            AgentFailureKind.PROVIDER_ERROR,
            AgentFailureClassifier.fromAiError(AiError.Server(503, "unavailable"))
        )
        assertEquals(
            AgentFailureKind.NETWORK,
            AgentFailureClassifier.fromAiError(AiError.Network(java.io.IOException()))
        )
        assertEquals(
            AgentFailureKind.TIMEOUT,
            AgentFailureClassifier.fromAiError(AiError.Timeout())
        )
        assertEquals(
            AgentFailureKind.MODEL_PARSE,
            AgentFailureClassifier.fromAiError(AiError.Parse(IllegalStateException()))
        )
    }

    @Test
    fun `classifies legacy messages without hiding database errors`() {
        assertEquals(
            AgentFailureKind.TIMEOUT,
            AgentFailureClassifier.fromMessage("请求超时，请稍后重试")
        )
        assertEquals(
            AgentFailureKind.RATE_LIMIT,
            AgentFailureClassifier.fromMessage("供应商返回 429 限流")
        )
        assertEquals(
            AgentFailureKind.DATABASE,
            AgentFailureClassifier.fromMessage("数据库写入失败：SQLite busy")
        )
        assertEquals(
            AgentFailureKind.MODEL_UNSUPPORTED,
            AgentFailureClassifier.fromMessage("当前供应商不支持工具调用")
        )
    }

    @Test
    fun `task retry is bounded by failure kind`() {
        val network = AgentRetryPolicy.taskDecision(AgentFailureKind.NETWORK, 1)
        assertTrue(network.retryable)
        assertEquals(AgentRetryPolicy.MAX_TASK_ATTEMPTS, network.maxAttempts)

        val lastNetwork = AgentRetryPolicy.taskDecision(
            AgentFailureKind.NETWORK,
            AgentRetryPolicy.MAX_TASK_ATTEMPTS
        )
        assertFalse(lastNetwork.retryable)

        assertFalse(AgentRetryPolicy.taskDecision(AgentFailureKind.VALIDATION, 1).retryable)
        assertFalse(AgentRetryPolicy.taskDecision(AgentFailureKind.WRITE_CONFLICT, 1).retryable)
        assertFalse(AgentRetryPolicy.taskDecision(AgentFailureKind.USER_CANCELLED, 1).retryable)

        assertTrue(AgentRetryPolicy.taskDecision(AgentFailureKind.DATABASE, 1).retryable)
        assertFalse(AgentRetryPolicy.taskDecision(AgentFailureKind.DATABASE, 3).retryable)
    }

    @Test
    fun `model retry is bounded and rate limit backs off`() {
        val first = AgentRetryPolicy.modelDecision(AgentFailureKind.NETWORK, 1)
        assertTrue(first.retryable)
        assertEquals(AgentRetryPolicy.MAX_MODEL_REQUEST_ATTEMPTS, first.maxAttempts)

        val last = AgentRetryPolicy.modelDecision(
            AgentFailureKind.NETWORK,
            AgentRetryPolicy.MAX_MODEL_REQUEST_ATTEMPTS
        )
        assertFalse(last.retryable)

        val rateLimit = AgentRetryPolicy.modelDecision(AgentFailureKind.RATE_LIMIT, 1)
        assertTrue(rateLimit.retryable)
        assertEquals(AgentRetryPolicy.RATE_LIMIT_RETRY_DELAY_MS, rateLimit.delayMs)

        assertFalse(AgentRetryPolicy.modelDecision(AgentFailureKind.MODEL_PARSE, 1).retryable)
        assertFalse(
            AgentRetryPolicy.modelDecision(AgentFailureKind.MODEL_UNSUPPORTED, 1).retryable
        )
    }

    @Test
    fun `worker backoff is centralized`() {
        assertEquals(60L, AgentRetryPolicy.workBackoffSeconds(AgentFailureKind.RATE_LIMIT))
        assertEquals(
            AgentRetryPolicy.TASK_BACKOFF_BASE_SECONDS,
            AgentRetryPolicy.workBackoffSeconds(AgentFailureKind.NETWORK)
        )
    }
}
