package com.orchestrator.context.providers

import com.orchestrator.context.domain.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridContextProviderTest {

    @Test
    fun `queries multiple providers in parallel`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        val provider2 = mockk<ContextProvider>()
        val provider3 = mockk<ContextProvider>()

        every { provider1.type } returns ContextProviderType.SEMANTIC
        every { provider2.type } returns ContextProviderType.SYMBOL
        every { provider3.type } returns ContextProviderType.FULL_TEXT

        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "semantic result 1")
        )
        coEvery { provider2.getContext(any(), any(), any()) } returns listOf(
            createSnippet(2, 0.8, "symbol result 1")
        )
        coEvery { provider3.getContext(any(), any(), any()) } returns listOf(
            createSnippet(3, 0.7, "fulltext result 1")
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1, provider2, provider3)
        )

        val result = hybrid.getContext("test query", ContextScope(), TokenBudget(maxTokens = 1000))

        // Should have called all providers
        coVerify(exactly = 1) { provider1.getContext(any(), any(), any()) }
        coVerify(exactly = 1) { provider2.getContext(any(), any(), any()) }
        coVerify(exactly = 1) { provider3.getContext(any(), any(), any()) }

        // Should have results from all providers
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `applies RRF fusion correctly`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        val provider2 = mockk<ContextProvider>()

        every { provider1.type } returns ContextProviderType.SEMANTIC
        every { provider2.type } returns ContextProviderType.SYMBOL

        // Provider 1 returns chunks 1, 2, 3 in that order
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "chunk 1"),
            createSnippet(2, 0.7, "chunk 2"),
            createSnippet(3, 0.5, "chunk 3")
        )

        // Provider 2 returns chunks 2, 1, 4 in that order
        coEvery { provider2.getContext(any(), any(), any()) } returns listOf(
            createSnippet(2, 0.8, "chunk 2"),
            createSnippet(1, 0.6, "chunk 1"),
            createSnippet(4, 0.4, "chunk 4")
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1, provider2),
            k = 60
        )

        val result = hybrid.getContext("test query", ContextScope(), TokenBudget(maxTokens = 10000))

        // RRF calculations (k=60, equal weights):
        // Chunk 1: 1/(60+1) + 1/(60+2) = 1/61 + 1/62 = 0.0164 + 0.0161 = 0.0325
        // Chunk 2: 1/(60+2) + 1/(60+1) = 1/62 + 1/61 = 0.0161 + 0.0164 = 0.0325
        // Chunk 3: 1/(60+3) = 1/63 = 0.0159
        // Chunk 4: 1/(60+3) = 1/63 = 0.0159

        // Both chunk 1 and 2 should have equal RRF scores, ranking depends on implementation
        // Chunks 3 and 4 have lower scores

        assertEquals(4, result.size)

        // First two should be chunks 1 and 2 (order may vary due to equal scores)
        val topTwoIds = result.take(2).map { it.chunkId }.toSet()
        assertTrue(topTwoIds.contains(1L))
        assertTrue(topTwoIds.contains(2L))

        // Should have RRF metadata
        result.forEach { snippet ->
            assertTrue(snippet.metadata.containsKey("rrf_score"))
            assertTrue(snippet.metadata.containsKey("rrf_provider_count"))
        }
    }

    @Test
    fun `handles provider failures with SKIP strategy`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        val provider2 = mockk<ContextProvider>()
        val provider3 = mockk<ContextProvider>()

        every { provider1.type } returns ContextProviderType.SEMANTIC
        every { provider2.type } returns ContextProviderType.SYMBOL
        every { provider3.type } returns ContextProviderType.FULL_TEXT

        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "result 1")
        )
        coEvery { provider2.getContext(any(), any(), any()) } throws RuntimeException("Provider 2 failed")
        coEvery { provider3.getContext(any(), any(), any()) } returns listOf(
            createSnippet(2, 0.8, "result 2")
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1, provider2, provider3),
            failureStrategy = HybridContextProvider.FailureStrategy.SKIP
        )

        // Should not throw, should return results from successful providers
        val result = hybrid.getContext("test query", ContextScope(), TokenBudget(maxTokens = 1000))

        assertTrue(result.isNotEmpty())
        assertEquals(2, result.size) // From provider1 and provider3
    }

    @Test
    fun `handles provider failures with FAIL strategy`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        val provider2 = mockk<ContextProvider>()

        every { provider1.type } returns ContextProviderType.SEMANTIC
        every { provider2.type } returns ContextProviderType.SYMBOL

        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "result 1")
        )
        coEvery { provider2.getContext(any(), any(), any()) } throws RuntimeException("Provider 2 failed")

        val hybrid = HybridContextProvider(
            providers = listOf(provider1, provider2),
            failureStrategy = HybridContextProvider.FailureStrategy.FAIL
        )

        // Should throw exception
        try {
            hybrid.getContext("test query", ContextScope(), TokenBudget(maxTokens = 1000))
            throw AssertionError("Expected exception to be thrown")
        } catch (e: RuntimeException) {
            assertEquals("Provider 2 failed", e.message)
        }
    }

    @Test
    fun `applies provider weights correctly`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        val provider2 = mockk<ContextProvider>()

        every { provider1.type } returns ContextProviderType.SEMANTIC
        every { provider2.type } returns ContextProviderType.SYMBOL

        // Provider 1 returns chunk 1 at rank 1
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "chunk 1")
        )

        // Provider 2 returns chunk 2 at rank 1
        coEvery { provider2.getContext(any(), any(), any()) } returns listOf(
            createSnippet(2, 0.8, "chunk 2")
        )

        // Weight provider1 2x more than provider2
        val hybrid = HybridContextProvider(
            providers = listOf(provider1, provider2),
            k = 60,
            weights = mapOf(
                ContextProviderType.SEMANTIC to 2.0,
                ContextProviderType.SYMBOL to 1.0
            )
        )

        val result = hybrid.getContext("test query", ContextScope(), TokenBudget(maxTokens = 1000))

        // RRF with weights:
        // Chunk 1: 2.0 × 1/(60+1) = 2.0/61 = 0.0328
        // Chunk 2: 1.0 × 1/(60+1) = 1.0/61 = 0.0164

        // Chunk 1 should be ranked higher due to weight
        assertEquals(2, result.size)
        assertEquals(1L, result[0].chunkId)
        assertEquals(2L, result[1].chunkId)
    }

    @Test
    fun `deduplicates chunks from multiple providers`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        val provider2 = mockk<ContextProvider>()

        every { provider1.type } returns ContextProviderType.SEMANTIC
        every { provider2.type } returns ContextProviderType.SYMBOL

        // Both providers return the same chunk
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "shared chunk")
        )
        coEvery { provider2.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.8, "shared chunk")
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1, provider2)
        )

        val result = hybrid.getContext("test query", ContextScope(), TokenBudget(maxTokens = 1000))

        // Should only have 1 result (deduplicated)
        assertEquals(1, result.size)
        assertEquals(1L, result[0].chunkId)

        // Should have metadata showing it came from 2 providers
        assertEquals("2", result[0].metadata["rrf_provider_count"])
        assertEquals("1.00", result[0].metadata["rrf_agreement"]) // 2/2 = 1.00
    }

    @Test
    fun `respects token budget`() = runBlocking {
        val provider1 = mockk<ContextProvider>()

        every { provider1.type } returns ContextProviderType.SEMANTIC

        // Return multiple large chunks
        coEvery { provider1.getContext(any(), any(), any()) } returns List(10) { index ->
            createSnippet((index + 1).toLong(), 0.9 - index * 0.05, "x".repeat(200)) // ~50 tokens each
        }

        val hybrid = HybridContextProvider(
            providers = listOf(provider1)
        )

        val result = hybrid.getContext("test query", ContextScope(), TokenBudget(maxTokens = 200))

        // Budget is 200 tokens, each chunk ~50 tokens
        // Should return at most 4 chunks (4 * 50 = 200)
        assertTrue(result.size <= 4)

        val totalTokens = result.sumOf { it.text.length / 4 }
        assertTrue(totalTokens <= 200)
    }

    @Test
    fun `has correct provider type`() {
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC

        val hybrid = HybridContextProvider(providers = listOf(provider1))

        assertEquals(ContextProviderType.HYBRID, hybrid.type)
    }

    @Test
    fun `validates configuration`() {
        // Empty providers list
        try {
            HybridContextProvider(providers = emptyList())
            throw AssertionError("Expected exception for empty providers")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("At least one provider"))
        }

        // Negative k value
        val provider = mockk<ContextProvider>()
        every { provider.type } returns ContextProviderType.SEMANTIC

        try {
            HybridContextProvider(providers = listOf(provider), k = -1)
            throw AssertionError("Expected exception for negative k")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("k must be positive"))
        }

        // Negative weight
        try {
            HybridContextProvider(
                providers = listOf(provider),
                weights = mapOf(ContextProviderType.SEMANTIC to -1.0)
            )
            throw AssertionError("Expected exception for negative weight")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("weights must be positive"))
        }
    }

    @Test
    fun `performance test - responds within 200ms`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        val provider2 = mockk<ContextProvider>()
        val provider3 = mockk<ContextProvider>()

        every { provider1.type } returns ContextProviderType.SEMANTIC
        every { provider2.type } returns ContextProviderType.SYMBOL
        every { provider3.type } returns ContextProviderType.FULL_TEXT

        // Simulate providers with some latency
        coEvery { provider1.getContext(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(10)
            listOf(createSnippet(1, 0.9, "result 1"))
        }
        coEvery { provider2.getContext(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(10)
            listOf(createSnippet(2, 0.8, "result 2"))
        }
        coEvery { provider3.getContext(any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(10)
            listOf(createSnippet(3, 0.7, "result 3"))
        }

        val hybrid = HybridContextProvider(
            providers = listOf(provider1, provider2, provider3)
        )

        // Warm up
        hybrid.getContext("warm up", ContextScope(), TokenBudget(maxTokens = 1000))

        // Measure performance over 5 runs
        val times = mutableListOf<Long>()
        repeat(5) {
            val start = System.currentTimeMillis()
            hybrid.getContext("test query", ContextScope(), TokenBudget(maxTokens = 1000))
            val duration = System.currentTimeMillis() - start
            times.add(duration)
        }

        val avgTime = times.average()
        println("Average response time: ${avgTime}ms over ${times.size} runs")
        println("Min: ${times.minOrNull()}ms, Max: ${times.maxOrNull()}ms")

        // With 3 providers at 10ms each running in parallel, total should be ~10-20ms
        // Add overhead for RRF calculation and coordination
        assertTrue(avgTime < 200.0, "Average response time ${avgTime}ms exceeds 200ms requirement")
    }

    // Helper method to create test snippets
    private fun createSnippet(chunkId: Long, score: Double, text: String): ContextSnippet {
        return ContextSnippet(
            chunkId = chunkId,
            score = score,
            filePath = "test/file.kt",
            label = "test snippet $chunkId",
            kind = ChunkKind.CODE_CLASS,
            text = text,
            language = "kotlin",
            offsets = IntRange(1, 10),
            metadata = emptyMap()
        )
    }
}
