package com.lianwenqi.app.agent.planning

import com.lianwenqi.app.agent.execution.AgentExecutionPolicy
import com.lianwenqi.app.agent.model.AgentCapabilityCatalog
import com.lianwenqi.app.agent.model.AgentPlan
import com.lianwenqi.app.agent.model.AgentPlanValidationResult
import com.lianwenqi.app.agent.model.AgentRiskLevel

/**
 * 计划结构校验。
 *
 * 结构错误（缺字段、重复 stepId、依赖缺失 / 环、非法风险等级等）使计划无效；
 * 能力缺失只产生警告并记录，UI 必须明确提示「未登记 / 暂不可执行」。
 */
class AgentPlanValidator(
    private val capabilityCatalog: AgentCapabilityCatalog = AgentCapabilityCatalog
) {

    fun validate(plan: AgentPlan): AgentPlanValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val unknownCapabilities = mutableListOf<String>()
        val notYetAvailable = mutableListOf<String>()

        if (plan.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            errors += "不支持的计划版本：${plan.schemaVersion}"
        }
        if (plan.taskTitle.isBlank()) errors += "缺少任务标题"
        if (plan.taskTitle.length > MAX_TITLE_CHARS) errors += "任务标题超过 $MAX_TITLE_CHARS 字符"
        if (plan.goalSummary.isBlank()) errors += "缺少目标概括"
        if (plan.goalSummary.length > MAX_SUMMARY_CHARS) errors += "目标概括超过 $MAX_SUMMARY_CHARS 字符"
        if (plan.thinkingSummary.length > MAX_SUMMARY_CHARS) {
            errors += "思考摘要超过 $MAX_SUMMARY_CHARS 字符"
        }
        if (plan.needsClarification && plan.clarificationQuestions.isEmpty()) {
            errors += "需要用户补充信息，但未提供问题"
        }
        if (plan.clarificationQuestions.size > MAX_CLARIFICATION_QUESTIONS) {
            errors += "澄清问题超过 $MAX_CLARIFICATION_QUESTIONS 个"
        }
        plan.clarificationQuestions.forEachIndexed { index, question ->
            if (question.isBlank()) errors += "第 ${index + 1} 个澄清问题为空"
            if (question.length > MAX_QUESTION_CHARS) {
                errors += "第 ${index + 1} 个澄清问题超过 $MAX_QUESTION_CHARS 字符"
            }
        }
        if (plan.clarificationOptions.size > MAX_CLARIFICATION_OPTIONS) {
            errors += "澄清候选项超过 $MAX_CLARIFICATION_OPTIONS 个"
        }
        plan.clarificationOptions.forEachIndexed { index, option ->
            if (option.isBlank()) errors += "第 ${index + 1} 个澄清候选项为空"
            if (option.length > MAX_OPTION_CHARS) {
                errors += "第 ${index + 1} 个澄清候选项超过 $MAX_OPTION_CHARS 字符"
            }
        }
        if (plan.clarificationOptions.isNotEmpty() && !plan.needsClarification) {
            warnings += "计划未要求澄清，但提供了澄清候选项"
        }
        if (plan.steps.isEmpty() && !plan.needsClarification) {
            errors += "计划没有步骤"
        }
        if (plan.steps.size > MAX_STEPS) {
            errors += "计划步骤超过 $MAX_STEPS 个"
        }

        val byId = LinkedHashMap<String, com.lianwenqi.app.agent.model.AgentPlanStep>()
        plan.steps.forEachIndexed { index, step ->
            val label = "第 ${index + 1} 步"
            if (step.stepId.isBlank()) {
                errors += "$label 缺少 stepId"
            } else if (!STEP_ID_PATTERN.matches(step.stepId)) {
                errors += "$label 的 stepId 只能包含字母、数字、下划线或短横线"
            } else if (byId.containsKey(step.stepId)) {
                errors += "stepId 重复：${step.stepId}"
            } else {
                byId[step.stepId] = step
            }
            if (step.title.isBlank()) errors += "$label 缺少标题"
            if (step.title.length > MAX_STEP_TITLE_CHARS) {
                errors += "$label 标题超过 $MAX_STEP_TITLE_CHARS 字符"
            }
            if (step.description.length > MAX_STEP_DESCRIPTION_CHARS) {
                errors += "$label 描述超过 $MAX_STEP_DESCRIPTION_CHARS 字符"
            }
            if (step.expectedOutput.length > MAX_EXPECTED_OUTPUT_CHARS) {
                errors += "$label 预期产出超过 $MAX_EXPECTED_OUTPUT_CHARS 字符"
            }
            if (!AgentRiskLevel.isValid(step.riskLevel)) {
                errors += "$label 的风险等级非法：${step.riskLevel}"
            }
            if (step.capabilityKey.isBlank()) {
                errors += "$label 缺少 capabilityKey"
            } else {
                val capability = capabilityCatalog.find(step.capabilityKey)
                when {
                    capability == null -> {
                        unknownCapabilities += step.capabilityKey
                        warnings += "$label 使用未登记能力：${step.capabilityKey}"
                    }
                    !AgentExecutionPolicy.isCapabilityExecutable(capability.key) -> {
                        notYetAvailable += step.capabilityKey
                        warnings += "$label 的能力「${capability.displayName}」当前阶段暂不可执行"
                    }
                }
            }
            step.dependencies.forEach { dep ->
                when {
                    dep == step.stepId -> errors += "$label 不能依赖自身"
                    dep !in byId && plan.steps.none { it.stepId == dep } ->
                        errors += "$label 依赖不存在的步骤：$dep"
                }
            }
        }

        // 循环依赖检测（DFS）
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return false
            if (id in visited) return true
            visiting += id
            val step = byId[id]
            if (step != null) {
                step.dependencies.forEach { dep ->
                    if (byId.containsKey(dep) && !visit(dep)) return false
                }
            }
            visiting -= id
            visited += id
            return true
        }
        byId.keys.forEach { id ->
            if (!visit(id)) errors += "计划存在循环依赖：$id"
        }

        if (plan.thinkingSummary.isBlank()) {
            warnings += "计划没有可展示的思考摘要"
        }

        return AgentPlanValidationResult(
            isValid = errors.isEmpty(),
            errors = errors.distinct(),
            warnings = warnings.distinct(),
            unknownCapabilities = unknownCapabilities.distinct(),
            notYetAvailableCapabilities = notYetAvailable.distinct(),
            stepCount = plan.steps.size
        )
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_STEPS = 30
        const val MAX_TITLE_CHARS = 200
        const val MAX_SUMMARY_CHARS = 1000
        const val MAX_STEP_TITLE_CHARS = 120
        const val MAX_STEP_DESCRIPTION_CHARS = 2000
        const val MAX_EXPECTED_OUTPUT_CHARS = 500
        const val MAX_QUESTION_CHARS = 500
        const val MAX_CLARIFICATION_QUESTIONS = 10
        const val MAX_CLARIFICATION_OPTIONS = 4
        const val MAX_OPTION_CHARS = 100

        private val STEP_ID_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
