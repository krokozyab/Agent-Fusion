package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlChunkerTest {

    @Test
    fun `empty content returns empty list`() {
        val chunker = SqlChunker()
        val chunks = chunker.chunk("", "test.sql")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `blank content returns empty list`() {
        val chunker = SqlChunker()
        val chunks = chunker.chunk("   \n\n  \n", "test.sql")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `chunks CREATE TABLE statement`() {
        val sql = "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100));"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(1, chunks.size)
        assertEquals(ChunkKind.SQL_STATEMENT, chunks[0].kind)
        assertEquals("CREATE TABLE users", chunks[0].summary)
        assertTrue(chunks[0].content.contains("CREATE TABLE users"))
    }

    @Test
    fun `chunks multiple statements`() {
        val sql = """
            CREATE TABLE users (id INT);
            INSERT INTO users VALUES (1, 'Alice');
            SELECT * FROM users;
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(ChunkKind.SQL_STATEMENT, it.kind) }
        assertTrue(chunks[0].summary!!.contains("CREATE TABLE"))
        assertTrue(chunks[1].summary!!.contains("INSERT INTO"))
        assertTrue(chunks[2].summary!!.contains("SELECT"))
    }

    @Test
    fun `preserves line comments`() {
        val sql = """
            -- Create users table
            CREATE TABLE users (
                id INT PRIMARY KEY,
                name VARCHAR(100)
            );
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("-- Create users table"))
        assertTrue(chunks[0].content.contains("CREATE TABLE"))
    }

    @Test
    fun `preserves block comments`() {
        val sql = """
            /*
             * Users table schema
             * Version 1.0
             */
            CREATE TABLE users (id INT);
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("Users table schema"))
        assertTrue(chunks[0].content.contains("CREATE TABLE"))
    }

    @Test
    fun `handles multi-line statements`() {
        val sql = """
            CREATE TABLE orders (
                id INT PRIMARY KEY,
                user_id INT,
                total DECIMAL(10,2),
                created_at TIMESTAMP,
                FOREIGN KEY (user_id) REFERENCES users(id)
            );
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("FOREIGN KEY"))
        assertEquals("CREATE TABLE orders", chunks[0].summary)
    }

    @Test
    fun `labels CREATE TABLE statements`() {
        val sql = "CREATE TABLE IF NOT EXISTS users (id INT);"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(1, chunks.size)
        assertEquals("CREATE TABLE users", chunks[0].summary)
    }

    @Test
    fun `labels CREATE VIEW statements`() {
        val sql = "CREATE VIEW active_users AS SELECT * FROM users WHERE active = true;"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("CREATE VIEW"))
        assertTrue(chunks[0].summary!!.contains("active_users"))
    }

    @Test
    fun `labels CREATE INDEX statements`() {
        val sql = "CREATE INDEX idx_name ON users(name);"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("CREATE INDEX"))
    }

    @Test
    fun `labels INSERT statements`() {
        val sql = "INSERT INTO users (name) VALUES ('Bob');"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "data.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("INSERT INTO"))
        assertTrue(chunks[0].summary!!.contains("users"))
    }

    @Test
    fun `labels UPDATE statements`() {
        val sql = "UPDATE users SET name = 'Robert' WHERE id = 1;"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "data.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("UPDATE"))
        assertTrue(chunks[0].summary!!.contains("users"))
    }

    @Test
    fun `labels DELETE statements`() {
        val sql = "DELETE FROM users WHERE id = 2;"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "data.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("DELETE FROM"))
        assertTrue(chunks[0].summary!!.contains("users"))
    }

    @Test
    fun `labels SELECT statements`() {
        val sql = "SELECT * FROM users WHERE active = true;"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "queries.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("SELECT"))
        assertTrue(chunks[0].summary!!.contains("users"))
    }

    @Test
    fun `labels DROP statements`() {
        val sql = """
            DROP TABLE IF EXISTS temp_users;
            DROP VIEW old_view;
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "cleanup.sql")

        assertEquals(2, chunks.size)
        assertTrue(chunks[0].summary!!.contains("DROP TABLE"))
        assertTrue(chunks[1].summary!!.contains("DROP VIEW"))
    }

    @Test
    fun `labels ALTER statements`() {
        val sql = "ALTER TABLE users ADD COLUMN email VARCHAR(255);"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "migrations.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("ALTER TABLE"))
        assertTrue(chunks[0].summary!!.contains("users"))
    }

    @Test
    fun `handles transaction statements`() {
        val sql = """
            BEGIN;
            INSERT INTO users VALUES (1, 'Test');
            COMMIT;
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "transaction.sql")

        assertEquals(3, chunks.size)
        assertTrue(chunks[0].summary!!.contains("BEGIN"))
        assertTrue(chunks[2].summary!!.contains("COMMIT"))
    }

    @Test
    fun `handles ROLLBACK statement`() {
        val sql = "ROLLBACK;"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "transaction.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("ROLLBACK"))
    }

    @Test
    fun `handles statements without semicolons`() {
        val sql = "CREATE TABLE test (id INT)"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "test.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("CREATE TABLE"))
    }

    @Test
    fun `handles GRANT statements`() {
        val sql = "GRANT SELECT ON users TO readonly_user;"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "permissions.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("GRANT"))
    }

    @Test
    fun `handles REVOKE statements`() {
        val sql = "REVOKE SELECT ON users FROM readonly_user;"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "permissions.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("REVOKE"))
    }

    @Test
    fun `chunk ordinals are sequential`() {
        val sql = """
            CREATE TABLE users (id INT);
            CREATE TABLE orders (id INT);
            CREATE TABLE products (id INT);
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.ordinal)
        }
    }

    @Test
    fun `token estimation returns positive values`() {
        val chunker = SqlChunker()
        val sql = "CREATE TABLE users (id INT PRIMARY KEY);"
        val chunks = chunker.chunk(sql, "test.sql")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks[0].tokenEstimate!! > 0)
    }

    @Test
    fun `handles complex SELECT with JOIN`() {
        val sql = """
            SELECT u.name, o.total
            FROM users u
            JOIN orders o ON u.id = o.user_id
            WHERE u.active = true;
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "queries.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("JOIN"))
        assertTrue(chunks[0].summary!!.contains("SELECT"))
    }

    @Test
    fun `handles CREATE PROCEDURE`() {
        val sql = """
            CREATE PROCEDURE get_user(IN user_id INT)
            BEGIN
                SELECT * FROM users WHERE id = user_id;
            END;
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "procedures.sql")

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks[0].summary!!.contains("CREATE") || chunks[0].summary!!.contains("get_user"))
    }

    @Test
    fun `handles CREATE FUNCTION`() {
        val sql = """
            CREATE FUNCTION calculate_total(price DECIMAL, quantity INT)
            RETURNS DECIMAL
            BEGIN
                RETURN price * quantity;
            END;
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "functions.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("CREATE FUNCTION"))
    }

    @Test
    fun `handles CREATE TRIGGER`() {
        val sql = """
            CREATE TRIGGER update_timestamp
            BEFORE UPDATE ON users
            FOR EACH ROW
            SET NEW.updated_at = NOW();
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "triggers.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("CREATE TRIGGER"))
    }

    @Test
    fun `handles nested block comments`() {
        val sql = """
            /* Outer comment
             * /* Not actually nested in standard SQL */
             * End comment
             */
            CREATE TABLE test (id INT);
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "test.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("Outer comment"))
    }

    @Test
    fun `handles mixed comments and code`() {
        val sql = """
            -- Line comment
            /* Block comment */
            CREATE TABLE users (
                id INT, -- inline comment
                name VARCHAR(100) /* inline block */
            );
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "test.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("Line comment"))
        assertTrue(chunks[0].content.contains("Block comment"))
        assertTrue(chunks[0].content.contains("inline comment"))
    }

    @Test
    fun `handles case insensitive keywords`() {
        val sql = "create table Users (Id int);"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "test.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.contains("CREATE TABLE"))
    }

    @Test
    fun `handles statements with string literals containing semicolons`() {
        val sql = "INSERT INTO messages VALUES ('Hello; World');"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "data.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("Hello; World"))
    }

    @Test
    fun `stress test with many statements`() {
        val statements = List(100) { i ->
            "CREATE TABLE table_$i (id INT PRIMARY KEY);"
        }
        val sql = statements.joinToString("\n")

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(100, chunks.size)
        chunks.forEach {
            assertEquals(ChunkKind.SQL_STATEMENT, it.kind)
            assertTrue(it.tokenEstimate!! > 0)
        }
    }

    @Test
    fun `handles empty lines between statements`() {
        val sql = """
            CREATE TABLE users (id INT);


            CREATE TABLE orders (id INT);
        """.trimIndent()

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        assertEquals(2, chunks.size)
    }

    @Test
    fun `chunks have non-null timestamps`() {
        val sql = "CREATE TABLE users (id INT);"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "schema.sql")

        chunks.forEach {
            assertTrue(it.createdAt != null)
        }
    }

    @Test
    fun `handles statement with no table name`() {
        val sql = "COMMIT;"

        val chunker = SqlChunker()
        val chunks = chunker.chunk(sql, "test.sql")

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].summary!!.isNotEmpty())
    }
}
