package com.lianwenqi.app.data

import com.lianwenqi.app.ai.AiClient
import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiException
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.ai.RemoteModelInfo
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.PromptType
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.ProviderKey
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
    private val providerKeyDao = db.providerKeyDao()
    private val modelDao = db.aiModelDao()

    // ---------------- 提示词 ----------------

    /** 全部提示词（两种类型混合）；新代码请用 [observePrompts] 的类型重载 */
    fun observePrompts(): Flow<List<Prompt>> = promptDao.observeAll()

    /** 只观察指定类型：模型提示词用于模型配置，快捷提示词用于聊天输入栏 */
    fun observePrompts(type: PromptType): Flow<List<Prompt>> = promptDao.observeByType(type)

    suspend fun getPrompts(): List<Prompt> = promptDao.getAll()

    suspend fun getPrompts(type: PromptType): List<Prompt> = promptDao.getByType(type)

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
        modelDao.getByProvider(id).forEach { removeModel(it) }
        providerKeyDao.deleteByProvider(id)
        providerDao.deleteById(id)
    }

    // ---------------- API 密钥 ----------------

    fun observeProviderKeys(): Flow<List<ProviderKey>> = providerKeyDao.observeAll()

    suspend fun getProviderKeys(providerId: Long): List<ProviderKey> =
        providerKeyDao.getByProvider(providerId)

    /**
     * 保存密钥（新增或编辑）。
     *
     * 校验：API Key 不能为空；同一供应商下不允许出现完全相同的 API Key。
     * 校验失败抛出 [IllegalArgumentException]，由界面提示。
     */
    suspend fun saveProviderKey(key: ProviderKey): Long {
        val clean = key.copy(
            name = key.name.trim().ifBlank { "默认密钥" },
            apiKey = key.apiKey.trim()
        )
        require(clean.apiKey.isNotBlank()) { "API Key 不能为空" }
        val duplicated = providerKeyDao.getByProvider(clean.providerId)
            .any { it.id != clean.id && it.apiKey == clean.apiKey }
        require(!duplicated) { "该供应商下已存在相同的 API Key" }
        val now = System.currentTimeMillis()
        return if (clean.id == 0L) {
            providerKeyDao.insert(clean.copy(createdAt = now, updatedAt = now))
        } else {
            providerKeyDao.update(clean.copy(updatedAt = now))
            clean.id
        }
    }

    /** 删除密钥及其下所有模型；同时清理失效的当前使用模型与默认模型 */
    suspend fun deleteProviderKey(id: Long) {
        modelDao.getByProviderKey(id).forEach { removeModel(it) }
        providerKeyDao.deleteById(id)
    }

    fun observeModels(): Flow<List<AiModel>> = modelDao.observeAll()

    suspend fun getModels(): List<AiModel> = modelDao.getAll()

    override suspend fun getModel(id: Long): AiModel? = modelDao.getById(id)

    suspend fun saveModel(model: AiModel): Long {
        // 兜底：历史/异常数据没有密钥归属时，挂到该供应商的第一个密钥上
        val resolved = if (model.providerKeyId == 0L) {
            providerKeyDao.getByProvider(model.providerId).firstOrNull()
                ?.let { model.copy(providerKeyId = it.id) } ?: model
        } else model
        if (resolved.isDefault) modelDao.clearDefault()
        return if (resolved.id == 0L) modelDao.insert(resolved)
        else {
            modelDao.update(resolved); resolved.id
        }
    }

    /** 批量添加模型（远程多选导入）：逐一保存，均不影响默认模型 */
    suspend fun saveModels(models: List<AiModel>): List<Long> = models.map { saveModel(it) }

    suspend fun deleteModel(id: Long) {
        modelDao.getById(id)?.let { removeModel(it) }
    }

    /**
     * 删除模型并维护关联状态：
     * - 被删的是默认模型 → 把剩余第一个模型设为默认；
     * - 被删的是当前使用模型 → 清空 activeModelId。
     */
    private suspend fun removeModel(model: AiModel) {
        modelDao.deleteById(model.id)
        if (model.isDefault && modelDao.getAll().none { it.isDefault }) {
            modelDao.getFirst()?.let { modelDao.update(it.copy(isDefault = true)) }
        }
        if (prefs.activeModelId.first() == model.id) prefs.setActiveModelId(0L)
    }

    /** 读取密钥可用模型列表；失败时 Result 的异常为 [com.lianwenqi.app.ai.AiException] */
    suspend fun fetchRemoteModels(provider: Provider, key: ProviderKey): Result<List<String>> =
        fetchRemoteModelInfos(provider, key).map { infos -> infos.map { it.id } }

    /** 读取密钥模型列表（使用该密钥自己的 API Key），包含上下文窗口（未提供时为 0） */
    suspend fun fetchRemoteModelInfos(
        provider: Provider,
        key: ProviderKey
    ): Result<List<RemoteModelInfo>> {
        if (!key.enabled) {
            return Result.failure(
                AiException(AiError.InvalidConfig("密钥「${key.name}」已禁用，请先启用后再读取模型"))
            )
        }
        return ai.listModelInfos(provider.baseUrl, key.apiKey)
    }

    /**
     * 把密钥返回的上下文窗口回填到该密钥下仍为「未知」的模型。
     *
     * 只补 0，不覆盖已有值；同步范围严格限定在 [providerKeyId]，不会影响其他密钥下的同名模型。
     */
    suspend fun syncModelContextWindows(
        providerKeyId: Long,
        infos: List<RemoteModelInfo>
    ): Int {
        val byName = infos.filter { it.contextWindow > 0 }.associate { it.id to it.contextWindow }
        if (byName.isEmpty()) return 0
        var updated = 0
        modelDao.getByProviderKey(providerKeyId).forEach { model ->
            if (model.contextWindow > 0) return@forEach
            val resolved = byName[model.name] ?: return@forEach
            modelDao.update(model.copy(contextWindow = resolved))
            updated++
        }
        return updated
    }

    /** 组装可用于发请求的模型配置；密钥缺失 / 禁用 / 为空时通过 [ModelEndpoint.blockedReason] 返回原因 */
    suspend fun endpointFor(modelId: Long): ModelEndpoint? {
        val model = modelDao.getById(modelId) ?: return null
        val provider = providerDao.getById(model.providerId) ?: return null
        val key = if (model.providerKeyId != 0L) providerKeyDao.getById(model.providerKeyId) else null
        return ModelEndpoint(
            provider = provider,
            apiKey = key?.apiKey.orEmpty(),
            blockedReason = when {
                key == null -> "该模型尚未绑定 API 密钥，请到设置中重新配置"
                !key.enabled -> "该模型所属密钥「${key.name}」已禁用，请启用密钥或切换其他模型"
                key.apiKey.isBlank() -> "该模型所属密钥「${key.name}」未填写 API Key，请到设置中补填"
                else -> null
            },
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
