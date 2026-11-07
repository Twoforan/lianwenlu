package com.lianwenqi.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.entity.VolumeSummaryStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 整卷梗概版本状态机验收：
 * 唯一生效版、候选确认 / 放弃、内容保护、用户编辑不被覆盖、恢复旧版、过期检测、
 * 删除级联与备份恢复。
 */
@RunWith(AndroidJUnit4::class)
class VolumeSummaryRepositoryTest {

    private lateinit var context: Context
    private lateinit var prefs: Prefs
    private val opened = mutableListOf<AppDatabase>()

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

    private data class Seed(val bookId: Long, val volumeId: Long, val chapterIds: List<Long>)

    private suspend fun seed(db: AppDatabase): Seed {
        val books = BookRepository(db)
        val bookId = books.createBook("测试书", "", null, 0, "第%n卷", "第%n章")
        val volumeId = db.volumeDao().getByBook(bookId).single().id
        val first = db.chapterDao().getByBook(bookId).single().id
        db.chapterDao().saveContent(first, "第一章", "梗概甲", "正文甲", 0, 1L)
        val second = books.createChapter(bookId, volumeId)
        db.chapterDao().saveContent(second, "第二章", "梗概乙", "正文乙", 0, 2L)
        return Seed(bookId, volumeId, listOf(first, second))
    }

