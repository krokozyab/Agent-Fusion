package com.orchestrator.context.indexing

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.SymbolRecord
import com.orchestrator.context.domain.SymbolType
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.FileStateRepository
import com.orchestrator.context.storage.SymbolRepository
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
class CrossFileLinkBuilderTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory("cross-file-links")
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
    fun `rebuildForFile creates CALLS and DEPENDS_ON edges across files`() {
        val fileA = insertFile("src/A.kt")
        val fileB = insertFile("src/B.kt")

        val chunkA = ChunkRepository.insert(
            Chunk(
                id = 0,
                fileId = fileA,
                ordinal = 0,
                kind = ChunkKind.CODE_FUNCTION,
                startLine = 1,
                endLine = 12,
                tokenEstimate = 30,
                content = "import com.example.FooService.fooService\nfun invoke() { fooService() }",
                summary = "invoke",
                createdAt = Instant.now()
            )
        )
        val chunkB = ChunkRepository.insert(
            Chunk(
                id = 0,
                fileId = fileB,
                ordinal = 0,
                kind = ChunkKind.CODE_FUNCTION,
                startLine = 1,
                endLine = 8,
                tokenEstimate = 24,
                content = "fun fooService() = Unit",
                summary = "fooService",
                createdAt = Instant.now()
            )
        )

        SymbolRepository.replaceForFile(
            fileA,
            listOf(
                SymbolRecord(
                    id = 0,
                    fileId = fileA,
                    chunkId = chunkA.id,
                    symbolType = SymbolType.IMPORT,
                    name = "fooService",
                    qualifiedName = "com.example.FooService.fooService",
                    signature = "import com.example.FooService.fooService",
                    language = "kotlin",
                    startLine = 1,
                    endLine = 1,
                    createdAt = Instant.now()
                )
            )
        )
        SymbolRepository.replaceForFile(
            fileB,
            listOf(
                SymbolRecord(
                    id = 0,
                    fileId = fileB,
                    chunkId = chunkB.id,
                    symbolType = SymbolType.FUNCTION,
                    name = "fooService",
                    qualifiedName = "com.example.FooService.fooService",
                    signature = "fun fooService()",
                    language = "kotlin",
                    startLine = 1,
                    endLine = 1,
                    createdAt = Instant.now()
                )
            )
        )

        CrossFileLinkBuilder().rebuildForFile(fileA)

        val edgeTypes = mutableSetOf<String>()
        val targets = mutableSetOf<Long>()
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT link_type, source_chunk_id, target_chunk_id
                FROM links
                WHERE source_chunk_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, chunkA.id)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        edgeTypes += rs.getString("link_type")
                        targets += rs.getLong("target_chunk_id")
                    }
                }
            }
        }

        assertTrue("CALLS" in edgeTypes)
        assertTrue("DEPENDS_ON" in edgeTypes)
        assertTrue(chunkB.id in targets)
    }

    private fun insertFile(relativePath: String): Long {
        val persisted = FileStateRepository.insert(
            FileState(
                id = 0,
                relativePath = relativePath,
                absolutePath = tempDir.resolve(relativePath).toString(),
                contentHash = "hash-$relativePath",
                sizeBytes = 256,
                modifiedTimeNs = 1,
                language = "kotlin",
                kind = "code",
                fingerprint = null,
                indexedAt = Instant.now(),
                isDeleted = false
            )
        )
        return persisted.id
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
