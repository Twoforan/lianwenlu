package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanParseResult

/**
 * 计划 JSON 解析器。
 *
 * 支持：
 * - 纯 JSON；
 * - Markdown 代码围栏中的 JSON；
 * - JSON 前后夹带说明文字。
 *
 * 只做「安全提取 + 严格反序列化」，不使用宽松正则掩盖结构错误。
 */
class AgentPlanParser(
    private val maxJsonChars: Int = MAX_PLAN_JSON_CHARS
) {

    fun parse(raw: String): AgentPlanParseResult {
        val jsonText = extractJson(raw)
            ?: return AgentPlanParseResult.Failure("模型未返回可解析的 JSON 计划")
        if (jsonText.length > maxJsonChars) {
            return AgentPlanParseResult.Failure("计划 JSON 过大（${jsonText.length} 字符，上限 $maxJsonChars）")
        }
        // 先做字段归一化：补齐 stepId / capabilityKey、映射常见别名、重写依赖 id
        val normalizedJson = AgentPlanNormalizer.normalize(jsonText)
        val plan = runCatching {
            agentJson.decodeFromString(AgentPlan.serializer(), normalizedJson)
        }.getOrNull() ?: return AgentPlanParseResult.Failure("计划 JSON 结构无法解析")
        val normalized = runCatching {
            agentJson.encodeToString(AgentPlan.serializer(), plan)
        }.getOrNull() ?: return AgentPlanParseResult.Failure("计划标准化失败")
        return AgentPlanParseResult.Success(plan, normalized)
    }

    /** 把结构化计划编码为持久化 JSON（与模型输出使用同一套字段） */
    fun encode(plan: AgentPlan): String =
        agentJson.encodeToString(AgentPlan.serializer(), plan)

    /** 从模型原始输出中提取第一个完整 JSON 对象；找不到返回 null */
    internal fun extractJson(raw: String): String? {
        val text = raw.trim()
        // 顶层是数组 / 标量：不是约定的计划对象，直接拒绝，避免从数组里抠出内部对象
        if (startsWithArray(text)) return null
        if (text.startsWith("{")) {
            balancedObject(text)?.let { return it }
        }
        // Markdown 代码围栏：逐个取出围栏内容，再在内容中提取完整对象
        var index = 0
        while (true) {
            val fenceStart = text.indexOf("```", index)
            if (fenceStart < 0) break
            val contentStart = text.indexOf('\n', fenceStart + 3)
            if (contentStart < 0) break
            val fenceEnd = text.indexOf("```", contentStart + 1)
            if (fenceEnd < 0) break
            val content = text.substring(contentStart + 1, fenceEnd).trim()
            if (!startsWithArray(content)) {
                balancedObject(content)?.let { return it }
            }
            index = fenceEnd + 3
        }
        // 兜底：正文中夹带 JSON
        return balancedObject(text)
    }

    private fun startsWithArray(text: String): Boolean {
        val index = text.indexOfFirst { it == '{' || it == '[' }
        return index >= 0 && text[index] == '['
    }

    /** 从第一个 `{` 开始读取一个括号平衡、字符串转义正确的 JSON 对象 */
    private fun balancedObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    companion object {
        /** 计划 JSON 总长度上限（64 KB） */
        const val MAX_PLAN_JSON_CHARS = 64 * 1024
    }
}
