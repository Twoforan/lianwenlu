package com.lianwenqi.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory

/**
 * Agent 迁移测试共用夹具。
 *
 * 用 v19 真实表结构建库并写入旧数据，再通过既有 Migration 逐级升到 v26 / v27；
 * 供 F1、F2 的迁移测试复用，避免各自维护一份易漂移的 schema。
 */
internal object AgentMigrationFixture {

    /** 按 v19 表结构建库、写入旧数据，并逐级迁移到 v26 */
    fun createV26Database(context: Context, dbName: String) {
        createV19Database(context, dbName)
        migrate(
            context = context,
            dbName = dbName,
            fromVersion = 19,
            targetVersion = 26,
            migrations = listOf(
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24,
                AppDatabase.MIGRATION_24_25,
                AppDatabase.MIGRATION_25_26
            )
        )
    }

    /** 在 v26 基础上执行 v27 迁移，并写入 F1 的 Agent 会话 / 消息 / 引用数据 */
    fun createV27Database(context: Context, dbName: String) {
        createV26Database(context, dbName)
        migrate(
            context = context,
            dbName = dbName,
            fromVersion = 26,
            targetVersion = 27,
            migrations = listOf(AppDatabase.MIGRATION_26_27)
        )
        val file = context.getDatabasePath(dbName)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL(
                "INSERT INTO agent_session (id, title, bookId, modelId, status, contextPrefix, " +
                    "lastTaskId, createdAt, updatedAt, lastMessageAt) VALUES " +
                    "(1, '旧 Agent 会话', 1, NULL, 'ACTIVE', '', NULL, 1, 2, 2)"
            )
            db.execSQL(
                "INSERT INTO agent_message (id, sessionId, taskId, role, kind, content, thinkingSummary, " +
                    "sequence, promptTokens, completionTokens, cachedTokens, promptLog, attachments, createdAt) VALUES " +
                    "(1, 1, NULL, 'USER', 'USER_INPUT', '旧 Agent 消息', '', 0, -1, 0, -1, '', '', 1)"
            )
            db.execSQL(
                "INSERT INTO agent_session_reference (id, sessionId, sourceType, sourceId, bookId, chapterId, " +
                    "volumeId, rangeStart, rangeEnd, titleSnapshot, contentHash, referenceMode, enabled, selectedAt) VALUES " +
                    "(1, 1, 'CHAPTER_SYNOPSIS', 1, 1, 1, NULL, NULL, NULL, '第一章', '', 'CONTEXT', 1, 1)"
            )
        } finally {
            db.close()
        }
    }

    /** 在 v27 基础上执行 v28 迁移，并写入一条 F2 任务，供 F5 迁移测试使用 */
    fun createV28Database(context: Context, dbName: String) {
        createV27Database(context, dbName)
        migrate(
            context = context,
            dbName = dbName,
            fromVersion = 27,
            targetVersion = 28,
            migrations = listOf(AppDatabase.MIGRATION_27_28)
        )
        val file = context.getDatabasePath(dbName)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL(
                "INSERT INTO agent_task (id, sessionId, title, userGoal, status, planJson, currentNodeId, " +
                    "modelId, errorMessage, createdAt, startedAt, updatedAt, completedAt) VALUES " +
                    "(1, 1, '旧任务', '检查旧任务', 'WAITING_CONFIRMATION', '{}', NULL, NULL, '', 1, NULL, 2, NULL)"
            )
        } finally {
            db.close()
        }
    }

    /** 在 v28 基础上执行 v29 迁移，供 F6 迁移测试使用 */
    fun createV29Database(context: Context, dbName: String) {
        createV28Database(context, dbName)
        migrate(
            context = context,
            dbName = dbName,
            fromVersion = 28,
            targetVersion = 29,
            migrations = listOf(AppDatabase.MIGRATION_28_29)
        )
    }

    /** 在 v29 基础上执行 v30 迁移，并写入一条 F6 写入操作，供 F7 迁移测试使用 */
    fun createV30Database(context: Context, dbName: String) {
        createV29Database(context, dbName)
        migrate(
            context = context,
            dbName = dbName,
            fromVersion = 29,
            targetVersion = 30,
            migrations = listOf(AppDatabase.MIGRATION_29_30)
        )
        val file = context.getDatabasePath(dbName)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL(
                "INSERT INTO agent_write_operation (id, taskId, sessionId, toolExecutionId, nodeId, " +
                    "toolName, operationType, targetType, targetId, bookId, targetTitleSnapshot, " +
                    "bookTitleSnapshot, riskLevel, argumentsJson, beforeSnapshot, afterSnapshot, " +
                    "proposedContent, operationSummary, permissionStatus, executionStatus, " +
                    "idempotencyKey, reversible, createdTargetId, errorMessage, rollbackMessage, " +
                    "createdAt, updatedAt, confirmedAt, executedAt, rolledBackAt) VALUES " +
                    "(1, 1, 1, NULL, NULL, 'update_chapter_synopsis', 'UPDATE_CHAPTER_SYNOPSIS', " +
                    "'CHAPTER', 1, 1, '第一章', '旧书', 'NORMAL_WRITE', '{}', '{}', '{}', " +
                    "'旧梗概（待确认）', '修改梗概', 'PENDING', 'WAITING_CONFIRMATION', " +
                    "'f7-migration-write-1', 1, NULL, '', '', 1, 2, NULL, NULL, NULL)"
            )
        } finally {
            db.close()
        }
    }

    private fun createV19Database(context: Context, dbName: String) {
        val file = context.getDatabasePath(dbName)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            v19Schema.forEach { db.execSQL(it) }

            db.execSQL(
                "INSERT INTO book (id, title, intro, coverPath, coverColor, volumePrefix, chapterPrefix, " +
                    "pinned, groupId, createdAt, updatedAt, lastChapterId, lastChapterTitle, styleId) VALUES " +
                    "(1, '旧书', '简介', NULL, 0, '第%n卷', '第%n章', 0, NULL, 1, 2, 1, '第一章', 0)"
            )
            db.execSQL("INSERT INTO volume (id, bookId, title, sortOrder, createdAt) VALUES (1, 1, '第一卷', 0, 1)")
            db.execSQL(
                "INSERT INTO chapter (id, bookId, volumeId, title, summary, content, lastCursor, sortOrder, " +
                    "createdAt, updatedAt) VALUES (1, 1, 1, '第一章', '旧梗概', '旧正文', 0, 0, 1, 2)"
            )
            db.execSQL(
                "INSERT INTO outline_item (id, bookId, category, tag, title, content, sortOrder, updatedAt) VALUES " +
                    "(1, 1, 'CHARACTER', '', '旧人设', '｛姓名｝\n姓名：张三', 0, 1)"
            )
            db.execSQL(
                "INSERT INTO chat_session (id, title, bookId, chapterId, modelId, prefix, createdAt, updatedAt) VALUES " +
                    "(1, '旧会话', NULL, NULL, NULL, '', 1, 2)"
            )
            db.execSQL(
                "INSERT INTO chat_message (id, sessionId, role, content, replyToId, variantIndex, active, reasoning, " +
                    "contextSnapshot, promptExtra, promptTokens, completionTokens, cachedTokens, contentCollapsed, " +
                    "chapterId, promptLog, roundName, createdAt) VALUES " +
                    "(1, 1, 'USER', '旧消息', 0, 0, 1, '', '', '', 0, 0, 0, 0, 0, '', '', 1)"
            )

            db.version = 19
        } finally {
            db.close()
        }
    }

    private fun migrate(
        context: Context,
        dbName: String,
        fromVersion: Int,
        targetVersion: Int,
        migrations: List<Migration>
    ) {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(fromVersion) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            migrations.forEach { it.migrate(db) }
            db.version = targetVersion
        } finally {
            helper.close()
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
