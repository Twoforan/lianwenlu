package com.lianwenqi.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorCodecTest {

    @Test
    fun encodeDecode_roundTrip() {
        val source = floatArrayOf(1.5f, -2.25f, 0f, 1e-7f, 12345.678f)
        val restored = decodeVector(encodeVector(source))
        assertEquals(source.size, restored.size)
        source.indices.forEach { assertEquals(source[it], restored[it], 0f) }
    }

    @Test
    fun encode_usesLittleEndianFourBytesPerElement() {
        assertEquals(4, encodeVector(floatArrayOf(0f)).size)
        assertEquals(12, encodeVector(floatArrayOf(0f, 0f, 0f)).size)
    }

    @Test
    fun decodeEmpty_returnsEmpty() {
        assertEquals(0, decodeVector(ByteArray(0)).size)
    }

    @Test
    fun cosine_sameDirectionIsOne() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(2f, 4f, 6f)
        assertEquals(1f, cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun cosine_orthogonalIsZero() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun cosine_dimMismatchOrZeroVectorIsZero() {
        assertEquals(0f, cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f, 0f)), 0f)
        assertEquals(0f, cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 0f)
        assertEquals(0f, cosineSimilarity(FloatArray(0), FloatArray(0)), 0f)
    }
}

class ChunkingTest {

    @Test
    fun shortContent_singleChunkNoOverlap() {
        val chunks = chunkChapter("这是一段很短的正文。")
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals("这是一段很短的正文。", chunks[0].text)
    }

    @Test
    fun blankContent_noChunks() {
        assertTrue(chunkChapter("").isEmpty())
        assertTrue(chunkChapter("   \n\n  \n ").isEmpty())
    }

    @Test
    fun longContent_splitIntoMultipleChunksWithOverlap() {
        // 4 段各 200 字，target 400 → 每 2 段一块，共 2 块
        val para = "甲".repeat(200)
        val content = listOf(para, para, para, para).joinToString("\n\n")
        val chunks = chunkChapter(content, target = 400, overlap = 60)
        assertEquals(2, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(1, chunks[1].index)
        // 第二块开头应是第一块结尾的 60 字重叠
        assertTrue(chunks[1].text.startsWith(para.takeLast(60)))
    }

    @Test
    fun overlapZero_noOverlap() {
        val para = "乙".repeat(300)
        val content = listOf(para, para).joinToString("\n\n")
        val chunks = chunkChapter(content, target = 300, overlap = 0)
        assertEquals(2, chunks.size)
        assertEquals(para, chunks[0].text)
        assertEquals(para, chunks[1].text)
    }

    @Test
    fun hash_isStableAndTextSensitive() {
        assertEquals(shortHash("同样的文本"), shortHash("同样的文本"))
        assertTrue(shortHash("文本甲") != shortHash("文本乙"))
        // 切块产出的 hash 与按块文本重算一致
        val chunks = chunkChapter("丙".repeat(500), target = 400, overlap = 0)
        assertEquals(shortHash(chunks[0].text), chunks[0].hash)
    }
}
