package com.lianwenqi.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地向量化引擎（llama.cpp + GGUF，CPU 推理）。
 *
 * 两个模型各持一个 native 句柄：[embeddingModel] 必填（bge-small-zh-v1.5），
 * [rerankModel] 缺失时只影响精排，向量化照常可用。句柄为 0 表示加载失败。
 */
class LocalEmbeddingEngine(
    embeddingModel: File,
    rerankModel: File?
) : AutoCloseable {

    private var embedHandle: Long = nativeLoadModel(embeddingModel.absolutePath, false)
    private var rerankHandle: Long =
        if (rerankModel != null) nativeLoadModel(rerankModel.absolutePath, true) else 0L

    /** 向量模型是否加载成功 */
    val isReady: Boolean get() = embedHandle != 0L

    /** 重排模型是否加载成功 */
    val hasRerank: Boolean get() = rerankHandle != 0L

    /** 向量维度；模型未就绪时为 0 */
    val embeddingDim: Int get() = if (embedHandle != 0L) nativeEmbeddingDim(embedHandle) else 0

    /** 批量向量化，返回与入参等长、顺序一致的归一化向量 */
    suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val handle = embedHandle
        if (handle == 0L) throw AiException(AiError.LocalEngine("本地向量模型未加载"))
        return withContext(Dispatchers.Default) {
            nativeEmbed(handle, texts.toTypedArray())?.toList()
                ?: throw AiException(AiError.LocalEngine("本地向量推理失败"))
        }
    }

    /** 对候选文本按与 [query] 的相关性打分，返回与 [candidates] 等长、顺序一致的分数 */
    suspend fun rerank(query: String, candidates: List<String>): FloatArray {
        if (candidates.isEmpty()) return FloatArray(0)
        val handle = rerankHandle
        if (handle == 0L) throw AiException(AiError.LocalEngine("本地重排模型未加载"))
        return withContext(Dispatchers.Default) {
            nativeRerank(handle, query, candidates.toTypedArray())
                ?: throw AiException(AiError.LocalEngine("本地重排推理失败"))
        }
    }

    override fun close() {
        val e = embedHandle
        val r = rerankHandle
        embedHandle = 0L
        rerankHandle = 0L
        if (e != 0L) nativeFreeModel(e)
        if (r != 0L) nativeFreeModel(r)
    }

    private external fun nativeLoadModel(path: String, rerank: Boolean): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeEmbeddingDim(handle: Long): Int
    private external fun nativeEmbed(handle: Long, texts: Array<String>): Array<FloatArray>?
    private external fun nativeRerank(handle: Long, query: String, docs: Array<String>): FloatArray?

    companion object {
        // 只编入 arm64-v8a（abiFilters）；非 arm64 设备加载会抛 UnsatisfiedLinkError，由调用方兜底
        init {
            System.loadLibrary("lwq_embed")
        }
    }
}
