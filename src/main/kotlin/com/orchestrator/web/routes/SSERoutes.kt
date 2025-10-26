package com.orchestrator.web.routes

import com.orchestrator.web.sse.SSEEvent
import com.orchestrator.web.sse.SSEConnection
import com.orchestrator.web.sse.SSEManager
import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import io.ktor.util.AttributeKey
import java.util.EnumMap
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

/**
 * Public SSE routes exposed by the web dashboard.
 *
 * Provides four distinct event channels:
 * - /sse/tasks   → task update stream
 * - /sse/index   → index status events
 * - /sse/metrics → metrics dashboard updates
 * - /sse/all     → aggregate stream receiving every event
 */
fun Route.sseRoutes() {
    sse("/sse/tasks") { streamEvents(SSEStreamKind.TASKS) }
    sse("/sse/index") { streamEvents(SSEStreamKind.INDEX) }
    sse("/sse/metrics") { streamEvents(SSEStreamKind.METRICS) }
    sse("/sse/all") { streamEvents(SSEStreamKind.ALL) }
}

internal enum class SSEStreamKind(val pathSegment: String) {
    TASKS("tasks"),
    INDEX("index"),
    METRICS("metrics"),
    ALL("all")
}

private val SSEManagerRegistryKey = AttributeKey<MutableMap<SSEStreamKind, SSEManager>>("web-sse-managers")
private const val DEFAULT_RETRY_MILLIS = 30_000L

private suspend fun ServerSSESession.streamEvents(kind: SSEStreamKind) {
    val lastEventId = call.request.headers["Last-Event-ID"]
    val manager = call.application.ensureSseManager(kind)
    val connectionId = "${kind.pathSegment}-${UUID.randomUUID()}"

    // Standard SSE response headers to keep intermediary caches from buffering.
    try {
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
    } catch (_: UnsupportedOperationException) {
        // Response may already be committed by the SSE plugin; ignore header append in that case.
    }

    // Notify client we acknowledged their reconnection hint (if any).
    lastEventId?.let { send(ServerSentEvent(comments = "resume-from:$it")) }

    val connection = manager.subscribe(connectionId, SSEConnection.Sender { event ->
        send(event.toServerSentEvent())
    })

    try {
        awaitCancellation()
    } finally {
        manager.unsubscribe(connectionId)
    }
}

internal fun Application.installSseManager(kind: SSEStreamKind, manager: SSEManager) {
    val registry = attributes.getOrNull(SSEManagerRegistryKey) ?: EnumMap<SSEStreamKind, SSEManager>(SSEStreamKind::class.java).also {
        attributes.put(SSEManagerRegistryKey, it)
    }

    registry[kind] = manager
    environment.monitor.subscribe(ApplicationStopping) {
        runBlocking { manager.shutdown() }
    }
}

internal fun Application.ensureSseManager(kind: SSEStreamKind): SSEManager {
    val registry = attributes.getOrNull(SSEManagerRegistryKey) ?: EnumMap<SSEStreamKind, SSEManager>(SSEStreamKind::class.java).also {
        attributes.put(SSEManagerRegistryKey, it)
    }

    return registry.getOrPut(kind) {
        val manager = SSEManager(this)

        // Shut down the manager when the application stops to avoid leaked coroutines.
        environment.monitor.subscribe(ApplicationStopping) {
            runBlocking { manager.shutdown() }
        }

        manager
    }
}

private fun SSEEvent.toServerSentEvent(): ServerSentEvent {
    // If we have an htmlFragment, send it directly as data with extracted event name
    if (htmlFragment != null) {
        // Extract event name from JSON data field
        val eventName = extractEventName(data)
        return ServerSentEvent(
            data = htmlFragment,
            event = eventName,
            id = id,
            retry = DEFAULT_RETRY_MILLIS
        )
    }

    return ServerSentEvent(
        data = data,
        event = type.wireName,
        id = id,
        retry = DEFAULT_RETRY_MILLIS
    )
}

private fun extractEventName(jsonData: String): String {
    return try {
        // Parse JSON to extract event name: {"event":"indexSummary",...}
        val startIdx = jsonData.indexOf("\"event\":\"") + 9
        val endIdx = jsonData.indexOf("\"", startIdx)
        if (startIdx > 8 && endIdx > startIdx) {
            jsonData.substring(startIdx, endIdx)
        } else {
            "message"
        }
    } catch (e: Exception) {
        "message"
    }
}
