package com.lianwenqi.app.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F7：ContextSnapshot v3（卷梗概 id）编码与 v1 / v2 兼容解码 */
class ContextSnapshotV3Test {

    @Test
    fun `v3 round trip keeps volume summary ids`() {
        val snapshot = ContextSnapshot(
            bookIds = listOf(1L),
            chapterIds = listOf(10L, 11L),
            outlineIds = listOf(20L),
            summaryChapterIds = listOf(10L),
            contentChapterIds = listOf(11L),
            chapterAll = false,
            summaryAll = false,
            contentAll = false,
            volumeSummaryIds = listOf(100L, 101L)
        )
        val raw = snapshot.encode()
        assertTrue(raw.endsWith("|v3"))
        assertEquals(snapshot, decodeContextSnapshot(raw))
    }

    @Test
    fun `v2 snapshot decodes with empty volume summaries`() {
        val raw = "1|10,11|20|10|11|0|0|0|v2"
        val snapshot = decodeContextSnapshot(raw)
        assertEquals(listOf(1L), snapshot.bookIds)
        assertEquals(listOf(10L, 11L), snapshot.chapterIds)
        assertEquals(listOf(20L), snapshot.outlineIds)
        assertEquals(listOf(10L), snapshot.summaryChapterIds)
        assertEquals(listOf(11L), snapshot.contentChapterIds)
        assertTrue(snapshot.volumeSummaryIds.isEmpty())
    }

    @Test
    fun `v1 snapshot still expands summary and content flags`() {
        val raw = "1|10,11|20|1|0|0"
        val snapshot = decodeContextSnapshot(raw)
        assertEquals(listOf(10L, 11L), snapshot.summaryChapterIds)
        assertTrue(snapshot.contentChapterIds.isEmpty())
        assertTrue(snapshot.volumeSummaryIds.isEmpty())
    }

    @Test
    fun `volume summary alone is not an empty selection`() {
        val snapshot = ContextSnapshot(volumeSummaryIds = listOf(100L))
        assertFalse(snapshot.isEmpty)
        assertEquals(snapshot, decodeContextSnapshot(snapshot.encode()))
    }

    @Test
    fun `blank snapshot decodes to defaults`() {
        assertTrue(decodeContextSnapshot("").isEmpty)
        assertTrue(decodeContextSnapshot("").volumeSummaryIds.isEmpty())
    }

    @Test
    fun `old session prefix json decodes with empty volume summary fields`() {
        val oldJson = """{"custom":"前缀","initialized":true,""" +
            """"books":[{"id":1,"text":"BOOK"}],"chapters":[{"id":2,"text":"CH"}],""" +
            """"checkedChapters":[2]}"""
        val prefix = SessionPrefix.decode(oldJson)
        assertTrue(prefix.volumeSummaries.isEmpty())
        assertTrue(prefix.checkedVolumeSummaries.isEmpty())
        assertTrue(prefix.tailVolumeSummaries.isEmpty())
        assertEquals("前缀", prefix.custom)
        assertEquals(1, prefix.books.size)
        assertEquals("", prefix.renderReference(includeChapters = false, includeOutlines = false))
        assertTrue(prefix.renderReference(includeChapters = true, includeOutlines = false).contains("BOOK"))
    }

    @Test
    fun `corrupt session prefix falls back to empty prefix`() {
        assertEquals(SessionPrefix(), SessionPrefix.decode("{broken"))
        assertEquals(SessionPrefix(), SessionPrefix.decode(""))
    }

    @Test
    fun `volume summary reference is dropped when chapter reference is off`() {
        val prefix = SessionPrefix(
            volumeSummaries = listOf(PrefixEntry(100, "VOLUME\n")),
            chapters = listOf(PrefixEntry(1, "CHAPTER\n"))
        )
        assertFalse(prefix.renderReference(includeChapters = false, includeOutlines = true).contains("VOLUME"))
        assertTrue(prefix.renderReference(includeChapters = true, includeOutlines = true).contains("VOLUME"))
    }

    @Test
    fun `prefix renders volume summary after outlines and before chapters`() {
        val rendered = SessionPrefix().renderEntries(
            books = listOf(PrefixEntry(1, "BOOK\n")),
            outlines = listOf(PrefixEntry(2, "OUTLINE\n", category = OutlineCategory.STORY.name)),
            chapters = listOf(PrefixEntry(3, "CHAPTER\n")),
            volumeSummaries = listOf(PrefixEntry(4, "VOLUME\n"))
        )
        val book = rendered.indexOf("BOOK")
        val outline = rendered.indexOf("OUTLINE")
        val volume = rendered.indexOf("VOLUME")
        val chapter = rendered.indexOf("CHAPTER")
        assertTrue(book in 0 until outline)
        assertTrue(outline in 0 until volume)
        assertTrue(volume in 0 until chapter)
    }
}
