package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.StyleRepository
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.CancellationException

sealed interface StyleSaveResult {
    data class Success(val profile: StyleProfile) : StyleSaveResult

    /** 已存在同名档案：不覆盖、不静默改名，交给用户调整名称 */
    data object DuplicateName : StyleSaveResult

    data class Failure(val message: String) : StyleSaveResult
}

/**
 * 把一次分析草稿写入 F1 的文风档案数据层。
 *
 * 只通过 [StyleRepository] 落库（不直接操作 Room）；保存前查重，避免同名档案被静默重复写入或覆盖；
 * 校验/写入失败返回明确错误，由界面保持「保存失败」状态并允许重试（重试保存不重新调用模型）。
 */
class StyleProfileSaver(private val repository: StyleRepository) {

    suspend fun save(name: String, draft: StyleAnalysisDraft): StyleSaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return StyleSaveResult.Failure("请填写文风档案名称")
        if (trimmed.length > StyleProfile.MAX_NAME_LENGTH) {
            return StyleSaveResult.Failure("档案名称不能超过 ${StyleProfile.MAX_NAME_LENGTH} 字")
        }
        if (repository.findByName(trimmed) != null) return StyleSaveResult.DuplicateName

        return try {
            val id = repository.createProfile(
                StyleProfile(
                    name = trimmed,
                    sourceType = draft.sourceType.name,
                    sourceMeta = draft.sourceMeta,
                    analysisJson = draft.analysisJson,
                    analysisVersion = draft.analysis.schemaVersion,
                    modelName = draft.modelName
                )
            )
            val saved = repository.getProfile(id)
                ?: return StyleSaveResult.Failure("保存后未能读回档案，请重试")
            StyleSaveResult.Success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            StyleSaveResult.Failure(e.message ?: "档案内容不符合数据约束，请重试")
        } catch (e: Exception) {
            StyleSaveResult.Failure("保存失败：${e.message ?: "未知错误"}")
        }
    }
}
