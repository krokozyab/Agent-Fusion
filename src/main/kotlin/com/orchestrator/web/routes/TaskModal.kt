package com.orchestrator.web.routes

import com.orchestrator.domain.Decision
import com.orchestrator.domain.Proposal
import com.orchestrator.domain.Task
import com.orchestrator.web.components.DecisionComponent
import com.orchestrator.web.components.Modal
import com.orchestrator.web.components.StatusBadge
import com.orchestrator.web.components.displayName
import com.orchestrator.web.components.toTone
import com.orchestrator.web.utils.JsonFormatter
import com.orchestrator.web.utils.MermaidGenerator
import kotlinx.html.*
import kotlinx.html.stream.createHTML
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun renderTaskModal(task: Task, proposals: List<Proposal>, decision: Decision?): String {
    return createHTML().div {
        // Backdrop - direct child of modal-container
        div(classes = "modal__backdrop") {}

        // Modal content - direct child of modal-container
        div(classes = "modal__content") {
            attributes["id"] = "task-detail-modal"
            div(classes = "modal__header") {
                h3(classes = "modal__title") {
                    attributes["id"] = "task-detail-title"
                    +"Task: ${task.title}"
                }
                button(classes = "modal__close") {
                    attributes["data-modal-close"] = "modal-container"
                    attributes["aria-label"] = "Close modal"
                    +"×"
                }
            }

            div(classes = "modal__body") {
                // Task Information
                div(classes = "mb-lg") {
                    h4(classes = "mt-0 mb-md") { +"Task Information" }
                    ul(classes = "details-list") {
                        li { strong { +"ID:" }; span { +task.id.value } }
                        li {
                            strong { +"Status:" }
                            span {
                                unsafe { +StatusBadge.render(StatusBadge.Config(label = task.status.displayName, tone = task.status.toTone())) }
                            }
                        }
                        li {
                            strong { +"Type:" }
                            span {
                                unsafe { +StatusBadge.render(StatusBadge.Config(label = task.type.displayName, tone = task.type.toTone(), outline = true)) }
                            }
                        }
                        li { strong { +"Routing:" }; span { +task.routing.name } }
                        li { strong { +"Complexity:" }; span { +"${task.complexity}/10" } }
                        li { strong { +"Risk:" }; span { +"${task.risk}/10" } }
                        li { strong { +"Assignees:" }; span { +task.assigneeIds.joinToString { it.value } } }
                        li {
                            strong { +"Created:" }
                            span { +task.createdAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.RFC_1123_DATE_TIME) }
                        }
                    }
                    task.description?.let { desc ->
                        div(classes = "mt-md") {
                            strong { +"Description:" }
                            p(classes = "mt-sm") { +desc }
                        }
                    }
                }

                // Proposals Section
                div(classes = "mb-lg") {
                    h4(classes = "mt-0 mb-md") { +"Proposals (${proposals.size})" }
                    if (proposals.isEmpty()) {
                        p(classes = "text-muted") { +"No proposals submitted for this task." }
                    } else {
                        proposals.forEach { proposal ->
                            div(classes = "proposal-item mb-md") {
                                h5 { +"Proposal from ${proposal.agentId.value}" }
                                pre { code { +JsonFormatter.format(proposal.content) } }
                            }
                        }
                    }
                }

                // Decision Section
                decision?.let {
                    div(classes = "mb-lg") {
                        unsafe {
                            +DecisionComponent.render(
                                DecisionComponent.Model(
                                    decision = it,
                                    zoneId = ZoneId.systemDefault()
                                )
                            )
                        }
                    }
                }

                // Mermaid Diagram
                val diagram = MermaidGenerator.buildTaskSequence(task, proposals, decision)
                val diagramId = "mermaid-modal-${task.id.value.replace(Regex("[^a-zA-Z0-9_-]"), "-")}"
                div(classes = "mb-lg") {
                    h4(classes = "mt-0 mb-md") { +"Task Flow" }
                    div(classes = "mermaid") {
                        attributes["id"] = diagramId
                        if (diagram.isNotBlank()) {
                            unsafe { +diagram }
                        }
                    }
                }
            }
        }

        // Scripts for Mermaid rendering
        script {
            unsafe {
                +"""
                    (function() {
                        // Load and render Mermaid
                        if (typeof mermaid === 'undefined') {
                            var mermaidScript = document.createElement('script');
                            mermaidScript.src = '/static/js/mermaid.min.js';
                            mermaidScript.onload = function() {
                                mermaid.initialize({
                                    startOnLoad: false,
                                    theme: 'default',
                                    securityLevel: 'loose'
                                });
                                mermaid.run({
                                    querySelector: '.mermaid'
                                }).then(function() {
                                    console.log('Mermaid diagram rendered in modal');
                                }).catch(function(error) {
                                    console.error('Mermaid rendering error:', error);
                                });
                            };
                            document.head.appendChild(mermaidScript);
                        } else {
                            // Mermaid already loaded, just render
                            mermaid.run({
                                querySelector: '.mermaid'
                            }).then(function() {
                                console.log('Mermaid diagram rendered in modal');
                            }).catch(function(error) {
                                console.error('Mermaid rendering error:', error);
                            });
                        }
                    })();
                """.trimIndent()
            }
        }
    }.toString()
}
