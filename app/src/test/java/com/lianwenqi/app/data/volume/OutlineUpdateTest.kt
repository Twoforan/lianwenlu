package com.lianwenqi.app.data.volume

import com.lianwenqi.app.ai.AiRequest
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.data.CompressionOutlineScopeMode
import com.lianwenqi.app.data.CompressionOutlineUpdateMode
import com.lianwenqi.app.data.OutlineWriteCommand
import com.lianwenqi.app.data.OutlineWritePlanner
import com.lianwenqi.app.data.decodeOutlineFields
import com.lianwenqi.app.data.encodeOutlineFields
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.OutlineCategory
import com.lianwenqi.app.data.entity.OutlineItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F6：范围并集、严格 JSON 解析、三态策略与防覆盖写入计划。
 * 全部为纯 JVM 测试；Room 迁移与真实模型不在本文件覆盖（见交付说明）。
 */
class OutlineUpdateTest {

    private val bookId = 10L

    private fun item(
        id: Long,
        category: String,
        title: String,
        content: String = "内容$id",
        updatedAt: Long = 1000L + id,
        tag: String = "",
        sortOrder: Int = id.toInt(),
        updatable: Boolean = false
    ) = OutlineItem(
        id = id,
        bookId = bookId,
        category = category,
        tag = tag,
        title = title,
        content = content,
        sortOrder = sortOrder,
        updatedAt = updatedAt,
        updatable = updatable
    )

    private fun fieldContent(id: Long, title: String, extra: Pair<String, String> = "身份" to "主角") =
        encodeOutlineFields(listOf("显示标签" to title, extra))

    private fun diffRequest(items: List<OutlineItem>) = OutlineDiffRequest(
        bookId = bookId,
        bookTitle = "测试书",
        bookIntro = "简介",
        volumeTitle = "第一卷",
        summary = "本卷中主角完成了第一次抉择。",
        items = items.map {
            OutlineDiffItem(it.id, it.category, it.title, it.content, it.updatedAt)
        },
        categories = listOf(
            OutlineCategoryTemplate(OutlineCategory.STORY.name, "故事大纲"),
            OutlineCategoryTemplate(OutlineCategory.CHARACTER.name, "人设", listOf("身份", "状态")),
            OutlineCategoryTemplate(OutlineCategory.WORLD.name, "世界观")
        ),
        modelId = 7L
    )

    // ---------------- 范围解析 ----------------

    @Test
    fun `updatable scope only returns marked items`() {
        val items = listOf(
            item(1, OutlineCategory.STORY.name, "主线", updatable = true),
            item(2, OutlineCategory.CHARACTER.name, "主角"),
            item(3, OutlineCategory.WORLD.name, "宗门", updatable = true),
            item(4, OutlineCategory.WORLD.name, "城邦")
        )
        val resolved = OutlineScopeResolver.resolve(
            items = items,
            scopeMode = CompressionOutlineScopeMode.UPDATABLE
        )
        assertEquals(listOf(1L, 3L), resolved.itemIds)
        assertEquals(2, resolved.totalCount)
    }

    @Test
    fun `updatable scope can be empty`() {
        val items = listOf(item(1, OutlineCategory.STORY.name, "主线"))
        val resolved = OutlineScopeResolver.resolve(
            items = items,
            scopeMode = CompressionOutlineScopeMode.UPDATABLE
        )
        assertEquals(emptyList<Long>(), resolved.itemIds)
        assertEquals(0, resolved.totalCount)
    }

    @Test
    fun `whole book ignores updatable flag`() {
        val items = listOf(
            item(1, OutlineCategory.STORY.name, "主线"),
            item(2, OutlineCategory.CHARACTER.name, "主角", updatable = true)
        )
        val resolved = OutlineScopeResolver.resolve(
            items = items,
            scopeMode = CompressionOutlineScopeMode.WHOLE_BOOK
        )
        assertEquals(listOf(1L, 2L), resolved.itemIds)
    }

    @Test
    fun `scope snapshot round trips ids only`() {
        val resolved = OutlineScopeResolved(itemIds = listOf(3L, 1L, 3L))
        assertEquals(listOf(3L, 1L), OutlineScopeResolved.decodeIds(resolved.encodeIds()))
    }

    // ---------------- 严格解析 ----------------

