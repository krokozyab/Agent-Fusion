package com.orchestrator.web.sse

import com.orchestrator.domain.Task
import com.orchestrator.modules.metrics.Alert
import com.orchestrator.modules.metrics.MetricsSnapshot
import com.orchestrator.web.components.DataTable
import com.orchestrator.web.components.TaskRow
import com.orchestrator.web.components.displayName
import com.orchestrator.web.components.toTone
import com.orchestrator.web.dto.IndexStatusDTO
import com.orchestrator.web.dto.toTaskDTO
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
     * Render a task row suitable for HTMX table swaps.
     */
    fun taskRow(task: Task): String {
        val dto = task.toTaskDTO(clock)
        val model = TaskRow.Model(
            id = task.id.value,
            title = task.title,
            status = TaskRow.Status(
                label = task.status.displayName,
                tone = task.status.toTone()
            ),
            type = TaskRow.Type(
                label = task.type.displayName,
                tone = task.type.toTone()
            ),
            routing = formatDisplay(task.routing.name),
            assignees = dto.assigneeIds,
            complexity = task.complexity,
            risk = task.risk,
            detailUrl = "/tasks/${task.id.value}/modal",
            editUrl = "/tasks/${task.id.value}/edit",
            updatedAt = task.updatedAt,
            createdAt = task.createdAt,
            referenceInstant = Instant.now(clock),
            zoneId = clock.zone,
            hxTarget = "#modal-container",
            hxSwap = "innerHTML",
            hxIndicator = "#tasks-table-indicator"
        )

        val row = TaskRow.toRow(model)
        return renderRow(row)
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
            attributes["class"] = "index-progress"
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

    private fun renderRow(row: DataTable.Row): String = buildString {
        append("<tr class=\"data-table__row\" role=\"row\" tabindex=\"0\"")
        row.id?.let { append(" id=\"").append(it.escapeHtml()).append("\"") }
        row.ariaLabel?.let { append(" aria-label=\"").append(it.escapeHtml()).append("\"") }
        row.href?.let { append(" data-href=\"").append(it.escapeHtml()).append("\"") }
        row.attributes.forEach { (key, value) ->
            append(" ").append(key.escapeHtml()).append("=\"").append(value.escapeHtml()).append("\"")
        }
        append(">")

        row.cells.forEach { cell ->
            val tag = if (cell.header) "th" else "td"
            append("<").append(tag).append(" class=\"data-table__cell\"")
            if (cell.header) {
                append(" scope=\"row\" role=\"rowheader\"")
            } else {
                append(" role=\"gridcell\"")
            }
            if (cell.numeric) {
                append(" data-type=\"numeric\"")
            }
            append(">")
            if (cell.raw) {
                append(cell.content)
            } else {
                append(cell.content.escapeHtml())
            }
            append("</").append(tag).append(">")
        }

        append("</tr>")
    }

    private fun formatDisplay(value: String): String =
        value.lowercase(locale)
            .split('_')
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.titlecase(locale) }
            }

    private fun String.escapeHtml(): String = buildString(length) {
        for (ch in this@escapeHtml) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }
}
