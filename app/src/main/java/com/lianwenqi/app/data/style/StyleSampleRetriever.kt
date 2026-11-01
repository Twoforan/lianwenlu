package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.StyleTagRepository
import com.lianwenqi.app.data.dao.StyleChunkTagRow
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.StyleSampleChunk
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/** 一个抽中的文风样本片段（只用于本次正文生成，不写回任何长期数据） */
data class StyleSampleHit(
    val chunkId: Long,
    val sampleId: Long,
    val chunkIndex: Int,
    val text: String,
    /** 「分类：名称」形式的标签，供提示词说明来源 */
    val tagLabels: List<String>,
    /** 该片段命中的本章标签权重之和（无标签命中时为 0） */
    val weight: Double = 0.0
)

/** 一次文风样本抽样的结果与可追踪信息 */
data class StyleSamplePickResult(
    val hits: List<StyleSampleHit> = emptyList(),
    val profileId: Long = 0L,
    val candidateChunks: Int = 0,
    /** 本章标签真正命中的候选片段数；为 0 时退化为全库随机抽样 */
    val matchedChunks: Int = 0,
    /** 没有结果 / 失败时的原因，用于日志与界面简要提示 */
    val reason: String = ""
)

/**
 * 正文生成前的文风样本抽样（已移除向量 RAG）：
 * 1. `styleProfileId` 是硬性过滤条件，候选只能来自当前书籍绑定的文风档案；
 * 2. 只按本章标签的权重做加权随机抽样，不再计算向量相似度：
 *    标签出现次数 × 分类权重累加到携带该标签的片段上，权重越高越容易被抽中；
 * 3. 本章标签全部未命中时，退化为在该档案全部片段中等概率随机抽样；
 * 4. 最多返回 1～2 条，不足不补足、不跨档案借用；失败时返回空结果，不阻断正文生成。
 */
class StyleSampleRetriever internal constructor(
    private val loadChunks: suspend (Long) -> List<StyleSampleChunk>,
    private val loadTagRows: suspend (Long) -> Map<Long, List<StyleChunkTagRow>>,
    private val random: Random = Random.Default
) {

    constructor(repository: StyleTagRepository) : this(
        loadChunks = { repository.chunksOfProfile(it) },
        loadTagRows = { repository.chunkTagsOfProfile(it) }
    )

    suspend fun pick(
        profileId: Long,
        chapterTags: List<ChapterSummaryTag>,
        limit: Int = 2
    ): StyleSamplePickResult {
        if (profileId <= 0L) return StyleSamplePickResult(reason = "书籍未绑定文风档案")
        val chunks = try {
            loadChunks(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return StyleSamplePickResult(profileId = profileId, reason = "读取档案片段失败：${e.message}")
        }.filter { it.text.isNotBlank() }
        if (chunks.isEmpty()) return StyleSamplePickResult(profileId = profileId, reason = "档案下没有已绑定样本片段")

        val tagsByChunk = try {
            loadTagRows(profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap()
        }

        // 章节标签按「名称」累加：同一标签出现次数越多、分类越重要，权重越大
        val tagWeights = HashMap<String, Double>()
        chapterTags.forEach { tag ->
            val name = tag.name.trim().lowercase()
            if (name.isNotEmpty()) {
                tagWeights[name] = (tagWeights[name] ?: 0.0) + categoryWeight(tag.category)
            }
        }

        val weights = chunks.associate { chunk ->
            val rows = tagsByChunk[chunk.id].orEmpty()
            val sum = rows.sumOf { row -> tagWeights[row.name.trim().lowercase()] ?: 0.0 }
            chunk.id to sum
        }
        val matchedChunks = weights.values.count { it > 0.0 }
        // 有命中就只在命中片段里按权重抽；没有命中则在整个档案中等概率随机抽
        val pool = if (matchedChunks > 0) {
            chunks.filter { weights[it.id] ?: 0.0 > 0.0 }
        } else {
            chunks
        }

        val hits = sampleWeighted(pool, weights, limit.coerceAtLeast(1)).map { (chunk, weight) ->
            StyleSampleHit(
                chunkId = chunk.id,
                sampleId = chunk.sampleId,
                chunkIndex = chunk.chunkIndex,
                text = chunk.text,
                tagLabels = tagsByChunk[chunk.id].orEmpty()
                    .map { "${it.category}：${it.name}" }
                    .distinct(),
                weight = weight
            )
        }

        return StyleSamplePickResult(
            hits = hits,
            profileId = profileId,
            candidateChunks = chunks.size,
            matchedChunks = matchedChunks,
            reason = if (hits.isNotEmpty()) "" else "档案下没有可抽样片段"
        )
    }

    /** 按权重不放回随机抽样；权重全为 0 时退化为等概率抽样 */
    private fun sampleWeighted(
        pool: List<StyleSampleChunk>,
        weights: Map<Long, Double>,
        count: Int
    ): List<Pair<StyleSampleChunk, Double>> {
        val remaining = pool.toMutableList()
        val picked = mutableListOf<Pair<StyleSampleChunk, Double>>()
        repeat(minOf(count, remaining.size)) {
            val total = remaining.sumOf { weights[it.id] ?: 0.0 }
            val index = if (total <= 0.0) {
                random.nextInt(remaining.size)
            } else {
                var cursor = random.nextDouble(total)
                var selected = remaining.lastIndex
                for (i in remaining.indices) {
                    cursor -= weights[remaining[i].id] ?: 0.0
                    if (cursor <= 0.0) {
                        selected = i
                        break
                    }
                }
                selected
            }
            val chunk = remaining.removeAt(index)
            picked += chunk to (weights[chunk.id] ?: 0.0)
        }
        return picked
    }

    /** 分类权重：场景 / 表达重要，情绪 / 节奏辅助；不限制用户自定义分类 */
    private fun categoryWeight(category: String): Double = when {
        category.contains("场景") || category.contains("情节") -> 3.0
        category.contains("表达") || category.contains("描写") || category.contains("句式") -> 3.0
        category.contains("情绪") || category.contains("氛围") -> 2.0
        category.contains("节奏") -> 2.0
        else -> 1.5
    }
}
