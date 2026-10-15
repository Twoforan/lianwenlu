package com.lianwenqi.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI 兼容的 Embedding 客户端，用于「长期记忆（书本向量化）」。
 *
 * 端点：`baseUrl` + `/embeddings`（通义 / 智谱 / 硅基流动等同构）。
 * 错误口径与 [AiClient] 保持一致：前置配置校验 + 状态码映射 + 异常兜底，
 * 任何情况下都只返回 [Result.failure]（异常为 [AiException]），绝不抛出导致 App 崩溃。
 */
class EmbeddingClient(
    private val client: OkHttpClient = AiClient.defaultClient()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * 批量向量化：返回与 [texts] 等长、顺序一致的向量列表。
     * 按 [BATCH_SIZE] 条一批串行请求；任一维度不一致即整体失败并提示重新向量化。
     */
    suspend fun embed(
        texts: List<String>,
        baseUrl: String,
        apiKey: String,
        model: String
    ): Result<List<FloatArray>> {
        if (texts.isEmpty()) return Result.success(emptyList())
        if (apiKey.isBlank()) {
            return Result.failure(AiException(AiError.InvalidConfig("未填写 apikey")))
        }
        val bad = apiKey.firstOrNull { it.code >= 0x7f || it.code <= 0x1f }
        if (bad != null) {
            return Result.failure(
                AiException(AiError.InvalidConfig("apikey 含非法字符（不支持中文或控制字符），请重新填写"))
            )
        }
        val trimmedBase = baseUrl.trim()
        if (trimmedBase.isBlank()) {
            return Result.failure(AiException(AiError.InvalidConfig("未填写 baseUrl")))
        }
        if (!trimmedBase.startsWith("http://") && !trimmedBase.startsWith("https://")) {
            return Result.failure(
                AiException(AiError.InvalidConfig("baseUrl 格式不正确（需以 http:// 或 https:// 开头）"))
            )
        }
        if (model.isBlank()) {
            return Result.failure(AiException(AiError.InvalidConfig("未填写向量模型名")))
        }
        val base = trimmedBase.trimEnd('/')
        return withContext(Dispatchers.IO) {
            try {
                val out = ArrayList<FloatArray>(texts.size)
                texts.chunked(BATCH_SIZE).forEach { batch ->
                    currentCoroutineContext().ensureActive()
                    out.addAll(embedBatch(batch, base, apiKey, model))
                }
                val dim = out.firstOrNull()?.size ?: 0
                if (out.any { it.size != dim }) {
                    return@withContext Result.failure(
                        AiException(AiError.InvalidConfig("向量维度不一致，请检查向量模型后重新向量化"))
                    )
                }
                Result.success(out)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(AiException(AiError.fromThrowable(e)))
            }
        }
    }

    /** 单个批次请求；失败以 [AiException] 抛出，由 [embed] 统一兜底 */
    private fun embedBatch(
        texts: List<String>,
        base: String,
        apiKey: String,
        model: String
    ): List<FloatArray> {
        val url = if (base.endsWith("/embeddings")) base else "$base/embeddings"
        val body = json.encodeToString(EmbeddingRequest.serializer(), EmbeddingRequest(model, texts))
            .toRequestBody(JSON_MEDIA)
        // 构造请求放进 try 之外无妨：url / header 非法都会以 IllegalArgumentException 逃出，
        // 由 embed 的 catch(Throwable) 兜成 InvalidConfig，不会崩 App
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val error = when (resp.code) {
                    401, 403 -> AiError.Unauthorized()
                    else -> AiError.Server(resp.code, text.take(200))
                }
                throw AiException(error)
            }
            val parsed = json.decodeFromString(EmbeddingResponse.serializer(), text)
            parsed.error?.message?.let { throw AiException(AiError.Server(resp.code, it)) }
            val sorted = parsed.data.sortedBy { it.index }
            if (sorted.size != texts.size) {
                throw AiException(AiError.Parse(IllegalStateException("向量条数与输入不一致")))
            }
            return sorted.map { it.embedding.toFloatArray() }
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** 每批条数：控制请求体大小，避免服务端拒绝或超时 */
        private const val BATCH_SIZE = 16
    }
}

@Serializable
private data class EmbeddingRequest(val model: String, val input: List<String>)

@Serializable
private data class EmbeddingResponse(
    val data: List<EmbeddingItem> = emptyList(),
    val error: ApiError? = null
) {
    @Serializable
    data class EmbeddingItem(val index: Int = 0, val embedding: List<Float> = emptyList())
}
