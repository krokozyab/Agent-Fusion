package com.orchestrator.web.components

import com.orchestrator.web.rendering.Fragment
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.TABLE
import kotlinx.html.TBODY
import kotlinx.html.TH
import kotlinx.html.THEAD
import kotlinx.html.TR
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.th
import kotlinx.html.tr
import kotlinx.html.unsafe

object DataTable {
    enum class SortDirection {
        ASC,
        DESC,
        NONE;

        fun toggle(): SortDirection = when (this) {
            ASC -> DESC
            DESC -> NONE
            NONE -> ASC
        }
    }

    data class SortState(
        val columnId: String,
        val direction: SortDirection
    )

    data class Column(
        val id: String,
        val title: String,
        val sortable: Boolean = false,
        val sortLinks: SortLinks? = null,
        val ariaLabel: String? = null,
        val numeric: Boolean = false
    ) {
        init {
            if (sortable && sortLinks == null) {
                error("Sortable column '$id' must provide sortLinks")
            }
        }
    }

    data class SortLinks(
        val ascending: String,
        val descending: String,
        val unsorted: String
    ) {
        fun urlFor(direction: SortDirection): String =
            when (direction) {
                SortDirection.ASC -> ascending
                SortDirection.DESC -> descending
                SortDirection.NONE -> unsorted
            }
    }

    data class Row(
        val id: String? = null,
        val ariaLabel: String? = null,
        val href: String? = null,
        val cells: List<Cell>,
        val attributes: Map<String, String> = emptyMap()
    )

    data class Cell(
        val header: Boolean = false,
        val numeric: Boolean = false,
        val content: String,
        val raw: Boolean = false
    )

    class RowBuilder {
        private val cells = mutableListOf<Cell>()
        private val attributes = linkedMapOf<String, String>()
        var href: String? = null

        fun cell(
            text: String,
            header: Boolean = false,
            numeric: Boolean = false
        ) {
            cells += Cell(header = header, numeric = numeric, content = text, raw = false)
        }

        fun cell(
            header: Boolean = false,
            numeric: Boolean = false,
            block: FlowContent.() -> Unit
        ) {
            cells += Cell(
                header = header,
                numeric = numeric,
                content = renderFragmentContent(block),
                raw = true
            )
        }

        fun rawCell(
            content: String,
            header: Boolean = false,
            numeric: Boolean = false
        ) {
            cells += Cell(header = header, numeric = numeric, content = content, raw = true)
        }

        fun attribute(name: String, value: String) {
            attributes[name] = value
        }

        fun href(link: String) {
            href = link
        }

        internal fun build(): List<Cell> = cells.toList()
        internal fun buildAttributes(): Map<String, String> = attributes.toMap()
    }

    data class EmptyState(
        val message: String = "No results found.",
        val description: (FlowContent.() -> Unit)? = null
    )

    fun row(
        id: String? = null,
        ariaLabel: String? = null,
        href: String? = null,
        build: RowBuilder.() -> Unit
    ): Row {
        val rowBuilder = RowBuilder().apply(build)
        val builderHref = rowBuilder.href ?: href
        return Row(
            id = id,
            ariaLabel = ariaLabel,
            href = builderHref,
            cells = rowBuilder.build(),
            attributes = rowBuilder.buildAttributes()
        )
    }

    fun render(
        id: String,
        columns: List<Column>,
        rows: List<Row>,
        sortState: SortState? = null,
        emptyState: EmptyState = EmptyState(),
        pagination: Pagination.Config? = null,
        hxTargetId: String = "${id}-body",
        hxIndicatorId: String = "${id}-indicator",
        hxSwapStrategy: String = "outerHTML"
    ): String = Fragment.render {
        dataTable(
            id = id,
            columns = columns,
            rows = rows,
            sortState = sortState,
            emptyState = emptyState,
            pagination = pagination,
            hxTargetId = hxTargetId,
            hxIndicatorId = hxIndicatorId,
            hxSwapStrategy = hxSwapStrategy
        )
    }

