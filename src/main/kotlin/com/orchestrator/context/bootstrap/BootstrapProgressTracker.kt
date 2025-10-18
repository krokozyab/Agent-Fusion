package com.orchestrator.context.bootstrap

import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.utils.Logger
import java.nio.file.Path
import java.sql.Connection

/**
 * Persists and retrieves bootstrap indexing progress to allow for resumability and progress monitoring.
 */
class BootstrapProgressTracker {

    private val log = Logger.logger("com.orchestrator.context.bootstrap.BootstrapProgressTracker")

    companion object {
        const val TABLE_NAME = "bootstrap_progress"
    }

    init {
        ContextDatabase.withConnection { conn ->
            ensureTable(conn)
        }
    }

    /**
     * Initializes the progress tracker by clearing any existing state and inserting the new set of
     * files to be processed.
     */
    fun initProgress(files: List<Path>) {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { it.execute("DELETE FROM " + TABLE_NAME) }
        }
        if (files.isNotEmpty()) {
            ContextDatabase.withConnection { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("INSERT INTO " + TABLE_NAME + " (path, status) VALUES (?, 'PENDING')").use { ps ->
                        for (file in files) {
                            ps.setString(1, file.toAbsolutePath().normalize().toString())
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                    conn.commit()
                } catch (t: Throwable) {
                    conn.rollback()
                    log.error("Failed to initialize bootstrap progress", t)
                    throw t
                } finally {
                    conn.autoCommit = true
                }
            }
        }
    }

    /** Marks the given file path as currently being processed. */
    fun markProcessing(path: Path) {
        updateStatus(path, "PROCESSING")
    }

    /** Marks the given file path as successfully completed. */
    fun markCompleted(path: Path) {
        updateStatus(path, "COMPLETED")
    }

    /** Marks the given file path as failed and records the error message. */
    fun markFailed(path: Path, error: String) {
        updateStatus(path, "FAILED", error)
    }

    /** Returns a summary of the current progress. */
    fun getProgress(): ProgressStats = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT status, COUNT(*) FROM " + TABLE_NAME + " GROUP BY status").use { ps ->
            ps.executeQuery().use { rs ->
                val counts = mutableMapOf<String, Int>()
                while (rs.next()) {
                    counts[rs.getString(1)] = rs.getInt(2)
                }
                ProgressStats(
                    total = counts.values.sum(),
                    completed = counts.getOrDefault("COMPLETED", 0),
                    pending = counts.getOrDefault("PENDING", 0),
                    processing = counts.getOrDefault("PROCESSING", 0),
                    failed = counts.getOrDefault("FAILED", 0)
                )
            }
        }
    }

    /** Returns the list of file paths that have not yet been successfully processed. */
    fun getRemaining(): List<Path> = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT path FROM " + TABLE_NAME + " WHERE status != 'COMPLETED'").use { ps ->
            ps.executeQuery().use { rs ->
                val remaining = mutableListOf<Path>()
                while (rs.next()) {
                    remaining.add(Path.of(rs.getString(1)))
                }
                remaining
            }
        }
    }

    private fun updateStatus(path: Path, status: String, error: String? = null) = ContextDatabase.withConnection { conn ->
        val sql = if (error != null) {
            "UPDATE " + TABLE_NAME + " SET status = ?, error = ? WHERE path = ?"
        } else {
            "UPDATE " + TABLE_NAME + " SET status = ? WHERE path = ?"
        }
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, status)
            if (error != null) {
                ps.setString(2, error)
                ps.setString(3, path.toAbsolutePath().normalize().toString())
            } else {
                ps.setString(2, path.toAbsolutePath().normalize().toString())
            }
            ps.executeUpdate()
        }
    }

    /**
     * Ensures the bootstrap_progress table exists without dropping existing data.
     * This allows the service to resume interrupted indexing operations.
     */
    private fun ensureTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS ${TABLE_NAME} (
                    path VARCHAR PRIMARY KEY,
                    status VARCHAR NOT NULL,
                    error VARCHAR
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Drops and recreates the bootstrap_progress table.
     * Only use this when you need to completely reset bootstrap state.
     */
    private fun recreateTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS " + TABLE_NAME)
            stmt.execute(
                """
                CREATE TABLE ${TABLE_NAME} (
                    path VARCHAR PRIMARY KEY,
                    status VARCHAR NOT NULL,
                    error VARCHAR
                )
                """.trimIndent()
            )
        }
    }

    /**
     * Clears all bootstrap progress. Use this to force a complete reindex.
     */
    fun reset() {
        ContextDatabase.withConnection { conn ->
            recreateTable(conn)
        }
        log.info("Bootstrap progress reset - next startup will do full reindex")
    }
}

data class ProgressStats(
    val total: Int,
    val completed: Int,
    val pending: Int,
    val processing: Int,
    val failed: Int
)
