package com.orchestrator.context.indexing

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.AuthorInfo
import com.orchestrator.context.domain.BlameInfo
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.CommitInfo
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.providers.GitHistoryAnalyzer
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.FileStateRepository
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class GitIntentLinkBuilderTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("git-intent-links")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
        clearTables()
    }

    @AfterTest
    fun teardown() {
        clearTables()
        ContextDatabase.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun `rebuildForFile creates commit chunk and MODIFIES edge`() {
        val sourcePath = tempDir.resolve("src/AuthService.kt")
        Files.createDirectories(sourcePath.parent)
        Files.writeString(sourcePath, "fun updateJwtTimeout() = Unit")

        val fileId = FileStateRepository.insert(
            FileState(
                id = 0,
                relativePath = "src/AuthService.kt",
                absolutePath = sourcePath.toString(),
                contentHash = "hash-auth",
                sizeBytes = Files.size(sourcePath),
                modifiedTimeNs = 1,
                language = "kotlin",
                kind = "code",
                fingerprint = null,
                indexedAt = Instant.now(),
                isDeleted = false
            )
        ).id

        val functionChunk = ChunkRepository.insert(
            Chunk(
                id = 0,
                fileId = fileId,
                ordinal = 0,
                kind = ChunkKind.CODE_FUNCTION,
                startLine = 1,
                endLine = 3,
                tokenEstimate = 24,
                content = "fun updateJwtTimeout() = Unit",
                summary = "updateJwtTimeout",
                createdAt = Instant.now()
            )
        )

        val commit = CommitInfo(
            hash = "abc123def456",
            shortHash = "abc123d",
            author = AuthorInfo("Dev", "dev@example.com"),
            committer = AuthorInfo("Dev", "dev@example.com"),
            message = "Increase JWT timeout for SSO retry stability",
            shortMessage = "Increase JWT timeout",
            timestamp = Instant.parse("2026-01-10T12:00:00Z"),
            filesChanged = listOf("src/AuthService.kt"),
            additions = 3,
            deletions = 1
        )

        val analyzer = mockk<GitHistoryAnalyzer>()
        every { analyzer.getRecentCommits(sourcePath, any()) } returns listOf(commit)
        every { analyzer.getBlame(sourcePath) } returns mapOf(
            1 to BlameInfo(
                line = 1,
                content = "fun updateJwtTimeout() = Unit",
                commit = commit
            )
        )

        GitIntentLinkBuilder(analyzer = analyzer).rebuildForFile(fileId)

        val commitChunkIds = mutableSetOf<Long>()
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT chunk_id
                FROM chunks
                WHERE kind = 'COMMIT_MESSAGE'
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        commitChunkIds += rs.getLong("chunk_id")
                    }
                }
            }
        }
        assertTrue(commitChunkIds.isNotEmpty(), "Expected a COMMIT_MESSAGE chunk to be created")

        val modifiesTargets = mutableSetOf<Long>()
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT source_chunk_id, target_chunk_id
                FROM links
                WHERE link_type = 'MODIFIES'
                """.trimIndent()
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val source = rs.getLong("source_chunk_id")
                        if (source in commitChunkIds) {
                            modifiesTargets += rs.getLong("target_chunk_id")
                        }
                    }
                }
            }
        }

        assertTrue(functionChunk.id in modifiesTargets)
    }

    @Test
    fun `shared commit across two files links both without FK violation`() {
        // A commit that touched two files. The commit chunk is shared; building links for the second
        // file must not fail trying to UPDATE the chunk already referenced by the first file's link
        // (DuckDB forbids updating an FK-referenced row).
        val (fileId1, chunk1, path1) = insertFileAndChunk("src/A.kt", "fun a() = Unit")
        val (fileId2, chunk2, path2) = insertFileAndChunk("src/B.kt", "fun b() = Unit")

        val commit = CommitInfo(
            hash = "shared0commit0",
            shortHash = "shared0",
            author = AuthorInfo("Dev", "dev@example.com"),
            committer = AuthorInfo("Dev", "dev@example.com"),
            message = "Touch both A and B",
            shortMessage = "Touch A and B",
            timestamp = Instant.parse("2026-02-01T09:00:00Z"),
            filesChanged = listOf("src/A.kt", "src/B.kt"),
            additions = 2,
            deletions = 0
        )

        val analyzer = mockk<GitHistoryAnalyzer>()
        every { analyzer.getRecentCommits(path1, any()) } returns listOf(commit)
        every { analyzer.getRecentCommits(path2, any()) } returns listOf(commit)
        every { analyzer.getBlame(path1) } returns mapOf(1 to BlameInfo(1, "fun a() = Unit", commit))
        every { analyzer.getBlame(path2) } returns mapOf(1 to BlameInfo(1, "fun b() = Unit", commit))

        val builder = GitIntentLinkBuilder(analyzer = analyzer)
        builder.rebuildForFile(fileId1)
        builder.rebuildForFile(fileId2)

        // Exactly one shared COMMIT_MESSAGE chunk, and MODIFIES links reach both files' chunks.
        var commitChunkCount = 0
        val modifiesTargets = mutableSetOf<Long>()
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM chunks WHERE kind = 'COMMIT_MESSAGE'").use { ps ->
                ps.executeQuery().use { rs -> if (rs.next()) commitChunkCount = rs.getInt(1) }
            }
            conn.prepareStatement("SELECT target_chunk_id FROM links WHERE link_type = 'MODIFIES'").use { ps ->
                ps.executeQuery().use { rs -> while (rs.next()) modifiesTargets += rs.getLong(1) }
            }
        }

        assertTrue(commitChunkCount == 1, "the commit chunk must be shared, not duplicated (was $commitChunkCount)")
        assertTrue(chunk1.id in modifiesTargets, "first file must be linked")
        assertTrue(chunk2.id in modifiesTargets, "second file must be linked despite sharing the commit chunk")
    }

    private fun insertFileAndChunk(relPath: String, content: String): Triple<Long, Chunk, Path> {
        val sourcePath = tempDir.resolve(relPath)
        Files.createDirectories(sourcePath.parent)
        Files.writeString(sourcePath, content)
        val fileId = FileStateRepository.insert(
            FileState(
                id = 0,
                relativePath = relPath,
                absolutePath = sourcePath.toString(),
                contentHash = "hash-$relPath",
                sizeBytes = Files.size(sourcePath),
                modifiedTimeNs = 1,
                language = "kotlin",
                kind = "code",
                fingerprint = null,
                indexedAt = Instant.now(),
                isDeleted = false
            )
        ).id
        val chunk = ChunkRepository.insert(
            Chunk(
                id = 0, fileId = fileId, ordinal = 0, kind = ChunkKind.CODE_FUNCTION,
                startLine = 1, endLine = 1, tokenEstimate = 12, content = content,
                summary = relPath, createdAt = Instant.now()
            )
        )
        return Triple(fileId, chunk, sourcePath)
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
