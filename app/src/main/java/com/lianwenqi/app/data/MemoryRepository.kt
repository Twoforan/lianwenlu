package com.lianwenqi.app.data

import androidx.room.withTransaction
import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiException
import com.lianwenqi.app.ai.EmbeddingClient
import com.lianwenqi.app.data.dao.ChapterDao
import com.lianwenqi.app.data.dao.MemoryChunkDao
import com.lianwenqi.app.data.dao.VolumeDao
import com.lianwenqi.app.data.entity.MemoryChunk
import com.lianwenqi.app.util.chunkChapter
import com.lianwenqi.app.util.cosineSimilarity
import com.lianwenqi.app.util.decodeVector
import com.lianwenqi.app.util.encodeVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 厂商 Embedding API 的调用配置 */
data class EmbeddingConfig(val baseUrl: String, val apiKey: String, val model: String)

/** 向量化流水线进度（上报给设置页 / 后台任务） */
data class VectorizeProgress(
    val bookId: Long,
    val volumeTitle: String = "",
    val chapterTitle: String = "",
    /** 已处理章节数（含命中跳过） */
    val done: Int = 0,
    /** 待处理章节总数（正文非空的章节） */
    val total: Int = 0,
    /** 正在运行 */
    val running: Boolean = false,
    /** 最近一次失败原因；为空表示无错误 */
    val error: String? = null
)

/**
 * 长期记忆（书本向量化）对外能力。
 * 发送消息时只用到 [retrieve] 与 [latestVectorizedUpdatedAt]；单元测试可手写 fake。
 */
interface MemoryGateway {
    /** 以 [query] 检索本书已向量化的正文块，返回最相关的 [topK] 段文本（未配置 / 无命中返回空） */
    suspend fun retrieve(bookId: Long, query: String, topK: Int = DEFAULT_TOP_K): List<String>

    /** 增量向量化本书正文（命中哈希的块跳过），逐章回调进度 */
    suspend fun vectorize(bookId: Long, onProgress: (VectorizeProgress) -> Unit = {})

    /** 本书已向量化内容所对应的最新章节更新时间；从未向量化返回 null */
    suspend fun latestVectorizedUpdatedAt(bookId: Long): Long?

    companion object {
        const val DEFAULT_TOP_K = 5
    }
}

/**
 * 长期记忆领域：书籍正文切块 → 调 Embedding API → 存 [MemoryChunk]；发送时按语义检索命中片段。
 *
 * 事务与配置通过构造参数注入：单元测试可用手写 fake DAO + 固定配置替换，无需真库与真网络。
 */
