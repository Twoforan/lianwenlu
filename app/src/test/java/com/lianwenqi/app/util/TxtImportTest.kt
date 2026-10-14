package com.lianwenqi.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TxtImport] 的 JVM 单元测试：编码检测与分卷分章切分 */
class TxtImportTest {

    // ---------------- 编码检测 ----------------

    @Test
    fun `decodeText utf8 no bom`() {
        val text = "第一章 测试\n中文内容"
        assertEquals(text, TxtImport.decodeText(text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `decodeText utf8 with bom`() {
        val text = "第一章 测试"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, TxtImport.decodeText(bytes))
    }

    @Test
    fun `decodeText gbk fallback`() {
        val text = "第五章 江湖夜雨"
        // GBK 编码的中文文本不是合法 UTF-8，应回退 GB18030 正确解码
        assertEquals(text, TxtImport.decodeText(text.toByteArray(charset("GBK"))))
    }

    @Test
    fun `decodeText utf16le with bom`() {
        val text = "楔子"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(text, TxtImport.decodeText(bytes))
    }

    // ---------------- 章节解析 ----------------

    @Test
    fun `auto parse chinese novel with volumes`() {
        val text = """
            书名：测试之书
            作者：某人

            第一卷 起
            第一章 初入江湖
            少年背剑出门。

            第二章 初逢敌手
            他拔剑。

            第二卷 承
            第三章 再遇故人
            老友重逢。
        """.trimIndent()

        val parsed = TxtImport.parse(text, "测试之书.txt", TxtParseMode.AUTO)

        assertEquals(2, parsed.volumeCount)
        assertEquals(3, parsed.chapterCount)
        val vol1 = parsed.volumes[0]
        val vol2 = parsed.volumes[1]
        assertEquals("第1卷 起", vol1.title)
        assertEquals("第2卷 承", vol2.title)
        // 开头的「书名/作者」两行元信息不足前言阈值，被丢弃
        assertEquals("第1章 初入江湖", vol1.chapters[0].title)
        assertEquals("第2章 初逢敌手", vol1.chapters[1].title)
        assertEquals("第3章 再遇故人", vol2.chapters[0].title)
        assertEquals("少年背剑出门。", vol1.chapters[0].content)
    }

    @Test
    fun `auto parse chinese numerals normalized to arabic`() {
        val text = """
            第一章 开端
            内容一。

            第一百二十三章 大结局
            内容二。
        """.trimIndent()

        val parsed = TxtImport.parse(text, "book.txt", TxtParseMode.AUTO)

        assertEquals(1, parsed.volumeCount)
        assertEquals(2, parsed.chapterCount)
        assertEquals("第1章 开端", parsed.volumes[0].chapters[0].title)
        assertEquals("第123章 大结局", parsed.volumes[0].chapters[1].title)
    }

    @Test
    fun `auto parse no-space chapter title`() {
        val text = "第一章试炼\n正文内容。"
        val parsed = TxtImport.parse(text, "b.txt", TxtParseMode.AUTO)
        assertEquals(1, parsed.chapterCount)
        assertEquals("第1章 试炼", parsed.volumes[0].chapters[0].title)
    }

    @Test
    fun `auto parse english chapters`() {
        val text = """
            Chapter 1 The Beginning
            It begins.

            Chapter 2 The Middle
            It continues.
        """.trimIndent()

        val parsed = TxtImport.parse(text, "en.txt", TxtParseMode.AUTO)

        assertEquals(2, parsed.chapterCount)
        assertEquals("Chapter 1 The Beginning", parsed.volumes[0].chapters[0].title)
        assertEquals("Chapter 2 The Middle", parsed.volumes[0].chapters[1].title)
    }

    @Test
    fun `auto parse numbered chapters`() {
        val text = """
            1、开端
            内容一。

            2. 转折
            内容二。
        """.trimIndent()

        val parsed = TxtImport.parse(text, "num.txt", TxtParseMode.AUTO)

        assertEquals(2, parsed.chapterCount)
        assertEquals("1、开端", parsed.volumes[0].chapters[0].title)
        assertEquals("2、转折", parsed.volumes[0].chapters[1].title)
    }

    @Test
    fun `auto parse special chapters`() {
        val text = """
            楔子
            开篇引言。

            序章
            正式开始。

            第一章 正文开始
            内容。

            番外一
            番外内容。
        """.trimIndent()

        val parsed = TxtImport.parse(text, "sp.txt", TxtParseMode.AUTO)

        assertEquals(4, parsed.chapterCount)
        assertEquals("楔子", parsed.volumes[0].chapters[0].title)
        assertEquals("序章", parsed.volumes[0].chapters[1].title)
        assertEquals("番外一", parsed.volumes[0].chapters[3].title)
    }

    @Test
    fun `auto parse no structure single chapter`() {
        val text = "只是一段没有章节结构的散文字。这里没有标题。"
        val parsed = TxtImport.parse(text, "plain.txt", TxtParseMode.AUTO)
        assertEquals(1, parsed.volumeCount)
        assertEquals(1, parsed.chapterCount)
        assertEquals("全文", parsed.volumes[0].chapters[0].title)
        assertEquals(text, parsed.volumes[0].chapters[0].content)
    }

    @Test
    fun `none mode single chapter`() {
        val text = "第一章 不会切分\n正文。"
        val parsed = TxtImport.parse(text, "b.txt", TxtParseMode.NONE)
        assertEquals(1, parsed.chapterCount)
        assertEquals("全文", parsed.volumes[0].chapters[0].title)
    }

    @Test
    fun `auto parse single chapter at top`() {
        val text = "第一章 唯一\n全部内容都在这里。"
        val parsed = TxtImport.parse(text, "one.txt", TxtParseMode.AUTO)
        assertEquals(1, parsed.chapterCount)
        assertEquals("第1章 唯一", parsed.volumes[0].chapters[0].title)
    }

    @Test
    fun `auto parse di-yi-bu-fen volume title preserved`() {
        val text = """
            第一部分 少年时代
            第一章 出发
            内容。

            第二部分 中年时代
            第二章 远行
            内容二。
        """.trimIndent()

        val parsed = TxtImport.parse(text, "p.txt", TxtParseMode.AUTO)

        assertEquals(2, parsed.volumeCount)
        // 「第N部分」不重建成「第N卷 分」，保留原文标题
        assertEquals("第一部分 少年时代", parsed.volumes[0].title)
        assertEquals("第二部分 中年时代", parsed.volumes[1].title)
    }

    @Test
    fun `chinese mode explicit selection`() {
        val text = "第3回 悟空收徒\n内容。\n第4回 师徒上路\n内容二。"
        val parsed = TxtImport.parse(text, "r.txt", TxtParseMode.CHINESE)
        assertEquals(2, parsed.chapterCount)
        // 章回体「第N回」保留回字
        assertEquals("第3回 悟空收徒", parsed.volumes[0].chapters[0].title)
        assertEquals("第4回 师徒上路", parsed.volumes[0].chapters[1].title)
    }

    @Test
    fun `parse empty text`() {
        val parsed = TxtImport.parse("", "empty.txt", TxtParseMode.AUTO)
        assertEquals(0, parsed.chapterCount)
        assertEquals(0, parsed.volumeCount)
    }

    @Test
    fun `suggested title from file name`() {
        val parsed = TxtImport.parse("内容", "我的小说.txt", TxtParseMode.NONE)
        assertEquals("我的小说", parsed.suggestedTitle)
    }

    @Test
    fun `prose lines not misdetected as headings`() {
        val text = """
            他第二天回到家中。
            这是第三章的情节，但不是标题行，因为它不在行首。
            然后就结束了。
        """.trimIndent()

        val parsed = TxtImport.parse(text, "p.txt", TxtParseMode.AUTO)
        assertEquals(1, parsed.chapterCount)
    }

    @Test
    fun `long preface kept as chapter`() {
        val text = """
            这是一段很长的引言文字。
            足够三行以上的引言，应当保留为前言章节。
            第三行引言文字，确保超过阈值。
            第一章 正文
            正文内容。
        """.trimIndent()

        val parsed = TxtImport.parse(text, "pre.txt", TxtParseMode.AUTO)
        assertEquals(2, parsed.chapterCount)
        assertEquals("前言", parsed.volumes[0].chapters[0].title)
        assertTrue(parsed.volumes[0].chapters[0].content.contains("引言文字"))
        assertEquals("第1章 正文", parsed.volumes[0].chapters[1].title)
    }
}
