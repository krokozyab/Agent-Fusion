package com.orchestrator.mcp.resources

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.storage.repositories.TaskRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * MCP Resource: tasks://
 *
 * Provides a queryable JSON feed of tasks with filters and pagination.
 *
 * Query parameters (all optional unless noted):
 * - status: one of TaskStatus enum (e.g., PENDING, IN_PROGRESS, COMPLETED)
 * - agent: AgentId string; filters tasks where agent is in assignee_ids
 * - from: ISO-8601 instant (inclusive) filter on created_at lower bound
 * - to: ISO-8601 instant (inclusive) filter on created_at upper bound
 * - page: 1-based page index (default 1)
 * - pageSize: items per page (default 50; max 200)
 *
 * Returns JSON with shape:
 * {
 *   "uri": "tasks://?status=...",
 *   "total": 123,
 *   "page": 1,
 *   "pageSize": 50,
 *   "items": [ {task}, ... ]
 * }
 */

@Serializable
data class TaskDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val type: String,
    val status: String,
    val routing: String,
    val assigneeIds: List<String>,
    val dependencies: List<String>,
    val complexity: Int,
    val risk: Int,
    val createdAt: String,
    val updatedAt: String? = null,
    val dueAt: String? = null,
    val metadata: Map<String, String>
)

@Serializable
data class TasksListResponse(
    val uri: String,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val items: List<TaskDto>
)

@Serializable
data class TaskByIdResponse(
    val uri: String,
    val task: TaskDto
)

class TasksResource {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    fun list(query: Map<String, String>): String {
        val status = query["status"]?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
            runCatching { TaskStatus.valueOf(s.uppercase()) }.getOrElse {
                throw IllegalArgumentException("Invalid status '$s'. Allowed: ${TaskStatus.values().joinToString(",")}")
            }
        }
        val agentId = query["agent"]?.trim()?.takeIf { it.isNotEmpty() }?.let { AgentId(it) }
        val from = parseInstant(query["from"])
        val to = parseInstant(query["to"])

        // Bug fix: Validate timestamp range ordering
        if (from != null && to != null && to.isBefore(from)) {
            throw IllegalArgumentException("'to' timestamp must be >= 'from' timestamp")
        }

        // Bug fix: Prevent pagination overflow with safe bounds
        val page = query["page"]?.toIntOrNull()?.coerceIn(1, 10_000) ?: 1
        val cappedPageSize = query["pageSize"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50

        // Use Long arithmetic to prevent overflow
        val offset = ((page.toLong() - 1) * cappedPageSize.toLong()).toInt()

        val (items, total) = TaskRepository.queryFiltered(status, agentId, from, to, cappedPageSize, offset)

        val uri = buildUri(query)

        // Convert Task domain objects to DTOs
        val taskDtos = items.map { task -> taskToDto(task) }

        val response = TasksListResponse(
            uri = uri,
            total = total,
            page = page,
            pageSize = cappedPageSize,
            items = taskDtos
        )

        return json.encodeToString(response)
    }

    fun getById(taskId: TaskId): String {
        val task = TaskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task '${taskId.value}' not found")

        val response = TaskByIdResponse(
            uri = "orchestrator://tasks/${task.id.value}",
            task = taskToDto(task)
        )

        return json.encodeToString(response)
    }

    /**
     * Convert Task domain object to DTO for serialization
     */
    private fun taskToDto(task: Task): TaskDto {
        return TaskDto(
            id = task.id.value,
            title = task.title,
            description = task.description,
            type = task.type.name,
            status = task.status.name,
            routing = task.routing.name,
            assigneeIds = task.assigneeIds.map { it.value },
            dependencies = task.dependencies.map { it.value },
            complexity = task.complexity,
            risk = task.risk,
            createdAt = task.createdAt.toString(),
            updatedAt = task.updatedAt?.toString(),
            dueAt = task.dueAt?.toString(),
            metadata = task.metadata
        )
    }

    private fun parseInstant(value: String?): Instant? {
        val v = value?.trim() ?: return null
        if (v.isEmpty()) return null
        return try {
            Instant.parse(v)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("Invalid timestamp '$v'. Expected ISO-8601 instant, e.g., 2025-01-01T00:00:00Z")
        }
    }

    private fun buildUri(query: Map<String, String>): String {
        if (query.isEmpty()) return "tasks://"
        val qp = query.entries.joinToString("&") { (k, v) ->
            encode(k) + "=" + encode(v)
        }
        return "tasks://?" + qp
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)
}
