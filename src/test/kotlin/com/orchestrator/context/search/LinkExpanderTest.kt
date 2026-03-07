package com.orchestrator.context.search

import com.orchestrator.context.config.GraphConfig
import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.storage.ContextDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkExpanderTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("link-expander-test")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
    }

    @AfterEach
    fun teardown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `disabled config returns input unchanged`() {
        val config = GraphConfig(enabled = false)
        val expander = LinkExpander(config)
        val snippets = listOf(makeSnippet(1L, 0.9))

        val result = expander.expand(snippets)

        assertEquals(snippets, result)
    }

    @Test
    fun `empty input returns empty`() {
        val config = GraphConfig(enabled = true)
        val expander = LinkExpander(config)

        val result = expander.expand(emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `1-hop adds linked chunks with correct scores`() {
        // Set up DB: file → 2 chunks → 1 link between them
        seedFileAndChunks(fileId = 1, chunkIds = listOf(10L, 20L))
        insertLink(sourceChunkId = 10, targetChunkId = 20, linkType = "DEPENDS_ON", score = 0.92)

        val config = GraphConfig(enabled = true, maxDepth = 1, decayFactor = 0.7, defaultLinkScore = 0.8)
        val expander = LinkExpander(config)

        val snippets = listOf(makeSnippet(10L, 0.9))
        val result = expander.expand(snippets)

        assertEquals(2, result.size)
        // Original preserved
        assertTrue(result.any { it.chunkId == 10L && it.score == 0.9 })
        // Propagated: 0.9 * 0.92 * 0.7^1 = 0.5796
        val propagated = result.first { it.chunkId == 20L }
        assertTrue(abs(propagated.score - 0.9 * 0.92 * 0.7) < 0.001,
            "Expected ~${0.9 * 0.92 * 0.7} but got ${propagated.score}")
    }

    @Test
    fun `already-present chunks not duplicated`() {
        seedFileAndChunks(fileId = 1, chunkIds = listOf(10L, 20L))
        insertLink(sourceChunkId = 10, targetChunkId = 20, linkType = "CALLS", score = 0.9)

        val config = GraphConfig(enabled = true, maxDepth = 1, decayFactor = 0.7)
        val expander = LinkExpander(config)

        // Both chunks already in results
        val snippets = listOf(makeSnippet(10L, 0.9), makeSnippet(20L, 0.8))
        val result = expander.expand(snippets)

        // No new chunks added — linked chunk is already present
        assertEquals(2, result.size)
    }

    @Test
    fun `maxGraphResults caps output`() {
        // Create 1 source chunk and 8 target chunks, all linked
        val allIds = (100L..108L).toList()
        seedFileAndChunks(fileId = 1, chunkIds = allIds)
        for (targetId in 101L..108L) {
            insertLink(sourceChunkId = 100, targetChunkId = targetId, linkType = "CALLS", score = 0.9)
        }

        val config = GraphConfig(enabled = true, maxDepth = 1, decayFactor = 0.7, maxGraphResults = 3)
        val expander = LinkExpander(config)

        val snippets = listOf(makeSnippet(100L, 0.9))
        val result = expander.expand(snippets)

        // 1 original + 3 capped graph results
        assertEquals(4, result.size)
        assertEquals(3, result.count { it.metadata["provider"] == "graph" })
    }

    @Test
    fun `minPropagatedScore filters weak links`() {
        seedFileAndChunks(fileId = 1, chunkIds = listOf(10L, 20L))
        insertLink(sourceChunkId = 10, targetChunkId = 20, linkType = "MODIFIES", score = 0.05)

        // With low link score (0.05) and decay: 0.3 * 0.05 * 0.7 = 0.0105 < 0.1 threshold
        val config = GraphConfig(enabled = true, maxDepth = 1, decayFactor = 0.7, minPropagatedScore = 0.1)
        val expander = LinkExpander(config)

        val snippets = listOf(makeSnippet(10L, 0.3))
        val result = expander.expand(snippets)

        // Weak link filtered out
        assertEquals(1, result.size)
        assertEquals(10L, result[0].chunkId)
    }

    @Test
    fun `multi-hop calls traverseGraph`() {
        // Chain: 10 → 20 → 30
        seedFileAndChunks(fileId = 1, chunkIds = listOf(10L, 20L, 30L))
        insertLink(sourceChunkId = 10, targetChunkId = 20, linkType = "CALLS", score = 0.9)
        insertLink(sourceChunkId = 20, targetChunkId = 30, linkType = "CALLS", score = 0.85)

        val config = GraphConfig(enabled = true, maxDepth = 2, decayFactor = 0.7, maxGraphResults = 10)
        val expander = LinkExpander(config)

        val snippets = listOf(makeSnippet(10L, 0.9))
        val result = expander.expand(snippets)

        // Should find both depth-1 (chunk 20) and depth-2 (chunk 30)
        assertTrue(result.any { it.chunkId == 20L }, "Should include 1-hop chunk 20")
        assertTrue(result.any { it.chunkId == 30L }, "Should include 2-hop chunk 30")

        val depth2 = result.first { it.chunkId == 30L }
        assertEquals("2", depth2.metadata["graph_depth"])
    }

    @Test
    fun `metadata set correctly`() {
        seedFileAndChunks(fileId = 1, chunkIds = listOf(10L, 20L))
        insertLink(sourceChunkId = 10, targetChunkId = 20, linkType = "DEPENDS_ON", score = 0.92)

        val config = GraphConfig(enabled = true, maxDepth = 1, decayFactor = 0.7)
        val expander = LinkExpander(config)

        val snippets = listOf(makeSnippet(10L, 0.9))
        val result = expander.expand(snippets)

        val propagated = result.first { it.chunkId == 20L }
        assertEquals("graph", propagated.metadata["provider"])
        assertEquals("10", propagated.metadata["graph_source"])
        assertEquals("1", propagated.metadata["graph_depth"])
        assertEquals("DEPENDS_ON", propagated.metadata["graph_link_type"])
    }

    @Test
    fun `expansion failure returns originals`() {
        // Shutdown DB to force an exception during expansion
        ContextDatabase.shutdown()

        val config = GraphConfig(enabled = true, maxDepth = 1, decayFactor = 0.7)
        val expander = LinkExpander(config)

        val snippets = listOf(makeSnippet(10L, 0.9))
        val result = expander.expand(snippets)

        // Should gracefully return originals
        assertEquals(snippets, result)
    }

    // -- helpers --

    private fun makeSnippet(chunkId: Long, score: Double) = ContextSnippet(
        chunkId = chunkId,
        score = score,
        filePath = "/test/file.kt",
        label = null,
        kind = ChunkKind.CODE_FUNCTION,
        text = "content for chunk $chunkId",
        language = "kotlin",
        offsets = 1..10,
        metadata = mapOf("token_estimate" to "10")
    )

    private fun seedFileAndChunks(fileId: Long, chunkIds: List<Long>) {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("""
                    INSERT INTO file_state (file_id, rel_path, abs_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                    VALUES ($fileId, 'test/file.kt', '/test/file.kt', 'hash', 1000, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
                    ON CONFLICT DO NOTHING
                """.trimIndent())
            }
            chunkIds.forEachIndexed { idx, chunkId ->
                conn.createStatement().use { st ->
                    st.execute("""
                        INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, token_count, content, summary, created_at)
                        VALUES ($chunkId, $fileId, $idx, 'CODE_FUNCTION', 1, 10, 50, 'content for chunk $chunkId', 'summary', CURRENT_TIMESTAMP)
                    """.trimIndent())
                }
            }
        }
    }

    private fun insertLink(sourceChunkId: Long, targetChunkId: Long, linkType: String, score: Double) {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("""
                    INSERT INTO links (link_id, source_chunk_id, target_file_id, target_chunk_id, link_type, label, score, created_at)
                    VALUES (nextval('links_seq'), $sourceChunkId, 1, $targetChunkId, '$linkType', NULL, $score, CURRENT_TIMESTAMP)
                """.trimIndent())
            }
        }
    }
}
