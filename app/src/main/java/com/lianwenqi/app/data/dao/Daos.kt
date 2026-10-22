package com.lianwenqi.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
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
import kotlinx.coroutines.flow.Flow

@Dao
interface BookGroupDao {
    @Query("SELECT * FROM book_group ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_group ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<BookGroup>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: BookGroup): Long

    @Update
    suspend fun update(group: BookGroup)

    @Delete
    suspend fun delete(group: BookGroup)
}

@Dao
interface BookDao {
    /** 置顶优先，之后按修改时间倒序 */
    @Query("SELECT * FROM book ORDER BY pinned DESC, updatedAt DESC")
    fun observeByTimeDesc(): Flow<List<Book>>

    @Query("SELECT * FROM book ORDER BY pinned DESC, updatedAt ASC")
    fun observeByTimeAsc(): Flow<List<Book>>

    @Query("SELECT * FROM book ORDER BY pinned DESC, title ASC")
    fun observeByNameAsc(): Flow<List<Book>>

    @Query("SELECT * FROM book ORDER BY pinned DESC, title DESC")
    fun observeByNameDesc(): Flow<List<Book>>

    @Query("SELECT * FROM book WHERE id = :id")
    fun observeById(id: Long): Flow<Book?>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun getById(id: Long): Book?

    /** 按书名精确匹配（内置说明书更新时，用于在丢失记录后重新找回这本书） */
    @Query("SELECT * FROM book WHERE title = :title LIMIT 1")
    suspend fun findByTitle(title: String): Book?

    @Query("SELECT * FROM book ORDER BY pinned DESC, updatedAt DESC")
    suspend fun getAll(): List<Book>

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Query("UPDATE book SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE book SET groupId = :groupId WHERE id = :id")
    suspend fun setGroup(id: Long, groupId: Long?)

    @Query("UPDATE book SET updatedAt = :time, lastChapterId = :chapterId, lastChapterTitle = :chapterTitle WHERE id = :id")
    suspend fun touch(id: Long, time: Long, chapterId: Long?, chapterTitle: String?)

