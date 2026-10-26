package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 文风档案 DAO / Repository 与书籍绑定的真实 Room 行为（内存库） */
@RunWith(AndroidJUnit4::class)
class StyleRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var styleRepo: StyleRepository
    private lateinit var bookRepo: BookRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        styleRepo = StyleRepository(db)
        bookRepo = BookRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun profile(name: String = "冷峻叙事") = StyleProfile(
        name = name,
        sourceType = "CHAPTERS",
        sourceMeta = """{"fileName":"样本.txt","chapterCount":12,"sampleWords":30000}""",
        analysisJson = """{"narrative":{"pov":"第三人称"},"diction":["克制"]}""",
        analysisVersion = 1,
        modelName = "deepseek-chat"
    )

    @Test
    fun 新建查询修改删除档案() = runBlocking {
        val id = styleRepo.createProfile(profile())
        assertTrue(id > 0)

        val all = styleRepo.allProfiles()
        assertEquals(1, all.size)
        assertEquals("冷峻叙事", all.single().name)
        assertEquals("CHAPTERS", all.single().sourceType)

        val loaded = styleRepo.getProfile(id)!!
        assertEquals("""{"narrative":{"pov":"第三人称"},"diction":["克制"]}""", loaded.analysisJson)

        styleRepo.updateProfile(
            loaded.copy(name = "冷峻叙事（改）", analysisJson = """{"diction":["更冷峻"]}""")
        )
        val updated = styleRepo.getProfile(id)!!
        assertEquals("冷峻叙事（改）", updated.name)
        assertEquals("""{"diction":["更冷峻"]}""", updated.analysisJson)
        assertTrue(updated.updatedAt >= loaded.updatedAt)

        styleRepo.deleteProfile(id)
        assertNull(styleRepo.getProfile(id))
        assertTrue(styleRepo.allProfiles().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun 超限分析结果不会写入数据库() {
        runBlocking {
            styleRepo.createProfile(
                StyleProfile(
                    name = "超长",
                    analysisJson = """{"pad":"""" + "字".repeat(StyleProfile.MAX_ANALYSIS_JSON_BYTES) + """"}"""
                )
            )
        }
    }

    @Test
    fun 书籍绑定与解绑() = runBlocking {
        val styleId = styleRepo.createProfile(profile())
        val bookId = bookRepo.createBook("书A", "", null, 0, "第%n卷", "第%n章")

        assertEquals(0L, db.bookDao().getById(bookId)!!.styleId)

        styleRepo.bindBook(bookId, styleId)
        assertEquals(styleId, db.bookDao().getById(bookId)!!.styleId)
        assertEquals(1, styleRepo.boundBookCount(styleId))

        styleRepo.unbindBook(bookId)
        assertEquals(0L, db.bookDao().getById(bookId)!!.styleId)
        assertEquals(0, styleRepo.boundBookCount(styleId))
    }

    @Test
    fun 多本书可引用同一档案() = runBlocking {
        val styleId = styleRepo.createProfile(profile())
        val a = bookRepo.createBook("书A", "", null, 0, "第%n卷", "第%n章")
        val b = bookRepo.createBook("书B", "", null, 0, "第%n卷", "第%n章")
        val c = bookRepo.createBook("书C", "", null, 0, "第%n卷", "第%n章")
        styleRepo.bindBook(a, styleId)
        styleRepo.bindBook(b, styleId)
        assertEquals(2, styleRepo.boundBookCount(styleId))
        assertEquals(1, styleRepo.allProfiles().size)

        // 删除已绑定档案：所有引用书籍统一解绑，档案本身删除，书籍保留
        styleRepo.deleteProfile(styleId)
        assertNull(styleRepo.getProfile(styleId))
        assertEquals(0L, db.bookDao().getById(a)!!.styleId)
        assertEquals(0L, db.bookDao().getById(b)!!.styleId)
        assertEquals(0L, db.bookDao().getById(c)!!.styleId)
        assertEquals(3, db.bookDao().getAll().size)
    }

    @Test
    fun 绑定不存在的档案被拒绝且不写入无效引用() = runBlocking {
        val bookId = bookRepo.createBook("书A", "", null, 0, "第%n卷", "第%n章")
        val failed = runCatching { styleRepo.bindBook(bookId, 9999L) }.isFailure
        assertTrue(failed)
        assertEquals(0L, db.bookDao().getById(bookId)!!.styleId)
    }

    @Test
    fun 复制书籍保留文风绑定且不复制档案() = runBlocking {
        val styleId = styleRepo.createProfile(profile())
        val src = bookRepo.createBook("原书", "简介", null, 0, "第%n卷", "第%n章")
        styleRepo.bindBook(src, styleId)

        val newId = bookRepo.copyBookOutline(src)
        val copied = db.bookDao().getById(newId)!!
        assertEquals(styleId, copied.styleId)
        assertEquals(1, styleRepo.allProfiles().size)
        assertEquals(2, styleRepo.boundBookCount(styleId))
    }

    @Test
    fun 新建书籍默认不绑定文风() = runBlocking {
        val bookId = bookRepo.createBook("新书", "", null, 0, "第%n卷", "第%n章")
        val book: Book = db.bookDao().getById(bookId)!!
        assertEquals(0L, book.styleId)
    }

    @Test
    fun 档案流读取真实数据并随增删改刷新() = runBlocking {
        assertEquals(0, styleRepo.observeProfiles().first().size)

        val id = styleRepo.createProfile(profile())
        assertEquals(listOf("冷峻叙事"), styleRepo.observeProfiles().first().map { it.name })

        styleRepo.updateProfile(styleRepo.getProfile(id)!!.copy(name = "冷峻叙事（改）"))
        assertEquals(
            listOf("冷峻叙事（改）"),
            styleRepo.observeProfiles().first().map { it.name }
        )

        styleRepo.deleteProfile(id)
        assertTrue(styleRepo.observeProfiles().first().isEmpty())
    }

    @Test
    fun 关闭并重开数据库后档案与书籍绑定仍然存在() = runBlocking {
        val name = "f7-persistence-test.db"
        context.deleteDatabase(name)
        val db1 = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        val styleId: Long
        val bookId: Long
        try {
            val styleRepo1 = StyleRepository(db1)
            styleId = styleRepo1.createProfile(profile(name = "跨重启档案"))
            bookId = BookRepository(db1).createBook(
                "跨重启书籍", "简介", null, 0, "第%n卷", "第%n章", styleId = styleId
            )
            assertEquals(styleId, db1.bookDao().getById(bookId)!!.styleId)
        } finally {
            db1.close()
        }

        val db2 = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        try {
            // 关闭并重新建立数据访问实例后，档案与绑定关系仍可正确读取
            assertEquals("跨重启档案", db2.styleProfileDao().getById(styleId)!!.name)
            assertEquals(styleId, db2.bookDao().getById(bookId)!!.styleId)
        } finally {
            db2.close()
            context.deleteDatabase(name)
        }
    }
}
