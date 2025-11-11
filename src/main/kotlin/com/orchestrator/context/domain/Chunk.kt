package com.orchestrator.context.domain

import java.time.Instant

/**
 * Represents an addressable semantic chunk extracted from a source file.
 */
data class Chunk(
    val id: Long,
    val fileId: Long,
    val ordinal: Int,
    val kind: ChunkKind,
    val startLine: Int?,
    val endLine: Int?,
    val tokenEstimate: Int?,
    val content: String,
    val summary: String?,
    val createdAt: Instant
) {
    init {
        require(ordinal >= 0) { "ordinal must be non-negative" }
        if (startLine != null && endLine != null) {
            require(startLine in 1..endLine) { "startLine must be >= 1 and <= endLine" }
        }
        require(content.isNotBlank()) { "content must not be blank" }
        tokenEstimate?.let { require(it >= 0) { "tokenEstimate must be non-negative" } }
    }

    /** Convenience span for display purposes when both bounds are present. */
    val lineSpan: IntRange?
        get() = if (startLine != null && endLine != null) startLine..endLine else null
}
