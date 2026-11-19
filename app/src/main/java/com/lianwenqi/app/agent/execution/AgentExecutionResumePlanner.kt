package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.agent.data.agentJson
import com.lianwenqi.app.agent.data.entity.AgentExecutionNode
import com.lianwenqi.app.agent.data.entity.AgentNodeStatus
import com.lianwenqi.app.agent.model.AgentPlanStep
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 从持久化节点推导出的恢复位置 */
data class AgentResumePlan(
    /** 第一个未完成步骤索引；等于步骤总数表示全部完成 */
    val startIndex: Int,
    /** 已完成步骤的输出，按计划顺序 */
    val stepResults: List<String>,
    val nodeIdByStepId: Map<String, Long>,
    /** 需要从 RUNNING / WAITING 重新打开的节点 */
    val reopenNodeId: Long? = null,
    /** 处于 FAILED / SKIPPED / CANCELLED，不能自动恢复的节点标题 */
    val blockedNodeTitle: String? = null,
    /** 当前执行批次中缺失的 stepId */
    val missingNodeStepId: String? = null,
    /** 节点标记完成但缺少结果；需要从该步骤重跑 */
    val inconsistentCompletedNode: Boolean = false
)

/**
 * F7 执行游标规划。
 *
 * 完全基于数据库中的节点状态推导，不依赖进程内循环变量。
 * 不允许从第一步重跑所有节点，也不把 RUNNING 节点直接当成成功或失败。
 */
object AgentExecutionResumePlanner {

    fun plan(
        orderedSteps: List<AgentPlanStep>,
        runNodes: List<AgentExecutionNode>
    ): AgentResumePlan {
        val nodeByStepId = runNodes.mapNotNull { node ->
            stepIdOf(node)?.let { it to node }
        }.toMap()
        val nodeIds = nodeByStepId.mapValues { it.value.id }

        val results = mutableListOf<String>()
        var startIndex = orderedSteps.size
        var reopenNodeId: Long? = null
        var blockedTitle: String? = null
        var inconsistent = false

        for ((index, step) in orderedSteps.withIndex()) {
            val node = nodeByStepId[step.stepId]
            if (node == null) {
                return AgentResumePlan(
                    startIndex = index,
                    stepResults = results,
                    nodeIdByStepId = nodeIds,
                    missingNodeStepId = step.stepId
                )
            }
            if (node.status == AgentNodeStatus.COMPLETED) {
                val result = resultOf(node)
                if (result.isNullOrBlank()) {
                    inconsistent = true
                    startIndex = index
                    break
                }
                results += result
                continue
            }
            startIndex = index
            when (node.status) {
                AgentNodeStatus.RUNNING, AgentNodeStatus.WAITING -> reopenNodeId = node.id
                AgentNodeStatus.FAILED,
                AgentNodeStatus.SKIPPED,
                AgentNodeStatus.CANCELLED -> blockedTitle = node.title
                else -> Unit
            }
            break
        }

        return AgentResumePlan(
            startIndex = startIndex,
            stepResults = results,
            nodeIdByStepId = nodeIds,
            reopenNodeId = reopenNodeId,
            blockedNodeTitle = blockedTitle,
            inconsistentCompletedNode = inconsistent
        )
    }

    private fun stepIdOf(node: AgentExecutionNode): String? = runCatching {
        agentJson.parseToJsonElement(node.inputJson).jsonObject["stepId"]
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun resultOf(node: AgentExecutionNode): String? = runCatching {
        agentJson.parseToJsonElement(node.outputJson).jsonObject["result"]
            ?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
