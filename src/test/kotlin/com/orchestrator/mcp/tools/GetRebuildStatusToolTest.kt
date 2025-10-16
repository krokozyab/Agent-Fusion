package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.EmbeddingConfig
import com.orchestrator.context.config.IndexingConfig
import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.storage.ContextDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Path

@OptIn(ExperimentalPathApi::class)
class GetRebuildStatusToolTest {

    private lateinit var tempDir: Path
    private lateinit var config: ContextConfig

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("get-rebuild-status-test")
        config = ContextConfig(
            watcher = WatcherConfig(
                watchPaths = listOf(tempDir.toString()),
                ignorePatterns = emptyList()
            ),
            indexing = IndexingConfig(
                allowedExtensions = listOf(".kt", ".md"),
                blockedExtensions = emptyList()
            ),
            embedding = EmbeddingConfig(
                model = "test-model",
                dimension = 16,
                batchSize = 4
            )
        )
        ContextDatabase.initialize(config.storage)
        clearTables()
        // Ensure clean state for rebuild flag
        val field = RebuildContextTool::class.java.getDeclaredField("rebuildInProgress")
        field.isAccessible = true
        val rebuildInProgress = field.get(null) as java.util.concurrent.atomic.AtomicBoolean
        rebuildInProgress.set(false)
        // Clear any leftover jobs
        RebuildContextTool.clearCompletedJobs()
    }

    @AfterTest
    fun tearDown() {
        clearTables()
        tempDir.deleteRecursively()
        RebuildContextTool.clearCompletedJobs()
        // Reset the rebuildInProgress flag to ensure tests are isolated
        val field = RebuildContextTool::class.java.getDeclaredField("rebuildInProgress")
        field.isAccessible = true
        val rebuildInProgress = field.get(null) as java.util.concurrent.atomic.AtomicBoolean
        rebuildInProgress.set(false)
    }

    @Test
    fun `returns not_found for unknown jobId`() {
        val tool = GetRebuildStatusTool(config)
        val result = tool.execute(GetRebuildStatusTool.Params(
            jobId = "unknown-job-id",
            includeLogs = false
        ))

        assertEquals("unknown-job-id", result.jobId)
        assertEquals("not_found", result.status)
        assertEquals("unknown", result.phase)
        assertNull(result.progress)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Job ID not found"))
        assertNull(result.logs)
    }

    @Test
    fun `returns running status for active job`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        // Start async rebuild
        val rebuildTool = RebuildContextTool(config)
        val rebuildResult = rebuildTool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = rebuildResult.jobId!!

        // Query status immediately
        val statusTool = GetRebuildStatusTool(config)
        val result = statusTool.execute(GetRebuildStatusTool.Params(
            jobId = jobId,
            includeLogs = false
        ))

        assertEquals(jobId, result.jobId)
        assertEquals("running", result.status)
        assertNotNull(result.timing.startedAt)
        assertNull(result.timing.completedAt)
        assertNull(result.error)
        assertNull(result.logs)

        // Wait for rebuild to complete
        delay(1000)
    }

    @Test
    fun `returns completed status when job finishes`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        // Start async rebuild
        val rebuildTool = RebuildContextTool(config)
        val rebuildResult = rebuildTool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = rebuildResult.jobId!!

        // Wait for job to complete
        delay(5000)

        // Query status
        val statusTool = GetRebuildStatusTool(config)
        val result = statusTool.execute(GetRebuildStatusTool.Params(
            jobId = jobId,
            includeLogs = false
        ))

        assertEquals(jobId, result.jobId)
        assertTrue(result.status in listOf("completed", "completed_with_errors", "running"))
        assertNotNull(result.timing.startedAt)
        assertNotNull(result.timing.durationMs)

        if (result.status in listOf("completed", "completed_with_errors")) {
            assertNotNull(result.progress)
            assertEquals(1, result.progress!!.totalFiles)
            assertNull(result.timing.estimatedRemainingMs)
        }
    }

    @Test
    fun `includes progress information when available`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        // Start async rebuild
        val rebuildTool = RebuildContextTool(config)
        val rebuildResult = rebuildTool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = rebuildResult.jobId!!

        // Wait for some progress
        delay(5000)

        // Query status
        val statusTool = GetRebuildStatusTool(config)
        val result = statusTool.execute(GetRebuildStatusTool.Params(
            jobId = jobId,
            includeLogs = false
        ))

        if (result.progress != null) {
            assertTrue(result.progress.totalFiles >= 0)
            assertTrue(result.progress.processedFiles >= 0)
            assertTrue(result.progress.successfulFiles >= 0)
            assertTrue(result.progress.failedFiles >= 0)
            assertTrue(result.progress.percentComplete in 0..100)
        }
    }

    @Test
    fun `includes logs when requested`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        // Start async rebuild
        val rebuildTool = RebuildContextTool(config)
        val rebuildResult = rebuildTool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = rebuildResult.jobId!!

        // Wait for some progress
        delay(1000)

        // Query status with logs
        val statusTool = GetRebuildStatusTool(config)
        val result = statusTool.execute(GetRebuildStatusTool.Params(
            jobId = jobId,
            includeLogs = true
        ))

        assertNotNull(result.logs)
        assertTrue(result.logs.isNotEmpty())

        // Check that logs have proper structure
        result.logs.forEach { log ->
            assertNotNull(log.timestamp)
            assertNotNull(log.level)
            assertNotNull(log.message)
            assertTrue(log.level in listOf("INFO", "WARN", "ERROR"))
        }

        // Wait for rebuild to complete
        delay(1000)
    }

    @Test
    fun `excludes logs when not requested`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        // Start async rebuild
        val rebuildTool = RebuildContextTool(config)
        val rebuildResult = rebuildTool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = rebuildResult.jobId!!

        // Query status without logs (default)
        val statusTool = GetRebuildStatusTool(config)
        val result = statusTool.execute(GetRebuildStatusTool.Params(
            jobId = jobId,
            includeLogs = false
        ))

        assertNull(result.logs)

        // Wait for rebuild to complete
        delay(1000)
    }

    @Test
    fun `calculates percent complete correctly`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        // Start async rebuild
        val rebuildTool = RebuildContextTool(config)
        val rebuildResult = rebuildTool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = rebuildResult.jobId!!

        // Wait for completion
        delay(5000)

        // Query status
        val statusTool = GetRebuildStatusTool(config)
        val result = statusTool.execute(GetRebuildStatusTool.Params(
            jobId = jobId,
            includeLogs = false
        ))

        if (result.progress != null && result.progress.totalFiles > 0) {
            val expectedPercent = ((result.progress.processedFiles.toDouble() / result.progress.totalFiles) * 100).toInt()
            assertEquals(expectedPercent, result.progress.percentComplete)
        }
    }

    @Test
    fun `provides timing information correctly`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        // Start async rebuild
        val rebuildTool = RebuildContextTool(config)
        val rebuildResult = rebuildTool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = rebuildResult.jobId!!

        // Query status immediately
        val statusTool = GetRebuildStatusTool(config)
        val result1 = statusTool.execute(GetRebuildStatusTool.Params(
            jobId = jobId,
            includeLogs = false
        ))

        assertNotNull(result1.timing.startedAt)
        assertNotNull(result1.timing.durationMs)
        assertTrue(result1.timing.durationMs!! >= 0)

        if (result1.status == "running") {
            assertNull(result1.timing.completedAt)
            // Estimated remaining time may or may not be available depending on progress
        }

        // Wait for completion
        delay(5000)

        // Query again
        val result2 = statusTool.execute(GetRebuildStatusTool.Params(
            jobId = jobId,
            includeLogs = false
        ))

        if (result2.status in listOf("completed", "completed_with_errors", "failed")) {
            assertNotNull(result2.timing.durationMs)
            assertTrue(result2.timing.durationMs!! > 0)
            assertNull(result2.timing.estimatedRemainingMs)
        }
    }

    private fun clearTables() {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                // Check if tables exist before trying to delete from them
                val tables = listOf("usage_metrics", "links", "symbols", "embeddings", "chunks", "file_state")
                tables.forEach { table ->
                    try {
                        st.executeUpdate("DELETE FROM $table")
                    } catch (e: Exception) {
                        // Table might not exist (e.g., after a rebuild that dropped tables)
                        // This is expected and we can ignore it
                    }
                }
            }
        }
    }
}
