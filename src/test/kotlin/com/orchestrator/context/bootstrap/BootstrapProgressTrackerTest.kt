package com.orchestrator.context.bootstrap

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.storage.ContextDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootstrapProgressTrackerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var tracker: BootstrapProgressTracker

    @BeforeTest
    fun setUp() {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))
        tracker = BootstrapProgressTracker()
    }

    @AfterTest
    fun tearDown() {
        ContextDatabase.shutdown()
    }

    @Test
    fun `initProgress should insert all files with PENDING status`() {
        val files = listOf(
            tempDir.resolve("file1.kt").createFile(),
            tempDir.resolve("file2.kt").createFile()
        )

        tracker.initProgress(files)

        val progress = tracker.getProgress()
        assertEquals(2, progress.total)
        assertEquals(2, progress.pending)
        assertEquals(0, progress.completed)
        assertEquals(0, progress.processing)
        assertEquals(0, progress.failed)
    }

    @Test
    fun `status updates should be reflected in progress`() {
        val file1 = tempDir.resolve("file1.kt").createFile()
        val file2 = tempDir.resolve("file2.kt").createFile()
        val file3 = tempDir.resolve("file3.kt").createFile()
        val files = listOf(file1, file2, file3)

        tracker.initProgress(files)

        tracker.markProcessing(file1)
        var progress = tracker.getProgress()
        assertEquals(3, progress.total)
        assertEquals(1, progress.processing)
        assertEquals(2, progress.pending)

        tracker.markCompleted(file1)
        progress = tracker.getProgress()
        assertEquals(1, progress.completed)
        assertEquals(0, progress.processing)

        tracker.markFailed(file2, "Test error")
        progress = tracker.getProgress()
        assertEquals(1, progress.failed)
        assertEquals(1, progress.pending)
    }

    @Test
    fun `getRemaining should return non-completed files`() {
        val file1 = tempDir.resolve("file1.kt").createFile()
        val file2 = tempDir.resolve("file2.kt").createFile()
        val file3 = tempDir.resolve("file3.kt").createFile()
        val files = listOf(file1, file2, file3)

        tracker.initProgress(files)

        tracker.markCompleted(file1)
        tracker.markFailed(file2, "error")

        val remaining = tracker.getRemaining().map { it.fileName.toString() }
        assertEquals(2, remaining.size)
        assertTrue(remaining.contains(file2.fileName.toString()))
        assertTrue(remaining.contains(file3.fileName.toString()))
    }

    @Test
    fun `initProgress should clear previous state`() {
        val oldFile = tempDir.resolve("old.kt").createFile()
        tracker.initProgress(listOf(oldFile))
        tracker.markCompleted(oldFile)

        val newFile = tempDir.resolve("new.kt").createFile()
        tracker.initProgress(listOf(newFile))

        val progress = tracker.getProgress()
        assertEquals(1, progress.total)
        assertEquals(1, progress.pending)

        val remaining = tracker.getRemaining()
        assertEquals(1, remaining.size)
        assertEquals(newFile.toAbsolutePath().normalize(), remaining.first())
    }
}
