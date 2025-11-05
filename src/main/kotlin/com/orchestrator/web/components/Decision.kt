package com.orchestrator.web.components

import com.orchestrator.domain.Decision
import com.orchestrator.domain.ProposalId
import com.orchestrator.domain.ProposalRef
import com.orchestrator.web.rendering.Fragment
import com.orchestrator.web.utils.TimeFormatters
import java.time.ZoneId
import java.util.Locale
import kotlinx.html.*

object DecisionComponent {

    data class Model(
        val decision: Decision,
        val zoneId: ZoneId = ZoneId.systemDefault(),
    )

    fun render(model: Model): String = Fragment.render {
        decision(model)
    }

    private fun FlowContent.decision(model: Model) {
        val decision = model.decision
        val strategies = extractStrategyTrail(decision)
        val primaryStrategy = strategies.firstOrNull()
        val consensusAchieved = decision.consensusAchieved
        val statusLabel = if (consensusAchieved) "Consensus Achieved" else "No Consensus Reached"
        val statusToneClass = if (consensusAchieved) {
            "decision-card__status-badge--success"
        } else {
            "decision-card__status-badge--warning"
        }
        val relativeTime = TimeFormatters.relativeTime(decision.decidedAt, zoneId = model.zoneId)
        val timestamp = relativeTime.absolute
        val agreementPercent = decision.agreementRate?.let { "%.0f%%".format(Locale.US, it * 100) } ?: "N/A"
        val savingsAbsolute = decision.tokenSavingsAbsolute
        val savingsPercent = "%.2f%%".format(Locale.US, (decision.tokenSavingsPercent * 100).coerceAtLeast(0.0))
        val consideredCount = decision.considered.size
        val outcomeSelectionCount = when {
            decision.winnerProposalId != null -> 1
            decision.selected.isNotEmpty() -> decision.selected.size
            else -> 0
        }
        val winnerRef = decision.winnerProposalId?.let { winnerId -> decision.considered.findProposal(winnerId) }

        div(classes = "decision-container") {
            h3(classes = "decision__title") { +"Consensus Decision" }
            div(classes = "decision-card") {
                div(classes = "decision-card__header") {
                    div(classes = "decision-card__status-group") {
                        span(classes = "decision-card__status-badge $statusToneClass") {
                            +statusLabel
                        }
                        consensusIndicator(primaryStrategy)
                    }
                    div(classes = "decision-card__timestamp") {
                        +timestamp
                    }
                }

                div(classes = "decision-card__metrics") {
                    div(classes = "decision-card__metric decision-card__metric--tokens") {
                        span(classes = "decision-card__metric-label") { +"Token Savings" }
                        span(classes = "decision-card__metric-value") { +"$savingsAbsolute tokens" }
                        span(classes = "decision-card__metric-subtitle") { +savingsPercent }
                    }
                    div(classes = "decision-card__metric") {
                        span(classes = "decision-card__metric-label") { +"Agreement" }
                        span(classes = "decision-card__metric-value") { +agreementPercent }
                        span(classes = "decision-card__metric-subtitle") { +"$outcomeSelectionCount of $consideredCount proposal(s)" }
                    }
                    div(classes = "decision-card__metric") {
                        span(classes = "decision-card__metric-label") { +"Decided" }
                        span(classes = "decision-card__metric-value") { +relativeTime.humanized }
                        span(classes = "decision-card__metric-subtitle") { +timestamp }
                    }
                }

                div(classes = "decision-card__body") {
                    div(classes = "decision-card__outcome") {
                        h4 { +"Decision Outcome" }
                        when {
                            winnerRef != null -> {
                                p { +"Winning proposal ${winnerRef.id.value} selected via consensus." }
                            }
                            consensusAchieved && decision.selected.isNotEmpty() -> {
                                val selectedIds = decision.selected.joinToString(", ") { it.value }
                                p { +"Consensus selected proposals: $selectedIds" }
                            }
                            else -> {
                                p { +"No consensus reached yet. Awaiting additional input or follow-up." }
                            }
                        }
                    }

                    winnerRef?.let { winner ->
                        div(classes = "decision-card__winner-highlight") {
                            span(classes = "decision-card__winner-label") { +"Winning Proposal" }
                            h4(classes = "decision-card__winner-id") { +winner.id.value }
                            ul(classes = "decision-card__winner-meta") {
                                li { strong { +"Agent: " }; span { +winner.agentId.value } }
                                li { strong { +"Type: " }; span { +winner.inputType.name.replace('_', ' ') } }
                                li { strong { +"Confidence: " }; span { +"%.0f%%".format(Locale.US, winner.confidence * 100) } }
                                li { strong { +"Tokens: " }; span { +"${winner.tokenUsage.totalTokens}" } }
                            }
                        }
                    }

                    div(classes = "decision-card__rationale") {
                        h4 { +"Rationale" }
                        p { +(decision.rationale?.takeIf { it.isNotBlank() } ?: "No rationale provided.") }
                    }

                    if (strategies.size > 1) {
                        div(classes = "decision-card__strategy-trail") {
                            strong { +"Strategy Sequence: " }
                            span { +strategies.joinToString(" -> ") { formatStrategyLabel(it) } }
                        }
                    }
                }

                div(classes = "decision-card__footer") {
                    div(classes = "decision-card__meta") {
                        span {
                            +"Considered: "
                            strong { +"$consideredCount proposals" }
                        }
                        span {
                            +"Selected: "
                            strong {
                                if (decision.selected.isEmpty()) +"None" else +decision.selected.joinToString(", ") { it.value }
                            }
                        }
                        if (strategies.isNotEmpty()) {
                            span {
                                +"Primary Strategy: "
                                strong { +formatStrategyLabel(primaryStrategy ?: "Consensus") }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.consensusIndicator(strategy: String?) {
        val label = strategy?.let { formatStrategyLabel(it) } ?: "Consensus"
        val tone = consensusIndicatorClass(strategy)
        span(classes = "consensus-indicator $tone") {
            span(classes = "consensus-indicator__dot") { }
            span(classes = "consensus-indicator__label") { +"Using $label" }
        }
    }

    private fun consensusIndicatorClass(strategy: String?): String = when (strategy) {
        "VOTING" -> "consensus-indicator--voting"
        "REASONING_QUALITY" -> "consensus-indicator--reasoning"
        "CUSTOM" -> "consensus-indicator--custom"
        else -> "consensus-indicator--default"
    }

    private fun extractStrategyTrail(decision: Decision): List<String> =
        decision.metadata["strategyTrail"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "<none>" }
            ?: emptyList()

    private fun formatStrategyLabel(raw: String): String =
        raw.lowercase(Locale.US)
            .split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.US) } }

    private fun List<ProposalRef>.findProposal(id: ProposalId): ProposalRef? =
        firstOrNull { it.id == id }
}
