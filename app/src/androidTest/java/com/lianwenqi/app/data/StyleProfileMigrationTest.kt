package com.lianwenqi.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库 v15 → v17 迁移验证：
 * 手工建出 v15 结构的真实数据库并写入各表数据，再用 Room + MIGRATION_15_16 / MIGRATION_16_17 打开，
 * 校验数据保留、旧书 styleId = 0、孤儿章节清理不误伤有效章节，且迁移后可正常读写文风档案与绑定。
 */
@RunWith(AndroidJUnit4::class)
class StyleProfileMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-v15-v16.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun v15升级到v17后数据保留且旧书未绑定文风() {
        createV15Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_15_16, AppDatabase.MIGRATION_16_17)
            .allowMainThreadQueries()
            .build()

        try {
            runBlocking {
                // 书籍保留，且统一未绑定
                val books = db.bookDao().getAll().sortedBy { it.id }
                assertEquals(2, books.size)
                assertEquals("旧书甲", books[0].title)
                assertEquals(0L, books[0].styleId)
                assertEquals(0L, books[1].styleId)

                // 分卷 / 章节 / 正文 / 大纲 / 模型配置 / 会话 / 记忆均保留
                assertEquals("第一卷", db.volumeDao().getByBook(1).single().title)
                val chapter = db.chapterDao().getById(1)!!
                assertEquals("第一章", chapter.title)
                assertEquals("旧梗概", chapter.summary)
                assertEquals("旧正文内容", chapter.content)
                // v17 迁移清理孤儿章节及其向量块，同时不误伤有效章节
                assertNull(db.chapterDao().getById(2))
                assertEquals(listOf(1L), db.chapterDao().getValidByBook(1).map { it.id })
                assertEquals(1, db.memoryChunkDao().getByBook(1).size)
                assertEquals(1, db.outlineDao().getByBook(1).size)
                assertEquals("旧模型", db.aiModelDao().getAll().single().name)
                assertEquals(1, db.providerDao().getAll().size)
                assertEquals("旧提示词", db.promptDao().getAll().single().title)
                assertEquals("旧会话", db.chatDao().getSession(1)!!.title)
                assertEquals(1, db.chatDao().getMessages(1).size)

                // 新表可正常读写
                assertTrue(db.styleProfileDao().getAll().isEmpty())
                val styleId = db.styleProfileDao().insert(
                    StyleProfile(
                        name = "迁移后档案",
                        sourceType = "WHOLE_BOOK",
                        sourceMeta = """{"fileName":"样本.txt"}""",
                        analysisJson = """{"diction":["克制"]}""",
                        modelName = "deepseek-chat"
                    )
                )
                assertEquals("迁移后档案", db.styleProfileDao().getById(styleId)!!.name)

                // 新字段可正常写入 / 清除
                db.bookDao().setStyle(1, styleId)
                assertEquals(styleId, db.bookDao().getById(1)!!.styleId)
                assertEquals(1, db.bookDao().countByStyle(styleId))
                db.bookDao().clearStyle(styleId)
                assertEquals(0L, db.bookDao().getById(1)!!.styleId)
            }
        } finally {
            db.close()
        }
    }

    /** 按 v15 的真实表结构建库并写入各表数据（不包含 style_profile 与 book.styleId） */
    private fun createV15Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            v15Schema.forEach { db.execSQL(it) }

            db.execSQL(
                "INSERT INTO book_group (id, name, sortOrder, createdAt) VALUES (1, '默认分组', 0, 1)"
            )
            db.execSQL(
                "INSERT INTO book (id, title, intro, coverPath, coverColor, volumePrefix, chapterPrefix, " +
                    "pinned, groupId, createdAt, updatedAt, lastChapterId, lastChapterTitle) VALUES " +
                    "(1, '旧书甲', '简介甲', NULL, 0, '第%n卷', '第%n章', 1, 1, 1, 2, 1, '第一章')"
            )
            db.execSQL(
                "INSERT INTO book (id, title, intro, coverPath, coverColor, volumePrefix, chapterPrefix, " +
                    "pinned, groupId, createdAt, updatedAt, lastChapterId, lastChapterTitle) VALUES " +
                    "(2, '旧书乙', '', NULL, 0, '第%n卷', '第%n章', 0, NULL, 3, 4, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO volume (id, bookId, title, sortOrder, createdAt) VALUES (1, 1, '第一卷', 0, 1)"
            )
            db.execSQL(
                "INSERT INTO chapter (id, bookId, volumeId, title, summary, content, lastCursor, sortOrder, " +
                    "createdAt, updatedAt) VALUES (1, 1, 1, '第一章', '旧梗概', '旧正文内容', 0, 0, 1, 2)"
            )
            // 模拟旧版本删分卷留下的孤儿章节：volumeId 指向不存在的分卷
            db.execSQL(
                "INSERT INTO chapter (id, bookId, volumeId, title, summary, content, lastCursor, sortOrder, " +
                    "createdAt, updatedAt) VALUES (2, 1, 99, '孤儿章节', '孤儿梗概', '孤儿正文', 0, 1, 1, 2)"
            )
            db.execSQL(
                "INSERT INTO outline_item (id, bookId, category, tag, title, content, sortOrder, updatedAt) " +
                    "VALUES (1, 1, 'STORY', '明线', '主线', '大纲内容', 0, 1)"
            )
            db.execSQL(
                "INSERT INTO prompt (id, title, content, createdAt) VALUES (1, '旧提示词', '提示词内容', 1)"
            )
            db.execSQL(
                "INSERT INTO provider (id, name, baseUrl, apiType, apiKey, createdAt) VALUES " +
                    "(1, '旧供应商', 'https://api.example.com', 'CHAT_COMPLETIONS', 'sk-test', 1)"
            )
            db.execSQL(
                "INSERT INTO ai_model (id, providerId, name, alias, temperature, maxTokens, maxContext, " +
                    "isDefault, systemPrompt, reasoningEnabled, reasoningLevel, custom) VALUES " +
                    "(1, 1, '旧模型', '', 0.8, 8192, 20, 1, '', 1, 'max', 1)"
            )
            db.execSQL(
                "INSERT INTO chat_session (id, title, bookId, chapterId, modelId, prefix, createdAt, updatedAt) " +
                    "VALUES (1, '旧会话', 1, 1, 1, '', 1, 2)"
            )
            db.execSQL(
                "INSERT INTO chat_message (id, sessionId, role, content, replyToId, variantIndex, active, " +
                    "reasoning, contextSnapshot, promptExtra, promptTokens, completionTokens, cachedTokens, " +
                    "contentCollapsed, chapterId, promptLog, roundName, createdAt) VALUES " +
                    "(1, 1, 'USER', '旧消息', 0, 0, 1, '', '', '', 0, 0, 0, 0, 1, '', '', 2)"
            )
            db.execSQL(
                "INSERT INTO memory_chunk (id, bookId, volumeId, chapterId, chunkIndex, text, hash, " +
                    "chapterUpdatedAt, dim, vector, createdAt) VALUES " +
                    "(1, 1, 1, 1, 0, '记忆片段', 'hash-1', 2, 4, x'00000000', 2)"
            )
            // 孤儿章节的向量块：迁移时必须与孤儿章节一起删除
            db.execSQL(
                "INSERT INTO memory_chunk (id, bookId, volumeId, chapterId, chunkIndex, text, hash, " +
                    "chapterUpdatedAt, dim, vector, createdAt) VALUES " +
                    "(2, 1, 99, 2, 0, '孤儿记忆片段', 'hash-2', 2, 4, x'00000000', 2)"
            )

            db.version = 15
        } finally {
            db.close()
        }
    }

    private val v15Schema = listOf(
        "CREATE TABLE IF NOT EXISTS book_group (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
            "sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS book (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, intro TEXT NOT NULL, " +
            "coverPath TEXT, coverColor INTEGER NOT NULL, volumePrefix TEXT NOT NULL, chapterPrefix TEXT NOT NULL, " +
            "pinned INTEGER NOT NULL, groupId INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
            "lastChapterId INTEGER, lastChapterTitle TEXT)",
        "CREATE TABLE IF NOT EXISTS volume (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, title TEXT NOT NULL, " +
            "sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_volume_bookId ON volume (bookId)",
        "CREATE TABLE IF NOT EXISTS chapter (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, volumeId INTEGER, " +
            "title TEXT NOT NULL, summary TEXT NOT NULL, content TEXT NOT NULL, lastCursor INTEGER NOT NULL, " +
            "sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_chapter_bookId ON chapter (bookId)",
        "CREATE INDEX IF NOT EXISTS index_chapter_volumeId ON chapter (volumeId)",
        "CREATE TABLE IF NOT EXISTS outline_item (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, category TEXT NOT NULL, " +
            "tag TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, sortOrder INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_outline_item_bookId ON outline_item (bookId)",
        "CREATE TABLE IF NOT EXISTS memory_chunk (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId INTEGER NOT NULL, volumeId INTEGER, " +
            "chapterId INTEGER NOT NULL, chunkIndex INTEGER NOT NULL, text TEXT NOT NULL, hash TEXT NOT NULL, " +
            "chapterUpdatedAt INTEGER NOT NULL, dim INTEGER NOT NULL, vector BLOB NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_memory_chunk_bookId ON memory_chunk (bookId)",
        "CREATE INDEX IF NOT EXISTS index_memory_chunk_chapterId ON memory_chunk (chapterId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_memory_chunk_chapterId_chunkIndex " +
            "ON memory_chunk (chapterId, chunkIndex)",
        "CREATE TABLE IF NOT EXISTS prompt (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS provider (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, baseUrl TEXT NOT NULL, " +
            "apiType TEXT NOT NULL, apiKey TEXT NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS ai_model (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, providerId INTEGER NOT NULL, name TEXT NOT NULL, " +
            "alias TEXT NOT NULL, temperature REAL NOT NULL, maxTokens INTEGER NOT NULL, " +
            "maxContext INTEGER NOT NULL, isDefault INTEGER NOT NULL, systemPrompt TEXT NOT NULL, " +
            "reasoningEnabled INTEGER NOT NULL, reasoningLevel TEXT NOT NULL, custom INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_ai_model_providerId ON ai_model (providerId)",
        "CREATE TABLE IF NOT EXISTS chat_session (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, bookId INTEGER, chapterId INTEGER, " +
            "modelId INTEGER, prefix TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS chat_message (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER NOT NULL, role TEXT NOT NULL, " +
            "content TEXT NOT NULL, replyToId INTEGER NOT NULL, variantIndex INTEGER NOT NULL, " +
            "active INTEGER NOT NULL, reasoning TEXT NOT NULL, contextSnapshot TEXT NOT NULL, " +
            "promptExtra TEXT NOT NULL, promptTokens INTEGER NOT NULL, completionTokens INTEGER NOT NULL, " +
            "cachedTokens INTEGER NOT NULL, contentCollapsed INTEGER NOT NULL, chapterId INTEGER NOT NULL, " +
            "promptLog TEXT NOT NULL, roundName TEXT NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_chat_message_sessionId ON chat_message (sessionId)"
    )
}
