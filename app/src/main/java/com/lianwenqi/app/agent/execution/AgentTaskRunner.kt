package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentModelRequestRepository
import com.lianwenqi.app.agent.data.AgentRecoveryLogRepository
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentToolExecutionRepository
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentRecoveryResult
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.jsonPayload
import com.lianwenqi.app.agent.write.AgentWriteExecutionService
import com.lianwenqi.app.agent.write.AgentWriteRecoveryService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

/** 一次后台执行 / 恢复的结果 */
data class AgentRunOutcome(
    val taskId: Long,
    val type: String,
    val message: String = "",
    val failureKind: String = ""
) {
    companion object {
        const val COMPLETED = "COMPLETED"
        const val WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
        const val PAUSED = "PAUSED"
        const val CANCELLED = "CANCELLED"
        const val RETRY = "RETRY"
        const val FAILED = "FAILED"
        const val NO_OP = "NO_OP"
        const val LEASE_LOST = "LEASE_LOST"
    }
}

/**
 * F7 任务生命周期编排器。
 *
 * 负责：
 * - 取得 / 释放执行租约；
 * - 执行前核对中断的工具、模型请求与写入操作；
 * - 状态迁移（WAITING_CONFIRMATION → RUNNING、PAUSED → RUNNING）；
 * - 调用 [AgentExecutionEngine] 并从持久化游标恢复；
 * - 写入恢复诊断日志。
 *
 * 不做：规划、工具协议、写入授权判定。
 */
