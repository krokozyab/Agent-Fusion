package com.orchestrator.web.components

import kotlin.test.Test
import kotlin.test.assertTrue

class ResultsContainerTest {

    @Test
    fun `results container renders with results and status bar`() {
        val results = listOf(
            ResultCard.Config(
                chunkId = 1L,
                filePath = "test.kt",
                startLine = 10,
                score = 0.9,
                kind = "CODE_CLASS",
                snippet = "class Test",
                language = "kotlin",
                tokenEstimate = 50,
                providers = "semantic"
            )
        )

        val config = ResultsContainer.Config(
            results = results,
            totalHits = 1,
            durationMs = 234L,
            providerStats = mapOf("semantic" to 1)
        )

        val html = ResultsContainer.render(config)

        assertTrue(html.contains("results-list"), "Should have results list")
        assertTrue(html.contains("result-card"), "Should contain result cards")
        assertTrue(html.contains("234ms"), "Should show duration")
        assertTrue(html.contains("1 results"), "Should show result count")
        assertTrue(html.contains("semantic: 1"), "Should show provider stats")
    }

    @Test
    fun `results container renders empty state`() {
        val html = ResultsContainer.renderEmpty()

        assertTrue(html.contains("No results found"), "Should show empty message")
        assertTrue(html.contains("Try adjusting"), "Should show help text")
    }

    @Test
    fun `results container renders error state`() {
        val html = ResultsContainer.renderError("Query failed")

        assertTrue(html.contains("Search Error"), "Should show error heading")
        assertTrue(html.contains("Query failed"), "Should show error message")
        assertTrue(html.contains("alert-danger"), "Should have danger alert class")
    }
}
