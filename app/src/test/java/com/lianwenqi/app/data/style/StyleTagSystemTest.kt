package com.lianwenqi.app.data.style

import com.lianwenqi.app.data.StyleTagRules
import com.lianwenqi.app.data.dao.StyleChunkTagRow
import com.lianwenqi.app.data.entity.ChapterSummaryTag
import com.lianwenqi.app.data.entity.GLOBAL_STYLE_TAG_SCOPE
import com.lianwenqi.app.data.entity.StyleSampleChunk
import com.lianwenqi.app.data.entity.StyleTag
import com.lianwenqi.app.data.entity.StyleTagSource
import com.lianwenqi.app.data.entity.SummaryTagSource
import com.lianwenqi.app.util.encodeVector
import com.lianwenqi.app.util.shortHash
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标签名称 / 分类规范化与去重口径 */
class StyleTagRulesTest {
    @Test
    fun `名称与分类会去掉多余空白`() {
        assertEquals("人物 对话", StyleTagRules.normalizeName("  人物 \n 对话  "))
        assertEquals("场景 类型", StyleTagRules.normalizeCategory(" 场景  类型 "))
        assertEquals(StyleTagRules.DEFAULT_CATEGORY, StyleTagRules.normalizeCategory("   "))
    }

    @Test
    fun `去重键大小写不敏感`() {
        assertEquals(
            StyleTagRules.key("表达方式", "Irony"),
            StyleTagRules.key(" 表达方式 ", " irony ")
        )
        assertTrue(StyleTagRules.sameName("压抑", " 压抑 "))
        assertFalse(StyleTagRules.sameName("压抑", "气氛压抑"))
    }
}

/** 应用层权限校验：模型输出的 id 必须真实存在且不跨档案 */
class StyleTagResolverTest {
    private fun tag(id: Long, scope: Long, category: String, name: String) =
        StyleTag(id = id, styleProfileId = scope, category = category, name = name, source = StyleTagSource.USER)

    private val profileId = 7L
    private val globals = listOf(
        tag(1, GLOBAL_STYLE_TAG_SCOPE, "情绪氛围", "压抑"),
        tag(2, GLOBAL_STYLE_TAG_SCOPE, "表达方式", "潜台词")
    )
    private val profileTags = listOf(
        tag(11, profileId, "表达方式", "细节递进"),
        tag(12, 8L, "表达方式", "其他档案标签")
    )

    @Test
    fun `只接受本次选中的全局标签`() {
        val result = StyleTagResolver.resolve(
            profileId = profileId,
            suggestions = listOf(StyleChunkTagSuggestion(100, listOf(1, 2), emptyList())),
            newTags = emptyList(),
            validChunkIds = setOf(100),
            userTags = globals,
            profileTags = profileTags,
            createProfileTag = { null },
            allowedUserTagIds = setOf(1)
        )
        assertEquals(listOf(1L), result.single().tagIds)
    }

    @Test
    fun `跨档案与不存在的 id 会被丢弃`() {
        val result = StyleTagResolver.resolve(
            profileId = profileId,
            suggestions = listOf(StyleChunkTagSuggestion(100, listOf(12, 999, 11), emptyList())),
            newTags = emptyList(),
            validChunkIds = setOf(100),
            userTags = globals,
            profileTags = profileTags,
            createProfileTag = { null }
        )
        assertEquals(listOf(11L), result.single().tagIds)
    }

    @Test
    fun `新标签同名时复用已有档案标签而不是重复创建`() {
        var created = 0
        val result = StyleTagResolver.resolve(
            profileId = profileId,
            suggestions = listOf(StyleChunkTagSuggestion(100, emptyList(), listOf("T1"))),
            newTags = listOf(StyleNewTagSuggestion("T1", "表达方式", "细节递进", "", "")),
            validChunkIds = setOf(100),
            userTags = globals,
            profileTags = profileTags,
            createProfileTag = { created++; tag(13, profileId, "表达方式", it.name) }
        )
        assertEquals(0, created)
        assertEquals(listOf(11L), result.single().tagIds)
    }

