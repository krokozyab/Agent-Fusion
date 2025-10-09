package com.orchestrator.modules.consensus.strategies

import com.orchestrator.domain.Proposal

/**
 * Consensus strategies evaluate a set of proposals produced by multiple agents and
 * return a structured result describing whether a consensus was reached, which
 * proposal (if any) is selected, and why.
 *
 * Contract and expectations:
 * - Implementations MUST be deterministic with the same inputs.
 * - Implementations MUST handle edge cases gracefully (e.g., empty input, ties).
 * - Implementations SHOULD provide clear human-readable reasoning in the result.
 * - Implementations SHOULD remain pure (no I/O or shared mutable state).
 * - Interface is designed to be extensible for multiple strategy types.
 */
interface ConsensusStrategy {
    /** The type/classification of this strategy for discovery and logging. */
    val type: ConsensusStrategyType

    /**
     * Evaluate a list of proposals and return a consensus decision.
     *
     * Requirements for implementations:
     * - MUST NOT throw on empty [proposals]; instead, return [ConsensusResult] with `agreed=false`.
     * - SHOULD prefer proposals with higher confidence when applicable.
     * - MUST document any assumptions about proposal content in the strategy's KDoc.
     *
     * @param proposals The candidate proposals to consider. May be empty.
     * @return A [ConsensusResult] summarizing the outcome, including reasoning.
     */
    fun evaluate(proposals: List<Proposal>): ConsensusResult
}

/**
 * Standardized result returned by all consensus strategies.
 *
 * @property agreed Whether the strategy determined that a consensus was reached.
 * @property winningProposal The selected winning proposal, or null if none selected.
 * @property reasoning Human-readable explanation describing how the result was obtained.
 * @property details Optional machine-friendly details (scores, votes, thresholds, etc.).
 */
data class ConsensusResult(
    val agreed: Boolean,
    val winningProposal: Proposal?,
    val reasoning: String,
    val details: Map<String, Any?> = emptyMap()
)

/**
 * Enumeration of built-in consensus strategy categories. New strategies can add
 * new enum entries as needed, while keeping backward compatibility.
 */
enum class ConsensusStrategyType {
    /** Majority or threshold-based voting across proposals. */
    VOTING,

    /** Selects proposal(s) based on quality of reasoning/justification. */
    REASONING_QUALITY,

    /** Uses weighted confidence/score aggregation. */
    WEIGHTED_CONFIDENCE,

    /** Statistical consensus (e.g., median/trimmed mean over numeric outputs). */
    STATISTICAL,

    /** Catch-all for custom or experimental strategies. */
    CUSTOM
}
