package com.lianwenqi.app.data.volume

import com.lianwenqi.app.ai.AiError
import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.VolumeSummarySourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3：章节正文压缩链路。全部使用手写 fake 网关，不访问网络与数据库。
 */
class VolumeContentCompressionTest {

    private val bookId = 1L
    private val volumeId = 2L
    private val modelId = 7L

    private val finalText = "本卷剧情从主角踏入宗门开始，历经试探与冲突，最终在卷末完成立场转变，并回收了开篇埋下的伏笔。"

    private fun model(
        contextWindow: Int = 32000,
        maxTokens: Int = 4096
    ) = AiModel(
        id = modelId,
        providerId = 1L,
        name = "test-model",
        alias = "测试模型",
        maxTokens = maxTokens,
        contextWindow = contextWindow
    )

    private fun chapter(
        id: Long,
        order: Int,
        content: String,
        summary: String = "不应被正文压缩使用的梗概"
    ) = Chapter(
        id = id,
        bookId = bookId,
        volumeId = volumeId,
        title = "第${order}章",
        summary = summary,
        content = content,
        sortOrder = order
    )

    private val idLine = Regex("""【章节 id】([0-9、]+)""")
    private val rangeLine = Regex("""【本批范围】([^\n（]+)""")

    private fun extractionJson(
        ids: List<Long>,
        range: String,
        eventCount: Int = 2,
        eventChapterIds: List<Long> = ids
    ): String = buildString {
        append("{\"chapterRange\":\"").append(range).append("\",")
        append("\"sourceChapterIds\":[").append(ids.joinToString(",")).append("],")
        append("\"events\":[")
        repeat(eventCount) { index ->
            if (index > 0) append(',')
            append("{\"text\":\"重大事件${index + 1}：主角做出关键选择并推动主线\",")
            append("\"chapterIds\":[").append(eventChapterIds.joinToString(",")).append("]}")
        }
        append("],")
        append("\"participants\":[\"主角\",\"配角\"],")
        append("\"cause\":\"起因\",\"result\":\"结果\",")
        append("\"characterChanges\":[\"主角立场发生转变\"],")
        append("\"relationshipChanges\":[\"主角与配角从试探走向信任\"],")
        append("\"foreshadowing\":[\"开篇信物首次出现\"],")
        append("\"unresolvedIssues\":[\"信物来源仍未解释\"],")
        append("\"importantDetails\":[\"主角在雨夜做出选择\"],")
        append("\"subplots\":[\"配角身世支线\"]}")
    }

    private fun respondToExtraction(user: String, eventCount: Int = 2): String {
        val ids = idLine.find(user)!!.groupValues[1].split("、").map { it.toLong() }
        val range = rangeLine.find(user)!!.groupValues[1].trim()
        return extractionJson(ids, range, eventCount = eventCount)
    }

    private class TestRun(
        val service: VolumeCompressionService,
        val requests: MutableList<AiRequest>,
        val saved: MutableList<CompressionCandidate>
    )

    private fun run(
        chapters: List<Chapter>,
        model: AiModel = model(),
        batcher: CompressionBatcher = SlicingBudgetBatcher(),
        planner: CompressionLengthPlanner = DefaultCompressionLengthPlanner,
        save: suspend (CompressionCandidate) -> Long? = { 88L },
        onChat: (Int, AiModel, AiRequest) -> AiResult
    ): TestRun {
        val requests = mutableListOf<AiRequest>()
        val saved = mutableListOf<CompressionCandidate>()
        val service = VolumeCompressionService(
            chapterSource = ContentChapterSourceAdapter { _, _ -> chapters },
            modelLoader = { if (it == modelId) model else null },
            chat = { aiModel, request ->
                requests += request
                onChat(requests.size - 1, aiModel, request)
            },
            saveCandidate = { candidate ->
                saved += candidate
                save(candidate)
            },
            batcher = batcher,
            lengthPlanner = planner
        )
        return TestRun(service, requests, saved)
    }

