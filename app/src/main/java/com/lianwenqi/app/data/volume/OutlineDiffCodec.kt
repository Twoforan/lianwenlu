package com.lianwenqi.app.data.volume

import com.lianwenqi.app.data.encodeOutlineFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 差异建议的严格解析与边界校验。
 *
 * 解析分两层：
 * 1. 结构层：根 JSON 必须可读且包含 suggestions 数组，否则整次分析失败；
 * 2. 条目层：单条格式错误不会拖垮整次分析，而是落成 [OutlineSuggestionStatus.INVALID] /
 *    [OutlineSuggestionStatus.CONFLICT]，界面必须显示出来，绝不静默写入。
 *
 * 所有目标 ID 只能来自本次范围快照；标题永远不参与匹配。
 */
object OutlineDiffCodec {

    sealed interface Result {
        data class Success(
            val suggestions: List<OutlineSuggestionDraft>,
            val note: String = ""
        ) : Result

        data class Failure(val message: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, request: OutlineDiffRequest): Result {
        val text = raw.trim()
        if (text.isBlank()) return Result.Failure("模型返回为空，无法生成大纲差异建议")
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Result.Failure("模型返回不是合法 JSON")

        val array = when {
            root is JsonArray -> root
            root is JsonObject && root["suggestions"] is JsonArray -> root["suggestions"]!!.jsonArray
            // 兼容模型只返回单条建议对象的情况
            root is JsonObject && root.containsKey("operation") -> JsonArray(listOf(root))
            else -> return Result.Failure("模型返回缺少 suggestions 数组")
        }

        if (array.isEmpty()) {
            return Result.Success(emptyList(), "本卷未发现需要同步的大纲变更")
        }

        val itemsById = request.items.associateBy { it.id }
        val categories = request.categories.map { it.key }.toSet()
        val usedTargets = mutableSetOf<Long>()
        val suggestions = array.mapIndexed { index, element ->
            validate(index, element, itemsById, categories, usedTargets)
        }
        val note = if (suggestions.all { it.status == OutlineSuggestionStatus.INVALID }) {
            "模型返回的建议均未通过范围 / 格式校验"
        } else {
            ""
        }
        return Result.Success(suggestions, note)
    }

