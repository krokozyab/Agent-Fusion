package com.orchestrator.domain

/**
 * Captures explicit directives inferred from a user's message.
 *
 * - All boolean flags default to false
 * - assignToAgent is optional (null when not explicitly assigned)
 * - originalText preserves the user's raw input for context
 */
data class UserDirective(
    /** The original user input text this directive was parsed from */
    val originalText: String,

    /** If true, force the orchestrator to use a consensus-based approach */
    val forceConsensus: Boolean = false,

    /** If true, prevent consensus flow and prefer a single-agent approach */
    val preventConsensus: Boolean = false,

    /** Optional explicit assignment to a particular agent (first/primary) */
    val assignToAgent: AgentId? = null,

    /** All agents mentioned in the directive (supports multi-agent workflows) */
    val assignedAgents: List<AgentId>? = null,

    /** Marks the request as urgent/emergency */
    val isEmergency: Boolean = false,

    /** Confidence score (0.0-1.0) that consensus should be forced */
    val forceConsensusConfidence: Double = 0.0,

    /** Confidence score (0.0-1.0) that consensus should be prevented */
    val preventConsensusConfidence: Double = 0.0,

    /** Confidence score (0.0-1.0) that the assignment should be honored */
    val assignmentConfidence: Double = 0.0,

    /** Confidence score (0.0-1.0) that the request is an emergency */
    val emergencyConfidence: Double = 0.0,

    /** Optional parser diagnostics for debugging/metering */
    val parsingNotes: List<String> = emptyList()
)
