package com.orchestrator.context.bootstrap

import com.orchestrator.context.config.BootstrapConfig
import com.orchestrator.context.discovery.DirectoryScanner
import com.orchestrator.context.indexing.BatchIndexer
import com.orchestrator.context.indexing.BatchResult
import com.orchestrator.context.indexing.BatchStats
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals

class BootstrapOrchestratorTest {

    @Test
    fun `bootstrap should scan, prioritize, and index files`() = runBlocking {
        val config = BootstrapConfig()
        val scanner = mockk<DirectoryScanner>()
        val prioritizer = mockk<FilePrioritizer>()
        val indexer = mockk<BatchIndexer>()
        val progressTracker = mockk<BootstrapProgressTracker>(relaxed = true)
        val errorLogger = mockk<BootstrapErrorLogger>(relaxed = true)

        val orchestrator = BootstrapOrchestrator(
            listOf(Path.of("/tmp")),
            config,
            scanner,
            prioritizer,
            indexer,
            progressTracker,
            errorLogger
        )

        val files = listOf(Path.of("/tmp/file1.kt"), Path.of("/tmp/file2.kt"))
        coEvery { scanner.scan(any()) } returns files
        coEvery { prioritizer.prioritize(any(), any()) } returns files
        coEvery { progressTracker.getRemaining() } returns emptyList()
        coEvery { indexer.indexFilesAsync(any(), any(), any()) } returns BatchResult(
            successes = emptyList(),
            failures = emptyList(),
            stats = BatchStats(2, 2, 2, 0, Instant.now(), Instant.now(), 1000)
        )

        val result = orchestrator.bootstrap()

        assertEquals(true, result.success)
        assertEquals(2, result.totalFiles)
        assertEquals(0, result.successfulFiles)
        assertEquals(0, result.failedFiles)
    }
}
