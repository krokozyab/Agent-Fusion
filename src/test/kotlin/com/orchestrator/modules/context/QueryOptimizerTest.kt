package com.orchestrator.modules.context

import com.orchestrator.context.config.QueryConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.search.MmrReranker
import com.orchestrator.context.search.SearchResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class QueryOptimizerTest {

    private val clock = Clock.systemUTC()

    @Test
    fun `caches reranked results for identical queries`() {
        val reranker = mockk<MmrReranker>()
        val config = QueryConfig(defaultK = 5, mmrLambda = 0.5, minScoreThreshold = 0.0, rerankEnabled = true)
        val optimizer = QueryOptimizer(config, reranker = reranker, clock = clock, cacheSize = 4, cacheTtl = Duration.ofMinutes(5))

        val budget = TokenBudget(maxTokens = 400, reserveForPrompt = 50, diversityWeight = 0.3)
        val results = listOf(searchResult(1L, 0.9f), searchResult(2L, 0.85f))

        every { reranker.rerank(any(), any(), any()) } returns results

        optimizer.optimize("query text", results, budget)
        optimizer.optimize("query text", results, budget)

        verify(exactly = 1) { reranker.rerank(any(), any(), any()) }
    }

    @Test
    fun `applies score threshold and defaultK without reranking`() {
        val reranker = mockk<MmrReranker>(relaxed = true)
        val config = QueryConfig(defaultK = 1, mmrLambda = 0.5, minScoreThreshold = 0.7, rerankEnabled = false)
        val optimizer = QueryOptimizer(config, reranker = reranker, clock = clock, cacheSize = 2, cacheTtl = Duration.ofMinutes(5))

        val budget = TokenBudget(maxTokens = 200, reserveForPrompt = 0, diversityWeight = 0.2)
        val results = listOf(searchResult(1L, 0.95f), searchResult(2L, 0.6f))

        val optimized = optimizer.optimize("query", results, budget)

        assertEquals(1, optimized.size)
        assertEquals(1L, optimized.first().chunk.id)
        verify(exactly = 0) { reranker.rerank(any(), any(), any()) }
    }

    private fun searchResult(id: Long, score: Float): SearchResult {
        val chunk = Chunk(
            id = id,
            fileId = id,
            ordinal = 0,
            kind = ChunkKind.CODE_BLOCK,
            startLine = 1,
            endLine = 10,
            tokenEstimate = 40,
            content = "fun example$id() = Unit",
            summary = "chunk$id",
            createdAt = Instant.now(clock)
        )
        return SearchResult(chunk, score, id)
    }
}
