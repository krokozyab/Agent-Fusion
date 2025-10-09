package com.orchestrator.modules.context

import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * FileRegistry: Track file operations across agents per task.
 * - recordFileOperation(taskId, agentId, operation)
 * - getFileHistory(taskId): List<FileOperation>
 * - Detects conflicts using expected base hash and simple state rules
 * - Supports diffs (line-based unified diff)
 *
 * Notes:
 * - This implementation is in-memory and thread-safe. It can be swapped with a persistent
 *   implementation later if needed without changing the API.
 */
object FileRegistry {
    enum class OperationType { CREATE, UPDATE, DELETE, RENAME }

    data class Operation(
        val path: String,
        val type: OperationType,
        val content: String? = null,           // for CREATE/UPDATE (new content)
        val expectedBaseHash: String? = null,  // for UPDATE/DELETE conflict detection
        val renameTo: String? = null,          // for RENAME
        val metadata: Map<String, String> = emptyMap()
    )

    data class FileOperation(
        val id: Long,
        val taskId: TaskId,
        val agentId: AgentId,
        val path: String,
        val type: OperationType,
        val timestamp: Instant = Instant.now(),
        val diff: String? = null,
        val contentHash: String? = null,
        val version: Int,
        val conflict: Boolean = false,
        val conflictReason: String? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    data class FileConflict(
        val operation: FileOperation,
        val reason: String
    )

    private data class FileState(
        var content: String?,
        var version: Int,
        var hash: String?
    )

    // Histories per task (append-only list)
    private val histories = ConcurrentHashMap<String, MutableList<FileOperation>>()

    // File states per taskId -> path -> state
    private val states = ConcurrentHashMap<String, ConcurrentHashMap<String, FileState>>()

    // Simple in-memory incrementing id
    @Volatile private var opSeq: Long = 0
    private fun nextId(): Long = synchronized(this) { ++opSeq }

    /**
     * Record a file operation for a task by an agent. Returns the recorded FileOperation.
     * Conflict detection rules:
     * - CREATE: conflict if file already exists
     * - UPDATE: conflict if expectedBaseHash is provided and doesn't match current hash
     * - DELETE: conflict if file does not exist, or expectedBaseHash provided and mismatched
     * - RENAME: conflict if source missing or destination already exists
     *
     * When a conflict occurs, operation is recorded with conflict=true and state is NOT mutated.
     */
    fun recordFileOperation(taskId: TaskId, agentId: AgentId, operation: Operation): FileOperation {
        val taskKey = taskId.value
        val byPath = states.computeIfAbsent(taskKey) { ConcurrentHashMap() }
        val now = Instant.now()

        val current = byPath[operation.path]
        val (conflict, reason) = when (operation.type) {
            OperationType.CREATE -> if (current != null) true to "File already exists" else false to null
            OperationType.UPDATE -> when {
                current == null -> true to "File does not exist"
                operation.expectedBaseHash != null && current.hash != operation.expectedBaseHash -> true to "Base hash mismatch"
                else -> false to null
            }
            OperationType.DELETE -> when {
                current == null -> true to "File does not exist"
                operation.expectedBaseHash != null && current.hash != operation.expectedBaseHash -> true to "Base hash mismatch"
                else -> false to null
            }
            OperationType.RENAME -> {
                val dst = operation.renameTo ?: return recordAsConflict(taskId, agentId, operation, now, "renameTo not provided")
                val existsDst = byPath.containsKey(dst)
                when {
                    current == null -> true to "Source does not exist"
                    existsDst -> true to "Destination already exists"
                    else -> false to null
                }
            }
        }

        // Compute diff against current content
        val oldContent = current?.content ?: ""
        val newContent = when (operation.type) {
            OperationType.CREATE -> operation.content ?: ""
            OperationType.UPDATE -> operation.content ?: oldContent
            OperationType.DELETE -> ""
            OperationType.RENAME -> oldContent // rename does not change content
        }
        val diff = when (operation.type) {
            OperationType.RENAME -> null // keep rename metadata only
            else -> unifiedDiff(operation.path, oldContent, newContent)
        }

        val newHash = when (operation.type) {
            OperationType.DELETE -> null
            OperationType.RENAME -> current?.hash
            else -> sha256(newContent)
        }

        val newVersion = if (!conflict) {
            when (operation.type) {
                OperationType.CREATE -> 1
                OperationType.UPDATE -> (current?.version ?: 0) + 1
                OperationType.DELETE -> (current?.version ?: 0) + 1
                OperationType.RENAME -> (current?.version ?: 0)
            }
        } else {
            current?.version ?: 0
        }

        val op = FileOperation(
            id = nextId(),
            taskId = taskId,
            agentId = agentId,
            path = operation.path,
            type = operation.type,
            timestamp = now,
            diff = diff,
            contentHash = newHash,
            version = newVersion,
            conflict = conflict,
            conflictReason = reason,
            metadata = operation.metadata
        )

        // Append to history
        val list = histories.computeIfAbsent(taskKey) { mutableListOf() }
        synchronized(list) { list.add(op) }

        // Mutate state if not a conflict
        if (!conflict) {
            when (operation.type) {
                OperationType.CREATE -> byPath[operation.path] = FileState(operation.content ?: "", 1, newHash)
                OperationType.UPDATE -> byPath[operation.path] = FileState(newContent, newVersion, newHash)
                OperationType.DELETE -> byPath.remove(operation.path)
                OperationType.RENAME -> {
                    val dst = operation.renameTo!!
                    // Move state
                    if (current != null) {
                        byPath.remove(operation.path)
                        byPath[dst] = FileState(current.content, current.version, current.hash)
                    }
                }
            }
        }

        return op
    }

    /** Retrieve immutable snapshot of file operation history for a task. */
    fun getFileHistory(taskId: TaskId): List<FileOperation> {
        val list = histories[taskId.value] ?: return emptyList()
        synchronized(list) { return list.toList() }
    }

    /** Optional helper: return all conflicted operations for the task. */
    fun detectConflicts(taskId: TaskId): List<FileConflict> = getFileHistory(taskId)
        .filter { it.conflict }
        .map { FileConflict(it, it.conflictReason ?: "Conflict") }

    // -------------- Internal helpers --------------

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray())
        .joinToString("") { b -> "%02x".format(b) }

