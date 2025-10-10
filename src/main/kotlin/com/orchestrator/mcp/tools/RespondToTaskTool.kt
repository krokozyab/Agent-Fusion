package com.orchestrator.mcp.tools

import com.orchestrator.domain.*
import com.orchestrator.modules.consensus.ProposalManager
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.storage.repositories.ProposalRepository
import com.orchestrator.storage.repositories.TaskRepository
import java.time.Instant

/**
 * MCP Tool: respond_to_task
 *
 * Unified tool that combines continue_task + submit_input in a single operation.
 * This provides a simpler UX for agents/humans: load context and submit response in one call.
 *
 * Workflow:
 * 1. Load task and full context (like continue_task)
 * 2. Submit the agent's response/proposal (like submit_input)
 * 3. Return complete result including updated status
 *
 * This tool is recommended for simple workflows where the agent wants to respond immediately.
 * For complex scenarios where analysis is needed before deciding to submit, use continue_task
 * followed by submit_input separately.
 */
class RespondToTaskTool {

    data class Params(
        val taskId: String,
        val agentId: String? = null,
        val response: ResponseContent,
        val maxTokens: Int? = null // optional cap for context retrieval
    )

    data class ResponseContent(
        val content: Any?,
        val inputType: String? = null,
        val confidence: Double? = null,
        val metadata: Map<String, String>? = null
    )

    data class Result(
        val taskId: String,
        val proposalId: String,
        val inputType: String,
        val taskStatus: String,
        val task: TaskDTO,
        val proposals: List<ProposalDTO>,
        val context: ContextDTO,
        val message: String
    ) {
        data class TaskDTO(
            val id: String,
            val title: String,
            val description: String?,
            val type: String,
            val status: String,
            val routing: String,
            val assigneeIds: List<String>,
            val dependencies: List<String>,
            val complexity: Int,
            val risk: Int,
            val createdAt: Long,
            val updatedAt: Long?,
            val dueAt: Long?,
            val metadata: Map<String, String>
        )
        data class ProposalDTO(
            val id: String,
            val taskId: String,
            val agentId: String,
            val inputType: String,
            val confidence: Double,
            val tokenUsage: Map<String, Int> = emptyMap(),
            val content: Any?,
            val createdAt: Long,
            val metadata: Map<String, String>
        )
        data class ContextDTO(
            val history: List<MessageDTO>,
            val fileHistory: List<FileOpDTO>
        )
        data class MessageDTO(
            val id: Long?,
            val role: String,
            val content: String,
            val agentId: String?,
            val tokens: Int,
            val ts: Long,
            val metadataJson: String?
        )
        data class FileOpDTO(
            val agentId: String,
            val type: String,
            val path: String,
            val ts: Long,
            val version: Int,
            val diff: String? = null,
            val contentHash: String? = null,
            val conflict: Boolean = false,
            val conflictReason: String? = null,
            val metadata: Map<String, String> = emptyMap()
        )
    }

