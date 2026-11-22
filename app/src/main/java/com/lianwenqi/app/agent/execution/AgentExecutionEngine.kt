package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.agent.context.AgentContextAssembler
import com.lianwenqi.app.agent.context.AgentContextCompressor
import com.lianwenqi.app.agent.context.AgentContextItemStatus
import com.lianwenqi.app.agent.context.AgentContextResult
import com.lianwenqi.app.agent.context.ContextEstimator
import com.lianwenqi.app.agent.data.AgentEventRepository
import com.lianwenqi.app.agent.data.AgentExecutionLeaseRepository
import com.lianwenqi.app.agent.data.AgentExecutionNodeRepository
import com.lianwenqi.app.agent.data.AgentModelRequestRepository
import com.lianwenqi.app.agent.data.AgentNodeSpec
import com.lianwenqi.app.agent.data.AgentSessionRepository
import com.lianwenqi.app.agent.data.AgentTaskReferenceRepository
import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentToolExecutionRepository
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.entity.AgentEventType
import com.lianwenqi.app.agent.data.entity.AgentMessage
import com.lianwenqi.app.agent.data.entity.AgentMessageKind
import com.lianwenqi.app.agent.data.entity.AgentMessageRole
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.data.entity.AgentNodeType
import com.lianwenqi.app.agent.data.entity.AgentTaskStatus
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.agent.data.entity.AgentWorkMode
import com.lianwenqi.app.agent.data.entity.AgentWriteOperation
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationStatus
import com.lianwenqi.app.agent.data.entity.AgentWriteOperationType
import com.lianwenqi.app.agent.data.entity.AgentWritePermissionStatus
import com.lianwenqi.app.agent.data.jsonPayload
import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanParseResult
import com.lianwenqi.app.agent.model.AgentPlanStep
import com.lianwenqi.app.agent.planning.AgentModelClient
import com.lianwenqi.app.agent.planning.AgentModelResponse
import com.lianwenqi.app.agent.planning.AgentPlanParser
import com.lianwenqi.app.agent.planning.AgentPlanValidator
import com.lianwenqi.app.agent.tool.AgentExecutionContext
import com.lianwenqi.app.agent.tool.AgentToolExecutionOutcome
import com.lianwenqi.app.agent.tool.AgentToolExecutor
import com.lianwenqi.app.agent.tool.AgentToolRegistry
import com.lianwenqi.app.agent.write.AgentWriteConfirmationGate
import com.lianwenqi.app.agent.write.AgentWriteExecutionService
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.data.entity.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.put

/** 注入 Agent 上下文的历史对话字符上限 */
private const val MAX_HISTORY_CHARS = 12_000

/** 自动上下文压缩在工具记录里的名称与展示名 */
private const val CONTEXT_COMPRESS_TOOL = "context_compress"
private const val CONTEXT_COMPRESS_TOOL_LABEL = "自动压缩上下文"

/** 同一任务最多自动压缩次数，避免压缩后仍超限时无限循环 */
private const val MAX_COMPRESSIONS_PER_TASK = 3

/** 执行日志（提交给模型的完整输入）上限，避免单条消息过大 */
private const val MAX_EXECUTION_LOG_CHARS = 200_000

/** F7 执行入口参数；为空时保持 F4/F6 的“单次直接执行”兼容行为 */
data class AgentExecutionOptions(
    /** 执行租约持有者；空表示旧式直接执行（测试 / 页面内兼容路径） */
    val owner: String = "",
    /** 任务级执行尝试次数，由租约提供 */
    val attempt: Int = 1,
    /** 写入提议后是否在进程内等待确认；后台执行必须为 false */
    val waitForWriteConfirmation: Boolean = true,
    /** 本次是否从持久化游标恢复 */
    val recovery: Boolean = false
)

/**
 * Agent 执行引擎。
 *
 * F7 在 F4/F6 基础上增加：
 * - 从 `executionRunId + executionCursor` 恢复，不重跑已完成节点；
 * - 持有租约期间在模型请求 / 工具调用 / 节点边界检查暂停、取消与租约；
 * - 记录每次模型请求的 STARTED / SUCCEEDED / FAILED / UNKNOWN；
 * - 后台执行时遇到待确认写入立即停止，不无限等待。
 *
 * 引擎不决定是否重新调度；调度与恢复日志由 AgentTaskRunner / Worker 负责。
 */
