package com.lianwenqi.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lianwenqi.app.data.entity.VolumeOutlineSuggestion
import com.lianwenqi.app.data.entity.VolumeOutlineUpdate
import kotlinx.coroutines.flow.Flow

/**
 * F6：大纲差异分析结果与逐条建议。
 *
 * 建议只与压缩 session 关联，不承载大纲实体本身；正式写入仍然走 OutlineDao。
 */
@Dao
interface VolumeOutlineDao {
    @Query("SELECT * FROM volume_outline_update WHERE sessionId = :sessionId LIMIT 1")
    suspend fun updateBySession(sessionId: Long): VolumeOutlineUpdate?

    @Query("SELECT * FROM volume_outline_update WHERE id = :id LIMIT 1")
    suspend fun updateById(id: Long): VolumeOutlineUpdate?

    @Query("SELECT * FROM volume_outline_update WHERE sessionId = :sessionId LIMIT 1")
    fun observeUpdate(sessionId: Long): Flow<VolumeOutlineUpdate?>

    @Insert
    suspend fun insertUpdate(update: VolumeOutlineUpdate): Long

    @Update
    suspend fun updateUpdate(update: VolumeOutlineUpdate)

    @Query(
        "SELECT * FROM volume_outline_suggestion WHERE updateId = :updateId " +
            "ORDER BY suggestionIndex ASC, id ASC"
    )
    suspend fun suggestions(updateId: Long): List<VolumeOutlineSuggestion>

    @Query(
        "SELECT * FROM volume_outline_suggestion WHERE updateId = :updateId " +
            "ORDER BY suggestionIndex ASC, id ASC"
    )
    fun observeSuggestions(updateId: Long): Flow<List<VolumeOutlineSuggestion>>

    @Query(
        "SELECT * FROM volume_outline_suggestion WHERE sessionId = :sessionId " +
            "ORDER BY suggestionIndex ASC, id ASC"
    )
    fun observeSuggestionsBySession(sessionId: Long): Flow<List<VolumeOutlineSuggestion>>

    @Query(
        "SELECT * FROM volume_outline_suggestion WHERE sessionId = :sessionId " +
            "ORDER BY suggestionIndex ASC, id ASC"
    )
    suspend fun suggestionsBySession(sessionId: Long): List<VolumeOutlineSuggestion>

    @Query("SELECT * FROM volume_outline_suggestion WHERE id IN (:ids)")
    suspend fun suggestionsByIds(ids: List<Long>): List<VolumeOutlineSuggestion>

    @Insert
    suspend fun insertSuggestions(items: List<VolumeOutlineSuggestion>)

    @Update
    suspend fun updateSuggestion(item: VolumeOutlineSuggestion)

    @Query("DELETE FROM volume_outline_suggestion WHERE updateId = :updateId")
    suspend fun deleteSuggestions(updateId: Long)

    @Query("DELETE FROM volume_outline_suggestion WHERE sessionId = :sessionId")
    suspend fun deleteSuggestionsBySession(sessionId: Long)

    @Query("DELETE FROM volume_outline_update WHERE sessionId = :sessionId")
    suspend fun deleteUpdateBySession(sessionId: Long)

    @Query(
        "DELETE FROM volume_outline_suggestion WHERE sessionId IN " +
            "(SELECT id FROM volume_compression_session WHERE bookId = :bookId)"
    )
    suspend fun deleteSuggestionsByBook(bookId: Long)

    @Query(
        "DELETE FROM volume_outline_update WHERE bookId = :bookId"
    )
    suspend fun deleteUpdatesByBook(bookId: Long)

    @Query(
        "DELETE FROM volume_outline_suggestion WHERE sessionId IN " +
            "(SELECT id FROM volume_compression_session WHERE volumeId = :volumeId)"
    )
    suspend fun deleteSuggestionsByVolume(volumeId: Long)

    @Query("DELETE FROM volume_outline_update WHERE volumeId = :volumeId")
    suspend fun deleteUpdatesByVolume(volumeId: Long)

    /**
     * 进程重启后仍在 ANALYZING 的分析已经不可能完成；
     * 标记为 FAILED，让用户能重新发起，而不是永久转圈。
     */
    @Query(
        "UPDATE volume_outline_update SET status = 'FAILED', " +
            "errorMessage = '上次大纲分析被中断，可重新发起', updatedAt = :time " +
            "WHERE status = 'ANALYZING'"
    )
    suspend fun markAnalyzingInterrupted(time: Long)
}
