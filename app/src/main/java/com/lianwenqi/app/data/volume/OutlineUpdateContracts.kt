package com.lianwenqi.app.data.volume

import com.lianwenqi.app.data.OUTLINE_HEAD_LABEL
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.util.shortHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * F6：大纲范围解析与差异建议的共享契约。
 *
 * 身份规则：`OutlineItem.id` 是唯一更新身份；标题只用于展示，
 * 永远不参与匹配或写入决策。分类常驻状态来自 DataStore 的「分类常驻化」，
 * 本层不新建任何平行分类或大纲实体。
 */

/** 建议操作：只有三种；无法判定时归为 [UNKNOWN]，界面必须显示为无效建议 */
object OutlineOperation {
    const val ADD = "ADD"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
    const val UNKNOWN = "UNKNOWN"

    fun normalize(raw: String): String = when (raw.trim().uppercase()) {
        ADD -> ADD
        UPDATE -> UPDATE
        DELETE -> DELETE
        "新增", "添加" -> ADD
        "更新", "修改" -> UPDATE
        "删除" -> DELETE
        else -> UNKNOWN
    }

    fun label(raw: String): String = when (normalize(raw)) {
        ADD -> "新增"
        UPDATE -> "更新"
        DELETE -> "删除"
        else -> "无效"
    }
}

/** 建议的持久化状态；只有 PENDING / LOW_CONFIDENCE 可以由用户勾选写入 */
object OutlineSuggestionStatus {
    /** 通过校验，等待用户确认 */
    const val PENDING = "PENDING"

    /** 通过校验但置信度低：可见、可单独勾选，但绝不自动写入 */
    const val LOW_CONFIDENCE = "LOW_CONFIDENCE"

    /** oldContent / 版本与本地快照不一致：不可写入，界面展示当前内容供用户处理 */
    const val CONFLICT = "CONFLICT"

    /** 结构错误、范围外 ID、空建议、无法判定操作等 */
    const val INVALID = "INVALID"

    /** 「开」模式下通过安全校验的 ADD / UPDATE 已自动写入 */
    const val AUTO_APPLIED = "AUTO_APPLIED"

    /** 用户在预览界面确认后写入成功 */
    const val APPLIED = "APPLIED"

    /** 用户主动放弃 */
    const val DISMISSED = "DISMISSED"

    /** 写入时数据库或校验失败 */
    const val FAILED = "FAILED"

    val selectable: List<String> = listOf(PENDING, LOW_CONFIDENCE)

    val open: List<String> = listOf(PENDING, LOW_CONFIDENCE, CONFLICT, INVALID)

    fun label(raw: String): String = when (raw) {
        PENDING -> "待确认"
        LOW_CONFIDENCE -> "低置信度"
        CONFLICT -> "冲突"
        INVALID -> "无效建议"
        AUTO_APPLIED -> "已自动写入"
        APPLIED -> "已写入"
        DISMISSED -> "已放弃"
        FAILED -> "写入失败"
        else -> raw
    }
}

/** 一次差异分析的整体状态；与压缩 session 一一关联 */
object OutlineUpdateStatus {
    const val ANALYZING = "ANALYZING"
    const val READY = "READY"
    const val FAILED = "FAILED"

    fun normalize(raw: String): String = when (raw) {
        ANALYZING, READY, FAILED -> raw
        else -> FAILED
    }
}

/**
 * 范围并集解析结果。
 *
 * `itemIds` 是最终写入目标 ID 的唯一快照；分类变化、删除条目时由
 * [OutlineScopeResolver] 以最新数据重新解析，不依赖标题。
 */
data class OutlineScopeResolved(
    val itemIds: List<Long> = emptyList(),
    val pinnedItemIds: List<Long> = emptyList(),
    val otherItemIds: List<Long> = emptyList(),
    val extraItemIds: List<Long> = emptyList(),
    val invalidIds: List<Long> = emptyList(),
    val pinnedCategories: List<String> = emptyList(),
    val otherCategories: List<String> = emptyList()
) {
    val totalCount: Int get() = itemIds.size

    val hasTargets: Boolean get() = itemIds.isNotEmpty()

    /** 写入压缩 session 的 ID 快照（逗号分隔）；空表示本次没有大纲目标 */
    fun encodeIds(): String = itemIds.joinToString(",")

    companion object {
        fun decodeIds(raw: String): List<Long> =
            raw.split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.distinct()

        /** 从 session 快照还原目标 ID（不重新解析范围，避免任务执行中范围悄悄变化） */
        fun fromSnapshot(raw: String): OutlineScopeResolved =
            OutlineScopeResolved(itemIds = decodeIds(raw))
    }
}

/** 提示词中一个真实大纲条目的快照；由本地数据编码，模型不得改动 id */
data class OutlineDiffItem(
    val id: Long,
    val category: String,
    val title: String,
    val content: String,
    val updatedAt: Long
) {
    val isFieldContent: Boolean get() = outlineContentIsFieldJson(content)

    val fields: List<Pair<String, String>> get() = decodeOutlineFields(content)

    val displayName: String
        get() = fields.firstOrNull()?.second?.ifBlank { title } ?: title
}

/** 新增建议可用的目标分类与字段模板 */
data class OutlineCategoryTemplate(
    val key: String,
    val label: String,
    val fieldNames: List<String> = emptyList()
)

/** 差异分析请求：只包含本次范围快照与必要的书籍 / 分卷背景 */
data class OutlineDiffRequest(
    val bookId: Long,
    val bookTitle: String,
    val bookIntro: String,
    val volumeTitle: String,
    val summary: String,
    val items: List<OutlineDiffItem>,
    val categories: List<OutlineCategoryTemplate>,
    val modelId: Long
)

