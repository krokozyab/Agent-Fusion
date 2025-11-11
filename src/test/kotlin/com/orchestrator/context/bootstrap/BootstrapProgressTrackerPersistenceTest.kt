package com.orchestrator.context.bootstrap

import com.orchestrator.context.storage.ContextDatabase
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that verify BootstrapProgressTracker persists state across restarts
 * and doesn't unnecessarily drop data on initialization.
 */
class BootstrapProgressTrackerPersistenceTest {

    @Test
    fun `progress persists across tracker recreations`() {
        // Clean slate
        ContextDatabase.shutdown()
        ContextDatabase.initialize(com.orchestrator.context.config.StorageConfig())

        // Create first tracker and add some progress
        val tracker1 = BootstrapProgressTracker()
        val files = listOf(
            Path.of("/test/file1.kt"),
            Path.of("/test/file2.kt"),
            Path.of("/test/file3.kt")
        )
        tracker1.initProgress(files)
        tracker1.markCompleted(Path.of("/test/file1.kt"))
        tracker1.markFailed(Path.of("/test/file2.kt"), "Test error")

        val progress1 = tracker1.getProgress()
        assertEquals(3, progress1.total)
        assertEquals(1, progress1.completed)
        assertEquals(1, progress1.pending)
        assertEquals(1, progress1.failed)

        // Create second tracker (simulating service restart)
        // This should NOT drop the table and lose progress
        val tracker2 = BootstrapProgressTracker()
        val progress2 = tracker2.getProgress()

        // Progress should be preserved
        assertEquals(progress1.total, progress2.total)
        assertEquals(progress1.completed, progress2.completed)
        assertEquals(progress1.pending, progress2.pending)
        assertEquals(progress1.failed, progress2.failed)

        // Remaining files should be correct
        val remaining = tracker2.getRemaining()
        assertEquals(2, remaining.size) // file2 (failed) and file3 (pending)
        assertTrue(remaining.any { it.toString().contains("file2") })
        assertTrue(remaining.any { it.toString().contains("file3") })
    }

    @Test
    fun `reset clears all progress`() {
        val tracker = BootstrapProgressTracker()
        val files = listOf(
            Path.of("/test/file1.kt"),
            Path.of("/test/file2.kt")
        )
        tracker.initProgress(files)
        tracker.markCompleted(Path.of("/test/file1.kt"))

        var progress = tracker.getProgress()
        assertEquals(2, progress.total)
        assertEquals(1, progress.completed)

        // Reset should clear everything
        tracker.reset()

        progress = tracker.getProgress()
        assertEquals(0, progress.total)
        assertEquals(0, progress.completed)

        val remaining = tracker.getRemaining()
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `empty table on first init`() {
        // Create tracker and explicitly reset to ensure clean state
        val tracker = BootstrapProgressTracker()
        tracker.reset()

        val remaining = tracker.getRemaining()
        assertTrue(remaining.isEmpty(), "Reset tracker should have no remaining files")

        val progress = tracker.getProgress()
        assertEquals(0, progress.total)
    }
}
