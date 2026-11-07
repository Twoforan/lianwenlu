package com.lianwenqi.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.lianwenqi.app.data.entity.VolumeSummary
import kotlinx.coroutines.flow.Flow

/**
 * 整卷梗概读写。
 *
 * 状态切换（候选确认 / 恢复旧版）一律由 VolumeSummaryRepository 在同一事务内编排，
 * 这里只提供原子 SQL，不在这里做业务判断。
 */
@Dao
interface VolumeSummaryDao {
    /** 某本书的全部当前生效卷梗概 */
    @Query(
        "SELECT * FROM volume_summary WHERE bookId = :bookId AND status = 'ACTIVE' " +
            "ORDER BY volumeId ASC, id ASC"
    )
    fun observeActiveByBook(bookId: Long): Flow<List<VolumeSummary>>

    /** 某本书的全部当前生效卷梗概（一次性读取，供上下文选择过滤 / 推荐计算） */
    @Query(
        "SELECT * FROM volume_summary WHERE bookId = :bookId AND status = 'ACTIVE' " +
            "ORDER BY volumeId ASC, id ASC"
    )
    suspend fun activeListByBook(bookId: Long): List<VolumeSummary>

    /** 某分卷的当前生效卷梗概；没有时为 null */
    @Query("SELECT * FROM volume_summary WHERE volumeId = :volumeId AND status = 'ACTIVE' LIMIT 1")
    fun observeActiveByVolume(volumeId: Long): Flow<VolumeSummary?>

    @Query("SELECT * FROM volume_summary WHERE volumeId = :volumeId AND status = 'ACTIVE' LIMIT 1")
    suspend fun activeByVolume(volumeId: Long): VolumeSummary?

    @Query("SELECT * FROM volume_summary WHERE id = :id")
    suspend fun getById(id: Long): VolumeSummary?

    @Query(
        "SELECT * FROM volume_summary WHERE volumeId = :volumeId AND status = 'ARCHIVED' " +
            "ORDER BY updatedAt DESC, id DESC"
    )
    suspend fun archivedByVolume(volumeId: Long): List<VolumeSummary>

    @Query(
        "SELECT * FROM volume_summary WHERE volumeId = :volumeId AND status = 'CANDIDATE' " +
            "ORDER BY updatedAt DESC, id DESC"
    )
    suspend fun candidatesByVolume(volumeId: Long): List<VolumeSummary>

    /** 某个压缩任务产出的全部卷梗概（F5 任务结果展示用） */
    @Query("SELECT * FROM volume_summary WHERE compressionSessionId = :compressionSessionId ORDER BY id ASC")
    suspend fun byCompressionSession(compressionSessionId: Long): List<VolumeSummary>

    @Insert
    suspend fun insert(summary: VolumeSummary): Long

    @Update
    suspend fun update(summary: VolumeSummary)

    @Query("DELETE FROM volume_summary WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 删除分卷时级联清理该卷的卷梗概（含候选与归档） */
    @Query("DELETE FROM volume_summary WHERE volumeId = :volumeId")
    suspend fun deleteByVolume(volumeId: Long)

    /** 删除书籍 / 整本覆盖恢复时级联清理 */
    @Query("DELETE FROM volume_summary WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    /**
     * 把该卷的当前生效版本转为归档（同时清空 isCurrent，释放唯一索引槽位）。
     * 必须在切换事务里、更新新版本之前调用。
     */
    @Query(
        "UPDATE volume_summary SET status = 'ARCHIVED', isCurrent = NULL, updatedAt = :time " +
            "WHERE volumeId = :volumeId AND status = 'ACTIVE'"
    )
    suspend fun archiveActive(volumeId: Long, time: Long)
}
