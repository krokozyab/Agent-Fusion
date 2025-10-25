package com.orchestrator.mcp

import com.orchestrator.config.OrchestratorConfig
import com.orchestrator.core.AgentRegistry
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.TaskId
import com.orchestrator.mcp.resources.MetricsResource
import com.orchestrator.mcp.resources.TasksResource
import com.orchestrator.mcp.tools.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.ContentConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.resources
import io.ktor.server.http.content.static
import io.ktor.server.sse.SSE
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.Method
import io.modelcontextprotocol.kotlin.sdk.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.RequestId
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.server.SseServerTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(McpServerImpl::class.java)

/**
 * Minimal MCP server implementation using Ktor (HTTP transport).
 * - Exposes endpoints to list and invoke tools, and query resources.
 * - Configurable host/port from OrchestratorConfig.
 * - Includes structured error handling and logging.
 */
class McpServerImpl(
    private val config: OrchestratorConfig,
    private val agentRegistry: AgentRegistry,
    private val contextConfig: com.orchestrator.context.config.ContextConfig = com.orchestrator.context.config.ContextConfig()
) {
    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    // Tool registration model
    private data class ToolEntry(
        val name: String,
        val description: String,
        val jsonSchema: String
    )

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    // Build tool instances
    private val routingModule by lazy { com.orchestrator.modules.routing.RoutingModule(agentRegistry) }
    private val taskRepository by lazy { com.orchestrator.storage.repositories.TaskRepository }

    private val createSimpleTaskTool by lazy { CreateSimpleTaskTool(agentRegistry, routingModule, taskRepository) }
    private val createConsensusTaskTool by lazy { CreateConsensusTaskTool(agentRegistry, routingModule, taskRepository) }
    private val assignTaskTool by lazy { AssignTaskTool(agentRegistry, routingModule, taskRepository) }
    private val continueTaskTool by lazy { ContinueTaskTool() }
    private val completeTaskTool by lazy { CompleteTaskTool() }
    private val getPendingTasksTool by lazy { GetPendingTasksTool() }
    private val getTaskStatusTool by lazy { GetTaskStatusTool() }
    private val submitInputTool by lazy { SubmitInputTool() }
    private val respondToTaskTool by lazy { RespondToTaskTool() }
    private val queryContextTool by lazy { QueryContextTool(contextConfig) }
    private val metricsCollector by lazy { com.orchestrator.modules.context.ContextMetricsCollector() }
    private val getContextStatsTool by lazy { GetContextStatsTool(contextConfig, metricsCollector) }
    private val refreshContextTool by lazy { RefreshContextTool(contextConfig) }
    private val rebuildContextTool by lazy { RebuildContextTool(contextConfig) }
    private val getRebuildStatusTool by lazy { GetRebuildStatusTool(contextConfig) }

    // Build resources
    private val tasksResource by lazy { TasksResource() }
    private val metricsResource by lazy { MetricsResource() }

    private val sseSessions = ConcurrentHashMap<String, SseServerTransport>()
    private val streamableSessions = StreamableHttpSessionManager({ sessionId -> createMcpServer(sessionId) }, STREAMABLE_REQUEST_TIMEOUT_MS)

    // Track session -> agent mapping for automatic agent identification
    private val sessionToAgent = ConcurrentHashMap<String, AgentId>()

    // Thread-local to track current session ID during request processing
    private val currentSessionId = ThreadLocal<String?>()

    companion object {
        private const val STREAMABLE_SESSION_HEADER = "mcp-session-id"
        private const val STREAMABLE_RESUMPTION_HEADER = "mcp-resumption-token"
        private const val SSE_POST_ENDPOINT = "/mcp"
        private const val STREAMABLE_REQUEST_TIMEOUT_MS = 30_000L
        private const val INITIALIZE_METHOD = "initialize"
        private const val ORCHESTRATOR_TASKS_PREFIX = "orchestrator://tasks"
        private const val ORCHESTRATOR_METRICS_PREFIX = "orchestrator://metrics"
    }

    fun start() {
        // Only HTTP transport is implemented for now
        val host = config.server.host
        val port = config.server.port

        val engine = embeddedServer(Netty, port = port, host = host) {
            install(DefaultHeaders)
            install(CallId) {
                retrieveFromHeader("X-Request-ID")
                generate { java.util.UUID.randomUUID().toString() }
                verify { it.isNotBlank() }
            }
            install(CallLogging) {
                callIdMdc("requestId")
            }
            install(CORS) {
                anyHost()
                allowHeader("Content-Type")
                allowHeader("X-Request-ID")
            }
            install(ContentNegotiation) {
                // Allow clients to request Server-Sent Events without triggering 406 from content negotiation
                register(ContentType.Text.EventStream, object : ContentConverter {
                    override suspend fun serialize(
                        contentType: ContentType,
                        charset: io.ktor.utils.io.charsets.Charset,
                        typeInfo: io.ktor.util.reflect.TypeInfo,
                        value: Any?
                    ): OutgoingContent? = null

                    override suspend fun deserialize(
                        charset: io.ktor.utils.io.charsets.Charset,
                        typeInfo: io.ktor.util.reflect.TypeInfo,
                        content: ByteReadChannel
                    ): Any? = null
                })
                json(json)
            }
            install(SSE)
            install(StatusPages) {
                exception<IllegalArgumentException> { call, cause ->
                    call.respond(HttpStatusCode.BadRequest, errorBody("bad_request", cause.message ?: "Invalid request"))
                }
                exception<NoSuchElementException> { call, cause ->
                    call.respond(HttpStatusCode.NotFound, errorBody("not_found", cause.message ?: "Not found"))
                }
                exception<Throwable> { call, cause ->
                    call.application.environment.log.error("Unhandled error", cause)
                    call.respond(HttpStatusCode.InternalServerError, errorBody("internal_error", cause.message ?: "Internal error"))
                }
                status(HttpStatusCode.NotAcceptable) { call, _ ->
                    val accept = call.request.headers[HttpHeaders.Accept]?.takeIf { it.isNotBlank() }
                    call.application.environment.log.warn(
                        "406 Not Acceptable for {} with Accept header {}",
                        call.request.uri,
                        accept ?: "<missing>"
                    )
                    val message = if (accept.isNullOrBlank()) {
                        "Client must send an Accept header supporting text/event-stream or application/json"
                    } else {
                        "Received unsupported Accept header '$accept'. Expected text/event-stream for SSE or application/json"
                    }
                    call.respond(HttpStatusCode.NotAcceptable, errorBody("not_acceptable", message))
                }
                status(HttpStatusCode.NotFound) { call, _ ->
                    call.respond(HttpStatusCode.NotFound, errorBody("not_found", "Route not found"))
                }
            }

            routing {
                static("/static") {
                    resources("static")
                }
                get("/healthz") {
                    call.respond(mapOf("status" to "ok"))
                }
                // MCP entry point supporting both SSE and Streamable HTTP transports
                get("/mcp") {
                    val streamSessionId = call.request.header(STREAMABLE_SESSION_HEADER)?.trim()?.ifEmpty { null }
                    if (streamSessionId != null) {
                        call.handleStreamableGet(streamSessionId)
                    } else {
                        call.handleSseGet()
                    }
                }

                post("/mcp") {
                    val sseSessionId = call.request.queryParameters["sessionId"]?.trim()?.ifEmpty { null }
                    if (sseSessionId != null) {
                        call.handleSsePost(sseSessionId)
                        return@post
                    }

                    val message = call.receiveJsonRpcMessage() ?: return@post
                    val streamSessionId = call.request.header(STREAMABLE_SESSION_HEADER)?.trim()?.ifEmpty { null }

                    if (streamSessionId != null) {
                        call.handleStreamablePost(streamSessionId, message)
                    } else {
                        call.handleStreamableHandshake(message)
                    }
                }

                delete("/mcp") {
                    val streamSessionId = call.request.header(STREAMABLE_SESSION_HEADER)?.trim()?.ifEmpty { null }
                    if (streamSessionId == null) {
                        call.respond(
                            HttpStatusCode.MethodNotAllowed,
                            errorBody("method_not_allowed", "DELETE /mcp requires mcp-session-id header")
                        )
                        return@delete
                    }
                    call.handleStreamableDelete(streamSessionId)
                }

                // MCP: tools discovery
                get("/mcp/tools") {
                    call.respond(mapOf(
                        "tools" to tools().map { t ->
                            mapOf(
                                "name" to t.name,
                                "description" to t.description,
                                "schema" to t.jsonSchema
                            )
                        }
                    ))
                }

                // MCP: invoke a tool
                post("/mcp/tools/call") {
                    val req = call.receive<ToolCallRequest>()
                    val result = executeTool(req.name, req.params)
                    val response = buildJsonObject {
                        put("ok", JsonPrimitive(true))
                        put("result", toolResultToJson(result))
                    }
                    call.respondText(response.toString(), contentType = ContentType.Application.Json)
                }

                // MCP: resources discovery
                get("/mcp/resources") {
                    call.respond(mapOf(
                        "resources" to listOf(
                            mapOf("uri" to "tasks://", "description" to "Tasks listing and filtering"),
                            mapOf("uri" to "metrics://", "description" to "Aggregated metrics and series"),
                            mapOf("uri" to ORCHESTRATOR_TASKS_PREFIX, "description" to "Task listing and details (alias)"),
                            mapOf("uri" to ORCHESTRATOR_METRICS_PREFIX, "description" to "Metrics queries (alias)")
                        )
                    ))
                }

                // MCP: fetch resource by URI via query param
                get("/mcp/resources/fetch") {
                    val uri = call.request.queryParameters["uri"]?.trim().orEmpty()
                    if (uri.isEmpty()) throw IllegalArgumentException("Missing 'uri' query parameter")
                    val body = fetchResourceBody(uri)
                    call.respondText(body, contentType = ContentType.Application.Json)
                }
            }
        }
        engine.start(wait = false)
        this.engine = engine
    }

    // ---- Transport handlers ----

    private suspend fun ApplicationCall.handleSseGet() {
        log.info("Handling SSE GET request")
        respond(SSEServerContent(this) {
            val transport = SseServerTransport(SSE_POST_ENDPOINT, this)
            val sessionId = transport.sessionId
            log.info("[Session: $sessionId] Created SSE transport")
            val closed = AtomicBoolean(false)
            val cleanup = {
                if (closed.compareAndSet(false, true)) {
                    sseSessions.remove(sessionId)
                    log.info("[Session: $sessionId] Cleaned up SSE session")
                }
            }

            sseSessions[sessionId] = transport
            transport.onClose(cleanup)

            val server = createMcpServer(sessionId)
            server.onClose(cleanup)

            try {
                val session = server.connect(transport)
                installOrchestratorResourceHandler(server, session)
            } catch (t: Throwable) {
                cleanup()
                throw t
            }
        })
    }

    private suspend fun ApplicationCall.handleSsePost(sessionId: String) {
        val transport = sseSessions[sessionId]
        if (transport == null) {
            respond(HttpStatusCode.NotFound, errorBody("session_not_found", "Unknown session '$sessionId'"))
            return
        }
        transport.handlePostMessage(this)
    }

    private suspend fun ApplicationCall.handleStreamableGet(sessionId: String) {
        val session = streamableSessions.get(sessionId)
        if (session == null) {
            respond(HttpStatusCode.NotFound, errorBody("session_not_found", "Unknown session '$sessionId'"))
            return
        }
        val resumptionToken = request.headers[STREAMABLE_RESUMPTION_HEADER]
            ?: request.headers[HttpHeaders.LastEventID]

        respond(SSEServerContent(this) {
            session.stream(this, resumptionToken)
        })
    }

    private suspend fun ApplicationCall.handleStreamablePost(
        sessionId: String,
        message: JSONRPCMessage
    ) {
        val session = streamableSessions.get(sessionId)
        if (session == null) {
            respond(HttpStatusCode.NotFound, errorBody("session_not_found", "Unknown session '$sessionId'"))
            return
        }

        // Set current session for agent resolution
        currentSessionId.set(sessionId)
        try {
            when (val result = session.handleMessage(message)) {
                is StreamableHttpServerTransport.PostResult.Json -> respondJsonRpc(sessionId, result.response)
                StreamableHttpServerTransport.PostResult.Accepted -> {
                    response.header(STREAMABLE_SESSION_HEADER, sessionId)
                    respond(HttpStatusCode.Accepted)
                }
                is StreamableHttpServerTransport.PostResult.Error -> {
                    respond(result.status, errorBody(result.code, result.message))
                }
            }
        } finally {
            currentSessionId.remove()
        }
    }

    private suspend fun ApplicationCall.handleStreamableHandshake(message: JSONRPCMessage) {
        log.info("Handling Streamable HTTP handshake request")
        if (message !is JSONRPCRequest || message.method != INITIALIZE_METHOD) {
            respond(HttpStatusCode.BadRequest, errorBody("invalid_request", "First message must be an initialize request"))
            return
        }

        val session = try {
            streamableSessions.createSession()
        } catch (t: Throwable) {
            application.environment.log.error("Failed to start streamable MCP session", t)
            respond(HttpStatusCode.InternalServerError, errorBody("internal_error", "Unable to create session"))
            return
        }

        log.info("Created Streamable HTTP session with ID: ${session.sessionId}")

        // Extract agent identity from initialize request if available
        extractAgentIdFromInitialize(message)?.let { agentId ->
            sessionToAgent[session.sessionId] = agentId
            log.info("Session ${session.sessionId} associated with agent ${agentId.value}")
        }

        val result = session.handleMessage(message)
        when (result) {
            is StreamableHttpServerTransport.PostResult.Json -> respondJsonRpc(session.sessionId, result.response)
            StreamableHttpServerTransport.PostResult.Accepted -> {
                response.header(STREAMABLE_SESSION_HEADER, session.sessionId)
                respond(HttpStatusCode.Accepted)
            }
            is StreamableHttpServerTransport.PostResult.Error -> {
                // Tear down the session on failure so clients can retry cleanly
                streamableSessions.remove(session.sessionId)
                sessionToAgent.remove(session.sessionId)
                respond(result.status, errorBody(result.code, result.message))
            }
        }
    }

    private suspend fun ApplicationCall.handleStreamableDelete(sessionId: String) {
        val removed = streamableSessions.remove(sessionId)
        if (!removed) {
            respond(HttpStatusCode.NotFound, errorBody("session_not_found", "Unknown session '$sessionId'"))
            return
        }
        respond(HttpStatusCode.NoContent)
    }

    private suspend fun ApplicationCall.receiveJsonRpcMessage(): JSONRPCMessage? {
        val rawBody = runCatching { receiveText() }.getOrElse { throwable ->
            respond(HttpStatusCode.BadRequest, errorBody("invalid_body", throwable.message ?: "Unable to read request body"))
            return null
        }

        return runCatching { McpJson.decodeFromString<JSONRPCMessage>(rawBody) }.getOrElse { throwable ->
            respond(HttpStatusCode.BadRequest, errorBody("invalid_json", throwable.message ?: "Invalid JSON-RPC payload"))
            null
        }
    }

    private suspend fun ApplicationCall.respondJsonRpc(sessionId: String, response: JSONRPCResponse) {
        this.response.header(STREAMABLE_SESSION_HEADER, sessionId)
        val payload = McpJson.encodeToString(JSONRPCResponse.serializer(), response)
        respondText(payload, contentType = ContentType.Application.Json)
    }

    private inner class StreamableHttpSessionManager(
        private val serverFactory: (String) -> Server,
        private val requestTimeoutMs: Long
    ) {
        private val sessions = ConcurrentHashMap<String, StreamableHttpSession>()

        suspend fun createSession(): StreamableHttpSession {
            val transport = StreamableHttpServerTransport(requestTimeoutMs)
            val sessionId = transport.sessionId  // Use the transport's session ID
            val server = serverFactory(sessionId)
            val session = StreamableHttpSession(server, transport) { id -> sessions.remove(id) }
            sessions[sessionId] = session
            try {
                session.connect()
            } catch (t: Throwable) {
                sessions.remove(sessionId)
                throw t
            }
            return session
        }

        fun get(sessionId: String): StreamableHttpSession? = sessions[sessionId]

        suspend fun remove(sessionId: String): Boolean {
            val session = sessions.remove(sessionId) ?: return false
            session.terminate()
            return true
        }
    }

    private inner class StreamableHttpSession(
        private val server: Server,
        private val transport: StreamableHttpServerTransport,
        private val onClosed: (String) -> Unit
    ) {
        val sessionId: String = transport.sessionId
        private val closed = AtomicBoolean(false)
        private val notifyClose: () -> Unit
        private val toolListAnnounced = AtomicBoolean(false)
        private var session: ServerSession? = null

        init {
            notifyClose = {
                if (closed.compareAndSet(false, true)) {
                    onClosed(sessionId)
                }
            }
            transport.onClose(notifyClose)
            server.onClose(notifyClose)
        }

        suspend fun connect() {
            val serverSession = server.connect(transport)
            installOrchestratorResourceHandler(server, serverSession)
            session = serverSession
            serverSession.onClose(notifyClose)
        }

        suspend fun handleMessage(message: JSONRPCMessage): StreamableHttpServerTransport.PostResult {
            val result = transport.handleMessage(message)
            if (message is JSONRPCRequest &&
                message.method.equals(INITIALIZE_METHOD, ignoreCase = true) &&
                toolListAnnounced.compareAndSet(false, true)
            ) {
                val currentSession = session
                if (currentSession != null && server.tools.isNotEmpty()) {
                    runCatching { currentSession.sendToolListChanged() }
                        .onFailure { throwable ->
                            log.warn("Failed to notify tool list change for session {}", sessionId, throwable)
                        }
                }
            }
            return result
        }

        suspend fun stream(session: ServerSSESession, resumptionToken: String?) {
            transport.attachSseSession(session, resumptionToken)
        }

        suspend fun terminate() {
            transport.close()
            server.close()
            session = null
        }
    }

    private class StreamableHttpServerTransport(
        private val requestTimeoutMs: Long
    ) : AbstractTransport() {

        sealed interface PostResult {
            data class Json(val response: JSONRPCResponse) : PostResult
            object Accepted : PostResult
            data class Error(val status: HttpStatusCode, val code: String, val message: String) : PostResult
        }

        val sessionId: String = UUID.randomUUID().toString()

        private val pendingResponses = ConcurrentHashMap<RequestId, CompletableDeferred<JSONRPCResponse>>()
        private val sendMutex = Mutex()
        private val pendingEvents = mutableListOf<JSONRPCMessage>()
        private var sseSession: ServerSSESession? = null
        private val eventCounter = AtomicLong(0)
        private val started = AtomicBoolean(false)
        private val closed = AtomicBoolean(false)

        override suspend fun start() {
            if (!started.compareAndSet(false, true)) {
                error("StreamableHttpServerTransport already started")
            }
        }

        override suspend fun send(message: JSONRPCMessage) {
            if (message is JSONRPCResponse) {
                val deferred = pendingResponses.remove(message.id)
                if (deferred != null) {
                    deferred.complete(message)
                    return
                }
            }

            var shouldDispatch = false
            sendMutex.withLock {
                val currentSession = sseSession
                if (currentSession == null) {
                    pendingEvents.add(message)
                } else {
                    shouldDispatch = true
                }
            }

            if (shouldDispatch) {
                dispatchToSse(message)
            }
        }

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return

            val sessionToClose = sendMutex.withLock {
                val current = sseSession
                sseSession = null
                current
            }

            sessionToClose?.close()

            pendingResponses.values.forEach { it.cancel(CancellationException("Transport closed")) }
            pendingResponses.clear()

            _onClose()
        }

        suspend fun handleMessage(message: JSONRPCMessage): PostResult {
            return when (message) {
                is JSONRPCRequest -> handleRequest(message)
                is JSONRPCNotification -> {
                    _onMessage(message)
                    PostResult.Accepted
                }
                else -> PostResult.Error(HttpStatusCode.BadRequest, "invalid_message", "Unsupported JSON-RPC message type")
            }
        }

        suspend fun attachSseSession(session: ServerSSESession, _resumptionToken: String?) {
            val buffered: List<JSONRPCMessage> = sendMutex.withLock {
                sseSession = session
                val copy = pendingEvents.toList()
                pendingEvents.clear()
                copy
            }

            try {
                for (message in buffered) {
                    dispatchToSse(message)
                }
                session.coroutineContext.job.join()
            } finally {
                sendMutex.withLock {
                    if (sseSession === session) {
                        sseSession = null
                    }
                }
            }
        }

        private suspend fun handleRequest(request: JSONRPCRequest): PostResult {
            val deferred = CompletableDeferred<JSONRPCResponse>()
            pendingResponses[request.id] = deferred

            try {
                _onMessage(request)
            } catch (t: Throwable) {
                pendingResponses.remove(request.id)
                return PostResult.Error(HttpStatusCode.InternalServerError, "internal_error", t.message ?: "Unhandled error")
            }

            val response = try {
                if (requestTimeoutMs > 0) {
                    withTimeout(requestTimeoutMs) { deferred.await() }
                } else {
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                pendingResponses.remove(request.id)
                return PostResult.Error(HttpStatusCode.GatewayTimeout, "timeout", "Timed out waiting for response")
            } catch (t: Throwable) {
                pendingResponses.remove(request.id)
                return PostResult.Error(HttpStatusCode.InternalServerError, "internal_error", t.message ?: "Unhandled error")
            }

            return PostResult.Json(response)
        }

        private suspend fun dispatchToSse(message: JSONRPCMessage) {
            val session = sendMutex.withLock { sseSession }
            if (session == null) {
                sendMutex.withLock { pendingEvents.add(message) }
                return
            }

            val payload = McpJson.encodeToString(JSONRPCMessage.serializer(), message)
            val eventId = eventCounter.incrementAndGet().toString()
            try {
                session.send(event = "message", data = payload, id = eventId)
            } catch (t: Throwable) {
                sendMutex.withLock {
                    if (sseSession === session) {
                        sseSession = null
                        pendingEvents.add(message)
                    }
                }
                throw t
            }
        }
    }

    private fun createMcpServer(sessionId: String): Server {
        log.info("[Session: $sessionId] Creating new MCP Server instance (Thread: ${Thread.currentThread().name})")
        val server = Server(
            serverInfo = Implementation(
                name = "codex-to-claude-orchestrator",
                version = "0.1.0"
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(subscribe = false, listChanged = false)
                )
            ),
            instructions = "Use orchestrator tools to manage tasks and submit work products."
        )

        registerTools(server, sessionId)
        registerResources(server)
        log.info("[Session: $sessionId] MCP Server instance created with ${tools().size} tools registered")

        return server
    }

    private fun registerTools(server: Server, sessionId: String) {
        tools().forEach { entry ->
            log.info("[Session: $sessionId] Registering tool: ${entry.name}")
            val inputSchema = toolInputFromJsonSchema(entry.jsonSchema)
            server.addTool(
                name = entry.name,
                description = entry.description,
                inputSchema = inputSchema
            ) { request ->
                runCatching {
                    val result = executeTool(entry.name, request.arguments)
                    val payload = toolResultToJson(result)
                    val structured = when (payload) {
                        is JsonObject -> payload
                        else -> buildJsonObject { put("value", payload) }
                    }
                    val serialized = json.encodeToString(JsonElement.serializer(), structured)
                    CallToolResult(
                        content = listOf(TextContent(serialized)),
                        structuredContent = structured,
                        isError = false
                    )
                }.getOrElse { throwable ->
                    val message = throwable.message ?: "Unhandled error"
                    CallToolResult(
                        content = listOf(TextContent("Error: $message")),
                        structuredContent = buildJsonObject { put("error", JsonPrimitive(message)) },
                        isError = true
                    )
                }
            }
        }
    }

    private fun registerResources(server: Server) {
        server.addResource(
            uri = "tasks://",
            name = "tasks",
            description = "Tasks listing and filtering",
            mimeType = ContentType.Application.Json.toString()
        ) { request ->
            val params = parseQueryParams(request.uri)
            val body = tasksResource.list(params)
            jsonResource(request.uri, body)
        }

        server.addResource(
            uri = "metrics://",
            name = "metrics",
            description = "Aggregated metrics and series",
            mimeType = ContentType.Application.Json.toString()
        ) { request ->
            val params = parseQueryParams(request.uri)
            val body = metricsResource.query(params)
            jsonResource(request.uri, body)
        }
    }

    private fun toolInputFromJsonSchema(schema: String): Tool.Input {
        val parsed = runCatching { json.parseToJsonElement(schema).asObj() }.getOrElse { return Tool.Input() }
        val properties = parsed["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val required = parsed["required"]?.jsonArray
            ?.mapNotNull { element -> element.jsonPrimitive.contentOrNull }
            ?.ifEmpty { null }

        return Tool.Input(properties = properties, required = required)
    }

    fun stop(gracePeriodMillis: Long = 1000, timeoutMillis: Long = 2000) {
        engine?.stop(gracePeriodMillis, timeoutMillis)
        engine = null
    }

    private fun installOrchestratorResourceHandler(server: Server, session: ServerSession) {
        session.setRequestHandler<ReadResourceRequest>(Method.Defined.ResourcesRead) { request, _ ->
            resolveResourceRead(server, request)
        }
    }

    private suspend fun resolveResourceRead(server: Server, request: ReadResourceRequest): ReadResourceResult {
        val uri = request.uri.trim()
        require(uri.isNotEmpty()) { "Resource URI cannot be blank" }

        val direct = server.resources[uri]
        if (direct != null) {
            return direct.readHandler(request)
        }

        return when {
            uri.startsWith(ORCHESTRATOR_TASKS_PREFIX) -> readOrchestratorTasksResource(uri)
            uri.startsWith(ORCHESTRATOR_METRICS_PREFIX) -> readOrchestratorMetricsResource(uri)
            else -> throw IllegalArgumentException("Resource not found: $uri")
        }
    }

    private fun readOrchestratorTasksResource(uri: String): ReadResourceResult {
        val suffix = uri.removePrefix(ORCHESTRATOR_TASKS_PREFIX)
        if (suffix.isEmpty() || suffix == "/" || suffix.startsWith("?")) {
            val params = parseQueryParams(uri)
            val body = tasksResource.list(params)
            return jsonResource(uri, body)
        }

        val trimmed = suffix.removePrefix("/")
        if (trimmed.isEmpty()) {
            val params = parseQueryParams(uri)
            val body = tasksResource.list(params)
            return jsonResource(uri, body)
        }

        val idPart = trimmed.substringBefore('?').trim()
        require(idPart.isNotEmpty()) { "Task resource missing id in URI '$uri'" }

        val body = tasksResource.getById(TaskId(idPart))
        return jsonResource(uri, body)
    }

    private fun readOrchestratorMetricsResource(uri: String): ReadResourceResult {
        val params = parseQueryParams(uri)
        val body = metricsResource.query(params)
        return jsonResource(uri, body)
    }

    private fun jsonResource(uri: String, body: String): ReadResourceResult =
        ReadResourceResult(
            contents = listOf(
                TextResourceContents(
                    text = body,
                    uri = uri,
                    mimeType = ContentType.Application.Json.toString()
                )
            )
        )

    private fun fetchResourceBody(uri: String): String = when {
        uri.startsWith("tasks://") -> {
            val params = parseQueryParams(uri)
            tasksResource.list(params)
        }
        uri.startsWith("metrics://") -> {
            val params = parseQueryParams(uri)
            metricsResource.query(params)
        }
        else -> throw IllegalArgumentException("Unsupported resource URI '$uri'")
    }

    private fun parseQueryParams(uri: String): Map<String, String> {
        val query = uri.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            if (part.isEmpty()) {
                null
            } else {
                val idx = part.indexOf('=')
                if (idx <= 0) {
                    null
                } else {
                    val key = URLDecoder.decode(part.substring(0, idx), Charsets.UTF_8)
                    val value = URLDecoder.decode(part.substring(idx + 1), Charsets.UTF_8)
                    key to value
                }
            }
        }.toMap()
    }

    private fun executeTool(name: String, params: JsonElement): Any = when (name) {
        "create_simple_task" -> createSimpleTaskTool.execute(mapCreateSimpleParams(params))
        "create_consensus_task" -> createConsensusTaskTool.execute(mapCreateConsensusParams(params))
        "assign_task" -> assignTaskTool.execute(mapAssignTaskParams(params))
        "continue_task" -> continueTaskTool.execute(mapContinueTaskParams(params))
        "complete_task" -> completeTaskTool.execute(mapCompleteTaskParams(params))
        "get_pending_tasks" -> {
            val (p, resolvedId) = mapGetPendingTasksParams(params)
            getPendingTasksTool.execute(p, resolvedId)
        }
        "get_task_status" -> getTaskStatusTool.execute(mapGetTaskStatusParams(params))
        "submit_input" -> {
            val (p, resolvedId) = mapSubmitInputParams(params)
            submitInputTool.execute(p, resolvedId)
        }
        "respond_to_task" -> {
            val (p, resolvedId) = mapRespondToTaskParams(params)
            respondToTaskTool.execute(p, resolvedId)
        }
        "query_context" -> queryContextTool.execute(mapQueryContextParams(params))
        "get_context_stats" -> getContextStatsTool.execute(mapGetContextStatsParams(params))
        "refresh_context" -> refreshContextTool.execute(mapRefreshContextParams(params))
        "rebuild_context" -> rebuildContextTool.execute(mapRebuildContextParams(params))
        "get_rebuild_status" -> getRebuildStatusTool.execute(mapGetRebuildStatusParams(params))
        else -> throw IllegalArgumentException("Unknown tool '$name'")
    }

    private fun toolResultToJson(result: Any): JsonElement = when (result) {
        is CreateSimpleTaskTool.Result -> taskCreationResultToJson(result.taskId, result.status, result.routing, result.primaryAgentId, result.participantAgentIds, result.warnings)
        is CreateConsensusTaskTool.Result -> taskCreationResultToJson(result.taskId, result.status, result.routing, result.primaryAgentId, result.participantAgentIds, result.warnings)
        is AssignTaskTool.Result -> taskCreationResultToJson(result.taskId, result.status, result.routing, result.primaryAgentId, result.participantAgentIds, result.warnings)
        is ContinueTaskTool.Result -> continueTaskResultToJson(result)
        is CompleteTaskTool.Result -> completeTaskResultToJson(result)
        is GetPendingTasksTool.Result -> getPendingTasksResultToJson(result)
        is GetTaskStatusTool.Result -> getTaskStatusResultToJson(result)
        is SubmitInputTool.Result -> submitInputResultToJson(result)
        is RespondToTaskTool.Result -> respondToTaskResultToJson(result)
        is QueryContextTool.Result -> queryContextResultToJson(result)
        is GetContextStatsTool.Result -> getContextStatsResultToJson(result)
        is RefreshContextTool.Result -> refreshContextResultToJson(result)
        is RebuildContextTool.Result -> rebuildContextResultToJson(result)
        is GetRebuildStatusTool.Result -> getRebuildStatusResultToJson(result)
        else -> anyToJsonElement(result)
    }

    private fun taskCreationResultToJson(
        taskId: String,
        status: String,
        routing: String,
        primaryAgentId: String?,
        participants: List<String>,
        warnings: List<String>
    ): JsonObject = buildJsonObject {
        put("taskId", JsonPrimitive(taskId))
        put("status", JsonPrimitive(status))
        put("routing", JsonPrimitive(routing))
        if (primaryAgentId == null) {
            put("primaryAgentId", JsonNull)
        } else {
            put("primaryAgentId", JsonPrimitive(primaryAgentId))
        }
        put("participantAgentIds", buildJsonArray { participants.forEach { add(JsonPrimitive(it)) } })
        put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
    }

    private fun continueTaskResultToJson(result: ContinueTaskTool.Result): JsonObject = buildJsonObject {
        put("task", buildJsonObject {
            put("id", JsonPrimitive(result.task.id))
            put("title", JsonPrimitive(result.task.title))
            if (result.task.description == null) {
                put("description", JsonNull)
            } else {
                put("description", JsonPrimitive(result.task.description))
            }
            put("type", JsonPrimitive(result.task.type))
            put("status", JsonPrimitive(result.task.status))
            put("routing", JsonPrimitive(result.task.routing))
            put("assigneeIds", buildJsonArray { result.task.assigneeIds.forEach { add(JsonPrimitive(it)) } })
            put("dependencies", buildJsonArray { result.task.dependencies.forEach { add(JsonPrimitive(it)) } })
            put("complexity", JsonPrimitive(result.task.complexity))
            put("risk", JsonPrimitive(result.task.risk))
            put("createdAt", JsonPrimitive(result.task.createdAt))
            if (result.task.updatedAt == null) {
                put("updatedAt", JsonNull)
            } else {
                put("updatedAt", JsonPrimitive(result.task.updatedAt))
            }
            if (result.task.dueAt == null) {
                put("dueAt", JsonNull)
            } else {
                put("dueAt", JsonPrimitive(result.task.dueAt))
            }
            put("metadata", buildJsonObject {
                result.task.metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
        })
        put("proposals", buildJsonArray {
            result.proposals.forEach { proposal ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(proposal.id))
                    put("taskId", JsonPrimitive(proposal.taskId))
                    put("agentId", JsonPrimitive(proposal.agentId))
                    put("inputType", JsonPrimitive(proposal.inputType))
                    put("confidence", JsonPrimitive(proposal.confidence))
                    put("tokenUsage", buildJsonObject {
                        proposal.tokenUsage.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                    put("content", anyToJsonElement(proposal.content))
                    put("createdAt", JsonPrimitive(proposal.createdAt))
                    put("metadata", buildJsonObject {
                        proposal.metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                })
            }
        })
        put("context", buildJsonObject {
            put("history", buildJsonArray {
                result.context.history.forEach { message ->
                    add(buildJsonObject {
                        if (message.id == null) {
                            put("id", JsonNull)
                        } else {
                            put("id", JsonPrimitive(message.id))
                        }
                        put("role", JsonPrimitive(message.role))
                        put("content", JsonPrimitive(message.content))
                        if (message.agentId == null) {
                            put("agentId", JsonNull)
                        } else {
                            put("agentId", JsonPrimitive(message.agentId))
                        }
                        put("tokens", JsonPrimitive(message.tokens))
                        put("ts", JsonPrimitive(message.ts))
                        if (message.metadataJson == null) {
                            put("metadataJson", JsonNull)
                        } else {
                            put("metadataJson", JsonPrimitive(message.metadataJson))
                        }
                    })
                }
            })
            put("fileHistory", buildJsonArray {
                result.context.fileHistory.forEach { fileOp ->
                    add(buildJsonObject {
                        put("agentId", JsonPrimitive(fileOp.agentId))
                        put("type", JsonPrimitive(fileOp.type))
                        put("path", JsonPrimitive(fileOp.path))
                        put("ts", JsonPrimitive(fileOp.ts))
                        put("version", JsonPrimitive(fileOp.version))
                        if (fileOp.diff == null) {
                            put("diff", JsonNull)
                        } else {
                            put("diff", JsonPrimitive(fileOp.diff))
                        }
                        if (fileOp.contentHash == null) {
                            put("contentHash", JsonNull)
                        } else {
                            put("contentHash", JsonPrimitive(fileOp.contentHash))
                        }
                        put("conflict", JsonPrimitive(fileOp.conflict))
                        if (fileOp.conflictReason == null) {
                            put("conflictReason", JsonNull)
                        } else {
                            put("conflictReason", JsonPrimitive(fileOp.conflictReason))
                        }
                        put("metadata", buildJsonObject {
                            fileOp.metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                        })
                    })
                }
            })
        })
    }

    private fun completeTaskResultToJson(result: CompleteTaskTool.Result): JsonObject = buildJsonObject {
        put("taskId", JsonPrimitive(result.taskId))
        put("status", JsonPrimitive(result.status))
        if (result.decisionId == null) {
            put("decisionId", JsonNull)
        } else {
            put("decisionId", JsonPrimitive(result.decisionId))
        }
        put("snapshotIds", buildJsonArray { result.snapshotIds.forEach { add(JsonPrimitive(it)) } })
        put("recordedMetrics", buildJsonObject {
            result.recordedMetrics.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        })
    }

    private fun getPendingTasksResultToJson(result: GetPendingTasksTool.Result): JsonObject = buildJsonObject {
        put("agentId", JsonPrimitive(result.agentId))
        put("count", JsonPrimitive(result.count))
        put("tasks", buildJsonArray {
            result.tasks.forEach { task ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(task.id))
                    put("title", JsonPrimitive(task.title))
                    put("status", JsonPrimitive(task.status))
                    put("type", JsonPrimitive(task.type))
                    put("priority", JsonPrimitive(task.priority))
                    put("createdAt", JsonPrimitive(task.createdAt))
                    if (task.dueAt == null) {
                        put("dueAt", JsonNull)
                    } else {
                        put("dueAt", JsonPrimitive(task.dueAt))
                    }
                    put("contextPreview", JsonPrimitive(task.contextPreview))
                })
            }
        })
    }

    private fun getTaskStatusResultToJson(result: GetTaskStatusTool.Result): JsonObject = buildJsonObject {
        put("taskId", JsonPrimitive(result.taskId))
        put("status", JsonPrimitive(result.status))
        put("type", JsonPrimitive(result.type))
        put("routing", JsonPrimitive(result.routing))
        put("assignees", buildJsonArray { result.assignees.forEach { add(JsonPrimitive(it)) } })
        put("createdAt", JsonPrimitive(result.createdAt))
        if (result.updatedAt == null) {
            put("updatedAt", JsonNull)
        } else {
            put("updatedAt", JsonPrimitive(result.updatedAt))
        }
        if (result.dueAt == null) {
            put("dueAt", JsonNull)
        } else {
            put("dueAt", JsonPrimitive(result.dueAt))
        }
        put("metadata", buildJsonObject {
            result.metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        })
    }

    private fun submitInputResultToJson(result: SubmitInputTool.Result): JsonObject = buildJsonObject {
        put("taskId", JsonPrimitive(result.taskId))
        put("proposalId", JsonPrimitive(result.proposalId))
        put("inputType", JsonPrimitive(result.inputType))
        put("taskStatus", JsonPrimitive(result.taskStatus))
        put("message", JsonPrimitive(result.message))
    }

    private fun respondToTaskResultToJson(result: RespondToTaskTool.Result): JsonObject = buildJsonObject {
        put("taskId", JsonPrimitive(result.taskId))
        put("proposalId", JsonPrimitive(result.proposalId))
        put("inputType", JsonPrimitive(result.inputType))
        put("taskStatus", JsonPrimitive(result.taskStatus))
        put("message", JsonPrimitive(result.message))
        put("task", buildJsonObject {
            put("id", JsonPrimitive(result.task.id))
            put("title", JsonPrimitive(result.task.title))
            if (result.task.description == null) {
                put("description", JsonNull)
            } else {
                put("description", JsonPrimitive(result.task.description))
            }
            put("type", JsonPrimitive(result.task.type))
            put("status", JsonPrimitive(result.task.status))
            put("routing", JsonPrimitive(result.task.routing))
            put("assigneeIds", buildJsonArray { result.task.assigneeIds.forEach { add(JsonPrimitive(it)) } })
            put("dependencies", buildJsonArray { result.task.dependencies.forEach { add(JsonPrimitive(it)) } })
            put("complexity", JsonPrimitive(result.task.complexity))
            put("risk", JsonPrimitive(result.task.risk))
            put("createdAt", JsonPrimitive(result.task.createdAt))
            if (result.task.updatedAt == null) {
                put("updatedAt", JsonNull)
            } else {
                put("updatedAt", JsonPrimitive(result.task.updatedAt))
            }
            if (result.task.dueAt == null) {
                put("dueAt", JsonNull)
            } else {
                put("dueAt", JsonPrimitive(result.task.dueAt))
            }
            put("metadata", buildJsonObject {
                result.task.metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            })
        })
        put("proposals", buildJsonArray {
            result.proposals.forEach { proposal ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(proposal.id))
                    put("taskId", JsonPrimitive(proposal.taskId))
                    put("agentId", JsonPrimitive(proposal.agentId))
                    put("inputType", JsonPrimitive(proposal.inputType))
                    put("confidence", JsonPrimitive(proposal.confidence))
                    put("tokenUsage", buildJsonObject {
                        proposal.tokenUsage.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                    put("content", anyToJsonElement(proposal.content))
                    put("createdAt", JsonPrimitive(proposal.createdAt))
                    put("metadata", buildJsonObject {
                        proposal.metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                })
            }
        })
        put("context", buildJsonObject {
            put("history", buildJsonArray {
                result.context.history.forEach { message ->
                    add(buildJsonObject {
                        if (message.id == null) {
                            put("id", JsonNull)
                        } else {
                            put("id", JsonPrimitive(message.id))
                        }
                        put("role", JsonPrimitive(message.role))
                        put("content", JsonPrimitive(message.content))
                        if (message.agentId == null) {
                            put("agentId", JsonNull)
                        } else {
                            put("agentId", JsonPrimitive(message.agentId))
                        }
                        put("tokens", JsonPrimitive(message.tokens))
                        put("ts", JsonPrimitive(message.ts))
                        if (message.metadataJson == null) {
                            put("metadataJson", JsonNull)
                        } else {
                            put("metadataJson", JsonPrimitive(message.metadataJson))
                        }
                    })
                }
            })
            put("fileHistory", buildJsonArray {
                result.context.fileHistory.forEach { fileOp ->
                    add(buildJsonObject {
                        put("agentId", JsonPrimitive(fileOp.agentId))
                        put("type", JsonPrimitive(fileOp.type))
                        put("path", JsonPrimitive(fileOp.path))
                        put("ts", JsonPrimitive(fileOp.ts))
                        put("version", JsonPrimitive(fileOp.version))
                        if (fileOp.diff == null) {
                            put("diff", JsonNull)
                        } else {
                            put("diff", JsonPrimitive(fileOp.diff))
                        }
                        if (fileOp.contentHash == null) {
                            put("contentHash", JsonNull)
                        } else {
                            put("contentHash", JsonPrimitive(fileOp.contentHash))
                        }
                        put("conflict", JsonPrimitive(fileOp.conflict))
                        if (fileOp.conflictReason == null) {
                            put("conflictReason", JsonNull)
                        } else {
                            put("conflictReason", JsonPrimitive(fileOp.conflictReason))
                        }
                        put("metadata", buildJsonObject {
                            fileOp.metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                        })
                    })
                }
            })
        })
    }

    private fun queryContextResultToJson(result: QueryContextTool.Result): JsonObject = buildJsonObject {
        put("hits", buildJsonArray {
            result.hits.forEach { hit ->
                add(buildJsonObject {
                    put("chunkId", JsonPrimitive(hit.chunkId))
                    put("score", JsonPrimitive(hit.score))
                    put("filePath", JsonPrimitive(hit.filePath))
                    if (hit.label == null) {
                        put("label", JsonNull)
                    } else {
                        put("label", JsonPrimitive(hit.label))
                    }
                    put("kind", JsonPrimitive(hit.kind))
                    put("text", JsonPrimitive(hit.text))
                    if (hit.language == null) {
                        put("language", JsonNull)
                    } else {
                        put("language", JsonPrimitive(hit.language))
                    }
                    if (hit.startLine == null) {
                        put("startLine", JsonNull)
                    } else {
                        put("startLine", JsonPrimitive(hit.startLine))
                    }
                    if (hit.endLine == null) {
                        put("endLine", JsonNull)
                    } else {
                        put("endLine", JsonPrimitive(hit.endLine))
                    }
                    put("metadata", buildJsonObject {
                        hit.metadata.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                })
            }
        })
        put("metadata", anyToJsonElement(result.metadata))
    }

    private fun getContextStatsResultToJson(result: GetContextStatsTool.Result): JsonObject = buildJsonObject {
        put("providerStatus", buildJsonArray {
            result.providerStatus.forEach { provider ->
                add(buildJsonObject {
                    put("id", JsonPrimitive(provider.id))
                    put("enabled", JsonPrimitive(provider.enabled))
                    put("weight", JsonPrimitive(provider.weight))
                    if (provider.type == null) {
                        put("type", JsonNull)
                    } else {
                        put("type", JsonPrimitive(provider.type))
                    }
                })
            }
        })
        put("storage", buildJsonObject {
            put("files", JsonPrimitive(result.storage.files))
            put("chunks", JsonPrimitive(result.storage.chunks))
            put("embeddings", JsonPrimitive(result.storage.embeddings))
            put("totalSizeBytes", JsonPrimitive(result.storage.totalSizeBytes))
        })
        put("languageDistribution", buildJsonArray {
            result.languageDistribution.forEach { stat ->
                add(buildJsonObject {
                    put("language", JsonPrimitive(stat.language))
                    put("fileCount", JsonPrimitive(stat.fileCount))
                })
            }
        })
        put("recentActivity", buildJsonArray {
            result.recentActivity.forEach { activity ->
                add(buildJsonObject {
                    if (activity.taskId == null) {
                        put("taskId", JsonNull)
                    } else {
                        put("taskId", JsonPrimitive(activity.taskId))
                    }
                    put("snippets", JsonPrimitive(activity.snippets))
                    put("tokens", JsonPrimitive(activity.tokens))
                    put("latencyMs", JsonPrimitive(activity.latencyMs))
                    put("recordedAt", JsonPrimitive(activity.recordedAt.toString()))
                })
            }
        })
        if (result.performance == null) {
            put("performance", JsonNull)
        } else {
            put("performance", buildJsonObject {
                put("totalRecords", JsonPrimitive(result.performance.totalRecords))
                put("totalContextTokens", JsonPrimitive(result.performance.totalContextTokens))
                put("averageLatencyMs", JsonPrimitive(result.performance.averageLatencyMs))
            })
        }
    }

    private fun refreshContextResultToJson(result: RefreshContextTool.Result): JsonObject = buildJsonObject {
        put("mode", JsonPrimitive(result.mode))
        put("status", JsonPrimitive(result.status))
        if (result.jobId == null) {
            put("jobId", JsonNull)
        } else {
            put("jobId", JsonPrimitive(result.jobId))
        }
        if (result.newFiles == null) {
            put("newFiles", JsonNull)
        } else {
            put("newFiles", JsonPrimitive(result.newFiles))
        }
        if (result.modifiedFiles == null) {
            put("modifiedFiles", JsonNull)
        } else {
            put("modifiedFiles", JsonPrimitive(result.modifiedFiles))
        }
        if (result.deletedFiles == null) {
            put("deletedFiles", JsonNull)
        } else {
            put("deletedFiles", JsonPrimitive(result.deletedFiles))
        }
        if (result.unchangedFiles == null) {
            put("unchangedFiles", JsonNull)
        } else {
            put("unchangedFiles", JsonPrimitive(result.unchangedFiles))
        }
        if (result.indexingFailures == null) {
            put("indexingFailures", JsonNull)
        } else {
            put("indexingFailures", JsonPrimitive(result.indexingFailures))
        }
        if (result.deletionFailures == null) {
            put("deletionFailures", JsonNull)
        } else {
            put("deletionFailures", JsonPrimitive(result.deletionFailures))
        }
        if (result.durationMs == null) {
            put("durationMs", JsonNull)
        } else {
            put("durationMs", JsonPrimitive(result.durationMs))
        }
        put("startedAt", JsonPrimitive(result.startedAt.toString()))
        if (result.completedAt == null) {
            put("completedAt", JsonNull)
        } else {
            put("completedAt", JsonPrimitive(result.completedAt.toString()))
        }
        if (result.message == null) {
            put("message", JsonNull)
        } else {
            put("message", JsonPrimitive(result.message))
        }
    }

    private fun mapRebuildContextParams(el: JsonElement): RebuildContextTool.Params {
        val o = el.asObj()
        return RebuildContextTool.Params(
            confirm = o.bool("confirm") ?: false,
            async = o.bool("async") ?: false,
            paths = o.listStr("paths"),
            validateOnly = o.bool("validateOnly") ?: false,
            parallelism = o.int("parallelism")
        )
    }

    private fun rebuildContextResultToJson(result: RebuildContextTool.Result): JsonObject = buildJsonObject {
        put("mode", JsonPrimitive(result.mode))
        put("status", JsonPrimitive(result.status))
        if (result.jobId == null) {
            put("jobId", JsonNull)
        } else {
            put("jobId", JsonPrimitive(result.jobId))
        }
        put("phase", JsonPrimitive(result.phase))
        if (result.totalFiles == null) {
            put("totalFiles", JsonNull)
        } else {
            put("totalFiles", JsonPrimitive(result.totalFiles))
        }
        if (result.processedFiles == null) {
            put("processedFiles", JsonNull)
        } else {
            put("processedFiles", JsonPrimitive(result.processedFiles))
        }
        if (result.successfulFiles == null) {
            put("successfulFiles", JsonNull)
        } else {
            put("successfulFiles", JsonPrimitive(result.successfulFiles))
        }
        if (result.failedFiles == null) {
            put("failedFiles", JsonNull)
        } else {
            put("failedFiles", JsonPrimitive(result.failedFiles))
        }
        if (result.durationMs == null) {
            put("durationMs", JsonNull)
        } else {
            put("durationMs", JsonPrimitive(result.durationMs))
        }
        put("startedAt", JsonPrimitive(result.startedAt.toString()))
        if (result.completedAt == null) {
            put("completedAt", JsonNull)
        } else {
            put("completedAt", JsonPrimitive(result.completedAt.toString()))
        }
        if (result.message == null) {
            put("message", JsonNull)
        } else {
            put("message", JsonPrimitive(result.message))
        }
        if (result.validationErrors == null) {
            put("validationErrors", JsonNull)
        } else {
            put("validationErrors", buildJsonArray {
                result.validationErrors.forEach { add(JsonPrimitive(it)) }
            })
        }
    }

    private fun mapGetRebuildStatusParams(el: JsonElement): GetRebuildStatusTool.Params {
        val o = el.asObj()
        return GetRebuildStatusTool.Params(
            jobId = o.str("jobId") ?: throw IllegalArgumentException("jobId is required"),
            includeLogs = o.bool("includeLogs") ?: false
        )
    }

    private fun getRebuildStatusResultToJson(result: GetRebuildStatusTool.Result): JsonObject = buildJsonObject {
        put("jobId", JsonPrimitive(result.jobId))
        put("status", JsonPrimitive(result.status))
        put("phase", JsonPrimitive(result.phase))

        if (result.progress == null) {
            put("progress", JsonNull)
        } else {
            put("progress", buildJsonObject {
                put("totalFiles", JsonPrimitive(result.progress.totalFiles))
                put("processedFiles", JsonPrimitive(result.progress.processedFiles))
                put("successfulFiles", JsonPrimitive(result.progress.successfulFiles))
                put("failedFiles", JsonPrimitive(result.progress.failedFiles))
                put("percentComplete", JsonPrimitive(result.progress.percentComplete))
            })
        }

        put("timing", buildJsonObject {
            put("startedAt", JsonPrimitive(result.timing.startedAt.toString()))
            if (result.timing.completedAt == null) {
                put("completedAt", JsonNull)
            } else {
                put("completedAt", JsonPrimitive(result.timing.completedAt.toString()))
            }
            if (result.timing.durationMs == null) {
                put("durationMs", JsonNull)
            } else {
                put("durationMs", JsonPrimitive(result.timing.durationMs))
            }
            if (result.timing.estimatedRemainingMs == null) {
                put("estimatedRemainingMs", JsonNull)
            } else {
                put("estimatedRemainingMs", JsonPrimitive(result.timing.estimatedRemainingMs))
            }
        })

        if (result.error == null) {
            put("error", JsonNull)
        } else {
            put("error", JsonPrimitive(result.error))
        }

        if (result.logs == null) {
            put("logs", JsonNull)
        } else {
            put("logs", buildJsonArray {
                result.logs.forEach { log ->
                    add(buildJsonObject {
                        put("timestamp", JsonPrimitive(log.timestamp.toString()))
                        put("level", JsonPrimitive(log.level))
                        put("message", JsonPrimitive(log.message))
                    })
                }
            })
        }
    }

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value.toInt())
        is Byte -> JsonPrimitive(value.toInt())
        is Float -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Map<*, *> -> buildJsonObject {
            value.forEach { (k, v) ->
                val key = k?.toString() ?: return@forEach
                put(key, anyToJsonElement(v))
            }
        }
        is Iterable<*> -> buildJsonArray { value.forEach { add(anyToJsonElement(it)) } }
        is Array<*> -> buildJsonArray { value.forEach { add(anyToJsonElement(it)) } }
        is IntArray -> buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        is LongArray -> buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        is DoubleArray -> buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        is FloatArray -> buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        is BooleanArray -> buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }
        else -> JsonPrimitive(value.toString())
    }
    // endregion

    // region Tools registration and handlers
    private val toolEntries: List<ToolEntry> by lazy {
        listOf(
            ToolEntry(
                name = "create_simple_task",
                description = """
                Create a single-agent task for straightforward implementation work. The orchestrator will still audit
                metadata and can escalate later, but you are signalling that one agent can carry the work end-to-end.

                ## Workflow Context
                You're inside a multi-agent system. Use this when you intend to own execution while keeping the
                orchestrator informed for history, follow-up reviews, or later reassignment if needed.

                ## Use When
                - Routine fixes or scoped features (risk ≤ 6, complexity ≤ 7)
                - User explicitly requests solo handling ("just implement", "skip consensus", "quick fix")
                - Emergency hotfixes where consensus must happen later
                - You need a lightweight tracking ticket for personal work before optionally inviting review

                ## Proactive Triggers
                - User requests action with no mention of other agents or consensus
                - Conversation starts with urgency keywords ("now", "ASAP", "production down")
                - Task mirrors recently completed solo work (pattern recognition)
                - You already checked pending tasks and nothing else is blocking you

                ## Directive Signals to Detect
                - Skip consensus: "solo", "skip review", "no consensus", "just do it"
                - Emergency: "production down", "urgent", "NOW", "hotfix"
                - Delegation: "you handle this", "Codex only", "Claude already did"

                ## Example Scenarios
                1. User: "Fix the validation bug in UserService" → create_simple_task (low risk)
                2. User: "Refactor auth module NOW - prod is down" → create_simple_task with skipConsensus=true, isEmergency=true
                3. User: "Add unit tests for UserController" → create_simple_task (routine work)

                ## Decision Flow
                ```
                User request
                  ├─ Mentions another agent's input/review? → create_consensus_task
                  ├─ Explicitly assigns to someone else? → assign_task
                  ├─ Contains emergency/skip-review keywords? → create_simple_task (set skipConsensus/isEmergency)
                  └─ Risk or complexity ≥ 7? → ask user about consensus; if declined → create_simple_task
                ```

                ## Key Parameters
                - skipConsensus: True when user bypasses multi-agent review
                - immediate / isEmergency: Flag production-impacting work for audit trail
                - assignToAgent: Optional override when user names the implementer
                - complexity & risk (1-10): Feed orchestrator heuristics (defaults: 5/5)
                - directives.originalText: Preserve the exact user wording for other agents
            """.trimIndent(),
            jsonSchema = CreateSimpleTaskTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "create_consensus_task",
            description = """
                Create a multi-agent consensus task requiring input from multiple AI agents. This formalizes a
                collaboration loop so independent proposals can be compared, merged, or voted on before work proceeds.

                ## Workflow Context
                You're escalating to full multi-agent problem solving. Expect to gather proposals from at least two
                agents, reconcile differences, and only then move to implementation or completion.

                ## Use When
                - Critical system decisions (auth, payments, data migration, security controls)
                - High-risk or high-uncertainty work (risk ≥ 7, complexity ≥ 8)
                - User explicitly requests another agent's input or a consensus review
                - Architecture or refactoring decisions spanning multiple services/modules
                - You want an intentional second opinion before implementing

                ## Proactive Triggers
                - Keywords: "critical", "important", "design", "strategy", "trade-offs"
                - User mentions multiple stakeholders or agents in prior turns
                - Task domain touches compliance, safety, or irreversible migrations
                - You already produced an architectural plan and need validation before coding

                ## Directive Signals to Detect
                - Force consensus: "get Codex to review", "need consensus", "check with Claude"
                - Shared ownership: "both agents", "compare approaches", "validate with other agent"
                - Escalation: "high stakes", "can't get this wrong", "double-check"

                ## Example Scenarios
                1. User: "Design OAuth2 authentication system (critical)" → create_consensus_task + note auth keyword
                2. User: "Refactor payment processing—get Codex to review the approach" → forceConsensus=true
                3. User: "Multi-region database migration plan" → create_consensus_task (complex + irreversible)

                ## Decision Flow
                ```
                User request
                  ├─ Mentions solo/skip consensus/emergency? → create_simple_task
                  ├─ Directly assigns to a specific agent? → assign_task
                  ├─ Contains consensus/critical/other-agent keywords? → create_consensus_task (forceConsensus)
                  └─ Risk or complexity ≥ 7? → ask user; if yes → create_consensus_task else document solo decision
                ```

                ## Collaboration Workflow
                1. Create task → Tell user which agent should respond next
                2. First agent submits analysis via submit_input()
                3. User switches agents → other agent calls get_pending_tasks()
                4. Second agent submits complementary or alternative proposal
                5. Original agent calls continue_task() to compare inputs and decide path forward

                ## Key Parameters
                - forceConsensus: Preserve user directive for audit; orchestrator enforces multi-agent path
                - preventConsensus: Respect explicit downgrades (typically paired with emergency flag)
                - complexity & risk (1-10): Feed prioritization and routing heuristics
                - directives.originalText: Store user wording for downstream agents to reference
            """.trimIndent(),
            jsonSchema = CreateConsensusTaskTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "assign_task",
            description = """
                Directly assign a task to a specific agent, bypassing automatic routing. Use this when the user (or you)
                already knows which specialist should own the next action.

                ## Workflow Context
                In the orchestrator, this is how you intentionally hand work to another agent. It creates clarity in
                the task queue about who should respond next and why.

                ## Use When
                - User explicitly names the agent: "Have Codex review", "Claude should implement"
                - You need a targeted follow-up (e.g., request a code review after implementation)
                - Coordinated handoffs: You finish architecture, want Codex to code, then plan to resume later
                - Creating TODOs for yourself or another agent with clear ownership

                ## Proactive Triggers
                - Hearing agent names or roles in user instructions
                - Detecting that consensus isn't required but specialized expertise is
                - Completing a task where a subsequent review/implementation step is standard practice

                ## Directive Signals to Detect
                - Agent call-outs: "ask Codex", "Codex, ...", "Claude should"
                - Ownership phrases: "let X handle", "assign to", "pass to"
                - Sequencing: "after you finish, have Codex..."

                ## Example Scenarios
                1. User: "Claude, design the API. Then have Codex implement it." → assign_task(targetAgent="codex-cli")
                2. After finishing feature → assign_task(..., roleInWorkflow="REVIEW") for code review
                3. User: "Ask Codex what they think about the schema" → assign_task to codex-cli for analysis

                ## Decision Flow
                ```
                Need to route work?
                  ├─ User demands multi-agent consensus? → create_consensus_task
                  ├─ User says keep it solo? → create_simple_task (assignToAgent optional)
                  └─ Specific agent named or specialized follow-up needed? → assign_task
                ```

                ## Key Parameters
                - targetAgent: Required; resolves aliases like "codex"/"claude"
                - title & description: Supply enough context so the assignee does not need to re-ask the user
                - roleInWorkflow: Clarify intent (EXECUTION, REVIEW, FOLLOW_UP)
                - directives.originalText: Preserve the handoff wording for traceability
                - complexity & risk: Give the assignee expectations about scope
            """.trimIndent(),
            jsonSchema = AssignTaskTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "continue_task",
            description = """
                Resume work on a previously created task by retrieving full context: task metadata, every agent proposal,
                conversation history, and file operations. Think of this as loading a save-game checkpoint for the
                orchestrator so you can continue seamlessly.

                ## Workflow Context
                Use this whenever you rejoin a task that you or another agent touched earlier. It prevents stale context
                and ensures decisions are based on the latest shared state.

                ## Use When
                - get_pending_tasks() reveals work assigned to you
                - Another agent just submitted input and you need to review it
                - Resuming paused/long-running efforts after conversation gaps
                - Preparing to implement or finalize a consensus task

                ## Proactive Triggers
                - User references a specific task ID or past work
                - You finished a plan earlier and need to inspect follow-up proposals
                - User asks "what did the other agent say" or similar

                ## Directive Signals to Detect
                - Context requests: "show task 42", "load the previous plan", "what's in that task"
                - Resume cues: "continue X", "pick up Y", "where did we leave off"
                - Cross-agent curiosity: "what did Codex/Claude propose", "compare the plans"

                ## Example Scenarios
                1. get_pending_tasks() lists task-123 → call continue_task("task-123") before working
                2. User: "What did Codex propose for the auth system?" → load task, summarize proposal
                3. After submitting plan, return later → continue_task() to check peer feedback before coding

                ## Decision Flow
                ```
                Need more context?
                  ├─ Only status snapshot required? → get_task_status
                  ├─ Want to inspect all proposals/history? → continue_task
                  └─ No existing task yet? → create_simple_task / create_consensus_task / assign_task
                ```

                ## What You Receive
                - task: Title, description, routing, risk/complexity, assignees, timestamps
                - proposals: Each agent submission with type, content, confidence, token usage
                - context.history: Conversation snippets between agents for this task
                - context.fileHistory: File reads/writes and version notes
                - Optional maxTokens knob to truncate massive histories

                ## Typical Workflow
                1. Discover task via get_pending_tasks() or user directive
                2. Load full context with continue_task()
                3. Summarize peer input to the user and decide on next action
                4. Submit your contribution with submit_input() or implement changes

                ## Parameters
                - taskId: Required identifier to load
                - maxTokens: Optional cap for extremely large histories
            """.trimIndent(),
            jsonSchema = ContinueTaskTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "complete_task",
            description = """
                Mark a task as completed and log the final decision record. This is the orchestrator's audit checkpoint
                capturing outcomes, rationale, and artifacts once all required inputs have been processed.

                ## Workflow Context
                Only the agent that created the task (or was delegated completion) should call this. It closes the loop
                in the shared task registry so other agents know no further action is required.

                ## Use When
                - Implementation, testing, and reviews are finished
                - Consensus tasks have agreed on a proposal and follow-up steps are complete
                - You produced the final result summary and want to archive the decision

                ## Proactive Triggers
                - All required proposals are in and no additional work remains
                - User asks for final status, delivery, or wrap-up
                - You handed off to another agent, received feedback, and applied changes

                ## Directive Signals to Detect
                - Completion cues: "ship it", "we're done", "close the loop"
                - Audit requests: "document the decision", "log the outcome"
                - Ownership check: ensure you created the task; otherwise wait for primary agent

                ## Example Scenarios
                1. After implementing bug fix + running tests → complete_task with result summary and test evidence
                2. Consensus on architecture reached → document chosen plan, note proposals considered, close task
                3. Emergency hotfix finished → complete task, then optionally create follow-up review assignment

                ## Decision Flow
                ```
                Ready to close?
                  ├─ Still awaiting other agent input? → wait / remind via submit_input request
                  ├─ Need additional verification? → create follow-up assign_task or run tests first
                  └─ All work approved and delivered? → complete_task
                ```

                ## Key Fields to Populate
                - resultSummary: Concise narrative of what changed and current state
                - decision: Document proposals considered, winner(s), agreement rate, and rationale
                - artifacts: Attach paths or IDs for produced code/docs (if supported)
                - tokenMetrics: Capture resource usage for analytics (optional but useful)
                - snapshots: Provide before/after context for audits when significant
            """.trimIndent(),
            jsonSchema = CompleteTaskTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "get_pending_tasks",
            description = """
                List all pending tasks assigned to a specific agent, filtered by status and limited by count. This is
                how agents discover work waiting for them - essential for the agent handoff workflow.

                ## Workflow Context
                You are part of a multi-agent system. This tool is how you check for work that other agents have
                created for you, or tasks that need your input. Think of this as your "inbox" for collaborative work.

                ## When to Use
                - At the start of a conversation (proactively check for pending work)
                - After user mentions switching from another agent ("I just talked to Codex...")
                - When user asks "what's pending", "any tasks for me", "check my queue"
                - Before creating new consensus tasks (check if similar work already exists)
                - Periodically during long conversations to catch new assignments

                ## Proactive Triggers
                - User says "I just worked with Codex" or mentions another agent
                - Conversation starts and you haven't checked pending tasks yet
                - User asks about work status or what needs to be done
                - User switches context topics (might be resuming previous work)

                ## User Phrases to Detect
                - Queue checking: "what's pending", "any tasks", "check my work", "what's waiting"
                - Agent handoff: "I talked to Codex", "switching from Claude", "Codex said to check"
                - Status inquiry: "where are we", "what's next", "what should I work on"

                ## Example Scenarios
                1. Session start → Proactively call get_pending_tasks() to check for work
                2. User: "I just talked to Codex about the auth system" → Call get_pending_tasks() to see if Codex created a task
                3. User: "What's on my plate?" → Call get_pending_tasks() and report results
                4. Before creating consensus task → Check if similar task already exists

                ## Decision Flow
                ```
                Start of session or user mentions past tasks?
                  ├─ Need to know if work exists? → get_pending_tasks
                  ├─ Specific task ID already provided? → continue_task directly
                  └─ No handoff context and brand-new work? → create_simple_task / create_consensus_task / assign_task as needed
                ```

                ## Expected Workflow
                1. Call get_pending_tasks() to see your queue
                2. If tasks found → Present summary, confirm prioritization with user
                3. User selects task → Call continue_task(taskId) for full context
                4. Analyze task, submit your input via submit_input()
                5. Follow up until task is completed or reassigned

                ## Parameters
                - agentId: Usually omit (defaults to you), or specify another agent to check their queue
                - statuses: Default is ["PENDING"], can add "IN_PROGRESS", "WAITING_INPUT"
                - limit: Default unlimited, use 10-20 for large queues

                ## Returns
                - tasks: Array of task summaries (id, title, status, type, priority, createdAt, dueAt, contextPreview)
                - count: Total number of matching tasks
                - agentId: Confirmed agent whose queue was checked
            """.trimIndent(),
            jsonSchema = GetPendingTasksTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "get_task_status",
            description = """
                Fetch the current status and core metadata for a task without loading full history. Think of this as a
                lightweight heartbeat check.

                ## Workflow Context
                Use this when you just need a quick progress snapshot or to confirm routing before taking action.

                ## Use When
                - User asks "what's the status of task-XYZ?"
                - You want to ensure another agent has responded before calling continue_task()
                - Monitoring long-running work for state changes

                ## Proactive Triggers
                - Task nearing due date or long since last update
                - Before notifying user about delays or waiting on other agents

                ## Directive Signals to Detect
                - Status queries: "is task 12 done?", "what's happening with..."
                - Progress checks: "has Codex responded yet?"

                ## Example Scenarios
                1. User: "Did Codex finish the review?" → get_task_status("task-id")
                2. Monitoring consensus task before proceeding → check if status moved to WAITING_INPUT or COMPLETED

                ## Decision Flow
                ```
                Need information depth?
                  ├─ Full proposals/history required → continue_task
                  ├─ Only want updates/assignees → get_task_status
                  └─ No task exists yet → create_simple_task / create_consensus_task / assign_task
                ```

                ## Returns
                - status: PENDING | IN_PROGRESS | WAITING_INPUT | COMPLETED | FAILED
                - routing, type, assignees, timestamps
                - metadata: task-specific attributes (risk, complexity, roleInWorkflow, etc.)
            """.trimIndent(),
            jsonSchema = GetTaskStatusTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "submit_input",
            description = """
                Submit an agent's contribution (proposal) to a task - this is how agents participate in consensus workflows
                or handoff work to each other. Each submission includes the agent's analysis, plan, implementation, or review
                along with confidence score and token usage.

                ## Agent Context
                This is THE primary way agents communicate their work to each other. When you analyze a task and develop
                a solution, plan, or review, you submit it via this tool so the other agent (and the orchestrator) can
                see your contribution. Think of this as "publishing" your work to a shared workspace.

                ## When to Use
                - After analyzing a consensus task and developing your proposal
                - After reviewing another agent's work (code review, architecture review)
                - After completing research or analysis that others need
                - When you have a plan/design ready for collaborative decision-making
                - Anytime you have input to contribute to a multi-agent task

                ## Proactive Triggers
                - You've analyzed a task from get_pending_tasks() and have a solution ready
                - User asks you to "submit your analysis", "share your plan", "send to Codex"
                - You've completed your part of a consensus task
                - You've reviewed another agent's proposal and have feedback

                ## Directive Signals to Detect
                - Submission: "submit your plan", "share with Codex", "send your proposal"
                - Completion: "done with analysis", "finished reviewing", "ready to submit"
                - Handoff: "pass to Codex", "let the other agent see this"

                ## Input Types and When to Use Each
                - ARCHITECTURAL_PLAN: High-level system design, data models, component architecture, API contracts
                - IMPLEMENTATION_PLAN: Step-by-step implementation approach, task breakdown, technical approach
                - CODE_REVIEW: Feedback on another agent's code/implementation, suggestions for improvement
                - TEST_PLAN: Testing strategy, test cases, QA approach, coverage plan
                - REFACTORING_SUGGESTION: Code improvement recommendations, design pattern suggestions
                - RESEARCH_SUMMARY: Investigation findings, technology comparisons, feasibility analysis
                - OTHER: Custom input types (always include description in metadata)

                ## Example Scenarios
                1. Consensus task for auth system → Analyze, design OAuth2 approach → submit_input with ARCHITECTURAL_PLAN
                2. Review Codex's implementation → Analyze code → submit_input with CODE_REVIEW
                3. Research task on database choice → Compare options → submit_input with RESEARCH_SUMMARY
                4. Implementation task assigned to you → Create implementation plan → submit_input with IMPLEMENTATION_PLAN

                ## Content Structure Recommendations
                - Use structured JSON for complex proposals: {"approach": "...", "components": [...], "tradeoffs": {...}}
                - Include rationale: Explain WHY you chose this approach
                - Note assumptions: What you're assuming about requirements/constraints
                - Highlight risks: What could go wrong, what needs validation
                - Suggest alternatives: Other approaches you considered and why rejected

                ## Confidence Scoring Guidelines
                - 0.9-1.0: Very confident, well-researched, proven approach
                - 0.7-0.9: Confident, good solution, minor uncertainties
                - 0.5-0.7: Moderate confidence, needs discussion, trade-offs unclear
                - 0.3-0.5: Low confidence, exploratory, needs validation
                - 0.0-0.3: Very uncertain, multiple unknowns, need other agent's input

                ## Decision Flow
                ```
                Want to contribute?
                  ├─ Need to log task first? → create_simple_task / create_consensus_task / assign_task
                  ├─ Reviewing another agent's work? → submit_input (inputType=CODE_REVIEW, etc.)
                  └─ Finished contribution and ready to hand off? → submit_input, then await follow-ups
                ```

                ## After Submission
                - Orchestrator records your proposal with timestamp
                - Other agents can see it via continue_task()
                - For consensus tasks: wait for other agent's proposal, then user decides or orchestrator merges
                - Task status may change to WAITING_INPUT (waiting for other agent) or COMPLETED (if all input collected)

                ## Parameters
                - taskId: Required - which task you're contributing to
                - content: Required - your proposal/analysis/review (can be string or structured JSON)
                - inputType: Recommended - helps orchestrator categorize (default: OTHER)
                - confidence: Recommended - your confidence in this proposal (0.0-1.0, default: 0.7)
                - agentId: Usually omit (defaults to you)
                - metadata: Optional - additional context (dependencies, assumptions, etc.)
            """.trimIndent(),
            jsonSchema = SubmitInputTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "respond_to_task",
            description = """
                Unified tool that loads task context and submits your response in a single operation. This is the
                RECOMMENDED tool for simple workflows where you want to respond to a task immediately after reviewing it.

                ## Workflow Context
                This tool combines continue_task + submit_input into one streamlined operation:
                1. Loads full task context (task metadata, proposals, conversation history, file operations)
                2. Submits your response/proposal
                3. Returns complete updated state including your submission

                ## When to Use (RECOMMENDED)
                - **Default choice** for most task responses
                - When you want to review a task and respond immediately
                - After get_pending_tasks() shows work assigned to you
                - When you have a ready solution/plan/review to submit
                - For straightforward workflows without complex analysis needs

                ## When NOT to Use
                - When you need to analyze task context before deciding whether to respond
                - When you want to review proposals from multiple tasks before choosing which to respond to
                - When response requires extensive research or analysis before submission
                - In these cases, use continue_task (analyze), then decide, then submit_input separately

                ## Comparison with Other Tools
                - **respond_to_task**: One call - load context + submit response (SIMPLER, RECOMMENDED)
                - **continue_task + submit_input**: Two calls - load context, analyze, then submit (more control)
                - Use respond_to_task unless you need the flexibility of separate analysis step

                ## Example Scenarios
                1. Pending task about auth system → respond_to_task with architectural plan (one call)
                2. Code review requested → respond_to_task with review feedback (one call)
                3. Research task on database → respond_to_task with research summary (one call)

                ## Input Types (same as submit_input)
                - ARCHITECTURAL_PLAN: System design, data models, architecture, API contracts
                - IMPLEMENTATION_PLAN: Step-by-step implementation approach, task breakdown
                - CODE_REVIEW: Code feedback and improvement suggestions
                - TEST_PLAN: Testing strategy, test cases, coverage plan
                - REFACTORING_SUGGESTION: Code improvement recommendations
                - RESEARCH_SUMMARY: Investigation findings, technology comparisons
                - OTHER: Custom types (include description in metadata)

                ## Response Structure
                - content: Your proposal/analysis/review (can be string or structured JSON)
                - inputType: Type of input (see above, default: OTHER)
                - confidence: Your confidence 0.0-1.0 (default: 0.5)
                - metadata: Optional additional context

                ## What You Receive Back
                - taskId, proposalId, inputType, taskStatus: Submission confirmation
                - task: Full task metadata (updated with your submission)
                - proposals: All proposals including yours
                - context: Conversation history and file operations
                - message: Human-readable status message

                ## Parameters
                - taskId: Required - which task to respond to
                - response: Required - your response (content, inputType, confidence, metadata)
                - agentId: Optional - defaults to you
                - maxTokens: Optional - limit for context retrieval (default: 6000)

                ## Typical Workflow
                1. get_pending_tasks() → see task-123 waiting
                2. respond_to_task(task-123, {your analysis}) → loads context + submits + returns complete state
                3. Done! Task updated, other agents can see your response
            """.trimIndent(),
            jsonSchema = RespondToTaskTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "query_context",
            description = """
                Explicit context query tool for agents to retrieve relevant code snippets
                based on a natural language query with optional filters and scoping.

                ## Use When
                - Need to find relevant code snippets for a task or question
                - Want to understand implementation details before making changes
                - Need to gather context about specific files, languages, or code types
                - Building context for multi-step tasks or consensus proposals

                ## Parameters
                - query (required): Natural language query describing what code/context you need
                - k (optional): Maximum number of results to return (default: 10)
                - maxTokens (optional): Token budget for results (default: 4000)
                - paths (optional): Filter to specific file paths (e.g., ["src/main/kotlin/"])
                - languages (optional): Filter to specific languages (e.g., ["kotlin", "java"])
                - kinds (optional): Filter to specific chunk types (e.g., ["CODE_CLASS", "CODE_METHOD"])
                - excludePatterns (optional): Exclude files matching patterns (e.g., ["test/", "*.md"])
                - providers (optional): Use specific providers (e.g., ["semantic", "symbol"])

                ## Example Queries
                1. "Find authentication implementation"
                2. "Show me database connection handling"
                3. "Classes implementing ContextProvider interface"
                4. "Error handling in task processing"

                ## Returns
                - hits: List of code snippets with score, file path, text, metadata
                - metadata: Query statistics (total hits, tokens used, provider stats)
            """.trimIndent(),
            jsonSchema = QueryContextTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "get_context_stats",
            description = """
                Return comprehensive statistics about the context system including provider status,
                storage metrics, performance data, language distribution, and recent activity.

                ## Use When
                - Need to understand the current state of the context system
                - Debugging context retrieval issues
                - Monitoring storage usage and performance
                - Checking which providers are enabled and configured
                - Analyzing recent context query patterns

                ## Parameters
                - recentLimit (optional): Number of recent activity entries to return (default: 10)

                ## Returns
                - providerStatus: List of all configured providers with enabled status, weight, and type
                - storage: Storage statistics (file count, chunk count, embeddings, total size)
                - languageDistribution: Breakdown of files by programming language
                - recentActivity: Recent context queries with task ID, snippets returned, tokens used, latency
                - performance: Aggregate performance metrics (total records, total tokens, average latency)

                ## Example Use Cases
                1. Check which providers are currently enabled
                2. Monitor storage growth over time
                3. Identify performance bottlenecks
                4. Understand which languages dominate the codebase
                5. Debug recent context retrieval issues
            """.trimIndent(),
            jsonSchema = GetContextStatsTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "refresh_context",
            description = """
                Manually trigger re-indexing of files to update the context database. Supports both
                synchronous (blocking) and asynchronous (background) execution modes.

                ## Use When
                - Files have been modified outside the watcher's scope
                - Need to force re-indexing of specific paths
                - Want to manually refresh context after bulk file operations
                - Testing or debugging indexing pipeline
                - After restoring from backup or changing configuration

                ## Parameters
                - paths (optional): List of file/directory paths to refresh. If null, uses config watch paths.
                - force (optional): Force re-indexing even if files haven't changed (default: false)
                - async (optional): Run in background and return jobId immediately (default: false)
                - parallelism (optional): Number of parallel workers for indexing

                ## Execution Modes

                ### Synchronous (async=false)
                - Blocks until indexing completes
                - Returns immediate results with file counts and duration
                - Use for small refreshes or when you need to wait for completion

                ### Asynchronous (async=true)
                - Returns jobId immediately
                - Indexing runs in background
                - Use getJobStatus() to check progress
                - Use for large refreshes or when you don't want to block

                ## Returns
                - mode: "sync" or "async"
                - status: "completed", "completed_with_errors", "running", "failed", or "error"
                - jobId: Job identifier for async mode (null for sync)
                - newFiles: Number of new files indexed
                - modifiedFiles: Number of modified files re-indexed
                - deletedFiles: Number of deleted files removed
                - unchangedFiles: Number of unchanged files skipped
                - indexingFailures: Number of files that failed to index
                - deletionFailures: Number of deletions that failed
                - durationMs: Time taken in milliseconds (null for async until complete)
                - message: Human-readable status message

                ## Example Use Cases
                1. Refresh specific directory: `paths: ["/src/components"]`
                2. Force complete re-index: `paths: null, force: true`
                3. Background refresh: `async: true` then poll with getJobStatus()
                4. Fast parallel refresh: `parallelism: 8`
            """.trimIndent(),
            jsonSchema = RefreshContextTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "rebuild_context",
            description = """
                Perform a complete rebuild of the context database. This is a DESTRUCTIVE operation that
                clears all existing context data and re-indexes from scratch.

                ## SAFETY CHECKS
                - Requires explicit `confirm: true` to execute (safety check against accidental deletion)
                - Blocks if another rebuild is already in progress
                - Validates all paths before starting destructive operations
                - Supports dry-run mode (`validateOnly: true`) to test before executing

                ## Use When
                - Context database is corrupted or inconsistent
                - Major configuration changes (new embedding model, chunking strategy)
                - After significant codebase restructuring
                - Migrating to new database version
                - Starting fresh with clean state

                ## Parameters
                - confirm (REQUIRED): Must be `true` to execute. Safety check to prevent accidental data loss.
                - async (optional): Run in background and return jobId immediately (default: false)
                - paths (optional): Paths to rebuild. If null, rebuilds all watched paths.
                - validateOnly (optional): Dry-run mode. Validates without executing (default: false)
                - parallelism (optional): Number of parallel workers for indexing

                ## Process Flow
                1. **Validation Phase**: Verify confirm=true, check for in-progress rebuild, validate paths
                2. **Pre-rebuild Phase**: Create job record, acquire rebuild lock
                3. **Destructive Phase**: Clear all context data (chunks, embeddings, file_state, etc.)
                4. **Rebuild Phase**: Run full bootstrap indexing from scratch
                5. **Post-rebuild Phase**: Optimize database (VACUUM, ANALYZE), release lock

                ## Async Mode
                - Returns immediately with jobId
                - Poll status with `getJobStatus(jobId)`
                - Job status tracks current phase: validation, pre-rebuild, destructive, rebuild, post-rebuild
                - Returns progress counts: totalFiles, processedFiles, successfulFiles, failedFiles

                ## Response Format
                - mode: "sync" or "async"
                - status: "validated", "completed", "completed_with_errors", "running", "failed", "error"
                - jobId: Job identifier for async mode (null for sync)
                - phase: Current phase (validation, pre-rebuild, destructive, rebuild, post-rebuild, completed)
                - totalFiles: Total number of files to index
                - processedFiles: Number of files processed so far
                - successfulFiles: Number of files successfully indexed
                - failedFiles: Number of files that failed
                - durationMs: Time taken in milliseconds
                - message: Human-readable status message
                - validationErrors: List of validation errors (if any)

                ## Example Use Cases
                1. Dry-run validation: `validateOnly: true` (checks paths without executing)
                2. Full rebuild with confirmation: `confirm: true, paths: null` (rebuilds everything)
                3. Partial rebuild: `confirm: true, paths: ["/src"]` (rebuilds only /src)
                4. Background rebuild: `confirm: true, async: true` (non-blocking execution)
                5. Fast rebuild: `confirm: true, parallelism: 8` (use 8 workers)

                ## WARNING
                This operation deletes ALL existing context data. Always use `validateOnly: true` first
                to verify paths and configuration before executing. Ensure you have backups if needed.
            """.trimIndent(),
            jsonSchema = RebuildContextTool.JSON_SCHEMA
        ),
        ToolEntry(
            name = "get_rebuild_status",
            description = """
                Check the status of a background rebuild job started by rebuild_context.

                This tool provides real-time progress updates for async rebuild operations, including:
                - Current execution phase and status
                - File processing progress (total, processed, successful, failed)
                - Timing information (started, duration, estimated remaining time)
                - Optional execution logs for debugging

                ## When to Use
                - After starting an async rebuild (`rebuild_context` with `async: true`)
                - To monitor long-running rebuild operations
                - To check if a rebuild has completed before performing other operations
                - To troubleshoot rebuild failures with detailed logs

                ## Parameters
                - **jobId** (required): Job ID returned from `rebuild_context` when `async: true`
                - **includeLogs** (optional, default: false): Include execution logs in response

                ## Response Fields
                - **jobId**: The job identifier
                - **status**: Current status (running, completed, completed_with_errors, failed, not_found)
                - **phase**: Current phase (pre-rebuild, destructive, rebuild, post-rebuild, completed)
                - **progress**: File processing progress (null if not started)
                  - totalFiles: Total files to index
                  - processedFiles: Files processed so far
                  - successfulFiles: Files successfully indexed
                  - failedFiles: Files that failed
                  - percentComplete: Progress percentage (0-100)
                - **timing**: Timing information
                  - startedAt: When the job started
                  - completedAt: When the job finished (null if still running)
                  - durationMs: Time elapsed in milliseconds
                  - estimatedRemainingMs: Estimated time remaining (null if complete or can't estimate)
                - **error**: Error message if status is 'failed' (null otherwise)
                - **logs**: Execution logs if includeLogs is true (null otherwise)

                ## Example Use Cases
                1. Poll for completion: Call periodically until status is 'completed' or 'failed'
                2. Show progress: Display percentComplete and estimatedRemainingMs to user
                3. Debug failures: Use `includeLogs: true` to see what went wrong
                4. Check before operations: Ensure no rebuild is running before performing other tasks

                ## Status Values
                - **running**: Rebuild is in progress
                - **completed**: Rebuild finished successfully
                - **completed_with_errors**: Rebuild finished but some files failed
                - **failed**: Rebuild encountered a fatal error
                - **not_found**: Job ID doesn't exist (may have been cleaned up)

                ## Notes
                - Job statuses are kept in memory and will be lost on server restart
                - Completed jobs may be periodically cleaned up to save memory
                - For sync rebuilds, use the immediate response from rebuild_context instead
            """.trimIndent(),
            jsonSchema = GetRebuildStatusTool.JSON_SCHEMA
        )
        )
    }

    private fun tools(): List<ToolEntry> = toolEntries
    // endregion

    // region models
    @Serializable
    data class ToolCallRequest(
        val name: String,
        val params: kotlinx.serialization.json.JsonElement
    )

    @Serializable
    data class ErrorBody(
        val ok: Boolean = false,
        val error: ErrorDetail
    )

    @Serializable
    data class ErrorDetail(
        val code: String,
        val message: String
    )

    private fun errorBody(code: String, message: String): ErrorBody {
        return ErrorBody(false, ErrorDetail(code, message))
    }

    // ---- JSON mapping helpers ----
    private fun mapCreateSimpleParams(el: JsonElement): CreateSimpleTaskTool.Params {
        val o = el.asObj()
        val directives = o.obj("directives")?.let { d ->
            CreateSimpleTaskTool.Params.Directives(
                skipConsensus = d.bool("skipConsensus"),
                assignToAgent = normalizeAgentId(d.str("assignToAgent")),
                immediate = d.bool("immediate"),
                isEmergency = d.bool("isEmergency"),
                notes = d.str("notes"),
                originalText = d.str("originalText")
            )
        }
        return CreateSimpleTaskTool.Params(
            title = o.reqStr("title"),
            description = o.str("description"),
            type = o.str("type"),
            complexity = o.int("complexity"),
            risk = o.int("risk"),
            assigneeIds = normalizeAgentIds(o.listStr("assigneeIds")),
            dependencyIds = o.listStr("dependencyIds"),
            dueAt = o.str("dueAt"),
            metadata = o.mapStr("metadata"),
            directives = directives
        )
    }

    private fun mapCreateConsensusParams(el: JsonElement): CreateConsensusTaskTool.Params {
        val o = el.asObj()
        val directives = o.obj("directives")?.let { d ->
            CreateConsensusTaskTool.Params.Directives(
                forceConsensus = d.bool("forceConsensus"),
                preventConsensus = d.bool("preventConsensus"),
                assignToAgent = normalizeAgentId(d.str("assignToAgent")),
                isEmergency = d.bool("isEmergency"),
                notes = d.str("notes"),
                originalText = d.str("originalText")
            )
        }
        return CreateConsensusTaskTool.Params(
            title = o.reqStr("title"),
            description = o.str("description"),
            type = o.str("type"),
            complexity = o.int("complexity"),
            risk = o.int("risk"),
            assigneeIds = normalizeAgentIds(o.listStr("assigneeIds")),
            dependencyIds = o.listStr("dependencyIds"),
            dueAt = o.str("dueAt"),
            metadata = o.mapStr("metadata"),
            directives = directives
        )
    }

    private fun mapAssignTaskParams(el: JsonElement): AssignTaskTool.Params {
        val o = el.asObj()
        val directives = o.obj("directives")?.let { d ->
            AssignTaskTool.Params.Directives(
                immediate = d.bool("immediate"),
                isEmergency = d.bool("isEmergency"),
                notes = d.str("notes"),
                originalText = d.str("originalText")
            )
        }
        val targetAgent = resolveAgentIdOrDefault(o.str("targetAgent"))
        return AssignTaskTool.Params(
            title = o.reqStr("title"),
            targetAgent = targetAgent,
            description = o.str("description"),
            type = o.str("type"),
            complexity = o.int("complexity"),
            risk = o.int("risk"),
            dependencyIds = o.listStr("dependencyIds"),
            dueAt = o.str("dueAt"),
            metadata = o.mapStr("metadata"),
            directives = directives
        )
    }

    private fun mapContinueTaskParams(el: JsonElement): ContinueTaskTool.Params {
        val o = el.asObj()
        return ContinueTaskTool.Params(
            taskId = o.reqStr("taskId"),
            maxTokens = o.int("maxTokens")
        )
    }

    private fun mapCompleteTaskParams(el: JsonElement): CompleteTaskTool.Params {
        val o = el.asObj()
        val decision = o.obj("decision")?.let { d ->
            val considered = d.array("considered")?.map { cEl ->
                val c = cEl.asObj()
                CompleteTaskTool.Params.DecisionDTO.ConsideredDTO(
                    proposalId = c.reqStr("proposalId"),
                    agentId = c.reqStr("agentId"),
                    inputType = c.reqStr("inputType"),
                    confidence = c.reqDouble("confidence"),
                    tokenUsage = c.obj("tokenUsage")?.let { tu ->
                        CompleteTaskTool.Params.TokenMetricsDTO(
                            inputTokens = tu.int("inputTokens"),
                            outputTokens = tu.int("outputTokens")
                        )
                    } ?: CompleteTaskTool.Params.TokenMetricsDTO()
                )
            } ?: emptyList()
            CompleteTaskTool.Params.DecisionDTO(
                considered = considered,
                selected = d.listStr("selected"),
                winnerProposalId = d.str("winnerProposalId"),
                agreementRate = d.double("agreementRate"),
                rationale = d.str("rationale"),
                metadata = d.mapStr("metadata") ?: emptyMap()
            )
        }
        val tokenMetrics = o.obj("tokenMetrics")?.let { tm ->
            CompleteTaskTool.Params.TokenMetricsDTO(
                inputTokens = tm.int("inputTokens"),
                outputTokens = tm.int("outputTokens")
            )
        }
        val artifacts = o.obj("artifacts")?.let { mapJsonToAny(it) }
        val snapshots = o.array("snapshots")?.map { sEl ->
            val s = sEl.asObj()
            CompleteTaskTool.Params.SnapshotDTO(
                label = s.str("label"),
                payload = s.any("payload")
            )
        }
        return CompleteTaskTool.Params(
            taskId = o.reqStr("taskId"),
            resultSummary = o.str("resultSummary"),
            decision = decision,
            tokenMetrics = tokenMetrics,
            artifacts = artifacts,
            snapshots = snapshots
        )
    }

    private fun mapGetPendingTasksParams(el: JsonElement): Pair<GetPendingTasksTool.Params, String> {
        val o = el.asObj()
        val params = GetPendingTasksTool.Params(
            agentId = o.str("agentId"),
            statuses = o.listStr("statuses"),
            limit = o.int("limit")
        )
        val resolvedId = resolveAgentIdOrDefault(params.agentId)
        return params to resolvedId
    }

    private fun resolveAgentIdOrDefault(requestedRaw: String?): String {
        val agents = agentRegistry.getAllAgents()
        require(agents.isNotEmpty()) { "No agents are registered; cannot resolve agentId" }

        // Helper to produce a friendly list for error messages
        fun availableAgents(): String = agents.joinToString(", ") { "${it.id.value} (${it.displayName})" }

        val trimmed = requestedRaw?.trim()?.takeIf { it.isNotEmpty() }
        val normalized = trimmed?.lowercase()
        // Treat common aliases as the active single agent when unambiguous
        val wantsDefault = trimmed == null || normalized == "user" || normalized == "me"
        if (wantsDefault) {
            log.debug("Resolving default agent. Requested: '$requestedRaw', agents count: ${agents.size}")
            
            if (agents.size == 1) {
                val agent = agents.first()
                log.info("Single agent configuration detected. Defaulting to: ${agent.id.value}")
                return agent.id.value
            }

            // Check if we have session-based agent identity in multi-agent setup
            val sessionId = currentSessionId.get()
            log.debug("Multi-agent setup. Checking session ID: $sessionId")
            
            if (sessionId != null) {
                val sessionAgent = sessionToAgent[sessionId]
                if (sessionAgent != null) {
                    log.info("Resolved agentId from session $sessionId: ${sessionAgent.value}")
                    return sessionAgent.value
                } else {
                    log.warn("Session $sessionId found but no agent mapping exists. Available mappings: ${sessionToAgent.keys}")
                }
            } else {
                log.warn("No session ID available for agent resolution")
            }

            throw IllegalArgumentException(
                "agentId is required when multiple agents are configured. " +
                "Available agents: ${availableAgents()}. " +
                "Please specify agentId explicitly (e.g., agentId=\"codex-cli\") or ensure your client is authenticated."
            )
        }

        log.debug("Resolving explicit agent: '$trimmed'")
        
        // Exact match on id
        runCatching { AgentId(trimmed!!) }.getOrNull()?.let { candidate ->
            agentRegistry.getAgent(candidate)?.let { 
                log.info("Resolved agent by exact ID match: ${it.id.value}")
                return it.id.value 
            }
        }

        // Match on display name for convenience
        agents.firstOrNull { it.displayName.equals(trimmed, ignoreCase = true) }?.let { 
            log.info("Resolved agent by display name match: ${it.id.value}")
            return it.id.value 
        }

        throw IllegalArgumentException("Unknown agentId '${requestedRaw?.trim()}'. Available: ${availableAgents()}")
    }

    private fun normalizeAgentId(optional: String?): String? =
        optional?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveAgentIdOrDefault(it) }

    private fun normalizeAgentIds(optional: List<String>?): List<String>? {
        if (optional == null) return null
        val cleaned = optional.mapNotNull { it.trim().takeIf { value -> value.isNotEmpty() } }
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.map { resolveAgentIdOrDefault(it) }
    }

    /**
     * Extract agent identity from MCP initialize request.
     * Checks clientInfo.name field and matches against registered agents.
     */
    private fun extractAgentIdFromInitialize(message: JSONRPCRequest): AgentId? {
        return try {
            val params = message.params as? JsonObject ?: return null
            val clientInfo = params["clientInfo"] as? JsonObject ?: return null
            val clientName = (clientInfo["name"] as? JsonPrimitive)?.contentOrNull ?: return null
            
            log.info("Extracting agent ID from initialize request. Client name: '$clientName'")

            // Try to match client name to registered agent IDs or display names
            val agents = agentRegistry.getAllAgents()
            
            // Normalize client name for matching
            val normalizedClientName = clientName.lowercase().replace(Regex("[\\s-_]"), "")
            
            val matchedAgent = agents.firstOrNull { agent ->
                val normalizedAgentId = agent.id.value.lowercase().replace(Regex("[\\s-_]"), "")
                val normalizedDisplayName = agent.displayName.lowercase().replace(Regex("[\\s-_]"), "")
                
                // Check various matching strategies
                agent.id.value.equals(clientName, ignoreCase = true) ||
                agent.displayName.equals(clientName, ignoreCase = true) ||
                normalizedClientName.contains(normalizedAgentId) ||
                normalizedClientName.contains(normalizedDisplayName) ||
                normalizedAgentId.contains(normalizedClientName) ||
                normalizedDisplayName.contains(normalizedClientName)
            }
            
            if (matchedAgent != null) {
                log.info("Matched client '$clientName' to agent '${matchedAgent.id.value}' (${matchedAgent.displayName})")
            } else {
                log.warn("Could not match client '$clientName' to any registered agent. Available agents: ${agents.map { "${it.id.value} (${it.displayName})" }}")
            }
            
            matchedAgent?.id
        } catch (e: Exception) {
            log.warn("Failed to extract agent ID from initialize request", e)
            null
        }
    }

    private fun mapGetTaskStatusParams(el: JsonElement): GetTaskStatusTool.Params {
        val o = el.asObj()
        return GetTaskStatusTool.Params(taskId = o.reqStr("taskId"))
    }

    private fun mapSubmitInputParams(el: JsonElement): Pair<SubmitInputTool.Params, String> {
        val o = el.asObj()
        val params = SubmitInputTool.Params(
            taskId = o.reqStr("taskId"),
            agentId = o.str("agentId"),
            content = o.any("content"),
            inputType = o.str("inputType"),
            confidence = o.double("confidence"),
            metadata = o.mapStr("metadata")
        )
        val resolvedId = resolveAgentIdOrDefault(params.agentId)
        return params to resolvedId
    }

    private fun mapRespondToTaskParams(el: JsonElement): Pair<RespondToTaskTool.Params, String> {
        val o = el.asObj()
        val responseObj = o.obj("response")
            ?: throw IllegalArgumentException("Missing required field 'response'")

        val response = RespondToTaskTool.ResponseContent(
            content = responseObj.any("content"),
            inputType = responseObj.str("inputType"),
            confidence = responseObj.double("confidence"),
            metadata = responseObj.mapStr("metadata")
        )

        val params = RespondToTaskTool.Params(
            taskId = o.reqStr("taskId"),
            agentId = o.str("agentId"),
            response = response,
            maxTokens = o.int("maxTokens")
        )

        val resolvedId = resolveAgentIdOrDefault(params.agentId)
        return params to resolvedId
    }

    private fun mapQueryContextParams(el: JsonElement): QueryContextTool.Params {
        val o = el.asObj()
        return QueryContextTool.Params(
            query = o.reqStr("query"),
            k = o.int("k"),
            maxTokens = o.int("maxTokens"),
            paths = o.listStr("paths"),
            languages = o.listStr("languages"),
            kinds = o.listStr("kinds"),
            excludePatterns = o.listStr("excludePatterns"),
            providers = o.listStr("providers")
        )
    }

    private fun mapGetContextStatsParams(el: JsonElement): GetContextStatsTool.Params {
        val o = el.asObj()
        return GetContextStatsTool.Params(
            recentLimit = o.int("recentLimit") ?: 10
        )
    }

    private fun mapRefreshContextParams(el: JsonElement): RefreshContextTool.Params {
        val o = el.asObj()
        return RefreshContextTool.Params(
            paths = o.listStr("paths"),
            force = o.bool("force") ?: false,
            async = o.bool("async") ?: false,
            parallelism = o.int("parallelism")
        )
    }

    // JsonObject helpers
    private fun JsonElement.asObj(): JsonObject = this as? JsonObject
        ?: throw IllegalArgumentException("Params must be a JSON object")
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.array(key: String): List<JsonElement>? = (this[key] as? JsonArray)?.toList()
    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { !it.isStringNull() }?.content
    private fun JsonObject.reqStr(key: String): String = str(key)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing or blank required field '$key'")
    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
    private fun JsonObject.double(key: String): Double? = prim(key)?.doubleOrNull
    private fun JsonObject.reqDouble(key: String): Double = double(key)
        ?: throw IllegalArgumentException("Missing required numeric field '$key'")
    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonObject.listStr(key: String): List<String>? = array(key)?.mapNotNull { (it as? JsonPrimitive)?.content }
    private fun JsonObject.mapStr(key: String): Map<String, String>? = obj(key)?.entries?.associate { (k, v) ->
        k to ((v as? JsonPrimitive)?.content ?: v.toString())
    }
    private fun JsonObject.any(key: String): Any? = this[key]?.let { jsonToAny(it) }

    private fun JsonPrimitive.isStringNull(): Boolean = this is JsonNull || (this.isString && this.content.isEmpty())

    private fun mapJsonToAny(obj: JsonObject): Map<String, Any?> = obj.entries.associate { (k, v) -> k to jsonToAny(v) }
    private fun jsonToAny(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.booleanOrNull != null -> el.booleanOrNull
            el.intOrNull != null -> el.intOrNull
            el.doubleOrNull != null -> el.doubleOrNull
            else -> el.content
        }
        is JsonObject -> mapJsonToAny(el)
        is JsonArray -> el.map { jsonToAny(it) }
    }
    // endregion
}
