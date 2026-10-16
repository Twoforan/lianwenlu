package com.lianwenqi.app.data

import com.lianwenqi.app.ai.EmbeddingClient
import com.lianwenqi.app.ai.EmbeddingGateway
import com.lianwenqi.app.ai.RemoteEmbeddingGateway
import com.lianwenqi.app.data.dao.ChapterDao
import com.lianwenqi.app.data.dao.ChunkHashRow
import com.lianwenqi.app.data.dao.MemoryChunkDao
import com.lianwenqi.app.data.dao.VolumeDao
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.MemoryChunk
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.util.chunkChapter
import com.lianwenqi.app.util.decodeVector
import com.lianwenqi.app.util.encodeVector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryRepository] 的增量与事务行为测试。
 * 依赖全部手写：fake DAO（内存表）+ 固定配置 + 拦截器伪造的 Embedding 响应。
 */
class MemoryRepositoryTest {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private var httpCalls = 0

    private fun embeddingClient(): EmbeddingClient {
        val okhttp = OkHttpClient.Builder().addInterceptor { chain ->
            httpCalls++
            val buffer = Buffer()
            chain.request().body?.writeTo(buffer)
            val root = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val inputs = root["input"]!!.jsonArray.map { it.jsonPrimitive.content }
            val data = inputs.mapIndexed { i, t ->
                """{"index":$i,"embedding":[${fakeVector(t).joinToString(",")}]}"""
            }.joinToString(",")
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"data":[$data]}""".toResponseBody(jsonMedia))
                .build()
        }.build()
        return EmbeddingClient(okhttp)
    }

    private fun repository(
        chapterDao: ChapterDao,
        volumeDao: VolumeDao,
        chunkDao: MemoryChunkDao,
        config: EmbeddingConfig = EmbeddingConfig("https://api.test/v1", "sk-abc", "m")
    ) = MemoryRepository(
        chapterDao, volumeDao, chunkDao,
        RemoteEmbeddingGateway(embeddingClient()) { config },
        { it() }
    )

    // ---------- fake DAO ----------

    private class FakeChunkDao : MemoryChunkDao {
        val rows = mutableListOf<MemoryChunk>()
        var deleteByChapterCalls = 0

        override suspend fun insertAll(chunks: List<MemoryChunk>) {
            rows.addAll(chunks)
        }

        override suspend fun deleteByChapter(chapterId: Long) {
            deleteByChapterCalls++
            rows.removeAll { it.chapterId == chapterId }
        }

        override suspend fun deleteByBook(bookId: Long) {
            rows.removeAll { it.bookId == bookId }
        }

        override suspend fun getByBook(bookId: Long) = rows.filter { it.bookId == bookId }

        override suspend fun hashesOf(chapterId: Long) =
            rows.filter { it.chapterId == chapterId }.map { ChunkHashRow(it.hash, it.chapterUpdatedAt) }

        override suspend fun countDistinctChapter(bookId: Long) =
            rows.filter { it.bookId == bookId }.map { it.chapterId }.distinct().size

        override suspend fun latestChapterUpdatedAt(bookId: Long) =
            rows.filter { it.bookId == bookId }.maxOfOrNull { it.chapterUpdatedAt }

        override suspend fun vectorizedChapterIds(bookId: Long) =
            rows.filter { it.bookId == bookId }.map { it.chapterId }.distinct()

        override suspend fun anyDim(bookId: Long) = rows.firstOrNull { it.bookId == bookId }?.dim

        override fun observeVectorizedChapters(bookId: Long): Flow<List<Long>> = flowOf(emptyList())
    }

    private class FakeChapterDao(private val chapters: List<Chapter>) : ChapterDao {
        override fun observeByBook(bookId: Long): Flow<List<Chapter>> = error("未使用")
        override suspend fun getByBook(bookId: Long) = chapters.filter { it.bookId == bookId }
        override fun observeById(id: Long): Flow<Chapter?> = error("未使用")
        override suspend fun getById(id: Long) = chapters.firstOrNull { it.id == id }
        override suspend fun countByBook(bookId: Long) = chapters.count { it.bookId == bookId }
        override fun observeCountByBook(bookId: Long): Flow<Int> = error("未使用")
        override suspend fun insert(chapter: Chapter) = error("未使用")
        override suspend fun update(chapter: Chapter) = error("未使用")
        override suspend fun saveContent(
            id: Long, title: String, summary: String, content: String, cursor: Int, time: Long
        ) = error("未使用")
        override suspend fun setOrder(id: Long, order: Int) = error("未使用")
        override suspend fun moveToVolume(id: Long, volumeId: Long?, order: Int) = error("未使用")
        override suspend fun deleteById(id: Long) = error("未使用")
        override suspend fun searchInBook(bookId: Long, keyword: String) = error("未使用")
    }

    private class FakeVolumeDao(private val volumes: List<Volume>) : VolumeDao {
        override fun observeByBook(bookId: Long): Flow<List<Volume>> = error("未使用")
        override suspend fun getByBook(bookId: Long) = volumes.filter { it.bookId == bookId }
        override suspend fun insert(volume: Volume) = error("未使用")
        override suspend fun update(volume: Volume) = error("未使用")
        override suspend fun setOrder(id: Long, order: Int) = error("未使用")
        override suspend fun deleteById(id: Long) = error("未使用")
    }

    private fun chapter(id: Long, bookId: Long, volumeId: Long, content: String, updatedAt: Long) =
        Chapter(id = id, bookId = bookId, volumeId = volumeId, title = "第${id}章", content = content, updatedAt = updatedAt)

    // ---------- 用例 ----------

    @Test
    fun vectorize_skipsChapterWhoseHashesAndUpdatedAtUnchanged() = runTest {
        val content = "第一段正文。\n\n第二段正文。"
        val updatedAt = 1000L
        val chunkDao = FakeChunkDao()
        val chunks = chunkChapter(content)
        chunkDao.rows.addAll(
            chunks.map { c ->
                MemoryChunk(
                    bookId = 1, volumeId = 10, chapterId = 7, chunkIndex = c.index, text = c.text,
                    hash = c.hash, chapterUpdatedAt = updatedAt, dim = 4, vector = encodeVector(fakeVector(c.text))
                )
            }
        )
        val repo = repository(
            FakeChapterDao(listOf(chapter(7, 1, 10, content, updatedAt))),
            FakeVolumeDao(listOf(Volume(id = 10, bookId = 1, title = "第一卷"))),
            chunkDao
        )
        var last: VectorizeProgress? = null
        repo.vectorize(1) { last = it }

        assertEquals(0, httpCalls)
        assertEquals(0, chunkDao.deleteByChapterCalls)
        assertEquals(chunks.size, chunkDao.rows.size)
        assertNotNull(last)
        assertEquals(1, last!!.done)
        assertEquals(1, last!!.total)
        assertTrue(!last!!.running)
        assertNull(last!!.error)
    }

    @Test
    fun vectorize_rewritesChapterWhenContentChanged() = runTest {
        val content = "改写后的正文。"
        val chunkDao = FakeChunkDao()
        // 旧内容留下的哈希（与当前切块不一致）
        chunkDao.rows.add(
            MemoryChunk(
                bookId = 1, volumeId = 10, chapterId = 7, chunkIndex = 0, text = "旧正文",
                hash = "stale-hash", chapterUpdatedAt = 1L, dim = 4, vector = encodeVector(fakeVector("旧正文"))
            )
        )
        val repo = repository(
            FakeChapterDao(listOf(chapter(7, 1, 10, content, 2000L))),
            FakeVolumeDao(listOf(Volume(id = 10, bookId = 1, title = "第一卷"))),
            chunkDao
        )
        val events = mutableListOf<VectorizeProgress>()
        repo.vectorize(1) { events.add(it) }

        assertEquals(1, httpCalls)
        assertEquals(1, chunkDao.deleteByChapterCalls)
        val expected = chunkChapter(content)
        assertEquals(expected.size, chunkDao.rows.size)
        assertEquals(expected[0].hash, chunkDao.rows[0].hash)
        assertEquals(2000L, chunkDao.rows[0].chapterUpdatedAt)
        assertEquals(4, chunkDao.rows[0].dim)
        // 逐章进度带卷名与章名；最后一条是收尾汇总
        assertTrue(events.any { it.volumeTitle == "第一卷" && it.chapterTitle == "第7章" })
        assertTrue(events.last().done == 1 && events.last().total == 1 && !events.last().running)
    }

    @Test
    fun vectorize_reportsErrorAndAbortsOnInvalidConfig() = runTest {
        val chunkDao = FakeChunkDao()
        val repo = repository(
            FakeChapterDao(
                listOf(
                    chapter(7, 1, 10, "正文一", 1L),
                    chapter(8, 1, 10, "正文二", 1L)
                )
            ),
            FakeVolumeDao(emptyList()),
            chunkDao,
            config = EmbeddingConfig("https://api.test/v1", "", "m")
        )
        var last: VectorizeProgress? = null
        repo.vectorize(1) { last = it }

        assertEquals(0, httpCalls)
        assertTrue(chunkDao.rows.isEmpty())
        assertNotNull(last!!.error)
        assertTrue(!last!!.running)
        assertEquals(0, last!!.done)
        assertEquals(2, last!!.total)
    }

    @Test
    fun vectorize_continuesAfterSingleChapterFailure() = runTest {
        // 第二章请求失败（500），第一章正常入库，进度继续推进
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val okhttp = OkHttpClient.Builder().addInterceptor { chain ->
            val n = calls.incrementAndGet()
            if (n == 2) {
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(500).message("Error").body("boom".toResponseBody(jsonMedia)).build()
            } else {
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                val root = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                val inputs = root["input"]!!.jsonArray.map { it.jsonPrimitive.content }
                val data = inputs.mapIndexed { i, t ->
                    """{"index":$i,"embedding":[${fakeVector(t).joinToString(",")}]}"""
                }.joinToString(",")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("""{"data":[$data]}""".toResponseBody(jsonMedia)).build()
            }
        }.build()
        val chunkDao = FakeChunkDao()
        val repo = MemoryRepository(
            FakeChapterDao(
                listOf(
                    chapter(7, 1, 10, "第一章正文", 1L),
                    chapter(8, 1, 10, "第二章正文", 1L)
                )
            ),
            FakeVolumeDao(emptyList()),
            chunkDao,
            RemoteEmbeddingGateway(EmbeddingClient(okhttp)) {
                EmbeddingConfig("https://api.test/v1", "sk-abc", "m")
            },
            { it() }
        )
        var last: VectorizeProgress? = null
        repo.vectorize(1) { last = it }

        assertEquals(2, last!!.done)
        assertEquals(2, last!!.total)
        assertEquals(1, chunkDao.rows.size)
        assertEquals(7L, chunkDao.rows[0].chapterId)
        assertTrue(last!!.error!!.contains("第8章"))
    }

    @Test
    fun retrieve_returnsMostSimilarChunkFirst() = runTest {
        val chunkDao = FakeChunkDao()
        val target = "命中目标片段"
        val other = "无关片段"
        chunkDao.rows.addAll(
            listOf(target, other).mapIndexed { i, text ->
                MemoryChunk(
                    bookId = 1, volumeId = null, chapterId = 7, chunkIndex = i, text = text,
                    hash = "h$i", chapterUpdatedAt = 1L, dim = 4, vector = encodeVector(fakeVector(text))
                )
            }
        )
        val repo = repository(FakeChapterDao(emptyList()), FakeVolumeDao(emptyList()), chunkDao)
        val result = repo.retrieve(1, target, topK = 1)
        assertEquals(listOf(target), result)
    }

    @Test
    fun retrieve_reordersCandidatesByRerankScore() = runTest {
        val chunkDao = FakeChunkDao()
        val texts = listOf("甲片段", "乙片段", "丙片段")
        chunkDao.rows.addAll(
            texts.mapIndexed { i, text ->
                MemoryChunk(
                    // 三块分属不同章节，避开「每章最多 2 块」的配额
                    bookId = 1, volumeId = null, chapterId = (7 + i).toLong(), chunkIndex = i, text = text,
                    hash = "h$i", chapterUpdatedAt = 1L, dim = 4, vector = encodeVector(fakeVector(text))
                )
            }
        )
        // 重排分数全 0（等价于厂商 API 无重排）：顺序就是余弦召回顺序
        val byCosine = MemoryRepository(
            FakeChapterDao(emptyList()), FakeVolumeDao(emptyList()), chunkDao,
            FakeGateway(), { it() }
        ).retrieve(1, "甲片段", topK = 3)
        // 重排给「越靠后的候选分数越高」：最终顺序应为余弦顺序的反序
        val reranked = MemoryRepository(
            FakeChapterDao(emptyList()), FakeVolumeDao(emptyList()), chunkDao,
            FakeGateway(rerankScores = { n -> FloatArray(n) { it.toFloat() } }), { it() }
        ).retrieve(1, "甲片段", topK = 3)

        assertEquals(3, byCosine.size)
        assertEquals(byCosine.reversed(), reranked)
    }

    @Test
    fun retrieve_excludesCurrentChapterAndCapsChunksPerChapter() = runTest {
        val chunkDao = FakeChunkDao()
        // 所有块向量相同 → 余弦全为 1（并列），召回顺序即入库顺序，便于断言
        fun row(chapterId: Long, index: Int, text: String) = MemoryChunk(
            bookId = 1, volumeId = null, chapterId = chapterId, chunkIndex = index, text = text,
            hash = "h-$chapterId-$index", chapterUpdatedAt = 1L, dim = 2,
            vector = encodeVector(floatArrayOf(1f, 0f))
        )
        chunkDao.rows.addAll(
            listOf(
                row(7, 0, "当前章片段"),
                row(8, 0, "第八章甲"),
                row(8, 1, "第八章乙"),
                row(8, 2, "第八章丙"),
                row(9, 0, "第九章甲")
            )
        )
        val gateway = object : EmbeddingGateway {
            override suspend fun embed(texts: List<String>) =
                Result.success(texts.map { floatArrayOf(1f, 0f) })

            override suspend fun rerank(query: String, candidates: List<String>) =
                Result.success(FloatArray(candidates.size))

            override suspend fun dimension() = 2
        }
        val result = MemoryRepository(
            FakeChapterDao(emptyList()), FakeVolumeDao(emptyList()), chunkDao, gateway, { it() }
        ).retrieve(1, "任意查询", topK = 5, excludeChapterId = 7)

        // 当前章（7）被排除
        assertTrue(result.none { it == "当前章片段" })
        // 第八章只保留 2 块，剩下的名额让给了第九章
        assertEquals(3, result.size)
        assertEquals(2, result.count { it.startsWith("第八章") })
        assertTrue(result.contains("第九章甲"))
    }

    @Test
    fun retrieve_returnsEmptyWhenNoChunks() = runTest {
        val repo = repository(FakeChapterDao(emptyList()), FakeVolumeDao(emptyList()), FakeChunkDao())
        assertTrue(repo.retrieve(1, "任意查询", topK = 5).isEmpty())
    }

    @Test
    fun latestVectorizedUpdatedAt_reflectsNewestChunk() = runTest {
        val chunkDao = FakeChunkDao()
        chunkDao.rows.addAll(
            listOf(11L, 22L).mapIndexed { i, t ->
                MemoryChunk(
                    bookId = 1, volumeId = null, chapterId = i.toLong(), chunkIndex = 0, text = "t",
                    hash = "h", chapterUpdatedAt = t, dim = 1, vector = encodeVector(floatArrayOf(1f))
                )
            }
        )
        val repo = repository(FakeChapterDao(emptyList()), FakeVolumeDao(emptyList()), chunkDao)
        assertEquals(22L, repo.latestVectorizedUpdatedAt(1))
    }

    @Test
    fun decodeVector_roundTripForStoredChunk() {
        val v = fakeVector("任意")
        assertEquals(v.toList(), decodeVector(encodeVector(v)).toList())
    }
}

/** 与文本稳定对应的 4 维假向量：同文本必得同向量，自身相似度为 1（必定排最前） */
private fun fakeVector(text: String): FloatArray {
    val h = text.hashCode()
    return floatArrayOf(
        ((h and 0xff) + 1).toFloat(),
        (((h shr 8) and 0xff) + 1).toFloat(),
        (((h shr 16) and 0xff) + 1).toFloat(),
        (((h shr 24) and 0xff) + 1).toFloat()
    )
}

/**
 * 测试用最小 [EmbeddingGateway]：embed 用 [fakeVector]，rerank 分数由 [rerankScores] 决定。
 * 默认返回全 0，等价于「厂商 API 无重排」。
 */
private class FakeGateway(
    private val dim: Int = 4,
    private val rerankScores: (Int) -> FloatArray = { FloatArray(it) }
) : EmbeddingGateway {
    override suspend fun embed(texts: List<String>): Result<List<FloatArray>> =
        Result.success(texts.map { fakeVector(it) })

    override suspend fun rerank(query: String, candidates: List<String>): Result<FloatArray> =
        Result.success(rerankScores(candidates.size))

    override suspend fun dimension(): Int = dim
}
