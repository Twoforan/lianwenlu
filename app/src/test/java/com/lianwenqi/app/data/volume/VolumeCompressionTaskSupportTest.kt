package com.lianwenqi.app.data.volume

import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.VolumeSummarySourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5 纯函数层测试：预估与真实 usage 分离、批次输入指纹、结构化结果编解码。
 * 不访问网络 / 数据库 / Android。
 */
class VolumeCompressionTaskSupportTest {

    private val bookId = 1L
    private val volumeId = 2L
    private val modelId = 7L

    private fun model() = AiModel(
        id = modelId,
        providerId = 1L,
        name = "test-model",
        alias = "测试模型",
        maxTokens = 4096,
        contextWindow = 32000
    )

    private fun chapter(id: Long, order: Int, summary: String) = Chapter(
        id = id,
        bookId = bookId,
        volumeId = volumeId,
        title = "第${order}章",
        summary = summary,
        content = "正文$id",
        sortOrder = order
    )

    private class OneChapterBatcher : CompressionBatcher {
        override fun plan(
            chapters: List<CompressionChapter>,
            inputBudgetTokens: Int,
            perChapterOverheadTokens: Int
        ): List<CompressionBatch> = chapters.mapIndexed { index, chapter ->
            CompressionBatch(
                index = index,
                chapters = listOf(chapter),
                estimatedInputTokens = CompressionTokenEstimator.estimate(chapter.title) +
                    CompressionTokenEstimator.estimate(chapter.text)
            )
        }
    }

    private fun service(chapters: List<Chapter>): VolumeCompressionService =
        VolumeCompressionService(
            chapterSource = SynopsisChapterSourceAdapter { _, _ -> chapters },
            modelLoader = { if (it == modelId) model() else null },
            chat = { _, _ -> AiResult.fail("测试不发起请求") },
            saveCandidate = { null },
            batcher = OneChapterBatcher()
        )

    private fun request() = VolumeCompressionRequest(
        bookId = bookId,
        volumeId = volumeId,
        modelId = modelId,
        sourceType = VolumeSummarySourceType.SYNOPSIS
    )

    @Test
    fun `estimate splits extraction and merge without reading real usage`() = runBlocking {
        val chapters = listOf(
            chapter(1, 1, "第一章梗概：主角离开故乡。"),
            chapter(2, 2, "第二章梗概：主角在旅途中遇到同伴。"),
            chapter(3, 3, "第三章梗概：卷末伏笔回收。")
        )
        val service = service(chapters)
        val prepared = service.prepare(request())
        assertTrue(prepared is PreparationOutcome.Ready)
        val preparation = (prepared as PreparationOutcome.Ready).preparation
        val estimate = service.estimate(preparation)

        val rawInput = chapters.sumOf {
            CompressionTokenEstimator.estimate(it.title) +
                CompressionTokenEstimator.estimate(it.summary)
        }
        assertEquals(3, estimate.batchCount)
        assertEquals(rawInput, estimate.plan.inputTokenEstimate)
        assertEquals(estimate.extractionInputTokens + estimate.mergeInputTokens, estimate.inputTokens)
        assertEquals(estimate.extractionOutputTokens + estimate.mergeOutputTokens, estimate.outputTokens)
        assertEquals(estimate.batchCount, estimate.plan.batchCount)
        assertTrue("整合至少一次请求", estimate.requestCount > estimate.batchCount)
        assertEquals(estimate.extractionOutputTokens, 3 * preparation.extractionCapacity.outputTokens)
    }

    @Test
    fun `input hash changes with content and slice layout`() {
        val base = CompressionChapter(
            chapterId = 11,
            order = 1,
            title = "第1章",
            text = "第一段",
            contentHash = "h1"
        )
        val batch = CompressionBatch(0, listOf(base), 10)
        val same = CompressionBatch(0, listOf(base.copy()), 10)
        val changedContent = CompressionBatch(0, listOf(base.copy(contentHash = "h2")), 10)
        val slice = CompressionBatch(
            0,
            listOf(
                base.copy(text = "第一", sliceIndex = 1, sliceCount = 2, sourceStart = 0, sourceEnd = 2),
                base.copy(text = "段", sliceIndex = 2, sliceCount = 2, sourceStart = 2, sourceEnd = 3)
            ),
            10
        )

        val hash = VolumeCompressionInputHash.of(VolumeSummarySourceType.SYNOPSIS, batch)
        assertEquals(hash, VolumeCompressionInputHash.of(VolumeSummarySourceType.SYNOPSIS, same))
        assertNotEquals(hash, VolumeCompressionInputHash.of(VolumeSummarySourceType.SYNOPSIS, changedContent))
        assertNotEquals(hash, VolumeCompressionInputHash.of(VolumeSummarySourceType.SYNOPSIS, slice))
        // 来源类型不同同样必须重跑，不能复用旧批次的成功结果
        assertNotEquals(hash, VolumeCompressionInputHash.of(VolumeSummarySourceType.CONTENT, batch))
    }

    @Test
    fun `extraction encoding round trips through strict parser`() {
        val chapter = CompressionChapter(
            chapterId = 21,
            order = 1,
            title = "第1章",
            text = "正文",
            contentHash = "h21"
        )
        val batch = CompressionBatch(0, listOf(chapter), 10)
        val json = """
            {
              "chapterRange": "第1章",
              "sourceChapterIds": [21],
              "events": [{"text": "事件一", "chapterIds": [21]}],
              "participants": ["主角"],
              "cause": "起因",
              "result": "结果",
              "characterChanges": ["变化"],
              "relationshipChanges": [],
              "foreshadowing": [],
              "unresolvedIssues": [],
              "importantDetails": [],
              "subplots": []
            }
        """.trimIndent()
        val parsed = VolumeCompressionCodec.decodeExtraction(json, batch)
        assertTrue(parsed != null)
        val encoded = VolumeCompressionCodec.encodeExtraction(parsed!!)
        val decoded = VolumeCompressionCodec.decodeExtraction(encoded, batch)
        assertEquals(parsed, decoded)
    }

    @Test
    fun `usage plus keeps estimate and real tokens in separate fields`() {
        val estimate = CompressionUsage(inputTokenEstimate = 100)
        val real = CompressionUsage(
            promptTokens = 80,
            completionTokens = 20,
            cachedTokens = 30,
            requestCount = 1,
            requestsWithUsage = 1
        )
        val merged = estimate.plus(real)
        assertEquals(100, merged.inputTokenEstimate)
        assertEquals(80, merged.promptTokens)
        assertEquals(20, merged.completionTokens)
        assertEquals(30, merged.cachedTokens)
        assertEquals(1, merged.requestCount)
    }
}
