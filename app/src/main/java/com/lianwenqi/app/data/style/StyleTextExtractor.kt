package com.lianwenqi.app.data.style

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** 单个文风样本的解析结果 */
sealed class StyleExtractResult {
    data class Success(val text: String, val format: StyleSampleFormat) : StyleExtractResult()

    data class Failure(val message: String) : StyleExtractResult()
}

/**
 * 把用户选择的样本文件解析为纯文本，供后续文风分析使用。
 *
 * - TXT：BOM / UTF-8 严格解码，失败后回退 GB18030；
 * - EPUB：解压到缓存临时文件，按 OPF spine 顺序拼接各章 XHTML 正文；
 * - DOCX：解析 word/document.xml 的段落与文本节点。
 *
 * 不依赖第三方解析库；不写日志、不落库、不上传。解析用临时文件在解析结束时立即删除，
 * 并在进入页面时清理上次异常退出可能遗留的临时文件。
 */
class StyleTextExtractor(private val context: Context) {

    fun displayName(uri: Uri): String {
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "未命名文件"
    }

    fun mimeType(uri: Uri): String? = runCatching { context.contentResolver.getType(uri) }.getOrNull()

    /** 清理遗留的解析临时文件（页面进入时调用） */
    fun cleanStaleTemp() {
        runCatching {
            tempDir.listFiles()?.forEach { it.delete() }
        }
    }

    suspend fun extract(uri: Uri, fileName: String? = null): StyleExtractResult =
        withContext(Dispatchers.IO) {
            val name = fileName?.takeIf { it.isNotBlank() } ?: displayName(uri)
            val format = detectStyleSampleFormat(name, mimeType(uri))
            if (format == StyleSampleFormat.UNKNOWN) {
                return@withContext StyleExtractResult.Failure(
                    "不支持的文件格式，目前仅支持 TXT、EPUB、DOCX"
                )
            }
            try {
                when (format) {
                    StyleSampleFormat.TXT -> extractTxt(uri)
                    StyleSampleFormat.EPUB -> extractEpub(uri)
                    StyleSampleFormat.DOCX -> extractDocx(uri)
                    StyleSampleFormat.UNKNOWN -> StyleExtractResult.Failure("不支持的文件格式")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SampleTooLargeException) {
                StyleExtractResult.Failure(e.message ?: "文件过大，无法处理")
            } catch (e: EmptyFileException) {
                StyleExtractResult.Failure(e.message ?: "文件内容为空")
            } catch (e: java.io.FileNotFoundException) {
                StyleExtractResult.Failure("文件不存在或已被移动")
            } catch (e: SecurityException) {
                StyleExtractResult.Failure("无法访问该文件：权限已失效或文件已被删除")
            } catch (e: ZipException) {
                StyleExtractResult.Failure("文件已损坏或不是有效的 ${format.label} 文件")
            } catch (e: IOException) {
                StyleExtractResult.Failure("读取文件失败，请检查文件是否可访问")
            } catch (e: Exception) {
                StyleExtractResult.Failure("解析文件失败，请确认文件内容完整")
            }
        }

    // ---------------- TXT ----------------

    private fun extractTxt(uri: Uri): StyleExtractResult {
        val bytes = openBounded(uri, MAX_TEXT_BYTES)
            ?: return StyleExtractResult.Failure("无法打开文件")
        if (bytes.isEmpty()) return StyleExtractResult.Failure("文件内容为空")
        val decoded = decodeText(bytes)
        return buildResult(decoded, StyleSampleFormat.TXT, "未提取到任何正文内容")
    }

    private fun openBounded(uri: Uri, limit: Int): ByteArray? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        return input.use { readBounded(it, limit) }
    }

    /** 按 BOM 与编码尝试解码：UTF-8 严格 → GB18030 → UTF-8 宽松兜底 */
    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        decodeStrict(bytes, Charsets.UTF_8)?.let { return it }
        runCatching { decodeStrict(bytes, Charset.forName("GB18030")) }
            .getOrNull()?.let { return it }
        return String(bytes, Charsets.UTF_8)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    // ---------------- EPUB ----------------

