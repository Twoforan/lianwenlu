package com.lianwenqi.app.data

import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.ContextSnapshot
import com.lianwenqi.app.util.AI_OUTLINE_BEGIN
import com.lianwenqi.app.util.AI_OUTLINE_END
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SendChatMessageUseCase] 的 JVM 单元测试。
 *
 * 用手写 fake 替换窄接口依赖（[ChatGateway] / [ModelGateway]），
 * 被测的解析逻辑（parseAiOutlineBlocks 等）与提示词常量使用真实实现。
 * 不使用 Mockito / MockK。
 */
class SendChatMessageUseCaseTest {

    // ---------------- 手写 fake ----------------

    /** 对话侧 fake：记录被写入的助手消息与取消的版本标记 */
    private class FakeChatGateway(
        var messages: List<ChatMessage> = emptyList(),
        var context: String = ""
    ) : ChatGateway {
        val savedMessages = mutableListOf<ChatMessage>()
        val deactivatedReplyToIds = mutableListOf<Long>()
        var buildContextCallCount = 0

        override suspend fun getMessages(sessionId: Long): List<ChatMessage> = messages

        override suspend fun buildContext(
            bookIds: List<Long>,
            chapterIds: List<Long>,
            outlineIds: List<Long>,
            includeContent: Boolean,
            includeSummary: Boolean
        ): String {
            buildContextCallCount++
            return context
        }

        override suspend fun deactivateVariants(replyToId: Long) {
            deactivatedReplyToIds.add(replyToId)
        }

        override suspend fun addMessage(
            sessionId: Long,
            role: ChatRole,
            content: String,
            replyToId: Long,
            variantIndex: Int,
            reasoning: String,
            contextSnapshot: String,
            promptExtra: String,
            promptTokens: Int,
            completionTokens: Int,
            cachedTokens: Int
        ): Long {
            savedMessages.add(
                ChatMessage(
                    sessionId = sessionId,
                    role = role,
                    content = content,
                    replyToId = replyToId,
                    variantIndex = variantIndex,
                    reasoning = reasoning,
                    contextSnapshot = contextSnapshot,
                    promptExtra = promptExtra,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    cachedTokens = cachedTokens
                )
            )
            return savedMessages.size.toLong()
        }
    }

    /** 模型侧 fake：按预设分片调用 onDelta，再返回预设结果（可选直接抛异常） */
    private class FakeModelGateway(
        var model: AiModel? = null,
        var chunks: List<String> = emptyList(),
        var result: AiResult = AiResult.fail("未设置结果"),
        var throwOnSend: Throwable? = null
    ) : ModelGateway {
        var lastRequest: AiRequest? = null

        override suspend fun getModel(id: Long): AiModel? = model

        override suspend fun sendToModel(
            modelId: Long,
            request: AiRequest,
            onDelta: ((String, String) -> Unit)?
        ): AiResult {
            lastRequest = request
            throwOnSend?.let { throw it }
            chunks.forEach { onDelta?.invoke(it, "") }
            return result
        }
    }

    // ---------------- 测试数据构造 ----------------

    private fun userMessage(id: Long = 1L, content: String = "你好") =
        ChatMessage(id = id, sessionId = 1L, role = ChatRole.USER, content = content)

    private fun request(
        writeOutlineTool: Boolean = false,
        chapterImportTool: Boolean = false,
        replyToUserId: Long = 0L,
        historyEndId: Long = 1L
    ) = SendChatRequest(
        sessionId = 1L,
        modelId = 1L,
        systemPrompt = "",
        selection = ContextSnapshot(),
        replyToUserId = replyToUserId,
        historyEndId = historyEndId,
        variantIndex = 0,
        writeOutlineTool = writeOutlineTool,
        chapterImportTool = chapterImportTool
    )

    // ---------------- 场景 1：正常发送 ----------------

