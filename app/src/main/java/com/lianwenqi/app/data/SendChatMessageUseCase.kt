package com.lianwenqi.app.data

import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.PRIOR_CONTEXT_HEADER
import com.lianwenqi.app.ai.buildGenerationPrefixTurns
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.ContextSnapshot
import com.lianwenqi.app.util.AI_IMPORT_CHAPTER_TOOL_SPEC
import com.lianwenqi.app.util.AI_IMPORT_OUTLINE_TOOL_SPEC
import com.lianwenqi.app.util.AI_TOOL_IMPORT_CHAPTER_NAME
import com.lianwenqi.app.util.AI_TOOL_IMPORT_OUTLINE_NAME
import com.lianwenqi.app.util.AiChapterBlock
import com.lianwenqi.app.util.AiOutlineBlock
import com.lianwenqi.app.util.parseAiChapterBlocksJson
import com.lianwenqi.app.util.parseAiChapterBlocksResult
import com.lianwenqi.app.util.parseAiOutlineBlocksJson
import com.lianwenqi.app.util.parseAiOutlineBlocksResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** [PromptLog.genKind] 取值：空表示普通对话，「生成梗概」为单独调用该功能产出的回答 */
const val GEN_KIND_SUMMARY = "summary"

/** 日志里的一个可展开节点（标题 + 正文） */
@Serializable
data class PromptLogNode(val title: String = "", val text: String = "")

/** 日志里的一轮历史对话 */
@Serializable
data class PromptLogRound(val user: String = "", val assistant: String = "")

/**
 * 一次发送时的「提示词日志」快照，供「查看日志 / 查看本轮提示词」弹窗回显。
 * 按「预设日志 → 上下文日志 → 本轮提示词日志」组织。
 */
