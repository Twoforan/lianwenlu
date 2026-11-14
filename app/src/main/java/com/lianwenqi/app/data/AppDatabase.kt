package com.lianwenqi.app.data

import android.content.Context
import android.util.Log
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
import com.lianwenqi.app.data.dao.ChapterSummaryTagDao
import com.lianwenqi.app.data.dao.ChatDao
import com.lianwenqi.app.data.dao.MemoryChunkDao
import com.lianwenqi.app.data.dao.OutlineDao
import com.lianwenqi.app.data.dao.PromptDao
import com.lianwenqi.app.data.dao.ProviderDao
import com.lianwenqi.app.data.dao.StyleProfileDao
import com.lianwenqi.app.data.dao.StyleTagDao
import com.lianwenqi.app.data.dao.VolumeCompressionDao
import com.lianwenqi.app.data.dao.VolumeDao
import com.lianwenqi.app.data.dao.VolumeOutlineDao
import com.lianwenqi.app.data.dao.VolumeSummaryDao
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatSession
import com.lianwenqi.app.data.entity.MemoryChunk
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.Prompt
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.StyleProfile
import com.lianwenqi.app.data.entity.StyleSampleChunk
import com.lianwenqi.app.data.entity.StyleSampleRecord
import com.lianwenqi.app.data.entity.StyleSampleTagLink
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagCategory
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeCompressionBatchRecord
import com.lianwenqi.app.data.entity.VolumeCompressionSession
import com.lianwenqi.app.data.entity.VolumeOutlineSuggestion
import com.lianwenqi.app.data.entity.VolumeOutlineUpdate
import com.lianwenqi.app.data.entity.VolumeSummary

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
        MemoryChunk::class,
        StyleProfile::class,
        StyleTagCategory::class,
        StyleTag::class,
        StyleSampleRecord::class,
        StyleSampleChunk::class,
        StyleSampleTagLink::class,
        ChapterSummaryTag::class,
        VolumeSummary::class,
        VolumeCompressionSession::class,
        VolumeCompressionBatchRecord::class,
        VolumeOutlineUpdate::class,
        VolumeOutlineSuggestion::class
    ],
    version = 25,
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
    abstract fun styleProfileDao(): StyleProfileDao
    abstract fun styleTagDao(): StyleTagDao
    abstract fun chapterSummaryTagDao(): ChapterSummaryTagDao
    abstract fun volumeSummaryDao(): VolumeSummaryDao
    abstract fun volumeCompressionDao(): VolumeCompressionDao
    abstract fun volumeOutlineDao(): VolumeOutlineDao

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
         * v16：文风档案表 + 书籍文风绑定字段。
         * 旧书统一为未绑定（styleId = 0），已有书籍 / 卷 / 章节 / 正文 / 大纲等数据保持不变。
         */
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS style_profile (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "sourceType TEXT NOT NULL, " +
                        "sourceMeta TEXT NOT NULL, " +
                        "analysisJson TEXT NOT NULL, " +
                        "analysisVersion INTEGER NOT NULL, " +
                        "modelName TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("ALTER TABLE book ADD COLUMN styleId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v17：清理历史遗留的「孤儿章节」。
         * 旧版本删除分卷只删 volume 行，章节的 volumeId 悬空：目录里看不见，却仍出现在
         * 向量化进度、能被记忆检索召回，并会被备份导出后在恢复时复活。
         * 与删除分卷的级联语义保持一致：连同这些章节的向量块一并删除；未分卷（volumeId 为空）
         * 的章节属于有效数据，不在此列。清理数量写入日志便于追溯。
         */
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val orphanIds = mutableListOf<Long>()
                db.query(
                    "SELECT c.id FROM chapter c LEFT JOIN volume v ON v.id = c.volumeId " +
                        "WHERE c.volumeId IS NOT NULL AND (v.id IS NULL OR v.bookId != c.bookId)"
                ).use { cursor ->
                    while (cursor.moveToNext()) orphanIds += cursor.getLong(0)
                }
                if (orphanIds.isEmpty()) return
                val placeholders = orphanIds.joinToString(",") { "?" }
                val args = orphanIds.toTypedArray()
                db.execSQL("DELETE FROM memory_chunk WHERE chapterId IN ($placeholders)", args)
                db.execSQL("DELETE FROM chapter WHERE id IN ($placeholders)", args)
                Log.i("AppDatabase", "v17 清理孤儿章节 ${orphanIds.size} 个：$orphanIds")
            }
        }

        /**
         * v18：文风标签体系与样本片段。
         * 只新增表，不改旧表：旧文风档案、书籍绑定、正文 / 梗概 / 向量全部原样保留。
         * 旧样本（旧版本只存在内存）没有可迁移的记录；旧档案标签库为空，允许为空。
         */
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS style_tag_category (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "styleProfileId INTEGER NOT NULL DEFAULT 0, " +
                        "name TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_style_tag_category_styleProfileId ON style_tag_category (styleProfileId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_style_tag_category_styleProfileId_name " +
                        "ON style_tag_category (styleProfileId, name)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS style_tag (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "styleProfileId INTEGER NOT NULL DEFAULT 0, " +
                        "category TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_style_tag_styleProfileId ON style_tag (styleProfileId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_style_tag_styleProfileId_category_name " +
                        "ON style_tag (styleProfileId, category, name)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS style_sample (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "styleProfileId INTEGER, " +
                        "fileName TEXT NOT NULL, " +
                        "format TEXT NOT NULL, " +
                        "charCount INTEGER NOT NULL DEFAULT 0, " +
                        "status TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_style_sample_styleProfileId ON style_sample (styleProfileId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS style_sample_chunk (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sampleId INTEGER NOT NULL, " +
                        "styleProfileId INTEGER NOT NULL DEFAULT 0, " +
                        "chunkIndex INTEGER NOT NULL, " +
                        "text TEXT NOT NULL, " +
                        "hash TEXT NOT NULL, " +
                        "dim INTEGER NOT NULL DEFAULT 0, " +
                        "vector BLOB, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_style_sample_chunk_sampleId ON style_sample_chunk (sampleId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_style_sample_chunk_styleProfileId ON style_sample_chunk (styleProfileId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_style_sample_chunk_sampleId_chunkIndex " +
                        "ON style_sample_chunk (sampleId, chunkIndex)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS style_sample_tag (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "chunkId INTEGER NOT NULL, " +
                        "tagId INTEGER NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_style_sample_tag_chunkId ON style_sample_tag (chunkId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_style_sample_tag_tagId ON style_sample_tag (tagId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_style_sample_tag_chunkId_tagId " +
                        "ON style_sample_tag (chunkId, tagId)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS chapter_summary_tag (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "chapterId INTEGER NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "tagId INTEGER NOT NULL DEFAULT 0, " +
                        "source TEXT NOT NULL, " +
                        "summaryHash TEXT NOT NULL, " +
                        "manual INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chapter_summary_tag_chapterId ON chapter_summary_tag (chapterId)")
            }
        }

        /**
         * v19：梗概标签补充「句子定位」。
         * 只新增列、不改旧行：已有书籍 / 梗概 / 正文 / 标签全部保留；
         * 旧标签的句子定位为 -1 / 空，不参与正文抽样，下一次生成或分析时自动替换。
         */
        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapter_summary_tag ADD COLUMN sentenceIndex INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE chapter_summary_tag ADD COLUMN sentenceStart INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE chapter_summary_tag ADD COLUMN sentenceEnd INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE chapter_summary_tag ADD COLUMN sentenceText TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v20：整卷梗概表 + 模型 Token 上下文窗口字段。
         * 只新增表与列，不改旧数据：旧书升级后没有任何卷梗概，
         * 书籍 / 分卷 / 章节 / 大纲 / 模型 / 会话 / 记忆全部原样保留。
         *
         * volume_summary 的唯一索引 (volumeId, isCurrent) 保证每卷至多一条生效版本：
         * 只有生效版把 isCurrent 置 1，候选 / 归档一律 NULL，SQLite 唯一索引允许多个 NULL。
         */
        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS volume_summary (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "bookId INTEGER NOT NULL, " +
                        "volumeId INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "sourceType TEXT NOT NULL, " +
                        "modelContent TEXT NOT NULL, " +
                        "userContent TEXT NOT NULL, " +
                        "sourceRevision TEXT NOT NULL, " +
                        "sourceChapterCount INTEGER NOT NULL, " +
                        "processedChapterCount INTEGER NOT NULL, " +
                        "sourceChapterIds TEXT NOT NULL, " +
                        "sourceChapterHashes TEXT NOT NULL, " +
                        "modelId INTEGER NOT NULL DEFAULT 0, " +
                        "modelName TEXT NOT NULL DEFAULT '', " +
                        "compressionSessionId INTEGER, " +
                        "isCurrent INTEGER, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_volume_summary_bookId ON volume_summary (bookId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_volume_summary_volumeId ON volume_summary (volumeId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_summary_compressionSessionId " +
                        "ON volume_summary (compressionSessionId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_volume_summary_volumeId_isCurrent " +
                        "ON volume_summary (volumeId, isCurrent)"
                )
                // 模型真实 Token 上下文窗口；0 = 未知（不能拿 maxContext 顶替）
                db.execSQL("ALTER TABLE ai_model ADD COLUMN contextWindow INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v21：整卷压缩任务（会话 + 请求批次）。
         * 只新增表，不改旧数据：旧书、卷梗概、聊天、模型全部保留。
         *
         * 批次表唯一索引 (sessionId, kind, batchIndex) 保证同一任务重跑同一请求时覆盖旧记录，
         * usage 不会因为恢复 / 单批重试被重复累计。
         */
        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS volume_compression_session (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "bookId INTEGER NOT NULL, " +
                        "volumeId INTEGER NOT NULL, " +
                        "sourceType TEXT NOT NULL, " +
                        "sourceRevision TEXT NOT NULL, " +
                        "modelId INTEGER NOT NULL, " +
                        "modelName TEXT NOT NULL, " +
                        "configSnapshot TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "phase TEXT NOT NULL, " +
                        "errorKind TEXT, " +
                        "errorMessage TEXT NOT NULL, " +
                        "plannedBatchCount INTEGER NOT NULL, " +
                        "completedBatchCount INTEGER NOT NULL, " +
                        "plannedRequestCount INTEGER NOT NULL, " +
                        "actualRequestCount INTEGER NOT NULL, " +
                        "estimatedInputTokens INTEGER NOT NULL, " +
                        "estimatedOutputTokens INTEGER NOT NULL, " +
                        "promptTokens INTEGER NOT NULL, " +
                        "completionTokens INTEGER NOT NULL, " +
                        "cachedTokens INTEGER NOT NULL, " +
                        "requestsWithUsage INTEGER NOT NULL, " +
                        "candidateSummaryId INTEGER, " +
                        "previousSessionId INTEGER, " +
                        "queuePosition INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "startedAt INTEGER, " +
                        "completedAt INTEGER, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_compression_session_bookId " +
                        "ON volume_compression_session (bookId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_compression_session_volumeId " +
                        "ON volume_compression_session (volumeId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_compression_session_status " +
                        "ON volume_compression_session (status)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS volume_compression_batch (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "batchIndex INTEGER NOT NULL, " +
                        "rangeLabel TEXT NOT NULL, " +
                        "chapterIds TEXT NOT NULL, " +
                        "inputHash TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "attempts INTEGER NOT NULL, " +
                        "requestCount INTEGER NOT NULL, " +
                        "promptTokens INTEGER NOT NULL, " +
                        "completionTokens INTEGER NOT NULL, " +
                        "cachedTokens INTEGER NOT NULL, " +
                        "requestsWithUsage INTEGER NOT NULL, " +
                        "extractionJson TEXT NOT NULL, " +
                        "rawContent TEXT NOT NULL, " +
                        "errorKind TEXT, " +
                        "errorMessage TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_compression_batch_sessionId " +
                        "ON volume_compression_batch (sessionId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_volume_compression_batch_sessionId_kind_batchIndex " +
                        "ON volume_compression_batch (sessionId, kind, batchIndex)"
                )
            }
        }

        /**
         * v22：F6 大纲范围快照与差异建议。
         *
         * - 只给 session 加一列，旧压缩任务的目标范围为空（表示没有大纲目标），行为不变；
         * - 只新增两张表：一次分析（volume_outline_update）与逐条建议
         *   （volume_outline_suggestion）。不新增任何大纲条目实体，
         *   写入仍然只走 outline_item。
         */
        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE volume_compression_session " +
                        "ADD COLUMN outlineScopeSnapshot TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS volume_outline_update (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "sessionId INTEGER NOT NULL, " +
                        "bookId INTEGER NOT NULL, " +
                        "volumeId INTEGER NOT NULL, " +
                        "mode TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "scopeIds TEXT NOT NULL, " +
                        "scopeLabel TEXT NOT NULL, " +
                        "summaryId INTEGER, " +
                        "promptTokens INTEGER NOT NULL, " +
                        "completionTokens INTEGER NOT NULL, " +
                        "cachedTokens INTEGER NOT NULL, " +
                        "requestCount INTEGER NOT NULL, " +
                        "requestsWithUsage INTEGER NOT NULL, " +
                        "rawResponse TEXT NOT NULL, " +
                        "errorMessage TEXT NOT NULL, " +
                        "note TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_volume_outline_update_sessionId " +
                        "ON volume_outline_update (sessionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_outline_update_bookId " +
                        "ON volume_outline_update (bookId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_outline_update_volumeId " +
                        "ON volume_outline_update (volumeId)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS volume_outline_suggestion (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "updateId INTEGER NOT NULL, " +
                        "sessionId INTEGER NOT NULL, " +
                        "suggestionIndex INTEGER NOT NULL, " +
                        "operation TEXT NOT NULL, " +
                        "targetOutlineItemId INTEGER, " +
                        "category TEXT NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "oldContent TEXT NOT NULL, " +
                        "newContent TEXT NOT NULL, " +
                        "reason TEXT NOT NULL, " +
                        "confidence REAL NOT NULL, " +
                        "relatedEvents TEXT NOT NULL, " +
                        "expectedUpdatedAt INTEGER NOT NULL, " +
                        "contentFingerprint TEXT NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "statusMessage TEXT NOT NULL, " +
                        "selected INTEGER NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_outline_suggestion_updateId " +
                        "ON volume_outline_suggestion (updateId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_volume_outline_suggestion_sessionId " +
                        "ON volume_outline_suggestion (sessionId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_volume_outline_suggestion_updateId_suggestionIndex " +
                        "ON volume_outline_suggestion (updateId, suggestionIndex)"
                )
            }
        }

        /**
         * v23：大纲条目新增「可更新」属性。
         *
         * 只给 outline_item 加一列，旧条目默认 0（不参与大纲更新），
         * 由用户在条目上长按按需开启。
         */
        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE outline_item " +
                        "ADD COLUMN updatable INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v24：大纲条目新增「常驻化」属性。
         *
         * 常驻化由原来的「分类级」改为「条目级」：只给 outline_item 加一列，
         * 旧条目默认 0（未常驻化），由用户在条目上点击图钉按需开启。
         */
        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE outline_item " +
                        "ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 聊天附件与联网搜索：
         * - chat_message.attachments 记录本轮图片 / 文件 / 链接的清单（JSON，只存本地路径与链接地址）；
         * - ai_model.webSearchEnabled 标记模型是否支持内置联网搜索，旧模型默认不支持。
         */
        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE chat_message " +
                        "ADD COLUMN attachments TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE ai_model " +
                        "ADD COLUMN webSearchEnabled INTEGER NOT NULL DEFAULT 0"
                )
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25).addCallback(REPAIR_OVERSIZED_ROWS).build().also { INSTANCE = it }
            }
    }
}