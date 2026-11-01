package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagSource
import com.lianwenqi.app.data.entity.StyleSampleChunk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** 模型返回的新标签建议（只代表建议，尚未写入任何标签库） */
data class StyleNewTagSuggestion(
    val key: String,
    val category: String,
    val name: String,
    val description: String,
    val evidence: String
)

/** 单个片段的标签建议：引用已有标签 id + 引用本批次新标签 key */
data class StyleChunkTagSuggestion(
    val chunkId: Long,
    val tagIds: List<Long>,
    val newTagKeys: List<String>
)

/** 解析后的标签分析结果 */
data class StyleTagAnalysisResult(
    val chunks: List<StyleChunkTagSuggestion>,
    val newTags: List<StyleNewTagSuggestion>
)

sealed interface StyleTagParseResult {
    data class Success(val result: StyleTagAnalysisResult, val json: String) : StyleTagParseResult
    data class Failure(val message: String) : StyleTagParseResult
}

/**
 * 文风样本标签分析的提示词。
 *
 * 只允许模型输出「引用已有标签 id / 新标签建议」，不允许模型直接操作数据库。
 * 新标签只能写入当前文风档案；用户全局标签库对 AI 只读。
 */
object StyleTagAnalysisPrompt {

    val SYSTEM: String = """
你是文风样本分类器。

你的任务是阅读用户提供的文本切片，为每个切片判断其主要描写内容，并分配简洁、宽泛的类型标签。

一、标签原则
标签只用于说明切片描写的内容类型，不用于分析写作技巧、语言风格、表达效果或文学手法。
标签应当简短、宽泛、直观，通常使用 2～4 个汉字。
推荐标签示例：场景、环境、对话、动作、外貌、心理、情绪、天气、物品、人物、动物、植物、食物、战斗、事件。
以上仅为示例，不是固定标签全集。应根据实际文本选择合适标签。

二、标签判断规则
1. 判断每个切片实际描写的对象或内容。
2. 优先使用已有标签，不要随意创造新标签。
3. 一个切片可以关联多个标签，但只选择确实适用的标签。
4. 不需要为了让每个切片都有标签而强行分类。
5. 如果一个切片没有明确可归类的内容，可以不分配标签。
6. 不要根据单个关键词判断，应结合整个切片的内容。
7. 不要将一个具体情节、人物关系、情绪状态或写作技巧创造为标签。
8. 不要创建过于细碎、含义相近或重复的标签。
9. 如果已有标签能够表达该切片的内容，不得创建新标签。
10. 只有现有标签确实无法覆盖该切片的主要描写内容时，才建议新增宽泛的类型标签。

三、禁止生成的标签
以下类型的标签一律禁止：
- 写作技巧：例如细节刻画、层层递进、对比烘托。
- 表达效果：例如氛围营造、情绪渲染、画面感。
- 复杂文学手法：例如潜台词交锋、心理博弈、意识流。
- 过于具体的情节：例如深夜追杀、雨中告白、师徒争执。
- 过于细碎的表达描述：例如平静中暗藏敌意、克制的悲伤。
- 对原文内容的总结句或解释句。
特别注意：「对话」是合适的标签，「潜台词交锋」不是合适的标签；「场景」是合适的标签，「压抑阴森的场景氛围」不是合适的标签；「心理」是合适的标签，「细腻复杂的心理变化」不是合适的标签。

四、新标签规则
如果确实需要新增标签：
- 标签名称必须是宽泛的内容类型。
- 名称尽量控制在 2～4 个汉字。
- 只填写 key 与 name，不添加分类、描述、定义、证据或解释。
- 不得与现有标签语义重复。
- 不得把具体情节或写作技巧作为新标签。

五、输出要求
严格按照指定 JSON 结构返回：
{
  "chunks": [
    {"chunkId": 7, "tagIds": [1, 12], "newTagKeys": ["T1"]},
    {"chunkId": 9, "tagIds": [], "newTagKeys": []}
  ],
  "newTags": [
    {"key": "T1", "name": "场景"}
  ]
}
要求：
1. chunks 必须覆盖所有输入切片。
2. tagIds 只能使用输入提供的已有标签 ID。
3. newTagKeys 只能引用本次新增标签的 key。
4. newTags 只包含确实需要新增的标签。
5. 新标签只填写 key、name，不要输出 category、description、evidence 等任何其它字段。
6. 不输出 JSON 以外的解释、Markdown 或文字。
7. 确保 JSON 格式正确。
""".trim()

    /** 单次请求的片段与标签候选；[chunks] 已按 token 预算分批 */
    fun userContent(
        chunks: List<StyleSampleChunk>,
        selectedUserTags: List<StyleTag>,
        profileTags: List<StyleTag>
    ): String = buildString {
        append("【可用全局标签（第一优先级）】\n")
        if (selectedUserTags.isEmpty()) {
            append("（全局标签库为空）\n")
        } else {
            selectedUserTags.forEach { appendTag(it) }
        }
        append("\n【当前文风档案已有标签（第二优先级，语义相符请复用）】\n")
        if (profileTags.isEmpty()) {
            append("（档案暂无标签）\n")
        } else {
            profileTags.forEach { appendTag(it) }
        }
        append("\n【待标注文本片段】\n")
        chunks.forEach { chunk ->
            append("片段 #").append(chunk.id).append("（第 ").append(chunk.chunkIndex + 1).append(" 段）\n")
            append(chunk.text.trim()).append("\n\n")
        }
        append("请按系统规则输出 JSON。")
    }.trim()

    private fun StringBuilder.appendTag(tag: StyleTag) {
        append("- id=").append(tag.id)
        append(" 分类=").append(tag.category)
        append(" 名称=").append(tag.name)
        if (tag.description.isNotBlank()) append(" 定义=").append(tag.description)
        append('\n')
    }
}

