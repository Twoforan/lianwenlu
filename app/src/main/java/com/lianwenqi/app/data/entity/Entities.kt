package com.lianwenqi.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lianwenqi.app.util.shortHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 书籍分组 */
@Entity(tableName = "book_group")
data class BookGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** 书籍 */
@Entity(tableName = "book")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val intro: String = "",
    /** 封面本地路径，为空时用纯色封面 */
    val coverPath: String? = null,
    /** 纯色封面底色（ARGB） */
    val coverColor: Int = 0xFF3F51B5.toInt(),
    /** 分卷标题前缀，%n 阿拉伯数字，%N 中文数字 */
    val volumePrefix: String = "第%n卷",
    /** 章节标题前缀 */
    val chapterPrefix: String = "第%n章",
    val pinned: Boolean = false,
    val groupId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 上次编辑的章节，用于书架展示 */
    val lastChapterId: Long? = null,
    val lastChapterTitle: String? = null
)

/** 分卷 */
@Entity(tableName = "volume", indices = [Index("bookId")])
data class Volume(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** 章节：标题 + 正文 + 梗概 */
@Entity(tableName = "chapter", indices = [Index("bookId"), Index("volumeId")])
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val volumeId: Long? = null,
    val title: String,
    /** 章节梗概 */
    val summary: String = "",
    /** 正文 */
    val content: String = "",
    /** 上一次退出时光标位置（快速定位） */
    val lastCursor: Int = 0,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 内置的大纲分类（标签）。key 即存储值，label 为展示名 */
enum class OutlineCategory(val label: String) {
    STORY("故事大纲"),
    CHARACTER("人设"),
    WORLD("世界观"),
    INSPIRATION("灵感库")
}

/** 书籍默认的大纲分类标签（存储值） */
val defaultOutlineKeys: List<String> = OutlineCategory.entries.map { it.name }

/** 存储值 → 展示名；自定义标签的存储值即展示名 */
fun outlineCategoryLabel(key: String): String =
    OutlineCategory.entries.firstOrNull { it.name == key }?.label ?: key

/** 作品大纲条目 */
@Entity(tableName = "outline_item", indices = [Index("bookId")])
data class OutlineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** 所属分类标签的存储值（内置分类用 OutlineCategory.name，自定义标签用标签名本身） */
    val category: String = OutlineCategory.STORY.name,
    /** 明线 / 暗线等标签 */
    val tag: String = "",
    val title: String,
    val content: String = "",
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 长期记忆：书籍正文切块后的向量化片段。
 * 只存正文（不含梗概 / 大纲），发送时用「本章梗概 + 当前提示词」做语义检索，
 * 命中片段作为临时参考资料随请求发送（不改会话前缀、不落库）。
 */
@Entity(
    tableName = "memory_chunk",
    indices = [
        Index("bookId"),
        Index("chapterId"),
        Index(value = ["chapterId", "chunkIndex"], unique = true)
    ]
)
data class MemoryChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val volumeId: Long? = null,
    val chapterId: Long,
    /** 章节内的块序号，从 0 开始 */
    val chunkIndex: Int,
    /** 块正文，用于检索命中后拼参考资料 */
    val text: String,
    /** 块文本的稳定短哈希，用于增量判定（命中即跳过） */
    val hash: String,
    /** 向量化时该章节的 updatedAt，正文再次变更即失效重算 */
    val chapterUpdatedAt: Long,
    /** 向量维度，校验一致性；换模型导致维度变化时需重跑 */
    val dim: Int,
    /** 向量数据（FloatArray 小端编码） */
    val vector: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
)

/** 提示词库 */
@Entity(tableName = "prompt")
data class Prompt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ApiType(val label: String) {
    CHAT_COMPLETIONS("chat completions"),
    RESPONSES("responses"),
    ANTHROPIC_MESSAGES("anthropic messages")
}

