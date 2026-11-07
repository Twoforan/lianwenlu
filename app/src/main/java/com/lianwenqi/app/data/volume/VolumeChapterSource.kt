package com.lianwenqi.app.data.volume

import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.VolumeSourceRevision
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.VolumeSummarySourceType

/** 章节来源适配器：F2 用章节梗概，F3 用章节正文，引擎其余流程完全复用 */
interface ChapterSourceAdapter {
    val sourceType: String

    /** 返回该卷全部有效章节（可以含空文本，由引擎统一筛选并计入缺失审计） */
    suspend fun load(bookId: Long, volumeId: Long): List<CompressionChapter>
}

/**
 * 章节梗概来源：读取某卷有效章节的 [Chapter.summary]，按目录顺序（sortOrder, id）排序。
 * 只读取，不写入章节梗概。
 */
class SynopsisChapterSourceAdapter(
    private val chaptersLoader: suspend (Long, Long) -> List<Chapter>
) : ChapterSourceAdapter {
    override val sourceType: String = VolumeSummarySourceType.SYNOPSIS

    override suspend fun load(bookId: Long, volumeId: Long): List<CompressionChapter> =
        chaptersLoader(bookId, volumeId)
            .sortedWith(compareBy<Chapter> { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, chapter ->
                CompressionChapter(
                    chapterId = chapter.id,
                    order = index + 1,
                    title = chapter.title,
                    text = chapter.summary,
                    contentHash = VolumeSourceRevision.contentHash(chapter.summary)
                )
            }

    companion object {
        /** 生产环境：复用 F1 确认的统一有效章节口径（同一本书、同一分卷、分卷仍存在） */
        fun from(db: AppDatabase): SynopsisChapterSourceAdapter =
            SynopsisChapterSourceAdapter { bookId, volumeId ->
                db.chapterDao().getValidByVolume(bookId, volumeId)
            }
    }
}

/**
 * 章节正文来源（F3）：读取 [Chapter.content]，与梗概来源共用同一套有效章节口径与排序。
 * 缺失正文的章节保留在审计里，由引擎统一筛选，绝不静默跳过。
 */
class ContentChapterSourceAdapter(
    private val chaptersLoader: suspend (Long, Long) -> List<Chapter>
) : ChapterSourceAdapter {
    override val sourceType: String = VolumeSummarySourceType.CONTENT

    override suspend fun load(bookId: Long, volumeId: Long): List<CompressionChapter> =
        chaptersLoader(bookId, volumeId)
            .sortedWith(compareBy<Chapter> { it.sortOrder }.thenBy { it.id })
            .mapIndexed { index, chapter ->
                CompressionChapter(
                    chapterId = chapter.id,
                    order = index + 1,
                    title = chapter.title,
                    text = chapter.content,
                    contentHash = VolumeSourceRevision.contentHash(chapter.content)
                )
            }

    companion object {
        fun from(db: AppDatabase): ContentChapterSourceAdapter =
            ContentChapterSourceAdapter { bookId, volumeId ->
                db.chapterDao().getValidByVolume(bookId, volumeId)
            }
    }
}