    @Test
    fun 同一卷不能出现两个当前生效版本() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)
        val dao = db.volumeSummaryDao()

        val first = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "模型梗概一")!!
        assertTrue(repo.confirmCandidate(first))
        val second = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "模型梗概二")!!
        assertTrue(repo.confirmCandidate(second))

        val active = repo.activeOfVolume(seed.volumeId)!!
        assertEquals(second, active.id)
        assertEquals(1, dao.archivedByVolume(seed.volumeId).size)
        assertTrue(dao.archivedByVolume(seed.volumeId).none { it.isCurrent == true })

        // 数据库唯一索引兜底：直接写入第二条生效版必须失败
        val duplicated = runCatching {
            dao.insert(
                VolumeSummary(
                    bookId = seed.bookId,
                    volumeId = seed.volumeId,
                    status = VolumeSummaryStatus.ACTIVE,
                    modelContent = "重复生效",
                    isCurrent = true
                )
            )
        }
        assertTrue(duplicated.isFailure)
    }

    @Test
    fun 放弃候选不影响当前生效版本() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)

        val activeId = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "已生效版本")!!
        assertTrue(repo.confirmCandidate(activeId))
        val candidateId = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "待放弃候选")!!
        assertTrue(repo.discardCandidate(candidateId))

        assertEquals(activeId, repo.activeOfVolume(seed.volumeId)!!.id)
        assertEquals("已生效版本", repo.activeOfVolume(seed.volumeId)!!.effectiveContent)
        assertTrue(repo.candidates(seed.volumeId).isEmpty())
        assertEquals(0, db.volumeSummaryDao().archivedByVolume(seed.volumeId).size)
        // 归档版不能被当成候选删除
        assertFalse(repo.discardCandidate(activeId))
    }

    @Test
    fun 内容为空或归属不符的候选不能创建也不能生效() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)
        val otherBook = BookRepository(db).createBook("另一本", "", null, 0, "第%n卷", "第%n章")

        assertNull(repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "  "))
        assertNull(repo.createCandidate(otherBook, seed.volumeId, "SYNOPSIS", "跨书候选"))
        // 传入不属于本卷的章节（另一本书第一章）
        val foreignChapter = db.chapterDao().getByBook(otherBook).single().id
        assertNull(
            repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "越界候选", chapterIds = listOf(foreignChapter))
        )

        // 直接写入一条不完整的候选（模拟历史脏数据）：确认必须被拒绝且不产生生效版
        val brokenId = db.volumeSummaryDao().insert(
            VolumeSummary(
                bookId = seed.bookId,
                volumeId = seed.volumeId,
                status = VolumeSummaryStatus.CANDIDATE,
                modelContent = "",
                processedChapterCount = seed.chapterIds.size,
                sourceChapterCount = 1,
                sourceChapterIds = seed.chapterIds.joinToString(",")
            )
        )
        assertFalse(repo.confirmCandidate(brokenId))
        assertNull(repo.activeOfVolume(seed.volumeId))

        // 正常候选可生效
        val okId = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "完整梗概")!!
        assertTrue(repo.confirmCandidate(okId))
        assertEquals(okId, repo.activeOfVolume(seed.volumeId)!!.id)
    }

    @Test
    fun 用户编辑后重新压缩不覆盖编辑版且恢复旧版可找回编辑内容() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)

        val v1 = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "模型一")!!
        assertTrue(repo.confirmCandidate(v1))
        assertTrue(repo.saveUserContent(v1, "用户改过的梗概"))
        assertEquals("用户改过的梗概", repo.activeOfVolume(seed.volumeId)!!.effectiveContent)

        val v2 = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "模型二")!!
        assertTrue(repo.confirmCandidate(v2))
        val active = repo.activeOfVolume(seed.volumeId)!!
        assertEquals(v2, active.id)
        assertEquals("模型二", active.effectiveContent)

        // 旧版转为归档，但用户编辑内容随行保留
        val archived = db.volumeSummaryDao().archivedByVolume(seed.volumeId).single()
        assertEquals(v1, archived.id)
        assertEquals("用户改过的梗概", archived.userContent)
        assertEquals("用户改过的梗概", archived.effectiveContent)

        // 恢复旧版：当前版本转归档，恢复前的内容不会丢
        assertTrue(repo.restoreVersion(v1))
        assertEquals(v1, repo.activeOfVolume(seed.volumeId)!!.id)
        assertEquals("用户改过的梗概", repo.activeOfVolume(seed.volumeId)!!.effectiveContent)
        val archivedNow = db.volumeSummaryDao().archivedByVolume(seed.volumeId).single()
        assertEquals(v2, archivedNow.id)
        assertEquals("模型二", archivedNow.effectiveContent)

        // 恢复失效：当前生效版不是可恢复归档
        assertFalse(repo.restoreVersion(v1))
    }

    @Test
    fun 候选不可写入用户编辑() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)
        val candidate = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "候选内容")!!
        assertFalse(repo.saveUserContent(candidate, "候选改稿"))
        assertEquals("", repo.getDetail(candidate)!!.userContent)
    }

    @Test
    fun 源内容变化_调序_增删都会标记过期且计数正确() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)
        val books = BookRepository(db)

        val id = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "整卷梗概")!!
        assertTrue(repo.confirmCandidate(id))
        assertFalse(repo.isStale(id)!!)
        val fresh = repo.checkFreshness(id)!!
        assertEquals(0, fresh.missingChapterCount)
        assertEquals(2, fresh.currentChapterCount)

        // 梗概内容变化
        db.chapterDao().saveContent(seed.chapterIds[1], "第二章", "梗概乙改", "正文乙", 0, 3L)
        assertTrue(repo.isStale(id)!!)

        // 改回相同内容（时间戳变了但内容一致）：不应误判过期
        db.chapterDao().saveContent(seed.chapterIds[1], "第二章", "梗概乙", "正文乙", 0, 4L)
        assertFalse(repo.isStale(id)!!)

        // 新增章节 / 删除章节
        books.createChapter(seed.bookId, seed.volumeId)
        assertTrue(repo.isStale(id)!!)
        db.chapterDao().deleteById(seed.chapterIds[0])
        val afterDelete = repo.checkFreshness(id)!!
        assertTrue(afterDelete.stale)
        assertEquals(1, afterDelete.missingChapterCount)
        assertEquals(2, afterDelete.currentChapterCount)
    }

    @Test
    fun 正文来源对正文变化敏感而忽略梗概变化() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)

        val id = repo.createCandidate(seed.bookId, seed.volumeId, "CONTENT", "正文卷梗概")!!
        assertTrue(repo.confirmCandidate(id))
        assertFalse(repo.isStale(id)!!)

        db.chapterDao().saveContent(seed.chapterIds[0], "第一章", "梗概甲改", "正文甲", 0, 5L)
        assertFalse(repo.isStale(id)!!)

        db.chapterDao().saveContent(seed.chapterIds[0], "第一章", "梗概甲改", "正文甲改", 0, 6L)
        assertTrue(repo.isStale(id)!!)
    }

    @Test
    fun 只压缩部分章节时记录实际参与数与总章节数() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)

        val id = repo.createCandidate(
            seed.bookId,
            seed.volumeId,
            "SYNOPSIS",
            "部分梗概",
            chapterIds = listOf(seed.chapterIds[0])
        )!!
        val detail = repo.getDetail(id)!!
        assertEquals(1, detail.processedChapterCount)
        assertEquals(2, detail.sourceChapterCount)
        assertEquals(listOf(seed.chapterIds[0]), detail.sourceChapterIdList())

        assertTrue(repo.confirmCandidate(id))
        // 部分压缩时未参与章节的变化不算过期
        db.chapterDao().saveContent(seed.chapterIds[1], "第二章", "梗概乙改", "正文乙", 0, 7L)
        assertFalse(repo.isStale(id)!!)
    }

    @Test
    fun 删除分卷与删除书籍会级联清理卷梗概() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val books = BookRepository(db)
        val seed = seed(db)

        val activeId = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "卷梗概")!!
        assertTrue(repo.confirmCandidate(activeId))
        repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "候选")

        books.deleteVolume(seed.volumeId)
        assertNull(repo.activeOfVolume(seed.volumeId))
        assertTrue(repo.candidates(seed.volumeId).isEmpty())
        assertTrue(db.volumeSummaryDao().archivedByVolume(seed.volumeId).isEmpty())

        // 删除书籍清理剩余卷梗概
        val otherVolume = books.createVolume(seed.bookId)
        val otherChapter = books.createChapter(seed.bookId, otherVolume)
        db.chapterDao().saveContent(otherChapter, "另一章", "另一卷梗概来源", "另一卷正文", 0, 1L)
        val otherId = repo.createCandidate(seed.bookId, otherVolume, "SYNOPSIS", "另一卷梗概")!!
        assertTrue(repo.confirmCandidate(otherId))
        books.deleteBook(seed.bookId)
        assertNull(repo.activeOfVolume(otherVolume))
        assertTrue(db.volumeSummaryDao().archivedByVolume(otherVolume).isEmpty())
    }

    @Test
    fun 备份导出只含生效版与一条归档版且恢复后关联正确() = runBlocking {
        val db = newDb()
        val repo = VolumeSummaryRepository(db)
        val seed = seed(db)
        val v1 = repo.createCandidate(
            seed.bookId,
            seed.volumeId,
            "SYNOPSIS",
            "模型一",
            modelName = "deepseek-chat"
        )!!
        assertTrue(repo.confirmCandidate(v1))
        assertTrue(repo.saveUserContent(v1, "用户定稿"))
        val v2 = repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "模型二")!!
        assertTrue(repo.confirmCandidate(v2))
        // 未确认的候选不进入备份
        repo.createCandidate(seed.bookId, seed.volumeId, "SYNOPSIS", "候选不入备份")

        val backup = BackupRepository(db, context, prefs)
        val file = backup.createBackupZip()
        val payload = backup.readBackup(Uri.fromFile(file))
        val exported = payload.books.single().volumeSummaries
        assertEquals(2, exported.size)
        assertTrue(exported.none { it.status == VolumeSummaryStatus.CANDIDATE })
        assertEquals(1, exported.count { it.current })

        val db2 = newDb()
        BackupRepository(db2, context, prefs).restore(payload, overwrite = false)
        val restoredBook = db2.bookDao().getAll().single()
        val restoredVolume = db2.volumeDao().getByBook(restoredBook.id).single()
        val restoredRepo = VolumeSummaryRepository(db2)

        val active = restoredRepo.activeOfVolume(restoredVolume.id)
        assertNotNull(active)
        assertEquals("模型二", active!!.effectiveContent)
        val archived = db2.volumeSummaryDao().archivedByVolume(restoredVolume.id).single()
        assertEquals("用户定稿", archived.effectiveContent)
        // 归档版生成时的模型名称随备份保留（D6：id 与名称都存）
        assertEquals("deepseek-chat", archived.modelName)

        // 参与章节已按新 id 重映射，且恢复后仍能正确判断「未过期」
        val restoredChapters = db2.chapterDao().getValidByBook(restoredBook.id)
        assertEquals(restoredChapters.map { it.id }, active.sourceChapterIdList())
        assertFalse(restoredRepo.isStale(active.id)!!)
        assertEquals(
            restoredChapters.map { VolumeSourceRevision.contentHash(it.summary) },
            active.sourceChapterHashList()
        )
    }
}
