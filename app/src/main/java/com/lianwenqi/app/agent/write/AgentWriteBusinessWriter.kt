package com.lianwenqi.app.agent.write

import androidx.room.withTransaction
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.OutlineRepository
import com.lianwenqi.app.data.entity.OutlineItem
import kotlinx.serialization.Serializable

/** 章节快照；梗概操作不加载正文，正文操作才包含 content */
@Serializable
data class AgentChapterSnapshot(
    val id: Long,
    val bookId: Long,
    val title: String,
    val summary: String,
    val content: String = "",
    val contentIncluded: Boolean = false,
    val contentLength: Int = 0,
    val updatedAt: Long = 0
)

/** 大纲条目快照；用于冲突检测与撤销 */
@Serializable
data class AgentOutlineSnapshot(
    val id: Long,
    val bookId: Long,
    val category: String,
    val tag: String,
    val title: String,
    val content: String,
    val sortOrder: Int,
    val pinned: Boolean,
    val updatable: Boolean,
    val updatedAt: Long
)

/**
 * F6 业务写入适配层。
 *
 * 只暴露单目标、可校验、可撤销的最小操作；不提供删除章节 / 整本书等能力。
 * 所有实现必须走现有 Repository，不直接拼 SQL。
 */
interface AgentWriteBusinessWriter {
    suspend fun bookTitle(bookId: Long): String?
    suspend fun chapterMeta(chapterId: Long): AgentChapterSnapshot?
    suspend fun chapterContent(chapterId: Long): String?
    suspend fun updateChapterSynopsis(chapterId: Long, summary: String)
    suspend fun updateChapterContent(chapterId: Long, content: String)
    suspend fun outline(outlineId: Long): AgentOutlineSnapshot?
    suspend fun insertOutline(
        bookId: Long,
        category: String,
        tag: String,
        title: String,
        content: String
    ): Long

    suspend fun updateOutlineContent(
        outlineId: Long,
        category: String,
        tag: String,
        title: String,
        content: String
    )

    suspend fun deleteOutline(outlineId: Long)
}

/** 事务执行器抽象；JVM 测试可注入直接执行实现 */
interface AgentWriteTransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomAgentWriteTransactionRunner(private val db: AppDatabase) : AgentWriteTransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}

class RoomAgentWriteBusinessWriter(
    private val db: AppDatabase,
    private val books: BookRepository,
    private val outlines: OutlineRepository
) : AgentWriteBusinessWriter {

    override suspend fun bookTitle(bookId: Long): String? = books.getBook(bookId)?.title

    override suspend fun chapterMeta(chapterId: Long): AgentChapterSnapshot? {
        val meta = db.agentReadDao().referenceChapterMeta(chapterId) ?: return null
        return AgentChapterSnapshot(
            id = meta.id,
            bookId = meta.bookId,
            title = meta.title,
            summary = meta.summary,
            content = "",
            contentIncluded = false,
            contentLength = meta.contentLength,
            updatedAt = meta.updatedAt
        )
    }

    override suspend fun chapterContent(chapterId: Long): String? =
        db.chapterDao().getById(chapterId)?.content

    override suspend fun updateChapterSynopsis(chapterId: Long, summary: String) =
        books.updateChapterSynopsis(chapterId, summary)

    override suspend fun updateChapterContent(chapterId: Long, content: String) =
        books.updateChapterContent(chapterId, content)

    override suspend fun outline(outlineId: Long): AgentOutlineSnapshot? {
        val item = outlines.getById(outlineId) ?: return null
        return item.toSnapshot()
    }

    override suspend fun insertOutline(
        bookId: Long,
        category: String,
        tag: String,
        title: String,
        content: String
    ): Long {
        val nextOrder = (outlines.getOutline(bookId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        return outlines.insertOutline(
            OutlineItem(
                bookId = bookId,
                category = category,
                tag = tag,
                title = title,
                content = content,
                sortOrder = nextOrder,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun updateOutlineContent(
        outlineId: Long,
        category: String,
        tag: String,
        title: String,
        content: String
    ) {
        val current = outlines.getById(outlineId)
            ?: throw IllegalArgumentException("大纲条目不存在：$outlineId")
        outlines.updateOutline(
            current.copy(
                category = category,
                tag = tag,
                title = title,
                content = content
            )
        )
    }

    override suspend fun deleteOutline(outlineId: Long) =
        outlines.deleteOutlineById(outlineId)

    private fun OutlineItem.toSnapshot() = AgentOutlineSnapshot(
        id = id,
        bookId = bookId,
        category = category,
        tag = tag,
        title = title,
        content = content,
        sortOrder = sortOrder,
        pinned = pinned,
        updatable = updatable,
        updatedAt = updatedAt
    )
}
