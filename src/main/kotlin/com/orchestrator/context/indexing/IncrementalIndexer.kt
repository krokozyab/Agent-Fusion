package com.orchestrator.context.indexing

import com.orchestrator.context.ContextDataService
import com.orchestrator.utils.Logger
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * Coordinates incremental indexing by diffing filesystem state, re-indexing stale files, and
 * removing artefacts for files that have been deleted.
 */
class IncrementalIndexer(
    private val changeDetector: ChangeDetector,
    private val batchIndexer: BatchIndexer,
    private val dataService: ContextDataService = ContextDataService(),
    private val clock: Clock = Clock.systemUTC()
) {

    private val log = Logger.logger("com.orchestrator.context.indexing.IncrementalIndexer")

    /**
     * Blocking entry-point that performs an incremental update for the provided file paths.
     */
    fun update(
        paths: List<Path>,
        parallelism: Int? = null,
        onProgress: ((BatchProgress) -> Unit)? = null
    ): UpdateResult = runBlocking {
        updateAsync(paths, parallelism, onProgress)
    }

    /**
     * Suspend-friendly variant allowing callers to integrate with existing coroutine scopes.
     */
    suspend fun updateAsync(
        paths: List<Path>,
        parallelism: Int? = null,
        onProgress: ((BatchProgress) -> Unit)? = null
    ): UpdateResult {
        val startedAt = Instant.now(clock)
        val changeSet = changeDetector.detectChanges(paths)

        val candidates = (changeSet.newFiles + changeSet.modifiedFiles).map { it.path }
        val batchResult = when {
            candidates.isEmpty() -> null
            parallelism != null -> batchIndexer.indexFilesAsync(candidates, parallelism, onProgress)
            else -> batchIndexer.indexFilesAsync(candidates, onProgress = onProgress)
        }

        val deletionResults = changeSet.deletedFiles.map { deleted ->
            runCatching {
                val removed = dataService.deleteFile(deleted.relativePath)
                if (removed) {
                    log.debug("Removed artefacts for {}", deleted.relativePath)
                    DeletionResult(deleted.relativePath, true, null)
                } else {
                    val message = "No persisted state found for ${deleted.relativePath}"
                    log.warn(message)
                    DeletionResult(deleted.relativePath, false, message)
                }
            }.getOrElse { throwable ->
                val message = throwable.message ?: throwable::class.simpleName ?: "Unknown deletion error"
                log.error("Failed deleting artefacts for ${deleted.relativePath}: $message", throwable)
                DeletionResult(deleted.relativePath, false, message)
            }
        }

        val completedAt = Instant.now(clock)
        val duration = Duration.between(startedAt, completedAt).toMillis()

        return UpdateResult(
            changeSet = changeSet,
            batchResult = batchResult,
            deletions = deletionResults,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMillis = duration
        )
    }
}

data class UpdateResult(
    val changeSet: ChangeSet,
    val batchResult: BatchResult?,
    val deletions: List<DeletionResult>,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMillis: Long
) {
    val newCount: Int get() = changeSet.newFiles.size
    val modifiedCount: Int get() = changeSet.modifiedFiles.size
    val deletedCount: Int get() = changeSet.deletedFiles.size
    val unchangedCount: Int get() = changeSet.unchangedFiles.size
    val indexingFailures: Int get() = batchResult?.failures?.size ?: 0
    val deletionFailures: Int get() = deletions.count { !it.success }
    val hasFailures: Boolean get() = indexingFailures + deletionFailures > 0
}

data class DeletionResult(
    val relativePath: String,
    val success: Boolean,
    val error: String?
)
