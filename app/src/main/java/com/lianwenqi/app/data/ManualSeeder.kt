package com.lianwenqi.app.data

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import com.lianwenqi.app.data.entity.Book
import com.lianwenqi.app.data.entity.Chapter
import kotlinx.coroutines.flow.first

/**
 * 内置《炼文炉 · 使用说明书》的导入器。
 *
 * 启动时把 assets/manual.txt 解析成一本书（按 `## ` 标题切章）写入数据库，
 * 说明书于是像普通书籍一样出现在书架上，也能在 AI 对话里被选中作为上下文书籍，
 * 用来直接向 AI 提问本 App 的用法。
 *
 * 导入策略按 App 版本号 + 说明书内容签名维护：
 * - 记录上次导入的 versionCode 与说明书内容签名；两者都未变则不动；
 * - 版本变化、或同版本内说明书内容有更新（签名变化）时，用最新内容整章替换说明书的章节
 *   （用户对说明书本身的手动编辑会被刷新）；
 * - 若说明书书已被用户删除，则重新生成一本。
 */
object ManualSeeder {

    /** 说明书在 assets 中的文件名（保持 ASCII，避免打包与读取问题） */
    private const val ASSET = "manual.txt"

    /** 书架上的书名 */
    const val TITLE = "炼文炉 · 使用说明书"

    /** 纯色封面底色（墨绿），与普通书籍区分 */
    private const val COVER_COLOR = 0xFF00695C.toInt()

    /** 解析结果：书籍简介 + 各章（标题 + 正文） */
    data class Manual(val intro: String, val sections: List<Section>)

    data class Section(val title: String, val content: String)

    /**
     * 章节分隔规则：以 `## ` 开头的行是章标题，其余归入当前章正文；
     * 第一个 `## ` 之前的内容（去掉开头的 `# ` 大标题行）作为书籍简介。
     */
    fun parse(text: String): Manual {
        val sections = mutableListOf<Section>()
        val intro = StringBuilder()
        val body = StringBuilder()
        var currentTitle: String? = null

        for (raw in text.lines()) {
            val line = raw.trimEnd()
            when {
                line.startsWith("## ") -> {
                    currentTitle?.let { sections += Section(it, body.toString().trim()) }
                    body.clear()
                    currentTitle = line.removePrefix("## ").trim()
                }
                currentTitle == null -> if (!line.startsWith("# ")) intro.appendLine(line)
                else -> body.appendLine(line)
            }
        }
        currentTitle?.let { sections += Section(it, body.toString().trim()) }
        return Manual(intro.toString().trim(), sections)
    }

    /** 当前 App 的 versionCode（API 28 起用 longVersionCode，兼容低版本） */
    private fun appVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }.getOrDefault(0)

    /**
     * 按版本维护内置说明书：首次导入、版本变化或说明书内容更新时刷新、被删除后重建。
     * 任何一步失败都静默跳过，不影响启动。
     */
    suspend fun ensureSeeded(context: Context, prefs: Prefs) {
        val currentVersion = appVersionCode(context)
        val text = runCatching {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val signature = signatureOf(text)

        val storedVersion = prefs.manualVersion.first()
        val storedSignature = prefs.manualSignature.first()
        val storedBookId = prefs.manualBookId.first()
        val db = AppDatabase.get(context)

        // 版本与说明书内容都没变，且说明书书仍存在：无需任何处理
        if (storedVersion == currentVersion && storedSignature == signature &&
            storedBookId > 0L && db.bookDao().getById(storedBookId) != null
        ) return

        val manual = parse(text)
        if (manual.sections.isEmpty()) return

        db.withTransaction {
            // 优先用记录的 id 找回说明书；记录丢失时按书名兜底查找
            val existing = (if (storedBookId > 0L) db.bookDao().getById(storedBookId) else null)
                ?: db.bookDao().findByTitle(TITLE)

            val bookId = if (existing != null) {
                // 已存在：仅刷新简介与封面色，保留分组 / 置顶 / 排序位置等用户设置
                db.bookDao().update(existing.copy(intro = manual.intro, coverColor = COVER_COLOR))
                // 整章替换：先清空旧章节与其向量，再写入最新内容
                db.chapterDao().deleteByBook(existing.id)
                db.memoryChunkDao().deleteByBook(existing.id)
                existing.id
            } else {
                // 已被用户删除：重新生成一本
                db.bookDao().insert(Book(title = TITLE, intro = manual.intro, coverColor = COVER_COLOR))
            }

            manual.sections.forEachIndexed { index, section ->
                db.chapterDao().insert(
                    Chapter(
                        bookId = bookId,
                        title = section.title,
                        content = section.content,
                        sortOrder = index
                    )
                )
            }
            prefs.setManualMeta(bookId, currentVersion, signature)
        }
    }

    /**
     * 说明书内容的短签名（SHA-256 前 8 字节的十六进制）。
     * 版本号不变但内容有更新时（例如同版本内补充说明章节），用它触发整章刷新，
     * 保证用户手里的说明书始终是最新一版。
     */
    private fun signatureOf(text: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        digest.take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault("len-${text.length}")
}
