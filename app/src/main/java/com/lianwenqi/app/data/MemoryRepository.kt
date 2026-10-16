package com.lianwenqi.app.data

import androidx.room.withTransaction
import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiException
import com.lianwenqi.app.ai.EmbeddingGateway
import com.lianwenqi.app.data.dao.ChapterDao
import com.lianwenqi.app.data.dao.MemoryChunkDao
import com.lianwenqi.app.data.dao.VolumeDao
import com.lianwenqi.app.data.entity.MemoryChunk
import com.lianwenqi.app.util.chunkChapter
import com.lianwenqi.app.util.cosineSimilarity
import com.lianwenqi.app.util.decodeVector
import com.lianwenqi.app.util.encodeVector
import com.lianwenqi.app.util.stampChunkHash
import com.lianwenqi.app.util.stampedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 厂商 Embedding API 的调用配置 */
data class EmbeddingConfig(val baseUrl: String, val apiKey: String, val model: String)

/** 余弦召回的候选数：先粗排取这么多，再交给重排模型精排到 [MemoryGateway.DEFAULT_TOP_K] */
private const val RECALL_TOP_K = 20

/** 同一章最多进入最终参考资料的块数：防止一段参考资料全被同一章占满 */
private const val MAX_PER_CHAPTER = 2

/** 范围偏好对余弦分数的最大加权幅度（±15%）：够把候选偏向所选范围，又不会压过语义相关度 */
private const val POSITION_BIAS = 0.15f

/** 余弦召回的一条命中：正文片段、所属章节与余弦分数 */
private data class Recall(val text: String, val chapterId: Long, val score: Float)

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
    /**
     * 以 [query] 检索本书已向量化的正文块，返回最相关的 [topK] 段文本。
     * [excludeChapterId] 用于排除「当前正在写的章节」：它的正文/梗概本来就在提示词里，
     * 留在候选池里会凭「和自己梗概最像」霸占结果。
     * [scope] 为范围偏好：落进 [MemorySearchScope.NEWEST] / [OLDEST] 时按章节位置给余弦分数加权，
     * 全书仍然参与检索，只是越符合偏好的章节越容易进入精排候选。
     */
    suspend fun retrieve(
        bookId: Long,
        query: String,
        topK: Int = DEFAULT_TOP_K,
        excludeChapterId: Long? = null,
        scope: String = MemorySearchScope.RANDOM
    ): List<String>

    /** 增量向量化本书正文（命中哈希的块跳过），逐章回调进度 */
    suspend fun vectorize(bookId: Long, onProgress: (VectorizeProgress) -> Unit = {})

    /** 本书已向量化内容所对应的最新章节更新时间；从未向量化返回 null */
    suspend fun latestVectorizedUpdatedAt(bookId: Long): Long?

    /**
     * 本书已存向量是否来自「其它向量化工具 / 模型」（即换了源却还没重新向量化）。
     * 换源后库里仍是旧源的向量，与当前查询向量算不了余弦，检索会一无所获，
     * 界面据此提示用户先重新向量化。
     */
    suspend fun vectorSourceStale(bookId: Long): Boolean

    companion object {
        const val DEFAULT_TOP_K = 5
    }
}

/**
 * 长期记忆领域：书籍正文切块 → 交给 [EmbeddingGateway] 向量化 → 存 [MemoryChunk]；
 * 发送时按语义召回候选再重排，命中片段作为参考资料。
 *
 * 事务与向量化能力通过构造参数注入：单元测试可用手写 fake DAO + fake gateway 替换，无需真库与真网络。
 */
