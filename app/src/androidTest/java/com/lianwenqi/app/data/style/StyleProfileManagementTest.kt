package com.lianwenqi.app.data.style

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.BookRepository
import com.lianwenqi.app.data.StyleRepository
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 文风档案编辑与删除的真实 Room 行为（内存库） */
@RunWith(AndroidJUnit4::class)
class StyleProfileManagementTest {

    private lateinit var db: AppDatabase
    private lateinit var styleRepo: StyleRepository
    private lateinit var bookRepo: BookRepository
    private lateinit var updater: StyleProfileUpdater

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        styleRepo = StyleRepository(db)
        bookRepo = BookRepository(db)
        updater = StyleProfileUpdater(styleRepo)
    }

    @After
    fun tearDown() = db.close()

    private fun analysis(): StyleAnalysis =
        (StyleAnalysisCodec.parse(analysisJson()) as StyleAnalysisParseResult.Success).analysis

    /** 合法的结构化分析结果；只用于验证编辑链路，不代表真实样本结论 */
    private fun analysisJson(schemaVersion: String = "1"): String = """
        {
          "schemaVersion": $schemaVersion,
          "overview": "整体偏冷峻克制，叙述围绕人物的即时反应展开，很少直接评论",
          "narration": ["多采用第三人称限制视角，叙述者极少直接评论人物动机"],
          "sentenceRhythm": ["段落以中短句为主，关键动作处连续使用短句形成停顿"],
          "diction": ["动词密度高于形容词，情绪多通过动作与物件细节间接呈现"],
          "description": ["环境描写集中在光线与声音，人物描写偏向局部动作而非全貌"],
          "paragraphStructure": ["段落普遍较短，一段只推进一个动作或一个信息点"],
          "dialogue": ["证据不足：样本对话较少，无法判断对话长度与口吻"],
          "emotion": ["情绪表达克制，很少直接使用情绪标签，多由停顿与视线变化承载"],
          "transition": ["场景转换依靠时间标记与动作衔接，较少使用总结性过渡句"],
          "writingGuidelines": ["写人物迟疑时先写动作停顿，再用一句短对白交代态度","场景转换前用一个具体物件或声音作为落点"],
          "avoidances": ["避免连续使用抽象情绪形容词收束段落"],
          "evidenceAndConfidence": [
            {"dimension": "narration", "confidence": "high", "evidence": "全篇未见叙述者评论性插入"},
            {"dimension": "diction", "confidence": "medium", "evidence": "动作动词密集而形容词较少"}
          ]
        }
    """.trimIndent()

    private suspend fun createProfile(
        name: String = "冷峻叙事",
        json: String = analysisJson()
    ): StyleProfile {
        val id = styleRepo.createProfile(
            StyleProfile(
                name = name,
                sourceType = "CHAPTERS",
                sourceMeta = """{"sampling":{"analyzedSamples":2}}""",
                analysisJson = json,
                analysisVersion = 1,
                modelName = "测试模型"
            )
        )
        return styleRepo.getProfile(id)!!
    }

    @Test
    fun 修改名称与分析内容后回读一致() = runBlocking {
        val original = createProfile()
        val edited = StyleAnalysisEditor.toAnalysis(
            StyleAnalysisEditor.toForm(analysis()).copy(overview = "改写后的整体风格描述内容"),
            analysis()
        )

        val result = updater.update(original, "  冷峻叙事（改）  ", edited)

        assertTrue(result is StyleUpdateResult.Success)
        val saved = (result as StyleUpdateResult.Success).profile
        assertEquals("冷峻叙事（改）", saved.name)
        assertEquals(original.id, saved.id)
        assertEquals(original.createdAt, saved.createdAt)
        assertTrue(saved.updatedAt >= original.updatedAt)

        val reloaded = styleRepo.getProfile(original.id)!!
        assertEquals(saved.analysisJson, reloaded.analysisJson)
        val decoded = (StyleAnalysisCodec.parse(reloaded.analysisJson)
            as StyleAnalysisParseResult.Success).analysis
        assertEquals(edited, decoded)
        assertEquals(analysis().evidenceAndConfidence, decoded.evidenceAndConfidence)
    }

    @Test
    fun 空名称拒绝保存且原档案不变() = runBlocking {
        val original = createProfile()

        val result = updater.update(original, "   ", analysis())

        assertTrue(result is StyleUpdateResult.Invalid)
        val reloaded = styleRepo.getProfile(original.id)!!
        assertEquals(original.name, reloaded.name)
        assertEquals(original.analysisJson, reloaded.analysisJson)
    }

    @Test
    fun 同名档案拒绝保存且不覆盖原档案() = runBlocking {
        val first = createProfile(name = "冷峻叙事")
        val second = createProfile(name = "另一份文风", json = analysisJson())

        val result = updater.update(second, "冷峻叙事", analysis())

        assertEquals(StyleUpdateResult.DuplicateName, result)
        assertEquals("另一份文风", styleRepo.getProfile(second.id)!!.name)
        assertEquals(first.analysisJson, styleRepo.getProfile(first.id)!!.analysisJson)
        assertEquals(2, styleRepo.allProfiles().size)
    }

    @Test
    fun 非法分析结构拒绝保存且原档案不变() = runBlocking {
        val original = createProfile()
        val invalid = analysis().copy(sentenceRhythm = emptyList())

        val result = updater.update(original, original.name, invalid)

        assertTrue(result is StyleUpdateResult.Invalid)
        val reloaded = styleRepo.getProfile(original.id)!!
        assertEquals(original.analysisJson, reloaded.analysisJson)
    }

    @Test
    fun 未知版本仅改名时原样保留分析数据() = runBlocking {
        val oldJson = analysisJson(schemaVersion = "3")
        val original = createProfile(name = "旧版档案", json = oldJson)

        val result = updater.update(original, "旧版档案（改名）", analysis = null)

        assertTrue(result is StyleUpdateResult.Success)
        val reloaded = styleRepo.getProfile(original.id)!!
        assertEquals("旧版档案（改名）", reloaded.name)
        assertEquals(oldJson, reloaded.analysisJson)
        assertEquals(original.analysisVersion, reloaded.analysisVersion)
    }

    @Test
    fun 含未识别字段时未确认不保存() = runBlocking {
        val rawWithExtra = analysisJson().let {
            it.substringBeforeLast('}') + ""","legacyField":"历史遗留"}"""
        }
        val original = createProfile(json = rawWithExtra)

        val blocked = updater.update(original, original.name, analysis())
        assertTrue(blocked is StyleUpdateResult.Invalid)
        assertEquals(rawWithExtra, styleRepo.getProfile(original.id)!!.analysisJson)

        val confirmed = updater.update(
            original,
            original.name,
            analysis(),
            confirmUnknownFieldRemoval = true
        )
        assertTrue(confirmed is StyleUpdateResult.Success)
        val reloadedJson = styleRepo.getProfile(original.id)!!.analysisJson
        assertTrue(!reloadedJson.contains("legacyField"))
    }

    @Test
    fun 未确认删除时档案仍然存在() = runBlocking {
        val original = createProfile()

        // 取消删除只发生在界面层：不调用删除接口，数据必须保持原状
        assertNotNull(styleRepo.getProfile(original.id))
        assertEquals(1, styleRepo.allProfiles().size)
    }

    @Test
    fun 删除被引用档案后书籍解绑且其他数据不变() = runBlocking {
        val profile = createProfile()
        val bookId = bookRepo.createBook("书A", "简介内容", null, 0, "第%n卷", "第%n章")
        styleRepo.bindBook(bookId, profile.id)
        val before = db.bookDao().getById(bookId)!!
        assertEquals(profile.id, before.styleId)

        styleRepo.deleteProfile(profile.id)

        assertNull(styleRepo.getProfile(profile.id))
        assertTrue(styleRepo.allProfiles().isEmpty())
        val after = db.bookDao().getById(bookId)!!
        assertEquals(0L, after.styleId)
        assertEquals(before.title, after.title)
        assertEquals(before.intro, after.intro)
        assertEquals(before.volumePrefix, after.volumePrefix)
        assertEquals(before.chapterPrefix, after.chapterPrefix)
    }
}
