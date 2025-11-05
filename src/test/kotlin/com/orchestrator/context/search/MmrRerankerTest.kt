package com.orchestrator.context.search

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.search.VectorSearchEngine.ScoredChunk
import com.orchestrator.context.embedding.VectorOps
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MmrRerankerTest {

    private val reranker = MmrReranker()

    @Test
    fun `lambda one preserves relevance ordering`() {
        val items = listOf(
            scoredChunk(id = 1, score = 0.9f, vector = floatArrayOf(1f, 0f, 0f)),
            scoredChunk(id = 2, score = 0.7f, vector = floatArrayOf(0f, 1f, 0f)),
            scoredChunk(id = 3, score = 0.5f, vector = floatArrayOf(0f, 0f, 1f))
        )

        val budget = TokenBudget(maxTokens = 500)
        val reranked = reranker.rerank(items, lambda = 1.0, budget = budget)

        assertEquals(3, reranked.size)
        assertEquals(listOf(1L, 2L, 3L), reranked.map { it.chunk.id })
    }

    @Test
    fun `lambda zero favors diversity`() {
        val primary = scoredChunk(id = 1, score = 0.95f, vector = floatArrayOf(1f, 0f, 0f))
        val similar = scoredChunk(id = 2, score = 0.85f, vector = floatArrayOf(0.95f, 0.05f, 0f))
        val diverse = scoredChunk(id = 3, score = 0.6f, vector = floatArrayOf(0f, 1f, 0f))

        val reranked = reranker.rerank(listOf(primary, similar, diverse), lambda = 0.0, budget = TokenBudget(maxTokens = 500))

        assertEquals(3, reranked.size)
        assertEquals(1L, reranked.first().chunk.id)
        assertEquals(3L, reranked[1].chunk.id, "Second selection should prefer diversity when lambda=0")
    }

    @Test
    fun `respects token budget`() {
        val smallContent = "fun x() = Unit"
        val largeContent = "class Example { fun big() { println(\"This is a fairly long block of code to inflate tokens\") } }"

        val first = scoredChunk(id = 1, score = 0.9f, vector = floatArrayOf(1f, 0f), content = smallContent)
        val second = scoredChunk(id = 2, score = 0.8f, vector = floatArrayOf(0f, 1f), content = largeContent)

        // Budget small enough to include only the first chunk
        val budget = TokenBudget(maxTokens = 20, reserveForPrompt = 0)
        val reranked = reranker.rerank(listOf(first, second), lambda = 0.5, budget = budget)

        assertEquals(1, reranked.size)
        assertEquals(1L, reranked.first().chunk.id)
    }

    private fun scoredChunk(
        id: Long,
        score: Float,
        vector: FloatArray,
        content: String = "fun example() = Unit"
    ): ScoredChunk {
        val normalized = VectorOps.normalize(vector)
        val chunk = Chunk(
            id = id,
            fileId = id,
            ordinal = 0,
            kind = ChunkKind.CODE_FUNCTION,
            startLine = 1,
            endLine = 5,
            tokenEstimate = content.length / 4,
            content = content,
            summary = "summary",
            createdAt = Instant.EPOCH
        )
        return ScoredChunk(
            chunk = chunk,
            path = "src/File$id.kt",
            language = "kotlin",
            score = score,
            embeddingId = id,
            vector = normalized
        )
    }
}
