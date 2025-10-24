package com.orchestrator.web.routes

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.RoutingStrategy
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
import com.orchestrator.storage.repositories.DecisionRepository
import com.orchestrator.storage.repositories.ProposalRepository
import com.orchestrator.storage.repositories.TaskRepository
import com.orchestrator.web.components.DataTable
import com.orchestrator.web.components.Pagination
import com.orchestrator.web.components.StatusBadge
import com.orchestrator.web.components.StatusBadge.Tone
import com.orchestrator.web.components.TaskRow
import com.orchestrator.web.components.displayName
import com.orchestrator.web.components.toTone
import com.orchestrator.web.dto.toTaskDTO
import com.orchestrator.web.pages.TaskDetailPage
import com.orchestrator.web.pages.TasksPage
import com.orchestrator.web.routes.renderTaskModal
import com.orchestrator.web.rendering.Fragment
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.html.span
import java.time.Clock
import java.time.Instant
import java.util.Locale

/**
 * Query parameters for task filtering, sorting, and pagination
 */
data class TaskQueryParams(
    val search: String? = null,
    val status: Set<TaskStatus> = emptySet(),
    val type: Set<TaskType> = emptySet(),
    val routing: Set<RoutingStrategy> = emptySet(),
    val assigneeIds: Set<AgentId> = emptySet(),
    val riskMin: Int = 1,
    val riskMax: Int = 10,
    val complexityMin: Int = 1,
    val complexityMax: Int = 10,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val sortBy: String = "updated_at",
    val sortOrder: DataTable.SortDirection = DataTable.SortDirection.DESC,
    val page: Int = 1,
    val pageSize: Int = 50
) {
    init {
        require(page >= 1) { "page must be >= 1" }
        require(pageSize in 1..200) { "pageSize must be between 1 and 200" }
        require(riskMin in 1..10) { "riskMin must be between 1 and 10" }
        require(riskMax in 1..10) { "riskMax must be between 1 and 10" }
        require(riskMin <= riskMax) { "riskMin must be <= riskMax" }
        require(complexityMin in 1..10) { "complexityMin must be between 1 and 10" }
        require(complexityMax in 1..10) { "complexityMax must be between 1 and 10" }
        require(complexityMin <= complexityMax) { "complexityMin must be <= complexityMax" }
    }
}

/**
 * Parse query parameters into TaskQueryParams with validation
 */
