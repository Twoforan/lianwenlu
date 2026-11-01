package com.lianwenqi.app.data

import androidx.room.withTransaction
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.GLOBAL_STYLE_TAG_SCOPE
import com.lianwenqi.app.data.entity.StyleSampleChunk
import com.lianwenqi.app.data.entity.StyleSampleRecord
import com.lianwenqi.app.data.entity.StyleSampleTagLink
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagCategory
import com.lianwenqi.app.data.entity.StyleTagSource
import com.lianwenqi.app.data.dao.StyleChunkTagRow
import com.lianwenqi.app.util.chunkStyleSample
import com.lianwenqi.app.util.encodeVector
import com.lianwenqi.app.util.shortHash
import kotlinx.coroutines.flow.Flow

/** 文风样本默认切片目标长度（字）；这是目标值，不是硬性上限 */
private const val STYLE_SAMPLE_CHUNK_TARGET = 100

/** 标签名称 / 分类的规范化与校验规则（AI 与用户输入共用，保证去重口径一致） */
object StyleTagRules {
    const val MAX_NAME_LENGTH = 40
    const val MAX_CATEGORY_LENGTH = 30
    const val MAX_DESCRIPTION_LENGTH = 200
    const val DEFAULT_CATEGORY = "未分类"

    fun normalizeName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH)

    fun normalizeCategory(raw: String): String =
        raw.trim().replace(Regex("\\s+"), " ").take(MAX_CATEGORY_LENGTH).ifBlank { DEFAULT_CATEGORY }

    fun normalizeDescription(raw: String): String = raw.trim().take(MAX_DESCRIPTION_LENGTH)

    /** 去重键：分类 + 名称；大小写不敏感，避免「压抑」「 压抑 」被当成两个标签 */
    fun key(category: String, name: String): String =
        normalizeCategory(category).lowercase() + "\u0000" + normalizeName(name).lowercase()

    /** 同义判定：规范化后完全相同；不做模糊匹配，由提示词要求模型语义复用 */
    fun sameName(a: String, b: String): Boolean = normalizeName(a) == normalizeName(b)
}

/** 一个样本及其片段、标签的可展示聚合 */
data class StyleSampleDetail(
    val sample: StyleSampleRecord,
    val chunks: List<StyleSampleChunk>,
    val tags: List<StyleTag>
)

/** 标签使用情况 */
data class StyleTagUsage(val tag: StyleTag, val chunkCount: Int)

/** 文风档案样式概览的真实统计 */
data class StyleProfileStyleStats(
    val tagCount: Int,
    val usedTagCount: Int,
    val sampleCount: Int,
    val chunkCount: Int,
    val markedChunkCount: Int
)

/** 单个文风样本的样式统计 */
data class StyleSampleStyleStats(
    val chunkCount: Int,
    val markedChunkCount: Int,
    val tagCount: Int
)

/**
 * 文风标签 / 样本领域。
 *
 * 权限边界在这一层强制：
 * - 全局标签（styleProfileId = 0）只能由用户主动的增删改方法改动；
 * - AI 只能通过 [createProfileTagFromAi] 在指定档案下新增标签；
 * - 任何标签删除都只解除片段关联，不删除片段的原文与向量。
 */
class StyleTagRepository(private val db: AppDatabase) {
    private val tagDao = db.styleTagDao()
    private val profileDao = db.styleProfileDao()
    private val summaryTagDao = db.chapterSummaryTagDao()

    // ---------------- 全局用户标签库 ----------------

    fun observeUserCategories(): Flow<List<StyleTagCategory>> =
        tagDao.observeCategories(GLOBAL_STYLE_TAG_SCOPE)

    fun observeUserTags(): Flow<List<StyleTag>> = tagDao.observeTags(GLOBAL_STYLE_TAG_SCOPE)

    suspend fun userTags(): List<StyleTag> = tagDao.getTags(GLOBAL_STYLE_TAG_SCOPE)

    suspend fun userCategories(): List<StyleTagCategory> = tagDao.getCategories(GLOBAL_STYLE_TAG_SCOPE)

    suspend fun createUserCategory(name: String): Long {
        val normalized = StyleTagRules.normalizeCategory(name)
        return tagDao.insertCategory(
            StyleTagCategory(
                styleProfileId = GLOBAL_STYLE_TAG_SCOPE,
                name = normalized,
                source = StyleTagSource.USER
            )
        )
    }