class MemoryRepository(
    private val chapterDao: ChapterDao,
    private val volumeDao: VolumeDao,
    private val chunkDao: MemoryChunkDao,
    private val embeddingClient: EmbeddingClient,
    private val config: suspend () -> EmbeddingConfig,
    private val runTransaction: suspend (suspend () -> Unit) -> Unit
) : MemoryGateway {

    constructor(db: AppDatabase, embeddingClient: EmbeddingClient, config: suspend () -> EmbeddingConfig) : this(
        chapterDao = db.chapterDao(),
        volumeDao = db.volumeDao(),
        chunkDao = db.memoryChunkDao(),
        embeddingClient = embeddingClient,
        config = config,
        runTransaction = { block -> db.withTransaction { block() } }
    )

    /** 某书已向量化的章节 id 变化流（进度页展示用） */
    fun observeVectorizedChapterIds(bookId: Long): Flow<List<Long>> = chunkDao.observeVectorizedChapters(bookId)

    /** 某书已向量化的章节 id 快照（进度页计算「到第X卷第Y章」用） */
    suspend fun vectorizedChapterIds(bookId: Long): List<Long> = chunkDao.vectorizedChapterIds(bookId)

    override suspend fun latestVectorizedUpdatedAt(bookId: Long): Long? =
        chunkDao.latestChapterUpdatedAt(bookId)

    override suspend fun retrieve(bookId: Long, query: String, topK: Int): List<String> {
        if (query.isBlank()) return emptyList()
        val rows = chunkDao.getByBook(bookId)
        if (rows.isEmpty()) return emptyList()
        // 检索失败（未配置 / 网络异常）时静默返回空：只影响本次参考资料，不阻断发送
        val cfg = config()
        val queryVector = embeddingClient.embed(listOf(query), cfg.baseUrl, cfg.apiKey, cfg.model)
            .getOrNull()?.firstOrNull() ?: return emptyList()
        if (queryVector.isEmpty()) return emptyList()
        return rows.mapNotNull { row ->
            val vector = decodeVector(row.vector)
            if (vector.size != queryVector.size) null
            else row.text to cosineSimilarity(queryVector, vector)
        }.filter { it.second > 0f }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
    }

    override suspend fun vectorize(bookId: Long, onProgress: (VectorizeProgress) -> Unit) {
        // 同一本书可能被「正文自动保存」与「发送前续跑」同时触发，串行化避免重复请求与写入
        vectorizeLock.withLock {
            runVectorize(bookId, onProgress)
        }
    }

    private val vectorizeLock = Mutex()

    private suspend fun runVectorize(bookId: Long, onProgress: (VectorizeProgress) -> Unit) {
        withContext(Dispatchers.IO) {
            val chapters = chapterDao.getByBook(bookId)
            val volumeTitles = volumeDao.getByBook(bookId).associate { it.id to it.title }
            val targets = chapters.filter { it.content.isNotBlank() }
            var done = 0
            var dim = 0
            var lastError: String? = null
            onProgress(
                VectorizeProgress(bookId, done = 0, total = targets.size, running = true)
            )

            for (chapter in targets) {
                val volumeTitle = volumeTitles[chapter.volumeId] ?: ""
                val chunks = chunkChapter(chapter.content)
                val stored = chunkDao.hashesOf(chapter.id)
                val upToDate = stored.isNotEmpty() &&
                    stored.all { it.chapterUpdatedAt == chapter.updatedAt } &&
                    stored.map { it.hash }.toSet() == chunks.map { it.hash }.toSet()
                var stepError: String? = null
                var aborted = false
                if (!upToDate) {
                    val cfg = config()
                    val vectors = embeddingClient.embed(chunks.map { it.text }, cfg.baseUrl, cfg.apiKey, cfg.model)
                    if (vectors.isFailure) {
                        val cause = vectors.exceptionOrNull()
                        stepError = "${chapter.title}：${cause?.message ?: "向量化失败"}"
                        lastError = stepError
                        // 配置类错误（缺 key / 地址非法）对每章都会失败，直接中止避免空跑整本书
                        aborted = (cause as? AiException)?.error is AiError.InvalidConfig
                    } else {
                        val list = vectors.getOrThrow()
                        val chapterDim = list.firstOrNull()?.size ?: 0
                        if (dim != 0 && chapterDim != dim) {
                            stepError = "向量维度不一致（$dim → $chapterDim），请检查向量模型后重新向量化"
                            lastError = stepError
                            aborted = true
                        } else {
                            dim = chapterDim
                            val rows = chunks.mapIndexed { i, chunk ->
                                MemoryChunk(
                                    bookId = bookId,
                                    volumeId = chapter.volumeId,
                                    chapterId = chapter.id,
                                    chunkIndex = chunk.index,
                                    text = chunk.text,
                                    hash = chunk.hash,
                                    chapterUpdatedAt = chapter.updatedAt,
                                    dim = chapterDim,
                                    vector = encodeVector(list[i])
                                )
                            }
                            runTransaction {
                                chunkDao.deleteByChapter(chapter.id)
                                chunkDao.insertAll(rows)
                            }
                        }
                    }
                }
                if (aborted) {
                    onProgress(
                        VectorizeProgress(
                            bookId = bookId,
                            volumeTitle = volumeTitle,
                            chapterTitle = chapter.title,
                            done = done,
                            total = targets.size,
                            running = false,
                            error = stepError
                        )
                    )
                    return@withContext
                }
                done++
                onProgress(
                    VectorizeProgress(
                        bookId = bookId,
                        volumeTitle = volumeTitle,
                        chapterTitle = chapter.title,
                        done = done,
                        total = targets.size,
                        running = done < targets.size,
                        error = stepError
                    )
                )
            }
            onProgress(
                VectorizeProgress(
                    bookId = bookId,
                    done = done,
                    total = targets.size,
                    running = false,
                    error = lastError
                )
            )
        }
    }
}