class AgentTaskRunner(
    private val tasks: AgentTaskRepository,
    private val nodes: AgentExecutionNodeRepository,
    private val tools: AgentToolExecutionRepository,
    private val events: AgentEventRepository,
    private val sessions: AgentSessionRepository,
    private val leases: AgentExecutionLeaseRepository,
    private val modelRequests: AgentModelRequestRepository,
    private val recoveryLogs: AgentRecoveryLogRepository,
    private val writeOperations: AgentWriteOperationRepository,
    private val writeService: AgentWriteExecutionService,
    private val writeRecovery: AgentWriteRecoveryService,
    private val engine: AgentExecutionEngine,
    /** 当前进程启动时间；用于立即判定上一进程遗留租约失效 */
    private val processStartedAt: Long = 0L
) {

    suspend fun run(
        taskId: Long,
        owner: String,
        reason: String = "WORKER"
    ): AgentRunOutcome {
        val snapshot = tasks.getTask(taskId)
            ?: return AgentRunOutcome(taskId, AgentRunOutcome.NO_OP, "任务不存在")
        if (AgentTaskStatus.isTerminal(snapshot.status)) {
            return AgentRunOutcome(taskId, AgentRunOutcome.NO_OP, "任务已结束")
        }
        if (snapshot.status == AgentTaskStatus.CREATED ||
            snapshot.status == AgentTaskStatus.PLANNING
        ) {
            return AgentRunOutcome(taskId, AgentRunOutcome.NO_OP, "任务尚未进入执行阶段")
        }
        if (snapshot.status == AgentTaskStatus.PAUSED) {
            return AgentRunOutcome(taskId, AgentRunOutcome.NO_OP, "任务已暂停，等待用户恢复")
        }
        if (snapshot.status == AgentTaskStatus.WAITING_CONFIRMATION) {
            if (!planConfirmed(taskId)) {
                return AgentRunOutcome(taskId, AgentRunOutcome.NO_OP, "等待用户确认计划")
            }
            if (writeOperations.hasUnresolved(taskId)) {
                return AgentRunOutcome(
                    taskId,
                    AgentRunOutcome.WAITING_CONFIRMATION,
                    "等待用户确认写入操作"
                )
            }
        }

        val previousOwner = snapshot.executionOwner
        val lease = leases.acquire(taskId, owner, processStartedAt)
            ?: return AgentRunOutcome(
                taskId,
                AgentRunOutcome.NO_OP,
                "同一任务已有有效执行者，跳过重复执行"
            )
        if (previousOwner.isNotBlank() && previousOwner != owner) {
            runCatching {
                leases.recordExpired(taskId, previousOwner, snapshot.executionAttempt)
            }
        }
        val attempt = lease.attempt
        val runIdBefore = snapshot.executionRunId
        val recovering = runIdBefore.isNotBlank()

        // 独立心跳协程：续租失败即停止；引擎在每个边界检查租约，过期后不再写任务状态。
        val heartbeatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val heartbeat = heartbeatScope.launch {
            while (true) {
                delay(AgentExecutionLeaseRepository.HEARTBEAT_INTERVAL_MS)
                if (!leases.renew(taskId, owner)) break
            }
        }

        var activeLogId: Long? = null
        try {
            if (attempt > AgentRetryPolicy.MAX_TASK_ATTEMPTS) {
                failExceeded(taskId, attempt)
                return AgentRunOutcome(
                    taskId,
                    AgentRunOutcome.FAILED,
                    "执行尝试次数超过上限（${AgentRetryPolicy.MAX_TASK_ATTEMPTS}）"
                )
            }
            if (tasks.isCancelRequested(taskId)) {
                finalizeCancel(taskId, snapshot.sessionId, "执行前已收到取消请求")
                return AgentRunOutcome(taskId, AgentRunOutcome.CANCELLED)
            }
            if (tasks.isPauseRequested(taskId)) {
                finalizePause(taskId, snapshot.sessionId)
                return AgentRunOutcome(taskId, AgentRunOutcome.PAUSED)
            }

            if (snapshot.status == AgentTaskStatus.WAITING_CONFIRMATION) {
                if (!tasks.tryStartExecution(taskId, snapshot.sessionId)) {
                    return AgentRunOutcome(
                        taskId,
                        AgentRunOutcome.NO_OP,
                        "任务状态已变化，停止本次执行"
                    )
                }
            }

            val task = tasks.getTask(taskId)
                ?: return AgentRunOutcome(taskId, AgentRunOutcome.NO_OP, "任务不存在")
            if (task.cancelRequested) {
                finalizeCancel(taskId, task.sessionId, "执行前已收到取消请求")
                return AgentRunOutcome(taskId, AgentRunOutcome.CANCELLED)
            }

            val currentNode = task.currentNodeId?.let { nodes.getNode(it) }
            val runningTools = tools.runningExecutions(taskId).size
            val startedRequests = modelRequests.startedOfTask(taskId).size
            val unappliedRequests = modelRequests.countUnappliedSucceeded(taskId)
            val unresolvedWrites = writeOperations.unresolvedOfTask(taskId).size
            val logId = if (recovering) {
                recoveryLogs.start(
                    taskId = taskId,
                    attempt = attempt,
                    reason = reason,
                    originalStatus = snapshot.status,
                    originalNodeId = currentNode?.id,
                    originalNodeTitle = currentNode?.title.orEmpty(),
                    cursorBefore = task.executionCursor,
                    succeededNodeCount = nodes.countCompleted(taskId),
                    failedNodeCount = nodes.countFailed(taskId),
                    uncertainToolCount = runningTools,
                    unresolvedWriteCount = unresolvedWrites,
                    duplicateRequestPossible = startedRequests > 0 || unappliedRequests > 0,
                    inconsistentDataFound = false
                )
            } else {
                null
            }
            activeLogId = logId
            if (recovering) {
                events.append(
                    taskId = taskId,
                    eventType = AgentEventType.TASK_RECOVERY_STARTED,
                    nodeId = currentNode?.id,
                    payloadJson = jsonPayload {
                        put("attempt", attempt)
                        put("reason", reason.take(200))
                        put("cursor", task.executionCursor)
                    }
                )
            }

            // 1) 工具中断：RUNNING → UNCERTAIN
            tools.markInterruptedUncertain(taskId)
            // 2) 模型请求中断：STARTED / 已返回但未消费 → UNKNOWN，记录重复请求风险
            val unknownRequests = modelRequests.markInterruptedUnknown(taskId) +
                modelRequests.markUnappliedSucceededUnknown(taskId)
            // 3) 写入中断核对：已写入补记成功，未写入标记失败，数据变化标记冲突
            val writeReport = writeRecovery.reconcileInterrupted(taskId)
            // 4) 已批准但尚未执行的写入：用户已授权，重新冲突校验后执行
            writeOperations.ofTask(taskId)
                .filter { it.executionStatus == AgentWriteOperationStatus.APPROVED }
                .forEach { runCatching { writeService.executeApproved(it.id) } }

            val unresolved = writeOperations.unresolvedOfTask(taskId)
            if (unresolved.isNotEmpty()) {
                parkInterruptedNodes(taskId)
                tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
                sessions.addMessage(
                    sessionId = task.sessionId,
                    taskId = taskId,
                    role = AgentMessageRole.ASSISTANT,
                    kind = AgentMessageKind.NOTICE,
                    content = "任务已从后台恢复，但仍有 ${unresolved.size} 个写入操作等待你确认；" +
                        "确认前不会修改数据，也不会自动继续。"
                )
                logId?.let {
                    recoveryLogs.finish(
                        id = it,
                        result = AgentRecoveryResult.WAITING_CONFIRMATION,
                        cursorAfter = tasks.getTask(taskId)?.executionCursor ?: task.executionCursor,
                        message = "存在未解决写入操作"
                    )
                }
                return AgentRunOutcome(
                    taskId,
                    AgentRunOutcome.WAITING_CONFIRMATION,
                    "存在待确认写入"
                )
            }
            if (tasks.isCancelRequested(taskId)) {
                finalizeCancel(taskId, task.sessionId, "恢复核对后收到取消请求")
                logId?.let {
                    recoveryLogs.finish(it, AgentRecoveryResult.SKIPPED, task.executionCursor, "用户取消")
                }
                return AgentRunOutcome(taskId, AgentRunOutcome.CANCELLED)
            }
            if (tasks.isPauseRequested(taskId)) {
                finalizePause(taskId, task.sessionId)
                logId?.let {
                    recoveryLogs.finish(it, AgentRecoveryResult.SKIPPED, task.executionCursor, "用户暂停")
                }
                return AgentRunOutcome(taskId, AgentRunOutcome.PAUSED)
            }

            val result = engine.execute(
                taskId = taskId,
                sessionId = task.sessionId,
                options = AgentExecutionOptions(
                    owner = owner,
                    attempt = attempt,
                    waitForWriteConfirmation = false,
                    recovery = recovering
                )
            )
            val latest = tasks.getTask(taskId)
            val cursorAfter = latest?.executionCursor ?: task.executionCursor
            return when (result) {
                is AgentExecutionResult.Completed -> {
                    logId?.let {
                        recoveryLogs.finish(
                            id = it,
                            result = AgentRecoveryResult.SUCCEEDED,
                            cursorAfter = cursorAfter,
                            message = buildRecoveryMessage(writeReport, unknownRequests)
                        )
                    }
                    if (recovering) {
                        events.append(
                            taskId = taskId,
                            eventType = AgentEventType.TASK_RECOVERY_COMPLETED,
                            payloadJson = jsonPayload {
                                put("attempt", attempt)
                                put("cursor", cursorAfter)
                                put("duplicateRequestPossible", unknownRequests > 0)
                            }
                        )
                    }
                    AgentRunOutcome(taskId, AgentRunOutcome.COMPLETED)
                }

                is AgentExecutionResult.WaitingConfirmation -> {
                    logId?.let {
                        recoveryLogs.finish(
                            it,
                            AgentRecoveryResult.WAITING_CONFIRMATION,
                            cursorAfter,
                            "等待用户确认 ${result.pendingCount} 个写入操作"
                        )
                    }
                    AgentRunOutcome(
                        taskId,
                        AgentRunOutcome.WAITING_CONFIRMATION,
                        "等待用户确认 ${result.pendingCount} 个写入操作"
                    )
                }

                is AgentExecutionResult.Paused -> {
                    logId?.let {
                        recoveryLogs.finish(it, AgentRecoveryResult.SKIPPED, cursorAfter, "用户暂停")
                    }
                    AgentRunOutcome(taskId, AgentRunOutcome.PAUSED)
                }

                is AgentExecutionResult.Cancelled -> {
                    logId?.let {
                        recoveryLogs.finish(it, AgentRecoveryResult.SKIPPED, cursorAfter, "用户取消")
                    }
                    AgentRunOutcome(taskId, AgentRunOutcome.CANCELLED)
                }

                is AgentExecutionResult.Failed -> {
                    logId?.let {
                        recoveryLogs.finish(
                            it,
                            AgentRecoveryResult.FAILED,
                            cursorAfter,
                            result.message
                        )
                    }
                    if (recovering) {
                        events.append(
                            taskId = taskId,
                            eventType = AgentEventType.TASK_RECOVERY_FAILED,
                            payloadJson = jsonPayload {
                                put("attempt", attempt)
                                put("message", result.message.take(500))
                            }
                        )
                    }
                    AgentRunOutcome(taskId, AgentRunOutcome.FAILED, result.message)
                }

                is AgentExecutionResult.Blocked -> {
                    logId?.let {
                        recoveryLogs.finish(
                            it,
                            AgentRecoveryResult.SKIPPED,
                            cursorAfter,
                            result.message
                        )
                    }
                    // 执行被阻止时不能停留在 RUNNING，否则界面既没有结果也没有错误提示
                    val current = tasks.getTask(taskId)
                    if (current?.status == AgentTaskStatus.RUNNING) {
                        runCatching {
                            tasks.updateStatus(
                                taskId,
                                AgentTaskStatus.WAITING_CONFIRMATION,
                                "执行被阻止：${result.message}".take(1_000)
                            )
                        }
                    }
                    runCatching {
                        sessions.addMessage(
                            sessionId = current?.sessionId ?: snapshot.sessionId,
                            taskId = taskId,
                            role = AgentMessageRole.ASSISTANT,
                            kind = AgentMessageKind.NOTICE,
                            content = "无法开始执行：${result.message}".take(1_000)
                        )
                    }
                    AgentRunOutcome(taskId, AgentRunOutcome.NO_OP, result.message)
                }
            }
        } catch (e: AgentRetryableFailureException) {
            runCatching {
                events.append(
                    taskId = taskId,
                    eventType = AgentEventType.TASK_RETRY_SCHEDULED,
                    payloadJson = jsonPayload {
                        put("failureKind", e.kind)
                        put("attempt", attempt)
                        put("message", e.message.orEmpty().take(500))
                    }
                )
                activeLogId?.let { id ->
                    recoveryLogs.finish(
                        id = id,
                        result = AgentRecoveryResult.FAILED,
                        cursorAfter = tasks.getTask(taskId)?.executionCursor
                            ?: snapshot.executionCursor,
                        message = "临时失败，等待重试：" + e.message.orEmpty().take(500)
                    )
                }
            }
            return AgentRunOutcome(
                taskId,
                AgentRunOutcome.RETRY,
                e.message.orEmpty(),
                e.kind
            )
        } catch (e: AgentExecutionLeaseLostException) {
            runCatching {
                events.append(
                    taskId = taskId,
                    eventType = AgentEventType.TASK_INTERRUPTED,
                    payloadJson = jsonPayload { put("reason", "LEASE_LOST") }
                )
                activeLogId?.let { id ->
                    recoveryLogs.finish(
                        id = id,
                        result = AgentRecoveryResult.FAILED,
                        cursorAfter = tasks.getTask(taskId)?.executionCursor
                            ?: snapshot.executionCursor,
                        message = "执行租约失效，停止写入任务状态"
                    )
                }
            }
            return AgentRunOutcome(
                taskId,
                AgentRunOutcome.LEASE_LOST,
                "执行租约已失效，停止写入任务状态"
            )
        } catch (e: CancellationException) {
            runCatching {
                activeLogId?.let { id ->
                    recoveryLogs.finish(
                        id = id,
                        result = AgentRecoveryResult.SKIPPED,
                        cursorAfter = tasks.getTask(taskId)?.executionCursor
                            ?: snapshot.executionCursor,
                        message = "执行被中断，等待下一次恢复"
                    )
                }
            }
            throw e
        } catch (e: Throwable) {
            val kind = AgentFailureClassifier.fromThrowable(e)
            val message = e.message ?: "后台执行失败"
            val decision = AgentRetryPolicy.taskDecision(kind, attempt)
            if (decision.retryable) {
                runCatching {
                    activeLogId?.let { id ->
                        recoveryLogs.finish(
                            id = id,
                            result = AgentRecoveryResult.FAILED,
                            cursorAfter = tasks.getTask(taskId)?.executionCursor
                                ?: snapshot.executionCursor,
                            message = "临时失败，等待重试：" + message.take(500)
                        )
                    }
                }
                runCatching { tasks.setLastErrorKind(taskId, kind) }
                return AgentRunOutcome(
                    taskId,
                    AgentRunOutcome.RETRY,
                    message,
                    kind
                )
            }
            runCatching {
                writeService.cancelPending(taskId, "任务失败，未执行的写入提议已失效")
                tasks.updateStatus(taskId, AgentTaskStatus.FAILED, message)
                activeLogId?.let { id ->
                    recoveryLogs.finish(
                        id = id,
                        result = AgentRecoveryResult.FAILED,
                        cursorAfter = tasks.getTask(taskId)?.executionCursor
                            ?: snapshot.executionCursor,
                        message = message.take(500)
                    )
                }
            }
            return AgentRunOutcome(taskId, AgentRunOutcome.FAILED, message, kind)
        } finally {
            heartbeat.cancel()
            withContext(NonCancellable) {
                runCatching { leases.release(taskId, owner) }
            }
        }
    }

    private suspend fun planConfirmed(taskId: Long): Boolean =
        events.eventsOfType(taskId, AgentEventType.CONFIRMATION_RECEIVED).isNotEmpty()

    private suspend fun failExceeded(taskId: Long, attempt: Int) {
        runCatching {
            writeService.cancelPending(taskId, "执行尝试次数超过上限")
            tasks.updateStatus(
                taskId,
                AgentTaskStatus.FAILED,
                "执行尝试次数超过上限（${AgentRetryPolicy.MAX_TASK_ATTEMPTS}，第 $attempt 次）"
            )
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_RECOVERY_FAILED,
                payloadJson = jsonPayload {
                    put("attempt", attempt)
                    put("reason", "MAX_ATTEMPTS")
                }
            )
        }
    }

    private suspend fun parkInterruptedNodes(taskId: Long) {
        nodes.interruptedNodes(taskId).forEach { node ->
            runCatching {
                nodes.updateStatus(
                    node.id,
                    AgentNodeStatus.WAITING,
                    errorMessage = "等待用户确认写入"
                )
            }
        }
    }

    private suspend fun finalizePause(taskId: Long, sessionId: Long) {
        val task = tasks.getTask(taskId) ?: return
        parkInterruptedNodes(taskId)
        tasks.updateExecutionCursor(
            taskId,
            task.executionCursor,
            task.currentNodeId
        )
        runCatching { tasks.updateStatus(taskId, AgentTaskStatus.PAUSED) }
        runCatching {
            sessions.addMessage(
                sessionId = sessionId,
                taskId = taskId,
                role = AgentMessageRole.ASSISTANT,
                kind = AgentMessageKind.NOTICE,
                content = "执行已暂停，已完成的步骤结果保留；恢复后从当前步骤继续。"
            )
        }
    }

    private suspend fun finalizeCancel(taskId: Long, sessionId: Long, reason: String) {
        val task = tasks.getTask(taskId) ?: return
        writeService.cancelPending(taskId, reason)
        task.currentNodeId?.let { nodeId ->
            runCatching {
                nodes.updateStatus(nodeId, AgentNodeStatus.CANCELLED, errorMessage = reason)
            }
        }
        tasks.updateExecutionCursor(taskId, task.executionCursor, task.currentNodeId)
        runCatching { tasks.updateStatus(taskId, AgentTaskStatus.CANCELLED, reason) }
        runCatching {
            sessions.addMessage(
                sessionId = sessionId,
                taskId = taskId,
                role = AgentMessageRole.ASSISTANT,
                kind = AgentMessageKind.NOTICE,
                content = "任务已取消，已完成的步骤结果保留；已成功的写入不会自动撤销。"
            )
        }
    }

    private fun buildRecoveryMessage(
        writeReport: com.lianwenqi.app.agent.write.AgentWriteRecoveryReport,
        unknownRequests: Int
    ): String = buildString {
        if (writeReport.changed) append(writeReport.message)
        if (unknownRequests > 0) {
            if (isNotEmpty()) append("；")
            append("检测到 $unknownRequests 个模型请求状态未知，可能产生重复请求")
        }
        if (isEmpty()) append("恢复完成")
    }.take(1_000)
}
