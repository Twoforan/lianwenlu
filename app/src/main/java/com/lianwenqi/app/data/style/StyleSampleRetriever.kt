package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.StyleTagRepository
import com.lianwenqi.app.data.dao.StyleChunkTagRow
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.StyleSampleChunk
import com.lianwenqi.app.util.cosineSimilarity
import com.lianwenqi.app.util.decodeVector
import kotlinx.coroutines.CancellationException

/** 一个命中的文风样本片段（只用于本次正文生成，不写回任何长期数据） */
data class StyleSampleHit(
    val chunkId: Long,
    val sampleId: Long,
    val chunkIndex: Int,
    val text: String,
    /** 「分类：名称」形式的标签，供提示词说明来源 */
    val tagLabels: List<String>,
    val tagScore: Double,
    val vectorScore: Float?,
    val score: Double
)

/** 一次文风样本检索的结果与可追踪信息 */
data class StyleRetrievalResult(
    val hits: List<StyleSampleHit> = emptyList(),
    val profileId: Long = 0L,
    val candidateChunks: Int = 0,
    val vectorUsed: Boolean = false,
    /** 没有结果 / 失败时的原因，用于日志与界面简要提示 */
    val reason: String = ""
)

/**
 * 正文生成前的文风样本检索：
 * 1. `styleProfileId` 是硬性过滤条件，候选只能来自当前书籍绑定的文风档案；
 * 2. 标签匹配负责控制与引导候选范围（不要求全部标签匹配）；
 * 3. 复用现有 [com.lianwenqi.app.ai.EmbeddingGateway] 的向量做余弦排序；
 * 4. 最多返回 1～2 条，不足不补足、不跨档案借用；失败时返回空结果，不阻断正文生成。
 */
class StyleSampleRetriever internal constructor(
    private val loadChunks: suspend (Long) -> List<StyleSampleChunk>,
    private val loadTagRows: suspend (Long) -> Map<Long, List<StyleChunkTagRow>>,
    private val embed: suspend (List<String>) -> Result<List<FloatArray>>
) {

    constructor(
        repository: StyleTagRepository,
        embed: suspend (List<String>) -> Result<List<FloatArray>>
    ) : this(
        loadChunks = { repository.chunksOfProfile(it) },
        loadTagRows = { repository.chunkTagsOfProfile(it) },
        embed = embed
    )

    suspend fun retrieve(
        profileId: Long,
        summary: String,
        chapterTags: List<ChapterSummaryTag>,
        limit: Int = 2,
        minCosine: Float = MIN_COSINE
    ): StyleRetrievalResult {
        if (profileId <= 0L) return StyleRetrievalResult(reason = "书籍未绑定文风档案")
        val chunks = try {
            loadChunks(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return StyleRetrievalResult(profileId = profileId, reason = "读取档案片段失败：${e.message}")
        }.filter { it.text.isNotBlank() }
        if (chunks.isEmpty()) return StyleRetrievalResult(profileId = profileId, reason = "档案下没有已绑定样本片段")

        val tagsByChunk = try {
            loadTagRows(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap()
        }

        val weights = chapterTags.map { it.name.trim().lowercase() to categoryWeight(it.category) }
            .filter { it.first.isNotEmpty() }
        val tagScores = chunks.associate { chunk ->
            val rows = tagsByChunk[chunk.id].orEmpty()
            var best = 0.0
            rows.forEach { row ->
                val name = row.name.trim().lowercase()
                weights.forEach { (tagName, weight) ->
                    // 同名标签即可；若章节标签带真实 id，也接受 id 相等（改名后仍可匹配）
                    if (name == tagName) best += weight
                }
            }
            chunk.id to best
        }

        val query = buildString {
            append(summary.trim())
            if (chapterTags.isNotEmpty()) {
                append('\n')
                append(chapterTags.joinToString(" ") { "${it.category}：${it.name}" })
            }
        }.trim()
        val queryVector = queryVector(query)

        val scored = chunks.map { chunk ->
            val tagScore = tagScores[chunk.id] ?: 0.0
            val vectorScore = cosine(chunk, queryVector)
            val score = (vectorScore ?: 0f).toDouble() + tagScore * TAG_WEIGHT
            Triple(chunk, tagScore, vectorScore) to score
        }

        val vectorUsed = queryVector != null && scored.any { it.first.third != null }
        val hits = scored
            .filter { (triple, _) ->
                val (_, tagScore, vectorScore) = triple
                tagScore > 0.0 || (vectorScore != null && vectorScore >= minCosine)
            }
            .sortedByDescending { it.second }
            .take(limit.coerceAtLeast(1))
            .map { (triple, score) ->
                val (chunk, tagScore, vectorScore) = triple
                StyleSampleHit(
                    chunkId = chunk.id,
                    sampleId = chunk.sampleId,
                    chunkIndex = chunk.chunkIndex,
                    text = chunk.text,
                    tagLabels = tagsByChunk[chunk.id].orEmpty().map { "${it.category}：${it.name}" }.distinct(),
                    tagScore = tagScore,
                    vectorScore = vectorScore,
                    score = score
                )
            }

        return StyleRetrievalResult(
            hits = hits,
            profileId = profileId,
            candidateChunks = chunks.size,
            vectorUsed = vectorUsed,
            reason = when {
                hits.isNotEmpty() -> ""
                weights.isEmpty() && queryVector == null -> "本章无标签且向量不可用"
                weights.isEmpty() -> "标签未命中且向量相似度不足"
                else -> "标签与向量均未达到阈值"
            }
        )
    }

    private suspend fun queryVector(query: String): FloatArray? {
        if (query.isBlank()) return null
        return try {
            embed(listOf(query)).getOrNull()?.firstOrNull()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null
        }
    }

    private fun cosine(chunk: StyleSampleChunk, query: FloatArray?): Float? {
        if (query == null) return null
        val bytes = chunk.vector ?: return null
        if (chunk.dim != query.size) return null
        val vector = decodeVector(bytes)
        if (vector.size != query.size) return null
        return cosineSimilarity(vector, query)
    }

    /** 分类权重：场景 / 表达重要，情绪 / 节奏辅助；不限制用户自定义分类 */
    private fun categoryWeight(category: String): Double = when {
        category.contains("场景") || category.contains("情节") -> 3.0
        category.contains("表达") || category.contains("描写") || category.contains("句式") -> 3.0
        category.contains("情绪") || category.contains("氛围") -> 2.0
        category.contains("节奏") -> 2.0
        else -> 1.5
    }

    private companion object {
        const val MIN_COSINE = 0.25f
        const val TAG_WEIGHT = 0.15
    }
}