    private fun validate(
        index: Int,
        element: JsonElement,
        itemsById: Map<Long, OutlineDiffItem>,
        categories: Set<String>,
        usedTargets: MutableSet<Long>
    ): OutlineSuggestionDraft {
        val obj = runCatching { element.jsonObject }.getOrNull()
            ?: return invalid(index, "建议不是 JSON 对象")

        val operation = OutlineOperation.normalize(obj.text("operation"))
        if (operation == OutlineOperation.UNKNOWN) {
            return invalid(index, "无法判定操作类型：${obj.text("operation").ifBlank { "（缺失）" }}")
        }

        val targetId = obj.longOrNull("targetOutlineItemId")
            ?: obj.longOrNull("targetId")
            ?: obj.longOrNull("outlineItemId")
        val modelTitle = obj.text("title").trim()
        val reason = obj.text("reason").trim().ifBlank { "模型未说明理由" }
        val relatedEvents = obj.stringList("relatedEvents")
        val confidence = obj.confidence()
        val providedOld = obj.text("oldContent").let { if (obj.containsKey("oldContent")) it else "" }
        val providedNew = obj.text("newContent").let { if (obj.containsKey("newContent")) it else "" }
        val fields = obj["fields"]?.let { parseSuggestionFields(it) }.orEmpty()
        val baseStatus = if (confidence < OutlineUpdatePolicy.MIN_AUTO_CONFIDENCE) {
            OutlineSuggestionStatus.LOW_CONFIDENCE
        } else {
            OutlineSuggestionStatus.PENDING
        }

        if (operation == OutlineOperation.ADD) {
            if (targetId != null) {
                return invalid(index, "ADD 不允许指定 targetOutlineItemId（落库后才产生真实 ID）")
            }
            val category = obj.text("category").trim()
            if (category.isBlank()) return invalid(index, "ADD 缺少目标分类")
            if (category !in categories) {
                return invalid(index, "目标分类「$category」不在本书的分类列表中；请先创建该分类")
            }
            val content = when {
                fields.isNotEmpty() -> {
                    val normalized = normalizeOutlineFieldsForWrite(fields, modelTitle)
                    encodeOutlineFields(normalized)
                }

                providedNew.isNotBlank() -> providedNew
                else -> ""
            }
            if (content.isBlank()) return invalid(index, "ADD 缺少可写入的字段或内容")
            val title = fields.firstOrNull { it.first == com.lianwenqi.app.data.OUTLINE_HEAD_LABEL }?.second
                ?.ifBlank { modelTitle }
                ?: modelTitle
            if (title.isBlank() && !outlineContentIsFieldJson(content)) {
                return invalid(index, "ADD 缺少标题")
            }
            return OutlineSuggestionDraft(
                index = index,
                operation = operation,
                targetOutlineItemId = null,
                category = category,
                title = title,
                oldContent = "",
                newContent = content,
                reason = reason,
                confidence = confidence,
                relatedEvents = relatedEvents,
                expectedUpdatedAt = 0,
                contentFingerprint = "",
                status = baseStatus,
                statusMessage = if (baseStatus == OutlineSuggestionStatus.LOW_CONFIDENCE) {
                    "置信度低于自动写入阈值，需逐条确认"
                } else {
                    ""
                }
            )
        }

        // UPDATE / DELETE 必须有真实且属于本次范围的目标 ID
        if (targetId == null) {
            return invalid(index, "${OutlineOperation.label(operation)}缺少 targetOutlineItemId")
        }
        val item = itemsById[targetId]
            ?: return invalid(index, "目标 id=$targetId 不在本次范围快照中（可能已删除或范围外）")
        if (targetId in usedTargets) {
            return invalid(index, "目标 id=$targetId 出现多条建议；已拒绝重复项")
        }

        val localOld = item.content
        val oldMismatch = providedOld.isNotBlank() &&
            providedOld.trim() != localOld.trim()
        val status = if (oldMismatch) {
            OutlineSuggestionStatus.CONFLICT
        } else {
            baseStatus
        }
        val statusMessage = when {
            oldMismatch -> "模型给出的 oldContent 与本地快照不一致；已标记冲突，不直接写入"
            status == OutlineSuggestionStatus.LOW_CONFIDENCE ->
                "置信度低于自动写入阈值，需逐条确认"
            else -> ""
        }

        if (operation == OutlineOperation.DELETE) {
            usedTargets += targetId
            return OutlineSuggestionDraft(
                index = index,
                operation = operation,
                targetOutlineItemId = targetId,
                category = item.category,
                title = item.title,
                oldContent = localOld,
                newContent = "",
                reason = reason,
                confidence = confidence,
                relatedEvents = relatedEvents,
                // 版本基线取本地快照时间，模型给出的时间不可信
                expectedUpdatedAt = item.updatedAt,
                contentFingerprint = outlineContentFingerprint(localOld),
                status = status,
                statusMessage = statusMessage
            )
        }

        val newContent = buildUpdatedContent(item, providedNew, fields, modelTitle)
            ?: return invalid(index, "UPDATE 未提供可写入的新内容")
        if (outlineContentFingerprint(newContent) == outlineContentFingerprint(localOld)) {
            return invalid(index, "新内容与当前内容一致，无需更新")
        }
        usedTargets += targetId
        val fieldsNew = if (outlineContentIsFieldJson(newContent)) {
            com.lianwenqi.app.data.decodeOutlineFields(newContent)
        } else {
            emptyList()
        }
        val headTitle = fieldsNew.firstOrNull { it.first == com.lianwenqi.app.data.OUTLINE_HEAD_LABEL }?.second
        val title = headTitle?.ifBlank { modelTitle }
            ?: modelTitle.ifBlank { item.title }

        return OutlineSuggestionDraft(
            index = index,
            operation = operation,
            targetOutlineItemId = targetId,
            category = item.category,
            title = title,
            oldContent = localOld,
            newContent = newContent,
            reason = reason,
            confidence = confidence,
            relatedEvents = relatedEvents,
            // 版本基线取本地快照时间，模型给出的时间不可信
            expectedUpdatedAt = item.updatedAt,
            contentFingerprint = outlineContentFingerprint(localOld),
            status = status,
            statusMessage = statusMessage
        )
    }