    private fun extractEpub(uri: Uri): StyleExtractResult {
        val temp = copyToTemp(uri, ".epub")
            ?: return StyleExtractResult.Failure("无法打开文件")
        try {
            ZipFile(temp).use { zip ->
                val container = zip.getEntry("META-INF/container.xml")
                    ?: return StyleExtractResult.Failure("EPUB 结构不完整（缺少 container.xml）")
                val opfPath = readOpfPath(zip, container)
                    ?: return StyleExtractResult.Failure("EPUB 结构不完整（未找到内容清单）")
                val opfEntry = zip.getEntry(opfPath)
                    ?: return StyleExtractResult.Failure("EPUB 内容清单缺失")
                val hrefs = parseOpfSpine(zip, opfEntry)
                if (hrefs.isEmpty()) {
                    return StyleExtractResult.Failure("EPUB 未找到可读取的章节正文")
                }

                val builder = StringBuilder()
                for (href in hrefs) {
                    val entryName = resolveEntryPath(opfPath, href)
                    if (entryName.isEmpty()) continue
                    val entry = zip.getEntry(entryName) ?: continue
                    val html = readEntryText(zip, entry, MAX_TEXT_BYTES) ?: continue
                    val chapter = extractXhtmlText(html)
                    if (chapter.isBlank()) continue
                    if (builder.length + chapter.length > StyleSample.MAX_SAMPLE_CHARS) {
                        return StyleExtractResult.Failure(
                            "正文过长（超过 ${StyleSample.MAX_SAMPLE_CHARS} 字符），请拆分文件后重新导入"
                        )
                    }
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(chapter)
                }
                return buildResult(
                    builder.toString(), StyleSampleFormat.EPUB,
                    "未提取到任何正文内容（该 EPUB 可能为图片版）"
                )
            }
        } finally {
            temp.delete()
        }
    }