    @Test
    fun `新标签与全局标签同名时复用全局标签且不写入档案`() {
        var created = 0
        val result = StyleTagResolver.resolve(
            profileId = profileId,
            suggestions = listOf(StyleChunkTagSuggestion(100, emptyList(), listOf("T1"))),
            newTags = listOf(StyleNewTagSuggestion("T1", "情绪氛围", "压抑", "", "")),
            validChunkIds = setOf(100),
            userTags = globals,
            profileTags = profileTags,
            createProfileTag = { created++; tag(13, profileId, "情绪氛围", it.name) }
        )
        assertEquals(0, created)
        assertEquals(listOf(1L), result.single().tagIds)
    }

    @Test
    fun `完全新的标签才会写入当前档案`() {
        var created = 0
        val result = StyleTagResolver.resolve(
            profileId = profileId,
            suggestions = listOf(StyleChunkTagSuggestion(100, emptyList(), listOf("T2"))),
            newTags = listOf(StyleNewTagSuggestion("T2", "叙事节奏", "快节奏切换", "", "")),
            validChunkIds = setOf(100),
            userTags = globals,
            profileTags = profileTags,
            createProfileTag = { created++; tag(21, profileId, "叙事节奏", it.name) }
        )
        assertEquals(1, created)
        assertEquals(listOf(21L), result.single().tagIds)
    }

    @Test
    fun `未知片段与重复标签会被过滤`() {
        val result = StyleTagResolver.resolve(
            profileId = profileId,
            suggestions = listOf(
                StyleChunkTagSuggestion(999, listOf(1), emptyList()),
                StyleChunkTagSuggestion(100, listOf(1, 1, 11), emptyList())
            ),
            newTags = emptyList(),
            validChunkIds = setOf(100),
            userTags = globals,
            profileTags = profileTags,
            createProfileTag = { null }
        )
        assertEquals(1, result.size)
        assertEquals(listOf(1L, 11L), result.single().tagIds)
    }
}

/** 模型标签 JSON 解析：合法、围栏、缺字段、非法 */
class StyleTagAnalysisCodecTest {
    @Test
    fun `合法 JSON 解析出片段与新建标签`() {
        val raw = """
            {"chunks":[{"chunkId":100,"tagIds":[1,2],"newTagKeys":["T1"]}],
             "newTags":[{"key":"T1","category":"表达方式","name":"潜台词交锋","description":"表面平静实则试探","evidence":"片段中对话"}]}
        """.trimIndent()
        val result = StyleTagAnalysisCodec.parse(raw)
        assertTrue(result is StyleTagParseResult.Success)
        val parsed = (result as StyleTagParseResult.Success).result
        assertEquals(listOf(1L, 2L), parsed.chunks.single().tagIds)
        assertEquals(listOf("T1"), parsed.chunks.single().newTagKeys)
        assertEquals("潜台词交锋", parsed.newTags.single().name)
    }

    @Test
    fun `新标签只需要 key 与 name`() {
        val raw = """
            {"chunks":[{"chunkId":100,"tagIds":[],"newTagKeys":["T1"]}],
             "newTags":[{"key":"T1","name":"场景"}]}
        """.trimIndent()
        val result = StyleTagAnalysisCodec.parse(raw)
        assertTrue(result is StyleTagParseResult.Success)
        val tag = (result as StyleTagParseResult.Success).result.newTags.single()
        assertEquals("场景", tag.name)
        assertEquals(StyleTagAnalysisCodec.DEFAULT_NEW_TAG_CATEGORY, tag.category)
        assertEquals("", tag.description)
        assertEquals("", tag.evidence)
    }

    @Test
    fun `支持代码围栏和前后说明`() {
        val raw = "分析如下：\n```json\n{\"chunks\":[{\"chunkId\":100}]}\n```\n以上。"
        val result = StyleTagAnalysisCodec.parse(raw)
        assertTrue(result is StyleTagParseResult.Success)
        assertTrue((result as StyleTagParseResult.Success).result.newTags.isEmpty())
    }

    @Test
    fun `缺少 chunkId 或非法 JSON 明确失败`() {
        assertTrue(StyleTagAnalysisCodec.parse("{\"chunks\":[{}]}") is StyleTagParseResult.Failure)
        assertTrue(StyleTagAnalysisCodec.parse("not json") is StyleTagParseResult.Failure)
        assertTrue(StyleTagAnalysisCodec.parse("{\"chunks\":{}}") is StyleTagParseResult.Failure)
    }
}

