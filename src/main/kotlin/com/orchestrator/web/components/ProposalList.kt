package com.orchestrator.web.components

import com.orchestrator.domain.Proposal
import com.orchestrator.web.rendering.Fragment
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.p
import kotlinx.html.ul
import kotlinx.html.li
import kotlinx.html.unsafe

object ProposalList {

    data class Model(
        val proposals: List<ProposalCard.Model>
    )

    fun render(model: Model): String = Fragment.render {
        proposalList(model)
    }

    private fun FlowContent.proposalList(model: Model) {
        div(classes = "proposal-list-container") {
            h3(classes = "proposal-list__title") { +"Proposals" }
            if (model.proposals.isEmpty()) {
                div(classes = "proposal-list--empty") {
                    p { +"No proposals submitted yet." }
                }
            } else {
                ul(classes = "proposal-list") {
                    model.proposals.forEach { proposalModel ->
                        li(classes = "proposal-list__item") {
                            unsafe { +ProposalCard.render(proposalModel) }
                        }
                    }
                }
            }
        }
    }
}