    /**
     * 字段型条目必须继续保持字段 JSON；纯文本条目只接受纯文本 newContent。
     * 字段型更新采用「按标签合并」：模型没提到的字段原样保留，
     * 明确给出空值的普通字段视为删除；绝不允许把字段压成不可恢复的普通文本。
     */
    private fun buildUpdatedContent(
        item: OutlineDiffItem,
        providedNew: String,
        fields: List<Pair<String, String>>,
        title: String
    ): String? {
        if (item.isFieldContent) {
            val changes = when {
                fields.isNotEmpty() -> fields
                providedNew.isNotBlank() && outlineContentIsFieldJson(providedNew) ->
                    com.lianwenqi.app.data.decodeOutlineFields(providedNew)

                else -> return null
            }
            val changeMap = changes.filter { it.first.isNotBlank() }.associate { it.first to it.second }
            val existing = item.fields
            val merged = existing.mapNotNull { (name, value) ->
                if (name == com.lianwenqi.app.data.OUTLINE_HEAD_LABEL) {
                    name to (changeMap[name]?.takeIf { it.isNotBlank() } ?: value)
                } else if (changeMap.containsKey(name)) {
                    val next = changeMap[name].orEmpty()
                    if (next.isBlank()) null else name to next
                } else {
                    name to value
                }
            }.toMutableList()
            changes.filter { change ->
                change.first.isNotBlank() &&
                    change.first != com.lianwenqi.app.data.OUTLINE_HEAD_LABEL &&
                    existing.none { it.first == change.first } &&
                    change.second.isNotBlank()
            }.forEach { merged += it }
            val normalized = normalizeOutlineFieldsForWrite(
                merged,
                title.ifBlank { item.title }
            )
            return encodeOutlineFields(normalized)
        }
        if (providedNew.isBlank()) return null
        if (outlineContentIsFieldJson(providedNew)) return null
        return providedNew
    }

    private fun invalid(index: Int, message: String): OutlineSuggestionDraft =
        OutlineSuggestionDraft(
            index = index,
            operation = OutlineOperation.UNKNOWN,
            targetOutlineItemId = null,
            category = "",
            title = "",
            oldContent = "",
            newContent = "",
            reason = message,
            confidence = 0.0,
            expectedUpdatedAt = 0,
            contentFingerprint = "",
            status = OutlineSuggestionStatus.INVALID,
            statusMessage = message
        )

    private fun JsonObject.text(key: String): String {
        val value = this[key] ?: return ""
        if (value is JsonPrimitive && value.isString) return value.content
        return (value as? JsonPrimitive)?.content ?: ""
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val raw = (this[key] as? JsonPrimitive)?.content?.trim() ?: return null
        if (raw.isBlank() || raw == "null") return null
        return raw.toLongOrNull()
    }

    private fun JsonObject.confidence(): Double {
        val raw = (this["confidence"] as? JsonPrimitive)?.content?.trim() ?: return 0.0
        val value = raw.toDoubleOrNull() ?: return 0.0
        val normalized = if (value > 1.0 && value <= 100.0) value / 100.0 else value
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val value = this[key] ?: return emptyList()
        (value as? JsonArray)?.let { array ->
            return array.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }
                .filter { it.isNotBlank() }
        }
        val single = (value as? JsonPrimitive)?.content?.trim()
        return if (single.isNullOrBlank()) emptyList() else listOf(single)
    }
}
