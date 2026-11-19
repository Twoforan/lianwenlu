package com.lianwenqi.app.agent.tool

import com.lianwenqi.app.ai.AiToolSpec

/**
 * Agent 工具注册表。
 *
 * 允许注册只读、生成草稿与受控写入工具；删除 / 批量写入仍被拒绝。
 * 模型只能按 name 调用，不能创建工具或改变权限。
 */
class AgentToolRegistry {

    private val byName = LinkedHashMap<String, AgentTool>()
    private val byKey = LinkedHashMap<String, AgentTool>()

    @Synchronized
    fun register(tool: AgentTool) {
        require(tool.name.matches(TOOL_NAME_PATTERN)) {
            "非法工具名：${tool.name}（只允许小写字母、数字、下划线，且以字母开头）"
        }
        require(tool.permissionLevel in ALLOWED_PERMISSIONS) {
            "F6 不允许注册该权限级别的工具：${tool.name}（${tool.permissionLevel}）"
        }
        require(tool.toolKey.isNotBlank()) { "toolKey 不能为空" }
        require(!byName.containsKey(tool.name)) { "工具已注册：${tool.name}" }
        require(!byKey.containsKey(tool.toolKey)) { "工具 key 已注册：${tool.toolKey}" }
        byName[tool.name] = tool
        byKey[tool.toolKey] = tool
    }

    @Synchronized
    fun registerAll(tools: List<AgentTool>) = tools.forEach { register(it) }

    fun findByName(name: String): AgentTool? = byName[name]

    fun findByKey(key: String): AgentTool? = byKey[key]

    fun findByCapability(capabilityKey: String): List<AgentTool> =
        byName.values.filter { it.capabilityKey == capabilityKey }

    /** 所有已注册工具（包括受控写入工具） */
    fun all(): List<AgentTool> = byName.values.toList()

    /** 只读工具；保留给 F4 回归与只读执行策略使用 */
    fun allReadOnly(): List<AgentTool> =
        byName.values.filter { it.permissionLevel == AgentToolPermission.READ_ONLY }

    fun toSpecs(tools: List<AgentTool> = all()): List<AiToolSpec> =
        tools.map { it.toSpec() }

    fun isRegisteredReadOnly(name: String): Boolean =
        byName[name]?.permissionLevel == AgentToolPermission.READ_ONLY

    companion object {
        private val TOOL_NAME_PATTERN = Regex("^[a-z][a-z0-9_]{1,63}$")

        /** F6 开放只读、生成草稿、创建、更新、覆盖；删除 / 批量写入不允许注册 */
        private val ALLOWED_PERMISSIONS = setOf(
            AgentToolPermission.READ_ONLY,
            AgentToolPermission.GENERATE,
            AgentToolPermission.CREATE,
            AgentToolPermission.UPDATE,
            AgentToolPermission.OVERWRITE
        )
    }
}
