package com.orchestrator.domain

import java.time.Instant

/** Unique identifier for a Proposal */
data class ProposalId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProposalId cannot be blank" }
    }
    override fun toString(): String = value
}

/** Classifies the type of input/content carried by a proposal. */
enum class InputType {
    ARCHITECTURAL_PLAN,
    CODE_REVIEW,
    IMPLEMENTATION_PLAN,
    TEST_PLAN,
    REFACTORING_SUGGESTION,
    RESEARCH_SUMMARY,
    OTHER
}

/** Tracks token usage/cost associated with generating a proposal. */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0
) {
    init {
        require(inputTokens >= 0) { "inputTokens cannot be negative" }
        require(outputTokens >= 0) { "outputTokens cannot be negative" }
    }

    val totalTokens: Int get() = inputTokens + outputTokens
}

/**
 * Proposal domain model. Links an Agent's suggestion to a Task.
 * - content: flexible JSON-compatible structure (null, String, Number, Boolean, List, Map<String, *>)
 * - confidence: value between 0.0 and 1.0 inclusive
 */
data class Proposal(
    val id: ProposalId,
    val taskId: TaskId,
    val agentId: AgentId,
    val inputType: InputType,

    /** JSON-compatible arbitrary content representing the proposal payload */
    val content: Any?,

    /** Confidence score of the proposal in range [0.0, 1.0] */
    val confidence: Double,

    /** Token usage incurred to produce this proposal */
    val tokenUsage: TokenUsage = TokenUsage(),

    /** Creation timestamp */
    val createdAt: Instant = Instant.now(),

    /** Optional metadata for extensibility */
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in [0.0, 1.0], was $confidence" }
        require(isJsonCompatible(content)) { "content must be JSON-compatible (null, String, Number, Boolean, List, Map<String, *>), was ${content?.javaClass?.name}" }
        // Optionally validate metadata keys/values are non-blank strings
        metadata.keys.forEach { key -> require(key.isNotBlank()) { "metadata keys cannot be blank" } }
    }
}

private fun isJsonCompatible(value: Any?): Boolean {
    return when (value) {
        null -> true
        is String -> true
        is Number -> true
        is Boolean -> true
        is List<*> -> value.all { isJsonCompatible(it) }
        is Map<*, *> -> value.keys.all { it is String } && value.values.all { isJsonCompatible(it) }
        else -> false
    }
}
