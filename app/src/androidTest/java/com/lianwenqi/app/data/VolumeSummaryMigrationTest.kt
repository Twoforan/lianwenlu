package com.lianwenqi.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.data.entity.VolumeCompressionBatchRecord
import com.lianwenqi.app.data.entity.VolumeCompressionSession
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.entity.VolumeSummaryStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库 v19 → v20 迁移验证：
 * 手工建出 v19 结构的真实数据库并写入数据，再用 Room + MIGRATION_19_20 打开，
 * 校验旧数据完整、ai_model 新增 contextWindow 默认 0、新表 volume_summary 可正常读写。
 */
@RunWith(AndroidJUnit4::class)
class VolumeSummaryMigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-v19-v20.db"

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
    fun v19升级到v20后数据保留且卷梗概表可读写() {
        createV19Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24
            )
            .allowMainThreadQueries()
            .build()

        try {
            runBlocking {
                // 旧数据完整保留
                val book = db.bookDao().getById(1)!!
                assertEquals("旧书", book.title)
                assertEquals(0L, book.styleId)

                val volume = db.volumeDao().getById(1)!!
                assertEquals("第一卷", volume.title)
                assertEquals(1L, volume.bookId)

                val chapter = db.chapterDao().getById(1)!!
                assertEquals("第一章", chapter.title)
                assertEquals("旧梗概", chapter.summary)
                assertEquals("旧正文", chapter.content)

                // 旧模型保留，新增的 Token 上下文窗口默认 0（未知）
                val model = db.aiModelDao().getById(1)!!
                assertEquals("旧模型", model.name)
                assertEquals(0, model.contextWindow)

                // 新表初始为空，可正常读写
                assertTrue(db.volumeSummaryDao().activeByVolume(1) == null)
                val id = db.volumeSummaryDao().insert(
                    VolumeSummary(
                        bookId = 1,
                        volumeId = 1,
                        status = VolumeSummaryStatus.ACTIVE,
                        modelContent = "迁移后卷梗概",
                        sourceChapterCount = 1,
                        processedChapterCount = 1,
                        sourceChapterIds = "1",
                        sourceChapterHashes = "hash",
                        isCurrent = true
                    )
                )
                val saved = db.volumeSummaryDao().getById(id)!!
                assertEquals("迁移后卷梗概", saved.effectiveContent)
                assertEquals(1L, saved.bookId)
                assertEquals(1L, saved.volumeId)
                assertEquals(VolumeSummaryStatus.ACTIVE, db.volumeSummaryDao().activeByVolume(1)!!.status)
            }
        } finally {
            db.close()
        }
    }

    /**
     * v19 → v20 → v21 → v22 → v23 → v24 逐级升级：
     * 证明旧库不是只在 v19→v20 可用，而是一路升到当前版本后旧数据完整、压缩相关新表可读写、
     * 卷梗概「每卷仅一条当前生效版」唯一约束在真实升级后的库上生效。
     * v23 新增的大纲「可更新」列、v24 新增的「常驻化」列默认关闭，旧条目行为不变。
     */
    @Test
    fun 旧库逐级升级到v24后旧数据保留且压缩相关表可用() {
        createV19Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24
            )
            .allowMainThreadQueries()
            .build()

        try {
            assertEquals(24, db.openHelper.writableDatabase.version)
            runBlocking {
                // 旧数据保留
                assertEquals("旧书", db.bookDao().getById(1)!!.title)
                val chapter = db.chapterDao().getById(1)!!
                assertEquals("旧梗概", chapter.summary)
                assertEquals("旧正文", chapter.content)
                assertEquals(1L, chapter.volumeId)
                val model = db.aiModelDao().getById(1)!!
                assertEquals("旧模型", model.name)
                assertEquals(0, model.contextWindow)

                // v20：卷梗概可读写；每卷至多一条当前生效版
                db.volumeSummaryDao().insert(
                    VolumeSummary(
                        bookId = 1,
                        volumeId = 1,
                        status = VolumeSummaryStatus.ACTIVE,
                        modelContent = "升级后卷梗概",
                        sourceChapterCount = 1,
                        processedChapterCount = 1,
                        sourceChapterIds = "1",
                        sourceChapterHashes = "hash",
                        isCurrent = true
                    )
                )
                assertNotNull(db.volumeSummaryDao().activeByVolume(1))
                val duplicateActive = runCatching {
                    db.volumeSummaryDao().insert(
                        VolumeSummary(
                            bookId = 1,
                            volumeId = 1,
                            status = VolumeSummaryStatus.ACTIVE,
                            modelContent = "第二条生效版",
                            isCurrent = true
                        )
                    )
                }
                assertTrue("同一分卷不能出现两条当前生效版", duplicateActive.isFailure)

                // v21：压缩任务与批次可读写，同一批次下标覆盖而不重复累计
                val sessionId = db.volumeCompressionDao().insertSession(
                    VolumeCompressionSession(
                        bookId = 1,
                        volumeId = 1,
                        sourceType = "SYNOPSIS",
                        status = "RUNNING",
                        phase = "EXTRACTING",
                        plannedBatchCount = 1
                    )
                )
                db.volumeCompressionDao().upsertBatch(
                    VolumeCompressionBatchRecord(
                        sessionId = sessionId,
                        batchIndex = 0,
                        status = "COMPLETED",
                        requestCount = 1,
                        requestsWithUsage = 1,
                        promptTokens = 10
                    )
                )
                db.volumeCompressionDao().upsertBatch(
                    VolumeCompressionBatchRecord(
                        sessionId = sessionId,
                        batchIndex = 0,
                        status = "COMPLETED",
                        requestCount = 2,
                        requestsWithUsage = 2,
                        promptTokens = 12
                    )
                )
                val batches = db.volumeCompressionDao().batches(sessionId)
                assertEquals(1, batches.size)
                assertEquals(12, batches.single().promptTokens)
                assertEquals(1, db.volumeCompressionDao().inFlightByBook(1).size)
            }
        } finally {
            db.close()
        }
    }

    /** 按 v19 的真实表结构建库并写入各表数据（不含 volume_summary 与 ai_model.contextWindow） */
    private fun createV19Database() {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            v19Schema.forEach { db.execSQL(it) }

            db.execSQL("INSERT INTO book (id, title, intro, coverPath, coverColor, volumePrefix, chapterPrefix, pinned, groupId, createdAt, updatedAt, lastChapterId, lastChapterTitle, styleId) VALUES (1, '旧书', '简介', NULL, 0, '第%n卷', '第%n章', 0, NULL, 1, 2, 1, '第一章', 0)")
            db.execSQL("INSERT INTO volume (id, bookId, title, sortOrder, createdAt) VALUES (1, 1, '第一卷', 0, 1)")
            db.execSQL("INSERT INTO chapter (id, bookId, volumeId, title, summary, content, lastCursor, sortOrder, createdAt, updatedAt) VALUES (1, 1, 1, '第一章', '旧梗概', '旧正文', 0, 0, 1, 2)")
            db.execSQL("INSERT INTO provider (id, name, baseUrl, apiType, apiKey, createdAt) VALUES (1, '旧供应商', 'https://api.example.com', 'CHAT_COMPLETIONS', 'sk-test', 1)")
            db.execSQL(
                "INSERT INTO ai_model (id, providerId, name, alias, temperature, maxTokens, maxContext, " +
                    "isDefault, systemPrompt, reasoningEnabled, reasoningLevel, custom) VALUES " +
                    "(1, 1, '旧模型', '', 0.8, 8192, 20, 1, '', 1, 'max', 1)"
            )

            db.version = 19
        } finally {
            db.close()
        }
    }

    private val v19Schema = listOf(
        "CREATE TABLE IF NOT EXISTS book_group (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
            "sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS book (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, intro TEXT NOT NULL, " +
            "coverPath TEXT, coverColor INTEGER NOT NULL, volumePrefix TEXT NOT NULL, chapterPrefix TEXT NOT NULL, " +
            "pinned INTEGER NOT NULL, groupId INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, " +
            "lastChapterId INTEGER, lastChapterTitle TEXT, styleId INTEGER NOT NULL DEFAULT 0)",
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
        "CREATE UNIQUE INDEX IF NOT EXISTS index_memory_chunk_chapterId_chunkIndex ON memory_chunk (chapterId, chunkIndex)",
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
        "CREATE INDEX IF NOT EXISTS index_chat_message_sessionId ON chat_message (sessionId)",
        "CREATE TABLE IF NOT EXISTS style_profile (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, sourceType TEXT NOT NULL, " +
            "sourceMeta TEXT NOT NULL, analysisJson TEXT NOT NULL, analysisVersion INTEGER NOT NULL, " +
            "modelName TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS style_tag_category (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, styleProfileId INTEGER NOT NULL DEFAULT 0, " +
            "name TEXT NOT NULL, source TEXT NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_style_tag_category_styleProfileId ON style_tag_category (styleProfileId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_style_tag_category_styleProfileId_name ON style_tag_category (styleProfileId, name)",
        "CREATE TABLE IF NOT EXISTS style_tag (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, styleProfileId INTEGER NOT NULL DEFAULT 0, " +
            "category TEXT NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL, description TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_style_tag_styleProfileId ON style_tag (styleProfileId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_style_tag_styleProfileId_category_name ON style_tag (styleProfileId, category, name)",
        "CREATE TABLE IF NOT EXISTS style_sample (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, styleProfileId INTEGER, fileName TEXT NOT NULL, " +
            "format TEXT NOT NULL, charCount INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL, " +
            "createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_style_sample_styleProfileId ON style_sample (styleProfileId)",
        "CREATE TABLE IF NOT EXISTS style_sample_chunk (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sampleId INTEGER NOT NULL, " +
            "styleProfileId INTEGER NOT NULL DEFAULT 0, chunkIndex INTEGER NOT NULL, text TEXT NOT NULL, " +
            "hash TEXT NOT NULL, dim INTEGER NOT NULL DEFAULT 0, vector BLOB, createdAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_style_sample_chunk_sampleId ON style_sample_chunk (sampleId)",
        "CREATE INDEX IF NOT EXISTS index_style_sample_chunk_styleProfileId ON style_sample_chunk (styleProfileId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_style_sample_chunk_sampleId_chunkIndex ON style_sample_chunk (sampleId, chunkIndex)",
        "CREATE TABLE IF NOT EXISTS style_sample_tag (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, chunkId INTEGER NOT NULL, tagId INTEGER NOT NULL, " +
            "source TEXT NOT NULL, createdAt INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS index_style_sample_tag_chunkId ON style_sample_tag (chunkId)",
        "CREATE INDEX IF NOT EXISTS index_style_sample_tag_tagId ON style_sample_tag (tagId)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_style_sample_tag_chunkId_tagId ON style_sample_tag (chunkId, tagId)",
        "CREATE TABLE IF NOT EXISTS chapter_summary_tag (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, chapterId INTEGER NOT NULL, category TEXT NOT NULL, " +
            "name TEXT NOT NULL, tagId INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL, " +
            "summaryHash TEXT NOT NULL, manual INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, " +
            "sentenceIndex INTEGER NOT NULL DEFAULT -1, sentenceStart INTEGER NOT NULL DEFAULT -1, " +
            "sentenceEnd INTEGER NOT NULL DEFAULT -1, sentenceText TEXT NOT NULL DEFAULT '')",
        "CREATE INDEX IF NOT EXISTS index_chapter_summary_tag_chapterId ON chapter_summary_tag (chapterId)"
    )
}
