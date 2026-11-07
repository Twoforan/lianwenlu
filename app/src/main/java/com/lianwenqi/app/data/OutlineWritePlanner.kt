package com.lianwenqi.app.data

import com.lianwenqi.app.data.entity.OutlineItem
import com.lianwenqi.app.data.volume.outlineContentFingerprint
import com.lianwenqi.app.data.volume.outlineContentIsFieldJson

/** 一次确认里针对单条大纲的最小写入指令；由已持久化的建议映射而来 */
data class OutlineWriteCommand(
    val suggestionId: Long,
    val operation: String,
    val targetOutlineItemId: Long?,
    val category: String,
    val title: String,
    val newContent: String,
    val expectedUpdatedAt: Long,
    val expectedFingerprint: String
)

/** 写入结果：冲突 / 无效绝不报告为成功 */
sealed interface OutlineWriteOutcome {
    val suggestionId: Long

    data class Applied(
        override val suggestionId: Long,
        val operation: String,
        val targetId: Long,
        val content: String
    ) : OutlineWriteOutcome

    data class Conflict(
        override val suggestionId: Long,
        val message: String,
        val currentContent: String
    ) : OutlineWriteOutcome

    data class Invalid(
        override val suggestionId: Long,
        val message: String
    ) : OutlineWriteOutcome

    data class Failed(
        override val suggestionId: Long,
        val message: String
    ) : OutlineWriteOutcome
}

/**
 * 纯逻辑写入计划器：把建议与当前真实条目做条件校验，产出「可直接执行」的最小变更。
 *
 * - 只按 id 定位，标题仅用于展示；
 * - expectedUpdatedAt 或内容指纹任一不一致即冲突，绝不覆盖用户在预览期间的编辑；
 * - UPDATE 只改标题 / 内容，分类、标签、排序、id 等未涉及数据保持原样；
 * - ADD 不伪造 id，交由数据库自增。
 *
 * 与 Room 解耦，便于单测覆盖冲突、范围外 ID、同名不同 ID 等边界。
 */
object OutlineWritePlanner {

    sealed interface Plan {
        val suggestionId: Long

        data class Update(
            override val suggestionId: Long,
            val current: OutlineItem,
            val next: OutlineItem
        ) : Plan

        data class Add(
            override val suggestionId: Long,
            val item: OutlineItem
        ) : Plan

        data class Delete(
            override val suggestionId: Long,
            val current: OutlineItem
        ) : Plan

        data class Conflict(
            override val suggestionId: Long,
            val message: String,
            val currentContent: String
        ) : Plan

        data class Invalid(
            override val suggestionId: Long,
            val message: String
        ) : Plan
    }

    fun plan(
        bookId: Long,
        commands: List<OutlineWriteCommand>,
        currentById: Map<Long, OutlineItem>,
        nextSortOrder: Int,
        now: Long = System.currentTimeMillis()
    ): List<Plan> {
        var sortOrder = nextSortOrder
        return commands.map { command ->
            when (command.operation) {
                "ADD" -> {
                    if (command.title.isBlank() && !outlineContentIsFieldJson(command.newContent)) {
                        Plan.Invalid(command.suggestionId, "新增条目缺少标题")
                    } else if (command.category.isBlank()) {
                        Plan.Invalid(command.suggestionId, "新增条目缺少分类")
                    } else if (command.newContent.isBlank()) {
                        Plan.Invalid(command.suggestionId, "新增条目内容为空")
                    } else {
                        Plan.Add(
                            command.suggestionId,
                            OutlineItem(
                                bookId = bookId,
                                category = command.category,
                                title = command.title,
                                content = command.newContent,
                                sortOrder = sortOrder++,
                                updatedAt = now
                            )
                        )
                    }
                }

                "UPDATE", "DELETE" -> {
                    val targetId = command.targetOutlineItemId
                        ?: return@map Plan.Invalid(command.suggestionId, "缺少目标大纲 id")
                    val current = currentById[targetId]
                        ?: return@map Plan.Invalid(
                            command.suggestionId,
                            "目标 id=$targetId 不存在或已删除"
                        )
                    if (current.bookId != bookId) {
                        return@map Plan.Invalid(
                            command.suggestionId,
                            "目标 id=$targetId 不属于当前书籍"
                        )
                    }
                    val fingerprintMismatch = outlineContentFingerprint(current.content) !=
                        command.expectedFingerprint
                    val updatedAtMismatch = command.expectedUpdatedAt > 0 &&
                        current.updatedAt != command.expectedUpdatedAt
                    if (fingerprintMismatch || updatedAtMismatch) {
                        return@map Plan.Conflict(
                            command.suggestionId,
                            "预览期间该大纲已被修改，未覆盖；请重新查看当前内容",
                            current.content
                        )
                    }
                    if (command.operation == "DELETE") {
                        Plan.Delete(command.suggestionId, current)
                    } else if (command.newContent.isBlank()) {
                        Plan.Invalid(command.suggestionId, "更新内容为空")
                    } else {
                        Plan.Update(
                            command.suggestionId,
                            current,
                            current.copy(
                                title = command.title.ifBlank { current.title },
                                content = command.newContent,
                                updatedAt = now
                            )
                        )
                    }
                }

                else -> Plan.Invalid(command.suggestionId, "未知操作：${command.operation}")
            }
        }
    }
}
