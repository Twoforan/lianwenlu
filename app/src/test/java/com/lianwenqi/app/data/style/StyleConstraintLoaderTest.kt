package com.lianwenqi.app.data.style

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleConstraintLoaderTest {

    private fun loader(source: FakeStyleSource) = loaderOf(source)

    @Test
    fun unboundBook_isNormalState() = runBlocking {
        val source = FakeStyleSource().apply { bookStyles[1L] = 0L }
        val app = loader(source).apply(1L)
        assertEquals(BookStyleSnapshot.Unbound(1L), app.snapshot)
        assertEquals("", app.block)
        assertNull(app.notice)
    }

    @Test
    fun boundBook_loadsValidatedAnalysis() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L, "甲")
        }
        val app = loader(source).apply(1L)
        val snapshot = app.snapshot as BookStyleSnapshot.Loaded
        assertEquals(1L, snapshot.bookId)
        assertEquals(10L, snapshot.styleId)
        assertEquals("甲风档案", snapshot.styleName)
        assertEquals(STYLE_ANALYSIS_SCHEMA_VERSION, snapshot.schemaVersion)
        assertTrue(app.block.contains("甲书整体风格"))
        assertNull(app.notice)
    }

    @Test
    fun missingProfile_isNotReplacedByAnotherProfile() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 99L
            profiles[10L] = testProfile(10L, "甲")
        }
        val app = loader(source).apply(1L)
        val snapshot = app.snapshot as BookStyleSnapshot.Unavailable
        assertEquals(StyleLoadError.PROFILE_MISSING, snapshot.error)
        assertEquals(99L, snapshot.styleId)
        assertEquals("", app.block)
        assertNotNull(app.notice)
        assertFalse(app.block.contains("甲书"))
    }

    @Test
    fun invalidJson_isReportedAndNotRepaired() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L).copy(analysisJson = "{\"overview\":\"太短\"")
        }
        val app = loader(source).apply(1L)
        assertEquals(StyleLoadError.INVALID_JSON, (app.snapshot as BookStyleSnapshot.Unavailable).error)
        assertEquals("", app.block)
        assertNotNull(app.notice)
    }

    @Test
    fun invalidStructureOfSupportedVersion_isInvalidJson() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L).copy(
                analysisJson = "{\"schemaVersion\":1,\"overview\":\"这一段的长度足够通过概述校验\"}"
            )
        }
        val app = loader(source).apply(1L)
        assertEquals(StyleLoadError.INVALID_JSON, (app.snapshot as BookStyleSnapshot.Unavailable).error)
    }

    @Test
    fun unsupportedVersion_isReportedBeforeParsing() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L).copy(analysisJson = "{\"schemaVersion\":99}")
        }
        val app = loader(source).apply(1L)
        assertEquals(
            StyleLoadError.UNSUPPORTED_VERSION,
            (app.snapshot as BookStyleSnapshot.Unavailable).error
        )
        assertEquals("", app.block)
    }

    @Test
    fun emptyAnalysis_isReportedInsteadOfFallingBack() = runBlocking {
        // 合法但全部维度只有「证据不足」：不生成通用文风，明确告知本次无法应用
        val analysis = testAnalysis().copy(
            overview = "证据不足：样本文本过短，无法判断整体风格",
            narration = listOf("证据不足：无法判断叙述视角"),
            sentenceRhythm = listOf("证据不足：无法判断句式节奏"),
            diction = listOf("证据不足：无法判断用词倾向"),
            description = listOf("证据不足：无法判断描写方式"),
            paragraphStructure = listOf("证据不足：无法判断段落组织"),
            dialogue = listOf("证据不足：无法判断对话特征"),
            emotion = listOf("证据不足：无法判断情绪表达"),
            transition = listOf("证据不足：无法判断场景衔接"),
            writingGuidelines = listOf("证据不足：无法给出仿写指导", "证据不足：无法给出第二条指导"),
            avoidances = listOf("证据不足：无法判断应避免的写法"),
            evidenceAndConfidence = listOf(
                StyleEvidence(
                    dimension = "narration",
                    evidence = "样本过短，无法观察稳定特征",
                    feature = "叙述视角特征",
                    confidenceInterval = StyleConfidenceInterval(0, 100),
                    evidenceQuality = StyleEvidenceQuality.INSUFFICIENT,
                    sampleCoverage = StyleSampleCoverage.INSUFFICIENT,
                    applicability = "",
                    limitations = "样本篇幅过短"
                ),
                StyleEvidence(
                    dimension = "diction",
                    evidence = "样本过短，无法观察用词倾向",
                    feature = "用词倾向特征",
                    confidenceInterval = StyleConfidenceInterval(0, 100),
                    evidenceQuality = StyleEvidenceQuality.INSUFFICIENT,
                    sampleCoverage = StyleSampleCoverage.INSUFFICIENT,
                    applicability = "",
                    limitations = "样本篇幅过短"
                ),
                StyleEvidence(
                    dimension = "sentenceRhythm",
                    evidence = "样本过短，无法观察句式节奏",
                    feature = "句式节奏特征",
                    confidenceInterval = StyleConfidenceInterval(0, 100),
                    evidenceQuality = StyleEvidenceQuality.INSUFFICIENT,
                    sampleCoverage = StyleSampleCoverage.INSUFFICIENT,
                    applicability = "",
                    limitations = "样本篇幅过短"
                )
            )
        )
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L).copy(analysisJson = StyleAnalysisCodec.encode(analysis))
        }
        val app = loader(source).apply(1L)
        assertEquals(
            StyleLoadError.EMPTY_ANALYSIS,
            (app.snapshot as BookStyleSnapshot.Unavailable).error
        )
        assertEquals("", app.block)
    }

    @Test
    fun bookReadFailure_isRepositoryError() = runBlocking {
        val source = FakeStyleSource().apply { failBookRead = true }
        val app = loader(source).apply(1L)
        assertEquals(
            StyleLoadError.REPOSITORY_ERROR,
            (app.snapshot as BookStyleSnapshot.Unavailable).error
        )
        assertNotNull(app.notice)
    }

    @Test
    fun profileReadFailure_isRepositoryError() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            failProfileRead = true
        }
        val app = loader(source).apply(1L)
        assertEquals(
            StyleLoadError.REPOSITORY_ERROR,
            (app.snapshot as BookStyleSnapshot.Unavailable).error
        )
    }

    @Test
    fun missingBook_isBookMissing() = runBlocking {
        val app = loader(FakeStyleSource()).apply(42L)
        assertEquals(StyleLoadError.BOOK_MISSING, (app.snapshot as BookStyleSnapshot.Unavailable).error)
        assertNull(app.snapshot.styleId)
        assertEquals("", app.block)
    }

    @Test
    fun deletedChapter_treatedAsMissingBook() = runBlocking {
        val app = loader(FakeStyleSource()).apply(null)
        assertEquals(StyleLoadError.BOOK_MISSING, (app.snapshot as BookStyleSnapshot.Unavailable).error)
        assertEquals("", app.block)
        assertNotNull(app.notice)
    }

    @Test
    fun rebind_takesEffectOnNextLoad() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L, "甲")
            profiles[20L] = testProfile(20L, "乙")
        }
        val loader = loader(source)
        assertTrue(loader.apply(1L).block.contains("甲书"))
        source.bookStyles[1L] = 20L
        val second = loader.apply(1L)
        assertTrue(second.block.contains("乙书"))
        assertFalse(second.block.contains("甲书"))
        assertEquals(20L, (second.snapshot as BookStyleSnapshot.Loaded).styleId)
    }

    @Test
    fun unbind_takesEffectOnNextLoad() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L, "甲")
        }
        val loader = loader(source)
        assertTrue(loader.apply(1L).block.isNotEmpty())
        source.bookStyles[1L] = 0L
        val second = loader.apply(1L)
        assertEquals(BookStyleSnapshot.Unbound(1L), second.snapshot)
        assertEquals("", second.block)
        assertNull(second.notice)
    }

    @Test
    fun editedProfile_isUsedOnNextLoad() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L, "甲")
        }
        val loader = loader(source)
        assertTrue(loader.apply(1L).block.contains("甲书"))
        source.profiles[10L] = testProfile(10L, "乙")
        assertTrue(loader.apply(1L).block.contains("乙书"))
    }

    @Test
    fun deletedProfile_isNotReused() = runBlocking {
        val source = FakeStyleSource().apply {
            bookStyles[1L] = 10L
            profiles[10L] = testProfile(10L, "甲")
        }
        val loader = loader(source)
        assertTrue(loader.apply(1L).block.isNotEmpty())
        source.profiles.remove(10L)
        val second = loader.apply(1L)
        assertEquals(
            StyleLoadError.PROFILE_MISSING,
            (second.snapshot as BookStyleSnapshot.Unavailable).error
        )
        assertEquals("", second.block)
    }
}