    suspend fun renameUserCategory(from: String, to: String) = db.withTransaction {
        val old = StyleTagRules.normalizeCategory(from)
        val next = StyleTagRules.normalizeCategory(to)
        require(old != next) { "新分类名与原分类名相同" }
        val existing = tagDao.findCategory(GLOBAL_STYLE_TAG_SCOPE, next)
        require(existing == null) { "已存在同名分类" }
        val category = tagDao.findCategory(GLOBAL_STYLE_TAG_SCOPE, old)
            ?: throw IllegalArgumentException("分类不存在：$from")
        tagDao.updateCategory(category.copy(name = next))
        tagDao.renameTagCategory(GLOBAL_STYLE_TAG_SCOPE, old, next)
    }

    suspend fun deleteUserCategory(name: String) = deleteCategory(GLOBAL_STYLE_TAG_SCOPE, name)

    suspend fun addUserTag(category: String, name: String, description: String = ""): Long =
        addTag(GLOBAL_STYLE_TAG_SCOPE, category, name, description, StyleTagSource.USER)

    suspend fun updateUserTag(tagId: Long, category: String, name: String, description: String = "") {
        require(tagDao.getTag(tagId)?.styleProfileId == GLOBAL_STYLE_TAG_SCOPE) { "只能修改全局用户标签" }
        updateTag(tagId, category, name, description)
    }

    suspend fun deleteUserTag(tagId: Long) {
        require(tagDao.getTag(tagId)?.styleProfileId == GLOBAL_STYLE_TAG_SCOPE) { "只能删除全局用户标签" }
        deleteTag(tagId)
    }

    // ---------------- 档案专属标签库 ----------------

    fun observeProfileCategories(profileId: Long): Flow<List<StyleTagCategory>> =
        tagDao.observeCategories(profileId)

    fun observeProfileTags(profileId: Long): Flow<List<StyleTag>> = tagDao.observeTags(profileId)

    suspend fun profileTags(profileId: Long): List<StyleTag> = tagDao.getTags(profileId)

    suspend fun profileCategories(profileId: Long): List<StyleTagCategory> =
        tagDao.getCategories(profileId)

    suspend fun addProfileTag(
        profileId: Long,
        category: String,
        name: String,
        description: String = ""
    ): Long = addTag(profileId, category, name, description, StyleTagSource.USER)

    suspend fun updateProfileTag(
        profileId: Long,
        tagId: Long,
        category: String,
        name: String,
        description: String = ""
    ) {
        require(tagDao.getTag(tagId)?.styleProfileId == profileId) { "标签不属于该文风档案" }
        updateTag(tagId, category, name, description)
    }

    suspend fun deleteProfileTag(profileId: Long, tagId: Long) {
        require(tagDao.getTag(tagId)?.styleProfileId == profileId) { "标签不属于该文风档案" }
        deleteTag(tagId)
    }

    suspend fun createProfileCategory(profileId: Long, name: String): Long {
        require(profileId > 0 && profileDao.getById(profileId) != null) { "文风档案不存在" }
        return tagDao.insertCategory(
            StyleTagCategory(
                styleProfileId = profileId,
                name = StyleTagRules.normalizeCategory(name),
                source = StyleTagSource.USER
            )
        )
    }

    suspend fun renameProfileCategory(profileId: Long, from: String, to: String) = db.withTransaction {
        val old = StyleTagRules.normalizeCategory(from)
        val next = StyleTagRules.normalizeCategory(to)
        val category = tagDao.findCategory(profileId, old)
            ?: throw IllegalArgumentException("分类不存在：$from")
        require(tagDao.findCategory(profileId, next) == null) { "已存在同名分类" }
        tagDao.updateCategory(category.copy(name = next))
        tagDao.renameTagCategory(profileId, old, next)
    }

    suspend fun deleteProfileCategory(profileId: Long, name: String) =
        deleteCategory(profileId, name)

