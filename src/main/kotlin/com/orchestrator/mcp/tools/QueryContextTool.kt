package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.embedding.Embedder
import com.orchestrator.context.embedding.LocalEmbedder
import com.orchestrator.context.providers.ContextProvider
import com.orchestrator.context.providers.ContextProviderType
import com.orchestrator.context.providers.ContextProviderRegistry
import com.orchestrator.context.providers.ResultOrganizer
import com.orchestrator.context.search.MmrReranker
import com.orchestrator.context.search.NeighborExpander
import com.orchestrator.context.search.ScoreBooster
import com.orchestrator.context.search.VectorSearchEngine
import com.orchestrator.context.ContextRepository
import com.orchestrator.context.ContextRepository.ChunkWithFile
import com.orchestrator.modules.context.QueryOptimizer
import com.orchestrator.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.max

/**
 * MCP Tool: query_context
 *
 * PREFERRED ALTERNATIVE TO GREP/FIND: Use this tool instead of grep, find, or other text search commands.
 *
 * Explicit context query tool for agents to retrieve relevant code snippets
 * based on semantic search with optional filters and scoping.
 * Returns semantic, symbol-based, and full-text search results from the indexed codebase.
 * Uses sentence-transformers/all-MiniLM-L6-v2 model for understanding semantic meaning.
 *
 * IMPORTANT: Use domain-specific descriptive phrases (5-10 words) with concrete terms.
 * Avoid question words and abstract meta-queries.
 *
 * Examples of effective queries:
 *   ✅ "PGP encryption FTP adapter configuration"
 *   ✅ "authentication JWT token validation implementation"
 *   ✅ "PathFilter shouldIgnore file exclusion patterns"
 *   ❌ "how does the path filtering work in context system" (question format)
 *   ❌ "query_context tool definition" (meta-query, too abstract)
 *   ❌ "explain authentication" (explanation-seeking, no concrete terms)
 */
