package com.orchestrator.modules.context

import com.orchestrator.context.config.QueryConfig
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.search.MmrReranker
import com.orchestrator.context.search.SearchResult
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Applies post-processing to raw search results: score thresholding, truncation, reranking,
 * and simple memoisation to avoid repeated reranks for identical queries.
 */
class QueryOptimizer(
    private val config: QueryConfig,
    private val reranker: MmrReranker,
    private val clock: Clock = Clock.systemUTC(),
    private val cacheSize: Int = 64,
    private val cacheTtl: Duration = Duration.ofMinutes(10)
) {

    private data class CacheEntry(val timestamp: Instant, val results: List<SearchResult>)
    private data class CacheKey(
        val query: String,
        val maxTokens: Int,
        val reserveForPrompt: Int,
        val diversityWeight: Double,
        val candidateSignature: Int
    )

    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean =
            size > cacheSize
    }

    fun optimize(query: String, results: List<SearchResult>, budget: TokenBudget): List<SearchResult> {
        if (results.isEmpty()) return emptyList()
        val normalizedQuery = query.trim().lowercase()
        val cacheKey = buildCacheKey(normalizedQuery, results, budget)
        val now = Instant.now(clock)

        synchronized(cache) {
            cache.entries.removeIf { (_, entry) -> Duration.between(entry.timestamp, now) > cacheTtl }
            cache[cacheKey]?.let { cached ->
                return cached.results
            }
        }

        var filtered = results
            .filter { it.score >= config.minScoreThreshold }
            .take(config.defaultK.coerceAtLeast(1))

        if (config.rerankEnabled && filtered.isNotEmpty()) {
            filtered = reranker.rerank(filtered, config.mmrLambda, budget)
        }

        synchronized(cache) {
            cache[cacheKey] = CacheEntry(now, filtered)
        }

        return filtered
    }

    private fun buildCacheKey(
        normalizedQuery: String,
        results: List<SearchResult>,
        budget: TokenBudget
    ): CacheKey {
        return CacheKey(
            query = normalizedQuery,
            maxTokens = budget.maxTokens,
            reserveForPrompt = budget.reserveForPrompt,
            diversityWeight = budget.diversityWeight,
            candidateSignature = candidateSignature(results)
        )
    }

    private fun candidateSignature(results: List<SearchResult>): Int {
        var hash = 1
        for (result in results) {
            hash = 31 * hash + result.chunk.id.hashCode()
            hash = 31 * hash + result.embeddingId.hashCode()
            hash = 31 * hash + result.path.hashCode()
            // Intentionally omit score.toBits(): Float scores can fluctuate
            // slightly between identical queries, causing cache misses.
        }
        return hash
    }

}
