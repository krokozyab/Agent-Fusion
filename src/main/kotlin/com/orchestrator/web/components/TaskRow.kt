package com.orchestrator.web.components

import com.orchestrator.web.components.StatusBadge.Tone
import com.orchestrator.web.utils.TimeFormatters
import java.time.Instant
import java.time.ZoneId
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span

object TaskRow {

    data class Status(
        val label: String,
        val tone: Tone
    )

    data class Type(
        val label: String,
        val tone: Tone = Tone.INFO
    )

    data class Model(
        val id: String,
        val title: String,
        val status: Status,
        val type: Type,
        val routing: String,
        val assignees: List<String> = emptyList(),
        val complexity: Int,
        val risk: Int,
        val detailUrl: String,
        val editUrl: String,
        val updatedAt: Instant? = null,
        val createdAt: Instant? = null,
        val referenceInstant: Instant = Instant.now(),
        val zoneId: ZoneId = ZoneId.systemDefault(),
        val hxTarget: String = "#modal",
        val hxSwap: String = "innerHTML",
        val hxIndicator: String? = null
    )

    fun toRow(model: Model): DataTable.Row {
        val timestamp = model.updatedAt ?: model.createdAt
        val relative = timestamp?.let {
            TimeFormatters.relativeTime(
                from = it,
                reference = model.referenceInstant,
                zoneId = model.zoneId
            )
        }

        val statusContent = buildString {
            append("<span class=\"task-row__status\">")
            append(StatusBadge.render(StatusBadge.Config(
                label = model.status.label,
                tone = model.status.tone,
                ariaLabel = "Status ${model.status.label}"
            )))
            append("<span class=\"task-row__status-text\">${model.status.label}</span>")
            append("</span>")
        }

        val typeContent = buildString {
            append("<span class=\"task-row__type\">")
            append(StatusBadge.render(StatusBadge.Config(
                label = model.type.label,
                tone = model.type.tone,
                ariaLabel = "Task type ${model.type.label}",
                outline = true
            )))
            append("<span class=\"task-row__type-text\">${model.type.label}</span>")
            append("</span>")
        }

        return DataTable.row(
            id = "task-row-${model.id}",
            ariaLabel = "${model.title}, status ${model.status.label}"
        ) {
            attribute("hx-get", "/tasks/${model.id}/modal")
            attribute("hx-target", "#modal-container")
            attribute("hx-trigger", "click")
            attribute("hx-swap", model.hxSwap)
            attribute("hx-push-url", "false")
            attribute("data-task-id", model.id)
            attribute("class", "data-table__row task-row")
            model.hxIndicator?.let { attribute("hx-indicator", it) }

            cell(header = true) {
                span(classes = "task-row__id") {
                    +"#${model.id}"
                }
            }
            cell {
                div(classes = "task-row__main") {
                    span(classes = "task-row__title") {
                        +model.title
                    }
                    div(classes = "task-row__meta") {
                        metaBadge(model.routing)
                        metaBadge("Complexity ${model.complexity}")
                        metaBadge("Risk ${model.risk}")
                    }
                }
            }
            rawCell(content = statusContent)
            rawCell(content = typeContent)
            cell {
                val agents = if (model.assignees.isEmpty()) listOf("Unassigned") else model.assignees
                div(classes = "task-row__agents") {
                    agents.forEach { agent ->
                        span(classes = "task-row__agent") {
                            +agent
                        }
                    }
                }
            }
            cell {
                span(classes = "task-row__timestamp") {
                    relative?.let {
                        attributes["title"] = it.absolute
                        +it.humanized
                    } ?: run {
                        +"—"
                    }
                }
            }
            cell {
                actions(model)
            }
        }
    }

    private fun FlowContent.actions(model: Model) {
        div(classes = "task-row__actions") {
            button(classes = "task-row__action task-row__action--view") {
                type = ButtonType.button
                attributes["hx-get"] = model.detailUrl
                attributes["hx-target"] = model.hxTarget
                attributes["hx-swap"] = model.hxSwap
                attributes["hx-trigger"] = "click consume"
                attributes["aria-label"] = "View task ${model.title}"
                model.hxIndicator?.let { attributes["hx-indicator"] = it }
                +"View"
            }
            button(classes = "task-row__action task-row__action--edit") {
                type = ButtonType.button
                attributes["hx-get"] = model.editUrl
                attributes["hx-target"] = model.hxTarget
                attributes["hx-swap"] = model.hxSwap
                attributes["hx-trigger"] = "click consume"
                attributes["aria-label"] = "Edit task ${model.title}"
                model.hxIndicator?.let { attributes["hx-indicator"] = it }
                +"Edit"
            }
        }
    }

    private fun FlowContent.metaBadge(text: String) {
        span(classes = "task-row__meta-item") {
            +text
        }
    }
}
