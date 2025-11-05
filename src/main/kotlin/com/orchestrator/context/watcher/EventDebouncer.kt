package com.orchestrator.context.watcher

import java.io.Closeable
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Debounces [FileWatchEvent] instances by path. Rapid events that target the same file path
 * are coalesced into a single emission delivered after [debounceMillis] has elapsed without
 * further updates for that path.
 */
class EventDebouncer(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : Closeable {

    private val mutex = Mutex()
    private val pending = mutableMapOf<Path, PendingEvent>()

    private data class PendingEvent(var event: FileWatchEvent, var job: Job)

    private val _events = MutableSharedFlow<FileWatchEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<FileWatchEvent> = _events.asSharedFlow()

    /**
     * Submit an event to be debounced. When [debounceMillis] is greater than zero, events
     * targeting the same [FileWatchEvent.path] within the window will be merged and only the
     * most recent event will be emitted.
     */
    fun submit(event: FileWatchEvent) {
        if (!scope.isActive) return
        if (debounceMillis <= 0) {
            scope.launch(dispatcher) { _events.emit(event) }
            return
        }

        scope.launch(dispatcher) {
            var jobToCancel: Job? = null
            mutex.withLock {
                val existing = pending[event.path]
                jobToCancel = existing?.job

                val job = scope.launch(dispatcher) {
                    delay(debounceMillis)
                    emitDebounced(event.path)
                }

                val pendingEvent = existing ?: PendingEvent(event, job)
                pendingEvent.event = mergeEvents(pendingEvent.event, event)
                pendingEvent.job = job
                pending[event.path] = pendingEvent
            }
            jobToCancel?.cancel()
        }
    }

    private suspend fun emitDebounced(path: Path) {
        val toEmit = mutex.withLock {
            val pendingEvent = pending.remove(path)
            pendingEvent?.event
        }
        if (toEmit != null) {
            _events.emit(toEmit)
        }
    }

    private fun mergeEvents(previous: FileWatchEvent, incoming: FileWatchEvent): FileWatchEvent = incoming

    override fun close() {
        val jobs = runBlocking {
            mutex.withLock {
                val entries = pending.values.toList()
                pending.clear()
                entries.map { it.job }
            }
        }
        jobs.forEach { it.cancel() }
    }

    companion object {
        private const val DEFAULT_DEBOUNCE_MS = 500L
    }
}
