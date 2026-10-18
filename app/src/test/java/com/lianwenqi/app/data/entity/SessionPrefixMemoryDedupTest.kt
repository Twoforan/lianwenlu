package com.lianwenqi.app.data.entity

import com.lianwenqi.app.util.shortHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话前缀的长期记忆片段去重与旧数据兼容 */
class SessionPrefixMemoryDedupTest {

    @Test
    fun freshMemoryHits_skipsSnippetsAlreadySubmitted() {
        val sent = "一段已经提交过的正文"
        val prefix = SessionPrefix(autoMemoryKeys = listOf(shortHash(sent)))
        val fresh = prefix.freshMemoryHits(listOf(sent, "一段全新的正文"))
        assertEquals(listOf("一段全新的正文"), fresh)
    }

    @Test
    fun freshMemoryHits_keepsAllWhenNothingSubmittedYet() {
        val hits = listOf("片段甲", "片段乙")
        assertEquals(hits, SessionPrefix().freshMemoryHits(hits))
    }

    @Test
    fun decode_legacyPrefixWithoutMemoryKeys_usesEmptyDefault() {
        // 旧版本写入的会话前缀里没有 autoMemoryKeys，升级后必须能正常解析且不丢其它字段
        val legacy = """{"custom":"前缀","initialized":true,"autoOutlineIds":[7]}"""
        val sp = SessionPrefix.decode(legacy)
        assertEquals("前缀", sp.custom)
        assertTrue(sp.initialized)
        assertEquals(listOf(7L), sp.autoOutlineIds)
        assertTrue(sp.autoMemoryKeys.isEmpty())
    }

    @Test
    fun encodeDecode_roundTripKeepsMemoryKeys() {
        val sp = SessionPrefix(autoMemoryKeys = listOf(shortHash("正文块")))
        assertEquals(sp, SessionPrefix.decode(sp.encode()))
    }
}
