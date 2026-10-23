package com.lianwenqi.app.ai

import com.lianwenqi.app.util.AI_CHAPTER_TOOL_PROMPT
import com.lianwenqi.app.util.AI_IMPORT_CHAPTER_TOOL_SPEC
import com.lianwenqi.app.util.AI_IMPORT_OUTLINE_TOOL_SPEC
import com.lianwenqi.app.util.AI_OUTLINE_TOOL_PROMPT
import com.lianwenqi.app.util.parseAiChapterBlocksJson
import com.lianwenqi.app.util.parseAiOutlineBlocksJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具调用：三种接口的响应 JSON 解析 + 流式分片累积 + 工具参数解析 */
class AiToolCallTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val outlineArguments =
        """{"blocks":[{"type":"人设","name":"主角","fields":[{"key":"姓名","value":"张三"}]}]}"""

    @Test
    fun chatCompletionsStream_toolCallArgumentsAccumulateByIndex() {
        val chunks = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"import_outline","arguments":"{\"blocks\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"[{\"type\":\"人设\","}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"name\":\"主角\",\"fields\":[{\"key\":\"姓名\",\"value\":\"张三\"}]}]}"}}]}}]}"""
        )
        val acc = ToolCallAccumulator()
        chunks.forEach { raw ->
            val event = json.decodeFromString<ChatCompletionChunk>(raw)
            event.choices.firstOrNull()?.delta?.toolCalls?.forEach { c ->
                acc.chatDelta(c.index, c.id, c.function?.name, c.function?.arguments)
            }
        }
        val calls = acc.build()
        assertEquals(1, calls.size)
        assertEquals("import_outline", calls[0].name)
        assertEquals("call_1", calls[0].id)
        assertEquals(outlineArguments, calls[0].arguments)
        // 累积出的参数可以被工具参数解析器还原成结构化区块
        val blocks = parseAiOutlineBlocksJson(calls[0].arguments)
        assertEquals(1, blocks?.size)
        assertEquals("人设", blocks!![0].type)
        assertEquals("主角", blocks[0].name)
    }

    @Test
    fun chatCompletionsNonStream_readsToolCallsFromMessage() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"已为你整理","tool_calls":[
            {"id":"call_9","function":{"name":"import_chapters","arguments":"{\"chapters\":[{\"volume\":\"第一卷\",\"chapterName\":\"开端\",\"summary\":\"主角登场\",\"order\":1}]}"}}
        ]},"finish_reason":"tool_calls"}]}"""
        val parsed = json.decodeFromString<ChatCompletionResponse>(raw)
        val message = parsed.choices.first().message
        assertEquals("已为你整理", message?.content)
        val call = message?.toolCalls?.first()
        assertEquals("import_chapters", call?.function?.name)
        val chapters = parseAiChapterBlocksJson(call?.function?.arguments.orEmpty())
        assertEquals(1, chapters?.size)
        assertEquals("第一卷", chapters!![0].volume)
        assertEquals(1, chapters[0].order)
    }

    @Test
    fun responsesStream_addedAndDeltaAccumulate() {
        val acc = ToolCallAccumulator()
        val added = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_2","name":"import_chapters","arguments":""}}"""
        )
        acc.responsesAdded(added.item?.id, added.item?.callId, added.item?.name, added.item?.arguments)
        val deltas = listOf(
            "{\"chapters\":[{\"chapterName\":\"开端\"",
            ",\"summary\":"
        )
        deltas.forEach { part ->
            val event = json.decodeFromString<ResponsesStreamEvent>(
                """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":${JsonPrimitive(part)}}"""
            )
            acc.responsesDelta(event.itemId, event.delta)
        }
        // 参数不完整时解析器应返回 null 而不是崩溃
        assertNull(parseAiChapterBlocksJson(acc.build().single().arguments))
        val tail = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","delta":"\"主角登场\",\"order\":1}]}"}"""
        )
        acc.responsesDelta(tail.itemId, tail.delta)
        val chapters = parseAiChapterBlocksJson(acc.build().single().arguments)
        assertEquals(1, chapters?.size)
        assertEquals("开端", chapters!![0].chapterName)
        assertEquals("主角登场", chapters[0].summary)
        assertEquals(1, chapters[0].order)
    }

    @Test
    fun anthropicStream_toolUseInputAccumulates() {
        val acc = ToolCallAccumulator()
        val start = json.decodeFromString<AnthropicStreamEvent>(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"import_outline","input":{}}}"""
        )
        acc.anthropicStart(start.index, start.contentBlock?.id, start.contentBlock?.name)
        listOf("""{"blocks":[{"type":"世界""", """观","name":"大陆","fields":[]}]}""").forEach {
            val delta = json.decodeFromString<AnthropicStreamEvent>(
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":${JsonPrimitive(it)}}}"""
            )
            acc.anthropicDelta(delta.index, delta.delta?.partialJson)
        }
        val call = acc.build().single()
        assertEquals("toolu_1", call.id)
        assertEquals("import_outline", call.name)
        val blocks = parseAiOutlineBlocksJson(call.arguments)
        assertEquals(1, blocks?.size)
        assertEquals("世界观", blocks!![0].type)
    }

    @Test
    fun malformedToolArgumentsReturnNull() {
        assertNull(parseAiOutlineBlocksJson("""{"blocks":[{"""))
        assertNull(parseAiChapterBlocksJson("not json"))
    }

    @Test
    fun toolSpecs_carryValidJsonSchema() {
        listOf(AI_IMPORT_OUTLINE_TOOL_SPEC, AI_IMPORT_CHAPTER_TOOL_SPEC).forEach { spec ->
            assertTrue(spec.name.isNotBlank())
            assertTrue(spec.description.isNotBlank())
            val obj = json.parseToJsonElement(spec.parametersJson)
            assertTrue(obj is kotlinx.serialization.json.JsonObject)
        }
        // 旧标记提示词仍然保留，供复制给外部 AI 与手动导入的二次解析使用
        assertTrue(AI_OUTLINE_TOOL_PROMPT.contains("<<<AI_OUTLINE_JSON_V1>>>"))
        assertTrue(AI_CHAPTER_TOOL_PROMPT.contains("<<<AI_CHAPTER_JSON_V1>>>"))
    }
}
