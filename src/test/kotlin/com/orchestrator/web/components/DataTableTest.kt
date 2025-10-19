package com.orchestrator.web.components

import kotlin.test.Test
import kotlin.test.assertTrue

class DataTableTest {

    @Test
    fun `sortable column adds htmx attributes and sort indicators`() {
        val columns = listOf(
            DataTable.Column(
                id = "id",
                title = "ID",
                sortable = true,
                sortLinks = DataTable.SortLinks(
                    ascending = "/tasks?sort=id&direction=asc",
                    descending = "/tasks?sort=id&direction=desc"
                )
            ),
            DataTable.Column(
                id = "name",
                title = "Name",
                sortable = true,
                sortLinks = DataTable.SortLinks(
                    ascending = "/tasks?sort=name&direction=asc",
                    descending = "/tasks?sort=name&direction=desc"
                )
            )
        )

        val rows = listOf(
            DataTable.row(id = "row-1") {
                cell("1", header = true)
                cell("Alpha Task")
            }
        )

        val html = DataTable.render(
            id = "tasks-table",
            columns = columns,
            rows = rows,
            sortState = DataTable.SortState(columnId = "id", direction = DataTable.SortDirection.ASC),
            pagination = Pagination.Config(
                page = 1,
                pageSize = 10,
                totalCount = 25,
                perPageOptions = listOf(10, 25, 50),
                makePageUrl = Pagination.PageUrlBuilder { page, size ->
                    "/tasks?page=$page&pageSize=$size"
                },
                hxTargetId = "tasks-table-body",
                hxIndicatorId = "tasks-table-indicator"
            )
        )

        assertTrue(html.contains("""id="tasks-table-body""""))
        assertTrue(html.contains("""hx-target="#tasks-table-body""""))
        assertTrue(html.contains("""hx-indicator="#tasks-table-indicator""""))
        assertTrue(html.contains("""aria-sort="ascending""""))
        assertTrue(html.contains(DataTableTestConstants.ascIndicator))
        assertTrue(html.contains(DataTableTestConstants.neutralIndicator))
        assertTrue(html.contains("""tabindex="0""""))
        assertTrue(html.contains("""Rows per page"""))
        assertTrue(html.contains("""aria-label="Page 1 of 3""""))
        assertTrue(html.contains("""data-state="active""""))
        assertTrue(html.contains("""hx-get="/tasks?page=1&amp;pageSize=25""""))
    }

    @Test
    fun `renders empty state when no rows provided`() {
        val columns = listOf(
            DataTable.Column(
                id = "id",
                title = "ID",
                sortable = false
            )
        )

        val html = DataTable.render(
            id = "empty-table",
            columns = columns,
            rows = emptyList(),
            emptyState = DataTable.EmptyState(
                message = "No tasks yet."
            )
        )

        assertTrue(html.contains("""class="data-table__empty""""))
        assertTrue(html.contains("No tasks yet."))
    }

    private object DataTableTestConstants {
        const val ascIndicator = "&uarr;"
        const val neutralIndicator = "&uarr;&darr;"
    }
}
