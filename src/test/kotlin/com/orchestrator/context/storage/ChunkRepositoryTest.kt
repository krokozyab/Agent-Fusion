package com.orchestrator.context.storage

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkRepositoryTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("chunk-repo-test")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
        // ensure parent file exists in file_state for FK
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                    VALUES (1, 'src/Main.kt', 'hash', 10, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
                    """.trimIndent()
                )
            }
        }
    }

    @AfterEach
    fun tearDown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun newChunk(id: Long = 0, fileId: Long = 1, ordinal: Int = 0, kind: ChunkKind = ChunkKind.CODE_FUNCTION, label: String? = "main") =
        Chunk(
            id = id,
            fileId = fileId,
            ordinal = ordinal,
            kind = kind,
            startLine = 1 + ordinal,
            endLine = 5 + ordinal,
            tokenEstimate = 10,
            content = "chunk-$ordinal",
            summary = label,
            createdAt = Instant.now()
        )

    @Test
    fun `insert and find by id`() {
        val inserted = ChunkRepository.insert(newChunk())
        assertTrue(inserted.id > 0)

        val fetched = ChunkRepository.findById(inserted.id)
        assertEquals(inserted.copy(createdAt = fetched!!.createdAt), fetched) // allow timestamp precision
    }

    @Test
    fun `batch insert and find by file`() {
        val batch = (0 until 3).map { idx -> newChunk(ordinal = idx, label = "label-$idx") }
        val persisted = ChunkRepository.insertBatch(batch)
        assertEquals(3, persisted.size)

        val byFile = ChunkRepository.findByFileId(1)
        assertEquals(3, byFile.size)
        assertEquals(listOf(0,1,2), byFile.map { it.ordinal })
    }

    @Test
    fun `find by kind and label`() {
        val a = ChunkRepository.insert(newChunk(kind = ChunkKind.PARAGRAPH, label = "intro"))
        ChunkRepository.insert(newChunk(kind = ChunkKind.CODE_FUNCTION, ordinal = 1, label = "intro"))

        val paragraphs = ChunkRepository.findByKind(ChunkKind.PARAGRAPH)
        assertEquals(1, paragraphs.size)
        assertEquals(a.id, paragraphs.single().id)

        val matches = ChunkRepository.findByLabel("intro")
        assertEquals(2, matches.size)
    }

    @Test
    fun `delete by file removes rows`() {
        ChunkRepository.insertBatch((0 until 2).map { newChunk(ordinal = it) })
        assertEquals(2, ChunkRepository.count())

        ChunkRepository.deleteByFileId(1)
        assertEquals(0, ChunkRepository.count())
    }
}
