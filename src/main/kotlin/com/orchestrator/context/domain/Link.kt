package com.orchestrator.context.domain

import java.time.Instant

/**
 * Cross-file relationship produced during indexing to help explain context relevance.
 */
data class Link(
    val id: Long,
    val sourceChunkId: Long,
    val targetFileId: Long,
    val targetChunkId: Long?,
    val type: String,
    val label: String?,
    val score: Double?,
    val createdAt: Instant
) {
    init {
        require(type.isNotBlank()) { "type must not be blank" }
        score?.let { require(it >= 0.0) { "score must be non-negative" } }
    }
}
