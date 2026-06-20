package com.orchestrator.context.chunking

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PL/SQL package bodies/specs must split into per-member chunks, not one giant chunk. */
class SqlChunkerPackageTest {

    private val chunker = SqlChunker(overlapPercent = 0)

    @Test
    fun `package body splits into header and one chunk per member`() {
        val sql = """
            CREATE OR REPLACE PACKAGE BODY pkg AS
              g_const CONSTANT NUMBER := 1;

              PROCEDURE init_log IS
              BEGIN
                NULL;
              END init_log;

              FUNCTION calc(p IN NUMBER) RETURN NUMBER IS
                v NUMBER;
              BEGIN
                v := p * 2;
                IF v > 10 THEN
                  v := 10;
                END IF;
                RETURN v;
              END calc;

              PROCEDURE finish IS
              BEGIN
                NULL;
              END finish;
            END pkg;
            /
        """.trimIndent()

        val chunks = chunker.chunk(sql, "pkg.pkb")
        val labels = chunks.mapNotNull { it.summary }

        // Header + 3 members, not a single chunk.
        assertTrue(chunks.size >= 4, "expected header + 3 members, got ${chunks.size}: $labels")
        assertTrue(labels.any { it.contains("PACKAGE BODY pkg") }, "header label missing: $labels")
        assertTrue("PROCEDURE init_log" in labels, "labels=$labels")
        assertTrue("FUNCTION calc" in labels, "labels=$labels")
        assertTrue("PROCEDURE finish" in labels, "labels=$labels")

        // No single chunk should contain all three members (i.e. it really split).
        assertTrue(
            chunks.none { it.content.contains("init_log") && it.content.contains("calc") && it.content.contains("finish") },
            "a chunk still contains every member — package was not split"
        )

        // The calc member chunk keeps its whole body (END IF must not have split it).
        val calc = chunks.first { it.summary == "FUNCTION calc" }
        assertTrue(calc.content.contains("RETURN v") && calc.content.contains("END calc"))

        // Line ranges valid and ascending.
        chunks.forEach { c -> assertTrue(c.startLine != null && c.endLine != null && c.startLine!! <= c.endLine!!) }
    }

    @Test
    fun `package spec splits declarations into members`() {
        val sql = """
            CREATE OR REPLACE PACKAGE pkg AS
              PROCEDURE do_a(p IN NUMBER);
              FUNCTION get_b RETURN VARCHAR2;
            END pkg;
            /
        """.trimIndent()

        val labels = chunker.chunk(sql, "pkg.pks").mapNotNull { it.summary }
        assertTrue("PROCEDURE do_a" in labels, "labels=$labels")
        assertTrue("FUNCTION get_b" in labels, "labels=$labels")
    }

    @Test
    fun `forward declarations fold into header and the body is the labelled chunk`() {
        val sql = """
            CREATE OR REPLACE PACKAGE BODY pkg AS
              PROCEDURE process_main;
              PROCEDURE helper;

              PROCEDURE helper IS
              BEGIN
                NULL;
              END helper;

              PROCEDURE process_main IS
              BEGIN
                helper;
              END process_main;
            END pkg;
            /
        """.trimIndent()

        val chunks = chunker.chunk(sql, "pkg.pkb")

        // process_main appears exactly once — as its body, not the forward declaration.
        val mains = chunks.filter { it.summary == "PROCEDURE process_main" }
        assertEquals(1, mains.size, "process_main must be one chunk (its body), got ${chunks.map { it.summary }}")
        val main = mains.single()
        assertTrue(main.content.contains("helper;"), "the body chunk must contain the procedure body")
        assertTrue(main.content.contains("END process_main"), "body chunk must span to its END")
        assertTrue(main.startLine!! > 4, "body chunk must start at the definition (line >4), not the forward decl")

        // The forward declarations live in the header chunk, not their own labelled member chunks.
        val header = chunks.first()
        assertTrue(
            header.content.contains("PROCEDURE process_main;") && header.content.contains("PROCEDURE helper;"),
            "forward declarations must fold into the header"
        )
        assertTrue(chunks.none { it.summary == "PROCEDURE helper" && !it.content.contains("BEGIN") },
            "a forward declaration must not become its own member chunk")
    }

    @Test
    fun `non-package sql is unaffected`() {
        val sql = "CREATE TABLE users (id INT PRIMARY KEY);"
        val chunks = chunker.chunk(sql, "schema.sql")
        assertEquals(1, chunks.size)
        assertEquals("CREATE TABLE users", chunks.single().summary)
    }
}
