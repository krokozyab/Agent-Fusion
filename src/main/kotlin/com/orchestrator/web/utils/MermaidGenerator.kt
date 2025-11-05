package com.orchestrator.web.utils

import com.orchestrator.domain.Decision
import com.orchestrator.domain.Proposal
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Utility to generate Mermaid sequence diagrams that describe how a task
 * moved through the orchestration pipeline.
 *
 * The output string is suitable for embedding directly inside a
 * `<div class="mermaid">…</div>` container.
 */
object MermaidGenerator {

    private const val INDENT = "    "
    private const val MAX_AGENT_PARTICIPANTS = 4
    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun buildTaskSequence(
        task: Task,
        proposals: List<Proposal>,
        decision: Decision?
    ): String {
        val builder = StringBuilder()
        builder.appendLine("sequenceDiagram")

        val taskAlias = "Task"
        val routerAlias = "Router"
        val consensusAlias = "Consensus"
        val decisionAlias = "Decision"
        val completionAlias = "Completion"

        val taskDisplay = "Task ${truncate(task.title.ifBlank { task.id.value })}"
        val routerDisplay = "Routing (${task.routing.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }})"
        val consensusDisplay = "Consensus Engine"
        val decisionDisplay = "Decision Record"
        val completionDisplay = "Status: ${task.status.name.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }}"

        appendParticipant(builder, taskAlias, taskDisplay)
        appendParticipant(builder, routerAlias, routerDisplay)

        val agentParticipants = buildAgentParticipants(task, proposals)
        agentParticipants.entries.forEach { appendParticipant(builder, it.alias, it.displayName) }

        val includeConsensus = task.routing == RoutingStrategy.CONSENSUS || decision != null
        if (includeConsensus) {
            appendParticipant(builder, consensusAlias, consensusDisplay)
        }

        if (decision != null) {
            appendParticipant(builder, decisionAlias, decisionDisplay)
        }

        appendParticipant(builder, completionAlias, completionDisplay)

        builder.appendLine()

        builder.appendLine(message(taskAlias, routerAlias, "Create ${task.type.name.lowercase(Locale.US)} task"))

        when (task.routing) {
            RoutingStrategy.SOLO -> appendSoloFlow(builder, routerAlias, agentParticipants, completionAlias, decision)
            RoutingStrategy.CONSENSUS -> appendConsensusFlow(builder, routerAlias, consensusAlias, decisionAlias, completionAlias, agentParticipants, proposals, decision)
            RoutingStrategy.SEQUENTIAL -> appendSequentialFlow(builder, routerAlias, agentParticipants, completionAlias, proposals, decision)
            RoutingStrategy.PARALLEL -> appendParallelFlow(builder, routerAlias, agentParticipants, completionAlias, proposals, decision)
        }

        if (decision == null) {
            val statusMsg = when {
                task.status.name == "COMPLETED" -> "Task completed successfully"
                task.status.name == "FAILED" -> "Task failed"
                task.status.name == "IN_PROGRESS" -> "Work in progress"
                task.status.name == "WAITING_INPUT" -> "Waiting for input"
                else -> "Awaiting completion"
            }
            builder.appendLine(message(routerAlias, completionAlias, statusMsg))
        } else {
            val winnerLabel = decision.winnerProposalId?.value ?: "No winner"
            val rationale = decision.rationale?.let { truncate(it, 80) }
            val noteLines = buildList {
                add("Winner: $winnerLabel")
                rationale?.let { add("Rationale: $it") }
            }
            if (noteLines.isNotEmpty()) {
                builder.appendLine(noteOver(decisionAlias, noteLines.joinToString("\\n")))
            }
            val statusMsg = when {
                task.status.name == "COMPLETED" -> "Finalized as completed"
                task.status.name == "FAILED" -> "Finalized as failed"
                else -> "Decision recorded"
            }
            builder.appendLine(message(decisionAlias, completionAlias, statusMsg))
        }

        if (agentParticipants.total > MAX_AGENT_PARTICIPANTS) {
            builder.appendLine(noteOver(routerAlias, "Additional agents omitted (${agentParticipants.total - MAX_AGENT_PARTICIPANTS} more)"))
        }

        return builder.toString()
    }

    private fun appendSoloFlow(
        builder: StringBuilder,
        routerAlias: String,
        agents: AgentParticipants,
        completionAlias: String,
        decision: Decision?
    ) {
        val agent = agents.entries.firstOrNull()
        val agentAlias = agent?.alias ?: "Worker"
        if (agent == null) {
            appendParticipant(builder, agentAlias, "Assigned Agent")
        }

        builder.appendLine(message(routerAlias, agentAlias, "Assign task"))
        builder.appendLine(message(agentAlias, routerAlias, "Acknowledge assignment"))

        if (decision != null) {
            builder.appendLine(message(agentAlias, "Decision", "Submit final outcome"))
        } else {
            builder.appendLine(message(agentAlias, completionAlias, "Return implementation plan"))
        }
    }