fun Parameters.toTaskQueryParams(): TaskQueryParams {
    val search = this["search"]?.take(100)

    val status = this.getAll("status")
        ?.mapNotNull { s -> TaskStatus.entries.find { it.name.equals(s, ignoreCase = true) } }
        ?.toSet() ?: emptySet()

    val type = this.getAll("type")
        ?.mapNotNull { t -> TaskType.entries.find { it.name.equals(t, ignoreCase = true) } }
        ?.toSet() ?: emptySet()

    val routing = this.getAll("routing")
        ?.mapNotNull { r -> RoutingStrategy.entries.find { it.name.equals(r, ignoreCase = true) } }
        ?.toSet() ?: emptySet()

    val assigneeIds = this.getAll("assigneeIds")
        ?.flatMap { it.split(",") }
        ?.map { AgentId(it.trim()) }
        ?.toSet() ?: emptySet()

    val riskMin = this["riskMin"]?.toIntOrNull()?.coerceIn(1, 10) ?: 1
    val riskMax = this["riskMax"]?.toIntOrNull()?.coerceIn(1, 10) ?: 10
    val complexityMin = this["complexityMin"]?.toIntOrNull()?.coerceIn(1, 10) ?: 1
    val complexityMax = this["complexityMax"]?.toIntOrNull()?.coerceIn(1, 10) ?: 10

    val createdAfter = this["createdAfter"]?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val createdBefore = this["createdBefore"]?.let { runCatching { Instant.parse(it) }.getOrNull() }

    val sortBy = this["sortBy"] ?: "updated_at"
    val sortOrder = when (this["sortOrder"]?.lowercase()) {
        "asc" -> DataTable.SortDirection.ASC
        "desc" -> DataTable.SortDirection.DESC
        "none" -> DataTable.SortDirection.NONE
        else -> DataTable.SortDirection.DESC
    }

    val page = this["page"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
    val pageSize = this["pageSize"]?.toIntOrNull()?.coerceIn(1, 200) ?: 20

    return TaskQueryParams(
        search = search,
        status = status,
        type = type,
        routing = routing,
        assigneeIds = assigneeIds,
        riskMin = riskMin,
        riskMax = riskMax,
        complexityMin = complexityMin,
        complexityMax = complexityMax,
        createdAfter = createdAfter,
        createdBefore = createdBefore,
        sortBy = sortBy,
        sortOrder = sortOrder,
        page = page,
        pageSize = pageSize
    )
}

fun Route.taskRoutes(clock: Clock = Clock.systemUTC()) {

    /**
     * GET /tasks - Main tasks list page
     */
    get("/tasks") {
        val html = TasksPage.render()
        call.respondText(html, io.ktor.http.ContentType.Text.Html)
    }

    /**
     * GET /tasks/table - Returns HTML fragment for task table body
     *
     * Query parameters:
     * - search: text search across id, title, description
     * - status: comma-separated TaskStatus values
     * - type: comma-separated TaskType values
     * - routing: comma-separated RoutingStrategy values
     * - assigneeIds: comma-separated agent IDs
     * - riskMin, riskMax: risk range (1-10)
     * - complexityMin, complexityMax: complexity range (1-10)
     * - createdAfter, createdBefore: ISO-8601 timestamps
     * - sortBy: column name (default: created_at)
     * - sortOrder: asc|desc (default: desc)
     * - page: page number (default: 1)
     * - pageSize: items per page (default: 50, max: 200)
     *
     * Returns:
     * - HTML tbody fragment for HTMX swap
     * - HX-Trigger response header for events
     */
    get("/tasks/table") {
        val params = try {
            call.request.queryParameters.toTaskQueryParams()
        } catch (e: IllegalArgumentException) {
            call.respondText(
                status = HttpStatusCode.BadRequest,
                text = "Invalid query parameters: ${e.message}"
            )
            return@get
        }

        // Execute query with filters
        val (tasks, totalCount) = queryTasks(params)

        // Calculate pagination
        val totalPages = (totalCount + params.pageSize - 1) / params.pageSize

        // Build current URL for sorting/pagination
        val baseUrl = "/tasks/table"

        // Render complete table
        val html = renderCompleteTaskTable(
            tasks = tasks,
            params = params,
            currentPage = params.page,
            totalPages = totalPages,
            totalCount = totalCount,
            baseUrl = baseUrl,
            clock = clock
        )

        // Set cache control header
        call.response.headers.append("Cache-Control", "no-cache, no-store, must-revalidate")
        call.response.headers.append("X-Total-Count", totalCount.toString())

        call.respondText(html, io.ktor.http.ContentType.Text.Html)
    }

    get("/tasks/{id}") {
        val id = call.parameters["id"]?.let { TaskId(it) }
        if (id == null) {
            call.respondText("Invalid task ID", status = HttpStatusCode.BadRequest)
            return@get
        }

        val task = TaskRepository.findById(id)
        if (task == null) {
            call.respondText("Task not found", status = HttpStatusCode.NotFound)
            return@get
        }

        val proposals = ProposalRepository.findByTask(id)
        val decision = DecisionRepository.findByTask(id)

        val config = TaskDetailPage.Config(
            task = task,
            proposals = proposals,
            decision = decision,
            clock = clock
        )

        val html = TaskDetailPage.render(config)
        call.respondText(html, io.ktor.http.ContentType.Text.Html)
    }

    get("/tasks/{id}/modal") {
        val id = call.parameters["id"]?.let { TaskId(it) }
        if (id == null) {
            call.respondText("Invalid task ID", status = HttpStatusCode.BadRequest)
            return@get
        }

        val task = TaskRepository.findById(id)
        if (task == null) {
            call.respondText("Task not found", status = HttpStatusCode.NotFound)
            return@get
        }

        val proposals = ProposalRepository.findByTask(id)
        val decision = DecisionRepository.findByTask(id)

        val html = renderTaskModal(task, proposals, decision)
        call.respondText(html, io.ktor.http.ContentType.Text.Html)
    }
}

/**
 * Query tasks from repository with filters applied
 */
private fun queryTasks(params: TaskQueryParams): Pair<List<Task>, Int> {
    // For now, use simple queryFiltered from TaskRepository
    // TODO: Extend TaskRepository to support full filter set including search, risk, complexity ranges

    // Simple implementation using queryFiltered with basic filters
    val status = params.status.firstOrNull() // Simplified - repository only supports single status
    val agentId = params.assigneeIds.firstOrNull() // Simplified - repository only supports single agent

    val (allTasks, total) = TaskRepository.queryFiltered(
        status = status,
        agentId = agentId,
        from = params.createdAfter,
        to = params.createdBefore,
        limit = 1000, // Fetch more for in-memory filtering
        offset = 0
    )

    // Apply additional filters in-memory (TODO: move to SQL for performance)
    var filtered = allTasks

    // Filter by status set
    if (params.status.isNotEmpty()) {
        filtered = filtered.filter { it.status in params.status }
    }

    // Filter by type set
    if (params.type.isNotEmpty()) {
        filtered = filtered.filter { it.type in params.type }
    }

    // Filter by routing set
    if (params.routing.isNotEmpty()) {
        filtered = filtered.filter { it.routing in params.routing }
    }

    // Filter by assignee IDs
    if (params.assigneeIds.isNotEmpty()) {
        filtered = filtered.filter { task ->
            params.assigneeIds.any { it in task.assigneeIds }
        }
    }

    // Filter by risk range
    filtered = filtered.filter { it.risk in params.riskMin..params.riskMax }

    // Filter by complexity range
    filtered = filtered.filter { it.complexity in params.complexityMin..params.complexityMax }

    // Filter by search text
    if (!params.search.isNullOrBlank()) {
        val searchLower = params.search.lowercase()
        filtered = filtered.filter {
            it.id.value.lowercase().contains(searchLower) ||
            it.title.lowercase().contains(searchLower) ||
            it.description?.lowercase()?.contains(searchLower) == true
        }
    }

    // Sort
    if (params.sortOrder != DataTable.SortDirection.NONE) {
        filtered = when (params.sortBy) {
            "id" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.id.value } else filtered.sortedByDescending { it.id.value }
            "title" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.title } else filtered.sortedByDescending { it.title }
            "status" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.status.ordinal } else filtered.sortedByDescending { it.status.ordinal }
            "type" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.type.ordinal } else filtered.sortedByDescending { it.type.ordinal }
            "agents" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.assigneeIds.firstOrNull()?.value } else filtered.sortedByDescending { it.assigneeIds.firstOrNull()?.value }
            "updated_at" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.updatedAt ?: it.createdAt } else filtered.sortedByDescending { it.updatedAt ?: it.createdAt }
            "routing" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.routing.ordinal } else filtered.sortedByDescending { it.routing.ordinal }
            "risk" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.risk } else filtered.sortedByDescending { it.risk }
            "complexity" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.complexity } else filtered.sortedByDescending { it.complexity }
            "created_at" -> if (params.sortOrder == DataTable.SortDirection.ASC)
                filtered.sortedBy { it.createdAt } else filtered.sortedByDescending { it.createdAt }
            else -> filtered.sortedByDescending { it.updatedAt ?: it.createdAt }
        }
    }

    val filteredTotal = filtered.size

    // Apply pagination
    val offset = (params.page - 1) * params.pageSize
    val paginated = filtered.drop(offset).take(params.pageSize)

    return Pair(paginated, filteredTotal)
}