    @Test
    fun normalSend_streamsReplyAndSavesMessage() = runTest {
        // Given：会话里已有一条用户消息，模型将分两个分片流式回复
        val chat = FakeChatGateway(messages = listOf(userMessage(id = 1L, content = "你好")))
        val model = FakeModelGateway(chunks = listOf("你", "好"), result = AiResult.ok("你好"))
        val useCase = SendChatMessageUseCase(chat, model)

        // When：发送消息并收集全部事件
        val events = useCase(request(replyToUserId = 1L)).toList()

        // Then：依次收到流式正文分片与完成事件
        assertEquals(
            listOf(ChatEvent.Chunk("你"), ChatEvent.Chunk("好"), ChatEvent.Done),
            events
        )
        // Then：助手消息以完整正文写库，且归属到该用户消息
        assertEquals(1, chat.savedMessages.size)
        val saved = chat.savedMessages.single()
        assertEquals(ChatRole.ASSISTANT, saved.role)
        assertEquals("你好", saved.content)
        assertEquals(1L, saved.replyToId)
        // Then：插入新版本前先清除旧版本展示标记
        assertEquals(listOf(1L), chat.deactivatedReplyToIds)
    }

    // ---------------- 场景 2：AI 返回工具调用（写大纲） ----------------

    @Test
    fun outlineToolCall_parsesBlocksAndEmitsReady() = runTest {
        // Given：勾选了「写入大纲工具」，AI 回复里带标准大纲标记块
        val outlineText = buildString {
            append("这是给你的人设：\n")
            append(AI_OUTLINE_BEGIN).append('\n')
            append("""{"blocks":[{"type":"人设","name":"主角","fields":[{"key":"姓名","value":"张三"}]}]}""")
            append('\n').append(AI_OUTLINE_END)
        }
        val chat = FakeChatGateway(messages = listOf(userMessage(content = "写个主角人设")))
        val model = FakeModelGateway(result = AiResult.ok(outlineText))
        val useCase = SendChatMessageUseCase(chat, model)

        // When
        val events = useCase(request(writeOutlineTool = true, replyToUserId = 1L)).toList()

        // Then：解析出 1 个大纲区块并通过 OutlineBlocksReady 上报
        // （按代码实际行为：UseCase 只负责解析 + 上报，真正写库由 UI 在用户确认后触发）
        val ready = events.filterIsInstance<ChatEvent.OutlineBlocksReady>().single()
        assertEquals(1, ready.blocks.size)
        val block = ready.blocks.single()
        assertEquals("人设", block.type)
        assertEquals("主角", block.name)
        assertEquals(listOf("姓名" to "张三"), block.fields)
        // Then：AI 正文照常落库，最后以 Done 收尾
        assertEquals(outlineText, chat.savedMessages.single().content)
        assertEquals(ChatEvent.Done, events.last())
    }

    // ---------------- 场景 3：AI 返回畸形 JSON ----------------

    @Test
    fun malformedJson_emitsError_andDoesNotWriteOutline() = runTest {
        // Given：标记存在，但其中的 JSON 非法
        val brokenText = "前言\n$AI_OUTLINE_BEGIN\n{ 这不是合法 JSON }\n$AI_OUTLINE_END"
        val chat = FakeChatGateway(messages = listOf(userMessage(content = "写个人设")))
        val model = FakeModelGateway(result = AiResult.ok(brokenText))
        val useCase = SendChatMessageUseCase(chat, model)

        // When
        val events = useCase(request(writeOutlineTool = true, replyToUserId = 1L)).toList()

        // Then：不再静默吞错 —— 发出 Error 事件，告知用户工具没生效
        val error = events.filterIsInstance<ChatEvent.Error>().single()
        assertTrue(error.message.isNotBlank())
        // Then：绝不写入大纲（不上报 OutlineBlocksReady），且不发 Done
        assertTrue(events.none { it is ChatEvent.OutlineBlocksReady })
        assertTrue(events.none { it is ChatEvent.Done })
        // Then：AI 正文本身仍然落库（工具解析失败不影响正文保存）
        assertEquals(brokenText, chat.savedMessages.single().content)
    }

    // ---------------- 场景 4：AI 请求失败 ----------------

    @Test
    fun requestFailure_emitsErrorEvent_andDoesNotSave() = runTest {
        // Given：模型侧返回失败，且失败原因是有类型的 AiError（网络失败）
        val chat = FakeChatGateway(messages = listOf(userMessage()))
        val model = FakeModelGateway(result = AiResult.fail(AiError.Network(RuntimeException("连接被拒绝"))))
        val useCase = SendChatMessageUseCase(chat, model)

        // When
        val events = useCase(request(replyToUserId = 1L)).toList()

        // Then：收到 Error 事件，展示的是该类型对应的提示文案
        val error = events.filterIsInstance<ChatEvent.Error>().single()
        assertEquals("网络连接失败", error.message)
        // Then：失败时不落库、不发 Done
        assertTrue(chat.savedMessages.isEmpty())
        assertTrue(events.none { it is ChatEvent.Done })
    }

