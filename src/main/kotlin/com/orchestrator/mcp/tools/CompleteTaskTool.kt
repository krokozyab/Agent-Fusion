package com.orchestrator.mcp.tools

import com.orchestrator.domain.*
import com.orchestrator.storage.Transaction
import com.orchestrator.storage.repositories.DecisionRepository
import com.orchestrator.storage.repositories.MessageRepository
import com.orchestrator.storage.repositories.MetricsRepository
import com.orchestrator.storage.repositories.ProposalRepository
import com.orchestrator.storage.repositories.SnapshotRepository
import com.orchestrator.storage.repositories.TaskRepository
import com.orchestrator.utils.Logger
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID

/**
 * MCP Tool: complete_task
 *
 * Responsibilities:
 * - Accept completion result (summary, artifacts)
 * - Update task status to COMPLETED
 * - Record decision if consensus payload provided
 * - Calculate and record token metrics
 * - Store final artifacts as context snapshots (and optional final message)
 *
 * IMPROVEMENTS (Consensus-driven):
 * - Transaction safety: All operations wrapped in atomic transaction
 * - Consensus validation: CONSENSUS tasks require decision payload
 * - Decision integrity: Validates proposals exist and match task
 * - Agent attribution: Records who completed the task
 * - Status validation: Prevents re-completion
 * - Error handling: Proper validation and warnings
 * - Token accounting: Fixed conversation token calculation
 */
class CompleteTaskTool {

    private val logger = Logger.logger<CompleteTaskTool>()

    // ---- Input/Output DTOs ----
    data class Params(
        val taskId: String,
        val resultSummary: String? = null,
        val decision: DecisionDTO? = null,
        val tokenMetrics: TokenMetricsDTO? = null,
        val artifacts: Map<String, Any?>? = null,
        val snapshots: List<SnapshotDTO>? = null,
        val completedBy: String? = null  // Agent who completed the task
    ) {
        data class DecisionDTO(
            val considered: List<ConsideredDTO>,
            val selected: List<String>? = null,
            val winnerProposalId: String? = null,
            val agreementRate: Double? = null,
            val rationale: String? = null,
            val metadata: Map<String, String> = emptyMap()
        ) {
            data class ConsideredDTO(
                val proposalId: String,
                val agentId: String,
                val inputType: String,
                val confidence: Double,
                val tokenUsage: TokenMetricsDTO
            )
        }
        data class TokenMetricsDTO(
            val inputTokens: Int? = null,
            val outputTokens: Int? = null
        )
        data class SnapshotDTO(
            val label: String? = null,
            val payload: Any?
        )
    }

    data class Result(
        val taskId: String,
        val status: String,
        val decisionId: String?,
        val snapshotIds: List<Long>,
        val recordedMetrics: Map<String, Double>,
        val warnings: List<String> = emptyList()
    )