    @Query("DELETE FROM book WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface VolumeDao {
    @Query("SELECT * FROM volume WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    fun observeByBook(bookId: Long): Flow<List<Volume>>

    @Query("SELECT * FROM volume WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    suspend fun getByBook(bookId: Long): List<Volume>

    @Insert
    suspend fun insert(volume: Volume): Long

    @Update
    suspend fun update(volume: Volume)

    @Query("UPDATE volume SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    @Query("DELETE FROM volume WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空某本书的全部分卷（数据恢复时整本覆盖用） */
    @Query("DELETE FROM volume WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapter WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    fun observeByBook(bookId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapter WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    suspend fun getByBook(bookId: Long): List<Chapter>

    @Query("SELECT * FROM chapter WHERE id = :id")
    fun observeById(id: Long): Flow<Chapter?>

    @Query("SELECT * FROM chapter WHERE id = :id")
    suspend fun getById(id: Long): Chapter?

    @Query("SELECT COUNT(*) FROM chapter WHERE bookId = :bookId")
    suspend fun countByBook(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM chapter WHERE bookId = :bookId")
    fun observeCountByBook(bookId: Long): Flow<Int>

    @Insert
    suspend fun insert(chapter: Chapter): Long

    @Update
    suspend fun update(chapter: Chapter)

    @Query("UPDATE chapter SET title = :title, summary = :summary, content = :content, lastCursor = :cursor, updatedAt = :time WHERE id = :id")
    suspend fun saveContent(id: Long, title: String, summary: String, content: String, cursor: Int, time: Long)

    @Query("UPDATE chapter SET sortOrder = :order WHERE id = :id")
    suspend fun setOrder(id: Long, order: Int)

    @Query("UPDATE chapter SET volumeId = :volumeId, sortOrder = :order WHERE id = :id")
    suspend fun moveToVolume(id: Long, volumeId: Long?, order: Int)

    @Query("DELETE FROM chapter WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空某本书的全部章节（内置说明书按版本更新时整章替换用） */
    @Query("DELETE FROM chapter WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query(
        "SELECT * FROM chapter WHERE bookId = :bookId AND " +
            "(title LIKE '%' || :keyword || '%' OR summary LIKE '%' || :keyword || '%' OR content LIKE '%' || :keyword || '%') " +
            "ORDER BY sortOrder ASC"
    )
    suspend fun searchInBook(bookId: Long, keyword: String): List<Chapter>
}

@Dao
interface OutlineDao {
    @Query("SELECT * FROM outline_item WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    fun observeByBook(bookId: Long): Flow<List<OutlineItem>>

    @Query("SELECT * FROM outline_item WHERE bookId = :bookId AND category = :category ORDER BY sortOrder ASC, id ASC")
    fun observeByCategory(bookId: Long, category: String): Flow<List<OutlineItem>>

    @Query("DELETE FROM outline_item WHERE bookId = :bookId AND category = :category")
    suspend fun deleteByCategory(bookId: Long, category: String)

    /** 重命名分类：把该分类下的条目迁移到新分类名 */
    @Query("UPDATE outline_item SET category = :to WHERE bookId = :bookId AND category = :from")
    suspend fun renameCategory(bookId: Long, from: String, to: String)

    @Query("SELECT * FROM outline_item WHERE bookId = :bookId ORDER BY sortOrder ASC, id ASC")
    suspend fun getByBook(bookId: Long): List<OutlineItem>

    @Query("SELECT * FROM outline_item WHERE id = :id")
    suspend fun getById(id: Long): OutlineItem?

    @Insert
    suspend fun insert(item: OutlineItem): Long

    @Update
    suspend fun update(item: OutlineItem)

    @Query("DELETE FROM outline_item WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清空某本书的全部大纲条目（数据恢复时整本覆盖用） */
    @Query("DELETE FROM outline_item WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)
}

/** 某章已向量化块的哈希与生成时的章节更新时间，用于增量判定 */
data class ChunkHashRow(val hash: String, val chapterUpdatedAt: Long)

/** 长期记忆：书籍正文向量块的读写 */
@Dao
interface MemoryChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<MemoryChunk>)

    @Query("DELETE FROM memory_chunk WHERE chapterId = :chapterId")
    suspend fun deleteByChapter(chapterId: Long)

    @Query("DELETE FROM memory_chunk WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)

    @Query("SELECT * FROM memory_chunk WHERE bookId = :bookId")
    suspend fun getByBook(bookId: Long): List<MemoryChunk>

    @Query("SELECT hash, chapterUpdatedAt FROM memory_chunk WHERE chapterId = :chapterId")
    suspend fun hashesOf(chapterId: Long): List<ChunkHashRow>

    @Query("SELECT COUNT(DISTINCT chapterId) FROM memory_chunk WHERE bookId = :bookId")
    suspend fun countDistinctChapter(bookId: Long): Int

    @Query("SELECT MAX(chapterUpdatedAt) FROM memory_chunk WHERE bookId = :bookId")
    suspend fun latestChapterUpdatedAt(bookId: Long): Long?

    @Query("SELECT DISTINCT chapterId FROM memory_chunk WHERE bookId = :bookId")
    suspend fun vectorizedChapterIds(bookId: Long): List<Long>

    /** 本书已存向量的维度（取任意一块）；未向量化返回 null。用于检测换模型后的维度变化 */
    @Query("SELECT dim FROM memory_chunk WHERE bookId = :bookId LIMIT 1")
    suspend fun anyDim(bookId: Long): Int?

    /** 本书任意一块的哈希（取第一块）；未向量化返回 null。用于判断已存向量是否由当前向量化源写入 */
    @Query("SELECT hash FROM memory_chunk WHERE bookId = :bookId LIMIT 1")
    suspend fun anyHash(bookId: Long): String?

    @Query("SELECT DISTINCT chapterId FROM memory_chunk WHERE bookId = :bookId")
    fun observeVectorizedChapters(bookId: Long): Flow<List<Long>>
}

@Dao
interface PromptDao {
    @Query("SELECT * FROM prompt ORDER BY id DESC")
    fun observeAll(): Flow<List<Prompt>>

    @Query("SELECT * FROM prompt ORDER BY id DESC")
    suspend fun getAll(): List<Prompt>

    @Insert
    suspend fun insert(prompt: Prompt): Long

    @Update
    suspend fun update(prompt: Prompt)

    @Query("DELETE FROM prompt WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider ORDER BY id ASC")
    fun observeAll(): Flow<List<Provider>>

    @Query("SELECT * FROM provider ORDER BY id ASC")
    suspend fun getAll(): List<Provider>

    @Query("SELECT * FROM provider WHERE id = :id")
    suspend fun getById(id: Long): Provider?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(provider: Provider): Long

    @Update
    suspend fun update(provider: Provider)

    @Query("DELETE FROM provider WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface AiModelDao {
    @Query("SELECT * FROM ai_model ORDER BY id ASC")
    fun observeAll(): Flow<List<AiModel>>

    @Query("SELECT * FROM ai_model ORDER BY id ASC")
    suspend fun getAll(): List<AiModel>

    @Query("SELECT * FROM ai_model WHERE providerId = :providerId ORDER BY id ASC")
    suspend fun getByProvider(providerId: Long): List<AiModel>

    @Query("SELECT * FROM ai_model WHERE id = :id")
    suspend fun getById(id: Long): AiModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: AiModel): Long

    @Update
    suspend fun update(model: AiModel)

    @Query("DELETE FROM ai_model WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE ai_model SET isDefault = 0")
    suspend fun clearDefault()
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_session ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_session WHERE id = :id")
    suspend fun getSession(id: Long): ChatSession?

    /** 章节内 AI 抽屉恢复历史对话：取该章节最近一次会话 */
    @Query("SELECT * FROM chat_session WHERE chapterId = :chapterId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getSessionByChapter(chapterId: Long): ChatSession?

    /** 「跟书」模式：取该书最近一次绑定本书（未绑定章节）的会话 */
    @Query(
        "SELECT * FROM chat_session WHERE bookId = :bookId AND chapterId IS NULL " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getBookSession(bookId: Long): ChatSession?

    /** 「跟书」模式：该书全部绑定本书的会话，最新的在前 */
    @Query(
        "SELECT * FROM chat_session WHERE bookId = :bookId AND chapterId IS NULL " +
            "ORDER BY updatedAt DESC"
    )
    suspend fun bookSessions(bookId: Long): List<ChatSession>

    /** 「跟章节」模式：该章节下的全部会话（新建对话时用于销毁旧对话） */
    @Query("SELECT * FROM chat_session WHERE chapterId = :chapterId ORDER BY updatedAt DESC")
    suspend fun sessionsByChapter(chapterId: Long): List<ChatSession>

    @Insert
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    @Update
    suspend fun updateSessions(sessions: List<ChatSession>)

    @Query("UPDATE chat_session SET title = :title WHERE id = :id")
    suspend fun updateSessionTitle(id: Long, title: String)

    /** 更新会话级「前缀」（自定义前缀 + 已冻结条目） */
    @Query("UPDATE chat_session SET prefix = :prefix WHERE id = :id")
    suspend fun updateSessionPrefix(id: Long, prefix: String)

    @Query("DELETE FROM chat_session WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeMessages(sessionId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_message WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun getMessages(sessionId: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_message WHERE id = :id")
    suspend fun getMessage(id: Long): ChatMessage?

    @Query("SELECT * FROM chat_message WHERE replyToId = :replyToId ORDER BY variantIndex ASC, id ASC")
    suspend fun getVariants(replyToId: Long): List<ChatMessage>

    @Query("SELECT COALESCE(MAX(variantIndex), -1) FROM chat_message WHERE replyToId = :replyToId")
    suspend fun maxVariantIndex(replyToId: Long): Int

    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("UPDATE chat_message SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Query("UPDATE chat_message SET active = 0 WHERE replyToId = :replyToId")
    suspend fun deactivateVariants(replyToId: Long)

    @Query("UPDATE chat_message SET contentCollapsed = :collapsed WHERE id = :id")
    suspend fun updateContentCollapsed(id: Long, collapsed: Boolean)

    /** 修改未绑定章节「本轮对话」的显示名（只改消息，不同步书籍） */
    @Query("UPDATE chat_message SET roundName = :name WHERE id = :id")
    suspend fun updateRoundName(id: Long, name: String)

    @Query("UPDATE chat_message SET active = 1 WHERE id = :id")
    suspend fun activateMessage(id: Long)

    @Query("DELETE FROM chat_message WHERE replyToId = :replyToId")
    suspend fun deleteVariants(replyToId: Long)

    @Query("DELETE FROM chat_message WHERE sessionId = :sessionId")
    suspend fun clearMessages(sessionId: Long)

    @Query("DELETE FROM chat_message WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    /** 批量删除指定消息（编辑历史提示词后丢弃旧内容用） */
    @Query("DELETE FROM chat_message WHERE id IN (:ids)")
    suspend fun deleteMessagesByIds(ids: List<Long>)
}