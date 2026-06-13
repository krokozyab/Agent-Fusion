package com.orchestrator.context.indexing

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.FileStateRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ChangeDetectorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var projectRoot: Path
    private lateinit var databasePath: Path
    private lateinit var detector: ChangeDetector

    @BeforeEach
    fun setup() {
        projectRoot = tempDir.resolve("workspace")
        Files.createDirectories(projectRoot)

        databasePath = tempDir.resolve("context.db")
        ContextDatabase.initialize(StorageConfig(dbPath = databasePath.toString()))

        detector = ChangeDetector(projectRoot)
    }

    @AfterEach
    fun tearDown() {
        ContextDatabase.shutdown()
    }

    @Test
    fun `detectChanges treats matching size and mtime as unchanged without hashing`() {
        val path = projectRoot.resolve("src/Stable.kt")
        Files.createDirectories(path.parent)
        Files.writeString(path, "fun stable() = 1")

        // Seed state with the file's REAL size+mtime but a deliberately WRONG content hash. If the
        // detector hashed the file, it would see the mismatch and report "modified". The size+mtime
        // fast path must classify it as unchanged without ever computing the hash.
        val (size, mtime) = FileMetadataExtractor.statSizeAndMtime(path)
        FileStateRepository.insert(
            FileState(
                id = 0,
                relativePath = "src/Stable.kt",
                absolutePath = path.toAbsolutePath().normalize().toString(),
                contentHash = "deadbeef-not-the-real-hash",
                sizeBytes = size,
                modifiedTimeNs = mtime,
                language = "kotlin",
                kind = null,
                fingerprint = null,
                indexedAt = Instant.now(),
                isDeleted = false
            )
        )

        val changeSet = detector.detectChanges(listOf(path))

        assertEquals(listOf("src/Stable.kt"), changeSet.unchangedFiles.map { it.relativePath })
        assertTrue(changeSet.modifiedFiles.isEmpty(), "matching size+mtime must not be re-hashed/modified")
    }

    @Test
    fun `detectChanges classifies new modified unchanged files`() {
        // Existing file already indexed
        val existingPath = projectRoot.resolve("src/Existing.kt")
        Files.createDirectories(existingPath.parent)
        Files.writeString(existingPath, "fun existing() = 1")
        insertFileState(existingPath)

        // Unchanged file (re-indexed baseline matches disk state)
        val unchangedPath = projectRoot.resolve("src/Unchanged.kt")
        Files.writeString(unchangedPath, "fun unchanged() = 2")
        insertFileState(unchangedPath)

        // Modify existing file after baseline insertion
        Files.writeString(existingPath, "fun existing() = 2 // modified")

        // New file not yet indexed
        val newPath = projectRoot.resolve("src/New.kt")
        Files.writeString(newPath, "fun newFile() = 3")

        val changeSet = detector.detectChanges(listOf(existingPath, newPath, unchangedPath))

        assertEquals(listOf("src/New.kt"), changeSet.newFiles.map { it.relativePath })
        assertEquals(listOf("src/Existing.kt"), changeSet.modifiedFiles.map { it.relativePath })
        assertEquals(listOf("src/Unchanged.kt"), changeSet.unchangedFiles.map { it.relativePath })
        assertTrue(changeSet.deletedFiles.isEmpty())
    }

    @Test
    fun `detectChanges flags deleted files even when not explicitly requested`() {
        val deletePath = projectRoot.resolve("dir/Removed.kt")
        Files.createDirectories(deletePath.parent)
        Files.writeString(deletePath, "fun removed() = 0")
        insertFileState(deletePath)

        // Remove from disk after indexing snapshot
        Files.delete(deletePath)

        val changeSet = detector.detectChanges(emptyList())

        assertEquals(listOf("dir/Removed.kt"), changeSet.deletedFiles.map { it.relativePath })
        assertTrue(changeSet.newFiles.isEmpty())
        assertTrue(changeSet.modifiedFiles.isEmpty())
        assertTrue(changeSet.unchangedFiles.isEmpty())
    }

    @Test
    fun `detectChanges does not delete virtual git nodes that have no filesystem presence`() {
        // A real file that is genuinely gone — must still be detected as deleted.
        val deletePath = projectRoot.resolve("dir/Removed.kt")
        Files.createDirectories(deletePath.parent)
        Files.writeString(deletePath, "fun removed() = 0")
        insertFileState(deletePath)
        Files.delete(deletePath)

        // Virtual git-intent nodes — never on disk; the sweep must leave them alone.
        insertVirtualFileState("git://commit/abc123")
        insertVirtualFileState("pr://42")

        val changeSet = detector.detectChanges(emptyList())

        val deletedPaths = changeSet.deletedFiles.map { it.absolutePath }
        assertTrue(deletedPaths.contains(deletePath.toAbsolutePath().normalize().toString()),
            "Genuinely removed file must be flagged deleted")
        assertTrue(deletedPaths.none { it.contains("://") },
            "Virtual git/pr nodes must never be flagged for deletion, got: $deletedPaths")
    }

    private fun insertVirtualFileState(virtualPath: String) {
        val state = FileState(
            id = 0,
            relativePath = virtualPath,
            absolutePath = virtualPath,
            contentHash = "virtual-hash",
            sizeBytes = 0,
            modifiedTimeNs = 0,
            language = null,
            kind = "git_commit",
            fingerprint = null,
            indexedAt = Instant.now(),
            isDeleted = false
        )
        FileStateRepository.insert(state)
    }

    private fun insertFileState(path: Path) {
        val metadata = FileMetadataExtractor.extractMetadata(path)
        val relativePath = projectRoot.relativize(path.toAbsolutePath().normalize()).toString()
        val state = FileState(
            id = 0,
            relativePath = relativePath,
            absolutePath = path.toAbsolutePath().normalize().toString(),
            contentHash = metadata.contentHash,
            sizeBytes = metadata.sizeBytes,
            modifiedTimeNs = metadata.modifiedTimeNs,
            language = metadata.language,
            kind = null,
            fingerprint = null,
            indexedAt = Instant.now(),
            isDeleted = false
        )
        FileStateRepository.insert(state)
    }
}
