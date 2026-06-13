package com.orchestrator.mcp.tools

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.InputType
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.modules.consensus.ProposalManager
import com.orchestrator.storage.repositories.TaskRepository
import java.time.Instant

/**
 * MCP Tool: submit_input
 *
 * Accepts input/proposal content from an agent for a specific task, validates the
 * task state, persists the proposal via ProposalManager (which also notifies any
 * waiters), and updates the task status accordingly.
 */
class SubmitInputTool {

    data class Params(
        val taskId: String,
        val agentId: String? = null,
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
        val message: String,
        val proposalStatus: ProposalStatus? = null
    ) {
        data class ProposalStatus(
            val totalAssignees: Int,
            val submittedCount: Int,
            val pendingAgents: List<String>,
            val allProposalsCollected: Boolean,
            val nextActionGuidance: String
        )
    }

    fun execute(p: Params, resolvedAgentId: String): Result {
        // Validate params
        require(p.taskId.isNotBlank()) { "taskId cannot be blank" }
        require(resolvedAgentId.isNotBlank()) { "agentId cannot be blank" }

        val taskId = TaskId(p.taskId.trim())
        val agentId = AgentId(resolvedAgentId.trim())

        // Validate task existence and that it expects input
        val task = TaskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task '${p.taskId}' does not exist")

        if (task.status !in setOf(TaskStatus.WAITING_INPUT, TaskStatus.IN_PROGRESS)) {
            throw IllegalStateException(
                "Task '${task.id.value}' is in status ${task.status}; expected IN_PROGRESS or WAITING_INPUT to accept input"
            )
        }

        // Parse input type (default to OTHER)
        val itype = when (val t = p.inputType?.trim()?.uppercase()) {
            null, "" -> InputType.OTHER
            else -> runCatching { InputType.valueOf(t) }
                .getOrElse { throw IllegalArgumentException("Invalid inputType '$t'. Allowed: ${InputType.values().joinToString(",")}") }
        }

        // Confidence default and bounds will be validated by Proposal constructor; clamp/forward here
        val confidence = p.confidence ?: 0.5
        require(confidence in 0.0..1.0) { "confidence must be in [0.0, 1.0]" }

        // Store via ProposalManager (also signals any waiters)
        val proposal = ProposalManager.submitProposal(
            taskId = taskId,
            agentId = agentId,
            content = p.content,
            inputType = itype,
            confidence = confidence,
            metadata = p.metadata ?: emptyMap()
        )

        // Atomically move the task to IN_PROGRESS (resume after input) and refresh updatedAt.
        // CAS guard prevents reactivating a task that moved to a terminal state concurrently;
        // the read-then-check above only catches the serialized case.
        val now = Instant.now()
        val moved = TaskRepository.updateStatus(
            id = taskId,
            newStatus = TaskStatus.IN_PROGRESS,
            expectedOldStatuses = setOf(TaskStatus.WAITING_INPUT, TaskStatus.IN_PROGRESS)
        )
        if (!moved) {
            throw IllegalStateException(
                "Task '${task.id.value}' is no longer accepting input (status changed concurrently)"
            )
        }

        // Calculate proposal status for consensus tasks
        val proposalStatus = if (task.routing.name == "CONSENSUS" && task.assigneeIds.size > 1) {
            calculateProposalStatus(task, taskId)
        } else null

        return Result(
            taskId = taskId.value,
            proposalId = proposal.id.value,
            inputType = itype.name,
            taskStatus = TaskStatus.IN_PROGRESS.name,
            message = if (task.status == TaskStatus.IN_PROGRESS) {
                "Input accepted and task status remains ${TaskStatus.IN_PROGRESS.name}"
            } else {
                "Input accepted and task status updated to ${TaskStatus.IN_PROGRESS.name}"
            },
            proposalStatus = proposalStatus
        )
    }

    private fun calculateProposalStatus(task: com.orchestrator.domain.Task, taskId: TaskId): Result.ProposalStatus {
        val allProposals = com.orchestrator.storage.repositories.ProposalRepository.findByTask(taskId)
        val submittedAgentIds = allProposals.map { it.agentId }.toSet()
        val pendingAgentIds = task.assigneeIds.filterNot { it in submittedAgentIds }
        val allCollected = pendingAgentIds.isEmpty()

        val guidance = when {
            allCollected -> "All proposals collected! Return to primary agent and say 'Continue task ${taskId.value}' for synthesis and completion."
            pendingAgentIds.size == 1 -> "Waiting for 1 agent: ${pendingAgentIds.first().value}. Switch to that agent to collect their proposal."
            else -> "Waiting for ${pendingAgentIds.size} agents: ${pendingAgentIds.joinToString(", ") { it.value }}. Primary agent will synthesize after all proposals are in."
        }

        return Result.ProposalStatus(
            totalAssignees = task.assigneeIds.size,
            submittedCount = submittedAgentIds.size,
            pendingAgents = pendingAgentIds.map { it.value },
            allProposalsCollected = allCollected,
            nextActionGuidance = guidance
        )
    }

    companion object {
        // JSON schema for MCP registration
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "submit_input params",
          "type": "object",
          "required": ["taskId", "content"],
          "properties": {
            "taskId": {"type": "string", "minLength": 1},
            "agentId": {
              "type": ["string", "null"],
              "minLength": 1,
              "description": "Optional. Defaults to the only configured agent; aliases 'user'/'me' resolve to that agent."
            },
            "content": {"type": ["null", "string", "number", "boolean", "array", "object"]},
            "inputType": {"type": ["string", "null"], "enum": [
              "ARCHITECTURAL_PLAN", "CODE_REVIEW", "IMPLEMENTATION_PLAN", "TEST_PLAN", "REFACTORING_SUGGESTION", "RESEARCH_SUMMARY", "OTHER"
            ]},
            "confidence": {"type": ["number", "null"], "minimum": 0.0, "maximum": 1.0},
            "metadata": {"type": ["object", "null"], "additionalProperties": {"type": "string"}}
          },
          "additionalProperties": false
        }
        """
    }
}
