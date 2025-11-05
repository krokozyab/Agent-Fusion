package com.orchestrator.modules.routing

import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.MetricsRepository
import java.time.Duration
import java.time.Instant

/**
 * Selects agents for a given task using:
 * - Capability matching (based on TaskType -> Capability mapping)
 * - Strength-based scoring (0..100 per capability)
 * - Availability checking (ONLINE preferred; OFFLINE excluded; BUSY penalized)
 * - Simple load balancing using recent assignment counts from MetricsRepository
 * - User directive override (explicit assignment wins when available)
 *
 * Selection reasoning is printed to stdout for now to satisfy acceptance criteria.
 */
class AgentSelector(
    private val registry: AgentRegistry,
    private val metrics: MetricsRepository = MetricsRepository
) {

    companion object {
        private const val MIN_ASSIGNMENT_CONFIDENCE = 0.35
    }

    fun selectAgentForTask(task: Task, directive: UserDirective? = null): Agent? {
        log("Selecting agent for task ${task.id}. Directive assignToAgent: ${directive?.assignToAgent?.value}, taskAssignees: ${task.assigneeIds.map { it.value }}")

        // 1) User directive override
        directive?.assignToAgent?.let { forcedId ->
            val confidence = directive.assignmentConfidence
            if (confidence >= MIN_ASSIGNMENT_CONFIDENCE) {
                val agent = registry.getAgent(forcedId)
                if (agent != null && agent.status != AgentStatus.OFFLINE) {
                    log("User directive assigned task ${task.id} to ${agent.displayName} (${agent.id}) conf=${formatConfidence(confidence)}")
                    return agent
                } else {
                    log("User directive requested ${forcedId} (conf=${formatConfidence(confidence)}), but agent not found or offline; falling back to auto selection")
                }
            } else {
                log("User directive suggested ${forcedId} with confidence=${formatConfidence(confidence)} < ${formatConfidence(MIN_ASSIGNMENT_CONFIDENCE)}; ignoring override")
            }
        }
        // 2) Task-level assignee hint
        task.assigneeIds.firstOrNull()?.let { preferred ->
            val a = registry.getAgent(preferred)
            if (a != null && a.status != AgentStatus.OFFLINE) {
                log("Task ${task.id} has assignee hint -> selecting ${a.displayName}")
                return a
            }
        }

        val requiredCaps = capabilitiesFor(task.type)
        val candidates = registry.getAllAgents()
            .filter { it.status != AgentStatus.OFFLINE }
            .filter { agent -> requiredCaps.all { it in agent.capabilities } }

        log("Found ${candidates.size} candidates for task ${task.id} with caps=$requiredCaps: ${candidates.map { "${it.id.value}(${it.displayName})" }}")

        if (candidates.isEmpty()) {
            log("No available agents found for task ${task.id} requiring $requiredCaps")
            return null
        }

        val scored = candidates.map { agent ->
            val strengthScore = strengthScore(agent, requiredCaps)
            val availabilityPenalty = when (agent.status) {
                AgentStatus.ONLINE -> 0.0
                AgentStatus.BUSY -> 10.0
                AgentStatus.OFFLINE -> 1000.0 // filtered above
            }
            val recentLoad = recentAssignments(agent.id)
            // Lower load is better; convert to penalty
            val loadPenalty = recentLoad.toDouble()
            val base = strengthScore - availabilityPenalty - loadPenalty
            ScoredAgent(agent, base, strengthScore, availabilityPenalty, loadPenalty)
        }

        // Log all scores for debugging
        scored.forEach {
            log("  Agent ${it.agent.id.value}(${it.agent.displayName}): total=${"%.2f".format(it.total)} strength=${it.strengthScore} avail=${it.availabilityPenalty} load=${it.loadPenalty}")
        }

        val best = scored.maxByOrNull { it.total }
        best?.let {
            log(
                "Selected ${it.agent.displayName} (${it.agent.id.value}) for task ${task.id}. " +
                        "reason: caps=$requiredCaps strength=${it.strengthScore} availabilityPen=${it.availabilityPenalty} loadPen=${it.loadPenalty} total=${"%.2f".format(it.total)}"
            )
        }
        return best?.agent
    }

    fun selectAgentsForConsensus(task: Task, directive: UserDirective? = null, maxAgents: Int = 3): List<Agent> {
        // If directive specifies a single agent, still respect it by including it first if possible
        val requiredCaps = capabilitiesFor(task.type)
        val candidates = registry.getAllAgents()
            .filter { it.status != AgentStatus.OFFLINE }
            .filter { agent -> requiredCaps.all { it in agent.capabilities } }

        if (candidates.isEmpty()) {
            log("No available agents for consensus selection on task ${task.id} requiring $requiredCaps")
            return emptyList()
        }

        val scored = candidates.map { agent ->
            val strength = strengthScore(agent, requiredCaps)
            val availabilityPenalty = if (agent.status == AgentStatus.BUSY) 10.0 else 0.0
            val loadPenalty = recentAssignments(agent.id).toDouble()
            ScoredAgent(agent, strength - availabilityPenalty - loadPenalty, strength, availabilityPenalty, loadPenalty)
        }.sortedByDescending { it.total }

        val list = mutableListOf<Agent>()
        // Include forced first if valid & confident
        directive?.assignToAgent?.let { id ->
            val confidence = directive.assignmentConfidence
            if (confidence >= MIN_ASSIGNMENT_CONFIDENCE) {
                val forced = scored.firstOrNull { it.agent.id == id }
                if (forced != null) {
                    list.add(forced.agent)
                    log("Consensus selection prioritized ${forced.agent.displayName} via directive conf=${formatConfidence(confidence)}")
                }
            }
        }

        // Add any remaining explicitly mentioned agents to honor user preference order
        directive?.assignedAgents
            ?.filter { agentId -> list.none { it.id == agentId } }
            ?.forEach { agentId ->
                val match = scored.firstOrNull { it.agent.id == agentId }
                if (match != null) {
                    list.add(match.agent)
                    log("Consensus selection honored mentioned agent ${match.agent.displayName}")
                }
            }
        for (sa in scored) {
            if (list.size >= maxAgents) break
            if (list.any { it.id == sa.agent.id }) continue
            list.add(sa.agent)
        }
        log("Consensus selection for task ${task.id}: ${list.joinToString { it.displayName }}")
        return list
    }

    private data class ScoredAgent(
        val agent: Agent,
        val total: Double,
        val strengthScore: Double,
        val availabilityPenalty: Double,
        val loadPenalty: Double
    )

    private fun strengthScore(agent: Agent, required: Set<Capability>): Double {
        if (agent.strengths.isEmpty()) return 50.0 // neutral default if unknown
        var sum = 0.0
        var count = 0
        for (cap in required) {
            val s = agent.strengths.firstOrNull { it.capability == cap }?.score ?: 50
            sum += s
            count++
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun recentAssignments(agentId: AgentId, window: Duration = Duration.ofHours(1)): Int {
        val end = Instant.now()
        val start = end.minus(window)
        return try {
            metrics.queryMetricsByAgent("tasks_assigned", MetricsRepository.TimeRange(start, end), agentId.value).size
        } catch (e: Exception) {
            // Be resilient if DB unavailable in tests
            0
        }
    }

    private fun capabilitiesFor(type: TaskType): Set<Capability> = when (type) {
        TaskType.IMPLEMENTATION -> setOf(Capability.CODE_GENERATION)
        TaskType.ARCHITECTURE -> setOf(Capability.ARCHITECTURE)
        TaskType.REVIEW -> setOf(Capability.CODE_REVIEW)
        TaskType.RESEARCH -> setOf(Capability.DATA_ANALYSIS)
        TaskType.TESTING -> setOf(Capability.TEST_WRITING)
        TaskType.DOCUMENTATION -> setOf(Capability.DOCUMENTATION)
        TaskType.PLANNING -> setOf(Capability.PLANNING)
        TaskType.BUGFIX -> setOf(Capability.DEBUGGING)
    }

    private fun log(message: String) {
        println("[AgentSelector] $message")
    }

    private fun formatConfidence(value: Double): String = "%.2f".format(value.coerceIn(0.0, 1.0))
}