/** 梗概标签解析与来源归属：不改动任何标签库 */
class SummaryTagTest {
    private val userTags = listOf(
        StyleTag(id = 1, styleProfileId = 0L, category = "情绪氛围", name = "压抑", source = StyleTagSource.USER)
    )
    private val profileTags = listOf(
        StyleTag(id = 11, styleProfileId = 7L, category = "表达方式", name = "潜台词", source = StyleTagSource.AI)
    )

    @Test
    fun `梗概与句子标签结构化输出解析`() {
        val raw = "{\"synopsis\":\"小明发现小红要离开，两人争执。\",\"sentences\":[{\"text\":\"小明发现小红要离开，两人争执。\",\"tags\":[{\"id\":11}]}]}"
        val parsed = SummaryWithTagsCodec.parse(raw)
        assertNotNull(parsed)
        assertEquals("小明发现小红要离开，两人争执。", parsed!!.synopsis)
        assertEquals(1, parsed.sentences.size)
        assertEquals(11L, parsed.sentences.single().tags.single().id)
    }

    @Test
    fun `标签解析失败时必须保留梗概语义`() {
        assertNull(SummaryWithTagsCodec.parse("这只是一段普通梗概文字"))
        assertNull(SummaryWithTagsCodec.parse("{\"tags\":[{\"name\":\"人物对话\"}]}"))
        assertNotNull(SummaryTagCodec.parse("{\"tags\":[]}"))
        assertNull(SummaryTagCodec.parse("不是 JSON"))
    }

    @Test
    fun `梗概变化后哈希不同可用于过期判断`() {
        assertTrue(shortHash("原文") != shortHash("原文（修改后）"))
        assertEquals(shortHash("原文"), shortHash("原文"))
    }
}

/** 正文生成前的文风样本检索：档案硬过滤、标签加权、向量排序、不足不补足、失败降级 */
class StyleSampleRetrieverTest {
    private fun chunk(id: Long, profileId: Long, text: String, vector: FloatArray? = null) =
        StyleSampleChunk(
            id = id,
            sampleId = 10L + id,
            styleProfileId = profileId,
            chunkIndex = id.toInt(),
            text = text,
            hash = "h$id",
            dim = vector?.size ?: 0,
            vector = vector?.let { encodeVector(it) }
        )

    private fun row(chunkId: Long, name: String, category: String = "场景类型") =
        StyleChunkTagRow(chunkId = chunkId, tagId = 100 + chunkId, category = category, name = name, scope = 7L, source = "USER")

    private fun chapterTag(name: String, category: String = "场景类型") =
        ChapterSummaryTag(chapterId = 1, category = category, name = name, summaryHash = "h")

    private fun retriever(
        chunks: List<StyleSampleChunk>,
        rows: Map<Long, List<StyleChunkTagRow>>,
        embed: suspend (List<String>) -> Result<List<FloatArray>>
    ) = StyleSampleRetriever(
        loadChunks = { pid -> chunks.filter { it.styleProfileId == pid } },
        loadTagRows = { rows },
        embed = embed
    )

    private val queryVector = floatArrayOf(1f, 0f)

    @Test
    fun `未绑定档案时不检索`() = runTest {
        val result = retriever(emptyList(), emptyMap()) { Result.success(listOf(queryVector)) }
            .retrieve(profileId = 0L, summary = "梗概", chapterTags = emptyList())
        assertEquals(0, result.hits.size)
        assertTrue(result.reason.contains("未绑定"))
    }

    @Test
    fun `标签加权可以压过稍高的向量相似度`() = runTest {
        val tagged = chunk(1, 7L, "带标签片段", floatArrayOf(1f, 0f))
        val untagged = chunk(2, 7L, "无标签片段", floatArrayOf(1f, 0f))
        val result = retriever(listOf(tagged, untagged), mapOf(1L to listOf(row(1, "人物对话")))) {
            Result.success(listOf(queryVector))
        }.retrieve(profileId = 7L, summary = "本章梗概", chapterTags = listOf(chapterTag("人物对话")))

        assertEquals(2, result.hits.size)
        assertEquals(1L, result.hits.first().chunkId)
        assertTrue(result.hits.first().tagScore > 0.0)
    }

