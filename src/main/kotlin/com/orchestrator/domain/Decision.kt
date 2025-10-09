package com.orchestrator.domain

import java.time.Instant

/** Unique identifier for a Decision */
data class DecisionId(val value: String) {
    init {
        require(value.isNotBlank()) { "DecisionId cannot be blank" }
    }
    override fun toString(): String = value
}

/**
 * Snapshot of a proposal at the moment a decision is taken. This records
 * essential context so a Decision remains self-contained even if the
 * underlying Proposal changes later.
 */
data class ProposalRef(
    val id: ProposalId,
    val agentId: AgentId,
    val inputType: InputType,
    val confidence: Double,
    val tokenUsage: TokenUsage
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be in [0.0, 1.0], was $confidence" }
    }
}

/**
 * Decision domain model.
 * - Links to the originating Task and the proposals that were considered.
 * - Records consensus results (selected proposals, optional winner, agreement rate, rationale).
 * - Provides token-savings calculations versus processing all considered proposals.
 */
data class Decision(
    val id: DecisionId,
    val taskId: TaskId,

    /** Proposals that participated in this decision (snapshotted for context) */
    val considered: List<ProposalRef>,

    /** The set of proposals selected by the consensus process (subset of considered) */
    val selected: Set<ProposalId> = emptySet(),

    /** Optional single winner among selected proposals */
    val winnerProposalId: ProposalId? = null,

    /** Agreement rate in [0.0, 1.0] representing degree of consensus */
    val agreementRate: Double? = null,

    /** Free-form rationale for auditing/traceability */
    val rationale: String? = null,

    /** Timestamp when the decision was made */
    val decidedAt: Instant = Instant.now(),

    /** Arbitrary metadata for extensibility */
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        // Validate considered list doesn't contain duplicates
        val consideredIds = considered.map { it.id }.toSet()
        require(consideredIds.size == considered.size) { "considered proposals must be unique" }

        // Validate selected is a subset of considered
        require(selected.all { it in consideredIds }) { "selected proposals must be a subset of considered proposals" }

        // Validate winner (if present) is among considered and, if selected set is non-empty, also among selected
        if (winnerProposalId != null) {
            require(winnerProposalId in consideredIds) { "winnerProposalId must be in considered proposals" }
            if (selected.isNotEmpty()) require(winnerProposalId in selected) { "winnerProposalId must be in selected proposals when 'selected' is non-empty" }
        }

        // Validate agreement rate bounds
        if (agreementRate != null) require(agreementRate in 0.0..1.0) { "agreementRate must be in [0.0, 1.0], was $agreementRate" }

        // Basic metadata hygiene
        metadata.keys.forEach { key -> require(key.isNotBlank()) { "metadata keys cannot be blank" } }
    }

    /** True if a consensus outcome was reached (at least one selection) */
    val consensusAchieved: Boolean get() = selected.isNotEmpty() || winnerProposalId != null

    /** Total tokens across all considered proposals */
    val totalTokensConsidered: Int get() = considered.sumOf { it.tokenUsage.totalTokens }

    /** Total tokens across the proposals that were selected by the decision */
    val totalTokensSelected: Int get() = considered.filter { it.id in selectedOrWinnerIds }.sumOf { it.tokenUsage.totalTokens }

    /** Absolute token savings versus processing every considered proposal */
    val tokenSavingsAbsolute: Int get() = (totalTokensConsidered - totalTokensSelected).coerceAtLeast(0)

    /** Percentage token savings in [0.0, 1.0] relative to considered tokens */
    val tokenSavingsPercent: Double get() =
        if (totalTokensConsidered <= 0) 0.0 else tokenSavingsAbsolute.toDouble() / totalTokensConsidered.toDouble()

    private val selectedOrWinnerIds: Set<ProposalId>
        get() = if (selected.isNotEmpty()) selected else winnerProposalId?.let { setOf(it) } ?: emptySet()
}
