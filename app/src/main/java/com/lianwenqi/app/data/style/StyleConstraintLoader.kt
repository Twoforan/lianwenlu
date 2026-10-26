package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** 文风加载失败的原因；`styleId = 0`（未绑定）是正常状态，不属于这里的错误 */
enum class StyleLoadError {
    /** 书籍（或发起生成的章节）已不存在 */
    BOOK_MISSING,

    /** 绑定的档案已被删除，绑定关系悬空 */
    PROFILE_MISSING,

    /** analysisJson 不是可解析的结构化分析结果 */
    INVALID_JSON,

    /** 分析结果版本不是当前支持的版本 */
    UNSUPPORTED_VERSION,

    /** 档案存在但没有可用于写作指导的内容（各维度只有「证据不足」） */
    EMPTY_ANALYSIS,

    /** Repository 读取异常 */
    REPOSITORY_ERROR
}

/**
 * 单次生成请求读取到的书籍文风快照。
 * 每个请求在开始构建提示词时读取一次并固定，生成过程中不再切换。
 */
sealed interface BookStyleSnapshot {
    val bookId: Long

    /** 未绑定文风（styleId = 0），正常状态：不注入任何文风内容 */
    data class Unbound(override val bookId: Long) : BookStyleSnapshot

    /** 绑定成功且已通过版本与结构校验 */
    data class Loaded(
        override val bookId: Long,
        val styleId: Long,
        val styleName: String,
        val schemaVersion: Int,
        val constraint: String
    ) : BookStyleSnapshot

    /** 已绑定但本次无法使用；绝不用其它档案替代 */
    data class Unavailable(
        override val bookId: Long,
        val styleId: Long?,
        val styleName: String?,
        val error: StyleLoadError
    ) : BookStyleSnapshot
}

/** 一次生成请求的文风加载结果：[block] 为空表示不注入，[notice] 非空表示需要告知用户本次未应用文风 */
data class StyleConstraintApplication(
    val snapshot: BookStyleSnapshot,
    val block: String,
    val notice: String?
)

/**
 * 统一的文风加载入口：书籍 id → 最新持久化的 styleId → 档案 → 结构化校验 → 写作约束文本。
 *
 * 不做跨请求缓存：换绑、解除绑定、档案编辑、档案删除在下次生成时立即生效，
 * 也不会把上一本书或默认档案的文风带到当前请求。
 */
class StyleConstraintLoader(
    /** 读取书籍最新的 styleId；返回 null 表示书籍不存在 */
    private val styleIdOf: suspend (Long) -> Long?,
    /** 按 id 读取档案；返回 null 表示档案已被删除 */
    private val profileOf: suspend (Long) -> StyleProfile?
) {

    /** [bookId] 为 null 表示当前章节已不存在，按「书籍无法读取」处理，不注入文风 */
    suspend fun apply(bookId: Long?): StyleConstraintApplication {
        if (bookId == null) {
            val snapshot = BookStyleSnapshot.Unavailable(0, null, null, StyleLoadError.BOOK_MISSING)
            return StyleConstraintApplication(snapshot, "", noticeFor(snapshot.error))
        }
        val snapshot = load(bookId)
        return when (snapshot) {
            is BookStyleSnapshot.Loaded ->
                StyleConstraintApplication(snapshot, snapshot.constraint, null)
            is BookStyleSnapshot.Unbound ->
                StyleConstraintApplication(snapshot, "", null)
            is BookStyleSnapshot.Unavailable ->
                StyleConstraintApplication(snapshot, "", noticeFor(snapshot.error))
        }
    }

    /** 读取并校验文风快照；任何异常都归类为明确原因，不静默改用其它档案 */
    suspend fun load(bookId: Long): BookStyleSnapshot {
        val styleId = try {
            styleIdOf(bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BookStyleSnapshot.Unavailable(bookId, null, null, StyleLoadError.REPOSITORY_ERROR)
        } ?: return BookStyleSnapshot.Unavailable(bookId, null, null, StyleLoadError.BOOK_MISSING)

        if (styleId == 0L) return BookStyleSnapshot.Unbound(bookId)

        val profile = try {
            profileOf(styleId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BookStyleSnapshot.Unavailable(bookId, styleId, null, StyleLoadError.REPOSITORY_ERROR)
        } ?: return BookStyleSnapshot.Unavailable(bookId, styleId, null, StyleLoadError.PROFILE_MISSING)

        val version = analysisVersion(profile.analysisJson)
            ?: return BookStyleSnapshot.Unavailable(
                bookId, styleId, profile.name, StyleLoadError.INVALID_JSON
            )
        if (version != STYLE_ANALYSIS_SCHEMA_VERSION) {
            return BookStyleSnapshot.Unavailable(
                bookId, styleId, profile.name, StyleLoadError.UNSUPPORTED_VERSION
            )
        }
        val analysis = when (val parsed = StyleAnalysisCodec.parse(profile.analysisJson)) {
            is StyleAnalysisParseResult.Success -> parsed.analysis
            is StyleAnalysisParseResult.Failure ->
                return BookStyleSnapshot.Unavailable(
                    bookId, styleId, profile.name, StyleLoadError.INVALID_JSON
                )
        }
        val constraint = StyleConstraintRenderer.render(analysis)
        if (constraint.isBlank()) {
            return BookStyleSnapshot.Unavailable(
                bookId, styleId, profile.name, StyleLoadError.EMPTY_ANALYSIS
            )
        }
        return BookStyleSnapshot.Loaded(
            bookId = bookId,
            styleId = styleId,
            styleName = profile.name,
            schemaVersion = analysis.schemaVersion,
            constraint = constraint
        )
    }

    /** 只读 schemaVersion 做版本预检，区分「版本不受支持」与「JSON 无法解析」 */
    private fun analysisVersion(raw: String): Int? {
        val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
        return (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
    }

    /** 面向用户的非阻塞说明：明确告知本次没有应用绑定文风，不暴露技术细节 */
    private fun noticeFor(error: StyleLoadError): String = when (error) {
        StyleLoadError.BOOK_MISSING -> "本次生成未应用文风：当前书籍已不存在"
        StyleLoadError.PROFILE_MISSING -> "本次生成未应用文风：绑定的文风档案已不存在，请在书籍设置中重新绑定"
        StyleLoadError.INVALID_JSON -> "本次生成未应用文风：绑定的文风档案分析结果已损坏"
        StyleLoadError.UNSUPPORTED_VERSION -> "本次生成未应用文风：绑定的文风档案版本不受支持"
        StyleLoadError.EMPTY_ANALYSIS -> "本次生成未应用文风：绑定的文风档案没有可用的写作指导"
        StyleLoadError.REPOSITORY_ERROR -> "本次生成未应用文风：读取文风档案失败"
    }
}
