package com.orchestrator.web.sse

import com.orchestrator.domain.Task
import com.orchestrator.modules.metrics.Alert
import com.orchestrator.modules.metrics.MetricsSnapshot
import com.orchestrator.web.components.TaskGridRowFactory
import com.orchestrator.web.components.TaskGridRowFactory.toJson
import com.orchestrator.web.dto.IndexStatusDTO
import com.orchestrator.web.pages.IndexStatusPage
import java.text.NumberFormat
import java.time.Clock
import java.time.Instant
import java.util.Locale
import kotlinx.html.div
import kotlinx.html.li
import kotlinx.html.progress
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.ul

/**
 * Generates HTML fragments for Server-Sent Events payloads.
 */
class FragmentGenerator(
    private val clock: Clock = Clock.systemUTC(),
    private val locale: Locale = Locale.getDefault()
) {

    /**
     * Render a lightweight task event payload for ag-Grid updates.
     */
    fun taskRow(task: Task, eventName: String, timestamp: Instant): String {
        val row = TaskGridRowFactory.fromTask(task, clock)

        return createHTML().div {
            attributes["class"] = "task-row"
            attributes["data-task-id"] = task.id.value
            attributes["data-event-type"] = eventName
            attributes["data-row"] = row.toJson()
            attributes["data-timestamp"] = timestamp.toString()

            // Render the task row with structure for testing
            div(classes = "task-row__title") {
                +task.title
            }
            div(classes = "task-row__status") {
                +task.status.name
            }
        }
    }

    /**
     * Render progress indicator for indexing operations.
     */
    internal fun indexProgress(event: IndexProgressEvent): String {
        val percentage = event.percentage.coerceIn(0, 100)
        val processed = event.processed
        val total = event.total
        val formatter = NumberFormat.getIntegerInstance(locale)

        return createHTML().div {
            attributes["id"] = "index-progress-region"
            attributes["class"] = "index-progress"
            attributes["sse-swap"] = "indexProgress"
            attributes["hx-swap"] = "outerHTML"
            attributes["data-operation-id"] = event.operationId
            attributes["data-timestamp"] = event.timestamp.toString()

            div(classes = "index-progress__header") {
                span(classes = "index-progress__title") {
                    +(event.title ?: "Index Update")
                }
                span(classes = "index-progress__value") {
                    +"$percentage%"
                }
            }

            progress(classes = "index-progress__bar") {
                attributes["max"] = "100"
                attributes["value"] = percentage.toString()
            }

            if (processed != null && total != null) {
                div(classes = "index-progress__meta") {
                    +"${formatter.format(processed)} of ${formatter.format(total)} items processed"
                }
            } else if (processed != null) {
                div(classes = "index-progress__meta") {
                    +"${formatter.format(processed)} items processed"
                }
            }

            event.message?.takeIf { it.isNotBlank() }?.let { message ->
                div(classes = "index-progress__message") {
                    +message
                }
            }
        }
    }

    fun indexSummary(status: IndexStatusDTO): String =
        IndexStatusPage.renderSummaryFragment(status)

    /**
     * Render metrics snapshot summary card.
     */
    fun metricsSnapshot(snapshot: MetricsSnapshot): String {
        val numberFormatter = NumberFormat.getIntegerInstance(locale)
        val percentFormatter = NumberFormat.getPercentInstance(locale).apply {
            maximumFractionDigits = 1
        }

        return createHTML().div {
            attributes["class"] = "metrics-summary"
            attributes["data-metrics-timestamp"] = snapshot.timestamp.toString()

            div(classes = "metrics-summary__section") {
                span(classes = "metrics-summary__label") { +"Total Tokens" }
                span(classes = "metrics-summary__value") {
                    +numberFormatter.format(snapshot.tokenUsage.totalTokens)
                }
                span(classes = "metrics-summary__subtext") {
                    +"Savings ${numberFormatter.format(snapshot.tokenUsage.savings)} tokens"
                }
            }

            div(classes = "metrics-summary__section") {
                span(classes = "metrics-summary__label") { +"Overall Success Rate" }
                span(classes = "metrics-summary__value") {
                    +percentFormatter.format(snapshot.performance.overallSuccessRate.coerceIn(0.0, 1.0))
                }
                span(classes = "metrics-summary__subtext") {
                    +"Avg completion ${(snapshot.performance.avgTaskCompletionTime.seconds)}s"
                }
            }

            div(classes = "metrics-summary__section metrics-summary__section--alerts") {
                span(classes = "metrics-summary__label") { +"Active Alerts" }
                span(classes = "metrics-summary__value") {
                    +numberFormatter.format(snapshot.alerts.unacknowledged)
                }
                span(classes = "metrics-summary__subtext") {
                    +"Total ${numberFormatter.format(snapshot.alerts.total)}"
                }
                ul {
                    snapshot.alerts.bySeverity.entries
                        .sortedByDescending { it.value }
                        .filter { it.value > 0 }
                        .forEach { (severity, count) ->
                            li {
                                attributes["class"] = "metrics-summary__bullet metrics-summary__bullet--${severity.name.lowercase()}"
                                +("${formatDisplay(severity.name)}: ${numberFormatter.format(count)}")
                            }
                        }
                }
            }
        }
    }

    /**
     * Render alert banner fragment.
     */
    fun alert(alert: Alert): String =
        createHTML().div {
            attributes["class"] = "alert-banner alert-banner--${alert.severity.name.lowercase()}"
            attributes["id"] = alert.id
            attributes["role"] = "alert"
            attributes["data-alert-type"] = alert.type.name
            attributes["data-created-at"] = alert.timestamp.toString()

            strong {
                +formatDisplay(alert.severity.name)
                +" · "
                +formatDisplay(alert.type.name)
            }

            span(classes = "alert-banner__message") {
                +" ${alert.message}"
            }

            alert.taskId?.let { taskId ->
                span(classes = "alert-banner__meta") {
                    +" Task ${taskId.value}"
                }
            }

            alert.agentId?.let { agentId ->
                span(classes = "alert-banner__meta") {
                    +" Agent ${agentId.value}"
                }
            }
        }

    private fun formatDisplay(value: String): String =
        value.lowercase(locale)
            .split('_')
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.titlecase(locale) }
            }
}
