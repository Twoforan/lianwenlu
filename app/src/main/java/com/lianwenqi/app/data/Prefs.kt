package com.lianwenqi.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lianwenqi.app.ai.RETRIEVAL_ASK
import com.lianwenqi.app.ai.RetrievalMode
import com.lianwenqi.app.ai.retrievalModeOf
import com.lianwenqi.app.ai.storage
import com.lianwenqi.app.data.entity.ContextSnapshot
import com.lianwenqi.app.data.entity.decodeContextSnapshot
import com.lianwenqi.app.util.FormatSettings
import com.lianwenqi.app.util.IMPORTED_IMAGE_PREFIX
import com.lianwenqi.app.util.ReaderBackgroundType
import com.lianwenqi.app.util.ReaderPageStyle
import com.lianwenqi.app.util.ReaderSettings
import com.lianwenqi.app.util.StyleSettings
import com.lianwenqi.app.util.TextTool
import com.lianwenqi.app.util.importedName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 向量化工具的取值：关闭 / 厂商 Embedding API / 本地 GGUF 工具，三者互斥只能选一个 */
object MemoryToolMode {
    const val OFF = "off"
    const val API = "api"
    const val LOCAL = "local"
}

/** 记忆检索的范围偏好：给不同位置的章节加权，决定「优先从哪一段书里找参考资料」 */
object MemorySearchScope {
    /** 随机匹配：不看章节位置，纯按相关度匹配（默认） */
    const val RANDOM = "random"

    /** 最新章节优先：越靠后的章节越容易被选中 */
    const val NEWEST = "newest"

    /** 最早章节优先：越靠前的章节越容易被选中 */
    const val OLDEST = "oldest"
}

/** 记忆检索区块数（注入参考资料的段数）的取值下限与上限 */
const val MEMORY_BLOCK_QUOTA_MIN = 5
const val MEMORY_BLOCK_QUOTA_MAX = 20

/** 生成梗概 / 生成正文 字数滑条的合法上限（下限 0 表示不约束） */
const val GEN_SUMMARY_WORDS_MAX = 500
const val GEN_CONTENT_WORDS_MAX = 6000

/** 全局偏好设置：夜间模式、排序、排版、样式等 */
class Prefs(private val context: Context) {

    private object K {
        val nightMode = booleanPreferencesKey("night_mode")
        // 内置说明书的导入状态：上次导入时的 App 版本号 + 说明书书籍 id + 说明书内容签名
        val manualVersion = intPreferencesKey("manual_version")
        val manualBookId = longPreferencesKey("manual_book_id")
        val manualSignature = stringPreferencesKey("manual_signature")
        val sortMode = stringPreferencesKey("sort_mode")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val listMode = booleanPreferencesKey("list_mode")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")

        val lineSpacing = floatPreferencesKey("fmt_line_spacing")
        val indent = intPreferencesKey("fmt_indent")
        val blankLines = intPreferencesKey("fmt_blank_lines")
        val quickLocate = booleanPreferencesKey("fmt_quick_locate")
        val autoPair = booleanPreferencesKey("fmt_auto_pair")
        val colonQuote = booleanPreferencesKey("fmt_colon_quote")
        val padH = intPreferencesKey("fmt_pad_h")
        val padBottom = intPreferencesKey("fmt_pad_bottom")

        val fontSize = floatPreferencesKey("style_font_size")
        val fontLabel = stringPreferencesKey("style_font_label")
        val bold = booleanPreferencesKey("style_bold")
        val italic = booleanPreferencesKey("style_italic")
        val center = booleanPreferencesKey("style_center")
        val textColor = intPreferencesKey("style_text_color")
        val bgColor = intPreferencesKey("style_bg_color")

        val activeModelId = longPreferencesKey("active_model_id")
        val lastBookId = longPreferencesKey("last_book_id")
        val lastChapterId = longPreferencesKey("last_chapter_id")

        val readerPageMode = intPreferencesKey("reader_page_mode")
        val readerImmersive = booleanPreferencesKey("reader_immersive")

        // 首轮全选大纲 / 首轮全选章节：仅把对应内容写进首轮前缀，后续轮次复用不再改写
        val aiOutlineFirstRound = booleanPreferencesKey("ai_outline_first_round")
        val aiChapterFirstRound = booleanPreferencesKey("ai_chapter_first_round")

        // AI 工具页：写入大纲工具是否启用（默认启用）
        val aiToolOutlineEnabled = booleanPreferencesKey("ai_tool_outline_enabled")

