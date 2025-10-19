package com.orchestrator.web.rendering

import com.orchestrator.web.components.Navigation
import java.time.Year
import kotlinx.html.BODY
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.main
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.unsafe

object PageLayout {
    /**
     * Main dashboard shell with navigation and footer
     *
     * @param pageTitle The title for the page (used for accessibility)
     * @param currentPath The current request path (e.g., "/", "/tasks", "/metrics")
     * @param content The page content lambda
     */
    fun BODY.dashboardShell(
        pageTitle: String,
        currentPath: String = "/",
        content: FlowContent.() -> Unit
    ) {
        // Render navigation using the Navigation component
        with(Navigation) {
            navigationBar(buildNavigationConfig(currentPath))
        }

        // Main content area
        main(classes = "main-content") {
            attributes["id"] = "main-content"
            attributes["role"] = "main"

            div(classes = "container") {
                content()
            }
        }

        // Footer
        footer(classes = "main-footer") {
            attributes["role"] = "contentinfo"

            div(classes = "container") {
                p(classes = "main-footer__copyright") {
                    +"© ${Year.now().value} Agent Fusion Orchestrator"
                }
            }
        }

        // Mobile menu toggle script
        script {
            unsafe {
                +Navigation.MOBILE_MENU_SCRIPT
            }
        }

        // Initialize HTMX and Mermaid
        script {
            unsafe {
                +"""
                    document.addEventListener('DOMContentLoaded', function () {
                        if (window.htmx) {
                            window.htmx.config.defaultSwapStyle = 'outerHTML';
                        }
                        if (window.mermaid) {
                            window.mermaid.initialize({ startOnLoad: true });
                        }
                    });
                """.trimIndent()
            }
        }
    }

    /**
     * Build navigation configuration with proper active state
     */
    private fun buildNavigationConfig(currentPath: String): Navigation.Config {
        return Navigation.Config(
            title = "Orchestrator",
            titleHref = "/",
            enableHtmxBoost = true,
            links = listOf(
                Navigation.Link(
                    label = "Home",
                    href = "/",
                    active = currentPath == "/",
                    ariaLabel = "Go to home page",
                    icon = "🏠"
                ),
                Navigation.Link(
                    label = "Tasks",
                    href = "/tasks",
                    active = currentPath.startsWith("/tasks"),
                    ariaLabel = "View and manage tasks",
                    icon = "📋"
                ),
                Navigation.Link(
                    label = "Index Status",
                    href = "/index",
                    active = currentPath.startsWith("/index"),
                    ariaLabel = "View index status and file browser",
                    icon = "📁"
                ),
                Navigation.Link(
                    label = "Metrics",
                    href = "/metrics",
                    active = currentPath.startsWith("/metrics"),
                    ariaLabel = "View metrics and analytics",
                    icon = "📊"
                )
            )
        )
    }
}