    /**
     * AI 新增档案标签：只允许写入 profileId > 0 的档案库。
     * 已存在同分类同名词条时直接复用，不重复创建；名称与用户全局标签同名时也复用全局标签，
     * 绝不把 AI 标签写进全局库。返回实际使用的标签 id。
     */
    suspend fun createProfileTagFromAi(
        profileId: Long,
        category: String,
        name: String,
        description: String = ""
    ): Long {
        require(profileId > 0L) { "AI 不能写入全局用户标签库" }
        val normalizedName = StyleTagRules.normalizeName(name)
        require(normalizedName.isNotBlank()) { "标签名称不能为空" }
        val normalizedCategory = StyleTagRules.normalizeCategory(category)

        // 先复用同名全局标签（用户标签库是 AI 的第一优先级来源）
        tagDao.findTagByName(GLOBAL_STYLE_TAG_SCOPE, normalizedName)?.let { return it.id }
        tagDao.findTag(profileId, normalizedCategory, normalizedName)?.let { return it.id }
        val id = tagDao.insertTag(
            StyleTag(
                styleProfileId = profileId,
                category = normalizedCategory,
                name = normalizedName,
                source = StyleTagSource.AI,
                description = StyleTagRules.normalizeDescription(description)
            )
        )
        // IGNORE 冲突时 insert 返回 -1，回读已有记录
        if (id > 0L) {
            tagDao.findTag(profileId, normalizedCategory, normalizedName)?.let {
                if (it.id != id) return it.id
            }
            return id
        }
        return tagDao.findTag(profileId, normalizedCategory, normalizedName)?.id
            ?: throw IllegalStateException("创建档案标签失败：$normalizedName")
    }

    private suspend fun addTag(
        scope: Long,
        category: String,
        name: String,
        description: String,
        source: String
    ): Long {
        if (scope > 0L) require(profileDao.getById(scope) != null) { "文风档案不存在" }
        val normalizedName = StyleTagRules.normalizeName(name)
        require(normalizedName.isNotBlank()) { "标签名称不能为空" }
        val normalizedCategory = StyleTagRules.normalizeCategory(category)
        tagDao.findTag(scope, normalizedCategory, normalizedName)?.let { return it.id }
        tagDao.insertCategory(
            StyleTagCategory(styleProfileId = scope, name = normalizedCategory, source = StyleTagSource.USER)
        )
        val id = tagDao.insertTag(
            StyleTag(
                styleProfileId = scope,
                category = normalizedCategory,
                name = normalizedName,
                source = source,
                description = StyleTagRules.normalizeDescription(description)
            )
        )
        return if (id > 0L) id else tagDao.findTag(scope, normalizedCategory, normalizedName)?.id
            ?: throw IllegalStateException("创建标签失败")
    }

    private suspend fun updateTag(tagId: Long, category: String, name: String, description: String) {
        val tag = tagDao.getTag(tagId) ?: throw IllegalArgumentException("标签不存在")
        val normalizedName = StyleTagRules.normalizeName(name)
        require(normalizedName.isNotBlank()) { "标签名称不能为空" }
        val normalizedCategory = StyleTagRules.normalizeCategory(category)
        val conflict = tagDao.findTag(tag.styleProfileId, normalizedCategory, normalizedName)
        require(conflict == null || conflict.id == tagId) { "同分类下已存在同名标签" }
        tagDao.insertCategory(
            StyleTagCategory(styleProfileId = tag.styleProfileId, name = normalizedCategory)
        )
        tagDao.updateTag(
            tag.copy(
                category = normalizedCategory,
                name = normalizedName,
                description = StyleTagRules.normalizeDescription(description)
            )
        )
    }

    private suspend fun deleteCategory(scope: Long, name: String) = db.withTransaction {
        val normalized = StyleTagRules.normalizeCategory(name)
        tagDao.getTags(scope).filter { StyleTagRules.sameName(it.category, normalized) }.forEach {
            tagDao.deleteLinksOfTag(it.id)
        }
        tagDao.deleteTagsInCategory(scope, normalized)
        tagDao.getCategories(scope)
            .firstOrNull { StyleTagRules.sameName(it.name, normalized) }
            ?.let { tagDao.deleteCategory(it.id) }
    }

    private suspend fun deleteTag(tagId: Long) = db.withTransaction {
        tagDao.deleteLinksOfTag(tagId)
        tagDao.deleteTag(tagId)
    }

    suspend fun tagUsage(tagId: Long): Int = tagDao.tagUsageCount(tagId)

    /** 某档案下全部已绑定片段（RAG 硬性检索范围） */
    suspend fun chunksOfProfile(profileId: Long): List<StyleSampleChunk> = tagDao.chunksOfProfile(profileId)

