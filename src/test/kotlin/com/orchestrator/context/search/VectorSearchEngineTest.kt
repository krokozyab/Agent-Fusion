package com.orchestrator.context.search

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.Embedding
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.embedding.VectorOps
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.EmbeddingRepository
import com.orchestrator.context.storage.FileStateRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class VectorSearchEngineTest {

    @TempDir
    lateinit var tempDir: Path

    private val engine = VectorSearchEngine()

    @BeforeTest
    fun setUp() {
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
    }

    @AfterTest
    fun tearDown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `returns top K results ordered by similarity`() {
        val chunkA = insertChunk("src/Main.kt", "kotlin", ChunkKind.CODE_FUNCTION, "fun main() = Unit")
        val chunkB = insertChunk("src/util/helpers.py", "python", ChunkKind.CODE_FUNCTION, "def helper(): pass")

        insertEmbedding(chunkA, VectorOps.normalize(floatArrayOf(0.9f, 0.1f, 0f)))
        insertEmbedding(chunkB, VectorOps.normalize(floatArrayOf(0.4f, 0.9f, 0f)))

        val query = floatArrayOf(1f, 0f, 0f)
        val results = engine.search(query, k = 2)

        assertEquals(2, results.size)
        assertEquals("src/Main.kt", results[0].path)
        assertTrue(results[0].score >= results[1].score)
    }

    @Test
    fun `applies language and kind filters`() {
        val kotlinChunk = insertChunk("src/Service.kt", "kotlin", ChunkKind.CODE_CLASS, "class Service")
        val pythonChunk = insertChunk("src/service.py", "python", ChunkKind.CODE_FUNCTION, "def service(): pass")

        insertEmbedding(kotlinChunk, VectorOps.normalize(floatArrayOf(0.7f, 0.7f)))
        insertEmbedding(pythonChunk, VectorOps.normalize(floatArrayOf(0.9f, 0.1f)))

        val filters = VectorSearchEngine.Filters(
            languages = setOf("python"),
            kinds = setOf(ChunkKind.CODE_FUNCTION)
        )

        val results = engine.search(floatArrayOf(1f, 0f), k = 5, filters = filters)

        assertEquals(1, results.size)
        assertEquals("src/service.py", results.single().path)
        assertEquals(ChunkKind.CODE_FUNCTION, results.single().chunk.kind)
    }

    @Test
    fun `filters by paths and skips dimension mismatch`() {
        val target = insertChunk("lib/Target.kt", "kotlin", ChunkKind.CODE_BLOCK, "println(\"hi\")")
        val other = insertChunk("lib/Other.kt", "kotlin", ChunkKind.CODE_BLOCK, "println(\"bye\")")

        insertEmbedding(target, VectorOps.normalize(floatArrayOf(0f, 1f, 0f)))
        // Dimension mismatch embedding should be ignored
        insertEmbedding(other, floatArrayOf(1f, 0f).let { VectorOps.normalize(it) }, dimensionsOverride = 2)

        val filters = VectorSearchEngine.Filters(paths = setOf("lib/Target.kt"))

        val results = engine.search(floatArrayOf(0f, 1f, 0f), k = 3, filters = filters)

        assertEquals(1, results.size)
        assertEquals("lib/Target.kt", results.single().path)
    }

    private fun insertChunk(
        relativePath: String,
        language: String,
        kind: ChunkKind,
        content: String
    ): Chunk {
        val fileState = FileStateRepository.insert(
            FileState(
                id = 0,
                relativePath = relativePath,
                contentHash = "hash-$relativePath",
                sizeBytes = content.length.toLong(),
                modifiedTimeNs = 1,
                language = language,
                kind = "source",
                fingerprint = "fp-$relativePath",
                indexedAt = Instant.now(),
                isDeleted = false
            )
        )

        return ChunkRepository.insert(
            Chunk(
                id = 0,
                fileId = fileState.id,
                ordinal = 0,
                kind = kind,
                startLine = 1,
                endLine = content.lines().size,
                tokenEstimate = content.length / 4,
                content = content,
                summary = "summary",
                createdAt = Instant.now()
            )
        )
    }

    private fun insertEmbedding(
        chunk: Chunk,
        normalizedVector: FloatArray,
        model: String = "default-model",
        dimensionsOverride: Int? = null
    ) {
        val vector = normalizedVector.copyOf()
        val dimensions = dimensionsOverride ?: vector.size
        EmbeddingRepository.insert(
            Embedding(
                id = 0,
                chunkId = chunk.id,
                model = model,
                dimensions = dimensions,
                vector = vector.toList(),
                createdAt = Instant.now()
            )
        )
    }
}
