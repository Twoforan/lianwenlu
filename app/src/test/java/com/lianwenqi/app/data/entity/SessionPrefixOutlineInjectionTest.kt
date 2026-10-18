package com.lianwenqi.app.data.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 生成梗概「限定范围」用的去重判定：复用会话前缀里的已注入集合，不重复提交完整内容 */
class SessionPrefixOutlineInjectionTest {

    @Test
    fun outlineInjected_trueWhenAlreadyInjectedByAdaptiveFilter() {
        val sp = SessionPrefix(autoOutlineIds = listOf(7L))
        assertTrue(sp.outlineInjected(7L, outlinePrefixOn = false))
    }

    @Test
    fun outlineInjected_trueWhenAppendedToHistoryTail() {
        val sp = SessionPrefix(tailOutlines = listOf(9L))
        assertTrue(sp.outlineInjected(9L, outlinePrefixOn = false))
    }

    @Test
    fun outlineInjected_trueOnlyWhenOutlinePrefixOnForPresetOutlines() {
        val sp = SessionPrefix(outlines = listOf(PrefixEntry(11L, "｛主角｝\n定位：主角")))
        assertTrue(sp.outlineInjected(11L, outlinePrefixOn = true))
        assertFalse(sp.outlineInjected(11L, outlinePrefixOn = false))
    }

    @Test
    fun outlineInjected_falseForUnknownId() {
        val sp = SessionPrefix(autoOutlineIds = listOf(7L), outlines = listOf(PrefixEntry(11L, "文本")))
        assertFalse(sp.outlineInjected(3L, outlinePrefixOn = true))
    }

    @Test
    fun markOutlinesInjected_appendsOnlyNewAndKeepsOrder() {
        val sp = SessionPrefix(autoOutlineIds = listOf(2L))
        val marked = sp.markOutlinesInjected(listOf(2L, 5L, 5L))
        assertEquals(listOf(2L, 5L), marked.autoOutlineIds)
        // 幂等：重复标记不再追加
        assertEquals(listOf(2L, 5L), marked.markOutlinesInjected(listOf(2L, 5L)).autoOutlineIds)
    }

    @Test
    fun markOutlinesInjected_doesNotTouchCheckedOrTail() {
        val sp = SessionPrefix(checkedOutlines = listOf(4L), tailOutlines = listOf(6L))
        val marked = sp.markOutlinesInjected(listOf(8L))
        assertEquals(listOf(4L), marked.checkedOutlines)
        assertEquals(listOf(6L), marked.tailOutlines)
    }

    @Test
    fun encodeRoundTrip_keepsInjectionSet() {
        val sp = SessionPrefix(autoOutlineIds = listOf(1L, 2L), autoMemoryKeys = listOf("h1"))
        val back = SessionPrefix.decode(sp.encode())
        assertEquals(listOf(1L, 2L), back.autoOutlineIds)
        assertEquals(listOf("h1"), back.autoMemoryKeys)
    }
}
