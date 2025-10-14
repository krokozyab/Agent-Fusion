package com.orchestrator.context.providers

import com.orchestrator.context.domain.*
import com.orchestrator.context.embedding.Embedder
import com.orchestrator.context.search.MmrReranker
import com.orchestrator.context.search.SearchResult
import com.orchestrator.context.search.VectorSearchEngine
import com.orchestrator.context.storage.ContextDatabase
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import kotlin.test.assertEquals

class SemanticContextProviderTest {

    private lateinit var embedder: Embedder
    private lateinit var searchEngine: VectorSearchEngine
    private lateinit var reranker: MmrReranker
    private lateinit var provider: SemanticContextProvider

    @BeforeEach
    fun setUp() {
        embedder = mockk()
        searchEngine = mockk()
        reranker = mockk()
        provider = SemanticContextProvider(embedder, searchEngine, reranker)

        // Mock ContextDatabase for database operations
        mockkObject(ContextDatabase)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(ContextDatabase)
    }

    @Test
    fun `getContext should return context snippets`() = runBlocking {
        // Given
        val id: Long = 1
        val fileId: Long = 1
        val ordinal: Int = 1
        val kind: ChunkKind = ChunkKind.CODE_FUNCTION
        val startLine: Int = 1
        val endLine: Int = 1
        val tokenEstimate: Int = 10
        val content: String = "fun main() {}"
        val summary: String? = "main function"
        val createdAt: Instant = Instant.parse("2023-01-01T00:00:00Z")

        val chunk = try {
            Chunk(
                id = id,
                fileId = fileId,
                ordinal = ordinal,
                kind = kind,
                startLine = startLine,
                endLine = endLine,
                tokenEstimate = tokenEstimate,
                content = content,
                summary = summary,
                createdAt = createdAt
            )
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
            throw e
        }

        val query = "test query"
        val scope = ContextScope()
        val budget = TokenBudget(1000)
        val queryVector = floatArrayOf(1.0f, 2.0f, 3.0f)

        val searchResults = listOf(
            SearchResult(
                chunk = chunk,
                score = 0.9f,
                embeddingId = 1
            )
        )

        coEvery { embedder.embed(query) } returns queryVector
        every { embedder.getModel() } returns "test-model"
        every { searchEngine.search(any(), any(), any()) } returns searchResults
        every { reranker.rerank(any(), any(), any()) } returns searchResults

        // Mock database operations for fetchFileMetadata
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }

        every { mockConnection.prepareStatement(any<String>()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet
        every { mockResultSet.next() } returns true
        every { mockResultSet.getString("rel_path") } returns "src/main.kt"
        every { mockResultSet.getString("language") } returns "kotlin"

        // When
        val snippets = provider.getContext(query, scope, budget)

        // Then
        assertEquals(1, snippets.size)
        assertEquals(chunk.id, snippets[0].chunkId)
        assertEquals(0.9, snippets[0].score, 0.001)
        assertEquals("src/main.kt", snippets[0].filePath)
        assertEquals("kotlin", snippets[0].language)
        assertEquals(chunk.summary, snippets[0].label)
    }
}
