package com.lianwenqi.app.data.volume

/**
 * 压缩任务专用提示词。
 *
 * 提取与整合各自使用逐字节稳定的系统前缀；不拼接进聊天 System Prompt，
 * 也不依赖任何聊天历史。变量只出现在 user 内容里。
 */
object VolumeCompressionPrompt {

    /** 内容保留优先级：提取与整合提示词共用同一份口径 */
    const val RETENTION_PRIORITY =
        "主线重大事件 → 人物重大转变 → 重要关系变化 → 伏笔及回收 → 关键支线 → 重要配角 → 日常互动 → 重复描写"

    /** 分批提取的稳定系统前缀 */
    const val EXTRACTION_SYSTEM: String =
        "你是长篇小说整卷压缩的章节提取编辑。你的任务是从给定章节或章节片段中提取可审计的结构化事实，" +
            "供后续全卷整合使用。\n" +
            "规则：\n" +
            "1. 只依据给定文本，不得虚构人物、事件、因果、伏笔或未来情节；不确定的内容不要写。\n" +
            "2. 输入可能是同一章节的片段：只提取本片段实际发生的事实，不要补写片段之外的内容。\n" +
            "3. 保留人物状态与关系的变化过程，不要只写片段末尾的静态结果。\n" +
            "4. 记录伏笔、未解决事项、重要细节与支线；重复描写可以合并。\n" +
            "5. chapterRange 必须与给定批次范围一致，sourceChapterIds 必须与给定章节 id 完全一致。\n" +
            "6. 每个事件必须标注它来自哪些章节 id，只能使用【章节 id】中列出的 id；跨片段事件使用同一 chapterId。\n" +
            "7. 只输出一个 JSON 对象，不要 Markdown 代码围栏，不要解释文字。\n" +
            "8. 输出结构固定为：\n" +
            "{\"chapterRange\":\"第x-y章\",\"sourceChapterIds\":[章节id...]," +
            "\"events\":[{\"text\":\"重大事件...\",\"chapterIds\":[章节id...]}]," +
            "\"participants\":[\"主要人物...\"]," +
            "\"cause\":\"事件起因\",\"result\":\"事件结果\"," +
            "\"characterChanges\":[\"人物状态变化...\"],\"relationshipChanges\":[\"人物关系变化...\"]," +
            "\"foreshadowing\":[\"伏笔埋设或回收...\"],\"unresolvedIssues\":[\"未解决事项...\"]," +
            "\"importantDetails\":[\"重要细节...\"],\"subplots\":[\"支线...\"]}\n" +
            "内容保留优先级：$RETENTION_PRIORITY。"

    /** 全卷整合的稳定系统前缀 */
    const val MERGE_SYSTEM: String =
        "你是长篇小说整卷梗概的整合编辑。你会收到按章节范围标记的阶段结果，" +
            "需要把它们整合成一条连贯的剧情脉络，而不是把各段摘要首尾拼接。\n" +
            "规则：\n" +
            "1. 合并跨批、跨片段的重复事件与重复描写，但不得删除任何重大事件；不得为了控制篇幅省略关键内容。\n" +
            "2. 保持事件的时间顺序与因果链，明确起因、经过与结果。\n" +
            "3. 人物关系必须写出演变过程（初遇、信任、冲突、决裂、和解等），不能只保留卷末静态状态。\n" +
            "4. 覆盖伏笔的埋设与回收、多条支线、重要配角、未解决事件，以及后文对前文的修正或反转。\n" +
            "5. 每一份阶段结果都必须被纳入；不得因为轮次或先后顺序丢掉早期章节。\n" +
            "6. 只输出最终梗概正文（纯文本），不要输出 JSON，不要 Markdown 代码围栏，不要解释你的处理过程。\n" +
            "7. 不同阶段结果如果互相矛盾，以后出现的内容为准，并保留必要的修正说明。\n" +
            "内容保留优先级：$RETENTION_PRIORITY。"

