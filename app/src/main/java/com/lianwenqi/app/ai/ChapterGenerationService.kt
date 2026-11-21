package com.lianwenqi.app.ai

import kotlinx.coroutines.CancellationException

/** 一次生成请求的原始结果（未做字数兜底，供需要自行解析的调用方使用） */
data class ChapterCompletion(
    val text: String = "",
    val promptTokens: Int = PROMPT_TOKENS_UNKNOWN,
    val completionTokens: Int = 0,
    /** null 表示服务端未提供缓存统计 */
    val cachedTokens: Int? = null,
    val errorMessage: String = ""
) {
    val success: Boolean get() = errorMessage.isBlank() && text.isNotBlank()
}

/** 已按字数约束处理过的章节生成结果 */
data class ChapterGenerationResult(
    val text: String = "",
    val over: Boolean = false,
    val under: Boolean = false,
    val promptTokens: Int = PROMPT_TOKENS_UNKNOWN,
    val completionTokens: Int = 0,
    val cachedTokens: Int? = null,
    val errorMessage: String = ""
) {
    val success: Boolean get() = errorMessage.isBlank() && text.isNotBlank()
}

/**
 * 章节梗概 / 正文生成的共享入口。
 *
 * 普通 AI 助手与 Agent 工具都通过这里发起模型请求并使用同一套提示词构造、
 * 字数兜底与错误文案，避免两条生成路径行为漂移。
 * 服务不读写聊天会话，也不落库；调用方决定结果如何展示、保存或写入。
 */
class ChapterGenerationService(
    private val sendToModel: suspend (
        modelId: Long,
        request: AiRequest,
        onDelta: ((String, String) -> Unit)?
    ) -> AiResult
) {

    /** 执行一次已组装好的生成请求；只做模型调用与空结果判定 */
    suspend fun complete(
        modelId: Long,
        turns: List<ChatTurn>,
        onDelta: ((String, String) -> Unit)? = null
    ): ChapterCompletion {
        if (modelId <= 0L) {
            return ChapterCompletion(errorMessage = "请先在设置中配置并选择一个模型")
        }
        val result = try {
            sendToModel(
                modelId,
                AiRequest(turns = turns, model = "", temperature = 0f, maxTokens = 0),
                onDelta
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return ChapterCompletion(errorMessage = e.message ?: "生成失败")
        }
        if (!result.success) {
            return ChapterCompletion(errorMessage = result.error?.userMessage ?: "生成失败")
        }
        val text = result.content.trim()
        if (text.isBlank()) return ChapterCompletion(errorMessage = "模型返回空内容")
        return ChapterCompletion(
            text = text,
            promptTokens = result.storedPromptTokens,
            completionTokens = result.completionTokens,
            cachedTokens = result.cachedTokens
        )
    }

    /** 生成章节梗概草稿：提示词与普通生成共用 [buildSummaryTurns] */
    suspend fun generateSynopsis(
        modelId: Long,
        chapterTitle: String,
        instruction: String,
        range: IntRange?,
        styleBlock: String = "",
        referenceBlock: String = "",
        /** 非空时走流式请求，可用于实时显示或保持长连接 */
        onDelta: ((String, String) -> Unit)? = null
    ): ChapterGenerationResult {
        val turns = buildGenerationPrefixTurns(
            customPrefix = "",
            reference = "",
            styleBlock = styleBlock
        ) + buildSummaryTurns(
            input = instruction,
            range = range,
            referenceBlock = referenceBlock,
            chapterTitle = chapterTitle
        )
        val completion = complete(modelId, turns, onDelta)
        if (!completion.success) {
            return ChapterGenerationResult(errorMessage = completion.errorMessage)
        }
        val enforced = enforceWordRange(completion.text, range)
        return ChapterGenerationResult(
            text = enforced.text,
            over = enforced.over,
            under = enforced.under,
            promptTokens = completion.promptTokens,
            completionTokens = completion.completionTokens,
            cachedTokens = completion.cachedTokens
        )
    }

    /** 生成章节正文草稿：提示词与普通生成共用 [buildContentTurns] */
    suspend fun generateContent(
        modelId: Long,
        chapterTitle: String,
        instruction: String,
        summary: String,
        outlineBlock: String,
        memoryBlock: String,
        range: IntRange?,
        styleBlock: String = "",
        /** 非空时走流式请求，可用于实时显示或保持长连接 */
        onDelta: ((String, String) -> Unit)? = null
    ): ChapterGenerationResult {
        val turns = buildGenerationPrefixTurns(
            customPrefix = "",
            reference = "",
            styleBlock = styleBlock
        ) + buildContentTurns(
            summary = summary,
            outlineSlot = outlineBlock,
            memoryBlock = memoryBlock,
            userInput = instruction,
            range = range,
            chapterTitle = chapterTitle
        )
        val completion = complete(modelId, turns, onDelta)
        if (!completion.success) {
            return ChapterGenerationResult(errorMessage = completion.errorMessage)
        }
        val enforced = enforceWordRange(completion.text, range)
        return ChapterGenerationResult(
            text = enforced.text,
            over = enforced.over,
            under = enforced.under,
            promptTokens = completion.promptTokens,
            completionTokens = completion.completionTokens,
            cachedTokens = completion.cachedTokens
        )
    }
}
