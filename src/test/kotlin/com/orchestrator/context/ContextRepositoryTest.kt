package com.orchestrator.context

import com.orchestrator.context.ContextRepository.ChunkArtifacts
import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.Embedding
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.Link
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.storage.ContextDatabase
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContextRepositoryTest {

    private val service = ContextDataService()
    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("context-repo-test")
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))
    }

    @AfterTest
    fun tearDown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `sync file artifacts persists chunks embeddings and links`() {
        val fileState = FileState(
            id = 0,
            relativePath = "src/Main.kt",
            absolutePath = tempDir.resolve("src/Main.kt").toString(),
            contentHash = "hash-1",
            sizeBytes = 128,
            modifiedTimeNs = 1000,
            language = "kotlin",
            kind = "source",
            fingerprint = "fp",
            indexedAt = Instant.parse("2024-01-01T00:00:00Z"),
            isDeleted = false
        )

        val chunk = Chunk(
            id = 0,
            fileId = 0,
            ordinal = 0,
            kind = ChunkKind.CODE_FUNCTION,
            startLine = 1,
            endLine = 5,
            tokenEstimate = 40,
            content = "fun main() = Unit",
            summary = "main function",
            createdAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        val embedding = Embedding(
            id = 0,
            chunkId = 0,
            model = "test-model",
            dimensions = 3,
            vector = listOf(0.1f, 0.2f, 0.3f),
            createdAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        val link = Link(
            id = 0,
            sourceChunkId = 0,
            targetFileId = 0,
            targetChunkId = null,
            type = "reference",
            label = "main",
            score = 0.9,
            createdAt = Instant.parse("2024-01-01T00:00:00Z")
        )

        val persisted = service.syncFileArtifacts(fileState, listOf(ChunkArtifacts(chunk, listOf(embedding), listOf(link))))

        assertTrue(persisted.file.id > 0)
        val persistedChunk = persisted.chunks.single().chunk
        assertTrue(persistedChunk.id > 0)
        val persistedEmbedding = persisted.chunks.single().embeddings.single()
        assertEquals(persistedChunk.id, persistedEmbedding.chunkId)
        val persistedLink = persisted.chunks.single().links.single()
        assertEquals(persistedChunk.id, persistedLink.sourceChunkId)

        val fetched = service.loadFileArtifacts("src/Main.kt")
        assertNotNull(fetched)
        assertEquals(persisted.file.id, fetched.file.id)
        assertEquals(1, fetched.chunks.size)
        assertEquals("fun main() = Unit", fetched.chunks.single().chunk.content)
        assertEquals(1, fetched.chunks.single().embeddings.size)
        assertEquals(1, fetched.chunks.single().links.size)
    }

    @Test
    fun `loadChunkReferenceColumns works with DuckDB information schema`() {
        // Create a file with chunks to establish foreign key references
        val fileState = FileState(
            id = 0,
            relativePath = "src/Test.kt",
            absolutePath = tempDir.resolve("src/Test.kt").toString(),
            contentHash = "hash-fk",
            sizeBytes = 100,
            modifiedTimeNs = 3000,
            language = "kotlin",
            kind = "source",
            fingerprint = "fp-fk",
            indexedAt = Instant.parse("2024-01-03T00:00:00Z"),
            isDeleted = false
        )

        val chunk = Chunk(
            id = 0,
            fileId = 0,
            ordinal = 0,
            kind = ChunkKind.CODE_FUNCTION,
            startLine = 1,
            endLine = 5,
            tokenEstimate = 30,
            content = "fun test() = Unit",
            summary = "test function",
            createdAt = Instant.parse("2024-01-03T00:00:00Z")
        )

        val embedding = Embedding(
            id = 0,
            chunkId = 0,
            model = "test-model",
            dimensions = 2,
            vector = listOf(0.5f, 0.5f),
            createdAt = Instant.parse("2024-01-03T00:00:00Z")
        )

        // This should not throw an error about PRAGMA foreign_key_list
        val persisted = service.syncFileArtifacts(
            fileState,
            listOf(ChunkArtifacts(chunk, listOf(embedding), emptyList()))
        )

        assertTrue(persisted.file.id > 0)
        assertTrue(persisted.chunks.isNotEmpty())
    }

    @Test
    fun `build snippets respects scope and token budget`() {
        val fileState = FileState(
            id = 0,
            relativePath = "src/Example.kt",
            absolutePath = tempDir.resolve("src/Example.kt").toString(),
            contentHash = "hash-2",
            sizeBytes = 256,
            modifiedTimeNs = 2000,
            language = "kotlin",
            kind = "source",
            fingerprint = "fp2",
            indexedAt = Instant.parse("2024-01-02T00:00:00Z"),
            isDeleted = false
        )

        val chunks = (0 until 3).map { index ->
            val chunk = Chunk(
                id = 0,
                fileId = 0,
                ordinal = index,
                kind = ChunkKind.PARAGRAPH,
                startLine = index * 10 + 1,
                endLine = index * 10 + 5,
                tokenEstimate = 20,
                content = "paragraph $index",
                summary = "para $index",
                createdAt = Instant.parse("2024-01-02T00:00:00Z")
            )
            ChunkArtifacts(chunk, embeddings = emptyList(), links = emptyList())
        }

        service.syncFileArtifacts(fileState, chunks)

        val scope = ContextScope(paths = listOf("src/Example.kt"))
        val snippets = service.buildSnippets(scope, TokenBudget(maxTokens = 30, reserveForPrompt = 10))

        // budget allows 20 tokens for snippets, so only the first chunk should be included
        assertEquals(1, snippets.size)
        val snippet = snippets.single()
        assertEquals("paragraph 0", snippet.text)
        assertEquals("src/Example.kt", snippet.filePath)
    }
}
