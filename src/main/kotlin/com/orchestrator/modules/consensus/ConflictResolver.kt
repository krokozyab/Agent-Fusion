package com.orchestrator.modules.consensus

import com.orchestrator.domain.*
import com.orchestrator.modules.consensus.strategies.ConsensusResult
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * ConflictResolver handles consensus conflicts and no-consensus situations.
 *
 * Responsibilities (TASK-029):
 * - Detect no-consensus situations
 * - Request proposal refinements
 * - Escalation-to-human logic
 * - Tiebreaker mechanisms
 * - Log/return a full resolution path (audit trail)
 *
 * This class is self-contained and does not persist anything on its own. It returns a
 * [ConflictResolutionResult] with an audit trail that can be recorded into the decision
 * metadata/rationale by higher-level modules (e.g., a ConsensusModule).
 */
class ConflictResolver(
    private val refiner: ProposalRefiner? = null,
    private val escalationHandler: HumanEscalationHandler? = null,
    private val options: ConflictResolutionOptions = ConflictResolutionOptions()
) {
    private val logger = Logger.getLogger(ConflictResolver::class.qualifiedName)

    /**
     * Attempt to resolve conflicts given an initial strategy result. If the initial result
     * already indicates agreement, we simply return a resolved outcome. Otherwise we try
     * refinements, then deterministic tiebreakers, and finally escalate to human if enabled.
     */
    fun resolve(
        taskId: TaskId,
        proposals: List<Proposal>,
        initial: ConsensusResult
    ): ConflictResolutionResult {
        val trail = mutableListOf<String>()
        val startTs = Instant.now()

        trail += "start: received ${proposals.size} proposals, initial.agreed=${initial.agreed}"
        if (initial.reasoning.isNotBlank()) trail += "initial.reasoning=${initial.reasoning}"

        // If initial consensus is achieved, return immediately
        if (initial.agreed && initial.winningProposal != null) {
            val res = ConflictResolutionResult(
                status = ResolutionStatus.RESOLVED,
                selected = initial.winningProposal.id,
                escalated = false,
                refinementsRequested = 0,
                tiebreakerUsed = null,
                rationale = initial.reasoning.ifBlank { "Initial consensus achieved." },
                path = trail,
                startedAt = startTs,
                finishedAt = Instant.now()
            )
            logTrail(taskId, res)
            return res
        }

        // Try refinement rounds when no consensus or tie
        var currentProposals = proposals
        var refinements = 0
        if (refiner != null && options.maxRefinementRounds > 0) {
            while (refinements < options.maxRefinementRounds) {
                refinements++
                trail += "refinement[$refinements]: requesting proposal improvements"
                currentProposals = safeRefine(taskId, currentProposals, trail)
                // Optional: simple heuristic — if a single highest-confidence stands out, select it now
                val maybe = pickByConfidenceTieAware(currentProposals)
                if (maybe != null) {
                    trail += "refinement[$refinements]: unique highest-confidence found=${maybe.id}"
                    val res = ConflictResolutionResult(
                        status = ResolutionStatus.RESOLVED,
                        selected = maybe.id,
                        escalated = false,
                        refinementsRequested = refinements,
                        tiebreakerUsed = "HIGHEST_CONFIDENCE",
                        rationale = "Resolved after refinement by selecting uniquely highest-confidence proposal.",
                        path = trail,
                        startedAt = startTs,
                        finishedAt = Instant.now()
                    )
                    logTrail(taskId, res)
                    return res
                }
            }
        }

        // Apply ordered tiebreakers
        for (tb in options.tiebreakers) {
            when (tb) {
                Tiebreaker.HIGHEST_CONFIDENCE -> {
                    val winner = pickByConfidenceTieAware(currentProposals)
                    if (winner != null) {
                        trail += "tiebreaker: HIGHEST_CONFIDENCE picked=${winner.id}"
                        val res = ConflictResolutionResult(
                            status = ResolutionStatus.RESOLVED,
                            selected = winner.id,
                            escalated = false,
                            refinementsRequested = refinements,
                            tiebreakerUsed = "HIGHEST_CONFIDENCE",
                            rationale = "Selected proposal with highest confidence.",
                            path = trail,
                            startedAt = startTs,
                            finishedAt = Instant.now()
                        )
                        logTrail(taskId, res)
                        return res
                    } else {
                        trail += "tiebreaker: HIGHEST_CONFIDENCE inconclusive (tie)"
                    }
                }
                Tiebreaker.LOWEST_TOKEN_USAGE -> {
                    val winner = pickByLowestTokensTieAware(currentProposals)
                    if (winner != null) {
                        trail += "tiebreaker: LOWEST_TOKEN_USAGE picked=${winner.id}"
                        val res = ConflictResolutionResult(
                            status = ResolutionStatus.RESOLVED,
                            selected = winner.id,
                            escalated = false,
                            refinementsRequested = refinements,
                            tiebreakerUsed = "LOWEST_TOKEN_USAGE",
                            rationale = "Selected proposal with lowest token usage.",
                            path = trail,
                            startedAt = startTs,
                            finishedAt = Instant.now()
                        )
                        logTrail(taskId, res)
                        return res
                    } else {
                        trail += "tiebreaker: LOWEST_TOKEN_USAGE inconclusive (tie)"
                    }
                }
                Tiebreaker.DETERMINISTIC -> {
                    val winner = pickDeterministic(currentProposals)
                    if (winner != null) {
                        trail += "tiebreaker: DETERMINISTIC picked=${winner.id}"
                        val res = ConflictResolutionResult(
                            status = ResolutionStatus.RESOLVED,
                            selected = winner.id,
                            escalated = false,
                            refinementsRequested = refinements,
                            tiebreakerUsed = "DETERMINISTIC",
                            rationale = "Applied deterministic fallback ordering.",
                            path = trail,
                            startedAt = startTs,
                            finishedAt = Instant.now()
                        )
                        logTrail(taskId, res)
                        return res
                    } else {
                        trail += "tiebreaker: DETERMINISTIC inconclusive (no proposals)"
                    }
                }
            }
        }

        // Escalation
        if (options.enableEscalation) {
            trail += "escalation: automated resolution failed, escalating to human"
            val ticketId = safeEscalate(taskId, currentProposals, trail)
            val res = ConflictResolutionResult(
                status = ResolutionStatus.ESCALATED,
                selected = null,
                escalated = true,
                refinementsRequested = refinements,
                tiebreakerUsed = null,
                rationale = "Automated resolution failed. Escalated for human review.",
                path = trail + listOf("escalation.ticketId=${ticketId ?: "n/a"}"),
                escalationTicketId = ticketId,
                startedAt = startTs,
                finishedAt = Instant.now()
            )
            logTrail(taskId, res)
            return res
        }

        // No resolution and escalation disabled
        trail += "result: unresolved and escalation disabled"
        val res = ConflictResolutionResult(
            status = ResolutionStatus.UNRESOLVED,
            selected = null,
            escalated = false,
            refinementsRequested = refinements,
            tiebreakerUsed = null,
            rationale = "Automated resolution failed and escalation disabled.",
            path = trail,
            startedAt = startTs,
            finishedAt = Instant.now()
        )
        logTrail(taskId, res)
        return res
    }

    private fun safeRefine(taskId: TaskId, proposals: List<Proposal>, trail: MutableList<String>): List<Proposal> {
        return try {
            refiner?.refine(taskId, proposals)?.also { refined ->
                trail += "refinement: received ${refined.size} proposals"
            } ?: proposals
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Refinement failed for task ${taskId.value}: ${e.message}", e)
            trail += "refinement: error=${e.javaClass.simpleName}:${e.message}"
            proposals
        }
    }

    private fun safeEscalate(taskId: TaskId, proposals: List<Proposal>, trail: MutableList<String>): String? {
        return try {
            val id = escalationHandler?.escalate(taskId, proposals)
            if (id != null) trail += "escalation: ticketId=$id" else trail += "escalation: handler returned null"
            id
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Escalation failed for task ${taskId.value}: ${e.message}", e)
            trail += "escalation: error=${e.javaClass.simpleName}:${e.message}"
            null
        }
    }

    private fun pickByConfidenceTieAware(proposals: List<Proposal>): Proposal? {
        if (proposals.isEmpty()) return null
        val max = proposals.maxBy { it.confidence }.confidence
        val top = proposals.filter { it.confidence == max }
        return if (top.size == 1) top.first() else null
    }

    private fun pickByLowestTokensTieAware(proposals: List<Proposal>): Proposal? {
        if (proposals.isEmpty()) return null
        val min = proposals.minBy { it.tokenUsage.totalTokens }.tokenUsage.totalTokens
        val best = proposals.filter { it.tokenUsage.totalTokens == min }
        return if (best.size == 1) best.first() else null
    }

    private fun pickDeterministic(proposals: List<Proposal>): Proposal? {
        if (proposals.isEmpty()) return null
        // Deterministic ordering: by confidence desc, then by total tokens asc, then by UUID-lexicographic of proposal id
        return proposals
            .sortedWith(compareByDescending<Proposal> { it.confidence }
                .thenBy { it.tokenUsage.totalTokens }
                .thenBy { it.id.value })
            .firstOrNull()
    }

    private fun logTrail(taskId: TaskId, result: ConflictResolutionResult) {
        val level = when (result.status) {
            ResolutionStatus.RESOLVED -> Level.INFO
            ResolutionStatus.ESCALATED -> Level.WARNING
            ResolutionStatus.UNRESOLVED -> Level.WARNING
        }
        val msg = buildString {
            append("[ConflictResolver] task=")
            append(taskId.value)
            append(" status=")
            append(result.status)
            append(" selected=")
            append(result.selected?.value ?: "none")
            append(" escalated=")
            append(result.escalated)
            append(" refinements=")
            append(result.refinementsRequested)
            result.tiebreakerUsed?.let { append(" tiebreaker=").append(it) }
            append(" path=")
            append(result.path.joinToString(" | "))
        }
        logger.log(level, msg)
    }
}

