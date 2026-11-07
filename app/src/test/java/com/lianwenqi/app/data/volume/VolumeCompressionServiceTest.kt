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
 * 统一压缩引擎的梗概来源链路（全部使用手写 fake，不访问网络 / 数据库）。
 * 真实模型输出验收在模拟器上手动执行。
 */
class VolumeCompressionServiceTest {

    private val bookId = 1L
    private val volumeId = 2L
    private val modelId = 7L

    private val finalText = "本卷围绕主角的抉择展开，关键人物关系发生转折，伏笔在卷末得到回收。"

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
        summary: String,
        title: String = "第${order}章"
    ) = Chapter(
        id = id,
        bookId = bookId,
        volumeId = volumeId,
        title = title,
        summary = summary,
        content = "正文$id",
        sortOrder = order
    )

    private fun extractionJson(
        ids: List<Long>,
        label: String,
        eventCount: Int = 1,
        detailCount: Int = 0,
        changeCount: Int = 1,
        unresolvedCount: Int = 0
    ): String {
        val events = (1..eventCount).joinToString(",") { "\"重大事件$it：主角做出关键选择并推动主线\"" }
        val details = (1..detailCount).joinToString(",") { "\"重要细节$it：人物状态与关系出现可追踪的变化\"" }
        val changes = (1..changeCount).joinToString(",") { "\"人物变化$it：立场与关系发生转变\"" }
        val unresolved = (1..unresolvedCount).joinToString(",") { "\"未解决事项$it：仍待后文回收\"" }
        return buildString {
            append("{\"chapterRange\":\"").append(label).append("\",")
            append("\"sourceChapterIds\":[").append(ids.joinToString(",")).append("],")
            append("\"events\":[").append(events).append("],")
            append("\"participants\":[\"主角\",\"配角\"],")
            append("\"cause\":\"起因\",\"result\":\"结果\",")
            append("\"characterChanges\":[").append(changes).append("],")
            append("\"relationshipChanges\":[],")
            append("\"foreshadowing\":[],")
            append("\"unresolvedIssues\":[").append(unresolved).append("],")
            append("\"importantDetails\":[").append(details).append("],")
            append("\"subplots\":[]}")
        }
    }

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

    private class TinyPlanner(
        private val finalTarget: Int,
        private val intermediateTarget: Int
    ) : CompressionLengthPlanner {
        override fun plan(
            complexity: CompressionComplexity,
            maxOutputTokens: Int,
            contextWindow: Int
        ): Int = finalTarget

        override fun intermediateTarget(finalTarget: Int): Int = intermediateTarget
    }

    private class TestRun(
        val service: VolumeCompressionService,
        val requests: MutableList<AiRequest>,
        val saved: MutableList<CompressionCandidate>
    )

    private fun run(
        chapters: List<Chapter>,
        model: AiModel? = model(),
        batcher: CompressionBatcher = TokenBudgetBatcher(),
        planner: CompressionLengthPlanner = DefaultCompressionLengthPlanner,
        save: suspend (CompressionCandidate) -> Long? = { 42L },
        onChat: (Int, AiModel, AiRequest) -> AiResult
    ): TestRun {
        val requests = mutableListOf<AiRequest>()
        val saved = mutableListOf<CompressionCandidate>()
        val service = VolumeCompressionService(
            chapterSource = SynopsisChapterSourceAdapter { _, _ -> chapters },
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
        sourceType: String = VolumeSummarySourceType.SYNOPSIS,
        contextWindowOverride: Int = 0,
        maxInputChars: Int = 0,
        targetOutputChars: Int = 0
    ) = VolumeCompressionRequest(
        bookId = bookId,
        volumeId = volumeId,
        modelId = modelId,
        sourceType = sourceType,
        contextWindowOverride = contextWindowOverride,
        maxInputChars = maxInputChars,
        targetOutputChars = targetOutputChars
    )

    /** 单批：先返回提取 JSON，再返回最终梗概 */
    private fun singleBatchChat(
        ids: List<Long> = listOf(11L, 10L),
        label: String = "第1-2章"
    ): (Int, AiModel, AiRequest) -> AiResult = { index, _, _ ->
        if (index == 0) AiResult.ok(extractionJson(ids, label)) else AiResult.ok(finalText)
    }

    @Test
    fun `梗概来源按目录顺序筛选并保留缺失章节审计`() = runBlocking {
        val run = run(
            chapters = listOf(
                chapter(10L, 2, "第二段梗概"),
                chapter(11L, 1, "第一段梗概"),
                chapter(12L, 3, "   ")
            ),
            onChat = singleBatchChat()
        )
        val outcome = run.service.compress(request())
        assertTrue(outcome is VolumeCompressionOutcome.Success)
        val success = outcome as VolumeCompressionOutcome.Success

        assertEquals(3, success.audit.totalChapterCount)
        assertEquals(2, success.audit.includedChapterCount)
        assertEquals(1, success.audit.missingChapterCount)
        assertEquals(listOf(12L), success.audit.missingChapterIds)
        assertEquals(listOf(11L, 10L), success.audit.includedChapterIds)
        assertTrue(success.audit.sourceRevision.isNotBlank())
        assertEquals(1, success.plan.batchCount)

        // 顺序必须是 sortOrder 后的 11 → 10，缺失的 12 不进入提取
        val extractionPrompt = run.requests.first().turns.first().content
        assertTrue(extractionPrompt.indexOf("id=11") < extractionPrompt.indexOf("id=10"))
        assertTrue(!extractionPrompt.contains("id=12"))

        assertEquals(42L, success.candidateId)
        assertEquals(listOf(11L, 10L), run.saved.single().chapterIds)
    }

    @Test
    fun `没有任何有效输入时不发起网络请求`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, " "), chapter(2L, 2, "")),
            onChat = { _, _, _ -> AiResult.ok(finalText) }
        )
        val outcome = run.service.compress(request())
        assertTrue(outcome is VolumeCompressionOutcome.NoInput)
        assertEquals(0, run.requests.size)
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `多批路径处理全部有效章节且整合不遗漏首末批`() = runBlocking {
        val chapters = (1..4).map { chapter(100L + it, it, "第${it}章梗概内容") }
        val run = run(
            chapters = chapters,
            batcher = OneChapterBatcher(),
            onChat = { index, _, _ ->
                if (index < 4) {
                    val id = 101L + index
                    AiResult.ok(extractionJson(listOf(id), "第${index + 1}章"))
                } else {
                    AiResult.ok(finalText)
                }
            }
        )
        val outcome = run.service.compress(request())
        assertTrue(outcome is VolumeCompressionOutcome.Success)
        val success = outcome as VolumeCompressionOutcome.Success
        assertEquals(4, success.plan.batchCount)
        assertEquals(5, run.requests.size)

        val finalPrompt = run.requests.last().turns.first().content
        assertTrue(finalPrompt.contains("101"))
        assertTrue(finalPrompt.contains("104"))
        assertTrue(finalPrompt.contains("最终全卷整合"))
        assertEquals(finalText, success.content)
    }

    @Test
    fun `模型配置缺失时在请求前失败`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            model = null,
            onChat = { _, _, _ -> AiResult.ok(finalText) }
        )
        val outcome = run.service.compress(request())
        assertTrue(outcome is VolumeCompressionOutcome.Failed)
        assertEquals(
            CompressionFailure.ModelConfigMissing,
            (outcome as VolumeCompressionOutcome.Failed).failure
        )
        assertEquals(0, run.requests.size)
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `上下文窗口未知时按默认单次提交字符数继续压缩`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            model = model(contextWindow = 0),
            onChat = singleBatchChat(ids = listOf(1L), label = "第1章")
        )
        val outcome = run.service.compress(request())
        assertTrue(outcome is VolumeCompressionOutcome.Success)
    }

    @Test
    fun `临时上下文覆盖值可以解除未知窗口阻塞`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            model = model(contextWindow = 0),
            onChat = singleBatchChat(ids = listOf(1L), label = "第1章")
        )
        val outcome = run.service.compress(request(maxInputChars = 32000))
        assertTrue(outcome is VolumeCompressionOutcome.Success)
    }

    @Test
    fun `输出字数只作为提示词里的篇幅约束而不限制 max_tokens`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = singleBatchChat(ids = listOf(1L), label = "第1章")
        )
        val outcome = run.service.compress(request(targetOutputChars = 1200))
        assertTrue(outcome is VolumeCompressionOutcome.Success)
        val finalRequest = run.requests.last()
        assertEquals(4096, finalRequest.maxTokens)
        assertTrue(finalRequest.turns.first().content.contains("必须约 1200 字"))
    }

    @Test
    fun `输出字数超过模型上限时目标篇幅按模型上限收敛`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            model = model(maxTokens = 2048),
            onChat = singleBatchChat(ids = listOf(1L), label = "第1章")
        )
        val outcome = run.service.compress(request(targetOutputChars = 8000))
        assertTrue(outcome is VolumeCompressionOutcome.Success)
        assertEquals(2048, run.requests.last().maxTokens)
        assertTrue(run.requests.last().turns.first().content.contains("必须约 2048 字"))
    }

    @Test
    fun `模型输出不限时压缩请求不携带输出上限`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            model = model(maxTokens = 0),
            onChat = singleBatchChat(ids = listOf(1L), label = "第1章")
        )
        val outcome = run.service.compress(request(targetOutputChars = 1200))
        assertTrue(outcome is VolumeCompressionOutcome.Success)
        assertTrue(run.requests.isNotEmpty())
        assertEquals(0, run.requests.first().maxTokens)
        assertEquals(0, run.requests.last().maxTokens)
    }

    @Test
    fun `请求失败返回可区分失败且不写候选`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { _, _, _ -> AiResult.fail(AiError.Timeout()) }
        )
        val outcome = run.service.compress(request())
        assertTrue(outcome is VolumeCompressionOutcome.Failed)
        val failed = outcome as VolumeCompressionOutcome.Failed
        assertTrue(failed.failure is CompressionFailure.RequestFailed)
        assertEquals(1, failed.usage.requestCount)
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `空输出和畸形 JSON 分别归类`() = runBlocking {
        val emptyRun = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { _, _, _ -> AiResult.ok("") }
        )
        val empty = emptyRun.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertEquals(CompressionStage.EXTRACTION, (empty.failure as CompressionFailure.EmptyOutput).stage)

        val malformedRun = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { _, _, _ -> AiResult.ok("""{"chapterRange":1}""") }
        )
        val malformed = malformedRun.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertTrue(malformed.failure is CompressionFailure.InvalidJson)
        assertTrue(malformedRun.saved.isEmpty())
    }

    @Test
    fun `未闭合 JSON 命中输出上限时判为截断`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { _, _, request ->
                AiResult.ok(
                    "{\"chapterRange\":\"第1章\",\"sourceChapterIds\":[1],\"events\":[\"",
                    completionTokens = request.maxTokens
                )
            }
        )
        val failed = run.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertEquals(
            CompressionStage.EXTRACTION,
            (failed.failure as CompressionFailure.TruncatedOutput).stage
        )
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `章节 id 被模型写成字符串时仍可解析`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { index, _, _ ->
                if (index == 0) {
                    AiResult.ok(
                        extractionJson(ids = listOf(1L), label = "第1章")
                            .replace("\"sourceChapterIds\":[1]", "\"sourceChapterIds\":[\"1\"]")
                    )
                } else {
                    AiResult.ok(finalText)
                }
            }
        )
        val outcome = run.service.compress(request())
        assertTrue(outcome is VolumeCompressionOutcome.Success)
    }

    @Test
    fun `协程取消原样抛出且不提交候选`() = runBlocking {
        val progress = mutableListOf<CompressionProgress>()
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { _, _, _ -> throw CancellationException("用户取消") }
        )
        val thrown = try {
            run.service.compress(request()) { progress += it }
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull(thrown)
        assertTrue(progress.any { it === CompressionProgress.Cancelled })
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `usage 按真实返回值汇总且无 usage 时不虚报`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { index, _, _ ->
                if (index == 0) {
                    AiResult.ok(
                        extractionJson(listOf(1L), "第1章"),
                        promptTokens = 100,
                        completionTokens = 20,
                        cachedTokens = 30
                    )
                } else {
                    AiResult.ok(finalText, promptTokens = 200, completionTokens = 50, cachedTokens = 60)
                }
            }
        )
        val success = run.service.compress(request()) as VolumeCompressionOutcome.Success
        assertEquals(300, success.usage.promptTokens)
        assertEquals(70, success.usage.completionTokens)
        assertEquals(90, success.usage.cachedTokens)
        assertEquals(2, success.usage.requestCount)
        assertEquals(2, success.usage.requestsWithUsage)

        val noUsageRun = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { index, _, _ ->
                if (index == 0) AiResult.ok(extractionJson(listOf(1L), "第1章")) else AiResult.ok(finalText)
            }
        )
        val noUsage = noUsageRun.service.compress(request()) as VolumeCompressionOutcome.Success
        assertEquals(0, noUsage.usage.totalTokens)
        assertEquals(0, noUsage.usage.requestsWithUsage)
        assertEquals(2, noUsage.usage.requestCount)
    }

    @Test
    fun `超限时走分级整合且最终仍覆盖首末章节`() = runBlocking {
        val chapters = (1..6).map { chapter(100L + it, it, "第${it}章梗概内容".repeat(20)) }
        val batches = 6
        val run = run(
            chapters = chapters,
            model = model(contextWindow = 4000, maxTokens = 1024),
            batcher = OneChapterBatcher(),
            planner = TinyPlanner(finalTarget = 512, intermediateTarget = 200),
            onChat = { index, _, request ->
                if (index < batches) {
                    val id = 101L + index
                    AiResult.ok(
                        extractionJson(
                            ids = listOf(id),
                            label = "第${index + 1}章",
                            eventCount = 20,
                            detailCount = 10,
                            changeCount = 5,
                            unresolvedCount = 5
                        )
                    )
                } else {
                    AiResult.ok("阶段整合结果，保留所有重大事件、人物变化与未解决事项。" + finalText)
                }
            }
        )
        val outcome = run.service.compress(request(maxInputChars = 4000))
        assertTrue(outcome is VolumeCompressionOutcome.Success)
        val success = outcome as VolumeCompressionOutcome.Success

        assertTrue(run.requests.size > batches + 1)
        assertEquals(batches, success.batchResults.size)
        val finalPrompt = run.requests.last().turns.first().content
        assertTrue(finalPrompt.contains("最终全卷整合"))
        assertTrue(finalPrompt.contains("101"))
        assertTrue(finalPrompt.contains("106"))
        (1..6).forEach { id ->
            assertTrue("最终整合必须覆盖章节 $id", finalPrompt.contains((100 + id).toString()))
        }
        assertTrue(success.content.endsWith(finalText))
        assertEquals(512, success.plan.outputTokenBudget)
    }

    @Test
    fun `整合失败时不把不完整结果当候选`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { index, _, _ ->
                if (index == 0) AiResult.ok(extractionJson(listOf(1L), "第1章"))
                else AiResult.fail(AiError.Server(500, "boom"))
            }
        )
        val failed = run.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertTrue(failed.failure is CompressionFailure.RequestFailed)
        assertEquals(1, failed.batchResults.size)
        assertTrue(run.saved.isEmpty())
    }

    @Test
    fun `来源类型不匹配在读取前失败`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            onChat = { _, _, _ -> AiResult.ok(finalText) }
        )
        val outcome = run.service.compress(request(sourceType = VolumeSummarySourceType.CONTENT))
        assertTrue(outcome is VolumeCompressionOutcome.Failed)
        assertTrue((outcome as VolumeCompressionOutcome.Failed).failure is CompressionFailure.SourceTypeUnsupported)
        assertEquals(0, run.requests.size)
    }

    @Test
    fun `候选写入被拒绝时保留已有批次结果但不返回成功`() = runBlocking {
        val run = run(
            chapters = listOf(chapter(1L, 1, "梗概")),
            save = { null },
            onChat = singleBatchChat(ids = listOf(1L), label = "第1章")
        )
        val failed = run.service.compress(request()) as VolumeCompressionOutcome.Failed
        assertEquals(CompressionFailure.CandidateSaveRejected, failed.failure)
        assertEquals(1, failed.batchResults.size)
        assertEquals(1, run.saved.size)
    }
}
