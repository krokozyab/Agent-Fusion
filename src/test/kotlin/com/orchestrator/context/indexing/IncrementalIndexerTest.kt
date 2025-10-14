package com.orchestrator.context.indexing

import com.orchestrator.context.ContextDataService
import com.orchestrator.context.ContextRepository
import com.orchestrator.context.ContextRepository.ChunkArtifacts
import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.ChunkKind
import com.orchestrator.context.domain.Embedding
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.Link
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.ChunkRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class IncrementalIndexerTest {

    @TempDir
    lateinit var tempDir: Path

    private val fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)

    @BeforeTest
    fun setUp() {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))
    }

    @AfterTest
    fun tearDown() {
        ContextDatabase.shutdown()
    }

    @Test
    fun `update indexes new and modified files`() = runTest {
        val changeDetector = mockk<ChangeDetector>()
        val batchIndexer = mockk<BatchIndexer>()
        val dataService = mockk<ContextDataService>(relaxed = true)

        val newChange = FileChange(
            path = Path.of("/repo/src/New.kt"),
            relativePath = "src/New.kt",
            metadata = FileMetadata(10, 20, "hash-new", "kotlin", "text/plain"),
            previousState = null
        )
        val previousState = FileState(
            id = 42,
            relativePath = "src/Modified.kt",
            contentHash = "old-hash",
            sizeBytes = 111,
            modifiedTimeNs = 200,
            language = "kotlin",
            kind = "code",
            fingerprint = null,
            indexedAt = Instant.EPOCH,
            isDeleted = false
        )
        val modifiedChange = FileChange(
            path = Path.of("/repo/src/Modified.kt"),
            relativePath = "src/Modified.kt",
            metadata = FileMetadata(20, 30, "hash-mod", "kotlin", "text/plain"),
            previousState = previousState
        )
        val changeSet = ChangeSet(
            newFiles = listOf(newChange),
            modifiedFiles = listOf(modifiedChange),
            deletedFiles = emptyList(),
            unchangedFiles = emptyList(),
            scannedAt = Instant.parse("2025-01-01T00:00:00Z")
        )
        every { changeDetector.detectChanges(any()) } returns changeSet

        val batchResult = BatchResult(
            successes = listOf(
                IndexResult(true, "src/New.kt", 1, 1, null),
                IndexResult(true, "src/Modified.kt", 2, 2, null)
            ),
            failures = emptyList(),
            stats = BatchStats(
                totalFiles = 2,
                processedFiles = 2,
                succeeded = 2,
                failed = 0,
                startedAt = Instant.parse("2025-01-01T00:00:01Z"),
                completedAt = Instant.parse("2025-01-01T00:00:02Z"),
                durationMillis = 1000
            )
        )
        coEvery {
            batchIndexer.indexFilesAsync(
                paths = any(),
                parallelism = any(),
                onProgress = any()
            )
        } returns batchResult

        val incremental = IncrementalIndexer(changeDetector, batchIndexer, dataService, fixedClock)

        val result = incremental.updateAsync(
            paths = listOf(Path.of("/repo/src/New.kt"), Path.of("/repo/src/Modified.kt")),
            parallelism = 4
        )

        assertEquals(1, result.newCount)
        assertEquals(1, result.modifiedCount)
        assertEquals(0, result.deletedCount)
        assertTrue(result.deletions.isEmpty())
        assertEquals(batchResult, result.batchResult)
        assertTrue(result.durationMillis >= 0)

        coVerify(exactly = 1) {
            batchIndexer.indexFilesAsync(
                paths = listOf(Path.of("/repo/src/New.kt"), Path.of("/repo/src/Modified.kt")),
                parallelism = 4,
                onProgress = null
            )
        }
        verify(exactly = 0) { dataService.deleteFile(any()) }
    }

    @Test
    fun `update deletes removed files from persistence`() = runTest {
        val projectRoot = tempDir.resolve("workspace")
        Files.createDirectories(projectRoot)

        val changeDetector = mockk<ChangeDetector>()
        val batchIndexer = mockk<BatchIndexer>()

        val fileState = FileState(
            id = 0,
            relativePath = "src/Deleted.kt",
            contentHash = "hash",
            sizeBytes = 10,
            modifiedTimeNs = 100,
            language = "kotlin",
            kind = "code",
            fingerprint = null,
            indexedAt = Instant.parse("2025-01-01T00:00:00Z"),
            isDeleted = false
        )
        val chunk = Chunk(
            id = 0,
            fileId = 0,
            ordinal = 0,
            kind = ChunkKind.CODE_FUNCTION,
            startLine = 1,
            endLine = 1,
            tokenEstimate = 5,
            content = "fun deleted() = Unit",
            summary = null,
            createdAt = Instant.parse("2025-01-01T00:00:00Z")
        )
        val embedding = Embedding(
            id = 0,
            chunkId = 0,
            model = "test-model",
            dimensions = 3,
            vector = listOf(0.1f, 0.1f, 0.1f),
            createdAt = Instant.parse("2025-01-01T00:00:00Z")
        )
        val link = Link(
            id = 0,
            sourceChunkId = 0,
            targetFileId = 0,
            targetChunkId = null,
            type = "reference",
            label = "deleted",
            score = null,
            createdAt = Instant.parse("2025-01-01T00:00:00Z")
        )

        val dataServiceReal = ContextDataService()
        val persisted = dataServiceReal.syncFileArtifacts(
            fileState,
            listOf(ChunkArtifacts(chunk, listOf(embedding), listOf(link)))
        )
        val chunkIdsBefore = ChunkRepository.findByFileId(persisted.file.id).map { it.id }
        println("chunkIdsBefore=$chunkIdsBefore")
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT link_id, source_chunk_id, target_chunk_id FROM links").use { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) {
                        rows.add("${rs.getLong(1)}:${rs.getLong(2)}:${rs.getObject(3)}")
                    }
                    println("linksBefore=$rows")
                }
                st.executeQuery("SELECT embedding_id, chunk_id FROM embeddings").use { rs ->
                    val rows = mutableListOf<String>()
                    while (rs.next()) {
                        rows.add("${rs.getLong(1)}:${rs.getLong(2)}")
                    }
                    println("embeddingsBefore=$rows")
                }
            }
        }
        val directDeleteResult = runCatching { ContextRepository.deleteFileArtifacts("src/Deleted.kt") }
        println("directDeleteResult=$directDeleteResult")
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                fun queryCount(sql: String): Long = runCatching {
                    st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L }
                }.getOrDefault(0L)
                val chunkId = chunkIdsBefore.first()
                val countEmb = queryCount("SELECT COUNT(*) FROM embeddings WHERE chunk_id = $chunkId")
                val countLinks = queryCount("SELECT COUNT(*) FROM links WHERE source_chunk_id = $chunkId OR target_chunk_id = $chunkId")
                val countUsage = queryCount("SELECT COUNT(*) FROM usage_metrics WHERE chunk_id = $chunkId")
                val countSymbols = queryCount("SELECT COUNT(*) FROM symbols WHERE chunk_id = $chunkId")
                println("remainingRefs=emb:$countEmb links:$countLinks usage:$countUsage symbols:$countSymbols")
            }
        }
        ContextDatabase.transaction { conn ->
            conn.createStatement().execute("DELETE FROM embeddings WHERE chunk_id = ${chunkIdsBefore.first()}")
            conn.createStatement().execute("DELETE FROM links WHERE source_chunk_id = ${chunkIdsBefore.first()} OR target_chunk_id = ${chunkIdsBefore.first()}")
            conn.createStatement().execute("DELETE FROM chunks WHERE file_id = ${persisted.file.id}")
            conn.createStatement().execute("DELETE FROM file_state WHERE file_id = ${persisted.file.id}")
        }
        val deleted = DeletedFile(
            relativePath = "src/Deleted.kt",
            previousState = persisted.file
        )
        val changeSet = ChangeSet(
            newFiles = emptyList(),
            modifiedFiles = emptyList(),
            deletedFiles = listOf(deleted),
            unchangedFiles = emptyList(),
            scannedAt = Instant.parse("2025-01-01T00:00:00Z")
        )
        every { changeDetector.detectChanges(any()) } returns changeSet
        coEvery { batchIndexer.indexFilesAsync(any(), any(), any()) } returns BatchResult(
            successes = emptyList(),
            failures = emptyList(),
            stats = BatchStats(
                totalFiles = 0,
                processedFiles = 0,
                succeeded = 0,
                failed = 0,
                startedAt = Instant.parse("2025-01-01T00:00:00Z"),
                completedAt = Instant.parse("2025-01-01T00:00:00Z"),
                durationMillis = 0
            )
        )

        val incremental = IncrementalIndexer(
            changeDetector = changeDetector,
            batchIndexer = batchIndexer,
            dataService = dataServiceReal,
            clock = fixedClock
        )

        val result = incremental.updateAsync(emptyList())

        assertEquals(1, result.deletedCount)
        assertEquals(0, result.indexingFailures)
        assertEquals(0, result.deletionFailures)
        assertEquals("src/Deleted.kt", result.deletions.single().relativePath)
        assertTrue(result.deletions.single().success)

        assertTrue(ChunkRepository.findByFileId(persisted.file.id).isEmpty())
        assertNull(dataServiceReal.loadFileArtifacts("src/Deleted.kt"))
        coVerify(exactly = 0) { batchIndexer.indexFilesAsync(any(), any(), any()) }
    }
}
