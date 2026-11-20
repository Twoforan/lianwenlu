package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.ai.AiToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 工具权限；生成工具只产出草稿，写入工具必须经过确认 */
enum class AgentToolPermission {
    READ_ONLY,
    GENERATE,
    CREATE,
    UPDATE,
    DELETE,
    OVERWRITE,
    BATCH_WRITE
}

enum class AgentToolParamType(val jsonType: String) {
    STRING("string"),
    LONG("integer"),
    INT("integer"),
    BOOLEAN("boolean"),
    LONG_ARRAY("array")
}

/** 工具参数声明；Validator 按此做本地校验，同时生成模型可见的 JSON Schema */
data class AgentToolParam(
    val name: String,
    val type: AgentToolParamType,
    val description: String,
    val required: Boolean = false,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val maxItems: Int? = null
)

/** 已通过本地校验的工具参数 */
class AgentToolArgs(val raw: JsonObject) {
    fun string(name: String, default: String = ""): String =
        raw[name]?.jsonPrimitive?.contentOrNull ?: default

    fun long(name: String, default: Long = 0L): Long =
        raw[name]?.jsonPrimitive?.longOrNull ?: default

    fun int(name: String, default: Int = 0): Int =
        long(name, default.toLong()).toInt()

    fun boolean(name: String, default: Boolean = false): Boolean =
        raw[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: default

    fun longList(name: String): List<Long> =
        raw[name]?.jsonArray?.mapNotNull { it.jsonPrimitive.longOrNull } ?: emptyList()
}

/** 工具执行上下文：bookId 由会话注入，模型参数不允许覆盖 */
data class AgentExecutionContext(
    val sessionId: Long,
    val taskId: Long,
    val runId: Long,
    val bookId: Long?,
    val bookTitle: String = "",
    /** F6：当前执行节点；写入操作用于按步骤核对 */
    val nodeId: Long? = null
)

/** 工具执行结果；contentJson 必须是可解析 JSON 对象 */
data class AgentToolResult(
    val success: Boolean,
    val contentJson: String = "{}",
    val summary: String = "",
    val errorMessage: String = "",
    val truncated: Boolean = false,
    /** F6：本次调用产生的写入操作 id（只读工具为 null） */
    val writeOperationId: Long? = null,
    /** F6：写入操作是否仍在等待用户确认 */
    val writeRequiresConfirmation: Boolean = false,
    /** 工具要求先向用户提问：执行器据此暂停任务，等待回答后继续 */
    val awaitingUserInput: Boolean = false
) {
    companion object {
        fun ok(
            contentJson: String,
            summary: String = "",
            truncated: Boolean = false,
            writeOperationId: Long? = null,
            writeRequiresConfirmation: Boolean = false,
            awaitingUserInput: Boolean = false
        ) = AgentToolResult(
            success = true,
            contentJson = contentJson,
            summary = summary,
            truncated = truncated,
            writeOperationId = writeOperationId,
            writeRequiresConfirmation = writeRequiresConfirmation,
            awaitingUserInput = awaitingUserInput
        )

        fun fail(
            message: String,
            writeOperationId: Long? = null,
            writeRequiresConfirmation: Boolean = false
        ) = AgentToolResult(
            success = false,
            errorMessage = message,
            summary = message,
            writeOperationId = writeOperationId,
            writeRequiresConfirmation = writeRequiresConfirmation
        )
    }
}

/**
 * 一个已注册的 Agent 工具。
 *
 * 工具定义只能由代码注册；模型只能按 name 调用，不能创建工具或改变权限。
 */
data class AgentTool(
    val toolKey: String,
    val name: String,
    val description: String,
    val capabilityKey: String,
    val permissionLevel: AgentToolPermission = AgentToolPermission.READ_ONLY,
    /** 单次参数 JSON 上限；写入正文等大内容工具需要单独放宽 */
    val maxArgumentsChars: Int = 16 * 1024,
    val parameters: List<AgentToolParam> = emptyList(),
    /** 是否在「Agent 工具」列表中展示给用户；内部交互工具设为 false */
    val visibleToUser: Boolean = true,
    val execute: suspend (AgentToolArgs, AgentExecutionContext) -> AgentToolResult
) {
    fun parametersSchemaJson(): String = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            parameters.forEach { param ->
                putJsonObject(param.name) {
                    put("type", param.type.jsonType)
                    put("description", param.description)
                    param.minLength?.let { put("minLength", it) }
                    param.maxLength?.let { put("maxLength", it) }
                    param.minValue?.let { put("minimum", it) }
                    param.maxValue?.let { put("maximum", it) }
                    if (param.type == AgentToolParamType.LONG_ARRAY) {
                        putJsonObject("items") { put("type", "integer") }
                        param.maxItems?.let { put("maxItems", it) }
                    }
                }
            }
        }
        val required = parameters.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
        put("additionalProperties", false)
    }.toString()

    fun toSpec(): AiToolSpec = AiToolSpec(
        name = name,
        description = description,
        parametersJson = parametersSchemaJson()
    )
}
