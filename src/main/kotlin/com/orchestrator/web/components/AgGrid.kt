package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.script
import kotlinx.html.unsafe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ag-Grid wrapper component for server-side HTML generation
 * Generates ag-Grid-compatible data and initialization scripts
 */
object AgGrid {

    @Serializable
    data class ColumnDef(
        val field: String,
        val headerName: String,
        val sortable: Boolean = true,
        val filter: Boolean = true,
        val width: Int? = null,
        val type: String? = null,
        val cellRenderer: String? = null
    )

    data class GridConfig(
        val id: String,
        val containerClass: String = "ag-theme-quartz",
        val columnDefs: List<ColumnDef>,
        val rowData: List<Map<String, Any>>,
        val enablePagination: Boolean = true,
        val pageSize: Int = 50,
        val pageSizeOptions: List<Int> = listOf(10, 25, 50, 100, 200),
        val height: String = "600px",
        val suppressRowClickSelection: Boolean = false,
        val customOptions: Map<String, Any> = emptyMap()
    )

    /**
     * Render a complete ag-Grid table with data initialization
     */
    fun render(config: GridConfig): String = Fragment.render {
        agGrid(config)
    }

    /**
     * Render ag-Grid container and initialization script
     */
    fun FlowContent.agGrid(config: GridConfig) {
        // Container div
        div(classes = "${config.containerClass} ag-grid-container") {
            attributes["id"] = config.id
            attributes["style"] = "height: ${config.height}; width: 100%;"
            attributes["role"] = "grid"
        }

        // Initialization script
        script {
            unsafe {
                +"""
                (function() {
                    const maxRetries = 40;
                    let retries = 0;
                    const localScriptSrc = '/static/js/ag-grid-community.min.js';
                    const cdnScriptSrc = 'https://cdn.jsdelivr.net/npm/ag-grid-community@33.3.0/dist/ag-grid-community.min.js';
                    const styleHrefs = [
                        '/static/css/ag-grid.css',
                        '/static/css/ag-theme-quartz.css'
                    ];

                    function ensureAgGridStyles() {
                        styleHrefs.forEach(function(href) {
                            if (document.querySelector('link[data-ag-grid-style="' + href + '"]')) {
                                return;
                            }
                            if (document.querySelector('link[href="' + href + '"]')) {
                                return;
                            }
                            const link = document.createElement('link');
                            link.rel = 'stylesheet';
                            link.href = href;
                            link.dataset.agGridStyle = href;
                            document.head.appendChild(link);
                        });
                    }

                    function ensureAgGridScript() {
                        if (typeof agGrid !== 'undefined' && typeof agGrid.createGrid === 'function') {
                            window.__agGridScriptReady = true;
                            window.__agGridScriptLoading = false;
                            return;
                        }

                        if (window.__agGridScriptLoading) {
                            return;
                        }

                        const existingScript = document.querySelector('script[data-ag-grid-source]');
                        if (existingScript) {
                            window.__agGridScriptLoading = true;
                            return;
                        }

                        window.__agGridScriptLoading = true;

                        function markReady() {
                            window.__agGridScriptReady = true;
                            window.__agGridScriptLoading = false;
                        }

                        function loadFallback() {
                            const fallback = document.createElement('script');
                            fallback.src = cdnScriptSrc;
                            fallback.defer = true;
                            fallback.dataset.agGridSource = 'cdn';
                            fallback.onload = markReady;
                            fallback.onerror = function() {
                                console.error('Failed to load ag-Grid from CDN');
                                window.__agGridScriptLoading = false;
                            };
                            document.head.appendChild(fallback);
                        }

                        const script = document.createElement('script');
                        script.src = localScriptSrc;
                        script.defer = true;
                        script.dataset.agGridSource = 'local';
                        script.onload = markReady;
                        script.onerror = function() {
                            console.warn('Failed to load local ag-Grid bundle, falling back to CDN');
                            loadFallback();
                        };
                        document.head.appendChild(script);
                    }

                    function initializeGrid() {
                        if (typeof agGrid === 'undefined' || typeof agGrid.createGrid === 'undefined') {
                            ensureAgGridScript();
                            retries++;
                            if (retries < maxRetries) {
                                setTimeout(initializeGrid, 100);
                            } else {
                                console.error('ag-Grid failed to load after ' + (maxRetries * 100) + 'ms');
                                console.error('agGrid object:', typeof window.agGrid);
                                console.error('agGrid.createGrid:', typeof window.agGrid?.createGrid);
                            }
                            return;
                        }

                        ensureAgGridStyles();

                        // Column definitions
                        const columnDefs = ${Json.encodeToString(config.columnDefs)};

                        // Row data - manually constructed to avoid serialization issues
                        const rowData = ${buildRowDataJson(config.rowData)};

                        // Grid options
                        const gridOptions = {
                            columnDefs: columnDefs,
                            rowData: rowData,
                            pagination: ${config.enablePagination},
                            paginationPageSize: ${config.pageSize},
                            paginationPageSizeSelector: ${Json.encodeToString(config.pageSizeOptions)},
                            suppressRowClickSelection: ${config.suppressRowClickSelection},
                            ${renderCustomOptions(config.customOptions)}
                        };

                        // Create grid
                        const container = document.getElementById('${config.id}');
                        if (container) {
                            try {
                                const gridApi = agGrid.createGrid(container, gridOptions);
                                // Store gridApi on container for later access
                                container._gridApi = gridApi;
                                console.log('ag-Grid initialized for container ${config.id}');
                            } catch (error) {
                                console.error('Failed to initialize ag-Grid for ${config.id}:', error);
                            }
                        } else {
                            console.error('Container with id ${config.id} not found');
                        }
                    }

                    function boot() {
                        ensureAgGridStyles();
                        ensureAgGridScript();
                        initializeGrid();
                    }

                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', boot);
                    } else {
                        boot();
                    }
                })();
                """.trimIndent()
            }
        }
    }

