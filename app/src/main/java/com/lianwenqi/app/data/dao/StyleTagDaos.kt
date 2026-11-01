package com.lianwenqi.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.StyleSampleChunk
import com.lianwenqi.app.data.entity.StyleSampleRecord
import com.lianwenqi.app.data.entity.StyleSampleTagLink
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagCategory
import kotlinx.coroutines.flow.Flow

/** 片段上的一个标签（检索时用于标签匹配打分） */
data class StyleChunkTagRow(
    val chunkId: Long,
    val tagId: Long,
    val category: String,
    val name: String,
    val scope: Long,
    val source: String
)

@Dao
interface StyleTagDao {
    // ---------------- 分类 ----------------

    @Query("SELECT * FROM style_tag_category WHERE styleProfileId = :scope ORDER BY name ASC")
    fun observeCategories(scope: Long): Flow<List<StyleTagCategory>>

    @Query("SELECT * FROM style_tag_category WHERE styleProfileId = :scope ORDER BY name ASC")
    suspend fun getCategories(scope: Long): List<StyleTagCategory>

    @Query("SELECT * FROM style_tag_category WHERE id = :id")
    suspend fun getCategory(id: Long): StyleTagCategory?

    @Query("SELECT * FROM style_tag_category WHERE styleProfileId = :scope AND name = :name LIMIT 1")
    suspend fun findCategory(scope: Long, name: String): StyleTagCategory?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: StyleTagCategory): Long

    @Update
    suspend fun updateCategory(category: StyleTagCategory)

    @Query("DELETE FROM style_tag_category WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("DELETE FROM style_tag_category WHERE styleProfileId = :scope")
    suspend fun deleteCategories(scope: Long)

    // ---------------- 标签 ----------------

    @Query("SELECT * FROM style_tag WHERE styleProfileId = :scope ORDER BY category ASC, name ASC")
    fun observeTags(scope: Long): Flow<List<StyleTag>>

    @Query("SELECT * FROM style_tag WHERE styleProfileId = :scope ORDER BY category ASC, name ASC")
    suspend fun getTags(scope: Long): List<StyleTag>

    @Query("SELECT * FROM style_tag WHERE id = :id")
    suspend fun getTag(id: Long): StyleTag?

    @Query("SELECT * FROM style_tag WHERE styleProfileId = :scope AND name = :name LIMIT 1")
    suspend fun findTagByName(scope: Long, name: String): StyleTag?

    @Query("SELECT * FROM style_tag WHERE styleProfileId = :scope AND category = :category AND name = :name LIMIT 1")
    suspend fun findTag(scope: Long, category: String, name: String): StyleTag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: StyleTag): Long

    @Update
    suspend fun updateTag(tag: StyleTag)

    @Query("DELETE FROM style_tag WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query("DELETE FROM style_tag WHERE styleProfileId = :scope")
    suspend fun deleteTags(scope: Long)

    @Query("DELETE FROM style_tag WHERE styleProfileId = :scope AND category = :category")
    suspend fun deleteTagsInCategory(scope: Long, category: String)

    @Query("UPDATE style_tag SET category = :to WHERE styleProfileId = :scope AND category = :from")
    suspend fun renameTagCategory(scope: Long, from: String, to: String)

    /** 标签被哪些文本片段使用 */
    @Query(
        "SELECT c.* FROM style_sample_chunk c INNER JOIN style_sample_tag l ON l.chunkId = c.id " +
            "WHERE l.tagId = :tagId ORDER BY c.sampleId ASC, c.chunkIndex ASC"
    )
    suspend fun chunksUsingTag(tagId: Long): List<StyleSampleChunk>

    @Query("SELECT COUNT(*) FROM style_sample_tag WHERE tagId = :tagId")
    suspend fun tagUsageCount(tagId: Long): Int

    // ---------------- 样本 ----------------

    @Query("SELECT * FROM style_sample ORDER BY createdAt DESC, id DESC")
    fun observeAllSamples(): Flow<List<StyleSampleRecord>>

    @Query("SELECT * FROM style_sample WHERE styleProfileId = :profileId ORDER BY createdAt DESC, id DESC")
    fun observeSamples(profileId: Long): Flow<List<StyleSampleRecord>>

    @Query("SELECT * FROM style_sample WHERE styleProfileId IS NULL ORDER BY createdAt DESC, id DESC")
    fun observeUnboundSamples(): Flow<List<StyleSampleRecord>>

    @Query("SELECT * FROM style_sample WHERE styleProfileId = :profileId ORDER BY createdAt DESC, id DESC")
    suspend fun getSamples(profileId: Long): List<StyleSampleRecord>

    @Query("SELECT * FROM style_sample WHERE id = :id")
    suspend fun getSample(id: Long): StyleSampleRecord?

    @Query("SELECT * FROM style_sample")
    suspend fun getAllSamples(): List<StyleSampleRecord>

    @Insert
    suspend fun insertSample(sample: StyleSampleRecord): Long

    @Update
    suspend fun updateSample(sample: StyleSampleRecord)

    @Query("UPDATE style_sample SET styleProfileId = :profileId, updatedAt = :time WHERE id = :id")
    suspend fun setSampleProfile(id: Long, profileId: Long?, time: Long)

    @Query("DELETE FROM style_sample WHERE id = :id")
    suspend fun deleteSample(id: Long)

    // ---------------- 片段 ----------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<StyleSampleChunk>): List<Long>

    @Query("SELECT * FROM style_sample_chunk WHERE sampleId = :sampleId ORDER BY chunkIndex ASC")
    suspend fun chunksOfSample(sampleId: Long): List<StyleSampleChunk>

    @Query("SELECT * FROM style_sample_chunk WHERE styleProfileId = :profileId ORDER BY sampleId ASC, chunkIndex ASC")
    suspend fun chunksOfProfile(profileId: Long): List<StyleSampleChunk>

    @Query("SELECT * FROM style_sample_chunk WHERE id = :id")
    suspend fun getChunk(id: Long): StyleSampleChunk?

    @Query("UPDATE style_sample_chunk SET styleProfileId = :profileId WHERE sampleId = :sampleId")
    suspend fun setChunksProfile(sampleId: Long, profileId: Long)

    @Query("UPDATE style_sample_chunk SET dim = :dim, vector = :vector WHERE id = :id")
    suspend fun updateChunkVector(id: Long, dim: Int, vector: ByteArray?)

    @Query("DELETE FROM style_sample_chunk WHERE sampleId = :sampleId")
    suspend fun deleteChunksOfSample(sampleId: Long)

    @Query("SELECT * FROM style_sample_chunk")
    suspend fun allChunks(): List<StyleSampleChunk>

    /** 某档案下所有片段的标签（一次查询，避免逐片段 N+1） */
    @Query(
        "SELECT l.chunkId AS chunkId, t.id AS tagId, t.category AS category, t.name AS name, " +
            "t.styleProfileId AS scope, t.source AS source " +
            "FROM style_sample_tag l INNER JOIN style_tag t ON t.id = l.tagId " +
            "INNER JOIN style_sample_chunk c ON c.id = l.chunkId WHERE c.styleProfileId = :profileId"
    )
    suspend fun chunkTagRowsOfProfile(profileId: Long): List<StyleChunkTagRow>

    // ---------------- 片段-标签关联 ----------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<StyleSampleTagLink>)

    @Query("SELECT * FROM style_sample_tag WHERE chunkId = :chunkId")
    suspend fun linksOfChunk(chunkId: Long): List<StyleSampleTagLink>

    @Query(
        "SELECT t.* FROM style_tag t INNER JOIN style_sample_tag l ON l.tagId = t.id WHERE l.chunkId = :chunkId"
    )
    suspend fun tagsOfChunk(chunkId: Long): List<StyleTag>

    @Query("SELECT * FROM style_sample_tag WHERE tagId = :tagId")
    suspend fun linksOfTag(tagId: Long): List<StyleSampleTagLink>

    @Query("DELETE FROM style_sample_tag WHERE tagId = :tagId")
    suspend fun deleteLinksOfTag(tagId: Long)

    @Query("DELETE FROM style_sample_tag WHERE chunkId = :chunkId")
    suspend fun deleteLinksOfChunk(chunkId: Long)

    /** 重新分析时只替换 AI 标注，保留用户手动添加的关联 */
    @Query("DELETE FROM style_sample_tag WHERE chunkId = :chunkId AND source = :source")
    suspend fun deleteLinksOfChunkBySource(chunkId: Long, source: String)

    @Query("DELETE FROM style_sample_tag WHERE chunkId = :chunkId AND tagId = :tagId")
    suspend fun deleteLink(chunkId: Long, tagId: Long)

    @Query("DELETE FROM style_sample_tag WHERE chunkId IN (:chunkIds)")
    suspend fun deleteLinksOfChunks(chunkIds: List<Long>)

    /** 重绑 / 清理时只保留全局标签与新档案的专属标签 */
    @Query(
        "DELETE FROM style_sample_tag WHERE chunkId IN (:chunkIds) AND tagId IN (" +
            "SELECT id FROM style_tag WHERE styleProfileId != 0 AND styleProfileId != :keepProfileId)"
    )
    suspend fun deleteForeignProfileLinks(chunkIds: List<Long>, keepProfileId: Long)
}

@Dao
interface ChapterSummaryTagDao {
    @Query("SELECT * FROM chapter_summary_tag WHERE chapterId = :chapterId ORDER BY category ASC, name ASC")
    fun observe(chapterId: Long): Flow<List<ChapterSummaryTag>>

    @Query("SELECT * FROM chapter_summary_tag WHERE chapterId = :chapterId ORDER BY category ASC, name ASC")
    suspend fun get(chapterId: Long): List<ChapterSummaryTag>

    @Insert
    suspend fun insertAll(tags: List<ChapterSummaryTag>)

    @Query("DELETE FROM chapter_summary_tag WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: Long)

    @Query("DELETE FROM chapter_summary_tag WHERE chapterId = :chapterId AND manual = 0")
    suspend fun deleteAiByChapter(chapterId: Long)

    @Query("DELETE FROM chapter_summary_tag WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE chapter_summary_tag SET summaryHash = :hash WHERE chapterId = :chapterId")
    suspend fun updateHash(chapterId: Long, hash: String)
}
