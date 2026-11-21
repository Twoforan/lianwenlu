package com.lianwenqi.app.agent.context

import com.lianwenqi.app.ai.ChatTurn
import com.lianwenqi.app.data.entity.ChatRole
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextEstimatorTest {

    @Test
    fun 中文按字估算英文按四字符估算() {
        assertTrue(ContextEstimator.estimateTokens("你好世界") >= 4)
        val ascii = ContextEstimator.estimateTokens("hello world")
        assertTrue(ascii in 2..4)
        assertTrue(ContextEstimator.estimateTokens("你好世界") > ascii)
    }

    @Test
    fun 空文本为零且多轮累加() {
        assertTrue(ContextEstimator.estimateTokens("") == 0)
        val single = ContextEstimator.estimateTokens(
            listOf(ChatTurn(ChatRole.USER, "你好"))
        )
        val double = ContextEstimator.estimateTokens(
            listOf(
                ChatTurn(ChatRole.USER, "你好"),
                ChatTurn(ChatRole.ASSISTANT, "你好")
            )
        )
        assertTrue(double > single)
    }
}
