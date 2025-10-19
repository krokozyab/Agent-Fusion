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
                link(rel = "stylesheet", href = "/static/css/orchestrator.css")
                link(rel = "stylesheet", href = "/static/css/dark-mode.css")

                // HTMX
                script(src = "/static/js/htmx.min.js") {}
            }

            body(classes = "dashboard-layout") {
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

                        // Placeholder content
                        div(classes = "card") {
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

                // JavaScript
                script(src = "/static/js/theme-toggle.js") {}
                script(src = "/static/js/navigation.js") {}
            }
        }
        return "<!DOCTYPE html>\n$htmlContent"
    }
}
