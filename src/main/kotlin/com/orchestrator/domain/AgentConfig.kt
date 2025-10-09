package com.orchestrator.domain

/**
 * Configuration for an Agent's runtime/model settings.
 */
data class AgentConfig(
    /** Optional human-friendly name for this agent instance */
    val name: String? = null,

    /** The model identifier or variant to use (e.g., "claude-3.5-sonnet", "gpt-4o") */
    val model: String? = null,

    /** Optional API key reference or secret name (never store secrets directly) */
    val apiKeyRef: String? = null,

    /** Optional organization/workspace/account identifier */
    val organization: String? = null,

    /** Sampling temperature where applicable (0.0..2.0 typical) */
    val temperature: Double? = null,

    /** Maximum tokens for generations where applicable */
    val maxTokens: Int? = null,

    /** Arbitrary extra configuration options */
    val extra: Map<String, String> = emptyMap()
) {
    init {
        if (temperature != null) require(temperature >= 0.0) { "temperature must be >= 0.0" }
        if (maxTokens != null) require(maxTokens > 0) { "maxTokens must be > 0" }
    }
}
