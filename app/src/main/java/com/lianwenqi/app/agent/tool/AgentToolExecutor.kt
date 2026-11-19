package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.agent.data.AgentTaskRepository
import com.lianwenqi.app.agent.data.AgentToolExecutionRepository
import com.lianwenqi.app.agent.data.AgentWriteOperationRepository
import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.data.entity.AgentToolExecution
import com.lianwenqi.app.agent.data.entity.AgentToolStatus
import com.lianwenqi.app.agent.data.jsonPayload
import com.lianwenqi.app.agent.model.AgentToolCallInfo
import com.lianwenqi.app.ai.PromptDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/** 一次工具执行的结果（用于引擎与模型上下文） */
data class AgentToolExecutionOutcome(
    val executionId: Long,
    val toolName: String,
    val status: String,
    val resultJson: String,
    val errorMessage: String,
    val reused: Boolean = false,
    val truncated: Boolean = false,
    /** F6：本次调用产生的写入操作 id */
    val writeOperationId: Long? = null,
    /** F6：写入操作是否仍在等待用户确认 */
    val writeRequiresConfirmation: Boolean = false
) {
    val success: Boolean get() = status == AgentToolStatus.SUCCEEDED

    fun toModelBlock(index: Int): String = buildString {
        appendLine("【工具执行结果 #$index】")
        appendLine("工具：$toolName")
        appendLine("状态：$status")
        if (writeOperationId != null) appendLine("写入操作：#$writeOperationId")
        if (writeRequiresConfirmation) {
            appendLine("写入状态：等待用户确认；尚未修改任何业务数据")
        }
        if (reused) appendLine("说明：复用已保存的成功结果，未重复执行")
        if (truncated) appendLine("说明：结果过大，以下内容已截断")
        if (errorMessage.isNotBlank()) appendLine("错误：$errorMessage")
        if (resultJson.isNotBlank() && resultJson != "{}") {
            appendLine("结果：")
            append(resultJson)
        }
    }.trimEnd()
}

/**
 * 工具执行器：幂等检查、参数校验、真实执行、状态与事件持久化。
 *
 * F4 在单个执行引擎内串行调用工具，且同一任务通过 Repository 原子占位；
 * 因此「查询幂等键 → 创建记录 → 执行」之间不存在同任务并发窗口。
 */
