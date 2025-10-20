package com.orchestrator.web.components

import com.orchestrator.domain.Proposal
import com.orchestrator.web.rendering.Fragment
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
                    // Basic expand/collapse for long content
                    val contentId = "proposal-content-${model.proposal.id.value}"
                    val preview = model.proposal.content.toString().take(200)
                    div {
                        +preview
                        if (model.proposal.content.toString().length > 200) {
                            span { +"..." }
                            button(classes = "proposal-card__expand-button") {
                                attributes["onclick"] = "document.getElementById('$contentId').style.display='block'; this.style.display='none';"
                                +"Expand"
                            }
                            div(classes = "proposal-card__full-content") {
                                id = contentId
                                style = "display:none;"
                                pre { code { +(model.proposal.content.toString()) } }
                            }
                        }
                    }
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