class AgentExecutionEngine(
    private val tasks: AgentTaskRepository,
    private val nodes: AgentExecutionNodeRepository,
    private val toolExecutions: AgentToolExecutionRepository,
    private val events: AgentEventRepository,
    private val sessions: AgentSessionRepository,
    private val modelClient: AgentModelClient,
    private val registry: AgentToolRegistry,
    private val toolExecutor: AgentToolExecutor,
    private val planParser: AgentPlanParser,
    private val planValidator: AgentPlanValidator,
    private val policy: AgentExecutionPolicy = AgentExecutionPolicy,
    private val limits: AgentExecutionLimits = AgentExecutionLimits(),
    /** F5：用户授权历史资料的上下文组装器；为空表示本次执行不注入引用资料 */
    private val contextAssembler: AgentContextAssembler? = null,
    /** F6：受控写入操作、执行服务与确认等待器 */
    private val writeOperations: AgentWriteOperationRepository? = null,
    private val writeService: AgentWriteExecutionService? = null,
    private val writeGate: AgentWriteConfirmationGate? = null,
    /** F7：执行租约与模型请求记录；测试可省略 */
    private val leases: AgentExecutionLeaseRepository? = null,
    private val modelRequests: AgentModelRequestRepository? = null,
    /** 上下文自动压缩；为空表示不启用 */
    private val contextCompressor: AgentContextCompressor? = null
) {

    suspend fun execute(
        taskId: Long,
        sessionId: Long,
        options: AgentExecutionOptions = AgentExecutionOptions()
    ): AgentExecutionResult {
        val initial = tasks.getTask(taskId)
            ?: return AgentExecutionResult.Blocked(taskId, "任务不存在")
        if (initial.sessionId != sessionId) {
            return AgentExecutionResult.Blocked(taskId, "任务不属于当前 Agent 会话")
        }
        if (AgentTaskStatus.isTerminal(initial.status)) {
            return AgentExecutionResult.Blocked(
                taskId,
                "任务已处于终态 ${initial.status}，不能再次执行"
            )
        }
        if (initial.cancelRequested) {
            if (options.owner.isBlank()) {
                finalizeCancelled(taskId, null, initial.executionCursor)
            }
            return AgentExecutionResult.Cancelled(taskId)
        }
        if (options.owner.isNotBlank() && leases != null &&
            !leases.isHeld(taskId, options.owner)
        ) {
            return AgentExecutionResult.Blocked(taskId, "执行租约已失效，停止执行")
        }
        if (options.owner.isBlank()) {
            if (initial.pauseRequested) {
                return AgentExecutionResult.Blocked(taskId, "任务已请求暂停")
            }
            if (initial.status == AgentTaskStatus.WAITING_CONFIRMATION) {
                if (events.eventsOfType(taskId, AgentEventType.CONFIRMATION_RECEIVED).isEmpty()) {
                    return AgentExecutionResult.Blocked(taskId, "计划尚未确认，不能开始执行")
                }
                if (!tasks.tryStartExecution(taskId, sessionId)) {
                    return AgentExecutionResult.Blocked(taskId, "任务正在执行或状态不允许")
                }
            } else if (initial.status != AgentTaskStatus.RUNNING) {
                return AgentExecutionResult.Blocked(
                    taskId,
                    "任务当前状态为 ${initial.status}，只有等待审核或执行中的计划可以开始执行"
                )
            }
        } else if (initial.status != AgentTaskStatus.RUNNING) {
            return AgentExecutionResult.Blocked(
                taskId,
                "任务状态为 ${initial.status}，后台执行器只处理 RUNNING 任务"
            )
        }

        val task = tasks.getTask(taskId)
            ?: return AgentExecutionResult.Blocked(taskId, "任务不存在")
        // 自动模式：直接回答为目标，允许不调用工具就完成
        val autoMode = task.workMode == AgentWorkMode.AUTO
        if (task.planJson.isBlank() || task.planJson == "{}") {
            return AgentExecutionResult.Blocked(taskId, "任务没有可执行计划")
        }
        if (events.eventsOfType(taskId, AgentEventType.CONFIRMATION_RECEIVED).isEmpty()) {
            return AgentExecutionResult.Blocked(taskId, "计划尚未确认，不能开始执行")
        }
        val plan = when (val parsed = planParser.parse(task.planJson)) {
            is AgentPlanParseResult.Failure ->
                return AgentExecutionResult.Blocked(taskId, "计划无法解析：${parsed.message}")
            is AgentPlanParseResult.Success -> parsed.plan
        }
        val validation = planValidator.validate(plan)
        if (!validation.isValid) {
            return AgentExecutionResult.Blocked(
                taskId,
                "计划校验未通过：" + validation.errors.take(3).joinToString("；")
            )
        }
        if (plan.steps.isEmpty()) {
            return AgentExecutionResult.Blocked(taskId, "计划没有可执行步骤")
        }
        val blockedSteps = plan.steps.filter { !policy.isCapabilityExecutable(it.capabilityKey) }
        if (blockedSteps.isNotEmpty()) {
            return AgentExecutionResult.Blocked(
                taskId,
                "计划包含当前阶段不可执行的步骤：" +
                    blockedSteps.joinToString("、") {
                        "${it.title}（${it.capabilityKey}）"
                    }
            )
        }
        val ordered = topologicalOrder(plan.steps)
            ?: return AgentExecutionResult.Blocked(taskId, "计划依赖顺序异常，无法执行")
        val session = sessions.getSession(sessionId)
        val modelId = task.modelId ?: session?.modelId ?: 0L
        if (modelId <= 0) {
            return AgentExecutionResult.Blocked(taskId, "任务未关联可用模型")
        }
        val toolSpecs = registry.toSpecs()
        if (toolSpecs.isEmpty()) {
            return AgentExecutionResult.Blocked(taskId, "没有注册任何工具")
        }
        // 上下文窗口只取真实配置；未知时由压缩器决定是否使用默认值
        val contextWindow = contextCompressor?.let { modelClient.contextWindow(modelId) } ?: 0
        writeOperations?.let { operations ->
            if (options.owner.isBlank()) {
                // 兼容旧式直接执行：没有 Runner 时仍先清理遗留 EXECUTING
                runCatching { operations.failStaleExecuting(taskId) }
            }
            if (operations.hasUnresolved(taskId)) {
                return AgentExecutionResult.Blocked(
                    taskId,
                    "任务存在待确认写入操作，请先批准或拒绝后再继续执行"
                )
            }
        }

        val runId = task.executionRunId.takeIf { it.isNotBlank() }
            ?: System.currentTimeMillis().toString().also {
                tasks.beginExecutionRun(taskId, it, 0)
            }
        val executionContext = AgentExecutionContext(
            sessionId = sessionId,
            taskId = taskId,
            runId = runId.toLongOrNull() ?: runId.hashCode().toLong(),
            bookId = session?.bookId
        )
        var runNodes = nodes.nodesOfTask(taskId)
            .filter { it.nodeKey.startsWith("$runId:") }
        if (runNodes.isEmpty()) {
            val baseSequence = nodes.maxSequence(taskId) + 1
            nodes.createNodes(
                taskId = taskId,
                specs = ordered.map { step ->
                    AgentNodeSpec(
                        nodeKey = "$runId:${step.stepId}",
                        nodeType = policy.nodeTypeFor(step.capabilityKey),
                        title = step.title,
                        description = step.description,
                        inputJson = jsonPayload {
                            put("stepId", step.stepId)
                            put("capabilityKey", step.capabilityKey)
                            put("runId", runId)
                        }
                    )
                },
                startSequence = baseSequence
            )
            runNodes = nodes.nodesOfTask(taskId)
                .filter { it.nodeKey.startsWith("$runId:") }
        }
        val resumePlan = AgentExecutionResumePlanner.plan(ordered, runNodes)
        if (resumePlan.missingNodeStepId != null) {
            return AgentExecutionResult.Blocked(
                taskId,
                "执行节点缺失（${resumePlan.missingNodeStepId}），无法恢复；请重新生成计划"
            )
        }
        if (resumePlan.blockedNodeTitle != null) {
            return AgentExecutionResult.Blocked(
                taskId,
                "节点「${resumePlan.blockedNodeTitle}」处于不可恢复状态，不能自动恢复"
            )
        }
        resumePlan.reopenNodeId?.let { nodeId ->
            nodes.reopenForRecovery(nodeId, "恢复执行：重新打开未完成节点")
        }
        val startIndex = resumePlan.startIndex
        val nodeIdByStepId = resumePlan.nodeIdByStepId
        val stepResults = ordered.take(resumePlan.stepResults.size)
            .zip(resumePlan.stepResults)
            .toMutableList()
        val startNodeId = ordered.getOrNull(startIndex)?.let { nodeIdByStepId[it.stepId] }
        tasks.updateExecutionCursor(taskId, startIndex, startNodeId)

        val runNodeIds = runNodes.mapNotNull { it.id }.toSet()
        val alreadySucceeded = toolExecutions.succeededExecutions(taskId)
            .filter { it.nodeId != null && it.nodeId in runNodeIds }
        val toolContext = StringBuilder()
        alreadySucceeded.forEachIndexed { index, execution ->
            val block = AgentToolExecutionOutcome(
                executionId = execution.id,
                toolName = execution.toolName,
                status = execution.status,
                resultJson = execution.resultJson,
                errorMessage = execution.errorMessage,
                reused = true
            ).toModelBlock(index + 1)
            val remaining = limits.maxContextToolResultChars - toolContext.length
            if (remaining <= 0) return@forEachIndexed
            if (toolContext.isNotEmpty()) toolContext.append('\n')
            toolContext.append(
                if (block.length <= remaining) block else block.take(remaining) + "\n（工具结果上下文已截断）"
            )
        }

        var currentNodeId: Long? = null
        var currentIndex = startIndex
        // 每次执行轮次重新计预算；否则失败重试会被历史调用次数直接卡死
        var totalToolCalls = 0
        var totalModelRounds = 0
        var lastPromptTokens = -1
        // 汇总本次执行所有模型调用的 usage，写入最终回答供界面回显缓存命中率
        var totalPromptTokens = 0
        var totalCompletionTokens = 0
        var totalCachedTokens = 0
        var usagePromptKnown = true
        var usageCacheKnown = true
        // 本轮执行提交给模型的完整输入（按步骤记录），写入最终回答供「日志」查看
        val executionLog = StringBuilder()
        val signatureCounts = mutableMapOf<String, Int>()
        val startedAt = System.currentTimeMillis()
        var waitAccumulatedMs = 0L
        // 用户在任务中途回答的补充信息：恢复执行时注入模型上下文
        // 压缩确认属于控制指令，不作为业务补充信息注入
        val clarificationBlock = runCatching {
            tasks.answeredClarifications(taskId)
                .filterNot { it.question.trim() == AgentContextCompressor.QUESTION_TEXT }
                .joinToString("\n") {
                    "问：${it.question}\n答：${it.answer}"
                }
        }.getOrDefault("")
        // Agent 会话是连续的：把本会话此前轮次的历史对话注入当前任务（与前缀词设置无关）
        val historyBlock = runCatching {
            buildConversationHistory(taskId, task.sessionId, truncate = !autoMode)
        }.getOrDefault("")

        try {
            val referenceContext = assembleReferenceContext(taskId, sessionId, unlimited = autoMode)
            if (!options.recovery) {
                notifyReferenceStatus(sessionId, taskId, referenceContext)
            }
            val recoveryContext = if (options.recovery) {
                buildRecoveryContext(taskId, startNodeId)
            } else {
                ""
            }

            for (index in startIndex until ordered.size) {
                val step = ordered[index]
                val stepNodeId = nodeIdByStepId.getValue(step.stepId)
                currentIndex = index
                currentNodeId = stepNodeId
                checkControl(taskId, options)
                tasks.updateExecutionCursor(taskId, index, stepNodeId)
                nodes.updateStatus(stepNodeId, AgentNodeStatus.RUNNING)

                var finalText: String? = null
                var finalRequestId: Long? = null
                var stepRounds = 0
                var stepToolCalls = 0
                /** 自动模式“只声称写入却没调用工具”的纠正次数，避免无限纠正 */
                var writeClaimNudges = 0
                val turns = mutableListOf<ChatTurn>()
                val authorizedBlock = buildString {
                    if (referenceContext.promptBlock.isNotBlank()) {
                        append(referenceContext.promptBlock)
                    }
                    if (recoveryContext.isNotBlank()) {
                        if (isNotEmpty()) appendLine()
                        append(recoveryContext)
                    }
                    if (historyBlock.isNotBlank()) {
                        if (isNotEmpty()) appendLine()
                        append("【历史对话】\n").append(historyBlock)
                    }
                    if (clarificationBlock.isNotBlank()) {
                        if (isNotEmpty()) appendLine()
                        append("【用户补充信息】\n").append(clarificationBlock)
                    }
                }
                val toolResults = if (toolContext.isBlank()) {
                    emptyList()
                } else {
                    listOf(toolContext.toString())
                }
                turns += ChatTurn(
                    ChatRole.SYSTEM,
                    if (autoMode) {
                        AgentExecutionPrompt.systemPromptAuto()
                    } else {
                        AgentExecutionPrompt.systemPrompt()
                    }
                )
                turns += ChatTurn(
                    ChatRole.USER,
                    if (autoMode) {
                        AgentExecutionPrompt.autoPrompt(
                            goal = task.userGoal,
                            toolResults = toolResults,
                            authorizedContext = authorizedBlock
                        )
                    } else {
                        AgentExecutionPrompt.stepPrompt(
                            taskGoal = task.userGoal,
                            plan = plan,
                            step = step,
                            priorStepResults = stepResults.map { it.second }.toList(),
                            toolResults = toolResults,
                            authorizedContext = authorizedBlock
                        )
                    }
                )

                while (finalText == null) {
                    checkControl(taskId, options)
                    // 上下文接近上限：计划执行模式自动压缩；自动模式先询问用户
                    applyContextCompression(
                        taskId = taskId,
                        nodeId = stepNodeId,
                        autoMode = autoMode,
                        modelId = modelId,
                        turns = turns,
                        contextWindow = contextWindow,
                        stepIndex = index,
                        actualPromptTokens = lastPromptTokens
                    )?.let { return it }
                    val elapsed = System.currentTimeMillis() - startedAt - waitAccumulatedMs
                    if (elapsed > limits.maxTaskDurationMs) {
                        throw ExecutionFailure(
                            AgentFailureKind.BACKGROUND_LIMIT,
                            "执行超时（超过 ${limits.maxTaskDurationMs / 60_000} 分钟）"
                        )
                    }
                    if (totalModelRounds >= limits.maxModelRoundsPerTask) {
                        throw ExecutionFailure(
                            AgentFailureKind.VALIDATION,
                            "模型调用轮数超过上限（${limits.maxModelRoundsPerTask}）"
                        )
                    }
                    totalModelRounds++
                    stepRounds++

                    val call = requestModelWithRetry(
                        taskId = taskId,
                        nodeId = stepNodeId,
                        runId = runId,
                        round = stepRounds - 1,
                        modelId = modelId,
                        modelName = "",
                        turns = turns,
                        toolSpecs = toolSpecs,
                        options = options
                    )
                    val response = call.response
                    // 记录服务端真实 prompt 用量，作为下一轮是否压缩的优先依据
                    if (response.usage.promptTokens > 0) {
                        lastPromptTokens = response.usage.promptTokens
                    }
                    // 累计执行期 usage；任一请求缺少字段时整体按「未知」展示，不用 0 冒充
                    if (response.usage.promptTokens >= 0) {
                        totalPromptTokens += response.usage.promptTokens
                    } else {
                        usagePromptKnown = false
                    }
                    totalCompletionTokens += response.usage.completionTokens
                    if (response.usage.cachedTokens >= 0) {
                        totalCachedTokens += response.usage.cachedTokens
                    } else {
                        usageCacheKnown = false
                    }
                    if (response.content.length > limits.maxModelResponseChars) {
                        throw ExecutionFailure(
                            AgentFailureKind.VALIDATION,
                            "模型响应超过上限"
                        )
                    }

                    val calls = response.toolCalls
                    if (calls.isEmpty()) {
                        val text = response.content.trim()
                        if (text.isEmpty()) {
                            throw ExecutionFailure(AgentFailureKind.MODEL_PARSE, "模型返回为空")
                        }
                        val successes = toolExecutions.executionsOfTask(taskId)
                            .filter { it.status == AgentToolStatus.SUCCEEDED }
                        val nodeHasSuccess = successes.any { it.nodeId == stepNodeId }
                        // 自动模式兜底：正文声称已新建 / 已修改，但本任务没有任何写入记录时，
                        // 说明模型把“文字描述”当成了提交，纠正一轮要求它真正调用写入工具
                        val hasWriteRecord = writeOperations?.ofTask(taskId)?.isNotEmpty() == true
                        if (autoMode && writeClaimNudges < 1 && !hasWriteRecord &&
                            looksLikeWriteClaim(text)
                        ) {
                            writeClaimNudges++
                            call.requestId?.let { modelRequests?.markApplied(it) }
                            turns += ChatTurn(ChatRole.ASSISTANT, text)
                            turns += ChatTurn(
                                ChatRole.USER,
                                "你还没有调用任何写入工具，上面的描述没有保存到小说数据。" +
                                    "请立即调用对应的写入工具提交修改（如 create_outline_node、" +
                                    "update_outline_node）；只有工具返回待确认或写入成功才算完成，" +
                                    "不要只用文字描述。"
                            )
                            continue
                        }
                        if (policy.isWriteCapability(step.capabilityKey)) {
                            val hasWriteOperation = writeOperations
                                ?.hasOperationForNode(taskId, stepNodeId) == true
                            if (!hasWriteOperation) {
                                throw ExecutionFailure(
                                    AgentFailureKind.VALIDATION,
                                    "步骤「${step.title}」没有产生真实写入提议或执行记录，" +
                                        "不能标记为完成"
                                )
                            }
                        } else if (!autoMode &&
                            policy.requiresTool(step.capabilityKey) && !nodeHasSuccess
                        ) {
                            throw ExecutionFailure(
                                AgentFailureKind.VALIDATION,
                                "步骤「${step.title}」未产生真实工具结果，不能标记为完成"
                            )
                        } else if (!autoMode && !policy.requiresTool(step.capabilityKey) &&
                            successes.isEmpty()
                        ) {
                            throw ExecutionFailure(
                                AgentFailureKind.VALIDATION,
                                "步骤「${step.title}」缺少真实工具结果，不能标记为完成"
                            )
                        }
                        finalText = text.take(limits.maxStepOutputChars)
                        finalRequestId = call.requestId
                    } else {
                        turns += ChatTurn(
                            ChatRole.ASSISTANT,
                            "调用工具：" + calls.joinToString("、") { it.name }
                        )
                        val pendingWriteIds = mutableListOf<Long>()
                        for (call in calls) {
                            checkControl(taskId, options)
                            if (totalToolCalls >= limits.maxToolCallsPerTask) {
                                throw ExecutionFailure(
                                    AgentFailureKind.VALIDATION,
                                    "工具调用次数超过上限（${limits.maxToolCallsPerTask}）"
                                )
                            }
                            totalToolCalls++
                            stepToolCalls++
                            val signature = call.name + "\u0000" + call.arguments
                            val count = (signatureCounts[signature] ?: 0) + 1
                            signatureCounts[signature] = count
                            if (count > limits.maxIdenticalCalls) {
                                throw ExecutionFailure(
                                    AgentFailureKind.VALIDATION,
                                    "同一工具与参数重复调用超过上限"
                                )
                            }
                            val outcome = toolExecutor.execute(
                                executionContext.copy(nodeId = stepNodeId),
                                call,
                                stepNodeId
                            )
                            if (outcome.awaitingUserInput) {
                                // ask_user：问题已落库并展示给用户，暂停任务等待回答
                                tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
                                tasks.updateExecutionCursor(taskId, index, stepNodeId)
                                runCatching {
                                    nodes.updateStatus(
                                        stepNodeId,
                                        AgentNodeStatus.WAITING,
                                        errorMessage = "等待用户回答"
                                    )
                                }
                                throw AgentExecutionWaitingConfirmationException(pendingCount = 0)
                            }
                            if (outcome.writeRequiresConfirmation && outcome.writeOperationId != null) {
                                pendingWriteIds += outcome.writeOperationId
                            }
                            val block = outcome.toModelBlock(totalToolCalls)
                            val remaining = limits.maxContextToolResultChars - toolContext.length
                            val clipped = if (block.length <= remaining) {
                                block
                            } else {
                                block.take(remaining.coerceAtLeast(0)) +
                                    "\n（工具结果上下文已截断）"
                            }
                            if (clipped.isNotBlank()) {
                                if (toolContext.isNotEmpty()) toolContext.append('\n')
                                toolContext.append(clipped)
                                turns += ChatTurn(ChatRole.USER, clipped)
                            }
                        }
                        call.requestId?.let { modelRequests?.markApplied(it) }
                        if (pendingWriteIds.isNotEmpty()) {
                            tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
                            tasks.updateExecutionCursor(taskId, index, stepNodeId)
                            runCatching {
                                nodes.updateStatus(
                                    stepNodeId,
                                    AgentNodeStatus.WAITING,
                                    errorMessage = "等待写入确认"
                                )
                            }
                            // 待确认写入通过弹窗展示，不再写对话消息
                            if (!options.waitForWriteConfirmation) {
                                throw AgentExecutionWaitingConfirmationException(
                                    pendingCount = pendingWriteIds.size
                                )
                            }
                            val waitStart = System.currentTimeMillis()
                            awaitWriteConfirmations(
                                sessionId = sessionId,
                                taskId = taskId,
                                pendingOperationIds = pendingWriteIds,
                                turns = turns,
                                toolContext = toolContext
                            )
                            waitAccumulatedMs += System.currentTimeMillis() - waitStart
                            runCatching {
                                nodes.updateStatus(stepNodeId, AgentNodeStatus.RUNNING)
                                tasks.updateExecutionCursor(taskId, index, stepNodeId)
                            }
                        }
                    }
                }

                recordStepPromptLog(
                    builder = executionLog,
                    step = step,
                    stepIndex = index,
                    stepCount = ordered.size,
                    turns = turns
                )

                checkControl(taskId, options)
                // 自动模式兜底：模型把提问写在正文里时转成提问弹窗，避免用户只能反复打字确认
                val answerText = finalText.orEmpty()
                if (autoMode && looksLikeUserQuestion(answerText)) {
                    val created = runCatching {
                        tasks.createClarification(
                            taskId = taskId,
                            question = answerText.trim().take(2_000),
                            options = emptyList()
                        )
                    }.isSuccess
                    if (created) {
                        tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
                        tasks.updateExecutionCursor(taskId, index, stepNodeId)
                        runCatching {
                            nodes.updateStatus(
                                stepNodeId,
                                AgentNodeStatus.WAITING,
                                errorMessage = "等待用户回答"
                            )
                        }
                        return AgentExecutionResult.WaitingConfirmation(taskId, 0)
                    }
                }
                nodes.updateStatus(
                    nodeId = stepNodeId,
                    status = AgentNodeStatus.COMPLETED,
                    outputJson = jsonPayload {
                        put("stepId", step.stepId)
                        put("capabilityKey", step.capabilityKey)
                        put("result", finalText.take(limits.maxStepOutputChars))
                        put("modelRounds", stepRounds)
                        put("toolCalls", stepToolCalls)
                    }
                )
                // 节点结果已落库后才标记响应已消费；否则恢复时会记录重复请求风险。
                finalRequestId?.let { modelRequests?.markApplied(it) }
                stepResults += step to finalText
                tasks.updateExecutionCursor(taskId, index + 1, null)
                currentNodeId = null
            }

            checkControl(taskId, options)
            // 最终消息预算跟随模型的最大输出 token 设置；不限输出（<=0）时不做限制
            val messageBudget = outputBudgetChars(modelClient.outputTokenLimit(modelId))
            val summary = buildFinalSummary(plan, stepResults, autoMode, messageBudget)
            val completeSequence = nodes.maxSequence(taskId) + 1
            val completeNode = nodes.createNode(
                taskId = taskId,
                nodeType = AgentNodeType.TASK_COMPLETE,
                title = "任务完成",
                nodeKey = "$runId:task_complete",
                sequence = completeSequence
            )
            nodes.updateStatus(
                nodeId = completeNode,
                status = AgentNodeStatus.COMPLETED,
                outputJson = jsonPayload { put("summary", summary.take(limits.maxStepOutputChars)) }
            )
            tasks.updateExecutionCursor(taskId, ordered.size, null)
            tasks.updateStatus(taskId, AgentTaskStatus.COMPLETED)
            // 计划模式：方案与执行结果已由计划卡 / 执行过程卡承载，不再追加总结或“执行完毕”消息
            if (autoMode) {
                runCatching {
                    sessions.addMessage(
                        sessionId = sessionId,
                        taskId = taskId,
                        role = AgentMessageRole.ASSISTANT,
                        kind = AgentMessageKind.FINAL_ANSWER,
                        content = summary,
                        promptTokens = if (usagePromptKnown && totalPromptTokens > 0) {
                            totalPromptTokens
                        } else {
                            AgentMessage.PROMPT_TOKENS_UNKNOWN
                        },
                        completionTokens = totalCompletionTokens,
                        cachedTokens = if (usageCacheKnown) {
                            totalCachedTokens
                        } else {
                            AgentMessage.CACHE_TOKENS_UNKNOWN
                        },
                        promptLog = executionLog.toString().take(MAX_EXECUTION_LOG_CHARS)
                    )
                }
            }
            return AgentExecutionResult.Completed(taskId, summary)
        } catch (e: AgentExecutionPausedException) {
            withContext(NonCancellable) {
                finalizePaused(taskId, currentNodeId, currentIndex)
            }
            return AgentExecutionResult.Paused(taskId)
        } catch (e: AgentExecutionCancelledException) {
            withContext(NonCancellable) {
                finalizeCancelled(taskId, currentNodeId, currentIndex)
            }
            return AgentExecutionResult.Cancelled(taskId)
        } catch (e: AgentExecutionWaitingConfirmationException) {
            return AgentExecutionResult.WaitingConfirmation(taskId, e.pendingCount)
        } catch (e: AgentExecutionLeaseLostException) {
            withContext(NonCancellable) {
                recordInterruption(taskId, "LEASE_LOST", currentNodeId, currentIndex)
            }
            throw e
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                handleCoroutineCancellation(taskId, sessionId, options, currentNodeId, currentIndex)
            }
            throw e
        } catch (e: Throwable) {
            val kind = if (e is ExecutionFailure) {
                e.kind
            } else {
                AgentFailureClassifier.fromThrowable(e)
            }
            val message = e.message ?: AgentFailureKind.label(kind)
            if (options.owner.isNotBlank()) {
                val decision = AgentRetryPolicy.taskDecision(kind, options.attempt)
                if (decision.retryable) {
                    withContext(NonCancellable) {
                        runCatching {
                            currentNodeId?.let { id ->
                                nodes.updateStatus(
                                    id,
                                    AgentNodeStatus.WAITING,
                                    errorMessage = "临时失败，等待重试：" + message.take(200)
                                )
                            }
                            tasks.updateExecutionCursor(taskId, currentIndex, currentNodeId)
                            tasks.recordRecoveryAttempt(taskId, kind)
                            events.append(
                                taskId = taskId,
                                eventType = AgentEventType.TASK_RETRY_SCHEDULED,
                                nodeId = currentNodeId,
                                payloadJson = jsonPayload {
                                    put("failureKind", kind)
                                    put("attempt", options.attempt)
                                    put("maxAttempts", decision.maxAttempts)
                                    put("message", message.take(500))
                                }
                            )
                        }
                    }
                    throw AgentRetryableFailureException(kind, message)
                }
            }
            withContext(NonCancellable) {
                runCatching {
                    writeService?.cancelPending(taskId, "任务失败，未执行的写入提议已失效")
                }
                currentNodeId?.let { id ->
                    runCatching {
                        nodes.updateStatus(
                            id,
                            AgentNodeStatus.FAILED,
                            errorMessage = message.take(500)
                        )
                    }
                }
                runCatching { tasks.updateExecutionCursor(taskId, currentIndex, currentNodeId) }
                runCatching { tasks.setLastErrorKind(taskId, kind) }
                // 失败原因记录在节点与任务上，由执行过程卡片统一展示，不再写对话消息
                runCatching { tasks.updateStatus(taskId, AgentTaskStatus.FAILED, message) }
            }
            return AgentExecutionResult.Failed(taskId, message)
        }
    }

    // ---------------- F7：控制与恢复 ----------------

    private suspend fun checkControl(taskId: Long, options: AgentExecutionOptions) {
        val current = tasks.getTask(taskId)
            ?: throw AgentExecutionCancelledException("任务已不存在")
        if (AgentTaskStatus.isTerminal(current.status)) {
            throw AgentExecutionCancelledException("任务已结束：${current.status}")
        }
        if (current.cancelRequested) throw AgentExecutionCancelledException()
        if (current.pauseRequested) throw AgentExecutionPausedException()
        if (options.owner.isNotBlank() && leases != null &&
            !leases.isHeld(taskId, options.owner)
        ) {
            throw AgentExecutionLeaseLostException()
        }
    }

    private suspend fun handleCoroutineCancellation(
        taskId: Long,
        sessionId: Long,
        options: AgentExecutionOptions,
        currentNodeId: Long?,
        currentIndex: Int
    ) {
        val task = tasks.getTask(taskId) ?: return
        val leaseLost = options.owner.isNotBlank() && leases != null &&
            !leases.isHeld(taskId, options.owner)
        if (leaseLost) {
            recordInterruption(taskId, "LEASE_LOST", currentNodeId, currentIndex)
            return
        }
        when {
            task.cancelRequested -> finalizeCancelled(
                taskId, currentNodeId, currentIndex
            )

            task.pauseRequested -> finalizePaused(
                taskId, currentNodeId, currentIndex
            )

            else -> {
                // 页面销毁 / Worker 被系统停止：任务保持可恢复。
                currentNodeId?.let { id ->
                    runCatching {
                        nodes.updateStatus(
                            id,
                            AgentNodeStatus.WAITING,
                            errorMessage = "执行被中断，可恢复"
                        )
                    }
                }
                // 若中断发生在写入提议已落库、任务状态尚未切换的窗口，必须停在等待确认，
                // 不能让待确认写入被误认为仍在执行中的普通步骤。
                val pendingWrites = runCatching {
                    writeOperations?.hasUnresolved(taskId) == true
                }.getOrDefault(false)
                if (pendingWrites) {
                    runCatching {
                        tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
                    }
                }
                runCatching { tasks.updateExecutionCursor(taskId, currentIndex, currentNodeId) }
                recordInterruption(taskId, "PROCESS_OR_WORKER_STOPPED", currentNodeId, currentIndex)
            }
        }
    }

    private suspend fun recordInterruption(
        taskId: Long,
        reason: String,
        currentNodeId: Long?,
        currentIndex: Int
    ) {
        runCatching {
            events.append(
                taskId = taskId,
                eventType = AgentEventType.TASK_INTERRUPTED,
                nodeId = currentNodeId,
                payloadJson = jsonPayload {
                    put("reason", reason)
                    put("cursor", currentIndex)
                }
            )
        }
    }

    private suspend fun finalizePaused(
        taskId: Long,
        currentNodeId: Long?,
        currentIndex: Int
    ) {
        currentNodeId?.let { id ->
            runCatching {
                nodes.updateStatus(id, AgentNodeStatus.WAITING, errorMessage = "用户暂停")
            }
        }
        runCatching { tasks.updateExecutionCursor(taskId, currentIndex, currentNodeId) }
        // 暂停状态由任务与执行过程卡片展示，不再写对话消息
        runCatching { tasks.updateStatus(taskId, AgentTaskStatus.PAUSED) }
    }

    private suspend fun finalizeCancelled(
        taskId: Long,
        currentNodeId: Long?,
        currentIndex: Int
    ) {
        runCatching { writeService?.cancelPending(taskId, "用户取消任务") }
        currentNodeId?.let { id ->
            runCatching {
                nodes.updateStatus(id, AgentNodeStatus.CANCELLED, errorMessage = "用户取消")
            }
        }
        runCatching { tasks.updateExecutionCursor(taskId, currentIndex, currentNodeId) }
        // 取消状态由任务与执行过程卡片展示，不再写对话消息
        runCatching { tasks.updateStatus(taskId, AgentTaskStatus.CANCELLED, "用户取消") }
    }

    private data class AgentModelCallResult(
        val response: AgentModelResponse,
        val requestId: Long?
    )

    /**
     * 上下文接近模型窗口上限时的处理。
     *
     * - 计划执行模式：直接压缩，压缩过程以一条“自动压缩上下文”工具记录回显；
     * - 自动模式：先询问用户，同意后压缩继续，拒绝则停止并提示新开对话；
     * - 压缩失败或压缩次数达到上限时抛出可恢复的失败，不伪装成成功。
     *
     * 返回非空表示任务需要暂停等待用户确认。
     */
    private suspend fun applyContextCompression(
        taskId: Long,
        nodeId: Long,
        autoMode: Boolean,
        modelId: Long,
        turns: MutableList<ChatTurn>,
        contextWindow: Int,
        stepIndex: Int,
        actualPromptTokens: Int
    ): AgentExecutionResult.WaitingConfirmation? {
        val compressor = contextCompressor ?: return null
        if (!compressor.shouldCompress(turns, contextWindow, actualPromptTokens)) return null

        val completed = runCatching {
            toolExecutions.executionsOfTask(taskId).count {
                it.toolName == CONTEXT_COMPRESS_TOOL && it.status == AgentToolStatus.SUCCEEDED
            }
        }.getOrDefault(0)
        if (completed >= MAX_COMPRESSIONS_PER_TASK) {
            throw ExecutionFailure(
                AgentFailureKind.BACKGROUND_LIMIT,
                "上下文压缩后仍接近上限（已压缩 $completed 次），请新开对话继续"
            )
        }

        if (autoMode) {
            val answers = runCatching { tasks.answeredClarifications(taskId) }
                .getOrDefault(emptyList())
                .filter { it.question.trim() == AgentContextCompressor.QUESTION_TEXT }
            if (answers.size <= completed) {
                // 本轮上下文压力还没有征得用户同意
                tasks.createClarification(
                    taskId = taskId,
                    question = AgentContextCompressor.QUESTION_TEXT,
                    options = listOf("压缩后继续", "不压缩，新开对话")
                )
                tasks.updateStatus(taskId, AgentTaskStatus.WAITING_CONFIRMATION)
                tasks.updateExecutionCursor(taskId, stepIndex, nodeId)
                runCatching {
                    nodes.updateStatus(
                        nodeId,
                        AgentNodeStatus.WAITING,
                        errorMessage = "等待上下文压缩确认"
                    )
                }
                return AgentExecutionResult.WaitingConfirmation(taskId, 0)
            }
            val latest = answers.last()
            if (!AgentContextCompressor.isAffirmative(latest.answer)) {
                throw ExecutionFailure(
                    AgentFailureKind.BACKGROUND_LIMIT,
                    "上下文已达到上限，且用户选择不压缩；请新开对话继续"
                )
            }
        }

        val beforeTokens = ContextEstimator.estimateTokens(turns)
        val executionId = runCatching {
            toolExecutions.createExecution(
                taskId = taskId,
                toolName = CONTEXT_COMPRESS_TOOL,
                toolDisplayName = CONTEXT_COMPRESS_TOOL_LABEL,
                nodeId = nodeId,
                argumentsJson = jsonPayload { put("beforeTokens", beforeTokens) }
            )
        }.getOrElse {
            throw ExecutionFailure(
                AgentFailureKind.MODEL_PARSE,
                "创建上下文压缩记录失败：${it.message ?: it::class.simpleName}"
            )
        }
        toolExecutions.start(executionId)
        val startedAt = System.currentTimeMillis()
        return when (val result = compressor.compress(
            modelId = modelId,
            turns = turns,
            configuredWindow = contextWindow,
            preserveFirstUser = !autoMode
        )) {
            is AgentContextCompressor.Result.Success -> {
                val rebuilt = compressor.rebuildTurns(
                    turns = turns,
                    summary = result.summary,
                    configuredWindow = contextWindow,
                    preserveFirstUser = !autoMode
                )
                turns.clear()
                turns.addAll(rebuilt)
                toolExecutions.succeed(
                    executionId = executionId,
                    resultJson = jsonPayload {
                        put("beforeTokens", result.beforeTokens)
                        put("afterTokens", result.afterTokens)
                        put("summaryChars", result.summary.length)
                    },
                    durationMs = System.currentTimeMillis() - startedAt
                )
                null
            }

            is AgentContextCompressor.Result.Failure -> {
                toolExecutions.fail(
                    executionId = executionId,
                    errorMessage = result.message,
                    durationMs = System.currentTimeMillis() - startedAt
                )
                throw ExecutionFailure(
                    AgentFailureKind.MODEL_PARSE,
                    "上下文自动压缩失败：${result.message}"
                )
            }
        }
    }

    private suspend fun requestModelWithRetry(
        taskId: Long,
        nodeId: Long,
        runId: String,
        round: Int,
        modelId: Long,
        modelName: String,
        turns: List<ChatTurn>,
        toolSpecs: List<com.lianwenqi.app.ai.AiToolSpec>,
        options: AgentExecutionOptions
    ): AgentModelCallResult {
        var attempt = 1
        while (true) {
            checkControl(taskId, options)
            val request = modelRequests?.begin(
                taskId = taskId,
                nodeId = nodeId,
                runId = runId,
                round = round,
                modelId = modelId,
                modelName = modelName
            )
            val started = System.nanoTime()
            val response = modelClient.completeWithTools(modelId, turns, toolSpecs)
            val latency = (System.nanoTime() - started) / 1_000_000
            if (response.success) {
                request?.let {
                    modelRequests.succeed(
                        id = it.id,
                        latencyMs = latency,
                        responseChars = response.content.length,
                        toolCallCount = response.toolCalls.size
                    )
                }
                return AgentModelCallResult(response, request?.id)
            }
            val kind = when {
                response.toolsUnsupported -> AgentFailureKind.MODEL_UNSUPPORTED
                response.error != null -> AgentFailureClassifier.fromAiError(response.error)
                else -> AgentFailureClassifier.fromMessage(response.errorMessage)
            }
            val message = response.errorMessage.ifBlank { AgentFailureKind.label(kind) }
            request?.let {
                modelRequests.fail(
                    id = it.id,
                    failureKind = kind,
                    errorMessage = message,
                    latencyMs = latency
                )
            }
            val decision = AgentRetryPolicy.modelDecision(
                kind = kind,
                attempt = request?.attempt ?: attempt
            )
            if (!decision.retryable) {
                throw ExecutionFailure(kind, message)
            }
            delay(decision.delayMs)
            attempt++
        }
    }

    /**
     * 记录一个步骤最终提交给模型的完整输入（system / user / 工具往返）。
     *
     * 仅在任务完成后随最终回答落库，供用户查看；不进入后续模型上下文。
     */
    private fun recordStepPromptLog(
        builder: StringBuilder,
        step: AgentPlanStep,
        stepIndex: Int,
        stepCount: Int,
        turns: List<ChatTurn>
    ) {
        if (builder.length >= MAX_EXECUTION_LOG_CHARS) return
        val section = buildString {
            if (builder.isNotEmpty()) appendLine()
            appendLine(
                "========== 步骤 ${stepIndex + 1}/$stepCount：${step.title} =========="
            )
            turns.forEach { turn ->
                val role = when (turn.role) {
                    ChatRole.SYSTEM -> "系统"
                    ChatRole.USER -> "用户"
                    ChatRole.ASSISTANT -> "助手"
                    else -> turn.role.name
                }
                appendLine("【$role】")
                appendLine(turn.content)
                if (turn.images.isNotEmpty()) {
                    appendLine("（附带图片 ${turn.images.size} 张）")
                }
                appendLine()
            }
        }
        val remaining = MAX_EXECUTION_LOG_CHARS - builder.length
        if (section.length <= remaining) {
            builder.append(section)
        } else {
            builder.append(section.take(remaining))
            builder.append("\n（日志过长，后续内容已截断）")
        }
    }

    /**
     * 恢复执行时给模型的只读状态块。
     * 只包含操作 id / 类型 / 状态，不包含正文，不授予任何新权限。
     */
    private suspend fun buildRecoveryContext(taskId: Long, nodeId: Long?): String {
        val operations = runCatching { writeOperations?.ofTask(taskId).orEmpty() }
            .getOrDefault(emptyList())
        val nodeOperations = operations.filter { nodeId == null || it.nodeId == nodeId }
        if (nodeOperations.isEmpty()) {
            return "【恢复执行】本次从持久化游标继续；已完成步骤不会重跑。"
        }
        // 用户已在弹窗中处理的写入：把确认结果原样返回给模型，让它据此回复，
        // 而不是继续把操作当成“待确认”再让用户确认一次。
        val decided = nodeOperations.filter { operation ->
            operation.executionStatus == AgentWriteOperationStatus.SUCCEEDED ||
                operation.executionStatus == AgentWriteOperationStatus.REJECTED ||
                operation.executionStatus == AgentWriteOperationStatus.FAILED ||
                operation.executionStatus == AgentWriteOperationStatus.CONFLICT ||
                operation.executionStatus == AgentWriteOperationStatus.ROLLED_BACK ||
                operation.executionStatus == AgentWriteOperationStatus.CANCELLED
        }
        return buildString {
            appendLine("【恢复执行】本次从持久化游标继续；已完成步骤不会重跑。")
            if (decided.isNotEmpty()) {
                appendLine("【写入确认结果】用户在弹窗中已经处理以下写入操作：")
                decided.takeLast(12).forEach { operation ->
                    appendLine(
                        "· ${writeDecisionLine(operation)}" +
                            "（${AgentWriteOperationType.label(operation.operationType)}）"
                    )
                }
                appendLine(
                    "请直接根据以上确认结果给用户最终回复：已成功的不再重复提议，" +
                        "已拒绝或失败的说明情况；不要再让用户确认同一操作。"
                )
            } else {
                appendLine("已成功写入的操作不会重复执行；待确认操作仍需用户确认。")
            }
            appendLine("本任务写入操作状态：")
            nodeOperations.take(12).forEach { operation ->
                appendLine(
                    "· #${operation.id} ${AgentWriteOperationType.label(operation.operationType)}" +
                        "：${AgentWriteOperationStatus.label(operation.executionStatus)}"
                )
            }
            append("不得因为恢复而重复提议已成功的写入；状态不确定时先用只读工具核对。")
        }
    }

    private suspend fun assembleReferenceContext(
        taskId: Long,
        sessionId: Long,
        unlimited: Boolean = false
    ): AgentContextResult {
        val assembler = contextAssembler ?: return AgentContextResult.empty(taskId)
        return try {
            assembler.assemble(taskId, sessionId, unlimited = unlimited)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw ExecutionFailure(
                AgentFailureKind.VALIDATION,
                "上下文组装失败：${e.message ?: "未知错误"}"
            )
        }
    }

    /**
     * 把「截断 / 失效 / 预算不足」等引用状态写入会话提示。
     * 只做用户可见的错误说明，不替换来源、不静默删除。
     */
    private suspend fun notifyReferenceStatus(
        sessionId: Long,
        taskId: Long,
        result: AgentContextResult
    ) {
        if (result.items.isEmpty()) return
        val problems = result.items.filter {
            it.status != AgentContextItemStatus.INJECTED &&
                it.status != AgentContextItemStatus.SKIPPED_REFERENCE_ONLY
        }
        if (problems.isEmpty()) return
        val text = buildString {
            appendLine("参考资料状态（${result.summaryLine()}）")
            result.items.filter { it.status == AgentContextItemStatus.TRUNCATED }
                .forEach { appendLine("· 截断：${it.title}（${it.contentChars}/${it.contentTotalChars} 字）") }
            result.items.filter { it.status == AgentContextItemStatus.INVALID }
                .forEach { appendLine("· 失效：${it.title}（${it.statusMessage}）") }
            result.items.filter {
                it.status == AgentContextItemStatus.SKIPPED_BUDGET ||
                    it.status == AgentContextItemStatus.EMPTY
            }.forEach { appendLine("· 未注入：${it.title}（${it.statusMessage}）") }
            appendLine("可在参考资料中减少资料或缩小引用范围后重新执行。")
        }.trimEnd().take(1_000)
        runCatching {
            sessions.addMessage(
                sessionId = sessionId,
                taskId = taskId,
                role = AgentMessageRole.ASSISTANT,
                kind = AgentMessageKind.NOTICE,
                content = text
            )
        }
    }

    /**
     * 暂停当前执行，等待用户逐项确认写入。
     *
     * 仅用于旧式进程内执行 / 测试；后台 Worker 使用
     * [AgentExecutionOptions.waitForWriteConfirmation] = false，落库后立即停止。
     */
    private suspend fun awaitWriteConfirmations(
        sessionId: Long,
        taskId: Long,
        pendingOperationIds: List<Long>,
        turns: MutableList<ChatTurn>,
        toolContext: StringBuilder
    ) {
        val gate = writeGate ?: throw ExecutionFailure(
            AgentFailureKind.VALIDATION,
            "写入确认入口未配置"
        )
        val service = writeService ?: throw ExecutionFailure(
            AgentFailureKind.VALIDATION,
            "写入执行服务未配置"
        )
        val operations = writeOperations ?: throw ExecutionFailure(
            AgentFailureKind.VALIDATION,
            "写入操作仓库未配置"
        )
        pendingOperationIds.forEach { operationId ->
            val approved = gate.awaitDecision(operationId)
            if (approved) {
                service.executeApproved(operationId)
            } else {
                service.reject(operationId)
            }
            val operation = operations.get(operationId)
            val resultBlock = writeResultBlock(operation)
            if (toolContext.isNotEmpty()) toolContext.append('\n')
            toolContext.append(resultBlock)
            turns += ChatTurn(ChatRole.USER, resultBlock)
            if (tasks.isCancelRequested(taskId)) {
                throw AgentExecutionCancelledException()
            }
        }
        val current = tasks.getTask(taskId)
        if (current == null || current.status == AgentTaskStatus.CANCELLED) {
            throw AgentExecutionCancelledException()
        }
        tasks.updateStatus(taskId, AgentTaskStatus.RUNNING)
    }

    private fun writeResultBlock(operation: AgentWriteOperation?): String = buildString {
        if (operation == null) {
            appendLine("【写入操作结果】")
            append("写入操作记录不存在；未执行任何写入。")
            return@buildString
        }
        appendLine("【写入操作结果 #${operation.id}】")
        appendLine("类型：${AgentWriteOperationType.label(operation.operationType)}")
        appendLine("目标：${operation.bookTitleSnapshot} / ${operation.targetTitleSnapshot}")
        appendLine("状态：${AgentWriteOperationStatus.label(operation.executionStatus)}")
        when (operation.executionStatus) {
            AgentWriteOperationStatus.SUCCEEDED ->
                appendLine("说明：用户已确认，业务数据已写入。")

            AgentWriteOperationStatus.REJECTED ->
                appendLine("说明：用户已拒绝；没有修改任何业务数据。")

            AgentWriteOperationStatus.CONFLICT ->
                appendLine("说明：目标数据在预览后发生变化，本次没有写入；需要重新预览并确认。")

            AgentWriteOperationStatus.FAILED ->
                appendLine("说明：写入失败：" + operation.errorMessage.ifBlank { "未知错误" })

            AgentWriteOperationStatus.CANCELLED ->
                appendLine("说明：操作已取消，没有写入。")

            else ->
                appendLine("说明：操作当前状态为 ${operation.executionStatus}。")
        }
        if (operation.executionStatus == AgentWriteOperationStatus.SUCCEEDED) {
            appendLine(if (operation.reversible) "该操作支持撤销。" else "该操作不支持撤销。")
        }
    }.trimEnd()

    /** Kahn 拓扑排序；同层保持计划顺序 */
    private fun topologicalOrder(steps: List<AgentPlanStep>): List<AgentPlanStep>? {
        val order = steps.withIndex().associate { it.value.stepId to it.index }
        val indegree = mutableMapOf<String, Int>()
        val next = mutableMapOf<String, MutableList<String>>()
        steps.forEach { step ->
            indegree[step.stepId] = step.dependencies.count { it in order }
            step.dependencies.filter { it in order }.forEach { dep ->
                next.getOrPut(dep) { mutableListOf() }.add(step.stepId)
            }
        }
        val available = steps.filter { indegree[it.stepId] == 0 }
            .sortedBy { order[it.stepId] }
            .toMutableList()
        val result = mutableListOf<AgentPlanStep>()
        while (available.isNotEmpty()) {
            val step = available.removeAt(0)
            result += step
            next[step.stepId]?.forEach { depId ->
                val left = (indegree[depId] ?: 0) - 1
                indegree[depId] = left
                if (left == 0) {
                    steps.firstOrNull { it.stepId == depId }?.let {
                        available += it
                        available.sortBy { s -> order[s.stepId] }
                    }
                }
            }
        }
        return result.takeIf { it.size == steps.size }
    }

    /**
     * Agent 会话是连续的：把本会话此前轮次的问答注入当前任务上下文。
     * 与「前缀词管理」设置无关，只要属于同一 Agent 会话就会进入上下文。
     */
    private suspend fun buildConversationHistory(
        taskId: Long,
        sessionId: Long,
        truncate: Boolean = true
    ): String {
        if (sessionId <= 0L) return ""
        val messages = runCatching { sessions.getMessages(sessionId) }.getOrDefault(emptyList())
        val lines = messages.asSequence()
            .filter { it.taskId != taskId }
            .filter {
                it.kind == AgentMessageKind.USER_INPUT ||
                    it.kind == AgentMessageKind.FINAL_ANSWER
            }
            .mapNotNull { message ->
                val text = message.content.trim()
                if (text.isBlank()) {
                    null
                } else {
                    val who = if (message.role == AgentMessageRole.USER) "用户" else "Agent"
                    "$who：$text"
                }
            }
            .toList()
        if (lines.isEmpty()) return ""
        val joined = lines.joinToString("\n")
        // 仅计划模式按字符截断；自动模式保留全部历史，交给上下文压缩在超限时处理
        return if (!truncate || joined.length <= MAX_HISTORY_CHARS) {
            joined
        } else {
            "（更早的对话已省略）\n" + joined.takeLast(MAX_HISTORY_CHARS)
        }
    }

    private fun buildFinalSummary(
        plan: AgentPlan,
        results: List<Pair<AgentPlanStep, String>>,
        autoMode: Boolean = false,
        maxChars: Int = Int.MAX_VALUE
    ): String {
        // 自动模式直接使用回答正文，不拼接计划 / 步骤等内部信息
        if (autoMode) {
            val answer = results.joinToString("\n\n") { it.second }.trim()
            if (answer.isNotBlank()) return truncateForBudget(answer, maxChars)
        }
        val text = buildString {
            appendLine("【只读执行结果】")
            appendLine("任务：${plan.taskTitle}")
            val skipped = results.count {
                it.second == AgentExecutionResumePlanner.SKIPPED_STEP_RESULT
            }
            val completed = results.size - skipped
            appendLine(
                "步骤完成：$completed/${plan.steps.size}" +
                    if (skipped > 0) "（用户跳过 $skipped 步）" else ""
            )
            results.forEachIndexed { index, (step, text) ->
                appendLine()
                appendLine("${index + 1}. ${step.title}")
                appendLine(text)
            }
            appendLine()
            append("以上结果来自真实工具与模型分析；写入操作均经过用户确认。")
        }
        return truncateForBudget(text, maxChars)
    }

    private class ExecutionFailure(
        val kind: String,
        message: String
    ) : Exception(message)
}

