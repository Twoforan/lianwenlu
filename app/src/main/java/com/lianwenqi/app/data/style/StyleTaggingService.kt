package com.lianwenqi.app.data.style

import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.ConfigRepository
import com.lianwenqi.app.data.StyleTagRepository
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.StyleSampleChunk
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.SummaryTagSource
import com.lianwenqi.app.util.shortHash
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 一次样本标签分析的结果 */
sealed interface StyleTaggingOutcome {
    data class Success(
        val sampleId: Long,
        val profileId: Long,
        /** 每个片段最终落库的标签 id */
        val chunkTags: Map<Long, List<Long>>,
        /** 本次 AI 新建的档案标签 id（已存在复用的不计入） */
        val createdTagIds: List<Long>,
        val analyzedChunks: Int,
        val json: String
    ) : StyleTaggingOutcome

    /** 调用 / 网络 / 空响应等失败；已成功的批次会保留，不破坏已有数据 */
    data class Failed(val message: String) : StyleTaggingOutcome

    /** 模型返回不符合结构化协议 */
    data class InvalidResult(val message: String) : StyleTaggingOutcome
}

/**
 * 样本片段标签分析：复用现有 [ConfigRepository] / [AiClient] 调用模型，
 * 模型只输出结构化建议，落库与权限校验全部由本服务 + [StyleTagResolver] + [StyleTagRepository] 完成。
 *
 * 依赖以挂起函数注入，便于单元测试；生产环境由 [ConfigRepository] 与 [AiClient] 组装。
 */
