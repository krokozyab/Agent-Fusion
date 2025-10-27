package com.orchestrator.web.routes

import com.orchestrator.context.ContextRepository
import com.orchestrator.context.domain.FileState
import com.orchestrator.web.components.DataTable
import com.orchestrator.web.components.FileDetail
import com.orchestrator.web.components.FileBrowser
import com.orchestrator.web.components.FileRow
import com.orchestrator.web.components.Pagination
import com.orchestrator.web.pages.FilesPage
import com.orchestrator.web.rendering.Fragment
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.span
import java.time.Instant
import java.time.ZoneId

/**
 * Query parameters for file filtering, sorting, and pagination
 */
data class FileQueryParams(
    val search: String? = null,                  // Search in file name/path
    val status: Set<String> = emptySet(),       // Filter by status (indexed, outdated, error)
    val extensions: Set<String> = emptySet(),   // Filter by file extension
    val sortBy: String = "path",
    val sortOrder: DataTable.SortDirection = DataTable.SortDirection.ASC,
    val page: Int = 1,
    val pageSize: Int = 50
) {
    init {
        require(page >= 1) { "page must be >= 1" }
        require(pageSize in 1..200) { "pageSize must be between 1 and 200" }
    }
}

/**
 * Parse query parameters into FileQueryParams with validation
 */
