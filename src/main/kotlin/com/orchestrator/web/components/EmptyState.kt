package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.p
import kotlinx.html.span

object EmptyState {
    fun render(config: Config): String =
        Fragment.render { emptyState(config) }

    fun FlowContent.emptyState(config: Config) {
        div(classes = "data-table__empty-state") {
            attributes["role"] = "status"
            attributes["aria-live"] = "polite"

            // Icon
            span(classes = "data-table__empty-icon") {
                attributes["aria-hidden"] = "true"
                +config.icon
            }

            // Heading
            h3(classes = "data-table__empty-heading") {
                +config.heading
            }

            // Description
            p(classes = "data-table__empty-description") {
                +config.description
            }

            // Optional call-to-action button
            config.action?.let { action ->
                button(classes = "data-table__empty-action") {
                    type = ButtonType.button
                    attributes["aria-label"] = action.label

                    if (action.hxGet != null) {
                        attributes["hx-get"] = action.hxGet
                        action.hxTarget?.let { attributes["hx-target"] = it }
                        action.hxSwap?.let { attributes["hx-swap"] = it }
                    }

                    +action.label
                }
            }
        }
    }

    data class Config(
        val heading: String,
        val description: String,
        val icon: String = "📋",
        val action: Action? = null
    )

    data class Action(
        val label: String,
        val hxGet: String? = null,
        val hxTarget: String? = null,
        val hxSwap: String? = null
    )

    // Common empty state configurations
    object Presets {
        fun noTasksFound() = Config(
            heading = "No tasks found",
            description = "There are no tasks in the system yet. Tasks will appear here once they are created.",
            icon = "📋"
        )

        fun noTasksMatchingFilters() = Config(
            heading = "No tasks match your filters",
            description = "Try adjusting your search or filter criteria to see more results.",
            icon = "🔍",
            action = Action(
                label = "Clear filters",
                hxGet = "/tasks",
                hxTarget = "#tasks-table",
                hxSwap = "outerHTML"
            )
        )

        fun noDataAvailable() = Config(
            heading = "No data available",
            description = "The requested data is not currently available. Please try again later.",
            icon = "📭"
        )

        fun noFilesIndexed() = Config(
            heading = "No files indexed",
            description = "No files have been indexed yet. Start indexing to see file information here.",
            icon = "📁",
            action = Action(
                label = "Refresh index",
                hxGet = "/index/refresh",
                hxTarget = "#index-status",
                hxSwap = "outerHTML"
            )
        )

        fun noMetricsAvailable() = Config(
            heading = "No metrics available",
            description = "Metrics data is not yet available. Start using the system to generate metrics.",
            icon = "📊"
        )
    }
}
