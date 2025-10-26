package com.orchestrator.web.routes

import com.orchestrator.config.ConfigLoader
import com.orchestrator.config.OrchestratorConfig
import com.orchestrator.context.bootstrap.BootstrapProgressTracker
import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.ProviderConfig
import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.web.WebServerConfig
import com.orchestrator.web.plugins.ApplicationConfigKey
import com.orchestrator.web.plugins.configureRouting
import com.orchestrator.web.services.IndexOperationsService
import com.orchestrator.web.services.OperationTriggerResult
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.application.install
import io.ktor.server.sse.SSE
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant
import java.util.Comparator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class IndexRoutesTest {

    private lateinit var tempDir: Path
    private lateinit var contextConfig: ContextConfig
    private lateinit var stubOperations: StubIndexOperationsService

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("context-db-")
        val dbPath = (tempDir / "context.duckdb").toString()

        contextConfig = ContextConfig(
            storage = StorageConfig(dbPath = dbPath),
            providers = mapOf(
                "semantic" to ProviderConfig(enabled = true, weight = 0.7),
                "symbol" to ProviderConfig(enabled = false, weight = 0.3)
            )
        )

        ContextModule.configure(contextConfig)
        ContextDatabase.initialize(contextConfig.storage)
        seedContextData()

        stubOperations = StubIndexOperationsService()
    }

    @AfterTest
    fun tearDown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                if (path != tempDir) {
                    path.deleteIfExists()
                }
            }
        }
        tempDir.deleteIfExists()
    }

    @Test
    fun `GET index renders status dashboard`() = testApplication {
        application {
            install(SSE)
            IndexOperationsService.install(this, stubOperations)
            val appConfig = ConfigLoader.ApplicationConfig(
                orchestrator = OrchestratorConfig(),
                web = WebServerConfig(),
                agents = emptyList(),
                context = contextConfig
            )
            attributes.put(ApplicationConfigKey, appConfig)

            configureRouting(WebServerConfig())
        }

        val response = client.get("/index")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/html; charset=UTF-8", response.headers["Content-Type"])

        val body = response.bodyAsText()

        assertContains(body, "Index Status")
        assertContains(body, "data-testid=\"stat-total-files\"")
        assertContains(body, "id=\"index-summary\"")
        assertContains(body, "sse-swap=\"indexSummary\"")
        assertContains(body, "data-sse-url=\"/sse/index\"")
        assertContains(body, "data-testid=\"action-refresh\"")
        assertContains(body, "fetch('/index/refresh'")
        assertContains(body, "data-testid=\"provider-semantic-status\"")
        assertContains(body, "data-testid=\"provider-symbol-status\"")
        assertContains(body, "data-testid=\"file-row-src/app/Indexed.kt\"")
        assertContains(body, "Pending Files")
        assertContains(body, "Failed")
    }

    private fun seedContextData() {
        ContextDatabase.transaction { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeUpdate("DELETE FROM chunks")
                stmt.executeUpdate("DELETE FROM file_state")
            }

            val now = Instant.parse("2025-01-02T03:04:05Z")
            val earlier = now.minusSeconds(3600)

            insertFile(
                conn = conn,
                fileId = 1,
                path = "src/app/Indexed.kt",
                sizeBytes = 4_096,
                modified = now.minusSeconds(30),
                indexedAt = now,
                chunkCount = 2
            )

            insertFile(
                conn = conn,
                fileId = 2,
                path = "src/app/Outdated.kt",
                sizeBytes = 2_048,
                modified = now,
                indexedAt = earlier,
                chunkCount = 1
            )
        }

        val tracker = BootstrapProgressTracker()
        val pendingPath = Path.of("docs/pending.md").toAbsolutePath()
        val failedPath = Path.of("scripts/error.py").toAbsolutePath()
        tracker.initProgress(listOf(pendingPath, failedPath))
        tracker.markFailed(failedPath, "Parser error")
    }

    private fun insertFile(
        conn: java.sql.Connection,
        fileId: Long,
        path: String,
        sizeBytes: Long,
        modified: Instant,
        indexedAt: Instant,
        chunkCount: Int
    ) {
        val modifiedNs = modified.epochSecond * 1_000_000_000L + modified.nano

        conn.prepareStatement(
            """
            INSERT INTO file_state (
                file_id, rel_path, content_hash, size_bytes, mtime_ns,
                language, kind, fingerprint, indexed_at, is_deleted
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, fileId)
            ps.setString(2, path)
            ps.setString(3, "hash-${fileId}")
            ps.setLong(4, sizeBytes)
            ps.setLong(5, modifiedNs)
            ps.setString(6, "kotlin")
            ps.setString(7, "code")
            ps.setNull(8, java.sql.Types.VARCHAR)
            ps.setTimestamp(9, Timestamp.from(indexedAt))
            ps.setBoolean(10, false)
            ps.executeUpdate()
        }

        conn.prepareStatement(
            """
            INSERT INTO chunks (
                chunk_id, file_id, ordinal, kind, start_line, end_line, token_count,
                content, summary, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            repeat(chunkCount) { index ->
                ps.setLong(1, fileId * 10 + index)
                ps.setLong(2, fileId)
                ps.setInt(3, index)
                ps.setString(4, "PLAIN_TEXT")
                ps.setInt(5, 1)
                ps.setInt(6, 20)
                ps.setInt(7, 120)
                ps.setString(8, "Sample chunk content ${index + 1}")
                ps.setString(9, "Summary ${index + 1}")
                ps.setTimestamp(10, Timestamp.from(indexedAt))
                ps.addBatch()
            }
            ps.executeBatch()
        }
    }

    @Test
    fun `POST refresh invokes operations service`() = testApplication {
        application {
            install(SSE)
            IndexOperationsService.install(this, stubOperations)
            val appConfig = ConfigLoader.ApplicationConfig(
                orchestrator = OrchestratorConfig(),
                web = WebServerConfig(),
                agents = emptyList(),
                context = contextConfig
            )
            attributes.put(ApplicationConfigKey, appConfig)

            configureRouting(WebServerConfig())
        }

        val response = client.post("/index/refresh")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(1, stubOperations.refreshCalls.get())
    }
}

private class StubIndexOperationsService : IndexOperationsService {
    val refreshCalls = AtomicInteger(0)
    val rebuildCalls = AtomicInteger(0)
    val optimizeCalls = AtomicInteger(0)

    override fun triggerRefresh(): OperationTriggerResult {
        refreshCalls.incrementAndGet()
        return OperationTriggerResult(accepted = true, message = "stub refresh")
    }

    override fun triggerRebuild(confirm: Boolean): OperationTriggerResult {
        rebuildCalls.incrementAndGet()
        return OperationTriggerResult(accepted = true, message = "stub rebuild")
    }

    override fun optimize(): OperationTriggerResult {
        optimizeCalls.incrementAndGet()
        return OperationTriggerResult(accepted = true, message = "stub optimize")
    }
}
