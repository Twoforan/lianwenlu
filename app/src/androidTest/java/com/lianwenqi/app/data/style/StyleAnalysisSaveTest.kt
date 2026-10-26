package com.lianwenqi.app.data.style

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.ModelEndpoint
import com.lianwenqi.app.data.AppDatabase
import com.lianwenqi.app.data.StyleRepository
import com.lianwenqi.app.data.entity.AiModel
import com.lianwenqi.app.data.entity.Provider
import com.lianwenqi.app.data.entity.StyleProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 分析草稿写入 F1 数据层的真实 Room 行为（内存库）。
 * 覆盖保存 / 回读 / 名称冲突不覆盖 / 校验失败不落库 / 未确认不自动落库。
 */
@RunWith(AndroidJUnit4::class)
class StyleAnalysisSaveTest {

    private lateinit var db: AppDatabase
    private lateinit var styleRepo: StyleRepository
    private lateinit var saver: StyleProfileSaver

    private val model = AiModel(id = 7L, providerId = 1L, name = "deepseek-chat", alias = "深度求索")
    private val endpoint = ModelEndpoint(
        provider = Provider(id = 1L, name = "测试供应商", baseUrl = "https://example.invalid/v1"),
        modelName = "deepseek-chat"
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        styleRepo = StyleRepository(db)
        saver = StyleProfileSaver(styleRepo)
    }

    @After
    fun tearDown() = db.close()

    private fun draft(overview: String = "整体偏冷峻克制，叙述围绕人物的即时反应展开"): StyleAnalysisDraft {
        val json = analysisJson(overview)
        val parsed = StyleAnalysisCodec.parse(json) as StyleAnalysisParseResult.Success
        val plan = StyleSampleSampler.plan(
            listOf(
                StyleSampleInput(
                    fileName = "雨夜样本.txt",
                    format = StyleSampleFormat.TXT,
                    order = 1,
                    charCount = 932,
                    text = "窗外雨声不断，他把杯子放回桌面。".repeat(40)
                )
            )
        )
        return StyleAnalysisDraft(
            analysis = parsed.analysis,
            analysisJson = parsed.json,
            sourceMeta = """{"schemaVersion":1,"sampling":{"strategy":"FULL"},"samples":[{"name":"雨夜样本.txt","chars":932}]}""",
            sourceType = com.lianwenqi.app.data.entity.StyleSourceType.CHAPTERS,
            modelName = "深度求索",
            modelIdentifier = "deepseek-chat",
            sampling = plan
        )
    }

    private fun analysisJson(overview: String): String = """
        {
          "schemaVersion": 1,
          "overview": "$overview",
          "narration": ["多采用第三人称限制视角，叙述者极少评论人物动机"],
          "sentenceRhythm": ["段落以中短句为主，关键动作处连续使用短句形成停顿"],
          "diction": ["动词密度高于形容词，情绪多通过动作与物件细节间接呈现"],
          "description": ["环境描写集中在光线与声音，人物描写偏向局部动作"],
          "paragraphStructure": ["段落普遍较短，一段只推进一个动作或一个信息点"],
          "dialogue": ["证据不足：样本对话较少，无法判断对话长度与口吻"],
          "emotion": ["情绪表达克制，很少直接使用情绪标签，多由停顿承载"],
          "transition": ["场景转换依靠时间标记与动作衔接，较少使用过渡句"],
          "writingGuidelines": ["写人物迟疑时先写动作停顿，再用一句短对白交代态度","场景转换前用一个具体物件作为落点"],
          "avoidances": ["避免连续使用抽象情绪形容词收束段落"],
          "evidenceAndConfidence": [
            {"dimension": "narration", "confidence": "high", "evidence": "全篇未见叙述者评论性插入"},
            {"dimension": "diction", "confidence": "medium", "evidence": "动作动词密集而形容词较少"}
          ]
        }
    """.trimIndent()

    @Test
    fun 保存后可重新读取且内容完整() = runBlocking {
        val result = saver.save("冷峻叙事", draft())
        assertTrue(result is StyleSaveResult.Success)
        val saved = (result as StyleSaveResult.Success).profile

        val loaded = styleRepo.getProfile(saved.id)!!
        assertEquals("冷峻叙事", loaded.name)
        assertEquals("CHAPTERS", loaded.sourceType)
        assertEquals("深度求索", loaded.modelName)
        assertEquals(1, loaded.analysisVersion)
        assertEquals(1, styleRepo.allProfiles().size)
        assertTrue(loaded.analysisJson.contains("\"schemaVersion\":1"))
        assertTrue(!loaded.sourceMeta.contains("窗外雨声不断"))
        val parsed = StyleAnalysisCodec.parse(loaded.analysisJson)
        assertTrue(parsed is StyleAnalysisParseResult.Success)
        assertEquals(
            "整体偏冷峻克制，叙述围绕人物的即时反应展开",
            (parsed as StyleAnalysisParseResult.Success).analysis.overview
        )
    }

    @Test
    fun 名称冲突不会覆盖已有档案() = runBlocking {
        val first = saver.save("冷峻叙事", draft()) as StyleSaveResult.Success
        val firstJson = styleRepo.getProfile(first.profile.id)!!.analysisJson

        val second = saver.save("冷峻叙事", draft(overview = "另一套完全不同的文风结论，需要保留原有档案"))
        assertTrue(second is StyleSaveResult.DuplicateName)
        assertEquals(1, styleRepo.allProfiles().size)
        assertEquals(firstJson, styleRepo.getProfile(first.profile.id)!!.analysisJson)
        assertEquals("冷峻叙事", styleRepo.findByName("冷峻叙事")!!.name)
    }

    @Test
    fun 空名称不会写入档案() = runBlocking {
        val result = saver.save("   ", draft())
        assertTrue(result is StyleSaveResult.Failure)
        assertTrue(styleRepo.allProfiles().isEmpty())
    }

    @Test
    fun 超限分析结果保存失败且不落库() = runBlocking {
        val oversized = draft().copy(
            analysisJson = """{"pad":"""" + "字".repeat(StyleProfile.MAX_ANALYSIS_JSON_BYTES) + """"}"""
        )
        val result = saver.save("超长档案", oversized)
        assertTrue(result is StyleSaveResult.Failure)
        assertTrue(styleRepo.allProfiles().isEmpty())
    }

    @Test
    fun 分析成功但未确认保存时不会自动落库() = runBlocking {
        val service = StyleAnalysisService(
            modelLoader = { model },
            endpointLoader = { endpoint },
            chat = { _, _ -> AiResult.ok(analysisJson("整体偏冷峻克制，叙述围绕人物的即时反应展开")) }
        )
        val outcome = service.analyze(
            7L,
            listOf(
                StyleSampleInput(
                    fileName = "雨夜样本.txt",
                    format = StyleSampleFormat.TXT,
                    order = 1,
                    charCount = 932,
                    text = "窗外雨声不断，他把杯子放回桌面。".repeat(40)
                )
            )
        )
        assertTrue(outcome is StyleAnalysisOutcome.Success)
        // 仅调用分析服务不会创建档案
        assertTrue(styleRepo.allProfiles().isEmpty())

        val saved = saver.save("冷峻叙事", (outcome as StyleAnalysisOutcome.Success).draft)
        assertTrue(saved is StyleSaveResult.Success)
        assertEquals(1, styleRepo.allProfiles().size)
    }
}
