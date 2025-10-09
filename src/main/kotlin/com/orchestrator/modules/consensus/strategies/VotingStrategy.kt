package com.orchestrator.modules.consensus.strategies

import com.orchestrator.domain.Proposal
import com.orchestrator.domain.ProposalId
import java.time.Instant

/**
 * Voting-based consensus strategy.
 *
 * Rules and assumptions:
 * - Each proposal counts as one approval vote for its content (content equality determines the option).
 * - Threshold is the minimum fraction of total proposals required for consensus on a single option.
 * - Ties on top vote count yield no consensus, even if the tied ratio meets the threshold.
 * - Winner selection within the top option is deterministic: highest confidence, then earliest createdAt, then id.
 * - Empty inputs return agreed=false with clear reasoning.
 */
class VotingStrategy(
    private val threshold: Double = 0.75
) : ConsensusStrategy {

    init {
        require(threshold in 0.0..1.0) { "threshold must be within [0.0, 1.0], was $threshold" }
    }

    override val type: ConsensusStrategyType = ConsensusStrategyType.VOTING

    override fun evaluate(proposals: List<Proposal>): ConsensusResult {
        if (proposals.isEmpty()) {
            return ConsensusResult(
                agreed = false,
                winningProposal = null,
                reasoning = "No proposals provided; cannot establish consensus.",
                details = mapOf(
                    "threshold" to threshold,
                    "totalProposals" to 0
                )
            )
        }

        // Group proposals by their content (JSON-compatible), counting votes per unique content value
        val votesByContent: Map<Any?, List<Proposal>> = proposals.groupBy { it.content }
        val counts: Map<Any?, Int> = votesByContent.mapValues { it.value.size }

        val total = proposals.size
        val maxCount = counts.maxOf { it.value }
        val leaders: List<Any?> = counts.filter { it.value == maxCount }.keys.toList()

        // Calculate top ratio
        val topRatio = maxCount.toDouble() / total.toDouble()

        // If there's a tie for the highest count, no consensus
        if (leaders.size > 1) {
            return ConsensusResult(
                agreed = false,
                winningProposal = null,
                reasoning = "Tie detected between ${leaders.size} options at ${"%.2f".format(topRatio * 100)}% each; no consensus.",
                details = mapOf(
                    "threshold" to threshold,
                    "totalProposals" to total,
                    "topRatio" to topRatio,
                    "topCount" to maxCount,
                    "optionVoteCounts" to counts.mapKeys { (k, _) -> k.toDebugKey() }
                )
            )
        }

        // Unique leader
        val winningContent: Any? = leaders.first()
        val winningVotes = maxCount
        val winningRatio = topRatio

        if (winningRatio >= threshold) {
            val winningProposals = votesByContent.getValue(winningContent)
            val winner = pickDeterministicWinner(winningProposals)
            return ConsensusResult(
                agreed = true,
                winningProposal = winner,
                reasoning = "Consensus reached: option approved by ${"%.2f".format(winningRatio * 100)}% (" +
                        "$winningVotes/$total) which meets threshold ${"%.0f".format(threshold * 100)}%.",
                details = mapOf(
                    "threshold" to threshold,
                    "totalProposals" to total,
                    "winningOptionVotes" to winningVotes,
                    "winningOptionRatio" to winningRatio,
                    "optionVoteCounts" to counts.mapKeys { (k, _) -> k.toDebugKey() },
                    "winningContent" to winningContent
                )
            )
        }

        // Leader exists but doesn't meet threshold
        return ConsensusResult(
            agreed = false,
            winningProposal = null,
            reasoning = "No consensus: top option at ${"%.2f".format(winningRatio * 100)}% (" +
                    "$winningVotes/$total) is below threshold ${"%.0f".format(threshold * 100)}%.",
            details = mapOf(
                "threshold" to threshold,
                "totalProposals" to total,
                "topRatio" to winningRatio,
                "topCount" to winningVotes,
                "optionVoteCounts" to counts.mapKeys { (k, _) -> k.toDebugKey() }
            )
        )
    }

    private fun pickDeterministicWinner(candidates: List<Proposal>): Proposal {
        return candidates.sortedWith(
            compareByDescending<Proposal> { it.confidence }
                .thenBy { it.createdAt }
                .thenBy { it.id.value }
        ).first()
    }
}

private fun Any?.toDebugKey(): String = when (this) {
    null -> "<null>"
    is String -> this
    is Number, is Boolean -> this.toString()
    is List<*> -> this.joinToString(prefix = "[", postfix = "]") { it.toDebugKey() }
    is Map<*, *> -> this.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
        val ks = if (k is String) k else k.toString()
        "$ks:${v.toDebugKey()}"
    }
    else -> this.toString()
}