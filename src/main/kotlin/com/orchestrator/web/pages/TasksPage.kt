package com.orchestrator.web.pages

import com.orchestrator.web.components.AgGrid
import com.orchestrator.web.rendering.PageLayout
import kotlinx.html.*
import kotlinx.html.stream.createHTML

object TasksPage {

    data class GridData(
        val columnDefs: List<AgGrid.ColumnDef> = emptyList(),
        val rowData: List<Map<String, Any>> = emptyList(),
        val pageSize: Int = 50,
        val pageSizeOptions: List<Int> = listOf(25, 50, 100, 200)
    )

    fun render(gridData: GridData = GridData()): String = createHTML().html {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title("Tasks - Orchestrator")

            // Match Files page styling for consistency
            link(rel = "stylesheet", href = "/static/css/base.css")
            link(rel = "stylesheet", href = "/static/css/bootstrap-litera.min.css")
            link(rel = "stylesheet", href = "/static/css/orchestrator.css?v=20241104")
            link(rel = "stylesheet", href = "/static/css/modal.css")
            link(rel = "stylesheet", href = "/static/css/sse-status.css")
            link(rel = "stylesheet", href = "/static/css/animations.css")
            link(rel = "stylesheet", href = "/static/css/styles.css")

            link(rel = "stylesheet", href = "/static/css/ag-grid.css")
            link(rel = "stylesheet", href = "/static/css/ag-theme-quartz.css")

            script(src = "/static/js/htmx.min.js") {}
            script(src = "/static/js/htmx-sse.min.js") {}
            script(src = "/static/js/sse-status.js") {}
            script(src = "/static/js/ag-grid-community.min.js") {}
        }

        body(classes = "dashboard-layout") {
            attributes["hx-ext"] = "sse"
            attributes["data-sse-url"] = "/sse/tasks"

            with(PageLayout) {
                dashboardShell(
                    pageTitle = "Tasks",
                    currentPath = "/tasks"
                ) {
                    div(classes = "card") {
                        div(classes = "card-body") {
                            div(classes = "flex flex-wrap gap-md justify-between items-center mb-md") {
                                h2(classes = "mt-0 mb-0") { +"Tasks" }
                            }

                            script(src = "/static/js/task-grid.js") {}

                            with(AgGrid) {
                                agGrid(
                                    AgGrid.GridConfig(
                                        id = "tasks-grid",
                                        columnDefs = gridData.columnDefs,
                                        rowData = gridData.rowData,
                                        enablePagination = true,
                                        pageSize = gridData.pageSize,
                                        pageSizeOptions = gridData.pageSizeOptions,
                                        height = "70vh",
                                        suppressRowClickSelection = false,
                                        customOptions = mapOf(
                                            "defaultColDef" to mapOf(
                                                "sortable" to true,
                                                "filter" to true,
                                                "floatingFilter" to true,
                                                "resizable" to true,
                                                "flex" to 1
                                            ),
                                            "rowSelection" to "single",
                                            "animateRows" to true,
                                            "paginationAutoPageSize" to false
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }

            div(classes = "modal") {
                id = "modal-container"
                attributes["role"] = "dialog"
                attributes["aria-modal"] = "true"
                attributes["aria-hidden"] = "true"
            }

            div {
                id = "tasks-grid-event-updated"
                attributes["style"] = "display:none;"
                attributes["sse-swap"] = "taskUpdated"
                attributes["hx-swap"] = "innerHTML"
            }
            div {
                id = "tasks-grid-event-created"
                attributes["style"] = "display:none;"
                attributes["sse-swap"] = "taskCreated"
                attributes["hx-swap"] = "innerHTML"
            }
            div {
                id = "tasks-grid-event-deleted"
                attributes["style"] = "display:none;"
                attributes["sse-swap"] = "taskDeleted"
                attributes["hx-swap"] = "innerHTML"
            }

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

            script(src = "/static/js/theme-toggle.js") {}
            script(src = "/static/js/navigation.js") {}
            script(src = "/static/js/modal.js") {}
            script(src = "/static/js/task-updates.js") {}
        }
    }.let { "<!DOCTYPE html>\n$it" }
}
