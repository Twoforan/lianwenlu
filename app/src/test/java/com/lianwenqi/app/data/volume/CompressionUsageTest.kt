package com.lianwenqi.app.data.volume

import com.lianwenqi.app.ai.AiResult
import com.lianwenqi.app.ai.CacheUsage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 压缩任务 usage 聚合：未知缓存 / 未知分母 / 估算 Token 三者必须与真实统计分开。
 */
class CompressionUsageTest {

    @Test
    fun `有 usage 但缓存未知时计入 unknown 而不是 0`() {
        val result = AiResult.ok(
            "正文",
            promptTokens = 100,
            completionTokens = 20,
            cacheUsage = CacheUsage(
                cachedTokens = null,
                usageReported = true,
                promptTokens = 100,
                completionTokens = 20
            )
        )
        val usage = CompressionUsage().plus(result)
        assertEquals(100, usage.promptTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(0, usage.cachedTokens)
        assertEquals(1, usage.requestCount)
        assertEquals(1, usage.requestsWithUsage)
        assertEquals(1, usage.unknownCacheRequests)
        assertEquals(0, usage.unknownPromptRequests)
        assertEquals(-1, usage.cachedTokensOrUnknown)
        assertEquals(100, usage.promptTokensOrUnknown)
    }

    @Test
    fun `有 usage 但分母未知时计入 unknownPrompt 且不累加估算值`() {
        val result = AiResult.ok(
            "正文",
            promptTokens = 9999, // 本地估算，绝不能进入真实 usage
            completionTokens = 20,
            cacheUsage = CacheUsage(
                cachedTokens = 8000,
                usageReported = true,
                promptTokens = null,
                completionTokens = 20
            )
        )
        val usage = CompressionUsage().plus(result)
        assertEquals(0, usage.promptTokens)
        assertEquals(8000, usage.cachedTokens)
        assertEquals(1, usage.requestsWithUsage)
        assertEquals(0, usage.unknownCacheRequests)
        assertEquals(1, usage.unknownPromptRequests)
        assertEquals(-1, usage.promptTokensOrUnknown)
        assertEquals(8000, usage.cachedTokensOrUnknown)
    }

    @Test
    fun `完全没有 usage 时只计请求数且估算值不进入真实字段`() {
        val result = AiResult.ok("正文", promptTokens = 500, completionTokens = 80)
        val usage = CompressionUsage().plus(result)
        assertEquals(1, usage.requestCount)
        assertEquals(0, usage.requestsWithUsage)
        assertEquals(0, usage.promptTokens)
        assertEquals(0, usage.completionTokens)
        assertEquals(0, usage.cachedTokens)
        assertEquals(0, usage.unknownCacheRequests)
        assertEquals(0, usage.unknownPromptRequests)
        assertEquals(-1, usage.promptTokensOrUnknown)
        assertEquals(-1, usage.cachedTokensOrUnknown)
    }

    @Test
    fun `明确 0 缓存不算未知`() {
        val result = AiResult.ok(
            "正文",
            promptTokens = 1000,
            completionTokens = 20,
            cacheUsage = CacheUsage(
                cachedTokens = 0,
                usageReported = true,
                promptTokens = 1000,
                completionTokens = 20
            )
        )
        val usage = CompressionUsage().plus(result)
        assertEquals(0, usage.cachedTokens)
        assertEquals(0, usage.unknownCacheRequests)
        assertEquals(0, usage.cachedTokensOrUnknown)
    }

    @Test
    fun `有 usage 但输出 Token 缺失时不累加估算值`() {
        val result = AiResult.ok(
            "正文",
            promptTokens = 100,
            completionTokens = 999, // 本地估算
            cacheUsage = CacheUsage(
                cachedTokens = 30,
                usageReported = true,
                promptTokens = 100,
                completionTokens = null
            )
        )
        val usage = CompressionUsage().plus(result)
        assertEquals(100, usage.promptTokens)
        assertEquals(0, usage.completionTokens)
        assertEquals(1, usage.requestsWithUsage)
    }

    @Test
    fun `合并另一段 usage 时未知计数一并相加`() {
        val first = CompressionUsage(
            promptTokens = 100,
            cachedTokens = 30,
            requestCount = 1,
            requestsWithUsage = 1,
            unknownCacheRequests = 2,
            unknownPromptRequests = 1
        )
        val second = CompressionUsage(
            promptTokens = 200,
            cachedTokens = 60,
            requestCount = 1,
            requestsWithUsage = 1,
            unknownCacheRequests = 3,
            unknownPromptRequests = 2
        )
        val merged = first.plus(second)
        assertEquals(300, merged.promptTokens)
        assertEquals(90, merged.cachedTokens)
        assertEquals(2, merged.requestCount)
        assertEquals(2, merged.requestsWithUsage)
        assertEquals(5, merged.unknownCacheRequests)
        assertEquals(3, merged.unknownPromptRequests)
    }

    @Test
    fun `旧数据库记录仍可按已知值读取`() {
        // v25 及更早的记录没有未知计数，默认 0 表示「当时没有这个概念」，
        // 已知 Token 数值必须原样保留。
        val legacy = CompressionUsage(
            promptTokens = 120,
            completionTokens = 40,
            cachedTokens = 12,
            requestCount = 2,
            requestsWithUsage = 2
        )
        assertEquals(120, legacy.promptTokens)
        assertEquals(12, legacy.cachedTokens)
        assertEquals(120, legacy.promptTokensOrUnknown)
        assertEquals(12, legacy.cachedTokensOrUnknown)
    }

    @Test
    fun `重置后的空 usage 保持未知哨兵`() {
        val empty = CompressionUsage()
        assertEquals(-1, CompressionUsage.TOKENS_UNKNOWN)
        assertEquals(-1, empty.promptTokensOrUnknown)
        assertEquals(-1, empty.cachedTokensOrUnknown)
    }
}
