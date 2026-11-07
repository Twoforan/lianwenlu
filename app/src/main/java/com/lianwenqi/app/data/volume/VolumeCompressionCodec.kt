package com.lianwenqi.app.data.volume

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** 提取结果解析：成功时返回已校验的结构，失败时返回可区分的 [CompressionFailure] */
sealed interface ExtractionParseResult {
    data class Success(val extraction: BatchExtraction) : ExtractionParseResult
    data class Failure(val failure: CompressionFailure) : ExtractionParseResult
}

/** 纯文本整合结果校验：成功时返回去围栏后的正文 */
sealed interface MergedTextResult {
    data class Valid(val content: String) : MergedTextResult
    data class Invalid(val failure: CompressionFailure) : MergedTextResult
}

/**
 * 压缩结果编解码。
 *
 * 提取结果必须严格符合约定 JSON 结构：
 * - 允许模型输出 Markdown 围栏或前后解释文字，只取第一个完整 JSON 对象；
 * - 必填字段齐全、类型正确、事件与参与者非空；
 * - chapterRange 必须包含本批范围，sourceChapterIds 必须与本批完全一致（防止模型编造章节）；
 * - 未闭合的 JSON / 命中输出上限时判为截断，而不是当成普通格式错误。
 */
object VolumeCompressionCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private const val MIN_FINAL_CHARS = 10

    fun parseExtraction(
        raw: String,
        batch: CompressionBatch,
        hitOutputLimit: Boolean
    ): ExtractionParseResult {
        if (raw.isBlank()) {
            return ExtractionParseResult.Failure(
                if (hitOutputLimit) CompressionFailure.TruncatedOutput(CompressionStage.EXTRACTION)
                else CompressionFailure.EmptyOutput(CompressionStage.EXTRACTION)
            )
        }
        val candidate = when (val located = locateJson(raw)) {
            JsonCandidate.Unterminated -> return ExtractionParseResult.Failure(
                CompressionFailure.TruncatedOutput(CompressionStage.EXTRACTION)
            )

            JsonCandidate.NotFound -> return ExtractionParseResult.Failure(
                if (hitOutputLimit) CompressionFailure.TruncatedOutput(CompressionStage.EXTRACTION)
                else CompressionFailure.InvalidJson(CompressionStage.EXTRACTION, "未找到 JSON 对象")
            )

            is JsonCandidate.Found -> located.text
        }
        val root = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
        if (root !is JsonObject) {
            return ExtractionParseResult.Failure(
                if (hitOutputLimit) CompressionFailure.TruncatedOutput(CompressionStage.EXTRACTION)
                else CompressionFailure.InvalidJson(CompressionStage.EXTRACTION, "不是合法的 JSON 对象")
            )
        }
        return try {
            val extraction = readExtraction(root, batch)
            ExtractionParseResult.Success(extraction)
        } catch (e: InvalidExtraction) {
            ExtractionParseResult.Failure(
                CompressionFailure.InvalidJson(CompressionStage.EXTRACTION, e.detail)
            )
        }
    }

    /**
     * 把已校验的提取结果编码成规范化 JSON，供 F5 批次表持久化。
     * 事件统一输出为 `{"text","chapterIds"}`，与 [parseExtraction] 的严格校验保持同一格式，
     * 恢复时可直接 [decodeExtraction] 还原，不必依赖模型的原始响应。
     */
    fun encodeExtraction(extraction: BatchExtraction): String = buildJsonObject {
        put("chapterRange", extraction.chapterRange)
        putJsonArray("sourceChapterIds") {
            extraction.sourceChapterIds.forEach { add(JsonPrimitive(it)) }
        }
        putJsonArray("events") {
            extraction.events.forEach { event ->
                addJsonObject {
                    put("text", event.text)
                    putJsonArray("chapterIds") {
                        event.chapterIds.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }
        putStringArray("participants", extraction.participants)
        put("cause", extraction.cause)
        put("result", extraction.result)
        putStringArray("characterChanges", extraction.characterChanges)
        putStringArray("relationshipChanges", extraction.relationshipChanges)
        putStringArray("foreshadowing", extraction.foreshadowing)
        putStringArray("unresolvedIssues", extraction.unresolvedIssues)
        putStringArray("importantDetails", extraction.importantDetails)
        putStringArray("subplots", extraction.subplots)
    }.toString()

    /** 从持久化的规范化 JSON 还原提取结果；结构不合法或不属于该批时返回 null（调用方重跑该批） */
    fun decodeExtraction(raw: String, batch: CompressionBatch): BatchExtraction? =
        (parseExtraction(raw, batch, hitOutputLimit = false) as? ExtractionParseResult.Success)
            ?.extraction

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStringArray(
        key: String,
        values: List<String>
    ) {
        putJsonArray(key) { values.forEach { add(JsonPrimitive(it)) } }
    }

    /**
     * 校验整合正文：[finalRound] 为 true 时用于最终卷梗概，否则用于分级整合的中间结果。
     * 到达输出上限时只把空结果 / 不完整结构判为截断；已经完整的正文仍然接受，
     * 避免把「恰好用完预算」的合法结果误报为截断。
     */
    fun validateMergedText(
        raw: String,
        hitOutputLimit: Boolean,
        finalRound: Boolean
    ): MergedTextResult {
        if (raw.isBlank()) {
            return MergedTextResult.Invalid(
                failureFor(finalRound, if (hitOutputLimit) "输出被截断" else "返回为空", truncated = hitOutputLimit)
            )
        }
        val content = unwrapFence(raw).trim()
        if (content.isBlank()) {
            return MergedTextResult.Invalid(
                failureFor(finalRound, if (hitOutputLimit) "输出被截断" else "返回为空", truncated = hitOutputLimit)
            )
        }
        val parsed = runCatching { json.parseToJsonElement(content) }.getOrNull()
        if (parsed is JsonObject || parsed is JsonArray) {
            return MergedTextResult.Invalid(failureFor(finalRound, "不能是 JSON 拼接", truncated = hitOutputLimit))
        }
        if (content.length < MIN_FINAL_CHARS) {
            return MergedTextResult.Invalid(failureFor(finalRound, "内容过短", truncated = hitOutputLimit))
        }
        return MergedTextResult.Valid(content)
    }

    private fun failureFor(finalRound: Boolean, detail: String, truncated: Boolean): CompressionFailure =
        when {
            truncated -> CompressionFailure.TruncatedOutput(CompressionStage.MERGE)
            finalRound -> CompressionFailure.InvalidFinalContent(detail)
            else -> CompressionFailure.InvalidMergeContent(detail)
        }

    private fun readExtraction(obj: JsonObject, batch: CompressionBatch): BatchExtraction {
        val range = obj.requireString("chapterRange")
        val expectedRange = batch.range.label
        if (!range.contains(expectedRange)) {
            throw InvalidExtraction("chapterRange 与本批范围不一致（应包含 $expectedRange）")
        }
        val ids = obj.requireLongArray("sourceChapterIds")
        if (ids != batch.chapterIds) {
            throw InvalidExtraction("sourceChapterIds 与本批章节不一致")
        }
        val events = obj.requireEvents("events", batch.chapterIds)
        val participants = obj.requireStringArray("participants", mustBeNonEmpty = true)
        val cause = obj.requireString("cause")
        val result = obj.requireString("result")
        return BatchExtraction(
            chapterRange = range,
            sourceChapterIds = ids,
            events = events,
            participants = participants,
            cause = cause,
            result = result,
            characterChanges = obj.requireStringArray("characterChanges"),
            relationshipChanges = obj.requireStringArray("relationshipChanges"),
            foreshadowing = obj.requireStringArray("foreshadowing"),
            unresolvedIssues = obj.requireStringArray("unresolvedIssues"),
            importantDetails = obj.requireStringArray("importantDetails"),
            subplots = obj.optionalStringArray("subplots")
        )
    }

    private fun JsonObject.requireString(key: String): String {
        val primitive = this[key] as? JsonPrimitive
            ?: throw InvalidExtraction("缺少字段 $key 或类型不是字符串")
        if (!primitive.isString) throw InvalidExtraction("字段 $key 必须是字符串")
        val value = primitive.content.trim()
        if (value.isEmpty()) throw InvalidExtraction("字段 $key 不能为空")
        return value
    }

    private fun JsonObject.requireLongArray(key: String): List<Long> {
        val element = this[key] as? JsonArray
            ?: throw InvalidExtraction("缺少字段 $key 或类型不是数组")
        if (element.isEmpty()) throw InvalidExtraction("字段 $key 不能为空数组")
        return element.mapIndexed { index, item ->
            val primitive = item as? JsonPrimitive
                ?: throw InvalidExtraction("字段 $key 第 ${index + 1} 项不是数字")
            // 兼容模型把 id 写成字符串（如 "12"）或整数值浮点数（如 12.0）
            primitive.flexibleLongOrNull()
                ?: throw InvalidExtraction("字段 $key 第 ${index + 1} 项必须是整数")
        }
    }

    /** JSON 数字原样读取；字符串则尝试按整数解析，容忍模型把 id 用引号包起来 */
    private fun JsonPrimitive.flexibleLongOrNull(): Long? {
        if (!isString) return longOrNull
        val text = content.trim()
        return text.toLongOrNull()
            ?: text.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toLong()
    }

    /**
     * 解析事件：支持 F3 的 `{"text": "...", "chapterIds": [...]}`；
     * 为兼容只返回字符串的模型输出，字符串事件会继承本批全部章节 id。
     * 事件来源必须非空且只能引用本批章节，防止编造来源。
     */
    private fun JsonObject.requireEvents(key: String, batchChapterIds: List<Long>): List<ExtractedEvent> {
        val element = this[key] as? JsonArray
            ?: throw InvalidExtraction("缺少字段 $key 或类型不是数组")
        if (element.isEmpty()) throw InvalidExtraction("字段 $key 不能为空")
        return element.mapIndexed { index, item ->
            val position = "字段 $key 第 ${index + 1} 项"
            when (item) {
                is JsonPrimitive -> {
                    if (!item.isString) throw InvalidExtraction("$position 必须是字符串或事件对象")
                    val text = item.content.trim()
                    if (text.isEmpty()) throw InvalidExtraction("$position 不能为空")
                    ExtractedEvent(text = text, chapterIds = batchChapterIds)
                }

                is JsonObject -> {
                    val text = item.requireString("text")
                    val ids = item.requireLongArray("chapterIds")
                    val unknown = ids.filterNot { it in batchChapterIds }
                    if (unknown.isNotEmpty()) {
                        throw InvalidExtraction("$position 引用了本批之外的章节 id：${unknown.joinToString("、")}")
                    }
                    ExtractedEvent(text = text, chapterIds = ids.distinct())
                }

                else -> throw InvalidExtraction("$position 必须是字符串或事件对象")
            }
        }
    }

    private fun JsonObject.requireStringArray(key: String, mustBeNonEmpty: Boolean = false): List<String> {
        val element = this[key] as? JsonArray
            ?: throw InvalidExtraction("缺少字段 $key 或类型不是数组")
        val values = element.mapIndexed { index, item ->
            val primitive = item as? JsonPrimitive
                ?: throw InvalidExtraction("字段 $key 第 ${index + 1} 项必须是字符串")
            if (!primitive.isString) throw InvalidExtraction("字段 $key 第 ${index + 1} 项必须是字符串")
            primitive.content.trim().takeIf { it.isNotEmpty() }
                ?: throw InvalidExtraction("字段 $key 第 ${index + 1} 项不能为空")
        }
        if (mustBeNonEmpty && values.isEmpty()) throw InvalidExtraction("字段 $key 不能为空")
        return values
    }

    private fun JsonObject.optionalStringArray(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        if (element !is JsonArray) throw InvalidExtraction("字段 $key 必须是数组")
        return element.mapIndexed { index, item ->
            val primitive = item as? JsonPrimitive
                ?: throw InvalidExtraction("字段 $key 第 ${index + 1} 项必须是字符串")
            if (!primitive.isString) throw InvalidExtraction("字段 $key 第 ${index + 1} 项必须是字符串")
            primitive.content.trim()
        }.filter { it.isNotEmpty() }
    }

    /** 定位 JSON：优先完整代码围栏，否则取第一个花括号到配对花括号 */
    private fun locateJson(text: String): JsonCandidate {
        val fenceStart = text.indexOf("```")
        if (fenceStart >= 0) {
            val contentStart = text.indexOf('\n', fenceStart + 3)
            if (contentStart < 0) return JsonCandidate.Unterminated
            val fenceEnd = text.indexOf("```", contentStart + 1)
            if (fenceEnd < 0) return JsonCandidate.Unterminated
            val inner = text.substring(contentStart + 1, fenceEnd).trim()
            return if (inner.isEmpty()) JsonCandidate.NotFound else JsonCandidate.Found(inner)
        }
        val start = text.indexOf('{')
        if (start < 0) return JsonCandidate.NotFound
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return JsonCandidate.Found(text.substring(start, index + 1))
                    }
                }
            }
        }
        return JsonCandidate.Unterminated
    }

    /** 去掉包裹整段输出的 Markdown 围栏，避免把围栏当成梗概正文 */
    private fun unwrapFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val contentStart = trimmed.indexOf('\n')
        if (contentStart < 0) return trimmed
        val end = trimmed.lastIndexOf("```")
        if (end <= contentStart) return trimmed
        return trimmed.substring(contentStart + 1, end)
    }

    private sealed interface JsonCandidate {
        data class Found(val text: String) : JsonCandidate
        object NotFound : JsonCandidate
        object Unterminated : JsonCandidate
    }

    private class InvalidExtraction(val detail: String) : Exception(detail)
}
