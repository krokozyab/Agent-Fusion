package com.orchestrator.mcp.tools

import com.orchestrator.domain.*
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.storage.repositories.ProposalRepository
import com.orchestrator.storage.repositories.TaskRepository
import java.time.Instant

/**
 * MCP Tool: continue_task
 *
 * Responsibilities:
 * - Fetch task by ID
 * - Retrieve all proposals for the task
 * - Retrieve full context (conversation history + file operations) via ContextModule
 * - Update task status to IN_PROGRESS (if applicable)
 * - Return a complete package for the caller to resume execution quickly
 */
class ContinueTaskTool {

    data class Params(
        val taskId: String,
        val maxTokens: Int? = null // optional cap for context (history + fileHistory); defaults applied internally
    )

    companion object {
        const val MAX_ALLOWED_TOKENS = 120_000
        const val DEFAULT_MAX_TOKENS = 6_000
        const val MIN_TOKENS = 1_000

        // Liberal JSON schema for MCP registration
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "continue_task params",
          "type": "object",
          "required": ["taskId"],
          "properties": {
            "taskId": {"type": "string", "minLength": 1},
            "maxTokens": {"type": ["integer", "null"], "minimum": $MIN_TOKENS, "maximum": $MAX_ALLOWED_TOKENS}
          },
          "additionalProperties": false
        }
        """
    }

    data class Result(
        val task: TaskDTO,
        val proposals: List<ProposalDTO>,
        val context: ContextDTO
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

    fun execute(p: Params): Result {
        require(p.taskId.isNotBlank()) { "taskId cannot be blank" }
        val id = TaskId(p.taskId)

        // 1) Fetch task
        val task = TaskRepository.findById(id)
            ?: throw IllegalArgumentException("Task not found: ${p.taskId}")

        // 2) Retrieve proposals (oldest-first for chronological narrative)
        val proposals = ProposalRepository.findByTask(id).reversed()

        // 3) Validate and clamp maxTokens to policy limits
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

        // 4) Retrieve context with budget enforcement (applied to history + fileHistory)
        val ctx = ContextModule.getTaskContext(id, maxTokens)

        // 5) Update status to IN_PROGRESS using narrow UPDATE to prevent concurrent overwrites
        val statusChanged = when (task.status) {
            TaskStatus.PENDING, TaskStatus.WAITING_INPUT ->
                TaskRepository.updateStatus(
                    id,
                    TaskStatus.IN_PROGRESS,
                    setOf(TaskStatus.PENDING, TaskStatus.WAITING_INPUT)
                )
            else -> false
        }

        // 6) Refetch task if status was updated to get latest state
        val updatedTask = if (statusChanged) {
            TaskRepository.findById(id) ?: task
        } else {
            task
        }

        // 7) Build result DTOs
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

        val proposalDTOs = proposals.map { pz ->
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

        return Result(
            task = taskDTO,
            proposals = proposalDTOs,
            context = Result.ContextDTO(
                history = historyDTOs,
                fileHistory = fileOpsDTOs
            )
        )
    }

}
