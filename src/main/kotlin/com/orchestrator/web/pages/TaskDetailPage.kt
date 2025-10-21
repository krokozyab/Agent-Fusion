
package com.orchestrator.web.pages

import com.orchestrator.domain.Decision
import com.orchestrator.domain.Proposal
import com.orchestrator.domain.Task
import com.orchestrator.web.components.Breadcrumbs
import com.orchestrator.web.components.DecisionComponent
import com.orchestrator.web.components.Navigation
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TaskDetailPage {

    data class Config(
        val task: Task,
        val proposals: List<Proposal>,
        val decision: Decision?,
        val clock: Clock = Clock.systemUTC(),
    )

    fun render(config: Config): String {
        val htmlContent = createHTML().html {
            pageLayout(config)
        }
        return "<!DOCTYPE html>\n$htmlContent"
    }

    private fun HTML.pageLayout(config: Config) {
        val navConfig = Navigation.Config(
            title = "Orchestrator",
            titleHref = "/",
            enableHtmxBoost = true,
            links = listOf(
                Navigation.Link(label = "Home", href = "/", icon = "🏠"),
                Navigation.Link(label = "Tasks", href = "/tasks", active = true, icon = "📋"),
                Navigation.Link(label = "Index Status", href = "/index", icon = "📁"),
                Navigation.Link(label = "Metrics", href = "/metrics", icon = "📊")
            )
        )

        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title("Task ${config.task.id.value} - Orchestrator")
            link(rel = "stylesheet", href = "/static/css/base.css")
            link(rel = "stylesheet", href = "/static/css/orchestrator.css")
            link(rel = "stylesheet", href = "/static/css/dark-mode.css")
            script(src = "/static/js/htmx.min.js") {}
            script(src = "https://cdn.jsdelivr.net/npm/mermaid/dist/mermaid.min.js") {}
        }

        body(classes = "dashboard-layout") {
            with(Navigation) {
                navigationBar(navConfig)
            }

            main(classes = "main-content") {
                attributes["id"] = "main-content"
                attributes["role"] = "main"

                // Breadcrumbs
                val breadcrumbConfig = Breadcrumbs.Config(
                    crumbs = listOf(
                        Breadcrumbs.Crumb("Home", "/"),
                        Breadcrumbs.Crumb("Tasks", "/tasks"),
                        Breadcrumbs.Crumb(config.task.id.value, current = true)
                    )
                )
                with(Breadcrumbs) {
                    breadcrumbs(breadcrumbConfig)
                }

                // Page Header
                pageHeader(config.task)

                // Task Details Section
                taskDetails(config.task)

                // Proposals Section
                proposalsSection(config.proposals)

                // Decision Section
                config.decision?.let { decisionSection(it, config.clock) }

                // Mermaid Diagram
                mermaidDiagram()
            }

            // Footer
            footer(classes = "main-footer") {
                small { +"Orchestrator Dashboard © 2025" }
            }
            script(src = "/static/js/theme-toggle.js") {}
            script(src = "/static/js/navigation.js") {}
            script { unsafe { +"mermaid.initialize({startOnLoad:true});" } }
        }
    }

    private fun FlowContent.pageHeader(task: Task) {
        div(classes = "flex justify-between items-center mb-lg") {
            div {
                h1(classes = "mt-0 mb-2") { +task.title }
                p(classes = "text-muted mb-0") { +"Details for task ${task.id.value}" }
            }
        }
    }

    private fun FlowContent.taskDetails(task: Task) {
        div(classes = "card mb-lg") {
            div(classes = "card-header") { h3(classes = "card-title") { +"Task Information" } }
            div(classes = "card-body") {
                ul(classes = "details-list") {
                    li { strong { +"ID:" }; span { +task.id.value } }
                    li { strong { +"Status:" }; span { +task.status.name } }
                    li { strong { +"Type:" }; span { +task.type.name } }
                    li { strong { +"Routing:" }; span { +task.routing.name } }
                    li { strong { +"Complexity:" }; span { +"${task.complexity}/10" } }
                    li { strong { +"Risk:" }; span { +"${task.risk}/10" } }
                    li { strong { +"Assignees:" }; span { +task.assigneeIds.joinToString { it.value } } }
                    li { strong { +"Created:" }; span { +task.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.RFC_1123_DATE_TIME) } }
                }
            }
        }
    }

    private fun FlowContent.proposalsSection(proposals: List<Proposal>) {
        div(classes = "card mb-lg") {
            div(classes = "card-header") { h3(classes = "card-title") { +"Proposals (${proposals.size})" } }
            div(classes = "card-body") {
                if (proposals.isEmpty()) {
                    p(classes = "text-muted") { +"No proposals submitted for this task." }
                } else {
                    proposals.forEach { proposal ->
                        div(classes = "proposal-item") {
                            h4 { +"Proposal from ${proposal.agentId.value}" }
                            pre { code { +(proposal.content?.toString() ?: "No content") } }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.decisionSection(decision: Decision, clock: Clock) {
        consumer.onTagContentUnsafe {
            +DecisionComponent.render(
                DecisionComponent.Model(
                    decision = decision,
                    zoneId = clock.zone
                )
            )
        }
    }

    private fun FlowContent.mermaidDiagram() {
        div(classes = "card") {
            div(classes = "card-header") { h3(classes = "card-title") { +"Task Flow" } }
            div(classes = "card-body") {
                div(classes = "mermaid") {
                    // Placeholder for Mermaid diagram
                    +"\n                    graph TD\n                        A[Task Created] --> B{Routing};\n                        B --> C[Agent 1];\n                        B --> D[Agent 2];\n                        C --> E{Consensus};\n                        D --> E;\n                        E --> F[Decision];\n                        F --> G[Task Completed];\n                    ".trimIndent()
                }
            }
        }
    }
}
