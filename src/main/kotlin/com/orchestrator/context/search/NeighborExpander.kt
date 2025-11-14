package com.orchestrator.context.search

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.utils.Logger

/**
 * Expands search results by including neighboring chunks for better context.
 */
class NeighborExpander {
    private val log = Logger.logger(this::class.qualifiedName!!)

    /**
     * Expands snippets by adding neighboring chunks within the specified window.
     * 
     * @param snippets Original search results
     * @param window Number of neighbors to fetch before and after each chunk (e.g., 1 = ±1 neighbor)
     * @return Expanded list with neighbors included, deduplicated and sorted
     */
    fun expand(snippets: List<ContextSnippet>, window: Int): List<ContextSnippet> {
        if (window <= 0 || snippets.isEmpty()) return snippets

        val expanded = mutableMapOf<Long, ContextSnippet>()
        
        // Add original snippets
        snippets.forEach { expanded[it.chunkId] = it }

        // Fetch neighbors for each snippet
        snippets.forEach { snippet ->
            try {
                val neighbors = fetchNeighbors(snippet, window)
                neighbors.forEach { neighbor ->
                    // Only add if not already present (preserve original scores)
                    if (!expanded.containsKey(neighbor.chunkId)) {
                        expanded[neighbor.chunkId] = neighbor
                    }
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch neighbors for chunk {}: {}", snippet.chunkId, e.message)
            }
        }

        // Sort by file path, then by ordinal (to maintain document order)
        return expanded.values.sortedWith(
            compareBy<ContextSnippet> { it.filePath }
                .thenBy { it.metadata["ordinal"]?.toIntOrNull() ?: 0 }
        )
    }

    private fun fetchNeighbors(snippet: ContextSnippet, window: Int): List<ContextSnippet> {
        val fileId = snippet.metadata["file_id"]?.toLongOrNull() ?: return emptyList()
        val ordinal = snippet.metadata["ordinal"]?.toIntOrNull() ?: return emptyList()

        // Fetch all chunks for the file
        val allChunks = ChunkRepository.findByFileId(fileId)
        if (allChunks.isEmpty()) return emptyList()

        // Find neighbors within window
        val neighbors = mutableListOf<Chunk>()
        for (chunk in allChunks) {
            val distance = kotlin.math.abs(chunk.ordinal - ordinal)
            if (distance in 1..window) {
                neighbors.add(chunk)
            }
        }

        // Convert to ContextSnippets with reduced score (neighbors are less relevant)
        return neighbors.map { chunk ->
            ContextSnippet(
                chunkId = chunk.id,
                score = snippet.score * 0.5, // Neighbors get half the score
                filePath = snippet.filePath,
                label = chunk.summary,
                kind = chunk.kind,
                text = chunk.content,
                language = snippet.language,
                offsets = chunk.lineSpan,
                metadata = mapOf(
                    "file_id" to fileId.toString(),
                    "ordinal" to chunk.ordinal.toString(),
                    "token_estimate" to (chunk.tokenEstimate ?: (chunk.content.length / 4)).toString(),
                    "neighbor_of" to snippet.chunkId.toString()
                )
            )
        }
    }
}
