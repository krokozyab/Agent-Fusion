package com.orchestrator.context.providers

import com.orchestrator.context.config.BoostConfig
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
    fun `normalized scores survive a typical downstream minScore threshold`() = runBlocking {
        // Regression guard: raw RRF scores (~1/k ≈ 0.016) were always wiped out by the
        // default minScore threshold (0.3), so hybrid returned nothing. After normalization
        // the top hit must map to 1.0 and clear the threshold.
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "top result"),
            createSnippet(2, 0.8, "second result"),
            createSnippet(3, 0.7, "third result")
        )

        val hybrid = HybridContextProvider(providers = listOf(provider1))
        val result = hybrid.getContext("test", ContextScope(), TokenBudget(maxTokens = 10000))

        assertEquals(3, result.size)
        // Top hit normalized to 1.0; comfortably above a 0.3 threshold.
        assertEquals(1.0, result[0].score, 0.001)
        assertTrue(result.count { it.score >= 0.3 } >= 1, "At least the top hit must clear a 0.3 threshold")
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

    @Test
    fun `applies file type penalties correctly`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC

        // Return snippets with different file types
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "pdf content", filePath = "docs/manual.pdf"),
            createSnippet(2, 0.9, "code content", filePath = "src/Main.kt"),
            createSnippet(3, 0.9, "markdown content", filePath = "README.md")
        )

        val boostConfig = BoostConfig(
            fileTypePenalties = mapOf(
                "pdf" to 0.5,  // 50% penalty
                "md" to 0.7    // 30% penalty
            )
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1),
            boostConfig = boostConfig
        )

        val result = hybrid.getContext("test", ContextScope(), TokenBudget(maxTokens = 10000))

        // All snippets should be present
        assertEquals(3, result.size)

        // Check penalties were applied via metadata
        val pdfSnippet = result.find { it.filePath.endsWith("pdf") }!!
        assertEquals("0.500", pdfSnippet.metadata["file_type_penalty"])

        val ktSnippet = result.find { it.filePath.endsWith("kt") }!!
        assertEquals("1.000", ktSnippet.metadata["file_type_penalty"]) // No penalty for .kt

        val mdSnippet = result.find { it.filePath.endsWith("md") }!!
        assertEquals("0.700", mdSnippet.metadata["file_type_penalty"])
    }

    @Test
    fun `applies file pattern penalties correctly`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC

        // Return snippets with different path patterns
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "docs content", filePath = "project/docs/guide.md"),
            createSnippet(2, 0.9, "code content", filePath = "src/main/Main.kt"),
            createSnippet(3, 0.9, "readme", filePath = "README.md")
        )

        val boostConfig = BoostConfig(
            filePatternPenalties = mapOf(
                "**/docs/**" to 0.6,    // Docs directory
                "**/README*" to 0.8     // READMEs
            )
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1),
            boostConfig = boostConfig
        )

        val result = hybrid.getContext("test", ContextScope(), TokenBudget(maxTokens = 10000))

        assertEquals(3, result.size)

        // Check pattern penalties
        val docsSnippet = result.find { it.filePath.contains("docs") }!!
        assertEquals("0.600", docsSnippet.metadata["pattern_penalty"])

        val codeSnippet = result.find { it.filePath.contains("src") }!!
        assertEquals("1.000", codeSnippet.metadata["pattern_penalty"]) // No matching pattern

        val readmeSnippet = result.find { it.filePath.contains("README") }!!
        assertEquals("0.800", readmeSnippet.metadata["pattern_penalty"])
    }

    @Test
    fun `applies chunk kind boosts correctly`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC

        // Return snippets with different chunk kinds
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.8, "class code", kind = ChunkKind.CODE_CLASS),
            createSnippet(2, 0.8, "function code", kind = ChunkKind.CODE_FUNCTION),
            createSnippet(3, 0.8, "comment", kind = ChunkKind.COMMENT),
            createSnippet(4, 0.8, "paragraph", kind = ChunkKind.PARAGRAPH)
        )

        val boostConfig = BoostConfig(
            chunkKindBoosts = mapOf(
                "CODE_CLASS" to 1.3,     // Boost code
                "CODE_FUNCTION" to 1.3,  // Boost code
                "COMMENT" to 0.8,        // Penalize comments
                "PARAGRAPH" to 0.7       // Penalize text
            )
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1),
            boostConfig = boostConfig
        )

        val result = hybrid.getContext("test", ContextScope(), TokenBudget(maxTokens = 10000))

        assertEquals(4, result.size)

        // Check kind boosts
        val classSnippet = result.find { it.kind == ChunkKind.CODE_CLASS }!!
        assertEquals("1.300", classSnippet.metadata["kind_boost"])

        val functionSnippet = result.find { it.kind == ChunkKind.CODE_FUNCTION }!!
        assertEquals("1.300", functionSnippet.metadata["kind_boost"])

        val commentSnippet = result.find { it.kind == ChunkKind.COMMENT }!!
        assertEquals("0.800", commentSnippet.metadata["kind_boost"])

        val paragraphSnippet = result.find { it.kind == ChunkKind.PARAGRAPH }!!
        assertEquals("0.700", paragraphSnippet.metadata["kind_boost"])
    }

    @Test
    fun `combines multiple penalties multiplicatively`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC

        // Return a PDF in docs directory with documentation chunk kind
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(
                chunkId = 1,
                score = 0.9,
                text = "documentation",
                filePath = "docs/manual.pdf",
                kind = ChunkKind.PARAGRAPH
            )
        )

        val boostConfig = BoostConfig(
            fileTypePenalties = mapOf("pdf" to 0.5),           // 50% penalty
            filePatternPenalties = mapOf("**/docs/**" to 0.6), // 40% penalty
            chunkKindBoosts = mapOf("PARAGRAPH" to 0.7)        // 30% penalty
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1),
            boostConfig = boostConfig
        )

        val result = hybrid.getContext("test", ContextScope(), TokenBudget(maxTokens = 10000))

        assertEquals(1, result.size)

        val snippet = result[0]

        // Check individual penalties
        assertEquals("0.500", snippet.metadata["file_type_penalty"])
        assertEquals("0.600", snippet.metadata["pattern_penalty"])
        assertEquals("0.700", snippet.metadata["kind_boost"])

        // Check combined multiplier: 0.5 * 0.6 * 0.7 = 0.21
        assertEquals("0.210", snippet.metadata["combined_multiplier"])

        // After RRF normalization the sole hit's base score is 1.0; penalties multiply it.
        // combined_multiplier 0.21 → final score 0.21.
        assertEquals(0.21, snippet.score, 0.001)
        // original_score reflects the pre-penalty (normalized) RRF score
        assertEquals("1.0000", snippet.metadata["original_score"])
    }

    @Test
    fun `penalty system preserves original score in metadata`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC

        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.85, "test", filePath = "docs/file.pdf")
        )

        val boostConfig = BoostConfig(
            fileTypePenalties = mapOf("pdf" to 0.5)
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1),
            boostConfig = boostConfig
        )

        val result = hybrid.getContext("test", ContextScope(), TokenBudget(maxTokens = 10000))

        assertEquals(1, result.size)

        // original_score reflects the pre-penalty (normalized) RRF score: the sole hit is 1.0
        assertEquals("1.0000", result[0].metadata["original_score"])

        // Adjusted score = normalized base (1.0) * combined penalty multiplier, and is demoted by the pdf penalty.
        val combined = result[0].metadata["combined_multiplier"]!!.toDouble()
        assertEquals(combined, result[0].score, 0.001)
        assertTrue(result[0].score < 1.0, "pdf penalty must demote the score below the normalized maximum")
    }

    @Test
    fun `no penalties applied when config is empty`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC

        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "test", filePath = "docs/file.pdf", kind = ChunkKind.PARAGRAPH)
        )

        val boostConfig = BoostConfig(
            fileTypePenalties = emptyMap(),
            filePatternPenalties = emptyMap(),
            chunkKindBoosts = emptyMap()
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1),
            boostConfig = boostConfig
        )

        val result = hybrid.getContext("test", ContextScope(), TokenBudget(maxTokens = 10000))

        assertEquals(1, result.size)

        // All penalties should be 1.0 (no penalty)
        assertEquals("1.000", result[0].metadata["file_type_penalty"])
        assertEquals("1.000", result[0].metadata["pattern_penalty"])
        assertEquals("1.000", result[0].metadata["kind_boost"])
        assertEquals("1.000", result[0].metadata["combined_multiplier"])

        // Score is the normalized RRF score; the sole/top hit maps to 1.0 with no penalties
        assertEquals(1.0, result[0].score, 0.001)
    }

    @Test
    fun `penalties affect ranking order`() = runBlocking {
        val provider1 = mockk<ContextProvider>()
        every { provider1.type } returns ContextProviderType.SEMANTIC

        // Return snippets with same initial scores but different file types
        coEvery { provider1.getContext(any(), any(), any()) } returns listOf(
            createSnippet(1, 0.9, "pdf content", filePath = "docs/file.pdf"),
            createSnippet(2, 0.9, "code content", filePath = "src/Main.kt")
        )

        val boostConfig = BoostConfig(
            fileTypePenalties = mapOf("pdf" to 0.5) // Heavy penalty for PDF
        )

        val hybrid = HybridContextProvider(
            providers = listOf(provider1),
            boostConfig = boostConfig
        )

        val result = hybrid.getContext("test", ContextScope(), TokenBudget(maxTokens = 10000))

        assertEquals(2, result.size)

        // Code file should rank higher than PDF despite same original score
        assertEquals("src/Main.kt", result[0].filePath, "Code file should rank first")
        assertEquals("docs/file.pdf", result[1].filePath, "PDF should rank second")
    }

    // Helper method to create test snippets
    private fun createSnippet(
        chunkId: Long,
        score: Double,
        text: String,
        filePath: String = "test/file.kt",
        kind: ChunkKind = ChunkKind.CODE_CLASS
    ): ContextSnippet {
        return ContextSnippet(
            chunkId = chunkId,
            score = score,
            filePath = filePath,
            label = "test snippet $chunkId",
            kind = kind,
            text = text,
            language = "kotlin",
            offsets = IntRange(1, 10),
            metadata = emptyMap()
        )
    }
}
