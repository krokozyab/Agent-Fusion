package com.orchestrator.context.indexing

import com.orchestrator.context.ContextRepository
import com.orchestrator.context.domain.FileState
import com.orchestrator.utils.Logger
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Determines which files require re-indexing by comparing current filesystem metadata with the
 * metadata persisted in the context database.
 */
class ChangeDetector(
    projectRoot: Path,
    private val metadataExtractor: FileMetadataExtractor = FileMetadataExtractor,
    private val repository: ContextRepository = ContextRepository
) {

    private val log = Logger.logger("com.orchestrator.context.indexing.ChangeDetector")
    private val root: Path = projectRoot.toAbsolutePath().normalize()

    /**
     * Inspect the provided file paths, compare them with persisted state, and surface a summary of
     * which files are new, modified, unchanged, or deleted.
     */
    fun detectChanges(paths: List<Path>): ChangeSet {
        val indexedStates = repository.listAllFiles().filter { it.isActive }.associateBy { it.relativePath }
        val newFiles = mutableListOf<FileChange>()
        val modifiedFiles = mutableListOf<FileChange>()
        val unchangedFiles = mutableListOf<FileChange>()
        val deletedFiles = mutableListOf<DeletedFile>()
        val seenDeleted = LinkedHashSet<String>()

        val normalizedPaths = LinkedHashMap<String, Path>()
        for (path in paths) {
            val absolute = path.toAbsolutePath().normalize()
            if (!absolute.startsWith(root)) {
                log.warn("Skipping path outside project root: {}", absolute)
                continue
            }
            val relative = root.relativize(absolute).toString()
            // Preserve the first occurrence to keep stable ordering.
            normalizedPaths.putIfAbsent(relative, absolute)
        }

        for ((relativePath, absolutePath) in normalizedPaths) {
            val previousState = indexedStates[relativePath]
            if (!Files.exists(absolutePath)) {
                if (previousState != null) {
                    deletedFiles += DeletedFile(relativePath, previousState)
                    seenDeleted += relativePath
                } else {
                    log.debug("Path {} missing from disk and no previous state recorded; skipping", absolutePath)
                }
                continue
            }

            if (!Files.isRegularFile(absolutePath)) {
                log.debug("Skipping non-regular file {}", absolutePath)
                continue
            }

            val metadata = extractMetadataSafely(absolutePath) ?: continue
            val change = FileChange(
                path = absolutePath,
                relativePath = relativePath,
                metadata = metadata,
                previousState = previousState
            )

            when {
                previousState == null || previousState.isDeleted -> newFiles += change
                hasChanged(metadata, previousState) -> modifiedFiles += change
                else -> unchangedFiles += change
            }
        }

        // Identify deletions that were not part of the provided path set (e.g., removed files).
        for ((relativePath, state) in indexedStates) {
            if (seenDeleted.contains(relativePath)) continue
            val absolutePath = safeResolve(relativePath) ?: continue
            if (!Files.exists(absolutePath)) {
                deletedFiles += DeletedFile(relativePath, state)
                seenDeleted += relativePath
            }
        }

        return ChangeSet(
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            deletedFiles = deletedFiles,
            unchangedFiles = unchangedFiles,
            scannedAt = Instant.now()
        )
    }

    private fun hasChanged(metadata: FileMetadata, state: FileState): Boolean {
        return metadata.contentHash != state.contentHash ||
            metadata.sizeBytes != state.sizeBytes ||
            metadata.modifiedTimeNs != state.modifiedTimeNs
    }

    private fun extractMetadataSafely(path: Path): FileMetadata? {
        return try {
            metadataExtractor.extractMetadata(path)
        } catch (ioe: IOException) {
            log.error("Failed to read file metadata for $path: ${ioe.message}", ioe)
            null
        } catch (illegal: IllegalArgumentException) {
            log.warn("Skipping path {} due to invalid state: {}", path, illegal.message)
            null
        }
    }

    private fun safeResolve(relativePath: String): Path? {
        val resolved = root.resolve(relativePath).normalize()
        if (!resolved.startsWith(root)) {
            log.warn("Stored file path {} resolves outside project root; skipping deletion check", relativePath)
            return null
        }
        return resolved
    }
}

data class ChangeSet(
    val newFiles: List<FileChange>,
    val modifiedFiles: List<FileChange>,
    val deletedFiles: List<DeletedFile>,
    val unchangedFiles: List<FileChange>,
    val scannedAt: Instant
) {
    val totalScanned: Int = newFiles.size + modifiedFiles.size + deletedFiles.size + unchangedFiles.size
    val hasChanges: Boolean get() = newFiles.isNotEmpty() || modifiedFiles.isNotEmpty() || deletedFiles.isNotEmpty()
}

data class FileChange(
    val path: Path,
    val relativePath: String,
    val metadata: FileMetadata,
    val previousState: FileState?
)

data class DeletedFile(
    val relativePath: String,
    val previousState: FileState
)
