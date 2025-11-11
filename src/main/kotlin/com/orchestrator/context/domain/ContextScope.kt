package com.orchestrator.context.domain

/**
 * Declarative filters applied when retrieving context snippets for a request.
 */
data class ContextScope(
    val paths: List<String> = emptyList(),
    val languages: Set<String> = emptySet(),
    val kinds: Set<ChunkKind> = emptySet(),
    val excludePatterns: Set<String> = emptySet()
) {
    init {
        require(paths.all { it.isNotBlank() }) { "paths must not contain blank entries" }
        require(languages.all { it.isNotBlank() }) { "languages must not contain blank entries" }
        require(excludePatterns.all { it.isNotBlank() }) { "excludePatterns must not contain blank entries" }
    }

    /** True when no filters are applied to restrict retrieval. */
    val isUnbounded: Boolean
        get() = paths.isEmpty() && languages.isEmpty() && kinds.isEmpty() && excludePatterns.isEmpty()
}
