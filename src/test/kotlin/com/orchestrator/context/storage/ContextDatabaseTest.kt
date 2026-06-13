package com.orchestrator.context.storage

import com.orchestrator.context.config.StorageConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ContextDatabaseTest {

    @Test
    fun `initializes schema and persists data`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        val config = StorageConfig(dbPath = dbPath)

        ContextDatabase.initialize(config)
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    INSERT INTO file_state (file_id, rel_path, abs_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                    VALUES (1, 'src/Main.kt', '/project/src/Main.kt', 'hash', 10, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
                    """.trimIndent()
                )
                st.execute(
                    """
                    INSERT INTO chunks (chunk_id, file_id, ordinal, kind, start_line, end_line, content, summary, created_at)
                    VALUES (1, 1, 0, 'CODE_FUNCTION', 1, 10, 'fun main() {}', 'main', CURRENT_TIMESTAMP)
                    """.trimIndent()
                )
            }
            conn.prepareStatement("SELECT COUNT(*) FROM file_state").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
            conn.prepareStatement("SELECT COUNT(*) FROM chunks").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }

        ContextDatabase.shutdown()
    }

    @Test
    fun `creates symbols indexes to keep cross-file link building from degrading`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))

        val expected = setOf(
            "idx_symbols_name_lower",
            "idx_symbols_qname_lower",
            "idx_symbols_file",
            "idx_symbols_chunk"
        )
        val found = mutableSetOf<String>()
        ContextDatabase.withConnection { conn ->
            conn.prepareStatement(
                "SELECT index_name FROM duckdb_indexes() WHERE table_name = 'symbols'"
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) found += rs.getString(1)
                }
            }
        }

        assertTrue(
            found.containsAll(expected),
            "expected symbol indexes $expected to exist, found $found"
        )

        ContextDatabase.shutdown()
    }

    @Test
    fun `executeSchema applies statements and leaves the connection usable`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))

        // executeSchema now runs under connectionLock; verify it applies DDL and restores the
        // connection to a usable auto-commit state afterwards (no leaked autoCommit=false).
        ContextDatabase.executeSchema(
            listOf("CREATE TABLE IF NOT EXISTS schema_probe (id INTEGER PRIMARY KEY)")
        )

        ContextDatabase.withConnection { conn ->
            assertTrue(conn.autoCommit, "autoCommit must be restored after executeSchema")
            conn.createStatement().use { it.execute("INSERT INTO schema_probe (id) VALUES (1)") }
            conn.prepareStatement("SELECT COUNT(*) FROM schema_probe").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }

        ContextDatabase.shutdown()
    }

    @Test
    fun `transaction commits and rolls back`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))

        ContextDatabase.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO file_state (file_id, rel_path, abs_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                VALUES (10, 'src/File.kt', '/project/src/File.kt', 'hash10', 100, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
                """.trimIndent()
            ).use { it.executeUpdate() }
        }

        kotlin.runCatching {
            ContextDatabase.transaction { conn ->
                conn.prepareStatement("DELETE FROM file_state WHERE file_id = 10").use { it.executeUpdate() }
                error("rollback")
            }
        }

        ContextDatabase.withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM file_state WHERE file_id = 10").use { ps ->
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }

        ContextDatabase.shutdown()
    }
}
