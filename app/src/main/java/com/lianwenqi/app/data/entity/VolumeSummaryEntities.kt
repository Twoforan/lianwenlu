package com.lianwenqi.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 卷梗概状态：候选（未生效）/ 当前生效 / 历史归档 */
object VolumeSummaryStatus {
    const val CANDIDATE = "CANDIDATE"
    const val ACTIVE = "ACTIVE"
    const val ARCHIVED = "ARCHIVED"

    fun normalize(raw: String): String = when (raw) {
        CANDIDATE, ACTIVE, ARCHIVED -> raw
        else -> CANDIDATE
    }

    /** 可以被备份导出 / 恢复的状态（候选属于未确认的压缩任务，不进备份） */
    val persisted: List<String> = listOf(ACTIVE, ARCHIVED)
}

/** 卷梗概的输入来源：章节梗概 / 章节正文 */
object VolumeSummarySourceType {
    const val SYNOPSIS = "SYNOPSIS"
    const val CONTENT = "CONTENT"

    fun normalize(raw: String): String = if (raw == CONTENT) CONTENT else SYNOPSIS
}

/**
 * 整卷梗概：一个分卷的压缩结果，独立于 [Chapter.summary]（绝不写入章节梗概）。
 *
 * 版本关系用单表 + 状态表达：
 * - 每卷至多一条 [VolumeSummaryStatus.ACTIVE]，由唯一索引 (volumeId, isCurrent) 保证：
 *   只有生效版把 [isCurrent] 置为 true，其余版本为 null（SQLite 唯一索引允许多个 NULL）。
 * - 重新压缩先写入 [VolumeSummaryStatus.CANDIDATE]，确认后才在同一事务里替换生效版。
 * - 旧生效版转为 [VolumeSummaryStatus.ARCHIVED]，每卷最多保留 [MAX_ARCHIVED] 条可恢复版本。
 *
 * 内容关系：[modelContent] 恒为模型原始输出；[userContent] 为用户编辑结果（空 = 未编辑）。
 * [effectiveContent] 是界面展示 / 后续生成实际使用的内容，用户编辑永远优先。
 */
@Entity(
    tableName = "volume_summary",
    indices = [
        Index("bookId"),
        Index("volumeId"),
        Index("compressionSessionId"),
        Index(value = ["volumeId", "isCurrent"], unique = true)
    ]
)
data class VolumeSummary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val volumeId: Long,
    /** 存储值见 [VolumeSummaryStatus] */
    val status: String = VolumeSummaryStatus.CANDIDATE,
    /** 存储值见 [VolumeSummarySourceType] */
    val sourceType: String = VolumeSummarySourceType.SYNOPSIS,
    /** 模型原始输出；非空才允许成为生效版本 */
    val modelContent: String = "",
    /** 用户编辑结果；为空表示未编辑，生效内容回落到 [modelContent] */
    val userContent: String = "",
    /** 生成时输入内容的指纹（见 VolumeSourceRevision），用于过期检测 */
    val sourceRevision: String = "",
    /** 生成时该卷有效章节总数（输入章节总数） */
    val sourceChapterCount: Int = 0,
    /** 实际参与压缩的章节数 */
    val processedChapterCount: Int = 0,
    /** 参与压缩的章节 id（逗号分隔，按 sortOrder,id 保序） */
    val sourceChapterIds: String = "",
    /** 与 [sourceChapterIds] 一一对应的内容指纹（逗号分隔） */
    val sourceChapterHashes: String = "",
    /** 生成时使用的模型 id（0 = 未知）；仅作历史记录 */
    @ColumnInfo(defaultValue = "0")
    val modelId: Long = 0,
    /** 生成时使用的模型名称，id 对不上时仍可展示 */
    @ColumnInfo(defaultValue = "''")
    val modelName: String = "",
    /** 所属压缩任务（F5 的压缩会话 id）；为空表示不属于任何任务 */
    val compressionSessionId: Long? = null,
    /**
     * 是否当前生效版本：true = 生效；null = 非生效（候选 / 归档）。
     * 不能存 false：唯一索引 (volumeId, isCurrent) 下多个 false 会互相冲突。
     */
    val isCurrent: Boolean? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val statusEnumValue: String get() = VolumeSummaryStatus.normalize(status)

    val sourceTypeEnumValue: String get() = VolumeSummarySourceType.normalize(sourceType)

    val isActive: Boolean get() = status == VolumeSummaryStatus.ACTIVE

    /** 生效内容：用户编辑优先，未编辑时回落模型原文 */
    val effectiveContent: String get() = userContent.ifBlank { modelContent }

    fun sourceChapterIdList(): List<Long> =
        sourceChapterIds.split(',').mapNotNull { it.trim().toLongOrNull() }

    fun sourceChapterHashList(): List<String> =
        sourceChapterHashes.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        /** 每卷保留的可恢复归档版本数量 */
        const val MAX_ARCHIVED = 1
    }
}
