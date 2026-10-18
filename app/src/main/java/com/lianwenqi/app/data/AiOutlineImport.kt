package com.lianwenqi.app.data

import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.entity.defaultOutlineKeys
import com.lianwenqi.app.data.entity.outlineCategoryLabel
import com.lianwenqi.app.util.AiOutlineBlock

/** 一次大纲导入的结果统计 */
data class AiOutlineImportResult(
    /** 成功写入的区块数 */
    val blocks: Int,
    /** 写入的大纲条目数（与区块数相同，保留给以后一块多条的情况） */
    val items: Int,
    /** 本次新建的分类标签名 */
    val newCategories: List<String>
)

/**
 * 把解析出的大纲区块写入某本书的作品大纲。
 * type → 分类标签（没有则新建）；
 * name → 条目「显示标签」：同一标签下已有同名条目时按其原有位置整条替换（字段全量覆盖），
 * 没有同名的则作为新条目追加到该标签末尾；
 * fields → 条目字段（key 为字段名，value 为内容）。
 */
class AiOutlineImporter(
    private val prefs: Prefs,
    private val outlineRepository: OutlineRepository
) {
    suspend fun import(bookId: Long, blocks: List<AiOutlineBlock>): AiOutlineImportResult {
        var categories = prefs.outlineCategories(bookId) ?: defaultOutlineKeys
        val newCategories = mutableListOf<String>()
        var items = 0
        // 同一次导入里后写的区块也要看到先写入的条目，所以每轮重新读取大纲
        blocks.forEach { block ->
            // 标签匹配：存储值或展示名（人设 / 世界观 / 故事大纲 / 灵感库）任一命中即归入该标签
            var categoryKey = categories.firstOrNull { key ->
                key == block.type || outlineCategoryLabel(key) == block.type
            }
            if (categoryKey == null) {
                categoryKey = block.type
                categories = categories + categoryKey
                prefs.setOutlineCategories(bookId, categories)
                newCategories += block.type
            }

            val existing = outlineRepository.getOutline(bookId).filter { it.category == categoryKey }
            val displayName = block.name
            val fields = listOf(OUTLINE_HEAD_LABEL to displayName) + block.fields
            // 同名条目替换：保留原 id 与排序位置，字段整条覆盖；其余作为新条目追加
            val sameName = existing.firstOrNull { displayNameOf(it) == displayName }
            outlineRepository.saveOutline(
                if (sameName != null) {
                    sameName.copy(
                        title = displayName,
                        content = encodeOutlineFields(fields)
                    )
                } else {
                    OutlineItem(
                        bookId = bookId,
                        category = categoryKey,
                        title = displayName,
                        content = encodeOutlineFields(fields),
                        sortOrder = existing.size
                    )
                }
            )
            items++

            // 把新字段名并进该标签的字段模板，之后手动新增条目时自动带上这些字段
            val template = prefs.outlineFields(bookId, categoryKey)
            val keys = block.fields.map { it.first }.filter { it != OUTLINE_HEAD_LABEL }
            when {
                template != null ->
                    prefs.setOutlineFields(bookId, categoryKey, (template + keys).distinct())
                block.type in newCategories ->
                    prefs.setOutlineFields(bookId, categoryKey, (listOf(OUTLINE_HEAD_LABEL) + keys).distinct())
            }
        }
        return AiOutlineImportResult(blocks = blocks.size, items = items, newCategories = newCategories)
    }

    /** 条目的对外显示名：首项「显示标签」的值，旧数据回落到标题 */
    private fun displayNameOf(item: OutlineItem): String =
        decodeOutlineFields(item.content).firstOrNull()?.second?.ifBlank { item.title } ?: item.title
}
