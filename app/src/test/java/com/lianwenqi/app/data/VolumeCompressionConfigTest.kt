package com.lianwenqi.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩设置（F4/F8）：三种压缩依据、大纲更新三态、范围选择在关闭后保留，
 * 以及设置字符串损坏 / 旧格式时不能阻塞压缩入口。
 */
class VolumeCompressionConfigTest {

    @Test
    fun 默认配置不自动压缩且大纲更新关闭() {
        val config = VolumeCompressionConfig.DEFAULT
        assertEquals(CompressionSourceMode.ASK, config.source)
        assertFalse(config.autoCompressWholeVolumeSummary)
        assertFalse(config.outlineUpdateEnabled)
    }

    @Test
    fun 压缩依据与大纲更新三态可持久化() {
        val config = VolumeCompressionConfig(
            source = CompressionSourceMode.CONTENT,
            autoCompressWholeVolumeSummary = true,
            maxOutputTokens = 16384,
            outlineUpdateMode = CompressionOutlineUpdateMode.ASK,
            outlineScopeMode = CompressionOutlineScopeMode.UPDATABLE
        )
        val decoded = VolumeCompressionConfig.decode(VolumeCompressionConfig.encode(config))
        assertEquals(CompressionSourceMode.CONTENT, decoded.source)
        assertTrue(decoded.autoCompressWholeVolumeSummary)
        assertEquals(16384, decoded.maxOutputTokens)
        assertEquals(CompressionOutlineUpdateMode.ASK, decoded.outlineUpdateMode)
        assertEquals(CompressionOutlineScopeMode.UPDATABLE, decoded.outlineScopeMode)
    }

    @Test
    fun 关闭大纲更新后范围仍保留() {
        val config = VolumeCompressionConfig(
            outlineUpdateMode = CompressionOutlineUpdateMode.OFF,
            outlineScopeMode = CompressionOutlineScopeMode.UPDATABLE
        )
        val decoded = VolumeCompressionConfig.decode(VolumeCompressionConfig.encode(config))
        assertFalse(decoded.outlineUpdateEnabled)
        assertEquals(CompressionOutlineScopeMode.UPDATABLE, decoded.outlineScopeMode)
    }

    @Test
    fun 空字符串与损坏的JSON回落到默认值() {
        assertEquals(VolumeCompressionConfig.DEFAULT, VolumeCompressionConfig.decode(""))
        assertEquals(VolumeCompressionConfig.DEFAULT, VolumeCompressionConfig.decode("   "))
        assertEquals(VolumeCompressionConfig.DEFAULT, VolumeCompressionConfig.decode("{not json"))
        assertEquals(VolumeCompressionConfig.DEFAULT, VolumeCompressionConfig.decode("[1,2,3]"))
    }

    @Test
    fun 旧格式缺字段时按默认补齐并容忍未知字段() {
        val decoded = VolumeCompressionConfig.decode(
            """{"source":"CONTENT","futureField":123}"""
        )
        assertEquals(CompressionSourceMode.CONTENT, decoded.source)
        assertFalse(decoded.autoCompressWholeVolumeSummary)
        assertEquals(0, decoded.maxOutputTokens)
        assertEquals(CompressionOutlineUpdateMode.OFF, decoded.outlineUpdateMode)
        assertEquals(CompressionOutlineScopeMode.WHOLE_BOOK, decoded.outlineScopeMode)
    }

    @Test
    fun 非法枚举值被归一化() {
        val decoded = VolumeCompressionConfig.decode(
            """{"source":"WHATEVER","outlineUpdateMode":"MAYBE","outlineScopeMode":"ALL"}"""
        )
        assertEquals(CompressionSourceMode.SYNOPSIS, decoded.source)
        assertEquals(CompressionOutlineUpdateMode.OFF, decoded.outlineUpdateMode)
        assertEquals(CompressionOutlineScopeMode.WHOLE_BOOK, decoded.outlineScopeMode)
    }

    @Test
    fun 已删除的自定义范围回落到全部大纲() {
        val decoded = VolumeCompressionConfig.decode(
            """{"outlineScopeMode":"CUSTOM","scope":{"pinnedAll":false,"pinnedIds":[1,2]}}"""
        )
        assertEquals(CompressionOutlineScopeMode.WHOLE_BOOK, decoded.outlineScopeMode)
    }

    @Test
    fun 本次实际来源在ASK时取弹窗选择其余情况取配置() {
        val ask = VolumeCompressionConfig(source = CompressionSourceMode.ASK)
        assertEquals(CompressionSourceMode.CONTENT, ask.resolvedSource(CompressionSourceMode.CONTENT))
        assertEquals(CompressionSourceMode.SYNOPSIS, ask.resolvedSource("invalid"))

        val fixed = VolumeCompressionConfig(source = CompressionSourceMode.SYNOPSIS)
        assertEquals(CompressionSourceMode.SYNOPSIS, fixed.resolvedSource(CompressionSourceMode.CONTENT))
    }

    @Test
    fun 大纲更新开关只受更新模式影响() {
        fun enabled(mode: String) = VolumeCompressionConfig(outlineUpdateMode = mode).outlineUpdateEnabled
        assertTrue(enabled(CompressionOutlineUpdateMode.ON))
        assertTrue(enabled(CompressionOutlineUpdateMode.ASK))
        assertFalse(enabled(CompressionOutlineUpdateMode.OFF))
        assertFalse(enabled("unknown"))
    }

    @Test
    fun 临时配置快照不反向修改全局默认值() {
        val global = VolumeCompressionConfig(source = CompressionSourceMode.SYNOPSIS)
        val snapshot = global.copy(
            source = CompressionSourceMode.CONTENT,
            autoCompressWholeVolumeSummary = true,
            outlineUpdateMode = CompressionOutlineUpdateMode.ON
        )
        assertEquals(CompressionSourceMode.SYNOPSIS, global.source)
        assertFalse(global.autoCompressWholeVolumeSummary)
        assertFalse(global.outlineUpdateEnabled)
        assertEquals(VolumeCompressionConfig.encode(global), VolumeCompressionConfig.encode(global.normalized()))
        assertTrue(snapshot.autoCompressWholeVolumeSummary)
    }
}
