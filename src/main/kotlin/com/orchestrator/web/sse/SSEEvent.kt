package com.orchestrator.web.sse

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a server-sent event emitted to subscribed clients.
 */
data class SSEEvent(
    val id: String,
    val type: SSEEventType,
    val data: String,
    val htmlFragment: String? = null,
    val timestamp: Instant = Instant.now()
) {
    companion object {
        private val counter = AtomicLong(0)

        private fun nextId(): String = counter.incrementAndGet().toString()

        fun connected(message: String = "connected", timestamp: Instant = Instant.now()): SSEEvent =
            SSEEvent(nextId(), SSEEventType.CONNECTED, message, null, timestamp)

        fun message(data: String, htmlFragment: String? = null, timestamp: Instant = Instant.now()): SSEEvent =
            SSEEvent(nextId(), SSEEventType.MESSAGE, data, htmlFragment, timestamp)

        fun keepAlive(payload: String = "ping", timestamp: Instant = Instant.now()): SSEEvent =
            SSEEvent(nextId(), SSEEventType.KEEP_ALIVE, payload, null, timestamp)

        fun disconnected(reason: String = "closed", timestamp: Instant = Instant.now()): SSEEvent =
            SSEEvent(nextId(), SSEEventType.DISCONNECTED, reason, null, timestamp)

        fun error(message: String, htmlFragment: String? = null, timestamp: Instant = Instant.now()): SSEEvent =
            SSEEvent(nextId(), SSEEventType.ERROR, message, htmlFragment, timestamp)
    }
}

/**
 * Enumerates SSE event categories emitted by the orchestrator UI.
 */
enum class SSEEventType(internal val wireName: String) {
    CONNECTED("connected"),
    MESSAGE("message"),
    KEEP_ALIVE("keep-alive"),
    DISCONNECTED("disconnected"),
    ERROR("error")
}
