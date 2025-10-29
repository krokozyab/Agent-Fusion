
package com.orchestrator.web.pages

import com.orchestrator.domain.Decision
import com.orchestrator.domain.Proposal
import com.orchestrator.domain.Task
import com.orchestrator.web.components.Breadcrumbs
import com.orchestrator.web.components.DecisionComponent
import com.orchestrator.web.components.Navigation
import com.orchestrator.web.utils.MermaidGenerator
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
            link(rel = "stylesheet", href = "/static/css/bootstrap-litera.min.css")
            link(rel = "stylesheet", href = "/static/css/orchestrator.css")
            script(src = "/static/js/htmx.min.js") {}
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
                mermaidDiagram(config)
            }

            // Footer
            footer(classes = "main-footer") {
                small { +"Orchestrator Dashboard © 2025" }
            }

            // Load Mermaid from local resources with onload handler
            script {
                unsafe {
                    +"""
                        (function() {
                            var mermaidScript = document.createElement('script');
                            mermaidScript.src = '/static/js/mermaid.min.js';
                            mermaidScript.onload = function() {
                                console.log('Mermaid library loaded');
                                if (typeof mermaid !== 'undefined') {
                                    mermaid.initialize({
                                        startOnLoad: false,
                                        theme: 'default',
                                        securityLevel: 'loose'
                                    });
                                    // Manually trigger rendering for elements already in DOM
                                    mermaid.run({
                                        querySelector: '.mermaid'
                                    }).then(function() {
                                        console.log('Mermaid diagrams rendered successfully');
                                    }).catch(function(error) {
                                        console.error('Mermaid rendering error:', error);
                                    });
                                } else {
                                    console.error('Mermaid is undefined after load');
                                }
                            };
                            mermaidScript.onerror = function() {
                                console.error('Failed to load Mermaid library');
                            };
                            document.head.appendChild(mermaidScript);
                        })();
                    """.trimIndent()
                }
            }

            script(src = "/static/js/app.js") {}
            script(src = "/static/js/theme-toggle.js") {}
            script(src = "/static/js/navigation.js") {}
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

    private fun FlowContent.mermaidDiagram(config: Config) {
        val diagram = MermaidGenerator.buildTaskSequence(config.task, config.proposals, config.decision)
        val diagramId = "mermaid-${config.task.id.value.replace(Regex("[^a-zA-Z0-9_-]"), "-")}"
        div(classes = "card") {
            div(classes = "card-header") { h3(classes = "card-title") { +"Task Flow" } }
            div(classes = "card-body") {
                div(classes = "mermaid") {
                    attributes["id"] = diagramId
                    if (diagram.isNotBlank()) {
                        consumer.onTagContentUnsafe { +diagram }
                    }
                }
            }
        }
    }
}
