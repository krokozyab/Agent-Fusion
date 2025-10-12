package com.orchestrator.context.domain

/**
 * Token allocation strategy for building context selections.
 */
data class TokenBudget(
    val maxTokens: Int,
    val reserveForPrompt: Int = 0,
    val diversityWeight: Double = 0.0
) {
    init {
        require(maxTokens > 0) { "maxTokens must be positive" }
        require(reserveForPrompt >= 0) { "reserveForPrompt must be non-negative" }
        require(reserveForPrompt <= maxTokens) { "reserveForPrompt cannot exceed maxTokens" }
        require(diversityWeight >= 0.0) { "diversityWeight must be non-negative" }
    }

    /** Remaining tokens available for snippets after reserving prompt tokens. */
    val availableForSnippets: Int
        get() = maxTokens - reserveForPrompt
}
