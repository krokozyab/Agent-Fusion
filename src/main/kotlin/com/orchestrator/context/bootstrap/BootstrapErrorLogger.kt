package com.orchestrator.context.bootstrap

import com.orchestrator.context.storage.ContextDatabase
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

/**
 * Logs and retrieves file processing errors that occur during the bootstrap process.
 */
class BootstrapErrorLogger {

    companion object {
        const val TABLE_NAME = "bootstrap_errors"
    }

    init {
        ContextDatabase.withConnection { conn ->
            recreateTable(conn)
        }
    }

    /** Logs a file processing error to the database. */
    fun logError(path: Path, error: Throwable) = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("INSERT INTO $TABLE_NAME (path, error_message, stack_trace, timestamp) VALUES (?, ?, ?, ?)").use { ps ->
            ps.setString(1, path.toAbsolutePath().normalize().toString())
            ps.setString(2, error.message ?: "Unknown error")
            ps.setString(3, error.stackTraceToString())
            ps.setTimestamp(4, Timestamp.from(Instant.now()))
            ps.executeUpdate()
        }
    }

    /** Returns all logged errors. */
    fun getErrors(): List<BootstrapError> = ContextDatabase.withConnection { conn ->
        conn.prepareStatement("SELECT * FROM $TABLE_NAME").use { ps ->
            ps.executeQuery().use { rs ->
                val errors = mutableListOf<BootstrapError>()
                while (rs.next()) {
                    errors.add(
                        BootstrapError(
                            path = Path.of(rs.getString("path")),
                            errorMessage = rs.getString("error_message"),
                            stackTrace = rs.getString("stack_trace"),
                            timestamp = rs.getTimestamp("timestamp").toInstant()
                        )
                    )
                }
                errors
            }
        }
    }

    /** Clears all logged errors. */
    fun clearErrors() = ContextDatabase.withConnection { conn ->
        conn.createStatement().use { it.execute("DELETE FROM $TABLE_NAME") }
    }

    /**
     * Returns a list of all file paths that have logged errors and then clears the errors.
     * This is useful for retrying failed files.
     */
    fun retryFailed(): List<Path> {
        val errors = getErrors()
        val paths = errors.map { it.path }
        clearErrors()
        return paths
    }

    private fun recreateTable(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS $TABLE_NAME")
            stmt.execute(
                """
                CREATE TABLE $TABLE_NAME (
                    path VARCHAR PRIMARY KEY,
                    error_message VARCHAR NOT NULL,
                    stack_trace VARCHAR NOT NULL,
                    timestamp TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )
        }
    }
}

data class BootstrapError(
    val path: Path,
    val errorMessage: String,
    val stackTrace: String,
    val timestamp: Instant
)
