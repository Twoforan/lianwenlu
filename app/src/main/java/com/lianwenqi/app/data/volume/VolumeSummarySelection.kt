package com.lianwenqi.app.data.volume

import com.lianwenqi.app.data.CompressionSourceMode
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 整卷梗概作为「独立上下文资产」的纯逻辑：
 * 选择计数、前序卷推荐、手动覆盖记忆、以及勾选缺失梗概时的自动压缩判定。
 *
 * 这里不读写数据库、不持有 Compose 状态，供 AiChatViewModel 与单元测试共用。
 */
object VolumeSummarySelection {

    /** 前序卷推荐状态：按目标卷分别记录用户手动排除的卷 */
    @Serializable
    data class RecoStore(val targets: Map<String, List<Long>> = emptyMap())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 上下文选择数量：N = 已确认选中的卷梗概数，M = 至少选择一种章节材料的章节数 */
    data class Counts(val volumeCount: Int, val chapterCount: Int)

    /** 勾选缺失卷梗概时的判定结果 */
    sealed interface CompressDecision {
        /** 已有生效卷梗概，直接切换选择即可 */
        data object NotNeeded : CompressDecision

        /** 全局自动压缩关闭：提示未生成，不改变选择 */
        data object Disabled : CompressDecision

        /** 来源配置为「每次询问」：先弹来源选择 */
        data class AskSource(val volumeId: Long) : CompressDecision

        /** 直接按配置的来源启动压缩 */
        data class Start(val volumeId: Long, val sourceType: String) : CompressDecision
    }

    fun counts(selectedVolumeSummaryIds: Set<Long>, selectedChapterIds: Set<Long>): Counts =
        Counts(
            volumeCount = selectedVolumeSummaryIds.size,
            chapterCount = selectedChapterIds.size
        )

    /**
     * 当前创作卷之前、具有当前生效卷梗概的分卷 id，按分卷 [Volume.sortOrder] / [Volume.id] 升序。
     * [targetVolumeId] 为空或不在 [volumes] 中时返回空集合（无法确定当前卷就不擅自推荐）。
     */
    fun precedingVolumesWithSummary(
        volumes: List<Volume>,
        activeSummaries: List<VolumeSummary>,
        targetVolumeId: Long?
    ): List<Long> {
        if (targetVolumeId == null) return emptyList()
        val ordered = volumes.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val targetIndex = ordered.indexOfFirst { it.id == targetVolumeId }
        if (targetIndex <= 0) return emptyList()
        val withSummary = activeSummaries.map { it.volumeId }.toSet()
        return ordered.take(targetIndex).map { it.id }.filter { it in withSummary }
    }

    /** 首次进入时的默认推荐：前序有梗概的分卷，减去用户手动排除的卷 */
    fun recommendedVolumeIds(
        volumes: List<Volume>,
        activeSummaries: List<VolumeSummary>,
        targetVolumeId: Long?,
        excludedVolumeIds: Set<Long>
    ): Set<Long> = precedingVolumesWithSummary(volumes, activeSummaries, targetVolumeId)
        .filterNot { it in excludedVolumeIds }
        .toSet()

    /** 当前生效梗概的 volumeId → VolumeSummary（同卷若有多条异常数据，取 id 最小的一条） */
    fun activeByVolume(activeSummaries: List<VolumeSummary>): Map<Long, VolumeSummary> {
        val out = LinkedHashMap<Long, VolumeSummary>()
        activeSummaries.sortedBy { it.id }.forEach { s ->
            if (!out.containsKey(s.volumeId)) out[s.volumeId] = s
        }
        return out
    }

    /**
     * 恢复历史选择时过滤：只保留仍生效、仍存在、且属于当前书的卷梗概版本。
     * 返回 [kept] 与因删除 / 换版 / 跨书被丢弃的数量 [dropped]。
     */
    fun pruneSelected(
        selectedSummaryIds: Set<Long>,
        bookId: Long?,
        activeSummaries: List<VolumeSummary>
    ): Pair<Set<Long>, Int> {
        if (selectedSummaryIds.isEmpty()) return emptySet<Long>() to 0
        val valid = activeSummaries
            .filter { bookId == null || it.bookId == bookId }
            .map { it.id }
            .toSet()
        val kept = selectedSummaryIds.filter { it in valid }.toSet()
        return kept to (selectedSummaryIds.size - kept.size)
    }

    /** 用户手动勾选 / 取消时同步推荐排除集：手动取消前序卷后不再自动勾上，手动勾回则解除排除 */
    fun applyManualDecision(
        excluded: Set<Long>,
        volumeId: Long,
        selected: Boolean
    ): Set<Long> = if (selected) excluded - volumeId else excluded + volumeId

    fun encodeRecommendations(store: Map<Long, Set<Long>>): String =
        json.encodeToString(
            RecoStore.serializer(),
            RecoStore(store.mapKeys { it.key.toString() }.mapValues { it.value.sorted() })
        )

    fun decodeRecommendations(raw: String): Map<Long, Set<Long>> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString(RecoStore.serializer(), raw)
        }.getOrElse { RecoStore() }
            .targets
            .mapNotNull { (k, v) -> k.toLongOrNull()?.let { it to v.toSet() } }
            .toMap()
    }

    /**
     * 勾选缺失卷梗概时的自动压缩判定。
     * 关闭状态不启动；ASK 先询问来源；固定来源直接使用本次配置快照。
     */
    fun decideCompress(
        volumeId: Long,
        hasActiveSummary: Boolean,
        autoCompressEnabled: Boolean,
        sourceMode: String
    ): CompressDecision {
        if (hasActiveSummary) return CompressDecision.NotNeeded
        if (!autoCompressEnabled) return CompressDecision.Disabled
        val source = CompressionSourceMode.normalize(sourceMode)
        return if (source == CompressionSourceMode.ASK) {
            CompressDecision.AskSource(volumeId)
        } else {
            CompressDecision.Start(volumeId, source)
        }
    }
}