    private fun request(
        contextWindowOverride: Int = 0,
        maxInputChars: Int = 0
    ) = VolumeCompressionRequest(
        bookId = bookId,
        volumeId = volumeId,
        modelId = modelId,
        sourceType = VolumeSummarySourceType.CONTENT,
        contextWindowOverride = contextWindowOverride,
        maxInputChars = maxInputChars
    )

    private fun defaultChat(eventCount: Int = 2): (Int, AiModel, AiRequest) -> AiResult =
        { _, _, req ->
            val user = req.turns.first().content
            if (user.contains("【整合轮次】")) {
                AiResult.ok(finalText)
            } else {
                AiResult.ok(respondToExtraction(user, eventCount))
            }
        }

    @Test
    fun `正文来源只读取 content 并保留缺失计数与顺序`() = runBlocking {
        val run = run(
            chapters = listOf(
                chapter(12L, 3, "  "),
                chapter(10L, 2, "第二章正文"),
                chapter(11L, 1, "第一章正文")
            ),
            onChat = defaultChat()
        )
        val success = run.service.compress(request()) as VolumeCompressionOutcome.Success
        assertEquals(3, success.audit.totalChapterCount)
        assertEquals(listOf(11L, 10L), success.audit.includedChapterIds)
        assertEquals(listOf(12L), success.audit.missingChapterIds)

        val prompt = run.requests.first().turns.first().content
        assertTrue(prompt.contains("第一章正文"))
        assertTrue(prompt.indexOf("id=11") < prompt.indexOf("id=10"))
        assertTrue(!prompt.contains("不应被正文压缩使用的梗概"))
    }

