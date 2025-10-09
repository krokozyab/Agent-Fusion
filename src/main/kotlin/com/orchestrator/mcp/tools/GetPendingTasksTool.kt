package com.orchestrator.mcp.tools

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskStatus
import com.orchestrator.storage.repositories.TaskRepository
import java.time.format.DateTimeFormatter

/**
 * MCP Tool: get_pending_tasks
 *
 * Retrieves tasks for a given agent filtered by status and returns lightweight summaries
 * sorted by priority (derived from risk/complexity) and creation time.
 */
class GetPendingTasksTool {

    data class Params(
        val agentId: String? = null,
        // If not provided, defaults to PENDING
        val statuses: List<String>? = null,
        // Optional maximum number of tasks to return (safeguard for performance)
        val limit: Int? = null
    )

    data class TaskSummary(
        val id: String,
        val title: String,
        val status: String,
        val type: String,
        val priority: Int,
        val createdAt: String,
        val dueAt: String?,
        val contextPreview: String
    )

    data class Result(
        val agentId: String,
        val count: Int,
        val tasks: List<TaskSummary>
    )

    fun execute(p: Params, resolvedAgentId: String): Result {
        require(resolvedAgentId.isNotBlank()) { "agentId cannot be blank" }
        val agentId = AgentId(resolvedAgentId.trim())

        // Parse/validate statuses; default to PENDING only for this tool
        val statuses: Set<TaskStatus> = (p.statuses?.takeIf { it.isNotEmpty() } ?: listOf("PENDING"))
            .map { it.uppercase() }
            .map {
                runCatching { TaskStatus.valueOf(it) }
                    .getOrElse { throw IllegalArgumentException("Invalid status '$it'. Allowed: ${TaskStatus.values().joinToString(",")}") }
            }
            .toSet()

        val allForAgent: List<Task> = TaskRepository.findByAgent(agentId)

        // Filter by statuses and map to summaries
        val filtered = allForAgent.asSequence()
            .filter { it.status in statuses }
            .sortedWith(compareByDescending<Task> { priorityScore(it) }
                .thenBy { it.createdAt })
            .let { seq ->
                val limited = p.limit?.coerceAtLeast(0) ?: Int.MAX_VALUE
                if (limited == Int.MAX_VALUE) seq.toList() else seq.take(limited).toList()
            }

        val formatter = DateTimeFormatter.ISO_INSTANT
        val summaries = filtered.map { t ->
            TaskSummary(
                id = t.id.value,
                title = t.title,
                status = t.status.name,
                type = t.type.name,
                priority = priorityScore(t),
                createdAt = formatter.format(t.createdAt),
                dueAt = t.dueAt?.let { formatter.format(it) },
                contextPreview = buildPreview(t)
            )
        }

        return Result(
            agentId = agentId.value,
            count = summaries.size,
            tasks = summaries
        )
    }

    private fun priorityScore(t: Task): Int {
        // Heuristic priority: weight risk more than complexity
        // Higher score means higher priority
        return (t.risk * 10) + t.complexity
    }

    private fun buildPreview(t: Task): String {
        // Prefer description; otherwise fall back to selected metadata fields
        val base = t.description?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                val keys = listOf("context", "notes", "summary", "scope")
                val fromMeta = keys.firstNotNullOfOrNull { k -> t.metadata[k] }
                fromMeta ?: ""
            }
        val normalized = base.replace("\n", " ").replace("\r", " ").replace("\t", " ").trim()
        if (normalized.isEmpty()) return ""
        val maxLen = 160
        return if (normalized.length <= maxLen) normalized else normalized.substring(0, maxLen - 1) + "…"
    }

    companion object {
        // JSON schema for MCP registration
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "get_pending_tasks params",
          "type": "object",
          "properties": {
            "agentId": {
              "type": ["string", "null"],
              "minLength": 1,
              "description": "Optional. Uses the only configured agent when omitted; aliases 'user' and 'me' map to that agent."
            },
            "statuses": {"type": ["array", "null"], "items": {"type": "string", "enum": [
              "PENDING", "IN_PROGRESS", "WAITING_INPUT", "COMPLETED", "FAILED"
            ]}},
            "limit": {"type": ["integer", "null"], "minimum": 0, "maximum": 1000}
          },
          "additionalProperties": false
        }
        """
    }
}
