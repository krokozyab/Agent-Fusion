package com.orchestrator.web.routes

import com.orchestrator.web.routes.SSEStreamKind.TASKS
import com.orchestrator.web.sse.SSEEvent
import com.orchestrator.web.sse.SSEManager
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.sse.SSE
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SSERoutesTest {

    @Test
    fun `tasks SSE endpoint streams broadcast events`() = testApplication {
        val managerJob = SupervisorJob()
        val managerScope = kotlinx.coroutines.CoroutineScope(managerJob + Dispatchers.Default)
        val manager = SSEManager(managerScope, keepAliveInterval = 5.seconds)

        application {
            this@application.install(SSE)
            this@application.installSseManager(TASKS, manager)
            routing {
                sseRoutes()
            }
        }

        val events = Channel<Map<String, String>>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val connected = CompletableDeferred<Unit>()

        val streamJob = SupervisorJob()
        val streamScope = kotlinx.coroutines.CoroutineScope(streamJob + Dispatchers.Default)

        val callJob = streamScope.launch {
            try {
                client.prepareGet("/sse/tasks") {
                    header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                }.execute { response: HttpResponse ->
                    val cacheControl = response.headers[HttpHeaders.CacheControl].orEmpty().lowercase()
                    assertTrue("no-store" in cacheControl)
                    assertEquals("keep-alive", response.headers[HttpHeaders.Connection])
                    val contentTypeHeader = response.headers[HttpHeaders.ContentType].orEmpty().lowercase()
                    assertTrue("text/event-stream" in contentTypeHeader)

                    collectEvents(response, events, connected)
                }
            } catch (t: Throwable) {
                throw t
            }
        }

        connected.await()
        val connectedEvent = withTimeout(2_000) { events.receive() }
        assertEquals("connected", connectedEvent["event"])
        assertEquals("connected", connectedEvent["data"])

        manager.broadcast(SSEEvent.message("task-update"))

        val messageEvent = withTimeout(2_000) { events.receive() }
        assertEquals("message", messageEvent["event"])
        assertEquals("task-update", messageEvent["data"])

        callJob.cancelAndJoin()
        events.close()
        client.close()
        streamScope.cancel()
        streamJob.cancel()
        streamJob.join()
        manager.shutdown()
        managerJob.cancel()
        managerJob.join()
    }

    private suspend fun collectEvents(
        response: HttpResponse,
        sink: Channel<Map<String, String>>,
        signal: CompletableDeferred<Unit>
    ) {
        val channel = response.bodyAsChannel()
        try {
            while (true) {
                val event = readEvent(channel) ?: break
                if (!signal.isCompleted) {
                    signal.complete(Unit)
                }
                sink.trySend(event)
            }
        } finally {
            response.cancel()
            sink.close()
        }
    }

    private suspend fun readEvent(channel: ByteReadChannel): Map<String, String>? {
        val fields = linkedMapOf<String, String>()
        while (true) {
            val line = channel.readUTF8Line() ?: return if (fields.isEmpty()) null else fields
            if (line.isBlank()) {
                if (fields.isEmpty()) {
                    continue
                }
                return fields
            }

            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) continue

            val field = line.substring(0, separatorIndex)
            val value = line.substring(separatorIndex + 1).removePrefix(" ")

            if (field == "data" && fields.containsKey("data")) {
                fields["data"] = fields.getValue("data") + "\n" + value
            } else {
                fields[field] = value
            }
        }
    }
}
