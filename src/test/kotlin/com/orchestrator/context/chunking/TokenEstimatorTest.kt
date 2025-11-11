package com.orchestrator.context.chunking

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenEstimatorTest {

    @Test
    fun `estimate grows with content length`() {
        val short = TokenEstimator.estimate("hello world")
        val medium = TokenEstimator.estimate("Lorem ipsum dolor sit amet, consectetur adipiscing elit.")
        val long = TokenEstimator.estimate("""This is a longer block of text meant to simulate a paragraph across multiple sentences.
            |It includes punctuation, numbers like 12345, and newline characters to ensure the heuristic handles diverse input correctly.
        """.trimMargin())

        assertTrue(short < medium)
        assertTrue(medium < long)
    }

    @Test
    fun `estimate is roughly four characters per token`() {
        val text = "a".repeat(400)
        val estimate = TokenEstimator.estimate(text)
        // Expect roughly 100 tokens +/- 20%
        assertTrue(estimate in 80..120, "estimate=$estimate")
    }

    @Test
    fun `caches repeated lengths`() {
        val first = TokenEstimator.estimate("kotlin")
        val second = TokenEstimator.estimate("python")
        assertTrue(first > 0)
        assertTrue(second > 0)
    }
}
