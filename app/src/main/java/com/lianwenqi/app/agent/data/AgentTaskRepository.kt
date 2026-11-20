package com.lianwenqi.app.agent.data

import androidx.room.withTransaction
import com.lianwenqi.app.agent.data.entity.AgentClarification
import com.lianwenqi.app.agent.data.entity.AgentClarificationStatus
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentTask
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentWorkMode
import com.lianwenqi.app.ai.PromptDiagnostics
import com.lianwenqi.app.data.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.put

/** Agent 计划消息的提示词日志上限，避免单条消息过大 */
private const val MAX_AGENT_PROMPT_LOG = 20_000

/** 保存一次规划结果所需的全部字段；由 ViewModel 在模型返回后组装 */
data class AgentPlanSaveRequest(
    val planJson: String,
    val taskTitle: String,
    val goalSummary: String,
    val stepCount: Int,
    val thinkingSummary: String,
    val previousSummary: String = "",
    val assistantContent: String,
    val promptTokens: Int = -1,
    val completionTokens: Int = 0,
    val cachedTokens: Int = -1,
    val promptLog: String = ""
)


/**
 * Agent 任务仓库。
 *
 * 任务状态以 `agent_task.status` 为唯一事实来源；所有关键写入都在事务内完成，
 * 并按状态变化追加任务级事件。F2 不调用模型、不执行工具、不自动重试。
 */
class AgentTaskRepository(private val db: AppDatabase) {

    private val taskDao = db.agentTaskDao()
    private val sessionDao = db.agentDao()
    private val events = AgentEventWriter(taskDao)

