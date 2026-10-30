package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.ai.EmbeddingGateway
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 正文修改后的增量向量化验收：旧块必须被替换而不是追加，检索必须命中新正文，
 * 进度页读取到的「已向量化时间」必须跟到最新正文。
 */
@RunWith(AndroidJUnit4::class)
class MemoryIncrementalVectorizeTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repo: BookRepository
    private lateinit var memory: MemoryRepository

    /** 关键词直接映射到不同向量：便于断言「召回的是新正文还是旧正文」 */
    private val gateway = object : EmbeddingGateway {
        override suspend fun embed(texts: List<String>) = Result.success(
            texts.map { text ->
                when {
                    text.contains("蓝色短刀") -> floatArrayOf(1f, 0f)
                    text.contains("红色长剑") -> floatArrayOf(0f, 1f)
                    else -> floatArrayOf(0f, 0f)
                }
            }
        )

        override suspend fun rerank(query: String, candidates: List<String>) =
            Result.success(FloatArray(candidates.size))

        override suspend fun dimension() = 2

        override suspend fun signature() = "fake|2"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = BookRepository(db)
        memory = MemoryRepository(db, gateway)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun 修改正文后增量重跑替换旧块且检索只命中新正文() = runBlocking {
        val bookId = repo.createBook("测试书", "", null, 0, "第%n卷", "第%n章")
        val chapterId = db.chapterDao().getByBook(bookId).single().id
        db.chapterDao().saveContent(chapterId, "第一章", "梗概", "他举起红色长剑。", 0, 100L)

        memory.vectorize(bookId)
        assertTrue(memory.retrieve(bookId, "红色长剑").any { it.contains("红色长剑") })

        // 修改正文并推进 updatedAt，模拟用户保存后的最新内容
        db.chapterDao().saveContent(chapterId, "第一章", "梗概", "他改用了蓝色短刀。", 0, 200L)

        memory.vectorize(bookId)

        val rows = db.memoryChunkDao().getByBook(bookId)
        assertTrue(rows.isNotEmpty())
        // 旧块被替换，而不是与新块并存
        assertTrue(rows.none { it.text.contains("红色长剑") })
        assertTrue(rows.any { it.text.contains("蓝色短刀") })
        // 进度页据此显示「已向量化」，不再停留在旧时间
        assertTrue((memory.vectorizedTimes(bookId)[chapterId] ?: 0L) >= 200L)

        // 新关键词能召回新正文；旧关键词不再召回旧片段
        assertTrue(memory.retrieve(bookId, "蓝色短刀").any { it.contains("蓝色短刀") })
        assertTrue(memory.retrieve(bookId, "红色长剑").isEmpty())

        // 当前章排除只作用于检索候选池，不影响该章已经被向量化
        assertTrue(memory.retrieve(bookId, "蓝色短刀", excludeChapterId = chapterId).isEmpty())
        assertTrue(db.memoryChunkDao().vectorizedChapterIds(bookId).contains(chapterId))
    }
}
