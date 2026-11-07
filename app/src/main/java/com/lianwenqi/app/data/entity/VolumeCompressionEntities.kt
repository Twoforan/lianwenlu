package com.lianwenqi.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 压缩任务生命周期：等待确认不是「模型仍在请求」，必须与运行态分开 */
object VolumeCompressionStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    fun normalize(raw: String): String = when (raw) {
        PENDING, RUNNING, AWAITING_CONFIRMATION, COMPLETED, FAILED, CANCELLED -> raw
        else -> PENDING
    }

    /** 已终结：不会再有模型请求，也不再占用同书运行位 */
    val terminal: List<String> = listOf(COMPLETED, FAILED, CANCELLED)

    /** 活跃：同卷去重、同书排队只看这些状态 */
    val active: List<String> = listOf(PENDING, RUNNING, AWAITING_CONFIRMATION)

    /** 正在使用模型请求的状态；等待确认不在此列 */
    val inFlight: List<String> = listOf(PENDING, RUNNING)
}

/** 任务阶段：与状态独立，等待确认时阶段也停在等待确认 */
object VolumeCompressionPhase {
    const val QUEUED = "QUEUED"
    const val READING = "READING"
    const val EXTRACTING = "EXTRACTING"
    const val MERGING = "MERGING"
    const val AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION"
    const val DONE = "DONE"

    fun normalize(raw: String): String = when (raw) {
        QUEUED, READING, EXTRACTING, MERGING, AWAITING_CONFIRMATION, DONE -> raw
        else -> QUEUED
    }
}

/** 可区分的失败类别；[INTERRUPTED] 专指进程中断，不冒充普通请求失败 */
object VolumeCompressionErrorKind {
    const val INTERRUPTED = "INTERRUPTED"
    const val REQUEST_FAILED = "REQUEST_FAILED"
    const val INVALID_OUTPUT = "INVALID_OUTPUT"
    const val CAPACITY = "CAPACITY"
    const val INPUT_TOO_LARGE = "INPUT_TOO_LARGE"
    const val EMPTY_INPUT = "EMPTY_INPUT"
    const val CONFIG = "CONFIG"
    const val UNKNOWN = "UNKNOWN"
}

/**
 * 批次类别：章节提取 / 整合请求（含分级整合与最终整合）/ F6 大纲差异分析。
 *
 * 大纲分析作为一条独立记录并入同一张批次表，使 Token 与请求数自然进入
 * 现有 usage 汇总，不另建一套计费数据结构。
 */
object VolumeCompressionBatchKind {
    const val EXTRACTION = "EXTRACTION"
    const val MERGE = "MERGE"
    const val OUTLINE = "OUTLINE"
}

object VolumeCompressionBatchStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    fun normalize(raw: String): String = when (raw) {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED -> raw
        else -> PENDING
    }
}

/**
 * 一次整卷压缩任务（独立于 ChatSession / ChatMessage）。
 *
 * 预估字段（[estimatedInputTokens] / [estimatedOutputTokens]）来自字符启发式，
 * 永远与真实 usage（[promptTokens] / [completionTokens] / [cachedTokens]）分开存储，
 * 界面不得把预估值当作计费结果。
 */
