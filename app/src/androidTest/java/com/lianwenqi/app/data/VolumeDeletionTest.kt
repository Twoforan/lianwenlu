package com.lianwenqi.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.ai.EmbeddingGateway
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.MemoryChunk
import com.lianwenqi.app.util.encodeVector
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 删除分卷的级联语义验收（方案 A）：
 * 删除分卷必须连同该卷章节与其向量块一起删除，且目录 / 搜索 / 向量化 / 记忆检索 / 备份导出
 * 各处对「有效章节」使用同一口径，历史孤儿章节不会被任何用户可见链路复活。
 */
@RunWith(AndroidJUnit4::class)
class VolumeDeletionTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private val opened = mutableListOf<AppDatabase>()

    /** 所有文本都映射成同一向量，余弦恒为 1，便于断言「谁被召回」 */
    private val gateway = object : EmbeddingGateway {
        override suspend fun embed(texts: List<String>) =
            Result.success(texts.map { floatArrayOf(1f, 0f) })

        override suspend fun rerank(query: String, candidates: List<String>) =
            Result.success(FloatArray(candidates.size))

        override suspend fun dimension() = 2

        override suspend fun signature() = "fake|2"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = Prefs(context)
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
    }

    private fun newDb(): AppDatabase {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        opened += db
        return db
    }

    private fun chunk(bookId: Long, chapterId: Long, volumeId: Long?, text: String) = MemoryChunk(
        bookId = bookId,
        volumeId = volumeId,
        chapterId = chapterId,
        chunkIndex = 0,
        text = text,
        hash = "hash-$chapterId",
        chapterUpdatedAt = 1L,
        dim = 2,
        vector = encodeVector(floatArrayOf(1f, 0f))
    )

    @Test
    fun 删除非空分卷级联删除章节与向量且目录搜索不再可见() = runBlocking {
        val db = newDb()
        val repo = BookRepository(db)
        val chunkDao = db.memoryChunkDao()

        val bookId = repo.createBook("测试书", "", null, 0, "第%n卷", "第%n章")
        val volumeA = repo.getVolumes(bookId).single().id
        val chapterA = db.chapterDao().getByBook(bookId).single().id
        db.chapterDao().saveContent(chapterA, "第一章", "卷A梗概", "卷A唯一正文", 0, 1L)
        chunkDao.insertAll(listOf(chunk(bookId, chapterA, volumeA, "卷A向量片段")))

        val volumeB = repo.createVolume(bookId)
        val chaptersB = (1..3).map { i ->
            val id = repo.createChapter(bookId, volumeB)
            db.chapterDao().saveContent(id, "B章$i", "卷B梗概$i", "卷B关键字正文$i", 0, 2L)
            id
        }
        chunkDao.insertAll(chaptersB.map { chunk(bookId, it, volumeB, "卷B向量片段") })

        // 删除前：向量进度与检索都能看到卷 B
        assertEquals(4, db.chapterDao().getByBook(bookId).size)
        assertEquals(4, chunkDao.vectorizedChapterIds(bookId).size)

        val deleted = repo.deleteVolume(volumeB)

        // 返回被删章节数；分卷与章节行、向量块全部消失
        assertEquals(3, deleted)
        assertEquals(listOf(volumeA), db.volumeDao().getByBook(bookId).map { it.id })
        assertEquals(listOf(chapterA), db.chapterDao().getByBook(bookId).map { it.id })
        assertEquals(listOf(chapterA), db.chapterDao().getValidByBook(bookId).map { it.id })
        assertEquals(listOf(chapterA), db.chapterDao().validIdsByBook(bookId))
        assertTrue(chunkDao.getByBook(bookId).all { it.chapterId == chapterA })

        // 目录 / 搜索不再能看到卷 B 的章节
        assertEquals(listOf(chapterA), repo.getChapters(bookId).map { it.id })
        assertTrue(repo.searchInBook(bookId, "卷B关键字").isEmpty())
        assertEquals(1, repo.searchInBook(bookId, "卷A唯一").size)

        // 记忆检索候选池不再命中卷 B 的正文
        val memory = MemoryRepository(db, gateway)
        val recalled = memory.retrieve(bookId, "任意查询", topK = 5)
        assertTrue(recalled.none { it.startsWith("卷B") })
        assertTrue(recalled.any { it == "卷A向量片段" })

        // 删除后章节与分卷序号都归一化为连续
        assertEquals(listOf(0), db.chapterDao().getByBook(bookId).map { it.sortOrder })
        assertEquals(listOf(0), db.volumeDao().getByBook(bookId).map { it.sortOrder })
    }

    @Test
    fun 删除空分卷不影响其他卷的章节与向量() = runBlocking {
        val db = newDb()
        val repo = BookRepository(db)
        val chunkDao = db.memoryChunkDao()

        val bookId = repo.createBook("测试书", "", null, 0, "第%n卷", "第%n章")
        val volumeA = repo.getVolumes(bookId).single().id
        val chapterA = db.chapterDao().getByBook(bookId).single().id
        chunkDao.insertAll(listOf(chunk(bookId, chapterA, volumeA, "卷A向量片段")))
        val volumeC = repo.createVolume(bookId)

        val deleted = repo.deleteVolume(volumeC)

        assertEquals(0, deleted)
        assertEquals(listOf(volumeA), db.volumeDao().getByBook(bookId).map { it.id })
        assertEquals(listOf(chapterA), db.chapterDao().getByBook(bookId).map { it.id })
        assertEquals(1, chunkDao.getByBook(bookId).size)
    }

    @Test
    fun 删除单章既有路径仍会清理该章向量() = runBlocking {
        val db = newDb()
        val repo = BookRepository(db)
        val chunkDao = db.memoryChunkDao()

        val bookId = repo.createBook("测试书", "", null, 0, "第%n卷", "第%n章")
        val volumeA = repo.getVolumes(bookId).single().id
        val chapterA = db.chapterDao().getByBook(bookId).single().id
        val chapterB = repo.createChapter(bookId, volumeA)
        db.chapterDao().saveContent(chapterB, "第二章", "第二章梗概", "第二章正文", 0, 2L)
        chunkDao.insertAll(
            listOf(
                chunk(bookId, chapterA, volumeA, "第一章向量"),
                chunk(bookId, chapterB, volumeA, "第二章向量")
            )
        )

        repo.deleteChapter(bookId, chapterB)

        assertEquals(listOf(chapterA), db.chapterDao().getByBook(bookId).map { it.id })
        assertTrue(chunkDao.getByBook(bookId).none { it.chapterId == chapterB })
        assertEquals(1, chunkDao.getByBook(bookId).size)
        assertEquals(listOf(0), db.chapterDao().getByBook(bookId).map { it.sortOrder })
    }

    @Test
    fun 批量删除与合并章节只影响所选章节并清理向量() = runBlocking {
        val db = newDb()
        val repo = BookRepository(db)
        val chunkDao = db.memoryChunkDao()

        val bookId = repo.createBook("测试书", "", null, 0, "第%n卷", "第%n章")
        val volumeA = repo.getVolumes(bookId).single().id
        val chapterA = db.chapterDao().getByBook(bookId).single().id
        db.chapterDao().saveContent(chapterA, "卷A章", "卷A梗概", "卷A正文", 0, 1L)
        val volumeB = repo.createVolume(bookId)
        val chapterB1 = repo.createChapter(bookId, volumeB)
        db.chapterDao().saveContent(chapterB1, "B章1", "B梗概1", "B正文1", 0, 2L)
        val chapterB2 = repo.createChapter(bookId, volumeB)
        db.chapterDao().saveContent(chapterB2, "B章2", "B梗概2", "B正文2", 0, 2L)
        chunkDao.insertAll(
            listOf(
                chunk(bookId, chapterA, volumeA, "卷A向量"),
                chunk(bookId, chapterB1, volumeB, "B章1向量"),
                chunk(bookId, chapterB2, volumeB, "B章2向量")
            )
        )

        // 批量删除只清理被选中的章节
        repo.deleteChapters(bookId, listOf(chapterB1))
        assertEquals(setOf(chapterA, chapterB2), db.chapterDao().getByBook(bookId).map { it.id }.toSet())
        assertEquals(setOf(chapterA, chapterB2), chunkDao.getByBook(bookId).map { it.chapterId }.toSet())
        assertEquals(listOf(volumeA, volumeB), db.volumeDao().getByBook(bookId).map { it.id })

        // 合并即删除原章节，其向量块一并清理
        val mergedId = repo.mergeChapters(bookId, listOf(chapterB2, chapterA))!!
        val merged = db.chapterDao().getById(mergedId)!!
        assertTrue(merged.content.contains("B正文2") && merged.content.contains("卷A正文"))
        assertEquals(listOf(mergedId), db.chapterDao().getByBook(bookId).map { it.id })
        assertTrue(chunkDao.getByBook(bookId).isEmpty())
        assertEquals(listOf(volumeA, volumeB), db.volumeDao().getByBook(bookId).map { it.id })
    }

    @Test
    fun 历史孤儿章节对目录搜索检索与备份导出均不可见() = runBlocking {
        val db = newDb()
        val repo = BookRepository(db)
        val chunkDao = db.memoryChunkDao()

        val bookId = repo.createBook("测试书", "", null, 0, "第%n卷", "第%n章")
        val volumeA = repo.getVolumes(bookId).single().id
        val chapterA = db.chapterDao().getByBook(bookId).single().id
        db.chapterDao().saveContent(chapterA, "第一章", "卷A梗概", "卷A正文", 0, 1L)
        chunkDao.insertAll(listOf(chunk(bookId, chapterA, volumeA, "卷A向量片段")))

        // 模拟旧版本删分卷留下的孤儿章节：volumeId 指向不存在的分卷
        val orphanId = db.chapterDao().insert(
            Chapter(
                bookId = bookId,
                volumeId = 999_999L,
                title = "孤儿章节",
                summary = "孤儿梗概",
                content = "孤儿关键字正文",
                sortOrder = 1,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        chunkDao.insertAll(listOf(chunk(bookId, orphanId, 999_999L, "孤儿向量片段")))

        // 目录 / 搜索 / 有效章节口径都排除孤儿
        assertEquals(listOf(chapterA), repo.getChapters(bookId).map { it.id })
        assertTrue(repo.searchInBook(bookId, "孤儿关键字").isEmpty())
        assertEquals(listOf(chapterA), db.chapterDao().validIdsByBook(bookId))

        // 记忆检索不召回孤儿向量
        val recalled = MemoryRepository(db, gateway).retrieve(bookId, "孤儿关键字", topK = 5)
        assertTrue(recalled.none { it == "孤儿向量片段" })
        assertTrue(recalled.any { it == "卷A向量片段" })

        // 备份导出只包含有效章节：孤儿不会进入备份，也不会在恢复后复活
        val backup = BackupRepository(db, context, prefs)
        val file = backup.createBackupZip()
        val payload = backup.readBackup(Uri.fromFile(file))
        val exportedTitles = payload.books.single().chapters.map { it.title }
        assertTrue(exportedTitles.none { it == "孤儿章节" })
        assertEquals(listOf("第一章"), exportedTitles)

        val db2 = newDb()
        BackupRepository(db2, context, prefs).restore(payload, overwrite = false)
        val restoredBook = db2.bookDao().getAll().single()
        assertEquals(listOf("第一章"), db2.chapterDao().getByBook(restoredBook.id).map { it.title })
    }

    @Test
    fun 导入时越界分卷下标回落为未分卷而非上一卷() = runBlocking {
        val db = newDb()
        val payload = BackupPayload(
            books = listOf(
                BackupBook(
                    title = "恢复书",
                    volumes = listOf(BackupVolume(title = "第一卷")),
                    chapters = listOf(
                        BackupChapter(title = "正常章", content = "正文", volumeIndex = 0),
                        // 越界下标：模拟备份里的分卷引用已失效，必须落入未分卷而不是上一卷
                        BackupChapter(title = "游离章", content = "正文", volumeIndex = 5)
                    )
                )
            )
        )
        BackupRepository(db, context, prefs).restore(payload, overwrite = false)

        val bookId = db.bookDao().getAll().single().id
        val byTitle = db.chapterDao().getByBook(bookId).associateBy { it.title }
        val volumeId = db.volumeDao().getByBook(bookId).single().id
        assertEquals(volumeId, byTitle.getValue("正常章").volumeId)
        assertNull(byTitle.getValue("游离章").volumeId)
        // 游离章仍属有效章节，目录可见
        assertEquals(2, db.chapterDao().getValidByBook(bookId).size)
    }
}
