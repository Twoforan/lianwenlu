package com.lianwenqi.app.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** 一个本地向量化模型的下载信息 */
data class LocalModelSpec(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    /** 主站 + 镜像，按顺序尝试；主站不通时自动换镜像 */
    val urls: List<String>
)

/**
 * 本地向量化模型的文件管理：路径、就绪校验、下载（带进度与断点续传）。
 *
 * 模型放在 App 私有外部目录（无需存储权限，卸载即清理）；
 * 就绪判定为「文件存在且前 4 字节是 GGUF 魔数」，带宽不足中断产生的残包不会被当成就绪。
 */
class LocalModelStore(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val modelDir: File
        get() = File(context.getExternalFilesDir(null), "models").apply { if (!exists()) mkdirs() }

    fun fileOf(spec: LocalModelSpec): File = File(modelDir, spec.fileName)

    private fun partOf(spec: LocalModelSpec): File = File(modelDir, "${spec.fileName}.part")

    fun isReady(spec: LocalModelSpec): Boolean = isGguf(fileOf(spec))

    /** 两个模型是否都已就绪（本地工具可开启的条件） */
    fun allReady(): Boolean = SPECS.all { isReady(it) }

    /** 已就绪模型的合计占用 */
    fun downloadedBytes(): Long = SPECS.sumOf { if (isReady(it)) fileOf(it).length() else 0L }

    fun delete(spec: LocalModelSpec) {
        fileOf(spec).delete()
        partOf(spec).delete()
    }

    /**
     * 下载单个模型：优先断点续传（从 `.part` 已有长度继续），完成后校验魔数再改名。
     * [onProgress] 回调（已写入字节，总字节；总字节为 0 表示服务端未给出长度）。
     */
    suspend fun download(
        spec: LocalModelSpec,
        onProgress: (Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = fileOf(spec)
        if (isReady(spec)) return@withContext Result.success(target)
        val part = partOf(spec)
        var lastError: Throwable? = null
        for (url in spec.urls) {
            try {
                downloadFrom(url, part, onProgress)
                if (!isGguf(part)) {
                    part.delete()
                    throw IOException("下载内容不是有效的 GGUF 模型文件")
                }
                target.delete()
                if (!part.renameTo(target)) throw IOException("模型文件重命名失败")
                return@withContext Result.success(target)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
            }
        }
        Result.failure(AiException(AiError.LocalEngine(lastError?.message ?: "模型下载失败")))
    }

    private fun downloadFrom(url: String, part: File, onProgress: (Long, Long) -> Unit) {
        val existing = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(url)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("下载失败：响应为空")
            val resumed = resp.code == 206
            // 服务端不支持续传时会返回 200 与完整内容，此时必须从头写，避免残包 + 全量拼接出错
            val append = resumed && part.exists() && part.length() > 0
            val startAt = if (append) part.length() else 0L
            val total = if (resumed) startAt + body.contentLength() else body.contentLength()
            var written = startAt
            onProgress(written, total)
            RandomAccessFile(part, "rw").use { raf ->
                if (append) raf.seek(raf.length()) else raf.setLength(0)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        written += n
                        onProgress(written, total)
                    }
                }
            }
        }
    }

    companion object {
        /** 向量模型（必须）：把正文块与检索 query 换成向量 */
        val EMBEDDING = LocalModelSpec(
            id = "bge-small-zh-v1.5-q4_k_m",
            label = "BGE small zh v1.5 Q4_K_M",
            fileName = "bge-small-zh-v1.5-q4_k_m.gguf",
            sizeBytes = 15_448_256L,
            urls = listOf(
                "https://hf-mirror.com/CompendiumLabs/bge-small-zh-v1.5-gguf/resolve/main/bge-small-zh-v1.5-q4_k_m.gguf",
                "https://huggingface.co/CompendiumLabs/bge-small-zh-v1.5-gguf/resolve/main/bge-small-zh-v1.5-q4_k_m.gguf"
            )
        )

        /** 重排模型（必须）：对余弦召回的候选做精排 */
        val RERANK = LocalModelSpec(
            id = "bge-reranker-base-q4_k_m",
            label = "BGE reranker base Q4_K_M",
            fileName = "bge-reranker-base-q4_k_m.gguf",
            sizeBytes = 219_068_480L,
            urls = listOf(
                "https://hf-mirror.com/sabafallah/bge-reranker-base-Q4_K_M-GGUF/resolve/main/bge-reranker-base-q4_k_m.gguf",
                "https://huggingface.co/sabafallah/bge-reranker-base-Q4_K_M-GGUF/resolve/main/bge-reranker-base-q4_k_m.gguf"
            )
        )

        val SPECS = listOf(EMBEDDING, RERANK)

        /** 前 4 字节是 GGUF 魔数才算有效模型文件 */
        fun isGguf(file: File): Boolean {
            if (!file.exists() || file.length() < 8) return false
            return try {
                val head = ByteArray(4)
                file.inputStream().use { if (it.read(head) != 4) return false }
                String(head, Charsets.US_ASCII) == "GGUF"
            } catch (e: IOException) {
                false
            }
        }
    }
}