class AgentToolExecutor(
    private val registry: AgentToolRegistry,
    private val validator: AgentToolValidator,
    private val tools: AgentToolExecutionRepository,
    private val tasks: AgentTaskRepository,
    private val maxResultChars: Int = 32 * 1024,
    /** F6：用于回填写入操作的 toolExecutionId；只读执行可为空 */
    private val writeOperations: AgentWriteOperationRepository? = null
) {

    suspend fun execute(
        context: AgentExecutionContext,
        call: AgentToolCallInfo,
        nodeId: Long?
    ): AgentToolExecutionOutcome {
        if (call.name.isBlank()) {
            return AgentToolExecutionOutcome(
                executionId = 0,
                toolName = "",
                status = AgentToolStatus.FAILED,
                resultJson = "{}",
                errorMessage = "模型返回了空白工具名，拒绝执行"
            )
        }
        if (tasks.getTask(context.taskId) == null) {
            return AgentToolExecutionOutcome(
                executionId = 0,
                toolName = call.name,
                status = AgentToolStatus.FAILED,
                resultJson = "{}",
                errorMessage = "任务不存在，拒绝执行工具"
            )
        }
        val idempotencyKey = idempotencyKey(context, call)
        val tool = registry.findByName(call.name)
        if (tool == null) {
            val executionId = createAndStart(
                context = context,
                call = call,
                nodeId = nodeId,
                idempotencyKey = idempotencyKey,
                displayName = "未注册工具"
            )
            val message = "未注册工具：${call.name}；只能使用已注册的只读或受控写入工具"
            tools.fail(executionId, message)
            return AgentToolExecutionOutcome(
                executionId = executionId,
                toolName = call.name,
                status = AgentToolStatus.FAILED,
                resultJson = "{}",
                errorMessage = message
            )
        }
        if (tool.permissionLevel !in EXECUTABLE_PERMISSIONS) {
            val executionId = createAndStart(
                context, call, nodeId, idempotencyKey, tool.description.take(100)
            )
            val message = "拒绝执行该权限级别的工具：${call.name}（${tool.permissionLevel}）"
            tools.fail(executionId, message)
            return AgentToolExecutionOutcome(
                executionId = executionId,
                toolName = call.name,
                status = AgentToolStatus.FAILED,
                resultJson = "{}",
                errorMessage = message
            )
        }

        // 只读与生成工具按调用级幂等复用结果，避免恢复时重复消耗模型；
        // 写入工具交给 agent_write_operation 的稳定幂等键。
        if (tool.permissionLevel == AgentToolPermission.READ_ONLY ||
            tool.permissionLevel == AgentToolPermission.GENERATE
        ) {
            val existing = tools.findByIdempotencyKey(idempotencyKey)
            if (existing != null) {
                return when (existing.status) {
                    AgentToolStatus.SUCCEEDED -> existing.toOutcome(reused = true)
                    AgentToolStatus.PENDING, AgentToolStatus.RUNNING -> AgentToolExecutionOutcome(
                        executionId = existing.id,
                        toolName = existing.toolName,
                        status = existing.status,
                        resultJson = existing.resultJson,
                        errorMessage = "同一工具调用正在执行，拒绝重复执行"
                    )
                    else -> AgentToolExecutionOutcome(
                        executionId = existing.id,
                        toolName = existing.toolName,
                        status = existing.status,
                        resultJson = existing.resultJson,
                        errorMessage = "该工具调用已失败或已取消，不自动重试；请使用新的调用 ID"
                    )
                }
            }
        }

        val executionId = createAndStart(
            context = context,
            call = call,
            nodeId = nodeId,
            idempotencyKey = idempotencyKey,
            displayName = tool.description.take(100)
        )

        val parsed = validator.parse(tool, call.arguments)
        if (parsed is AgentToolParseResult.Failure) {
            tools.fail(executionId, parsed.message)
            return AgentToolExecutionOutcome(
                executionId = executionId,
                toolName = call.name,
                status = AgentToolStatus.FAILED,
                resultJson = "{}",
                errorMessage = parsed.message
            )
        }

        return try {
            val result = tool.execute((parsed as AgentToolParseResult.Success).args, context)
            result.writeOperationId?.let { operationId ->
                runCatching { writeOperations?.attachToolExecution(operationId, executionId) }
            }
            if (!result.success) {
                tools.fail(executionId, result.errorMessage.ifBlank { "工具执行失败" })
                AgentToolExecutionOutcome(
                    executionId = executionId,
                    toolName = call.name,
                    status = AgentToolStatus.FAILED,
                    resultJson = "{}",
                    errorMessage = result.errorMessage.ifBlank { "工具执行失败" },
                    writeOperationId = result.writeOperationId,
                    writeRequiresConfirmation = result.writeRequiresConfirmation
                )
            } else {
                val capped = capResult(result.contentJson)
                tools.succeed(executionId, capped.json)
                AgentToolExecutionOutcome(
                    executionId = executionId,
                    toolName = call.name,
                    status = AgentToolStatus.SUCCEEDED,
                    resultJson = capped.json,
                    errorMessage = "",
                    truncated = result.truncated || capped.truncated,
                    writeOperationId = result.writeOperationId,
                    writeRequiresConfirmation = result.writeRequiresConfirmation
                )
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                runCatching { tools.cancel(executionId, "执行已取消") }
            }
            throw e
        } catch (e: Throwable) {
            val message = e.message ?: "工具执行异常"
            runCatching { tools.fail(executionId, message) }
            AgentToolExecutionOutcome(
                executionId = executionId,
                toolName = call.name,
                status = AgentToolStatus.FAILED,
                resultJson = "{}",
                errorMessage = message
            )
        }
    }

    private suspend fun createAndStart(
        context: AgentExecutionContext,
        call: AgentToolCallInfo,
        nodeId: Long?,
        idempotencyKey: String,
        displayName: String
    ): Long {
        val executionId = tools.createExecution(
            taskId = context.taskId,
            toolName = call.name,
            toolDisplayName = displayName,
            nodeId = nodeId,
            argumentsJson = safeArgumentsJson(call.arguments),
            idempotencyKey = idempotencyKey
        )
        tools.start(executionId)
        return executionId
    }

    /** 即使模型参数不是合法 JSON 或过大，也要能把这次调用记录为失败，而不是让执行器崩溃 */
    private fun safeArgumentsJson(raw: String): String {
        val obj = runCatching {
            agentJson.parseToJsonElement(raw.ifBlank { "{}" }) as? JsonObject
        }.getOrNull()
        val normalized = obj?.toString()
        return if (normalized != null && normalized.length <= MAX_STORED_ARGUMENTS_CHARS) {
            normalized
        } else {
            jsonPayload {
                put("stored", false)
                put("note", "参数非法或超过存储上限")
                put("preview", raw.take(1_000))
            }
        }
    }

    private fun idempotencyKey(
        context: AgentExecutionContext,
        call: AgentToolCallInfo
    ): String {
        val callId = call.id.ifBlank {
            "auto_" + PromptDiagnostics.hash(call.name + "\u0000" + call.arguments)
        }
        return "task:${context.taskId}:$callId"
    }

    private fun AgentToolExecution.toOutcome(reused: Boolean) = AgentToolExecutionOutcome(
        executionId = id,
        toolName = toolName,
        status = status,
        resultJson = resultJson,
        errorMessage = errorMessage,
        reused = reused
    )

    private data class CappedResult(val json: String, val truncated: Boolean)

    companion object {
        private const val MAX_STORED_ARGUMENTS_CHARS = 16 * 1024

        /** F6：可执行的权限级别；删除 / 批量写入不在其中 */
        private val EXECUTABLE_PERMISSIONS = setOf(
            AgentToolPermission.READ_ONLY,
            AgentToolPermission.GENERATE,
            AgentToolPermission.CREATE,
            AgentToolPermission.UPDATE,
            AgentToolPermission.OVERWRITE
        )
    }

    /** 结果超过上限时仍返回合法 JSON，明确标记截断，不伪装完整 */
    private fun capResult(json: String): CappedResult {
        val valid = runCatching {
            agentJson.parseToJsonElement(json) as? JsonObject
        }.getOrNull() ?: return CappedResult(
            jsonPayload {
                put("truncated", true)
                put("note", "工具返回非法 JSON，已包裹为错误结果")
                put("preview", json.take(1_000))
            },
            truncated = true
        )
        if (json.length <= maxResultChars) return CappedResult(valid.toString(), false)
        return CappedResult(
            jsonPayload {
                put("truncated", true)
                put("note", "工具结果超过 $maxResultChars 字符，仅返回预览")
                put("preview", json.take(maxResultChars - 200))
            },
            truncated = true
        )
    }
}
