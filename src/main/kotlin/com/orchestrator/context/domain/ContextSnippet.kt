package com.orchestrator.context.domain

/**
 * Result element returned from a context retrieval operation.
 */
data class ContextSnippet(
    val chunkId: Long,
    val score: Double,
    val filePath: String,
    val label: String?,
    val kind: ChunkKind,
    val text: String,
    val language: String?,
    val offsets: IntRange?,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(score in 0.0..1.0) { "score must be between 0.0 and 1.0" }
        require(filePath.isNotBlank()) { "filePath must not be blank" }
        require(text.isNotBlank()) { "text must not be blank" }
        metadata.keys.forEach { require(it.isNotBlank()) { "metadata keys must not be blank" } }
        offsets?.let { range ->
            require(range.first >= 0 && range.last >= range.first) {
                "offsets must be non-negative with start <= end"
            }
        }
    }
}