/** 宽容解析：允许代码围栏、前后说明文字；任何字段不合规都明确失败，不静默猜测 */
object StyleTagAnalysisCodec {

    /** AI 新标签只给 key / name，分类由应用层统一补为「描写类型」 */
    const val DEFAULT_NEW_TAG_CATEGORY = "描写类型"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): StyleTagParseResult {
        val candidate = extractJson(raw) ?: return StyleTagParseResult.Failure("未找到 JSON 对象")
        val root = runCatching { json.parseToJsonElement(candidate) as? JsonObject }.getOrNull()
            ?: return StyleTagParseResult.Failure("返回内容不是合法 JSON 对象")

        val chunksElement = root["chunks"] as? JsonArray
            ?: return StyleTagParseResult.Failure("缺少 chunks 数组")
        val newTagsElement = root["newTags"] as? JsonArray ?: JsonArray(emptyList())

        val chunks = mutableListOf<StyleChunkTagSuggestion>()
        chunksElement.forEachIndexed { index, element ->
            val obj = element as? JsonObject
                ?: return StyleTagParseResult.Failure("chunks[$index] 不是对象")
            val chunkId = obj.long("chunkId")
                ?: return StyleTagParseResult.Failure("chunks[$index] 缺少 chunkId")
            val ids = obj.longArray("tagIds") ?: emptyList()
            val keys = obj.stringArray("newTagKeys") ?: emptyList()
            chunks += StyleChunkTagSuggestion(chunkId, ids, keys)
        }

        val newTags = mutableListOf<StyleNewTagSuggestion>()
        newTagsElement.forEachIndexed { index, element ->
            val obj = element as? JsonObject
                ?: return StyleTagParseResult.Failure("newTags[$index] 不是对象")
            val key = obj.string("key")?.trim().orEmpty()
            val name = obj.string("name")?.trim().orEmpty()
            if (key.isEmpty() || name.isEmpty()) {
                return StyleTagParseResult.Failure("newTags[$index] 缺少 key / name")
            }
            newTags += StyleNewTagSuggestion(
                key = key,
                category = DEFAULT_NEW_TAG_CATEGORY,
                name = name,
                // 新规则下 AI 不再输出定义与证据，应用层统一留空
                description = "",
                evidence = ""
            )
        }
        return StyleTagParseResult.Success(
            StyleTagAnalysisResult(chunks = chunks, newTags = newTags),
            candidate
        )
    }

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.longArray(key: String): List<Long>? {
        val array = this[key] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.intOrNull?.toLong() } }
    }

    private fun JsonObject.stringArray(key: String): List<String>? {
        val array = this[key] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }

    /** 优先代码围栏内容，其次首尾花括号之间；避免前后解释文字干扰 */
    internal fun extractJson(raw: String): String? {
        val text = raw.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(text)
        if (fence != null) {
            val body = fence.groupValues[1].trim()
            if (body.startsWith("{") && body.endsWith("}")) return body
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        return null
    }
}

/** 应用层最终采用的标签引用 */
data class ResolvedChunkTags(
    val chunkId: Long,
    val tagIds: List<Long>
)

/**
 * 纯逻辑：把模型建议转换成可落库的标签 id。
 * 这是权限校验的最后一关：任何不存在的 id、跨档案的 id 都会被丢弃；
 * 新标签只会通过 [createProfileTag] 写入档案。便于单元测试，不依赖 Room。
 */
object StyleTagResolver {

    fun resolve(
        profileId: Long,
        suggestions: List<StyleChunkTagSuggestion>,
        newTags: List<StyleNewTagSuggestion>,
        validChunkIds: Set<Long>,
        userTags: List<StyleTag>,
        profileTags: List<StyleTag>,
        createProfileTag: (StyleNewTagSuggestion) -> StyleTag?,
        /** null = 不限制；非 null 时全局标签只接受本次选中的 id */
        allowedUserTagIds: Set<Long>? = null
    ): List<ResolvedChunkTags> {
        val globalById = userTags.associateBy { it.id }
        val profileById = profileTags.filter { it.styleProfileId == profileId }.associateBy { it.id }

        val newTagByKey = mutableMapOf<String, Long>()
        newTags.forEach { suggestion ->
            val name = suggestion.name.trim()
            if (name.isEmpty()) return@forEach
            // 同名标签优先复用（不考虑模型给出的分类差异）
            globalById.values.firstOrNull { it.name == name }?.let {
                newTagByKey[suggestion.key] = it.id
                return@forEach
            }
            profileById.values.firstOrNull { it.name == name }?.let {
                newTagByKey[suggestion.key] = it.id
                return@forEach
            }
            createProfileTag(suggestion)?.let { newTagByKey[suggestion.key] = it.id }
        }

        return suggestions.mapNotNull { suggestion ->
            if (suggestion.chunkId !in validChunkIds) return@mapNotNull null
            val ids = mutableListOf<Long>()
            suggestion.tagIds.forEach { id ->
                val global = globalById[id]
                val profile = profileById[id]
                when {
                    // 用户全局标签：必须真实存在；若本次指定了允许集合，只接受本次选中的
                    global != null && (allowedUserTagIds == null || id in allowedUserTagIds) -> ids += id
                    profile != null -> ids += id
                }
            }
            suggestion.newTagKeys.forEach { key ->
                newTagByKey[key]?.let { ids += it }
            }
            ResolvedChunkTags(suggestion.chunkId, ids.distinct())
        }
    }

    /** 新标签建议的稳定去重键 */
    fun dedupeKey(suggestion: StyleNewTagSuggestion): String =
        suggestion.name.trim().lowercase()
}