    fun FlowContent.dataTable(
        id: String,
        columns: List<Column>,
        rows: List<Row>,
        sortState: SortState? = null,
        emptyState: EmptyState = EmptyState(),
        pagination: Pagination.Config? = null,
        hxTargetId: String = "${id}-body",
        hxIndicatorId: String = "${id}-indicator",
        hxSwapStrategy: String = "outerHTML"
    ) {
        require(columns.isNotEmpty()) { "DataTable requires at least one column" }

        val targetSelector = "#$hxTargetId"
        val indicatorSelector = "#$hxIndicatorId"

        div(classes = "data-table__container") {
            attributes["role"] = "region"
            attributes["aria-live"] = "polite"
            attributes["aria-busy"] = "false"

            div(classes = "data-table__indicator htmx-indicator") {
                attributes["id"] = hxIndicatorId
                attributes["role"] = "status"
                attributes["aria-live"] = "assertive"
                attributes["aria-hidden"] = "true"
                +"Loading..."
            }

            div(classes = "data-table__scroll") {
                attributes["tabindex"] = "0"
                table(classes = "data-table") {
                    attributes["id"] = id
                    attributes["role"] = "grid"
                    header(
                        columns = columns,
                        sortState = sortState,
                        targetSelector = targetSelector,
                        indicatorSelector = indicatorSelector,
                        hxSwapStrategy = hxSwapStrategy
                    )
                    body(
                        columns = columns,
                        rows = rows,
                        emptyState = emptyState,
                        bodyId = hxTargetId
                    )
                }
            }

            pagination?.let { config ->
                with(Pagination) {
                    this@div.controls(
                        config.copy(
                            hxTargetId = hxTargetId,
                            hxIndicatorId = hxIndicatorId,
                            hxSwap = hxSwapStrategy
                        )
                    )
                }
            }
        }
    }

    private fun TABLE.header(
        columns: List<Column>,
        sortState: SortState?,
        targetSelector: String,
        indicatorSelector: String,
        hxSwapStrategy: String
    ) {
        thead(classes = "data-table__head") {
            attributes["role"] = "rowgroup"
            tr {
                attributes["role"] = "row"
                columns.forEach { column ->
                    columnHeader(
                        column = column,
                        sortState = sortState,
                        targetSelector = targetSelector,
                        indicatorSelector = indicatorSelector,
                        hxSwapStrategy = hxSwapStrategy
                    )
                }
            }
        }
    }

    private fun TR.columnHeader(
        column: Column,
        sortState: SortState?,
        targetSelector: String,
        indicatorSelector: String,
        hxSwapStrategy: String
    ) {
        th {
            attributes["role"] = "columnheader"
            attributes["scope"] = "col"
            if (column.numeric) {
                attributes["data-type"] = "numeric"
            }

            val currentSortDirection =
                if (sortState?.columnId == column.id) sortState.direction else null

            val ariaSort = when (currentSortDirection) {
                SortDirection.ASC -> "ascending"
                SortDirection.DESC -> "descending"
                SortDirection.NONE -> "none"
                null -> "none"
            }
            attributes["aria-sort"] = ariaSort

            if (column.sortable) {
                sortableHeaderButton(
                    column = column,
                    currentDirection = currentSortDirection,
                    targetSelector = targetSelector,
                    indicatorSelector = indicatorSelector,
                    hxSwapStrategy = hxSwapStrategy
                )
            } else {
                span(classes = "data-table__header-label") {
                    +(column.title)
                }
            }
        }
    }