        // AI 工具页：章节梗概导入工具是否启用（默认启用）
        val aiToolChapterEnabled = booleanPreferencesKey("ai_tool_chapter_enabled")

        // AI 聊天页勾选的 AI 工具 id 集合（可多选，跨会话保持）
        val aiChatTools = stringSetPreferencesKey("ai_chat_tools")

        // 前缀词管理：上下文前缀化 / 大纲前缀化（均默认开启）
        val prefixContextEnabled = booleanPreferencesKey("prefix_context_enabled")
        val prefixOutlineEnabled = booleanPreferencesKey("prefix_outline_enabled")

        // 前缀词管理：自定义前缀（纯文本）
        val prefixCustomText = stringPreferencesKey("prefix_custom_text")

        // 「适配性筛选」：开启后首轮只全选「常驻化」分类下的大纲，非常驻大纲按本章梗概动态匹配注入（默认关闭）
        val prefixAdaptiveFilter = booleanPreferencesKey("prefix_adaptive_filter_enabled")

        // 生成工具：生成梗概 / 生成正文的配置
        val genSummaryMinWords = intPreferencesKey("gen_summary_min_words")
        val genSummaryMaxWords = intPreferencesKey("gen_summary_max_words")
        val genSummaryLimitScope = booleanPreferencesKey("gen_summary_limit_scope")
        val genContentMinWords = intPreferencesKey("gen_content_min_words")
        val genContentMaxWords = intPreferencesKey("gen_content_max_words")
        val genContentMemoryMode = stringPreferencesKey("gen_content_memory_mode")
        val genContentWithSummary = booleanPreferencesKey("gen_content_with_summary")

        // 生成工具（生成梗概 / 生成正文）的请求是否携带本会话历史对话（默认关闭）
        val genCarryHistory = booleanPreferencesKey("gen_carry_history")

        // AI 对话分组中「用户手动展开」的分组 key 集合（默认全部收起，下次进入沿用上次的展开状态）
        val chatExpandedGroups = stringSetPreferencesKey("chat_expanded_groups")

        // 长期记忆（书本向量化）：向量化工具（互斥）、默认开启、自动续跑、默认勾选检索
        val memoryToolMode = stringPreferencesKey("memory_tool_mode")
        val memoryEmbeddingBaseUrl = stringPreferencesKey("memory_embedding_base_url")
        val memoryEmbeddingApiKey = stringPreferencesKey("memory_embedding_api_key")
        val memoryEmbeddingModel = stringPreferencesKey("memory_embedding_model")
        val memoryAutoVectorize = booleanPreferencesKey("memory_auto_vectorize")
        val memoryRetrievalEnabled = booleanPreferencesKey("memory_retrieval_enabled")
        val memoryScopeLimitEnabled = booleanPreferencesKey("memory_scope_limit_enabled")
        val memoryBlockQuota = intPreferencesKey("memory_block_quota")
        val memorySearchScope = stringPreferencesKey("memory_search_scope")

        // 阅读器独立的样式与排版：与编辑页各存一套，互不影响
        val rFontSize = floatPreferencesKey("reader_style_font_size")
        val rFontLabel = stringPreferencesKey("reader_style_font_label")
        val rBold = booleanPreferencesKey("reader_style_bold")
        val rItalic = booleanPreferencesKey("reader_style_italic")
        val rCenter = booleanPreferencesKey("reader_style_center")
        val rThin = booleanPreferencesKey("reader_style_thin")
        val rTextColor = intPreferencesKey("reader_style_text_color")
        val rBgColor = intPreferencesKey("reader_style_bg_color")

        val rLineSpacing = floatPreferencesKey("reader_fmt_line_spacing")
        val rPadH = intPreferencesKey("reader_fmt_pad_h")
        val rPadBottom = intPreferencesKey("reader_fmt_pad_bottom")
        val rPadV = intPreferencesKey("reader_fmt_pad_v")
        val rIndent = intPreferencesKey("reader_fmt_indent")
        val rBlankLines = intPreferencesKey("reader_fmt_blank_lines")

        // 阅读行为：翻页动画（旧版 reader_page_mode 仅用于迁移）、音量键、背景类型与图片
        val rPageStyle = stringPreferencesKey("reader_page_style")
        val rVolumeKeys = booleanPreferencesKey("reader_volume_keys")
        val rBgType = stringPreferencesKey("reader_bg_type")
        val rBgImage = stringPreferencesKey("reader_bg_image")

