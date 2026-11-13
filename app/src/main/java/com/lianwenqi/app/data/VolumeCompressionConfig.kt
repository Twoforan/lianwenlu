package com.lianwenqi.app.data

import com.lianwenqi.app.data.entity.VolumeSummarySourceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 压缩来源：SYNOPSIS / CONTENT / ASK（每次询问） */
object CompressionSourceMode {
    const val SYNOPSIS = VolumeSummarySourceType.SYNOPSIS
    const val CONTENT = VolumeSummarySourceType.CONTENT
    const val ASK = "ASK"

    val all = listOf(SYNOPSIS, CONTENT, ASK)

    fun normalize(raw: String): String =
        if (raw == CONTENT || raw == ASK) raw else SYNOPSIS

    fun label(raw: String): String = when (normalize(raw)) {
        CONTENT -> "章节正文"
        ASK -> "每次询问"
        else -> "章节梗概"
    }
}

/** 压缩完成后是否更新大纲：开 / 关 / 询问（关闭只影响大纲，不影响压缩本身） */
object CompressionOutlineUpdateMode {
    const val ON = "ON"
    const val OFF = "OFF"
    const val ASK = "ASK"

    fun normalize(raw: String): String = if (raw == ON || raw == ASK) raw else OFF

    fun label(raw: String): String = when (normalize(raw)) {
        ON -> "自动更新"
        ASK -> "每次询问"
        else -> "关闭"
    }
}

/** 大纲更新范围：全部大纲 / 仅可更新大纲（条目长按标记「可更新」） */
object CompressionOutlineScopeMode {
    const val WHOLE_BOOK = "WHOLE_BOOK"
    const val UPDATABLE = "UPDATABLE"

    val all = listOf(WHOLE_BOOK, UPDATABLE)

    fun normalize(raw: String): String = if (raw == UPDATABLE) UPDATABLE else WHOLE_BOOK

    fun label(raw: String): String =
        if (normalize(raw) == UPDATABLE) "仅可更新大纲" else "全部大纲"
}

/** 全局默认压缩配置；单次压缩可在配置弹窗中临时覆盖，不影响这里保存的默认值 */
@Serializable
data class VolumeCompressionConfig(
    /** 见 [CompressionSourceMode] */
    val source: String = CompressionSourceMode.ASK,
    /** 在上下文里勾选尚未生成的卷梗概时，是否按默认来源自动压缩 */
    val autoCompressWholeVolumeSummary: Boolean = false,
    /** 分卷压缩默认使用的模型 id；0 表示未指定（回退到当前对话模型 / 第一个模型） */
    val compressionModelId: Long = 0L,
    /** 分卷压缩最大输出 Token；0 表示跟随模型设置 */
    val maxOutputTokens: Int = 0,
    /** 单次请求最多提交的字符数（按字符启发式估算，默认 12000 字） */
    val maxCharsPerRequest: Int = DEFAULT_MAX_CHARS_PER_REQUEST,
    /** 最终卷梗概的目标字数（500–10000，默认 2000 字） */
    val outputChars: Int = DEFAULT_OUTPUT_CHARS,
    /** 见 [CompressionOutlineUpdateMode] */
    val outlineUpdateMode: String = CompressionOutlineUpdateMode.OFF,
    /** 见 [CompressionOutlineScopeMode] */
    val outlineScopeMode: String = CompressionOutlineScopeMode.WHOLE_BOOK
) {
    fun normalized(): VolumeCompressionConfig = copy(
        source = CompressionSourceMode.normalize(source),
        maxOutputTokens = if (maxOutputTokens <= 0) 0 else maxOutputTokens.coerceIn(
            MIN_MAX_OUTPUT_TOKENS,
            MAX_MAX_OUTPUT_TOKENS
        ),
        maxCharsPerRequest = maxCharsPerRequest.coerceIn(
            MIN_MAX_CHARS_PER_REQUEST,
            MAX_MAX_CHARS_PER_REQUEST
        ),
        outputChars = outputChars.coerceIn(MIN_OUTPUT_CHARS, MAX_OUTPUT_CHARS),
        outlineUpdateMode = CompressionOutlineUpdateMode.normalize(outlineUpdateMode),
        outlineScopeMode = CompressionOutlineScopeMode.normalize(outlineScopeMode)
    )

    /** 本次压缩实际使用的来源；ASK 由弹窗当场选择 */
    fun resolvedSource(asked: String): String =
        if (CompressionSourceMode.normalize(source) == CompressionSourceMode.ASK) {
            CompressionSourceMode.normalize(asked)
        } else {
            CompressionSourceMode.normalize(source)
        }

    val outlineUpdateEnabled: Boolean
        get() = CompressionOutlineUpdateMode.normalize(outlineUpdateMode) !=
            CompressionOutlineUpdateMode.OFF

    companion object {
        const val DEFAULT_MAX_OUTPUT_TOKENS = 8192
        const val MIN_MAX_OUTPUT_TOKENS = 256
        const val MAX_MAX_OUTPUT_TOKENS = 32768
        const val DEFAULT_MAX_CHARS_PER_REQUEST = 12000
        const val MIN_MAX_CHARS_PER_REQUEST = 500
        const val MAX_MAX_CHARS_PER_REQUEST = 200000
        const val DEFAULT_OUTPUT_CHARS = 2000
        const val MIN_OUTPUT_CHARS = 500
        const val MAX_OUTPUT_CHARS = 10000

        val DEFAULT = VolumeCompressionConfig()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** 存储 / 快照编码 */
        fun encode(config: VolumeCompressionConfig): String =
            runCatching { json.encodeToString(serializer(), config.normalized()) }
                .getOrElse { json.encodeToString(serializer(), DEFAULT) }

        /** 读取损坏或旧格式时回落到默认值，绝不让格式问题阻塞压缩入口 */
        fun decode(raw: String): VolumeCompressionConfig {
            if (raw.isBlank()) return DEFAULT
            return runCatching { json.decodeFromString(serializer(), raw).normalized() }
                .getOrElse { DEFAULT }
        }
    }
}
