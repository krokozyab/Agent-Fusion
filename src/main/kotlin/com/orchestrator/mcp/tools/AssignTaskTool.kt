package com.orchestrator.mcp.tools

import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.modules.routing.RoutingModule
import com.orchestrator.storage.repositories.TaskRepository
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * MCP Tool: assign_task
 *
 * Assigns a task directly to a specific target agent provided by the user.
 * Validates that the agent exists and is available. If the agent is OFFLINE,
 * returns an error. If BUSY, the task is created in PENDING state with a warning.
 */
class AssignTaskTool(
    private val agentRegistry: AgentRegistry,
    private val routingModule: RoutingModule,
    private val taskRepository: TaskRepository
) {
    data class Params(
        val title: String,
        val targetAgent: String? = null,  // Bug fix: Make nullable to match schema
        val description: String? = null,
        val type: String? = null,
        val complexity: Int? = null,
        val risk: Int? = null,
        val dependencyIds: List<String>? = null,
        val dueAt: String? = null,
        val metadata: Map<String, String>? = null,
        val directives: Directives? = null
    ) {
        data class Directives(
            // If true, start immediately (IN_PROGRESS) if the agent is available
            val immediate: Boolean? = null,
            val isEmergency: Boolean? = null, // alias for immediate
            val notes: String? = null,
            val originalText: String? = null
        )
    }

    data class Result(
        val taskId: String,
        val status: String,
        val routing: String,
        val primaryAgentId: String?,
        val participantAgentIds: List<String>,
        val warnings: List<String> = emptyList()
    )

    fun execute(p: Params): Result {
        val warnings = mutableListOf<String>()

        // 1) Validate basic fields
        require(p.title.isNotBlank()) { "title cannot be blank" }

        val taskType: TaskType = when {
            p.type == null -> TaskType.IMPLEMENTATION
            else -> runCatching { TaskType.valueOf(p.type.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Invalid type '${p.type}'. Allowed: ${TaskType.values().joinToString(",")}") }
        }

        val complexity = p.complexity ?: 5
        require(complexity in 1..10) { "complexity must be 1..10" }

        val risk = p.risk ?: 5
        require(risk in 1..10) { "risk must be 1..10" }

        val dueAtInstant: Instant? = if (p.dueAt.isNullOrBlank()) null else try {
            Instant.parse(p.dueAt)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("dueAt must be ISO-8601 Instant (e.g., 2025-10-05T23:05:00Z)")
        }

        // 2) Resolve and validate target agent (Bug fix: support defaults and aliases)
        val resolvedTargetAgent = resolveTargetAgent(p.targetAgent)
        val targetId = AgentId(resolvedTargetAgent)
        val agent = agentRegistry.getAgent(targetId)
            ?: throw IllegalArgumentException("Agent '$resolvedTargetAgent' does not exist")

        when (agent.status) {
            AgentStatus.OFFLINE -> throw IllegalStateException("Agent '${agent.id.value}' is offline and cannot be assigned tasks right now")
            AgentStatus.BUSY -> warnings += "Agent '${agent.id.value}' is busy; task will be queued (PENDING)."
            AgentStatus.ONLINE -> {}
        }

        // 3) Determine initial status from directives and agent availability
        val d = p.directives
        val emergency = when {
            d?.immediate == true -> true
            d?.isEmergency == true -> true
            else -> false
        }
        val initialStatus: TaskStatus = when {
            agent.status == AgentStatus.BUSY -> TaskStatus.PENDING
            emergency -> TaskStatus.IN_PROGRESS
            else -> TaskStatus.PENDING
        }

        // 4) Validate dependencies (Bug fix: prevent dangling dependencies)
        val validatedDependencies = validateDependencies(p.dependencyIds, warnings)

        // 5) Build metadata including directive context (Bug fix: preserve originalText/notes)
        val enrichedMetadata = buildMetadataWithDirectives(p.metadata, d)

        // 6) Build and persist task assigned to the specific agent
        val id = TaskId(generateTaskId())
        val now = Instant.now()
        val task = Task(
            id = id,
            title = p.title.trim(),
            description = p.description?.trim(),
            type = taskType,
            status = initialStatus,
            routing = RoutingStrategy.SOLO,
            assigneeIds = setOf(targetId),
            dependencies = validatedDependencies,
            complexity = complexity,
            risk = risk,
            createdAt = now,
            updatedAt = null,
            dueAt = dueAtInstant,
            metadata = enrichedMetadata
        )

        taskRepository.insert(task)

        return Result(
            taskId = task.id.value,
            status = task.status.name,
            routing = task.routing.name,
            primaryAgentId = targetId.value,
            participantAgentIds = listOf(targetId.value),
            warnings = warnings
        )
    }

    /**
     * Resolve targetAgent with support for null, defaults, and aliases (user/me).
     * Bug fix: Aligns implementation with schema that declares targetAgent as optional.
     */
    private fun resolveTargetAgent(requestedRaw: String?): String {
        val agents = agentRegistry.getAllAgents()
        require(agents.isNotEmpty()) { "No agents are registered; cannot resolve targetAgent" }

        val trimmed = requestedRaw?.trim()?.takeIf { it.isNotEmpty() }
        val normalized = trimmed?.lowercase()

        // Treat null, "user", or "me" as requesting the default single agent
        val wantsDefault = trimmed == null || normalized == "user" || normalized == "me"

        return when {
            wantsDefault -> {
                if (agents.size == 1) {
                    agents.first().id.value
                } else {
                    throw IllegalArgumentException(
                        "Cannot resolve default agent: ${agents.size} agents registered. " +
                        "Available: ${agents.joinToString(", ") { "${it.id.value} (${it.displayName})" }}"
                    )
                }
            }
            else -> trimmed!!
        }
    }

    /**
     * Validate dependency IDs against the repository.
     * Bug fix: Prevents dangling dependencies that cannot be satisfied.
     */
    private fun validateDependencies(dependencyIds: List<String>?, warnings: MutableList<String>): Set<TaskId> {
        if (dependencyIds.isNullOrEmpty()) return emptySet()

        val validated = mutableSetOf<TaskId>()
        for (depId in dependencyIds.filter { it.isNotBlank() }) {
            val taskId = TaskId(depId)
            val existingTask = TaskRepository.findById(taskId)

            when {
                existingTask == null -> {
                    warnings += "Dependency task '$depId' does not exist; ignoring"
                }
                existingTask.status == TaskStatus.FAILED -> {
                    warnings += "Dependency task '$depId' is FAILED; task may not be satisfiable"
                    validated += taskId
                }
                existingTask.status == TaskStatus.COMPLETED -> {
                    // Completed dependencies are fine
                    validated += taskId
                }
                else -> {
                    // PENDING, IN_PROGRESS, WAITING_INPUT - all valid
                    validated += taskId
                }
            }
        }
        return validated
    }

    /**
     * Build enriched metadata that includes directive context for audit trails.
     * Bug fix: Preserves originalText and notes from directives for downstream context.
     */
    private fun buildMetadataWithDirectives(
        userMetadata: Map<String, String>?,
        directives: Params.Directives?
    ): Map<String, String> {
        val metadata = userMetadata?.toMutableMap() ?: mutableMapOf()

        // Preserve directive context in metadata for audit/handoff
        directives?.originalText?.let { metadata["directive.originalText"] = it }
        directives?.notes?.let { metadata["directive.notes"] = it }
        if (directives?.immediate == true || directives?.isEmergency == true) {
            metadata["directive.immediate"] = "true"
        }

        return metadata
    }

    companion object {
        // JSON schema for MCP registration
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "assign_task params",
          "type": "object",
          "required": ["title"],
          "properties": {
            "title": {"type": "string", "minLength": 1},
            "targetAgent": {
              "type": ["string", "null"],
              "minLength": 1,
              "description": "Optional. Defaults to the only configured agent; aliases 'user'/'me' resolve to that agent."
            },
            "description": {"type": ["string", "null"]},
            "type": {"type": ["string", "null"], "enum": [
              "IMPLEMENTATION", "ARCHITECTURE", "REVIEW", "RESEARCH", "TESTING", "DOCUMENTATION", "PLANNING", "BUGFIX"
            ]},
            "complexity": {"type": ["integer", "null"], "minimum": 1, "maximum": 10},
            "risk": {"type": ["integer", "null"], "minimum": 1, "maximum": 10},
            "dependencyIds": {"type": ["array", "null"], "items": {"type": "string"}},
            "dueAt": {"type": ["string", "null"], "description": "ISO-8601 instant, e.g., 2025-10-05T23:05:00Z"},
            "metadata": {
              "type": ["object", "null"],
              "additionalProperties": {"type": "string"}
            },
            "directives": {
              "type": ["object", "null"],
              "properties": {
                "immediate": {"type": ["boolean", "null"]},
                "isEmergency": {"type": ["boolean", "null"]},
                "notes": {"type": ["string", "null"]},
                "originalText": {"type": ["string", "null"]}
              }
            }
          },
          "additionalProperties": false
        }
        """
    }
}

private fun generateTaskId(): String = "task-" + UUID.randomUUID().toString()
