package com.lianwenqi.app.data.volume

import com.lianwenqi.app.data.CompressionOutlineScopeMode
import com.lianwenqi.app.data.entity.OutlineItem

/**
 * 大纲更新范围解析（纯计算，不读写数据库）。
 *
 * - [CompressionOutlineScopeMode.WHOLE_BOOK]：整本书的全部大纲条目；
 * - [CompressionOutlineScopeMode.UPDATABLE]：只包含被标记「可更新」的条目。
 *
 * 范围始终按解析时的最新条目计算，只输出真实 id，不按标题匹配，也不创建任何平行大纲实体。
 */
object OutlineScopeResolver {

    fun resolve(
        items: List<OutlineItem>,
        scopeMode: String = CompressionOutlineScopeMode.WHOLE_BOOK
    ): OutlineScopeResolved {
        val ids = if (CompressionOutlineScopeMode.normalize(scopeMode) ==
            CompressionOutlineScopeMode.UPDATABLE
        ) {
            items.filter { it.updatable }.map { it.id }
        } else {
            items.map { it.id }
        }
        return OutlineScopeResolved(itemIds = ids.distinct())
    }
}