fun Parameters.toFileQueryParams(): FileQueryParams {
    val search = this["search"]?.take(100)

    val status = this.getAll("status")
        ?.filter { it.isNotEmpty() }
        ?.toSet() ?: emptySet()

    val extensions = this.getAll("extension")
        ?.filter { it.isNotEmpty() }
        ?.map { if (it.startsWith(".")) it.substring(1) else it }
        ?.toSet() ?: emptySet()

    val sortBy = this["sortBy"] ?: "path"
    val sortOrder = when (this["sortOrder"]?.lowercase()) {
        "asc" -> DataTable.SortDirection.ASC
        "desc" -> DataTable.SortDirection.DESC
        "none" -> DataTable.SortDirection.NONE
        else -> DataTable.SortDirection.ASC
    }

    val page = this["page"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
    val pageSize = this["pageSize"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50

    return FileQueryParams(
        search = search,
        status = status,
        extensions = extensions,
        sortBy = sortBy,
        sortOrder = sortOrder,
        page = page,
        pageSize = pageSize
    )
}

fun Route.fileRoutes() {
    /**
     * GET /files - Main files list page
     */
    get("/files") {
        val html = FilesPage.render()
        call.respondText(html, io.ktor.http.ContentType.Text.Html)
    }

    /**
     * GET /files/table - Returns HTML fragment for file table
     *
     * Query parameters:
     * - search: text search in file path/name (100 char max)
     * - status: filter by status (indexed, outdated, error, pending)
     * - extension: filter by file extension
     * - sortBy: column name (path, status, extension, size, modified, chunks)
     * - sortOrder: asc|desc (default: asc)
     * - page: page number (default: 1)
     * - pageSize: items per page (default: 50, max: 200)
     *
     * Returns:
     * - HTML table fragment for HTMX swap
     */
    get("/files/table") {
        val params = try {
            call.request.queryParameters.toFileQueryParams()
        } catch (e: IllegalArgumentException) {
            call.respondText(
                status = HttpStatusCode.BadRequest,
                text = "Invalid query parameters: ${e.message}"
            )
            return@get
        }

        // Query files with filters
        val (files, totalCount) = queryFiles(params)

        // Build current URL for sorting/pagination
        val baseUrl = "/files/table"

        // Render complete table
        val html = renderCompleteFileTable(
            files = files,
            params = params,
            currentPage = params.page,
            totalCount = totalCount,
            baseUrl = baseUrl
        )

        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.response.headers.append("X-Total-Count", totalCount.toString())

        call.respondText(html, ContentType.Text.Html)
    }

    /**
     * GET /files/{filePath}/detail - Returns file detail view modal
     *
     * Returns:
     * - HTML modal content for file details with chunk list
     */
    get("/files/{filePath}/detail") {
        val filePath = call.parameters["filePath"]
        if (filePath == null) {
            call.respondText(
                status = HttpStatusCode.BadRequest,
                text = "File path required"
            )
            return@get
        }

        // Fetch file from repository
        val fileState = ContextRepository.listAllFiles()
            .find { it.relativePath == filePath || it.id.toString() == filePath }

        if (fileState == null) {
            call.respondText(
                status = HttpStatusCode.NotFound,
                text = "File not found"
            )
            return@get
        }

        // Fetch file artifacts (chunks)
        val artifacts = ContextRepository.fetchFileArtifactsByPath(fileState.relativePath)

        // Convert chunks to model objects
        val chunkModels = artifacts?.chunks?.mapIndexed { index, chunkArtifact ->
            FileDetail.ChunkInfo(
                id = chunkArtifact.chunk.id,
                ordinal = index,
                kind = chunkArtifact.chunk.kind.name,
                startLine = chunkArtifact.chunk.startLine,
                endLine = chunkArtifact.chunk.endLine,
                tokenCount = chunkArtifact.chunk.tokenEstimate,
                content = chunkArtifact.chunk.content,
                summary = chunkArtifact.chunk.summary
            )
        } ?: emptyList()

        val fileDetailModel = FileDetail.Model(
            path = fileState.relativePath,
            status = determineFileStatus(fileState),
            sizeBytes = fileState.sizeBytes,
            lastModified = fileState.indexedAt,
            language = fileState.language,
            extension = fileState.relativePath.substringAfterLast(".", ""),
            contentHash = fileState.contentHash,
            chunks = chunkModels,
            totalChunks = chunkModels.size,
            referenceInstant = Instant.now(),
            zoneId = ZoneId.systemDefault()
        )

        val html = FileDetail.render(
            FileDetail.Config(
                model = fileDetailModel,
                closeButtonLabel = "Close"
            )
        )

        call.respondText(html, io.ktor.http.ContentType.Text.Html)
    }
}

/**
 * Query files from repository with filters applied
 */
private fun queryFiles(params: FileQueryParams): Pair<List<FileState>, Int> {
    // Fetch all files from repository
    val allFiles = ContextRepository.listAllFiles()

    // Filter files based on query params
    val filteredFiles = allFiles
        .filterNot { it.isDeleted }
        .filter { file ->
            // Search filter
            if (params.search != null) {
                file.relativePath.contains(params.search, ignoreCase = true)
            } else {
                true
            }
        }
        .filter { file ->
            // Status filter - determine status from file state
            if (params.status.isNotEmpty()) {
                val fileStatus = determineFileStatus(file)
                params.status.contains(fileStatus)
            } else {
                true
            }
        }
        .filter { file ->
            // Extension filter
            if (params.extensions.isNotEmpty()) {
                val ext = file.relativePath.substringAfterLast(".", "")
                params.extensions.contains(ext.lowercase())
            } else {
                true
            }
        }

    // Sort files
    val sortedFiles = when (params.sortBy) {
        "path" -> {
            if (params.sortOrder == DataTable.SortDirection.DESC) {
                filteredFiles.sortedByDescending { it.relativePath }
            } else {
                filteredFiles.sortedBy { it.relativePath }
            }
        }
        "size" -> {
            if (params.sortOrder == DataTable.SortDirection.DESC) {
                filteredFiles.sortedByDescending { it.sizeBytes }
            } else {
                filteredFiles.sortedBy { it.sizeBytes }
            }
        }
        "status" -> {
            val statusPriority = mapOf(
                "indexed" to 0,
                "pending" to 1,
                "outdated" to 2,
                "error" to 3
            )
            if (params.sortOrder == DataTable.SortDirection.DESC) {
                filteredFiles.sortedByDescending { statusPriority[determineFileStatus(it)] ?: 4 }
            } else {
                filteredFiles.sortedBy { statusPriority[determineFileStatus(it)] ?: 4 }
            }
        }
        "extension" -> {
            if (params.sortOrder == DataTable.SortDirection.DESC) {
                filteredFiles.sortedByDescending { it.relativePath.substringAfterLast(".", "") }
            } else {
                filteredFiles.sortedBy { it.relativePath.substringAfterLast(".", "") }
            }
        }
        "modified" -> {
            if (params.sortOrder == DataTable.SortDirection.DESC) {
                filteredFiles.sortedByDescending { it.indexedAt }
            } else {
                filteredFiles.sortedBy { it.indexedAt }
            }
        }
        "chunks" -> {
            if (params.sortOrder == DataTable.SortDirection.DESC) {
                filteredFiles.sortedByDescending { getChunkCountForFile(it.id) }
            } else {
                filteredFiles.sortedBy { getChunkCountForFile(it.id) }
            }
        }
        else -> filteredFiles.sortedBy { it.relativePath }
    }

    val totalCount = sortedFiles.size

    // Paginate results
    val offset = (params.page - 1) * params.pageSize
    val paginatedFiles = sortedFiles.drop(offset).take(params.pageSize)

    return Pair(paginatedFiles, totalCount)
}

/**
 * Render complete file table with all components
 */
private fun renderCompleteFileTable(
    files: List<FileState>,
    params: FileQueryParams,
    currentPage: Int,
    totalCount: Int,
    baseUrl: String
): String {
    val columns = buildFileTableColumns(baseUrl, params)
    val rows = files.map { file -> buildFileRow(file) }

    val sortState = DataTable.SortState(
        columnId = params.sortBy,
        direction = params.sortOrder
    )

    val paginationConfig = Pagination.Config(
        page = currentPage,
        pageSize = params.pageSize,
        totalCount = totalCount.toLong(),
        perPageOptions = listOf(10, 25, 50, 100),
        makePageUrl = Pagination.PageUrlBuilder { page, pageSize ->
            buildPaginationUrlForPage(baseUrl, params, page, pageSize)
        },
        hxTargetId = "files-table-container",
        hxIndicatorId = "files-table-indicator",
        hxSwap = "innerHTML"
    )

    return Fragment.render {
        with(DataTable) {
            dataTable(
                id = "files-table",
                columns = columns,
                rows = rows,
                sortState = sortState,
                emptyState = DataTable.EmptyState(
                    message = "No files found",
                    description = {
                        span { +"Try adjusting your search or filter criteria" }
                    }
                ),
                pagination = paginationConfig,
                hxTargetId = "files-table-container",
                hxIndicatorId = "files-table-indicator",
                hxSwapStrategy = "innerHTML"
            )
        }
    }
}

/**
 * Build file table columns with sorting links
 */
private fun buildFileTableColumns(baseUrl: String, params: FileQueryParams): List<DataTable.Column> =
    listOf(
        DataTable.Column(
            id = "path",
            title = "File Path",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "path", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "path", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "path", DataTable.SortDirection.NONE)
            ),
            ariaLabel = "Sort by file path"
        ),
        DataTable.Column(
            id = "status",
            title = "Status",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "status", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "status", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "status", DataTable.SortDirection.NONE)
            ),
            ariaLabel = "Sort by status"
        ),
        DataTable.Column(
            id = "extension",
            title = "Type",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "extension", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "extension", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "extension", DataTable.SortDirection.NONE)
            ),
            ariaLabel = "Sort by file type"
        ),
        DataTable.Column(
            id = "size",
            title = "Size",
            sortable = true,
            numeric = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "size", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "size", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "size", DataTable.SortDirection.NONE)
            ),
            ariaLabel = "Sort by file size"
        ),
        DataTable.Column(
            id = "modified",
            title = "Modified",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "modified", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "modified", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "modified", DataTable.SortDirection.NONE)
            ),
            ariaLabel = "Sort by modification date"
        ),
        DataTable.Column(
            id = "chunks",
            title = "Chunks",
            sortable = true,
            numeric = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "chunks", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "chunks", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "chunks", DataTable.SortDirection.NONE)
            ),
            ariaLabel = "Sort by chunk count"
        ),
        DataTable.Column(
            id = "actions",
            title = "Actions",
            sortable = false
        )
    )

