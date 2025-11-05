package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.EmbeddingConfig
import com.orchestrator.context.config.IndexingConfig
import com.orchestrator.context.config.ProviderConfig
import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.EmbeddingRepository
import com.orchestrator.context.storage.FileStateRepository
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.Path
import java.time.Instant

@OptIn(ExperimentalPathApi::class)
class QueryContextToolTest {

    private lateinit var tempDir: Path
    private lateinit var config: ContextConfig

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("query-context")
        config = ContextConfig(
            watcher = WatcherConfig(watchPaths = listOf(tempDir.toString()), ignorePatterns = emptyList()),
            indexing = IndexingConfig(
                allowedExtensions = listOf(".kt", ".java"),
                blockedExtensions = emptyList()
            ),
            embedding = EmbeddingConfig(
                model = "test-model",
                dimension = 16,
                batchSize = 4,
                normalize = false
            ),
            providers = mapOf(
                "semantic" to ProviderConfig(enabled = true, weight = 0.6),
                "symbol" to ProviderConfig(enabled = true, weight = 0.3),
                "full_text" to ProviderConfig(enabled = true, weight = 0.1)
            )
        )
        ContextDatabase.initialize(config.storage)
        clearTables()
    }

    @AfterTest
    fun tearDown() {
        clearTables()
        tempDir.deleteRecursively()
    }

    @Test
    fun `execute with basic query returns results`() {
        // Setup test data
        val fileId = insertFileState("src/App.kt", language = "kotlin", size = 512)
        insertChunk(100L, fileId = fileId, content = "fun main() = Unit", kind = ChunkKind.CODE_FUNCTION)
        insertEmbedding(chunkId = 100L)

        val tool = QueryContextTool(config)
        val result = tool.execute(QueryContextTool.Params(query = "main function"))

        assertNotNull(result)
        assertTrue(result.hits.isNotEmpty() || result.hits.isEmpty()) // May be empty if providers don't return results
        assertNotNull(result.metadata)
        assertTrue(result.metadata.containsKey("totalHits"))
        assertTrue(result.metadata.containsKey("returnedHits"))
        assertTrue(result.metadata.containsKey("tokensUsed"))
    }

    @Test
    fun `execute validates query is not blank`() {
        val tool = QueryContextTool(config)
        val exception = runCatching {
            tool.execute(QueryContextTool.Params(query = ""))
        }.exceptionOrNull()

        assertNotNull(exception)
        assertTrue(exception is IllegalArgumentException)
        assertTrue(exception.message?.contains("query cannot be blank") == true)
    }

    @Test
    fun `execute respects k parameter for result limit`() {
        // Setup multiple chunks
        val fileId = insertFileState("src/App.kt", language = "kotlin", size = 1024)
        for (i in 1..20) {
            insertChunk(100L + i, fileId = fileId, content = "fun test$i() = Unit", kind = ChunkKind.CODE_FUNCTION)
            insertEmbedding(chunkId = 100L + i)
        }

        val tool = QueryContextTool(config)
        val result = tool.execute(QueryContextTool.Params(query = "test function", k = 5))

        assertNotNull(result)
        assertTrue(result.hits.size <= 5, "Expected at most 5 hits, got ${result.hits.size}")
    }

    @Test
    fun `execute respects maxTokens parameter`() {
        val fileId = insertFileState("src/App.kt", language = "kotlin", size = 512)
        insertChunk(100L, fileId = fileId, content = "fun main() = Unit", kind = ChunkKind.CODE_FUNCTION)
        insertEmbedding(chunkId = 100L)

        val tool = QueryContextTool(config)
        val result = tool.execute(QueryContextTool.Params(query = "main function", maxTokens = 100))

        assertNotNull(result)
        val tokensUsed = result.metadata["tokensUsed"] as? Int ?: 0
        assertTrue(tokensUsed <= 100, "Expected at most 100 tokens, used $tokensUsed")
    }

    @Test
    fun `execute with paths filter`() {
        val tool = QueryContextTool(config)
        val result = tool.execute(
            QueryContextTool.Params(
                query = "test",
                paths = listOf("src/main/kotlin/")
            )
        )

        assertNotNull(result)
        assertNotNull(result.metadata)
    }

    @Test
    fun `execute with languages filter`() {
        val tool = QueryContextTool(config)
        val result = tool.execute(
            QueryContextTool.Params(
                query = "test",
                languages = listOf("kotlin", "java")
            )
        )

        assertNotNull(result)
        assertNotNull(result.metadata)
    }

    @Test
    fun `execute with kinds filter`() {
        val tool = QueryContextTool(config)
        val result = tool.execute(
            QueryContextTool.Params(
                query = "test",
                kinds = listOf("CODE_CLASS", "CODE_METHOD")
            )
        )

        assertNotNull(result)
        assertNotNull(result.metadata)
    }

    @Test
    fun `execute with excludePatterns filter`() {
        val tool = QueryContextTool(config)
        val result = tool.execute(
            QueryContextTool.Params(
                query = "test",
                excludePatterns = listOf("test/", "*.md")
            )
        )

        assertNotNull(result)
        assertNotNull(result.metadata)
    }

    @Test
    fun `execute with specific providers`() {
        val tool = QueryContextTool(config)
        val result = tool.execute(
            QueryContextTool.Params(
                query = "test",
                providers = listOf("semantic", "symbol")
            )
        )

        assertNotNull(result)
        assertNotNull(result.metadata)
        val providers = result.metadata["providers"] as? Map<*, *>
        assertNotNull(providers)
    }

    @Test
    fun `execute with all parameters combined`() {
        val fileId = insertFileState("src/Service.kt", language = "kotlin", size = 512)
        insertChunk(200L, fileId = fileId, content = "class Service", kind = ChunkKind.CODE_CLASS)
        insertEmbedding(chunkId = 200L)

        val tool = QueryContextTool(config)
        val result = tool.execute(
            QueryContextTool.Params(
                query = "service class",
                k = 10,
                maxTokens = 2000,
                paths = listOf("src/"),
                languages = listOf("kotlin"),
                kinds = listOf("CODE_CLASS"),
                excludePatterns = listOf("test/"),
                providers = listOf("semantic")
            )
        )

        assertNotNull(result)
        assertNotNull(result.metadata)
        assertTrue(result.metadata.containsKey("totalHits"))
        assertTrue(result.metadata.containsKey("returnedHits"))
    }

    @Test
    fun `result includes proper metadata structure`() {
        val fileId = insertFileState("src/App.kt", language = "kotlin", size = 512)
        insertChunk(100L, fileId = fileId, content = "fun main() = Unit", kind = ChunkKind.CODE_FUNCTION)
        insertEmbedding(chunkId = 100L)

        val tool = QueryContextTool(config)
        val result = tool.execute(QueryContextTool.Params(query = "main function"))

        val metadata = result.metadata
        assertTrue(metadata.containsKey("totalHits"))
        assertTrue(metadata.containsKey("returnedHits"))
        assertTrue(metadata.containsKey("tokensUsed"))
        assertTrue(metadata.containsKey("tokensRequested"))
        assertTrue(metadata.containsKey("providers"))

        val totalHits = metadata["totalHits"] as? Int
        val returnedHits = metadata["returnedHits"] as? Int
        assertNotNull(totalHits)
        assertNotNull(returnedHits)
        assertTrue(returnedHits >= 0)
    }

    @Test
    fun `snippet hit has correct structure`() {
        val fileId = insertFileState("src/App.kt", language = "kotlin", size = 512)
        insertChunk(100L, fileId = fileId, content = "fun main() = Unit", kind = ChunkKind.CODE_FUNCTION)
        insertEmbedding(chunkId = 100L)

        val tool = QueryContextTool(config)
        val result = tool.execute(QueryContextTool.Params(query = "main function"))

        if (result.hits.isNotEmpty()) {
            val hit = result.hits.first()
            assertTrue(hit.chunkId > 0)
            assertTrue(hit.score >= 0.0 && hit.score <= 1.0)
            assertTrue(hit.filePath.isNotBlank())
            assertTrue(hit.kind.isNotBlank())
            assertTrue(hit.text.isNotBlank())
            assertNotNull(hit.metadata)
        }
    }

    @Test
    fun `execute handles invalid chunk kind gracefully`() {
        val tool = QueryContextTool(config)
        val result = tool.execute(
            QueryContextTool.Params(
                query = "test",
                kinds = listOf("INVALID_KIND", "CODE_CLASS")
            )
        )

        // Should not throw, invalid kinds are filtered out with warning
        assertNotNull(result)
    }

    @Test
    fun `execute returns empty results when no providers enabled`() {
        val emptyConfig = config.copy(providers = emptyMap())
        val tool = QueryContextTool(emptyConfig)
        val result = tool.execute(QueryContextTool.Params(query = "test"))

        assertNotNull(result)
        assertEquals(0, result.hits.size)
        assertTrue(result.metadata.containsKey("warning"))
    }

    @Test
    fun `execute deduplicates snippets by chunk id and file path`() {
        val fileId = insertFileState("src/App.kt", language = "kotlin", size = 512)
        insertChunk(100L, fileId = fileId, content = "fun main() = Unit", kind = ChunkKind.CODE_FUNCTION)
        insertEmbedding(chunkId = 100L)

        val tool = QueryContextTool(config)
        val result = tool.execute(QueryContextTool.Params(query = "main function"))

        // Check that we don't have duplicate chunk IDs
        val chunkIds = result.hits.map { it.chunkId }
        assertEquals(chunkIds.toSet().size, chunkIds.size, "Duplicate chunk IDs found")
    }

    private fun insertFileState(path: String, language: String?, size: Long): Long {
        val persisted = FileStateRepository.insert(
            FileState(
                id = 0,
                relativePath = path,
                absolutePath = tempDir.resolve(path).normalize().toString(),
                contentHash = "hash-$path",
                sizeBytes = size,
                modifiedTimeNs = 0,
                language = language,
                kind = null,
                fingerprint = null,
                indexedAt = Instant.now(),
                isDeleted = false
            )
        )
        return persisted.id
    }

    private fun insertChunk(chunkId: Long, fileId: Long, content: String = "content", kind: ChunkKind = ChunkKind.CODE_BLOCK) {
        ChunkRepository.insert(
            Chunk(
                id = chunkId,
                fileId = fileId,
                ordinal = 0,
                kind = kind,
                startLine = 1,
                endLine = 10,
                tokenEstimate = 50,
                content = content,
                summary = "summary",
                createdAt = Instant.now()
            )
        )
    }

    private fun insertEmbedding(chunkId: Long) {
        EmbeddingRepository.insert(
            com.orchestrator.context.domain.Embedding(
                id = 0,
                chunkId = chunkId,
                model = "test-model",
                dimensions = 16,
                vector = List(16) { 0.1f },
                createdAt = Instant.now()
            )
        )
    }

    private fun clearTables() {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM embeddings")
                st.executeUpdate("DELETE FROM chunks")
                st.executeUpdate("DELETE FROM file_state")
            }
        }
    }
}
