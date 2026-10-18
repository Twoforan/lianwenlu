package com.lianwenqi.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生成流程的纯逻辑：弹窗「一次性收集」顺序 + 记忆检索三态读写 */
class GenerationFlowTest {

    @Test
    fun contentDialogOrder_sameOrderEveryTime_decisionsCollectedBeforeExecuting() {
        // 同时生成梗概 + 限定梗概范围 + 记忆检索询问 → 两个弹窗都要弹，且顺序固定
        assertEquals(
            listOf(GenerationDialogKind.PICK_SUMMARY_OUTLINES, GenerationDialogKind.ASK_MEMORY),
            contentDialogOrder(GenerationMode.CONTENT_WITH_SUMMARY, limitSummaryScope = true, memoryAsk = true)
        )
        // 不限定梗概范围 → 只剩检索询问
        assertEquals(
            listOf(GenerationDialogKind.ASK_MEMORY),
            contentDialogOrder(GenerationMode.CONTENT_WITH_SUMMARY, limitSummaryScope = false, memoryAsk = true)
        )
        // 已有梗概的纯正文模式 → 限定范围对它无意义，只问检索
        assertEquals(
            listOf(GenerationDialogKind.ASK_MEMORY),
            contentDialogOrder(GenerationMode.CONTENT_ONLY, limitSummaryScope = true, memoryAsk = true)
        )
        // 都不需要 → 无需弹窗，直接执行
        assertTrue(contentDialogOrder(GenerationMode.CONTENT_ONLY, false, memoryAsk = false).isEmpty())
        assertTrue(contentDialogOrder(GenerationMode.SUMMARY_ONLY, true, memoryAsk = true).isEmpty())
    }

    @Test
    fun contentDialogOrder_memoryOnOrOffNeverAsks() {
        assertTrue(contentDialogOrder(GenerationMode.CONTENT_WITH_SUMMARY, true, memoryAsk = false).none {
            it == GenerationDialogKind.ASK_MEMORY
        })
    }

    @Test
    fun retrievalMode_storageRoundTrip() {
        RetrievalMode.entries.forEach { mode ->
            assertEquals(mode, retrievalModeOf(mode.storage))
        }
        assertEquals(RetrievalMode.ON, retrievalModeOf("on"))
        assertEquals(RetrievalMode.OFF, retrievalModeOf("off"))
        assertEquals(RetrievalMode.ASK, retrievalModeOf("ask"))
        // 旧数据 / 脏数据回退为「询问」，保证每次生成前都会问一次
        assertEquals(RetrievalMode.ASK, retrievalModeOf(""))
        assertEquals(RetrievalMode.ASK, retrievalModeOf("unknown"))
    }

    @Test
    fun generationPlan_keepsAllCollectedDecisions() {
        val plan = GenerationPlan(
            mode = GenerationMode.CONTENT_WITH_SUMMARY,
            summaryWordRange = 100..150,
            contentWordRange = 0..6000,
            selectedOutlineIds = listOf(3L, 5L),
            memoryRetrieval = RetrievalMode.ON
        )
        assertEquals(GenerationMode.CONTENT_WITH_SUMMARY, plan.mode)
        assertEquals(100..150, plan.summaryWordRange)
        assertEquals(0..6000, plan.contentWordRange)
        assertEquals(listOf(3L, 5L), plan.selectedOutlineIds)
        assertEquals(RetrievalMode.ON, plan.memoryRetrieval)
    }
}
