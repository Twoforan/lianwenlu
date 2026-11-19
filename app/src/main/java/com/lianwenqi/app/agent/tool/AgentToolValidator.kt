package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.agent.data.agentJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface AgentToolParseResult {
    data class Success(val args: AgentToolArgs) : AgentToolParseResult
    data class Failure(val message: String) : AgentToolParseResult
}

/**
 * 工具参数本地校验。
 *
 * 按工具声明的参数表做严格校验：拒绝未知参数、必填缺失、类型错误、长度 / 数量超限。
 * 不使用宽松转换，避免非法参数进入业务层。
 */
class AgentToolValidator {

    fun parse(tool: AgentTool, rawArguments: String): AgentToolParseResult {
        val limit = tool.maxArgumentsChars
        if (rawArguments.length > limit) {
            return AgentToolParseResult.Failure(
                "工具参数过大（${rawArguments.length} 字符，上限 $limit）"
            )
        }
        val obj = runCatching {
            agentJson.parseToJsonElement(rawArguments.ifBlank { "{}" }) as? JsonObject
        }.getOrNull() ?: return AgentToolParseResult.Failure("工具参数必须是 JSON 对象")

        val declared = tool.parameters.associateBy { it.name }
        val unknown = obj.keys - declared.keys
        if (unknown.isNotEmpty()) {
            return AgentToolParseResult.Failure("存在未知参数：${unknown.joinToString("、")}")
        }
        tool.parameters.forEach { param ->
            val element = obj[param.name]
            if (element == null) {
                if (param.required) {
                    return AgentToolParseResult.Failure("缺少必填参数：${param.name}")
                }
                return@forEach
            }
            if (param.type == AgentToolParamType.LONG_ARRAY) {
                val array = element as? kotlinx.serialization.json.JsonArray
                    ?: return AgentToolParseResult.Failure("参数 ${param.name} 必须是数组")
                if (param.maxItems != null && array.size > param.maxItems) {
                    return AgentToolParseResult.Failure(
                        "参数 ${param.name} 最多 ${param.maxItems} 项"
                    )
                }
                if (array.any { it.jsonPrimitive.longOrNull == null }) {
                    return AgentToolParseResult.Failure(
                        "参数 ${param.name} 必须全部是整数"
                    )
                }
                return@forEach
            }
            val primitive = element as? JsonPrimitive
                ?: return AgentToolParseResult.Failure("参数 ${param.name} 类型错误")
            when (param.type) {
                AgentToolParamType.STRING -> {
                    if (!primitive.isString) {
                        return AgentToolParseResult.Failure("参数 ${param.name} 必须是字符串")
                    }
                    val length = primitive.content.length
                    if (param.minLength != null && length < param.minLength) {
                        return AgentToolParseResult.Failure(
                            "参数 ${param.name} 长度不能小于 ${param.minLength}"
                        )
                    }
                    if (param.maxLength != null && length > param.maxLength) {
                        return AgentToolParseResult.Failure(
                            "参数 ${param.name} 长度不能超过 ${param.maxLength}"
                        )
                    }
                }
                AgentToolParamType.LONG, AgentToolParamType.INT -> {
                    val value = primitive.longOrNull
                        ?: return AgentToolParseResult.Failure("参数 ${param.name} 必须是整数")
                    if (param.type == AgentToolParamType.INT && primitive.intOrNull == null) {
                        return AgentToolParseResult.Failure("参数 ${param.name} 超出整数范围")
                    }
                    if (param.minValue != null && value < param.minValue) {
                        return AgentToolParseResult.Failure(
                            "参数 ${param.name} 不能小于 ${param.minValue}"
                        )
                    }
                    if (param.maxValue != null && value > param.maxValue) {
                        return AgentToolParseResult.Failure(
                            "参数 ${param.name} 不能大于 ${param.maxValue}"
                        )
                    }
                }
                AgentToolParamType.BOOLEAN -> {
                    if (primitive.booleanOrNull == null) {
                        return AgentToolParseResult.Failure("参数 ${param.name} 必须是布尔值")
                    }
                }
                AgentToolParamType.LONG_ARRAY -> Unit
            }
        }
        return AgentToolParseResult.Success(AgentToolArgs(obj))
    }
}
