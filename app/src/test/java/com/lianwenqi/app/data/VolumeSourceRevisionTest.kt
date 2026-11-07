package com.lianwenqi.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷梗概源指纹规则：相同输入稳定、格式差异被归一化、真正的输入变化必须敏感。
 */
class VolumeSourceRevisionTest {

    private fun entries(vararg pairs: Pair<Long, String>) =
        pairs.map { VolumeSourceRevision.Entry(it.first, VolumeSourceRevision.contentHash(it.second)) }

    @Test
    fun 相同输入得到相同指纹() {
        val a = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "第一章梗概", 2L to "第二章梗概"))
        val b = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "第一章梗概", 2L to "第二章梗概"))
        assertEquals(a, b)
    }

    @Test
    fun 换行符与行尾空白差异不影响指纹() {
        val crlf = VolumeSourceRevision.contentHash("第一段  \r\n第二段\t\r\n")
        val lf = VolumeSourceRevision.contentHash("第一段\n第二段")
        assertEquals(lf, crlf)
    }

    @Test
    fun 连续空行与首尾空行被归一化() {
        val messy = VolumeSourceRevision.contentHash("\n\n第一段\n\n\n\n第二段\n\n\n")
        val clean = VolumeSourceRevision.contentHash("第一段\n\n第二段")
        assertEquals(clean, messy)
        assertEquals("第一段\n\n第二段", VolumeSourceRevision.normalize("\n\n第一段\n\n\n\n第二段\n\n\n"))
    }

    @Test
    fun 内容变化会改变指纹() {
        val before = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "旧梗概"))
        val after = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "新梗概"))
        assertNotEquals(before, after)
    }

    @Test
    fun 章节调序会改变指纹() {
        val ordered = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "甲", 2L to "乙"))
        val reversed = VolumeSourceRevision.revision("SYNOPSIS", entries(2L to "乙", 1L to "甲"))
        assertNotEquals(ordered, reversed)
    }

    @Test
    fun 章节增删会改变指纹() {
        val two = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "甲", 2L to "乙"))
        val one = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "甲"))
        val three = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "甲", 2L to "乙", 3L to "丙"))
        assertNotEquals(two, one)
        assertNotEquals(two, three)
    }

    @Test
    fun 输入来源切换会改变指纹() {
        val synopsis = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "同一段文字"))
        val content = VolumeSourceRevision.revision("CONTENT", entries(1L to "同一段文字"))
        assertNotEquals(synopsis, content)
    }

    @Test
    fun 输入串带版本号便于规则升级时整体失效() {
        val payload = VolumeSourceRevision.payload("CONTENT", entries(7L to "正文"))
        assertTrue(payload.startsWith("v1|CONTENT|7:"))
    }

    @Test
    fun 章节缺失占位也会改变指纹() {
        val present = VolumeSourceRevision.revision("SYNOPSIS", entries(1L to "甲"))
        val missing = VolumeSourceRevision.revision(
            "SYNOPSIS",
            listOf(VolumeSourceRevision.Entry(1L, VolumeSourceRevision.MISSING_HASH))
        )
        assertNotEquals(present, missing)
    }
}
