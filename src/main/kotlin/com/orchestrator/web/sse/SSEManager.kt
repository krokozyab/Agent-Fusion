package com.orchestrator.web.sse

import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Coordinates Server-Sent Events connections and provides utilities for broadcasting.
 */
class SSEManager(
    scope: CoroutineScope,
    private val keepAliveInterval: Duration = 30.seconds,
    private val staleThreshold: JavaDuration = JavaDuration.ofMinutes(1),
    private val clock: () -> Instant = { Instant.now() }
) {

    private val supervisor: Job = SupervisorJob(scope.coroutineContext[Job])
    private val managerScope: CoroutineScope = CoroutineScope(scope.coroutineContext + supervisor)
    private val connections = ConcurrentHashMap<String, SSEConnection>()

    /**
     * Register a new connection and start its lifecycle.
     */
    suspend fun subscribe(connectionId: String, sender: SSEConnection.Sender): SSEConnection {
        val connection = SSEConnection(
            id = connectionId,
            sender = sender,
            scope = managerScope,
            keepAliveInterval = keepAliveInterval,
            clock = clock,
            onClosed = ::handleClosed
        )

        connections.put(connectionId, connection)?.close()

        managerScope.launch { connection.start() }
        return connection
    }

    /**
     * Broadcast an event to all active connections.
     */
    suspend fun broadcast(event: SSEEvent) {
        val snapshot = connections.values.toList()
        snapshot.forEach { connection ->
            runCatching { connection.send(event) }
                .onFailure { /* connection handles closure */ }
        }
    }

    /**
     * Convenience helper for broadcasting simple payloads.
     */
    suspend fun broadcast(type: SSEEventType, data: String, htmlFragment: String? = null) {
        val event = when (type) {
            SSEEventType.CONNECTED -> SSEEvent.connected(message = data, timestamp = clock())
            SSEEventType.MESSAGE -> SSEEvent.message(data = data, htmlFragment = htmlFragment, timestamp = clock())
            SSEEventType.KEEP_ALIVE -> SSEEvent.keepAlive(payload = data, timestamp = clock())
            SSEEventType.DISCONNECTED -> SSEEvent.disconnected(reason = data, timestamp = clock())
            SSEEventType.ERROR -> SSEEvent.error(message = data, htmlFragment = htmlFragment, timestamp = clock())
        }
        broadcast(event)
    }

    /**
     * Remove and close a connection.
     */
    fun unsubscribe(connectionId: String) {
        connections.remove(connectionId)?.close()
    }

    /**
     * Return a snapshot of all connection health metrics.
     */
    fun snapshot(now: Instant = clock()): List<ConnectionSnapshot> =
        connections.values.map { it.snapshot(now, staleThreshold) }

    /**
     * Total number of active connections.
     */
    val activeConnections: Int get() = connections.size

    /**
     * Cancel all connections and stop keep-alive processing.
     */
    suspend fun shutdown() {
        val active = connections.values.toList()
        active.forEach { it.close() }
        connections.clear()
        active.forEach { it.awaitTermination() }
        supervisor.cancelAndJoin()
    }

    private fun handleClosed(connection: SSEConnection) {
        connections.remove(connection.id, connection)
    }

    data class ConnectionSnapshot(
        val id: String,
        val connectedAt: Instant,
        val lastEventAt: Instant,
        val lastKeepAliveAt: Instant,
        val status: ConnectionStatus
    )

    enum class ConnectionStatus { ACTIVE, STALE, CLOSED }
}
