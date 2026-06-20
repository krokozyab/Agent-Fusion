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
    fun `non-package sql is unaffected`() {
        val sql = "CREATE TABLE users (id INT PRIMARY KEY);"
        val chunks = chunker.chunk(sql, "schema.sql")
        assertEquals(1, chunks.size)
        assertEquals("CREATE TABLE users", chunks.single().summary)
    }
}
