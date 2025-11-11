package com.orchestrator.context.providers

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class GitHistoryAnalyzerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var analyzer: GitHistoryAnalyzer
    private lateinit var git: Git
    private lateinit var testFile: Path

    @BeforeEach
    fun setUp() {
        analyzer = GitHistoryAnalyzer()

        // Initialize git repository
        git = Git.init().setDirectory(tempDir.toFile()).call()

        // Configure git user
        git.repository.config.apply {
            setString("user", null, "name", "Test User")
            setString("user", null, "email", "test@example.com")
            save()
        }

        // Create initial commit
        testFile = tempDir.resolve("test.txt")
        Files.writeString(testFile, "Initial content\n")
        git.add().addFilepattern("test.txt").call()
        git.commit().setMessage("Initial commit").call()
    }

    @AfterEach
    fun tearDown() {
        git.close()
        analyzer.clearCache()
    }

    @Test
    fun `getRecentCommits returns commits for file`() {
        // Add more commits
        Files.writeString(testFile, "Initial content\nSecond line\n")
        git.add().addFilepattern("test.txt").call()
        git.commit().setMessage("Add second line").call()

        Files.writeString(testFile, "Initial content\nSecond line\nThird line\n")
        git.add().addFilepattern("test.txt").call()
        git.commit().setMessage("Add third line").call()

        // Get recent commits
        val commits = analyzer.getRecentCommits(testFile, limit = 10)

        // Verify
        assertEquals(3, commits.size, "Should have 3 commits")
        assertEquals("Add third line", commits[0].shortMessage, "Most recent commit first")
        assertEquals("Add second line", commits[1].shortMessage)
        assertEquals("Initial commit", commits[2].shortMessage)

        // Verify commit details
        val latestCommit = commits[0]
        assertNotNull(latestCommit.hash)
        assertTrue(latestCommit.hash.length == 40, "Full hash should be 40 chars")
        assertTrue(latestCommit.shortHash.length == 7, "Short hash should be 7 chars")
        assertEquals("Test User", latestCommit.author.name)
        assertEquals("test@example.com", latestCommit.author.email)
        assertNotNull(latestCommit.timestamp)
    }

    @Test
    fun `getRecentCommits respects limit parameter`() {
        // Create multiple commits
        repeat(10) { index ->
            Files.writeString(testFile, "Content $index\n")
            git.add().addFilepattern("test.txt").call()
            git.commit().setMessage("Commit $index").call()
        }

        // Get with limit
        val commits = analyzer.getRecentCommits(testFile, limit = 5)

        assertEquals(5, commits.size, "Should respect limit parameter")
    }

    @Test
    fun `getRecentCommits returns empty list for non-git path`() {
        val nonGitPath = tempDir.resolve("../non-git-file.txt")
        Files.createDirectories(nonGitPath.parent)
        Files.writeString(nonGitPath, "content")

        val commits = analyzer.getRecentCommits(nonGitPath)

        assertTrue(commits.isEmpty(), "Should return empty list for non-git path")
    }

    @Test
    fun `getRecentCommits uses cache`() {
        // First call
        val commits1 = analyzer.getRecentCommits(testFile)
        assertNotNull(commits1)

        // Second call should hit cache
        val commits2 = analyzer.getRecentCommits(testFile)
        assertNotNull(commits2)

        // Verify cache stats
        val stats = analyzer.getCacheStats()
        assertTrue((stats["recentCommitsCacheSize"] as Int) > 0, "Cache should contain entries")
    }

    @Test
    fun `getBlame returns line-by-line authorship`() {
        // Create multi-line file with different commits
        Files.writeString(testFile, "Line 1\n")
        git.add().addFilepattern("test.txt").call()
        git.commit().setMessage("Add line 1").call()

        Files.writeString(testFile, "Line 1\nLine 2\n")
        git.add().addFilepattern("test.txt").call()
        git.commit().setMessage("Add line 2").call()

        Files.writeString(testFile, "Line 1\nLine 2\nLine 3\n")
        git.add().addFilepattern("test.txt").call()
        git.commit().setMessage("Add line 3").call()

        // Get blame
        val blame = analyzer.getBlame(testFile)

        // Verify
        assertTrue(blame.isNotEmpty(), "Blame should not be empty")
        assertTrue(blame.containsKey(1), "Should have blame for line 1")
        assertTrue(blame.containsKey(2), "Should have blame for line 2")
        assertTrue(blame.containsKey(3), "Should have blame for line 3")

        // Check line content
        assertEquals("Line 1", blame[1]?.content?.trim())
        assertEquals("Line 2", blame[2]?.content?.trim())
        assertEquals("Line 3", blame[3]?.content?.trim())

        // Check commit messages
        assertNotNull(blame[1]?.commit)
        assertNotNull(blame[2]?.commit)
        assertNotNull(blame[3]?.commit)
    }

    @Test
    fun `getBlame returns empty map for non-git path`() {
        val nonGitPath = tempDir.resolve("../non-git-file.txt")
        Files.createDirectories(nonGitPath.parent)
        Files.writeString(nonGitPath, "content")

        val blame = analyzer.getBlame(nonGitPath)

        assertTrue(blame.isEmpty(), "Should return empty map for non-git path")
    }

    @Test
    fun `getBlame uses cache`() {
        // First call
        val blame1 = analyzer.getBlame(testFile)
        assertNotNull(blame1)

        // Second call should hit cache
        val blame2 = analyzer.getBlame(testFile)
        assertNotNull(blame2)

        // Verify cache stats
        val stats = analyzer.getCacheStats()
        assertTrue((stats["blameCacheSize"] as Int) > 0, "Cache should contain entries")
    }

    @Test
    fun `findCoChangedFiles identifies files changed together`() {
        // Create multiple files
        val file1 = tempDir.resolve("file1.txt")
        val file2 = tempDir.resolve("file2.txt")
        val file3 = tempDir.resolve("file3.txt")

        // Commit 1: file1 and file2 together
        Files.writeString(file1, "content1\n")
        Files.writeString(file2, "content2\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("Add file1 and file2").call()

        // Commit 2: file1 and file2 together again
        Files.writeString(file1, "content1\nupdated\n")
        Files.writeString(file2, "content2\nupdated\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("Update file1 and file2").call()

        // Commit 3: file1 and file3 together
        Files.writeString(file1, "content1\nupdated\nmore\n")
        Files.writeString(file3, "content3\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("Update file1 and add file3").call()

        // Find co-changed files for file1
        val coChanged = analyzer.findCoChangedFiles(file1, limit = 10, minCoOccurrence = 2)

        // Verify
        assertTrue(coChanged.isNotEmpty(), "Should find co-changed files")

        // file2 should be in the list (changed with file1 twice)
        val file2Found = coChanged.any { it.fileName.toString() == "file2.txt" }
        assertTrue(file2Found, "file2 should be identified as co-changed")

        // file3 might not be (only changed once with file1, below minCoOccurrence)
        val file3Found = coChanged.any { it.fileName.toString() == "file3.txt" }
        assertFalse(file3Found, "file3 should not be in list with minCoOccurrence=2")
    }

    @Test
    fun `findCoChangedFiles returns empty list for non-git path`() {
        val nonGitPath = tempDir.resolve("../non-git-file.txt")
        Files.createDirectories(nonGitPath.parent)
        Files.writeString(nonGitPath, "content")

        val coChanged = analyzer.findCoChangedFiles(nonGitPath)

        assertTrue(coChanged.isEmpty(), "Should return empty list for non-git path")
    }

    @Test
    fun `findCoChangedFiles respects minCoOccurrence parameter`() {
        val file1 = tempDir.resolve("file1.txt")
        val file2 = tempDir.resolve("file2.txt")

        // Single commit with both files
        Files.writeString(file1, "content1\n")
        Files.writeString(file2, "content2\n")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("Add both files").call()

        // With minCoOccurrence=1, file2 should appear
        val coChanged1 = analyzer.findCoChangedFiles(file1, minCoOccurrence = 1)
        assertTrue(coChanged1.any { it.fileName.toString() == "file2.txt" })

        // With minCoOccurrence=2, file2 should NOT appear
        val coChanged2 = analyzer.findCoChangedFiles(file1, minCoOccurrence = 2)
        assertFalse(coChanged2.any { it.fileName.toString() == "file2.txt" })
    }

    @Test
    fun `findCoChangedFiles uses cache`() {
        // First call
        val coChanged1 = analyzer.findCoChangedFiles(testFile)
        assertNotNull(coChanged1)

        // Second call should hit cache
        val coChanged2 = analyzer.findCoChangedFiles(testFile)
        assertNotNull(coChanged2)

        // Verify cache stats
        val stats = analyzer.getCacheStats()
        assertTrue((stats["coChangedFilesCacheSize"] as Int) > 0, "Cache should contain entries")
    }

    @Test
    fun `clearCache clears all caches`() {
        // Populate caches
        analyzer.getRecentCommits(testFile)
        analyzer.getBlame(testFile)
        analyzer.findCoChangedFiles(testFile)

        // Verify caches have entries
        val statsBefore = analyzer.getCacheStats()
        assertTrue((statsBefore["recentCommitsCacheSize"] as Int) > 0)
        assertTrue((statsBefore["blameCacheSize"] as Int) > 0)

        // Clear caches
        analyzer.clearCache()

        // Verify caches are empty
        val statsAfter = analyzer.getCacheStats()
        assertEquals(0, statsAfter["recentCommitsCacheSize"])
        assertEquals(0, statsAfter["blameCacheSize"])
        assertEquals(0, statsAfter["coChangedFilesCacheSize"])
    }

    @Test
    fun `getCacheStats returns correct statistics`() {
        val stats = analyzer.getCacheStats()

        assertTrue(stats.containsKey("recentCommitsCacheSize"))
        assertTrue(stats.containsKey("blameCacheSize"))
        assertTrue(stats.containsKey("coChangedFilesCacheSize"))
        assertTrue(stats.containsKey("cacheEnabled"))
        assertTrue(stats.containsKey("maxCacheSize"))

        assertEquals(true, stats["cacheEnabled"])
    }

    @Test
    fun `analyzer with caching disabled does not cache`() {
        val noCacheAnalyzer = GitHistoryAnalyzer(cacheEnabled = false)

        // Make calls
        noCacheAnalyzer.getRecentCommits(testFile)
        noCacheAnalyzer.getBlame(testFile)

        // Verify no caching
        val stats = noCacheAnalyzer.getCacheStats()
        assertEquals(false, stats["cacheEnabled"])
        assertEquals(0, stats["recentCommitsCacheSize"])
        assertEquals(0, stats["blameCacheSize"])
    }
}
