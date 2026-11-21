package com.lianwenqi.app.data

import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.ai.RemoteModelInfo
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 配置领域：提示词、供应商、模型，以及模型请求转发 */
class ConfigRepository(
    private val db: AppDatabase,
    private val ai: AiClient = AiClient(),
    private val prefs: Prefs
) : ModelGateway {
    private val promptDao = db.promptDao()
    private val providerDao = db.providerDao()
    private val modelDao = db.aiModelDao()

    // ---------------- 提示词 ----------------

    fun observePrompts(): Flow<List<Prompt>> = promptDao.observeAll()

    suspend fun getPrompts(): List<Prompt> = promptDao.getAll()

    suspend fun savePrompt(prompt: Prompt) {
        if (prompt.id == 0L) promptDao.insert(prompt) else promptDao.update(prompt)
    }

    suspend fun deletePrompt(id: Long) = promptDao.deleteById(id)

    // ---------------- 供应商 / 模型 ----------------

    fun observeProviders(): Flow<List<Provider>> = providerDao.observeAll()

    suspend fun getProviders(): List<Provider> = providerDao.getAll()

    suspend fun saveProvider(provider: Provider): Long =
        if (provider.id == 0L) providerDao.insert(provider)
        else {
            providerDao.update(provider); provider.id
        }

    suspend fun deleteProvider(id: Long) {
        modelDao.getByProvider(id).forEach { modelDao.deleteById(it.id) }
        providerDao.deleteById(id)
    }

    fun observeModels(): Flow<List<AiModel>> = modelDao.observeAll()

    suspend fun getModels(): List<AiModel> = modelDao.getAll()

    override suspend fun getModel(id: Long): AiModel? = modelDao.getById(id)

    suspend fun saveModel(model: AiModel): Long {
        if (model.isDefault) modelDao.clearDefault()
        return if (model.id == 0L) modelDao.insert(model)
        else {
            modelDao.update(model); model.id
        }
    }

    suspend fun deleteModel(id: Long) = modelDao.deleteById(id)

    /** 读取供应商可用模型列表；失败时 Result 的异常为 [com.lianwenqi.app.ai.AiException]，内含可区分类型的 AiError */
    suspend fun fetchRemoteModels(provider: Provider): Result<List<String>> =
        ai.listModels(provider.baseUrl, provider.apiKey)

    /** 读取供应商模型列表，包含供应商暴露的上下文窗口（未提供时为 0） */
    suspend fun fetchRemoteModelInfos(provider: Provider): Result<List<RemoteModelInfo>> =
        ai.listModelInfos(provider.baseUrl, provider.apiKey)

    /**
     * 把供应商返回的上下文窗口回填到该供应商下仍为「未知」的模型。
     *
     * 只补 0，不覆盖用户已填写的值；返回更新条数。
     */
    suspend fun syncModelContextWindows(
        providerId: Long,
        infos: List<RemoteModelInfo>
    ): Int {
        val byName = infos.filter { it.contextWindow > 0 }.associate { it.id to it.contextWindow }
        if (byName.isEmpty()) return 0
        var updated = 0
        modelDao.getByProvider(providerId).forEach { model ->
            if (model.contextWindow > 0) return@forEach
            val resolved = byName[model.name] ?: return@forEach
            modelDao.update(model.copy(contextWindow = resolved))
            updated++
        }
        return updated
    }

    /** 组装可用于发请求的模型配置 */
    suspend fun endpointFor(modelId: Long): ModelEndpoint? {
        val model = modelDao.getById(modelId) ?: return null
        val provider = providerDao.getById(model.providerId) ?: return null
        return ModelEndpoint(
            provider = provider,
            modelName = model.name,
            apiType = provider.apiType,
            temperature = model.temperature,
            maxTokens = model.maxTokens,
            maxContext = model.maxContext,
            contextWindow = model.contextWindow,
            systemPrompt = model.systemPrompt,
            reasoningEnabled = model.reasoningEnabled,
            reasoningLevel = model.reasoningLevel,
            forceEnglishThinking = prefs.genForceEnglishThinking.first()
        )
    }

    override suspend fun sendToModel(
        modelId: Long,
        request: AiRequest,
        onDelta: ((String, String) -> Unit)?
    ): AiResult {
        val endpoint = endpointFor(modelId) ?: return AiResult.fail("未找到模型配置，请先在设置中添加")
        val merged = if (request.systemPrompt.isNotBlank() && endpoint.systemPrompt.isBlank()) {
            endpoint.copy(systemPrompt = request.systemPrompt)
        } else {
            endpoint
        }
        return ai.chat(merged, request.turns, request.tools, onDelta, request.webSearch)
    }
}
