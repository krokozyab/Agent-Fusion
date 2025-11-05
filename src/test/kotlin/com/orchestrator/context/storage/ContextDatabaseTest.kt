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
                    INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                    VALUES (1, 'src/Main.kt', 'hash', 10, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
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
    fun `transaction commits and rolls back`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("context.duckdb").toString()
        ContextDatabase.initialize(StorageConfig(dbPath = dbPath))

        ContextDatabase.transaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO file_state (file_id, rel_path, content_hash, size_bytes, mtime_ns, language, kind, fingerprint, indexed_at, is_deleted)
                VALUES (10, 'src/File.kt', 'hash10', 100, 1, 'kotlin', 'source', 'fp', CURRENT_TIMESTAMP, FALSE)
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