/** AI 供应商 */
@Entity(tableName = "provider")
data class Provider(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val apiType: ApiType = ApiType.CHAT_COMPLETIONS,
    val apiKey: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 供应商下的模型 */
@Entity(tableName = "ai_model", indices = [Index("providerId")])
data class AiModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    /** 模型标识（请求时提交的 model 名） */
    val name: String,
    /** 用户自定义的显示名，用于区分同类模型；为空时回退显示 [name] */
    val alias: String = "",
    val temperature: Float = 0.8f,
    /** 最大输出 token；<= 0 表示不限（不向接口发送 max_tokens） */
    val maxTokens: Int = 8192,
    /** 最多携带的历史消息条数（上下文窗口）；0 表示不限（携带全部历史），否则取最近 N 条 */
    val maxContext: Int = 20,
    val isDefault: Boolean = false,
    /** 预设提示词 */
    val systemPrompt: String = "",
    /** 是否开启思考/推理模式；默认开启 */
    val reasoningEnabled: Boolean = true,
    /** 思考强度档位存储值：low / medium / high / max（低/中/高/极）；默认最高强度 */
    val reasoningLevel: String = "max",
    /** true：用户手动创建的模型，模型标识可修改；false：来自厂商列表，模型标识只读 */
    val custom: Boolean = false
) {
    /** 界面展示用名称 */
    val displayName: String get() = alias.ifBlank { name }
}

enum class ChatRole { SYSTEM, USER, ASSISTANT }

@Entity(tableName = "chat_session")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "新对话",
    val bookId: Long? = null,
    /** 从章节内发起的会话所绑定的章节；为空表示从导航栏发起（不记忆） */
    val chapterId: Long? = null,
    val modelId: Long? = null,
    /**
     * 会话级「前缀」：已冻结的参考资料条目与自定义前缀（JSON，见 [SessionPrefix]）。
     * 只属于当前会话，新建对话即全新的空前缀，删除会话随之删除。
     */
    val prefix: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_message", indices = [Index("sessionId")])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: ChatRole,
    val content: String,
    /** 助手回答所回应的用户消息 id；0 表示独立消息（不参与重新生成的版本切换） */
    val replyToId: Long = 0,
    /** 同一用户消息下的回答版本序号，从 0 开始 */
    val variantIndex: Int = 0,
    /** 多版本回答中当前展示的版本 */
    val active: Boolean = true,
    /** 模型的深度思考内容，为空表示没有思考过程 */
    val reasoning: String = "",
    /** 发送这条消息时选中的上下文快照（书籍/章节/大纲），用于在消息上回显「文件清单」 */
    val contextSnapshot: String = "",
    /**
     * 随消息一起发给模型、但不显示在对话气泡里的附加提示词（如「＋本章梗概」的章节名与梗概）。
     * 它与 content 一起落库，重建历史时原样拼回，保证跨轮请求前缀逐字节一致。
     */
    val promptExtra: String = "",
    /** 本次回答消耗的输入 token（usage.prompt_tokens），0 表示未记录 */
    val promptTokens: Int = 0,
    /* 本次回答消耗的输出 token（usage.completion_tokens），0 表示未记录 */
    val completionTokens: Int = 0,
    /** 本次输入中命中缓存的 token 数，用于计算缓存命中率，0 表示未记录 */
    val cachedTokens: Int = 0,
    /** 回答正文是否被用户收起；收起后再次打开会话仍保持收起 */
    val contentCollapsed: Boolean = false,
    /**
     * 本对话发生时所在章节 id（0 表示未绑定章节）。
     * 用于把历史对话按章节分组后整组收起，新一轮对话记录的是发送当时所处的章节。
     */
    val chapterId: Long = 0,
    /**
     * 本轮发送时的提示词日志快照（JSON，见 [com.lianwenqi.app.data.PromptLog]）。
     * 供「查看日志 / 查看本轮提示词」弹窗回显当时的预设、上下文与本轮提示词。
     */
    val promptLog: String = "",
    /**
     * 未绑定章节的「本轮对话」自定义名称（存于该轮的用户消息上）。
     * 为空时界面按「第X轮对话」显示；编辑只改这里，不会同步到书籍章节。
     */
    val roundName: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 一条消息发送时的上下文选择快照。
 * 编码为「书籍id|章节id|大纲id|梗概章节id|正文章节id|章节全选|梗概全选|正文全选|v2」的紧凑字符串，
 * 存入 [ChatMessage.contextSnapshot]（旧格式为「书籍id|章节id|大纲id|含梗概|含正文|章节全选」，
 * 解析时按旧语义把全局开关展开到当时的章节列表）。
 */
