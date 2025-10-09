package com.orchestrator.modules.consensus

import com.orchestrator.domain.*
import com.orchestrator.modules.consensus.strategies.*
import com.orchestrator.storage.repositories.DecisionRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ConsensusModule coordinates proposal collection and strategy execution,
 * records the resulting Decision, and returns a standardized outcome.
 *
 * Capabilities:
 * - Collect proposals for a task (optionally waiting for new arrivals)
 * - Select and run strategies in order (chaining) until agreement is reached
 * - Robust error handling: strategy failures do not abort the chain
 * - Persist Decision with full context via DecisionRepository
 */
interface ConsensusService {
    fun decide(
        taskId: TaskId,
        strategyOrder: List<ConsensusStrategyType>,
        waitFor: Duration
    ): ConsensusOutcome

    fun shutdown() {}
}

data class ConsensusOutcome(
    val decisionId: DecisionId,
    val result: ConsensusResult,
    val strategyTrail: List<String>,
    val consideredCount: Int
)

object ConsensusModule {
    private val log: Logger = Logger.getLogger(ConsensusModule::class.java.name)

    /** Simple registry of available strategies. Extendable. */
    object Registry {
        fun all(): Map<ConsensusStrategyType, ConsensusStrategy> = mapOf(
            ConsensusStrategyType.VOTING to VotingStrategy(),
            ConsensusStrategyType.REASONING_QUALITY to ReasoningQualityStrategy(),
            // TokenOptimization is classified as CUSTOM in current implementation
            ConsensusStrategyType.CUSTOM to TokenOptimizationStrategy()
        )

        fun get(type: ConsensusStrategyType): ConsensusStrategy? = all()[type]
    }

    /**
     * Run consensus for a given task.
     * @param taskId Task to decide for.
     * @param strategyOrder Preferred strategy types to try in order. If empty, use registry order.
     * @param waitFor Optional wait for new proposals (added to whatever is already present).
     */
    fun decide(
        taskId: TaskId,
        strategyOrder: List<ConsensusStrategyType> = listOf(
            ConsensusStrategyType.VOTING,
            ConsensusStrategyType.REASONING_QUALITY,
            ConsensusStrategyType.CUSTOM
        ),
        waitFor: Duration = Duration.ZERO
    ): ConsensusOutcome {
        // 1) Collect proposals (allow waiting for new arrivals if requested)
        val proposals = if (waitFor.isZero || waitFor.isNegative) {
            ProposalManager.getProposals(taskId)
        } else {
            ProposalManager.waitForProposals(taskId, waitFor)
        }

        if (proposals.isEmpty()) {
            // Record a no-consensus decision with clear rationale
            val decision = buildAndPersistDecision(
                taskId = taskId,
                proposals = proposals,
                result = ConsensusResult(
                    agreed = false,
                    winningProposal = null,
                    reasoning = "No proposals available for task; cannot reach consensus.",
                    details = mapOf("totalProposals" to 0)
                ),
                strategyTrail = listOf("<none>")
            )
            return ConsensusOutcome(decision.id, decisionToConsensusResult(decision), listOf("<none>"), 0)
        }

        // 2) Determine strategies to try
        val strategies: List<ConsensusStrategy> = buildList {
            val reg = Registry.all()
            val seen = mutableSetOf<ConsensusStrategyType>()
            // Add requested order first
            for (t in strategyOrder) {
                val s = reg[t]
                if (s != null && seen.add(s.type)) add(s)
            }
            // Ensure all others remain available at the end
            for ((_, s) in reg) if (seen.add(s.type)) add(s)
        }

        // 3) Execute strategies in order with robust error handling
        val trail = mutableListOf<String>()
        var lastResult: ConsensusResult? = null
        for (strategy in strategies) {
            val label = strategy.type.name
            trail += label
            val result = try {
                strategy.evaluate(proposals)
            } catch (t: Throwable) {
                log.log(Level.WARNING, "Consensus strategy ${'$'}label failed: ${'$'}{t.message}", t)
                ConsensusResult(
                    agreed = false,
                    winningProposal = null,
                    reasoning = "Strategy ${'$'}label threw: ${'$'}{t.message}",
                    details = mapOf("exception" to (t::class.simpleName ?: "Throwable"))
                )
            }
            lastResult = result
            if (result.agreed && result.winningProposal != null) {
                // 4) Persist decision and return
                val decision = buildAndPersistDecision(taskId, proposals, result, trail)
                return ConsensusOutcome(decision.id, result, trail.toList(), proposals.size)
            }
        }

        // No strategy reached agreement; persist an unresolved decision capturing rationale from lastResult
        val finalResult = lastResult ?: ConsensusResult(
            agreed = false,
            winningProposal = null,
            reasoning = "No strategies executed.",
            details = emptyMap()
        )
        val decision = buildAndPersistDecision(taskId, proposals, finalResult, trail)
        return ConsensusOutcome(decision.id, finalResult, trail.toList(), proposals.size)
    }

    private fun buildAndPersistDecision(
        taskId: TaskId,
        proposals: List<Proposal>,
        result: ConsensusResult,
        strategyTrail: List<String>
    ): Decision {
        val considered = proposals.map { p ->
            ProposalRef(
                id = p.id,
                agentId = p.agentId,
                inputType = p.inputType,
                confidence = p.confidence,
                tokenUsage = p.tokenUsage
            )
        }
        val winner = result.winningProposal?.id
        val agreementRate = extractAgreementRate(result)
        val rationale = buildString {
            append(result.reasoning)
            if (strategyTrail.isNotEmpty()) {
                append(" | strategies=")
                append(strategyTrail.joinToString("->"))
            }
        }
        val decision = Decision(
            id = DecisionId(UUID.randomUUID().toString()),
            taskId = taskId,
            considered = considered,
            selected = winner?.let { setOf(it) } ?: emptySet(),
            winnerProposalId = winner,
            agreementRate = agreementRate,
            rationale = rationale,
            decidedAt = Instant.now(),
            metadata = mapOf("strategyTrail" to strategyTrail.joinToString(","))
        )
        DecisionRepository.insert(decision)
        return decision
    }

    /** Attempt to standardize agreement rate extraction from strategy details when available. */
    private fun extractAgreementRate(result: ConsensusResult): Double? {
        val d = result.details
        // VotingStrategy exposes "winningOptionRatio"
        val r1 = (d["winningOptionRatio"] as? Number)?.toDouble()
        if (r1 != null) return r1.coerceIn(0.0, 1.0)
        // Some strategies might report percentage under a generic key
        val pct = (d["agreementPct"] as? Number)?.toDouble()
        if (pct != null) return (pct / 100.0).coerceIn(0.0, 1.0)
        return null
    }

    private fun decisionToConsensusResult(decision: Decision): ConsensusResult {
        val winner = decision.winnerProposalId
        return ConsensusResult(
            agreed = decision.consensusAchieved,
            winningProposal = null, // Not available from Decision alone here
            reasoning = decision.rationale ?: "",
            details = mapOf(
                "agreementRate" to decision.agreementRate,
                "winnerProposalId" to winner?.value,
                "decidedAt" to decision.decidedAt.toString()
            )
        )
    }
}

class DefaultConsensusService : ConsensusService {
    override fun decide(
        taskId: TaskId,
        strategyOrder: List<ConsensusStrategyType>,
        waitFor: Duration
    ): ConsensusOutcome = ConsensusModule.decide(taskId, strategyOrder, waitFor)
}
