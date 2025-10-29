package com.orchestrator.web.components

import com.orchestrator.domain.Proposal
import com.orchestrator.web.rendering.Fragment
import com.orchestrator.web.utils.JsonFormatter
import com.orchestrator.web.utils.TimeFormatters
import kotlinx.html.*
import java.time.ZoneId

object ProposalCard {

    data class Model(
        val proposal: Proposal,
        val agentName: String,
        val agentAvatarUrl: String,
        val isWinner: Boolean = false,
        val zoneId: ZoneId = ZoneId.systemDefault(),
    )

    fun render(model: Model): String = Fragment.render {
        proposalCard(model)
    }

    private fun FlowContent.proposalCard(model: Model) {
        div(classes = "proposal-card" + if (model.isWinner) " proposal-card--winner" else "") {
            div(classes = "proposal-card__header") {
                div(classes = "proposal-card__agent") {
                    img(src = model.agentAvatarUrl, alt = model.agentName, classes = "proposal-card__agent-avatar")
                    span(classes = "proposal-card__agent-name") { +model.agentName }
                }
                div(classes = "proposal-card__timestamp") {
                    +TimeFormatters.relativeTime(model.proposal.createdAt, zoneId = model.zoneId).absolute
                }
            }
            div(classes = "proposal-card__body") {
                div(classes = "proposal-card__content") {
                    val formattedContent = JsonFormatter.format(model.proposal.content)
                    pre { code { +formattedContent } }
                }
            }
            div(classes = "proposal-card__footer") {
                div(classes = "proposal-card__meta") {
                    span(classes = "proposal-card__confidence") {
                        +"Confidence: "
                        strong { +"%.2f".format(model.proposal.confidence) }
                    }
                    span(classes = "proposal-card__tokens") {
                        +"Tokens: ${model.proposal.tokenUsage.totalTokens}"
                    }
                }
                if (model.isWinner) {
                    div(classes = "proposal-card__winner-indicator") {
                        +"Winner"
                    }
                }
            }
        }
    }
}