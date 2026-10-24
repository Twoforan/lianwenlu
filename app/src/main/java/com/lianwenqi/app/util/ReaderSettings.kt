package com.lianwenqi.app.util

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.lianwenqi.app.R
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 阅读器翻页动画：与阅读布局一起决定正文的滚动 / 分页方式 */
object ReaderPageStyle {
    /** 连续纵向滚动（不分页） */
    const val SCROLL = "scroll"

    /** 纵向滑动分页：上下滑动在相邻页之间切换 */
    const val SLIDE = "slide"

    /** 仿真：横向分页 + 3D 纸张翻页效果 */
    const val SIMULATION = "simulation"

    /** 无动画：横向分页，切换时直接显示目标页 */
    const val NONE = "none"

    /** 覆盖：横向分页，新页面固定、旧页面向左滑出覆盖 */
    const val COVER = "cover"

    val options = listOf(
        SCROLL to "滚动",
        SLIDE to "滑动",
        SIMULATION to "仿真",
        NONE to "无动画",
        COVER to "覆盖"
    )

    fun labelOf(style: String): String =
        options.firstOrNull { it.first == style }?.second ?: "滚动"

    /** 是否为横向分页（仿真 / 无动画 / 覆盖） */
    fun isHorizontal(style: String): Boolean =
        style == SIMULATION || style == NONE || style == COVER

    fun isPaged(style: String): Boolean = style != SCROLL
}

object ReaderBackgroundType {
    const val COLOR = "color"
    const val IMAGE = "image"
}

/** 阅读器行为类设置：翻页动画、音量键、背景、导入资源 */
data class ReaderSettings(
    val pageStyle: String = ReaderPageStyle.SCROLL,
    val volumeKeys: Boolean = false,
    val backgroundType: String = ReaderBackgroundType.COLOR,
    /** 当前背景图片：预设 key（parchment/landscape/green/grid）或 "import:名称" */
    val backgroundImage: String = "",
    /** 已导入字体，元素格式 "显示名|绝对路径" */
    val importedFonts: Set<String> = emptySet(),
    /** 已导入背景图，元素格式 "显示名|绝对路径" */
    val importedImages: Set<String> = emptySet()
)

/** 预设背景图片（与 res/drawable-nodpi 中的文件一一对应） */
data class ReaderBackgroundPreset(val key: String, val label: String, val resId: Int)

val ReaderBackgroundPresets = listOf(
    ReaderBackgroundPreset("parchment", "羊皮纸", R.drawable.reader_bg_parchment),
    ReaderBackgroundPreset("landscape", "山水画", R.drawable.reader_bg_landscape),
    ReaderBackgroundPreset("green", "护眼蔓绿", R.drawable.reader_bg_green),
    ReaderBackgroundPreset("grid", "草稿纸", R.drawable.reader_bg_grid)
)

/** 字体显示名：导入字体统一使用「导入：名称」，与内置字体区分 */
const val IMPORTED_FONT_PREFIX = "导入："

/** 背景图片：导入图片统一使用 "import:名称" */
const val IMPORTED_IMAGE_PREFIX = "import:"

/** 解析 "显示名|绝对路径" 形式的导入记录 */
fun importedName(spec: String): String = spec.substringBefore('|')

fun importedPath(spec: String): String = spec.substringAfter('|', "")

fun importedSpec(name: String, path: String): String = "$name|$path"

/** 内置字体 + 导入字体统一解析为 FontFamily；解析失败安全回退默认字体 */
fun readerFontFamily(label: String, importedFonts: Set<String>): FontFamily = when {
    label == "楷体" -> FontFamily(Font(R.font.reader_kaiti))
    label == "宋体" -> FontFamily(Font(R.font.reader_songti))
    label == "衬线" -> FontFamily.Serif
    label.startsWith(IMPORTED_FONT_PREFIX) -> {
        val name = label.removePrefix(IMPORTED_FONT_PREFIX)
        val spec = importedFonts.firstOrNull { importedName(it) == name }
        val path = spec?.let(::importedPath).orEmpty()
        val file = path.takeIf { it.isNotBlank() }?.let(::File)
        if (file != null && file.exists() && file.length() > 0) {
            runCatching { FontFamily(Font(file = file)) }.getOrDefault(FontFamily.Default)
        } else {
            FontFamily.Default
        }
    }
    else -> FontFamily.Default
}

