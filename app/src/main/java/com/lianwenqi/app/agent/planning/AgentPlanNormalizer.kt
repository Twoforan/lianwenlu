package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.model.AgentCapabilityCatalog
import com.lianwenqi.app.agent.model.AgentRiskLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 计划 JSON 字段归一化。
 *
 * 模型经常不按约定输出：用 `id` / `type` / `name` 等别名、漏掉 `stepId` 或
 * `capabilityKey`、步骤放在 `plan` / `tasks` 下等。这里先做一次宽容的字段修复，
 * 再交给 [AgentPlanParser] 严格反序列化与 [AgentPlanValidator] 校验。
 *
 * 归一化只做「字段补齐与别名映射」，不改变计划语义：
 * - `stepId`：取 `id` 等别名，缺失时按序号补 `step_001`；
 * - `capabilityKey`：按 key / 显示名 / 常见写法匹配能力目录，仍无法判断时按步骤语义推断；
 * - `dependencies`：随 stepId 一起重写，避免指向旧 id；
 * - 标题 / 摘要 / 风险等级：补齐并截断到校验允许的长度。
 */
internal object AgentPlanNormalizer {

    private const val MAX_STEPS = 30
    private val STEP_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun normalize(rawJson: String): String = runCatching {
        val root = agentJson.parseToJsonElement(rawJson) as JsonObject
        val rawSteps = root
            .arrayAlias("steps", "plan", "tasks", "actions", "stepList")
            .take(MAX_STEPS)
            .mapIndexed { index, element -> prepareStep(element, index) }

        // 生成最终 stepId（唯一），并建立「模型原始 id -> 最终 stepId」映射
        val usedIds = mutableSetOf<String>()
        val finalIds = rawSteps.mapIndexed { index, step ->
            val base = if (step.id.isNotBlank() && STEP_ID_PATTERN.matches(step.id)) {
                step.id
            } else {
                "step_%03d".format(index + 1)
            }
            var candidate = base
            var suffix = 2
            while (!usedIds.add(candidate)) {
                candidate = "${base}_$suffix"
                suffix++
            }
            candidate
        }
        val idMap = mutableMapOf<String, String>()
        rawSteps.forEachIndexed { index, step ->
            if (step.id.isNotBlank()) idMap[step.id] = finalIds[index]
        }

        val questions = root.stringList(
            "clarificationQuestions", "clarification_questions", "questions",
            limit = 10, maxChars = 500
        )
        val options = root.stringList(
            "clarificationOptions", "clarification_options", "options",
            limit = 4, maxChars = 100
        )
        val needsClarification = root.boolAlias(
            "needsClarification", "needs_clarification", "needClarification"
        ) || (questions.isNotEmpty() && rawSteps.isEmpty())

        val firstStepTitle = rawSteps.firstOrNull { it.title.isNotBlank() }?.title.orEmpty()
        val taskTitle = root.text("taskTitle", "task_title", "title", "taskName", "task_name", "name")
            .ifBlank { firstStepTitle }
            .ifBlank { "执行计划" }
            .take(200)
        val goalSummary = root.text("goalSummary", "goal_summary", "goal", "summary", "objective")
            .ifBlank { taskTitle }
            .take(1000)

        buildJsonObject {
            // 只支持 schemaVersion = 1，统一写回避免历史版本号导致整份计划无效
            put("schemaVersion", 1)
            put("taskTitle", taskTitle)
            put("goalSummary", goalSummary)
            put("thinkingSummary", root.text("thinkingSummary", "thinking_summary", "thinking").take(1000))
            put("needsClarification", needsClarification)
            putJsonArray("clarificationQuestions") { questions.forEach { add(it) } }
            putJsonArray("clarificationOptions") { options.forEach { add(it) } }
            putJsonArray("steps") {
                rawSteps.forEachIndexed { index, step ->
                    addJsonObject {
                        put("stepId", finalIds[index])
                        put("title", step.title)
                        put("description", step.description)
                        put("capabilityKey", step.capabilityKey)
                        put("expectedOutput", step.expectedOutput)
                        putJsonArray("dependencies") {
                            step.dependencies.forEach { dependency ->
                                add(idMap[dependency] ?: dependency)
                            }
                        }
                        put("riskLevel", step.riskLevel)
                    }
                }
            }
        }.toString()
    }.getOrDefault(rawJson)

    private data class RawStep(
        val id: String,
        val title: String,
        val description: String,
        val capabilityKey: String,
        val expectedOutput: String,
        val dependencies: List<String>,
        val riskLevel: String
    )

