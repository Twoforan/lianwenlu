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
    // 删除章节 / 书籍 / 合并章节时需要同步清理其长期记忆向量块，避免已删内容仍被检索召回
    private val memoryChunkDao = db.memoryChunkDao()

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

    suspend fun deleteBook(bookId: Long) = db.withTransaction {
        chapterDao.getByBook(bookId).forEach { chapterDao.deleteById(it.id) }
        volumeDao.getByBook(bookId).forEach { volumeDao.deleteById(it.id) }
        outlineDao.getByBook(bookId).forEach { outlineDao.deleteById(it.id) }
        // 长期记忆向量块按书籍清理，防止删书后仍被检索召回
        memoryChunkDao.deleteByBook(bookId)
        bookDao.deleteById(bookId)
    }

    /**
     * 「复制本书」：新建一本空白书（只有默认首卷首章），把原书的大纲条目完整复制过去
     * （分类标签、字段、排序位置一并保留）。新书标题自动加序号，从（1）开始取第一个未被占用的。
     * 返回新书 id（原书不存在时返回 0）。
     */
    suspend fun copyBookOutline(sourceBookId: Long): Long = db.withTransaction {
        val src = bookDao.getById(sourceBookId) ?: return@withTransaction 0L
        // 去掉原标题末尾已有的（n）作为基准名，避免复制副本时叠加成「书名（1）（1）」
        val base = src.title.replace(Regex("（\\d+）$"), "")
        val existing = bookDao.getAll().map { it.title }.toSet()
        var index = 1
        while ("$base（$index）" in existing) index++
        val newId = createBook(
            title = "$base（$index）",
            intro = src.intro,
            coverPath = src.coverPath,
            coverColor = src.coverColor,
            volumePrefix = src.volumePrefix,
            chapterPrefix = src.chapterPrefix,
            groupId = src.groupId
        )
        outlineDao.getByBook(sourceBookId).forEach { item ->
            outlineDao.insert(item.copy(id = 0, bookId = newId))
        }
        newId
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
        // 同步清理该章节的全部向量块：删章节即意味着「不要了」，残留在向量库会污染后续检索
        memoryChunkDao.deleteByChapter(chapterId)
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

    /** 批量删除章节，剩余章节序号归一化为连续 */
    suspend fun deleteChapters(bookId: Long, chapterIds: List<Long>) {
        chapterIds.forEach { chapterDao.deleteById(it) }
        // 被删章节的向量块一并清理，避免已删内容继续参与记忆检索
        chapterIds.forEach { memoryChunkDao.deleteByChapter(it) }
        normalizeChapterOrder(bookId)
    }

    /**
     * 批量把章节移动到指定分卷：被移动章节保持原有相对顺序，追加到目标分卷末尾，
     * 其余章节顺序不变。最后整体归一化 sortOrder。
     */
    suspend fun moveChaptersToVolume(bookId: Long, orderedIds: List<Long>, volumeId: Long?) {
        val all = chapterDao.getByBook(bookId)
        val idSet = orderedIds.toSet()
        val moving = orderedIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
        if (moving.isEmpty()) return
        // 目标分卷内（排除即将移入的）最后一章，移入章节追加其后
        val lastInTarget = all.lastOrNull { it.volumeId == volumeId && it.id !in idSet }
        val seq = mutableListOf<Chapter>()
        all.filter { it.id !in idSet }.forEach { c ->
            seq.add(c)
            if (c.id == lastInTarget?.id) seq.addAll(moving)
        }
        if (lastInTarget == null) seq.addAll(moving)
        seq.forEachIndexed { index, c ->
            val vol = if (c.id in idSet) volumeId else c.volumeId
            chapterDao.moveToVolume(c.id, vol, index)
        }
    }

    /**
     * 把若干章节按给定顺序合并为一章：标题取第一章，正文以各章标题作为分隔；
     * 合并后删除原章节并归一化序号，返回新章节 id（无有效章节时返回 null）。
     */
    suspend fun mergeChapters(bookId: Long, orderedIds: List<Long>): Long? {
        val all = chapterDao.getByBook(bookId)
        val chosen = orderedIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
        if (chosen.isEmpty()) return null
        val first = chosen.first()
        val content = buildString {
            chosen.forEach { c ->
                append(c.title).append("\n\n")
                if (c.content.isNotBlank()) append(c.content).append("\n\n")
            }
        }.trimEnd('\n')
        val mergedId = chapterDao.insert(
            Chapter(
                bookId = bookId,
                volumeId = first.volumeId,
                title = first.title,
                summary = first.summary,
                content = content,
                sortOrder = first.sortOrder
            )
        )
        chosen.forEach { chapterDao.deleteById(it.id) }
        // 合并即删除原章节：其向量块同步清理，避免残留在检索池里
        chosen.forEach { memoryChunkDao.deleteByChapter(it.id) }
        touchBook(bookId, mergedId, first.title)
        normalizeChapterOrder(bookId)
        return mergedId
    }

    /**
     * 章节序号重排：只重排 [volumeId] 所在分卷（null 表示未分卷章节）的章节标题，卷内从 1 重新编号；
     * 其他分卷的标题保持不变，最后整体归一化 sortOrder（不改变现有先后顺序）。
     */
    suspend fun renumberChapters(bookId: Long, volumeId: Long?) {
        val template = bookDao.getById(bookId)?.chapterPrefix ?: "第%n章"
        // getByBook 已按 sortOrder 排序，卷内相对顺序即当前顺序
        chapterDao.getByBook(bookId)
            .filter { it.volumeId == volumeId }
            .forEachIndexed { i, c -> chapterDao.update(c.copy(title = buildTitle(template, i + 1))) }
        normalizeChapterOrder(bookId)
    }

    /** 把整本书的章节 sortOrder 归一化为连续序号 */
    private suspend fun normalizeChapterOrder(bookId: Long) {
        chapterDao.getByBook(bookId).forEachIndexed { i, c ->
            if (c.sortOrder != i) chapterDao.setOrder(c.id, i)
        }
    }

    suspend fun searchInBook(bookId: Long, keyword: String): List<Chapter> =
        if (keyword.isBlank()) emptyList() else chapterDao.searchInBook(bookId, keyword)
}
