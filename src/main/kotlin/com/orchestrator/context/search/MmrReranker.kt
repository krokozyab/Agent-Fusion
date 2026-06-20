package com.orchestrator.context.search

import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.embedding.VectorOps
import com.orchestrator.utils.TokenEstimator

/**
 * Greedy maximal marginal relevance reranker.
 */
class MmrReranker(
    private val tokenEstimator: (String) -> Int = { text -> TokenEstimator.estimateTokens(text) }
) {

    fun rerank(
        results: List<SearchResult>,
        lambda: Double,
        budget: TokenBudget
    ): List<SearchResult> {
        require(lambda in 0.0..1.0) { "lambda must be between 0.0 and 1.0" }
        if (results.isEmpty()) return emptyList()

        val availableTokens = budget.availableForSnippets
        if (availableTokens <= 0) return emptyList()

        val tokenCounts = results.associateWith { chunk ->
            tokenEstimator(chunk.chunk.content)
        }

        val parentPenalty = 0.05  // small diversity boost against siblings
        val leafBoost = 0.02      // slight preference for leaves (no children observed)
        val parentCounts = results.groupingBy { it.chunk.parentChunkId }.eachCount()
        val pathCounts = results.groupingBy { it.chunk.chunkPath }.eachCount()

        fun adjustedRelevance(candidate: SearchResult): Double {
            var rel = candidate.score.toDouble()
            val siblings = parentCounts[candidate.chunk.parentChunkId] ?: 0
            if (candidate.chunk.parentChunkId != null && siblings > 1) {
                rel -= parentPenalty
            }
            val isLeaf = candidate.chunk.chunkPath?.let { path -> (pathCounts[path] ?: 0) <= 1 } ?: false
            if (isLeaf) rel += leafBoost
            return rel
        }

        val candidates = results.sortedByDescending { adjustedRelevance(it) }.toMutableList()
        val selected = mutableListOf<SearchResult>()
        var tokensUsed = 0

        fun fitsBudget(candidate: SearchResult): Boolean {
            val tokens = tokenCounts[candidate] ?: return false
            return tokensUsed + tokens <= availableTokens && tokens > 0
        }

        // Seed with the single most-relevant candidate — even if it alone exceeds the budget.
        // An exact-name match is often one huge chunk (e.g. a 1600-line PL/SQL procedure body);
        // dropping it for being oversized and substituting a smaller, less-relevant neighbour is
        // precisely how an exact-name query loses its real answer. Downstream truncation trims the
        // oversized seed to fit. Lower-relevance oversized candidates are still dropped in the loop
        // below, so the budget is still honoured for everything except the top hit.
        val seed = candidates.first()
        selected += seed
        tokensUsed += tokenCounts[seed] ?: 0
        candidates.remove(seed)

        while (candidates.isNotEmpty()) {
            var bestCandidate: SearchResult? = null
            var bestScore = Double.NEGATIVE_INFINITY
            var bestRelevance = Double.NEGATIVE_INFINITY

            for (candidate in candidates) {
                if (!fitsBudget(candidate)) continue

                val relevance = adjustedRelevance(candidate)
                val maxSimilarity = selected.maxOfOrNull { other ->
                    VectorOps.dotProduct(candidate.vector, other.vector).toDouble()
                } ?: 0.0

                val mmrScore = lambda * relevance - (1.0 - lambda) * maxSimilarity

                if (mmrScore > bestScore) {
                    bestScore = mmrScore
                    bestRelevance = relevance
                    bestCandidate = candidate
                } else if (mmrScore == bestScore && bestCandidate != null) {
                    // Tie-breaker: prefer higher relevance. Compare against the incumbent's
                    // *adjusted relevance* (bestRelevance), not its raw .score — those are different
                    // scales, so the old comparison `relevance > bestCandidate.score` was meaningless.
                    if (relevance > bestRelevance) {
                        bestRelevance = relevance
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
