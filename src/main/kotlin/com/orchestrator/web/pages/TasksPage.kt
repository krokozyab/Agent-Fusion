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
            link(rel = "stylesheet", href = "/static/css/bootstrap-litera.min.css")
            link(rel = "stylesheet", href = "/static/css/orchestrator.css")
            link(rel = "stylesheet", href = "/static/css/modal.css")
            link(rel = "stylesheet", href = "/static/css/sse-status.css")
            link(rel = "stylesheet", href = "/static/css/animations.css")
            link(rel = "stylesheet", href = "/static/css/styles.css")

            link(rel = "stylesheet", href = "/static/css/ag-grid.css")
            link(rel = "stylesheet", href = "/static/css/ag-theme-quartz.css")

            script(src = "/static/js/htmx.min.js") {}
            script(src = "/static/js/ag-grid-community.min.js") {}
        }

        body(classes = "dashboard-layout") {
            attributes["hx-ext"] = "sse"
            attributes["sse-connect"] = "/sse/tasks"

            with(PageLayout) {
                dashboardShell(
                    pageTitle = "Tasks",
                    currentPath = "/tasks"
                ) {
                    div(classes = "card") {
                        div(classes = "card-body") {
                            div(classes = "flex flex-wrap gap-md justify-between items-center mb-md") {
                                div {
                                    h1(classes = "mt-0 mb-1") { +"Tasks" }
                                    p(classes = "text-muted mb-0") {
                                        +"Use the column headers to sort or filter."
                                    }
                                }
                                div(classes = "form-group mb-0") {
                                    label {
                                        htmlFor = "tasks-quick-filter"
                                        +"Quick Filter"
                                    }
                                    input(type = InputType.search) {
                                        id = "tasks-quick-filter"
                                        placeholder = "Filter tasks..."
                                        attributes["aria-label"] = "Filter tasks"
                                        classes = setOf("form-control")
                                        attributes["style"] = "min-width: 220px;"
                                    }
                                }
                            }

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

            div {
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
            script(src = "/static/js/sse-status.js") {}
            script(src = "/static/js/task-updates.js") {}
            script(src = "/static/js/task-grid.js") {}

            script {
                unsafe {
                    +"""
                        (function() {
                            function bindQuickFilter() {
                                const input = document.getElementById('tasks-quick-filter');
                                const container = document.getElementById('tasks-grid');
                                if (!input || !container) return;

                                const applyFilter = function(value) {
                                    if (!container._gridApi) return;
                                    if (typeof container._gridApi.setGridOption === 'function') {
                                        container._gridApi.setGridOption('quickFilterText', value);
                                    } else if (typeof container._gridApi.setQuickFilter === 'function') {
                                        container._gridApi.setQuickFilter(value);
                                    }
                                };

                                input.addEventListener('input', function(event) {
                                    applyFilter(event.target.value || '');
                                });

                                if (container._gridApi) {
                                    applyFilter(input.value || '');
                                }
                            }

                            if (document.readyState === 'loading') {
                                document.addEventListener('DOMContentLoaded', bindQuickFilter, { once: true });
                            } else {
                                bindQuickFilter();
                            }
                        })();
                    """.trimIndent()
                }
            }
        }
    }.let { "<!DOCTYPE html>\n$it" }
}
