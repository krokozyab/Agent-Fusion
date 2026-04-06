package com.orchestrator.mcp.tools

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.storage.ContextDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end test for the impact-radius tool.
 *
 * Graph used throughout:
 *
 *   chunk 100 (src/Target.kt, lines 1..10) — the seed (edited)
 *   chunk 200 (src/DirectCaller.kt)     ──CALLS──▶ 100
 *   chunk 300 (src/TransitiveCaller.kt) ──CALLS──▶ 200
 *   chunk 400 (src/test/TargetTest.kt)  ──COVERS──▶ 100
 */
class GetImpactRadiusToolTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("impact-radius-tool-test")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))

        seedFile(1L, "src/Target.kt", "/proj/src/Target.kt", listOf(Quad(100L, 1, 10, 40)))
        seedFile(2L, "src/DirectCaller.kt", "/proj/src/DirectCaller.kt", listOf(Quad(200L, 1, 20, 60)))
        seedFile(3L, "src/TransitiveCaller.kt", "/proj/src/TransitiveCaller.kt", listOf(Quad(300L, 1, 30, 80)))
        seedFile(4L, "src/test/TargetTest.kt", "/proj/src/test/TargetTest.kt", listOf(Quad(400L, 1, 15, 50)))

        insertLink(source = 200L, target = 100L, type = "CALLS", score = 0.9)
        insertLink(source = 300L, target = 200L, type = "CALLS", score = 0.85)
        insertLink(source = 400L, target = 100L, type = "COVERS", score = 0.95)
    }

    @AfterEach
    fun teardown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `whole-file change returns seed, impact, and tests`() {
        val result = GetImpactRadiusTool().execute(
            GetImpactRadiusTool.Params(
                paths = listOf("src/Target.kt"),
                maxDepth = 2,
                tokenBudget = 10_000
            )
        )

        assertEquals(1, result.seedCount)
        assertEquals(2, result.impactCount, "direct + transitive caller")
        assertEquals(1, result.testCount)
        assertEquals(0, result.droppedDueToBudget)
        assertEquals(4, result.chunks.size)

        val byId = result.chunks.associateBy { it.chunkId }
        assertEquals("seed", byId.getValue(100L).role)
        assertEquals("impact", byId.getValue(200L).role)
        assertEquals("impact", byId.getValue(300L).role)
        assertEquals("test", byId.getValue(400L).role)

        // Depths correctly propagated.
        assertNull(byId.getValue(100L).depth)
        assertEquals(1, byId.getValue(200L).depth)
        assertEquals(2, byId.getValue(300L).depth)
        assertEquals(1, byId.getValue(400L).depth)

        // Link types recorded.
        assertEquals("CALLS", byId.getValue(200L).linkType)
        assertEquals("COVERS", byId.getValue(400L).linkType)

        // Seed always present with full text.
        assertTrue(byId.getValue(100L).text.isNotBlank())
        assertTrue(!byId.getValue(100L).droppedFromBudget)
    }

    @Test
    fun `includeTests=false omits COVERS chunks`() {
        val result = GetImpactRadiusTool().execute(
            GetImpactRadiusTool.Params(
                paths = listOf("src/Target.kt"),
                includeTests = false,
                maxDepth = 2
            )
        )

        assertEquals(0, result.testCount)
        assertTrue(result.chunks.none { it.role == "test" })
        assertTrue(result.chunks.any { it.chunkId == 200L && it.role == "impact" })
    }

    @Test
    fun `maxDepth=1 drops transitive callers`() {
        val result = GetImpactRadiusTool().execute(
            GetImpactRadiusTool.Params(
                paths = listOf("src/Target.kt"),
                maxDepth = 1
            )
        )

        val ids = result.chunks.map { it.chunkId }.toSet()
        assertTrue(200L in ids, "direct caller still present")
        assertTrue(300L !in ids, "transitive caller filtered out at depth 1")
    }

    @Test
    fun `line-range change resolves correct seed`() {
        val result = GetImpactRadiusTool().execute(
            GetImpactRadiusTool.Params(
                changes = listOf(GetImpactRadiusTool.ChangeInput("src/Target.kt", 5, 7)),
                maxDepth = 1
            )
        )
        assertEquals(1, result.seedCount)
        assertTrue(result.chunks.any { it.chunkId == 100L && it.role == "seed" })
    }

    @Test
    fun `line range that hits no chunk returns empty result`() {
        val result = GetImpactRadiusTool().execute(
            GetImpactRadiusTool.Params(
                changes = listOf(GetImpactRadiusTool.ChangeInput("src/Target.kt", 999, 1000))
            )
        )
        assertEquals(0, result.seedCount)
        assertEquals(0, result.chunks.size)
    }

    @Test
    fun `token budget drops impact chunks without touching seed`() {
        // Seed (40) alone fits; budget of 50 lets nothing else in.
        val result = GetImpactRadiusTool().execute(
            GetImpactRadiusTool.Params(
                paths = listOf("src/Target.kt"),
                maxDepth = 2,
                tokenBudget = 50
            )
        )

        // Seed body intact.
        val seed = result.chunks.first { it.chunkId == 100L }
        assertTrue(seed.text.isNotBlank())
        assertTrue(!seed.droppedFromBudget)

        // Impact+test chunks should have been dropped (empty body, droppedFromBudget=true).
        assertTrue(result.droppedDueToBudget >= 2)
        result.chunks.filter { it.role != "seed" }.forEach {
            assertTrue(it.droppedFromBudget, "chunk ${it.chunkId} should be dropped")
            assertEquals("", it.text)
        }
        assertTrue(result.tokensUsed <= 50)
    }

    @Test
    fun `empty input returns empty result`() {
        val result = GetImpactRadiusTool().execute(GetImpactRadiusTool.Params())
        assertEquals(0, result.seedCount)
        assertEquals(0, result.chunks.size)
    }

    @Test
    fun `unknown path returns empty result without error`() {
        val result = GetImpactRadiusTool().execute(
            GetImpactRadiusTool.Params(paths = listOf("nope/Missing.kt"))
        )
        assertEquals(0, result.seedCount)
        assertEquals(0, result.chunks.size)
    }

    // -- helpers --

    private data class Quad(val chunkId: Long, val startLine: Int, val endLine: Int, val tokens: Int)

    private fun seedFile(fileId: Long, relPath: String, absPath: String, chunks: List<Quad>) {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("""
                    INSERT INTO file_state (file_id, rel_path, abs_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                    VALUES ($fileId, '$relPath', '$absPath', 'h$fileId', 1000, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
                """.trimIndent())
            }
            chunks.forEachIndexed { idx, q ->
                conn.createStatement().use { st ->
                    st.execute("""
                        INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, token_count, content, summary, created_at)
                        VALUES (${q.chunkId}, $fileId, $idx, 'CODE_FUNCTION', ${q.startLine}, ${q.endLine}, ${q.tokens}, 'body of chunk ${q.chunkId}', 'sum${q.chunkId}', CURRENT_TIMESTAMP)
                    """.trimIndent())
                }
            }
        }
    }

    private fun insertLink(source: Long, target: Long, type: String, score: Double) {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("""
                    INSERT INTO links (link_id, source_chunk_id, target_file_id, target_chunk_id, link_type, label, score, created_at)
                    VALUES (nextval('links_seq'), $source, 1, $target, '$type', NULL, $score, CURRENT_TIMESTAMP)
                """.trimIndent())
            }
        }
    }
}
