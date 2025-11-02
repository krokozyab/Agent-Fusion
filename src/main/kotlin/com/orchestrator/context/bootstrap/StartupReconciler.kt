package com.orchestrator.context.bootstrap

import com.orchestrator.context.ContextRepository
import com.orchestrator.context.discovery.DirectoryScanner
import com.orchestrator.context.indexing.ChangeDetector
import com.orchestrator.context.indexing.IncrementalIndexer
import com.orchestrator.utils.Logger
import java.nio.file.Path
import java.time.Instant

/**
 * Reconciles database state with filesystem state at startup.
 *
 * When the server restarts, files may have been added or deleted from the filesystem.
 * Instead of doing a full rescan (which is slow), this reconciler:
 * 1. Scans the current filesystem for all files
 * 2. Uses ChangeDetector to compare with database state (handles multiple roots correctly)
 * 3. Indexes new files and removes database records for deleted files
 *
 * This provides fast startup reconciliation without a full rescan.
 *
 * NOTE: Uses ChangeDetector which properly handles multiple watch roots and relative path resolution.
 */
class StartupReconciler(
    private val projectRoot: Path,
    private val roots: List<Path>,
    private val scanner: DirectoryScanner,
    private val changeDetector: ChangeDetector,
    private val incrementalIndexer: IncrementalIndexer,
    private val repository: ContextRepository = ContextRepository
) {

    private val log = Logger.logger("com.orchestrator.context.bootstrap.StartupReconciler")

    /**
     * Run reconciliation between database and filesystem.
     *
     * @return Reconciliation result with statistics
     */
    suspend fun reconcile(): ReconciliationResult {
        val startedAt = Instant.now()

        log.info("Starting startup reconciliation with ${roots.size} root(s)")

        // Get all indexed files from database
        val indexedFiles = repository.listAllFiles().filter { it.isActive }
        if (indexedFiles.isEmpty()) {
            log.info("Database is empty - no reconciliation needed")
            return ReconciliationResult(
                filesInDatabase = 0,
                filesInFilesystem = 0,
                newFilesIndexed = 0,
                deletedFilesRemoved = 0,
                startedAt = startedAt,
                completedAt = Instant.now()
            )
        }

        // Scan current filesystem
        val filesystemPaths = try {
            scanner.scan(roots)
        } catch (e: Exception) {
            log.error("Failed to scan filesystem during reconciliation: ${e.message}", e)
            return ReconciliationResult(
                filesInDatabase = indexedFiles.size,
                filesInFilesystem = 0,
                newFilesIndexed = 0,
                deletedFilesRemoved = 0,
                startedAt = startedAt,
                completedAt = Instant.now(),
                error = e.message ?: "Unknown error during filesystem scan"
            )
        }

        // Use ChangeDetector to properly handle multiple roots and path resolution
        // detectImplicitDeletions=true because we're doing a full filesystem scan
        val changeSet = changeDetector.detectChanges(filesystemPaths, detectImplicitDeletions = true)

        log.info(
            "Reconciliation analysis: ${indexedFiles.size} indexed, ${filesystemPaths.size} in filesystem, " +
            "${changeSet.newFiles.size} new files, ${changeSet.modifiedFiles.size} modified files, " +
            "${changeSet.deletedFiles.size} deleted files"
        )

        // Index new and modified files
        var newFilesIndexed = 0
        var modifiedFilesIndexed = 0
        val filesToIndex = changeSet.newFiles + changeSet.modifiedFiles
        if (filesToIndex.isNotEmpty()) {
            log.info("Indexing ${filesToIndex.size} file(s)...")
            try {
                val indexPaths = filesToIndex.map { it.path }
                val result = incrementalIndexer.updateAsync(
                    indexPaths,
                    detectImplicitDeletions = false
                )
                newFilesIndexed = result.newCount
                modifiedFilesIndexed = result.modifiedCount
                log.info("Indexed $newFilesIndexed new file(s) and $modifiedFilesIndexed modified file(s)")
            } catch (e: Exception) {
                log.error("Failed to index files: ${e.message}", e)
            }
        }

        // Remove deleted files from database
        var deletedFilesRemoved = 0
        for (deletedFile in changeSet.deletedFiles) {
            try {
                val removed = repository.deleteFileArtifacts(deletedFile.relativePath)
                if (removed) {
                    deletedFilesRemoved++
                }
            } catch (e: Exception) {
                log.error("Failed to remove ${deletedFile.relativePath} from database: ${e.message}", e)
            }
        }

        if (deletedFilesRemoved > 0) {
            log.info("Removed $deletedFilesRemoved deleted file(s) from database")
        }

        val completedAt = Instant.now()
        val duration = java.time.Duration.between(startedAt, completedAt).toMillis()

        return ReconciliationResult(
            filesInDatabase = indexedFiles.size,
            filesInFilesystem = filesystemPaths.size,
            newFilesIndexed = newFilesIndexed,
            deletedFilesRemoved = deletedFilesRemoved,
            startedAt = startedAt,
            completedAt = completedAt,
            durationMillis = duration
        )
    }
}

/**
 * Result of startup reconciliation.
 */
data class ReconciliationResult(
    val filesInDatabase: Int,
    val filesInFilesystem: Int,
    val newFilesIndexed: Int,
    val deletedFilesRemoved: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMillis: Long = java.time.Duration.between(startedAt, completedAt).toMillis(),
    val error: String? = null
) {
    val hasChanges: Boolean get() = newFilesIndexed > 0 || deletedFilesRemoved > 0
    val isSuccessful: Boolean get() = error == null
}
