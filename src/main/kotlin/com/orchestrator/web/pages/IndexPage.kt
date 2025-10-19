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
                link(rel = "stylesheet", href = "/static/css/orchestrator.css")
                link(rel = "stylesheet", href = "/static/css/dark-mode.css")

                // HTMX
                script(src = "/static/js/htmx.min.js") {}
            }

            body(classes = "dashboard-layout") {
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

                        // Placeholder content
                        div(classes = "card") {
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

                // JavaScript
                script(src = "/static/js/theme-toggle.js") {}
                script(src = "/static/js/navigation.js") {}
            }
        }
        return "<!DOCTYPE html>\n$htmlContent"
    }
}
