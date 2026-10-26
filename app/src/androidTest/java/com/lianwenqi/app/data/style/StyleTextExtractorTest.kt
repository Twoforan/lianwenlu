package com.lianwenqi.app.data.style

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 样本文本提取的仪器测试：TXT 编码兜底、EPUB spine 顺序、DOCX 段落、空文件与损坏文件。
 * 样本文件写入应用 cacheDir，通过 file:// URI 交给 ContentResolver 读取。
 */
@RunWith(AndroidJUnit4::class)
class StyleTextExtractorTest {

    private lateinit var context: Context
    private lateinit var extractor: StyleTextExtractor
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        extractor = StyleTextExtractor(context)
        dir = File(context.cacheDir, "style_extractor_test").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun writeFile(name: String, bytes: ByteArray): Uri {
        val file = File(dir, name)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    private fun extract(uri: Uri, name: String): StyleExtractResult =
        runBlocking { extractor.extract(uri, name) }

    @Test
    fun txt_utf8_保留段落并清理控制字符() {
        val uri = writeFile("样本.txt", "第一章\r\n\r\n这是正文。\r\n第二段\u0000".toByteArray(Charsets.UTF_8))
        val result = extract(uri, "样本.txt")
        assertTrue(result is StyleExtractResult.Success)
        result as StyleExtractResult.Success
        assertEquals(StyleSampleFormat.TXT, result.format)
        assertEquals("第一章\n\n这是正文。\n第二段", result.text)
    }

    @Test
    fun txt_gb18030_可正确解码() {
        val text = "这是用国标编码保存的正文内容。"
        val uri = writeFile("国标.txt", text.toByteArray(charset("GB18030")))
        val result = extract(uri, "国标.txt")
        assertTrue(result is StyleExtractResult.Success)
        assertEquals(text, (result as StyleExtractResult.Success).text)
    }

    @Test
    fun txt_空文件返回失败() {
        val uri = writeFile("空.txt", ByteArray(0))
        val result = extract(uri, "空.txt")
        assertTrue(result is StyleExtractResult.Failure)
        assertEquals("文件内容为空", (result as StyleExtractResult.Failure).message)
    }

    @Test
    fun txt_超长正文明确失败() {
        val oversized = "a".repeat(StyleSample.MAX_SAMPLE_CHARS + 1)
        val uri = writeFile("超长.txt", oversized.toByteArray(Charsets.UTF_8))
        val result = extract(uri, "超长.txt")
        assertTrue(result is StyleExtractResult.Failure)
        assertTrue((result as StyleExtractResult.Failure).message.contains("正文过长"))
    }

    @Test
    fun 不支持格式明确失败() {
        val uri = writeFile("文档.pdf", "not a supported file".toByteArray())
        val result = extract(uri, "文档.pdf")
        assertTrue(result is StyleExtractResult.Failure)
        assertTrue((result as StyleExtractResult.Failure).message.contains("不支持"))
    }

    @Test
    fun docx_按段落提取并忽略域代码与制表位定义() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:tabs><w:tab w:val="left" w:pos="420"/></w:tabs></w:pPr>
                  <w:r><w:t>第一段</w:t></w:r>
                </w:p>
                <w:p><w:r><w:instrText>PAGE</w:instrText><w:t>第二段</w:t></w:r></w:p>
                <w:p><w:r><w:t>第三段</w:t></w:r><w:r><w:br/><w:t>换行后</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val uri = writeFile("文档.docx", zipOf(mapOf("word/document.xml" to documentXml)))
        val result = extract(uri, "文档.docx")
        assertTrue(result is StyleExtractResult.Success)
        result as StyleExtractResult.Success
        assertEquals(StyleSampleFormat.DOCX, result.format)
        assertEquals("第一段\n第二段\n第三段\n换行后", result.text)
    }

    @Test
    fun epub_按spine顺序合并而不是按文件名() {
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <manifest>
                <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="c2"/>
                <itemref idref="c1"/>
              </spine>
            </package>
        """.trimIndent()
        val container = """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()
        val ch1 = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第一章</h1><p>第一章正文。</p></body></html>
        """.trimIndent()
        // &nbsp; 不是 XML 预定义实体，这里顺便验证解析失败时能退化为标签剥离
        val ch2 = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body><h1>第二章</h1><p>第二章&nbsp;正文。</p></body></html>
        """.trimIndent()

        val uri = writeFile(
            "书籍.epub",
            zipOf(
                mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to container,
                    "OEBPS/content.opf" to opf,
                    "OEBPS/ch1.xhtml" to ch1,
                    "OEBPS/ch2.xhtml" to ch2
                )
            )
        )
        val result = extract(uri, "书籍.epub")
        assertTrue(result is StyleExtractResult.Success)
        result as StyleExtractResult.Success
        assertEquals(StyleSampleFormat.EPUB, result.format)
        val second = result.text.indexOf("第二章")
        val first = result.text.indexOf("第一章")
        assertTrue(second >= 0 && first >= 0)
        assertTrue("spine 顺序应让第二章排在第一章之前", second < first)
    }

    @Test
    fun epub_缺少内容清单时失败() {
        val uri = writeFile(
            "残缺.epub",
            zipOf(mapOf("OEBPS/ch1.xhtml" to "<html><body><p>正文</p></body></html>"))
        )
        val result = extract(uri, "残缺.epub")
        assertTrue(result is StyleExtractResult.Failure)
        assertTrue((result as StyleExtractResult.Failure).message.contains("EPUB 结构不完整"))
    }

    @Test
    fun epub_损坏压缩包时失败() {
        val uri = writeFile("损坏.epub", "这不是一个 zip 文件".toByteArray())
        val result = extract(uri, "损坏.epub")
        assertTrue(result is StyleExtractResult.Failure)
        assertTrue((result as StyleExtractResult.Failure).message.contains("损坏"))
    }

    @Test
    fun 进入页面时会清理遗留的解析临时文件() {
        val tempDir = File(context.cacheDir, "style_samples").apply { mkdirs() }
        val junk = File(tempDir, "junk.epub").apply { writeText("x") }
        assertTrue(junk.exists())
        extractor.cleanStaleTemp()
        assertFalse(junk.exists())
    }

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((path, content) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