@Serializable
data class PromptLog(
    /** 预设提示词：所选模型的系统提示词 */
    val systemPrompt: String = "",
    /** 自定义前缀：会话建立时冻结下来的自定义前缀 */
    val customPrefix: String = "",
    /** 预设里勾选的大纲清单（每项可单独展开） */
    val outlines: List<PromptLogNode> = emptyList(),
    /** 命中缓存的历史轮次（第一轮、第二轮…） */
    val rounds: List<PromptLogRound> = emptyList(),
    /** 本轮「＋本章梗概」附带内容 */
    val summary: String = "",
    /** 本轮长期记忆检索到的参考资料 */
    val memory: String = "",
    /** 本轮「适配性筛选」新命中的大纲参考资料 */
    val autoOutline: String = "",
    /** 本轮用户输入的提示词 */
    val userPrompt: String = "",
    /** 本轮实际发送的完整提示词（按角色顺序拼接） */
    val fullPrompt: String = "",
    /** 本轮请求实际携带的工具名（空表示普通对话，不带 tools） */
    val tools: List<String> = emptyList(),
    /** 模型原始响应的摘要（前 500 字），用于排查「工具调用写成正文」一类问题 */
    val rawResponse: String = "",
    /**
     * 本轮产出的类型标记：空表示普通对话；[GEN_KIND_SUMMARY] 表示单独「生成梗概」产出的回答，
     * 操作栏据此显示「写入梗概」图标（回答只有点该图标才写回本章，不再自动写回）。
     */
    val genKind: String = ""
) {
    fun encode(): String = promptLogJson.encodeToString(serializer(), this)

    companion object {
        /** 解析 [ChatMessage.promptLog]；空值或损坏时返回 null */
        fun decode(raw: String): PromptLog? {
            if (raw.isBlank()) return null
            return runCatching { promptLogJson.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}

private val promptLogJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** ViewModel 侧提供的本轮日志素材（UseCase 无法自行取得的部分） */
data class PromptLogContext(
    /** 预设里勾选的大纲清单 */
    val outlines: List<PromptLogNode> = emptyList(),
    val summary: String = "",
    val memory: String = "",
    val autoOutline: String = ""
)

/**
 * 「发送 AI 消息」所需的一次性输入快照。
 * 全部为值类型，UseCase 自身不持有任何 UI 状态。
 */
data class SendChatRequest(
    val sessionId: Long,
    val modelId: Long,
    val systemPrompt: String,
    /** 发送时 ViewModel 当前的上下文选择（书籍 / 章节 / 大纲 + 梗概 / 正文开关） */
    val selection: ContextSnapshot,
    /** 该回答归属的用户消息 id（0 表示不归属于任何用户消息） */
    val replyToUserId: Long,
    /** 历史截止到哪条消息（只取该 id 之前的上下文） */
    val historyEndId: Long,
    val variantIndex: Int,
    /** 「写入大纲工具」当前是否生效 */
    val writeOutlineTool: Boolean,
    /** 「章节梗概导入工具」当前是否生效 */
    val chapterImportTool: Boolean,
    /** 会话级「文风约束块」（冻结在会话前缀首块），为空则不发送 */
    val stylePrefix: String = "",
    /** 会话级的「自定义前缀」（纯文本），为空则不发送 */
    val prefixText: String = "",
    /** 会话已冻结的「参考资料前缀」（书籍/大纲/章节字面文本），为空则不发送 */
    val referencePrefix: String = "",
    /** 上一轮之前的全部轮次，渲染成与生成路径相同的「前情提要」块；为空则不带历史 */
    val priorContext: String = "",
    /** 本轮对话所属章节 id（0 表示未绑定章节），随消息落库用于按章节分组收起 */
    val chapterId: Long = 0,
    /** 本轮日志素材（本章梗概 / 检索片段 / 新增大纲资料 / 预设大纲清单） */
    val logContext: PromptLogContext = PromptLogContext()
)

/** 流式生成过程中对外吐出的事件 */
sealed class ChatEvent {
    /** 回答正文的流式增量片段 */
    data class Chunk(val text: String) : ChatEvent()

    /** 深度思考内容的流式增量片段 */
    data class ReasoningChunk(val text: String) : ChatEvent()

    /** 回答里解析出的可导入大纲区块（按「写入大纲工具」） */
    data class OutlineBlocksReady(val blocks: List<AiOutlineBlock>) : ChatEvent()

    /** 回答里解析出的可导入章节梗概区块（按「章节梗概导入工具」） */
    data class ChapterBlocksReady(val blocks: List<AiChapterBlock>) : ChatEvent()

    /** 生成完成（助手消息已写库） */
    object Done : ChatEvent()

    /** 当前模型不支持工具调用（已自动重试无 tools 请求），提示用户改用回答上的「导入」 */
    data class ToolFallback(val message: String) : ChatEvent()

    /** 生成出错 */
    data class Error(val message: String) : ChatEvent()
}

/**
 * 「发送 AI 消息」用例所需的对话侧能力（由 ChatRepository 实现；单元测试可手写 fake）。
 */
interface ChatGateway {
    suspend fun getMessages(sessionId: Long): List<ChatMessage>

    suspend fun buildContext(
        bookIds: List<Long>,
        chapterIds: List<Long>,
        outlineIds: List<Long>,
        contentChapterIds: Set<Long>,
        summaryChapterIds: Set<Long>
    ): String

    suspend fun deactivateVariants(replyToId: Long)

    suspend fun addMessage(
        sessionId: Long,
        role: ChatRole,
        content: String,
        replyToId: Long = 0,
        variantIndex: Int = 0,
        reasoning: String = "",
        contextSnapshot: String = "",
        promptExtra: String = "",
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        cachedTokens: Int = 0,
        chapterId: Long = 0,
        promptLog: String = ""
    ): Long
}

/**
 * 「发送 AI 消息」用例所需的模型侧能力（由 ConfigRepository 实现；单元测试可手写 fake）。
 */
interface ModelGateway {
    suspend fun getModel(id: Long): AiModel?

    suspend fun sendToModel(
        modelId: Long,
        request: AiRequest,
        onDelta: ((String, String) -> Unit)? = null
    ): AiResult
}

/**
 * 「发送 AI 消息」用例：组装上下文与历史 → 流式请求模型 → 写回助手消息 → 解析工具区块。
 *
 * 仅搬动原先 AiChatViewModel.generate(...) 的逻辑，行为保持不变：
 * 助手消息的落库、工具区块的解析与「入队」都在这里完成（真正写库仍由用户在弹窗确认时触发）。
 */
class SendChatMessageUseCase(
    private val chatRepository: ChatGateway,
    private val configRepository: ModelGateway
) {
    operator fun invoke(request: SendChatRequest): Flow<ChatEvent> = channelFlow {
        val allMessages = chatRepository.getMessages(request.sessionId)
        val sel = request.selection
        // 本轮实时上下文：未被「前缀化」的部分（前缀化开启时对应项为空，由会话前缀承载）
        val context = chatRepository.buildContext(
            bookIds = sel.bookIds,
            chapterIds = sel.chapterIds,
            outlineIds = sel.outlineIds,
            contentChapterIds = sel.contentChapterIds.toSet(),
            summaryChapterIds = sel.summaryChapterIds.toSet()
        )
        // 会话前缀（字面文本，跨轮逐字节稳定）拼在实时上下文前面，共同作为「参考资料」
        val reference = listOf(request.referencePrefix, context)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        // 只取该用户消息之前的上下文，避免把同组的其他回答版本带进历史。
        // 0 表示不限：携带全部历史，长对话下前缀更稳定、缓存命中率更高
        val model = configRepository.getModel(request.modelId)
        val maxContext = model?.maxContext ?: 20
        // 实际生效的系统提示词：模型配置里的「预设提示词」优先（真正随请求发出），
        // 仅当模型未配置预设时才回退到请求里带的自定义系统提示词。
        // 日志必须记录这个「实际值」，否则「预设提示词」一栏会永远为空。
        val effectiveSystemPrompt = model?.systemPrompt?.takeIf { it.isNotBlank() } ?: request.systemPrompt
        val history = allMessages
            .filter { it.id <= request.historyEndId }
            .filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }
            // 同一轮用户消息下只保留「当前生效」的那一条回答：多次「重新生成」产生的废弃候选
            // 不进后续请求的上下文，否则它们会并列堆积在历史里，造成 token 线性膨胀。
            .filter { it.role == ChatRole.USER || it.active }
            .let { if (maxContext > 0) it.takeLast(maxContext) else it }
            // 拼回这条消息隐藏的附加提示词（如「＋本章梗概」「长期记忆检索片段」），保证与当初发送的字节一致。
            // 顺序上附加提示词放在用户指令**之前**：让「用户的要求」始终是本轮最后一句话，
            // 否则参考资料（尤其检索到的本书正文片段）会被模型当成要续写的内容，直接把片段原样吐回来。
            .map {
                val extra = it.promptExtra
                ChatTurn(it.role, if (extra.isBlank()) it.content else extra + "\n\n" + it.content)
            }
        val turns = buildList {
            // 「自定义前缀 / 工具提示 / 参考资料前缀」与生成路径共用同一段只读前缀
            // （见 buildGenerationPrefixTurns），保证两种请求逐字节一致
            addAll(
                buildGenerationPrefixTurns(
                    customPrefix = request.prefixText,
                    reference = reference,
                    styleBlock = request.stylePrefix
                )
            )
            // 历史统一压成一个 System「前情提要」块，与生成路径同构：
            // 聊天与生成交替使用时，前缀之后的内容也是「只追加」的，
            // 切换功能不会再让整段历史重新计费。
            if (request.priorContext.isNotBlank()) {
                add(ChatTurn(ChatRole.SYSTEM, PRIOR_CONTEXT_HEADER + request.priorContext))
            }
            // 最后一轮始终是真实的用户输入；历史以助手消息结尾时（编辑回答后继续）补兜底指令
            val last = history.lastOrNull()
            if (last != null && last.role == ChatRole.USER) {
                add(last)
            } else {
                add(ChatTurn(ChatRole.USER, "请基于以上内容继续往下写。"))
            }
        }
        // 工具定义只随聊天请求提供；生成正文 / 梗概路径不携带 tools
        val tools = buildList {
            if (request.writeOutlineTool) add(AI_IMPORT_OUTLINE_TOOL_SPEC)
            if (request.chapterImportTool) add(AI_IMPORT_CHAPTER_TOOL_SPEC)
        }
        // 模型请求可能抛出异常（如连接/解析异常）：统一兜底成失败结果，
        // 保证任何情况下都只会向 UI 吐 ChatEvent.Error，而不是让异常冲出 Flow 导致崩溃。
        val result = try {
            configRepository.sendToModel(
                request.modelId,
                AiRequest(
                    turns = turns,
                    model = "",
                    temperature = 0f,
                    maxTokens = 0,
                    systemPrompt = request.systemPrompt,
                    tools = tools
                )
            ) { dc, dr ->
                if (dc.isNotEmpty()) trySend(ChatEvent.Chunk(dc))
                if (dr.isNotEmpty()) trySend(ChatEvent.ReasoningChunk(dr))
            }
        } catch (e: CancellationException) {
            // 「停止响应」触发的取消需要原样抛出，不能当成请求失败
            throw e
        } catch (e: Throwable) {
            AiResult.fail(AiError.fromThrowable(e))
        }
        if (result.success) {
            // 新版本插入前先清除旧版本的展示标记
            if (request.replyToUserId != 0L) chatRepository.deactivateVariants(request.replyToUserId)
            // 模型只发起工具调用、没输出正文时补一句占位说明，避免对话里出现空白气泡
            val storedContent = result.content.ifBlank {
                if (result.toolCalls.isNotEmpty()) "（本轮已生成可导入内容，请在导入确认框中选择要写入的条目）" else ""
            }
            val responseDigest = buildString {
                append(result.content.take(500))
                if (result.toolCalls.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append("[工具调用] ")
                    append(result.toolCalls.joinToString("；") { "${it.name}：${it.arguments.take(120)}" })
                }
            }
            chatRepository.addMessage(
                request.sessionId, ChatRole.ASSISTANT, storedContent,
                replyToId = request.replyToUserId,
                variantIndex = request.variantIndex,
                reasoning = result.reasoning,
                promptTokens = result.promptTokens,
                completionTokens = result.completionTokens,
                cachedTokens = result.cachedTokens,
                chapterId = request.chapterId,
                promptLog = buildPromptLog(
                    request, turns, history, allMessages, effectiveSystemPrompt,
                    toolNames = tools.map { it.name },
                    rawResponse = responseDigest
                ).encode()
            )
            if (result.toolsUnsupported) {
                trySend(ChatEvent.ToolFallback("当前模型不支持工具调用，可在回答上点「导入」手动导入"))
            } else if (result.leakedToolSyntax && result.toolCalls.isEmpty()) {
                // 正文里有工具调用语法却没能还原：明确告知，避免再次静默失败
                trySend(ChatEvent.ToolFallback("模型把工具调用写成了正文且没能解析，可在回答上点「导入」手动导入"))
            }
            // AI 工具：优先解析模型发起的工具调用；没有工具调用时再兼容旧标记块。
            // 若工具参数存在却无法解析（畸形 JSON），上报 Error，让用户知道工具没生效。
            var toolError: String? = null
            if (request.writeOutlineTool) {
                val calls = result.toolCalls.filter { it.name == AI_TOOL_IMPORT_OUTLINE_NAME }
                if (calls.isNotEmpty()) {
                    val blocks = mutableListOf<AiOutlineBlock>()
                    var bad = false
                    calls.forEach { call ->
                        val parsed = parseAiOutlineBlocksJson(call.arguments)
                        if (parsed == null) bad = true else blocks += parsed
                    }
                    if (blocks.isNotEmpty()) send(ChatEvent.OutlineBlocksReady(blocks))
                    if (bad) toolError = "AI 返回的导入数据格式有误，可在回答上点「导入」重试"
                } else {
                    val parsed = parseAiOutlineBlocksResult(result.content)
                    when {
                        parsed.blocks.isNotEmpty() -> send(ChatEvent.OutlineBlocksReady(parsed.blocks))
                        parsed.malformed -> toolError = "AI 返回的大纲内容格式有误，无法解析，请重试"
                    }
                }
            }
            if (request.chapterImportTool) {
                val calls = result.toolCalls.filter { it.name == AI_TOOL_IMPORT_CHAPTER_NAME }
                if (calls.isNotEmpty()) {
                    val blocks = mutableListOf<AiChapterBlock>()
                    var bad = false
                    calls.forEach { call ->
                        val parsed = parseAiChapterBlocksJson(call.arguments)
                        if (parsed == null) bad = true else blocks += parsed
                    }
                    if (blocks.isNotEmpty()) send(ChatEvent.ChapterBlocksReady(blocks))
                    if (bad) toolError = toolError ?: "AI 返回的导入数据格式有误，可在回答上点「导入」重试"
                } else {
                    val parsed = parseAiChapterBlocksResult(result.content)
                    when {
                        parsed.blocks.isNotEmpty() -> send(ChatEvent.ChapterBlocksReady(parsed.blocks))
                        parsed.malformed && toolError == null ->
                            toolError = "AI 返回的章节梗概内容格式有误，无法解析，请重试"
                    }
                }
            }
            if (toolError != null) send(ChatEvent.Error(toolError)) else send(ChatEvent.Done)
        } else {
            // 按 AiError 类型给出对应提示（鉴权 / 超时 / 网络 / 服务器错误…）
            send(ChatEvent.Error(result.error?.userMessage ?: "请求失败"))
        }
    }.buffer(Channel.UNLIMITED)

    /**
     * 组装本轮「提交日志」快照：预设 / 上下文 / 本轮新增三部分，
     * 供聊天页「查看日志 / 查看本轮提示词」弹窗回显。
     */
    private fun buildPromptLog(
        request: SendChatRequest,
        turns: List<ChatTurn>,
        history: List<ChatTurn>,
        allMessages: List<ChatMessage>,
        systemPrompt: String,
        toolNames: List<String> = emptyList(),
        rawResponse: String = ""
    ): PromptLog {
        // 历史轮次：一条用户发言 + 紧随其后的助手回答算作一轮
        val rounds = mutableListOf<PromptLogRound>()
        var pendingUser: String? = null
        history.forEach { turn ->
            when (turn.role) {
                ChatRole.USER -> {
                    pendingUser?.let { rounds += PromptLogRound(it, "") }
                    pendingUser = turn.content
                }

                ChatRole.ASSISTANT -> {
                    rounds += PromptLogRound(pendingUser.orEmpty(), turn.content)
                    pendingUser = null
                }

                else -> Unit
            }
        }
        pendingUser?.let { rounds += PromptLogRound(it, "") }
        return PromptLog(
            systemPrompt = systemPrompt,
            customPrefix = request.prefixText,
            outlines = request.logContext.outlines,
            rounds = rounds,
            summary = request.logContext.summary,
            memory = request.logContext.memory,
            autoOutline = request.logContext.autoOutline,
            userPrompt = allMessages.firstOrNull { it.id == request.replyToUserId }?.content.orEmpty(),
            // 「本轮提示词」只回显本轮真正发给 AI 的那部分（含本章梗概 / 检索片段 / 新增大纲资料），
            // 即请求里最后一条用户发言；预设提示词、自定义前缀与历史轮次属于命中缓存的前缀，不在此重复展示
            fullPrompt = turns.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty(),
            tools = toolNames,
            rawResponse = rawResponse
        )
    }
}
