package com.orchestrator.modules.routing

import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import java.time.Instant

/**
 * High-level composition module that integrates routing components:
 * - DirectiveParser: parse user directives from free text (routeTask uses neutral directive)
 * - TaskClassifier: classify task complexity/risk to inform strategy
 * - StrategyPicker: choose RoutingStrategy honoring directive and heuristics
 * - AgentSelector: select appropriate agent(s) according to chosen strategy
 *
 * Provides two entry points:
 * - routeTask(task): RoutingDecision        -> neutral directive (no explicit user text)
 * - routeTaskWithDirective(task, directive): RoutingDecision
 *
 * Prints comprehensive logs of its reasoning steps.
 */
class RoutingModule(
    private val agentRegistry: AgentRegistry,
    private val strategyPicker: StrategyPicker = StrategyPicker(),
    private val strategyCalibrator: StrategyPickerCalibrator = StrategyPickerCalibrator(DecisionAnalyticsTelemetryProvider())
) {
    private val agentSelector = AgentSelector(agentRegistry)
    private val directiveParser = DirectiveParser(agentRegistry)

    fun routeTask(task: Task): RoutingDecision {
        val directive = UserDirective(originalText = "")
        return routeTaskWithDirective(task, directive)
    }

    fun routeTaskWithDirective(task: Task, directive: UserDirective): RoutingDecision {
        val start = Instant.now()
        log("Routing task=${task.id} type=${task.type} title='${task.title}' assignees=${task.assigneeIds}")

        // 0) Refresh picker configuration from telemetry
        strategyCalibrator.let { strategyPicker.applyCalibration(it) }

        // 1) Classify task
        val text = task.description ?: task.title
        val classification = TaskClassifier.classify(text)
        log("Classification: complexity=${classification.complexity} risk=${classification.risk} critical=${classification.criticalKeywords} conf=${"%.2f".format(classification.confidence)}")

        // 2) Determine strategy (directive already given; routeTask uses neutral directive)
        val strategy = strategyPicker.pickStrategy(task, directive, classification)
        log(
            "Strategy selected: $strategy (" +
                "force=${directive.forceConsensus} conf=${formatConfidence(directive.forceConsensusConfidence)}, " +
                "prevent=${directive.preventConsensus} conf=${formatConfidence(directive.preventConsensusConfidence)}, " +
                "assigned=${directive.assignToAgent} conf=${formatConfidence(directive.assignmentConfidence)}, " +
                "emergency=${directive.isEmergency} conf=${formatConfidence(directive.emergencyConfidence)}, " +
                "agents=${directive.assignedAgents?.joinToString { it.value }}" +
                ")"
        )

        // 3) Select agent(s) according to strategy
        val participants: List<AgentId> = when (strategy) {
            RoutingStrategy.SOLO -> {
                val agent = agentSelector.selectAgentForTask(task, directive)
                if (agent == null) emptyList() else listOf(agent.id)
            }
            RoutingStrategy.CONSENSUS -> {
                agentSelector.selectAgentsForConsensus(task, directive, maxAgents = 3).map { it.id }
            }
            RoutingStrategy.SEQUENTIAL -> {
                // Use consensus selection as a pool; orchestration layer will handle sequential ordering
                agentSelector.selectAgentsForConsensus(task, directive, maxAgents = 3).map { it.id }
            }
            RoutingStrategy.PARALLEL -> {
                // Similar candidate selection as consensus, executed in parallel by orchestration
                agentSelector.selectAgentsForConsensus(task, directive, maxAgents = 3).map { it.id }
            }
        }

        val primary = participants.firstOrNull()
        log("Agents selected (${participants.size}): ${participants.joinToString(", ") { it.value }} primary=${primary?.value}")

        // 4) Build decision
        val decision = RoutingDecision(
            taskId = task.id,
            strategy = strategy,
            primaryAgentId = primary,
            participantAgentIds = participants,
            directive = directive,
            classification = classification,
            decidedAt = start,
            notes = null,
            metadata = directiveMetadata(directive)
        )
        log("Routing decision ready for task=${task.id}: strategy=$strategy participants=${participants.map { it.value }}")
        return decision
    }

    private fun log(message: String) {
        println("[RoutingModule] $message")
    }

    private fun formatConfidence(value: Double): String = when {
        value <= 0.0 -> "0.00"
        else -> "%.2f".format(value.coerceIn(0.0, 1.0))
    }

    private fun directiveMetadata(directive: UserDirective): Map<String, String> {
        val meta = mutableMapOf<String, String>()
        meta["directive.forceConsensus"] = directive.forceConsensus.toString()
        meta["directive.preventConsensus"] = directive.preventConsensus.toString()
        directive.assignToAgent?.let { meta["directive.assignToAgent"] = it.value }
        directive.assignedAgents?.takeIf { it.isNotEmpty() }?.let { agents ->
            meta["directive.assignedAgents"] = agents.joinToString(",") { it.value }
        }
        meta["directive.isEmergency"] = directive.isEmergency.toString()

        if (directive.forceConsensusConfidence > 0.0) {
            meta["directive.forceConsensusConfidence"] = directive.forceConsensusConfidence.toString()
        }
        if (directive.preventConsensusConfidence > 0.0) {
            meta["directive.preventConsensusConfidence"] = directive.preventConsensusConfidence.toString()
        }
        if (directive.assignmentConfidence > 0.0) {
            meta["directive.assignmentConfidence"] = directive.assignmentConfidence.toString()
        }
        if (directive.emergencyConfidence > 0.0) {
            meta["directive.emergencyConfidence"] = directive.emergencyConfidence.toString()
        }
        if (directive.parsingNotes.isNotEmpty()) {
            meta["directive.parsingNotes"] = directive.parsingNotes.joinToString(" | ")
        }

        if (directive.originalText.isNotBlank()) {
            meta["directive.originalText"] = directive.originalText
        }
        return meta
    }
}
