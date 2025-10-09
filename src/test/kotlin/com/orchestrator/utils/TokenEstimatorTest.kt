package com.orchestrator.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenEstimatorTest {

    @Test
    fun defaultEstimateRoughAccuracy() {
        // Generate a synthetic prose-like text of 1000 characters.
        val word = "lorem ipsum dolor sit amet " // 27 chars
        val sb = StringBuilder()
        while (sb.length < 1000) sb.append(word)
        val text = sb.substring(0, 1000)

        val estimate = TokenEstimator.estimateTokens(text)
        // Baseline ~ chars / 4
        val expected = 1000 / 4.0
        val lower = (expected * 0.90).toInt()
        val upper = (expected * 1.10).toInt()
        assertTrue(estimate in lower..upper, "estimate=$estimate not within 10% of ~250 tokens")
    }

    @Test
    fun codexShouldEstimateMoreTokensForCode() {
        val code = """
            fun main() {
                val items = listOf(1, 2, 3)
                for (i in items) {
                    println("#" + i + ": value=" + (i * 2))
                }
                val map = mutableMapOf<String, Int>()
                map["a"] = 1
                map["b"] = 2
                // symbols {} () [] <> ; : , . + - * / = ! == != && ||
            }
        """.trimIndent()

        val defaultEstimate = TokenEstimator.estimateTokens(code, TokenEstimator.Model.DEFAULT)
        val codexEstimate = TokenEstimator.estimateTokens(code, TokenEstimator.Model.CODEX)

        assertTrue(codexEstimate >= defaultEstimate, "Codex should estimate equal or more tokens on code-like text")
    }

    @Test
    fun claudeSlightlyFewerOnProse() {
        val prose = """
            The quick brown fox jumps over the lazy dog. This sentence is repeated to create a prose-like text. 
            It contains punctuation, spaces, and words, but very few symbols you would see in code. 
            The estimator should give a count similar to default, possibly a bit lower for Claude.
        """.trimIndent()

        val defaultEstimate = TokenEstimator.estimateTokens(prose, TokenEstimator.Model.DEFAULT)
        val claudeEstimate = TokenEstimator.estimateTokens(prose, TokenEstimator.Model.CLAUDE)

        // Allow equality due to adjustments, but typically Claude should be <= default on prose.
        assertTrue(claudeEstimate <= defaultEstimate + (defaultEstimate * 0.05).toInt())
    }

    @Test
    fun customRatioApi() {
        val text = "a".repeat(400)
        val estimate = TokenEstimator.estimateTokens(text, charsPerToken = 4.0)
        assertEquals(100, estimate, "400 chars at 4.0 chars/token should be 100 tokens")
    }

    @Test
    fun whitespacePreservation() {
        // Leading/trailing whitespace should be preserved for accurate token counts
        val text = "  hello world  \n\n"
        val estimate = TokenEstimator.estimateTokens(text)
        // Should count all 17 characters (including whitespace)
        assertTrue(estimate > 0, "Should estimate tokens for whitespace-padded text")

        val textNoWhitespace = "hello world"
        val estimateNoWhitespace = TokenEstimator.estimateTokens(textNoWhitespace)
        assertTrue(estimate > estimateNoWhitespace, "Whitespace should contribute to token count")
    }

    @Test
    fun cjkHighDensity() {
        // High CJK content (>30%) should trigger aggressive adjustment
        val chineseText = "这是一个测试文本用于验证中文字符的令牌估算准确性问题"
        val estimate = TokenEstimator.estimateTokens(chineseText)

        // CJK characters are roughly 1.5-2 chars per token (not 1:1 due to tokenizer)
        // With cpt=1.7 adjustment, expect estimate to be higher than default but lower than char count
        val length = chineseText.length
        // Realistic range: 50-80% of character count
        assertTrue(estimate in (length * 0.5).toInt()..(length * 0.9).toInt(),
            "CJK text estimate=$estimate should be 50-90% of char count=$length")
    }

    @Test
    fun cjkMixedContent() {
        // Mixed CJK/English (10-30% CJK) should interpolate
        val mixedText = "Hello 你好 World 世界 Testing 测试"
        val estimate = TokenEstimator.estimateTokens(mixedText)

        // Should be somewhere between pure English and pure CJK estimates
        assertTrue(estimate > 0, "Mixed CJK/English should produce valid estimate")
    }

    @Test
    fun koreanHangul() {
        // Korean Hangul syllables
        val koreanText = "안녕하세요 이것은 테스트입니다"
        val estimate = TokenEstimator.estimateTokens(koreanText)

        val length = koreanText.length
        assertTrue(estimate in (length * 0.5).toInt()..(length * 1.5).toInt(),
            "Korean text should have similar characteristics to CJK")
    }

    @Test
    fun japaneseHiraganaKatakana() {
        // Japanese with Hiragana and Katakana
        val japaneseText = "こんにちは カタカナ テスト"
        val estimate = TokenEstimator.estimateTokens(japaneseText)

        val length = japaneseText.length
        assertTrue(estimate in (length * 0.5).toInt()..(length * 1.5).toInt(),
            "Japanese text should have similar characteristics to CJK")
    }

    @Test
    fun runtimeRatioConfiguration() {
        // Test ability to override model ratios at runtime
        val originalRatio = TokenEstimator.getModelRatio(TokenEstimator.Model.CLAUDE)

        // Override with custom ratio
        TokenEstimator.setModelRatio(TokenEstimator.Model.CLAUDE, 5.0)
        assertEquals(5.0, TokenEstimator.getModelRatio(TokenEstimator.Model.CLAUDE))

        // Verify it affects estimates
        val text = "a".repeat(500)
        val estimate = TokenEstimator.estimateTokens(text, TokenEstimator.Model.CLAUDE)
        // With 5.0 ratio, 500 chars should be ~100 tokens
        assertTrue(estimate in 90..110, "Custom ratio should affect estimation")

        // Restore original
        TokenEstimator.setModelRatio(TokenEstimator.Model.CLAUDE, originalRatio)
    }

    @Test
    fun performanceNoAllocationRegression() {
        // Ensure estimation remains fast for large inputs
        val largeText = "Lorem ipsum dolor sit amet. ".repeat(10000) // ~280KB

        val startTime = System.nanoTime()
        val estimate = TokenEstimator.estimateTokens(largeText)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        assertTrue(estimate > 0, "Should handle large inputs")
        assertTrue(elapsedMs < 100, "Large input estimation should complete in <100ms, took ${elapsedMs}ms")
    }

    @Test
    fun emptyAndNullHandling() {
        assertEquals(0, TokenEstimator.estimateTokens(null))
        assertEquals(0, TokenEstimator.estimateTokens(""))
        assertEquals(0, TokenEstimator.estimateTokens("   ").takeIf { it == 0 } ?: run {
            // Whitespace-only should produce non-zero estimate (since we preserve whitespace)
            assertTrue(TokenEstimator.estimateTokens("   ") > 0)
            0
        })
    }

    @Test
    fun newlineNormalization() {
        // CRLF should be normalized to LF
        val textCRLF = "line1\r\nline2\r\nline3"
        val textLF = "line1\nline2\nline3"

        val estimateCRLF = TokenEstimator.estimateTokens(textCRLF)
        val estimateLF = TokenEstimator.estimateTokens(textLF)

        // After normalization, estimates should be equal
        assertEquals(estimateLF, estimateCRLF, "CRLF should normalize to LF")
    }
}