class MemoryRepository(
    private val chapterDao: ChapterDao,
    private val volumeDao: VolumeDao,
    private val chunkDao: MemoryChunkDao,
    private val gateway: EmbeddingGateway,
    private val runTransaction: suspend (suspend () -> Unit) -> Unit
) : MemoryGateway {

    constructor(db: AppDatabase, gateway: EmbeddingGateway) : this(
        chapterDao = db.chapterDao(),
        volumeDao = db.volumeDao(),
        chunkDao = db.memoryChunkDao(),
        gateway = gateway,
        runTransaction = { block -> db.withTransaction { block() } }
    )

    /** 某书已向量化的章节 id 变化流（进度页展示用） */
    fun observeVectorizedChapterIds(bookId: Long): Flow<List<Long>> = chunkDao.observeVectorizedChapters(bookId)

    /** 某书已向量化的章节 id 快照（进度页计算「到第X卷第Y章」用） */
    suspend fun vectorizedChapterIds(bookId: Long): List<Long> = chunkDao.vectorizedChapterIds(bookId)

    override suspend fun latestVectorizedUpdatedAt(bookId: Long): Long? =
        chunkDao.latestChapterUpdatedAt(bookId)

    override suspend fun vectorSourceStale(bookId: Long): Boolean {
        val storedHash = chunkDao.anyHash(bookId) ?: return false
        return !stampedBy(storedHash, gateway.signature())
    }

    override suspend fun retrieve(
        bookId: Long,
        query: String,
        topK: Int,
        excludeChapterId: Long?,
        scope: String
    ): List<String> {
        if (query.isBlank()) return emptyList()
        // 当前正在写的章节不进候选池：它已经在本章梗概 / 正文里了，
        // 留着只会凭「和自己梗概最像」把其它章节挤出去
        val rows = chunkDao.getByBook(bookId).filter { excludeChapterId == null || it.chapterId != excludeChapterId }
        if (rows.isEmpty()) return emptyList()
        // 检索失败（工具关闭 / 模型未就绪 / 网络异常）时静默返回空：只影响本次参考资料，不阻断发送
        val queryVector = gateway.embed(listOf(query)).getOrNull()?.firstOrNull() ?: return emptyList()
        if (queryVector.isEmpty()) return emptyList()
        // 范围偏好：按章节在全书中的位置换算权重，作用在余弦分数上（随机匹配不加权）
        val positionBias = positionBiasOf(bookId, scope)
        // 全书正文块逐块算余弦是纯 CPU 计算（大书可达上万块），必须离开调用方线程，
        // 否则发送时会同步占用主线程造成界面卡顿
        val recalled = withContext(Dispatchers.Default) {
            rows.mapNotNull { row ->
                val vector = decodeVector(row.vector)
                if (vector.size != queryVector.size) null
                else Recall(
                    row.text,
                    row.chapterId,
                    cosineSimilarity(queryVector, vector) * (positionBias?.get(row.chapterId) ?: 1f)
                )
            }.filter { it.score > 0f }
                .sortedByDescending { it.score }
                .take(RECALL_TOP_K)
        }
        if (recalled.isEmpty()) return emptyList()
        // 余弦只做粗召回，命中质量交给重排模型精排；
        // 远程工具没有重排（分数全 0，稳定排序保持原序），结果与原来的纯余弦排序完全一致
        val scores = gateway.rerank(query, recalled.map { it.text }).getOrNull()
        val ordered = if (scores == null || scores.size != recalled.size) {
            recalled
        } else {
            recalled.mapIndexed { i, candidate -> candidate to scores[i] }
                .sortedByDescending { it.second }
                .map { it.first }
        }
        // 每章配额：精排后的结果里同一章最多留 MAX_PER_CHAPTER 块，
        // 否则「5 段参考资料」很容易全来自同一章，等于把上下文又塞回最近那章
        val used = HashMap<Long, Int>()
        val picked = ArrayList<String>(topK)
        for (hit in ordered) {
            val count = used.getOrDefault(hit.chapterId, 0)
            if (count >= MAX_PER_CHAPTER) continue
            used[hit.chapterId] = count + 1
            picked.add(hit.text)
            if (picked.size >= topK) break
        }
        return picked
    }

    /**
     * 范围偏好权重表：把章节在全书中的相对位置换算成 [-1, 1]（最早章 -1、最新章 +1），
     * 再按 [scope] 决定偏向哪一端，得到 ±[POSITION_BIAS] 的乘数；
     * [MemorySearchScope.RANDOM] 返回 null，表示不加权。
     */
    private suspend fun positionBiasOf(bookId: Long, scope: String): Map<Long, Float>? {
        if (scope == MemorySearchScope.RANDOM) return null
        val chapters = chapterDao.getByBook(bookId)
        if (chapters.size <= 1) return null
        val weight = HashMap<Long, Float>(chapters.size)
        chapters.forEachIndexed { index, chapter ->
            val centered = 2f * index / (chapters.size - 1) - 1f
            val aligned = if (scope == MemorySearchScope.OLDEST) -centered else centered
            weight[chapter.id] = 1f + POSITION_BIAS * aligned
        }
        return weight
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
            // 向量化源签名（向量化工具 + 模型）并进块哈希：换了源，旧哈希全部失配，整本自动重跑。
            // 这是「本地向量 → 切回厂商 API」后仍能检索到的关键：旧向量与新查询向量算不了余弦。
            val source = gateway.signature()
            // 换向量模型（维度变化）后，旧向量与新 vector 维度不一致会检索不到：
            // 此时整本书强制重跑，把已存向量统一切到新维度。
            // 只有本书已经存过向量时才需要比对维度，否则必然全量重跑，没必要多打一次探测请求。
            val storedDim = chunkDao.anyDim(bookId) ?: 0
            val targetDim = if (storedDim != 0) gateway.dimension() else 0
            val dimChanged = storedDim != 0 && targetDim != 0 && storedDim != targetDim
            var done = 0
            var dim = 0
            var lastError: String? = null
            onProgress(
                VectorizeProgress(bookId, done = 0, total = targets.size, running = true)
            )

            for (chapter in targets) {
                val volumeTitle = volumeTitles[chapter.volumeId] ?: ""
                val chunks = chunkChapter(chapter.content).map { it.copy(hash = stampChunkHash(source, it.hash)) }
                val stored = chunkDao.hashesOf(chapter.id)
                val upToDate = !dimChanged && stored.isNotEmpty() &&
                    stored.all { it.chapterUpdatedAt == chapter.updatedAt } &&
                    stored.map { it.hash }.toSet() == chunks.map { it.hash }.toSet()
                var stepError: String? = null
                var aborted = false
                if (!upToDate) {
                    val vectors = gateway.embed(chunks.map { it.text })
                    if (vectors.isFailure) {
                        val cause = vectors.exceptionOrNull()
                        stepError = "${chapter.title}：${cause?.message ?: "向量化失败"}"
                        lastError = stepError
                        // 配置 / 本地模型类错误对每章都会失败，直接中止避免空跑整本书
                        aborted = when ((cause as? AiException)?.error) {
                            is AiError.InvalidConfig, is AiError.LocalEngine -> true
                            else -> false
                        }
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
