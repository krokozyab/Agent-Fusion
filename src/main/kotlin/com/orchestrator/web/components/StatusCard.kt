package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.span

/**
 * StatusCard component for displaying metric cards with visual status indicators.
 *
 * Supports:
 * - Metric values with labels
 * - Color-coded status indicators (green, yellow, red)
 * - Optional secondary information
 * - Responsive grid layout
 */
object StatusCard {

    enum class Status {
        HEALTHY,
        DEGRADED,
        CRITICAL
    }

    data class Config(
        val id: String,
        val title: String,
        val value: String,
        val label: String,
        val status: Status = Status.HEALTHY,
        val subtext: String? = null,
        val ariaLabel: String? = null,
        val testId: String? = null
    )

    fun render(config: Config): String = Fragment.render {
        card(config)
    }

    fun FlowContent.card(config: Config) {
        div(classes = classesFor(config)) {
            attributes["id"] = config.id
            config.testId?.let { attributes["data-testid"] = it }
            config.ariaLabel?.let { attributes["aria-label"] = it }
            attributes["role"] = "region"

            // Status indicator dot
            div(classes = "status-card__indicator") {
                attributes["class"] += " status-indicator--${config.status.name.lowercase()}"
                attributes["aria-hidden"] = "true"
            }

            // Main content
            div(classes = "status-card__content") {
                // Large value
                div(classes = "status-card__value") {
                    +config.value
                }

                // Label
                div(classes = "status-card__label") {
                    +config.label
                }

                // Optional subtext
                config.subtext?.let {
                    div(classes = "status-card__subtext") {
                        +it
                    }
                }
            }
        }
    }

    private fun classesFor(config: Config): String {
        return listOf(
            "status-card",
            "status-card--${config.status.name.lowercase()}"
        ).joinToString(" ")
    }
}
