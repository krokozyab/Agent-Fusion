package com.orchestrator.context.indexing

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.SymbolRecord
import com.orchestrator.context.domain.SymbolType
import com.orchestrator.context.ContextRepository
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
    fun `builds intra-file CALLS edge so reverse traversal finds an in-file caller`() {
        // One file, two subprograms: caller() invokes callee() in the same file (PL/SQL-style
        // intra-package call). Previously the same-file CALLS edge was never built, so
        // get_impact_radius (reverse traversal) returned zero callers — a false negative.
        val fileId = insertFile("pkg/sample.pkb")

        val callerChunk = ChunkRepository.insert(
            Chunk(
                id = 0, fileId = fileId, ordinal = 0, kind = ChunkKind.CODE_FUNCTION,
                startLine = 1, endLine = 3, tokenEstimate = 12,
                content = "PROCEDURE caller IS BEGIN callee(); END caller;", summary = "caller",
                createdAt = Instant.now()
            )
        )
        val calleeChunk = ChunkRepository.insert(
            Chunk(
                id = 0, fileId = fileId, ordinal = 1, kind = ChunkKind.CODE_FUNCTION,
                startLine = 5, endLine = 7, tokenEstimate = 10,
                content = "PROCEDURE callee IS BEGIN NULL; END callee;", summary = "callee",
                createdAt = Instant.now()
            )
        )
        SymbolRepository.replaceForFile(
            fileId,
            listOf(
                SymbolRecord(
                    id = 0, fileId = fileId, chunkId = callerChunk.id, symbolType = SymbolType.FUNCTION,
                    name = "caller", qualifiedName = "pkg.caller", signature = "PROCEDURE caller",
                    language = "plsql", startLine = 1, endLine = 3, createdAt = Instant.now()
                ),
                SymbolRecord(
                    id = 0, fileId = fileId, chunkId = calleeChunk.id, symbolType = SymbolType.FUNCTION,
                    name = "callee", qualifiedName = "pkg.callee", signature = "PROCEDURE callee",
                    language = "plsql", startLine = 5, endLine = 7, createdAt = Instant.now()
                )
            )
        )

        CrossFileLinkBuilder().rebuildForFile(fileId)

        var found = false
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM links WHERE source_chunk_id = ? AND target_chunk_id = ? AND link_type = 'CALLS'"
            ).use { ps ->
                ps.setLong(1, callerChunk.id)
                ps.setLong(2, calleeChunk.id)
                ps.executeQuery().use { rs -> found = rs.next() }
            }
        }
        assertTrue(found, "expected an intra-file CALLS edge caller -> callee")

        // Reverse traversal from the callee must surface the caller (what get_impact_radius does).
        val callers = ContextRepository.traverseGraphReverse(
            seedChunkIds = listOf(calleeChunk.id),
            maxDepth = 3,
            linkTypes = setOf("CALLS", "DEPENDS_ON", "MODIFIES")
        ).map { it.chunkId }
        assertTrue(callerChunk.id in callers, "reverse traversal must find the in-file caller")
    }

    @Test
    fun `rebuildForFiles builds links for multiple files in one batch`() {
        // Two source files, each calling/importing a symbol defined in a shared target file.
        val target = insertFile("src/Target.kt")
        val targetChunk = ChunkRepository.insert(
            Chunk(
                id = 0, fileId = target, ordinal = 0, kind = ChunkKind.CODE_FUNCTION,
                startLine = 1, endLine = 4, tokenEstimate = 10,
                content = "fun fooService() = Unit", summary = "fooService", createdAt = Instant.now()
            )
        )
        SymbolRepository.replaceForFile(
            target,
            listOf(
                SymbolRecord(
                    id = 0, fileId = target, chunkId = targetChunk.id, symbolType = SymbolType.FUNCTION,
                    name = "fooService", qualifiedName = "com.example.fooService",
                    signature = "fun fooService()", language = "kotlin",
                    startLine = 1, endLine = 1, createdAt = Instant.now()
                )
            )
        )

        val sourceChunkIds = listOf("src/S1.kt", "src/S2.kt").map { rel ->
            val fileId = insertFile(rel)
            val chunk = ChunkRepository.insert(
                Chunk(
                    id = 0, fileId = fileId, ordinal = 0, kind = ChunkKind.CODE_FUNCTION,
                    startLine = 1, endLine = 6, tokenEstimate = 20,
                    content = "fun invoke() { fooService() }", summary = "invoke", createdAt = Instant.now()
                )
            )
            SymbolRepository.replaceForFile(
                fileId,
                listOf(
                    SymbolRecord(
                        id = 0, fileId = fileId, chunkId = chunk.id, symbolType = SymbolType.FUNCTION,
                        name = "invoke", qualifiedName = null, signature = "fun invoke()",
                        language = "kotlin", startLine = 1, endLine = 1, createdAt = Instant.now()
                    )
                )
            )
            fileId to chunk.id
        }

        CrossFileLinkBuilder().rebuildForFiles(sourceChunkIds.map { it.first })

        // Every source chunk must have a CALLS edge into the shared target chunk.
        sourceChunkIds.forEach { (_, chunkId) ->
            var calls = false
            ContextDatabase.withConnection { conn ->
                conn.prepareStatement(
                    "SELECT 1 FROM links WHERE source_chunk_id = ? AND target_chunk_id = ? AND link_type = 'CALLS'"
                ).use { ps ->
                    ps.setLong(1, chunkId)
                    ps.setLong(2, targetChunk.id)
                    ps.executeQuery().use { rs -> calls = rs.next() }
                }
            }
            assertTrue(calls, "batch rebuild must create CALLS edge for source chunk $chunkId")
        }
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
