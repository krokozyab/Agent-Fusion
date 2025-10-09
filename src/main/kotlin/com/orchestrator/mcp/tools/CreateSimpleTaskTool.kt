package com.orchestrator.mcp.tools

import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.modules.routing.RoutingModule
import com.orchestrator.storage.repositories.TaskRepository
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * MCP Tool: create_simple_task
 *
 * Creates a task that defaults to solo execution but still allows the router to
 * escalate to multi-agent strategies when the heuristics deem it necessary
 * (e.g., high risk). Callers can explicitly opt out of consensus by setting
 * `skipConsensus = true`. The tool validates inputs, invokes the RoutingModule,
 * persists the task, and returns a structured response.
 */
class CreateSimpleTaskTool(
    private val agentRegistry: AgentRegistry,
    private val routingModule: RoutingModule,
    private val taskRepository: TaskRepository
) {

    data class Params(
        val title: String,
        val description: String? = null,
        val type: String? = null,
        val complexity: Int? = null,
        val risk: Int? = null,
        val assigneeIds: List<String>? = null,
        val dependencyIds: List<String>? = null,
        val dueAt: String? = null,
        val metadata: Map<String, String>? = null,
        val directives: Directives? = null
    ) {
        data class Directives(
            // When true, explicitly bypasses consensus routing.
            val skipConsensus: Boolean? = null,
            // If provided, directly prefer/assign to a specific agent
            val assignToAgent: String? = null,
            // Signals immediate execution / emergency bypass
            val immediate: Boolean? = null,
            val isEmergency: Boolean? = null, // alias for immediate; either can be used
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

        // 1) Validate core fields
        require(p.title.isNotBlank()) { "title cannot be blank" }

        val taskType: TaskType = when {
            p.type == null -> TaskType.IMPLEMENTATION
            else -> runCatching { TaskType.valueOf(p.type.uppercase()) }
                .getOrElse { throw IllegalArgumentException("Invalid type '${p.type}'. Allowed: ${TaskType.values().joinToString(",")} ") }
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

        // 2) Directives handling
        val d = p.directives
        val skipConsensus = d?.skipConsensus ?: false // allow router to pick consensus unless user opts out
        val assignToAgent = d?.assignToAgent?.takeIf { it.isNotBlank() }?.let { AgentId(it) }
        val emergency = when {
            d?.immediate == true -> true
            d?.isEmergency == true -> true
            else -> false
        }
        if (skipConsensus) {
            warnings += "skipConsensus=true provided; forcing SOLO routing"
        }

        val userDirective = UserDirective(
            originalText = d?.originalText ?: (d?.notes ?: ""),
            forceConsensus = false,
            preventConsensus = skipConsensus,
            assignToAgent = assignToAgent,
            isEmergency = emergency,
            preventConsensusConfidence = if (skipConsensus) 0.9 else 0.0,
            assignmentConfidence = if (assignToAgent != null) 0.85 else 0.0,
            emergencyConfidence = if (emergency) 0.95 else 0.0,
            assignedAgents = assignToAgent?.let { listOf(it) }
        )

        // 3) Build initial Task (SOLO routing)
        val id = TaskId(generateTaskId())
        val initialAssignees: Set<AgentId> = p.assigneeIds?.filter { it.isNotBlank() }?.map { AgentId(it) }?.toSet() ?: emptySet()
        val dependencies: Set<TaskId> = p.dependencyIds?.filter { it.isNotBlank() }?.map { TaskId(it) }?.toSet() ?: emptySet()
        val now = Instant.now()
        val task = Task(
            id = id,
            title = p.title.trim(),
            description = p.description?.trim(),
            type = taskType,
            status = if (emergency) TaskStatus.IN_PROGRESS else TaskStatus.PENDING,
            routing = RoutingStrategy.SOLO,
            assigneeIds = initialAssignees,
            dependencies = dependencies,
            complexity = complexity,
            risk = risk,
            createdAt = now,
            updatedAt = null,
            dueAt = dueAtInstant,
            metadata = p.metadata ?: emptyMap()
        )

        // 4) Route task using RoutingModule (with directive)
        val decision = routingModule.routeTaskWithDirective(task, userDirective)

        // 5) Persist task, applying selected participants
        val withParticipants = task.copy(
            routing = decision.strategy, // should be SOLO
            assigneeIds = decision.participantAgentIds.toSet()
        )
        taskRepository.insert(withParticipants)

        // 6) Return result
        return Result(
            taskId = withParticipants.id.value,
            status = withParticipants.status.name,
            routing = withParticipants.routing.name,
            primaryAgentId = decision.primaryAgentId?.value,
            participantAgentIds = decision.participantAgentIds.map { it.value },
            warnings = warnings
        )
    }

    companion object {
        // JSON schema for MCP registration
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "create_simple_task params",
          "type": "object",
          "required": ["title"],
          "properties": {
            "title": {"type": "string", "minLength": 1},
            "description": {"type": ["string", "null"]},
            "type": {"type": ["string", "null"], "enum": [
              "IMPLEMENTATION", "ARCHITECTURE", "REVIEW", "RESEARCH", "TESTING", "DOCUMENTATION", "PLANNING", "BUGFIX"
            ]},
            "complexity": {"type": ["integer", "null"], "minimum": 1, "maximum": 10},
            "risk": {"type": ["integer", "null"], "minimum": 1, "maximum": 10},
            "assigneeIds": {"type": ["array", "null"], "items": {"type": "string"}},
            "dependencyIds": {"type": ["array", "null"], "items": {"type": "string"}},
            "dueAt": {"type": ["string", "null"], "description": "ISO-8601 instant, e.g., 2025-10-05T23:05:00Z"},
            "metadata": {
              "type": ["object", "null"],
              "additionalProperties": {"type": "string"}
            },
            "directives": {
              "type": ["object", "null"],
              "properties": {
                "skipConsensus": {"type": ["boolean", "null"]},
                "assignToAgent": {"type": ["string", "null"]},
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