    /** 单个批次的用户内容；章节标记包含 id、顺序、标题与片段位置，保证整合阶段可追溯 */
    fun extractionUser(batch: CompressionBatch, batchCount: Int): String = buildString {
        append("【本批范围】").append(batch.range.label)
        append("（批次 ").append(batch.index + 1).append('/').append(batchCount).append("）\n")
        append("【章节 id】").append(batch.chapterIds.joinToString("、")).append('\n')
        if (batch.isSliced) {
            append("【切分说明】本批包含超长章节的无损片段；片段之间不重叠，请只提取各片段内的事实，")
            append("同一章节的跨片段事件在事件来源中复用同一 chapterId。\n")
        }
        append("请提取以下 ").append(batch.chapters.size).append(" 个章节或章节片段：\n\n")
        batch.chapters.forEach { chapter ->
            append("### 章节 id=").append(chapter.chapterId)
            append("｜顺序=").append(chapter.order)
            append("｜标题=").append(chapter.title.ifBlank { "（无标题）" })
            if (chapter.isSlice) {
                append("｜片段=").append(chapter.sliceIndex).append('/').append(chapter.sliceCount)
            }
            append('\n')
            append(chapter.text.trim())
            append("\n\n")
        }
        append("只输出约定结构的 JSON 对象。")
    }

    /** 整合的用户内容：按顺序列出所有节点，保留每个节点的来源覆盖信息 */
    fun mergeUser(
        nodes: List<CompressionMergeNode>,
        finalRound: Boolean,
        targetTokens: Int
    ): String = buildString {
        append("【整合轮次】").append(if (finalRound) "最终全卷整合" else "分级整合")
        append('\n')
        append("【节点数量】").append(nodes.size).append('\n')
        append("【覆盖章节】")
        append(nodes.map { it.rangeLabel }.distinct().joinToString("、"))
        append('\n')
        append("【来源章节 id】")
        append(nodes.flatMap { it.chapterIds }.distinct().joinToString("、"))
        append("\n\n")
        if (finalRound) {
            append("请把以下阶段结果整合成一份连贯的整卷梗概。")
            append("最终梗概篇幅必须约 ").append(targetTokens)
            append(" 字（允许上下浮动约 10%），不得明显偏短或偏长。")
            append("不得为了达到篇幅删减重大事件。\n\n")
        } else {
            append("以下阶段结果仍未满足最终整合的输入容量，请在不丢失事实的前提下压缩，")
            append("输出仍会作为后续整合的输入；务必保留事件、人物变化、伏笔与未解决事项。")
            append("建议本轮输出约 ").append(targetTokens).append(" 字（系统估算值）。\n\n")
        }
        nodes.forEachIndexed { index, node ->
            append("【阶段结果 ").append(index + 1)
            append("｜覆盖 ").append(node.rangeLabel)
            append("｜章节ID ").append(node.chapterIds.joinToString("、"))
            append("】\n")
            append(node.content.trim()).append("\n\n")
        }
    }

    /** 把结构化提取结果渲染成可读文本（只用于整合输入，最终展示不会是 JSON 拼接） */
    fun renderExtraction(result: CompressionBatchResult): String = buildString {
        val extraction = result.extraction
        append("覆盖范围：").append(result.batch.range.label)
        if (result.batch.isSliced) {
            append("（含片段 ").append(result.batch.chapters.joinToString("、") { it.label }).append("）")
        }
        append("（章节ID ").append(result.batch.chapterIds.joinToString("、")).append("）\n")
        append("一、重大事件\n")
        extraction.events.forEach { event ->
            append("- ").append(event.text)
            if (event.chapterIds.isNotEmpty()) {
                append("〔章节 ").append(event.chapterIds.joinToString("、")).append("〕")
            }
            append('\n')
        }
        append("二、参与者\n")
        extraction.participants.forEach { append("- ").append(it).append('\n') }
        append("三、起因与结果\n")
        append("- 起因：").append(extraction.cause).append('\n')
        append("- 结果：").append(extraction.result).append('\n')
        append("四、人物状态变化\n")
        appendOrNone(extraction.characterChanges)
        append("五、人物关系变化\n")
        appendOrNone(extraction.relationshipChanges)
        append("六、伏笔\n")
        appendOrNone(extraction.foreshadowing)
        append("七、未解决事项\n")
        appendOrNone(extraction.unresolvedIssues)
        append("八、重要细节\n")
        appendOrNone(extraction.importantDetails)
        append("九、支线\n")
        appendOrNone(extraction.subplots)
    }.trimEnd()

    private fun StringBuilder.appendOrNone(items: List<String>) {
        if (items.isEmpty()) {
            append("- 无\n")
        } else {
            items.forEach { append("- ").append(it).append('\n') }
        }
    }
}