/**
 * Build a file row from FileState
 */
private fun buildFileRow(file: FileState): DataTable.Row {
    return FileRow.toRow(
        model = FileBrowser.Model(
            path = file.relativePath,
            status = determineFileStatus(file),
            sizeBytes = file.sizeBytes,
            lastModified = file.indexedAt,
            chunkCount = getChunkCountForFile(file.id),
            extension = file.relativePath.substringAfterLast(".", ""),
            referenceInstant = Instant.now(),
            zoneId = ZoneId.systemDefault()
        ),
        detailsUrl = "/files/${file.relativePath}/detail"
    )
}

/**
 * Build sort URL with current params
 */
private fun buildSortUrl(
    baseUrl: String,
    params: FileQueryParams,
    sortBy: String,
    sortOrder: DataTable.SortDirection
): String {
    val queryParams = mutableListOf<String>()
    queryParams.add("sortBy=$sortBy")
    queryParams.add("sortOrder=${sortOrder.name.lowercase()}")
    if (params.search != null) queryParams.add("search=${params.search}")
    if (params.status.isNotEmpty()) {
        params.status.forEach { status ->
            queryParams.add("status=$status")
        }
    }
    if (params.extensions.isNotEmpty()) {
        params.extensions.forEach { ext ->
            queryParams.add("extension=$ext")
        }
    }
    queryParams.add("page=1")
    queryParams.add("pageSize=${params.pageSize}")

    return "$baseUrl?${queryParams.joinToString("&")}"
}

/**
 * Build pagination URL with current params
 */
private fun buildPaginationUrlForPage(
    baseUrl: String,
    params: FileQueryParams,
    page: Int,
    pageSize: Int
): String {
    val queryParams = mutableListOf<String>()
    queryParams.add("sortBy=${params.sortBy}")
    queryParams.add("sortOrder=${params.sortOrder.name.lowercase()}")
    if (params.search != null) queryParams.add("search=${params.search}")
    if (params.status.isNotEmpty()) {
        params.status.forEach { status ->
            queryParams.add("status=$status")
        }
    }
    if (params.extensions.isNotEmpty()) {
        params.extensions.forEach { ext ->
            queryParams.add("extension=$ext")
        }
    }
    queryParams.add("page=$page")
    queryParams.add("pageSize=$pageSize")

    return "$baseUrl?${queryParams.joinToString("&")}"
}

/**
 * Determine the status of a file (indexed, outdated, error, pending)
 */
private fun determineFileStatus(file: FileState): String {
    return when {
        file.isDeleted -> "error"
        file.indexedAt.isBefore(Instant.now().minusSeconds(3600)) -> "outdated"
        file.contentHash.isEmpty() -> "pending"
        else -> "indexed"
    }
}

/**
 * Get chunk count for a file (with caching to avoid repeated queries)
 */
private val chunkCountCache = mutableMapOf<Long, Int>()

private fun getChunkCountForFile(fileId: Long): Int {
    return chunkCountCache.getOrPut(fileId) {
        // In a real implementation, this should query the database
        // For now, return a placeholder that would be replaced with actual data
        0
    }
}
