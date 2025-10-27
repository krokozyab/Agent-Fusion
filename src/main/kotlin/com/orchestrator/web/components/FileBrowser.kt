package com.orchestrator.web.components

import com.orchestrator.web.dto.FileStateDTO
import com.orchestrator.web.rendering.Fragment
import com.orchestrator.web.utils.TimeFormatters
import java.time.Instant
import java.time.ZoneId
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span

/**
 * File browser component for viewing and managing indexed files
 * Features: search, filtering, sorting, pagination, and detail view
 */
object FileBrowser {

    data class Model(
        val path: String,
        val status: String,           // "indexed", "outdated", "error"
        val sizeBytes: Long,
        val lastModified: Instant?,
        val chunkCount: Int,
        val extension: String,
        val referenceInstant: Instant = Instant.now(),
        val zoneId: ZoneId = ZoneId.systemDefault(),
        val detailsUrl: String? = null
    )

    data class Config(
        val id: String = "file-browser",
        val rows: List<Model> = emptyList(),
        val sortBy: String = "path",
        val sortOrder: DataTable.SortDirection = DataTable.SortDirection.ASC,
        val page: Int = 1,
        val pageSize: Int = 50,
        val totalFiles: Int = 0,
        val hxTargetId: String = "${id}-body",
        val hxIndicatorId: String = "${id}-indicator",
        val hxSwapStrategy: String = "outerHTML",
        val searchQuery: String? = null,
        val statusFilter: Set<String> = emptySet(),
        val extensionFilter: Set<String> = emptySet()
    )

    fun render(config: Config): String = Fragment.render {
        fileBrowser(config)
    }

    fun FlowContent.fileBrowser(config: Config) {
        val columns = listOf(
            DataTable.Column(
                id = "path",
                title = "File Path",
                sortable = true,
                sortLinks = DataTable.SortLinks(
                    ascending = "/files/table?sortBy=path&sortOrder=asc",
                    descending = "/files/table?sortBy=path&sortOrder=desc",
                    unsorted = "/files/table"
                ),
                ariaLabel = "Sort by file path"
            ),
            DataTable.Column(
                id = "status",
                title = "Status",
                sortable = true,
                sortLinks = DataTable.SortLinks(
                    ascending = "/files/table?sortBy=status&sortOrder=asc",
                    descending = "/files/table?sortBy=status&sortOrder=desc",
                    unsorted = "/files/table"
                ),
                ariaLabel = "Sort by status"
            ),
            DataTable.Column(
                id = "extension",
                title = "Type",
                sortable = true,
                sortLinks = DataTable.SortLinks(
                    ascending = "/files/table?sortBy=extension&sortOrder=asc",
                    descending = "/files/table?sortBy=extension&sortOrder=desc",
                    unsorted = "/files/table"
                ),
                ariaLabel = "Sort by file type"
            ),
            DataTable.Column(
                id = "size",
                title = "Size",
                sortable = true,
                numeric = true,
                sortLinks = DataTable.SortLinks(
                    ascending = "/files/table?sortBy=size&sortOrder=asc",
                    descending = "/files/table?sortBy=size&sortOrder=desc",
                    unsorted = "/files/table"
                ),
                ariaLabel = "Sort by file size"
            ),
            DataTable.Column(
                id = "modified",
                title = "Modified",
                sortable = true,
                sortLinks = DataTable.SortLinks(
                    ascending = "/files/table?sortBy=modified&sortOrder=asc",
                    descending = "/files/table?sortBy=modified&sortOrder=desc",
                    unsorted = "/files/table"
                ),
                ariaLabel = "Sort by modification date"
            ),
            DataTable.Column(
                id = "chunks",
                title = "Chunks",
                sortable = true,
                numeric = true,
                sortLinks = DataTable.SortLinks(
                    ascending = "/files/table?sortBy=chunks&sortOrder=asc",
                    descending = "/files/table?sortBy=chunks&sortOrder=desc",
                    unsorted = "/files/table"
                ),
                ariaLabel = "Sort by chunk count"
            ),
            DataTable.Column(
                id = "actions",
                title = "Actions",
                sortable = false,
                ariaLabel = null
            )
        )

        val rows = config.rows.map { model ->
            FileRow.toRow(
                model = model,
                detailsUrl = model.detailsUrl ?: "/files/${model.path.hashCode()}"
            )
        }

        val sortState = DataTable.SortState(
            columnId = config.sortBy,
            direction = config.sortOrder
        )

        val pagination = Pagination.Config(
            page = config.page,
            pageSize = config.pageSize,
            totalCount = config.totalFiles.toLong(),
            makePageUrl = Pagination.PageUrlBuilder { page, pageSize ->
                "/files/table?page=$page&pageSize=$pageSize"
            },
            hxTargetId = config.hxTargetId,
            hxIndicatorId = config.hxIndicatorId
        )

        with(DataTable) {
            dataTable(
                id = config.id,
                columns = columns,
                rows = rows,
                sortState = sortState,
                emptyState = DataTable.EmptyState(
                    message = "No files found.",
                    description = {
                        span {
                            +"Try adjusting your search or filter criteria."
                        }
                    }
                ),
                pagination = pagination,
                hxTargetId = config.hxTargetId,
                hxIndicatorId = config.hxIndicatorId,
                hxSwapStrategy = config.hxSwapStrategy
            )
        }
    }
}
