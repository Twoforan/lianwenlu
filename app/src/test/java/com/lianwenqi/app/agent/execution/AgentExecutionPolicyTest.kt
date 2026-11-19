package com.lianwenqi.app.agent.execution

import com.lianwenqi.app.agent.data.entity.AgentNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExecutionPolicyTest {

    @Test
    fun 只读能力可执行() {
        listOf(
            "READ_BOOK_INFO", "READ_OUTLINE", "READ_CHAPTER_SYNOPSIS",
            "READ_CHAPTER_CONTENT", "SEARCH_CHAPTERS", "RETRIEVE_MEMORY",
            "READ_VOLUME_SYNOPSIS", "READ_STORY_CONTEXT"
        ).forEach {
            assertTrue("$it 应可执行", AgentExecutionPolicy.isCapabilityExecutable(it))
        }
    }

    @Test
    fun 写入能力可执行但必须按受控写入处理() {
        listOf(
            "WRITE_OUTLINE", "WRITE_CHAPTER_SYNOPSIS", "WRITE_CHAPTER_CONTENT",
            "CREATE_OUTLINE_NODE", "UPDATE_OUTLINE_NODE"
        ).forEach {
            assertTrue("$it 应可执行", AgentExecutionPolicy.isCapabilityExecutable(it))
            assertTrue("$it 应标记为写入能力", AgentExecutionPolicy.isWriteCapability(it))
            assertFalse("$it 不是只读工具", AgentExecutionPolicy.requiresTool(it))
            assertFalse("$it 不应被明确阻止", AgentExecutionPolicy.isBlockedCapability(it))
        }
    }

    @Test
    fun 生成能力可执行但重写与澄清仍被阻止() {
        listOf("GENERATE_CONTENT", "GENERATE_SYNOPSIS").forEach {
            assertTrue("$it 应可执行", AgentExecutionPolicy.isCapabilityExecutable(it))
            assertTrue("$it 应标记为生成能力", AgentExecutionPolicy.isGenerationCapability(it))
            assertFalse("$it 不是写入能力", AgentExecutionPolicy.isWriteCapability(it))
            assertFalse("$it 不应被阻止", AgentExecutionPolicy.isBlockedCapability(it))
        }
        listOf(
            "REWRITE_CONTENT",
            "ASK_USER_CLARIFICATION"
        ).forEach {
            assertFalse("$it 不应可执行", AgentExecutionPolicy.isCapabilityExecutable(it))
            assertTrue("$it 应被明确阻止", AgentExecutionPolicy.isBlockedCapability(it))
        }
    }

    @Test
    fun 分析类能力可执行但不强制工具() {
        assertTrue(AgentExecutionPolicy.isCapabilityExecutable("ANALYZE_CHARACTER_RELATIONSHIPS"))
        assertFalse(AgentExecutionPolicy.requiresTool("ANALYZE_CHARACTER_RELATIONSHIPS"))
        assertTrue(AgentExecutionPolicy.requiresPriorToolResult("ANALYZE_CHARACTER_RELATIONSHIPS"))
        assertTrue(AgentExecutionPolicy.requiresTool("READ_OUTLINE"))
    }

    @Test
    fun 节点类型映射正确() {
        assertEquals(AgentNodeType.SEARCH_OUTLINE, AgentExecutionPolicy.nodeTypeFor("READ_OUTLINE"))
        assertEquals(AgentNodeType.SEARCH_MEMORY, AgentExecutionPolicy.nodeTypeFor("RETRIEVE_MEMORY"))
        assertEquals(AgentNodeType.ANALYZE_RESULT, AgentExecutionPolicy.nodeTypeFor("GENERATE_REPORT"))
        assertEquals(AgentNodeType.VALIDATE, AgentExecutionPolicy.nodeTypeFor("VALIDATE_CONTENT"))
        assertEquals(
            AgentNodeType.WRITE_DATA,
            AgentExecutionPolicy.nodeTypeFor("WRITE_CHAPTER_SYNOPSIS")
        )
        assertEquals(
            AgentNodeType.GENERATE_SYNOPSIS,
            AgentExecutionPolicy.nodeTypeFor("GENERATE_SYNOPSIS")
        )
        assertEquals(
            AgentNodeType.GENERATE_CONTENT,
            AgentExecutionPolicy.nodeTypeFor("GENERATE_CONTENT")
        )
        assertEquals(AgentNodeType.PLAN_STEP, AgentExecutionPolicy.nodeTypeFor("UNKNOWN"))
    }
}
