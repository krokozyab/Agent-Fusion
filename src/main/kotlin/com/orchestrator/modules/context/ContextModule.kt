package com.orchestrator.modules.context

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import com.orchestrator.modules.context.FileRegistry.Operation
import com.orchestrator.modules.context.FileRegistry.FileOperation
import com.orchestrator.modules.context.MemoryManager.ConversationMessage
import com.orchestrator.modules.context.MemoryManager.Role
import com.orchestrator.storage.Transaction
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * ContextModule
 * - Provides unified, efficient access to task context: conversation memory, file ops history, snapshots
 * - Coordinates atomic updates for DB-backed parts (messages, snapshots)
 * - File operations are recorded after DB commit and never throw; conflicts are captured in results
 */
interface ContextService {
    fun getTaskContext(taskId: TaskId, maxTokens: Int): ContextModule.TaskContext
    fun updateContext(taskId: TaskId, updates: ContextModule.ContextUpdates): ContextModule.UpdateResult
    fun shutdown() {}
}

object ContextModule {

    // -------- Public API types --------

    data class TaskContext(
        val taskId: TaskId,
        val history: List<ConversationMessage>,
        val fileHistory: List<FileOperation>
    )

    data class MessageUpdate(
        val role: Role,
        val content: String,
        val agentId: AgentId? = null,
        val tokens: Int? = null,
        val metadataJson: String? = null,
        val ts: Instant = Instant.now()
    )

    data class FileOpUpdate(
        val agentId: AgentId,
        val operation: Operation
    )

    data class ContextUpdates(
        val messages: List<MessageUpdate> = emptyList(),
        val fileOps: List<FileOpUpdate> = emptyList(),
        val snapshotLabel: String? = null
    )

    data class UpdateResult(
        val appendedMessageIds: List<Long>,
        val snapshotId: Long?,
        val fileOperations: List<FileOperation>
    )

    // -------- Retrieval --------

    /**
     * Efficiently retrieve a task's context.
     * Memory retrieval automatically summarizes if token budget exceeded.
     */
    fun getTaskContext(taskId: TaskId, maxTokens: Int = 4000): TaskContext {
        val history = MemoryManager.getHistory(taskId, maxTokens)
        val fileHistory = FileRegistry.getFileHistory(taskId)
        return TaskContext(taskId, history, fileHistory)
    }

    // -------- Updates --------

    /**
     * Apply updates to a task's context. Ensures DB-backed parts (messages, snapshots) are atomic.
     * File operations are recorded after DB commit and never throw; conflicts are represented on the returned operations.
     */
    fun updateContext(taskId: TaskId, updates: ContextUpdates): UpdateResult = runBlocking {
        // 1) Do DB-backed work atomically (messages + optional snapshot creation)
        val (messageIds, maybeSnapshotId) = Transaction.transaction { _ ->
            val ids = mutableListOf<Long>()
            // Append messages
            for (m in updates.messages) {
                val id = MemoryManager.appendMessage(
                    taskId = taskId,
                    role = m.role,
                    content = m.content,
                    agentId = m.agentId,
                    tokens = m.tokens,
                    metadataJson = m.metadataJson,
                    ts = m.ts
                )
                ids.add(id)
            }

            // Optional snapshot capture of the latest full state bundle
            val snapshotId: Long? = updates.snapshotLabel?.let { label ->
                // Create TaskState snapshot JSON via StateManager and persist via SnapshotRepository
                val snapId = StateManager.createSnapshot(taskId, label)
                snapId
            }

            ids to snapshotId
        }

        // 2) Record file operations after commit (no throws; conflicts represented in records)
        val recordedOps = mutableListOf<FileOperation>()
        for (fo in updates.fileOps) {
            val op = FileRegistry.recordFileOperation(taskId, fo.agentId, fo.operation)
            recordedOps.add(op)
        }

        UpdateResult(
            appendedMessageIds = messageIds,
            snapshotId = maybeSnapshotId,
            fileOperations = recordedOps
        )
    }
}

class DefaultContextService : ContextService {
    override fun getTaskContext(taskId: TaskId, maxTokens: Int): ContextModule.TaskContext =
        ContextModule.getTaskContext(taskId, maxTokens)

    override fun updateContext(
        taskId: TaskId,
        updates: ContextModule.ContextUpdates
    ): ContextModule.UpdateResult = ContextModule.updateContext(taskId, updates)
}