    fun execute(p: Params): Result = runBlocking {
        require(p.taskId.isNotBlank()) { "taskId cannot be blank" }
        val taskId = TaskId(p.taskId)
        val warnings = mutableListOf<String>()

        // Wrap entire operation in transaction for atomicity
        Transaction.transaction { conn ->
            val now = Instant.now()

            // 1) Fetch existing task and validate
            val existing = TaskRepository.findById(taskId)
                ?: throw IllegalArgumentException("Task not found: ${p.taskId}")

            // CRITICAL: Validate task can be completed
            when (existing.status) {
                TaskStatus.COMPLETED -> throw IllegalStateException(
                    "Task ${p.taskId} is already COMPLETED at ${existing.updatedAt}. Cannot complete again."
                )
                TaskStatus.FAILED -> {
                    warnings.add("Task was previously FAILED, now being marked COMPLETED")
                    logger.warn("Task ${p.taskId} transitioning from FAILED to COMPLETED")
                }
                else -> {} // PENDING, IN_PROGRESS, WAITING_INPUT are valid
            }

            // CRITICAL: Validate consensus requirements (Codex's top finding)
            if (existing.routing == RoutingStrategy.CONSENSUS) {
                if (p.decision == null) {
                    throw IllegalArgumentException(
                        "Task ${p.taskId} has routing=CONSENSUS but no decision payload provided. " +
                        "Consensus tasks MUST include decision data with considered proposals."
                    )
                }
                if (p.decision.considered.isEmpty()) {
                    throw IllegalArgumentException(
                        "Task ${p.taskId} consensus decision must have non-empty considered proposals list"
                    )
                }
            }

            val recordedMetrics = mutableMapOf<String, Double>()
            val snapshotIds = mutableListOf<Long>()
            var decisionId: DecisionId? = null

            // 2) If decision payload provided, validate and persist it
            if (p.decision != null) {
                val d = p.decision

                // CRITICAL: Validate decision data integrity against actual proposals
                val considered = d.considered.map { c ->
                    // Validate inputType enum
                    val inputType = try {
                        InputType.valueOf(c.inputType.uppercase())
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException(
                            "Invalid inputType '${c.inputType}'. Must be one of: ${InputType.entries.joinToString()}"
                        )
                    }

                    // Validate numeric constraints
                    require(c.confidence in 0.0..1.0) {
                        "Confidence ${c.confidence} for proposal ${c.proposalId} must be in range [0.0, 1.0]"
                    }

                    // CRITICAL: Verify proposal exists and matches task (Codex's second finding)
                    val proposalId = ProposalId(c.proposalId)
                    val actualProposal = ProposalRepository.findById(proposalId)
                        ?: throw IllegalArgumentException(
                            "Proposal ${c.proposalId} referenced in decision does not exist in database"
                        )

                    if (actualProposal.taskId != taskId) {
                        throw IllegalArgumentException(
                            "Proposal ${c.proposalId} belongs to task ${actualProposal.taskId.value}, " +
                            "not ${taskId.value}"
                        )
                    }

                    // Use authoritative data from DB, but warn if mismatch
                    if (actualProposal.agentId.value != c.agentId) {
                        warnings.add(
                            "Proposal ${c.proposalId} agentId mismatch: provided='${c.agentId}' " +
                            "but DB has '${actualProposal.agentId.value}'"
                        )
                    }
                    if (actualProposal.inputType != inputType) {
                        warnings.add(
                            "Proposal ${c.proposalId} inputType mismatch: provided='${c.inputType}' " +
                            "but DB has '${actualProposal.inputType}'"
                        )
                    }

                    // Build ProposalRef from authoritative proposal data
                    ProposalRef(
                        id = actualProposal.id,
                        agentId = actualProposal.agentId,
                        inputType = actualProposal.inputType,
                        confidence = actualProposal.confidence,
                        tokenUsage = actualProposal.tokenUsage
                    )
                }

                // Validate winner and selected are in considered list
                val consideredIds = considered.map { it.id }.toSet()
                if (d.winnerProposalId != null) {
                    val winnerId = ProposalId(d.winnerProposalId)
                    require(winnerId in consideredIds) {
                        "winnerProposalId ${d.winnerProposalId} is not in considered proposals list"
                    }
                }
                d.selected?.forEach { selectedId ->
                    require(ProposalId(selectedId) in consideredIds) {
                        "Selected proposal $selectedId is not in considered proposals list"
                    }
                }

                // Validate agreementRate if provided
                d.agreementRate?.let { rate ->
                    require(rate in 0.0..1.0) {
                        "agreementRate $rate must be in range [0.0, 1.0]"
                    }
                }

                val selectedSet = d.selected?.map { ProposalId(it) }?.toSet() ?: emptySet()
                val winner = d.winnerProposalId?.let { ProposalId(it) }

                // Add completedBy to decision metadata
                val decisionMetadata = d.metadata.toMutableMap()
                p.completedBy?.let { decisionMetadata["completedBy"] = it }

                val dec = Decision(
                    id = DecisionId(generateDecisionId()),
                    taskId = taskId,
                    considered = considered,
                    selected = selectedSet,
                    winnerProposalId = winner,
                    agreementRate = d.agreementRate,
                    rationale = d.rationale,
                    metadata = decisionMetadata
                )
                DecisionRepository.upsert(dec)
                decisionId = dec.id

                // Record decision token savings metrics
                MetricsRepository.recordMetric(
                    name = "decision.token_savings.absolute",
                    value = dec.tokenSavingsAbsolute.toDouble(),
                    tags = mapOf("taskId" to taskId.value),
                    taskId = taskId.value,
                )
                recordedMetrics["decision.token_savings.absolute"] = dec.tokenSavingsAbsolute.toDouble()
                MetricsRepository.recordMetric(
                    name = "decision.token_savings.percent",
                    value = dec.tokenSavingsPercent,
                    tags = mapOf("taskId" to taskId.value),
                    taskId = taskId.value,
                )
                recordedMetrics["decision.token_savings.percent"] = dec.tokenSavingsPercent
            }

            // 3) Calculate/record token metrics
            val inputTokens = p.tokenMetrics?.inputTokens
            val outputTokens = p.tokenMetrics?.outputTokens
            if (inputTokens != null) {
                MetricsRepository.recordMetric(
                    name = "task.tokens.input",
                    value = inputTokens.toDouble(),
                    taskId = taskId.value
                )
                recordedMetrics["task.tokens.input"] = inputTokens.toDouble()
            }
            if (outputTokens != null) {
                MetricsRepository.recordMetric(
                    name = "task.tokens.output",
                    value = outputTokens.toDouble(),
                    taskId = taskId.value
                )
                recordedMetrics["task.tokens.output"] = outputTokens.toDouble()
            }
            if (inputTokens != null || outputTokens != null) {
                val total = (inputTokens ?: 0) + (outputTokens ?: 0)
                MetricsRepository.recordMetric(
                    name = "task.tokens.total",
                    value = total.toDouble(),
                    taskId = taskId.value
                )
                recordedMetrics["task.tokens.total"] = total.toDouble()
            }

            // 4) Store final artifacts/snapshots BEFORE calculating conversation tokens
            // 4a) If resultSummary provided, store as a final system message
            var summaryTokens = 0
            if (!p.resultSummary.isNullOrBlank()) {
                // Estimate tokens for summary (rough: 4 chars per token)
                summaryTokens = (p.resultSummary.length / 4).coerceAtLeast(1)
                MessageRepository.insert(
                    taskId = taskId,
                    role = "system",
                    content = p.resultSummary,
                    tokens = summaryTokens,
                    agentId = p.completedBy?.let { AgentId(it) },
                    metadataJson = null,
                    ts = now
                )
            }

            // 4b) Artifacts map as a single snapshot
            if (p.artifacts != null) {
                val json = anyToJsonLocal(p.artifacts) ?: "{}"
                val sid = SnapshotRepository.insert(
                    taskId = taskId,
                    decisionId = decisionId,
                    label = "final_artifacts",
                    snapshotJson = json,
                    createdAt = now
                )
                snapshotIds += sid
            }

            // 4c) Additional snapshots list
            p.snapshots?.forEach { s ->
                val json = anyToJsonLocal(s.payload) ?: "null"
                val sid = SnapshotRepository.insert(
                    taskId = taskId,
                    decisionId = decisionId,
                    label = s.label,
                    snapshotJson = json,
                    createdAt = now
                )
                snapshotIds += sid
            }

            // 5) Calculate conversation tokens AFTER adding summary (fixes Codex's bug finding)
            try {
                val convTokens = MessageRepository.countTokens(taskId)
                MetricsRepository.recordMetric(
                    name = "task.conversation.tokens.total",
                    value = convTokens.toDouble(),
                    taskId = taskId.value
                )
                recordedMetrics["task.conversation.tokens.total"] = convTokens.toDouble()
            } catch (e: Exception) {
                warnings.add("Failed to calculate conversation tokens: ${e.message}")
                logger.warn("Failed to calculate conversation tokens for task ${taskId.value}: ${e.message}")
            }

            // 6) Mark task as complete (LAST operation for safety)
            val completed = existing.copy(
                status = TaskStatus.COMPLETED,
                updatedAt = now,
                metadata = existing.metadata + mapOf("completedBy" to (p.completedBy ?: "unknown"))
            )
            TaskRepository.update(completed)

            Result(
                taskId = completed.id.value,
                status = completed.status.name,
                decisionId = decisionId?.value,
                snapshotIds = snapshotIds,
                recordedMetrics = recordedMetrics,
                warnings = warnings
            )
        }
    }

