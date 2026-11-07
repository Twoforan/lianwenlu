package com.lianwenqi.app.data

import androidx.room.withTransaction
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.entity.VolumeSummarySourceType
import com.lianwenqi.app.data.entity.VolumeSummaryStatus
import kotlinx.coroutines.flow.Flow

/** 卷梗概的过期状态：只呈现状态，不删除旧梗概、不自动重压缩 */
data class VolumeSummaryFreshness(
    /** 当前章节内容与生成时不一致（含章节增删 / 调序 / 已删除） */
    val stale: Boolean,
    /** 按当前章节重新计算出的指纹 */
    val currentRevision: String,
    /** 当前分卷的有效章节总数 */
    val currentChapterCount: Int,
    /** 生成时参与、但现在已经不存在于该卷的章节数 */
    val missingChapterCount: Int
)

/**
 * 整卷梗概领域仓库：负责版本状态机与跨表一致性，供后续压缩引擎（F2）与界面（F3）调用。
 *
 * 事务规则：
 * - 确认候选 = 旧生效版转归档 → 候选转生效 → 裁剪归档，全部在同一事务；
 * - 放弃候选只删除候选行，不触碰生效版；
 * - 恢复旧版 = 当前生效版转归档（用户编辑随行保留）→ 目标版本转生效；
 * - 模型原文为空、参与章节数大于总数、书 / 卷归属不一致时一律拒绝，生效版保持不变。
 */
class VolumeSummaryRepository(private val db: AppDatabase) {
    private val summaryDao = db.volumeSummaryDao()
    private val volumeDao = db.volumeDao()
    private val chapterDao = db.chapterDao()

    fun observeActiveByBook(bookId: Long): Flow<List<VolumeSummary>> = summaryDao.observeActiveByBook(bookId)

    /** 一次性读取当前生效卷梗概，供上下文选择恢复 / 推荐计算 */
    suspend fun activeListByBook(bookId: Long): List<VolumeSummary> = summaryDao.activeListByBook(bookId)

    fun observeActive(volumeId: Long): Flow<VolumeSummary?> = summaryDao.observeActiveByVolume(volumeId)

    suspend fun activeOfVolume(volumeId: Long): VolumeSummary? = summaryDao.activeByVolume(volumeId)

    suspend fun getDetail(id: Long): VolumeSummary? = summaryDao.getById(id)

    /** 版本列表：当前生效版在最前，其后按归档时间倒序；候选不属于历史版本，不在其中 */
    suspend fun listVersions(volumeId: Long): List<VolumeSummary> {
        val active = summaryDao.activeByVolume(volumeId)?.let { listOf(it) } ?: emptyList()
        return active + summaryDao.archivedByVolume(volumeId)
    }

    suspend fun candidates(volumeId: Long): List<VolumeSummary> = summaryDao.candidatesByVolume(volumeId)

    suspend fun byCompressionSession(compressionSessionId: Long): List<VolumeSummary> =
        summaryDao.byCompressionSession(compressionSessionId)

