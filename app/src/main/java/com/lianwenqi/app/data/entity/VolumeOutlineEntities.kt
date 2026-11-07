package com.lianwenqi.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * F6：一次压缩任务的大纲差异分析结果。
 *
 * 与 [VolumeCompressionSession] 一一关联（sessionId 唯一），
 * 用于进程 / 页面切换后恢复未确认的建议；不引入任何平行大纲实体。
 */
@Entity(
    tableName = "volume_outline_update",
    indices = [
        Index(value = ["sessionId"], unique = true),
        Index(value = ["bookId"]),
        Index(value = ["volumeId"])
    ]
)
data class VolumeOutlineUpdate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val bookId: Long,
    val volumeId: Long,
    /** 本次配置快照中的大纲更新模式：ON / ASK / OFF */
    val mode: String,
    /** 见 VolumeOutlineUpdateStatus（ANALYZING / READY / FAILED） */
    val status: String,
    /** 解析后的目标 OutlineItem.id 快照（逗号分隔）；不会在任务中悄悄变化 */
    val scopeIds: String = "",
    val scopeLabel: String = "",
    val summaryId: Long? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val requestCount: Int = 0,
    val requestsWithUsage: Int = 0,
    val rawResponse: String = "",
    val errorMessage: String = "",
    val note: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/** 一条持久化的差异建议；targetOutlineItemId 是唯一更新身份，标题只用于展示 */
@Entity(
    tableName = "volume_outline_suggestion",
    indices = [
        Index(value = ["updateId"]),
        Index(value = ["sessionId"]),
        Index(value = ["updateId", "suggestionIndex"], unique = true)
    ]
)
data class VolumeOutlineSuggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val updateId: Long,
    val sessionId: Long,
    val suggestionIndex: Int,
    /** ADD / UPDATE / DELETE / UNKNOWN */
    val operation: String,
    val targetOutlineItemId: Long? = null,
    val category: String = "",
    val title: String = "",
    val oldContent: String = "",
    val newContent: String = "",
    val reason: String = "",
    val confidence: Double = 0.0,
    /** 多行文本存储，避免额外序列化依赖 */
    val relatedEvents: String = "",
    val expectedUpdatedAt: Long = 0,
    val contentFingerprint: String = "",
    /** 见 OutlineSuggestionStatus */
    val status: String,
    val statusMessage: String = "",
    val selected: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)
