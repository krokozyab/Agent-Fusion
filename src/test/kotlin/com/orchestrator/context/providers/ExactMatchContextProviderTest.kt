package com.orchestrator.context.providers

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.EmbeddingConfig
import com.orchestrator.context.config.IndexingConfig
import com.orchestrator.context.config.ProviderConfig
import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.FileStateRepository
import kotlinx.coroutines.runBlocking
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Path
import java.time.Instant

@OptIn(ExperimentalPathApi::class)
class ExactMatchContextProviderTest {

    private lateinit var tempDir: Path
    private val provider = ExactMatchContextProvider()

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("exact-match-test")
        val config = ContextConfig(
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
            storage = com.orchestrator.context.config.StorageConfig(
                dbPath = tempDir.resolve("context.duckdb").toString()
            ),
            providers = mapOf(
                "exact_match" to ProviderConfig(enabled = true, weight = 0.15)
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

    // -- extractLiteralPatterns tests --

    @Test
    fun `extractLiteralPatterns preserves at-signs and hyphens`() {
        val patterns = provider.extractLiteralPatterns("@Sergey INT-1434")
        assertEquals(1, patterns.size)
        assertEquals("@Sergey INT-1434", patterns[0])
    }

    @Test
    fun `extractLiteralPatterns handles quoted phrases`() {
        val patterns = provider.extractLiteralPatterns("\"@Sergey Rudenko\" bug report")
        assertEquals(2, patterns.size)
        assertTrue(patterns.contains("@Sergey Rudenko"))
        assertTrue(patterns.contains("bug report"))
    }

    @Test
    fun `extractLiteralPatterns preserves UUIDs`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val patterns = provider.extractLiteralPatterns(uuid)
        assertEquals(1, patterns.size)
        assertEquals(uuid, patterns[0])
    }

    @Test
    fun `extractLiteralPatterns returns empty for blank query`() {
        assertTrue(provider.extractLiteralPatterns("").isEmpty())
        assertTrue(provider.extractLiteralPatterns("   ").isEmpty())
    }

    @Test
    fun `extractLiteralPatterns handles multiple quoted phrases`() {
        val patterns = provider.extractLiteralPatterns("\"INT-1434\" \"@Sergey Rudenko\"")
        assertEquals(2, patterns.size)
        assertTrue(patterns.contains("INT-1434"))
        assertTrue(patterns.contains("@Sergey Rudenko"))
    }

    @Test
    fun `extractLiteralPatterns preserves dots and colons`() {
        val patterns = provider.extractLiteralPatterns("com.orchestrator.context:main")
        assertEquals(1, patterns.size)
        assertEquals("com.orchestrator.context:main", patterns[0])
    }

    // -- getContext tests --

    @Test
    fun `getContext returns empty for blank query`() = runBlocking {
        val result = provider.getContext("", ContextScope(), TokenBudget(maxTokens = 4000))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getContext searches database with literal pattern`() = runBlocking {
        val fileId = insertFileState("src/App.kt", language = "kotlin", size = 512)
        insertChunk(100L, fileId = fileId, content = "Assigned to @Sergey Rudenko for review of INT-1434")

        val result = provider.getContext("@Sergey", ContextScope(), TokenBudget(maxTokens = 4000))
        assertTrue(result.isNotEmpty(), "Expected at least one result for @Sergey")
        assertTrue(result.any { it.chunkId == 100L })
    }

    @Test
    fun `getContext finds ticket IDs that full_text would miss`() = runBlocking {
        val fileId = insertFileState("src/Tracker.kt", language = "kotlin", size = 512)
        insertChunk(101L, fileId = fileId, content = "Fixed issue INT-1434: null pointer in login flow")

        val result = provider.getContext("INT-1434", ContextScope(), TokenBudget(maxTokens = 4000))
        assertTrue(result.isNotEmpty(), "Expected at least one result for INT-1434")
        assertEquals(101L, result.first().chunkId)
    }

    @Test
    fun `getContext finds UUIDs`() = runBlocking {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val fileId = insertFileState("src/Config.kt", language = "kotlin", size = 512)
        insertChunk(102L, fileId = fileId, content = "val sessionId = \"$uuid\"")

        val result = provider.getContext(uuid, ContextScope(), TokenBudget(maxTokens = 4000))
        assertTrue(result.isNotEmpty(), "Expected at least one result for UUID")
    }

    @Test
    fun `getContext respects scope path filter`() = runBlocking {
        val absPath = tempDir.resolve("src/main/App.kt").normalize().toString()
        val fileId = insertFileStateAbsolute("src/main/App.kt", absPath, "kotlin")
        insertChunk(103L, fileId = fileId, content = "Assigned to @Sergey for INT-1434")

        val absPath2 = tempDir.resolve("test/AppTest.kt").normalize().toString()
        val fileId2 = insertFileStateAbsolute("test/AppTest.kt", absPath2, "kotlin")
        insertChunk(104L, fileId = fileId2, content = "Assigned to @Sergey for INT-1434 in test")

        // Scope to only src/main
        val scope = ContextScope(paths = listOf(absPath.substringBeforeLast("/")))
        val result = provider.getContext("@Sergey", scope, TokenBudget(maxTokens = 4000))
        assertTrue(result.all { it.filePath.contains("src/main") })
    }

    @Test
    fun `getContext penalizes short content`() = runBlocking {
        val fileId = insertFileState("src/Short.kt", language = "kotlin", size = 64)
        insertChunk(105L, fileId = fileId, content = "@Sergey")  // Very short

        val fileId2 = insertFileState("src/Long.kt", language = "kotlin", size = 512)
        insertChunk(106L, fileId = fileId2, content = "This is a longer piece of content where @Sergey is mentioned in a meaningful context with surrounding text")

        val result = provider.getContext("@Sergey", ContextScope(), TokenBudget(maxTokens = 4000))
        assertTrue(result.size >= 2)
        // Long content should score higher
        val shortScore = result.first { it.chunkId == 105L }.score
        val longScore = result.first { it.chunkId == 106L }.score
        assertTrue(longScore > shortScore, "Long content ($longScore) should score higher than short ($shortScore)")
    }

    // -- helpers --

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

    private fun insertFileStateAbsolute(relativePath: String, absolutePath: String, language: String?): Long {
        val persisted = FileStateRepository.insert(
            FileState(
                id = 0,
                relativePath = relativePath,
                absolutePath = absolutePath,
                contentHash = "hash-$relativePath",
                sizeBytes = 256,
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

    private fun insertChunk(chunkId: Long, fileId: Long, content: String, kind: ChunkKind = ChunkKind.CODE_BLOCK) {
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

    private fun clearTables() {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("DELETE FROM links")
                st.executeUpdate("DELETE FROM symbols")
                st.executeUpdate("DELETE FROM embeddings")
                st.executeUpdate("DELETE FROM chunks")
                st.executeUpdate("DELETE FROM file_state")
            }
        }
    }
}
