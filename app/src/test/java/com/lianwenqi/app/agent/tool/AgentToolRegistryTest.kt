package com.lianwenqi.app.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRegistryTest {

    private fun tool(
        name: String = "get_outline",
        permission: AgentToolPermission = AgentToolPermission.READ_ONLY
    ) = AgentTool(
        toolKey = name,
        name = name,
        description = "测试工具",
        capabilityKey = "READ_OUTLINE",
        permissionLevel = permission,
        execute = { _, _ -> AgentToolResult.ok("{}") }
    )

    @Test
    fun 注册并查询只读工具() {
        val registry = AgentToolRegistry()
        registry.register(tool())
        assertEquals("get_outline", registry.findByName("get_outline")?.name)
        assertEquals("get_outline", registry.findByKey("get_outline")?.name)
        assertEquals(1, registry.allReadOnly().size)
        assertEquals(1, registry.toSpecs().size)
        assertTrue(registry.isRegisteredReadOnly("get_outline"))
        assertNull(registry.findByName("missing"))
    }

    @Test
    fun 重复注册被拒绝() {
        val registry = AgentToolRegistry()
        registry.register(tool())
        val result = runCatching { registry.register(tool()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun 受控写入工具可注册而删除工具被拒绝() {
        val registry = AgentToolRegistry()
        registry.register(tool("write_outline", AgentToolPermission.UPDATE))
        assertEquals("write_outline", registry.findByName("write_outline")?.name)
        assertEquals(0, registry.allReadOnly().size)

        val result = runCatching {
            registry.register(tool("delete_outline", AgentToolPermission.DELETE))
        }
        assertTrue(result.isFailure)
        assertNull(registry.findByName("delete_outline"))
    }

    @Test
    fun 生成工具可注册且不计入只读工具() {
        val registry = AgentToolRegistry()
        registry.register(
            tool("generate_chapter_synopsis", AgentToolPermission.GENERATE).copy(
                capabilityKey = "GENERATE_SYNOPSIS"
            )
        )
        assertEquals(
            "generate_chapter_synopsis",
            registry.findByCapability("GENERATE_SYNOPSIS").single().name
        )
        assertEquals(0, registry.allReadOnly().size)
        assertEquals(1, registry.toSpecs().size)
    }

    @Test
    fun 非法工具名被拒绝() {
        val registry = AgentToolRegistry()
        val result = runCatching { registry.register(tool("Get Outline")) }
        assertTrue(result.isFailure)
    }

    @Test
    fun 按能力查询() {
        val registry = AgentToolRegistry()
        registry.register(tool("get_outline"))
        registry.register(
            tool("get_chapter_synopsis").copy(
                capabilityKey = "READ_CHAPTER_SYNOPSIS"
            )
        )
        assertEquals(1, registry.findByCapability("READ_OUTLINE").size)
        assertEquals(1, registry.findByCapability("READ_CHAPTER_SYNOPSIS").size)
        assertTrue(registry.findByCapability("READ_CHAPTER_CONTENT").isEmpty())
    }
}