    companion object {
        // JSON Schema for MCP registration
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "complete_task params",
          "type": "object",
          "required": ["taskId"],
          "properties": {
            "taskId": {"type": "string", "minLength": 1},
            "resultSummary": {"type": ["string", "null"]},
            "completedBy": {"type": ["string", "null"], "description": "Agent ID that completed the task"},
            "tokenMetrics": {
              "type": ["object", "null"],
              "properties": {
                "inputTokens": {"type": ["integer", "null"], "minimum": 0},
                "outputTokens": {"type": ["integer", "null"], "minimum": 0}
              },
              "additionalProperties": false
            },
            "artifacts": {"type": ["object", "null"]},
            "snapshots": {
              "type": ["array", "null"],
              "items": {
                "type": "object",
                "properties": {
                  "label": {"type": ["string", "null"]},
                  "payload": {}
                },
                "required": ["payload"],
                "additionalProperties": false
              }
            },
            "decision": {
              "type": ["object", "null"],
              "properties": {
                "considered": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "proposalId": {"type": "string"},
                      "agentId": {"type": "string"},
                      "inputType": {"type": "string"},
                      "confidence": {"type": "number", "minimum": 0.0, "maximum": 1.0},
                      "tokenUsage": {
                        "type": "object",
                        "properties": {
                          "inputTokens": {"type": ["integer", "null"], "minimum": 0},
                          "outputTokens": {"type": ["integer", "null"], "minimum": 0}
                        },
                        "additionalProperties": false
                      }
                    },
                    "required": ["proposalId", "agentId", "inputType", "confidence", "tokenUsage"],
                    "additionalProperties": false
                  }
                },
                "selected": {"type": ["array", "null"], "items": {"type": "string"}},
                "winnerProposalId": {"type": ["string", "null"]},
                "agreementRate": {"type": ["number", "null"], "minimum": 0.0, "maximum": 1.0},
                "rationale": {"type": ["string", "null"]},
                "metadata": {"type": ["object", "null"]}
              },
              "required": ["considered"],
              "additionalProperties": false
            }
          },
          "additionalProperties": false
        }
        """
    }
}

private fun generateDecisionId(): String = "dec-" + UUID.randomUUID().toString()

// Minimal local JSON utility to serialize arbitrary artifact payloads to a string
private fun anyToJsonLocal(value: Any?): String? {
    return when (value) {
        null -> null
        is String -> "\"${escapeJsonLocal(value)}\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> mapAnyToJsonLocal(value)
        is List<*> -> listAnyToJsonLocal(value)
        else -> "\"${escapeJsonLocal(value.toString())}\""
    }
}

private fun mapAnyToJsonLocal(map: Map<*, *>): String {
    val parts = map.entries.joinToString(separator = ",") { (k, v) ->
        val key = k?.toString() ?: "null"
        val jsonVal = anyToJsonLocal(v) ?: "null"
        "\"${escapeJsonLocal(key)}\":$jsonVal"
    }
    return "{$parts}"
}

private fun listAnyToJsonLocal(list: List<*>): String {
    val parts = list.joinToString(separator = ",") { anyToJsonLocal(it) ?: "null" }
    return "[$parts]"
}

private fun escapeJsonLocal(s: String): String = buildString {
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> append(c)
    }
}
