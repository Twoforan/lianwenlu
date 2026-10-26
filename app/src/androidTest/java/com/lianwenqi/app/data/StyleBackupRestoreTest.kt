package com.lianwenqi.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 备份 / 恢复的文风兼容：导出档案与绑定、恢复时 id 重映射、旧备份仍可恢复 */
@RunWith(AndroidJUnit4::class)
class StyleBackupRestoreTest {

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

    @Test
    fun 导出并恢复后档案与书籍绑定都完整() = runBlocking {
        val db1 = newDb()
        val styleRepo1 = StyleRepository(db1)
        val bookRepo1 = BookRepository(db1)
        val styleId = styleRepo1.createProfile(
            StyleProfile(
                name = "冷峻叙事",
                sourceType = "CHAPTERS",
                sourceMeta = """{"chapterCount":10}""",
                analysisJson = """{"diction":["克制"]}""",
                modelName = "deepseek-chat"
            )
        )
        val bookId = bookRepo1.createBook("样书", "简介", null, 0, "第%n卷", "第%n章")
        styleRepo1.bindBook(bookId, styleId)

        val backupRepo1 = BackupRepository(db1, context, prefs)
        val file = backupRepo1.createBackupZip()
        val payload = backupRepo1.readBackup(Uri.fromFile(file))
        assertEquals(1, payload.styleProfiles.size)
        assertEquals(styleId, payload.books.single().styleId)
        assertEquals("冷峻叙事", payload.styleProfiles.single().name)

        val db2 = newDb()
        val result = BackupRepository(db2, context, prefs).restore(payload, overwrite = false)
        assertEquals(1, result.created)

        val restoredProfiles = db2.styleProfileDao().getAll()
        assertEquals(1, restoredProfiles.size)
        assertEquals("冷峻叙事", restoredProfiles.single().name)
        assertEquals("""{"diction":["克制"]}""", restoredProfiles.single().analysisJson)

        val restoredBook = db2.bookDao().getAll().single()
        assertEquals(restoredProfiles.single().id, restoredBook.styleId)
        assertEquals("样书", restoredBook.title)
        assertEquals(1, db2.chapterDao().getByBook(restoredBook.id).size)
    }

    @Test
    fun 同名档案复用且不存在档案的绑定回落为未绑定() = runBlocking {
        val db = newDb()
        val existingId = db.styleProfileDao().insert(StyleProfile(name = "档案A"))

        val payload = BackupPayload(
            books = listOf(
                BackupBook(title = "书X", styleId = 77),
                BackupBook(title = "书Y", styleId = 999)
            ),
            styleProfiles = listOf(
                BackupStyleProfile(id = 77, name = "档案A", analysisJson = """{"old":true}"""),
                BackupStyleProfile(id = 77, name = "档案A", analysisJson = """{"old":true}""")
            )
        )
        BackupRepository(db, context, prefs).restore(payload, overwrite = false)

        // 同名档案复用本机已有的一份，不重复创建、不覆盖已有分析结果
        val profiles = db.styleProfileDao().getAll()
        assertEquals(1, profiles.size)
        assertEquals(existingId, profiles.single().id)
        assertEquals("{}", profiles.single().analysisJson)

        val books = db.bookDao().getAll().associateBy { it.title }
        assertEquals(existingId, books["书X"]!!.styleId)
        assertEquals(0L, books["书Y"]!!.styleId)
    }

    @Test
    fun 旧版备份没有文风字段时正常恢复为未绑定() = runBlocking {
        val db = newDb()
        val payload = BackupPayload(
            books = listOf(
                BackupBook(
                    title = "旧书",
                    chapters = listOf(BackupChapter(title = "第一章", content = "正文"))
                )
            )
        )
        val result = BackupRepository(db, context, prefs).restore(payload, overwrite = false)
        assertEquals(1, result.created)
        assertTrue(db.styleProfileDao().getAll().isEmpty())
        val book = db.bookDao().getAll().single()
        assertEquals(0L, book.styleId)
        assertEquals("正文", db.chapterDao().getByBook(book.id).single().content)
    }
}
