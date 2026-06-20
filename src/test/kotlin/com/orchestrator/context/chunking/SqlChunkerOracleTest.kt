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
    fun `CREATE OR REPLACE PACKAGE BODY stays whole and terminates on slash`() {
        val sql = """
            CREATE OR REPLACE PACKAGE BODY pkg AS
              PROCEDURE a IS BEGIN NULL; END a;
              PROCEDURE b IS BEGIN NULL; END b;
            END pkg;
            /
        """.trimIndent()

        val chunks = chunker.chunk(sql, "pkg.pkb")

        assertEquals(1, chunks.size, "a package body's inner procedure ENDs must not split it")
        val c = chunks.single()
        assertEquals("CREATE PACKAGE BODY pkg", c.summary)
        assertTrue(c.content.contains("PROCEDURE a") && c.content.contains("PROCEDURE b"))
        assertEquals(1, c.startLine)
        assertEquals(4, c.endLine, "ends at 'END pkg;' (line 4); the '/' is dropped")
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