class QueryContextTool(
    private val config: ContextConfig = ContextConfig()
) {
    private val log = Logger.logger(this::class.qualifiedName!!)

    // Lazy initialization of optimizer and embedder
    private val embedder: Embedder by lazy {
        LocalEmbedder(
            modelPath = config.embedding.modelPath?.let { java.nio.file.Path.of(it) },
            modelName = config.embedding.model,
            dimension = config.embedding.dimension,
            normalize = config.embedding.normalize,
            maxBatchSize = config.embedding.batchSize
        )
    }
    private val reranker: MmrReranker by lazy { MmrReranker() }
    private val queryOptimizer: QueryOptimizer by lazy {
        QueryOptimizer(config.query, reranker)
    }
    private val neighborExpander: NeighborExpander by lazy { NeighborExpander() }
    private val scoreBooster: ScoreBooster by lazy { ScoreBooster(config.query.boosts) }

    private val resultOrganizer: ResultOrganizer by lazy {
        ResultOrganizer()
    }

    // Whether to use structured output (markdown)
    private val useStructuredOutput = config.useStructuredOutput
    
    // LRU cache for embeddings of non-semantic results
    private val embeddingCache = object : LinkedHashMap<String, FloatArray>(
        config.query.embeddingCacheSize,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?): Boolean =
            size > config.query.embeddingCacheSize
    }

    data class Params(
        val query: String = "",
        val chunkIds: List<Long>? = null,
        val k: Int? = null,
        val maxTokens: Int? = null,
        val paths: List<String>? = null,
        val languages: List<String>? = null,
        val kinds: List<String>? = null,
        val excludePatterns: List<String>? = null,
        val providers: List<String>? = null,
        val format: String? = null
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
        val chunkPath: String?,
        val parentChunkId: Long?,
        val siblingChunkIds: List<Long> = emptyList(),
        val prevChunkId: Long? = null,
        val nextChunkId: Long? = null,
        val childrenChunkIds: List<Long> = emptyList(),
        val depth: Int? = null,
        val chunkPathSegments: List<String>? = null,
        val metadata: Map<String, String>
    )

    data class Result(
        val hits: List<SnippetHit>,
        val metadata: Map<String, Any>,
        val content: String? = null  // Hierarchical markdown output (Phase 3)
    )

    private data class ProviderQueryResult(
        val providerId: String,
        val providerType: String,
        val snippets: List<ContextSnippet>,
        val durationMs: Long,
        val error: String? = null
    )

    fun execute(params: Params): Result {
        if (params.chunkIds.isNullOrEmpty()) {
            require(params.query.isNotBlank()) { "query cannot be blank" }
        }

        // Direct lookup by chunk IDs
        if (!params.chunkIds.isNullOrEmpty()) {
            return fetchChunksByIds(params.chunkIds)
        }

        // Validate query quality and provide suggestions if likely to fail
        val queryWarnings = validateQueryQuality(params.query)

        val k = params.k?.coerceAtLeast(1) ?: config.query.defaultK
        val maxTokens = params.maxTokens?.coerceAtLeast(100) ?: 4000

        // Build ContextScope from parameters
        val scope = buildScope(params)

        // Create TokenBudget
        val reserveForPrompt = config.budget.reserveForPrompt
            .coerceAtLeast(0)
            .coerceAtMost(maxTokens / 2)
        val budget = TokenBudget(
            maxTokens = maxTokens,
            reserveForPrompt = reserveForPrompt
        )

        // Get enabled providers
        val providers = getEnabledProviders(params.providers)

        if (providers.isEmpty()) {
            log.warn("No enabled providers found for query: {}", params.query)
            return Result(hits = emptyList(), metadata = mapOf("warning" to "No enabled providers"))
        }

        // Query all enabled providers concurrently.
        val providerResults = queryProviders(params.query, scope, budget, providers)
        val allSnippets = mutableListOf<ContextSnippet>()
        val providerStats = linkedMapOf<String, Map<String, Any>>()

        for (result in providerResults) {
            allSnippets.addAll(result.snippets)
            providerStats[result.providerId] = if (result.error == null) {
                mapOf(
                    "snippets" to result.snippets.size,
                    "durationMs" to result.durationMs,
                    "type" to result.providerType
                )
            } else {
                mapOf(
                    "error" to result.error,
                    "type" to result.providerType,
                    "durationMs" to result.durationMs
                )
            }
        }

        // Sort by score and deduplicate
        val uniqueSnippets = deduplicateSnippets(allSnippets)

        // Apply path/language boosts
        val boostedSnippets = applyScoreBoosts(uniqueSnippets)
        
        // Filter by minimum score threshold from config
        val filteredSnippets = boostedSnippets.filter { it.score >= config.query.minScoreThreshold }
        if (filteredSnippets.size < boostedSnippets.size) {
            log.debug("Filtered {} snippets below min_score_threshold of {}",
                boostedSnippets.size - filteredSnippets.size,
                config.query.minScoreThreshold)
        }

        // Apply MMR optimization if enabled
        val shouldRunMmr = config.query.useOptimizerInTool &&
            filteredSnippets.isNotEmpty() &&
            isMmrEligible(providers)
        val optimizedSnippets = if (shouldRunMmr) {
            applyMmrOptimization(params.query, filteredSnippets, budget)
        } else {
            if (config.query.useOptimizerInTool && filteredSnippets.isNotEmpty() && !isMmrEligible(providers)) {
                log.debug("Skipping MMR optimization for non-semantic provider set: {}",
                    providers.joinToString(",") { it.id })
            }
            filteredSnippets
        }
        
        // Apply neighbor expansion if enabled
        val expandedSnippets = if (config.query.neighborWindow > 0 && optimizedSnippets.isNotEmpty()) {
            applyNeighborExpansion(optimizedSnippets)
        } else {
            optimizedSnippets
        }

        // Ensure highest-score-first ordering
        val rankedSnippets = expandedSnippets.sortedWith(
            compareByDescending<ContextSnippet> { it.score }
                .thenBy { it.filePath }
                .thenBy { it.chunkId }
        )

        // Apply token budget and limit to k results
        val finalSnippets = applyBudgetAndLimit(rankedSnippets, budget, k)

        val (existingSnippets, purgedCount) = dropMissingFiles(finalSnippets)
        if (purgedCount > 0) {
            log.warn("Dropped {} query_context snippets because files no longer exist on disk", purgedCount)
        }

        // Build parent relationships using stored parentChunkId or derived from chunk_path prefix
        val pathToId = existingSnippets.mapNotNull { snippet ->
            snippet.chunkPath?.let { it to snippet.chunkId }
        }.toMap()

        data class Enriched(val snippet: ContextSnippet, val parentId: Long?)

        val enriched = existingSnippets.map { snippet ->
            val derivedParent = snippet.chunkPath
                ?.let { path -> path.lastIndexOf('/').takeIf { it > 0 }?.let { path.substring(0, it) } }
                ?.let { pathToId[it] }
            Enriched(snippet, snippet.parentChunkId ?: derivedParent)
        }

        val byParent = enriched.groupBy { it.parentId }
        val orderedByFile = enriched.groupBy { it.snippet.filePath }.mapValues { (_, list) ->
            list.sortedWith(compareByDescending<Enriched> { it.snippet.score }.thenBy { it.snippet.chunkId })
        }
        val children = enriched.groupBy { it.parentId }.mapValues { entry -> entry.value.map { it.snippet.chunkId } }

        val hits = enriched.map { enrichedSnippet ->
            val snippet = enrichedSnippet.snippet
            val meta = snippet.metadata.toMutableMap()
            snippet.chunkPath?.let { meta["chunk_path"] = it }
            enrichedSnippet.parentId?.let { meta["parent_chunk_id"] = it.toString() }
            val siblings = byParent[enrichedSnippet.parentId].orEmpty().map { it.snippet.chunkId }.filter { it != snippet.chunkId }
            val orderedSiblings = orderedByFile[snippet.filePath].orEmpty()
            val idx = orderedSiblings.indexOfFirst { it.snippet.chunkId == snippet.chunkId }
            val prevId = if (idx > 0) orderedSiblings[idx - 1].snippet.chunkId else null
            val nextId = if (idx >= 0 && idx + 1 < orderedSiblings.size) orderedSiblings[idx + 1].snippet.chunkId else null
            val pathSegments = snippet.chunkPath?.split("/")?.filter { it.isNotBlank() }
            val depth = snippet.chunkPath?.count { it == '/' }
            val childIds = children[snippet.chunkId] ?: emptyList()
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
                chunkPath = snippet.chunkPath,
                parentChunkId = enrichedSnippet.parentId,
                siblingChunkIds = siblings,
                prevChunkId = prevId,
                nextChunkId = nextId,
                childrenChunkIds = childIds,
                depth = depth,
                chunkPathSegments = pathSegments,
                metadata = meta
            )
        }

        val tokensUsed = existingSnippets.sumOf { estimateTokens(it) }
        val metadata = buildMap<String, Any> {
            put("totalHits", allSnippets.size)
            put("returnedHits", hits.size)
            put("tokensUsed", tokensUsed)
            put("tokensRequested", budget.availableForSnippets)
            put("providers", providerStats)

            // Add query warnings if query is likely to fail
            if (queryWarnings.isNotEmpty()) {
                put("queryWarnings", queryWarnings)
                if (allSnippets.isEmpty()) {
                    put("suggestionForEmptyResults",
                        "Query may be too short or generic. Try: ${suggestBetterQuery(params.query)}")
                }
            }
        }

        log.debug("Returned {} hits ({}% of {} total) using {} tokens",
            hits.size,
            if (allSnippets.isNotEmpty()) (hits.size * 100 / allSnippets.size) else 0,
            allSnippets.size,
            tokensUsed
        )

        // Generate hierarchical markdown content if enabled or requested
        val shouldUseStructuredOutput = useStructuredOutput || params.format?.lowercase() == "markdown"
        val content = if (shouldUseStructuredOutput && existingSnippets.isNotEmpty()) {
            try {
                resultOrganizer.organizeHierarchically(existingSnippets)
            } catch (e: Exception) {
                log.warn("Failed to organize results hierarchically: {}", e.message)
                null
            }
        } else {
            null
        }

        return Result(hits = hits, metadata = metadata, content = content)
    }

    private fun buildScope(params: Params): ContextScope {
        val paths = params.paths?.filter { it.isNotBlank() } ?: emptyList()
        // Normalize relative paths to absolute paths for filtering
        val normalizedPaths = paths.map { normalizePath(it) }
        val languages = params.languages?.filter { it.isNotBlank() }?.toSet() ?: emptySet()
        val kinds = parseKinds(params.kinds)
        val excludePatterns = params.excludePatterns?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

        return ContextScope(
            paths = normalizedPaths,
            languages = languages,
            kinds = kinds,
            excludePatterns = excludePatterns
        )
    }

    /**
     * Normalize a path to absolute form for database filtering.
     * Relative paths are resolved against the current working directory.
     * Absolute paths are returned as-is after normalization.
     */
    private fun normalizePath(pathStr: String): String {
        val candidate = Path.of(pathStr)
        val baseDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val resolved = if (candidate.isAbsolute) {
            candidate
        } else {
            baseDir.resolve(candidate)
        }
        return resolved.normalize().toString()
    }

    private fun dropMissingFiles(snippets: List<ContextSnippet>): Pair<List<ContextSnippet>, Int> {
        if (snippets.isEmpty()) return snippets to 0
        val existing = ArrayList<ContextSnippet>(snippets.size)
        var purged = 0
        for (snippet in snippets) {
            val exists = runCatching { Files.exists(Path.of(snippet.filePath)) }.getOrElse { false }
            if (exists) {
                existing.add(snippet)
            } else {
                purged++
            }
        }
        return existing to purged
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
            val requested = requestedProviders
                .map { canonicalProviderId(it) }
                .toSet()
            return allProviders.filter { canonicalProviderId(it.id) in requested }
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

    private fun canonicalProviderId(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun isMmrEligible(providers: List<ContextProvider>): Boolean =
        providers.any { it.type == ContextProviderType.SEMANTIC || it.type == ContextProviderType.HYBRID }

    private fun queryProviders(
        query: String,
        scope: ContextScope,
        budget: TokenBudget,
        providers: List<ContextProvider>
    ): List<ProviderQueryResult> = runBlocking {
        providers.map { provider ->
            async(Dispatchers.IO) {
                val startTime = System.currentTimeMillis()
                try {
                    val snippets = provider.getContext(query, scope, budget)
                    val durationMs = System.currentTimeMillis() - startTime
                    log.debug("Provider {} returned {} snippets in {}ms", provider.id, snippets.size, durationMs)
                    ProviderQueryResult(
                        providerId = provider.id,
                        providerType = provider.type.name,
                        snippets = snippets,
                        durationMs = durationMs
                    )
                } catch (e: Exception) {
                    val durationMs = System.currentTimeMillis() - startTime
                    val errorMessage = e.message ?: "Unknown error"
                    log.warn("Provider {} failed: {}", provider.id, errorMessage)
                    ProviderQueryResult(
                        providerId = provider.id,
                        providerType = provider.type.name,
                        snippets = emptyList(),
                        durationMs = durationMs,
                        error = errorMessage
                    )
                }
            }
        }.awaitAll()
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

    private fun applyMmrOptimization(
        query: String,
        snippets: List<ContextSnippet>,
        budget: TokenBudget
    ): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()
        
        try {
            // Convert ContextSnippets to SearchResults
        val deduped = dedupByChunkPath(snippets)
        val searchResults = deduped.map { snippet ->
            val vector = getOrEmbedVector(snippet)
            VectorSearchEngine.ScoredChunk(
                chunk = Chunk(
                    id = snippet.chunkId,
                    fileId = snippet.metadata["file_id"]?.toLongOrNull() ?: 0L,
                        ordinal = snippet.metadata["ordinal"]?.toIntOrNull() ?: 0,
                        kind = snippet.kind,
                        startLine = snippet.offsets?.first,
                        endLine = snippet.offsets?.last,
                        tokenEstimate = estimateTokens(snippet),
                        content = snippet.text,
                        summary = snippet.label,
                        createdAt = java.time.Instant.now(),
                        chunkPath = snippet.metadata["chunk_path"],
                        parentChunkId = snippet.metadata["parent_chunk_id"]?.toLongOrNull()
                    ),
                    score = snippet.score.toFloat(),
                    embeddingId = snippet.metadata["embedding_id"]?.toLongOrNull() ?: 0L,
                    path = snippet.filePath,
                    language = snippet.language,
                    vector = vector,
                    chunkPath = snippet.metadata["chunk_path"],
                    parentChunkId = snippet.metadata["parent_chunk_id"]?.toLongOrNull()
                )
            }
            
            // Apply MMR reranking
            val optimized = queryOptimizer.optimize(query, searchResults, budget)
            
            // Convert back to ContextSnippets, preserving original metadata
            return optimized.map { result ->
                val original = snippets.find { it.chunkId == result.chunk.id }
                original?.copy(score = result.score.toDouble()) ?: ContextSnippet(
                    chunkId = result.chunk.id,
                    score = result.score.toDouble(),
                    filePath = result.path,
                    label = result.chunk.summary,
                    kind = result.chunk.kind,
                    text = result.chunk.content,
                    language = result.language,
                    offsets = result.chunk.lineSpan,
                    metadata = emptyMap()
                )
            }
        } catch (e: Exception) {
            log.warn("MMR optimization failed, returning original results: {}", e.message)
            return snippets
        }
    }

    private fun dedupByChunkPath(snippets: List<ContextSnippet>): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()
        val bestByPath = LinkedHashMap<String, ContextSnippet>()
        val passthrough = mutableListOf<ContextSnippet>()

        snippets.forEach { snippet ->
            val path = snippet.metadata["chunk_path"]
            if (path.isNullOrBlank()) {
                passthrough.add(snippet)
            } else {
                val existing = bestByPath[path]
                if (existing == null || snippet.score > existing.score) {
                    bestByPath[path] = snippet
                }
            }
        }

        return buildList {
            addAll(bestByPath.values)
            addAll(passthrough)
        }
    }
    
    private fun getOrEmbedVector(snippet: ContextSnippet): FloatArray {
        // Try to get vector from metadata (for semantic results)
        snippet.metadata["embedding_vector"]?.let { vectorStr ->
            try {
                return parseVectorString(vectorStr)
            } catch (e: Exception) {
                log.debug("Failed to parse embedding vector from metadata: {}", e.message)
            }
        }
        
        // Check cache
        val cacheKey = "${snippet.chunkId}:${snippet.text.hashCode()}"
        synchronized(embeddingCache) {
            embeddingCache[cacheKey]?.let { return it }
        }
        
        // Embed the text
        val vector = runBlocking {
            embedder.embed(snippet.text)
        }
        
        // Cache it
        synchronized(embeddingCache) {
            embeddingCache[cacheKey] = vector
        }
        
        return vector
    }
    
    private fun parseVectorString(vectorStr: String): FloatArray {
        // Handle JSON array format: "[0.1, 0.2, 0.3]"
        val cleaned = vectorStr.trim().removeSurrounding("[", "]")
        return cleaned.split(",")
            .map { it.trim().toFloat() }
            .toFloatArray()
    }
    
    private fun applyNeighborExpansion(snippets: List<ContextSnippet>): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()
        
        try {
            return neighborExpander.expand(snippets, config.query.neighborWindow)
        } catch (e: Exception) {
            log.warn("Neighbor expansion failed, returning original results: {}", e.message)
            return snippets
        }
    }
    
    private fun applyScoreBoosts(snippets: List<ContextSnippet>): List<ContextSnippet> {
        if (snippets.isEmpty()) return emptyList()
        if (config.query.boosts.pathPrefixes.isEmpty() && config.query.boosts.languages.isEmpty()) {
            return snippets
        }
        
        try {
            return scoreBooster.applyBoosts(snippets)
        } catch (e: Exception) {
            log.warn("Score boosting failed, returning original results: {}", e.message)
            return snippets
        }
    }
    
    private fun estimateTokens(snippet: ContextSnippet): Int =
        max(1, snippet.metadata["token_estimate"]?.toIntOrNull() ?: snippet.text.length / 4)

    /**
     * Validates query quality and returns warnings if likely to produce poor results.
     * Helps agents understand why their queries might fail.
     */
    private fun validateQueryQuality(query: String): List<String> {
        val warnings = mutableListOf<String>()
        val words = query.trim().split("\\s+".toRegex())

        // Check if query is too short
        if (words.size < 3) {
            warnings.add("Query is very short (${words.size} words). Semantic search works better with 5-8 words.")
        }

        // Check for question words (likely to fail)
        val questionWords = listOf("how", "why", "what", "where", "when", "who", "which", "explain", "show")
        if (words.any { it.lowercase() in questionWords }) {
            warnings.add("Query contains question words. Use declarative phrases instead (e.g., 'authentication implementation' not 'how does authentication work').")
        }

        // Check if query is just a single generic term
        val genericTerms = listOf("ftp", "file", "data", "config", "error", "test", "code", "class", "method")
        if (words.size == 1 && words[0].lowercase() in genericTerms) {
            warnings.add("Single generic term detected. Add domain context and action words (e.g., 'FTP adapter file transfer operations').")
        }

        return warnings
    }

    /**
     * Suggests a better query by expanding the original with common domain terms.
     */
    private fun suggestBetterQuery(query: String): String {
        val words = query.trim().split("\\s+".toRegex())

        // If very short, add generic expansion suggestions
        return when {
            words.size == 1 -> "$query configuration implementation usage"
            words.size == 2 -> "$query implementation configuration details"
            else -> "$query (add more specific domain terms)"
        }
    }

    private fun fetchChunksByIds(ids: List<Long>): Result {
        val chunks = ContextRepository.getChunksByIds(ids)
        if (chunks.isEmpty()) {
            return Result(
                hits = emptyList(),
                metadata = mapOf(
                    "totalHits" to 0,
                    "returnedHits" to 0,
                    "tokensUsed" to 0,
                    "tokensRequested" to 0,
                    "providers" to emptyMap<String, Any>()
                ),
                content = null
            )
        }

        val tokensUsed = chunks.sumOf { it.chunk.tokenEstimate ?: max(1, it.chunk.content.length / 4) }
        val byParent = chunks.groupBy { it.chunk.parentChunkId }

        val hits = chunks.map { chunkWithFile ->
            val chunk = chunkWithFile.chunk
            val meta = buildMap<String, String> {
                put("fileId", chunk.fileId.toString())
                put("tokens", (chunk.tokenEstimate ?: max(1, chunk.content.length / 4)).toString())
                chunkWithFile.relativePath?.let { put("relativePath", it) }
                chunk.chunkPath?.let { put("chunk_path", it) }
                chunk.parentChunkId?.let { put("parent_chunk_id", it.toString()) }
            }
            val siblings = byParent[chunk.parentChunkId].orEmpty().map { it.chunk.id }.filter { it != chunk.id }
            val pathSegments = chunk.chunkPath?.split("/")?.filter { it.isNotBlank() }
            SnippetHit(
                chunkId = chunk.id,
                score = 1.0,
                filePath = chunkWithFile.filePath,
                label = chunk.summary,
                kind = chunk.kind.name,
                text = chunk.content,
                language = chunkWithFile.language,
                startLine = chunk.startLine,
                endLine = chunk.endLine,
                chunkPath = chunk.chunkPath,
                parentChunkId = chunk.parentChunkId,
                siblingChunkIds = siblings,
                prevChunkId = null,
                nextChunkId = null,
                chunkPathSegments = pathSegments,
                metadata = meta
            )
        }

        val metadata = mapOf(
            "totalHits" to hits.size,
            "returnedHits" to hits.size,
            "tokensUsed" to tokensUsed,
            "tokensRequested" to tokensUsed,
            "providers" to emptyMap<String, Any>()
        )

        return Result(hits = hits, metadata = metadata, content = null)
    }


    companion object {
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "query_context params",
          "type": "object",
          "required": [],
          "properties": {
            "query": {"type": "string", "minLength": 0},
            "chunkIds": {"type": ["array", "null"], "items": {"type": "number"}},
            "k": {"type": ["integer", "null"], "minimum": 1},
            "maxTokens": {"type": ["integer", "null"], "minimum": 100},
            "paths": {"type": ["array", "null"], "items": {"type": "string"}},
            "languages": {"type": ["array", "null"], "items": {"type": "string"}},
            "kinds": {"type": ["array", "null"], "items": {"type": "string"}},
            "excludePatterns": {"type": ["array", "null"], "items": {"type": "string"}},
            "providers": {"type": ["array", "null"], "items": {"type": "string"}},
            "format": {"type": ["string", "null"], "enum": ["json", "markdown"]}
          },
          "additionalProperties": false
        }
        """
    }
}
