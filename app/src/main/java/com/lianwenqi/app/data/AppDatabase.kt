package com.lianwenqi.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lianwenqi.app.data.dao.AiModelDao
import com.lianwenqi.app.data.dao.BookDao
import com.lianwenqi.app.data.dao.BookGroupDao
import com.lianwenqi.app.data.dao.ChapterDao
import com.lianwenqi.app.data.dao.ChatDao
import com.lianwenqi.app.data.dao.MemoryChunkDao
import com.lianwenqi.app.data.dao.OutlineDao
import com.lianwenqi.app.data.dao.PromptDao
import com.lianwenqi.app.data.dao.ProviderDao
import com.lianwenqi.app.data.dao.VolumeDao
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatSession
import com.lianwenqi.app.data.entity.MemoryChunk
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.Volume

@Database(
    entities = [
        BookGroup::class,
        Book::class,
        Volume::class,
        Chapter::class,
        OutlineItem::class,
        Prompt::class,
        Provider::class,
        AiModel::class,
        ChatSession::class,
        ChatMessage::class,
        MemoryChunk::class
    ],
    version = 15,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookGroupDao(): BookGroupDao
    abstract fun bookDao(): BookDao
    abstract fun volumeDao(): VolumeDao
    abstract fun chapterDao(): ChapterDao
    abstract fun outlineDao(): OutlineDao
    abstract fun promptDao(): PromptDao
    abstract fun providerDao(): ProviderDao
    abstract fun aiModelDao(): AiModelDao
    abstract fun chatDao(): ChatDao
    abstract fun memoryChunkDao(): MemoryChunkDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v2：聊天消息支持「重新生成」的多版本切换与深度思考内容 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN replyToId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_message ADD COLUMN variantIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_message ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE chat_message ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v3：旧默认的 2048 最大输出 token 会被推理模型的思考过程吃满，导致正文为空，
         * 这里把过小的值统一提高到 8192。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE ai_model SET maxTokens = 8192 WHERE maxTokens < 8192")
            }
        }

        /** v4：聊天消息记录发送时选中的上下文，用于在消息上回显「文件清单」 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN contextSnapshot TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5：模型支持用户自定义显示名，并区分厂商模型（标识只读）与用户自建模型 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_model ADD COLUMN alias TEXT NOT NULL DEFAULT ''")
                // 既有模型默认按「用户自建」处理，保证仍可修改
                db.execSQL("ALTER TABLE ai_model ADD COLUMN custom INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** v6：模型支持「最大上下文条数」，聊天消息记录本次消耗的 token 用量 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_model ADD COLUMN maxContext INTEGER NOT NULL DEFAULT 20")
                db.execSQL("ALTER TABLE chat_message ADD COLUMN promptTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_message ADD COLUMN completionTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7：会话可绑定发起时的章节，用于章节内 AI 抽屉恢复历史对话 */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_session ADD COLUMN chapterId INTEGER")
            }
        }

        /** v8：回答正文支持折叠，折叠状态随消息持久化 */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN contentCollapsed INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v9：记录本次输入中命中缓存的 token 数，用于展示缓存命中率 */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN cachedTokens INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v10：随消息一起发给模型但不显示在气泡里的附加提示词（如「＋本章梗概」） */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN promptExtra TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v11：会话级「前缀」（自定义前缀 + 已冻结的书籍/大纲/章节条目） */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_session ADD COLUMN prefix TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v12：长期记忆（书本向量化）——书籍正文切块后的向量片段 */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_chunk (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "bookId INTEGER NOT NULL, " +
                        "volumeId INTEGER, " +
                        "chapterId INTEGER NOT NULL, " +
                        "chunkIndex INTEGER NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "hash TEXT NOT NULL, " +
                        "chapterUpdatedAt INTEGER NOT NULL, " +
                        "dim INTEGER NOT NULL, " +
                        "vector BLOB NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_chunk_bookId ON memory_chunk (bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_chunk_chapterId ON memory_chunk (chapterId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_memory_chunk_chapterId_chunkIndex " +
                        "ON memory_chunk (chapterId, chunkIndex)"
                )
            }
        }

        /** v13：模型新增「思考模式开关」与「思考强度档位」（默认开启、最高强度） */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_model ADD COLUMN reasoningEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE ai_model ADD COLUMN reasoningLevel TEXT NOT NULL DEFAULT 'max'")
            }
        }

        /** v14：消息记录「所属章节」与「本轮提示词日志」，用于按章节分组收起与查看提示词日志 */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN chapterId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_message ADD COLUMN promptLog TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v15：未绑定章节的「本轮对话」自定义名称（存于该轮用户消息上，不同步书籍） */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_message ADD COLUMN roundName TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 修复历史遗留的超长行：整本正文被冻结进会话前缀、超长提示词日志 / 正文等，
         * 单行超过 SQLite CursorWindow（2MB）上限后读取会抛 SQLiteBlobTooBigException 直接闪退。
         * 首次打开数据库时把超限文本截断到安全长度，之后用标记表跳过，避免每次启动全表扫描。
         */
        private val REPAIR_OVERSIZED_ROWS = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS db_repair_flag (id INTEGER PRIMARY KEY, done INTEGER NOT NULL DEFAULT 0)")
                val repaired = db.query("SELECT done FROM db_repair_flag WHERE id = 1").use {
                    it.moveToFirst() && it.getInt(0) == 1
                }
                if (repaired) return
                db.execSQL("UPDATE chat_message SET content = substr(content, 1, $MAX_STORED_CONTENT) WHERE length(content) > $MAX_STORED_CONTENT")
                db.execSQL("UPDATE chat_message SET reasoning = substr(reasoning, 1, $MAX_STORED_REASONING) WHERE length(reasoning) > $MAX_STORED_REASONING")
                db.execSQL("UPDATE chat_message SET promptExtra = substr(promptExtra, 1, $MAX_STORED_PROMPT_EXTRA) WHERE length(promptExtra) > $MAX_STORED_PROMPT_EXTRA")
                db.execSQL("UPDATE chat_message SET promptLog = substr(promptLog, 1, $MAX_STORED_PROMPT_LOG) WHERE length(promptLog) > $MAX_STORED_PROMPT_LOG")
                db.execSQL("UPDATE chat_session SET prefix = substr(prefix, 1, $MAX_STORED_PREFIX) WHERE length(prefix) > $MAX_STORED_PREFIX")
                db.execSQL("INSERT OR REPLACE INTO db_repair_flag (id, done) VALUES (1, 1)")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lianwenqi.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15).addCallback(REPAIR_OVERSIZED_ROWS).build().also { INSTANCE = it }
            }
    }
}