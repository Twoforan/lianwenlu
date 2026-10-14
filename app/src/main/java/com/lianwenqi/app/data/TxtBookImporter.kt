package com.lianwenqi.app.data

import com.lianwenqi.app.util.ParsedTxt

/** TXT 导入结果 */
data class TxtImportResult(
    val bookId: Long,
    val chapterCount: Int,
    val firstChapterId: Long?
)

/**
 * 把 [ParsedTxt] 写入书架：建书壳 → 批量写卷章，全部在一个调用内完成。
 * 空章节文本（0 章）会被拒绝，调用方需先校验。
 */
class TxtBookImporter(private val bookRepository: BookRepository) {

    suspend fun import(
        parsed: ParsedTxt,
        intro: String,
        coverColor: Int,
        groupId: Long?
    ): TxtImportResult {
        val volumeData = parsed.volumes
            .filter { it.chapters.isNotEmpty() }
            .map { v -> v.title to v.chapters.map { it.title to it.content } }
        require(volumeData.isNotEmpty()) { "没有可导入的章节" }

        val bookId = bookRepository.createBookShell(
            title = parsed.suggestedTitle,
            intro = intro,
            coverColor = coverColor,
            groupId = groupId
        )
        val (chapterCount, firstChapterId) = bookRepository.importVolumes(bookId, volumeData)
        return TxtImportResult(bookId, chapterCount, firstChapterId)
    }
}
