package com.orchestrator.context.search

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.storage.ContextDatabase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffResolverTest {

    private lateinit var tempDir: Path

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("diff-resolver-test")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("context.duckdb").toString()))
        // file_id=1, rel=src/Foo.kt, abs=/proj/src/Foo.kt
        // chunks: 100 [1..10], 101 [11..30], 102 [31..50]
        seedFile(
            fileId = 1,
            relPath = "src/Foo.kt",
            absPath = "/proj/src/Foo.kt",
            chunks = listOf(
                Triple(100L, 1, 10),
                Triple(101L, 11, 30),
                Triple(102L, 31, 50)
            )
        )
        // unrelated file
        seedFile(
            fileId = 2,
            relPath = "src/Bar.kt",
            absPath = "/proj/src/Bar.kt",
            chunks = listOf(Triple(200L, 1, 20))
        )
    }

    @AfterEach
    fun teardown() {
        ContextDatabase.shutdown()
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `whole-file resolution returns all chunks of that file`() {
        val out = DiffResolver().resolveSeedChunks(
            listOf(DiffResolver.ChangedRegion(path = "src/Foo.kt", lineRange = null))
        )
        assertEquals(listOf(100L, 101L, 102L), out)
    }

    @Test
    fun `line range overlap picks only intersecting chunks`() {
        val out = DiffResolver().resolveSeedChunks(
            listOf(DiffResolver.ChangedRegion("src/Foo.kt", 12..15))
        )
        assertEquals(listOf(101L), out)
    }

    @Test
    fun `range straddling two chunks returns both`() {
        val out = DiffResolver().resolveSeedChunks(
            listOf(DiffResolver.ChangedRegion("src/Foo.kt", 8..14))
        )
        assertEquals(listOf(100L, 101L), out)
    }

    @Test
    fun `absolute path matches`() {
        val out = DiffResolver().resolveSeedChunks(
            listOf(DiffResolver.ChangedRegion("/proj/src/Foo.kt", 1..1))
        )
        assertEquals(listOf(100L), out)
    }

    @Test
    fun `relative path is matched as suffix of abs_path`() {
        // Even though we pass rel-style, it should still find the file via LIKE %/rel
        val out = DiffResolver().resolveSeedChunks(
            listOf(DiffResolver.ChangedRegion("Foo.kt", 31..40))
        )
        assertEquals(listOf(102L), out)
    }

    @Test
    fun `multiple regions are deduped while preserving order`() {
        val out = DiffResolver().resolveSeedChunks(
            listOf(
                DiffResolver.ChangedRegion("src/Foo.kt", 1..5),
                DiffResolver.ChangedRegion("src/Foo.kt", 1..15),  // overlaps prev → 100 dedup
                DiffResolver.ChangedRegion("src/Bar.kt", null)
            )
        )
        assertEquals(listOf(100L, 101L, 200L), out)
    }

    @Test
    fun `empty input returns empty`() {
        assertTrue(DiffResolver().resolveSeedChunks(emptyList()).isEmpty())
    }

    @Test
    fun `unknown path returns empty without error`() {
        val out = DiffResolver().resolveSeedChunks(
            listOf(DiffResolver.ChangedRegion("does/not/exist.kt", 1..10))
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `range outside file lines returns empty`() {
        val out = DiffResolver().resolveSeedChunks(
            listOf(DiffResolver.ChangedRegion("src/Foo.kt", 9000..9999))
        )
        assertTrue(out.isEmpty())
    }

    // -- helpers --

    private fun seedFile(fileId: Long, relPath: String, absPath: String, chunks: List<Triple<Long, Int, Int>>) {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("""
                    INSERT INTO file_state (file_id, rel_path, abs_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                    VALUES ($fileId, '$relPath', '$absPath', 'h$fileId', 1000, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
                """.trimIndent())
            }
            chunks.forEachIndexed { idx, (chunkId, startLine, endLine) ->
                conn.createStatement().use { st ->
                    st.execute("""
                        INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, token_count, content, summary, created_at)
                        VALUES ($chunkId, $fileId, $idx, 'CODE_FUNCTION', $startLine, $endLine, 50, 'c$chunkId', 's$chunkId', CURRENT_TIMESTAMP)
                    """.trimIndent())
                }
            }
        }
    }
}