    private fun appendConsensusFlow(
        builder: StringBuilder,
        routerAlias: String,
        consensusAlias: String,
        decisionAlias: String,
        completionAlias: String,
        agents: AgentParticipants,
        proposals: List<Proposal>,
        decision: Decision?
    ) {
        agents.entries.forEach { agent ->
            builder.appendLine(message(routerAlias, agent.alias, "Invite proposal"))
        }
        val orderedProposals = proposals.sortedBy { it.createdAt }
        if (orderedProposals.isEmpty()) {
            val target = if (decision != null) decisionAlias else completionAlias
            builder.appendLine(message(consensusAlias, target, "No proposals available"))
            return
        }
        orderedProposals.forEach { proposal ->
            val alias = agents.aliasFor(proposal.agentId.value)
            val summary = buildProposalSummary(proposal)
            builder.appendLine(message(alias, consensusAlias, summary))
        }
        if (decision != null) {
            val agreement = decision.agreementRate?.let { "%.0f%%".format(Locale.US, it * 100) } ?: "n/a"
            builder.appendLine(message(consensusAlias, decisionAlias, "Aggregate consensus (agreement $agreement)"))
        } else {
            builder.appendLine(message(consensusAlias, completionAlias, "Awaiting consensus outcome"))
        }
    }

    private fun appendSequentialFlow(
        builder: StringBuilder,
        routerAlias: String,
        agents: AgentParticipants,
        completionAlias: String,
        proposals: List<Proposal>,
        decision: Decision?
    ) {
        val orderedAgents = agents.entries
        orderedAgents.forEachIndexed { index, agent ->
            builder.appendLine(message(routerAlias, agent.alias, "Stage ${index + 1} assignment"))
            val proposal = proposals.find { it.agentId.value == agent.agentId }
            if (proposal != null) {
                val summary = buildProposalSummary(proposal)
                builder.appendLine(message(agent.alias, routerAlias, summary))
            }
        }
        if (decision != null) {
            builder.appendLine(message(routerAlias, "Decision", "Compile sequential outputs"))
        }
        builder.appendLine(message(routerAlias, completionAlias, "Finalize pipeline"))
    }

    private fun appendParallelFlow(
        builder: StringBuilder,
        routerAlias: String,
        agents: AgentParticipants,
        completionAlias: String,
        proposals: List<Proposal>,
        decision: Decision?
    ) {
        agents.entries.forEach { agent ->
            builder.appendLine(message(routerAlias, agent.alias, "Dispatch parallel work"))
        }
        proposals.sortedBy { it.createdAt }.forEach { proposal ->
            val alias = agents.aliasFor(proposal.agentId.value)
            val summary = buildProposalSummary(proposal)
            builder.appendLine(message(alias, routerAlias, summary))
        }
        if (decision != null) {
            builder.appendLine(message(routerAlias, "Decision", "Aggregate parallel outputs"))
            builder.appendLine(message("Decision", completionAlias, "Publish result"))
        } else {
            builder.appendLine(message(routerAlias, completionAlias, "Combine parallel outputs"))
        }
    }

    private fun appendParticipant(builder: StringBuilder, alias: String, display: String) {
        builder.appendLine("${INDENT}participant $alias as ${escape(display)}")
    }

    private fun message(sourceAlias: String, targetAlias: String, text: String): String =
        "${INDENT}${escapeAlias(sourceAlias)}->>${escapeAlias(targetAlias)}: ${escape(text)}"

    private fun noteOver(alias: String, text: String): String =
        "${INDENT}Note over ${escapeAlias(alias)}: ${escape(text)}"

    private fun buildProposalSummary(proposal: Proposal): String {
        val created = isoFormatter.format(proposal.createdAt)
        val confidence = "%.0f%%".format(Locale.US, proposal.confidence * 100)
        val tokenInfo = proposal.tokenUsage.totalTokens.takeIf { it > 0 }?.let { "$it tok" } ?: "tokens n/a"
        return "Proposal (${confidence}, $tokenInfo) @ $created"
    }

    private fun buildAgentParticipants(task: Task, proposals: List<Proposal>): AgentParticipants {
        val order = linkedSetOf<String>()
        proposals.sortedBy { it.createdAt }.forEach { order += it.agentId.value }
        task.assigneeIds.map { it.value }.forEach { order += it }

        val entries = order.take(MAX_AGENT_PARTICIPANTS).mapIndexed { index, agentId ->
            AgentParticipant(
                alias = "Agent${index + 1}",
                displayName = "Agent ${shortenAgent(agentId)}",
                agentId = agentId
            )
        }
        return AgentParticipants(entries, order.size)
    }

    private fun shortenAgent(agentId: String, max: Int = 18): String =
        if (agentId.length <= max) agentId else agentId.take(max - 1) + "…"

    private data class AgentParticipant(
        val alias: String,
        val displayName: String,
        val agentId: String
    )

    private data class AgentParticipants(
        val entries: List<AgentParticipant>,
        val total: Int
    ) {
        fun aliasFor(agentId: String): String =
            entries.find { it.agentId == agentId }?.alias ?: "Agent"
    }

    private fun escape(value: String): String =
        buildString(value.length) {
            value.forEach { ch ->
                when (ch) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    else -> append(ch)
                }
            }
        }

    private fun escapeAlias(value: String): String = value

    private fun truncate(text: String, max: Int = 40): String =
        if (text.length <= max) text else text.take(max - 1) + "…"
}
