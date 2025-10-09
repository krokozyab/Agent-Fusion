package com.orchestrator.domain

/**
 * High-level capabilities an Agent can possess.
 */
enum class Capability {
    CODE_GENERATION,
    CODE_REVIEW,
    TEST_WRITING,
    REFACTORING,
    ARCHITECTURE,
    DOCUMENTATION,
    DATA_ANALYSIS,
    PLANNING,
    DEBUGGING
}

/**
 * Represents the strength of an Agent for a given capability.
 *
 * Score is an integer representing proficiency in the 0..100 range.
 */
data class Strength(
    val capability: Capability,
    val score: Int
) {
    init {
        require(score in 0..100) { "Strength score must be between 0 and 100 inclusive, was $score" }
    }
}