    // ---------------- 场景 4 补充：模型侧真正抛异常（验证兜底 + 类型映射） ----------------

    @Test
    fun sendToModelThrows_isConvertedToErrorEvent() = runTest {
        // Given：模型侧直接抛出 SocketTimeoutException（模拟底层硬失败 / 其他 AiClient 实现）
        val chat = FakeChatGateway(messages = listOf(userMessage()))
        val model = FakeModelGateway(throwOnSend = java.net.SocketTimeoutException("读取超时"))
        val useCase = SendChatMessageUseCase(chat, model)

        // When：异常被 UseCase 兜底，不应冲出 Flow 导致崩溃
        val events = useCase(request(replyToUserId = 1L)).toList()

        // Then：收到 Error 事件（超时被映射为 Timeout 类型），而非异常
        val error = events.filterIsInstance<ChatEvent.Error>().single()
        assertEquals("请求超时", error.message)
        // Then：失败时不落库、不发 Done
        assertTrue(chat.savedMessages.isEmpty())
        assertTrue(events.none { it is ChatEvent.Done })
    }

    // ---------------- 场景 5：长期记忆检索结果注入位 ----------------

    private fun assistantMessage(id: Long, content: String) =
        ChatMessage(id = id, sessionId = 1L, role = ChatRole.ASSISTANT, content = content, replyToId = 1L)

    @Test
    fun memoryReference_insertedRightBeforeLastUserTurn_withLeadingBytesUntouched() = runTest {
        val history = listOf(
            userMessage(id = 1L, content = "你好"),
            assistantMessage(id = 2L, content = "回答"),
            userMessage(id = 3L, content = "继续写")
        )
        val memory = "【记忆检索】相关片段"

        // 不勾选时的请求（作为前缀字节基线）
        val baseChat = FakeChatGateway(messages = history)
        val baseModel = FakeModelGateway(result = AiResult.ok("好的"))
        SendChatMessageUseCase(baseChat, baseModel)(request(replyToUserId = 3L, historyEndId = 3L)).toList()
        val baseline = baseModel.lastRequest!!.turns

        // 勾选时的请求
        val chat = FakeChatGateway(messages = history)
        val model = FakeModelGateway(result = AiResult.ok("好的"))
        SendChatMessageUseCase(chat, model)(
            request(replyToUserId = 3L, historyEndId = 3L).copy(memoryReference = memory)
        ).toList()
        val turns = model.lastRequest!!.turns

        // 临时 SYSTEM turn 落在「最后一个 user turn」之前，最后一条仍是本轮用户消息
        assertEquals(4, turns.size)
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.SYSTEM, ChatRole.USER),
            turns.map { it.role }
        )
        assertEquals(memory, turns[2].content)
        assertEquals("继续写", turns.last().content)
        // 检索结果之前的所有轮次与不勾选时逐字节一致（prompt cache 不受影响）
        assertEquals(baseline.take(2), turns.take(2))
    }

    @Test
    fun memoryReference_appendedBeforeContinueTurnWhenHistoryHasNoUser() = runTest {
        val chat = FakeChatGateway(messages = listOf(assistantMessage(id = 1L, content = "上文回答")))
        val model = FakeModelGateway(result = AiResult.ok("好的"))
        SendChatMessageUseCase(chat, model)(
            request(replyToUserId = 0L, historyEndId = 1L).copy(memoryReference = "【记忆检索】片段")
        ).toList()

        val turns = model.lastRequest!!.turns
        assertEquals(listOf(ChatRole.ASSISTANT, ChatRole.SYSTEM, ChatRole.USER), turns.map { it.role })
        assertEquals("【记忆检索】片段", turns[1].content)
        assertEquals("请基于以上内容继续往下写。", turns.last().content)
    }

    @Test
    fun blankMemoryReference_doesNotInsertTurn() = runTest {
        val chat = FakeChatGateway(messages = listOf(userMessage(id = 1L, content = "你好")))
        val model = FakeModelGateway(result = AiResult.ok("好的"))
        SendChatMessageUseCase(chat, model)(
            request(replyToUserId = 1L, historyEndId = 1L).copy(memoryReference = "")
        ).toList()

        val turns = model.lastRequest!!.turns
        assertEquals(listOf(ChatRole.USER), turns.map { it.role })
    }
}
