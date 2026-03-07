package com.orchestrator.context.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryExpansionServiceTest {

    @Test
    fun `expands synonyms when enabled`() {
        val service = QueryExpansionService(
            synonyms = mapOf(
                "login" to listOf("authentication", "auth", "token")
            ),
            maxExpansionTerms = 4
        )

        val result = service.expand(
            query = "login flow",
            synonymExpansionEnabled = true,
            hydeEnabled = false
        )

        assertTrue(result.effectiveQuery.contains("authentication"))
        assertTrue(result.effectiveQuery.contains("auth"))
        assertTrue(result.effectiveQuery.contains("token"))
        assertEquals(3, result.synonymTerms.size)
    }

    @Test
    fun `includes hyde document when enabled`() {
        val service = QueryExpansionService(
            synonyms = mapOf("login" to listOf("authentication")),
            maxExpansionTerms = 4
        )

        val result = service.expand(
            query = "login failure",
            synonymExpansionEnabled = true,
            hydeEnabled = true
        )

        assertTrue(!result.hydeDocument.isNullOrBlank())
        assertTrue(result.effectiveQuery.contains("Relevant implementation likely includes"))
    }
}

