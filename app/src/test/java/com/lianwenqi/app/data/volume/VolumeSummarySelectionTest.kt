package com.lianwenqi.app.data.volume

import com.lianwenqi.app.data.CompressionSourceMode
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.VolumeSummary
import com.lianwenqi.app.data.entity.VolumeSummaryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F7 卷梗概选择 / 前序卷推荐 / 自动压缩判定的纯逻辑测试 */
class VolumeSummarySelectionTest {

    private fun volume(id: Long, sortOrder: Int, bookId: Long = 1L) =
        Volume(id = id, bookId = bookId, title = "第${id}卷", sortOrder = sortOrder)

    private fun summary(
        id: Long,
        volumeId: Long,
        bookId: Long = 1L,
        status: String = VolumeSummaryStatus.ACTIVE
    ) = VolumeSummary(
        id = id,
        bookId = bookId,
        volumeId = volumeId,
        status = status,
        modelContent = "内容$id"
    )

    @Test
    fun `counts volume and chapter independently`() {
        val counts = VolumeSummarySelection.counts(
            selectedVolumeSummaryIds = setOf(1L, 2L),
            selectedChapterIds = setOf(10L, 11L, 12L)
        )
        assertEquals(2, counts.volumeCount)
        assertEquals(3, counts.chapterCount)
    }

    @Test
    fun `preceding volumes only include earlier volumes with active summary`() {
        val volumes = listOf(volume(12, 2), volume(10, 0), volume(11, 1))
        val summaries = listOf(
            summary(100, 10),
            summary(101, 11),
            summary(103, 12, status = VolumeSummaryStatus.ARCHIVED)
        )
        assertEquals(
            listOf(10L, 11L),
            VolumeSummarySelection.precedingVolumesWithSummary(volumes, summaries, 12)
        )
        assertEquals(
            listOf(10L),
            VolumeSummarySelection.precedingVolumesWithSummary(volumes, summaries, 11)
        )
        assertTrue(VolumeSummarySelection.precedingVolumesWithSummary(volumes, summaries, 10).isEmpty())
    }

    @Test
    fun `unknown target volume never recommends anything`() {
        val volumes = listOf(volume(10, 0), volume(11, 1))
        val summaries = listOf(summary(100, 10))
        assertTrue(VolumeSummarySelection.precedingVolumesWithSummary(volumes, summaries, null).isEmpty())
        assertTrue(VolumeSummarySelection.precedingVolumesWithSummary(volumes, summaries, 999).isEmpty())
    }

    @Test
    fun `recommendation respects manual exclusion and can be restored`() {
        val volumes = listOf(volume(10, 0), volume(11, 1), volume(12, 2))
        val summaries = listOf(summary(100, 10), summary(101, 11))

        val initial = VolumeSummarySelection.recommendedVolumeIds(volumes, summaries, 12, emptySet())
        assertEquals(setOf(10L, 11L), initial)

        val excluded = VolumeSummarySelection.applyManualDecision(emptySet(), 10, selected = false)
        assertEquals(setOf(10L), excluded)
        assertEquals(
            setOf(11L),
            VolumeSummarySelection.recommendedVolumeIds(volumes, summaries, 12, excluded)
        )

        val restored = VolumeSummarySelection.applyManualDecision(excluded, 10, selected = true)
        assertEquals(emptySet<Long>(), restored)
    }

    @Test
    fun `prune drops deleted archived and cross-book selections`() {
        val active = listOf(summary(100, 10), summary(101, 11))
        val (kept, dropped) = VolumeSummarySelection.pruneSelected(
            selectedSummaryIds = setOf(100L, 101L, 103L, 999L),
            bookId = 1L,
            activeSummaries = active
        )
        assertEquals(setOf(100L, 101L), kept)
        assertEquals(2, dropped)

        val (crossBook, crossDropped) = VolumeSummarySelection.pruneSelected(
            selectedSummaryIds = setOf(100L),
            bookId = 2L,
            activeSummaries = active
        )
        assertTrue(crossBook.isEmpty())
        assertEquals(1, crossDropped)
    }

    @Test
    fun `active summary per volume prefers the lowest id`() {
        val map = VolumeSummarySelection.activeByVolume(
            listOf(summary(102, 10), summary(100, 10), summary(101, 11))
        )
        assertEquals(100L, map[10L]?.id)
        assertEquals(101L, map[11L]?.id)
    }

    @Test
    fun `auto compression decision follows switch and source mode`() {
        assertEquals(
            VolumeSummarySelection.CompressDecision.NotNeeded,
            VolumeSummarySelection.decideCompress(10, true, true, CompressionSourceMode.SYNOPSIS)
        )
        assertEquals(
            VolumeSummarySelection.CompressDecision.Disabled,
            VolumeSummarySelection.decideCompress(10, false, false, CompressionSourceMode.SYNOPSIS)
        )
        assertEquals(
            VolumeSummarySelection.CompressDecision.AskSource(10),
            VolumeSummarySelection.decideCompress(10, false, true, CompressionSourceMode.ASK)
        )
        assertEquals(
            VolumeSummarySelection.CompressDecision.Start(10, CompressionSourceMode.CONTENT),
            VolumeSummarySelection.decideCompress(10, false, true, CompressionSourceMode.CONTENT)
        )
    }

    @Test
    fun `recommendation memory round trips per target volume`() {
        val store = mapOf(12L to setOf(10L, 11L), 15L to setOf(13L))
        val decoded = VolumeSummarySelection.decodeRecommendations(
            VolumeSummarySelection.encodeRecommendations(store)
        )
        assertEquals(store, decoded)
        assertTrue(VolumeSummarySelection.decodeRecommendations("not json").isEmpty())
    }
}
