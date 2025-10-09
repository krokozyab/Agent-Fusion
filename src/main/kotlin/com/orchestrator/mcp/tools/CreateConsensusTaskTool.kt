package com.orchestrator.mcp.tools

import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.*
import com.orchestrator.modules.routing.RoutingModule
import com.orchestrator.storage.repositories.TaskRepository
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * MCP Tool: create_consensus_task
 *
 * Parses parameters, validates input, invokes RoutingModule, persists the task,
 * and returns a structured response with routing details.
 */
class CreateConsensusTaskTool(
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
            val forceConsensus: Boolean? = null,
            val preventConsensus: Boolean? = null,
            val assignToAgent: String? = null,
            val isEmergency: Boolean? = null,
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

    /** Execute tool with given params, returning a structured result. */
    fun execute(p: Params): Result {
        val warnings = mutableListOf<String>()

        // 1) Validate core fields
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

        // 2) Directives handling
        val d = p.directives
        val forceConsensus = d?.forceConsensus ?: true // this tool enforces consensus by default
        val preventConsensus = d?.preventConsensus ?: false

        // Fail fast if preventConsensus is explicitly set
        if (preventConsensus) {
            throw IllegalArgumentException(
                "preventConsensus cannot be used with create_consensus_task. Use create_simple_task instead."
            )
        }

        val assignToAgent = d?.assignToAgent?.takeIf { it.isNotBlank() }?.let {
            val agentId = AgentId(it)
            // Validate agent exists in registry
            require(agentRegistry.getAgent(agentId) != null) {
                "Agent '${agentId.value}' not registered in agent registry"
            }
            agentId
        }

        val userDirective = UserDirective(
            originalText = d?.originalText ?: (d?.notes ?: ""),
            forceConsensus = forceConsensus,
            preventConsensus = false, // Always false for consensus tasks
            assignToAgent = assignToAgent,
            assignedAgents = assignToAgent?.let { listOf(it) },
            isEmergency = d?.isEmergency ?: false,
            forceConsensusConfidence = if (forceConsensus) 0.95 else 0.0,
            assignmentConfidence = if (assignToAgent != null) 0.85 else 0.0,
            emergencyConfidence = if (d?.isEmergency == true) 0.75 else 0.0
        )

        // 3) Build initial Task (CONSENSUS routing)
        val id = TaskId(generateTaskId())

        // Validate assignee IDs against registry
        val initialAssignees: Set<AgentId> = p.assigneeIds
            ?.filter { it.isNotBlank() }
            ?.map {
                val agentId = AgentId(it)
                require(agentRegistry.getAgent(agentId) != null) {
                    "Assignee agent '${agentId.value}' not registered in agent registry"
                }
                agentId
            }
            ?.toSet() ?: emptySet()

        val dependencies: Set<TaskId> = p.dependencyIds?.filter { it.isNotBlank() }?.map { TaskId(it) }?.toSet() ?: emptySet()
        val now = Instant.now()

        // Build metadata with directive audit trail
        val enrichedMetadata = buildMetadataWithDirectives(p.metadata, d)

        val task = Task(
            id = id,
            title = p.title.trim(),
            description = p.description?.trim(),
            type = taskType,
            status = TaskStatus.PENDING,
            routing = RoutingStrategy.CONSENSUS,
            assigneeIds = initialAssignees,
            dependencies = dependencies,
            complexity = complexity,
            risk = risk,
            createdAt = now,
            updatedAt = null,
            dueAt = dueAtInstant,
            metadata = enrichedMetadata
        )

        // 4) Route task using RoutingModule (with directive)
        val decision = routingModule.routeTaskWithDirective(task, userDirective)

        // 5) Assert that CONSENSUS strategy was applied
        require(decision.strategy == RoutingStrategy.CONSENSUS) {
            "Expected CONSENSUS routing but got ${decision.strategy}. This indicates a routing configuration issue."
        }

        // Validate that assignToAgent directive was honored if specified
        assignToAgent?.let { requested ->
            require(decision.participantAgentIds.contains(requested)) {
                "Requested agent '${requested.value}' not included in consensus participants: ${decision.participantAgentIds.map { it.value }}"
            }
        }

        // 6) Persist task, applying participants decided by routing
        val withParticipants = task.copy(
            routing = decision.strategy,
            assigneeIds = decision.participantAgentIds.toSet()
        )
        taskRepository.insert(withParticipants)

        // 7) Return result
        return Result(
            taskId = withParticipants.id.value,
            status = withParticipants.status.name,
            routing = withParticipants.routing.name,
            primaryAgentId = decision.primaryAgentId?.value,
            participantAgentIds = decision.participantAgentIds.map { it.value },
            warnings = warnings
        )
    }

    /**
     * Enriches metadata with directive information for audit trail.
     * Follows the same pattern as AssignTaskTool for consistency.
     */
    private fun buildMetadataWithDirectives(
        baseMetadata: Map<String, String>?,
        directives: Params.Directives?
    ): Map<String, String> {
        val meta = baseMetadata?.toMutableMap() ?: mutableMapOf()

        directives?.let { d ->
            d.forceConsensus?.let { meta["directive.forceConsensus"] = it.toString() }
            d.preventConsensus?.let { meta["directive.preventConsensus"] = it.toString() }
            d.assignToAgent?.let { meta["directive.assignToAgent"] = it }
            d.isEmergency?.let { meta["directive.isEmergency"] = it.toString() }
            d.notes?.let { meta["directive.notes"] = it }
            d.originalText?.let { meta["directive.originalText"] = it }
        }

        return meta
    }

    companion object {
        // JSON schema for MCP registration
        // Kept liberal to allow optional fields and future extensions
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "create_consensus_task params",
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
                "forceConsensus": {"type": ["boolean", "null"]},
                "preventConsensus": {"type": ["boolean", "null"]},
                "assignToAgent": {"type": ["string", "null"]},
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
