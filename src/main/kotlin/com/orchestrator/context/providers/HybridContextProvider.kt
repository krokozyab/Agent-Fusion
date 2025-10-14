package com.orchestrator.context.providers

import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.max

class HybridContextProvider(
    private val providers: List<ContextProvider>,
    private val k: Int = DEFAULT_K,
    private val weights: Map<ContextProviderType, Double> = emptyMap(),
    private val failureStrategy: FailureStrategy = FailureStrategy.SKIP
) : ContextProvider {

    init {
        require(providers.isNotEmpty()) { "At least one provider must be supplied" }
        require(k > 0) { "k must be positive" }
        weights.values.forEach { require(it > 0) { "weights must be positive" } }
    }

    enum class FailureStrategy { SKIP, FAIL }

    override val id: String = "hybrid"
    override val type: ContextProviderType = ContextProviderType.HYBRID

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> = coroutineScope {
        val results = providers.map { provider ->
            async {
                runCatching {
                    provider to provider.getContext(query, scope, budget)
                }
            }
        }.mapNotNull { deferred ->
            val outcome = deferred.await()
            outcome.getOrElse { error ->
                if (failureStrategy == FailureStrategy.FAIL) throw error
                null
            }
        }

        val aggregated = linkedMapOf<Long, MutableEntry>()

        providers.forEach { provider ->
            val providerSnippets = results.firstOrNull { it.first == provider }?.second ?: emptyList()
            providerSnippets.forEachIndexed { index, snippet ->
                val entry = aggregated.getOrPut(snippet.chunkId) {
                    MutableEntry(snippet, mutableListOf(), snippet.score)
                }
                entry.providers += provider.type
                val rank = index + 1
                val weight = weights[provider.type] ?: 1.0
                entry.rrfScore += weight / (k + rank)
            }
        }

        val ordered = aggregated.values
            .sortedByDescending { it.rrfScore }
            .map { entry ->
                val providerCount = entry.providers.distinct().size
                val metadata = entry.snippet.metadata + mapOf(
                    "sources" to mergeSources(entry.snippet.metadata["sources"], entry.providers.map { it.name.lowercase() }),
                    "rrf_score" to "%.4f".format(entry.rrfScore),
                    "rrf_provider_count" to providerCount.toString(),
                    "rrf_agreement" to "%.2f".format(providerCount.toDouble() / providers.size.toDouble())
                )
                entry.snippet.copy(
                    score = entry.rrfScore.coerceIn(0.0, 1.0),
                    metadata = metadata
                )
            }

        enforceBudget(ordered, budget)
    }

    private fun enforceBudget(snippets: List<ContextSnippet>, budget: TokenBudget): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()

        val limit = budget.availableForSnippets.coerceAtLeast(0)
        if (limit == 0) return emptyList()

        val result = mutableListOf<ContextSnippet>()
        var tokensUsed = 0

        for (snippet in snippets) {
            val tokens = max(1, snippet.metadata["token_estimate"]?.toIntOrNull() ?: snippet.text.length / 4)
            if (tokensUsed + tokens > limit) break
            tokensUsed += tokens
            result += snippet
        }
        return result
    }

    private fun mergeSources(existing: String?, providers: List<String>): String {
        val entries = mutableListOf<String>()
        if (!existing.isNullOrBlank()) {
            entries += existing.split(',').map { it.trim() }
        }
        entries += providers
        return entries.filter { it.isNotBlank() }.distinct().joinToString(",")
    }

    private data class MutableEntry(
        val snippet: ContextSnippet,
        val providers: MutableList<ContextProviderType>,
        var rrfScore: Double
    )

    companion object {
        private const val DEFAULT_K = 60
    }
}
