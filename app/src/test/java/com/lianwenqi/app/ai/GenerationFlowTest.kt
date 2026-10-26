package com.lianwenqi.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生成流程的纯逻辑：弹窗「一次性收集」顺序 */
class GenerationFlowTest {

    @Test
    fun contentDialogOrder_collectsSummaryOutlinesBeforeExecuting() {
        // 同时生成梗概 + 限定梗概范围 → 需要选梗概参考标签
        assertEquals(
            listOf(GenerationDialogKind.PICK_SUMMARY_OUTLINES),
            contentDialogOrder(GenerationMode.CONTENT_WITH_SUMMARY, limitSummaryScope = true)
        )
        // 不限定梗概范围 → 无需弹窗
        assertTrue(contentDialogOrder(GenerationMode.CONTENT_WITH_SUMMARY, limitSummaryScope = false).isEmpty())
        // 纯正文模式 → 限定范围对它无意义
        assertTrue(contentDialogOrder(GenerationMode.CONTENT_ONLY, limitSummaryScope = true).isEmpty())
        assertTrue(contentDialogOrder(GenerationMode.CONTENT_ONLY, limitSummaryScope = false).isEmpty())
        // 只生成梗概 → 无需弹窗
        assertTrue(contentDialogOrder(GenerationMode.SUMMARY_ONLY, limitSummaryScope = true).isEmpty())
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
