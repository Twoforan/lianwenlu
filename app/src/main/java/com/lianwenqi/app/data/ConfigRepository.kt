package com.lianwenqi.app.data

import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.Provider
import kotlinx.coroutines.flow.Flow

/** 配置领域：提示词、供应商、模型，以及模型请求转发 */
class ConfigRepository(
    private val db: AppDatabase,
    private val ai: AiClient = AiClient()
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
            systemPrompt = model.systemPrompt,
            reasoningEnabled = model.reasoningEnabled,
            reasoningLevel = model.reasoningLevel
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
        return ai.chat(merged, request.turns, request.tools, onDelta)
    }
}
