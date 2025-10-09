package com.orchestrator.mcp.tools

import com.orchestrator.domain.TaskId
import com.orchestrator.storage.repositories.TaskRepository
import java.time.format.DateTimeFormatter

/**
 * MCP Tool: get_task_status
 *
 * Looks up a task by ID and returns its current status along with concise metadata
 * useful to the caller. Throws IllegalArgumentException for invalid task IDs.
 */
class GetTaskStatusTool {

    data class Params(
        val taskId: String
    )

    data class Result(
        val taskId: String,
        val status: String,
        val type: String,
        val routing: String,
        val assignees: List<String>,
        val createdAt: String,
        val updatedAt: String?,
        val dueAt: String?,
        val metadata: Map<String, String>
    )

    fun execute(p: Params): Result {
        require(p.taskId.isNotBlank()) { "taskId cannot be blank" }
        val id = TaskId(p.taskId.trim())

        val task = TaskRepository.findById(id)
            ?: throw IllegalArgumentException("Task with id '${id.value}' not found")

        val fmt = DateTimeFormatter.ISO_INSTANT
        return Result(
            taskId = task.id.value,
            status = task.status.name,
            type = task.type.name,
            routing = task.routing.name,
            assignees = task.assigneeIds.map { it.value }.sorted(),
            createdAt = fmt.format(task.createdAt),
            updatedAt = task.updatedAt?.let { fmt.format(it) },
            dueAt = task.dueAt?.let { fmt.format(it) },
            metadata = task.metadata
        )
    }

    companion object {
        // JSON schema for MCP registration
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "get_task_status params",
          "type": "object",
          "required": ["taskId"],
          "properties": {
            "taskId": {"type": "string", "minLength": 1}
          },
          "additionalProperties": false
        }
        """
    }
}
