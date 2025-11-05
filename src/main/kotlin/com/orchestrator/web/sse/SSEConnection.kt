package com.orchestrator.web.sse

import java.time.Instant
import java.time.Duration as JavaDuration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Represents a live SSE connection and manages its keep-alive lifecycle.
 */
class SSEConnection(
    val id: String,
    private val sender: Sender,
    private val scope: CoroutineScope,
    private val keepAliveInterval: Duration = 30.seconds,
    private val clock: () -> Instant = { Instant.now() },
    private val onClosed: (SSEConnection) -> Unit = {}
) {

    fun interface Sender {
        suspend fun send(event: SSEEvent)
    }

    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lastEventMillis = AtomicLong(clock().toEpochMilli())
    private val lastKeepAliveMillis = AtomicLong(lastEventMillis.get())
    private val connectedAtMillis = AtomicLong(lastEventMillis.get())
    private val sendMutex = Mutex()

    @Volatile
    private var keepAliveJob: Job? = null

    val connectedAt: Instant get() = Instant.ofEpochMilli(connectedAtMillis.get())
    val lastEventAt: Instant get() = Instant.ofEpochMilli(lastEventMillis.get())
    val lastKeepAliveAt: Instant get() = Instant.ofEpochMilli(lastKeepAliveMillis.get())
    val isClosed: Boolean get() = closed.get()

    /**
     * Starts the connection lifecycle by sending a connected event and scheduling keep-alives.
     */
    suspend fun start() {
        if (!started.compareAndSet(false, true)) return
        connectedAtMillis.set(clock().toEpochMilli())
        sendInternal(SSEEvent.connected(timestamp = clock()))
        startKeepAlive()
    }

    /**
     * Sends an event to this connection. Throws if the connection is closed.
     */
    suspend fun send(event: SSEEvent) {
        if (isClosed) throw IllegalStateException("Connection '$id' is closed")
        sendInternal(event)
    }

    private suspend fun sendInternal(event: SSEEvent) {
        sendMutex.withLock {
            if (closed.get()) return
            try {
                sender.send(event)
                val now = clock().toEpochMilli()
                lastEventMillis.set(now)
                if (event.type == SSEEventType.KEEP_ALIVE) {
                    lastKeepAliveMillis.set(now)
                }
            } catch (t: Throwable) {
                close(t)
                throw t
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob = scope.launch {
            while (isActive && !closed.get()) {
                delay(keepAliveInterval)
                runCatching {
                    sendInternal(SSEEvent.keepAlive(timestamp = clock()))
                }.onFailure {
                    // sendInternal already closed the connection
                    return@launch
                }
            }
        }
    }

    fun close(cause: Throwable? = null) {
        if (!closed.compareAndSet(false, true)) return
        keepAliveJob?.cancel()
        onClosed(this)
    }

    suspend fun awaitTermination() {
        keepAliveJob?.join()
    }

    fun snapshot(now: Instant, staleThreshold: JavaDuration): SSEManager.ConnectionSnapshot {
        val lastEvent = lastEventAt
        val status = when {
            isClosed -> SSEManager.ConnectionStatus.CLOSED
            JavaDuration.between(lastEvent, now) > staleThreshold -> SSEManager.ConnectionStatus.STALE
            else -> SSEManager.ConnectionStatus.ACTIVE
        }
        return SSEManager.ConnectionSnapshot(
            id = id,
            connectedAt = connectedAt,
            lastEventAt = lastEvent,
            lastKeepAliveAt = lastKeepAliveAt,
            status = status
        )
    }
}
