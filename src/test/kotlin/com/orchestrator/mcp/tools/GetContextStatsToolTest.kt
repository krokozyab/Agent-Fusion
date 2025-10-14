package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.EmbeddingConfig
import com.orchestrator.context.config.IndexingConfig
import com.orchestrator.context.config.ProviderConfig
import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.ContextSnippet
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.EmbeddingRepository
import com.orchestrator.context.storage.FileStateRepository
import com.orchestrator.modules.context.ContextMetricsCollector
import com.orchestrator.modules.context.ContextRetrievalModule
import com.orchestrator.modules.context.ContextRetrievalModule.ContextDiagnostics
import com.orchestrator.modules.context.ContextRetrievalModule.TaskContext
import com.orchestrator.domain.AgentId
import com.orchestrator.domain.Task
import com.orchestrator.domain.TaskId
import com.orchestrator.domain.TaskStatus
import com.orchestrator.domain.TaskType
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
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalPathApi::class)
class GetContextStatsToolTest {

    private lateinit var tempDir: Path
    private lateinit var config: ContextConfig
    private val metricsCollector = ContextMetricsCollector()

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("context-stats")
        config = ContextConfig(
            watcher = WatcherConfig(watchPaths = listOf(tempDir.toString()), ignorePatterns = emptyList()),
            indexing = IndexingConfig(
                allowedExtensions = listOf(".kt"),
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
                "git_history" to ProviderConfig(enabled = true, weight = 0.2)
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
    fun `execute returns storage and performance stats`() {
        insertFileState("src/App.kt", language = "kotlin", size = 512)
        insertChunk(100L, fileId = 1L)
        insertEmbedding(chunkId = 100L)

        val task = Task(
            id = TaskId("ctx-1"),
            title = "Collect stats",
            description = "",
            type = TaskType.IMPLEMENTATION,
            status = TaskStatus.IN_PROGRESS,
            routing = com.orchestrator.domain.RoutingStrategy.SOLO,
            createdAt = Instant.now()
        )

        val diagnostics = ContextDiagnostics(
            budget = TokenBudget(maxTokens = 800, reserveForPrompt = 200, diversityWeight = 0.3),
            providerMetrics = emptyMap(),
            duration = Duration.ofMillis(120),
            warnings = emptyList(),
            fallbackUsed = false,
            fallbackProvider = null,
            tokensRequested = 600,
            tokensUsed = 300
        )
        val snippet = ContextSnippet(
            chunkId = 10L,
            score = 0.92,
            filePath = "src/App.kt",
            label = "App",
            kind = ChunkKind.CODE_BLOCK,
            text = "fun main() = Unit",
            language = "kotlin",
            offsets = 1..1,
            metadata = emptyMap()
        )
        val context = TaskContext(task.id.value, listOf(snippet), diagnostics)
        metricsCollector.record(task, AgentId("agent-ctx"), context, Duration.ofMillis(120))

        val tool = GetContextStatsTool(config, metricsCollector)
        val result = tool.execute(GetContextStatsTool.Params(recentLimit = 5))

        assertEquals(2, result.providerStatus.size)
        assertEquals(1, result.storage.files)
        assertEquals(1, result.storage.chunks)
        assertEquals(1, result.storage.embeddings)
        assertEquals(512, result.storage.totalSizeBytes)
        assertTrue(result.languageDistribution.any { it.language == "kotlin" && it.fileCount == 1L })
        assertEquals(1, result.recentActivity.size)

        val performance = result.performance
        assertNotNull(performance)
        assertEquals(1, performance.totalRecords)
        assertTrue(performance.totalContextTokens > 0)
    }

    private fun insertFileState(path: String, language: String?, size: Long) {
        FileStateRepository.insert(
            FileState(
                id = 0,
                relativePath = path,
                contentHash = "hash",
                sizeBytes = size,
                modifiedTimeNs = 0,
                language = language,
                kind = null,
                fingerprint = null,
                indexedAt = Instant.now(),
                isDeleted = false
            )
        )
    }

    private fun insertChunk(chunkId: Long, fileId: Long) {
        ChunkRepository.insert(
            Chunk(
                id = chunkId,
                fileId = fileId,
                ordinal = 0,
                kind = ChunkKind.CODE_BLOCK,
                startLine = 1,
                endLine = 10,
                tokenEstimate = 50,
                content = "content",
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
                st.executeUpdate("DELETE FROM usage_metrics")
                st.executeUpdate("DELETE FROM embeddings")
                st.executeUpdate("DELETE FROM chunks")
                st.executeUpdate("DELETE FROM file_state")
            }
        }
    }
}
