package com.orchestrator.context.search

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.embedding.VectorOps
import com.orchestrator.context.storage.EmbeddingRepository
import kotlin.math.abs

class VectorSearchEngine(
    private val repository: EmbeddingRepository = EmbeddingRepository
) {

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

    data class ScoredChunk(
        val chunk: Chunk,
        val path: String,
        val language: String?,
        val score: Float,
        val embeddingId: Long,
        val vector: FloatArray
    )

    fun search(
        queryVector: FloatArray,
        k: Int,
        filters: Filters = Filters.NONE,
        model: String? = null
    ): List<ScoredChunk> {
        require(k > 0) { "k must be positive" }
        if (queryVector.isEmpty()) return emptyList()

        val normalizedQuery = VectorOps.normalize(queryVector)
        if (normalizedQuery.all { it == 0f }) return emptyList()

        val rows = repository.fetchAllWithMetadata(model)

        val scored = buildList<ScoredChunk> {
            for (row in rows) {
                if (row.embedding.dimensions != normalizedQuery.size) continue
                if (!filters.matches(row.language, row.chunk.kind, row.relativePath)) continue

                val candidateVector = row.embedding.vector.toFloatArray()
                val normalizedCandidate = when {
                    candidateVector.isEmpty() -> continue
                    candidateVector.isUnitLength() -> candidateVector
                    else -> VectorOps.normalize(candidateVector)
                }
                if (normalizedCandidate.all { it == 0f }) continue

                val score = VectorOps.dotProduct(normalizedQuery, normalizedCandidate)
                if (!score.isNaN()) {
                    add(
                        ScoredChunk(
                            chunk = row.chunk,
                            path = row.relativePath,
                            language = row.language,
                            score = score,
                            embeddingId = row.embedding.id,
                            vector = normalizedCandidate
                        )
                    )
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
}
