package com.orchestrator.context.providers

import com.orchestrator.context.config.BoostConfig
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.util.Locale
import kotlin.math.max

class HybridContextProvider(
    private val providers: List<ContextProvider>? = null,
    private val k: Int = DEFAULT_K,
    private val weights: Map<ContextProviderType, Double> = emptyMap(),
    private val failureStrategy: FailureStrategy = FailureStrategy.SKIP,
    private val boostConfig: BoostConfig = BoostConfig()
) : ContextProvider {

    // Lazy initialization to discover other providers when needed
    private val effectiveProviders: List<ContextProvider> by lazy {
        providers ?: discoverNonHybridProviders()
    }

    init {
        // If providers is explicitly provided (not null), it must not be empty
        if (providers != null) {
            require(providers.isNotEmpty()) { "At least one provider must be supplied" }
        }
        require(k > 0) { "k must be positive" }
        weights.values.forEach { require(it > 0) { "weights must be positive" } }
    }

    /**
     * Discover all non-hybrid providers using ServiceLoader.
     * This allows HybridContextProvider to be discovered via SPI
     * without circular dependencies.
     */
    private fun discoverNonHybridProviders(): List<ContextProvider> {
        val loader = java.util.ServiceLoader.load(ContextProvider::class.java)
        return loader.filter { it.type != ContextProviderType.HYBRID }.toList()
    }

    enum class FailureStrategy { SKIP, FAIL }

    private val log = Logger.logger<HybridContextProvider>()

    override val id: String = "hybrid"
    override val type: ContextProviderType = ContextProviderType.HYBRID

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> = coroutineScope {
        val results = effectiveProviders.map { provider ->
            async {
                try {
                    provider to provider.getContext(query, scope, budget)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (failureStrategy == FailureStrategy.FAIL) throw e
                    // SKIP strategy: don't fail the whole query, but never swallow silently —
                    // a degraded provider must be visible in the logs.
                    log.warn("Hybrid provider {} failed and was skipped: {}", provider.type, e.message)
                    null
                }
            }
        }.awaitAll().filterNotNull()

        val aggregated = linkedMapOf<Long, MutableEntry>()

        effectiveProviders.forEach { provider ->
            val providerSnippets = results.firstOrNull { it.first == provider }?.second ?: emptyList()
            providerSnippets.forEachIndexed { index, snippet ->
                val entry = aggregated.getOrPut(snippet.chunkId) {
                    MutableEntry(snippet, mutableListOf(), 0.0)
                }
                entry.providers += provider.type
                val rank = index + 1
                val weight = weights[provider.type] ?: 1.0
                entry.rrfScore += weight / (k + rank)
            }
        }

        // Raw RRF scores are tiny (max ~1/(k+1) per provider, e.g. ~0.016 at k=60) and would be
        // wiped out by the downstream minScore threshold (default 0.3). Normalize by the maximum
        // so the top hit maps to 1.0 while preserving relative ordering — matching ReciprocalRankFusion.
        val maxRrf = aggregated.values.maxOfOrNull { it.rrfScore } ?: 0.0
        if (maxRrf <= 0.0) return@coroutineScope emptyList()

        val ordered = aggregated.values
            .map { entry ->
                // Use the normalized fused RRF score as the base, then apply penalties on top.
                val normalizedScore = (entry.rrfScore / maxRrf).coerceIn(0.0, 1.0)
                val baseSnippet = entry.snippet.copy(score = normalizedScore)
                val penalizedSnippet = applyPenalties(baseSnippet)

                val providerCount = entry.providers.distinct().size
                val metadata = penalizedSnippet.metadata + mapOf(
                    "sources" to mergeSources(penalizedSnippet.metadata["sources"], entry.providers.map { it.name.lowercase() }),
                    "rrf_score" to "%.4f".format(Locale.US, entry.rrfScore),
                    "rrf_provider_count" to providerCount.toString(),
                    "rrf_agreement" to "%.2f".format(Locale.US, providerCount.toDouble() / effectiveProviders.size.toDouble())
                )
                penalizedSnippet.copy(metadata = metadata)
            }
            .sortedByDescending { it.score }

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

    /**
     * Apply file type penalties, pattern penalties, and chunk kind boosts to a snippet.
     * Returns a new snippet with adjusted score and penalty metadata.
     */
    private fun applyPenalties(snippet: ContextSnippet): ContextSnippet {
        val fileTypePenalty = getFileTypePenalty(snippet.filePath)
        val patternPenalty = getFilePatternPenalty(snippet.filePath)
        val kindBoost = getChunkKindBoost(snippet.kind)

        // Combine all penalties multiplicatively
        val combinedMultiplier = fileTypePenalty * patternPenalty * kindBoost
        val adjustedScore = (snippet.score * combinedMultiplier).coerceIn(0.0, 1.0)

        // Add penalty info to metadata for transparency
        val updatedMetadata = snippet.metadata + mapOf(
            "file_type_penalty" to "%.3f".format(Locale.US, fileTypePenalty),
            "pattern_penalty" to "%.3f".format(Locale.US, patternPenalty),
            "kind_boost" to "%.3f".format(Locale.US, kindBoost),
            "combined_multiplier" to "%.3f".format(Locale.US, combinedMultiplier),
            "original_score" to "%.4f".format(Locale.US, snippet.score)
        )

        return snippet.copy(
            score = adjustedScore,
            metadata = updatedMetadata
        )
    }

    /**
     * Get penalty for a specific file extension.
     * Returns 1.0 (no penalty) if extension not in penalty map.
     */
    private fun getFileTypePenalty(filePath: String): Double {
        val extension = filePath.substringAfterLast('.', "")
        if (extension.isBlank()) return 1.0
        return boostConfig.fileTypePenalties[extension] ?: 1.0
    }

    /**
     * Get penalty for file path matching configured patterns.
     * Checks all patterns and returns the minimum penalty (most restrictive).
     */
    private fun getFilePatternPenalty(filePath: String): Double {
        if (boostConfig.filePatternPenalties.isEmpty()) return 1.0

        // Find matching patterns and get the minimum penalty (most restrictive)
        val penalties = boostConfig.filePatternPenalties.mapNotNull { (pattern, penalty) ->
            if (matchesGlobPattern(filePath, pattern)) penalty else null
        }

        return penalties.minOrNull() ?: 1.0
    }

    /**
     * Get boost for a specific chunk kind.
     * Returns 1.0 (no boost/penalty) if kind not in boost map.
     */
    private fun getChunkKindBoost(kind: com.orchestrator.context.domain.ChunkKind): Double {
        return boostConfig.chunkKindBoosts[kind.name] ?: 1.0
    }

    /**
     * Simple glob pattern matcher supporting wildcards.
     */
    private fun matchesGlobPattern(path: String, pattern: String): Boolean {
        // Normalize paths
        val normalizedPath = path.replace('\\', '/').removePrefix("/")
        val normalizedPattern = pattern.replace('\\', '/')

        // A leading "**/" means "zero or more path segments" → optional prefix, so "**/foo" matches
        // both "foo" and "bar/foo". Decide this on the raw pattern BEFORE escaping. The old code
        // inspected the escaped regex and did substring(3) assuming the prefix was ".*/" (3 chars);
        // for patterns like "**.log" that chopped the backslash off "\." and left a bare "." that
        // matched any character (so "**.log" wrongly matched e.g. "catalog").
        val anchorPrefix: String
        val core: String
        if (normalizedPattern.startsWith("**/")) {
            anchorPrefix = "^(.*/)?"
            core = normalizedPattern.removePrefix("**/")
        } else {
            anchorPrefix = "^"
            core = normalizedPattern
        }

        val regex = anchorPrefix + core
            .replace(".", "\\.")  // Escape dots
            .replace("**", "###DOUBLESTAR###")  // Temporarily replace **
            .replace("*", "[^/]*")  // * matches anything except /
            .replace("###DOUBLESTAR###", ".*") + "$"  // ** matches anything including /

        return try {
            Regex(regex).matches(normalizedPath)
        } catch (e: Exception) {
            // Fallback to simple contains check
            normalizedPath.contains(normalizedPattern.replace("**", "").replace("*", ""))
        }
    }

    companion object {
        private const val DEFAULT_K = 60
    }
}
