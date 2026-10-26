package com.lianwenqi.app.data.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 文风样本的格式判定、排序、可用性与文本清洗（纯 JVM 测试） */
class StyleSampleTest {

    private fun sample(
        key: String,
        order: Int,
        status: StyleSampleStatus = StyleSampleStatus.SUCCESS,
        charCount: Int = 200,
        format: StyleSampleFormat = StyleSampleFormat.TXT
    ) = StyleSample(
        key = key,
        uri = "file:///tmp/$key.txt",
        fileName = "$key.txt",
        format = format,
        order = order,
        status = status,
        text = "文".repeat(charCount),
        charCount = charCount
    )

    @Test
    fun `按扩展名判定格式并忽略大小写`() {
        assertEquals(StyleSampleFormat.TXT, detectStyleSampleFormat("a.TXT"))
        assertEquals(StyleSampleFormat.EPUB, detectStyleSampleFormat("b.Epub"))
        assertEquals(StyleSampleFormat.DOCX, detectStyleSampleFormat("c.docx"))
    }

    @Test
    fun `无扩展名时用 MIME 兜底`() {
        assertEquals(
            StyleSampleFormat.TXT,
            detectStyleSampleFormat("无扩展名", "text/plain")
        )
        assertEquals(
            StyleSampleFormat.EPUB,
            detectStyleSampleFormat("无扩展名", "application/epub+zip")
        )
    }

    @Test
    fun `无法判定的格式返回未知`() {
        assertEquals(StyleSampleFormat.UNKNOWN, detectStyleSampleFormat("a.pdf"))
        assertEquals(StyleSampleFormat.UNKNOWN, detectStyleSampleFormat("a.azw3", "application/octet-stream"))
    }

    @Test
    fun `移除后序号连续`() {
        val list = listOf(sample("a", 1), sample("b", 2), sample("c", 3))
            .filterNot { it.key == "b" }
            .renumbered()
        assertEquals(listOf(1, 2), list.map { it.order })
        assertEquals(listOf("a", "c"), list.map { it.key })
    }

    @Test
    fun `分析输入按顺序排列且只包含可用样本`() {
        val list = listOf(
            sample("c", 3),
            sample("a", 1),
            sample("b", 2, status = StyleSampleStatus.FAILED, charCount = 0),
            sample("short", 4, charCount = StyleSample.MIN_ANALYSIS_CHARS - 1)
        )
        val input = list.toAnalysisInput()
        assertEquals(listOf("a", "c"), input.map { it.fileName.substringBefore('.') })
        assertEquals(listOf(1, 3), input.map { it.order })
    }

    @Test
    fun `单个样本解析失败不影响其他成功样本`() {
        val list = listOf(
            sample("ok1", 1),
            sample("bad", 2, status = StyleSampleStatus.FAILED, charCount = 0),
            sample("ok2", 3)
        )

        // 失败样本仍留在列表中并保留错误状态，成功样本与其顺序不受影响
        assertEquals(3, list.size)
        assertEquals(StyleSampleStatus.FAILED, list[1].status)
        assertEquals(listOf(1, 2, 3), list.map { it.order })
        assertEquals(listOf("ok1", "ok2"), list.toAnalysisInput().map { it.fileName.substringBefore('.') })
    }

    @Test
    fun `可用性与过短判断以最低字符数为界`() {
        assertTrue(sample("ok", 1, charCount = StyleSample.MIN_ANALYSIS_CHARS).isUsable)
        assertFalse(sample("ok", 1, charCount = StyleSample.MIN_ANALYSIS_CHARS).isTooShort)
        assertTrue(sample("short", 1, charCount = StyleSample.MIN_ANALYSIS_CHARS - 1).isTooShort)
        assertFalse(sample("short", 1, charCount = StyleSample.MIN_ANALYSIS_CHARS - 1).isUsable)
        assertFalse(sample("failed", 1, status = StyleSampleStatus.FAILED, charCount = 0).isUsable)
    }

    @Test
    fun `清洗统一换行并去掉控制字符与多余空行`() {
        val raw = "第一段\r\n\r\n\r\n第二段\u0000\u0007\n\u200B第三段\n\n"
        assertEquals("第一段\n\n第二段\n第三段", StyleTextNormalizer.normalize(raw))
    }

    @Test
    fun `清洗保留制表符与段落结构`() {
        val raw = "标题\t副标题\n\n  正文内容   \n"
        assertEquals("标题\t副标题\n\n  正文内容", StyleTextNormalizer.normalize(raw))
    }

    @Test
    fun `空白文本清洗后为空`() {
        assertEquals("", StyleTextNormalizer.normalize(" \r\n\t\n\uFEFF"))
    }
}
