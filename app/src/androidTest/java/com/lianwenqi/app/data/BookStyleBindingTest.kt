package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 新建书籍的文风绑定：保存、校验、复制与旧数据兼容（内存库，真实 Room 行为） */
@RunWith(AndroidJUnit4::class)
class BookStyleBindingTest {

    private lateinit var db: AppDatabase
    private lateinit var bookRepo: BookRepository
    private lateinit var styleRepo: StyleRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        bookRepo = BookRepository(db)
        styleRepo = StyleRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun createProfile(name: String = "冷峻叙事"): StyleProfile {
        val id = styleRepo.createProfile(
            StyleProfile(
                name = name,
                sourceType = "CHAPTERS",
                sourceMeta = """{"samples":[{"name":"样本A"}]}""",
                analysisJson = """{"schemaVersion":1,"overview":"克制冷峻"}""",
                analysisVersion = 1,
                modelName = "测试模型"
            )
        )
        return styleRepo.getProfile(id)!!
    }

    private suspend fun createBook(
        title: String = "书A",
        styleId: Long = 0
    ): Long = bookRepo.createBook(
        title = title,
        intro = "简介",
        coverPath = null,
        coverColor = 0xFF3F51B5.toInt(),
        volumePrefix = "第%n卷",
        chapterPrefix = "第%n章",
        groupId = null,
        styleId = styleId
    )

    @Test
    fun 创建书籍保存指定的文风档案id() = runBlocking {
        val profile = createProfile()

        val bookId = createBook(styleId = profile.id)

        // 通过数据读取流程回读，而不是依赖调用方传入的值
        val saved = bookRepo.getBook(bookId)!!
        assertEquals(profile.id, saved.styleId)
        // 绑定不复制档案本身
        assertEquals(1, styleRepo.allProfiles().size)
        assertEquals(profile.name, styleRepo.getProfile(profile.id)!!.name)
    }

    @Test
    fun 创建书籍未选择文风时保存零() = runBlocking {
        val bookId = createBook()

        val saved = bookRepo.getBook(bookId)!!
        assertEquals(0L, saved.styleId)
        assertTrue(styleRepo.allProfiles().isEmpty())
    }