/** Configuration for conflict resolution behaviour. */
data class ConflictResolutionOptions(
    val maxRefinementRounds: Int = 1,
    val enableEscalation: Boolean = true,
    val tiebreakers: List<Tiebreaker> = listOf(
        Tiebreaker.HIGHEST_CONFIDENCE,
        Tiebreaker.LOWEST_TOKEN_USAGE,
        Tiebreaker.DETERMINISTIC
    )
) {
    init {
        require(maxRefinementRounds >= 0) { "maxRefinementRounds must be >= 0" }
    }
}

/** Tiebreaker strategies applied in this order when no consensus is reached. */
enum class Tiebreaker { HIGHEST_CONFIDENCE, LOWEST_TOKEN_USAGE, DETERMINISTIC }

/** Result of conflict resolution including an audit trail. */
data class ConflictResolutionResult(
    val status: ResolutionStatus,
    val selected: ProposalId?,
    val escalated: Boolean,
    val refinementsRequested: Int,
    val tiebreakerUsed: String?,
    val rationale: String,
    val path: List<String>,
    val escalationTicketId: String? = null,
    val startedAt: Instant,
    val finishedAt: Instant,
) {
    val durationMs: Long get() = java.time.Duration.between(startedAt, finishedAt).toMillis()
}

/** Final status classification produced by the resolver. */
enum class ResolutionStatus { RESOLVED, ESCALATED, UNRESOLVED }

/**
 * A refinement hook to allow callers to improve proposals when no consensus is reached.
 * Implementations may call agents to revise content or add metadata. Implementations
 * must be resilient and return a proposal list (possibly unchanged).
 */
fun interface ProposalRefiner {
    fun refine(taskId: TaskId, proposals: List<Proposal>): List<Proposal>
}

/**
 * Escalation hook used to hand off unresolved conflicts to a human operator or system.
 * Implementations may create a ticket and return its ID, or return null if unavailable.
 */
fun interface HumanEscalationHandler {
    fun escalate(taskId: TaskId, proposals: List<Proposal>): String?
}
