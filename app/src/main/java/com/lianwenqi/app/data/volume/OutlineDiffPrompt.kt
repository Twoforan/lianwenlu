package com.lianwenqi.app.data.volume

/**
 * F6：大纲差异分析提示词。
 *
 * 独立请求，不写入普通聊天历史；系统前缀逐字节稳定，变量只出现在 user 内容。
 * 提示词明确区分「本卷已经发生的事实」与「大纲里的未来规划」，
 * 禁止把未来规划改成已发生剧情，也禁止要求重写整本大纲。
 */
object OutlineDiffPrompt {

    const val SYSTEM: String =
        "你是长篇小说的大纲同步编辑。你会收到一份『本卷已经发生的剧情梗概』和一组『现有大纲条目』，" +
            "需要判断哪些大纲条目应该新增、更新或删除。\n" +
            "规则：\n" +
            "1. 以本卷梗概为事实依据。只同步本卷已经发生的事实；不得虚构未写出的情节。\n" +
            "2. 必须区分『本卷已经发生的事实』与『大纲中的未来规划』。大纲里尚未写到的未来规划" +
            "不得被改写成本卷已经发生；如果两者冲突，只补充本卷已经发生的变化，不要删除未来规划。\n" +
            "3. 只做最小必要修改：输出针对单条大纲条目的局部更新，不重写整本大纲，不输出全书大纲全文。\n" +
            "4. 更新目标只能使用给定条目中的 id。禁止按标题匹配，禁止猜测范围外的 id，" +
            "禁止为同一个 id 输出多条建议。\n" +
            "5. 只有确实与本卷事实直接相关、且现有条目缺少或写错时才给出建议；没有必要时返回空数组。\n" +
            "6. 删除只用于本卷事实已明确推翻、且继续保留会误导后续写作的条目；不确定就不要删除。\n" +
            "7. 字段型条目（content 为 [{\"n\":标签,\"v\":内容}]）必须继续输出 fields 字段数组，" +
            "保持原有字段名与结构，只修改受影响的字段，不要压平成纯文本。\n" +
            "8. 新增条目必须给出目标分类 category 与 fields；分类只能是给定分类之一。" +
            "新增时 targetOutlineItemId 必须为 null。\n" +
            "9. oldContent 必须逐字复制给定条目当前的 content；expectedUpdatedAt 必须复制给定条目的 updatedAt。\n" +
            "10. 剧情梗概仅用于限定事实来源和判断本次同步范围，不是大纲内容的叙述主体。" +
            "生成或修改大纲时，必须直接以小说世界中的客观事实进行陈述，" +
            "使用脱离章节、卷次和创作过程也能独立理解的百科式描述。" +
            "严禁在 title、newContent、fields 中出现「本卷中」之类叙述方式，" +
            "也不得使用其他含义相同的章节总结、卷总结或创作过程描述。\n" +
            "11. 只输出一个 JSON 对象，不要 Markdown 代码围栏，不要解释文字。输出结构：\n" +
            "{\"suggestions\":[{" +
            "\"operation\":\"ADD|UPDATE|DELETE\"," +
            "\"targetOutlineItemId\":条目id或null," +
            "\"category\":\"分类键\"," +
            "\"title\":\"条目名称\"," +
            "\"oldContent\":\"给定条目的当前 content（ADD 为空字符串）\"," +
            "\"newContent\":\"纯文本条目的新内容（字段型条目必须为空字符串）\"," +
            "\"fields\":[{\"n\":\"字段名\",\"v\":\"字段内容\"}]," +
            "\"reason\":\"为什么要改，对应本卷哪些事实\"," +
            "\"confidence\":0.0到1.0的小数," +
            "\"relatedEvents\":[\"本卷相关事件...\"]," +
            "\"expectedUpdatedAt\":给定条目的 updatedAt（ADD 为 0）" +
            "}]}"

    fun user(request: OutlineDiffRequest): String = buildString {
        append("【书籍】").append(request.bookTitle.ifBlank { "（未命名）" }).append('\n')
        if (request.bookIntro.isNotBlank()) {
            append("【书籍简介】").append(request.bookIntro.trim().take(MAX_BOOK_INTRO)).append('\n')
        }
        append("【本卷】").append(request.volumeTitle.ifBlank { "（未命名分卷）" }).append('\n')
        append("【本卷已经发生的剧情梗概】\n")
        append(request.summary.trim()).append("\n\n")

        append("【本书大纲分类】\n")
        if (request.categories.isEmpty()) {
            append("- 无\n")
        } else {
            request.categories.forEach { category ->
                append("- ").append(category.key).append("（").append(category.label).append("）")
                if (category.fieldNames.isNotEmpty()) {
                    append(" 常用字段：").append(category.fieldNames.joinToString("、"))
                }
                append('\n')
            }
        }
        append('\n')

        append("【本次范围内的现有大纲条目】（共 ").append(request.items.size).append(" 条）\n")
        if (request.items.isEmpty()) {
            append("- 无：本次范围没有可更新的条目，只能给出 ADD 或返回空数组。\n")
        } else {
            request.items.forEach { item ->
                append("### 条目 id=").append(item.id)
                append("｜分类=").append(item.category)
                append("｜标题=").append(item.title.ifBlank { "（无标题）" })
                append("｜updatedAt=").append(item.updatedAt)
                append('\n')
                if (item.isFieldContent) {
                    append("字段结构：\n")
                    item.fields.forEach { (name, value) ->
                        append("- ").append(name).append("：").append(value.replace('\n', ' ')).append('\n')
                    }
                    append("content 原文：").append(item.content).append('\n')
                } else {
                    append("content：").append(item.content.trim()).append('\n')
                }
                append('\n')
            }
        }
        append("请只输出约定结构的 JSON 对象；没有需要同步的变更时输出 {\"suggestions\":[]}。")
    }

    private const val MAX_BOOK_INTRO = 400
}
