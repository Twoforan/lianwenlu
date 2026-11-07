package com.lianwenqi.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lianwenqi.app.data.entity.VolumeCompressionBatchRecord
import com.lianwenqi.app.data.entity.VolumeCompressionSession
import kotlinx.coroutines.flow.Flow

/**
 * 压缩任务与会话批次读写。
 *
 * 状态流转由 VolumeCompressionCoordinator 负责；这里只做原子 SQL，
 * 批次写入使用 (sessionId, kind, batchIndex) 唯一索引覆盖，保证重跑同一请求不重复累计。
 */
@Dao
interface VolumeCompressionDao {
    @Query("SELECT * FROM volume_compression_session WHERE id = :id")
    suspend fun sessionById(id: Long): VolumeCompressionSession?

    @Query("SELECT * FROM volume_compression_session WHERE id = :id")
    fun observeSession(id: Long): Flow<VolumeCompressionSession?>

    @Query("SELECT * FROM volume_compression_session WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC")
    fun observeByBook(bookId: Long): Flow<List<VolumeCompressionSession>>

    /** 某分卷当前的活跃任务（等待中 / 运行中 / 等待确认）；用于同卷去重 */
    @Query(
        "SELECT * FROM volume_compression_session WHERE volumeId = :volumeId " +
            "AND status IN ('PENDING', 'RUNNING', 'AWAITING_CONFIRMATION') " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1"
    )
    suspend fun activeByVolume(volumeId: Long): VolumeCompressionSession?

    @Query(
        "SELECT * FROM volume_compression_session WHERE volumeId = :volumeId " +
            "AND status IN ('PENDING', 'RUNNING', 'AWAITING_CONFIRMATION') " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1"
    )
    fun observeActiveByVolume(volumeId: Long): Flow<VolumeCompressionSession?>

    /** 同一本书占用运行位的任务（等待中 / 运行中），用于排队位置计算 */
    @Query(
        "SELECT * FROM volume_compression_session WHERE bookId = :bookId " +
            "AND status IN ('PENDING', 'RUNNING') ORDER BY createdAt ASC, id ASC"
    )
    suspend fun inFlightByBook(bookId: Long): List<VolumeCompressionSession>

    @Query(
        "SELECT * FROM volume_compression_session WHERE bookId = :bookId " +
            "ORDER BY createdAt DESC, id DESC"
    )
    suspend fun sessionsByBook(bookId: Long): List<VolumeCompressionSession>

    @Query(
        "SELECT * FROM volume_compression_session WHERE status IN ('PENDING', 'RUNNING') " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun unfinishedSessions(): List<VolumeCompressionSession>

    @Insert
    suspend fun insertSession(session: VolumeCompressionSession): Long

    @Update
    suspend fun updateSession(session: VolumeCompressionSession)

    @Query("DELETE FROM volume_compression_session WHERE bookId = :bookId")
    suspend fun deleteSessionsByBook(bookId: Long)

    @Query("DELETE FROM volume_compression_session WHERE volumeId = :volumeId")
    suspend fun deleteSessionsByVolume(volumeId: Long)

    /** 只更新规划 / 预估字段，避免覆盖并发取消写入的状态 */
    @Query(
        "UPDATE volume_compression_session SET sourceRevision = :sourceRevision, " +
            "plannedBatchCount = :plannedBatchCount, plannedRequestCount = :plannedRequestCount, " +
            "estimatedInputTokens = :estimatedInputTokens, estimatedOutputTokens = :estimatedOutputTokens, " +
            "updatedAt = :time WHERE id = :id"
    )
    suspend fun updatePlan(
        id: Long,
        sourceRevision: String,
        plannedBatchCount: Int,
        plannedRequestCount: Int,
        estimatedInputTokens: Int,
        estimatedOutputTokens: Int,
        time: Long
    )

    /** 只更新真实 usage 与完成批次数；usage 汇总永远来自批次记录，不靠累加内存值 */
    @Query(
        "UPDATE volume_compression_session SET completedBatchCount = :completedBatchCount, " +
            "actualRequestCount = :actualRequestCount, promptTokens = :promptTokens, " +
            "completionTokens = :completionTokens, cachedTokens = :cachedTokens, " +
            "requestsWithUsage = :requestsWithUsage, updatedAt = :time WHERE id = :id"
    )
    suspend fun updateUsage(
        id: Long,
        completedBatchCount: Int,
        actualRequestCount: Int,
        promptTokens: Int,
        completionTokens: Int,
        cachedTokens: Int,
        requestsWithUsage: Int,
        time: Long
    )

    /** 只推进阶段；调用方需先确认任务仍是 RUNNING */
    @Query("UPDATE volume_compression_session SET phase = :phase, updatedAt = :time WHERE id = :id")
    suspend fun updatePhase(id: Long, phase: String, time: Long)

    // ---------------- 批次 ----------------

    @Query(
        "SELECT * FROM volume_compression_batch WHERE sessionId = :sessionId " +
            "ORDER BY kind ASC, batchIndex ASC, id ASC"
    )
    suspend fun batches(sessionId: Long): List<VolumeCompressionBatchRecord>

    @Query(
        "SELECT * FROM volume_compression_batch WHERE sessionId = :sessionId " +
            "ORDER BY kind ASC, batchIndex ASC, id ASC"
    )
    fun observeBatches(sessionId: Long): Flow<List<VolumeCompressionBatchRecord>>

    @Query(
        "SELECT * FROM volume_compression_batch WHERE sessionId = :sessionId " +
            "AND kind = :kind AND batchIndex = :batchIndex LIMIT 1"
    )
    suspend fun batch(sessionId: Long, kind: String, batchIndex: Int): VolumeCompressionBatchRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBatch(record: VolumeCompressionBatchRecord): Long

    @Update
    suspend fun updateBatch(record: VolumeCompressionBatchRecord)

    @Query("DELETE FROM volume_compression_batch WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun deleteBatchesByKind(sessionId: Long, kind: String)

    @Query("DELETE FROM volume_compression_batch WHERE sessionId = :sessionId")
    suspend fun deleteBatches(sessionId: Long)

    @Query("DELETE FROM volume_compression_batch WHERE sessionId IN (SELECT id FROM volume_compression_session WHERE bookId = :bookId)")
    suspend fun deleteBatchesByBook(bookId: Long)

    @Query("DELETE FROM volume_compression_batch WHERE sessionId IN (SELECT id FROM volume_compression_session WHERE volumeId = :volumeId)")
    suspend fun deleteBatchesByVolume(volumeId: Long)

    /** 进程重启：把仍显示 RUNNING 的批次统一标记为中断失败，避免把部分结果当成功 */
    @Query(
        "UPDATE volume_compression_batch SET status = 'FAILED', errorKind = 'INTERRUPTED', " +
            "errorMessage = '上次压缩被中断，可恢复重试该批', updatedAt = :time WHERE status = 'RUNNING'"
    )
    suspend fun markRunningBatchesInterrupted(time: Long)

    @Query(
        "UPDATE volume_compression_batch SET status = 'CANCELLED', updatedAt = :time " +
            "WHERE sessionId = :sessionId AND status IN ('PENDING', 'RUNNING')"
    )
    suspend fun markSessionBatchesCancelled(sessionId: Long, time: Long)
}