    /**
     * 创建候选版本（重新压缩的结果先落成候选，不改变当前生效版本）。
     *
     * [chapterIds] 为空（null）表示整卷参与；传入集合时必须全部属于该卷的有效章节。
     * [modelContent] 为空、书 / 卷归属不一致、参与章节不属于该卷时返回 null，不写库。
     */
    suspend fun createCandidate(
        bookId: Long,
        volumeId: Long,
        sourceType: String,
        modelContent: String,
        modelId: Long = 0,
        modelName: String = "",
        compressionSessionId: Long? = null,
        chapterIds: List<Long>? = null
    ): Long? = db.withTransaction {
        val volume = volumeDao.getById(volumeId) ?: return@withTransaction null
        if (volume.bookId != bookId) return@withTransaction null
        if (modelContent.isBlank()) return@withTransaction null

        val type = VolumeSummarySourceType.normalize(sourceType)
        val chapters = chapterDao.getValidByVolume(bookId, volumeId)
        if (chapters.isEmpty()) return@withTransaction null
        val selected = if (chapterIds == null) {
            chapters
        } else {
            val wanted = chapterIds.distinct()
            if (wanted.isEmpty()) return@withTransaction null
            chapters.filter { it.id in wanted }.also {
                // 传入的章节必须全部属于本卷，避免跨卷 / 已删章节混入计数
                if (it.size != wanted.size) return@withTransaction null
            }
        }
        val entries = selected.map { chapter ->
            VolumeSourceRevision.Entry(chapter.id, hashOf(chapter, type))
        }
        val now = System.currentTimeMillis()
        summaryDao.insert(
            VolumeSummary(
                bookId = bookId,
                volumeId = volumeId,
                status = VolumeSummaryStatus.CANDIDATE,
                sourceType = type,
                modelContent = modelContent,
                sourceRevision = VolumeSourceRevision.revision(type, entries),
                sourceChapterCount = chapters.size,
                processedChapterCount = entries.size,
                sourceChapterIds = entries.joinToString(",") { it.chapterId.toString() },
                sourceChapterHashes = entries.joinToString(",") { it.contentHash },
                modelId = modelId,
                modelName = modelName,
                compressionSessionId = compressionSessionId,
                isCurrent = null,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    /**
     * 确认候选为当前生效版本：旧生效版转归档、候选转生效、归档只保留最近一条，整段同一事务。
     * 候选不存在 / 已不是候选 / 内容为空 / 归属不符时返回 false，当前生效版保持不变。
     */
    suspend fun confirmCandidate(id: Long): Boolean = db.withTransaction {
        val candidate = summaryDao.getById(id) ?: return@withTransaction false
        if (candidate.status != VolumeSummaryStatus.CANDIDATE) return@withTransaction false
        if (!ownershipValid(candidate)) return@withTransaction false
        if (!contentComplete(candidate)) return@withTransaction false

        val now = System.currentTimeMillis()
        summaryDao.archiveActive(candidate.volumeId, now)
        summaryDao.update(
            candidate.copy(
                status = VolumeSummaryStatus.ACTIVE,
                isCurrent = true,
                updatedAt = now
            )
        )
        trimArchived(candidate.volumeId)
        true
    }

    /** 放弃候选：只删候选行，生效版与归档版都不受影响 */
    suspend fun discardCandidate(id: Long): Boolean = db.withTransaction {
        val candidate = summaryDao.getById(id) ?: return@withTransaction false
        if (candidate.status != VolumeSummaryStatus.CANDIDATE) return@withTransaction false
        summaryDao.deleteById(id)
        true
    }

    /**
     * 保存用户编辑内容（空字符串表示恢复为模型原文）。
     * 生效版与归档版都可编辑；候选不可编辑，避免确认时把半成品当成用户定稿。
     */
    suspend fun saveUserContent(id: Long, content: String): Boolean = db.withTransaction {
        val summary = summaryDao.getById(id) ?: return@withTransaction false
        if (summary.status == VolumeSummaryStatus.CANDIDATE) return@withTransaction false
        if (!ownershipValid(summary)) return@withTransaction false
        summaryDao.update(summary.copy(userContent = content, updatedAt = System.currentTimeMillis()))
        true
    }

    /**
     * 恢复某个归档版本：当前生效版先转归档（连同用户编辑一起保留），目标版本再转生效。
     * 目标不存在 / 不是归档版 / 内容不完整 / 归属不符时返回 false。
     */
    suspend fun restoreVersion(id: Long): Boolean = db.withTransaction {
        val target = summaryDao.getById(id) ?: return@withTransaction false
        if (target.status != VolumeSummaryStatus.ARCHIVED) return@withTransaction false
        if (!ownershipValid(target)) return@withTransaction false
        if (!contentComplete(target)) return@withTransaction false

        val now = System.currentTimeMillis()
        summaryDao.archiveActive(target.volumeId, now)
        summaryDao.update(target.copy(status = VolumeSummaryStatus.ACTIVE, isCurrent = true, updatedAt = now))
        trimArchived(target.volumeId)
        true
    }

    /**
     * 按当前章节重新计算源指纹并与生成时的记录比较。
     * 记录不存在或分卷已不存在时返回 null / stale = true（UI 按「过期」展示即可）。
     */
    suspend fun checkFreshness(id: Long): VolumeSummaryFreshness? {
        val summary = summaryDao.getById(id) ?: return null
        val volume = volumeDao.getById(summary.volumeId)
        if (volume == null || volume.bookId != summary.bookId) {
            return VolumeSummaryFreshness(
                stale = true,
                currentRevision = "",
                currentChapterCount = 0,
                missingChapterCount = summary.sourceChapterIdList().size
            )
        }
        val chapters = chapterDao.getValidByVolume(summary.bookId, summary.volumeId)
        val byId = chapters.associateBy { it.id }
        var missing = 0
        val entries = summary.sourceChapterIdList().map { chapterId ->
            val chapter = byId[chapterId]
            if (chapter == null) {
                missing++
                VolumeSourceRevision.Entry(chapterId, VolumeSourceRevision.MISSING_HASH)
            } else {
                VolumeSourceRevision.Entry(chapterId, hashOf(chapter, summary.sourceType))
            }
        }
        val currentRevision = VolumeSourceRevision.revision(summary.sourceType, entries)
        val storedIds = summary.sourceChapterIdList()
        val currentIds = chapters.map { it.id }
        // 生成时覆盖了整卷，则卷内章节的增删 / 调序同样算过期；只压缩了部分章节时以参与者为准
        val coveredWholeVolume = summary.sourceChapterCount == storedIds.size
        val stale = summary.sourceRevision.isBlank() ||
            currentRevision != summary.sourceRevision ||
            (coveredWholeVolume && currentIds != storedIds)
        return VolumeSummaryFreshness(
            stale = stale,
            currentRevision = currentRevision,
            currentChapterCount = chapters.size,
            missingChapterCount = missing
        )
    }

    /** 便捷判断（记录不存在返回 null，避免调用方把「查不到」误当成「未过期」） */
    suspend fun isStale(id: Long): Boolean? = checkFreshness(id)?.stale

    /** 书 / 卷归属一致：版本记录的书与卷都必须仍存在且互相对应 */
    private suspend fun ownershipValid(summary: VolumeSummary): Boolean {
        val volume = volumeDao.getById(summary.volumeId) ?: return false
        return volume.bookId == summary.bookId
    }

    /** 内容完整性：模型原文非空、参与数不超过总数、参与章节 id 与计数一致 */
    private fun contentComplete(summary: VolumeSummary): Boolean {
        if (summary.modelContent.isBlank()) return false
        if (summary.processedChapterCount > summary.sourceChapterCount) return false
        if (summary.sourceChapterIdList().size != summary.processedChapterCount) return false
        return true
    }

    /** 归档只保留最近 [VolumeSummary.MAX_ARCHIVED] 条，其余删除 */
    private suspend fun trimArchived(volumeId: Long) {
        summaryDao.archivedByVolume(volumeId)
            .drop(VolumeSummary.MAX_ARCHIVED)
            .forEach { summaryDao.deleteById(it.id) }
    }

    private fun hashOf(chapter: Chapter, sourceType: String): String =
        VolumeSourceRevision.contentHash(
            if (sourceType == VolumeSummarySourceType.CONTENT) chapter.content else chapter.summary
        )
}
