package com.orchestrator.web.components

import kotlin.test.Test
import kotlin.test.assertTrue

class ResultCardTest {

    @Test
    fun `result card renders with all elements`() {
        val config = ResultCard.Config(
            chunkId = 12345L,
            filePath = "src/main/kotlin/auth/JwtValidator.kt",
            startLine = 42,
            score = 0.85,
            kind = "CODE_CLASS",
            snippet = "class JwtValidator {\n  fun validate(token: String): Boolean {\n    // Validates JWT",
            language = "kotlin",
            tokenEstimate = 245,
            providers = "semantic, symbol"
        )

        val html = ResultCard.render(config)

        assertTrue(html.contains("result-card"), "Should have result-card class")
        assertTrue(html.contains("data-chunk-id=\"12345\""), "Should have chunk ID")
        assertTrue(html.contains("JwtValidator.kt:42"), "Should show file path and line")
        assertTrue(html.contains("0.85"), "Should show score")
        assertTrue(html.contains("CODE CLASS"), "Should show kind")
        assertTrue(html.contains("kotlin"), "Should show language")
        assertTrue(html.contains("245 tokens"), "Should show token count")
        assertTrue(html.contains("semantic, symbol"), "Should show providers")
        assertTrue(html.contains("📂 Open"), "Should have Open button")
        assertTrue(html.contains("📋 Copy"), "Should have Copy button")
        assertTrue(html.contains("🔗 Related"), "Should have Related button")
    }

    @Test
    fun `result card escapes HTML in snippet`() {
        val config = ResultCard.Config(
            chunkId = 1L,
            filePath = "test.kt",
            startLine = 1,
            score = 0.5,
            kind = "CODE",
            snippet = "<script>alert('xss')</script>",
            language = "kotlin",
            tokenEstimate = 10,
            providers = "test"
        )

        val html = ResultCard.render(config)

        assertTrue(html.contains("&lt;script&gt;"), "Should escape < and >")
        assertTrue(!html.contains("<script>alert"), "Should not contain unescaped script tag")
    }
}