class StyleTaggingService(
    private val repository: StyleTagRepository,
    private val modelLoader: suspend (Long) -> com.lianwenqi.app.data.entity.AiModel?,
    private val endpointLoader: suspend (Long) -> ModelEndpoint?,
    private val chat: suspend (ModelEndpoint, List<ChatTurn>) -> AiResult,
    /** 单次请求提交的片段字符预算，避免一次请求过大 */
    private val budgetChars: Int = DEFAULT_BUDGET_CHARS
) {

    constructor(config: ConfigRepository, aiClient: AiClient, repository: StyleTagRepository) : this(
        repository = repository,
        modelLoader = { config.getModel(it) },
        endpointLoader = { config.endpointFor(it) },
        chat = { endpoint, turns -> aiClient.chat(endpoint, turns) }
    )

    /**
     * 分析样本片段并写入标签关联。
     * [selectedUserTagIds] 是本次用户选择的全局标签（为空表示本次不优先使用全局标签）。
     */
    suspend fun analyzeSample(
        modelId: Long,
        sampleId: Long,
        selectedUserTagIds: Set<Long> = emptySet()
    ): StyleTaggingOutcome {
        val sample = repository.sample(sampleId)
            ?: return StyleTaggingOutcome.Failed("样本不存在")
        val profileId = sample.styleProfileId
            ?: return StyleTaggingOutcome.Failed("样本未绑定文风档案，请先选择归属档案")
        val chunks = repository.chunksOfSample(sampleId)
        if (chunks.isEmpty()) return StyleTaggingOutcome.Failed("样本没有可分析的片段")

        val endpoint = endpointLoader(modelId)
            ?: return StyleTaggingOutcome.Failed("模型配置不完整，请先检查供应商与模型设置")
        val requestEndpoint = endpoint.copy(
            systemPrompt = StyleTagAnalysisPrompt.SYSTEM,
            temperature = ANALYSIS_TEMPERATURE,
            maxTokens = if (endpoint.maxTokens <= 0) 0 else maxOf(endpoint.maxTokens, MIN_OUTPUT_TOKENS)
        )

        val allUserTags = repository.userTags()
        // 默认把全部全局标签交给模型（第一优先级）；用户显式选择时只提交所选集合
        val selectedUserTags = if (selectedUserTagIds.isEmpty()) {
            allUserTags
        } else {
            allUserTags.filter { it.id in selectedUserTagIds }
        }
        val allowedUserTagIds: Set<Long>? = if (selectedUserTagIds.isEmpty()) {
            null
        } else {
            selectedUserTags.map { it.id }.toSet()
        }

        val createdTagIds = mutableListOf<Long>()
        val chunkTags = mutableMapOf<Long, List<Long>>()
        var lastJson = ""

        val batches = planBatches(chunks, budgetChars)
        for (batch in batches) {
            val profileTags = repository.profileTags(profileId)
            val turns = listOf(
                ChatTurn(
                    ChatRole.USER,
                    StyleTagAnalysisPrompt.userContent(batch, selectedUserTags, profileTags)
                )
            )
            val result = try {
                chat(requestEndpoint, turns)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return StyleTaggingOutcome.Failed(e.message ?: "标签分析请求失败")
            }
            if (!result.success) {
                return StyleTaggingOutcome.Failed(result.error?.userMessage ?: "标签分析失败")
            }
            val raw = result.content.trim()
            if (raw.isBlank()) return StyleTaggingOutcome.Failed("模型返回为空，请重试")
            when (val parsed = StyleTagAnalysisCodec.parse(raw)) {
                is StyleTagParseResult.Failure -> return StyleTaggingOutcome.InvalidResult(parsed.message)
                is StyleTagParseResult.Success -> {
                    lastJson = parsed.json
                    // 先为每个新标签建议落库 / 复用（createProfileTagFromAi 幂等，且绝不写全局库）
                    val createdByKey = mutableMapOf<String, Long>()
                    parsed.result.newTags.forEach { suggestion ->
                        val existing = allUserTags.firstOrNull {
                            com.lianwenqi.app.data.StyleTagRules.sameName(it.name, suggestion.name)
                        } ?: profileTags.firstOrNull {
                            com.lianwenqi.app.data.StyleTagRules.sameName(it.name, suggestion.name)
                        }
                        val id = try {
                            repository.createProfileTagFromAi(
                                profileId,
                                suggestion.category,
                                suggestion.name,
                                suggestion.description
                            )
                        } catch (e: Throwable) {
                            if (e is CancellationException) throw e
                            null
                        }
                        if (id != null) {
                            createdByKey[suggestion.key] = id
                            // 只有真正新建（两套标签库都没有同名标签）时才计入新建列表
                            if (existing == null && id !in createdTagIds) createdTagIds += id
                        }
                    }
                    val freshProfileTags = repository.profileTags(profileId)
                    val resolved = StyleTagResolver.resolve(
                        profileId = profileId,
                        suggestions = parsed.result.chunks,
                        newTags = parsed.result.newTags,
                        validChunkIds = batch.map { it.id }.toSet(),
                        userTags = allUserTags,
                        profileTags = freshProfileTags,
                        createProfileTag = { suggestion ->
                            createdByKey[suggestion.key]?.let { id -> freshProfileTags.firstOrNull { it.id == id } }
                        },
                        allowedUserTagIds = allowedUserTagIds
                    )
                    resolved.forEach { item ->
                        repository.replaceChunkTagsFromAi(item.chunkId, item.tagIds)
                        chunkTags[item.chunkId] = item.tagIds
                    }
                }
            }
        }

        return StyleTaggingOutcome.Success(
            sampleId = sampleId,
            profileId = profileId,
            chunkTags = chunkTags,
            createdTagIds = createdTagIds.distinct(),
            analyzedChunks = chunkTags.size,
            json = lastJson
        )
    }

    /** 按字符预算切批；单片段超过预算时独占一批，保证不会丢片段 */
    internal fun planBatches(chunks: List<StyleSampleChunk>, budget: Int): List<List<StyleSampleChunk>> =
        planBatchesOf(chunks, budget)

    /**
     * 梗概句子标签分析：只使用当前绑定文风档案的已有标签，返回可直接落库的句子关联。
     *
     * 未绑定档案 / 档案没有标签 / 梗概没有可切分句子时不调用模型，直接返回空标签；
     * 自动打标失败只影响标签，不影响已经生成的梗概。
     */
    suspend fun analyzeSummary(
        modelId: Long,
        summary: String,
        profileId: Long
    ): SummaryTaggingOutcome {
        val text = summary.trim()
        val hash = shortHash(text)
        if (text.isBlank()) return SummaryTaggingOutcome.Failed("梗概为空，无法分析标签")
        if (profileId <= 0L) return SummaryTaggingOutcome.Success(emptyList(), hash, "")
        val profileTags = repository.profileTags(profileId)
        if (profileTags.isEmpty()) return SummaryTaggingOutcome.Success(emptyList(), hash, "")
        val sentences = SummarySentenceSplitter.split(text)
        if (sentences.isEmpty()) return SummaryTaggingOutcome.Success(emptyList(), hash, "")
        // 打标粒度是句段：每 3 句合并为一段，模型只对段输出标签
        val segments = SummarySentenceSegmenter.segment(text, sentences)

        val endpoint = endpointLoader(modelId)
            ?: return SummaryTaggingOutcome.Failed("模型配置不完整，请先检查供应商与模型设置")
        val requestEndpoint = endpoint.copy(
            systemPrompt = SummarySentenceTagPrompt.SYSTEM,
            temperature = ANALYSIS_TEMPERATURE,
            maxTokens = if (endpoint.maxTokens <= 0) 0 else maxOf(endpoint.maxTokens, MIN_SUMMARY_OUTPUT_TOKENS)
        )
        val result = try {
            chat(
                requestEndpoint,
                listOf(ChatTurn(ChatRole.USER, SummarySentenceTagPrompt.userContent(segments, profileTags)))
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return SummaryTaggingOutcome.Failed(e.message ?: "梗概标签分析请求失败")
        }
        if (!result.success) {
            return SummaryTaggingOutcome.Failed(result.error?.userMessage ?: "梗概标签分析失败")
        }
        val raw = result.content.trim()
        if (raw.isBlank()) return SummaryTaggingOutcome.Failed("模型返回为空")
        val parsed = SummarySentenceTagCodec.parse(raw)
            ?: return SummaryTaggingOutcome.InvalidResult("返回内容不是合法的句子标签 JSON")

        val tags = SummarySentenceTagResolver.resolve(parsed, text, sentences, segments, profileTags, hash)
        return SummaryTaggingOutcome.Success(tags, hash, raw)
    }

    internal companion object {
        private const val DEFAULT_BUDGET_CHARS = 3_000
        private const val ANALYSIS_TEMPERATURE = 0.2f
        private const val MIN_OUTPUT_TOKENS = 2048
        private const val MIN_SUMMARY_OUTPUT_TOKENS = 1024

        /** 纯函数切批：便于单元测试，不依赖 Room / 网络 */
        internal fun planBatchesOf(
            chunks: List<StyleSampleChunk>,
            budget: Int
        ): List<List<StyleSampleChunk>> {
            val safeBudget = budget.coerceAtLeast(1)
            val batches = mutableListOf<List<StyleSampleChunk>>()
            var current = mutableListOf<StyleSampleChunk>()
            var currentChars = 0
            chunks.forEach { chunk ->
                val len = chunk.text.length
                if (current.isNotEmpty() && currentChars + len > safeBudget) {
                    batches += current
                    current = mutableListOf()
                    currentChars = 0
                }
                current += chunk
                currentChars += len
            }
            if (current.isNotEmpty()) batches += current
            return batches
        }
    }
}

sealed interface SummaryTaggingOutcome {
    data class Success(
        val tags: List<ChapterSummaryTag>,
        val summaryHash: String,
        val json: String
    ) : SummaryTaggingOutcome

    data class Failed(val message: String) : SummaryTaggingOutcome

    data class InvalidResult(val message: String) : SummaryTaggingOutcome
}

/** 单个梗概标签条目 */
data class SummaryTagItem(val id: Long?, val category: String, val name: String)

object SummaryTagCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): List<SummaryTagItem>? {
        val candidate = StyleTagAnalysisCodec.extractJson(raw) ?: return null
        val root = runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull() ?: return null
        val array = root["tags"] as? JsonArray ?: return null
        return parseItems(array)
    }

    fun parseItems(array: JsonArray): List<SummaryTagItem> = array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.longOrNull
            ?: (obj["tagId"] as? JsonPrimitive)?.longOrNull
        val category = (obj["category"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (name.isBlank() && id == null) return@mapNotNull null
        SummaryTagItem(id, category, name)
    }
}

/** 一次结构化输出：梗概正文 + 句子标签关联 */
data class SummaryWithTags(
    val synopsis: String,
    val tags: List<SummaryTagItem>,
    val sentences: List<SummarySentenceTagItem>,
    val json: String
)

/**
 * 解析 `{"synopsis":"…","sentences":[…]}`（兼容旧的 `tags` 字段）。
 * 解析失败返回 null，调用方必须把原始文本当梗概使用，绝不因标签解析失败丢失梗概正文。
 */
object SummaryWithTagsCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): SummaryWithTags? {
        val candidate = StyleTagAnalysisCodec.extractJson(raw) ?: return null
        val root = runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull() ?: return null
        val synopsis = (root["synopsis"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (synopsis.isBlank()) return null
        val tags = (root["tags"] as? JsonArray)?.let { SummaryTagCodec.parseItems(it) } ?: emptyList()
        val sentences = SummarySentenceTagCodec.parse(candidate) ?: emptyList()
        return SummaryWithTags(synopsis, tags, sentences, candidate)
    }
}
