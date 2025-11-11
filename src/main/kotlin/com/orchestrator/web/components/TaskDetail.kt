package com.orchestrator.web.components

import com.orchestrator.web.components.StatusBadge.Tone
import com.orchestrator.web.rendering.Fragment
import com.orchestrator.web.utils.TimeFormatters
import java.time.Instant
import java.time.ZoneId
import kotlinx.html.*

object TaskDetail {

    data class Model(
        val id: String,
        val title: String,
        val status: TaskRow.Status,
        val type: TaskRow.Type,
        val description: String?,
        val metadata: Map<String, String>,
        val complexity: Int,
        val risk: Int,
        val proposals: List<Proposal> = emptyList(),
        val decision: Decision? = null,
        val createdAt: Instant,
        val updatedAt: Instant,
        val zoneId: ZoneId = ZoneId.systemDefault(),
    )

    data class Proposal(
        val agentId: String,
        val content: String,
        val confidence: Double,
    )

    data class Decision(
        val rationale: String,
        val winnerProposalId: String,
    )

    fun render(model: Model): String = Fragment.render {
        taskDetail(model)
    }

    private fun FlowContent.taskDetail(model: Model) {
        div(classes = "task-detail") {
            header(model)
            statusSection(model.status)
            descriptionSection(model.description)
            metadataSection(model.metadata)
            indicatorsSection(model.complexity, model.risk)
            proposalsSection(model.proposals)
            decisionSection(model.decision)
            actionsSection(model.id)
        }
    }

    private fun FlowContent.header(model: Model) {
        div(classes = "task-detail__header") {
            h2(classes = "task-detail__title") { +model.title }
            div(classes = "task-detail__id-container") {
                span(classes = "task-detail__id") { +"#${model.id}" }
                button(classes = "task-detail__copy-button") {
                    attributes["onclick"] = "copyToClipboard('${model.id}')"
                    +"Copy ID"
                }
            }
            div(classes = "task-detail__timestamps") {
                span { +"Created: ${TimeFormatters.relativeTime(model.createdAt, zoneId = model.zoneId).absolute}" }
                span { +"Updated: ${TimeFormatters.relativeTime(model.updatedAt, zoneId = model.zoneId).absolute}" }
            }
        }
    }

    private fun FlowContent.statusSection(status: TaskRow.Status) {
        div(classes = "task-detail__section") {
            h3 { +"Status" }
            unsafe { +StatusBadge.render(StatusBadge.Config(label = status.label, tone = status.tone)) }
        }
    }

    private fun FlowContent.descriptionSection(description: String?) {
        div(classes = "task-detail__section") {
            h3 { +"Description" }
            if (description != null) {
                div {
                    unsafe { +description }
                }
            } else {
                p { +"No description provided." }
            }
        }
    }

    private fun FlowContent.metadataSection(metadata: Map<String, String>) {
        div(classes = "task-detail__section") {
            h3 { +"Metadata" }
            ul(classes = "task-detail__metadata-list") {
                metadata.forEach { (key, value) ->
                    li {
                        strong { +"$key: " }
                        span { +value }
                    }
                }
            }
        }
    }

    private fun FlowContent.indicatorsSection(complexity: Int, risk: Int) {
        div(classes = "task-detail__section") {
            h3 { +"Indicators" }
            div(classes = "task-detail__indicators") {
                div {
                    strong { +"Complexity: " }
                    span { +"$complexity" }
                }
                div {
                    strong { +"Risk: " }
                    span { +"$risk" }
                }
            }
        }
    }

    private fun FlowContent.proposalsSection(proposals: List<Proposal>) {
        if (proposals.isNotEmpty()) {
            div(classes = "task-detail__section") {
                h3 { +"Proposals" }
                proposals.forEach { proposal ->
                    div(classes = "task-detail__proposal") {
                        strong { +"Agent: ${proposal.agentId}" }
                        p { +proposal.content }
                        span { +"Confidence: ${proposal.confidence}" }
                    }
                }
            }
        }
    }

    private fun FlowContent.decisionSection(decision: Decision?) {
        if (decision != null) {
            div(classes = "task-detail__section") {
                h3 { +"Decision" }
                div(classes = "task-detail__decision") {
                    strong { +"Winning Proposal: ${decision.winnerProposalId}" }
                    p { +decision.rationale }
                }
            }
        }
    }

    private fun FlowContent.actionsSection(taskId: String) {
        div(classes = "task-detail__actions") {
            button(classes = "task-detail__action-button") {
                attributes["hx-get"] = "/tasks/$taskId/refresh"
                attributes["hx-target"] = "#task-detail-$taskId"
                +"Refresh"
            }
            button(classes = "task-detail__action-button task-detail__action-button--danger") {
                attributes["hx-delete"] = "/tasks/$taskId"
                attributes["hx-confirm"] = "Are you sure you want to delete this task?"
                attributes["hx-target"] = "#task-row-$taskId"
                attributes["hx-swap"] = "delete"
                +"Delete"
            }
        }
    }
}