    @Test
    fun `没有标签命中时按向量相似度排序`() = runTest {
        val close = chunk(1, 7L, "接近", floatArrayOf(1f, 0f))
        val far = chunk(2, 7L, "不相关", floatArrayOf(0f, 1f))
        val result = retriever(listOf(far, close), emptyMap()) {
            Result.success(listOf(queryVector))
        }.retrieve(profileId = 7L, summary = "本章梗概", chapterTags = emptyList())

        assertTrue(result.hits.size >= 1)
        assertEquals(1L, result.hits.first().chunkId)
        assertTrue(result.vectorUsed)
    }

    @Test
    fun `不要求全部标签匹配也能命中`() = runTest {
        val onlyOne = chunk(1, 7L, "只匹配一个标签", floatArrayOf(1f, 0f))
        val result = retriever(listOf(onlyOne), mapOf(1L to listOf(row(1, "人物对话")))) {
            Result.success(listOf(queryVector))
        }.retrieve(
            profileId = 7L,
            summary = "本章梗概",
            chapterTags = listOf(chapterTag("人物对话"), chapterTag("压抑", "情绪氛围"), chapterTag("动作表现心理", "描写方式"))
        )
        assertTrue(result.hits.isNotEmpty())
    }

    @Test
    fun `候选不足时只返回现有数量`() = runTest {
        val only = chunk(1, 7L, "只有一个", floatArrayOf(1f, 0f))
        val result = retriever(listOf(only), emptyMap()) {
            Result.success(listOf(queryVector))
        }.retrieve(profileId = 7L, summary = "本章梗概", chapterTags = emptyList(), limit = 2)
        assertEquals(1, result.hits.size)
    }

    @Test
    fun `向量不可用时仍可依靠标签命中`() = runTest {
        val tagged = chunk(1, 7L, "带标签片段", floatArrayOf(1f, 0f))
        val result = retriever(listOf(tagged), mapOf(1L to listOf(row(1, "人物对话")))) {
            Result.failure(RuntimeException("embedding offline"))
        }.retrieve(profileId = 7L, summary = "本章梗概", chapterTags = listOf(chapterTag("人物对话")))

        assertTrue(result.hits.isNotEmpty())
        assertFalse(result.vectorUsed)
    }

    @Test
    fun `无标签且向量不可用时不强行返回结果`() = runTest {
        val chunk = chunk(1, 7L, "片段")
        val result = retriever(listOf(chunk), emptyMap()) {
            Result.failure(RuntimeException("embedding offline"))
        }.retrieve(profileId = 7L, summary = "本章梗概", chapterTags = emptyList())
        assertEquals(0, result.hits.size)
    }

    @Test
    fun `只会加载当前档案的片段`() = runTest {
        val mine = chunk(1, 7L, "本档案", floatArrayOf(1f, 0f))
        val other = chunk(2, 8L, "其他档案", floatArrayOf(1f, 0f))
        val result = retriever(listOf(mine, other), emptyMap()) {
            Result.success(listOf(queryVector))
        }.retrieve(profileId = 7L, summary = "本章梗概", chapterTags = emptyList())

        assertEquals(listOf(1L), result.hits.map { it.chunkId })
    }
}

/** 片段标签分析的分批预算：过大片段独占一批，不丢片段 */
class StyleTaggingPlanTest {
    private fun chunk(id: Long, text: String) = StyleSampleChunk(
        id = id,
        sampleId = 1L,
        styleProfileId = 7L,
        chunkIndex = id.toInt(),
        text = text,
        hash = "h$id"
    )

    @Test
    fun `按字符预算分批且不丢片段`() {
        val chunks = listOf(chunk(1, "a".repeat(10)), chunk(2, "b".repeat(10)), chunk(3, "c".repeat(30)))
        val batches = StyleTaggingService.planBatchesOf(chunks, budget = 20)
        // 前两个片段合计恰好等于预算，可以同批；第三个片段超预算后独占一批
        assertEquals(2, batches.size)
        assertEquals(listOf(1L, 2L), batches[0].map { it.id })
        assertEquals(listOf(3L), batches[1].map { it.id })
        assertEquals(3, batches.sumOf { it.size })
    }

    @Test
    fun `预算为 0 时仍能完成分批`() {
        val chunks = listOf(chunk(1, "a"), chunk(2, "b"))
        val batches = StyleTaggingService.planBatchesOf(chunks, budget = 0)
        assertEquals(2, batches.size)
    }
}