    /**
     * 创建任务并写入 `TASK_CREATED` 事件；同时更新会话的 `lastTaskId`。
     * 会话不存在、目标为空或计划 JSON 非法时整体回滚。
     */
    suspend fun createTask(
        sessionId: Long,
        userGoal: String,
        title: String = AgentTask.DEFAULT_TITLE,
        modelId: Long? = null,
        planJson: String = "{}"
    ): Long {
        require(userGoal.isNotBlank()) { "userGoal 不能为空" }
        requireJsonObject(planJson, "planJson")
        return db.withTransaction {
            require(sessionDao.getSession(sessionId) != null) { "Agent 会话不存在：$sessionId" }
            val now = System.currentTimeMillis()
            val taskId = taskDao.insertTask(
                AgentTask(
                    sessionId = sessionId,
                    title = title.trim().ifBlank { AgentTask.DEFAULT_TITLE },
                    userGoal = userGoal,
                    status = AgentTaskStatus.CREATED,
                    planJson = planJson,
                    modelId = modelId,
                    createdAt = now,
                    updatedAt = now
                )
            )
            sessionDao.setLastTaskId(sessionId, taskId, now)
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_CREATED,
                payloadJson = jsonPayload {
                    put("sessionId", sessionId)
                    put("title", title.trim().ifBlank { AgentTask.DEFAULT_TITLE })
                    put("userGoal", userGoal)
                    modelId?.let { put("modelId", it) }
                }
            )
            taskId
        }
    }

    fun observeTask(id: Long): Flow<AgentTask?> = taskDao.observeTask(id)

    suspend fun getTask(id: Long): AgentTask? = taskDao.getTask(id)

    /**
     * 创建任务 + 用户任务消息 + 最近任务 + TASK_CREATED，全部在一个事务内完成。
     * 返回 (taskId, userMessageId)。
     */
    suspend fun createTaskWithUserMessage(
        sessionId: Long,
        userGoal: String,
        title: String = AgentTask.DEFAULT_TITLE,
        modelId: Long? = null,
        workMode: String = AgentWorkMode.PLAN,
        contextSnapshot: String = "",
        attachmentsJson: String = ""
    ): Pair<Long, Long> {
        require(userGoal.isNotBlank()) { "userGoal 不能为空" }
        require(AgentWorkMode.isValid(workMode)) { "非法工作模式：$workMode" }
        return db.withTransaction {
            require(sessionDao.getSession(sessionId) != null) { "Agent 会话不存在：$sessionId" }
            val now = System.currentTimeMillis()
            val taskTitle = title.trim().ifBlank { AgentTask.DEFAULT_TITLE }
            val taskId = taskDao.insertTask(
                AgentTask(
                    sessionId = sessionId,
                    title = taskTitle,
                    userGoal = userGoal,
                    status = AgentTaskStatus.CREATED,
                    modelId = modelId,
                    workMode = workMode,
                    createdAt = now,
                    updatedAt = now
                )
            )
            sessionDao.setLastTaskId(sessionId, taskId, now)
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_CREATED,
                payloadJson = jsonPayload {
                    put("sessionId", sessionId)
                    put("title", taskTitle)
                    put("userGoal", userGoal)
                    put("workMode", workMode)
                    modelId?.let { put("modelId", it) }
                }
            )
            val sequence = sessionDao.maxSequence(sessionId) + 1
            val messageId = sessionDao.insertMessage(
                AgentMessage(
                    sessionId = sessionId,
                    taskId = taskId,
                    role = AgentMessageRole.USER,
                    kind = AgentMessageKind.USER_INPUT,
                    content = userGoal,
                    sequence = sequence,
                    attachments = attachmentsJson,
                    contextSnapshot = contextSnapshot,
                    createdAt = now
                )
            )
            sessionDao.touchSession(sessionId, now, now)
            taskId to messageId
        }
    }

    suspend fun tasksOfSession(sessionId: Long): List<AgentTask> =
        taskDao.tasksOfSession(sessionId)

    fun observeTasksOfSession(sessionId: Long): Flow<List<AgentTask>> =
        taskDao.observeTasksOfSession(sessionId)

    /** 当前书籍下所有生成过计划书的任务（跨会话），用于计划书库 */
    suspend fun tasksWithPlanOfBook(bookId: Long): List<AgentTask> =
        taskDao.tasksWithPlanOfBook(bookId)

    /** 当前任务的直接子分支 */
    fun observeChildTasks(taskId: Long): Flow<List<AgentTask>> =
        taskDao.observeChildTasks(taskId)

    /** 任务最近一条用户输入；用于分支继承上下文快照与附件 */
    suspend fun lastUserMessage(taskId: Long): AgentMessage? =
        sessionDao.lastMessageOfKind(taskId, AgentMessageKind.USER_INPUT)

    /**
     * 创建任务分支：新任务 + 用户消息，可选携带已编辑计划。
     *
     * 父分支的用户目标、计划、事件和执行结果保持不变；
     * 新分支从未执行状态开始，不继承确认、游标、工具结果或写入结果。
     * 返回 (新任务 id, 新用户消息 id)。
     */
    suspend fun createBranchTask(
        parentTaskId: Long,
        userGoal: String,
        branchSource: String,
        title: String? = null,
        planJson: String = "{}",
        assistantPlanContent: String = "",
        contextSnapshot: String = "",
        attachmentsJson: String = "",
        modelId: Long? = null
    ): Pair<Long, Long> {
        require(userGoal.isNotBlank()) { "userGoal 不能为空" }
        requireJsonObject(planJson, "planJson")
        return db.withTransaction {
            val parent = taskDao.getTask(parentTaskId)
                ?: throw IllegalArgumentException("Agent 任务不存在：$parentTaskId")
            require(parent.status != AgentTaskStatus.RUNNING) { "任务执行中，不能创建分支" }
            val now = System.currentTimeMillis()
            val hasPlan = planJson != "{}"
            val taskTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: parent.title
            val rootId = parent.rootTaskId ?: parent.id
            val taskId = taskDao.insertTask(
                AgentTask(
                    sessionId = parent.sessionId,
                    title = taskTitle,
                    userGoal = userGoal,
                    status = if (hasPlan) {
                        AgentTaskStatus.WAITING_CONFIRMATION
                    } else {
                        AgentTaskStatus.CREATED
                    },
                    planJson = planJson,
                    modelId = modelId ?: parent.modelId,
                    workMode = parent.workMode,
                    parentTaskId = parent.id,
                    rootTaskId = rootId,
                    branchSource = branchSource,
                    createdAt = now,
                    updatedAt = now
                )
            )
            sessionDao.setLastTaskId(parent.sessionId, taskId, now)
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_CREATED,
                payloadJson = jsonPayload {
                    put("sessionId", parent.sessionId)
                    put("title", taskTitle)
                    put("userGoal", userGoal)
                    put("workMode", parent.workMode)
                    put("parentTaskId", parent.id)
                    put("rootTaskId", rootId)
                    put("branchSource", branchSource)
                    modelId?.let { put("modelId", it) }
                }
            )
            var sequence = sessionDao.maxSequence(parent.sessionId) + 1
            val messageId = sessionDao.insertMessage(
                AgentMessage(
                    sessionId = parent.sessionId,
                    taskId = taskId,
                    role = AgentMessageRole.USER,
                    kind = AgentMessageKind.USER_INPUT,
                    content = userGoal,
                    sequence = sequence,
                    attachments = attachmentsJson,
                    contextSnapshot = contextSnapshot,
                    createdAt = now
                )
            )
            if (hasPlan) {
                events.append(
                    taskId = taskId,
                    eventType = AgentEventType.TASK_PLAN_UPDATED,
                    payloadJson = jsonPayload {
                        put("source", branchSource)
                        put("planVersion", 1)
                        put("taskTitle", taskTitle)
                        put("planHash", PromptDiagnostics.hash(planJson))
                        put("planLength", planJson.length)
                    }
                )
                events.append(
                    taskId = taskId,
                    eventType = AgentEventType.WAITING_FOR_CONFIRMATION,
                    payloadJson = jsonPayload { put("planVersion", 1) }
                )
                sequence += 1
                sessionDao.insertMessage(
                    AgentMessage(
                        sessionId = parent.sessionId,
                        taskId = taskId,
                        role = AgentMessageRole.ASSISTANT,
                        kind = AgentMessageKind.PLAN,
                        content = assistantPlanContent.take(4_000),
                        sequence = sequence,
                        createdAt = now
                    )
                )
            }
            sessionDao.touchSession(parent.sessionId, now, now)
            taskId to messageId
        }
    }

    // ---------------- 澄清请求 ----------------

    /** 写入待回答的澄清请求；同一任务只保留一条 PENDING */
    suspend fun createClarification(
        taskId: Long,
        question: String,
        options: List<String> = emptyList()
    ): Long = db.withTransaction {
        val task = taskDao.getTask(taskId)
            ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
        require(!AgentTaskStatus.isTerminal(task.status)) { "任务已结束，不能请求补充信息" }
        val questionText = question.trim()
        require(questionText.isNotEmpty()) { "澄清问题不能为空" }
        val now = System.currentTimeMillis()
        // 澄清完成前不向用户展示计划消息
        sessionDao.deleteMessagesOfTaskKind(taskId, AgentMessageKind.PLAN)
        val cancelled = taskDao.cancelPendingClarifications(taskId, now)
        val id = taskDao.insertClarification(
            AgentClarification(
                sessionId = task.sessionId,
                taskId = taskId,
                question = questionText.take(2_000),
                optionsJson = agentJson.encodeToString(
                    ListSerializer(String.serializer()),
                    options.map { it.trim() }.filter { it.isNotEmpty() }.take(4)
                ),
                status = AgentClarificationStatus.PENDING,
                createdAt = now
            )
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.CLARIFICATION_REQUESTED,
            payloadJson = jsonPayload {
                put("clarificationId", id)
                put("question", questionText.take(500))
                put("optionCount", options.size)
                put("cancelledPrevious", cancelled)
            }
        )
        id
    }

    fun observePendingClarification(taskId: Long): Flow<AgentClarification?> =
        taskDao.observePendingClarifications(taskId).map { it.firstOrNull() }

    suspend fun pendingClarification(taskId: Long): AgentClarification? =
        taskDao.pendingClarification(taskId)

    suspend fun answeredClarifications(taskId: Long): List<AgentClarification> =
        taskDao.answeredClarifications(taskId)

    fun observeAnsweredClarifications(taskId: Long): Flow<List<AgentClarification>> =
        taskDao.observeAnsweredClarifications(taskId)

    /** 保存用户补充信息；只有 PENDING 的澄清请求可以回答 */
    suspend fun answerClarification(clarificationId: Long, answer: String): AgentClarification? =
        db.withTransaction {
            val clarification = taskDao.getClarification(clarificationId)
                ?: throw IllegalArgumentException("澄清请求不存在：$clarificationId")
            if (clarification.status != AgentClarificationStatus.PENDING) return@withTransaction null
            val text = answer.trim()
            require(text.isNotEmpty()) { "补充信息不能为空" }
            val now = System.currentTimeMillis()
            val updated = clarification.copy(
                status = AgentClarificationStatus.ANSWERED,
                answer = text.take(4_000),
                answeredAt = now
            )
            taskDao.updateClarification(updated)
            events.append(
                taskId = clarification.taskId,
                eventType = AgentEventType.CLARIFICATION_ANSWERED,
                payloadJson = jsonPayload {
                    put("clarificationId", clarification.id)
                    put("answerChars", updated.answer.length)
                }
            )
            updated
        }

    /** 取消尚未回答的澄清请求；任务状态保持不变 */
    suspend fun cancelPendingClarification(taskId: Long) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            val cancelled = taskDao.cancelPendingClarifications(taskId, now)
            if (cancelled > 0) {
                events.append(
                    taskId = taskId,
                    eventType = AgentEventType.CLARIFICATION_CANCELLED,
                    payloadJson = jsonPayload { put("count", cancelled) }
                )
            }
        }
    }

    /** 解析澄清候选回答；非法 JSON 视为没有候选项 */
    fun clarificationOptions(optionsJson: String): List<String> =
        runCatching {
            agentJson.decodeFromString(
                ListSerializer(String.serializer()),
                optionsJson
            )
        }.getOrDefault(emptyList())

    /** 全部未完成任务（应用重启后用于判断是否可恢复） */
    suspend fun unfinishedTasks(): List<AgentTask> =
        taskDao.tasksWithStatuses(AgentTaskStatus.unfinished)

    suspend fun unfinishedTasksOfSession(sessionId: Long): List<AgentTask> =
        taskDao.tasksOfSessionWithStatuses(sessionId, AgentTaskStatus.unfinished)

    /** 把某个任务标记为会话的最近任务 */
    suspend fun markAsRecent(taskId: Long): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        sessionDao.setLastTaskId(task.sessionId, taskId, System.currentTimeMillis())
        true
    }

    /**
     * 用户从失败节点重试：仅允许 FAILED → RUNNING。
     *
     * 已完成节点与执行游标保持不变，执行器会从重新打开的失败节点继续。
     */
    suspend fun reopenFailedForRetry(taskId: Long, nodeId: Long): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        if (task.status != AgentTaskStatus.FAILED) return@withTransaction false
        if (task.currentNodeId != nodeId) return@withTransaction false
        val now = System.currentTimeMillis()
        taskDao.updateTask(
            task.copy(
                status = AgentTaskStatus.RUNNING,
                errorMessage = "",
                completedAt = null,
                pauseRequested = false,
                cancelRequested = false,
                // 用户手动重试视为新一轮执行，重置恢复尝试次数，避免立刻再次命中上限
                executionAttempt = 0,
                updatedAt = now
            )
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.TASK_STARTED,
            nodeId = nodeId,
            payloadJson = jsonPayload {
                put("from", task.status)
                put("to", AgentTaskStatus.RUNNING)
                put("retryFromNode", true)
            }
        )
        true
    }

    /**
     * 用户结束失败任务：FAILED → CANCELLED，保留节点与消息记录，便于继续新任务。
     */
    suspend fun finishFailed(taskId: Long): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        if (task.status != AgentTaskStatus.FAILED) return@withTransaction false
        val now = System.currentTimeMillis()
        taskDao.updateTask(
            task.copy(
                status = AgentTaskStatus.CANCELLED,
                errorMessage = AgentTask.USER_ENDED_REASON,
                completedAt = now,
                updatedAt = now
            )
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.TASK_CANCELLED,
            payloadJson = jsonPayload {
                put("from", task.status)
                put("to", AgentTaskStatus.CANCELLED)
                put("reason", "用户结束失败任务")
            }
        )
        true
    }

    /**
     * 更新任务状态并追加对应事件。
     *
     * 终态（COMPLETED / FAILED / CANCELLED）不可再被改写；
     * FAILED / CANCELLED 不会被自动当作 COMPLETED。
     */
    suspend fun updateStatus(
        taskId: Long,
        status: String,
        errorMessage: String? = null
    ): Unit = db.withTransaction {
        require(status in AgentTaskStatus.all) { "非法任务状态：$status" }
        val task = taskDao.getTask(taskId) ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
        require(!AgentTaskStatus.isTerminal(task.status) || status == task.status) {
            "任务已处于终态 ${task.status}，不能再改为 $status"
        }
        val now = System.currentTimeMillis()
        if (task.status == status) {
            if (errorMessage != null && errorMessage != task.errorMessage) {
                taskDao.updateTask(task.copy(errorMessage = errorMessage, updatedAt = now))
            }
            return@withTransaction
        }
        val next = task.copy(
            status = status,
            errorMessage = errorMessage ?: task.errorMessage,
            startedAt = if (status == AgentTaskStatus.RUNNING) task.startedAt ?: now else task.startedAt,
            completedAt = if (AgentTaskStatus.isTerminal(status)) task.completedAt ?: now else task.completedAt,
            updatedAt = now
        )
        taskDao.updateTask(next)
        val eventType = eventTypeFor(task.status, status) ?: return@withTransaction
        events.append(
            taskId = taskId,
            eventType = eventType,
            payloadJson = jsonPayload {
                put("from", task.status)
                put("to", status)
                if (next.errorMessage.isNotBlank()) put("errorMessage", next.errorMessage)
            }
        )
        Unit
    }

    /** 更新结构化计划；只允许未结束任务，计划必须是合法 JSON 对象 */
    suspend fun updatePlan(taskId: Long, planJson: String): Unit {
        requireJsonObject(planJson, "planJson")
        db.withTransaction {
            val task = taskDao.getTask(taskId) ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
            require(!AgentTaskStatus.isTerminal(task.status)) { "任务已结束，不能更新计划" }
            val now = System.currentTimeMillis()
            taskDao.updateTask(task.copy(planJson = planJson, updatedAt = now))
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_PLAN_UPDATED,
                payloadJson = jsonPayload { put("planLength", planJson.length) }
            )
            Unit
        }
    }

    /** 修改任务标题（只影响展示，不追加计划事件） */
    suspend fun renameTask(taskId: Long, title: String) {
        val name = title.trim()
        if (name.isEmpty()) return
        db.withTransaction {
            val task = taskDao.getTask(taskId) ?: return@withTransaction
            taskDao.updateTask(task.copy(title = name, updatedAt = System.currentTimeMillis()))
        }
    }

    /** 设置当前执行节点；节点必须属于该任务，且任务未结束 */
    suspend fun setCurrentNode(taskId: Long, nodeId: Long?) {
        db.withTransaction {
            val task = taskDao.getTask(taskId) ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
            require(!AgentTaskStatus.isTerminal(task.status)) { "任务已结束，不能更新当前节点" }
            if (nodeId != null) {
                val node = taskDao.getNode(nodeId)
                    ?: throw IllegalArgumentException("执行节点不存在：$nodeId")
                require(node.taskId == taskId) { "执行节点 $nodeId 不属于任务 $taskId" }
            }
            taskDao.setCurrentNode(taskId, nodeId, System.currentTimeMillis())
        }
    }

    /**
     * 显式重启规划：允许 PLANNING（中断重试）、FAILED（失败重试）、
     * WAITING_CONFIRMATION（用户要求重新生成）回到 PLANNING。
     *
     * 这是对 F2「终态不可改写」的最小兼容调整：只有该方法能处理 FAILED，
     * 且必须写 TASK_PLANNING 事件，不绕过状态机直接 DAO 修改。
     * 返回重启前的计划版本号（TASK_PLAN_UPDATED 事件数）。
     */
    suspend fun restartPlanning(taskId: Long): Int = db.withTransaction {
        val task = taskDao.getTask(taskId)
            ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
        require(
            task.status in listOf(
                AgentTaskStatus.PLANNING,
                AgentTaskStatus.FAILED,
                AgentTaskStatus.WAITING_CONFIRMATION
            )
        ) { "当前状态 ${task.status} 不能重新规划" }
        val previousVersion = taskDao.eventsOfType(taskId, AgentEventType.TASK_PLAN_UPDATED).size
        val now = System.currentTimeMillis()
        taskDao.updateTask(
            task.copy(
                status = AgentTaskStatus.PLANNING,
                errorMessage = "",
                completedAt = null,
                // 新计划必须从第一步重新执行，旧游标 / 节点不再有效
                currentNodeId = null,
                executionRunId = "",
                executionCursor = 0,
                pauseRequested = false,
                cancelRequested = false,
                lastErrorKind = "",
                updatedAt = now
            )
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.TASK_PLANNING,
            payloadJson = jsonPayload {
                put("retry", true)
                put("from", task.status)
                put("previousPlanVersion", previousVersion)
            }
        )
        previousVersion
    }

    /**
     * 保存规划结果：写 planJson、任务标题、WAITING_CONFIRMATION 状态、
     * TASK_PLAN_UPDATED + WAITING_FOR_CONFIRMATION 事件、计划消息与 Token / 日志。
     * 全部在同一个事务内完成；任一步失败整体回滚。
     *
     * 返回新的计划版本号（从 1 开始）。
     */
    suspend fun savePlanAndWaitConfirmation(
        taskId: Long,
        request: AgentPlanSaveRequest,
        /** 需要用户补充信息时传 false：计划只作为内部草稿保存，不生成计划消息 */
        emitPlanMessage: Boolean = true
    ): Int = db.withTransaction {
        requireJsonObject(request.planJson, "planJson")
        val task = taskDao.getTask(taskId)
            ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
        require(task.status == AgentTaskStatus.PLANNING) {
            "只有 PLANNING 任务可以保存计划，当前为 ${task.status}"
        }
        val now = System.currentTimeMillis()
        val version = taskDao.eventsOfType(taskId, AgentEventType.TASK_PLAN_UPDATED).size + 1
        val previousLength = if (task.planJson.isBlank() || task.planJson == "{}") 0 else task.planJson.length
        val title = request.taskTitle.trim().ifBlank { task.title }
        taskDao.updateTask(
            task.copy(
                title = title,
                planJson = request.planJson,
                status = AgentTaskStatus.WAITING_CONFIRMATION,
                errorMessage = "",
                currentNodeId = null,
                executionRunId = "",
                executionCursor = 0,
                pauseRequested = false,
                cancelRequested = false,
                lastErrorKind = "",
                updatedAt = now
            )
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.TASK_PLAN_UPDATED,
            payloadJson = jsonPayload {
                put("planVersion", version)
                put("taskTitle", title)
                put("goalSummary", request.goalSummary.take(500))
                put("stepCount", request.stepCount)
                put("planHash", PromptDiagnostics.hash(request.planJson))
                put("planLength", request.planJson.length)
                put("previousPlanLength", previousLength)
                val previous = request.previousSummary.ifBlank {
                    if (previousLength > 0) "上一版计划 $previousLength 字符" else ""
                }
                if (previous.isNotBlank()) put("previousPlanSummary", previous.take(500))
            }
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.WAITING_FOR_CONFIRMATION,
            payloadJson = jsonPayload { put("planVersion", version) }
        )
        val sequence = sessionDao.maxSequence(task.sessionId) + 1
        if (emitPlanMessage) {
            sessionDao.insertMessage(
                AgentMessage(
                    sessionId = task.sessionId,
                    taskId = taskId,
                    role = AgentMessageRole.ASSISTANT,
                    kind = AgentMessageKind.PLAN,
                    content = request.assistantContent.take(4_000),
                    thinkingSummary = request.thinkingSummary.take(1_000),
                    sequence = sequence,
                    promptTokens = request.promptTokens,
                    completionTokens = request.completionTokens,
                    cachedTokens = request.cachedTokens,
                    promptLog = request.promptLog.take(MAX_AGENT_PROMPT_LOG),
                    createdAt = now
                )
            )
        }
        sessionDao.touchSession(task.sessionId, now, now)
        version
    }

    /** 删除任务的计划消息；用于需要补充信息时清掉旧版直接生成的“已生成计划” */
    suspend fun clearPlanMessages(taskId: Long) {
        sessionDao.deleteMessagesOfTaskKind(taskId, AgentMessageKind.PLAN)
    }

    /**
     * 保存用户手动修改后的计划；只允许 WAITING_CONFIRMATION 任务。
     * 不新增计划消息，但会追加带 source=USER_EDIT 的 TASK_PLAN_UPDATED。
     */
    suspend fun saveEditedPlan(
        taskId: Long,
        planJson: String,
        taskTitle: String,
        goalSummary: String,
        stepCount: Int,
        previousSummary: String = ""
    ): Int = db.withTransaction {
        requireJsonObject(planJson, "planJson")
        val task = taskDao.getTask(taskId)
            ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
        require(task.status == AgentTaskStatus.WAITING_CONFIRMATION) {
            "只有等待确认的任务可以修改计划，当前为 ${task.status}"
        }
        val now = System.currentTimeMillis()
        val version = taskDao.eventsOfType(taskId, AgentEventType.TASK_PLAN_UPDATED).size + 1
        val previousLength = if (task.planJson.isBlank() || task.planJson == "{}") 0 else task.planJson.length
        val title = taskTitle.trim().ifBlank { task.title }
        taskDao.updateTask(
            task.copy(
                title = title,
                planJson = planJson,
                currentNodeId = null,
                executionRunId = "",
                executionCursor = 0,
                updatedAt = now
            )
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.TASK_PLAN_UPDATED,
            payloadJson = jsonPayload {
                put("source", "USER_EDIT")
                put("planVersion", version)
                put("taskTitle", title)
                put("goalSummary", goalSummary.take(500))
                put("stepCount", stepCount)
                put("planHash", PromptDiagnostics.hash(planJson))
                put("planLength", planJson.length)
                put("previousPlanLength", previousLength)
                val previous = previousSummary.ifBlank {
                    if (previousLength > 0) "上一版计划 $previousLength 字符" else ""
                }
                if (previous.isNotBlank()) put("previousPlanSummary", previous.take(500))
            }
        )
        version
    }

    /**
     * 规划失败：任务 FAILED + TASK_FAILED + 失败提示消息，全部在一个事务内完成。
     * 用户原始目标不会被删除，可稍后通过 [restartPlanning] 重试。
     */
    suspend fun failPlanning(
        taskId: Long,
        errorMessage: String,
        promptLog: String = "",
        promptTokens: Int = -1,
        completionTokens: Int = 0,
        cachedTokens: Int = -1
    ): Unit = db.withTransaction {
        val task = taskDao.getTask(taskId)
            ?: throw IllegalArgumentException("Agent 任务不存在：$taskId")
        require(task.status == AgentTaskStatus.PLANNING || task.status == AgentTaskStatus.CREATED) {
            "只有规划中的任务可以标记失败，当前为 ${task.status}"
        }
        val message = errorMessage.take(1_000).ifBlank { "规划失败" }
        val now = System.currentTimeMillis()
        taskDao.updateTask(
            task.copy(
                status = AgentTaskStatus.FAILED,
                errorMessage = message,
                completedAt = now,
                updatedAt = now
            )
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.TASK_FAILED,
            payloadJson = jsonPayload { put("errorMessage", message.take(500)) }
        )
        val sequence = sessionDao.maxSequence(task.sessionId) + 1
        sessionDao.insertMessage(
            AgentMessage(
                sessionId = task.sessionId,
                taskId = taskId,
                role = AgentMessageRole.ASSISTANT,
                kind = AgentMessageKind.NOTICE,
                content = "规划失败：$message",
                sequence = sequence,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = cachedTokens,
                promptLog = promptLog.take(MAX_AGENT_PROMPT_LOG),
                createdAt = now
            )
        )
        sessionDao.touchSession(task.sessionId, now, now)
        Unit
    }

    /** 记录用户已确认计划；不改变任务状态，也不授权任何执行 */
    suspend fun markPlanConfirmed(taskId: Long): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        if (task.status != AgentTaskStatus.WAITING_CONFIRMATION) return@withTransaction false
        if (taskDao.eventsOfType(taskId, AgentEventType.CONFIRMATION_RECEIVED).isNotEmpty()) {
            return@withTransaction true
        }
        val version = taskDao.eventsOfType(taskId, AgentEventType.TASK_PLAN_UPDATED).size
        events.append(
            taskId = taskId,
            eventType = AgentEventType.CONFIRMATION_RECEIVED,
            payloadJson = jsonPayload {
                put("planVersion", version)
                put("source", "PLAN_REVIEW")
            }
        )
        true
    }

    /** 取消尚未执行的任务 */
    suspend fun cancelTask(taskId: Long) = updateStatus(taskId, AgentTaskStatus.CANCELLED)

    // ---------------- F7：暂停 / 恢复 / 执行游标 ----------------

    /**
     * 请求暂停。
     *
     * - RUNNING：只写持久化标记，由持有租约的执行器在安全边界落为 PAUSED；
     * - 没有执行者的 RUNNING（进程已死）：直接落为 PAUSED；
     * - WAITING_CONFIRMATION：保留等待确认状态，只写标记；
     * - 终态：返回 false。
     */
    suspend fun requestPause(taskId: Long): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        if (AgentTaskStatus.isTerminal(task.status)) return@withTransaction false
        val now = System.currentTimeMillis()
        taskDao.setPauseRequested(taskId, true, now)
        if (task.status == AgentTaskStatus.RUNNING &&
            task.executionOwner.isBlank()
        ) {
            taskDao.updateTask(
                task.copy(
                    status = AgentTaskStatus.PAUSED,
                    pauseRequested = true,
                    updatedAt = now
                )
            )
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_PAUSED,
                payloadJson = jsonPayload {
                    put("from", task.status)
                    put("source", "NO_ACTIVE_EXECUTOR")
                }
            )
        }
        true
    }

    /** 清除暂停标记；PAUSED 任务同时回到 RUNNING，由调度器继续执行 */
    suspend fun requestResume(taskId: Long): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        if (AgentTaskStatus.isTerminal(task.status)) return@withTransaction false
        val now = System.currentTimeMillis()
        taskDao.setPauseRequested(taskId, false, now)
        when (task.status) {
            AgentTaskStatus.PAUSED -> {
                taskDao.updateTask(
                    task.copy(
                        status = AgentTaskStatus.RUNNING,
                        pauseRequested = false,
                        errorMessage = "",
                        updatedAt = now
                    )
                )
                events.append(
                    taskId = taskId,
                    eventType = AgentEventType.TASK_RESUMED,
                    payloadJson = jsonPayload {
                        put("from", AgentTaskStatus.PAUSED)
                        put("to", AgentTaskStatus.RUNNING)
                        put("source", "USER_RESUME")
                    }
                )
                true
            }

            AgentTaskStatus.WAITING_CONFIRMATION -> true

            AgentTaskStatus.RUNNING -> true

            else -> task.status in listOf(
                AgentTaskStatus.CREATED,
                AgentTaskStatus.PLANNING
            )
        }
    }

    suspend fun setCancelRequested(taskId: Long, requested: Boolean): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        if (AgentTaskStatus.isTerminal(task.status)) return@withTransaction false
        taskDao.setCancelRequested(taskId, requested, System.currentTimeMillis())
        true
    }

    suspend fun setPauseRequested(taskId: Long, requested: Boolean): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        if (AgentTaskStatus.isTerminal(task.status)) return@withTransaction false
        taskDao.setPauseRequested(taskId, requested, System.currentTimeMillis())
        true
    }

    suspend fun isCancelRequested(taskId: Long): Boolean =
        taskDao.getTask(taskId)?.cancelRequested == true

    suspend fun isPauseRequested(taskId: Long): Boolean =
        taskDao.getTask(taskId)?.pauseRequested == true

    /** 记录本次执行的批次 id 与起始游标；只写执行控制字段，不改任务状态 */
    suspend fun beginExecutionRun(taskId: Long, runId: String, cursor: Int) {
        require(runId.isNotBlank()) { "runId 不能为空" }
        require(cursor >= 0) { "cursor 不能为负数" }
        taskDao.setExecutionRun(taskId, runId, cursor, System.currentTimeMillis())
    }

    /** 节点开始 / 结束时同步游标与当前节点 */
    suspend fun updateExecutionCursor(taskId: Long, cursor: Int, nodeId: Long?) {
        require(cursor >= 0) { "cursor 不能为负数" }
        taskDao.setExecutionCursor(taskId, cursor, nodeId, System.currentTimeMillis())
    }

    suspend fun recordRecoveryAttempt(taskId: Long, failureKind: String = "") {
        taskDao.recordRecoveryAttempt(taskId, failureKind, System.currentTimeMillis())
    }

    suspend fun setLastErrorKind(taskId: Long, failureKind: String) {
        taskDao.setLastErrorKind(taskId, failureKind, System.currentTimeMillis())
    }

    /**
     * 原子占位开始执行：只允许 WAITING_CONFIRMATION + 已确认 + 有计划的会话任务。
     * 在同一事务内把状态改为 RUNNING 并写 TASK_STARTED；并发第二次调用返回 false。
     */
    suspend fun tryStartExecution(taskId: Long, sessionId: Long): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        if (task.sessionId != sessionId) return@withTransaction false
        if (task.status != AgentTaskStatus.WAITING_CONFIRMATION) return@withTransaction false
        if (task.planJson.isBlank() || task.planJson == "{}") return@withTransaction false
        if (taskDao.eventsOfType(taskId, AgentEventType.CONFIRMATION_RECEIVED).isEmpty()) {
            return@withTransaction false
        }
        val now = System.currentTimeMillis()
        taskDao.updateTask(
            task.copy(
                status = AgentTaskStatus.RUNNING,
                startedAt = task.startedAt ?: now,
                completedAt = null,
                errorMessage = "",
                pauseRequested = false,
                cancelRequested = false,
                updatedAt = now
            )
        )
        events.append(
            taskId = taskId,
            eventType = AgentEventType.TASK_STARTED,
            payloadJson = jsonPayload {
                put("from", AgentTaskStatus.WAITING_CONFIRMATION)
                put("to", AgentTaskStatus.RUNNING)
                put("execution", true)
            }
        )
        true
    }

    /**
     * 删除尚未执行的任务：只允许没有执行节点 / 工具执行记录的任务。
     * 同一事务内删除任务消息、事件、节点、工具记录和任务本身，并修正会话最近任务。
     */
    suspend fun deleteTask(taskId: Long): Boolean = db.withTransaction {
        val task = taskDao.getTask(taskId) ?: return@withTransaction false
        require(task.status != AgentTaskStatus.COMPLETED) { "已完成任务不能删除" }
        require(taskDao.nodesOfTask(taskId).isEmpty()) { "任务已有执行节点，不能删除" }
        require(taskDao.toolExecutionsOfTask(taskId).isEmpty()) { "任务已有工具执行记录，不能删除" }

        taskDao.deleteEventsForTask(taskId)
        taskDao.deleteClarificationsForTask(taskId)
        taskDao.deleteNodesForTask(taskId)
        taskDao.deleteToolExecutionsForTask(taskId)
        taskDao.deleteTaskReferencesForTask(taskId)
        taskDao.deleteWriteOperationsForTask(taskId)
        taskDao.deleteModelRequestsForTask(taskId)
        taskDao.deleteRecoveryLogsForTask(taskId)
        sessionDao.deleteMessagesForTask(task.sessionId, taskId)
        taskDao.deleteTask(taskId)

        val session = sessionDao.getSession(task.sessionId)
        if (session?.lastTaskId == taskId) {
            val remaining = taskDao.tasksOfSession(task.sessionId).firstOrNull()?.id
            sessionDao.setLastTaskId(task.sessionId, remaining, System.currentTimeMillis())
        }
        true
    }

    private fun eventTypeFor(from: String, to: String): String? = when {
        to == AgentTaskStatus.PLANNING -> AgentEventType.TASK_PLANNING
        to == AgentTaskStatus.RUNNING &&
            from in listOf(AgentTaskStatus.PAUSED, AgentTaskStatus.WAITING_CONFIRMATION) ->
            AgentEventType.TASK_RESUMED
        to == AgentTaskStatus.RUNNING -> AgentEventType.TASK_STARTED
        to == AgentTaskStatus.WAITING_CONFIRMATION -> AgentEventType.WAITING_FOR_CONFIRMATION
        to == AgentTaskStatus.PAUSED -> AgentEventType.TASK_PAUSED
        to == AgentTaskStatus.COMPLETED -> AgentEventType.TASK_COMPLETED
        to == AgentTaskStatus.FAILED -> AgentEventType.TASK_FAILED
        to == AgentTaskStatus.CANCELLED -> AgentEventType.TASK_CANCELLED
        else -> null
    }
}
