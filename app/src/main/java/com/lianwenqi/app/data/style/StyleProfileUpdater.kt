package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.StyleRepository
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.CancellationException

sealed interface StyleUpdateResult {
    data class Success(val profile: StyleProfile) : StyleUpdateResult

    /** 已存在其他同名档案：不覆盖、不静默改名 */
    data object DuplicateName : StyleUpdateResult

    /** 本地校验未通过（名称为空 / 分析结构不符合 F4 协议 / 需要确认移除未识别字段） */
    data class Invalid(val message: String) : StyleUpdateResult

    data class Failure(val message: String) : StyleUpdateResult
}

/**
 * 编辑已保存的文风档案。
 *
 * 只通过 [StyleRepository] 更新（不直接操作 Room）；保存前做名称查重与结构化校验，
 * 保存后回读确认最新内容。未知版本或无法安全编辑的档案可仅改名：
 * 此时 [analysis] 传 null，原始 analysisJson 原样保留，不会被改写或丢字段。
 */
class StyleProfileUpdater(private val repository: StyleRepository) {

    suspend fun update(
        profile: StyleProfile,
        name: String,
        analysis: StyleAnalysis?,
        confirmUnknownFieldRemoval: Boolean = false
    ): StyleUpdateResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return StyleUpdateResult.Invalid("请填写文风档案名称")
        if (trimmed.length > StyleProfile.MAX_NAME_LENGTH) {
            return StyleUpdateResult.Invalid("档案名称不能超过 ${StyleProfile.MAX_NAME_LENGTH} 字")
        }
        val duplicate = repository.findByName(trimmed)
        if (duplicate != null && duplicate.id != profile.id) return StyleUpdateResult.DuplicateName

        if (analysis != null) {
            val summary = StyleProfileSummaryReader.read(profile)
            if (summary.unknownFields.isNotEmpty() && !confirmUnknownFieldRemoval) {
                return StyleUpdateResult.Invalid(
                    "档案包含当前版本未识别的字段（${summary.unknownFields.joinToString("、")}），" +
                        "保存分析内容会移除这些字段，请确认后重试"
                )
            }
            val invalid = StyleAnalysisCodec.validate(analysis)
            if (invalid != null) return StyleUpdateResult.Invalid("分析结果格式不合法：$invalid")
        }

        val updated = profile.copy(
            name = trimmed,
            analysisJson = analysis?.let { StyleAnalysisCodec.encode(it) } ?: profile.analysisJson,
            analysisVersion = analysis?.schemaVersion ?: profile.analysisVersion
        )

        return try {
            repository.updateProfile(updated)
            val saved = repository.getProfile(profile.id)
                ?: return StyleUpdateResult.Failure("档案已不存在，无法保存")
            StyleUpdateResult.Success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            StyleUpdateResult.Invalid(e.message ?: "档案内容不符合数据约束")
        } catch (e: Exception) {
            StyleUpdateResult.Failure("保存失败：${e.message ?: "未知错误"}")
        }
    }
}
