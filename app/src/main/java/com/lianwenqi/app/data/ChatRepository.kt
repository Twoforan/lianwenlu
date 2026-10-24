package com.lianwenqi.app.data

import com.lianwenqi.app.data.entity.ChatMessage
import com.lianwenqi.app.data.entity.ChatRole
import com.lianwenqi.app.data.entity.ChatSession
import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import kotlinx.coroutines.flow.Flow

/*
 * 单行文本超过 SQLite CursorWindow（2MB）后，读取该行会抛 SQLiteBlobTooBigException，
 * 导致「打开 AI 聊天就闪退」。以下上限保证单行体积远低于该限制（中文按 3 字节/字估算）。
 */
internal const val MAX_STORED_CONTENT = 250_000
internal const val MAX_STORED_REASONING = 80_000
internal const val MAX_STORED_PROMPT_EXTRA = 50_000
internal const val MAX_STORED_PROMPT_LOG = 120_000
internal const val MAX_STORED_PREFIX = 300_000

/** 单条聊天消息允许提交的最大字数；超过时由界面提示「消息过长，请缩短后重试」并放弃提交，不做截断 */
internal const val MAX_CHAT_MESSAGE_CHARS = MAX_STORED_CONTENT

internal fun String.capStored(max: Int): String = if (length > max) take(max) else this

/** 对话领域：会话、消息、上下文组装 */
class ChatRepository(private val db: AppDatabase) : ChatGateway {
    private val chatDao = db.chatDao()
    // 组装上下文时还需要书 / 章节 / 大纲
    private val bookDao = db.bookDao()
    private val chapterDao = db.chapterDao()
    private val outlineDao = db.outlineDao()

    // ---------------- 对话 ----------------

    fun observeSessions(): Flow<List<ChatSession>> = chatDao.observeSessions()

    suspend fun createSession(
        title: String,
        bookId: Long?,
        modelId: Long?,
        chapterId: Long? = null
    ): Long =
        chatDao.insertSession(
            ChatSession(title = title, bookId = bookId, chapterId = chapterId, modelId = modelId)
        )

    suspend fun getSession(id: Long): ChatSession? = chatDao.getSession(id)

    /** 章节内 AI 抽屉恢复历史对话 */
    suspend fun getSessionByChapter(chapterId: Long): ChatSession? = chatDao.getSessionByChapter(chapterId)

    /** 「跟书」模式：该书最近一次绑定本书的会话 */
    suspend fun getBookSession(bookId: Long): ChatSession? = chatDao.getBookSession(bookId)

    /** 「跟书」模式：该书全部绑定本书的会话，最新的在前 */
    suspend fun bookSessions(bookId: Long): List<ChatSession> = chatDao.bookSessions(bookId)

    /** 「跟章节」模式：该章节下的全部会话，最新的在前 */
    suspend fun sessionsByChapter(chapterId: Long): List<ChatSession> =
        chatDao.sessionsByChapter(chapterId)

    suspend fun updateSessions(sessions: List<ChatSession>) = chatDao.updateSessions(sessions)

    suspend fun updateSession(session: ChatSession) =
        chatDao.updateSession(session.copy(prefix = session.prefix.capStored(MAX_STORED_PREFIX)))

    /** 修改会话标题 */
    suspend fun updateSessionTitle(id: Long, title: String) = chatDao.updateSessionTitle(id, title)

    /** 更新会话级「前缀」（只增不减的前缀冻结数据） */
    suspend fun updateSessionPrefix(id: Long, prefix: String) =
        chatDao.updateSessionPrefix(id, prefix.capStored(MAX_STORED_PREFIX))

    suspend fun deleteSession(id: Long) {
        chatDao.clearMessages(id)
        chatDao.deleteSession(id)
    }

    /** 清空会话的全部历史消息（换绑书籍时选择「不带历史对话」用） */
    suspend fun clearSessionMessages(sessionId: Long) = chatDao.clearMessages(sessionId)

    fun observeMessages(sessionId: Long): Flow<List<ChatMessage>> = chatDao.observeMessages(sessionId)

    override suspend fun getMessages(sessionId: Long): List<ChatMessage> = chatDao.getMessages(sessionId)

    override suspend fun addMessage(
        sessionId: Long,
        role: ChatRole,
        content: String,
        replyToId: Long,
        variantIndex: Int,
        reasoning: String,
        contextSnapshot: String,
        promptExtra: String,
        promptTokens: Int,
        completionTokens: Int,
        cachedTokens: Int,
        chapterId: Long,
        promptLog: String
    ): Long {
        val id = chatDao.insertMessage(
            ChatMessage(
                sessionId = sessionId,
                role = role,
                content = content,
                replyToId = replyToId,
                variantIndex = variantIndex,
                reasoning = reasoning.capStored(MAX_STORED_REASONING),
                contextSnapshot = contextSnapshot,
                promptExtra = promptExtra.capStored(MAX_STORED_PROMPT_EXTRA),
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = cachedTokens,
                chapterId = chapterId,
                promptLog = promptLog.capStored(MAX_STORED_PROMPT_LOG)
            )
        )
        chatDao.getSession(sessionId)?.let {
            chatDao.updateSession(it.copy(updatedAt = System.currentTimeMillis()))
        }
        return id
    }

    /** 编辑已有消息的正文（超长在前面的输入校验里已拦下，这里不做截断） */
    suspend fun updateMessageContent(id: Long, content: String) = chatDao.updateContent(id, content)

    /** 设置某条回答正文的折叠状态（收起后再次进入会话仍保持） */
    suspend fun setContentCollapsed(id: Long, collapsed: Boolean) =
        chatDao.updateContentCollapsed(id, collapsed)