        // 用户导入的字体 / 背景图，元素格式 "显示名|绝对路径"
        val rImportedFonts = stringSetPreferencesKey("reader_imported_fonts")
        val rImportedImages = stringSetPreferencesKey("reader_imported_images")
    }

    val nightMode: Flow<Boolean> = context.dataStore.data.map { it[K.nightMode] ?: false }

    /** 内置说明书上次导入时的 App 版本号（0 表示从未导入） */
    val manualVersion: Flow<Int> = context.dataStore.data.map { it[K.manualVersion] ?: 0 }

    /** 内置说明书在书架上的书籍 id（0 表示尚未记录） */
    val manualBookId: Flow<Long> = context.dataStore.data.map { it[K.manualBookId] ?: 0L }

    /**
     * 内置说明书上次导入时的内容签名（空表示尚未记录）。
     * 版本号不变但说明书内容有更新时（例如同版本内补充说明），靠它触发整章刷新。
     */
    val manualSignature: Flow<String> = context.dataStore.data.map { it[K.manualSignature] ?: "" }
    val sortMode: Flow<String> = context.dataStore.data.map { it[K.sortMode] ?: "TIME" }
    val sortAsc: Flow<Boolean> = context.dataStore.data.map { it[K.sortAsc] ?: false }
    val listMode: Flow<Boolean> = context.dataStore.data.map { it[K.listMode] ?: true }
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[K.keepScreenOn] ?: false }
    val activeModelId: Flow<Long> = context.dataStore.data.map { it[K.activeModelId] ?: 0L }
    val lastBookId: Flow<Long> = context.dataStore.data.map { it[K.lastBookId] ?: 0L }
    val lastChapterId: Flow<Long> = context.dataStore.data.map { it[K.lastChapterId] ?: 0L }

    /** 阅读器全屏（隐藏系统状态栏与导航栏） */
    val readerImmersive: Flow<Boolean> = context.dataStore.data.map { it[K.readerImmersive] ?: true }

    /** 首轮全选大纲：仅首轮把大纲写进前缀（默认关闭） */
    val aiOutlineFirstRound: Flow<Boolean> = context.dataStore.data.map { it[K.aiOutlineFirstRound] ?: false }

    /** 首轮全选章节：仅首轮把章节（梗概）写进前缀（默认开启，与旧版新建会话自动全选章节保持一致） */
    val aiChapterFirstRound: Flow<Boolean> = context.dataStore.data.map { it[K.aiChapterFirstRound] ?: true }

    /** 「写入大纲工具」是否启用（默认启用；关闭后不出现在聊天页工具多选里） */
    val aiToolOutlineEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.aiToolOutlineEnabled] ?: true }

    /** 「章节梗概导入工具」是否启用（默认启用；关闭后不出现在聊天页工具多选里） */
    val aiToolChapterEnabled: Flow<Boolean> = context.dataStore.data.map { it[K.aiToolChapterEnabled] ?: true }

    /** AI 聊天页勾选的 AI 工具 id 集合 */
    val aiChatTools: Flow<Set<String>> = context.dataStore.data.map { it[K.aiChatTools] ?: emptySet() }

    /** 「上下文前缀化」：勾选的书籍 / 章节写进会话前缀后只增不减（默认开启） */
    val prefixContextEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[K.prefixContextEnabled] ?: true }

    /** 「大纲前缀化」：勾选的大纲写进会话前缀后只增不减（默认开启） */
    val prefixOutlineEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[K.prefixOutlineEnabled] ?: true }

    /** 「自定义前缀」：纯文本，新建会话前缀时取一次并冻结在该会话中 */
    val prefixCustomText: Flow<String> = context.dataStore.data.map { it[K.prefixCustomText] ?: "" }

    /** 「适配性筛选」：开启后首轮只全选「常驻化」分类下的大纲，非常驻大纲按本章梗概动态匹配注入（默认关闭） */
    val prefixAdaptiveFilterEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[K.prefixAdaptiveFilter] ?: false }

    /** 「生成梗概」的字数区间（0..500，下限 0 表示不约束） */
    val genSummaryWordRange: Flow<IntRange> = context.dataStore.data.map {
        wordsRange(it[K.genSummaryMinWords] ?: 0, it[K.genSummaryMaxWords] ?: GEN_SUMMARY_WORDS_MAX, GEN_SUMMARY_WORDS_MAX)
    }

    /** 「生成梗概」是否限定梗概范围（开启后点击需先选择大纲标签，默认关闭） */
    val genSummaryLimitScope: Flow<Boolean> =
        context.dataStore.data.map { it[K.genSummaryLimitScope] ?: false }

    /** 「生成正文」的字数区间（0..6000，下限 0 表示不约束） */
    val genContentWordRange: Flow<IntRange> = context.dataStore.data.map {
        wordsRange(it[K.genContentMinWords] ?: 0, it[K.genContentMaxWords] ?: GEN_CONTENT_WORDS_MAX, GEN_CONTENT_WORDS_MAX)
    }

    /** 「生成正文」的记忆检索三态（开 / 关 / 询问，默认询问） */
    val genContentMemoryMode: Flow<RetrievalMode> =
        context.dataStore.data.map { retrievalModeOf(it[K.genContentMemoryMode] ?: RETRIEVAL_ASK) }

    /** 「生成正文」是否同时生成梗概（生成后静默写入本章梗概，默认关闭） */
    val genContentWithSummary: Flow<Boolean> =
        context.dataStore.data.map { it[K.genContentWithSummary] ?: false }

    /**
     * 生成工具（生成梗概 / 生成正文）的请求是否携带本会话历史对话（默认关闭）。
     * 关闭：只复用与聊天请求逐字一致的前置块，缓存命中长度恒定；
     * 开启：在前置块之后追加本会话历史，随轮次累积，命中率更高（生成上下文包含历史对话）。
     */
    val genCarryHistory: Flow<Boolean> =
        context.dataStore.data.map { it[K.genCarryHistory] ?: false }

    /**
     * AI 对话分组的展开状态：只记录用户手动展开过的分组 key。
     * 不在集合里的分组一律按「收起成块」显示，下次进入沿用上次点击的结果。
     */
    val chatExpandedGroups: Flow<Set<String>> =
        context.dataStore.data.map { it[K.chatExpandedGroups] ?: emptySet() }

    /** 向量化工具（互斥）：api = 厂商 Embedding API；local = 本地 GGUF 工具；off = 关闭 */
    val memoryToolMode: Flow<String> =
        context.dataStore.data.map { it[K.memoryToolMode] ?: MemoryToolMode.API }

    /** 厂商 Embedding API 配置 */
    val memoryEmbeddingBaseUrl: Flow<String> =
        context.dataStore.data.map { it[K.memoryEmbeddingBaseUrl] ?: "" }
    val memoryEmbeddingApiKey: Flow<String> =
        context.dataStore.data.map { it[K.memoryEmbeddingApiKey] ?: "" }
    val memoryEmbeddingModel: Flow<String> =
        context.dataStore.data.map { it[K.memoryEmbeddingModel] ?: "" }

    /** 记忆检索是否限定范围：开启后每次勾选记忆检索都先选择章节范围（默认关闭） */
    val memoryScopeLimitEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[K.memoryScopeLimitEnabled] ?: false }

    /** 勾选记忆检索时自动续跑向量化（关闭则正文有更新时每次弹窗询问） */
    val memoryAutoVectorize: Flow<Boolean> =
        context.dataStore.data.map { it[K.memoryAutoVectorize] ?: false }

    /** 记忆检索勾选框的持久状态（会话间保持） */
    val memoryRetrievalEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[K.memoryRetrievalEnabled] ?: false }

    /** 记忆检索注入参考资料的区块数（5-20，默认 5） */
    val memoryBlockQuota: Flow<Int> = context.dataStore.data.map {
        val v = it[K.memoryBlockQuota] ?: MEMORY_BLOCK_QUOTA_MIN
        v.coerceIn(MEMORY_BLOCK_QUOTA_MIN, MEMORY_BLOCK_QUOTA_MAX)
    }

    /** 记忆检索的范围偏好（随机匹配 / 最新章节优先 / 最早章节优先） */
    val memorySearchScope: Flow<String> =
        context.dataStore.data.map { it[K.memorySearchScope] ?: MemorySearchScope.RANDOM }

    /**
     * 向量化工具是否已配置可用：
     * - 厂商 Embedding API：baseUrl / apiKey / 向量模型名三者都填齐；
     * - 本地工具：已勾选（能勾选即表示两个本地模型都已下载就绪）；
     * - 关闭：不可用。
     * 只有配置可用时，记忆检索才允许勾选（否则检索必然失败）。
     */
    val memoryVectorizationReady: Flow<Boolean> = context.dataStore.data.map { p ->
        when (p[K.memoryToolMode] ?: MemoryToolMode.API) {
            MemoryToolMode.API -> !p[K.memoryEmbeddingBaseUrl].isNullOrBlank() &&
                !p[K.memoryEmbeddingApiKey].isNullOrBlank() &&
                !p[K.memoryEmbeddingModel].isNullOrBlank()
            MemoryToolMode.LOCAL -> true
            else -> false
        }
    }

    /** 阅读器排版：与编辑页排版互相独立 */
    val readerFormat: Flow<FormatSettings> = context.dataStore.data.map {
        FormatSettings(
            lineSpacing = it[K.rLineSpacing] ?: 1.5f,
            paddingHorizontal = it[K.rPadH] ?: 16,
            paddingBottom = it[K.rPadBottom] ?: 24,
            paddingVertical = it[K.rPadV] ?: 12,
            indent = it[K.rIndent] ?: 2,
            blankLines = it[K.rBlankLines] ?: 0
        )
    }

    /** 阅读器样式：与编辑页样式互相独立 */
    val readerStyle: Flow<StyleSettings> = context.dataStore.data.map {
        val label = it[K.rFontLabel] ?: "默认"
        StyleSettings(
            fontSizeSp = it[K.rFontSize] ?: 17f,
            fontFamily = TextTool.fontFamilyOf(label),
            fontLabel = label,
            bold = it[K.rBold] ?: false,
            italic = it[K.rItalic] ?: false,
            center = it[K.rCenter] ?: false,
            thin = it[K.rThin] ?: false,
            textColor = it[K.rTextColor] ?: 0xFF1A1A1A.toInt(),
            backgroundColor = it[K.rBgColor] ?: 0xFFFFFFFF.toInt()
        )
    }

    /** 阅读器行为设置：翻页动画（含旧版两档设置的迁移）、音量键翻页、背景与导入资源 */
    val readerSettings: Flow<ReaderSettings> = context.dataStore.data.map {
        val legacyPageMode = it[K.readerPageMode] ?: 0
        val pageStyle = it[K.rPageStyle]
            ?: if (legacyPageMode == 1) ReaderPageStyle.SLIDE else ReaderPageStyle.SCROLL
        ReaderSettings(
            pageStyle = pageStyle,
            volumeKeys = it[K.rVolumeKeys] ?: false,
            backgroundType = it[K.rBgType] ?: ReaderBackgroundType.COLOR,
            backgroundImage = it[K.rBgImage] ?: "",
            importedFonts = it[K.rImportedFonts] ?: emptySet(),
            importedImages = it[K.rImportedImages] ?: emptySet()
        )
    }

    val format: Flow<com.lianwenqi.app.util.FormatSettings> = context.dataStore.data.map {
        com.lianwenqi.app.util.FormatSettings(
            lineSpacing = it[K.lineSpacing] ?: 1.5f,
            paddingHorizontal = it[K.padH] ?: 16,
            paddingBottom = it[K.padBottom] ?: 24,
            indent = it[K.indent] ?: 2,
            blankLines = it[K.blankLines] ?: 0,
            quickLocate = it[K.quickLocate] ?: true,
            autoPair = it[K.autoPair] ?: true,
            colonFollowQuote = it[K.colonQuote] ?: true
        )
    }

    val style: Flow<com.lianwenqi.app.util.StyleSettings> = context.dataStore.data.map {
        val label = it[K.fontLabel] ?: "默认"
        com.lianwenqi.app.util.StyleSettings(
            fontSizeSp = it[K.fontSize] ?: 17f,
            fontFamily = com.lianwenqi.app.util.TextTool.fontFamilyOf(label),
            fontLabel = label,
            bold = it[K.bold] ?: false,
            italic = it[K.italic] ?: false,
            center = it[K.center] ?: false,
            textColor = it[K.textColor] ?: 0xFF1A1A1A.toInt(),
            backgroundColor = it[K.bgColor] ?: 0xFFFFFFFF.toInt()
        )
    }

    suspend fun setNightMode(v: Boolean) = writePrefs { it[K.nightMode] = v }
    /** 记录说明书导入/更新结果：书籍 id + 本次导入时的 App 版本号 + 说明书内容签名 */
    suspend fun setManualMeta(bookId: Long, version: Int, signature: String) = writePrefs {
        it[K.manualBookId] = bookId
        it[K.manualVersion] = version
        it[K.manualSignature] = signature
    }
    suspend fun setSortMode(v: String) = writePrefs { it[K.sortMode] = v }
    suspend fun setSortAsc(v: Boolean) = writePrefs { it[K.sortAsc] = v }
    suspend fun setListMode(v: Boolean) = writePrefs { it[K.listMode] = v }
    suspend fun setKeepScreenOn(v: Boolean) = writePrefs { it[K.keepScreenOn] = v }
    suspend fun setActiveModelId(v: Long) = writePrefs { it[K.activeModelId] = v }
    suspend fun setReaderImmersive(v: Boolean) = writePrefs { it[K.readerImmersive] = v }
    suspend fun setAiOutlineFirstRound(v: Boolean) = writePrefs { it[K.aiOutlineFirstRound] = v }
    suspend fun setAiChapterFirstRound(v: Boolean) = writePrefs { it[K.aiChapterFirstRound] = v }
    suspend fun setAiToolOutlineEnabled(v: Boolean) = writePrefs { it[K.aiToolOutlineEnabled] = v }
    suspend fun setAiToolChapterEnabled(v: Boolean) = writePrefs { it[K.aiToolChapterEnabled] = v }
    suspend fun setAiChatTools(v: Set<String>) = writePrefs { it[K.aiChatTools] = v }
    suspend fun setPrefixContextEnabled(v: Boolean) = writePrefs { it[K.prefixContextEnabled] = v }
    suspend fun setPrefixOutlineEnabled(v: Boolean) = writePrefs { it[K.prefixOutlineEnabled] = v }
    suspend fun setPrefixCustomText(v: String) = writePrefs { it[K.prefixCustomText] = v }
    suspend fun setPrefixAdaptiveFilterEnabled(v: Boolean) = writePrefs { it[K.prefixAdaptiveFilter] = v }
    suspend fun setGenSummaryWordRange(min: Int, max: Int) = writePrefs {
        val r = wordsRange(min, max, GEN_SUMMARY_WORDS_MAX)
        it[K.genSummaryMinWords] = r.first
        it[K.genSummaryMaxWords] = r.last
    }
    suspend fun setGenSummaryLimitScope(v: Boolean) = writePrefs { it[K.genSummaryLimitScope] = v }
    suspend fun setGenContentWordRange(min: Int, max: Int) = writePrefs {
        val r = wordsRange(min, max, GEN_CONTENT_WORDS_MAX)
        it[K.genContentMinWords] = r.first
        it[K.genContentMaxWords] = r.last
    }
    suspend fun setGenContentMemoryMode(v: RetrievalMode) = writePrefs { it[K.genContentMemoryMode] = v.storage }
    suspend fun setGenContentWithSummary(v: Boolean) = writePrefs { it[K.genContentWithSummary] = v }
    suspend fun setGenCarryHistory(v: Boolean) = writePrefs { it[K.genCarryHistory] = v }

    /** 记录某个对话分组被用户展开 / 收起 */
    suspend fun setChatGroupExpanded(key: String, expanded: Boolean) = writePrefs {
        val cur = it[K.chatExpandedGroups] ?: emptySet()
        it[K.chatExpandedGroups] = if (expanded) cur + key else cur - key
    }
    suspend fun setMemoryToolMode(v: String) = writePrefs { it[K.memoryToolMode] = v }
    suspend fun setMemoryEmbeddingBaseUrl(v: String) = writePrefs { it[K.memoryEmbeddingBaseUrl] = v }
    suspend fun setMemoryEmbeddingApiKey(v: String) = writePrefs { it[K.memoryEmbeddingApiKey] = v }
    suspend fun setMemoryEmbeddingModel(v: String) = writePrefs { it[K.memoryEmbeddingModel] = v }

    suspend fun setMemoryScopeLimitEnabled(v: Boolean) = writePrefs {
        it[K.memoryScopeLimitEnabled] = v
        // 关闭限定范围时取消勾选，避免仍按上一次选中的章节范围悄悄检索
        if (!v) it[K.memoryRetrievalEnabled] = false
    }

    suspend fun setMemoryAutoVectorize(v: Boolean) = writePrefs {
        it[K.memoryAutoVectorize] = v
    }
    suspend fun setMemoryRetrievalEnabled(v: Boolean) = writePrefs { it[K.memoryRetrievalEnabled] = v }
    suspend fun setMemoryBlockQuota(v: Int) = writePrefs {
        it[K.memoryBlockQuota] = v.coerceIn(MEMORY_BLOCK_QUOTA_MIN, MEMORY_BLOCK_QUOTA_MAX)
    }
    suspend fun setMemorySearchScope(v: String) = writePrefs { it[K.memorySearchScope] = v }
    suspend fun setLastOpened(bookId: Long, chapterId: Long) = writePrefs {
        it[K.lastBookId] = bookId
        it[K.lastChapterId] = chapterId
    }

    suspend fun updateFormat(block: (com.lianwenqi.app.util.FormatSettings) -> com.lianwenqi.app.util.FormatSettings) {
        val cur = format.firstValue()
        val next = block(cur)
        writePrefs {
            it[K.lineSpacing] = next.lineSpacing
            it[K.padH] = next.paddingHorizontal
            it[K.padBottom] = next.paddingBottom
            it[K.indent] = next.indent
            it[K.blankLines] = next.blankLines
            it[K.quickLocate] = next.quickLocate
            it[K.autoPair] = next.autoPair
            it[K.colonQuote] = next.colonFollowQuote
        }
    }

    suspend fun updateStyle(block: (com.lianwenqi.app.util.StyleSettings) -> com.lianwenqi.app.util.StyleSettings) {
        val next = block(style.firstValue())
        writePrefs {
            it[K.fontSize] = next.fontSizeSp
            it[K.fontLabel] = next.fontLabel
            it[K.bold] = next.bold
            it[K.italic] = next.italic
            it[K.center] = next.center
            it[K.textColor] = next.textColor
            it[K.bgColor] = next.backgroundColor
        }
    }

    suspend fun updateReaderFormat(block: (FormatSettings) -> FormatSettings) {
        val next = block(readerFormat.firstValue())
        writePrefs {
            it[K.rLineSpacing] = next.lineSpacing
            it[K.rPadH] = next.paddingHorizontal
            it[K.rPadBottom] = next.paddingBottom
            it[K.rPadV] = next.paddingVertical
            it[K.rIndent] = next.indent
            it[K.rBlankLines] = next.blankLines
        }
    }

    suspend fun updateReaderStyle(block: (StyleSettings) -> StyleSettings) {
        val next = block(readerStyle.firstValue())
        writePrefs {
            it[K.rFontSize] = next.fontSizeSp
            it[K.rFontLabel] = next.fontLabel
            it[K.rBold] = next.bold
            it[K.rItalic] = next.italic
            it[K.rCenter] = next.center
            it[K.rThin] = next.thin
            it[K.rTextColor] = next.textColor
            it[K.rBgColor] = next.backgroundColor
        }
    }

    suspend fun updateReaderSettings(block: (ReaderSettings) -> ReaderSettings) {
        val next = block(readerSettings.firstValue())
        writePrefs {
            it[K.rPageStyle] = next.pageStyle
            it[K.rVolumeKeys] = next.volumeKeys
            it[K.rBgType] = next.backgroundType
            it[K.rBgImage] = next.backgroundImage
        }
    }

    /** 记录导入字体；同名的旧记录会被新路径覆盖，避免列表膨胀 */
    suspend fun addImportedFont(spec: String) = writePrefs {
        val cur = it[K.rImportedFonts] ?: emptySet()
        val name = importedName(spec)
        it[K.rImportedFonts] = cur.filterNot { old -> importedName(old) == name }.toSet() + spec
    }

    /** 记录导入背景图；同名旧图会被新路径覆盖 */
    suspend fun addImportedImage(spec: String) = writePrefs {
        val cur = it[K.rImportedImages] ?: emptySet()
        val name = importedName(spec)
        it[K.rImportedImages] = cur.filterNot { old -> importedName(old) == name }.toSet() + spec
    }

    /** 删除导入背景图记录（同时把仍指向它的当前背景重置为纯色） */
    suspend fun removeImportedImage(spec: String) = writePrefs {
        val cur = it[K.rImportedImages] ?: emptySet()
        val name = importedName(spec)
        it[K.rImportedImages] = cur.filterNot { old -> importedName(old) == name }.toSet()
        val current = it[K.rBgImage] ?: ""
        if (current == IMPORTED_IMAGE_PREFIX + name) {
            it[K.rBgImage] = ""
            it[K.rBgType] = ReaderBackgroundType.COLOR
        }
    }

    private suspend fun writePrefs(
        block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ) {
        context.dataStore.edit(block)
    }

    // ---------------- AI 上下文选择记忆 ----------------

    /** 某本书上次在 AI 侧栏选中的章节 / 大纲与「仅梗概 / 仅正文」开关 */
    suspend fun aiContextMemory(bookId: Long): ContextSnapshot? {
        val raw = context.dataStore.data.first()[aiContextKey(bookId)] ?: return null
        return decodeContextSnapshot(raw)
    }

    suspend fun setAiContextMemory(bookId: Long, snapshot: ContextSnapshot) {
        writePrefs { it[aiContextKey(bookId)] = snapshot.encode() }
    }

    private fun aiContextKey(bookId: Long) = stringPreferencesKey("ai_ctx_$bookId")

    // ---------------- 大纲字段表 ----------------

    /** 大纲「表」：新增条目时预填的字段名列表，按 书籍 + 分类 分别保存 */
    suspend fun outlineFields(bookId: Long, category: String): List<String>? {
        val raw = context.dataStore.data.first()[outlineFieldsKey(bookId, category)] ?: return null
        return runCatching {
            Json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrNull()
    }

    suspend fun setOutlineFields(bookId: Long, category: String, names: List<String>) {
        val json = Json.encodeToString(ListSerializer(String.serializer()), names)
        writePrefs { it[outlineFieldsKey(bookId, category)] = json }
    }

    private fun outlineFieldsKey(bookId: Long, category: String) =
        stringPreferencesKey("outline_fields_${bookId}_$category")

    // ---------------- 大纲分类标签 ----------------

    /** 某本书的大纲分类标签（存储值有序列表）；未自定义过时返回 null */
    suspend fun outlineCategories(bookId: Long): List<String>? {
        val raw = context.dataStore.data.first()[outlineCategoriesKey(bookId)] ?: return null
        return runCatching {
            Json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrNull()
    }

    suspend fun setOutlineCategories(bookId: Long, keys: List<String>) {
        val json = Json.encodeToString(ListSerializer(String.serializer()), keys)
        writePrefs { it[outlineCategoriesKey(bookId)] = json }
    }

    private fun outlineCategoriesKey(bookId: Long) =
        stringPreferencesKey("outline_categories_$bookId")

    // ---------------- 大纲分类「常驻化」 ----------------

    /** 某本书被「常驻化」的大纲分类标签（存储值集合）；未设置过时返回空集 */
    suspend fun pinnedOutlineCategories(bookId: Long): Set<String> {
        val raw = context.dataStore.data.first()[pinnedOutlineCategoriesKey(bookId)] ?: return emptySet()
        return runCatching {
            Json.decodeFromString(ListSerializer(String.serializer()), raw)
        }.getOrNull()?.toSet() ?: emptySet()
    }

    suspend fun setPinnedOutlineCategories(bookId: Long, keys: Set<String>) {
        val json = Json.encodeToString(ListSerializer(String.serializer()), keys.toList())
        writePrefs { it[pinnedOutlineCategoriesKey(bookId)] = json }
    }

    private fun pinnedOutlineCategoriesKey(bookId: Long) =
        stringPreferencesKey("outline_pinned_cats_$bookId")

    // ---------------- 复制书籍：迁移大纲相关偏好 ----------------

    /**
     * 「复制本书」时把原书的大纲相关偏好一并迁移到新书：分类标签列表、每个分类的字段模板，
     * 以及「常驻化」的分类集合。
     *
     * 这些数据按书籍 id 存在 DataStore 里，不随数据库的 outline_item 行一起复制；若不迁移，
     * 新书会丢失「已常驻化的分类」（本应常驻的大纲在首轮上下文里消失），自定义分类与字段表也会丢失。
     */
    suspend fun copyOutlinePrefs(fromBookId: Long, toBookId: Long) {
        val all = context.dataStore.data.first()
        val categories = all[outlineCategoriesKey(fromBookId)]
        val pinned = all[pinnedOutlineCategoriesKey(fromBookId)]
        val fieldsPrefix = "outline_fields_${fromBookId}_"
        val fields = all.asMap().entries
            .filter { it.key.name.startsWith(fieldsPrefix) }
            .associate { it.key.name.removePrefix(fieldsPrefix) to it.value }
        writePrefs { prefs ->
            categories?.let { prefs[outlineCategoriesKey(toBookId)] = it }
            pinned?.let { prefs[pinnedOutlineCategoriesKey(toBookId)] = it }
            fields.forEach { (category, value) ->
                (value as? String)?.let { prefs[outlineFieldsKey(toBookId, category)] = it }
            }
        }
    }

    /** 字数区间规整：夹取到 [0, cap] 并保证下限不大于上限 */
    private fun wordsRange(min: Int, max: Int, cap: Int): IntRange {
        val a = min.coerceIn(0, cap)
        val b = max.coerceIn(0, cap)
        return if (a <= b) a..b else b..a
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()
}