    @Test
    fun `update suggestion keeps field json structure`() {
        val original = item(1, OutlineCategory.CHARACTER.name, "主角", fieldContent(1, "主角"))
        val newFields = encodeOutlineFields(
            listOf("显示标签" to "主角", "身份" to "主角", "状态" to "已觉醒")
        )
        val raw = """
            {"suggestions":[{
              "operation":"UPDATE",
              "targetOutlineItemId":1,
              "title":"主角",
              "oldContent":${jsonString(original.content)},
              "newContent":"",
              "fields":[{"n":"显示标签","v":"主角"},{"n":"身份","v":"主角"},{"n":"状态","v":"已觉醒"}],
              "reason":"本卷主角觉醒",
              "confidence":0.9,
              "relatedEvents":["觉醒"],
              "expectedUpdatedAt":${original.updatedAt}
            }]}
        """.trimIndent()
        val result = OutlineDiffCodec.parse(raw, diffRequest(listOf(original)))
        val suggestion = (result as OutlineDiffCodec.Result.Success).suggestions.single()
        assertEquals(OutlineOperation.UPDATE, suggestion.operation)
        assertEquals(1L, suggestion.targetOutlineItemId)
        assertTrue(outlineContentIsFieldJson(suggestion.newContent))
        assertEquals(decodeOutlineFields(newFields), decodeOutlineFields(suggestion.newContent))
        assertEquals(OutlineSuggestionStatus.PENDING, suggestion.status)
    }

    @Test
    fun `field update merges untouched fields and only clears explicit blanks`() {
        val original = item(
            1,
            OutlineCategory.CHARACTER.name,
            "主角",
            encodeOutlineFields(
                listOf(
                    "显示标签" to "主角",
                    "身份" to "主角",
                    "状态" to "未觉醒",
                    "备注" to "旧备注"
                )
            )
        )
        val raw = """
            {"suggestions":[{
              "operation":"UPDATE","targetOutlineItemId":1,"title":"主角",
              "oldContent":${jsonString(original.content)},
              "fields":[{"n":"状态","v":"已觉醒"},{"n":"备注","v":""}],
              "reason":"本卷觉醒","confidence":0.9,"expectedUpdatedAt":${original.updatedAt}
            }]}
        """.trimIndent()
        val suggestion = (OutlineDiffCodec.parse(raw, diffRequest(listOf(original)))
            as OutlineDiffCodec.Result.Success).suggestions.single()
        val fields = decodeOutlineFields(suggestion.newContent)
        assertEquals("主角", fields.first { it.first == "显示标签" }.second)
        assertEquals("主角", fields.first { it.first == "身份" }.second)
        assertEquals("已觉醒", fields.first { it.first == "状态" }.second)
        assertTrue(fields.none { it.first == "备注" })
    }

    @Test
    fun `out of scope target becomes invalid`() {
        val raw = """
            {"suggestions":[{
              "operation":"UPDATE","targetOutlineItemId":99,
              "oldContent":"内容99","newContent":"新内容",
              "reason":"越界","confidence":0.9
            }]}
        """.trimIndent()
        val result = OutlineDiffCodec.parse(raw, diffRequest(listOf(item(1, OutlineCategory.STORY.name, "主线"))))
        val suggestion = (result as OutlineDiffCodec.Result.Success).suggestions.single()
        assertEquals(OutlineSuggestionStatus.INVALID, suggestion.status)
        assertFalse(suggestion.selectable)
    }

    @Test
    fun `same title different id updates only the id target`() {
        val a = item(1, OutlineCategory.STORY.name, "同名条目", "旧A")
        val b = item(2, OutlineCategory.STORY.name, "同名条目", "旧B")
        val raw = """
            {"suggestions":[{
              "operation":"UPDATE","targetOutlineItemId":2,"title":"同名条目",
              "oldContent":"旧B","newContent":"新B","reason":"按 id 更新","confidence":0.8,
              "expectedUpdatedAt":${b.updatedAt}
            }]}
        """.trimIndent()
        val result = OutlineDiffCodec.parse(raw, diffRequest(listOf(a, b)))
        val suggestion = (result as OutlineDiffCodec.Result.Success).suggestions.single()
        assertEquals(2L, suggestion.targetOutlineItemId)
        assertEquals("旧B", suggestion.oldContent)
        assertEquals(a.content, "旧A")
    }