    private fun recordAsConflict(taskId: TaskId, agentId: AgentId, operation: Operation, now: Instant, reason: String): FileOperation {
        val op = FileOperation(
            id = nextId(),
            taskId = taskId,
            agentId = agentId,
            path = operation.path,
            type = operation.type,
            timestamp = now,
            diff = null,
            contentHash = null,
            version = currentVersion(taskId, operation.path),
            conflict = true,
            conflictReason = reason,
            metadata = operation.metadata
        )
        val list = histories.computeIfAbsent(taskId.value) { mutableListOf() }
        synchronized(list) { list.add(op) }
        return op
    }

    private fun currentVersion(taskId: TaskId, path: String): Int {
        return states[taskId.value]?.get(path)?.version ?: 0
    }

    /**
     * Produce a simple unified diff. This is a lightweight implementation
     * and not a fully RFC 4184 compliant patch format, but sufficient for
     * human-readable inspection and testing.
     */
    private fun unifiedDiff(path: String, oldText: String, newText: String): String {
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        val sb = StringBuilder()
        sb.append("--- a/").append(path).append('\n')
        sb.append("+++ b/").append(path).append('\n')

        // Very simple line-by-line comparison with context of 0
        val max = maxOf(oldLines.size, newLines.size)
        for (i in 0 until max) {
            val oldLine = oldLines.getOrNull(i)
            val newLine = newLines.getOrNull(i)
            when {
                oldLine == null && newLine != null -> sb.append("+").append(newLine).append('\n')
                newLine == null && oldLine != null -> sb.append("-").append(oldLine).append('\n')
                oldLine != newLine -> {
                    if (oldLine != null) sb.append("-").append(oldLine).append('\n')
                    if (newLine != null) sb.append("+").append(newLine).append('\n')
                }
                else -> { /* equal line omitted to keep diff concise */ }
            }
        }
        return sb.toString()
    }
}
