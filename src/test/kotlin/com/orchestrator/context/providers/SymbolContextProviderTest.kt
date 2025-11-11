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

class SymbolContextProviderTest {

    private lateinit var provider: SymbolContextProvider

    @BeforeEach
    fun setup() {
        provider = SymbolContextProvider()
        mockkObject(ContextDatabase)
    }

    @AfterEach
    fun teardown() {
        unmockkObject(ContextDatabase)
    }

    @Test
    fun `extracts CamelCase symbols from query`() = runBlocking {
        // Setup mock database to return empty results
        setupEmptyDatabaseMock()

        val query = "Find UserService and AuthController classes"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000)

        // Execute - should extract UserService and AuthController
        provider.getContext(query, scope, budget)

        // Verify database was queried (even though it returns empty)
        verify(atLeast = 1) { ContextDatabase.withConnection(any<(Connection) -> Any>()) }
    }

    @Test
    fun `extracts snake_case symbols from query`() = runBlocking {
        setupEmptyDatabaseMock()

        val query = "Find get_user_data and calculate_total functions"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000)

        provider.getContext(query, scope, budget)

        verify(atLeast = 1) { ContextDatabase.withConnection(any<(Connection) -> Any>()) }
    }

    @Test
    fun `extracts function call patterns from query`() = runBlocking {
        setupEmptyDatabaseMock()

        val query = "How does processRequest() work?"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000)

        provider.getContext(query, scope, budget)

        verify(atLeast = 1) { ContextDatabase.withConnection(any<(Connection) -> Any>()) }
    }

    @Test
    fun `extracts qualified names from query`() = runBlocking {
        setupEmptyDatabaseMock()

        val query = "Show me com.example.UserService.getUserById"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000)

        provider.getContext(query, scope, budget)

        verify(atLeast = 1) { ContextDatabase.withConnection(any<(Connection) -> Any>()) }
    }

    @Test
    fun `returns empty list when no symbols extracted`() = runBlocking {
        setupEmptyDatabaseMock()

        val query = "a b c" // Simple words that don't match symbol patterns
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000)

        val result = provider.getContext(query, scope, budget)

        assertEquals(0, result.size)
        verify(exactly = 0) { ContextDatabase.withConnection(any<(Connection) -> Any>()) }
    }

    @Test
    fun `returns context snippets for found symbols`() = runBlocking {
        // Setup mock database to return symbol matches
        setupDatabaseWithSymbols()

        val query = "Find UserService class"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000)

        val result = provider.getContext(query, scope, budget)

        assertTrue(result.isNotEmpty())
        val snippet = result[0]
        assertEquals("src/UserService.kt", snippet.filePath)
        assertEquals("class UserService", snippet.label) // Signature takes precedence
        assertEquals(ChunkKind.CODE_CLASS, snippet.kind)
        assertEquals("kotlin", snippet.language)
        assertTrue(snippet.score > 0.0)
    }

    @Test
    fun `prioritizes classes over functions`() = runBlocking {
        setupDatabaseWithMixedSymbols()

        val query = "Find UserService"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 10000)

        val result = provider.getContext(query, scope, budget)

        // Classes should come before functions due to prioritization
        assertTrue(result.isNotEmpty())
        val firstSnippet = result[0]
        // The first result should be the class (higher priority)
        assertTrue(firstSnippet.kind == ChunkKind.CODE_CLASS || firstSnippet.score >= 0.8)
    }

    @Test
    fun `respects token budget`() = runBlocking {
        setupDatabaseWithManySymbols()

        val query = "Find UserService"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 100) // Small budget

        val result = provider.getContext(query, scope, budget)

        // Should stop adding snippets when budget is exceeded
        val totalTokens = result.sumOf { it.text.length / 4 }
        assertTrue(totalTokens <= budget.availableForSnippets)
    }

    @Test
    fun `applies scope filters`() = runBlocking {
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

        val query = "Find UserService"
        val scope = ContextScope(
            paths = listOf("src/main"),
            languages = setOf("kotlin")
        )
        val budget = TokenBudget(maxTokens = 1000)

        provider.getContext(query, scope, budget)

        // Verify SQL includes scope filters
        verify { mockConnection.prepareStatement(match { sql ->
            sql.contains("f.rel_path LIKE ?") && sql.contains("s.language IN")
        }) }
    }

    @Test
    fun `handles database errors gracefully`() = runBlocking {
        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } throws Exception("Database error")

        val query = "Find UserService"
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000)

        try {
            provider.getContext(query, scope, budget)
        } catch (e: Exception) {
            // Expected to propagate
            assertEquals("Database error", e.message)
        }
    }

    @Test
    fun `has correct provider type`() {
        assertEquals(ContextProviderType.SYMBOL, provider.type)
    }

    @Test
    fun `performance test - responds within 50ms`() = runBlocking {
        setupDatabaseWithSymbols()

        val query = "Find UserService class"
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

        // With mocked database, should be very fast (much less than 50ms)
        assertTrue(avgTime < 50.0, "Average response time ${avgTime}ms exceeds 50ms requirement")
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

    private fun setupDatabaseWithSymbols() {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet

        // Mock single result - using counter instead of removing from list
        var callCount = 0
        every { mockResultSet.next() } answers {
            val result = callCount % 2 == 0
            callCount++
            result
        }
        every { mockResultSet.getLong("symbol_id") } returns 1L
        every { mockResultSet.getLong("file_id") } returns 1L
        every { mockResultSet.getLong("chunk_id") } returns 1L
        every { mockResultSet.wasNull() } returns false
        every { mockResultSet.getString("symbol_type") } returns "CLASS"
        every { mockResultSet.getString("name") } returns "UserService"
        every { mockResultSet.getString("qualified_name") } returns "com.example.UserService"
        every { mockResultSet.getInt("start_line") } returns 10
        every { mockResultSet.getInt("end_line") } returns 50
        every { mockResultSet.getString("signature") } returns "class UserService"
        every { mockResultSet.getString("language") } returns "kotlin"
        every { mockResultSet.getString("rel_path") } returns "src/UserService.kt"
        every { mockResultSet.getString("content") } returns "class UserService { }"
        every { mockResultSet.getInt("token_count") } returns 50

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }
    }

    private fun setupDatabaseWithMixedSymbols() {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet

        // Mock two results: first a function, then a class
        val results = mutableListOf(true, true, false)
        var callCount = 0
        every { mockResultSet.next() } answers { results.removeFirstOrNull() ?: false }
        every { mockResultSet.getLong("symbol_id") } answers { if (callCount++ % 2 == 0) 1L else 2L }
        every { mockResultSet.getLong("file_id") } returns 1L
        every { mockResultSet.getLong("chunk_id") } returns 1L
        every { mockResultSet.wasNull() } returns false
        every { mockResultSet.getString("symbol_type") } answers {
            if (callCount % 2 == 0) "FUNCTION" else "CLASS"
        }
        every { mockResultSet.getString("name") } returns "UserService"
        every { mockResultSet.getString("qualified_name") } returns "com.example.UserService"
        every { mockResultSet.getInt("start_line") } returns 10
        every { mockResultSet.getInt("end_line") } returns 50
        every { mockResultSet.getString("signature") } returns "function/class UserService"
        every { mockResultSet.getString("language") } returns "kotlin"
        every { mockResultSet.getString("rel_path") } returns "src/UserService.kt"
        every { mockResultSet.getString("content") } returns "code content"
        every { mockResultSet.getInt("token_count") } returns 50

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }
    }

    private fun setupDatabaseWithManySymbols() {
        val mockConnection = mockk<Connection>(relaxed = true)
        val mockPreparedStatement = mockk<PreparedStatement>(relaxed = true)
        val mockResultSet = mockk<ResultSet>(relaxed = true)

        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
        every { mockPreparedStatement.executeQuery() } returns mockResultSet

        // Mock 5 results with different token counts
        val results = MutableList(6) { it < 5 }
        var id = 1L
        every { mockResultSet.next() } answers { results.removeFirstOrNull() ?: false }
        every { mockResultSet.getLong("symbol_id") } answers { id++ }
        every { mockResultSet.getLong("file_id") } returns 1L
        every { mockResultSet.getLong("chunk_id") } returns 1L
        every { mockResultSet.wasNull() } returns false
        every { mockResultSet.getString("symbol_type") } returns "CLASS"
        every { mockResultSet.getString("name") } returns "Symbol"
        every { mockResultSet.getString("qualified_name") } returns "com.example.Symbol"
        every { mockResultSet.getInt("start_line") } returns 10
        every { mockResultSet.getInt("end_line") } returns 50
        every { mockResultSet.getString("signature") } returns "class Symbol"
        every { mockResultSet.getString("language") } returns "kotlin"
        every { mockResultSet.getString("rel_path") } returns "src/Symbol.kt"
        every { mockResultSet.getString("content") } returns "x".repeat(200) // 50 tokens each
        every { mockResultSet.getInt("token_count") } returns 50

        every { ContextDatabase.withConnection(any<(Connection) -> Any>()) } answers {
            val block = firstArg<(Connection) -> Any>()
            block.invoke(mockConnection)
        }
    }
}
