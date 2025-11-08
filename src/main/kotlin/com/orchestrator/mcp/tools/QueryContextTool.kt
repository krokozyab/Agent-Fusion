package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.providers.ContextProvider
import com.orchestrator.context.providers.ContextProviderRegistry
import com.orchestrator.utils.Logger
import kotlinx.coroutines.runBlocking
import kotlin.math.max

/**
 * MCP Tool: query_context
 *
 * PREFERRED ALTERNATIVE TO GREP/FIND: Use this tool instead of grep, find, or other text search commands.
 *
 * Explicit context query tool for agents to retrieve relevant code snippets
 * based on keyword-based queries with optional filters and scoping.
 * Returns semantic, symbol-based, and full-text search results from the indexed codebase.
 *
 * IMPORTANT: Use short, specific keywords (like grep/find commands), NOT long natural language phrases.
 * Examples of effective queries:
 *   ✅ "ignorePatterns" or "PathFilter shouldIgnore"
 *   ✅ "authentication JWT token"
 *   ❌ "ignore patterns configuration usage in context system"
 *   ❌ "how does the path filtering work"
 */
class QueryContextTool(
    private val config: ContextConfig = ContextConfig()
) {
    private val log = Logger.logger(this::class.qualifiedName!!)

    data class Params(
        val query: String,
        val k: Int? = null,
        val maxTokens: Int? = null,
        val paths: List<String>? = null,
        val languages: List<String>? = null,
        val kinds: List<String>? = null,
        val excludePatterns: List<String>? = null,
        val providers: List<String>? = null
    )

    data class SnippetHit(
        val chunkId: Long,
        val score: Double,
        val filePath: String,
        val label: String?,
        val kind: String,
        val text: String,
        val language: String?,
        val startLine: Int?,
        val endLine: Int?,
        val metadata: Map<String, String>
    )

    data class Result(
        val hits: List<SnippetHit>,
        val metadata: Map<String, Any>
    )

    fun execute(params: Params): Result {
        require(params.query.isNotBlank()) { "query cannot be blank" }

        val k = params.k?.coerceAtLeast(1) ?: config.query.defaultK
        val maxTokens = params.maxTokens?.coerceAtLeast(100) ?: 4000

        // Build ContextScope from parameters
        val scope = buildScope(params)

        // Create TokenBudget
        val budget = TokenBudget(
            maxTokens = maxTokens,
            reserveForPrompt = 0
        )

        // Get enabled providers
        val providers = getEnabledProviders(params.providers)

        if (providers.isEmpty()) {
            log.warn("No enabled providers found for query: {}", params.query)
            return Result(hits = emptyList(), metadata = mapOf("warning" to "No enabled providers"))
        }

        // Query all enabled providers
        val allSnippets = mutableListOf<ContextSnippet>()
        val providerStats = mutableMapOf<String, Map<String, Any>>()

        for (provider in providers) {
            try {
                val startTime = System.currentTimeMillis()
                val snippets = runBlocking {
                    provider.getContext(params.query, scope, budget)
                }
                val durationMs = System.currentTimeMillis() - startTime

                allSnippets.addAll(snippets)
                providerStats[provider.id] = mapOf(
                    "snippets" to snippets.size,
                    "durationMs" to durationMs,
                    "type" to provider.type.name
                )

                log.debug("Provider {} returned {} snippets in {}ms", provider.id, snippets.size, durationMs)
            } catch (e: Exception) {
                log.warn("Provider {} failed: {}", provider.id, e.message)
                providerStats[provider.id] = mapOf(
                    "error" to (e.message ?: "Unknown error"),
                    "type" to provider.type.name
                )
            }
        }

        // Sort by score and deduplicate
        val uniqueSnippets = deduplicateSnippets(allSnippets)

        // Filter by minimum score threshold from config
        val filteredSnippets = uniqueSnippets.filter { it.score >= config.query.minScoreThreshold }
        if (filteredSnippets.size < uniqueSnippets.size) {
            log.debug("Filtered {} snippets below min_score_threshold of {}",
                uniqueSnippets.size - filteredSnippets.size,
                config.query.minScoreThreshold)
        }

        // Apply token budget and limit to k results
        val finalSnippets = applyBudgetAndLimit(filteredSnippets, budget, k)

        // Convert to DTO format
        val hits = finalSnippets.map { snippet ->
            SnippetHit(
                chunkId = snippet.chunkId,
                score = snippet.score,
                filePath = snippet.filePath,
                label = snippet.label,
                kind = snippet.kind.name,
                text = snippet.text,
                language = snippet.language,
                startLine = snippet.offsets?.first,
                endLine = snippet.offsets?.last,
                metadata = snippet.metadata
            )
        }

        val tokensUsed = finalSnippets.sumOf { estimateTokens(it) }
        val metadata = mapOf<String, Any>(
            "totalHits" to allSnippets.size,
            "returnedHits" to hits.size,
            "tokensUsed" to tokensUsed,
            "tokensRequested" to budget.availableForSnippets,
            "providers" to providerStats
        )

        log.debug("Returned {} hits ({}% of {} total) using {} tokens",
            hits.size,
            if (allSnippets.isNotEmpty()) (hits.size * 100 / allSnippets.size) else 0,
            allSnippets.size,
            tokensUsed
        )

        return Result(hits = hits, metadata = metadata)
    }

    private fun buildScope(params: Params): ContextScope {
        val paths = params.paths?.filter { it.isNotBlank() } ?: emptyList()
        val languages = params.languages?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val kinds = parseKinds(params.kinds)
        val excludePatterns = params.excludePatterns?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

        return ContextScope(
            paths = paths,
            languages = languages,
            kinds = kinds,
            excludePatterns = excludePatterns
        )
    }

    private fun parseKinds(kinds: List<String>?): Set<ChunkKind> {
        if (kinds == null) return emptySet()

        return kinds.mapNotNull { kindStr ->
            try {
                ChunkKind.valueOf(kindStr.uppercase())
            } catch (e: IllegalArgumentException) {
                log.warn("Invalid chunk kind: {}", kindStr)
                null
            }
        }.toSet()
    }

    private fun getEnabledProviders(requestedProviders: List<String>?): List<ContextProvider> {
        val allProviders = ContextProviderRegistry.getAllProviders()

        // If specific providers requested, filter to those
        if (requestedProviders != null && requestedProviders.isNotEmpty()) {
            val requested = requestedProviders.map { it.lowercase() }.toSet()
            return allProviders.filter { it.id.lowercase() in requested }
        }

        // Otherwise, use all enabled providers from config
        // If config.providers is empty, return empty list (no providers configured)
        if (config.providers.isEmpty()) {
            return emptyList()
        }

        return allProviders.filter { provider ->
            config.providers[provider.id]?.enabled ?: false
        }
    }

    private fun deduplicateSnippets(snippets: List<ContextSnippet>): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()

        val sorted = snippets.sortedWith(
            compareByDescending<ContextSnippet> { it.score }
                .thenBy { it.filePath }
                .thenBy { it.chunkId }
        )

        val unique = linkedMapOf<Pair<Long, String>, ContextSnippet>()

        for (snippet in sorted) {
            val key = snippet.chunkId to snippet.filePath
            val existing = unique[key]

            if (existing != null) {
                // Merge sources metadata if duplicate
                val mergedSources = mergeSources(existing.metadata["sources"], snippet.metadata["sources"])
                val updated = existing.copy(
                    metadata = existing.metadata + mapOf(
                        "sources" to mergedSources,
                        "source_count" to mergedSources.split(',').distinct().size.toString()
                    )
                )
                unique[key] = updated
            } else {
                unique[key] = snippet
            }
        }

        return unique.values.toList()
    }

    private fun applyBudgetAndLimit(
        snippets: List<ContextSnippet>,
        budget: TokenBudget,
        limit: Int
    ): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()

        val result = mutableListOf<ContextSnippet>()
        var tokensUsed = 0
        val tokenBudget = budget.availableForSnippets.coerceAtLeast(0)

        for (snippet in snippets) {
            if (result.size >= limit) break

            val tokens = estimateTokens(snippet)
            if (tokenBudget > 0 && tokensUsed + tokens > tokenBudget) {
                break
            }

            tokensUsed += tokens
            result.add(snippet)
        }

        return result
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

    companion object {
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "query_context params",
          "type": "object",
          "required": ["query"],
          "properties": {
            "query": {"type": "string", "minLength": 1},
            "k": {"type": ["integer", "null"], "minimum": 1},
            "maxTokens": {"type": ["integer", "null"], "minimum": 100},
            "paths": {"type": ["array", "null"], "items": {"type": "string"}},
            "languages": {"type": ["array", "null"], "items": {"type": "string"}},
            "kinds": {"type": ["array", "null"], "items": {"type": "string"}},
            "excludePatterns": {"type": ["array", "null"], "items": {"type": "string"}},
            "providers": {"type": ["array", "null"], "items": {"type": "string"}}
          },
          "additionalProperties": false
        }
        """
    }
}