    @Test
    fun `malformed json fails as a whole`() {
        val result = OutlineDiffCodec.parse("{not-json", diffRequest(emptyList()))
        assertTrue(result is OutlineDiffCodec.Result.Failure)
    }

    @Test
    fun `empty suggestions succeed without any write`() {
        val result = OutlineDiffCodec.parse("{\"suggestions\":[]}", diffRequest(emptyList()))
        val success = result as OutlineDiffCodec.Result.Success
        assertTrue(success.suggestions.isEmpty())
        assertTrue(success.note.contains("未发现"))
    }

    @Test
    fun `old content mismatch is marked conflict and not writable`() {
        val target = item(1, OutlineCategory.STORY.name, "主线", "当前内容", updatedAt = 55L)
        val raw = """
            {"suggestions":[{
              "operation":"UPDATE","targetOutlineItemId":1,
              "oldContent":"模型以为的旧内容","newContent":"新内容",
              "reason":"事实更新","confidence":0.95,"expectedUpdatedAt":55
            }]}
        """.trimIndent()
        val suggestion = (OutlineDiffCodec.parse(raw, diffRequest(listOf(target)))
            as OutlineDiffCodec.Result.Success).suggestions.single()
        assertEquals(OutlineSuggestionStatus.CONFLICT, suggestion.status)
        assertFalse(suggestion.selectable)
        assertFalse(suggestion.autoApplicable)
    }

    @Test
    fun `low confidence suggestion is visible but never auto applied`() {
        val target = item(1, OutlineCategory.STORY.name, "主线", "旧")
        val raw = """
            {"suggestions":[{
              "operation":"UPDATE","targetOutlineItemId":1,
              "oldContent":"旧","newContent":"新","reason":"不确定","confidence":0.3
            }]}
        """.trimIndent()
        val suggestion = (OutlineDiffCodec.parse(raw, diffRequest(listOf(target)))
            as OutlineDiffCodec.Result.Success).suggestions.single()
        assertEquals(OutlineSuggestionStatus.LOW_CONFIDENCE, suggestion.status)
        assertTrue(suggestion.selectable)
        assertFalse(OutlineUpdatePolicy.autoApplicable(CompressionOutlineUpdateMode.ON, suggestion))
        assertFalse(OutlineUpdatePolicy.initiallySelected(suggestion))
    }

    @Test
    fun `add requires category and never carries a target id`() {
        val request = diffRequest(listOf(item(1, OutlineCategory.STORY.name, "主线")))
        val valid = """
            {"suggestions":[{
              "operation":"ADD","targetOutlineItemId":null,"category":"CHARACTER","title":"配角",
              "fields":[{"n":"显示标签","v":"配角"},{"n":"身份","v":"同门"}],
              "reason":"新登场人物","confidence":0.9
            }]}
        """.trimIndent()
        val suggestion = (OutlineDiffCodec.parse(valid, request) as OutlineDiffCodec.Result.Success)
            .suggestions.single()
        assertEquals(OutlineOperation.ADD, suggestion.operation)
        assertNull(suggestion.targetOutlineItemId)
        assertTrue(outlineContentIsFieldJson(suggestion.newContent))

        val unknownCategory = valid.replace("CHARACTER", "FACTION")
        val invalid = (OutlineDiffCodec.parse(unknownCategory, request) as OutlineDiffCodec.Result.Success)
            .suggestions.single()
        assertEquals(OutlineSuggestionStatus.INVALID, invalid.status)

        val withId = valid.replace("\"targetOutlineItemId\":null", "\"targetOutlineItemId\":1")
        val invalid2 = (OutlineDiffCodec.parse(withId, request) as OutlineDiffCodec.Result.Success)
            .suggestions.single()
        assertEquals(OutlineSuggestionStatus.INVALID, invalid2.status)
    }

    @Test
    fun `delete is parsed but never auto applied nor selected by default`() {
        val target = item(1, OutlineCategory.WORLD.name, "旧设定", "旧设定内容")
        val raw = """
            {"suggestions":[{
              "operation":"DELETE","targetOutlineItemId":1,"title":"旧设定",
              "oldContent":"旧设定内容","reason":"本卷已推翻","confidence":0.99,
              "expectedUpdatedAt":${target.updatedAt}
            }]}
        """.trimIndent()
        val suggestion = (OutlineDiffCodec.parse(raw, diffRequest(listOf(target)))
            as OutlineDiffCodec.Result.Success).suggestions.single()
        assertEquals(OutlineOperation.DELETE, suggestion.operation)
        assertFalse(OutlineUpdatePolicy.autoApplicable(CompressionOutlineUpdateMode.ON, suggestion))
        assertFalse(OutlineUpdatePolicy.initiallySelected(suggestion))
        assertFalse(OutlineUpdatePolicy.safeForSelectAll(suggestion))
    }