@Entity(
    tableName = "volume_compression_session",
    indices = [
        Index("bookId"),
        Index("volumeId"),
        Index("status")
    ]
)
data class VolumeCompressionSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val volumeId: Long,
    /** SYNOPSIS / CONTENT */
    val sourceType: String,
    /** 规划时的源指纹；恢复时与已完成批次一起校验输入是否仍一致 */
    val sourceRevision: String = "",
    val modelId: Long = 0,
    val modelName: String = "",
    /** 本次不可变配置快照（JSON 文本）；空表示使用全局默认 */
    val configSnapshot: String = "",
    /**
     * F6：创建任务时解析好的大纲目标 ID 快照（逗号分隔）。
     * 任务执行中范围再怎么变化，差异分析与写入都只认这份快照。
     */
    val outlineScopeSnapshot: String = "",
    /** 存储值见 [VolumeCompressionStatus] */
    val status: String = VolumeCompressionStatus.PENDING,
    /** 存储值见 [VolumeCompressionPhase] */
    val phase: String = VolumeCompressionPhase.QUEUED,
    /** 存储值见 [VolumeCompressionErrorKind]；null = 无错误 */
    val errorKind: String? = null,
    val errorMessage: String = "",
    /** 计划提取批次数 */
    val plannedBatchCount: Int = 0,
    /** 已完成（落库成功）的提取批次数 */
    val completedBatchCount: Int = 0,
    /** 计划请求数（提取 + 分级整合 + 最终整合） */
    val plannedRequestCount: Int = 0,
    /** 实际请求数（含重试与失败请求） */
    val actualRequestCount: Int = 0,
    /** 预计输入 Token（启发式） */
    val estimatedInputTokens: Int = 0,
    /** 预计输出 Token（启发式） */
    val estimatedOutputTokens: Int = 0,
    /** 真实 usage：输入 Token */
    val promptTokens: Int = 0,
    /** 真实 usage：输出 Token */
    val completionTokens: Int = 0,
    /** 真实 usage：命中缓存的输入 Token（只来自服务商返回） */
    val cachedTokens: Int = 0,
    /** 有 usage 返回的请求数；用于区分「供应商没返回 usage」和「确实为 0」 */
    val requestsWithUsage: Int = 0,
    /** 本次任务产出的候选卷梗概 id；确认后清空或保留均可，界面按 id 查询 */
    val candidateSummaryId: Long? = null,
    /** 重新压缩时关联的旧任务 */
    val previousSessionId: Long? = null,
    /** 同书排队位置；0 = 正在运行或已终结 */
    val queuePosition: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val statusEnumValue: String get() = VolumeCompressionStatus.normalize(status)

    val phaseEnumValue: String get() = VolumeCompressionPhase.normalize(phase)

    val isActive: Boolean get() = statusEnumValue in VolumeCompressionStatus.active
}

/**
 * 单个请求的持久化记录：
 * - [VolumeCompressionBatchKind.EXTRACTION]：一个提取批次；[batchIndex] 与批次计划下标一致，
 *   [inputHash] 是恢复时判断能否跳过该批次的输入指纹；
 * - [VolumeCompressionBatchKind.MERGE]：一次分级 / 最终整合请求，[batchIndex] 为本次任务的请求序号。
 *
 * 唯一索引 (sessionId, kind, batchIndex) 保证重跑同一下标时覆盖旧记录，不重复累计 usage。
 */
@Entity(
    tableName = "volume_compression_batch",
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "kind", "batchIndex"], unique = true)
    ]
)
data class VolumeCompressionBatchRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 存储值见 [VolumeCompressionBatchKind] */
    val kind: String = VolumeCompressionBatchKind.EXTRACTION,
    val batchIndex: Int = 0,
    val rangeLabel: String = "",
    /** 参与本次请求的章节 id（逗号分隔，按目录顺序，去重） */
    val chapterIds: String = "",
    /** 提取批次的输入指纹（源类型 + 章节 id + 内容指纹 + 批次结构）；整合请求为空 */
    val inputHash: String = "",
    /** 存储值见 [VolumeCompressionBatchStatus] */
    val status: String = VolumeCompressionBatchStatus.PENDING,
    /** 格式修复尝试次数（含首次） */
    val attempts: Int = 0,
    /** 本次记录累计的真实请求数（含重试） */
    val requestCount: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    /** 本次记录中有 usage 返回的真实请求数（其余请求只有 requestCount，不虚报 Token） */
    val requestsWithUsage: Int = 0,
    /** 结构化提取结果（规范化 JSON）；恢复时优先用它，不依赖原始响应 */
    val extractionJson: String = "",
    /** 模型原始响应（用于诊断；确认永不使用它作为正式内容） */
    val rawContent: String = "",
    /** 存储值见 [VolumeCompressionErrorKind] */
    val errorKind: String? = null,
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun chapterIdList(): List<Long> =
        chapterIds.split(',').mapNotNull { it.trim().toLongOrNull() }

    val statusEnumValue: String get() = VolumeCompressionBatchStatus.normalize(status)
}
