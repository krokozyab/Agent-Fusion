package com.orchestrator.context.search

import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.search.VectorSearchEngine.ScoredChunk
import com.orchestrator.context.embedding.VectorOps
import com.orchestrator.utils.TokenEstimator

/**
 * Greedy maximal marginal relevance reranker.
 */
class MmrReranker(
    private val tokenEstimator: (String) -> Int = { text -> TokenEstimator.estimateTokens(text) }
) {

    fun rerank(
        results: List<ScoredChunk>,
        lambda: Double,
        budget: TokenBudget
    ): List<ScoredChunk> {
        require(lambda in 0.0..1.0) { "lambda must be between 0.0 and 1.0" }
        if (results.isEmpty()) return emptyList()

        val availableTokens = budget.availableForSnippets
        if (availableTokens <= 0) return emptyList()

        val tokenCounts = results.associateWith { chunk ->
            tokenEstimator(chunk.chunk.content)
        }

        val candidates = results.sortedByDescending { it.score }.toMutableList()
        val selected = mutableListOf<ScoredChunk>()
        var tokensUsed = 0

        fun fitsBudget(candidate: ScoredChunk): Boolean {
            val tokens = tokenCounts[candidate] ?: return false
            return tokensUsed + tokens <= availableTokens && tokens > 0
        }

        // Select the highest relevance candidate that fits the budget as the seed.
        val seed = candidates.firstOrNull { fitsBudget(it) } ?: return emptyList()
        selected += seed
        tokensUsed += tokenCounts[seed] ?: 0
        candidates.remove(seed)

        while (candidates.isNotEmpty()) {
            var bestCandidate: ScoredChunk? = null
            var bestScore = Double.NEGATIVE_INFINITY

            for (candidate in candidates) {
                if (!fitsBudget(candidate)) continue

                val relevance = candidate.score.toDouble()
                val maxSimilarity = selected.maxOfOrNull { other ->
                    VectorOps.dotProduct(candidate.vector, other.vector).toDouble()
                } ?: 0.0

                val mmrScore = lambda * relevance - (1.0 - lambda) * maxSimilarity

                if (mmrScore > bestScore) {
                    bestScore = mmrScore
                    bestCandidate = candidate
                } else if (mmrScore == bestScore && bestCandidate != null) {
                    // Tie-breaker: prefer higher relevance
                    if (relevance > bestCandidate.score) {
                        bestCandidate = candidate
                    }
                }
            }

            val chosen = bestCandidate ?: break
            selected += chosen
            tokensUsed += tokenCounts[chosen] ?: 0
            candidates.remove(chosen)
        }

        return selected
    }
}
