package com.lianwenqi.app.agent.tool

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolValidatorTest {

    private val tool = AgentTool(
        toolKey = "search_chapters",
        name = "search_chapters",
        description = "测试",
        capabilityKey = "SEARCH_CHAPTERS",
        parameters = listOf(
            AgentToolParam(
                name = "keyword",
                type = AgentToolParamType.STRING,
                description = "关键词",
                required = true,
                minLength = 1,
                maxLength = 10
            ),
            AgentToolParam(
                name = "topK",
                type = AgentToolParamType.LONG,
                description = "数量",
                required = false,
                minValue = 1,
                maxValue = 5
            ),
            AgentToolParam(
                name = "ids",
                type = AgentToolParamType.LONG_ARRAY,
                description = "id 列表",
                required = false,
                maxItems = 2
            )
        ),
        execute = { _, _ -> AgentToolResult.ok("{}") }
    )

    private val validator = AgentToolValidator()

    @Test
    fun 合法参数通过校验() {
        val result = validator.parse(tool, """{"keyword":"冲突","topK":3,"ids":[1,2]}""")
        assertTrue(result is AgentToolParseResult.Success)
        val args = (result as AgentToolParseResult.Success).args
        assertTrue(args.string("keyword") == "冲突")
        assertTrue(args.long("topK") == 3L)
        assertTrue(args.longList("ids") == listOf(1L, 2L))
    }

    @Test
    fun 未知参数被拒绝() {
        val result = validator.parse(tool, """{"keyword":"a","bookId":2}""")
        assertTrue(result is AgentToolParseResult.Failure)
        assertTrue((result as AgentToolParseResult.Failure).message.contains("未知参数"))
    }

    @Test
    fun 缺少必填参数被拒绝() {
        val result = validator.parse(tool, """{"topK":1}""")
        assertTrue(result is AgentToolParseResult.Failure)
    }

    @Test
    fun 错误类型被拒绝() {
        val result = validator.parse(tool, """{"keyword":123}""")
        assertTrue(result is AgentToolParseResult.Failure)
    }

    @Test
    fun 字符串超长被拒绝() {
        val result = validator.parse(tool, """{"keyword":"12345678901"}""")
        assertTrue(result is AgentToolParseResult.Failure)
    }

    @Test
    fun 数值越界被拒绝() {
        val result = validator.parse(tool, """{"keyword":"a","topK":9}""")
        assertTrue(result is AgentToolParseResult.Failure)
    }

    @Test
    fun 数组超量被拒绝() {
        val result = validator.parse(tool, """{"keyword":"a","ids":[1,2,3]}""")
        assertTrue(result is AgentToolParseResult.Failure)
    }

    @Test
    fun 数组元素类型错误被拒绝() {
        val result = validator.parse(tool, """{"keyword":"a","ids":[1,"x"]}""")
        assertTrue(result is AgentToolParseResult.Failure)
    }

    @Test
    fun 非法JSON被拒绝() {
        val result = validator.parse(tool, "not-json")
        assertTrue(result is AgentToolParseResult.Failure)
    }

    @Test
    fun 非对象JSON被拒绝() {
        val result = validator.parse(tool, "[1,2]")
        assertTrue(result is AgentToolParseResult.Failure)
    }

    @Test
    fun 参数过大被拒绝() {
        val huge = "{\"keyword\":\"" + "a".repeat(17_000) + "\"}"
        val result = validator.parse(tool, huge)
        assertTrue(result is AgentToolParseResult.Failure)
        assertTrue((result as AgentToolParseResult.Failure).message.contains("过大"))
    }
}
