package com.orchestrator.modules.context

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import com.orchestrator.context.bootstrap.BootstrapProgressTracker
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.modules.context.FileRegistry.Operation
import com.orchestrator.modules.context.FileRegistry.FileOperation
import com.orchestrator.modules.context.MemoryManager.ConversationMessage
import com.orchestrator.modules.context.MemoryManager.Role
import com.orchestrator.storage.Transaction
import com.orchestrator.utils.Logger
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths
import java.time.Instant
import java.util.Locale

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
    private val log = Logger.logger("com.orchestrator.modules.context.ContextModule")

    @Volatile
    private var moduleConfig: ContextConfig = ContextConfig()

    fun configure(config: ContextConfig) {
        moduleConfig = config
        log.info("Context module configured (enabled={}, mode={})", config.enabled, config.mode)
    }

    fun configuration(): ContextConfig = moduleConfig

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

    enum class IndexHealthStatus { HEALTHY, DEGRADED, CRITICAL }

    enum class FileIndexStatus { INDEXED, OUTDATED, PENDING, ERROR }

    data class FileIndexEntry(
        val path: String,
        val status: FileIndexStatus,
        val sizeBytes: Long,
        val lastModified: Instant?,
        val chunkCount: Int
    )

    data class IndexStatusSnapshot(
        val totalFiles: Int,
        val indexedFiles: Int,
        val pendingFiles: Int,
        val failedFiles: Int,
        val lastRefresh: Instant?,
        val health: IndexHealthStatus,
        val files: List<FileIndexEntry>
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

    fun getIndexStatus(limit: Int? = null): IndexStatusSnapshot {
        val entriesByPath = linkedMapOf<String, FileIndexEntry>()
        var maxIndexedAt: Instant? = null

        ContextDatabase.withConnection { conn ->
            val sql = buildString {
                append(
                    """
                    SELECT fs.rel_path,
                           fs.size_bytes,
                           fs.mtime_ns,
                           fs.indexed_at,
                           COUNT(c.chunk_id) AS chunk_count
                    FROM file_state fs
                    LEFT JOIN chunks c ON c.file_id = fs.file_id
                    WHERE fs.is_deleted = FALSE
                    GROUP BY fs.rel_path, fs.size_bytes, fs.mtime_ns, fs.indexed_at
                    ORDER BY fs.rel_path
                    """.trimIndent()
                )
                if (limit != null && limit > 0) {
                    append(" LIMIT ?")
                }
            }

            conn.prepareStatement(sql).use { ps ->
                if (limit != null && limit > 0) {
                    ps.setInt(1, limit)
                }
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val path = rs.getString("rel_path") ?: continue
                        val size = rs.getLong("size_bytes")
                        val modifiedNs = rs.getLong("mtime_ns")
                        val indexedAt = rs.getTimestamp("indexed_at")?.toInstant()
                        val chunkCount = rs.getInt("chunk_count")

                        val lastModified = toInstantOrNull(modifiedNs)
                        val status = when {
                            indexedAt == null -> FileIndexStatus.PENDING
                            lastModified != null && lastModified.isAfter(indexedAt) -> FileIndexStatus.OUTDATED
                            else -> FileIndexStatus.INDEXED
                        }

                        entriesByPath[path] = FileIndexEntry(
                            path = path,
                            status = status,
                            sizeBytes = size,
                            lastModified = lastModified,
                            chunkCount = chunkCount
                        )

                        if (indexedAt != null) {
                            maxIndexedAt = when {
                                maxIndexedAt == null -> indexedAt
                                indexedAt.isAfter(maxIndexedAt) -> indexedAt
                                else -> maxIndexedAt
                            }
                        }
                    }
                }
            }
        }

        runCatching {
            ContextDatabase.withConnection { conn ->
                conn.prepareStatement(
                    "SELECT path, status FROM ${BootstrapProgressTracker.TABLE_NAME}"
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val rawPath = rs.getString("path") ?: continue
                            val status = rs.getString("status")?.uppercase(Locale.US) ?: continue
                            val normalized = normalizePath(rawPath)
                            when (status) {
                                "FAILED" -> {
                                    val existing = entriesByPath[normalized]
                                    val entry = (existing?.copy(status = FileIndexStatus.ERROR))
                                        ?: FileIndexEntry(
                                            path = normalized,
                                            status = FileIndexStatus.ERROR,
                                            sizeBytes = 0,
                                            lastModified = null,
                                            chunkCount = 0
                                        )
                                    entriesByPath[normalized] = entry
                                }
                                "PENDING", "PROCESSING" -> {
                                    entriesByPath.putIfAbsent(
                                        normalized,
                                        FileIndexEntry(
                                            path = normalized,
                                            status = FileIndexStatus.PENDING,
                                            sizeBytes = 0,
                                            lastModified = null,
                                            chunkCount = 0
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure { throwable ->
            log.debug("Bootstrap progress table unavailable: {}", throwable.message)
        }

        val files = entriesByPath.values.sortedBy { it.path }
        val totalFiles = files.size
        val indexedFiles = files.count { it.status == FileIndexStatus.INDEXED }
        val pendingFiles = files.count { it.status == FileIndexStatus.PENDING }
        val failedFiles = files.count { it.status == FileIndexStatus.ERROR }

        val health = when {
            failedFiles > 0 -> IndexHealthStatus.CRITICAL
            files.any { it.status == FileIndexStatus.OUTDATED } || pendingFiles > 0 -> IndexHealthStatus.DEGRADED
            else -> IndexHealthStatus.HEALTHY
        }

        return IndexStatusSnapshot(
            totalFiles = totalFiles,
            indexedFiles = indexedFiles,
            pendingFiles = pendingFiles,
            failedFiles = failedFiles,
            lastRefresh = maxIndexedAt,
            health = health,
            files = files
        )
    }

    private fun toInstantOrNull(nanosSinceEpoch: Long): Instant? {
        if (nanosSinceEpoch <= 0L) return null
        val seconds = nanosSinceEpoch / 1_000_000_000L
        val nanos = (nanosSinceEpoch % 1_000_000_000L).toInt()
        return runCatching { Instant.ofEpochSecond(seconds, nanos.toLong()) }.getOrNull()
    }

    private fun normalizePath(rawPath: String): String = runCatching {
        val absolute = Paths.get(rawPath).toAbsolutePath().normalize()
        val root = Paths.get("").toAbsolutePath().normalize()
        if (absolute.startsWith(root)) root.relativize(absolute).toString() else absolute.toString()
    }.getOrElse { rawPath }
}

class DefaultContextService : ContextService {
    override fun getTaskContext(taskId: TaskId, maxTokens: Int): ContextModule.TaskContext =
        ContextModule.getTaskContext(taskId, maxTokens)

    override fun updateContext(
        taskId: TaskId,
        updates: ContextModule.ContextUpdates
    ): ContextModule.UpdateResult = ContextModule.updateContext(taskId, updates)
}