/**
 * Render complete task table with header, body, and pagination
 */
private fun renderCompleteTaskTable(
    tasks: List<Task>,
    params: TaskQueryParams,
    currentPage: Int,
    totalPages: Int,
    totalCount: Int,
    baseUrl: String,
    clock: Clock
): String {
    val columns = buildTaskTableColumns(baseUrl, params)
    val rows = tasks.map { task -> buildTaskRow(task, clock) }

    val sortState = DataTable.SortState(
        columnId = params.sortBy,
        direction = params.sortOrder
    )

    val paginationConfig = Pagination.Config(
        page = currentPage,
        pageSize = params.pageSize,
        totalCount = totalCount.toLong(),
        perPageOptions = listOf(10, 20, 50, 100),
        makePageUrl = Pagination.PageUrlBuilder { page, pageSize ->
            buildPaginationUrlForPage(baseUrl, params, page, pageSize)
        },
        hxTargetId = "tasks-table-container",
        hxIndicatorId = "tasks-table-indicator",
        hxSwap = "innerHTML"
    )

    return Fragment.render {
        with(DataTable) {
            dataTable(
                id = "tasks-table",
                columns = columns,
                rows = rows,
                sortState = sortState,
                emptyState = DataTable.EmptyState(
                    message = "No tasks found",
                    description = {
                        span { +"Try adjusting your filters or search criteria" }
                    }
                ),
                pagination = paginationConfig,
                hxTargetId = "tasks-table-container",
                hxIndicatorId = "tasks-table-indicator",
                hxSwapStrategy = "innerHTML"
            )
        }
    }
}

private fun buildTaskTableColumns(baseUrl: String, params: TaskQueryParams): List<DataTable.Column> =
    listOf(
        DataTable.Column(
            id = "id",
            title = "ID",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "id", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "id", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "id", DataTable.SortDirection.NONE)
            ),
            ariaLabel = "Sort by ID"
        ),
        DataTable.Column(
            id = "title",
            title = "Task",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "title", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "title", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "title", DataTable.SortDirection.NONE)
            )
        ),
        DataTable.Column(
            id = "status",
            title = "Status",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "status", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "status", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "status", DataTable.SortDirection.NONE)
            )
        ),
        DataTable.Column(
            id = "type",
            title = "Type",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "type", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "type", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "type", DataTable.SortDirection.NONE)
            )
        ),
        DataTable.Column(
            id = "agents",
            title = "Agents",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "agents", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "agents", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "agents", DataTable.SortDirection.NONE)
            )
        ),
        DataTable.Column(
            id = "updated_at",
            title = "Updated",
            sortable = true,
            sortLinks = DataTable.SortLinks(
                ascending = buildSortUrl(baseUrl, params, "updated_at", DataTable.SortDirection.ASC),
                descending = buildSortUrl(baseUrl, params, "updated_at", DataTable.SortDirection.DESC),
                unsorted = buildSortUrl(baseUrl, params, "updated_at", DataTable.SortDirection.NONE)
            )
        ),
        DataTable.Column(
            id = "actions",
            title = "Actions",
            sortable = false,
            ariaLabel = "Task actions"
        )
    )

