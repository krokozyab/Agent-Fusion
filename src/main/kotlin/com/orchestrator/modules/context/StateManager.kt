package com.orchestrator.modules.context

import com.orchestrator.domain.*
import com.orchestrator.storage.repositories.DecisionRepository
import com.orchestrator.storage.repositories.ProposalRepository
import com.orchestrator.storage.repositories.SnapshotRepository
import com.orchestrator.storage.repositories.TaskRepository
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Public alias for snapshot identifiers stored in context_snapshots.id */
typealias SnapshotId = Long

/** Aggregated in-memory state captured for a task. */
data class TaskState(
    val task: Task,
    val proposals: List<Proposal>,
    val decisions: List<Decision>
)

/**
 * StateManager provides creation and restoration of compressed context snapshots.
 * - Snapshots capture full state (Task + Proposals + Decisions for the task)
 * - Serialized as JSON, compressed with gzip, Base64-encoded, and wrapped in an envelope JSON
 */
object StateManager {

    // Envelope stored in DB JSON column
    @Serializable
    private data class SnapshotEnvelope(
        val version: Int = 1,
        val compression: String = "gzip",
        val encoding: String = "base64",
        val createdAt: Long = System.currentTimeMillis(),
        val payload: String // base64(gzip(json(TaskStateDTO)))
    )

