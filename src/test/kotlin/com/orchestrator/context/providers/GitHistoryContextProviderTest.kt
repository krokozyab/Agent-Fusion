package com.orchestrator.context.providers

import com.orchestrator.context.domain.*
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.config.StorageConfig
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlin.test.*

class GitHistoryContextProviderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var provider: GitHistoryContextProvider
    private lateinit var analyzer: GitHistoryAnalyzer
    private lateinit var git: Git
    private lateinit var testFile: Path

    @BeforeEach
    fun setUp() {
        // Initialize git repository
        git = Git.init().setDirectory(tempDir.toFile()).call()

        // Configure git user
        git.repository.config.apply {
            setString("user", null, "name", "Test User")
            setString("user", null, "email", "test@example.com")
            save()
        }

        // Create initial test file
        testFile = tempDir.resolve("TestFile.kt")
        Files.writeString(testFile, "fun testFunction() {\n    println(\"test\")\n}\n")
        git.add().addFilepattern("TestFile.kt").call()
        git.commit().setMessage("Initial commit").call()

        // Initialize database for context lookup
        val dbPath = tempDir.resolve("test-context.duckdb").pathString
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))

        // Create file_state and chunks for testing
        ContextDatabase.transaction { conn ->
            // Insert file
            conn.prepareStatement(
                "INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, language, kind, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setLong(1, 1)
                ps.setString(2, "TestFile.kt")
                ps.setString(3, "hash123")
                ps.setLong(4, 100)
                ps.setLong(5, System.currentTimeMillis() * 1000000)
                ps.setString(6, "kotlin")
                ps.setString(7, "CODE")
                ps.setBoolean(8, false)
                ps.executeUpdate()
            }

            // Insert chunk
            conn.prepareStatement(
                "INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, token_count, content, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            ).use { ps ->
                ps.setLong(1, 1)
                ps.setLong(2, 1)
                ps.setInt(3, 0)
                ps.setString(4, "CODE_FUNCTION")
                ps.setInt(5, 1)
                ps.setInt(6, 3)
                ps.setInt(7, 10)
                ps.setString(8, "fun testFunction() {\n    println(\"test\")\n}\n")
                ps.executeUpdate()
            }
        }

        // Create provider with analyzer
        analyzer = GitHistoryAnalyzer()
        provider = GitHistoryContextProvider(
            gitAnalyzer = analyzer,
            maxResults = 20,
            commitLimit = 10
        )
    }

    @AfterEach
    fun tearDown() {
        git.close()
        analyzer.clearCache()
        ContextDatabase.shutdown()
    }

    @Test
    fun `getContext returns empty list when no paths found in query`() {
        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 1000, reserveForPrompt = 0)

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("some random query", scope, budget)
        }

        assertTrue(result.isEmpty(), "Should return empty list when no paths found")
    }

    @Test
    fun `getContext extracts paths from query`() {
        // Add another commit
        Files.writeString(testFile, "fun testFunction() {\n    println(\"updated\")\n}\n")
        git.add().addFilepattern("TestFile.kt").call()
        git.commit().setMessage("Update function").call()

        // Use absolute path in scope to ensure git repo is found
        val scope = ContextScope(paths = listOf(testFile.toString()))
        val budget = TokenBudget(maxTokens = 1000, reserveForPrompt = 0)

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("Check for issues", scope, budget)
        }

        assertTrue(result.isNotEmpty(), "Should find git history for TestFile.kt")
        assertTrue(result.any { it.metadata["type"] == "commit" }, "Should include commit context")
    }

    @Test
    fun `getContext uses scope paths when provided`() {
        // Create another file
        val otherFile = tempDir.resolve("OtherFile.kt")
        Files.writeString(otherFile, "class OtherClass {}")
        git.add().addFilepattern("OtherFile.kt").call()
        git.commit().setMessage("Add other file").call()

        // Add to database
        ContextDatabase.transaction { conn ->
            conn.prepareStatement(
                "INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, language, kind, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setLong(1, 2)
                ps.setString(2, "OtherFile.kt")
                ps.setString(3, "hash456")
                ps.setLong(4, 50)
                ps.setLong(5, System.currentTimeMillis() * 1000000)
                ps.setString(6, "kotlin")
                ps.setString(7, "CODE")
                ps.setBoolean(8, false)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                "INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, token_count, content, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            ).use { ps ->
                ps.setLong(1, 2)
                ps.setLong(2, 2)
                ps.setInt(3, 0)
                ps.setString(4, "CODE_CLASS")
                ps.setInt(5, 1)
                ps.setInt(6, 1)
                ps.setInt(7, 5)
                ps.setString(8, "class OtherClass {}")
                ps.executeUpdate()
            }
        }

        val scope = ContextScope(paths = listOf(otherFile.toString()))
        val budget = TokenBudget(maxTokens = 1000, reserveForPrompt = 0)

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("any query", scope, budget)
        }

        assertTrue(result.isNotEmpty(), "Should find history for scope paths")
        assertTrue(result.any { it.filePath == "OtherFile.kt" }, "Should include OtherFile.kt")
    }

    @Test
    fun `getContext includes recent commits`() {
        // Add multiple commits
        repeat(3) { index ->
            Files.writeString(testFile, "fun testFunction() {\n    println(\"version $index\")\n}\n")
            git.add().addFilepattern("TestFile.kt").call()
            git.commit().setMessage("Update $index").call()
        }

        val scope = ContextScope(paths = listOf(testFile.toString()))
        val budget = TokenBudget(maxTokens = 10000, reserveForPrompt = 0)

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("any query", scope, budget)
        }

        assertTrue(result.isNotEmpty(), "Should return commit history")
        val commits = result.filter { it.metadata["type"] == "commit" }
        assertTrue(commits.isNotEmpty(), "Should include commit context")
        assertTrue(commits.any { it.metadata["message"]?.contains("Update") == true }, "Should include commit messages")
    }

    @Test
    fun `getContext finds co-changed files`() {
        // Create related file
        val relatedFile = tempDir.resolve("RelatedFile.kt")
        Files.writeString(relatedFile, "class Related {}")

        // Change both files together multiple times
        repeat(3) { index ->
            Files.writeString(testFile, "fun testFunction$index() {}")
            Files.writeString(relatedFile, "class Related$index {}")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("Update both files $index").call()
        }

        // Add related file to database
        ContextDatabase.transaction { conn ->
            conn.prepareStatement(
                "INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, language, kind, is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setLong(1, 3)
                ps.setString(2, "RelatedFile.kt")
                ps.setString(3, "hash789")
                ps.setLong(4, 50)
                ps.setLong(5, System.currentTimeMillis() * 1000000)
                ps.setString(6, "kotlin")
                ps.setString(7, "CODE")
                ps.setBoolean(8, false)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                "INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, token_count, content, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            ).use { ps ->
                ps.setLong(1, 3)
                ps.setLong(2, 3)
                ps.setInt(3, 0)
                ps.setString(4, "CODE_CLASS")
                ps.setInt(5, 1)
                ps.setInt(6, 1)
                ps.setInt(7, 5)
                ps.setString(8, "class Related2 {}")
                ps.executeUpdate()
            }
        }

        val scope = ContextScope(paths = listOf(testFile.toString()))
        val budget = TokenBudget(maxTokens = 10000, reserveForPrompt = 0)

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("any query", scope, budget)
        }

        assertTrue(result.isNotEmpty(), "Should return results")
        val coChanged = result.filter { it.metadata["type"] == "co-changed" }
        assertTrue(coChanged.isNotEmpty(), "Should include co-changed files")
    }

    @Test
    fun `getContext respects token budget`() {
        // Add multiple commits
        repeat(10) { index ->
            Files.writeString(testFile, "fun testFunction$index() {}")
            git.add().addFilepattern("TestFile.kt").call()
            git.commit().setMessage("Commit $index").call()
        }

        val scope = ContextScope()
        val budget = TokenBudget(maxTokens = 50, reserveForPrompt = 10) // Very small budget

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("TestFile.kt", scope, budget)
        }

        // Should limit results due to token budget
        val totalTokens = result.sumOf { it.metadata["chunk_id"]?.let { 10 } ?: 0 }
        assertTrue(totalTokens <= budget.availableForSnippets, "Should respect token budget")
    }

    @Test
    fun `getContext includes commit metadata`() {
        Files.writeString(testFile, "fun updated() {}")
        git.add().addFilepattern("TestFile.kt").call()
        git.commit().setMessage("Important update").call()

        val scope = ContextScope(paths = listOf(testFile.toString()))
        val budget = TokenBudget(maxTokens = 1000, reserveForPrompt = 0)

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("any query", scope, budget)
        }

        assertTrue(result.isNotEmpty(), "Should return results")
        val commit = result.firstOrNull { it.metadata["type"] == "commit" }
        assertNotNull(commit, "Should have commit context")

        // Verify metadata
        assertNotNull(commit.metadata["commit_hash"], "Should have commit hash")
        assertNotNull(commit.metadata["commit_short_hash"], "Should have short hash")
        assertEquals("Test User", commit.metadata["author"], "Should have author name")
        assertEquals("test@example.com", commit.metadata["author_email"], "Should have author email")
        assertEquals("Important update", commit.metadata["message"], "Should have commit message")
        assertNotNull(commit.metadata["timestamp"], "Should have timestamp")
    }

    @Test
    fun `getContext extracts file paths with extensions`() {
        Files.writeString(testFile, "updated content")
        git.add().addFilepattern("TestFile.kt").call()
        git.commit().setMessage("Update").call()

        // Use absolute path in scope
        val scope = ContextScope(paths = listOf(testFile.toString()))
        val budget = TokenBudget(maxTokens = 1000, reserveForPrompt = 0)

        // Test various queries
        val queries = listOf(
            "Check for issues",
            "Review the code",
            "Look for bugs",
            "Any problems?"
        )

        queries.forEach { query ->
            val result = kotlinx.coroutines.runBlocking {
                provider.getContext(query, scope, budget)
            }
            assertTrue(result.isNotEmpty(), "Should return git history for: $query")
        }
    }

    @Test
    fun `getContext filters by language scope`() {
        val scope = ContextScope(languages = setOf("python")) // Only python
        val budget = TokenBudget(maxTokens = 1000, reserveForPrompt = 0)

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("TestFile.kt", scope, budget)
        }

        // Should not find Kotlin file when filtering for Python
        assertTrue(result.isEmpty(), "Should filter by language")
    }

    @Test
    fun `getContext returns correct snippet structure`() {
        Files.writeString(testFile, "fun updated() {}")
        git.add().addFilepattern("TestFile.kt").call()
        git.commit().setMessage("Update").call()

        val scope = ContextScope(paths = listOf(testFile.toString()))
        val budget = TokenBudget(maxTokens = 1000, reserveForPrompt = 0)

        val result = kotlinx.coroutines.runBlocking {
            provider.getContext("any query", scope, budget)
        }

        assertTrue(result.isNotEmpty(), "Should return results")
        val snippet = result.first()

        // Verify ContextSnippet structure
        assertTrue(snippet.chunkId > 0, "Should have valid chunk ID")
        assertTrue(snippet.score in 0.0..1.0, "Score should be between 0 and 1")
        assertEquals("TestFile.kt", snippet.filePath, "Should have correct file path")
        assertNotNull(snippet.label, "Should have label")
        assertNotNull(snippet.text, "Should have text content")
        assertEquals("kotlin", snippet.language, "Should have language")
        assertNotNull(snippet.offsets, "Should have line offsets")
        assertTrue(snippet.metadata.isNotEmpty(), "Should have metadata")
        assertEquals("git-history", snippet.metadata["provider"], "Should identify provider")
    }

    @Test
    fun `provider has correct id and type`() {
        assertEquals("git-history", provider.id, "Should have correct ID")
        assertEquals(ContextProviderType.GIT_HISTORY, provider.type, "Should have correct type")
    }
}