    companion object {
        const val MAX_ALLOWED_TOKENS = 120_000
        const val DEFAULT_MAX_TOKENS = 6_000
        const val MIN_TOKENS = 1_000

        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "respond_to_task params",
          "type": "object",
          "required": ["taskId", "response"],
          "properties": {
            "taskId": {"type": "string", "minLength": 1},
            "agentId": {
              "type": ["string", "null"],
              "minLength": 1,
              "description": "Optional. Defaults to the only configured agent; aliases 'user'/'me' resolve to that agent."
            },
            "response": {
              "type": "object",
              "required": ["content"],
              "properties": {
                "content": {"type": ["null", "string", "number", "boolean", "array", "object"]},
                "inputType": {"type": ["string", "null"], "enum": [
                  "ARCHITECTURAL_PLAN", "CODE_REVIEW", "IMPLEMENTATION_PLAN", "TEST_PLAN",
                  "REFACTORING_SUGGESTION", "RESEARCH_SUMMARY", "OTHER"
                ]},
                "confidence": {"type": ["number", "null"], "minimum": 0.0, "maximum": 1.0},
                "metadata": {"type": ["object", "null"], "additionalProperties": {"type": "string"}}
              }
            },
            "maxTokens": {
              "type": ["integer", "null"],
              "minimum": $MIN_TOKENS,
              "maximum": $MAX_ALLOWED_TOKENS,
              "description": "Optional limit for context retrieval (history + fileHistory)"
            }
          },
          "additionalProperties": false
        }
        """
    }

    fun execute(p: Params, resolvedAgentId: String): Result {
        // Validate params
        require(p.taskId.isNotBlank()) { "taskId cannot be blank" }
        require(resolvedAgentId.isNotBlank()) { "agentId cannot be blank" }

        val taskId = TaskId(p.taskId.trim())
        val agentId = AgentId(resolvedAgentId.trim())

        // STEP 1: Load task and validate state
        val task = TaskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task '${p.taskId}' does not exist")

        if (task.status !in setOf(TaskStatus.WAITING_INPUT, TaskStatus.IN_PROGRESS, TaskStatus.PENDING)) {
            throw IllegalStateException(
                "Task '${task.id.value}' is in status ${task.status}; cannot respond to completed or failed tasks"
            )
        }

        // STEP 2: Validate and clamp maxTokens
        val maxTokens = when {
            p.maxTokens == null -> DEFAULT_MAX_TOKENS
            p.maxTokens < MIN_TOKENS -> throw IllegalArgumentException(
                "maxTokens must be at least $MIN_TOKENS (requested: ${p.maxTokens})"
            )
            p.maxTokens > MAX_ALLOWED_TOKENS -> throw IllegalArgumentException(
                "maxTokens exceeds policy limit of $MAX_ALLOWED_TOKENS (requested: ${p.maxTokens})"
            )
            else -> p.maxTokens
        }

        // STEP 3: Retrieve context
        val ctx = ContextModule.getTaskContext(taskId, maxTokens)

        // STEP 4: Parse input type
        val inputType = when (val t = p.response.inputType?.trim()?.uppercase()) {
            null, "" -> InputType.OTHER
            else -> runCatching { InputType.valueOf(t) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "Invalid inputType '$t'. Allowed: ${InputType.values().joinToString(",")}"
                    )
                }
        }

        // STEP 5: Validate confidence
        val confidence = p.response.confidence ?: 0.5
        require(confidence in 0.0..1.0) { "confidence must be in [0.0, 1.0]" }

        // STEP 6: Submit proposal via ProposalManager
        val proposal = ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agentId,
            content = p.response.content,
            inputType = inputType,
            confidence = confidence,
            metadata = p.response.metadata ?: emptyMap()
        )

        // STEP 7: Update task status
        // For SOLO routing: auto-complete after response submission (single agent completes the work)
        // For CONSENSUS/multi-agent: move to IN_PROGRESS (needs coordination/additional inputs)
        val now = Instant.now()
        val updatedTask = when {
            // SOLO tasks auto-complete on response
            task.routing == RoutingStrategy.SOLO -> {
                val updated = task.copy(
                    status = TaskStatus.COMPLETED,
                    updatedAt = now,
                    metadata = task.metadata + mapOf("completedBy" to agentId.value)
                )
                TaskRepository.update(updated)
                updated
            }
            // Multi-agent tasks transition to IN_PROGRESS
            task.status == TaskStatus.PENDING || task.status == TaskStatus.WAITING_INPUT -> {
                val updated = task.copy(status = TaskStatus.IN_PROGRESS, updatedAt = now)
                TaskRepository.update(updated)
                updated
            }
            // Already IN_PROGRESS, just update timestamp
            task.status == TaskStatus.IN_PROGRESS -> {
                val updated = task.copy(updatedAt = now)
                TaskRepository.update(updated)
                updated
            }
            else -> task
        }

        // STEP 8: Retrieve all proposals (including the one just submitted)
        val allProposals = ProposalRepository.findByTask(taskId).reversed()

        // STEP 9: Build result DTOs
        val taskDTO = Result.TaskDTO(
            id = updatedTask.id.value,
            title = updatedTask.title,
            description = updatedTask.description,
            type = updatedTask.type.name,
            status = updatedTask.status.name,
            routing = updatedTask.routing.name,
            assigneeIds = updatedTask.assigneeIds.map { it.value },
            dependencies = updatedTask.dependencies.map { it.value },
            complexity = updatedTask.complexity,
            risk = updatedTask.risk,
            createdAt = updatedTask.createdAt.toEpochMilli(),
            updatedAt = updatedTask.updatedAt?.toEpochMilli(),
            dueAt = updatedTask.dueAt?.toEpochMilli(),
            metadata = updatedTask.metadata
        )

        val proposalDTOs = allProposals.map { pz ->
            Result.ProposalDTO(
                id = pz.id.value,
                taskId = pz.taskId.value,
                agentId = pz.agentId.value,
                inputType = pz.inputType.name,
                confidence = pz.confidence,
                tokenUsage = mapOf(
                    "inputTokens" to (pz.tokenUsage?.inputTokens ?: 0),
                    "outputTokens" to (pz.tokenUsage?.outputTokens ?: 0)
                ),
                content = pz.content,
                createdAt = pz.createdAt.toEpochMilli(),
                metadata = pz.metadata
            )
        }

        val historyDTOs = ctx.history.map { m ->
            Result.MessageDTO(
                id = m.id,
                role = m.role.name,
                content = m.content,
                agentId = m.agentId?.value,
                tokens = m.tokens,
                ts = m.ts.toEpochMilli(),
                metadataJson = m.metadataJson
            )
        }

        val fileOpsDTOs = ctx.fileHistory.map { fo ->
            Result.FileOpDTO(
                agentId = fo.agentId.value,
                type = fo.type.name,
                path = fo.path,
                ts = fo.timestamp.toEpochMilli(),
                version = fo.version,
                diff = fo.diff,
                contentHash = fo.contentHash,
                conflict = fo.conflict,
                conflictReason = fo.conflictReason,
                metadata = fo.metadata
            )
        }

        val message = when {
            updatedTask.status == TaskStatus.COMPLETED && task.routing == RoutingStrategy.SOLO ->
                "Response submitted successfully (proposal: ${proposal.id.value}). SOLO task auto-completed."
            task.status == updatedTask.status ->
                "Response submitted successfully (proposal: ${proposal.id.value}). Task status remains ${updatedTask.status.name}"
            else ->
                "Response submitted successfully (proposal: ${proposal.id.value}). Task status updated to ${updatedTask.status.name}"
        }

        return Result(
            taskId = taskId.value,
            proposalId = proposal.id.value,
            inputType = inputType.name,
            taskStatus = updatedTask.status.name,
            task = taskDTO,
            proposals = proposalDTOs,
            context = Result.ContextDTO(
                history = historyDTOs,
                fileHistory = fileOpsDTOs
            ),
            message = message
        )
    }
}
