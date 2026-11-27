package com.lianwenqi.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 书架背景图的本地持久化。
 *
 * 用户选中的图片经校验、缩放（最长边 1920px）后复制到应用私有目录，
 * DataStore 只保存文件路径，不依赖相册返回的临时 Content URI。
 */
class ShelfBackgroundStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "shelf_bg").apply { mkdirs() }

    /** 图片文件是否可用（存在且非空），读取配置时用于回退判断 */
    fun isUsable(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val file = File(path)
        return file.isFile && file.length() > 0
    }

    /**
     * 把系统图片选择器返回的 Uri 复制为私有目录中的 JPEG。
     * 成功返回新文件绝对路径；失败返回中文错误信息，不抛出异常。
     */
    suspend fun copyFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // 边界探测时 decodeStream 固定返回 null，不能把它的返回值当作打开成功与否
            val boundsStream = context.contentResolver.openInputStream(uri)
                ?: error("无法读取所选图片")
            boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                error("图片文件无效或格式不受支持")
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            } ?: error("图片解码失败")
            // 背景统一铺白底，避免带透明通道的图片（PNG/WEBP）压成 JPEG 后出现黑块
            val opaque = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            Canvas(opaque).apply {
                drawColor(AndroidColor.WHITE)
                drawBitmap(decoded, 0f, 0f, null)
            }
            decoded.recycle()
            val target = File(dir, "shelf_bg_${UUID.randomUUID().toString().take(8)}.jpg")
            try {
                FileOutputStream(target).use { out ->
                    opaque.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } finally {
                opaque.recycle()
            }
            if (!target.isFile || target.length() <= 0) {
                target.delete()
                error("图片保存失败，请检查存储空间")
            }
            target.absolutePath
        }
    }

    /** 删除不再使用的背景图文件；失败时静默忽略 */
    suspend fun deleteFile(path: String?) = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext
        runCatching { File(path).takeIf { it.isFile }?.delete() }
        Unit
    }

    companion object {
        /** 最长边上限，与阅读器背景图一致，避免超大图片反复解码占用内存 */
        private const val MAX_EDGE = 1920
    }
}
