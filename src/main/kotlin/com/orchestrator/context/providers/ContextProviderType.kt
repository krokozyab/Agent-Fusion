package com.orchestrator.context.providers

/**
 * Categorisation for context providers. Used for weighting, fusion, and routing.
 */
enum class ContextProviderType {
    SEMANTIC,
    SYMBOL,
    FULL_TEXT,
    HYBRID,
    GIT_HISTORY
}
