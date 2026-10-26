package com.lianwenqi.app.data.style

import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.ConfigRepository
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.StyleProfile
import com.lianwenqi.app.data.entity.StyleSourceType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次成功分析、尚未保存为档案的草稿 */
data class StyleAnalysisDraft(
    val analysis: StyleAnalysis,
    val analysisJson: String,
    val sourceMeta: String,
    val sourceType: StyleSourceType,
    /** 档案字段 modelName：模型展示名（别名优先） */
    val modelName: String,
    /** 实际请求使用的模型标识（供应商侧 model 名称） */
    val modelIdentifier: String,
    val sampling: StyleSamplePlan
)

sealed interface StyleAnalysisOutcome {
    data class Success(val draft: StyleAnalysisDraft) : StyleAnalysisOutcome

    /** 调用 / 配置 / 网络 / 鉴权 / 超时 / 空响应等失败 */
    data class Failed(val message: String) : StyleAnalysisOutcome

    /** 模型有返回，但不符合结构化协议 */
    data class InvalidResult(val message: String) : StyleAnalysisOutcome
}

/**
 * 文风分析编排：复用项目现有模型配置与 [AiClient] 调用，不另建 API 客户端。
 *
 * 依赖以挂起函数注入，便于在单元测试中用 fake 验证「调用 → 校验」链路，
 * 而生产环境由 [ConfigRepository] 与 [AiClient] 组装。
 */
class StyleAnalysisService(
    private val modelLoader: suspend (Long) -> AiModel?,
    private val endpointLoader: suspend (Long) -> ModelEndpoint?,
    private val chat: suspend (ModelEndpoint, List<ChatTurn>) -> AiResult,
    private val budgetChars: Int = StyleSampleSampler.DEFAULT_BUDGET_CHARS
) {

    constructor(config: ConfigRepository, aiClient: AiClient) : this(
        modelLoader = { config.getModel(it) },
        endpointLoader = { config.endpointFor(it) },
        chat = { endpoint, turns -> aiClient.chat(endpoint, turns) }
    )

    suspend fun analyze(modelId: Long, samples: List<StyleSampleInput>): StyleAnalysisOutcome {
        val usable = samples.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return StyleAnalysisOutcome.Failed("没有可用于分析的样本")
        val model = modelLoader(modelId)
            ?: return StyleAnalysisOutcome.Failed("未找到所选模型，请先在设置中检查模型配置")
        val endpoint = endpointLoader(modelId)
            ?: return StyleAnalysisOutcome.Failed("模型配置不完整，请先检查供应商与模型设置")

        val plan = StyleSampleSampler.plan(usable, budgetChars)
        val requestEndpoint = endpoint.copy(
            // 分析任务使用独立提示词，避免模型预设的系统提示词改变分析目标
            systemPrompt = StyleAnalysisPrompt.SYSTEM,
            temperature = ANALYSIS_TEMPERATURE,
            maxTokens = outputBudget(endpoint.maxTokens)
        )
        val turns = listOf(ChatTurn(ChatRole.USER, StyleAnalysisPrompt.userContent(plan)))

        val result = chat(requestEndpoint, turns)
        if (!result.success) {
            return StyleAnalysisOutcome.Failed(result.error?.userMessage ?: "模型调用失败")
        }
        if (result.content.isBlank()) {
            return StyleAnalysisOutcome.Failed("模型返回为空，请重试")
        }
        val parsed = StyleAnalysisCodec.parse(result.content)
        if (parsed is StyleAnalysisParseResult.Failure) {
            return StyleAnalysisOutcome.InvalidResult(parsed.message)
        }
        val success = parsed as StyleAnalysisParseResult.Success

        return StyleAnalysisOutcome.Success(
            StyleAnalysisDraft(
                analysis = success.analysis,
                analysisJson = success.json,
                sourceMeta = buildSourceMeta(plan, model),
                sourceType = if (
                    usable.size == 1 &&
                    usable.first().format in setOf(StyleSampleFormat.EPUB, StyleSampleFormat.DOCX)
                ) {
                    StyleSourceType.WHOLE_BOOK
                } else {
                    StyleSourceType.CHAPTERS
                },
                modelName = model.displayName,
                modelIdentifier = model.name,
                sampling = plan
            )
        )
    }

    /** <=0 表示模型配置为不限输出；否则保证至少 [MIN_OUTPUT_TOKENS] 以容纳完整 JSON */
    private fun outputBudget(maxTokens: Int): Int =
        if (maxTokens <= 0) 0 else maxOf(maxTokens, MIN_OUTPUT_TOKENS)

    /**
     * 档案元信息：只记录样本名称、字符数、抽样策略与模型标识，不写入任何样本文本。
     * 文案/字段总量远低于 [StyleProfile.MAX_SOURCE_META_BYTES]。
     */
    private fun buildSourceMeta(plan: StyleSamplePlan, model: AiModel): String = buildJsonObject {
        put("schemaVersion", STYLE_ANALYSIS_SCHEMA_VERSION)
        put(
            "sampling",
            buildJsonObject {
                put("strategy", plan.strategy.name)
                put("budgetChars", plan.budgetChars)
                put("totalSamples", plan.totalSampleCount)
                put("analyzedSamples", plan.analyzedSampleCount)
                put("totalChars", plan.totalChars)
                put("includedChars", plan.includedChars)
            }
        )
        put(
            "model",
            buildJsonObject {
                put("id", model.id)
                put("identifier", model.name)
                put("displayName", model.displayName)
            }
        )
        put(
            "samples",
            JsonArray(
                plan.blocks.map { block ->
                    buildJsonObject {
                        put("name", block.fileName.take(MAX_META_NAME_CHARS))
                        put("format", block.format.name)
                        put("chars", block.totalChars)
                        put("includedChars", block.text.length)
                        put("mode", block.mode)
                    }
                }
            )
        )
    }.toString()

    private companion object {
        const val ANALYSIS_TEMPERATURE = 0.3f
        const val MIN_OUTPUT_TOKENS = 4096
        const val MAX_META_NAME_CHARS = 80
    }
}
