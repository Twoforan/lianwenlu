package com.lianwenqi.app.ai

/** 「生成正文」时记忆检索的配置三态：开 / 关 / 询问 */
enum class RetrievalMode { ON, OFF, ASK }

/** [RetrievalMode] 在 DataStore 里的存储值 */
const val RETRIEVAL_ON = "on"
const val RETRIEVAL_OFF = "off"
const val RETRIEVAL_ASK = "ask"

/** 存储值 → 三态（未知值回退为「询问」） */
fun retrievalModeOf(storage: String): RetrievalMode = when (storage) {
    RETRIEVAL_ON -> RetrievalMode.ON
    RETRIEVAL_OFF -> RetrievalMode.OFF
    else -> RetrievalMode.ASK
}

/** 三态 → 存储值 */
val RetrievalMode.storage: String
    get() = when (this) {
        RetrievalMode.ON -> RETRIEVAL_ON
        RetrievalMode.OFF -> RETRIEVAL_OFF
        RetrievalMode.ASK -> RETRIEVAL_ASK
    }

/** 生成正文「一次性收集」阶段要弹出的弹窗类型 */
enum class GenerationDialogKind { PICK_SUMMARY_OUTLINES, ASK_MEMORY }

/**
 * 生成正文「一次性收集」阶段的弹窗顺序，**顺序固定**，前一个确定后才弹下一个：
 * ① 选梗概参考标签（仅「同时生成梗概」且「限定梗概范围」开启时）；
 * ② 询问是否启用记忆检索（仅配置为「询问」时）。
 * 返回空列表表示无需弹窗，可直接进入执行阶段。
 */
fun contentDialogOrder(
    mode: GenerationMode,
    limitSummaryScope: Boolean,
    memoryAsk: Boolean
): List<GenerationDialogKind> = buildList {
    if (mode == GenerationMode.CONTENT_WITH_SUMMARY && limitSummaryScope) {
        add(GenerationDialogKind.PICK_SUMMARY_OUTLINES)
    }
    // 记忆检索只服务于生成正文，「生成梗概」不涉及
    if (memoryAsk && mode != GenerationMode.SUMMARY_ONLY) add(GenerationDialogKind.ASK_MEMORY)
}

/** 单次生成的模式 */
enum class GenerationMode {
    /** 只生成梗概（侧边抽屉的「生成梗概」按钮） */
    SUMMARY_ONLY,

    /** 生成正文的同时先生成梗概（并静默写入本章梗概） */
    CONTENT_WITH_SUMMARY,

    /** 只生成正文（本章梗概已存在或无需生成） */
    CONTENT_ONLY
}

/**
 * 生成流程状态机。
 *
 * 强制约束：[Executing] 阶段禁止弹出任何需要用户输入的选择型弹窗。
 * 所有弹窗都在 [CollectingConfig] 阶段按序弹完并记录进 [GenerationPlan]；
 * 用户在任何一次弹窗中取消 → 置 [Cancelled]，plan 作废，不发出任何模型请求。
 */
enum class FlowState {
    Idle,
    /** 一次性收集所有需用户输入的决策 */
    CollectingConfig,
    /** 只读 plan，不再弹任何窗 */
    Executing,
    Done,
    Cancelled,
    Failed
}

/**
 * 单次生成计划：所有用户决策必须在执行前收集完毕，执行阶段只读不弹。
 */
data class GenerationPlan(
    val mode: GenerationMode,
    /** 梗概字数区间（0..500；可空表示本次不约束） */
    val summaryWordRange: IntRange?,
    /** 正文字数区间（0..6000；可空表示本次不约束） */
    val contentWordRange: IntRange?,
    /** 限定梗概范围时用户选中的大纲条目 id；非限定模式为空 */
    val selectedOutlineIds: List<Long> = emptyList(),
    /** 已从 ASK 解析为 ON/OFF 的最终决策 */
    val memoryRetrieval: RetrievalMode = RetrievalMode.OFF,
    /** 用户选定的记忆检索章节范围；null 表示全书章节 */
    val memoryChapterIds: Set<Long>? = null
)

/** 记忆检索范围弹窗里的单个章节 */
data class MemoryScopeChapter(val id: Long, val title: String, val volumeId: Long?)

/** 记忆检索范围弹窗里的分卷 */
data class MemoryScopeVolume(val id: Long, val title: String)

/** 记忆检索范围弹窗的数据：章节按分卷折叠，默认全部章节 */
data class MemoryScopePick(
    val chapters: List<MemoryScopeChapter>,
    val volumes: List<MemoryScopeVolume>,
    val selected: Set<Long>
)

/** 收集阶段需要弹出的选择型弹窗（UI 按此渲染，onDismiss 一律映射为「取消 → 终止」） */
sealed interface GenerationDialog {
    /** 限定梗概范围：让用户按大纲条目多选，确定后作为参考资料 */
    data object PickSummaryOutlines : GenerationDialog

    /** 询问是否启用记忆检索；[queryDigest] 展示将以本章梗概构造的检索 query 摘要 */
    data class AskMemoryRetrieval(val queryDigest: String) : GenerationDialog

    /** 开启「记忆检索是否限定范围」时，确认启用检索后选择检索章节 */
    data class PickMemoryScope(val pick: MemoryScopePick) : GenerationDialog

    /** 梗概子调用失败：显式三选项（重试梗概 / 使用已有梗概 / 取消） */
    data class SummaryFailed(val hasExisting: Boolean) : GenerationDialog

    /** 纯提示（如「请先填写本章梗概」），点确定即终止 */
    data class Notice(val text: String) : GenerationDialog
}

/** 生成流程的最终结果 */
sealed interface GenerationOutcome {
    /** 生成成功；[summary] 非空表示本次同时产出了梗概（需写回本章梗概） */
    data class Done(val summary: String? = null) : GenerationOutcome

    /** 用户在收集阶段取消，或执行阶段主动取消：未发出 / 已中止模型请求 */
    data object Cancelled : GenerationOutcome

    /** 生成失败 */
    data class Failed(val message: String) : GenerationOutcome
}

/** 梗概子调用失败时用户的三选项 */
enum class SummaryFailureChoice { RETRY, USE_EXISTING, CANCEL }