    private fun prepareStep(element: JsonElement, index: Int): RawStep {
        val obj = element as? JsonObject ?: return RawStep(
            id = "",
            title = "步骤 ${index + 1}",
            description = "",
            capabilityKey = inferCapability(""),
            expectedOutput = "",
            dependencies = emptyList(),
            riskLevel = AgentRiskLevel.LOW
        )
        val description = obj.text("description", "desc", "detail", "details", "content")
            .take(2000)
        val title = obj.text("title", "name", "stepTitle", "step_title", "task")
            .ifBlank { description.lineSequence().firstOrNull().orEmpty().trim() }
            .ifBlank { "步骤 ${index + 1}" }
            .take(120)
        val rawCapability = obj.text(
            "capabilityKey", "capability_key", "capability",
            "type", "action", "tool", "toolKey"
        )
        return RawStep(
            id = obj.text("stepId", "step_id", "id", "key").take(64),
            title = title,
            description = description,
            capabilityKey = normalizeCapability(rawCapability, "$title $description"),
            expectedOutput = obj.text(
                "expectedOutput", "expected_output", "output", "result"
            ).take(500),
            dependencies = obj.stringList(
                "dependencies", "dependsOn", "depends_on", "depends",
                limit = MAX_STEPS, maxChars = 64
            ),
            riskLevel = normalizeRisk(obj.text("riskLevel", "risk_level", "risk"))
        )
    }

    private fun normalizeCapability(raw: String, stepText: String): String {
        val text = raw.trim()
        if (text.isBlank()) return inferCapability(stepText)
        AgentCapabilityCatalog.all.firstOrNull { it.key.equals(text, ignoreCase = true) }
            ?.let { return it.key }
        AgentCapabilityCatalog.all.firstOrNull { it.displayName == text }
            ?.let { return it.key }
        val slug = text.uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        AgentCapabilityCatalog.find(slug)?.let { return it.key }
        return inferCapability("$text $stepText")
    }

    /** 模型没给能力时按步骤语义推断；无法判断则回落到通用的「生成分析报告」 */
    private fun inferCapability(text: String): String {
        val t = text.lowercase()
        return when {
            (t.contains("大纲") || t.contains("outline")) &&
                (t.contains("写") || t.contains("创建") || t.contains("更新") ||
                    t.contains("修改") || t.contains("write") || t.contains("create") ||
                    t.contains("update")) -> "WRITE_OUTLINE"
            (t.contains("大纲") || t.contains("outline")) &&
                (t.contains("读取") || t.contains("查看") || t.contains("read")) -> "READ_OUTLINE"
            (t.contains("正文") || t.contains("content")) &&
                (t.contains("写") || t.contains("生成") ||
                    t.contains("write") || t.contains("generate")) -> "GENERATE_CONTENT"
            (t.contains("梗概") || t.contains("synopsis")) &&
                (t.contains("写") || t.contains("生成") ||
                    t.contains("write") || t.contains("generate")) -> "GENERATE_SYNOPSIS"
            t.contains("检索") || t.contains("搜索") || t.contains("查找") ||
                t.contains("search") || t.contains("find") -> "SEARCH_CHAPTERS"
            t.contains("读取") || t.contains("查看") || t.contains("read") -> "READ_STORY_CONTEXT"
            t.contains("分析") || t.contains("检查") || t.contains("校验") ||
                t.contains("analyze") || t.contains("check") -> "ANALYZE_PLOT_CONSISTENCY"
            else -> "GENERATE_REPORT"
        }
    }

    private fun normalizeRisk(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return AgentRiskLevel.LOW
        AgentRiskLevel.all.firstOrNull { it.equals(text, ignoreCase = true) }
            ?.let { return it.uppercase() }
        return when (text) {
            "低", "低风险" -> AgentRiskLevel.LOW
            "中", "中风险" -> AgentRiskLevel.MEDIUM
            "高", "高风险", "严重" -> AgentRiskLevel.HIGH
            else -> AgentRiskLevel.LOW
        }
    }

    private fun JsonObject.text(vararg keys: String): String {
        keys.forEach { key ->
            val value = textOf(this[key]) ?: return@forEach
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    private fun textOf(element: JsonElement?): String? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> element.content
        is JsonObject -> element.text(
            "text", "content", "question", "label", "value", "title", "name"
        ).ifBlank { null }
        else -> null
    }

    private fun JsonObject.arrayAlias(vararg keys: String): List<JsonElement> {
        keys.forEach { key ->
            when (val element = this[key]) {
                is JsonArray -> if (element.isNotEmpty()) return element
                is JsonObject -> {
                    val nested = element.arrayAlias("steps", "items", "list", "plan")
                    if (nested.isNotEmpty()) return nested
                }
                else -> Unit
            }
        }
        return emptyList()
    }

    private fun JsonObject.stringList(
        vararg keys: String,
        limit: Int,
        maxChars: Int
    ): List<String> {
        keys.forEach { key ->
            val values = when (val element = this[key]) {
                is JsonArray -> element.mapNotNull { textOf(it) }
                null, JsonNull -> emptyList()
                else -> listOfNotNull(textOf(element))
            }.map { it.trim() }.filter { it.isNotEmpty() }
            if (values.isNotEmpty()) {
                return values.take(limit).map { it.take(maxChars) }
            }
        }
        return emptyList()
    }

    private fun JsonObject.boolAlias(vararg keys: String): Boolean {
        keys.forEach { key ->
            val text = (this[key] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: return@forEach
            when (text) {
                "true", "yes", "1", "是" -> return true
                "false", "no", "0", "否" -> return false
            }
        }
        return false
    }
}
