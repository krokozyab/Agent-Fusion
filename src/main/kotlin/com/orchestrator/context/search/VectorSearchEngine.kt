package com.orchestrator.context.search

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.embedding.VectorOps
import com.orchestrator.context.storage.EmbeddingRepository
import kotlin.math.abs

class VectorSearchEngine(
    private val repository: EmbeddingRepository = EmbeddingRepository
) {

    companion object {
        // Minimum cosine similarity score threshold (0.0-1.0) for result inclusion
        // Default 0.3 filters out low-relevance matches while preserving valid results
        // Can be adjusted per-query via minScore parameter
        const val DEFAULT_MIN_SCORE = 0.3f
    }

    data class Filters(
        val languages: Set<String> = emptySet(),
        val kinds: Set<ChunkKind> = emptySet(),
        val paths: Set<String> = emptySet()
    ) {
        private val normalizedLanguages = languages.map { it.lowercase() }.toSet()

        fun matches(language: String?, kind: ChunkKind, path: String): Boolean {
            if (normalizedLanguages.isNotEmpty()) {
                val candidate = language?.lowercase() ?: return false
                if (candidate !in normalizedLanguages) return false
            }
            if (kinds.isNotEmpty() && kind !in kinds) return false
            if (paths.isNotEmpty() && path !in paths) return false
            return true
        }

        companion object {
            val NONE = Filters()
        }
    }

    fun search(
        queryVector: FloatArray,
        k: Int,
        filters: Filters = Filters.NONE,
        model: String? = null,
        minScore: Float = DEFAULT_MIN_SCORE
    ): List<SearchResult> {
        require(k > 0) { "k must be positive" }
        require(minScore in 0f..1f) { "minScore must be between 0.0 and 1.0" }
        if (queryVector.isEmpty()) return emptyList()

        val normalizedQuery = VectorOps.normalize(queryVector)
        if (normalizedQuery.all { it == 0f }) return emptyList()

        val rows = repository.fetchAllWithMetadata(model)

        val scored = buildList<ScoredChunk> {
            for (row in rows) {
                if (row.embedding.dimensions != normalizedQuery.size) continue
                if (!filters.matches(row.language, row.chunk.kind, row.relativePath)) continue  // Note: relativePath field in EmbeddingRepository.EmbeddingWithMetadata now contains abs_path

                val candidateVector = row.embedding.vector.toFloatArray()
                val normalizedCandidate = when {
                    candidateVector.isEmpty() -> continue
                    candidateVector.isUnitLength() -> candidateVector
                    else -> VectorOps.normalize(candidateVector)
                }
                if (normalizedCandidate.all { it == 0f }) continue

                val score = VectorOps.dotProduct(normalizedQuery, normalizedCandidate)
                if (!score.isNaN()) {
                    // Boost document language scores to match code relevance
                    // Documents (CVs, PDFs, etc) should be equally visible in search results
                    val adjustedScore = if (row.language?.lowercase() == "document") {
                        (score * 1.35f).coerceAtMost(0.99f)  // Boost by 35% but cap at 0.99
                    } else {
                        score
                    }

                    // Filter by minimum score threshold: reduces result set size and memory usage
                    // for large indexes, prevents low-relevance matches from being returned
                    if (adjustedScore >= minScore) {
                        add(
                            ScoredChunk(
                                chunk = row.chunk,
                                path = row.relativePath,
                                language = row.language,
                                score = adjustedScore,
                                embeddingId = row.embedding.id,
                                vector = normalizedCandidate
                            )
                        )
                    }
                }
            }
        }

        if (scored.isEmpty()) return emptyList()

        return scored.sortedByDescending { it.score }
            .take(k)
    }

    private fun FloatArray.isUnitLength(): Boolean {
        var sum = 0.0
        for (value in this) {
            sum += value * value
        }
        return abs(sum - 1.0) < 1e-4
    }

    data class ScoredChunk(
        val chunk: Chunk,
        val score: Float,
        val embeddingId: Long,
        val path: String = "(unknown)",
        val language: String? = null,
        val vector: FloatArray = floatArrayOf(score)
    )
}
