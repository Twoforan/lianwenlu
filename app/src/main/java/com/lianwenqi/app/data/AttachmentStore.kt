package com.lianwenqi.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 一条聊天附件：图片 / 文件 / 链接。只记录本地缓存路径与来源，正文按需读取 */
@Serializable
data class ChatAttachment(
    /** image / file / link */
    val type: String,
    /** 展示名：图片或文件名、链接标题 */
    val name: String,
    /** 本地缓存文件（图片为压缩后的 jpg，文件与链接为提取后的 txt） */
    val path: String = "",
    /** 链接原始地址（type=link 时有效） */
    val url: String = ""
)

/**
 * 聊天附件的本地存储与读取。
 *
 * 图片压缩后落盘、文件与链接正文提取后落盘，数据库只存路径，
 * 避免把 base64 与大段文本写进 chat_message；发送时再按路径读出来。
 */
class AttachmentStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val dir: File
        get() = File(context.filesDir, "attachments").apply { mkdirs() }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun encode(list: List<ChatAttachment>): String =
        if (list.isEmpty()) "" else json.encodeToString(list)

    fun decode(raw: String): List<ChatAttachment> =
        if (raw.isBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<ChatAttachment>>(raw) }.getOrDefault(emptyList())
        }

    /** 相册图片：解码 → 长边缩到 [MAX_IMAGE_EDGE] → JPEG 压缩落盘 */
    fun saveImage(uri: Uri, name: String): ChatAttachment? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) > MAX_IMAGE_EDGE && bounds.outHeight / (sample * 2) > MAX_IMAGE_EDGE) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val scaled = scaleToMaxEdge(decoded)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== decoded) decoded.recycle()
        scaled.recycle()
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.writeBytes(out.toByteArray())
        ChatAttachment(type = "image", name = name.ifBlank { "图片" }, path = file.absolutePath)
    }.getOrNull()

    /** 文本类文件：读取前 [MAX_TEXT_BYTES] 字节后落盘（UTF-8） */
    fun saveTextFile(uri: Uri, name: String): ChatAttachment? = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(MAX_TEXT_BYTES)
            val n = input.read(buf)
            if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
        } ?: return null
        if (text.isBlank()) return null
        val file = File(dir, "${UUID.randomUUID()}.txt")
        file.writeText(text)
        ChatAttachment(type = "file", name = name.ifBlank { "文本文件" }, path = file.absolutePath)
    }.getOrNull()

    /** 链接：抓取网页并用 Jsoup 提取正文；抓取失败时只保留链接地址 */
    suspend fun fetchLink(rawUrl: String): ChatAttachment = withContext(Dispatchers.IO) {
        val url = if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl else "https://$rawUrl"
        val html = runCatching {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            ).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
        }.getOrDefault("")
        var title = ""
        val text = if (html.isBlank()) {
            ""
        } else {
            runCatching {
                val doc = Jsoup.parse(html, url)
                title = doc.title().trim()
                doc.select("script,style,nav,footer,header,aside,form,iframe").remove()
                doc.body()?.text().orEmpty()
            }.getOrDefault("")
        }
        val content = if (text.isBlank()) {
            "（网页正文抓取失败，仅提供链接地址）\n$url"
        } else {
            "来源：$url\n\n" + text.take(MAX_INJECT_CHARS)
        }
        val file = File(dir, "${UUID.randomUUID()}.txt")
        file.writeText(content)
        ChatAttachment(type = "link", name = title.ifBlank { url }, path = file.absolutePath, url = url)
    }

    /** 图片转 data URL，供多模态请求使用；超过 [MAX_IMAGE_BYTES] 或文件缺失时返回 null */
    fun imageDataUrl(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists() || file.length() > MAX_IMAGE_BYTES) return null
        "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /** 文件 / 链接提取出的正文；读不到时返回 null */
    fun textContent(path: String): String? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        file.readText().take(MAX_INJECT_CHARS)
    }.getOrNull()

    /** 删除附件对应的本地缓存文件（消息删除或附件被移除时调用） */
    fun deleteFiles(list: List<ChatAttachment>) {
        list.forEach { attachment ->
            if (attachment.path.isBlank()) return@forEach
            runCatching { File(attachment.path).takeIf { it.exists() }?.delete() }
        }
    }

    private fun scaleToMaxEdge(src: Bitmap): Bitmap {
        val maxEdge = maxOf(src.width, src.height)
        if (maxEdge <= MAX_IMAGE_EDGE) return src
        val ratio = MAX_IMAGE_EDGE.toFloat() / maxEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val MAX_IMAGE_EDGE = 1568
        private const val MAX_IMAGE_BYTES = 1_500_000L
        private const val MAX_TEXT_BYTES = 200 * 1024
        private const val MAX_INJECT_CHARS = 20_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
