package com.orchestrator.context.domain

import java.time.Instant

/**
 * Embedding vector tied to a chunk for semantic search operations.
 */
data class Embedding(
    val id: Long,
    val chunkId: Long,
    val model: String,
    val dimensions: Int,
    val vector: List<Float>,
    val createdAt: Instant
) {
    init {
        require(dimensions > 0) { "dimensions must be positive" }
        require(vector.size == dimensions) {
            "vector size ${'$'}{vector.size} must match dimensions ${'$'}dimensions"
        }
    }
}
