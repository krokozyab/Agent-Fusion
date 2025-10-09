package com.orchestrator.modules.routing

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TaskClassifierTest {

    @Test
    fun `complexity and risk are within bounds`() {
        val simple = "Fix typo in README"
        val complex = "Refactor architecture to support distributed processing with Kafka integration and caching layers"

        val c1 = TaskClassifier.estimateComplexity(simple)
        val r1 = TaskClassifier.estimateRisk(simple)
        val c2 = TaskClassifier.estimateComplexity(complex)
        val r2 = TaskClassifier.estimateRisk(complex)

        assertTrue(c1 in 1..10, "complexity bound 1..10")
        assertTrue(r1 in 1..10, "risk bound 1..10")
        assertTrue(c2 in 1..10, "complexity bound 1..10")
        assertTrue(r2 in 1..10, "risk bound 1..10")

        // Complex text should generally have higher complexity than the simple one
        assertTrue(c2 >= c1)
    }

    @Test
    fun `critical keywords detection works`() {
        val txt = "Implement OAuth authentication and secure payment checkout with JWT tokens"
        val hits = TaskClassifier.detectCriticalKeywords(txt)
        assertContains(hits, "oauth")
        assertContains(hits, "authentication")
        assertContains(hits, "payment")
        assertContains(hits, "jwt")
        assertContains(hits, "token")
    }

    @Test
    fun `risk increases with critical terms`() {
        val base = "Add logging to service"
        val risky = "Handle security vulnerability in authentication module affecting payments checkout"

        val rBase = TaskClassifier.estimateRisk(base)
        val rRisky = TaskClassifier.estimateRisk(risky)

        assertTrue(rRisky >= rBase, "Risk should not be lower when critical keywords present")
    }

    @Test
    fun `classify returns confidence and is fast`() {
        val text = "Migrate database schema and add authentication with OAuth and JWT; ensure PCI compliance for payment checkout"

        val start = System.nanoTime()
        val result = TaskClassifier.classify(text)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue(result.complexity in 1..10)
        assertTrue(result.risk in 1..10)
        assertTrue(result.confidence in 0.0..1.0)
        assertTrue(result.criticalKeywords.isNotEmpty())

        // Performance: aim for <10ms on typical environments; keep soft margin to avoid CI flakiness
        // Use a relaxed upper bound to avoid flaky CI timing (still well under typical constraints for this classifier)
        assertTrue(elapsedMs < 50.0, "Classifier should execute quickly, was ${'$'}elapsedMs ms")
    }
}