/** 背景图片模型：预设返回 drawable 资源 id，导入图片返回本地文件 */
fun readerBackgroundModel(key: String, importedImages: Set<String>): Any? {
    if (key.isBlank()) return null
    if (key.startsWith(IMPORTED_IMAGE_PREFIX)) {
        val name = key.removePrefix(IMPORTED_IMAGE_PREFIX)
        val path = importedImages.firstOrNull { importedName(it) == name }
            ?.let(::importedPath).orEmpty()
        val file = path.takeIf { it.isNotBlank() }?.let(::File)
        return file?.takeIf { it.exists() && it.length() > 0 }
    }
    return ReaderBackgroundPresets.firstOrNull { it.key == key }?.resId
}

/** 导入结果：成功时 [entry] 为 "显示名|绝对路径" */
data class ReaderImportResult(val entry: String?, val message: String) {
    val ok: Boolean get() = entry != null
}

object ReaderImport {

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx).orEmpty()
                }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    /** TTF/OTF/TTC 文件头校验，避免无效文件被当作字体加载 */
    private fun isFontFile(file: File): Boolean {
        if (!file.exists() || file.length() < 12) return false
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(4)
                if (input.read(head) != 4) return@runCatching false
                val tag = String(head, Charsets.US_ASCII)
                tag == "OTTO" || tag == "true" || tag == "ttcf" ||
                    (head[0].toInt() == 0 && head[1].toInt() == 1 && head[2].toInt() == 0 && head[3].toInt() == 0)
            }
        }.getOrDefault(false)
    }

    /** 复制字体到应用私有目录并校验；失败时返回中文提示，绝不抛出异常 */
    fun importFont(context: Context, uri: Uri): ReaderImportResult {
        return runCatching {
            val display = queryDisplayName(context.contentResolver, uri).ifBlank { "字体" }
            val base = display.substringBeforeLast('.').ifBlank { "字体" }.take(24)
            val dir = File(context.filesDir, "reader_fonts").apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID().toString().take(8)}_$base.ttf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return ReaderImportResult(null, "无法读取所选字体文件")
            if (!isFontFile(target)) {
                target.delete()
                return ReaderImportResult(null, "字体文件无效或格式不受支持")
            }
            ReaderImportResult(importedSpec(base, target.absolutePath), "字体「$base」导入成功")
        }.getOrElse { ReaderImportResult(null, "字体导入失败：${it.message ?: "未知错误"}") }
    }

    /** 复制并压缩背景图片到应用私有目录，最长边限制在 1600px 内，避免超大图反复解码 */
    fun importImage(context: Context, uri: Uri): ReaderImportResult {
        return runCatching {
            val display = queryDisplayName(context.contentResolver, uri).ifBlank { "图片" }
            val base = display.substringBeforeLast('.').ifBlank { "图片" }.take(24)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // inJustDecodeBounds 时 decodeStream 固定返回 null，这里只校验能否打开输入流
            val stream = context.contentResolver.openInputStream(uri)
                ?: return ReaderImportResult(null, "无法读取所选图片")
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return ReaderImportResult(null, "图片文件无效或格式不受支持")
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return ReaderImportResult(null, "图片解码失败")
            // 透明区域统一铺白底，避免 JPEG 压缩后出现黑块
            val white = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            Canvas(white).apply {
                drawColor(AndroidColor.WHITE)
                drawBitmap(decoded, 0f, 0f, null)
            }
            decoded.recycle()
            val dir = File(context.filesDir, "reader_bg").apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID().toString().take(8)}_$base.jpg")
            FileOutputStream(target).use { out ->
                white.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            white.recycle()
            ReaderImportResult(importedSpec(base, target.absolutePath), "背景图片「$base」导入成功")
        }.getOrElse { ReaderImportResult(null, "图片导入失败：${it.message ?: "未知错误"}") }
    }
}
