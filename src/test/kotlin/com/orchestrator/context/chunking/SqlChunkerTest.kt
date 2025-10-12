package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SqlChunkerTest {
    
    private val chunker = SqlChunker()
    
    @Test
    fun `chunks simple SQL statements`() {
        val sql = """
            CREATE TABLE users (id INT, name VARCHAR(100));
            INSERT INTO users VALUES (1, 'Alice');
            SELECT * FROM users;
        """.trimIndent()
        
        val chunks = chunker.chunk(sql, "schema.sql")
        
        assertEquals(3, chunks.size)
        chunks.forEach { assertEquals(ChunkKind.SQL_STATEMENT, it.kind) }
        
        assertTrue(chunks[0].summary!!.contains("CREATE TABLE"))
        assertTrue(chunks[0].summary!!.contains("users"))
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
        
        val chunks = chunker.chunk(sql, "schema.sql")
        
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("FOREIGN KEY"))
        assertEquals("CREATE TABLE orders", chunks[0].summary)
    }
    
    @Test
    fun `labels CREATE statements correctly`() {
        val sql = """
            CREATE TABLE users (id INT);
            CREATE VIEW active_users AS SELECT * FROM users;
            CREATE INDEX idx_name ON users(name);
        """.trimIndent()
        
        val chunks = chunker.chunk(sql, "schema.sql")
        
        assertEquals(3, chunks.size)
        assertEquals("CREATE TABLE users", chunks[0].summary)
        assertTrue(chunks[1].summary!!.contains("CREATE VIEW"))
        assertTrue(chunks[2].summary!!.contains("CREATE INDEX"))
    }
    
    @Test
    fun `labels DML statements correctly`() {
        val sql = """
            INSERT INTO users (name) VALUES ('Bob');
            UPDATE users SET name = 'Robert' WHERE id = 1;
            DELETE FROM users WHERE id = 2;
        """.trimIndent()
        
        val chunks = chunker.chunk(sql, "data.sql")
        
        assertEquals(3, chunks.size)
        assertTrue(chunks[0].summary!!.contains("INSERT INTO"))
        assertTrue(chunks[1].summary!!.contains("UPDATE"))
        assertTrue(chunks[2].summary!!.contains("DELETE FROM"))
    }
    
    @Test
    fun `handles SELECT statements`() {
        val sql = """
            SELECT * FROM users WHERE active = true;
            SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id;
        """.trimIndent()
        
        val chunks = chunker.chunk(sql, "queries.sql")
        
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].summary!!.contains("SELECT"))
        assertTrue(chunks[0].summary!!.contains("users"))
    }
    
    @Test
    fun `handles DROP statements`() {
        val sql = """
            DROP TABLE IF EXISTS temp_users;
            DROP VIEW old_view;
        """.trimIndent()
        
        val chunks = chunker.chunk(sql, "cleanup.sql")
        
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].summary!!.contains("DROP TABLE"))
        assertTrue(chunks[1].summary!!.contains("DROP VIEW"))
    }
    
    @Test
    fun `handles ALTER statements`() {
        val sql = """
            ALTER TABLE users ADD COLUMN email VARCHAR(255);
            ALTER TABLE orders DROP COLUMN notes;
        """.trimIndent()
        
        val chunks = chunker.chunk(sql, "migrations.sql")
        
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].summary!!.contains("ALTER TABLE"))
        assertTrue(chunks[0].summary!!.contains("users"))
    }
    
    @Test
    fun `handles statements without semicolons`() {
        val sql = """
            CREATE TABLE test (id INT)
        """.trimIndent()
        
        val chunks = chunker.chunk(sql, "test.sql")
        
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("CREATE TABLE"))
    }
    
    @Test
    fun `handles empty SQL`() {
        val sql = ""
        
        val chunks = chunker.chunk(sql, "empty.sql")
        
        assertEquals(0, chunks.size)
    }
    
    @Test
    fun `handles transaction statements`() {
        val sql = """
            BEGIN;
            INSERT INTO users VALUES (1, 'Test');
            COMMIT;
        """.trimIndent()
        
        val chunks = chunker.chunk(sql, "transaction.sql")
        
        assertEquals(3, chunks.size)
        assertTrue(chunks[0].summary!!.contains("BEGIN"))
        assertTrue(chunks[2].summary!!.contains("COMMIT"))
    }
}