    /** 某档案下片段与标签的关联（按片段分组，用于标签匹配打分） */
    suspend fun chunkTagsOfProfile(profileId: Long): Map<Long, List<StyleChunkTagRow>> =
        tagDao.chunkTagRowsOfProfile(profileId).groupBy { it.chunkId }

    suspend fun chunksUsingTag(tagId: Long): List<StyleSampleChunk> = tagDao.chunksUsingTag(tagId)

    /** 档案下每个标签被多少个片段使用（一次查询统计，不用逐标签查询） */
    suspend fun profileTagUsage(profileId: Long): Map<Long, Int> =
        tagDao.chunkTagRowsOfProfile(profileId).groupingBy { it.tagId }.eachCount()

    /** 档案样式统计：专属标签、样本、片段、已标记片段、实际使用标签数 */
    suspend fun profileStyleStats(profileId: Long): StyleProfileStyleStats {
        val tags = tagDao.getTags(profileId)
        val chunks = tagDao.chunksOfProfile(profileId)
        val rows = tagDao.chunkTagRowsOfProfile(profileId)
        return StyleProfileStyleStats(
            tagCount = tags.size,
            usedTagCount = rows.map { it.tagId }.distinct().size,
            sampleCount = tagDao.getSamples(profileId).size,
            chunkCount = chunks.size,
            markedChunkCount = rows.map { it.chunkId }.distinct().size
        )
    }

    /** 样本样式统计：片段数、已标记片段数、实际使用标签数 */
    suspend fun sampleStyleStats(sampleId: Long): StyleSampleStyleStats {
        val chunks = tagDao.chunksOfSample(sampleId)
        val tagsByChunk = chunkTagsOfSample(sampleId)
        val distinctTags = tagsByChunk.values.flatten().distinctBy { it.id }
        return StyleSampleStyleStats(
            chunkCount = chunks.size,
            markedChunkCount = tagsByChunk.count { it.value.isNotEmpty() },
            tagCount = distinctTags.size
        )
    }

    // ---------------- 样本 ----------------

    fun observeAllSamples(): Flow<List<StyleSampleRecord>> = tagDao.observeAllSamples()

    fun observeSamples(profileId: Long): Flow<List<StyleSampleRecord>> = tagDao.observeSamples(profileId)

    fun observeUnboundSamples(): Flow<List<StyleSampleRecord>> = tagDao.observeUnboundSamples()

    suspend fun allSamples(): List<StyleSampleRecord> = tagDao.getAllSamples()

    suspend fun sample(id: Long): StyleSampleRecord? = tagDao.getSample(id)

    suspend fun chunksOfSample(sampleId: Long): List<StyleSampleChunk> = tagDao.chunksOfSample(sampleId)

    /** 片段 → 标签（样本详情展示用） */
    suspend fun chunkTagsOfSample(sampleId: Long): Map<Long, List<StyleTag>> =
        tagDao.chunksOfSample(sampleId).associate { chunk -> chunk.id to tagDao.tagsOfChunk(chunk.id) }

    suspend fun sampleDetail(id: Long): StyleSampleDetail? {
        val sample = tagDao.getSample(id) ?: return null
        val chunks = tagDao.chunksOfSample(id)
        val tags = chunks.flatMap { tagDao.tagsOfChunk(it.id) }.distinctBy { it.id }
        return StyleSampleDetail(sample, chunks, tags)
    }

    /**
     * 导入并切分样本。[profileId] 为 0 时样本默认未绑定（不参与任何档案的 RAG 检索），
     * 之后可在样本管理里再绑定档案；大于 0 时直接归属该档案。
     * 切片使用文风专用的 [chunkStyleSample]：默认约 100 字、按自然段与句末标点切分、不重叠。
     * 向量化由调用方注入现有 [com.lianwenqi.app.ai.EmbeddingGateway]，
     * 失败时片段照常保存（vector = null），不阻断导入。
     */
    suspend fun importSample(
        profileId: Long,
        fileName: String,
        format: String,
        text: String,
        embed: suspend (List<String>) -> Result<List<FloatArray>> = { Result.success(emptyList()) }
    ): Long {
        val normalizedProfileId = profileId.takeIf { it > 0L }
        if (normalizedProfileId != null) {
            require(profileDao.getById(normalizedProfileId) != null) { "文风档案不存在" }
        }
        val body = text.trim()
        require(body.isNotBlank()) { "样本内容为空" }
        val scope = normalizedProfileId ?: GLOBAL_STYLE_TAG_SCOPE
        val sampleId = tagDao.insertSample(
            StyleSampleRecord(
                styleProfileId = normalizedProfileId,
                fileName = fileName,
                format = format,
                charCount = body.length,
                status = "SUCCESS"
            )
        )
        val pieces = chunkStyleSample(body, STYLE_SAMPLE_CHUNK_TARGET)
        val rows = pieces.map { chunk ->
            StyleSampleChunk(
                sampleId = sampleId,
                styleProfileId = scope,
                chunkIndex = chunk.index,
                text = chunk.text,
                hash = chunk.hash
            )
        }
        // 插入后必须带上自增 id，否则向量写回会落到 id=0 行而失败
        val chunkIds = tagDao.insertChunks(rows)
        vectorizeChunks(
            rows.mapIndexed { index, chunk -> chunk.copy(id = chunkIds.getOrElse(index) { 0L }) },
            embed
        )
        return sampleId
    }

