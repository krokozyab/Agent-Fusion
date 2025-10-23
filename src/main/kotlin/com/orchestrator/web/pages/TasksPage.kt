package com.orchestrator.web.pages

import com.orchestrator.web.rendering.PageLayout
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * Tasks page for viewing and managing all tasks.
 *
 * Displays task list with:
 * - Search and filter controls
 * - Sortable data table
 * - Pagination
 * - HTMX live updates
 */
object TasksPage {

    /**
     * Render complete tasks list page
     */
    fun render(): String = createHTML().html {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title("Tasks - Orchestrator")

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
                    pageTitle = "Tasks",
                    currentPath = "/tasks"
                ) {
                    // Page header
                    div(classes = "page-header mb-lg") {
                        div(classes = "flex justify-between items-center") {
                            div {
                                h1(classes = "mt-0 mb-2") { +"Tasks" }
                                p(classes = "text-muted mb-0") {
                                    +"View and manage all orchestrator tasks"
                                }
                            }

                            // Future: Add "Create Task" button
                            // button(classes = "btn btn-primary") {
                            //     +"+ Create Task"
                            // }
                        }
                    }

                    // Search and filter controls
                    div(classes = "card mb-md") {
                        div(classes = "card-body") {
                            form {
                                id = "task-filters"
                                attributes["hx-get"] = "/tasks/table"
                                attributes["hx-target"] = "#tasks-table-container"
                                attributes["hx-trigger"] = "submit, change delay:500ms"
                                attributes["hx-swap"] = "innerHTML"

                                div(classes = "grid grid-cols-4 gap-md") {
                                    // Search input
                                    div(classes = "form-group") {
                                        label {
                                            htmlFor = "search"
                                            +"Search"
                                        }
                                        input(type = InputType.search, name = "search") {
                                            id = "search"
                                            placeholder = "Search tasks..."
                                            classes = setOf("form-control")
                                        }
                                    }

                                    // Status filter
                                    div(classes = "form-group") {
                                        label {
                                            htmlFor = "status"
                                            +"Status"
                                        }
                                        select {
                                            id = "status"
                                            name = "status"
                                            classes = setOf("form-control")

                                            option {
                                                value = ""
                                                +"All Statuses"
                                            }
                                            option { value = "PENDING"; +"Pending" }
                                            option { value = "IN_PROGRESS"; +"In Progress" }
                                            option { value = "WAITING_INPUT"; +"Waiting Input" }
                                            option { value = "COMPLETED"; +"Completed" }
                                            option { value = "FAILED"; +"Failed" }
                                        }
                                    }

                                    // Type filter
                                    div(classes = "form-group") {
                                        label {
                                            htmlFor = "type"
                                            +"Type"
                                        }
                                        select {
                                            id = "type"
                                            name = "type"
                                            classes = setOf("form-control")

                                            option {
                                                value = ""
                                                +"All Types"
                                            }
                                            option { value = "IMPLEMENTATION"; +"Implementation" }
                                            option { value = "BUGFIX"; +"Bug Fix" }
                                            option { value = "ARCHITECTURE"; +"Architecture" }
                                            option { value = "REVIEW"; +"Review" }
                                            option { value = "RESEARCH"; +"Research" }
                                            option { value = "TESTING"; +"Testing" }
                                            option { value = "DOCUMENTATION"; +"Documentation" }
                                            option { value = "PLANNING"; +"Planning" }
                                        }
                                    }

                                    // Routing filter
                                    div(classes = "form-group") {
                                        label {
                                            htmlFor = "routing"
                                            +"Routing"
                                        }
                                        select {
                                            id = "routing"
                                            name = "routing"
                                            classes = setOf("form-control")

                                            option {
                                                value = ""
                                                +"All Routing"
                                            }
                                            option { value = "SOLO"; +"Solo" }
                                            option { value = "CONSENSUS"; +"Consensus" }
                                            option { value = "PARALLEL"; +"Parallel" }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Tasks table
                    div(classes = "card") {
                        div(classes = "card-body") {
                            // Loading indicator (shared by all HTMX requests)
                            div(classes = "htmx-indicator") {
                                id = "tasks-table-indicator"
                                attributes["role"] = "status"
                                attributes["aria-live"] = "assertive"
                                +"Loading..."
                            }

                            // Table container with HTMX
                            div {
                                id = "tasks-table-container"
                                attributes["hx-get"] = "/tasks/table"
                                attributes["hx-trigger"] = "load"
                                attributes["hx-swap"] = "innerHTML"
                                attributes["hx-indicator"] = "#tasks-table-indicator"

                                // Placeholder content while loading
                                div(classes = "text-center text-muted p-xl") {
                                    +"Loading tasks..."
                                }
                            }
                        }
                    }
                }
            }

            // JavaScript
            script(src = "/static/js/theme-toggle.js") {}
            script(src = "/static/js/navigation.js") {}
        }
    }.let { "<!DOCTYPE html>\n$it" }
}
