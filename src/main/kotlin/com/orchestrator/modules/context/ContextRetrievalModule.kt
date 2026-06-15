package com.orchestrator.modules.context

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.providers.ContextProvider
import com.orchestrator.context.providers.ContextProviderRegistry
import com.orchestrator.context.providers.ContextProviderType
import com.orchestrator.context.search.LinkExpander
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import com.orchestrator.utils.Logger
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.max

/**
 * Coordinates context providers, budget allocation, and telemetry for a task request.
 */
class ContextRetrievalModule(
    private val config: ContextConfig,
    private val agentDirectory: AgentDirectory,
    private val budgetManager: BudgetManager,
    private val queryOptimizer: QueryOptimizer,
    private val metricsRecorder: ContextMetricsRecorder,
    private val linkExpander: LinkExpander = LinkExpander(config.query.graph),
    private val logger: Logger = Logger.logger<ContextRetrievalModule>()
) {

    data class TaskContext(
        val taskId: String,
        val snippets: List<ContextSnippet>,
        val diagnostics: ContextDiagnostics,
        val metadata: Map<String, String> = emptyMap()
    )

    data class ContextDiagnostics(
        val budget: TokenBudget,
        val providerMetrics: Map<String, ProviderStatsEntry>,
        val duration: Duration,
        val warnings: List<String>,
        val fallbackUsed: Boolean,
        val fallbackProvider: String?,
        val tokensRequested: Int,
        val tokensUsed: Int
    )

    sealed interface ProviderStatsEntry {
        val providerId: String
        val providerType: ContextProviderType
    }

    data class ProviderStats(
        override val providerId: String,
        override val providerType: ContextProviderType,
        val snippetCount: Int,
        val durationMs: Double,
        val isFallback: Boolean = false
    ) : ProviderStatsEntry

    data class ProviderStatsFailure(
        override val providerId: String,
        override val providerType: ContextProviderType,
        val error: String
    ) : ProviderStatsEntry

    suspend fun getTaskContext(task: Task, agentId: AgentId): TaskContext {
        val budget = budgetManager.calculateBudget(task, agentId)
        val overallStart = Instant.now()
        val warnings = mutableListOf<String>()

        if (budget.availableForSnippets <= 0) {
            warnings += "No tokens available for snippets"
            val diagnostics = ContextDiagnostics(
                budget = budget,
                providerMetrics = emptyMap(),
                duration = Duration.ZERO,
                warnings = warnings,
                fallbackUsed = false,
                fallbackProvider = null,
                tokensRequested = budget.availableForSnippets,
                tokensUsed = 0
            )
            val context = TaskContext(task.id.value, emptyList(), diagnostics)
            metricsRecorder.record(task, agentId, context, Duration.ZERO)
            return context
        }

        val providerMetrics = linkedMapOf<String, ProviderStatsEntry>()
        val aggregated = mutableListOf<ContextSnippet>()

        val query = task.description?.takeIf { it.isNotBlank() } ?: task.title
        val scope = ContextScope()

        val providers = ContextProviderRegistry.getAllProviders()
            .map { provider -> providerKey(provider) to provider }
            .filter { (providerId, _) ->
                config.providers[providerId]?.enabled ?: true
            }

        for ((providerId, provider) in providers) {
            val providerStart = Instant.now()
            try {
                val snippets = provider.getContext(query, scope, budget)
                val optimised = optimise(provider, query, snippets, budget)
                val annotated = optimised.map { annotateSnippet(it, providerId) }
                aggregated += annotated
                val duration = Duration.between(providerStart, Instant.now())
                providerMetrics[providerId] = ProviderStats(
                    providerId = providerId,
                    providerType = provider.type,
                    snippetCount = annotated.size,
                    durationMs = duration.toMillis().toDouble(),
                    isFallback = false
                )
            } catch (t: Throwable) {
                logger.warn("Provider {} failed: {}", providerId, t.message ?: t::class.simpleName ?: "error")
                providerMetrics[providerId] = ProviderStatsFailure(
                    providerId = providerId,
                    providerType = provider.type,
                    error = t.message ?: t::class.simpleName ?: "error"
                )
            }
        }

        var fallbackUsed = false
        var fallbackProvider: String? = null

        if (aggregated.isEmpty()) {
            val fallbackId = config.providers.keys.firstOrNull { it.equals("semantic", ignoreCase = true) }
            val fallback = fallbackId?.let { ContextProviderRegistry.getProvider(it) }
            if (fallback != null) {
                val resolvedFallbackId = providerKey(fallback)
                val fallbackStart = Instant.now()
                // Guard the fallback exactly like the main providers: it exists to recover from an
                // empty result, so its own failure must not crash the whole context retrieval.
                try {
                    val snippets = fallback.getContext(query, scope, budget)
                    val optimised = optimise(fallback, query, snippets, budget)
                    val annotated = optimised.map { annotateSnippet(it, resolvedFallbackId) }
                    aggregated += annotated
                    val duration = Duration.between(fallbackStart, Instant.now())
                    providerMetrics[resolvedFallbackId] = ProviderStats(
                        providerId = resolvedFallbackId,
                        providerType = fallback.type,
                        snippetCount = annotated.size,
                        durationMs = duration.toMillis().toDouble(),
                        isFallback = true
                    )
                    if (annotated.isNotEmpty()) {
                        fallbackUsed = true
                        fallbackProvider = resolvedFallbackId
                    }
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    logger.warn("Fallback provider {} failed: {}", resolvedFallbackId, t.message ?: t::class.simpleName ?: "error")
                    providerMetrics[resolvedFallbackId] = ProviderStatsFailure(
                        providerId = resolvedFallbackId,
                        providerType = fallback.type,
                        error = t.message ?: t::class.simpleName ?: "error"
                    )
                }
            }
        }

        if (aggregated.isEmpty()) {
            warnings += "No providers returned context"
        }

        val expanded = try {
            linkExpander.expand(aggregated)
        } catch (t: Throwable) {
            logger.warn("Graph link expansion failed: {}", t.message)
            aggregated
        }

        val finalSnippets = deduplicateAndLimit(expanded, budget)
        val tokensUsed = finalSnippets.sumOf { estimateTokens(it) }
        val duration = Duration.between(overallStart, Instant.now())

        val diagnostics = ContextDiagnostics(
            budget = budget,
            providerMetrics = providerMetrics,
            duration = duration,
            warnings = warnings,
            fallbackUsed = fallbackUsed,
            fallbackProvider = fallbackProvider,
            tokensRequested = budget.availableForSnippets,
            tokensUsed = tokensUsed
        )

        val context = TaskContext(task.id.value, finalSnippets, diagnostics)
        metricsRecorder.record(task, agentId, context, duration)
        return context
    }

    private fun optimise(
        provider: ContextProvider,
        query: String,
        snippets: List<ContextSnippet>,
        budget: TokenBudget
    ): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()

        // All providers now go through the same score-threshold + sort + truncate path.
        // MMR with fake vectors was causing score distortion; real embedding-based MMR
        // is only applied in the QueryContextTool path where stored vectors are available.
        return snippets
            .filter { it.score >= config.query.minScoreThreshold }
            .sortedByDescending { it.score }
            .take(config.query.defaultK.coerceAtLeast(1))
    }

    private fun annotateSnippet(snippet: ContextSnippet, providerId: String): ContextSnippet {
        val existingSources = snippet.metadata["sources"]
        val sources = when {
            existingSources.isNullOrBlank() -> providerId
            existingSources.contains(providerId) -> existingSources
            else -> "$existingSources,$providerId"
        }
        val updatedMetadata = snippet.metadata +
            mapOf(
                "provider" to providerId,
                "sources" to sources,
                "source_count" to sources.split(',').distinct().size.toString()
            )
        return snippet.copy(metadata = updatedMetadata)
    }

    private fun deduplicateAndLimit(
        snippets: List<ContextSnippet>,
        budget: TokenBudget
    ): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()

        // Weighted fusion-aware aggregation (same as QueryContextTool).
        // Each provider's score is multiplied by its configured weight.
        data class WeightedScore(val score: Double, val weight: Double)
        data class Acc(
            var bestSnippet: ContextSnippet,
            val sources: MutableSet<String>,
            val weightedScores: MutableList<WeightedScore>
        )

        val grouped = linkedMapOf<Pair<Long, String>, Acc>()
        for (snippet in snippets) {
            val key = snippet.chunkId to snippet.filePath
            val providerSources = snippet.metadata["sources"]
                ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet()
            val providerId = snippet.metadata["provider"] ?: providerSources.firstOrNull() ?: ""
            val weight = config.providers[providerId]?.weight ?: 1.0

            val existing = grouped[key]
            if (existing != null) {
                existing.sources.addAll(providerSources)
                existing.weightedScores += WeightedScore(snippet.score, weight)
                if (snippet.score > existing.bestSnippet.score) {
                    existing.bestSnippet = snippet
                }
            } else {
                grouped[key] = Acc(
                    bestSnippet = snippet,
                    sources = providerSources.toMutableSet(),
                    weightedScores = mutableListOf(WeightedScore(snippet.score, weight))
                )
            }
        }

        val fused = grouped.values.map { acc ->
            val mergedSources = acc.sources.joinToString(",")
            val providerCount = acc.sources.size
            val totalWeight = acc.weightedScores.sumOf { it.weight }
            val weightedMean = if (totalWeight > 0) acc.weightedScores.sumOf { it.score * it.weight } / totalWeight else 0.0
            val agreementMultiplier = 1.0 + (providerCount - 1) * 0.15
            val fusedScore = (weightedMean * agreementMultiplier).coerceAtMost(1.0)
            acc.bestSnippet.copy(
                score = fusedScore,
                metadata = acc.bestSnippet.metadata + mapOf(
                    "sources" to mergedSources,
                    "source_count" to providerCount.toString()
                )
            )
        }.sortedByDescending { it.score }

        // Apply token budget
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)
        val result = mutableListOf<ContextSnippet>()
        var tokensUsed = 0

        for (snippet in fused) {
            val tokens = estimateTokens(snippet)
            if (tokenBudget > 0 && tokensUsed + tokens > tokenBudget) continue
            tokensUsed += tokens
            result += snippet
        }

        return result
    }

    private fun providerKey(provider: ContextProvider): String {
        val resolved = runCatching { provider.id }.getOrNull()
        if (!resolved.isNullOrBlank()) {
            return resolved
        }
        return provider.type.name.lowercase(Locale.US)
    }

    private fun mergeSources(a: String?, b: String?): String =
        listOfNotNull(a, b)
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")

    private fun estimateTokens(snippet: ContextSnippet): Int =
        max(1, snippet.metadata["token_estimate"]?.toIntOrNull() ?: snippet.text.length / 4)
}