data class ContextSnapshot(
    val bookIds: List<Long> = emptyList(),
    val chapterIds: List<Long> = emptyList(),
    val outlineIds: List<Long> = emptyList(),
    /** 纳入「梗概」的章节 id（与 [chapterIds] 取交集后生效） */
    val summaryChapterIds: List<Long> = emptyList(),
    /** 纳入「正文」的章节 id（与 [chapterIds] 取交集后生效） */
    val contentChapterIds: List<Long> = emptyList(),
    /** 章节是否处于「全选」状态：为真时忽略 [chapterIds]，按当前书本的全部章节重新展开 */
    val chapterAll: Boolean = false,
    /** 梗概是否全选：为真时忽略 [summaryChapterIds]，按当前书本的全部章节重新展开 */
    val summaryAll: Boolean = true,
    /** 正文是否全选：为真时忽略 [contentChapterIds]，按当前书本的全部章节重新展开 */
    val contentAll: Boolean = true
) {
    val isEmpty: Boolean
        get() = bookIds.isEmpty() && chapterIds.isEmpty() && outlineIds.isEmpty()

    fun encode(): String = listOf(
        bookIds.joinToString(","),
        chapterIds.joinToString(","),
        outlineIds.joinToString(","),
        summaryChapterIds.joinToString(","),
        contentChapterIds.joinToString(","),
        if (chapterAll) "1" else "0",
        if (summaryAll) "1" else "0",
        if (contentAll) "1" else "0",
        "v2"
    ).joinToString("|")
}

/** 解析 [ChatMessage.contextSnapshot]；旧消息没有快照时返回空快照 */
fun decodeContextSnapshot(raw: String): ContextSnapshot {
    if (raw.isBlank()) return ContextSnapshot()
    val parts = raw.split("|")
    fun ids(part: String?): List<Long> =
        part?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
    if (parts.getOrNull(8) == "v2") {
        return ContextSnapshot(
            bookIds = ids(parts[0]),
            chapterIds = ids(parts[1]),
            outlineIds = ids(parts[2]),
            summaryChapterIds = ids(parts[3]),
            contentChapterIds = ids(parts[4]),
            chapterAll = parts.getOrNull(5) == "1",
            summaryAll = parts.getOrNull(6) == "1",
            contentAll = parts.getOrNull(7) == "1"
        )
    }
    // 旧格式：字段 3/4 为全局「含梗概 / 含正文」布尔，按当时的章节列表展开成集合
    val chapters = ids(parts.getOrNull(1))
    val summaryOn = parts.getOrNull(3) != "0"
    val contentOn = parts.getOrNull(4) != "0"
    return ContextSnapshot(
        bookIds = ids(parts.getOrNull(0)),
        chapterIds = chapters,
        outlineIds = ids(parts.getOrNull(2)),
        summaryChapterIds = if (summaryOn) chapters else emptyList(),
        contentChapterIds = if (contentOn) chapters else emptyList(),
        chapterAll = parts.getOrNull(5) == "1",
        summaryAll = summaryOn,
        contentAll = contentOn
    )
}

/**
 * 会话前缀里的一个冻结条目：记录的 id 与「冻结当时渲染出来的字面文本」。
 * 存字面文本而不是每轮按 id 重算，才能保证前缀逐字节稳定、不被后续改动打破。
 */
@Serializable
data class PrefixEntry(
    val id: Long,
    /** 冻结当时渲染的文本（书籍含简介，大纲含字段行，章节含梗概/正文） */
    val text: String,
    /** 大纲条目的分类存储值（拼接时据相邻关系输出分类标题）；书籍 / 章节条目为空 */
    val category: String = ""
)

