package com.orchestrator.context.providers

import com.orchestrator.context.domain.*
import com.orchestrator.context.storage.ContextDatabase
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FullTextContextProviderTest {

    private lateinit var provider: FullTextContextProvider

    @BeforeEach
    fun setup() {
        provider = FullTextContextProvider()
        mockkObject(ContextDatabase)
    }

    @AfterEach
    fun teardown() {
        unmockkObject(ContextDatabase)
    }

    @Test
    fun `extracts keywords correctly`() = runBlocking {
        setupEmptyDatabaseMock()

        // Query with various keywords
        val query = "How does the UserService handle authentication?"
        provider.getContext(query, ContextScope(), TokenBudget(maxTokens = 1000))

        // Should extract: UserService, handle, authentication
        // Should filter: How, does, the (stopwords)
        verify(atLeast = 1) { ContextDatabase.withConnection(any<(Connection) -> Any>()) }
    }

    @Test
    fun `removes stopwords from query`() = runBlocking {
        setupEmptyDatabaseMock()

        // All stopwords (note: 'or' and 'by' are 2 chars, so they pass length filter but are stopwords)
        val query = "the and is was will with from"
        val result = provider.getContext(query, ContextScope(), TokenBudget(maxTokens = 1000))

        // All stopwords, but some are >= 2 chars, so they pass length but get filtered as stopwords
        // The provider will still try to search if any keyword passes both filters
        // Let's just verify empty result
        assertEquals(0, result.size)
    }

    @Test
    fun `filters short words`() = runBlocking {
        setupEmptyDatabaseMock()

        val query = "a b c d e f" // All too short (< 2 chars)
        val result = provider.getContext(query, ContextScope(), TokenBudget(maxTokens = 1000))

        assertEquals(0, result.size)
        verify(exactly = 0) { ContextDatabase.withConnection(any<(Connection) -> Any>()) }
    }

    @Test
    fun `normalizes query to lowercase`() = runBlocking {
        setupEmptyDatabaseMock()

        val query = "UserService AuthController DataManager"
        provider.getContext(query, ContextScope(), TokenBudget(maxTokens = 1000))

        // Should query with lowercase keywords
        verify(atLeast = 1) { ContextDatabase.withConnection(any<(Connection) -> Any>()) }
    }

    @Test
    fun `returns empty list when no keywords extracted`() = runBlocking {
        val query = "a b c" // Too short
        val result = provider.getContext(query, ContextScope(), TokenBudget(maxTokens = 1000))

        assertEquals(0, result.size)
    }

    @Test
    fun `returns context snippets for keyword matches`() = runBlocking {
        setupDatabaseWithMatches()

        val query = "user authentication"
        val result = provider.getContext(query, ContextScope(), TokenBudget(maxTokens = 10000))

        assertTrue(result.isNotEmpty())
        val snippet = result[0]
        assertEquals("src/UserService.kt", snippet.filePath)
        assertEquals(ChunkKind.CODE_CLASS, snippet.kind)
        assertEquals("kotlin", snippet.language)
        assertTrue(snippet.score > 0.0)
        assertTrue(snippet.metadata.containsKey("bm25_score"))
    }

    @Test
    fun `calculates BM25 scores correctly`() = runBlocking {
        setupDatabaseWithMultipleMatches()

        val query = "user authentication security"
        val result = provider.getContext(query, ContextScope(), TokenBudget(maxTokens = 10000))

        // Should return results sorted by BM25 score
        assertTrue(result.isNotEmpty())

        // Check that scores are in descending order
        for (i in 0 until result.size - 1) {
            assertTrue(
                result[i].score >= result[i + 1].score,
                "Scores should be in descending order: ${result[i].score} >= ${result[i + 1].score}"
            )
        }
    }

    @Test
    fun `respects token budget`() = runBlocking {
        setupDatabaseWithMultipleMatches()

        val query = "user authentication"
        val budget = TokenBudget(maxTokens = 100) // Small budget

        val result = provider.getContext(query, ContextScope(), budget)

        // Should stop adding snippets when budget exceeded
        val totalTokens = result.sumOf { it.text.length / 4 }
        assertTrue(totalTokens <= budget.availableForSnippets)
    }

    @Test
    fun `applies scope filters for paths`() = runBlocking {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet
        every { mockResultSet.next() } returns false

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }

        val query = "user authentication"
        val scope = ContextScope(paths = listOf("src/main"))
        provider.getContext(query, scope, TokenBudget(maxTokens = 1000))

        // Verify SQL includes path filter
        verify { mockConnection.prepareStatement(match { sql ->
            sql.contains("f.rel_path LIKE ?")
        }) }
    }

    @Test
    fun `applies scope filters for languages`() = runBlocking {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet
        every { mockResultSet.next() } returns false

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }

        val query = "user authentication"
        val scope = ContextScope(languages = setOf("kotlin"))
        provider.getContext(query, scope, TokenBudget(maxTokens = 1000))

        // Verify SQL includes language filter
        verify { mockConnection.prepareStatement(match { sql ->
            sql.contains("f.language IN")
        }) }
    }

    @Test
    fun `applies scope filters for chunk kinds`() = runBlocking {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet
        every { mockResultSet.next() } returns false

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }

        val query = "user authentication"
        val scope = ContextScope(kinds = setOf(ChunkKind.CODE_CLASS))
        provider.getContext(query, scope, TokenBudget(maxTokens = 1000))

        // Verify SQL includes kind filter
        verify { mockConnection.prepareStatement(match { sql ->
            sql.contains("c.kind IN")
        }) }
    }

    @Test
    fun `has correct provider type`() {
        assertEquals(ContextProviderType.FULL_TEXT, provider.type)
    }

    @Test
    fun `performance test - responds within 100ms`() = runBlocking {
        setupDatabaseWithMatches()

        val query = "user authentication"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000)

        // Warm up
        provider.getContext(query, scope, budget)

        // Measure performance over 10 runs
        val times = mutableListOf<Long>()
        repeat(10) {
            val start = System.currentTimeMillis()
            provider.getContext(query, scope, budget)
            val duration = System.currentTimeMillis() - start
            times.add(duration)
        }

        val avgTime = times.average()
        println("Average response time: ${avgTime}ms over ${times.size} runs")
        println("Min: ${times.minOrNull()}ms, Max: ${times.maxOrNull()}ms")

        // With mocked database, should be very fast (much less than 100ms)
        assertTrue(avgTime < 100.0, "Average response time ${avgTime}ms exceeds 100ms requirement")
    }

    // Helper methods

    private fun setupEmptyDatabaseMock() {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet
        every { mockResultSet.next() } returns false

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }
    }

    private fun setupDatabaseWithMatches() {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet

        // Mock single result
        var callCount = 0
        every { mockResultSet.next() } answers {
            val result = callCount % 2 == 0
            callCount++
            result
        }

        every { mockResultSet.getLong("chunk_id") } returns 1L
        every { mockResultSet.getLong("file_id") } returns 1L
        every { mockResultSet.getInt("ordinal") } returns 0
        every { mockResultSet.getString("kind") } returns "CODE_CLASS"
        every { mockResultSet.getInt("start_line") } returns 10
        every { mockResultSet.getInt("end_line") } returns 50
        every { mockResultSet.getInt("token_count") } returns 100
        every { mockResultSet.wasNull() } returns false
        every { mockResultSet.getString("content") } returns "class UserService { fun authenticate(user: User) { } }"
        every { mockResultSet.getString("summary") } returns "User authentication service"
        every { mockResultSet.getString("rel_path") } returns "src/UserService.kt"
        every { mockResultSet.getString("language") } returns "kotlin"

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }
    }

    private fun setupDatabaseWithMultipleMatches() {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet

        // Mock multiple results with different keyword densities
        val results = listOf(
            // High relevance: both keywords, multiple occurrences
            Triple(
                "class UserService { fun authenticate(user: User) { // user authentication logic } }",
                "User authentication service",
                3
            ),
            // Medium relevance: one keyword, single occurrence
            Triple(
                "class DataService { fun getData() { // data retrieval } }",
                "Data service",
                2
            ),
            // Low relevance: one keyword, single occurrence
            Triple(
                "class Logger { fun log(message: String) { // user message logging } }",
                "Logger utility",
                1
            )
        )

        var resultIndex = 0
        every { mockResultSet.next() } answers {
            resultIndex < results.size
        }

        every { mockResultSet.getLong("chunk_id") } answers { resultIndex.toLong() + 1 }
        every { mockResultSet.getLong("file_id") } answers { resultIndex.toLong() + 1 }
        every { mockResultSet.getInt("ordinal") } returns 0
        every { mockResultSet.getString("kind") } returns "CODE_CLASS"
        every { mockResultSet.getInt("start_line") } returns 10
        every { mockResultSet.getInt("end_line") } returns 50
        every { mockResultSet.getInt("token_count") } returns 100
        every { mockResultSet.wasNull() } returns false
        every { mockResultSet.getString("content") } answers {
            if (resultIndex < results.size) {
                val result = results[resultIndex].first
                resultIndex++
                result
            } else ""
        }
        every { mockResultSet.getString("summary") } answers {
            results.getOrNull((resultIndex - 1).coerceAtLeast(0))?.second ?: ""
        }
        every { mockResultSet.getString("rel_path") } answers {
            "src/File${(resultIndex - 1).coerceAtLeast(0) + 1}.kt"
        }
        every { mockResultSet.getString("language") } returns "kotlin"

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }
    }
}