/**
 * 自动模式兜底判断：正文是否是在向用户提问 / 等待用户做决定。
 *
 * 只用于把「本该用 ask_user 却写进正文」的问题转成提问弹窗；
 * 限制长度与关键词，避免普通回答中的反问被误判。
 */
private fun looksLikeUserQuestion(text: String): Boolean {
    val content = text.trim()
    if (content.isEmpty() || content.length > 1_500) return false
    if (!content.contains('？') && !content.contains('?')) return false
    val cues = listOf(
        "要不要", "是否", "还是您", "还是你", "选哪", "哪个",
        "确认", "您回", "你回", "回复我", "告诉我", "可以吗",
        "行吗", "想跟您确认", "想和你确认", "麻烦您"
    )
    return cues.any { content.contains(it) }
}

/**
 * 判断正文是否在声称已经完成写入（新建 / 修改 / 删除等）。
 *
 * 用于自动模式兜底：没有真实写入记录时，这类表述属于幻觉，需要纠正模型去调用写入工具。
 */
private fun looksLikeWriteClaim(text: String): Boolean {
    val cues = listOf(
        "已新建", "已创建", "已添加", "已修改", "已更新", "已删除",
        "已提交", "已写入", "已保存", "已经新建", "已经创建", "已经修改",
        "都办好了", "都改好了", "已经拆", "已拆成", "我现在就提交", "马上提交"
    )
    return cues.any { text.contains(it) }
}

