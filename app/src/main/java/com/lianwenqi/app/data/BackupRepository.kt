package com.lianwenqi.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.BookGroup
import com.lianwenqi.app.data.entity.Chapter
import com.lianwenqi.app.data.entity.OutlineCategory
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.StyleProfile
import com.lianwenqi.app.data.entity.StyleSourceType
import com.lianwenqi.app.data.entity.Volume
import com.lianwenqi.app.data.entity.defaultOutlineKeys
import com.lianwenqi.app.util.buildTitle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 备份文件里的书籍：只保留大纲 / 正文 / 梗概等文学数据，不含会话、模型与向量 */
@Serializable
data class BackupBook(
    val title: String,
    val intro: String = "",
    val coverColor: Int = 0,
    val volumePrefix: String = "第%n卷",
    val chapterPrefix: String = "第%n章",
    /** 所属书籍分组名；恢复时按名称匹配，不存在则新建 */
    val groupName: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val volumes: List<BackupVolume> = emptyList(),
    val chapters: List<BackupChapter> = emptyList(),
    val outlines: List<BackupOutline> = emptyList(),
    /** 大纲分类标签顺序（含自定义标签）；为空表示沿用默认分类 */
    val outlineCategories: List<String> = emptyList(),
    /** 已「常驻化」的大纲分类标签 */
    val pinnedOutlineCategories: List<String> = emptyList(),
    /** 各分类的字段模板（未设置过的分类不出现） */
    val outlineFields: Map<String, List<String>> = emptyMap(),
    /**
     * 绑定的文风档案在 [BackupStyleProfile.id] 中的值；0 表示未绑定。
     * 旧备份没有该字段，按 0（未绑定）处理。
     */
    val styleId: Long = 0
)

