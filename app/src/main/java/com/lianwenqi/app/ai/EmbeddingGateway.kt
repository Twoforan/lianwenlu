package com.lianwenqi.app.ai

import com.lianwenqi.app.data.EmbeddingConfig
import com.lianwenqi.app.data.MemoryToolMode
import kotlinx.coroutines.CancellationException

/**
 * 向量化能力抽象：把「厂商 Embedding API」与「本地 GGUF 工具」统一成同一套接口。
 *
 * 上层（[com.lianwenqi.app.data.MemoryRepository]）只依赖本接口，
 * 于是「之前提交给向量 API 的一切」在切到本地工具后自动改走本地模型，上层逻辑零改动。
 */
interface EmbeddingGateway {
    /** 批量向量化：返回与 [texts] 等长、顺序一致的向量 */
    suspend fun embed(texts: List<String>): Result<List<FloatArray>>

    /** 对候选文本按与 [query] 的相关性重排，返回与 [candidates] 等长、顺序一致的分数（越大越相关） */
    suspend fun rerank(query: String, candidates: List<String>): Result<FloatArray>

    /** 当前向量维度；0 表示未知（用于判断与库中已存向量是否一致） */
    suspend fun dimension(): Int
}

/**
 * 厂商 Embedding API：沿用 [EmbeddingClient] 的错误口径。
 * 该接口不提供重排，[rerank] 返回全 0，排序结果与原来的「余弦召回」完全一致。
 */
class RemoteEmbeddingGateway(
    private val client: EmbeddingClient,
    private val config: suspend () -> EmbeddingConfig
) : EmbeddingGateway {

    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> {
        val cfg = config()
        return client.embed(texts, cfg.baseUrl, cfg.apiKey, cfg.model)
    }

    override suspend fun rerank(query: String, candidates: List<String>): Result<FloatArray> =
        Result.success(FloatArray(candidates.size))

    /** 厂商模型的维度只有真正请求了才知道，这里不额外发请求，统一视为未知 */
    override suspend fun dimension(): Int = 0
}

/** 本地 GGUF 工具：所有推理都走 [LocalEmbeddingEngine]（llama.cpp，CPU） */
class LocalEmbeddingGateway(
    private val engine: suspend () -> LocalEmbeddingEngine?
) : EmbeddingGateway {

    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> = guard {
        requireEngine().embed(texts)
    }

    override suspend fun rerank(query: String, candidates: List<String>): Result<FloatArray> = guard {
        requireEngine().rerank(query, candidates)
    }

    override suspend fun dimension(): Int = engine()?.embeddingDim ?: 0

    private suspend fun requireEngine(): LocalEmbeddingEngine =
        engine() ?: throw AiException(AiError.LocalEngine("本地向量模型尚未下载或加载失败"))

    private suspend fun <T> guard(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AiException) {
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(AiException(AiError.LocalEngine(e.message ?: "本地向量化失败")))
    }
}

/** 按当前「向量化工具」设置分发到远程 / 本地；工具关闭时一律失败（等价于未配置） */
class MemoryEmbeddingGateway(
    private val remote: EmbeddingGateway,
    private val local: EmbeddingGateway,
    private val mode: suspend () -> String
) : EmbeddingGateway {

    private suspend fun target(): EmbeddingGateway? = when (mode()) {
        MemoryToolMode.LOCAL -> local
        MemoryToolMode.API -> remote
        else -> null
    }

    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> =
        target()?.embed(texts) ?: disabled()

    override suspend fun rerank(query: String, candidates: List<String>): Result<FloatArray> =
        target()?.rerank(query, candidates) ?: disabled()

    override suspend fun dimension(): Int = target()?.dimension() ?: 0

    private fun <T> disabled(): Result<T> =
        Result.failure(AiException(AiError.InvalidConfig("未开启向量化工具")))
}
