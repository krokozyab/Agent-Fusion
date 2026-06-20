package com.orchestrator.context.chunking

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Oracle PL/SQL coverage for SqlChunker (CREATE OR REPLACE, PACKAGE/TYPE, `/` terminator, END IF). */
class SqlChunkerOracleTest {

    private val chunker = SqlChunker(overlapPercent = 0)

    @Test
    fun `CREATE OR REPLACE PROCEDURE is one chunk despite internal semicolons and END IF`() {
        val sql = """
            CREATE OR REPLACE PROCEDURE adjust(p IN NUMBER) IS
              v NUMBER;
            BEGIN
              v := p * 2;
              IF v > 10 THEN
                v := 10;
              END IF;
              UPDATE t SET c = v;
            END adjust;
            /
        """.trimIndent()

        val chunks = chunker.chunk(sql, "adjust.sql")

        assertEquals(1, chunks.size, "the whole routine body must stay in one chunk")
        val c = chunks.single()
        assertEquals("CREATE PROCEDURE adjust", c.summary)
        assertTrue(c.content.contains("END adjust"), "chunk must include the routine body")
        assertEquals(1, c.startLine)
        assertEquals(9, c.endLine, "ends at 'END adjust;' (line 9), the '/' directive is dropped")
    }

    @Test
    fun `package body splits into per-member chunks and is not fragmented by inner semicolons`() {
        val sql = """
            CREATE OR REPLACE PACKAGE BODY pkg AS
              PROCEDURE a IS BEGIN NULL; END a;
              PROCEDURE b IS BEGIN NULL; END b;
            END pkg;
            /
        """.trimIndent()

        val chunks = chunker.chunk(sql, "pkg.pkb")
        val labels = chunks.mapNotNull { it.summary }

        // The package is split at member boundaries (header + a + b), but NOT fragmented by the
        // inner `;` of each one-line procedure (each member stays intact).
        assertTrue("PROCEDURE a" in labels, "labels=$labels")
        assertTrue("PROCEDURE b" in labels, "labels=$labels")
        val a = chunks.first { it.summary == "PROCEDURE a" }
        assertTrue(a.content.contains("BEGIN NULL; END a;"), "member A must stay intact: '${a.content}'")
        // No chunk should hold both members (proves real splitting, not one giant chunk).
        assertTrue(chunks.none { it.content.contains("END a;") && it.content.contains("END b;") })
    }

    @Test
    fun `anonymous DECLARE block is one chunk, not split on internal semicolons`() {
        val sql = """
            DECLARE
              v NUMBER;
            BEGIN
              v := 1;
              UPDATE t SET c = v;
            END;
            /
        """.trimIndent()

        val chunks = chunker.chunk(sql, "anon.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks.single().content.contains("DECLARE"))
        assertTrue(chunks.single().content.contains("END;"))
    }

    @Test
    fun `two slash-terminated routines split into two chunks`() {
        val sql = """
            CREATE OR REPLACE FUNCTION f RETURN NUMBER IS
            BEGIN
              RETURN 1;
            END f;
            /
            CREATE OR REPLACE PROCEDURE p IS
            BEGIN
              NULL;
            END p;
            /
        """.trimIndent()

        val chunks = chunker.chunk(sql, "two.sql")

        assertEquals(2, chunks.size)
        assertEquals("CREATE FUNCTION f", chunks[0].summary)
        assertEquals("CREATE PROCEDURE p", chunks[1].summary)
    }

    @Test
    fun `transaction BEGIN is not treated as a PL-SQL block`() {
        // Regression guard: `BEGIN;` ends with a semicolon → transaction control, three statements.
        val sql = """
            BEGIN;
            INSERT INTO t VALUES (1);
            COMMIT;
        """.trimIndent()

        val chunks = chunker.chunk(sql, "tx.sql")

        assertEquals(3, chunks.size)
        assertTrue(chunks.first().summary!!.contains("BEGIN"))
    }

    @Test
    fun `labels CREATE OR REPLACE TRIGGER`() {
        val sql = """
            CREATE OR REPLACE TRIGGER trg_audit BEFORE INSERT ON t
            FOR EACH ROW
            BEGIN
              NULL;
            END;
            /
        """.trimIndent()

        val chunk = chunker.chunk(sql, "trg.trg").firstOrNull()
        assertNotNull(chunk)
        assertEquals("CREATE TRIGGER trg_audit", chunk.summary)
    }
}
