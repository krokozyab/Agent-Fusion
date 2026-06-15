package com.orchestrator.web.routes

import com.orchestrator.context.ContextRepository
import com.orchestrator.context.domain.FileState
import com.orchestrator.web.components.AgGrid
import com.orchestrator.web.components.DataTable
import com.orchestrator.web.components.FileDetail
import com.orchestrator.web.pages.FilesPage
import com.orchestrator.web.rendering.Fragment
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // DB access is blocking JDBC; keep it off the Netty event-loop threads.
        val rowData = withContext(Dispatchers.IO) {
            val files = ContextRepository.listAllFiles()
                .filterNot { it.isDeleted }
                .sortedBy { it.relativePath.lowercase() }
            val chunkCounts = fetchChunkCounts()

            files.map { file ->
                mapOf<String, Any>(
                    "path" to file.relativePath,
                    "status" to determineFileStatus(file),
                    "extension" to file.relativePath.substringAfterLast(".", ""),
                    "sizeBytes" to file.sizeBytes,
                    "lastModified" to formatInstant(file.indexedAt),
                    "chunkCount" to (chunkCounts[file.id] ?: 0),
                    "fileId" to file.id
                )
            }
        }

        val gridData = FilesPage.GridData(
            columnDefs = FilesPage.GridData.defaultColumns(),
            rowData = rowData
        )

        val html = FilesPage.render(gridData)
        call.respondText(html, ContentType.Text.Html)
    }

    /**
     * GET /files/table - Returns ag-Grid compatible HTML fragment
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
     * - HTML div with updated ag-Grid data table
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

        // Query files with filters (blocking JDBC → run on the IO dispatcher)
        val (rowData, totalCount) = withContext(Dispatchers.IO) {
            val chunkCounts = fetchChunkCounts()
            val (files, count) = queryFiles(params, chunkCounts)
            val rows = files.map { file ->
                mapOf<String, Any>(
                    "path" to file.relativePath,
                    "status" to determineFileStatus(file),
                    "extension" to file.relativePath.substringAfterLast(".", ""),
                    "sizeBytes" to formatFileSize(file.sizeBytes),
                    "lastModified" to formatInstant(file.indexedAt),
                    "chunkCount" to (chunkCounts[file.id] ?: 0),
                    "fileId" to file.id
                )
            }
            rows to count
        }

        // Render ag-Grid table with updated data
        val html = Fragment.render {
            with(AgGrid) {
                agGrid(AgGrid.GridConfig(
                    id = "files-grid",
                    columnDefs = FilesPage.GridData.defaultColumns(),
                    rowData = rowData,
                    height = "600px",
                    enablePagination = true,
                    pageSize = params.pageSize,
                    customOptions = mapOf(
                        "suppressRowClickSelection" to false,
                        "enableCellTextSelection" to true
                    )
                ))
            }
        }

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

        // Fetch file + artifacts from the repository off the event-loop (blocking JDBC).
        val (fileState, artifacts) = withContext(Dispatchers.IO) {
            val state = ContextRepository.listAllFiles()
                .find { it.relativePath == filePath || it.id.toString() == filePath }
            val arts = state?.let { ContextRepository.fetchFileArtifactsByPath(it.relativePath) }
            state to arts
        }

        if (fileState == null) {
            call.respondText(
                status = HttpStatusCode.NotFound,
                text = "File not found"
            )
            return@get
        }

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
private fun queryFiles(
    params: FileQueryParams,
    chunkCounts: Map<Long, Int>
): Pair<List<FileState>, Int> {
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
                filteredFiles.sortedByDescending { chunkCounts[it.id] ?: 0 }
            } else {
                filteredFiles.sortedBy { chunkCounts[it.id] ?: 0 }
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
 * Determine the status of a file (indexed, outdated, error, pending).
 *
 * Status is based on whether the file has been modified since it was last indexed:
 * - "error": File is marked as deleted
 * - "pending": File has never been indexed (contentHash is empty)
 * - "outdated": File has been modified on disk but not yet re-indexed
 * - "indexed": File is current (indexed time matches or is after modification time)
 */
private fun determineFileStatus(file: FileState): String {
    return when {
        file.isDeleted -> "error"
        file.contentHash.isEmpty() -> "pending"
        // Check if file was modified after it was last indexed
        // modifiedTimeNs is in nanoseconds, convert to Instant for comparison
        Instant.ofEpochSecond(
            file.modifiedTimeNs / 1_000_000_000,
            file.modifiedTimeNs % 1_000_000_000
        ).isAfter(file.indexedAt) -> "outdated"
        else -> "indexed"
    }
}

/**
 * Fetch chunk counts for all files in a single GROUP BY query.
 *
 * Replaces a per-file COUNT(*) (N+1 queries per page render) plus a process-wide
 * `mutableMapOf` cache that was (a) not thread-safe — concurrent request coroutines mutated it via
 * getOrPut, risking corruption/lost updates; (b) unbounded — it leaked an entry per file id forever;
 * (c) never invalidated — counts stayed stale after re-indexing. One aggregate query per request is
 * cheaper and always current. Callers run this inside withContext(Dispatchers.IO).
 */
private fun fetchChunkCounts(): Map<Long, Int> {
    return try {
        com.orchestrator.context.storage.ContextDatabase.withConnection { conn ->
            conn.prepareStatement("SELECT file_id, COUNT(*) FROM chunks GROUP BY file_id").use { ps ->
                ps.executeQuery().use { rs ->
                    val counts = HashMap<Long, Int>()
                    while (rs.next()) {
                        counts[rs.getLong(1)] = rs.getInt(2)
                    }
                    counts
                }
            }
        }
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * Format file size in human-readable format
 */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${String.format("%.1f", bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${String.format("%.1f", bytes / (1024.0 * 1024))} MB"
        else -> "${String.format("%.1f", bytes / (1024.0 * 1024 * 1024))} GB"
    }
}

/**
 * Format Instant to readable date string
 */
private fun formatInstant(instant: Instant?): String {
    if (instant == null) return "Never"
    return instant.atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm"))
}
