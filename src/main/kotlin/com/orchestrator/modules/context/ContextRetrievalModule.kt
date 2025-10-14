package com.orchestrator.modules.context

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.providers.ContextProvider
import com.orchestrator.context.providers.ContextProviderRegistry
import com.orchestrator.context.providers.ContextProviderType
import com.orchestrator.context.search.SearchResult
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import com.orchestrator.utils.Logger
import java.time.Duration
import java.time.Instant
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

        val enabledProviders = ContextProviderRegistry.getAllProviders()
            .filter { provider ->
                config.providers[provider.id]?.enabled ?: true
            }

        for (provider in enabledProviders) {
            val providerStart = Instant.now()
            try {
                val snippets = provider.getContext(query, scope, budget)
                val optimised = optimise(provider, query, snippets, budget)
                val annotated = optimised.map { annotateSnippet(it, provider.id) }
                aggregated += annotated
                val duration = Duration.between(providerStart, Instant.now())
                providerMetrics[provider.id] = ProviderStats(
                    providerId = provider.id,
                    providerType = provider.type,
                    snippetCount = annotated.size,
                    durationMs = duration.toMillis().toDouble(),
                    isFallback = false
                )
            } catch (t: Throwable) {
                logger.warn("Provider {} failed: {}", provider.id, t.message ?: t::class.simpleName ?: "error")
                providerMetrics[provider.id] = ProviderStatsFailure(
                    providerId = provider.id,
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
                val fallbackStart = Instant.now()
                val snippets = fallback.getContext(query, scope, budget)
                val optimised = optimise(fallback, query, snippets, budget)
                val annotated = optimised.map { annotateSnippet(it, fallback.id) }
                aggregated += annotated
                val duration = Duration.between(fallbackStart, Instant.now())
                providerMetrics[fallback.id] = ProviderStats(
                    providerId = fallback.id,
                    providerType = fallback.type,
                    snippetCount = annotated.size,
                    durationMs = duration.toMillis().toDouble(),
                    isFallback = true
                )
                if (annotated.isNotEmpty()) {
                    fallbackUsed = true
                    fallbackProvider = fallback.id
                }
            }
        }

        if (aggregated.isEmpty()) {
            warnings += "No providers returned context"
        }

        val finalSnippets = deduplicateAndLimit(aggregated, budget)
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

        val results = snippets.mapIndexed { index, snippet ->
            SearchResult(
                chunk = snippet.asChunk(index),
                score = snippet.score.toFloat(),
                embeddingId = snippet.chunkId,
                path = snippet.filePath,
                language = snippet.language,
                vector = floatArrayOf(snippet.score.toFloat())
            )
        }
        val optimised = queryOptimizer.optimize(query, results, budget)
        val byChunkId = snippets.associateBy { it.chunkId }
        return optimised.mapNotNull { result ->
            byChunkId[result.chunk.id] ?: byChunkId[result.embeddingId]
        }
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

        val sorted = snippets.sortedWith(
            compareByDescending<ContextSnippet> { it.score }
                .thenBy { it.filePath }
                .thenBy { it.chunkId }
        )

        val unique = linkedMapOf<Pair<Long, String>, ContextSnippet>()
        var tokensUsed = 0
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)

        for (snippet in sorted) {
            val key = snippet.chunkId to snippet.filePath
            val tokens = estimateTokens(snippet)

            val existing = unique[key]
            if (existing != null) {
                val mergedSources = mergeSources(existing.metadata["sources"], snippet.metadata["sources"])
                val updated = existing.copy(
                    metadata = existing.metadata + mapOf(
                        "sources" to mergedSources,
                        "source_count" to mergedSources.split(',').distinct().size.toString()
                    )
                )
                unique[key] = updated
                continue
            }

            if (tokenBudget > 0 && tokensUsed + tokens > tokenBudget) {
                continue
            }

            tokensUsed += tokens
            unique[key] = snippet
        }

        return unique.values.toList()
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

    private fun ContextSnippet.asChunk(ordinal: Int): Chunk = Chunk(
        id = chunkId,
        fileId = chunkId,
        ordinal = ordinal,
        kind = kind,
        startLine = offsets?.first ?: 0,
        endLine = offsets?.last ?: 0,
        tokenEstimate = estimateTokens(this),
        content = text,
        summary = label,
        createdAt = Instant.EPOCH
    )
}