/** 一条通过结构解析的建议草稿；尚未落库。
 *  `expectedUpdatedAt` 与 `contentFingerprint` 是防覆盖校验依据。
 */
data class OutlineSuggestionDraft(
    val index: Int,
    val operation: String,
    val targetOutlineItemId: Long?,
    val category: String,
    val title: String,
    val oldContent: String,
    val newContent: String,
    val reason: String,
    val confidence: Double,
    val relatedEvents: List<String> = emptyList(),
    val expectedUpdatedAt: Long = 0,
    val contentFingerprint: String = "",
    val status: String = OutlineSuggestionStatus.PENDING,
    val statusMessage: String = ""
) {
    val isFieldContent: Boolean get() = outlineContentIsFieldJson(newContent)

    val fields: List<Pair<String, String>> get() = decodeOutlineFields(newContent)

    /** 用户可以勾选（冲突 / 无效 / 已写入不可再次写入） */
    val selectable: Boolean get() = status in OutlineSuggestionStatus.selectable

    /** 「开」模式下可自动写入：只包括通过校验的 ADD / UPDATE */
    val autoApplicable: Boolean
        get() = status == OutlineSuggestionStatus.PENDING &&
            operation != OutlineOperation.DELETE &&
            operation != OutlineOperation.UNKNOWN
}

/** 差异分析结果；usage 必须并入压缩任务统计，原始响应只用于诊断 */
sealed interface OutlineDiffOutcome {
    data class Success(
        val suggestions: List<OutlineSuggestionDraft>,
        val usage: CompressionUsage,
        val rawContent: String = "",
        val note: String = ""
    ) : OutlineDiffOutcome

    /** 「关」或没有可同步目标：不发起任何模型请求 */
    data class Skipped(val note: String) : OutlineDiffOutcome

    data class Failed(
        val message: String,
        val usage: CompressionUsage = CompressionUsage(),
        val rawContent: String = ""
    ) : OutlineDiffOutcome
}

/** 写入结果汇总；冲突项绝不能计为成功 */
data class OutlineApplySummary(
    val applied: Int = 0,
    val conflicts: Int = 0,
    val invalid: Int = 0,
    val failed: Int = 0,
    val messages: List<String> = emptyList()
) {
    val total: Int get() = applied + conflicts + invalid + failed
}

/** 「开 / 询问 / 关」的判定集中在这里，避免界面与执行层各写一套 */
object OutlineUpdatePolicy {
    /** 低置信度阈值：低于该值不自动写入，仅展示 */
    const val MIN_AUTO_CONFIDENCE = 0.6

    /** 「开」只自动写安全的 ADD / UPDATE；DELETE 永远需要用户确认 */
    fun autoApplicable(mode: String, draft: OutlineSuggestionDraft): Boolean =
        mode == com.lianwenqi.app.data.CompressionOutlineUpdateMode.ON &&
            draft.autoApplicable &&
            draft.confidence >= MIN_AUTO_CONFIDENCE

    /** 任何建议默认都不勾选；DELETE 也默认不选 */
    fun initiallySelected(@Suppress("UNUSED_PARAMETER") draft: OutlineSuggestionDraft): Boolean = false

    /** 「全部选择」只覆盖安全的非删除项，冲突 / 无效 / 低置信度不会被静默加入 */
    fun safeForSelectAll(draft: OutlineSuggestionDraft): Boolean =
        draft.status == OutlineSuggestionStatus.PENDING &&
            draft.operation != OutlineOperation.DELETE &&
            draft.operation != OutlineOperation.UNKNOWN
}

/** 大纲内容是否为字段 JSON（`[{"n":..,"v":..}]`），用于保持字段结构 */
fun outlineContentIsFieldJson(content: String): Boolean {
    val trimmed = content.trim()
    if (!trimmed.startsWith("[")) return false
    val array = runCatching {
        Json.parseToJsonElement(trimmed).jsonArray
    }.getOrNull() ?: return false
    if (array.isEmpty()) return false
    return array.all { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@all false
        obj["n"] is JsonPrimitive || obj["v"] is JsonPrimitive
    }
}

/** 内容指纹：忽略首尾空白，用于和预览期间的用户编辑做冲突判定 */
fun outlineContentFingerprint(content: String): String = shortHash(content.trim())

/** 字段列表写入前补齐统一的第一项「显示标签」；空标签会被丢弃 */
fun normalizeOutlineFieldsForWrite(
    fields: List<Pair<String, String>>,
    title: String
): List<Pair<String, String>> {
    val cleaned = fields.filter { it.first.isNotBlank() }
    if (cleaned.any { it.first == OUTLINE_HEAD_LABEL }) return cleaned
    return if (title.isBlank()) cleaned
    else listOf(OUTLINE_HEAD_LABEL to title) + cleaned
}

/** 把模型返回的 fields 数组安全解析为标签 → 内容；兼容 `{"n","v"}` 与 `{"name","value"}` */
fun parseSuggestionFields(element: kotlinx.serialization.json.JsonElement): List<Pair<String, String>> {
    val array = runCatching { element.jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { item ->
        val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
        val name = (obj["n"] ?: obj["name"] ?: obj["label"])?.asText().orEmpty().trim()
        if (name.isBlank()) return@mapNotNull null
        name to (obj["v"] ?: obj["value"] ?: obj["content"])?.asText().orEmpty()
    }
}

/** JsonElement 的文本值；非字符串也按内容读取，数字 id 不丢 */
fun kotlinx.serialization.json.JsonElement.asText(): String =
    (this as? JsonPrimitive)?.content ?: ""