@Serializable
data class SessionPrefix(
    /** 自定义前缀（纯文本）：建立前缀时从设置取一次后冻结在本会话，之后不随设置变化 */
    val custom: String = "",
    /** 是否已建立：为 false 时下次发送才会取一次设置里的自定义前缀 */
    val initialized: Boolean = false,
    /**
     * 「一开始的上下文预设」：仅在首轮发送时把当时勾选的书籍 / 大纲 / 章节冻结进这里，
     * 作为最前面的参考资料块，之后不再增长，从而不破坏「预设 + 历史对话」的前缀字节结构。
     */
    val books: List<PrefixEntry> = emptyList(),
    val outlines: List<PrefixEntry> = emptyList(),
    val chapters: List<PrefixEntry> = emptyList(),
    /**
     * 上一次发送时勾选的 id 集合（用于判断「本次是否为重新勾选」）：
     * 只有从「上次没勾选」变成「本次勾选」的条目才需要处理，持续勾选的条目一律不再刷新。
     */
    val checkedBooks: List<Long> = emptyList(),
    val checkedOutlines: List<Long> = emptyList(),
    val checkedChapters: List<Long> = emptyList(),
    /**
     * 已经「追加到历史对话尾端」的条目 id（只增不减）：
     * 首轮之后新勾选的内容不再回填前置预设，而是渲染成文本挂到当轮用户消息尾端成为历史的一部分；
     * 记录这些 id 后，后续「取消勾选再重新勾选」不会重复追加。
     */
    val tailBooks: List<Long> = emptyList(),
    val tailOutlines: List<Long> = emptyList(),
    val tailChapters: List<Long> = emptyList(),
    /**
     * 「适配性筛选」动态命中的大纲条目 id（只增不减）：
     * 开启适配性筛选后，非常驻化分类下「显示标签」被本章梗概命中的条目，
     * 其完整字段（表）作为「新增大纲资料」随当轮用户消息落库；记录 id 后，
     * 后续重复命中的条目不再重复提交，只有新命中的才追加。
     */
    val autoOutlineIds: List<Long> = emptyList(),
    /**
     * 已提交过的长期记忆检索片段（只增不减，存片段文本的稳定短哈希）：
     * 命中的片段随当轮用户消息落库后记入这里，后续轮次即使再次召回同一片段也不再重复提交，
     * 避免同一批参考资料在多轮里反复占 token（内容仍在历史对话里，不会丢）。
     */
    val autoMemoryKeys: List<String> = emptyList()
) {
    fun encode(): String = prefixJson.encodeToString(serializer(), this)

    /** 长期记忆去重：剔除本会话已提交过的检索片段，只返回尚未提交过的新片段 */
    fun freshMemoryHits(hits: List<String>): List<String> =
        hits.filterNot { shortHash(it) in autoMemoryKeys }

    /**
     * 该大纲条目的内容是否已在本会话上下文中可用：
     * 已在前置预设（[outlines] 且大纲前缀化开启）、已追加到历史尾部（[tailOutlines]）、
     * 或已被「适配性筛选」注入过（[autoOutlineIds]）都算「已有」，无需重复提交完整内容。
     */
    fun outlineInjected(id: Long, outlinePrefixOn: Boolean): Boolean =
        id in autoOutlineIds || id in tailOutlines || (outlinePrefixOn && outlines.any { it.id == id })

    /** 把本轮新注入的大纲条目 id 记回集合（只增不减，重复 / 重复项均幂等） */
    fun markOutlinesInjected(ids: List<Long>): SessionPrefix =
        copy(autoOutlineIds = autoOutlineIds + ids.filterNot { it in autoOutlineIds }.distinct())

    /** 拼接前置「参考资料」文本；[includeChapters] / [includeOutlines] 对应该前缀化开关 */
    fun renderReference(includeChapters: Boolean, includeOutlines: Boolean): String =
        renderEntries(
            books = if (includeChapters) books else emptyList(),
            outlines = if (includeOutlines) outlines else emptyList(),
            chapters = if (includeChapters) chapters else emptyList()
        )

    /**
     * 按「书籍 → 大纲 → 章节」顺序拼接条目文本（大纲按相邻分类去重输出分类标题）。
     * 既用于前置的参考资料预设，也用于「本轮新勾选内容」追加到历史对话尾端。
     */
    fun renderEntries(
        books: List<PrefixEntry>,
        outlines: List<PrefixEntry>,
        chapters: List<PrefixEntry>
    ): String {
        val sb = StringBuilder()
        books.forEach { sb.append(it.text) }
        var prevCategory: String? = null
        outlines.forEach { entry ->
            if (entry.category != prevCategory) {
                sb.append("【").append(outlineCategoryLabel(entry.category)).append("】\n")
            }
            sb.append(entry.text)
            prevCategory = entry.category
        }
        chapters.forEach { sb.append(it.text) }
        return sb.toString().trimEnd('\n')
    }

    companion object {
        /** 解析 [ChatSession.prefix]；空值或损坏时回落到空前缀 */
        fun decode(raw: String): SessionPrefix {
            if (raw.isBlank()) return SessionPrefix()
            return runCatching { prefixJson.decodeFromString(serializer(), raw) }
                .getOrDefault(SessionPrefix())
        }
    }
}

private val prefixJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}