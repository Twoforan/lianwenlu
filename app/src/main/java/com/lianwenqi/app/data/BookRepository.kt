package com.lianwenqi.app.data

import androidx.room.withTransaction
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.util.TextTool
import com.lianwenqi.app.util.buildTitle
import com.lianwenqi.app.util.nextTitleIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SortMode { TIME, NAME }

/** 书籍列表所需的聚合信息 */
data class BookSummary(
    val book: Book,
    val wordCount: Int,
    val chapterCount: Int
)

/** 书籍领域：书籍、分卷、章节、书籍分组 */
class BookRepository(private val db: AppDatabase) {
    private val bookDao = db.bookDao()
    private val volumeDao = db.volumeDao()
    private val chapterDao = db.chapterDao()
    private val outlineDao = db.outlineDao()
    private val groupDao = db.bookGroupDao()

    // ---------------- 书架 ----------------

    fun observeBooks(mode: SortMode, asc: Boolean): Flow<List<Book>> = when (mode) {
        SortMode.TIME -> if (asc) bookDao.observeByTimeAsc() else bookDao.observeByTimeDesc()
        SortMode.NAME -> if (asc) bookDao.observeByNameAsc() else bookDao.observeByNameDesc()
    }

    fun observeBookSummaries(mode: SortMode, asc: Boolean): Flow<List<BookSummary>> =
        observeBooks(mode, asc).map { books ->
            books.map { b ->
                val chapters = chapterDao.getByBook(b.id)
                BookSummary(
                    book = b,
                    wordCount = chapters.sumOf { TextTool.countWords(it.content) },
                    chapterCount = chapters.size
                )
            }
        }

    fun observeBook(id: Long): Flow<Book?> = bookDao.observeById(id)

    suspend fun getBook(id: Long): Book? = bookDao.getById(id)

    suspend fun allBooks(): List<Book> = bookDao.getAll()

    /** 新建书籍：同时创建首卷与首章 */
    suspend fun createBook(
        title: String,
        intro: String,
        coverPath: String?,
        coverColor: Int,
        volumePrefix: String,
        chapterPrefix: String,
        groupId: Long? = null
    ): Long = db.withTransaction {
        val bookId = bookDao.insert(
            Book(
                title = title,
                intro = intro,
                coverPath = coverPath,
                coverColor = coverColor,
                volumePrefix = volumePrefix,
                chapterPrefix = chapterPrefix,
                groupId = groupId
            )
        )
        val volumeId = volumeDao.insert(
            Volume(bookId = bookId, title = buildTitle(volumePrefix, 1), sortOrder = 0)
        )
        val chapterId = chapterDao.insert(
            Chapter(
                bookId = bookId,
                volumeId = volumeId,
                title = buildTitle(chapterPrefix, 1),
                sortOrder = 0
            )
        )
        bookDao.touch(bookId, System.currentTimeMillis(), chapterId, buildTitle(chapterPrefix, 1))
        bookId
    }

    /**
     * TXT 导入：建书 + 首卷（不建首章），随后由 [importVolumes] 批量写入分卷章节。
     * 章节前缀沿用默认「第%n章」，导入后在这本书里新建章节会从最大编号顺延。
     */
    suspend fun createBookShell(
        title: String,
        intro: String,
        coverColor: Int,
        groupId: Long? = null
    ): Long = db.withTransaction {
        val bookId = bookDao.insert(
            Book(
                title = title,
                intro = intro,
                coverPath = null,
                coverColor = coverColor,
                groupId = groupId
            )
        )
        volumeDao.insert(Volume(bookId = bookId, title = buildTitle("第%n卷", 1), sortOrder = 0))
        bookId
    }

    /** TXT 导入：事务内批量写入分卷与章节，返回 (章节数, 首章id) */
    suspend fun importVolumes(
        bookId: Long,
        volumes: List<Pair<String, List<Pair<String, String>>>> // 卷名 → (章节标题, 章节正文) 列表
    ): Pair<Int, Long?> = db.withTransaction {
        var chapterCount = 0
        var firstChapterId: Long? = null
        var lastChapterTitle: String? = null
        volumes.forEachIndexed { vi, (volumeTitle, chapters) ->
            // 首卷复用 createBookShell 已建的第1卷，其余新建
            val volumeId = if (vi == 0) {
                volumeDao.getByBook(bookId).firstOrNull()?.id
                    ?: volumeDao.insert(Volume(bookId = bookId, title = volumeTitle, sortOrder = 0))
            } else {
                volumeDao.insert(
                    Volume(bookId = bookId, title = volumeTitle, sortOrder = vi)
                )
            }
            chapters.forEach { (chapterTitle, content) ->
                val id = chapterDao.insert(
                    Chapter(
                        bookId = bookId,
                        volumeId = volumeId,
                        title = chapterTitle,
                        content = content,
                        sortOrder = chapterCount
                    )
                )
                if (firstChapterId == null) firstChapterId = id
                lastChapterTitle = chapterTitle
                chapterCount++
            }
        }
        if (firstChapterId != null) {
            bookDao.touch(bookId, System.currentTimeMillis(), firstChapterId, lastChapterTitle)
        }
        chapterCount to firstChapterId
    }

    suspend fun deleteBook(bookId: Long) = db.withTransaction {
        chapterDao.getByBook(bookId).forEach { chapterDao.deleteById(it.id) }
        volumeDao.getByBook(bookId).forEach { volumeDao.deleteById(it.id) }
        outlineDao.getByBook(bookId).forEach { outlineDao.deleteById(it.id) }
        bookDao.deleteById(bookId)
    }

