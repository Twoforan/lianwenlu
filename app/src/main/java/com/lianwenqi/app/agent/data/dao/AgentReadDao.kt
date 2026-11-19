package com.lianwenqi.app.agent.data.dao

import androidx.room.Dao
import androidx.room.Query

/** 只读取章节元信息 / 正文片段，不加载整章正文，避免超长章节触发 CursorWindow */
data class AgentChapterMeta(
    val id: Long,
    val bookId: Long,
    val volumeId: Long?,
    val title: String,
    val summary: String,
    val contentLength: Int
)

data class AgentChapterSearchHit(
    val id: Long,
    val bookId: Long,
    val volumeId: Long?,
    val title: String,
    val summarySnippet: String,
    val contentSnippet: String
)

/** F5：参考资料选择列表的章节投影；不加载正文，只返回长度与梗概片段 */
data class AgentReferenceChapterRow(
    val id: Long,
    val bookId: Long,
    val volumeId: Long?,
    val title: String,
    val synopsisSnippet: String,
    val synopsisLength: Int,
    val contentLength: Int,
    val updatedAt: Long
)

/** F5：单章引用元信息；summary 为完整梗概，content 只按长度使用 */
data class AgentReferenceChapterMeta(
    val id: Long,
    val bookId: Long,
    val volumeId: Long?,
    val title: String,
    val summary: String,
    val contentLength: Int,
    val updatedAt: Long
)

/**
 * F4 只读工具专用查询。
 *
 * 只读；不修改任何数据。所有章节查询都用于 Agent 工具，避免与编辑 / 阅读路径耦合。
 */
@Dao
interface AgentReadDao {

    @Query(
        "SELECT id, bookId, volumeId, title, summary, length(content) AS contentLength " +
            "FROM chapter WHERE id = :chapterId"
    )
    suspend fun chapterMeta(chapterId: Long): AgentChapterMeta?

    /** 读取正文子串；[offset] 从 0 开始，SQLite substr 从 1 开始，因此 +1 */
    @Query("SELECT substr(content, :offset + 1, :length) FROM chapter WHERE id = :chapterId")
    suspend fun contentSlice(chapterId: Long, offset: Int, length: Int): String?

    @Query(
        "SELECT COUNT(*) FROM chapter c LEFT JOIN volume v ON v.id = c.volumeId " +
            "WHERE c.bookId = :bookId AND (c.volumeId IS NULL OR (v.id IS NOT NULL AND v.bookId = c.bookId))"
    )
    suspend fun validChapterCount(bookId: Long): Int

    @Query(
        "SELECT c.id, c.bookId, c.volumeId, c.title, " +
            "substr(c.summary, 1, :snippet) AS summarySnippet, " +
            "substr(c.content, 1, :snippet) AS contentSnippet " +
            "FROM chapter c LEFT JOIN volume v ON v.id = c.volumeId " +
            "WHERE c.bookId = :bookId " +
            "AND (c.volumeId IS NULL OR (v.id IS NOT NULL AND v.bookId = c.bookId)) " +
            "AND (instr(lower(c.title), lower(:keyword)) > 0 " +
            "OR instr(lower(c.summary), lower(:keyword)) > 0 " +
            "OR instr(lower(c.content), lower(:keyword)) > 0) " +
            "ORDER BY c.sortOrder ASC, c.id ASC LIMIT :limit"
    )
    suspend fun searchChapterHits(
        bookId: Long,
        keyword: String,
        snippet: Int,
        limit: Int
    ): List<AgentChapterSearchHit>

    /** F5：章节梗概 / 正文候选列表；按书籍与可选分卷过滤，只读投影 */
    @Query(
        "SELECT c.id, c.bookId, c.volumeId, c.title, " +
            "substr(c.summary, 1, :snippet) AS synopsisSnippet, " +
            "length(c.summary) AS synopsisLength, " +
            "length(c.content) AS contentLength, c.updatedAt " +
            "FROM chapter c LEFT JOIN volume v ON v.id = c.volumeId " +
            "WHERE c.bookId = :bookId " +
            "AND (c.volumeId IS NULL OR (v.id IS NOT NULL AND v.bookId = c.bookId)) " +
            "AND (:volumeId IS NULL OR c.volumeId = :volumeId) " +
            "AND (:query = '' OR instr(lower(c.title), lower(:query)) > 0 " +
            "OR instr(lower(c.summary), lower(:query)) > 0) " +
            "ORDER BY c.sortOrder ASC, c.id ASC LIMIT :limit"
    )
    suspend fun referenceChapterCandidates(
        bookId: Long,
        volumeId: Long?,
        query: String,
        snippet: Int,
        limit: Int
    ): List<AgentReferenceChapterRow>

    /** F5：单章引用元信息（完整梗概 + 正文长度） */
    @Query(
        "SELECT id, bookId, volumeId, title, summary, " +
            "length(content) AS contentLength, updatedAt " +
            "FROM chapter WHERE id = :chapterId"
    )
    suspend fun referenceChapterMeta(chapterId: Long): AgentReferenceChapterMeta?
}