/**
 * 把模型的最大输出 token 折算为整条最终消息的字符预算。
 *
 * 中文场景下 1 token 约 1.5-3 字，这里按 3 字/token 取上限，避免过早截断；
 * maxTokens <= 0（不限输出）时视为不限制。
 */
private fun outputBudgetChars(maxTokens: Int): Int {
    if (maxTokens <= 0) return Int.MAX_VALUE
    return (maxTokens.toLong() * 3L)
        .coerceAtMost(Int.MAX_VALUE.toLong() - 1)
        .toInt()
}

/** 超出预算时明确标注截断，避免悄悄丢内容 */
private fun truncateForBudget(text: String, maxChars: Int): String {
    if (maxChars == Int.MAX_VALUE || maxChars <= 0 || text.length <= maxChars) return text
    return text.take(maxChars) + "\n\n（内容过长，已按模型最大输出预算截断）"
}

/**
 * 把一项已处理的写入操作转成模型可读的确认结果。
 *
 * 弹窗确认后需要把结果回传给模型（与 ask_user 一致），
 * 避免模型以为操作仍在等待确认而重复询问。
 */
private fun writeDecisionLine(operation: AgentWriteOperation): String {
    val target = operation.operationSummary
        .ifBlank { AgentWriteOperationType.label(operation.operationType) }
        .take(120)
    val decision = when (operation.executionStatus) {
        AgentWriteOperationStatus.SUCCEEDED ->
            if (operation.permissionStatus == AgentWritePermissionStatus.AUTO_APPROVED) {
                "已自动执行成功"
            } else {
                "用户已批准，执行成功"
            }

        AgentWriteOperationStatus.REJECTED -> "用户已拒绝，未执行"
        AgentWriteOperationStatus.FAILED ->
            "执行失败：" + operation.errorMessage.ifBlank { "未知原因" }

        AgentWriteOperationStatus.CONFLICT ->
            "执行冲突：" + operation.errorMessage.ifBlank { "目标数据已变化，需重新预览" }

        AgentWriteOperationStatus.ROLLED_BACK -> "用户已撤销，已恢复原内容"
        AgentWriteOperationStatus.CANCELLED -> "已取消"
        else -> AgentWriteOperationStatus.label(operation.executionStatus)
    }
    return "$target → $decision"
}
