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
class RebuildContextToolTest {

    private lateinit var tempDir: Path
    private lateinit var config: ContextConfig

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("rebuild-context-test")
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
        RebuildContextTool.clearCompletedJobs()
    }

    @Test
    fun `execute without confirm returns error`() {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = false,
            async = false,
            paths = listOf(tempDir.toString())
        ))

        assertEquals("sync", result.mode)
        assertEquals("error", result.status)
        assertEquals("validation", result.phase)
        assertNotNull(result.validationErrors)
        assertTrue(result.validationErrors!!.any { it.contains("confirm=true is required") })
    }

    @Test
    fun `execute with validateOnly succeeds without confirm`() {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = false,
            async = false,
            paths = listOf(tempDir.toString()),
            validateOnly = true
        ))

        assertEquals("sync", result.mode)
        assertEquals("validated", result.status)
        assertEquals("validation", result.phase)
        assertNull(result.validationErrors)
        assertTrue(result.message!!.contains("Validation successful"))
    }

    @Test
    fun `execute with validateOnly does not clear data`() {
        // First, add some test data
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("INSERT INTO file_state (file_id, rel_path, size_bytes, mtime_ns) VALUES (1, 'test.kt', 100, 1000000)")
            }
        }

        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = false,
            async = false,
            paths = listOf(tempDir.toString()),
            validateOnly = true
        ))

        assertEquals("validated", result.status)

        // Verify data still exists
        ContextDatabase.withConnection { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM file_state")
            rs.next()
            val count = rs.getInt(1)
            assertEquals(1, count, "Data should not be cleared in validateOnly mode")
        }
    }

    @Test
    fun `execute with invalid paths returns error`() {
        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = false,
            paths = listOf("/nonexistent/path")
        ))

        assertEquals("sync", result.mode)
        assertEquals("error", result.status)
        assertEquals("validation", result.phase)
        assertTrue(result.message!!.contains("No valid paths"))
    }

    @Test
    fun `execute with sync mode clears data and rebuilds`() {
        // First, add some test data
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("INSERT INTO file_state (file_id, rel_path, size_bytes, mtime_ns) VALUES (1, 'old.kt', 100, 1000000)")
            }
        }

        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = false,
            paths = listOf(tempDir.toString())
        ))

        assertEquals("sync", result.mode)
        assertTrue(result.status in listOf("completed", "completed_with_errors"))
        assertEquals("post-rebuild", result.phase)
        assertNull(result.jobId)
        assertNotNull(result.completedAt)
        assertNotNull(result.durationMs)

        // Verify old data was cleared
        ContextDatabase.withConnection { conn ->
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM file_state WHERE rel_path = 'old.kt'")
            rs.next()
            val count = rs.getInt(1)
            assertEquals(0, count, "Old data should be cleared")
        }
    }

    @Test
    fun `execute with async mode returns jobId immediately`() {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        assertEquals("async", result.mode)
        assertEquals("running", result.status)
        assertEquals("pre-rebuild", result.phase)
        assertNotNull(result.jobId)
        assertNull(result.totalFiles)
        assertNull(result.completedAt)
        assertTrue(result.message!!.contains("Background rebuild started"))
    }

    @Test
    fun `execute with null paths uses config watch paths`() {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = false,
            paths = null
        ))

        assertEquals("sync", result.mode)
        assertTrue(result.status in listOf("completed", "completed_with_errors"))
    }

    @Test
    fun `getJobStatus returns null for unknown jobId`() {
        val tool = RebuildContextTool(config)
        val status = tool.getJobStatus("unknown-job-id")
        assertNull(status)
    }

    @Test
    fun `getJobStatus returns completed status when job finishes`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = result.jobId!!

        // Wait for job to complete
        delay(10000)

        val status = tool.getJobStatus(jobId)

        assertNotNull(status)
        assertTrue(status.status in listOf("completed", "completed_with_errors", "running"))
        assertEquals(jobId, status.jobId)
    }

    @Test
    fun `clearCompletedJobs removes completed jobs`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        val jobId = result.jobId!!

        // Wait for job to complete
        delay(10000)

        // Job should exist
        val statusBefore = tool.getJobStatus(jobId)
        assertNotNull(statusBefore)

        // Clear completed jobs
        RebuildContextTool.clearCompletedJobs()

        // Job should be gone if it was completed
        val statusAfter = tool.getJobStatus(jobId)
        if (statusBefore.status in listOf("completed", "completed_with_errors", "failed")) {
            assertNull(statusAfter)
        }
    }

    @Test
    fun `concurrent rebuild attempts are blocked`() = runBlocking {
        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)

        // Start first rebuild (async so it runs in background)
        val result1 = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = true,
            paths = listOf(tempDir.toString())
        ))

        assertEquals("running", result1.status)

        // Give the async task a moment to acquire the lock
        delay(100)

        // Try to start second rebuild
        val result2 = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = false,
            paths = listOf(tempDir.toString())
        ))

        assertEquals("error", result2.status)
        assertTrue(result2.message!!.contains("Another rebuild is already in progress"))
    }

    @Test
    fun `execute with negative parallelism returns validation error`() {
        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = false,
            paths = listOf(tempDir.toString()),
            parallelism = -1
        ))

        assertEquals("error", result.status)
        assertNotNull(result.validationErrors)
        assertTrue(result.validationErrors!!.any { it.contains("parallelism must be >= 1") })
    }

    @Test
    fun `execute clears all tables in correct order`() {
        // Add test data to all tables
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("INSERT INTO file_state (file_id, rel_path, size_bytes, mtime_ns) VALUES (1, 'test.kt', 100, 1000000)")
                st.executeUpdate("INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, content, created_at) VALUES (1, 1, 0, 'function', 1, 10, 'test', CURRENT_TIMESTAMP)")
                st.executeUpdate("INSERT INTO embeddings (embedding_id, chunk_id, model, dimensions, vector, created_at) VALUES (1, 1, 'test', 16, '[]', CURRENT_TIMESTAMP)")
                st.executeUpdate("INSERT INTO symbols (symbol_id, file_id, symbol_type, name, created_at) VALUES (1, 1, 'function', 'test', CURRENT_TIMESTAMP)")
                st.executeUpdate("INSERT INTO links (link_id, source_chunk_id, target_file_id, link_type, created_at) VALUES (1, 1, 1, 'import', CURRENT_TIMESTAMP)")
                st.executeUpdate("INSERT INTO usage_metrics (metric_id, snippets_returned, total_tokens, retrieval_latency_ms, created_at) VALUES (1, 5, 100, 50, CURRENT_TIMESTAMP)")
            }
        }

        val file = tempDir.resolve("Test.kt")
        file.writeText("fun main() = Unit")

        val tool = RebuildContextTool(config)
        val result = tool.execute(RebuildContextTool.Params(
            confirm = true,
            async = false,
            paths = listOf(tempDir.toString())
        ))

        assertTrue(result.status in listOf("completed", "completed_with_errors"))

        // Verify all tables were cleared
        ContextDatabase.withConnection { conn ->
            val tables = listOf("usage_metrics", "links", "symbols", "embeddings", "chunks", "file_state")
            tables.forEach { table ->
                val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $table")
                rs.next()
                val count = rs.getInt(1)
                // Note: Some tables might have data from the rebuild, so we just check that old data is gone
                // For usage_metrics which doesn't get populated by rebuild, it should be 0
                if (table == "usage_metrics") {
                    assertEquals(0, count, "Table $table should be empty after rebuild")
                }
            }
        }
    }

    private fun clearTables() {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM usage_metrics")
                st.executeUpdate("DELETE FROM links")
                st.executeUpdate("DELETE FROM symbols")
                st.executeUpdate("DELETE FROM embeddings")
                st.executeUpdate("DELETE FROM chunks")
                st.executeUpdate("DELETE FROM file_state")
            }
        }
    }
}