    /** 更新书籍信息（编辑：封面 / 书名 / 简介 / 分组 / 前缀等） */
    suspend fun updateBook(book: Book) =
        bookDao.update(book.copy(updatedAt = System.currentTimeMillis()))

    suspend fun setPinned(bookId: Long, pinned: Boolean) = bookDao.setPinned(bookId, pinned)

    suspend fun moveToGroup(bookId: Long, groupId: Long?) = bookDao.setGroup(bookId, groupId)

    suspend fun touchBook(bookId: Long, chapterId: Long?, chapterTitle: String?) =
        bookDao.touch(bookId, System.currentTimeMillis(), chapterId, chapterTitle)

    fun observeGroups() = groupDao.observeAll()
    suspend fun createGroup(name: String) = groupDao.insert(BookGroup(name = name))
    suspend fun deleteGroup(id: Long) = groupDao.delete(BookGroup(id = id, name = ""))

    // ---------------- 分卷 / 章节 ----------------

    fun observeVolumes(bookId: Long): Flow<List<Volume>> = volumeDao.observeByBook(bookId)

    fun observeChapters(bookId: Long): Flow<List<Chapter>> = chapterDao.observeByBook(bookId)

    suspend fun getChapters(bookId: Long): List<Chapter> = chapterDao.getByBook(bookId)

    suspend fun getVolumes(bookId: Long): List<Volume> = volumeDao.getByBook(bookId)

    fun observeChapter(id: Long): Flow<Chapter?> = chapterDao.observeById(id)

    suspend fun getChapter(id: Long): Chapter? = chapterDao.getById(id)

    suspend fun createVolume(bookId: Long, prefix: String? = null): Long {
        val book = bookDao.getById(bookId)
        val volumes = volumeDao.getByBook(bookId)
        val template = prefix ?: book?.volumePrefix ?: "第%n卷"
        val index = nextTitleIndex(template, volumes.map { it.title })
        return volumeDao.insert(
            Volume(bookId = bookId, title = buildTitle(template, index), sortOrder = volumes.size)
        )
    }

    /** 以指定标题新建分卷（章节梗概导入时按 AI 给的分卷名建卷） */
    suspend fun createVolumeWithTitle(bookId: Long, title: String): Long {
        val volumes = volumeDao.getByBook(bookId)
        return volumeDao.insert(
            Volume(bookId = bookId, title = title, sortOrder = volumes.size)
        )
    }

    /** 新建章节。volumeId 为空时放入最后一卷 */
    suspend fun createChapter(bookId: Long, volumeId: Long? = null): Long {
        val book = bookDao.getById(bookId)
        val chapters = chapterDao.getByBook(bookId)
        val order = chapters.size
        val vid = volumeId ?: volumeDao.getByBook(bookId).lastOrNull()?.id
        val template = book?.chapterPrefix ?: "第%n章"
        // 编号按「当前分卷内」顺延：每个分卷都从第1章重新开始
        val siblings = chapters.filter { it.volumeId == vid }
        val title = buildTitle(template, nextTitleIndex(template, siblings.map { it.title }))
        val id = chapterDao.insert(
            Chapter(bookId = bookId, volumeId = vid, title = title, sortOrder = order)
        )
        touchBook(bookId, id, title)
        return id
    }

    /** 以指定标题与梗概新建章节（章节梗概导入用），追加到全书末尾 */
    suspend fun createChapterWithSummary(
        bookId: Long,
        volumeId: Long?,
        title: String,
        summary: String
    ): Long {
        val chapters = chapterDao.getByBook(bookId)
        val id = chapterDao.insert(
            Chapter(
                bookId = bookId,
                volumeId = volumeId,
                title = title,
                summary = summary,
                sortOrder = chapters.size
            )
        )
        touchBook(bookId, id, title)
        return id
    }

    suspend fun saveChapter(chapter: Chapter) = chapterDao.update(chapter.copy(updatedAt = System.currentTimeMillis()))

    suspend fun saveChapterContent(id: Long, title: String, summary: String, content: String, cursor: Int) {
        chapterDao.saveContent(id, title, summary, content, cursor, System.currentTimeMillis())
    }

    suspend fun deleteChapter(bookId: Long, chapterId: Long) {
        chapterDao.deleteById(chapterId)
        val remaining = chapterDao.getByBook(bookId)
        remaining.forEachIndexed { i, c -> if (c.sortOrder != i) chapterDao.setOrder(c.id, i) }
    }

    suspend fun deleteVolume(volumeId: Long) {
        volumeDao.deleteById(volumeId)
    }

    suspend fun renameVolume(volume: Volume, title: String) = volumeDao.update(volume.copy(title = title))

    /** 排序：按 sortOrder 列表重排章节 */
    suspend fun reorderChapters(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> chapterDao.setOrder(id, index) }
    }

    suspend fun reorderVolumes(ordered: List<Volume>) {
        ordered.forEachIndexed { index, v -> volumeDao.setOrder(v.id, index) }
    }

    suspend fun moveChapterToVolume(chapterId: Long, volumeId: Long?, order: Int) =
        chapterDao.moveToVolume(chapterId, volumeId, order)

    suspend fun searchInBook(bookId: Long, keyword: String): List<Chapter> =
        if (keyword.isBlank()) emptyList() else chapterDao.searchInBook(bookId, keyword)
}