    private fun TH.sortableHeaderButton(
        column: Column,
        currentDirection: SortDirection?,
        targetSelector: String,
        indicatorSelector: String,
        hxSwapStrategy: String
    ) {
        val nextDirection = currentDirection?.toggle() ?: SortDirection.ASC
        button(classes = "data-table__sort-button") {
            type = ButtonType.button
            attributes["hx-get"] = column.sortLinks!!.urlFor(nextDirection)
            attributes["hx-target"] = targetSelector
            attributes["hx-swap"] = hxSwapStrategy
            attributes["hx-indicator"] = indicatorSelector
            attributes["data-next-sort"] = nextDirection.name.lowercase()
            attributes["tabindex"] = "0"
            attributes["aria-label"] = column.ariaLabel ?: "Sort by ${column.title}"

            span(classes = "data-table__header-label") {
                +column.title
            }

            span(classes = "data-table__sort-indicator") {
                unsafe {
                    when (currentDirection) {
                        SortDirection.ASC -> +SORT_ASC_SYMBOL
                        SortDirection.DESC -> +SORT_DESC_SYMBOL
                        SortDirection.NONE -> +SORT_NEUTRAL_SYMBOL
                        null -> +SORT_NEUTRAL_SYMBOL
                    }
                }
            }
        }
    }

    private fun TABLE.body(
        columns: List<Column>,
        rows: List<Row>,
        emptyState: EmptyState,
        bodyId: String
    ) {
        tbody(classes = "data-table__body") {
            attributes["id"] = bodyId
            attributes["role"] = "rowgroup"
            // SSE swap for new tasks (prepend to top)
            attributes["sse-swap"] = "taskCreated swap:afterbegin"
            if (rows.isEmpty()) {
                emptyRow(columns = columns, emptyState = emptyState)
            } else {
                rows.forEach { row ->
                    renderRow(row, columns.size)
                }
            }
        }
    }

    private fun TBODY.renderRow(row: Row, columnCount: Int) {
        tr(classes = "data-table__row") {
            attributes["role"] = "row"
            attributes["tabindex"] = "0"
            row.id?.let { attributes["id"] = it }
            row.ariaLabel?.let { attributes["aria-label"] = it }
            row.href?.let { attributes["data-href"] = it }
            row.attributes.forEach { (key, value) ->
                attributes[key] = value
            }

            if (row.cells.isEmpty()) {
                td {
                    attributes["colspan"] = columnCount.toString()
                    +""
                }
            } else {
                row.cells.forEach { cell ->
                    if (cell.header) {
                        th(classes = "data-table__cell") {
                            attributes["scope"] = "row"
                            attributes["role"] = "rowheader"
                            if (cell.numeric) {
                                attributes["data-type"] = "numeric"
                            }
                            if (cell.raw) {
                                unsafe { +cell.content }
                            } else {
                                +cell.content
                            }
                        }
                    } else {
                        td(classes = "data-table__cell") {
                            attributes["role"] = "gridcell"
                            if (cell.numeric) {
                                attributes["data-type"] = "numeric"
                            }
                            if (cell.raw) {
                                unsafe { +cell.content }
                            } else {
                                +cell.content
                            }
                        }
                    }
                }
            }
        }
    }

    private fun TBODY.emptyRow(
        columns: List<Column>,
        emptyState: EmptyState
    ) {
        tr(classes = "data-table__empty-row") {
            attributes["role"] = "row"
            td {
                attributes["role"] = "gridcell"
                attributes["colspan"] = columns.size.toString()
                attributes["class"] = "data-table__empty"
                span { +emptyState.message }
                emptyState.description?.let { desc ->
                    div(classes = "data-table__empty-description") {
                        desc()
                    }
                }
            }
        }
    }

    private const val SORT_ASC_SYMBOL = "&uarr;"
    private const val SORT_DESC_SYMBOL = "&darr;"
    private const val SORT_NEUTRAL_SYMBOL = "&uarr;&darr;"

    private fun renderFragmentContent(block: FlowContent.() -> Unit): String {
        val rendered = Fragment.render(block).trim()
        if (!rendered.startsWith("<div")) {
            return rendered
        }

        val closingIndex = rendered.indexOf('>')
        if (closingIndex == -1 || !rendered.endsWith("</div>")) {
            return rendered
        }

        val inner = rendered.substring(closingIndex + 1, rendered.length - "</div>".length)
        return inner.trim()
    }
}
