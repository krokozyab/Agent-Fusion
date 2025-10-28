package com.orchestrator.web.pages

import com.orchestrator.web.components.AgGrid
import com.orchestrator.web.rendering.PageLayout
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * Files page for viewing and managing indexed files.
 *
 * Displays file browser with:
 * - Search and filter controls
 * - ag-Grid powered data table
 * - Pagination
 * - File detail view modal
 */
object FilesPage {

    /**
     * Data class for ag-Grid configuration and row data
     */
    data class GridData(
        val columnDefs: List<AgGrid.ColumnDef> = defaultColumns(),
        val rowData: List<Map<String, Any>> = emptyList()
    ) {
        companion object {
            fun defaultColumns(): List<AgGrid.ColumnDef> = listOf(
                AgGrid.ColumnDef(
                    field = "path",
                    headerName = "File Path",
                    width = 300,
                    sortable = true,
                    filter = true
                ),
                AgGrid.ColumnDef(
                    field = "status",
                    headerName = "Status",
                    width = 120,
                    sortable = true,
                    filter = true
                ),
                AgGrid.ColumnDef(
                    field = "extension",
                    headerName = "Type",
                    width = 100,
                    sortable = true,
                    filter = true
                ),
                AgGrid.ColumnDef(
                    field = "sizeBytes",
                    headerName = "Size",
                    width = 100,
                    sortable = true,
                    filter = true,
                    type = "numericColumn"
                ),
                AgGrid.ColumnDef(
                    field = "lastModified",
                    headerName = "Modified",
                    width = 150,
                    sortable = true,
                    filter = true
                ),
                AgGrid.ColumnDef(
                    field = "chunkCount",
                    headerName = "Chunks",
                    width = 100,
                    sortable = true,
                    filter = true,
                    type = "numericColumn"
                )
            )
        }
    }

    /**
     * Render complete files list page
     */
    fun render(gridData: GridData = GridData()): String = createHTML().html {
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title("Files - Orchestrator")

            // CSS
            link(rel = "stylesheet", href = "/static/css/base.css")
            link(rel = "stylesheet", href = "/static/css/orchestrator.css")
            link(rel = "stylesheet", href = "/static/css/dark-mode.css")
            link(rel = "stylesheet", href = "/static/css/modal.css")
            link(rel = "stylesheet", href = "/static/css/sse-status.css")
            link(rel = "stylesheet", href = "/static/css/animations.css")

            // ag-Grid CSS
            link(rel = "stylesheet", href = "/static/css/ag-grid.css")
            link(rel = "stylesheet", href = "/static/css/ag-theme-quartz.css")

            // HTMX
            script(src = "/static/js/htmx.min.js") {}

            // ag-Grid - Load early
            script(src = "/static/js/ag-grid-community.min.js") {}
        }

        body(classes = "dashboard-layout") {
            with(PageLayout) {
                dashboardShell(
                    pageTitle = "Files",
                    currentPath = "/files"
                ) {
                    // Files table
                    div(classes = "card") {
                        div(classes = "card-body") {
                            div(classes = "flex flex-wrap gap-md justify-between items-center mb-md") {
                                div {
                                    h2(classes = "mt-0 mb-1") { +"Indexed Files" }
                                    p(classes = "text-muted mb-0") {
                                        +"Use the column headers to sort or filter."
                                    }
                                }
                                div(classes = "form-group mb-0") {
                                    label {
                                        htmlFor = "files-quick-filter"
                                        +"Quick Filter"
                                    }
                                    input(type = InputType.search) {
                                        id = "files-quick-filter"
                                        placeholder = "Filter rows..."
                                        attributes["aria-label"] = "Filter files"
                                        classes = setOf("form-control")
                                        attributes["style"] = "min-width: 220px;"
                                    }
                                }
                            }

                            with(AgGrid) {
                                agGrid(
                                    AgGrid.GridConfig(
                                        id = "files-grid",
                                        columnDefs = gridData.columnDefs,
                                        rowData = gridData.rowData,
                                        height = "70vh",
                                        pageSize = 100,
                                        pageSizeOptions = listOf(25, 50, 100, 200, 500),
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
                                            "paginationAutoPageSize" to false,
                                            "suppressCsvExport" to false
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Modal container for file details
            div {
                id = "modal-container"
                attributes["role"] = "dialog"
                attributes["aria-modal"] = "true"
                attributes["aria-hidden"] = "true"
                // Empty by default, populated by HTMX
            }

            // JavaScript
            script(src = "/static/js/theme-toggle.js") {}
            script(src = "/static/js/navigation.js") {}
            script(src = "/static/js/modal.js") {}

            script {
                unsafe {
                    +"""
                        (function() {
                            function formatFileSize(bytes) {
                                if (bytes === null || bytes === undefined || isNaN(bytes)) return '0 B';
                                const units = ['B', 'KB', 'MB', 'GB', 'TB'];
                                let value = Math.max(0, Number(bytes));
                                let unitIndex = 0;
                                while (value >= 1024 && unitIndex < units.length - 1) {
                                    value /= 1024;
                                    unitIndex++;
                                }
                                const formatted = unitIndex === 0 ? value.toString() : value.toFixed(1);
                                return formatted + ' ' + units[unitIndex];
                            }

                            function bindGridEnhancements() {
                                const input = document.getElementById('files-quick-filter');
                                const container = document.getElementById('files-grid');
                                if (!input || !container) {
                                    return;
                                }

                                const applyFilter = (value) => {
                                    if (container._gridApi) {
                                        container._gridApi.setGridOption('quickFilterText', value || '');
                                    }
                                };

                                const handleGridReady = (event) => {
                                    const api = event.detail?.gridApi;
                                    const columnApi = event.detail?.columnApi;
                                    if (api && columnApi) {
                                        const sizeColumn = columnApi.getColumn('sizeBytes');
                                        if (sizeColumn) {
                                            sizeColumn.getColDef().valueFormatter = (params) => formatFileSize(params.value);
                                            api.refreshCells({ columns: ['sizeBytes'] });
                                        }
                                        api.setGridOption('quickFilterText', input.value || '');
                                    }
                                };

                                container.addEventListener('ag-grid:ready', handleGridReady, { once: true });

                                input.addEventListener('input', (event) => {
                                    applyFilter(event.target.value);
                                });

                                if (container._gridApi) {
                                    applyFilter(input.value);
                                }
                            }

                            if (document.readyState === 'loading') {
                                document.addEventListener('DOMContentLoaded', bindGridEnhancements, { once: true });
                            } else {
                                bindGridEnhancements();
                            }
                        })();
                    """.trimIndent()
                }
            }
        }
    }.let { "<!DOCTYPE html>\n$it" }
}