    /**
     * Convert a DataTable.Row to ag-Grid row data format
     */
    fun dataTableRowToAgGridRow(row: DataTable.Row, columnFields: List<String>): Map<String, Any> {
        val rowMap = mutableMapOf<String, Any>()

        // Add standard row attributes
        if (row.id != null) {
            rowMap["_id"] = row.id
        }
        if (row.ariaLabel != null) {
            rowMap["_ariaLabel"] = row.ariaLabel
        }
        if (row.href != null) {
            rowMap["_href"] = row.href
        }

        // Extract cell data based on column field names
        row.cells.forEachIndexed { index, cell ->
            if (index < columnFields.size) {
                rowMap[columnFields[index]] = cell.content
            }
        }

        return rowMap
    }

    /**
     * Build row data JSON string
     */
    private fun buildRowDataJson(rowData: List<Map<String, Any>>): String {
        if (rowData.isEmpty()) return "[]"

        val rows = rowData.map { row ->
            val fields = row.entries.joinToString(", ") { (key, value) ->
                val jsonValue = when (value) {
                    is String -> "\"${escapeJsonString(value)}\""
                    is Number -> value.toString()
                    is Boolean -> value.toString()
                    else -> "\"${escapeJsonString(value.toString())}\""
                }
                "\"$key\": $jsonValue"
            }
            "{$fields}"
        }
        return "[${rows.joinToString(", ")}]"
    }

    /**
     * Escape special characters in JSON strings
     */
    private fun escapeJsonString(s: String): String {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * Render custom options as JavaScript object literal
     */
    private fun renderCustomOptions(options: Map<String, Any>): String {
        if (options.isEmpty()) return ""

        return options.entries.joinToString(", ") { (key, value) ->
            when (value) {
                is String -> "$key: '$value'"
                is Number -> "$key: $value"
                is Boolean -> "$key: $value"
                is List<*> -> "$key: ${Json.encodeToString(value)}"
                else -> "$key: ${Json.encodeToString(value.toString())}"
            }
        }
    }
}
