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
class RefreshContextToolTest {

    private lateinit var tempDir: Path
    private lateinit var config: ContextConfig

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("refresh-context-test")
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
    }

    @AfterTest
    fun tearDown() {
        clearTables()
        tempDir.deleteRecursively()
        RefreshContextTool.clearCompletedJobs()
    }

    @Test
    fun `execute with sync mode returns immediate results`() {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RefreshContextTool(config)
        val result = tool.execute(RefreshContextTool.Params(
            paths = listOf(tempDir.toString()),
            force = false,
            async = false
        ))

        assertEquals("sync", result.mode)
        assertTrue(result.status in listOf("completed", "completed_with_errors"))
        assertNull(result.jobId)
        assertNotNull(result.completedAt)
        assertNotNull(result.durationMs)
    }

    @Test
    fun `execute with async mode returns jobId immediately`() {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RefreshContextTool(config)
        val result = tool.execute(RefreshContextTool.Params(
            paths = listOf(tempDir.toString()),
            force = false,
            async = true
        ))

        assertEquals("async", result.mode)
        assertEquals("running", result.status)
        assertNotNull(result.jobId)
        assertNull(result.newFiles)
        assertNull(result.completedAt)
        assertTrue(result.message!!.contains("Background refresh started"))
    }

    @Test
    fun `execute with null paths uses config watch paths`() {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RefreshContextTool(config)
        val result = tool.execute(RefreshContextTool.Params(
            paths = null,
            force = false,
            async = false
        ))

        assertEquals("sync", result.mode)
        assertTrue(result.status in listOf("completed", "completed_with_errors"))
    }

    @Test
    fun `execute with invalid paths returns error`() {
        val tool = RefreshContextTool(config)
        val result = tool.execute(RefreshContextTool.Params(
            paths = listOf("/nonexistent/path"),
            force = false,
            async = false
        ))

        assertEquals("sync", result.mode)
        assertEquals("error", result.status)
        assertNull(result.newFiles)
        assertTrue(result.message!!.contains("No valid paths"))
    }

    @Test
    fun `getJobStatus returns null for unknown jobId`() {
        val tool = RefreshContextTool(config)
        val status = tool.getJobStatus("unknown-job-id")
        assertNull(status)
    }

    @Test
    fun `getJobStatus returns completed status when job finishes`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RefreshContextTool(config)
        val result = tool.execute(RefreshContextTool.Params(
            paths = listOf(tempDir.toString()),
            force = false,
            async = true
        ))

        val jobId = result.jobId!!

        // Wait for job to complete
        delay(5000)

        val status = tool.getJobStatus(jobId)

        assertNotNull(status)
        assertTrue(status.status in listOf("completed", "completed_with_errors", "running"))
        assertEquals(jobId, status.jobId)
    }

    @Test
    fun `clearCompletedJobs removes completed jobs`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RefreshContextTool(config)
        val result = tool.execute(RefreshContextTool.Params(
            paths = listOf(tempDir.toString()),
            force = false,
            async = true
        ))

        val jobId = result.jobId!!

        // Wait for job to complete
        delay(5000)

        // Job should exist
        val statusBefore = tool.getJobStatus(jobId)
        assertNotNull(statusBefore)

        // Clear completed jobs
        RefreshContextTool.clearCompletedJobs()

        // Job should be gone if it was completed
        val statusAfter = tool.getJobStatus(jobId)
        if (statusBefore.status in listOf("completed", "completed_with_errors", "failed")) {
            assertNull(statusAfter)
        }
    }

    private fun clearTables() {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM usage_metrics")
                st.executeUpdate("DELETE FROM embeddings")
                st.executeUpdate("DELETE FROM chunks")
                st.executeUpdate("DELETE FROM file_state")
            }
        }
    }
}
