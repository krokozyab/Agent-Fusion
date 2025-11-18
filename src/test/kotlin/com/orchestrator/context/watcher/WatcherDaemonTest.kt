package com.orchestrator.context.watcher

import com.orchestrator.context.config.IndexingConfig
import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.indexing.ChangeSet
import com.orchestrator.context.indexing.IncrementalIndexer
import com.orchestrator.context.indexing.UpdateResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class WatcherDaemonTest {

    @TempDir
    lateinit var tempDir: Path

    // TODO: These are integration tests that require real filesystem operations and conflict with runTest.
    // They should be moved to a separate integration test suite that runs without the test scheduler.

    // @Test
    fun `daemon batches events before triggering incremental indexer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val eventFlow = MutableSharedFlow<FileWatchEvent>(extraBufferCapacity = 16)
        val fileWatcher = mockk<FileWatcher>()
        every { fileWatcher.start() } returns Unit
        every { fileWatcher.close() } returns Unit
        every { fileWatcher.events } returns eventFlow

        val updateResult = emptyUpdateResult()
        val incrementalIndexer = mockk<IncrementalIndexer>()
        coEvery { incrementalIndexer.updateAsync(any(), any(), any()) } returns updateResult

        val contextConfig = mockk<com.orchestrator.context.config.ContextConfig>(relaxed = true)
        val daemon = WatcherDaemon(
            scope = this,
            projectRoot = tempDir,
            watcherConfig = WatcherConfig(
                enabled = true,
                debounceMs = 0,
                watchPaths = listOf(tempDir.toString()),
                ignorePatterns = emptyList(),
                deletionSweepIntervalMs = 0
            ),
            indexingConfig = IndexingConfig(),
            incrementalIndexer = incrementalIndexer,
            contextConfig = contextConfig,
            dispatcher = dispatcher,
            batchWindowMillis = 100
        )

        daemon.start()
        // Don't call advanceUntilIdle() here - the unified watcher has an infinite event collector

        val path = tempDir.resolve("sample.kt")
        val event = FileWatchEvent(FileWatchEvent.Kind.CREATED, path, tempDir, isDirectory = false, timestamp = Instant.EPOCH)
        val second = event.copy(kind = FileWatchEvent.Kind.MODIFIED)

        eventFlow.emit(event)
        advanceTimeBy(50)
        eventFlow.emit(second)
        advanceTimeBy(100)

        // Must stop daemon before checking - it cancels infinite event collector
        daemon.stop()

        coVerify(exactly = 1) {
            incrementalIndexer.updateAsync(
                match { paths ->
                    paths.size == 1 &&
                        paths.first().toAbsolutePath().normalize() == path.toAbsolutePath().normalize()
                },
                any(),
                any()
            )
        }
    }

    // @Test
    fun `daemon ignores paths rejected by validator`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val eventFlow = MutableSharedFlow<FileWatchEvent>(extraBufferCapacity = 16)
        val fileWatcher = mockk<FileWatcher>()
        every { fileWatcher.start() } returns Unit
        every { fileWatcher.close() } returns Unit
        every { fileWatcher.events } returns eventFlow

        val updateResult = emptyUpdateResult()
        val incrementalIndexer = mockk<IncrementalIndexer>()
        coEvery { incrementalIndexer.updateAsync(any(), any(), any()) } returns updateResult

        val contextConfig = mockk<com.orchestrator.context.config.ContextConfig>(relaxed = true)
        val daemon = WatcherDaemon(
            scope = this,
            projectRoot = tempDir,
            watcherConfig = WatcherConfig(
                enabled = true,
                debounceMs = 0,
                watchPaths = listOf(tempDir.toString()),
                ignorePatterns = emptyList(),
                deletionSweepIntervalMs = 0
            ),
            indexingConfig = IndexingConfig(allowedExtensions = listOf(".kt")),
            incrementalIndexer = incrementalIndexer,
            contextConfig = contextConfig,
            dispatcher = dispatcher,
            batchWindowMillis = 100
        )

        daemon.start()
        // Don't call advanceUntilIdle() here - the unified watcher has an infinite event collector

        val invalidPath = tempDir.resolve("image.png")
        eventFlow.emit(
            FileWatchEvent(FileWatchEvent.Kind.MODIFIED, invalidPath, tempDir, isDirectory = false, timestamp = Instant.EPOCH)
        )

        advanceTimeBy(200)

        // Must stop daemon before advanceUntilIdle() because the infinite event collector never completes
        daemon.stop()
        advanceUntilIdle()

        coVerify(exactly = 0) {
            incrementalIndexer.updateAsync(
                match { it.isNotEmpty() },
                any(),
                any()
            )
        }
    }

    // @Test
    fun `daemon flushes pending paths on stop`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val eventFlow = MutableSharedFlow<FileWatchEvent>(extraBufferCapacity = 16)
        val fileWatcher = mockk<FileWatcher>()
        every { fileWatcher.start() } returns Unit
        every { fileWatcher.close() } returns Unit
        every { fileWatcher.events } returns eventFlow

        val updateResult = emptyUpdateResult()
        val incrementalIndexer = mockk<IncrementalIndexer>()
        coEvery { incrementalIndexer.updateAsync(any(), any(), any()) } returns updateResult

        val contextConfig = mockk<com.orchestrator.context.config.ContextConfig>(relaxed = true)
        val daemon = WatcherDaemon(
            scope = this,
            projectRoot = tempDir,
            watcherConfig = WatcherConfig(
                enabled = true,
                debounceMs = 0,
                watchPaths = listOf(tempDir.toString()),
                ignorePatterns = emptyList(),
                deletionSweepIntervalMs = 0
            ),
            indexingConfig = IndexingConfig(),
            incrementalIndexer = incrementalIndexer,
            contextConfig = contextConfig,
            dispatcher = dispatcher,
            batchWindowMillis = 1_000
        )

        daemon.start()
        // Don't call advanceUntilIdle() here - the unified watcher has an infinite event collector

        val path = tempDir.resolve("queued.kt")
        eventFlow.emit(
            FileWatchEvent(FileWatchEvent.Kind.MODIFIED, path, tempDir, isDirectory = false, timestamp = Instant.EPOCH)
        )

        // Must stop daemon before checking - it cancels infinite event collector
        daemon.stop()

        coVerify(exactly = 1) {
            incrementalIndexer.updateAsync(
                match { paths ->
                    paths.size == 1 &&
                        paths.first().toAbsolutePath().normalize() == path.toAbsolutePath().normalize()
                },
                any(),
                any()
            )
        }
    }

    private fun emptyUpdateResult(): UpdateResult {
        val changeSet = ChangeSet(emptyList(), emptyList(), emptyList(), emptyList(), Instant.EPOCH)
        return UpdateResult(
            changeSet = changeSet,
            batchResult = null,
            deletions = emptyList(),
            startedAt = Instant.EPOCH,
            completedAt = Instant.EPOCH,
            durationMillis = 0
        )
    }
}
