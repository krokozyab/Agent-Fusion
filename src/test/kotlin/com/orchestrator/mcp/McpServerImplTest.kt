package com.orchestrator.mcp

import com.orchestrator.config.OrchestratorConfig
import com.orchestrator.config.ServerConfig
import com.orchestrator.core.AgentRegistry
import com.orchestrator.config.ConfigLoader
import com.orchestrator.domain.AgentConfig
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.AgentType
import org.junit.jupiter.api.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpServerImplTest {
    private lateinit var server: McpServerImpl
    private lateinit var baseUrl: String
    private lateinit var tempDir: Path
    private lateinit var tempDbPath: Path

    @BeforeAll
    fun setUp() {
        val port = findFreePort()
        baseUrl = "http://127.0.0.1:$port"
        // Use a temporary DuckDB file for isolation. Do NOT pre-create the file; DuckDB will create it.
        tempDir = Files.createTempDirectory("orchestrator-test-")
        tempDbPath = tempDir.resolve("orchestrator.duckdb")
        System.setProperty("orchestrator.storage.duckdb.path", tempDbPath.toAbsolutePath().toString())
        System.setProperty("orchestrator.storage.duckdb.initSchema", "true")

        val cfg = OrchestratorConfig(
            server = ServerConfig(host = "127.0.0.1", port = port)
        )
        val agents = buildTestRegistry()
        server = McpServerImpl(cfg, agents)
        server.start()
        // Give Netty a short moment to bind
        Thread.sleep(150)
    }

    @AfterAll
    fun tearDown() {
        runCatching { server.stop() }
        runCatching { Files.deleteIfExists(tempDbPath) }
        runCatching { Files.deleteIfExists(tempDir) }
    }

    @Test
    fun healthz_should_return_ok() {
        val conn = URL("$baseUrl/healthz").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
        assertTrue(body.contains("\"status\":\"ok\""), "Expected status=ok, got: $body")
    }

    @Test
    fun tools_list_should_include_known_tools() {
        val conn = URL("$baseUrl/mcp/tools").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        assertEquals(200, conn.responseCode)
        val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
        assertTrue(body.contains("create_simple_task"), "Tools list should contain create_simple_task. Body: $body")
        assertTrue(body.contains("create_consensus_task"), "Tools list should contain create_consensus_task. Body: $body")
        assertTrue(body.contains("get_task_status"), "Tools list should contain get_task_status. Body: $body")
    }

    @Test
    fun tool_call_create_simple_task_happy_path() {
        val payload = """
            {"name":"create_simple_task","params":{"title":"Test task from IT"}}
        """.trimIndent()
        val conn = URL("$baseUrl/mcp/tools/call").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }

        val code = conn.responseCode
        if (code != 200) {
            val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use(BufferedReader::readText)
            println("[DEBUG_LOG] create_simple_task error: $err")
        }
        assertEquals(200, code)
        val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
        assertTrue(body.contains("\"ok\":true"), "Expected ok=true in response. Body: $body")
        assertTrue(body.contains("\"taskId\":"), "Expected taskId in result. Body: $body")
        assertTrue(body.contains("\"routing\":\"SOLO\""), "Expected routing SOLO. Body: $body")
    }

    @Test
    fun tool_call_validation_error_should_return_400() {
        val payload = """
            {"name":"create_simple_task","params":{}}
        """.trimIndent()
        val conn = URL("$baseUrl/mcp/tools/call").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }

        val code = conn.responseCode
        assertEquals(400, code, "Expected 400 on validation error")
        val err = (conn.errorStream ?: conn.inputStream).bufferedReader().use(BufferedReader::readText)
        assertTrue(err.contains("bad_request"), "Expected bad_request error. Body: $err")
    }

    @Test
    fun resources_listing_and_fetch_should_work() {
        // List resources
        val listConn = URL("$baseUrl/mcp/resources").openConnection() as HttpURLConnection
        listConn.requestMethod = "GET"
        assertEquals(200, listConn.responseCode)
        val listBody = listConn.inputStream.bufferedReader().use(BufferedReader::readText)
        assertTrue(listBody.contains("tasks://"), "Expected tasks:// in resources. Body: $listBody")
        assertTrue(listBody.contains("metrics://"), "Expected metrics:// in resources. Body: $listBody")

        // Fetch tasks:// (no filters)
        val tasksConn = URL("$baseUrl/mcp/resources/fetch?uri=" + encode("tasks://")).openConnection() as HttpURLConnection
        tasksConn.requestMethod = "GET"
        assertEquals(200, tasksConn.responseCode)
        val tasksBody = tasksConn.inputStream.bufferedReader().use(BufferedReader::readText)
        assertTrue(tasksBody.contains("\"items\":"), "Expected items array in tasks body. Body: $tasksBody")

        // Fetch metrics:// with a dummy metric name
        val metricsUri = "metrics://?name=test.metric"
        val metricsConn = URL("$baseUrl/mcp/resources/fetch?uri=" + encode(metricsUri)).openConnection() as HttpURLConnection
        metricsConn.requestMethod = "GET"
        assertEquals(200, metricsConn.responseCode)
        val metricsBody = metricsConn.inputStream.bufferedReader().use(BufferedReader::readText)
        assertTrue(metricsBody.contains("\"metrics\":"), "Expected metrics object in response. Body: $metricsBody")
    }

    @Test
    fun streamable_session_emits_tool_list_changed_notification() {
        val sessionId = initializeStreamableSession()
        sendInitializedNotification(sessionId)
        val payload = consumeFirstSseEvent(sessionId)

        assertNotNull(payload, "Expected tool list change notification over SSE")
        assertTrue(
            payload.contains("\"notifications/tools/list_changed\""),
            "Tool list changed notification missing. Payload: $payload"
        )
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, Charsets.UTF_8)

    private fun initializeStreamableSession(): String {
        val payload = """
            {
              "jsonrpc": "2.0",
              "id": "init-1",
              "method": "initialize",
              "params": {
                "protocolVersion": "2025-06-18",
                "capabilities": { "experimental": {} },
                "clientInfo": { "name": "integration-test", "version": "0.0" }
              }
            }
        """.trimIndent()

        val conn = URL("$baseUrl/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }

        val code = conn.responseCode
        val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
        val rawSessionId = conn.getHeaderField("mcp-session-id")?.trim()
        conn.disconnect()

        assertEquals(200, code, "Expected 200 response from initialize. Body: $body")
        val sessionId = assertNotNull(rawSessionId, "initialize response missing mcp-session-id header")
        assertTrue(sessionId.isNotBlank(), "mcp-session-id header should not be blank")

        return sessionId
    }

    private fun sendInitializedNotification(sessionId: String) {
        val payload = """
            {
              "jsonrpc": "2.0",
              "method": "notifications/initialized",
              "params": {}
            }
        """.trimIndent()

        val conn = URL("$baseUrl/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("mcp-session-id", sessionId)
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }

        val code = conn.responseCode
        conn.disconnect()

        assertEquals(202, code, "notifications/initialized should return 202 Accepted")
    }

    private fun consumeFirstSseEvent(sessionId: String): String? {
        val conn = URL("$baseUrl/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.setRequestProperty("mcp-session-id", sessionId)
        conn.connectTimeout = 1_000
        conn.readTimeout = 3_000

        val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
        val start = System.currentTimeMillis()
        var payload: String? = null

        while (System.currentTimeMillis() - start < 3_000) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data:")) {
                payload = line.removePrefix("data:").trim()
            }
            if (line.isBlank() && payload != null) {
                break
            }
        }

        reader.close()
        conn.disconnect()

        return payload
    }

    private fun findFreePort(): Int {
        ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { socket ->
            return socket.localPort
        }
    }
}

private fun buildTestRegistry(): AgentRegistry {
    val def = ConfigLoader.AgentDefinition(
        id = AgentId("agent-1"),
        type = AgentType.CLAUDE_CODE,
        config = AgentConfig(
            name = "Local Test Agent",
            model = "dummy-model",
            apiKeyRef = null,
            organization = null,
            temperature = 0.0,
            maxTokens = 1,
            extra = emptyMap()
        )
    )
    return AgentRegistry.build(listOf(def))
}