/** 备份文件里的文风档案：只保留分析结果与来源元数据，不含样本文本 */
@Serializable
data class BackupStyleProfile(
    /** 导出时的原 id，仅用于恢复时把书籍绑定重映射到新档案，不直接写入数据库 */
    val id: Long = 0,
    val name: String,
    val sourceType: String = StyleSourceType.MANUAL.name,
    val sourceMeta: String = "{}",
    val analysisJson: String = "{}",
    val analysisVersion: Int = 1,
    val modelName: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class BackupVolume(
    val title: String,
    val sortOrder: Int = 0
)

@Serializable
data class BackupChapter(
    val title: String,
    val summary: String = "",
    val content: String = "",
    /** 所属分卷在 [BackupBook.volumes] 排序后的下标；为空表示未分卷 */
    val volumeIndex: Int? = null,
    val sortOrder: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class BackupOutline(
    val category: String = OutlineCategory.STORY.name,
    val tag: String = "",
    val title: String,
    val content: String = "",
    val sortOrder: Int = 0
)

/** 备份包内容：app + format 作为「符合规则」的校验标识 */
@Serializable
data class BackupPayload(
    val app: String = BackupRepository.APP_ID,
    val format: Int = BackupRepository.FORMAT_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val appVersion: String = "",
    val books: List<BackupBook> = emptyList(),
    /** 文风档案；旧备份没有该字段，按空列表处理 */
    val styleProfiles: List<BackupStyleProfile> = emptyList()
)

/** 恢复结果：新建 / 覆盖 / 跳过的书籍数量 */
data class RestoreResult(val created: Int, val replaced: Int, val skipped: Int)

/**
 * 数据备份与恢复：把全部书籍的大纲 / 正文 / 梗概打包成一个 zip（内含一份 JSON 清单）。
 * 恢复时校验清单标识，书名与现有书籍重合时由界面询问「覆盖」或「跳过」。
 */
class BackupRepository(
    private val db: AppDatabase,
    private val context: Context,
    private val prefs: Prefs
) {
    private val bookDao = db.bookDao()
    private val volumeDao = db.volumeDao()
    private val chapterDao = db.chapterDao()
    private val outlineDao = db.outlineDao()
    private val groupDao = db.bookGroupDao()
    private val memoryChunkDao = db.memoryChunkDao()
    private val styleDao = db.styleProfileDao()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 备份文件名（带时间戳） */
    fun defaultFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date())
        return "炼文炉备份_$stamp.zip"
    }

    /** 导出全部书籍到缓存目录下的 zip，供「分享」使用 */
    suspend fun createBackupZip(): File {
        val payload = exportPayload()
        // 与 FileProvider 的 cache-path「exports/」保持一致，分享时才能取到可读 Uri
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, defaultFileName())
        BufferedOutputStream(file.outputStream()).use { writeZip(payload, it) }
        return file
    }

    /** 导出并直接写入用户选择的保存位置（SAF） */
    suspend fun writeBackupTo(uri: Uri): Int {
        val payload = exportPayload()
        val out = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法写入所选位置")
        BufferedOutputStream(out).use { writeZip(payload, it) }
        return payload.books.size
    }

    /** 读取并校验备份文件；不符合规则时抛出 [IllegalArgumentException] */
    suspend fun readBackup(uri: Uri): BackupPayload {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            readManifest(input)
        } ?: throw IllegalArgumentException("无法读取所选文件")
        val payload = runCatching { json.decodeFromString<BackupPayload>(bytes.decodeToString()) }
            .getOrElse { throw IllegalArgumentException("备份文件内容已损坏，无法解析") }
        if (payload.app != APP_ID || payload.format < 1) {
            throw IllegalArgumentException("不是有效的炼文炉备份文件")
        }
        if (payload.books.isEmpty()) throw IllegalArgumentException("备份文件里没有书籍数据")
        return payload
    }

    /** 与现有书籍重名的书名（恢复前用于询问是否覆盖） */
    suspend fun conflictTitles(payload: BackupPayload): List<String> =
        payload.books.map { it.title.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { title -> bookDao.findByTitle(title) != null }

    /**
     * 恢复：重名书籍按 [overwrite] 覆盖（整本替换）或跳过，其余按新书加入书架。
     * 先恢复文风档案并建立「备份原 id → 新 id」映射，再按映射恢复书籍绑定，保证引用有效。
     * 整段导入在同一事务内完成，中途失败不会留下半本书。
     */
    suspend fun restore(payload: BackupPayload, overwrite: Boolean): RestoreResult = db.withTransaction {
        val groups = groupDao.getAll().toMutableList()
        val styleMap = restoreStyleProfiles(payload.styleProfiles)
        var created = 0
        var replaced = 0
        var skipped = 0
        payload.books.forEach { raw ->
            val title = raw.title.trim()
            if (title.isBlank()) return@forEach
            val existing = bookDao.findByTitle(title)
            if (existing != null) {
                if (!overwrite) {
                    skipped++
                    return@forEach
                }
                deleteBookRows(existing.id)
                replaced++
            } else {
                created++
            }
            insertBook(raw.copy(title = title), groups, styleMap)
        }
        RestoreResult(created = created, replaced = replaced, skipped = skipped)
    }

    /**
     * 恢复文风档案：同一份备份里同名档案只建一份，本机已有的同名档案直接复用（不覆盖已有分析结果）；
     * 新档案一律由数据库分配新 id，不用备份里的 id，避免与本机档案冲突。
     */
    private suspend fun restoreStyleProfiles(profiles: List<BackupStyleProfile>): Map<Long, Long> {
        val idMap = mutableMapOf<Long, Long>()
        profiles.forEach { raw ->
            val name = raw.name.trim()
            if (name.isBlank()) return@forEach
            val existing = styleDao.findByName(name)
            if (existing != null) {
                if (raw.id != 0L) idMap[raw.id] = existing.id
                return@forEach
            }
            val now = System.currentTimeMillis()
            val newId = styleDao.insert(
                StyleProfile(
                    name = name,
                    sourceType = StyleSourceType.fromKey(raw.sourceType).name,
                    sourceMeta = raw.sourceMeta.ifBlank { "{}" },
                    analysisJson = raw.analysisJson.ifBlank { "{}" },
                    analysisVersion = raw.analysisVersion,
                    modelName = raw.modelName,
                    createdAt = raw.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = raw.updatedAt.takeIf { it > 0 } ?: now
                )
            )
            if (raw.id != 0L) idMap[raw.id] = newId
        }
        return idMap
    }

    private suspend fun exportPayload(): BackupPayload {
        val groupNames = groupDao.getAll().associate { it.id to it.name }
        val books = bookDao.getAll().map { book ->
            val volumes = volumeDao.getByBook(book.id).sortedBy { it.sortOrder }
            val volumeIndex = volumes.mapIndexed { index, volume -> volume.id to index }.toMap()
            val outlines = outlineDao.getByBook(book.id)
            // 分类标签 / 字段模板 / 常驻化标记按书籍 id 存在 DataStore，不随数据库行迁移，需一并导出
            val categories = (prefs.outlineCategories(book.id) ?: defaultOutlineKeys) +
                outlines.map { it.category }
            val distinctCategories = categories.distinct()
            val fields = distinctCategories.mapNotNull { category ->
                prefs.outlineFields(book.id, category)?.let { category to it }
            }.toMap()
            BackupBook(
                title = book.title,
                intro = book.intro,
                coverColor = book.coverColor,
                volumePrefix = book.volumePrefix,
                chapterPrefix = book.chapterPrefix,
                groupName = book.groupId?.let { groupNames[it] }.orEmpty(),
                createdAt = book.createdAt,
                updatedAt = book.updatedAt,
                volumes = volumes.map { BackupVolume(it.title, it.sortOrder) },
                chapters = chapterDao.getByBook(book.id).sortedBy { it.sortOrder }.map { chapter ->
                    BackupChapter(
                        title = chapter.title,
                        summary = chapter.summary,
                        content = chapter.content,
                        volumeIndex = chapter.volumeId?.let { volumeIndex[it] },
                        sortOrder = chapter.sortOrder,
                        createdAt = chapter.createdAt,
                        updatedAt = chapter.updatedAt
                    )
                },
                outlines = outlines.map { item ->
                    BackupOutline(
                        category = item.category,
                        tag = item.tag,
                        title = item.title,
                        content = item.content,
                        sortOrder = item.sortOrder
                    )
                },
                outlineCategories = distinctCategories,
                pinnedOutlineCategories = prefs.pinnedOutlineCategories(book.id).toList(),
                outlineFields = fields,
                styleId = book.styleId
            )
        }
        return BackupPayload(
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersionName(),
            books = books,
            styleProfiles = styleDao.getAll().map { profile ->
                BackupStyleProfile(
                    id = profile.id,
                    name = profile.name,
                    sourceType = profile.sourceType,
                    sourceMeta = profile.sourceMeta,
                    analysisJson = profile.analysisJson,
                    analysisVersion = profile.analysisVersion,
                    modelName = profile.modelName,
                    createdAt = profile.createdAt,
                    updatedAt = profile.updatedAt
                )
            }
        )
    }

    private suspend fun deleteBookRows(bookId: Long) {
        memoryChunkDao.deleteByBook(bookId)
        chapterDao.deleteByBook(bookId)
        volumeDao.deleteByBook(bookId)
        outlineDao.deleteByBook(bookId)
        bookDao.deleteById(bookId)
    }

    private suspend fun insertBook(
        book: BackupBook,
        groups: MutableList<BookGroup>,
        styleMap: Map<Long, Long>
    ): Long {
        val groupId = book.groupName.trim().takeIf { it.isNotBlank() }?.let { name ->
            groups.firstOrNull { it.name == name }?.id ?: run {
                val id = groupDao.insert(BookGroup(name = name, sortOrder = groups.size))
                groups += BookGroup(id = id, name = name, sortOrder = groups.size)
                id
            }
        }
        val now = System.currentTimeMillis()
        val volumePrefix = book.volumePrefix.ifBlank { "第%n卷" }
        val chapterPrefix = book.chapterPrefix.ifBlank { "第%n章" }
        val bookId = bookDao.insert(
            Book(
                title = book.title,
                intro = book.intro,
                coverPath = null,
                coverColor = book.coverColor,
                volumePrefix = volumePrefix,
                chapterPrefix = chapterPrefix,
                pinned = false,
                groupId = groupId,
                createdAt = book.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = book.updatedAt.takeIf { it > 0 } ?: now,
                // 按恢复时建立的 id 映射换算；映射不到（档案被跳过 / 旧备份）一律视为未绑定
                styleId = styleMap[book.styleId] ?: 0
            )
        )
        val volumes = book.volumes.sortedBy { it.sortOrder }
        val volumeIds = volumes.map { volume ->
            volumeDao.insert(Volume(bookId = bookId, title = volume.title, sortOrder = volume.sortOrder))
        }
        var firstChapter: Pair<Long, String>? = null
        val chapters = book.chapters.sortedBy { it.sortOrder }
        chapters.forEachIndexed { index, chapter ->
            val id = chapterDao.insert(
                Chapter(
                    bookId = bookId,
                    volumeId = chapter.volumeIndex?.let { volumeIds.getOrNull(it) },
                    title = chapter.title,
                    summary = chapter.summary,
                    content = chapter.content,
                    sortOrder = index,
                    createdAt = chapter.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = chapter.updatedAt.takeIf { it > 0 } ?: now
                )
            )
            if (firstChapter == null) firstChapter = id to chapter.title
        }
        // 备份里没有章节（用户清空过）时补一个默认章节，保证书本打开后仍有可编辑内容
        if (firstChapter == null) {
            val volumeId = volumeIds.firstOrNull()
                ?: volumeDao.insert(Volume(bookId = bookId, title = buildTitle(volumePrefix, 1), sortOrder = 0))
            val title = buildTitle(chapterPrefix, 1)
            val id = chapterDao.insert(
                Chapter(bookId = bookId, volumeId = volumeId, title = title, sortOrder = 0)
            )
            firstChapter = id to title
        }
        firstChapter?.let { (id, title) -> bookDao.touch(bookId, now, id, title) }
        book.outlines.forEach { item ->
            outlineDao.insert(
                OutlineItem(
                    bookId = bookId,
                    category = item.category.ifBlank { OutlineCategory.STORY.name },
                    tag = item.tag,
                    title = item.title,
                    content = item.content,
                    sortOrder = item.sortOrder
                )
            )
        }
        // 大纲分类 / 字段模板 / 常驻化标记按新书 id 写回 DataStore
        if (book.outlineCategories.isNotEmpty()) {
            prefs.setOutlineCategories(bookId, book.outlineCategories)
        }
        if (book.pinnedOutlineCategories.isNotEmpty()) {
            prefs.setPinnedOutlineCategories(bookId, book.pinnedOutlineCategories.toSet())
        }
        book.outlineFields.forEach { (category, fields) ->
            prefs.setOutlineFields(bookId, category, fields)
        }
        return bookId
    }

    private fun writeZip(payload: BackupPayload, out: OutputStream) {
        val bytes = json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry(ENTRY_NAME))
            zos.write(bytes)
            zos.closeEntry()
        }
    }

    /** 从 zip 中读出清单 JSON 的字节；找不到清单或不是 zip 时抛出 [IllegalArgumentException] */
    private fun readManifest(input: InputStream): ByteArray {
        val zis = runCatching { ZipInputStream(BufferedInputStream(input)) }
            .getOrElse { throw IllegalArgumentException("不是有效的炼文炉备份文件") }
        zis.use { zip ->
            val entry = runCatching {
                var e = zip.nextEntry
                while (e != null && !e.name.equals(ENTRY_NAME, ignoreCase = true) &&
                    !e.name.endsWith(".json", ignoreCase = true)
                ) {
                    e = zip.nextEntry
                }
                e
            }.getOrElse { throw IllegalArgumentException("不是有效的炼文炉备份文件") }
            if (entry == null) throw IllegalArgumentException("不是有效的炼文炉备份文件")
            return zip.readBytes()
        }
    }

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    companion object {
        const val APP_ID = "lianwenlu"
        const val FORMAT_VERSION = 1
        const val ENTRY_NAME = "lianwenlu-backup.json"
    }
}
