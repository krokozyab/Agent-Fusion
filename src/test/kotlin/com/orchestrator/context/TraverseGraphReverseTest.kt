package com.orchestrator.context

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.storage.ContextDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the inbound graph traversal used by impact-radius queries.
 *
 * Topology used by most tests:
 *
 *   10 (caller A) ──CALLS──▶ 30 (target/seed)
 *   20 (caller B) ──CALLS──▶ 30
 *   40 (transitive) ──CALLS──▶ 10
 *   50 (test) ──COVERS──▶ 30
 */
class TraverseGraphReverseTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("traverse-reverse-test")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
        seedFileAndChunks(1L, listOf(10L, 20L, 30L, 40L, 50L))
        insertLink(10L, 30L, "CALLS", 0.9)
        insertLink(20L, 30L, "CALLS", 0.9)
        insertLink(40L, 10L, "CALLS", 0.85)
        insertLink(50L, 30L, "COVERS", 0.95)
    }

    @AfterEach
    fun teardown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `1-hop inbound finds direct callers`() {
        val result = ContextRepository.traverseGraphReverse(
            seedChunkIds = listOf(30L),
            maxDepth = 1,
            defaultLinkScore = 0.8,
            maxResults = 50,
            linkTypes = setOf("CALLS")
        )

        val ids = result.map { it.chunkId }.toSet()
        assertTrue(10L in ids, "Should include direct caller 10")
        assertTrue(20L in ids, "Should include direct caller 20")
        assertTrue(40L !in ids, "Should NOT include depth-2 caller 40 at maxDepth=1")
        assertTrue(50L !in ids, "Should NOT include COVERS link when filtered to CALLS")
        result.forEach { assertEquals(1, it.depth) }
    }

    @Test
    fun `multi-hop inbound finds transitive callers`() {
        val result = ContextRepository.traverseGraphReverse(
            seedChunkIds = listOf(30L),
            maxDepth = 2,
            defaultLinkScore = 0.8,
            maxResults = 50,
            linkTypes = setOf("CALLS")
        )

        val byId = result.associateBy { it.chunkId }
        assertTrue(10L in byId)
        assertTrue(20L in byId)
        assertTrue(40L in byId, "Depth-2 transitive caller should be included")
        assertEquals(2, byId.getValue(40L).depth)
    }

    @Test
    fun `linkTypes filter restricts to COVERS only`() {
        val result = ContextRepository.traverseGraphReverse(
            seedChunkIds = listOf(30L),
            maxDepth = 1,
            defaultLinkScore = 0.8,
            maxResults = 50,
            linkTypes = setOf("COVERS")
        )

        assertEquals(setOf(50L), result.map { it.chunkId }.toSet())
        assertEquals("COVERS", result.single().linkType)
    }

    @Test
    fun `null linkTypes returns all link types`() {
        val result = ContextRepository.traverseGraphReverse(
            seedChunkIds = listOf(30L),
            maxDepth = 1,
            defaultLinkScore = 0.8,
            maxResults = 50,
            linkTypes = null
        )

        val ids = result.map { it.chunkId }.toSet()
        assertEquals(setOf(10L, 20L, 50L), ids)
    }

    @Test
    fun `seeds are excluded from results`() {
        val result = ContextRepository.traverseGraphReverse(
            seedChunkIds = listOf(30L),
            maxDepth = 2,
            defaultLinkScore = 0.8,
            maxResults = 50,
            linkTypes = null
        )

        assertTrue(result.none { it.chunkId == 30L })
    }

    @Test
    fun `empty seeds returns empty`() {
        val result = ContextRepository.traverseGraphReverse(
            seedChunkIds = emptyList(),
            maxDepth = 2,
            defaultLinkScore = 0.8,
            maxResults = 50
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `maxResults caps output`() {
        val result = ContextRepository.traverseGraphReverse(
            seedChunkIds = listOf(30L),
            maxDepth = 2,
            defaultLinkScore = 0.8,
            maxResults = 1,
            linkTypes = null
        )
        assertEquals(1, result.size)
    }

    // -- helpers (mirrors LinkExpanderTest) --

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
                        VALUES ($chunkId, $fileId, $idx, 'CODE_FUNCTION', 1, 10, 50, 'content $chunkId', 'summary', CURRENT_TIMESTAMP)
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
