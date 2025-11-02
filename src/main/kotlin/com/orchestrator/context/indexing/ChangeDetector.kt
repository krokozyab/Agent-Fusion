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
 *
 * Supports multiple watch roots to allow indexing files from the main project root as well as
 * external directories configured via include_paths.
 */
class ChangeDetector(
    projectRoot: Path,
    watchRoots: List<Path> = emptyList(),
    private val metadataExtractor: FileMetadataExtractor = FileMetadataExtractor,
    private val repository: ContextRepository = ContextRepository
) {

    private val log = Logger.logger("com.orchestrator.context.indexing.ChangeDetector")
    private val projectRoot: Path = projectRoot.toAbsolutePath().normalize()
    private val allRoots: List<Path> = if (watchRoots.isNotEmpty()) {
        (listOf(projectRoot) + watchRoots)
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .sortedByDescending { it.toString().length }  // Match most specific roots first
    } else {
        listOf(projectRoot)
    }

    /**
     * Inspect the provided file paths, compare them with persisted state, and surface a summary of
     * which files are new, modified, unchanged, or deleted.
     *
     * @param paths The file paths to analyze
     * @param detectImplicitDeletions If true, any indexed files not in the provided paths that don't exist
     *                                on disk will be marked as deleted. Set to false when calling with only
     *                                changed/modified files from the watcher (incremental updates). Set to true
     *                                when calling with a complete directory scan (bootstrap/full rescan).
     */
    fun detectChanges(paths: List<Path>, detectImplicitDeletions: Boolean = true): ChangeSet {
        val indexedStates = repository.listAllFiles().filter { it.isActive }.associateBy { it.relativePath }
        val newFiles = mutableListOf<FileChange>()
        val modifiedFiles = mutableListOf<FileChange>()
        val unchangedFiles = mutableListOf<FileChange>()
        val deletedFiles = mutableListOf<DeletedFile>()
        val seenDeleted = LinkedHashSet<String>()

        log.info("ChangeDetector.detectChanges: Processing {} paths, allRoots={}", paths.size, allRoots)

        data class NormalizedPath(val absolutePath: Path, val relativePath: String, val root: Path)
        val normalizedPaths = LinkedHashMap<String, NormalizedPath>()  // Key is absolute path string to avoid collisions
        val rejectedPaths = mutableListOf<String>()
        for (path in paths) {
            val absolute = path.toAbsolutePath().normalize()

            // Find which root this path belongs to
            val matchingRoot = findMatchingRoot(absolute)
            if (matchingRoot == null) {
                rejectedPaths.add(absolute.toString())
                log.warn("Skipping path that doesn't belong to any configured root: {} (allRoots={})", absolute, allRoots)
                continue
            }

            val relative = matchingRoot.relativize(absolute).toString()
            // Use absolute path as key to avoid collisions when multiple roots have files with same relative paths
            // e.g., codex_to_claude/README.md and of_mcp/README.md both have relative="README.md"
            normalizedPaths.putIfAbsent(absolute.toString(), NormalizedPath(absolute, relative, matchingRoot))
        }

        for ((_, normalizedPath) in normalizedPaths) {
            val absolutePath = normalizedPath.absolutePath
            val relativePath = normalizedPath.relativePath
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
        // Only do this when we've performed a complete scan (detectImplicitDeletions=true).
        // Skip this for incremental updates from the watcher where we only get changed files.
        if (detectImplicitDeletions) {
            for ((relativePath, state) in indexedStates) {
                if (seenDeleted.contains(relativePath)) continue
                val absolutePath = safeResolve(relativePath) ?: continue
                if (!Files.exists(absolutePath)) {
                    deletedFiles += DeletedFile(relativePath, state)
                    seenDeleted += relativePath
                }
            }
        }

        log.info("ChangeDetector.detectChanges: Processed {} paths, accepted={}, rejected={}",
            paths.size, normalizedPaths.size, rejectedPaths.size)
        if (rejectedPaths.isNotEmpty()) {
            log.warn("ChangeDetector.detectChanges: {} paths rejected - {}",
                rejectedPaths.size, rejectedPaths.take(3).joinToString(", "))
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
        // Try to resolve against each known root to handle files from different watch paths
        for (root in allRoots) {
            val resolved = root.resolve(relativePath).normalize()
            if (resolved.startsWith(root) && Files.exists(resolved)) {
                return resolved
            }
        }
        // Try main project root as fallback for backward compatibility
        val resolved = projectRoot.resolve(relativePath).normalize()
        if (!resolved.startsWith(projectRoot)) {
            log.warn("Stored file path {} cannot be resolved from any configured root; skipping deletion check", relativePath)
            return null
        }
        return resolved
    }

    private fun findMatchingRoot(absolutePath: Path): Path? {
        val normalized = absolutePath.toAbsolutePath().normalize()
        return allRoots.find { root ->
            normalized == root || normalized.startsWith(root)
        }
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
