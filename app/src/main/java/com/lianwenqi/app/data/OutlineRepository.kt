package com.lianwenqi.app.data

import androidx.room.withTransaction
import com.lianwenqi.app.data.entity.OutlineItem
import kotlinx.coroutines.flow.Flow

/** 大纲领域：大纲条目（人设 / 世界观等分类标签下的内容） */
class OutlineRepository(private val db: AppDatabase) {
    private val outlineDao = db.outlineDao()

    fun observeOutline(bookId: Long): Flow<List<OutlineItem>> = outlineDao.observeByBook(bookId)

    suspend fun getOutline(bookId: Long): List<OutlineItem> = outlineDao.getByBook(bookId)

    /** F6：按范围快照读取真实条目；空列表直接返回，避免无意义的 IN () 查询 */
    suspend fun getByIds(ids: List<Long>): List<OutlineItem> {
        val distinct = ids.filter { it > 0 }.distinct()
        return if (distinct.isEmpty()) emptyList() else outlineDao.getByIds(distinct)
    }

    suspend fun getById(id: Long): OutlineItem? = outlineDao.getById(id)

    suspend fun saveOutline(item: OutlineItem) {
        if (item.id == 0L) {
            outlineDao.insert(item)
        } else {
            outlineDao.update(item.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteOutline(id: Long) = outlineDao.deleteById(id)

    /** 删除某个分类标签下的全部条目 */
    suspend fun deleteOutlineByCategory(bookId: Long, category: String) =
        outlineDao.deleteByCategory(bookId, category)

    /** 重命名某个分类标签，并同步迁移该标签下的全部条目 */
    suspend fun renameOutlineCategory(bookId: Long, from: String, to: String) =
        outlineDao.renameCategory(bookId, from, to)

    /**
     * F6：一次确认中的大纲写入统一在一个事务里完成。
     * 任何异常都会整体回滚，不会留下「半条写入」。
     */
    suspend fun <T> inTransaction(block: suspend OutlineRepository.() -> T): T =
        db.withTransaction { block() }

    /** 走现有创建流程：数据库自增 id，绝不预先伪造 [OutlineItem.id] */
    suspend fun insertOutline(item: OutlineItem): Long = outlineDao.insert(item)

    suspend fun updateOutline(item: OutlineItem) =
        outlineDao.update(item.copy(updatedAt = System.currentTimeMillis()))

    /** 设置条目是否参与卷梗概后的大纲更新 */
    suspend fun setOutlineUpdatable(id: Long, updatable: Boolean) =
        outlineDao.setUpdatable(id, updatable)

    /** 设置条目是否常驻化 */
    suspend fun setOutlinePinned(id: Long, pinned: Boolean) =
        outlineDao.setPinned(id, pinned)

    /** 整类批量切换「可更新」 */
    suspend fun setCategoryUpdatable(bookId: Long, category: String, updatable: Boolean) =
        outlineDao.setUpdatableByCategory(bookId, category, updatable)

    /** 整类批量切换「常驻化」 */
    suspend fun setCategoryPinned(bookId: Long, category: String, pinned: Boolean) =
        outlineDao.setPinnedByCategory(bookId, category, pinned)

    suspend fun deleteOutlineById(id: Long) = outlineDao.deleteById(id)

    /**
     * F6：在一个事务里执行一次确认的全部写入。
     *
     * 先重新读取目标并按 expectedUpdatedAt / 内容指纹做条件校验，
     * 冲突项只返回冲突结果、不覆盖；ADD 走数据库自增，
     * 不整表替换、不根据标题批量覆盖同名条目。
     */
    suspend fun applyOutlineWrites(
        bookId: Long,
        commands: List<OutlineWriteCommand>
    ): List<OutlineWriteOutcome> = db.withTransaction {
        if (commands.isEmpty()) return@withTransaction emptyList()
        val targetIds = commands.mapNotNull { it.targetOutlineItemId }.distinct()
        val current = if (targetIds.isEmpty()) emptyMap() else {
            outlineDao.getByIds(targetIds).associateBy { it.id }
        }
        val nextSortOrder = (outlineDao.getByBook(bookId).maxOfOrNull { it.sortOrder } ?: -1) + 1
        val now = System.currentTimeMillis()
        val plans = OutlineWritePlanner.plan(bookId, commands, current, nextSortOrder, now)
        plans.map { plan ->
            try {
                when (plan) {
                    is OutlineWritePlanner.Plan.Add -> {
                        val id = outlineDao.insert(plan.item)
                        OutlineWriteOutcome.Applied(
                            plan.suggestionId,
                            "ADD",
                            id,
                            plan.item.content
                        )
                    }

                    is OutlineWritePlanner.Plan.Update -> {
                        outlineDao.update(plan.next)
                        OutlineWriteOutcome.Applied(
                            plan.suggestionId,
                            "UPDATE",
                            plan.next.id,
                            plan.next.content
                        )
                    }

                    is OutlineWritePlanner.Plan.Delete -> {
                        outlineDao.deleteById(plan.current.id)
                        OutlineWriteOutcome.Applied(
                            plan.suggestionId,
                            "DELETE",
                            plan.current.id,
                            ""
                        )
                    }

                    is OutlineWritePlanner.Plan.Conflict -> OutlineWriteOutcome.Conflict(
                        plan.suggestionId,
                        plan.message,
                        plan.currentContent
                    )

                    is OutlineWritePlanner.Plan.Invalid -> OutlineWriteOutcome.Invalid(
                        plan.suggestionId,
                        plan.message
                    )
                }
            } catch (t: Throwable) {
                OutlineWriteOutcome.Failed(
                    plan.suggestionId,
                    t.message ?: "写入大纲失败"
                )
            }
        }
    }
}
