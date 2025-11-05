package com.orchestrator.context.watcher

import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventDebouncerTest {

    private val root = Path.of("root")
    private val fileA = Path.of("fileA.txt")
    private val fileB = Path.of("fileB.txt")

    @Test
    fun `debouncer merges rapid events for same path`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val debouncer = EventDebouncer(this, debounceMillis = 100, dispatcher = dispatcher)

        val collected = mutableListOf<FileWatchEvent>()
        val collector = launchCollector(this, debouncer, collected)

        debouncer.submit(event(FileWatchEvent.Kind.CREATED, fileA))
        advanceTimeBy(50)
        debouncer.submit(event(FileWatchEvent.Kind.MODIFIED, fileA))

        assertTrue(collected.isEmpty(), "Events should not emit before debounce window elapses")

        advanceTimeBy(100)
        advanceUntilIdle()

        assertEquals(1, collected.size)
        assertEquals(FileWatchEvent.Kind.MODIFIED, collected.first().kind)
        assertEquals(fileA, collected.first().path)

        collector.cancelAndJoin()
        debouncer.close()
    }

    @Test
    fun `debouncer emits events for different paths independently`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val debouncer = EventDebouncer(this, debounceMillis = 100, dispatcher = dispatcher)

        val collected = mutableListOf<FileWatchEvent>()
        val collector = launchCollector(this, debouncer, collected)

        debouncer.submit(event(FileWatchEvent.Kind.CREATED, fileA))
        advanceTimeBy(10)
        debouncer.submit(event(FileWatchEvent.Kind.DELETED, fileB))

        advanceTimeBy(100)
        advanceUntilIdle()

        assertEquals(2, collected.size)
        val kindsByPath = collected.associate { it.path to it.kind }
        assertEquals(FileWatchEvent.Kind.CREATED, kindsByPath[fileA])
        assertEquals(FileWatchEvent.Kind.DELETED, kindsByPath[fileB])

        collector.cancelAndJoin()
        debouncer.close()
    }

    @Test
    fun `zero debounce emits every event immediately`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val debouncer = EventDebouncer(this, debounceMillis = 0, dispatcher = dispatcher)

        val collected = mutableListOf<FileWatchEvent>()
        val collector = launchCollector(this, debouncer, collected)

        debouncer.submit(event(FileWatchEvent.Kind.CREATED, fileA))
        debouncer.submit(event(FileWatchEvent.Kind.MODIFIED, fileA))

        advanceUntilIdle()

        assertEquals(2, collected.size)
        assertEquals(listOf(FileWatchEvent.Kind.CREATED, FileWatchEvent.Kind.MODIFIED), collected.map { it.kind })

        collector.cancelAndJoin()
        debouncer.close()
    }

    private fun launchCollector(
        scope: TestScope,
        debouncer: EventDebouncer,
        sink: MutableList<FileWatchEvent>
    ) = scope.launch {
        debouncer.events.collect { sink += it }
    }

    private fun event(kind: FileWatchEvent.Kind, path: Path): FileWatchEvent = FileWatchEvent(
        kind = kind,
        path = path,
        root = root,
        isDirectory = false,
        timestamp = Instant.EPOCH
    )
}