    /** 对尚未向量化的片段补向量；批量交给现有 Embedding 网关 */
    suspend fun vectorizeChunks(
        chunks: List<StyleSampleChunk>,
        embed: suspend (List<String>) -> Result<List<FloatArray>> = { Result.success(emptyList()) }
    ) {
        chunks.chunked(VECTOR_BATCH).forEach { batch ->
            val result = runCatching { embed(batch.map { it.text }) }.getOrNull() ?: return@forEach
            val vectors = result.getOrNull() ?: return@forEach
            if (vectors.size != batch.size) return@forEach
            batch.forEachIndexed { index, chunk ->
                val vector = vectors[index]
                if (vector.isEmpty()) return@forEachIndexed
                tagDao.updateChunkVector(chunk.id, vector.size, encodeVector(vector))
            }
        }
    }

    /**
     * 绑定 / 重新绑定样本到另一个档案。
     * 片段与样本的 styleProfileId 一起更新，并清理原档案专属标签关联；
     * 全局标签关联保留。原文、片段、向量均不删除。
     */
    suspend fun bindSample(sampleId: Long, profileId: Long) = db.withTransaction {
        require(profileId > 0L && profileDao.getById(profileId) != null) { "文风档案不存在" }
        val sample = tagDao.getSample(sampleId) ?: throw IllegalArgumentException("样本不存在")
        tagDao.setSampleProfile(sampleId, profileId, System.currentTimeMillis())
        tagDao.setChunksProfile(sampleId, profileId)
        val chunkIds = tagDao.chunksOfSample(sampleId).map { it.id }
        if (chunkIds.isNotEmpty()) tagDao.deleteForeignProfileLinks(chunkIds, profileId)
        sample
    }

    /** 解绑：样本与片段都不再参与任何档案的 RAG 检索；原文、向量、标签关联全部保留 */
    suspend fun unbindSample(sampleId: Long) = db.withTransaction {
        tagDao.setSampleProfile(sampleId, null, System.currentTimeMillis())
        tagDao.setChunksProfile(sampleId, GLOBAL_STYLE_TAG_SCOPE)
    }

    suspend fun deleteSample(sampleId: Long) = db.withTransaction {
        val chunks = tagDao.chunksOfSample(sampleId)
        if (chunks.isNotEmpty()) tagDao.deleteLinksOfChunks(chunks.map { it.id })
        tagDao.deleteChunksOfSample(sampleId)
        tagDao.deleteSample(sampleId)
    }

    suspend fun setChunkTags(chunkId: Long, tagIds: Collection<Long>, source: String = StyleTagSource.USER) {
        val chunk = tagDao.getChunk(chunkId) ?: return
        val links = tagIds.distinct().mapNotNull { tagId ->
            tagDao.getTag(tagId)?.let { tag ->
                // 只允许全局标签或与片段同一档案的专属标签
                if (tag.styleProfileId != 0L && tag.styleProfileId != chunk.styleProfileId) return@mapNotNull null
                StyleSampleTagLink(chunkId = chunkId, tagId = tagId, source = source)
            }
        }
        if (links.isNotEmpty()) tagDao.insertLinks(links)
    }

    suspend fun addChunkTag(chunkId: Long, tagId: Long, source: String) {
        setChunkTags(chunkId, listOf(tagId), source)
    }

