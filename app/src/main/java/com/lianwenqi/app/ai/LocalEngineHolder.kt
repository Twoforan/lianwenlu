package com.lianwenqi.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 本地推理引擎的懒加载持有者。
 *
 * 重排模型有 200MB+，启动即加载会明显拖慢冷启动并长期占用内存，
 * 因此首次真正需要本地推理时才加载。加载失败（模型缺失 / 内存不足 / 非 arm64）时返回 null，
 * 由上层以「本地向量模型未就绪」提示，不影响其他功能。
 */
class LocalEngineHolder(private val store: LocalModelStore) {

    private val mutex = Mutex()
    private var engine: LocalEmbeddingEngine? = null
    private var failed = false

    suspend fun get(): LocalEmbeddingEngine? = mutex.withLock {
        engine?.let { return@withLock it }
        if (failed) return@withLock null
        if (!store.isReady(LocalModelStore.EMBEDDING)) return@withLock null

        val embedding = store.fileOf(LocalModelStore.EMBEDDING)
        val rerank = store.fileOf(LocalModelStore.RERANK)
            .takeIf { store.isReady(LocalModelStore.RERANK) }
        val loaded = withContext(Dispatchers.IO) {
            // 类加载 / 加载 .so 失败都是 Error（UnsatisfiedLinkError），runCatching 一并兜住
            runCatching { LocalEmbeddingEngine(embedding, rerank) }.getOrNull()
        }
        if (loaded == null || !loaded.isReady) {
            loaded?.close()
            failed = true
            return@withLock null
        }
        engine = loaded
        loaded
    }

    /** 模型文件有变动（重新下载 / 删除）后调用，让下次推理重新尝试加载 */
    suspend fun invalidate() = mutex.withLock {
        engine?.close()
        engine = null
        failed = false
    }
}
