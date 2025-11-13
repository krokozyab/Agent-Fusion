package com.orchestrator.context.search

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NeighborExpanderTest {

    private val expander = NeighborExpander()
    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("neighbor-test")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
        // Create file_state entry for FK
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    INSERT INTO file_state (file_id, rel_path, abs_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                    VALUES (1, 'test/file.kt', '/test/file.kt', 'hash', 1000, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
                    """.trimIndent()
                )
            }
        }
    }

    @AfterEach
    fun teardown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `returns original snippets when window is 0`() {
        val snippets = listOf(
            createSnippet(1, 0.9, mapOf("file_id" to "1", "ordinal" to "0"))
        )

        val result = expander.expand(snippets, window = 0)

        assertEquals(1, result.size)
        assertEquals(1L, result[0].chunkId)
    }

    @Test
    fun `expands with neighbors when window is 1`() {
        val fileId = 1L
        val chunks = listOf(
            Chunk(0, fileId, 0, ChunkKind.CODE_FUNCTION, 1, 10, 50, "chunk 0", null, Instant.now()),
            Chunk(0, fileId, 1, ChunkKind.CODE_FUNCTION, 11, 20, 50, "chunk 1", null, Instant.now()),
            Chunk(0, fileId, 2, ChunkKind.CODE_FUNCTION, 21, 30, 50, "chunk 2", null, Instant.now())
        )
        val inserted = ChunkRepository.insertBatch(chunks)

        // Create snippet for middle chunk
        val snippet = ContextSnippet(
            chunkId = inserted[1].id,
            score = 0.9,
            filePath = "/test/file.kt",
            label = null,
            kind = ChunkKind.CODE_FUNCTION,
            text = "chunk 1",
            language = "kotlin",
            offsets = 11..20,
            metadata = mapOf(
                "file_id" to fileId.toString(),
                "ordinal" to "1"
            )
        )

        val result = expander.expand(listOf(snippet), window = 1)

        // Should include original + 2 neighbors
        assertEquals(3, result.size)
        assertTrue(result.any { it.chunkId == inserted[0].id })
        assertTrue(result.any { it.chunkId == inserted[1].id })
        assertTrue(result.any { it.chunkId == inserted[2].id })
    }

    @Test
    fun `neighbors have reduced score`() {
        val fileId = 1L
        val chunks = listOf(
            Chunk(0, fileId, 0, ChunkKind.CODE_FUNCTION, 1, 10, 50, "chunk 0", null, Instant.now()),
            Chunk(0, fileId, 1, ChunkKind.CODE_FUNCTION, 11, 20, 50, "chunk 1", null, Instant.now())
        )
        val inserted = ChunkRepository.insertBatch(chunks)

        val snippet = ContextSnippet(
            chunkId = inserted[1].id,
            score = 0.8,
            filePath = "/test/file.kt",
            label = null,
            kind = ChunkKind.CODE_FUNCTION,
            text = "chunk 1",
            language = "kotlin",
            offsets = 11..20,
            metadata = mapOf(
                "file_id" to fileId.toString(),
                "ordinal" to "1"
            )
        )

        val result = expander.expand(listOf(snippet), window = 1)

        val neighbor = result.find { it.chunkId == inserted[0].id }
        assertEquals(0.4, neighbor?.score) // 0.8 * 0.5
    }

    @Test
    fun `deduplicates overlapping neighbors`() {
        val fileId = 1L
        val chunks = listOf(
            Chunk(0, fileId, 0, ChunkKind.CODE_FUNCTION, 1, 10, 50, "chunk 0", null, Instant.now()),
            Chunk(0, fileId, 1, ChunkKind.CODE_FUNCTION, 11, 20, 50, "chunk 1", null, Instant.now()),
            Chunk(0, fileId, 2, ChunkKind.CODE_FUNCTION, 21, 30, 50, "chunk 2", null, Instant.now())
        )
        val inserted = ChunkRepository.insertBatch(chunks)

        // Two snippets that are neighbors
        val snippets = listOf(
            ContextSnippet(
                chunkId = inserted[0].id,
                score = 0.9,
                filePath = "/test/file.kt",
                label = null,
                kind = ChunkKind.CODE_FUNCTION,
                text = "chunk 0",
                language = "kotlin",
                offsets = 1..10,
                metadata = mapOf("file_id" to fileId.toString(), "ordinal" to "0")
            ),
            ContextSnippet(
                chunkId = inserted[2].id,
                score = 0.8,
                filePath = "/test/file.kt",
                label = null,
                kind = ChunkKind.CODE_FUNCTION,
                text = "chunk 2",
                language = "kotlin",
                offsets = 21..30,
                metadata = mapOf("file_id" to fileId.toString(), "ordinal" to "2")
            )
        )

        val result = expander.expand(snippets, window = 1)

        // Should have all 3 chunks, but chunk 1 should appear only once
        assertEquals(3, result.size)
        assertEquals(1, result.count { it.chunkId == inserted[1].id })
    }

    private fun createSnippet(chunkId: Long, score: Double, metadata: Map<String, String>) =
        ContextSnippet(
            chunkId = chunkId,
            score = score,
            filePath = "/test/file.kt",
            label = null,
            kind = ChunkKind.CODE_FUNCTION,
            text = "test content",
            language = "kotlin",
            offsets = 1..10,
            metadata = metadata
        )
}
