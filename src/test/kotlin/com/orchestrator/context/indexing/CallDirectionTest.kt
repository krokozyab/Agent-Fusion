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
class CallDirectionTest {
    private lateinit var tempDir: Path

    @BeforeTest fun setup() {
        tempDir = createTempDirectory("calldir")
        ContextDatabase.initialize(StorageConfig(dbPath = tempDir.resolve("c.duckdb").toString()))
        ContextDatabase.withConnection { conn -> conn.createStatement().use {
            it.executeUpdate("DELETE FROM links"); it.executeUpdate("DELETE FROM symbols")
            it.executeUpdate("DELETE FROM chunks"); it.executeUpdate("DELETE FROM file_state")
        } }
    }
    @AfterTest fun teardown() { ContextDatabase.shutdown(); tempDir.deleteRecursively() }

    @Test fun `a name mentioned in a comment does not become a phantom caller`() {
        val fileId = FileStateRepository.insert(FileState(0, "p.pkb", tempDir.resolve("p.pkb").toString(),
            "h", 1, 1, "plsql", "code", null, Instant.now(), false)).id

        // create_delivery [1-2], assign_detail [4-5], seed (calls both, with parens) [7-11], xxd (calls seed parenless) [13-16]
        fun ins(ord: Int, s: Int, e: Int, content: String) = ChunkRepository.insert(
            Chunk(0, fileId, ord, ChunkKind.SQL_STATEMENT, s, e, 20, content, "PROCEDURE", Instant.now()))
        val cCreate = ins(0, 1, 3, "PROCEDURE create_delivery IS\n-- invoked by process_load_confirmation_main(p_x)\nBEGIN NULL; END create_delivery;")
        val cAssign = ins(1, 4, 5, "PROCEDURE assign_detail_to_delivery IS\nBEGIN NULL; END assign_detail_to_delivery;")
        val cSeed = ins(2, 7, 11, "PROCEDURE process_load_confirmation_main IS\nBEGIN\n  create_delivery(p_x);\n  assign_detail_to_delivery(p_y);\nEND process_load_confirmation_main;")
        val cXxd = ins(3, 13, 16, "PROCEDURE xxd_shpcnf_main_prc IS\nBEGIN\n  process_load_confirmation_main;\nEND xxd_shpcnf_main_prc;")

        SymbolRepository.replaceForFile(fileId, listOf(
            sym(fileId, cCreate.id, "create_delivery", 1), sym(fileId, cAssign.id, "assign_detail_to_delivery", 4),
            sym(fileId, cSeed.id, "process_load_confirmation_main", 7), sym(fileId, cXxd.id, "xxd_shpcnf_main_prc", 13)))

        CrossFileLinkBuilder().rebuildForFile(fileId)

        // create_delivery's body only *mentions* the seed in a comment; the seed actually calls
        // create_delivery and assign_detail. Reverse traversal (callers of the seed) must therefore
        // return ONLY xxd — not the callees, and not the comment-mentioning create_delivery.
        val callers = com.orchestrator.context.ContextRepository
            .traverseGraphReverse(listOf(cSeed.id), 3, linkTypes = setOf("CALLS", "DEPENDS_ON", "MODIFIES"))
            .map { it.chunkId }
            .toSet()

        assertTrue(cXxd.id in callers, "the real caller xxd must be present")
        assertTrue(cCreate.id !in callers, "a callee mentioned in a comment must not be a caller")
        assertTrue(cAssign.id !in callers, "callees must not appear as callers")
    }

    private fun sym(fileId: Long, chunkId: Long, name: String, line: Int) = SymbolRecord(
        0, fileId, chunkId, SymbolType.FUNCTION, name, "p.$name", "PROCEDURE $name", "plsql", line, line + 1, Instant.now())
}