    /** 修改未绑定章节「本轮对话」的显示名（只改消息，不同步书籍章节） */
    suspend fun updateMessageRoundName(id: Long, name: String) = chatDao.updateRoundName(id, name)

    suspend fun getMessage(id: Long): ChatMessage? = chatDao.getMessage(id)

    /** 批量删除指定消息（编辑历史提示词后丢弃旧内容用） */
    suspend fun deleteMessagesByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) chatDao.deleteMessagesByIds(ids)
    }

    /** 下一个回答版本序号 */
    suspend fun nextVariantIndex(replyToId: Long): Int = chatDao.maxVariantIndex(replyToId) + 1

    /** 切换到某个回答版本 */
    suspend fun setActiveVariant(replyToId: Long, messageId: Long) {
        chatDao.deactivateVariants(replyToId)
        chatDao.activateMessage(messageId)
    }

    /** 取消某用户消息下所有回答版本的展示标记（用于插入新版本前） */
    override suspend fun deactivateVariants(replyToId: Long) = chatDao.deactivateVariants(replyToId)

    /**
     * 删除消息：用户消息连同其所有回答版本一起删除；
     * 删除助手版本时，若删的是当前展示版本，则回退到剩余最后一个版本。
     */
    suspend fun deleteMessage(id: Long) {
        val msg = chatDao.getMessage(id) ?: return
        chatDao.deleteMessage(id)
        when {
            msg.role == ChatRole.USER -> chatDao.deleteVariants(id)
            msg.replyToId != 0L && msg.active -> {
                chatDao.getVariants(msg.replyToId).lastOrNull()?.let { chatDao.activateMessage(it.id) }
            }
        }
    }

    /** 按勾选的书籍 / 分卷 / 章节 / 人设 组装上下文（章节的梗概 / 正文逐章判定） */
    override suspend fun buildContext(
        bookIds: List<Long>,
        chapterIds: List<Long>,
        outlineIds: List<Long>,
        contentChapterIds: Set<Long>,
        summaryChapterIds: Set<Long>
    ): String {
        val sb = StringBuilder()
        bookIds.forEach { bid ->
            val book = bookDao.getById(bid) ?: return@forEach
            sb.append("《").append(book.title).append("》")
            if (book.intro.isNotBlank()) sb.append("（简介：").append(book.intro).append("）")
            sb.append('\n')
        }
        // 大纲：同一分类只输出一次分类标题，其下每条以「｛首项值｝」单独起行，
        // 其余字段逐行「字段名：值」，不再输出「显示标签：xxx」也不再重复分类标题
        val outlineItems = outlineIds.mapNotNull { outlineDao.getById(it) }
        if (outlineItems.isNotEmpty()) {
            val grouped = LinkedHashMap<String, MutableList<OutlineItem>>()
            outlineItems.forEach { grouped.getOrPut(it.category) { mutableListOf() }.add(it) }
            grouped.forEach { (category, items) ->
                items.forEachIndexed { index, item ->
                    val fields = decodeOutlineFields(item.content)
                    // 首项即条目的「显示标签」，其值作为对外标题；旧数据回落用 item.title
                    val head = fields.firstOrNull()?.second?.ifBlank { item.title } ?: item.title
                    if (index == 0) sb.append("【").append(outlineCategoryLabel(category)).append("】")
                    sb.append("｛").append(head).append("｝").append('\n')
                    fields.drop(1).forEach { f ->
                        sb.append(f.first).append("：").append(f.second).append('\n')
                    }
                }
            }
        }
        chapterIds.forEach { cid ->
            val ch = chapterDao.getById(cid) ?: return@forEach
            sb.append("【章节】").append(ch.title).append('\n')
            if (cid in summaryChapterIds && ch.summary.isNotBlank()) {
                sb.append("梗概：").append(ch.summary).append('\n')
            }
            if (cid in contentChapterIds) sb.append("正文：").append(ch.content).append('\n')
        }
        return sb.toString()
    }

    // ---------------- 会话前缀条目渲染（冻结时按当时的字面文本存档） ----------------

    /** 书籍条目：与 [buildContext] 的书籍部分保持一致 */
    suspend fun renderBookEntry(bookId: Long): String? {
        val book = bookDao.getById(bookId) ?: return null
        return buildString {
            append("《").append(book.title).append("》")
            if (book.intro.isNotBlank()) append("（简介：").append(book.intro).append("）")
            append('\n')
        }
    }

    /** 单条大纲条目：不含分类标题（拼接时按相邻分类去重输出） */
    suspend fun renderOutlineEntry(outlineId: Long): String? {
        val item = outlineDao.getById(outlineId) ?: return null
        val fields = decodeOutlineFields(item.content)
        val head = fields.firstOrNull()?.second?.ifBlank { item.title } ?: item.title
        return buildString {
            append("｛").append(head).append("｝").append('\n')
            fields.drop(1).forEach { f ->
                append(f.first).append("：").append(f.second).append('\n')
            }
        }
    }

    /** 单条章节条目：与 [buildContext] 的章节部分保持一致 */
    suspend fun renderChapterEntry(
        chapterId: Long,
        includeSummary: Boolean,
        includeContent: Boolean
    ): String? {
        val ch = chapterDao.getById(chapterId) ?: return null
        return buildString {
            append("【章节】").append(ch.title).append('\n')
            if (includeSummary && ch.summary.isNotBlank()) {
                append("梗概：").append(ch.summary).append('\n')
            }
            if (includeContent) append("正文：").append(ch.content).append('\n')
        }
    }
}