    private fun readOpfPath(zip: ZipFile, entry: ZipEntry): String? {
        val xml = readEntryText(zip, entry, MAX_METADATA_BYTES) ?: return null
        val parser = newParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && localName(parser) == "rootfile") {
                val path = parser.getAttributeValue(null, "full-path")
                if (!path.isNullOrBlank()) return path
            }
            event = parser.next()
        }
        return null
    }

    /** 返回按 spine 阅读顺序排列的章节 href；无 spine 时退化为 manifest 中的 XHTML 顺序 */
    private fun parseOpfSpine(zip: ZipFile, entry: ZipEntry): List<String> {
        val xml = readEntryText(zip, entry, MAX_METADATA_BYTES) ?: return emptyList()
        val parser = newParser(xml)
        val hrefById = LinkedHashMap<String, String>()
        val xhtmlHrefs = ArrayList<String>()
        val spineIds = ArrayList<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (localName(parser)) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type").orEmpty().lowercase()
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            hrefById[id] = href
                            if (mediaType.contains("xhtml") || mediaType.contains("html")) {
                                xhtmlHrefs.add(href)
                            }
                        }
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (!idref.isNullOrBlank()) spineIds.add(idref)
                    }
                }
            }
            event = parser.next()
        }
        val ordered = spineIds.mapNotNull { hrefById[it] }
        return ordered.ifEmpty { xhtmlHrefs }
    }

    /** 把 href 解析为相对 OPF 所在目录的 zip 条目名 */
    private fun resolveEntryPath(opfPath: String, href: String): String {
        val clean = Uri.decode(href.substringBefore('#')).trim()
        if (clean.isEmpty()) return ""
        val baseDir = opfPath.substringBeforeLast('/', "")
        val raw = when {
            clean.startsWith("/") -> clean.trimStart('/')
            baseDir.isEmpty() -> clean
            else -> "$baseDir/$clean"
        }
        val segments = ArrayList<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    private fun extractXhtmlText(html: String): String {
        val parsed = runCatching { parseXhtmlWithXml(html) }.getOrNull()
        val text = if (parsed.isNullOrBlank()) stripHtml(html) else parsed
        return StyleTextNormalizer.normalize(text)
    }

    private fun parseXhtmlWithXml(html: String): String {
        val parser = newParser(html)
        val builder = StringBuilder()
        var skipDepth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = localName(parser)
                    when {
                        name == "script" || name == "style" -> skipDepth++
                        skipDepth == 0 && name in BLOCK_TAGS -> newline(builder)
                    }
                }
                XmlPullParser.TEXT -> if (skipDepth == 0) {
                    val text = parser.text ?: ""
                    // 源码缩进产生的纯空白文本节点不代表正文内容 / 段落间距
                    if (!(text.isBlank() && text.contains('\n'))) builder.append(text)
                }
                XmlPullParser.END_TAG -> {
                    val name = localName(parser)
                    when {
                        name == "script" || name == "style" -> if (skipDepth > 0) skipDepth--
                        skipDepth == 0 && name in BLOCK_TAGS -> newline(builder)
                    }
                }
            }
            event = parser.next()
        }
        return builder.toString()
    }

    /** 结构不规范（如含未定义实体）的 XHTML 退化为按标签剥离 */
    private fun stripHtml(html: String): String {
        var text = html
        text = Regex("(?is)<(script|style)[^>]*>.*?</\\1\\s*>").replace(text, "\n")
        text = Regex("(?i)<br\\s*/?>").replace(text, "\n")
        text = Regex("(?i)</?(p|div|h[1-6]|li|tr|blockquote|section|article|dd|dt|td|th|pre)\\b[^>]*>")
            .replace(text, "\n")
        text = Regex("<[^>]+>").replace(text, "")
        text = Regex("&#x([0-9a-fA-F]+);").replace(text) { match ->
            match.groupValues[1].toIntOrNull(16)?.let(::codePointToString) ?: ""
        }
        text = Regex("&#(\\d+);").replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
        }
        text = text.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        return text
    }

    private fun codePointToString(value: Int): String =
        if (value in 0..0x10FFFF) String(Character.toChars(value)) else ""

    // ---------------- DOCX ----------------

    private fun extractDocx(uri: Uri): StyleExtractResult {
        val temp = copyToTemp(uri, ".docx")
            ?: return StyleExtractResult.Failure("无法打开文件")
        try {
            ZipFile(temp).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: return StyleExtractResult.Failure("DOCX 结构不完整（缺少正文 document.xml）")
                val xml = readEntryText(zip, entry, MAX_TEXT_BYTES)
                    ?: return StyleExtractResult.Failure("无法读取 DOCX 正文")
                return buildResult(
                    parseDocxText(xml), StyleSampleFormat.DOCX, "未提取到任何正文内容"
                )
            }
        } finally {
            temp.delete()
        }
    }

    private fun parseDocxText(xml: String): String {
        val parser = newParser(xml)
        val builder = StringBuilder()
        // 只取 <w:t> 内的文本：字段代码 / 修订删除 / 源码缩进都不会混入正文
        var textDepth = 0
        // 段落属性（<w:pPr>）里的 <w:tab> 是制表位定义，不是正文制表符，需忽略
        var propertyDepth = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser)) {
                    // 每个段落独立成行，保留原有段落结构
                    "p" -> newline(builder)
                    "pPr" -> propertyDepth++
                    "t" -> textDepth++
                    "br" -> if (propertyDepth == 0) builder.append('\n')
                    "tab" -> if (propertyDepth == 0) builder.append('\t')
                }
                XmlPullParser.TEXT -> if (textDepth > 0) builder.append(parser.text ?: "")
                XmlPullParser.END_TAG -> when (localName(parser)) {
                    "p" -> newline(builder)
                    "pPr" -> if (propertyDepth > 0) propertyDepth--
                    "t" -> if (textDepth > 0) textDepth--
                }
            }
            event = parser.next()
        }
        return StyleTextNormalizer.normalize(builder.toString())
    }

    // ---------------- 通用 ----------------

    private fun copyToTemp(uri: Uri, suffix: String): File? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val dir = tempDir
        val file = File(dir, "sample_${System.nanoTime()}$suffix")
        try {
            input.use { source ->
                FileOutputStream(file).use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_ARCHIVE_BYTES) throw SampleTooLargeException()
                        target.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        if (file.length() == 0L) {
            file.delete()
            throw EmptyFileException()
        }
        return file
    }

    private fun readEntryBytes(zip: ZipFile, entry: ZipEntry, limit: Int): ByteArray? =
        zip.getInputStream(entry).use { readBounded(it, limit) }

    private fun readEntryText(zip: ZipFile, entry: ZipEntry, limit: Int): String? =
        readEntryBytes(zip, entry, limit)?.let { decodeText(it) }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 64 * 1024))
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (total < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun buildResult(
        raw: String,
        format: StyleSampleFormat,
        emptyMessage: String
    ): StyleExtractResult {
        val text = StyleTextNormalizer.normalize(raw)
        if (text.isEmpty()) return StyleExtractResult.Failure(emptyMessage)
        if (text.length > StyleSample.MAX_SAMPLE_CHARS) {
            return StyleExtractResult.Failure(
                "正文过长（超过 ${StyleSample.MAX_SAMPLE_CHARS} 字符），请拆分文件后重新导入"
            )
        }
        return StyleExtractResult.Success(text, format)
    }

    private fun newParser(xml: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        return parser
    }

    private fun localName(parser: XmlPullParser): String =
        parser.name?.substringAfterLast(':') ?: ""

    private fun newline(builder: StringBuilder) {
        if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
    }

    private val tempDir: File
        get() = File(context.cacheDir, TEMP_DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 解析临时文件过大（解压炸弹 / 异常大文件）时明确失败，不做截断 */
    private class SampleTooLargeException : IOException("文件过大（超过 64 MB），无法处理")

    private class EmptyFileException : IOException("文件内容为空")

    companion object {
        private const val TEMP_DIR_NAME = "style_samples"
        private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
        private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        private const val MAX_TEXT_BYTES = 4 * StyleSample.MAX_SAMPLE_CHARS + 4

        private val BLOCK_TAGS = setOf(
            "p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "tr", "blockquote", "section", "article", "title",
            "td", "th", "dd", "dt", "pre", "hr"
        )
    }
}
