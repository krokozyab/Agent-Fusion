package com.orchestrator.web.pages

import com.orchestrator.web.rendering.PageLayout
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * Index Status page for viewing indexed files and context database status.
 */
object IndexPage {

    /**
     * Render complete index status page
     */
    fun render(): String {
        val htmlContent = createHTML().html {
            head {
                meta(charset = "utf-8")
                meta(name = "viewport", content = "width=device-width, initial-scale=1")
                title("Index Status - Orchestrator")

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
                attributes["sse-connect"] = "/sse/index"

                with(PageLayout) {
                    dashboardShell(
                        pageTitle = "Index Status",
                        currentPath = "/index"
                    ) {
                        // Page header
                        div(classes = "page-header mb-lg") {
                            h1 { +"Index Status" }
                            p(classes = "text-muted") {
                                +"View and manage the context index database"
                            }
                        }

                        // Placeholder content with SSE swap
                        div(classes = "card") {
                            id = "index-status-container"
                            attributes["sse-swap"] = "indexProgress"

                            h2 { +"Coming Soon" }
                            p {
                                +"The Index Status page is currently under development. "
                                +"This page will show:"
                            }
                            ul {
                                li { +"Total files indexed" }
                                li { +"Indexed chunks and embeddings" }
                                li { +"File browser with search" }
                                li { +"Index health status" }
                                li { +"Refresh and rebuild controls" }
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
