package com.orchestrator.web.pages

import com.orchestrator.web.rendering.PageLayout
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * Metrics dashboard page for viewing analytics and performance metrics.
 */
object MetricsPage {

    /**
     * Render complete metrics dashboard page
     */
    fun render(): String {
        val htmlContent = createHTML().html {
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title("Metrics - Orchestrator")

                // CSS
                link(rel = "stylesheet", href = "/static/css/base.css")
                link(rel = "stylesheet", href = "/static/css/bootstrap-litera.min.css")
                link(rel = "stylesheet", href = "/static/css/orchestrator.css?v=20241104")
                link(rel = "stylesheet", href = "/static/css/sse-status.css")

                // HTMX
                script(src = "/static/js/htmx.min.js") {}
            }

            body(classes = "dashboard-layout") {
                attributes["hx-ext"] = "sse"
                attributes["sse-connect"] = "/sse/metrics"

                with(PageLayout) {
                    dashboardShell(
                        pageTitle = "Metrics",
                        currentPath = "/metrics"
                    ) {
                        // Page header
                        div(classes = "page-header mb-lg") {
                            h1 { +"Metrics Dashboard" }
                            p(classes = "text-muted") {
                                +"View analytics, token usage, and performance metrics"
                            }
                        }

                        // Placeholder content with SSE swap
                        div(classes = "card") {
                            id = "metrics-container"
                            attributes["sse-swap"] = "metricsUpdated"

                            h2 { +"Coming Soon" }
                            p {
                                +"The Metrics Dashboard is currently under development. "
                                +"This page will show:"
                            }
                            ul {
                                li { +"Token usage statistics and trends" }
                                li { +"Task completion rates and times" }
                                li { +"Agent performance metrics" }
                                li { +"Decision analytics and routing accuracy" }
                                li { +"Export functionality (CSV, JSON)" }
                                li { +"Interactive charts and graphs" }
                            }
                        }
                    }
                }

                // Status indicator for SSE
                div(classes = "sse-status") {
                    id = "sse-status-indicator"
                    attributes["hx-swap-oob"] = "true"
                    div(classes = "sse-status__light") {
                        id = "sse-status-light"
                        attributes["class"] = "sse-status__light sse-status__light--disconnected"
                    }
                    span(classes = "sse-status__text") {
                        id = "sse-status-text"
                        +"Connecting..."
                    }
                }

                // JavaScript
                script(src = "/static/js/theme-toggle.js") {}
                script(src = "/static/js/navigation.js") {}
                script(src = "/static/js/sse-status.js") {}
            }
        }
        return "<!DOCTYPE html>\n$htmlContent"
    }
}
