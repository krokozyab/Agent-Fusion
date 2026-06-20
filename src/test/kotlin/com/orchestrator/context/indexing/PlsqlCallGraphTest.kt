package com.orchestrator.context.indexing

import com.orchestrator.context.ContextRepository
import com.orchestrator.context.chunking.SqlChunker
import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.storage.ChunkRepository
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.storage.FileStateRepository
import kotlinx.coroutines.runBlocking
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
class PlsqlCallGraphTest {
    private lateinit var tempDir: Path
    @BeforeTest fun s() { tempDir = createTempDirectory("plp"); ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("c.duckdb").toString()))
        ContextDatabase.withConnection { c -> c.createStatement().use { it.executeUpdate("DELETE FROM links"); it.executeUpdate("DELETE FROM symbols"); it.executeUpdate("DELETE FROM chunks"); it.executeUpdate("DELETE FROM file_state") } } }
    @AfterTest fun t() { ContextDatabase.shutdown(); tempDir.deleteRecursively() }

    @Test fun `parameterless and paren PL-SQL calls both build CALLS edges end to end`() = runBlocking {
        val src = """
            CREATE OR REPLACE PACKAGE BODY pkg AS
              PROCEDURE create_delivery(p_x NUMBER);
              PROCEDURE process_load_confirmation_main;
              PROCEDURE xxd_shpcnf_main_prc;

              PROCEDURE create_delivery(p_x NUMBER) IS
              BEGIN
                NULL;
              END create_delivery;

              PROCEDURE process_load_confirmation_main IS
              BEGIN
                create_delivery(1);
              END process_load_confirmation_main;

              PROCEDURE xxd_shpcnf_main_prc IS
                v VARCHAR2(100);
              BEGIN
                v := q'[O'Brien don't]';
                process_load_confirmation_main;
              END xxd_shpcnf_main_prc;
            END pkg;
            /
        """.trimIndent()
        val p = tempDir.resolve("pkg.pkb"); Files.writeString(p, src)
        val fileId = FileStateRepository.insert(FileState(0, "pkg.pkb", p.toString(), "h", 1, 1, "plsql", "code", null, Instant.now(), false)).id

        val rawChunks = SqlChunker(overlapPercent = 0).chunk(src, "pkg.pkb")
        val chunks = rawChunks.map { ChunkRepository.insert(it.copy(fileId = fileId)) }
        println("=== chunks ===")
        ContextDatabase.withConnection { c -> c.prepareStatement("SELECT chunk_id, kind, summary, start_line, end_line, content FROM chunks WHERE file_id=? ORDER BY ordinal").use { ps -> ps.setLong(1, fileId); ps.executeQuery().use { rs -> while (rs.next()) {
            val content = rs.getString("content")
            println("  [${rs.getLong("chunk_id")}] kind=${rs.getString("kind")} '${rs.getString("summary")}' lines=${rs.getInt("start_line")}-${rs.getInt("end_line")} hasParenlessCall=${content.contains("process_load_confirmation_main;") && !content.contains("PROCEDURE process_load_confirmation_main;")}")
        } } } }

        SymbolIndexBuilder().indexFile(p, fileId, "plsql", chunks)
        CrossFileLinkBuilder().rebuildForFile(fileId)

        val seed = chunks.first { it.summary == "PROCEDURE process_load_confirmation_main" }
        val xxd = chunks.first { it.summary == "PROCEDURE xxd_shpcnf_main_prc" }
        val create = chunks.first { it.summary == "PROCEDURE create_delivery" }

        // process_load_confirmation_main is called only as a parameterless `name;` (after a tricky
        // q-quoted string with apostrophes) — its caller xxd must still be found.
        val seedCallers = ContextRepository.traverseGraphReverse(listOf(seed.id), 3, linkTypes = setOf("CALLS", "DEPENDS_ON", "MODIFIES")).map { it.chunkId }.toSet()
        assertTrue(xxd.id in seedCallers, "parameterless caller xxd must be found: $seedCallers")
        assertTrue(create.id !in seedCallers, "callee create_delivery must not be a caller")

        // create_delivery is called with parens by the seed; reverse must return the seed.
        val createCallers = ContextRepository.traverseGraphReverse(listOf(create.id), 3, linkTypes = setOf("CALLS", "DEPENDS_ON", "MODIFIES")).map { it.chunkId }.toSet()
        assertTrue(seed.id in createCallers, "paren caller (seed) must be found for create_delivery")
    }
}