    /** AI 重新分析片段：替换该片段的 AI 标签关联，保留用户手动关联 */
    suspend fun replaceChunkTagsFromAi(chunkId: Long, tagIds: Collection<Long>) = db.withTransaction {
        tagDao.deleteLinksOfChunkBySource(chunkId, StyleTagSource.AI)
        setChunkTags(chunkId, tagIds, StyleTagSource.AI)
    }

    suspend fun removeChunkTag(chunkId: Long, tagId: Long) = tagDao.deleteLink(chunkId, tagId)

    // ---------------- 章节梗概标签 ----------------

    fun observeSummaryTags(chapterId: Long): Flow<List<ChapterSummaryTag>> =
        summaryTagDao.observe(chapterId)

    suspend fun summaryTags(chapterId: Long): List<ChapterSummaryTag> = summaryTagDao.get(chapterId)

    /**
     * 保存梗概标签。默认保留用户手动标签，只替换 AI 生成的标签；
     * 梗概变更时由调用方传入新的 [summaryHash] 并重新分析。
     */
    suspend fun saveSummaryTags(
        chapterId: Long,
        summaryHash: String,
        tags: List<ChapterSummaryTag>,
        keepManual: Boolean = true
    ) = db.withTransaction {
        if (keepManual) summaryTagDao.deleteAiByChapter(chapterId)
        else summaryTagDao.deleteByChapter(chapterId)
        if (tags.isNotEmpty()) {
            summaryTagDao.insertAll(tags.map { it.copy(chapterId = chapterId, summaryHash = summaryHash) })
        }
    }

    suspend fun replaceSummaryTags(chapterId: Long, summaryHash: String, tags: List<ChapterSummaryTag>) =
        db.withTransaction {
            summaryTagDao.deleteByChapter(chapterId)
            summaryTagDao.insertAll(tags.map { it.copy(chapterId = chapterId, summaryHash = summaryHash) })
        }

    suspend fun deleteSummaryTag(id: Long) = summaryTagDao.deleteById(id)

    /**
     * 用户为「本章某一个句段」多选标签后保存：先删除该梗概版本下这一句段的全部旧关联，
     * 再按本次多选重建（manual = true，带完整句段定位，来源按标签归属记为全局或档案）。
     * 保存后这些标签与自动关联同权，可直接参与正文抽样。
     */
    suspend fun replaceSegmentSummaryTags(
        chapterId: Long,
        summaryHash: String,
        sentenceIndex: Int,
        sentenceStart: Int,
        sentenceEnd: Int,
        sentenceText: String,
        tags: List<StyleTag>
    ) = db.withTransaction {
        summaryTagDao.deleteBySegment(chapterId, summaryHash, sentenceIndex)
        if (tags.isNotEmpty()) {
            summaryTagDao.insertAll(
                tags.distinctBy { it.id }.map { tag ->
                    ChapterSummaryTag(
                        chapterId = chapterId,
                        category = tag.category,
                        name = tag.name,
                        tagId = tag.id,
                        source = if (tag.styleProfileId == GLOBAL_STYLE_TAG_SCOPE) {
                            com.lianwenqi.app.data.entity.SummaryTagSource.USER_GLOBAL
                        } else {
                            com.lianwenqi.app.data.entity.SummaryTagSource.PROFILE
                        },
                        summaryHash = summaryHash,
                        manual = true,
                        sentenceIndex = sentenceIndex,
                        sentenceStart = sentenceStart,
                        sentenceEnd = sentenceEnd,
                        sentenceText = sentenceText
                    )
                }
            )
        }
    }

    // ---------------- 档案删除清理 ----------------

    /**
     * 删除档案时调用（在同一事务外由 [StyleRepository] 组织）：
     * 档案标签与分类删除、片段解除标签关联、样本解绑；不删原文 / 向量。
     */
    suspend fun detachProfile(profileId: Long) {
        val tags = tagDao.getTags(profileId)
        tags.forEach { tagDao.deleteLinksOfTag(it.id) }
        tagDao.deleteTags(profileId)
        tagDao.deleteCategories(profileId)
        val samples = tagDao.getSamples(profileId)
        samples.forEach { sample ->
            tagDao.setSampleProfile(sample.id, null, System.currentTimeMillis())
            tagDao.setChunksProfile(sample.id, GLOBAL_STYLE_TAG_SCOPE)
        }
    }

    companion object {
        const val VECTOR_BATCH = 8
    }
}
