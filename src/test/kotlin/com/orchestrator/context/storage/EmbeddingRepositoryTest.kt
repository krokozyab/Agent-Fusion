package com.orchestrator.context.storage

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.Embedding
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EmbeddingRepositoryTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("embedding-repo-test")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
        // ensure chunk exists for FK
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted) VALUES (1, 'src/Main.kt', 'hash', 10, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)")
                st.execute("INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, token_count, content, summary, created_at) VALUES (1, 1, 0, 'CODE_FUNCTION', 1, 10, 10, 'code', 'main', CURRENT_TIMESTAMP)")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun newEmbedding(id: Long = 0, chunkId: Long = 1, model: String = "model-a", vector: List<Float> = listOf(0.1f, 0.2f, 0.3f)) =
        Embedding(
            id = id,
            chunkId = chunkId,
            model = model,
            dimensions = vector.size,
            vector = vector,
            createdAt = Instant.now()
        )

    @Test
    fun `insert and find`() {
        val inserted = EmbeddingRepository.insert(newEmbedding())
        assertNotNull(inserted.id)

        val fetched = EmbeddingRepository.findByChunkId(inserted.chunkId, inserted.model)
        assertNotNull(fetched)
        assertEquals(inserted.chunkId, fetched.chunkId)
        assertEquals(inserted.vector, fetched.vector)
    }

    @Test
    fun `batch insert and delete`() {
        val embeddings = listOf(
            newEmbedding(model = "model-a"),
            newEmbedding(model = "model-a", vector = listOf(0.5f, 0.6f, 0.7f))
        )
        val persisted = EmbeddingRepository.insertBatch(embeddings)
        assertEquals(2, persisted.size)

        val fetched = EmbeddingRepository.findByChunkIds(listOf(1), "model-a")
        assertEquals(2, fetched.size)

        EmbeddingRepository.deleteByChunkId(1)
        assertNull(EmbeddingRepository.findByChunkId(1, "model-a"))
    }
}
