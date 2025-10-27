package com.orchestrator.web.pages

import com.orchestrator.web.rendering.PageLayout
import kotlinx.html.*
import kotlinx.html.stream.createHTML

/**
 * Files page for viewing and managing indexed files.
 *
 * Displays file browser with:
 * - Search and filter controls
 * - Sortable data table
 * - Pagination
 * - File detail view modal
 */
object FilesPage {

    /**
     * Render complete files list page
     */
    fun render(): String = createHTML().html {
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

            // HTMX
            script(src = "/static/js/htmx.min.js") {}
        }

        body(classes = "dashboard-layout") {
            with(PageLayout) {
                dashboardShell(
                    pageTitle = "Files",
                    currentPath = "/files"
                ) {
                    // Page header
                    div(classes = "page-header mb-lg") {
                        div(classes = "flex justify-between items-center") {
                            div {
                                h1(classes = "mt-0 mb-2") { +"File Browser" }
                                p(classes = "text-muted mb-0") {
                                    +"Browse and manage all indexed files"
                                }
                            }
                        }
                    }

                    // Search and filter controls
                    div(classes = "card mb-md") {
                        div(classes = "card-body") {
                            form {
                                id = "file-filters"
                                attributes["hx-get"] = "/files/table"
                                attributes["hx-target"] = "#files-table-container"
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
                                            placeholder = "Search files..."
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
                                            multiple = true

                                            option { value = "indexed"; +"Indexed" }
                                            option { value = "pending"; +"Pending" }
                                            option { value = "outdated"; +"Outdated" }
                                            option { value = "error"; +"Error" }
                                        }
                                    }

                                    // File type/extension filter
                                    div(classes = "form-group") {
                                        label {
                                            htmlFor = "extension"
                                            +"File Type"
                                        }
                                        input(type = InputType.text, name = "extension") {
                                            id = "extension"
                                            placeholder = "e.g., .kt, .ts, .py"
                                            classes = setOf("form-control")
                                        }
                                    }

                                    // Sort options
                                    div(classes = "form-group") {
                                        label {
                                            htmlFor = "sortBy"
                                            +"Sort By"
                                        }
                                        select {
                                            id = "sortBy"
                                            name = "sortBy"
                                            classes = setOf("form-control")

                                            option { value = "path"; +"File Path" }
                                            option { value = "status"; +"Status" }
                                            option { value = "extension"; +"Type" }
                                            option { value = "size"; +"Size" }
                                            option { value = "modified"; +"Modified" }
                                            option { value = "chunks"; +"Chunks" }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Files table
                    div(classes = "card") {
                        div(classes = "card-body") {
                            // Loading indicator
                            div(classes = "htmx-indicator") {
                                id = "files-table-indicator"
                                attributes["role"] = "status"
                                attributes["aria-live"] = "assertive"
                                +"Loading..."
                            }

                            // Table container
                            div {
                                id = "files-table-container"
                                attributes["hx-get"] = "/files/table"
                                attributes["hx-trigger"] = "revealed"
                                attributes["hx-swap"] = "innerHTML"
                                attributes["hx-indicator"] = "#files-table-indicator"

                                // Placeholder content while loading
                                div(classes = "text-center text-muted p-xl") {
                                    +"Loading files..."
                                }
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

            // Fallback: Ensure table loads even if hx-trigger:revealed doesn't fire
            script {
                unsafe {
                    +"""
                        (function() {
                            function ensureTableLoaded() {
                                const container = document.getElementById('files-table-container');
                                if (!container) return;

                                // Check if table is already loaded
                                if (container.querySelector('table')) return;

                                // If still showing loading text, trigger load
                                if (window.htmx && container.textContent.includes('Loading')) {
                                    htmx.ajax('GET', '/files/table', {
                                        target: container,
                                        swap: 'innerHTML',
                                        indicator: '#files-table-indicator'
                                    });
                                }
                            }

                            // Try on DOMContentLoaded
                            if (document.readyState === 'loading') {
                                document.addEventListener('DOMContentLoaded', ensureTableLoaded);
                            } else {
                                setTimeout(ensureTableLoaded, 100);
                            }

                            // Also try after a short delay as backup
                            setTimeout(ensureTableLoaded, 500);
                        })();
                    """.trimIndent()
                }
            }
        }
    }.let { "<!DOCTYPE html>\n$it" }
}