    @Test
    fun 选择已删除档案时创建失败且不新增书籍() = runBlocking {
        val profile = createProfile()
        val before = bookRepo.allBooks().size
        styleRepo.deleteProfile(profile.id)

        try {
            createBook(title = "不应创建", styleId = profile.id)
            fail("绑定不存在的档案应抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("文风档案不存在"))
        }

        // 事务回滚：书籍、分卷、章节都不应留下
        assertEquals(before, bookRepo.allBooks().size)
        assertTrue(bookRepo.allBooks().none { it.title == "不应创建" })
    }

    @Test
    fun 复制书籍沿用原文风绑定且不复制档案() = runBlocking {
        val profile = createProfile()
        val sourceId = createBook(title = "原书", styleId = profile.id)

        val copyId = bookRepo.copyBookOutline(sourceId)

        val copy = bookRepo.getBook(copyId)!!
        assertEquals(profile.id, copy.styleId)
        assertEquals(1, styleRepo.allProfiles().size)
        assertEquals(profile.analysisJson, styleRepo.getProfile(profile.id)!!.analysisJson)
    }

    @Test
    fun 复制未绑定文风的书籍仍为零() = runBlocking {
        val sourceId = createBook(title = "空白书")

        val copyId = bookRepo.copyBookOutline(sourceId)

        assertEquals(0L, bookRepo.getBook(copyId)!!.styleId)
    }

    @Test
    fun 旧书籍未绑定文风可读取且编辑后保持零() = runBlocking {
        // F1 约定：未绑定文风的旧书籍 styleId = 0
        val bookId = createBook(title = "旧书")
        val old = bookRepo.getBook(bookId)!!
        assertEquals(0L, old.styleId)

        bookRepo.updateBook(old.copy(title = "旧书（改名）", intro = "新简介"))

        val updated = bookRepo.getBook(bookId)!!
        assertEquals("旧书（改名）", updated.title)
        assertEquals("新简介", updated.intro)
        assertEquals(0L, updated.styleId)
    }

    @Test
    fun 已绑定书籍编辑其他字段后保留文风绑定() = runBlocking {
        val profile = createProfile()
        val bookId = createBook(styleId = profile.id)
        val old = bookRepo.getBook(bookId)!!

        bookRepo.updateBook(old.copy(title = "新标题", intro = "新简介"))

        assertEquals(profile.id, bookRepo.getBook(bookId)!!.styleId)
    }

    @Test
    fun 删除档案后书籍解绑且仍可读取() = runBlocking {
        val profile = createProfile()
        val bookId = createBook(styleId = profile.id)

        styleRepo.deleteProfile(profile.id)

        val after = bookRepo.getBook(bookId)
        assertTrue(after != null)
        assertEquals(0L, after!!.styleId)
        assertNull(styleRepo.getProfile(profile.id))
        // 解绑后书籍编辑不受影响
        bookRepo.updateBook(after.copy(intro = "解绑后的简介"))
        assertEquals("解绑后的简介", bookRepo.getBook(bookId)!!.intro)
        assertEquals(0L, bookRepo.getBook(bookId)!!.styleId)
    }

    // ---------------- F8：已有书籍的文风换绑 / 解除绑定 ----------------

    @Test
    fun 编辑书籍换绑到另一档案并回读一致() = runBlocking {
        val first = createProfile("冷峻叙事")
        val second = createProfile("细腻抒情")
        val bookId = createBook(title = "待换绑", styleId = first.id)

        val current = bookRepo.getBook(bookId)!!
        assertTrue(bookRepo.updateBook(current.copy(styleId = second.id)))

        // 以重新读取的数据为准，确认已落库
        assertEquals(second.id, bookRepo.getBook(bookId)!!.styleId)
        // 换绑只改关联：不复制、不修改任何档案
        assertEquals(2, styleRepo.allProfiles().size)
        assertEquals("冷峻叙事", styleRepo.getProfile(first.id)!!.name)
        assertEquals("细腻抒情", styleRepo.getProfile(second.id)!!.name)
    }

    @Test
    fun 编辑书籍解除绑定为零且重新读取仍为零() = runBlocking {
        val profile = createProfile()
        val bookId = createBook(title = "解绑书", styleId = profile.id)

        val current = bookRepo.getBook(bookId)!!
        assertTrue(bookRepo.updateBook(current.copy(styleId = 0)))

        assertEquals(0L, bookRepo.getBook(bookId)!!.styleId)
        assertEquals(0, styleRepo.boundBookCount(profile.id))
        // 解除绑定不删除档案本身
        assertEquals(profile.id, styleRepo.getProfile(profile.id)!!.id)
    }

    @Test
    fun 换绑只影响当前书籍() = runBlocking {
        val p1 = createProfile("档案一")
        val p2 = createProfile("档案二")
        val a = createBook(title = "书A", styleId = p1.id)
        val b = createBook(title = "书B", styleId = p1.id)

        assertTrue(bookRepo.updateBook(bookRepo.getBook(a)!!.copy(styleId = p2.id)))

        assertEquals(p2.id, bookRepo.getBook(a)!!.styleId)
        assertEquals(p1.id, bookRepo.getBook(b)!!.styleId)
        assertEquals(1, styleRepo.boundBookCount(p1.id))
        assertEquals(1, styleRepo.boundBookCount(p2.id))
    }

    @Test
    fun 换绑到已删除档案会被拒绝且保留原绑定() = runBlocking {
        val keep = createProfile("原档案")
        val gone = createProfile("将被删除")
        val bookId = createBook(title = "悬空测试", styleId = keep.id)
        val current = bookRepo.getBook(bookId)!!
        styleRepo.deleteProfile(gone.id)

        try {
            bookRepo.updateBook(current.copy(styleId = gone.id))
            fail("保存不存在的档案应抛出 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("文风档案不存在"))
        }

        // 事务回滚：旧绑定与其他字段都保持不变，不产生悬空关联
        val reloaded = bookRepo.getBook(bookId)!!
        assertEquals(keep.id, reloaded.styleId)
        assertEquals("悬空测试", reloaded.title)
    }

    @Test
    fun 书籍已删除时编辑不会写入() = runBlocking {
        val profile = createProfile()
        val ghost = createBook(title = "将被删除", styleId = profile.id)
        val snapshot = bookRepo.getBook(ghost)!!
        bookRepo.deleteBook(ghost)

        assertFalse(bookRepo.updateBook(snapshot.copy(title = "改名应失败", styleId = 0)))

        assertNull(bookRepo.getBook(ghost))
        assertEquals(1, styleRepo.allProfiles().size)
    }

    @Test
    fun 换绑时其他字段与章节结构一并保留() = runBlocking {
        val p1 = createProfile("原档案")
        val p2 = createProfile("新档案")
        val bookId = createBook(title = "原书名", styleId = p1.id)
        val current = bookRepo.getBook(bookId)!!
        val chaptersBefore = bookRepo.getChapters(bookId).map { it.id }

        assertTrue(
            bookRepo.updateBook(current.copy(title = "新书名", intro = "新简介", styleId = p2.id))
        )

        val reloaded = bookRepo.getBook(bookId)!!
        assertEquals("新书名", reloaded.title)
        assertEquals("新简介", reloaded.intro)
        assertEquals(p2.id, reloaded.styleId)
        // 换绑不改动章节等正文数据
        assertEquals(chaptersBefore, bookRepo.getChapters(bookId).map { it.id })
    }
}
