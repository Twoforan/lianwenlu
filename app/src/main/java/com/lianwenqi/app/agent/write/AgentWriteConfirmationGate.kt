package com.lianwenqi.app.agent.write

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内写入确认等待器（F6）。
 *
 * 仅用于同一页面内的即时继续；页面销毁或进程被杀后，
 * 待确认操作仍以数据库状态为准，重新进入后可再次确认。
 */
class AgentWriteConfirmationGate {

    private val waiters = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()

    /**
     * 等待某个操作的确认结果。
     * 返回 true 表示批准，false 表示拒绝；协程取消时抛出 CancellationException。
     *
     * 如果用户 / 测试在引擎真正进入等待前已经发出决定，这里会立即返回该决定。
     */
    suspend fun awaitDecision(operationId: Long): Boolean {
        val deferred = waiters.computeIfAbsent(operationId) { CompletableDeferred() }
        return try {
            deferred.await()
        } finally {
            waiters.remove(operationId, deferred)
        }
    }

    /** 用户已批准 / 拒绝；等待尚未建立时会预存决定，供下一次 await 立即消费 */
    fun signal(operationId: Long, approved: Boolean) {
        waiters.computeIfAbsent(operationId) { CompletableDeferred() }.complete(approved)
    }

    /** 页面销毁 / 任务取消时释放等待，避免协程永久挂起 */
    fun cancel(operationId: Long) {
        waiters.remove(operationId)?.cancel()
    }

    fun cancelAll() {
        waiters.values.forEach { it.cancel() }
        waiters.clear()
    }
}