private fun buildTaskRow(task: Task, clock: Clock): DataTable.Row {
    val dto = task.toTaskDTO(clock)

    val model = TaskRow.Model(
        id = task.id.value,
        title = task.title,
        status = TaskRow.Status(
            label = task.status.displayName,
            tone = task.status.toTone()
        ),
        type = TaskRow.Type(
            label = task.type.displayName,
            tone = task.type.toTone()
        ),
        routing = formatDisplay(task.routing.name),
        assignees = dto.assigneeIds,
        complexity = task.complexity,
        risk = task.risk,
        detailUrl = "/tasks/${task.id.value}/modal",
        editUrl = "/tasks/${task.id.value}/edit",
        updatedAt = task.updatedAt,
        createdAt = task.createdAt,
        referenceInstant = Instant.now(clock),
        zoneId = clock.zone,
        hxTarget = "#modal-container",
        hxSwap = "innerHTML",
        hxIndicator = "#tasks-table-indicator"
    )

    return TaskRow.toRow(model)
}

private fun formatDisplay(value: String): String =
    value.lowercase(Locale.getDefault())
        .split('_')
        .joinToString(" ") { part ->
            part.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
        }

private fun buildSortUrl(baseUrl: String, params: TaskQueryParams, sortBy: String, direction: DataTable.SortDirection): String {
    val queryParams = mutableListOf<String>()

    // Preserve filters
    params.search?.let { queryParams += "search=$it" }
    params.status.forEach { queryParams += "status=${it.name}" }
    params.type.forEach { queryParams += "type=${it.name}" }
    params.routing.forEach { queryParams += "routing=${it.name}" }
    params.assigneeIds.forEach { queryParams += "assigneeIds=${it.value}" }
    if (params.riskMin != 1) queryParams += "riskMin=${params.riskMin}"
    if (params.riskMax != 10) queryParams += "riskMax=${params.riskMax}"
    if (params.complexityMin != 1) queryParams += "complexityMin=${params.complexityMin}"
    if (params.complexityMax != 10) queryParams += "complexityMax=${params.complexityMax}"
    params.createdAfter?.let { queryParams += "createdAfter=$it" }
    params.createdBefore?.let { queryParams += "createdBefore=$it" }

    // Add sort parameters
    if (direction != DataTable.SortDirection.NONE) {
        queryParams += "sortBy=$sortBy"
        queryParams += "sortOrder=${direction.name.lowercase()}"
    }

    // Preserve pagination
    queryParams += "page=${params.page}"
    queryParams += "pageSize=${params.pageSize}"

    return "$baseUrl?${queryParams.joinToString("&")}"
}

private fun buildPaginationUrlForPage(baseUrl: String, params: TaskQueryParams, page: Int, pageSize: Int): String {
    val queryParams = mutableListOf<String>()

    // Preserve all filters and sort
    params.search?.let { queryParams += "search=$it" }
    params.status.forEach { queryParams += "status=${it.name}" }
    params.type.forEach { queryParams += "type=${it.name}" }
    params.routing.forEach { queryParams += "routing=${it.name}" }
    params.assigneeIds.forEach { queryParams += "assigneeIds=${it.value}" }
    if (params.riskMin != 1) queryParams += "riskMin=${params.riskMin}"
    if (params.riskMax != 10) queryParams += "riskMax=${params.riskMax}"
    if (params.complexityMin != 1) queryParams += "complexityMin=${params.complexityMin}"
    if (params.complexityMax != 10) queryParams += "complexityMax=${params.complexityMax}"
    params.createdAfter?.let { queryParams += "createdAfter=$it" }
    params.createdBefore?.let { queryParams += "createdBefore=$it" }
    queryParams += "sortBy=${params.sortBy}"
    queryParams += "sortOrder=${params.sortOrder.name.lowercase()}"

    // Add dynamic page and pageSize
    queryParams += "page=$page"
    queryParams += "pageSize=$pageSize"

    return "$baseUrl?${queryParams.joinToString("&")}"
}
