package com.orchestrator.context.search

import com.orchestrator.context.ContextRepository
import com.orchestrator.context.config.GraphConfig
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.utils.Logger
import kotlin.math.pow

/**
 * Expands search results by following cross-chunk links in the links table.
 * When a chunk scores highly, its linked neighbors (CALLS, DEPENDS_ON, MODIFIES)
 * get pulled into results with propagated scores.
 */
class LinkExpander(
    private val config: GraphConfig = GraphConfig()
) {
    private val log = Logger.logger(this::class.qualifiedName!!)

    /**
     * Expands snippets by adding graph-linked chunks with propagated scores.
     *
     * @param snippets Original search results from providers
     * @return Original snippets + linked chunks (deduped), or originals unchanged if disabled
     */
    fun expand(snippets: List<ContextSnippet>): List<ContextSnippet> {
        if (!config.enabled || snippets.isEmpty()) return snippets

        return try {
            doExpand(snippets)
        } catch (t: Throwable) {
            log.warn("Graph link expansion failed, returning originals: {}", t.message)
            snippets
        }
    }

    private fun doExpand(snippets: List<ContextSnippet>): List<ContextSnippet> {
        val chunkIds = snippets.map { it.chunkId }
        val scoreMap = snippets.associate { it.chunkId to it.score }
        val existingIds = chunkIds.toHashSet()

        // Fetch linked chunks from the repository
        val linkedChunks = if (config.maxDepth == 1) {
            ContextRepository.getLinkedChunkIds(chunkIds, config.defaultLinkScore)
        } else {
            ContextRepository.traverseGraph(chunkIds, config.maxDepth, config.defaultLinkScore, config.maxGraphResults * 3)
        }

        // Filter out chunks already in results
        val newLinks = linkedChunks.filter { it.chunkId !in existingIds }
        if (newLinks.isEmpty()) return snippets

        // Calculate propagated scores
        data class ScoredLink(
            val chunkId: Long,
            val sourceChunkId: Long,
            val linkType: String,
            val propagatedScore: Double,
            val depth: Int
        )

        val scoredLinks = newLinks.mapNotNull { link ->
            val sourceScore = scoreMap[link.sourceChunkId] ?: return@mapNotNull null
            val propagated = (sourceScore * link.linkScore * config.decayFactor.pow(link.depth))
                .coerceIn(0.0, 1.0)
            if (propagated < config.minPropagatedScore) return@mapNotNull null
            ScoredLink(link.chunkId, link.sourceChunkId, link.linkType, propagated, link.depth)
        }
            .sortedByDescending { it.propagatedScore }
            .take(config.maxGraphResults)

        if (scoredLinks.isEmpty()) return snippets

        // Fetch full chunk data
        val chunkData = ContextRepository.getChunksByIds(scoredLinks.map { it.chunkId })
        val chunkMap = chunkData.associateBy { it.chunk.id }

        // Convert to ContextSnippets
        val propagated = scoredLinks.mapNotNull { scored ->
            val data = chunkMap[scored.chunkId] ?: return@mapNotNull null
            val chunk = data.chunk
            ContextSnippet(
                chunkId = chunk.id,
                score = scored.propagatedScore,
                filePath = data.filePath,
                label = chunk.summary,
                kind = chunk.kind,
                text = chunk.content,
                language = data.language,
                offsets = chunk.lineSpan,
                chunkPath = chunk.chunkPath,
                parentChunkId = chunk.parentChunkId,
                metadata = mapOf(
                    "provider" to "graph",
                    "graph_source" to scored.sourceChunkId.toString(),
                    "graph_depth" to scored.depth.toString(),
                    "graph_link_type" to scored.linkType,
                    "token_estimate" to (chunk.tokenEstimate ?: (chunk.content.length / 4)).toString()
                )
            )
        }

        log.debug("Graph expansion: {} originals + {} propagated", snippets.size, propagated.size)
        return snippets + propagated
    }
}