    // ----- Serializable DTOs to avoid polluting domain with serialization -----
    @Serializable private data class TaskDTO(
        val id: String,
        val title: String,
        val description: String? = null,
        val type: String,
        val status: String,
        val routing: String,
        val assigneeIds: List<String> = emptyList(),
        val dependencies: List<String> = emptyList(),
        val complexity: Int,
        val risk: Int,
        val createdAt: Long,
        val updatedAt: Long? = null,
        val dueAt: Long? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    @Serializable private data class TokenUsageDTO(val inputTokens: Int = 0, val outputTokens: Int = 0)

    @Serializable private data class ProposalDTO(
        val id: String,
        val taskId: String,
        val agentId: String,
        val inputType: String,
        val content: JsonElement? = JsonNull,
        val confidence: Double,
        val tokenUsage: TokenUsageDTO = TokenUsageDTO(),
        val createdAt: Long,
        val metadata: Map<String, String> = emptyMap()
    )

    @Serializable private data class ProposalRefDTO(
        val id: String,
        val agentId: String,
        val inputType: String,
        val confidence: Double,
        val tokenUsage: TokenUsageDTO
    )

    @Serializable private data class DecisionDTO(
        val id: String,
        val taskId: String,
        val considered: List<ProposalRefDTO>,
        val selected: List<String> = emptyList(),
        val winnerProposalId: String? = null,
        val agreementRate: Double? = null,
        val rationale: String? = null,
        val decidedAt: Long,
        val metadata: Map<String, String> = emptyMap()
    )

    @Serializable private data class TaskStateDTO(
        val task: TaskDTO,
        val proposals: List<ProposalDTO>,
        val decisions: List<DecisionDTO>
    )

    private val json = Json { 
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ----- Public API -----

    fun createSnapshot(taskId: TaskId, label: String? = null): SnapshotId {
        val task = TaskRepository.findById(taskId)
            ?: throw IllegalArgumentException("Task not found: ${taskId.value}")
        val proposals = ProposalRepository.findByTask(taskId)
        val decisions = DecisionRepository.listByTask(taskId)

        val dto = TaskStateDTO(
            task = toDTO(task),
            proposals = proposals.map { toDTO(it) },
            decisions = decisions.map { toDTO(it) }
        )

        val dataJson = json.encodeToString(TaskStateDTO.serializer(), dto)
        val compressedB64 = base64(gzip(dataJson.toByteArray(Charsets.UTF_8)))
        val envelope = SnapshotEnvelope(payload = compressedB64)
        val envelopeJson = json.encodeToString(SnapshotEnvelope.serializer(), envelope)

        return SnapshotRepository.insert(
            taskId = taskId,
            decisionId = null,
            label = label,
            snapshotJson = envelopeJson,
            createdAt = Instant.now()
        )
    }

    fun restoreSnapshot(snapshotId: SnapshotId): TaskState {
        val row = SnapshotRepository.findById(snapshotId)
            ?: throw IllegalArgumentException("Snapshot not found: $snapshotId")

        val env = json.decodeFromString(SnapshotEnvelope.serializer(), row.snapshotJson)
        require(env.compression == "gzip") { "Unsupported compression: ${env.compression}" }
        require(env.encoding == "base64") { "Unsupported encoding: ${env.encoding}" }
        val dataBytes = gunzip(fromBase64(env.payload))
        val dto = json.decodeFromString(TaskStateDTO.serializer(), dataBytes.toString(Charsets.UTF_8))
        return fromDTO(dto)
    }

    // ----- Mapping helpers -----

    private fun toDTO(t: Task): TaskDTO = TaskDTO(
        id = t.id.value,
        title = t.title,
        description = t.description,
        type = t.type.name,
        status = t.status.name,
        routing = t.routing.name,
        assigneeIds = t.assigneeIds.map { it.value },
        dependencies = t.dependencies.map { it.value },
        complexity = t.complexity,
        risk = t.risk,
        createdAt = t.createdAt.toEpochMilli(),
        updatedAt = t.updatedAt?.toEpochMilli(),
        dueAt = t.dueAt?.toEpochMilli(),
        metadata = t.metadata
    )

    private fun toDTO(p: Proposal): ProposalDTO = ProposalDTO(
        id = p.id.value,
        taskId = p.taskId.value,
        agentId = p.agentId.value,
        inputType = p.inputType.name,
        content = anyToJsonElement(p.content),
        confidence = p.confidence,
        tokenUsage = TokenUsageDTO(p.tokenUsage.inputTokens, p.tokenUsage.outputTokens),
        createdAt = p.createdAt.toEpochMilli(),
        metadata = p.metadata
    )

    private fun toDTO(d: Decision): DecisionDTO = DecisionDTO(
        id = d.id.value,
        taskId = d.taskId.value,
        considered = d.considered.map {
            ProposalRefDTO(
                id = it.id.value,
                agentId = it.agentId.value,
                inputType = it.inputType.name,
                confidence = it.confidence,
                tokenUsage = TokenUsageDTO(it.tokenUsage.inputTokens, it.tokenUsage.outputTokens)
            )
        },
        selected = d.selected.map { it.value },
        winnerProposalId = d.winnerProposalId?.value,
        agreementRate = d.agreementRate,
        rationale = d.rationale,
        decidedAt = d.decidedAt.toEpochMilli(),
        metadata = d.metadata
    )

    private fun fromDTO(dto: TaskStateDTO): TaskState {
        val task = Task(
            id = TaskId(dto.task.id),
            title = dto.task.title,
            description = dto.task.description,
            type = TaskType.valueOf(dto.task.type),
            status = TaskStatus.valueOf(dto.task.status),
            routing = RoutingStrategy.valueOf(dto.task.routing),
            assigneeIds = dto.task.assigneeIds.map { AgentId(it) }.toSet(),
            dependencies = dto.task.dependencies.map { TaskId(it) }.toSet(),
            complexity = dto.task.complexity,
            risk = dto.task.risk,
            createdAt = Instant.ofEpochMilli(dto.task.createdAt),
            updatedAt = dto.task.updatedAt?.let { Instant.ofEpochMilli(it) },
            dueAt = dto.task.dueAt?.let { Instant.ofEpochMilli(it) },
            metadata = dto.task.metadata
        )

        val proposals = dto.proposals.map { p ->
            Proposal(
                id = ProposalId(p.id),
                taskId = TaskId(p.taskId),
                agentId = AgentId(p.agentId),
                inputType = InputType.valueOf(p.inputType),
                content = jsonElementToAny(p.content),
                confidence = p.confidence,
                tokenUsage = TokenUsage(p.tokenUsage.inputTokens, p.tokenUsage.outputTokens),
                createdAt = Instant.ofEpochMilli(p.createdAt),
                metadata = p.metadata
            )
        }

        val decisions = dto.decisions.map { d ->
            Decision(
                id = DecisionId(d.id),
                taskId = TaskId(d.taskId),
                considered = d.considered.map {
                    ProposalRef(
                        id = ProposalId(it.id),
                        agentId = AgentId(it.agentId),
                        inputType = InputType.valueOf(it.inputType),
                        confidence = it.confidence,
                        tokenUsage = TokenUsage(it.tokenUsage.inputTokens, it.tokenUsage.outputTokens)
                    )
                },
                selected = d.selected.map { ProposalId(it) }.toSet(),
                winnerProposalId = d.winnerProposalId?.let { ProposalId(it) },
                agreementRate = d.agreementRate,
                rationale = d.rationale,
                decidedAt = Instant.ofEpochMilli(d.decidedAt),
                metadata = d.metadata
            )
        }
        return TaskState(task, proposals, decisions)
    }

    // ----- JSON Any conversion helpers -----

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        is Map<*, *> -> {
            val content = value.entries.associate { (k, v) -> k.toString() to anyToJsonElement(v) }
            JsonObject(content)
        }
        else -> JsonPrimitive(value.toString()) // fallback safe string
    }

    private fun jsonElementToAny(el: JsonElement?): Any? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.booleanOrNull != null -> el.boolean
            el.longOrNull != null -> el.long
            el.doubleOrNull != null -> el.double
            else -> el.content
        }
        is JsonArray -> el.map { jsonElementToAny(it) }
        is JsonObject -> el.mapValues { jsonElementToAny(it.value) }
        else -> null
    }

    // ----- Compression helpers -----

    private fun gzip(input: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(input) }
        return baos.toByteArray()
    }

    private fun gunzip(input: ByteArray): ByteArray {
        GZIPInputStream(ByteArrayInputStream(input)).use { gz ->
            val buffer = ByteArray(8 * 1024)
            val out = ByteArrayOutputStream()
            while (true) {
                val n = gz.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun fromBase64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
