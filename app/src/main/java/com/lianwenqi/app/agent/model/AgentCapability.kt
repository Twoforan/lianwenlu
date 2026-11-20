package com.lianwenqi.app.agent.model

/**
 * 一条可规划能力。
 *
 * 是否可执行以 [com.lianwenqi.app.agent.execution.AgentExecutionPolicy] 为准；
 * 计划校验与规划提示都从这里读取能力目录，避免两处列表不一致。
 */
data class AgentCapability(
    val key: String,
    val displayName: String,
    val description: String
)

/**
 * Agent 能力目录。
 *
 * 模型只能使用这里的 key；未知 key 会由 Validator 标记为「未登记能力」。
 */
object AgentCapabilityCatalog {

    val all: List<AgentCapability> = listOf(
        AgentCapability("READ_STORY_CONTEXT", "读取小说上下文", "读取当前书籍的章节、梗概或正文作为分析依据"),
        AgentCapability("READ_BOOK_INFO", "读取书籍信息", "读取书籍标题、简介等基本信息"),
        AgentCapability("READ_OUTLINE", "读取大纲", "读取作品大纲条目"),
        AgentCapability("READ_CHAPTER_SYNOPSIS", "读取章节梗概", "读取指定章节的梗概"),
        AgentCapability("READ_CHAPTER_CONTENT", "读取章节正文", "读取指定章节正文"),
        AgentCapability("READ_CHAPTER_LIST", "读取章节列表", "列出当前书籍的章节与分卷，包含章节 id"),
        AgentCapability("READ_VOLUME_SYNOPSIS", "读取卷梗概", "读取整卷梗概"),
        AgentCapability("SEARCH_CHAPTERS", "检索章节", "按关键词检索章节标题、梗概或正文"),
        AgentCapability("SEARCH_OUTLINE", "检索大纲", "在大纲条目中检索"),
        AgentCapability("SEARCH_HISTORY_CONTENT", "检索历史正文", "按范围检索历史章节正文"),
        AgentCapability("SEARCH_STYLE_SAMPLES", "检索文风样本", "抽取文风样本片段"),
        AgentCapability("RETRIEVE_MEMORY", "记忆检索", "从长期记忆向量中召回相关片段"),
        AgentCapability("ANALYZE_CHARACTER_RELATIONSHIPS", "分析人物关系", "分析人物关系的连续性与冲突"),
        AgentCapability("ANALYZE_PLOT_CONSISTENCY", "分析剧情一致性", "检查剧情、设定与前文是否冲突"),
        AgentCapability("GENERATE_REPORT", "生成分析报告", "把分析结果整理为报告"),
        AgentCapability("GENERATE_SYNOPSIS", "生成章节梗概", "调用现有梗概生成能力生成草稿；只返回文本，不写入章节"),
        AgentCapability("GENERATE_CONTENT", "生成章节正文", "调用现有正文生成能力生成草稿；只返回文本，不写入章节"),
        AgentCapability("REWRITE_CONTENT", "重写正文", "按指令改写已有正文"),
        AgentCapability("WRITE_OUTLINE", "写入大纲", "写入或更新作品大纲"),
        AgentCapability("WRITE_CHAPTER_SYNOPSIS", "写入章节梗概", "写入或更新章节梗概"),
        AgentCapability("WRITE_CHAPTER_CONTENT", "写入章节正文", "覆盖章节正文（高风险，必须确认）"),
        AgentCapability("CREATE_OUTLINE_NODE", "创建大纲节点", "在当前书籍中创建大纲节点"),
        AgentCapability("UPDATE_OUTLINE_NODE", "修改大纲节点", "修改已有大纲节点的字段"),
        AgentCapability("DELETE_OUTLINE_NODE", "删除大纲节点", "删除指定大纲节点"),
        AgentCapability("CREATE_CHAPTER", "新建章节", "在当前书籍中新建章节"),
        AgentCapability("DELETE_CHAPTER", "删除章节", "删除指定章节（不可恢复）"),
        AgentCapability("CREATE_VOLUME", "新建分卷", "在当前书籍中新建分卷"),
        AgentCapability("DELETE_VOLUME", "删除分卷", "删除分卷及其中的全部章节（不可恢复）"),
        AgentCapability("VALIDATE_CONTENT", "校验内容", "对生成或修改的内容做校验"),
        AgentCapability("ASK_USER_CLARIFICATION", "请求用户补充", "向用户提出澄清问题")
    ).sortedBy { it.key }

    private val byKey = all.associateBy { it.key }

    fun find(key: String): AgentCapability? = byKey[key]

    fun keys(): List<String> = all.map { it.key }
}