    // ---------------- 三态与服务 ----------------

    @Test
    fun `off mode sends zero outline requests`() = runBlocking {
        var calls = 0
        val service = OutlineDiffService(
            modelLoader = { AiModel(id = 7L, providerId = 1L, name = "fake") },
            chat = { _, _ ->
                calls++
                AiResult.ok("{\"suggestions\":[]}")
            }
        )
        val outcome = service.analyze(
            diffRequest(listOf(item(1, OutlineCategory.STORY.name, "主线"))),
            CompressionOutlineUpdateMode.OFF
        )
        assertTrue(outcome is OutlineDiffOutcome.Skipped)
        assertEquals(0, calls)
    }

    @Test
    fun `ask mode returns suggestions and counts usage without writing`() = runBlocking {
        var requests = 0
        val service = OutlineDiffService(
            modelLoader = { AiModel(id = 7L, providerId = 1L, name = "fake") },
            chat = { _, request: AiRequest ->
                requests++
                assertTrue(request.systemPrompt.contains("未来规划"))
                AiResult.ok(
                    "{\"suggestions\":[]}",
                    promptTokens = 120,
                    completionTokens = 30,
                    cachedTokens = 10
                )
            }
        )
        val outcome = service.analyze(
            diffRequest(listOf(item(1, OutlineCategory.STORY.name, "主线"))),
            CompressionOutlineUpdateMode.ASK
        )
        val success = outcome as OutlineDiffOutcome.Success
        assertEquals(1, requests)
        assertEquals(1, success.usage.requestCount)
        assertEquals(120, success.usage.promptTokens)
        assertEquals(30, success.usage.completionTokens)
        assertEquals(10, success.usage.cachedTokens)
    }

    @Test
    fun `off mode keeps scope for later`() {
        val config = com.lianwenqi.app.data.VolumeCompressionConfig(
            outlineUpdateMode = CompressionOutlineUpdateMode.OFF,
            outlineScopeMode = CompressionOutlineScopeMode.UPDATABLE
        )
        val decoded = com.lianwenqi.app.data.VolumeCompressionConfig.decode(
            com.lianwenqi.app.data.VolumeCompressionConfig.encode(config)
        )
        assertFalse(decoded.outlineUpdateEnabled)
        assertEquals(CompressionOutlineScopeMode.UPDATABLE, decoded.outlineScopeMode)
    }

    @Test
    fun `only on mode may auto apply safe suggestions`() {
        val draft = OutlineSuggestionDraft(
            index = 0,
            operation = OutlineOperation.ADD,
            targetOutlineItemId = null,
            category = OutlineCategory.CHARACTER.name,
            title = "配角",
            oldContent = "",
            newContent = encodeOutlineFields(listOf("显示标签" to "配角")),
            reason = "新登场",
            confidence = 0.95
        )
        assertFalse(OutlineUpdatePolicy.autoApplicable(CompressionOutlineUpdateMode.ASK, draft))
        assertFalse(OutlineUpdatePolicy.autoApplicable(CompressionOutlineUpdateMode.OFF, draft))
        assertTrue(OutlineUpdatePolicy.autoApplicable(CompressionOutlineUpdateMode.ON, draft))
    }

    @Test
    fun `prompt separates happened facts from future planning`() {
        val prompt = OutlineDiffPrompt.user(diffRequest(listOf(item(1, OutlineCategory.STORY.name, "主线"))))
        assertTrue(prompt.contains("本卷已经发生的剧情梗概"))
        assertTrue(prompt.contains("id=1"))
        assertTrue(OutlineDiffPrompt.SYSTEM.contains("未来规划"))
        assertTrue(OutlineDiffPrompt.SYSTEM.contains("不得被改写成本卷已经发生"))
        assertTrue(OutlineDiffPrompt.SYSTEM.contains("不重写整本大纲"))
    }

