package com.orchestrator.web.pages

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.web.components.ConfigSection
import com.orchestrator.web.components.Navigation
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * Configuration display page showing all active fusionagent.toml parameters.
 */
object ConfigPage {

    fun render(config: ContextConfig): String {
        val htmlContent = createHTML().html {
            pageLayout(config)
        }
        return "<!DOCTYPE html>\n$htmlContent"
    }

    private fun HTML.pageLayout(config: ContextConfig) {
        val navConfig = Navigation.Config(
            title = "Orchestrator",
            titleHref = "/",
            enableHtmxBoost = false,
            links = listOf(
                Navigation.Link("Home", "/", icon = "🏠"),
                Navigation.Link("Tasks", "/tasks", icon = "📋"),
                Navigation.Link("Index Status", "/index", icon = "📊"),
                Navigation.Link("Files", "/files", icon = "📂"),
                Navigation.Link("Context Explorer", "/explorer", icon = "🔍", disableBoost = true),
                Navigation.Link("Config", "/config", active = true, icon = "⚙️"),
                Navigation.Link("Metrics", "/metrics", icon = "📈")
            )
        )

        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title("Configuration - Orchestrator")
            link(rel = "icon", href = "/static/images/favicon.svg", type = "image/svg+xml")
            link(rel = "stylesheet", href = "/static/css/base.css")
            link(rel = "stylesheet", href = "/static/css/bootstrap-litera.min.css")
            link(rel = "stylesheet", href = "/static/css/orchestrator.css")
            link(rel = "stylesheet", href = "/static/css/config.css")
        }

        body(classes = "dashboard-layout") {
            with(Navigation) { navigationBar(navConfig) }

            main(classes = "main-content") {
                attributes["id"] = "main-content"
                attributes["role"] = "main"

                pageHeader()
                configSections(config)
            }

            footer(classes = "main-footer") {
                small { +"Orchestrator Dashboard © 2025" }
            }

            script(src = "/static/js/theme-toggle.js") {}
            script(src = "/static/js/navigation.js") {}
        }
    }

    private fun FlowContent.pageHeader() {
        div(classes = "mb-4") {
            h1(classes = "mt-0 mb-2") { +"⚙️ Configuration" }
            p(classes = "text-muted mb-0") {
                +"Active parameters from fusionagent.toml"
            }
        }
    }

    private fun FlowContent.configSections(config: ContextConfig) {
        div(classes = "config-container") {
            with(ConfigSection) {
                renderAllSections(config)
            }
        }
    }
}
