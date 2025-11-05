package com.orchestrator.context.indexing

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BatchIndexerTest {

    private val fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `indexFiles processes all paths and reports successes and failures`() = runTest {
        val pathSuccess = Path.of("a.kt")
        val pathErrorResult = Path.of("b.kt")
        val pathThrows = Path.of("c.kt")

        val fileIndexer = mockk<FileIndexer>()
        coEvery { fileIndexer.indexFileAsync(pathSuccess) } returns IndexResult(
            success = true,
            relativePath = "src/a.kt",
            chunkCount = 2,
            embeddingCount = 2,
            error = null
        )
        coEvery { fileIndexer.indexFileAsync(pathErrorResult) } returns IndexResult(
            success = false,
            relativePath = "src/b.kt",
            chunkCount = 0,
            embeddingCount = 0,
            error = "failed to index"
        )
        coEvery { fileIndexer.indexFileAsync(pathThrows) } throws IllegalStateException("boom")

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val progresses = Collections.synchronizedList(mutableListOf<BatchProgress>())

        val batchIndexer = BatchIndexer(
            fileIndexer = fileIndexer,
            defaultParallelism = 4,
            dispatcher = dispatcher,
            clock = fixedClock
        )

        val result = batchIndexer.indexFilesAsync(
            paths = listOf(pathSuccess, pathErrorResult, pathThrows),
            parallelism = 2,
            onProgress = { progresses += it }
        )

        assertEquals(3, result.stats.totalFiles)
        assertEquals(3, result.stats.processedFiles)
        assertEquals(1, result.stats.succeeded)
        assertEquals(2, result.stats.failed)
        assertFalse(result.isSuccessful)
        assertTrue(result.hasFailures)

        assertEquals(listOf("src/a.kt"), result.successes.map { it.relativePath })
        val failureRelativePaths = result.failures.map { it.relativePath }.toSet()
        assertEquals(setOf("src/b.kt", "c.kt"), failureRelativePaths)

        assertEquals(3, progresses.size)
        val finalProgress = progresses.last()
        assertEquals(3, finalProgress.processedFiles)
        assertEquals(1, finalProgress.succeeded)
        assertEquals(2, finalProgress.failed)
        assertTrue(finalProgress.lastError?.isNotBlank() ?: false)

        coVerify { fileIndexer.indexFileAsync(pathSuccess) }
        coVerify { fileIndexer.indexFileAsync(pathErrorResult) }
        coVerify { fileIndexer.indexFileAsync(pathThrows) }
    }

    @Test
    fun `indexFiles handles empty input`() = runTest {
        val fileIndexer = mockk<FileIndexer>(relaxed = true)
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val batchIndexer = BatchIndexer(
            fileIndexer = fileIndexer,
            dispatcher = dispatcher,
            clock = fixedClock
        )

        val result = batchIndexer.indexFilesAsync(emptyList())

        assertEquals(0, result.stats.totalFiles)
        assertEquals(0, result.stats.processedFiles)
        assertTrue(result.successes.isEmpty())
        assertTrue(result.failures.isEmpty())
        assertTrue(result.isSuccessful)

    }
}