    // ---------------- 写入计划 ----------------

    @Test
    fun `update plan keeps category tag id and rejects stale fingerprint`() {
        val current = item(1, OutlineCategory.CHARACTER.name, "主角", "旧内容", updatedAt = 50L, tag = "明线")
        val stale = OutlineWritePlanner.plan(
            bookId = bookId,
            commands = listOf(
                OutlineWriteCommand(
                    suggestionId = 9,
                    operation = "UPDATE",
                    targetOutlineItemId = 1,
                    category = OutlineCategory.CHARACTER.name,
                    title = "主角",
                    newContent = "新内容",
                    expectedUpdatedAt = 50L,
                    expectedFingerprint = outlineContentFingerprint("用户刚改过的内容")
                )
            ),
            currentById = mapOf(1L to current),
            nextSortOrder = 5
        ).single()
        assertTrue(stale is OutlineWritePlanner.Plan.Conflict)
        assertEquals("旧内容", (stale as OutlineWritePlanner.Plan.Conflict).currentContent)

        val ok = OutlineWritePlanner.plan(
            bookId = bookId,
            commands = listOf(
                OutlineWriteCommand(
                    suggestionId = 9,
                    operation = "UPDATE",
                    targetOutlineItemId = 1,
                    category = OutlineCategory.CHARACTER.name,
                    title = "主角",
                    newContent = "新内容",
                    expectedUpdatedAt = 50L,
                    expectedFingerprint = outlineContentFingerprint("旧内容")
                )
            ),
            currentById = mapOf(1L to current),
            nextSortOrder = 5
        ).single()
        val update = ok as OutlineWritePlanner.Plan.Update
        assertEquals(1L, update.next.id)
        assertEquals(OutlineCategory.CHARACTER.name, update.next.category)
        assertEquals("明线", update.next.tag)
        assertEquals(1, update.next.sortOrder)
        assertEquals("新内容", update.next.content)
    }

    @Test
    fun `updatedAt mismatch is a conflict even when fingerprint matches`() {
        val current = item(1, OutlineCategory.STORY.name, "主线", "一样的内容", updatedAt = 999L)
        val plan = OutlineWritePlanner.plan(
            bookId = bookId,
            commands = listOf(
                OutlineWriteCommand(
                    suggestionId = 3,
                    operation = "DELETE",
                    targetOutlineItemId = 1,
                    category = OutlineCategory.STORY.name,
                    title = "主线",
                    newContent = "",
                    expectedUpdatedAt = 111L,
                    expectedFingerprint = outlineContentFingerprint("一样的内容")
                )
            ),
            currentById = mapOf(1L to current),
            nextSortOrder = 0
        ).single()
        assertTrue(plan is OutlineWritePlanner.Plan.Conflict)
    }

    @Test
    fun `missing target is invalid instead of a silent add`() {
        val plan = OutlineWritePlanner.plan(
            bookId = bookId,
            commands = listOf(
                OutlineWriteCommand(
                    suggestionId = 3,
                    operation = "UPDATE",
                    targetOutlineItemId = 88,
                    category = OutlineCategory.STORY.name,
                    title = "不存在",
                    newContent = "新",
                    expectedUpdatedAt = 0,
                    expectedFingerprint = ""
                )
            ),
            currentById = emptyMap(),
            nextSortOrder = 0
        ).single()
        assertTrue(plan is OutlineWritePlanner.Plan.Invalid)
    }

    @Test
    fun `add plan uses database generated id and field content`() {
        val content = encodeOutlineFields(listOf("显示标签" to "配角", "身份" to "同门"))
        val plan = OutlineWritePlanner.plan(
            bookId = bookId,
            commands = listOf(
                OutlineWriteCommand(
                    suggestionId = 5,
                    operation = "ADD",
                    targetOutlineItemId = null,
                    category = OutlineCategory.CHARACTER.name,
                    title = "配角",
                    newContent = content,
                    expectedUpdatedAt = 0,
                    expectedFingerprint = ""
                )
            ),
            currentById = emptyMap(),
            nextSortOrder = 3
        ).single() as OutlineWritePlanner.Plan.Add
        assertEquals(0L, plan.item.id)
        assertEquals(3, plan.item.sortOrder)
        assertEquals(content, plan.item.content)
        assertNotNull(decodeOutlineFields(plan.item.content).firstOrNull())
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
