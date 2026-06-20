package com.orchestrator.context.providers

import com.orchestrator.context.chunking.SqlChunker
import com.orchestrator.context.config.StorageConfig
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.indexing.SymbolIndexBuilder
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
class SymbolProviderPlsqlTest {
    private lateinit var tempDir: Path
    @BeforeTest fun s() { tempDir = createTempDirectory("symp"); ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("c.duckdb").toString()))
        ContextDatabase.withConnection { c -> c.createStatement().use { it.executeUpdate("DELETE FROM links"); it.executeUpdate("DELETE FROM symbols"); it.executeUpdate("DELETE FROM chunks"); it.executeUpdate("DELETE FROM file_state") } } }
    @AfterTest fun t() { ContextDatabase.shutdown(); tempDir.deleteRecursively() }

    @Test fun `exact name returns the body chunk and tolerates a stale symbol language`() = runBlocking {
        // Body package: forward decl of process_load_confirmation_main, plus its definition.
        val body = """
            CREATE OR REPLACE PACKAGE BODY xxd AS
              PROCEDURE process_load_confirmation_main;
              PROCEDURE process_load_confirmation_main IS
              BEGIN
                NULL;
              END process_load_confirmation_main;
            END xxd;
            /
        """.trimIndent()
        // Spec (decommissioned monolith) with a forward declaration of a similar name.
        val spec = "CREATE OR REPLACE PACKAGE old3pl AS\n  PROCEDURE process_load_confirmation;\nEND old3pl;\n/"

        for ((rel, src, lang) in listOf(Triple("xxd.pkb", body, "plsql"), Triple("3pl.pks", spec, "plsql"))) {
            val p = tempDir.resolve(rel); Files.writeString(p, src)
            val fid = FileStateRepository.insert(FileState(0, rel, p.toString(), "h", 1, 1, lang, "code", null, Instant.now(), false)).id
            val chunks = SqlChunker(overlapPercent = 15).chunk(src, rel).map { ChunkRepository.insert(it.copy(fileId = fid)) }
            SymbolIndexBuilder().indexFile(p, fid, lang, chunks)
        }

        // A stale symbol whose own language is "sql" while its file is "plsql" must still match a
        // ["plsql"] scope (the file's language is authoritative). Insert it directly.
        ContextDatabase.withConnection { c ->
            val fid = c.prepareStatement("SELECT file_id FROM file_state WHERE rel_path='xxd.pkb'").use { ps -> ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) } }
            val cid = c.prepareStatement("SELECT chunk_id FROM chunks WHERE file_id=? ORDER BY ordinal DESC LIMIT 1").use { ps -> ps.setLong(1, fid); ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) } }
            c.prepareStatement("INSERT INTO symbols (symbol_id, file_id, chunk_id, symbol_type, name, qualified_name, signature, language, start_line, end_line, created_at) VALUES (99999, ?, ?, 'FUNCTION', 'stale_lang_proc', 'xxd.stale_lang_proc', 'PROCEDURE stale_lang_proc', 'sql', 3, 3, CURRENT_TIMESTAMP)").use { ps -> ps.setLong(1, fid); ps.setLong(2, cid); ps.executeUpdate() }
        }

        val res = SymbolContextProvider().getContext(
            "process_load_confirmation_main",
            ContextScope(languages = setOf("plsql")),
            TokenBudget(maxTokens = 8000)
        )
        // Exact name resolves to the definition body, in the target package file — not a forward decl.
        assertTrue(res.isNotEmpty(), "symbol provider must return the body for an exact name")
        val top = res.first()
        assertTrue(top.filePath.endsWith("xxd.pkb"), "top hit must be the body file, not the spec: ${top.filePath}")
        assertTrue(top.text.contains("process_load_confirmation_main"), "must return the body chunk")

        val stale = SymbolContextProvider().getContext(
            "stale_lang_proc",
            ContextScope(languages = setOf("plsql")),
            TokenBudget(maxTokens = 8000)
        )
        assertTrue(stale.isNotEmpty(), "a symbol with stale s.language=sql in a plsql file must still match a plsql scope")
    }
}