    @Test
    fun `完全没有正文时不发起请求`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, ""), chapter(2L, 2, "   ")),
            onChat = defaultChat()
        )
        val outcome = run.service.compress(request())
        assertTrue(outcome is VolumeCompressionOutcome.NoInput)
        assertEquals(0, run.requests.size)
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `按预算自动合批且章节顺序不乱`() = runBlocking {
        val chapters = (1..7).map { chapter(100L + it, it, "第${it}章正文。".repeat(100)) }
        val run = run(
            chapters = chapters,
            model = model(contextWindow = 4000, maxTokens = 2048),
            onChat = defaultChat()
        )
        val success = run.service.compress(request(maxInputChars = 3000)) as VolumeCompressionOutcome.Success

        assertTrue("长卷应自动拆成多批", success.plan.batchCount > 1)
        assertEquals(0, success.plan.slicedChapterCount)
        val units = success.plan.batches.flatMap { it.chapters }
        assertEquals(chapters.map { it.id }, units.map { it.chapterId })
        assertEquals(chapters.map { it.id }, success.batchResults.flatMap { it.batch.chapterIds })
        assertEquals(chapters.map { it.id }, run.saved.single().chapterIds)
    }

    @Test
    fun `单章超预算时无损切片并保持同一章节可追溯`() = runBlocking {
        val content = (1..40).joinToString("\n") {
            "第${it}段：主角在雨夜追踪线索，发现了新的证据，并与旧友发生争执。"
        }
        val run = run(
            chapters = listOf(chapter(21L, 1, content)),
            model = model(contextWindow = 2500, maxTokens = 2048),
            onChat = defaultChat()
        )
        val success = run.service.compress(request(maxInputChars = 1000)) as VolumeCompressionOutcome.Success

        assertEquals(1, success.plan.slicedChapterCount)
        val slices = success.plan.batches.flatMap { it.chapters }.sortedBy { it.sliceIndex }
        assertTrue(slices.size > 1)
        assertTrue(slices.first().isSlice)
        assertEquals(content, slices.joinToString("") { it.text })
        slices.forEachIndexed { index, slice ->
            assertEquals(21L, slice.chapterId)
            assertEquals(index + 1, slice.sliceIndex)
            assertEquals(slices.size, slice.sliceCount)
        }
        // 每个批次的事件来源都指向原章节，跨片事件可由同一 chapterId 追溯
        success.batchResults.forEach { result ->
            assertEquals(listOf(21L), result.batch.chapterIds)
            result.extraction.events.forEach { event ->
                assertEquals(listOf(21L), event.chapterIds)
            }
        }
        assertTrue(run.requests.first().turns.first().content.contains("片段=1/"))
        assertEquals(finalText, run.saved.single().modelContent)
    }

    @Test
    fun `单次提交字符数过低时明确失败而不是死循环`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "正文".repeat(100))),
            model = model(contextWindow = 1024, maxTokens = 512),
            onChat = defaultChat()
        )
        val failed = run.service.compress(request(maxInputChars = 100)) as VolumeCompressionOutcome.Failed
        assertTrue(failed.failure is CompressionFailure.InputBudgetTooSmall)
        assertEquals(0, run.requests.size)
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `Markdown 围栏与额外解释文字可以正确解析`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "第一章正文")),
            onChat = { _, _, req ->
                val user = req.turns.first().content
                if (user.contains("【整合轮次】")) {
                    AiResult.ok(finalText)
                } else {
                    val json = respondToExtraction(user)
                    AiResult.ok("下面是提取结果：\n```json\n$json\n```\n以上。")
                }
            }
        )
        val success = run.service.compress(request()) as VolumeCompressionOutcome.Success
        assertEquals(finalText, success.content)
        assertEquals(1, success.batchResults.single().attempts)
    }

    @Test
    fun `格式错误有界重试后成功并记录尝试次数`() = runBlocking {
        var extractionAttempts = 0
        val run = run(
            chapters = listOf(chapter(1L, 1, "第一章正文")),
            onChat = { _, _, req ->
                val user = req.turns.first().content
                if (user.contains("【整合轮次】")) {
                    AiResult.ok(finalText)
                } else {
                    extractionAttempts++
                    if (extractionAttempts == 1) {
                        AiResult.ok("{\"chapterRange\":\"第1章\"}")
                    } else {
                        AiResult.ok(respondToExtraction(user))
                    }
                }
            }
        )
        val success = run.service.compress(request()) as VolumeCompressionOutcome.Success
        assertEquals(2, success.batchResults.single().attempts)
        assertEquals(2, extractionAttempts)
        assertEquals(1, run.saved.size)
    }

    @Test
    fun `重试耗尽后失败且不提交候选`() = runBlocking {
        var extractionAttempts = 0
        val run = run(
            chapters = listOf(chapter(1L, 1, "第一章正文")),
            onChat = { _, _, _ ->
                extractionAttempts++
                AiResult.ok("{\"chapterRange\":\"第1章\"}")
            }
        )
        val failed = run.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertTrue(failed.failure is CompressionFailure.InvalidJson)
        assertEquals(2, extractionAttempts)
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `事件引用本批之外的章节会被拒绝`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "第一章正文")),
            onChat = { _, _, req ->
                val user = req.turns.first().content
                if (user.contains("【整合轮次】")) {
                    AiResult.ok(finalText)
                } else {
                    AiResult.ok(extractionJson(listOf(1L), "第1章", eventChapterIds = listOf(999L)))
                }
            }
        )
        val failed = run.service.compress(request()) as VolumeCompressionOutcome.Failed
        val detail = (failed.failure as CompressionFailure.InvalidJson).detail
        assertTrue(detail.contains("999"))
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `单批请求失败不提交候选`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "第一章正文")),
            onChat = { _, _, _ -> AiResult.fail(AiError.Timeout()) }
        )
        val failed = run.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertTrue(failed.failure is CompressionFailure.RequestFailed)
        assertEquals(1, failed.usage.requestCount)
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `最终输出被截断时不提交候选`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "第一章正文")),
            onChat = { _, _, req ->
                val user = req.turns.first().content
                if (user.contains("【整合轮次】")) {
                    AiResult.ok("{\"cut\":", completionTokens = req.maxTokens)
                } else {
                    AiResult.ok(respondToExtraction(user))
                }
            }
        )
        val failed = run.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertEquals(
            CompressionStage.MERGE,
            (failed.failure as CompressionFailure.TruncatedOutput).stage
        )
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `取消会停止后续请求并保留中间结果语义`() = runBlocking {
        val progress = mutableListOf<CompressionProgress>()
        val run = run(
            chapters = (1..6).map { chapter(100L + it, it, "第${it}章正文。".repeat(40)) },
            onChat = { index, _, _ ->
                if (index == 0) throw CancellationException("用户取消")
                else AiResult.ok(finalText)
            }
        )
        val thrown = try {
            run.service.compress(request()) { progress += it }
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull(thrown)
        assertEquals(1, run.requests.size)
        assertTrue(progress.any { it === CompressionProgress.Cancelled })
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `多级整合覆盖全部章节且不丢首末章`() = runBlocking {
        val chapters = (1..14).map { index ->
            chapter(200L + index, index, "第${index}章正文。".repeat(60))
        }
        val run = run(
            chapters = chapters,
            model = model(contextWindow = 4000, maxTokens = 1024),
            onChat = defaultChat(eventCount = 30)
        )
        val success = run.service.compress(request(maxInputChars = 2000)) as VolumeCompressionOutcome.Success

        val batches = success.plan.batchCount
        assertTrue("应触发多级整合", run.requests.size > batches + 1)
        val finalPrompt = run.requests.last().turns.first().content
        assertTrue(finalPrompt.contains("最终全卷整合"))
        chapters.forEach { chapter ->
            assertTrue(
                "最终整合必须覆盖章节 ${chapter.id}",
                finalPrompt.contains(chapter.id.toString())
            )
        }
        assertEquals(chapters.map { it.id }, run.saved.single().chapterIds)
        assertEquals(chapters.map { it.id }, success.audit.includedChapterIds)
    }

    @Test
    fun `多请求 usage 与 cachedTokens 汇总正确`() = runBlocking {
        val chapters = (1..5).map { chapter(100L + it, it, "第${it}章正文。".repeat(40)) }
        val run = run(
            chapters = chapters,
            onChat = { _, _, req ->
                val user = req.turns.first().content
                AiResult.ok(
                    if (user.contains("【整合轮次】")) finalText else respondToExtraction(user),
                    promptTokens = 100,
                    completionTokens = 20,
                    cachedTokens = 30
                )
            }
        )
        val success = run.service.compress(request()) as VolumeCompressionOutcome.Success
        val total = run.requests.size
        assertEquals(total, success.usage.requestCount)
        assertEquals(total * 100, success.usage.promptTokens)
        assertEquals(total * 20, success.usage.completionTokens)
        assertEquals(total * 30, success.usage.cachedTokens)
        assertEquals(total, success.usage.requestsWithUsage)
        // 每批的 usage 独立保留，供 F5 持久化与单批重试
        assertEquals(1, success.batchResults.sumOf { it.usage.requestCount })
        assertTrue(success.batchResults.all { it.rawContent.isNotBlank() })
    }

    @Test
    fun `无 usage 返回时只统计请求数不伪造缓存命中`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "第一章正文")),
            onChat = defaultChat()
        )
        val success = run.service.compress(request()) as VolumeCompressionOutcome.Success
        assertEquals(2, success.usage.requestCount)
        assertEquals(0, success.usage.totalTokens)
        assertEquals(0, success.usage.cachedTokens)
        assertEquals(0, success.usage.requestsWithUsage)
    }

    @Test
    fun `候选写入被拒绝时返回明确失败`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "第一章正文")),
            save = { null },
            onChat = defaultChat()
        )
        val failed = run.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertEquals(CompressionFailure.CandidateSaveRejected, failed.failure)
        assertTrue(failed.batchResults.isNotEmpty())
    }
}
