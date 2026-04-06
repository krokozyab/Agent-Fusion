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

    @Test
    fun `rebuildForFile emits COVERS edges when source is a test file`() {
        val testFile = insertFile("src/test/kotlin/com/example/FooServiceTest.kt")
        val prodFile = insertFile("src/main/kotlin/com/example/FooService.kt")

        val testChunk = ChunkRepository.insert(
            Chunk(
                id = 0,
                fileId = testFile,
                ordinal = 0,
                kind = ChunkKind.CODE_FUNCTION,
                startLine = 1,
                endLine = 12,
                tokenEstimate = 30,
                content = "import com.example.FooService.fooService\nfun testIt() { fooService() }",
                summary = "testIt",
                createdAt = Instant.now()
            )
        )
        val prodChunk = ChunkRepository.insert(
            Chunk(
                id = 0,
                fileId = prodFile,
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
            testFile,
            listOf(
                SymbolRecord(
                    id = 0, fileId = testFile, chunkId = testChunk.id,
                    symbolType = SymbolType.IMPORT,
                    name = "fooService",
                    qualifiedName = "com.example.FooService.fooService",
                    signature = "import com.example.FooService.fooService",
                    language = "kotlin", startLine = 1, endLine = 1, createdAt = Instant.now()
                )
            )
        )
        SymbolRepository.replaceForFile(
            prodFile,
            listOf(
                SymbolRecord(
                    id = 0, fileId = prodFile, chunkId = prodChunk.id,
                    symbolType = SymbolType.FUNCTION,
                    name = "fooService",
                    qualifiedName = "com.example.FooService.fooService",
                    signature = "fun fooService()",
                    language = "kotlin", startLine = 1, endLine = 1, createdAt = Instant.now()
                )
            )
        )

        CrossFileLinkBuilder().rebuildForFile(testFile)

        var coversCount = 0
        var coversTarget: Long = -1
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                "SELECT target_chunk_id FROM links WHERE source_chunk_id = ? AND link_type = 'COVERS'"
            ).use { ps ->
                ps.setLong(1, testChunk.id)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        coversCount++
                        coversTarget = rs.getLong(1)
                    }
                }
            }
        }

        assertTrue(coversCount >= 1, "Expected at least one COVERS edge from the test chunk")
        assertTrue(coversTarget == prodChunk.id, "COVERS should target the production chunk")
    }

    @Test
    fun `rebuildForFile does not emit COVERS for non-test files`() {
        val fileA = insertFile("src/main/kotlin/com/example/A.kt")
        val fileB = insertFile("src/main/kotlin/com/example/B.kt")

        val chunkA = ChunkRepository.insert(
            Chunk(
                id = 0, fileId = fileA, ordinal = 0, kind = ChunkKind.CODE_FUNCTION,
                startLine = 1, endLine = 8, tokenEstimate = 20,
                content = "import com.example.B.bar\nfun foo() { bar() }",
                summary = "foo", createdAt = Instant.now()
            )
        )
        val chunkB = ChunkRepository.insert(
            Chunk(
                id = 0, fileId = fileB, ordinal = 0, kind = ChunkKind.CODE_FUNCTION,
                startLine = 1, endLine = 4, tokenEstimate = 10,
                content = "fun bar() = Unit", summary = "bar", createdAt = Instant.now()
            )
        )

        SymbolRepository.replaceForFile(
            fileA,
            listOf(
                SymbolRecord(
                    id = 0, fileId = fileA, chunkId = chunkA.id,
                    symbolType = SymbolType.IMPORT,
                    name = "bar", qualifiedName = "com.example.B.bar",
                    signature = "import com.example.B.bar",
                    language = "kotlin", startLine = 1, endLine = 1, createdAt = Instant.now()
                )
            )
        )
        SymbolRepository.replaceForFile(
            fileB,
            listOf(
                SymbolRecord(
                    id = 0, fileId = fileB, chunkId = chunkB.id,
                    symbolType = SymbolType.FUNCTION,
                    name = "bar", qualifiedName = "com.example.B.bar",
                    signature = "fun bar()",
                    language = "kotlin", startLine = 1, endLine = 1, createdAt = Instant.now()
                )
            )
        )

        CrossFileLinkBuilder().rebuildForFile(fileA)

        var coversCount = 0
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM links WHERE source_chunk_id = ? AND link_type = 'COVERS'"
            ).use { ps ->
                ps.setLong(1, chunkA.id)
                ps.executeQuery().use { rs -> if (rs.next()) coversCount = rs.getInt(1) }
            }
        }
        assertTrue(coversCount == 0, "Non-test source must not produce COVERS edges")
    }

    @Test
    fun `looksLikeTestFile recognises common test conventions`() {
        val positives = listOf(
            "src/test/kotlin/com/example/FooTest.kt",
            "src/Tests/Bar.kt",
            "tests/integration/baz_test.go",
            "pkg/foo_test.go",
            "tests/test_widget.py",
            "src/__tests__/Button.test.tsx",
            "app/Button.spec.ts",
            "spec/models/user_spec.rb"
        )
        val negatives = listOf(
            "src/main/kotlin/com/example/Foo.kt",
            "src/main/kotlin/com/example/TestData.kt",
            "src/main/python/widget.py",
            "lib/contestant.go"
        )
        positives.forEach {
            assertTrue(CrossFileLinkBuilder.looksLikeTestFile(it), "should detect $it as test")
        }
        negatives.forEach {
            assertTrue(!CrossFileLinkBuilder.looksLikeTestFile(it), "should NOT detect $it as test")
        }
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
