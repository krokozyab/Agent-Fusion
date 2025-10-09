package com.orchestrator.storage

import com.orchestrator.storage.schema.Schema
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.duckdb.DuckDBDriver
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Database connection manager for DuckDB.
 * - Single-connection pattern
 * - Initializes schema on first run (idempotent)
 * - Reads configuration from HOCON (application.conf), with sane defaults
 * - Provides health check and graceful shutdown
 */
object Database {
    private const val DEFAULT_DB_PATH = "data/orchestrator.duckdb"
    private const val CONF_ROOT = "orchestrator.storage.duckdb"

    private val initialized = AtomicBoolean(false)
    @Volatile private var connection: Connection? = null

    private val shutdownHook = Thread {
        try {
            shutdown()
        } catch (_: Throwable) {
            // swallow
        }
    }

    /** Obtain the global JDBC connection, initializing on first access. */
    @Synchronized
    fun getConnection(): Connection {
        if (connection != null && !connection!!.isClosed) return connection!!
        // Ensure DuckDB driver is available
        try {
            Class.forName(DuckDBDriver::class.qualifiedName)
        } catch (_: ClassNotFoundException) {
            // DuckDB DriverManager can still resolve via SPI, proceed
        }

        val config = loadConfig()
        val dbPath = config.getStringOrDefault("$CONF_ROOT.path", DEFAULT_DB_PATH)
        val initSchema = config.getBooleanOrDefault("$CONF_ROOT.initSchema", true)

        // Ensure parent directory exists (file will be auto-created by DuckDB if missing)
        ensureParentDirectory(dbPath)

        val jdbcUrl = "jdbc:duckdb:$dbPath"
        val conn = DriverManager.getConnection(jdbcUrl)
        connection = conn

        // Register shutdown hook once
        if (initialized.compareAndSet(false, true)) {
            Runtime.getRuntime().addShutdownHook(shutdownHook)
        }

        if (initSchema) {
            initializeSchema(conn)
        }
        return conn
    }

    /** Execute a simple health check query. */
    fun isHealthy(): Boolean {
        return try {
            val conn = getConnection()
            conn.createStatement().use { st ->
                st.executeQuery("SELECT 1").use { rs ->
                    rs.next() && rs.getInt(1) == 1
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Gracefully close the connection and remove shutdown hook. */
    @Synchronized
    fun shutdown() {
        try {
            connection?.let { conn ->
                if (!conn.isClosed) conn.close()
            }
        } catch (_: SQLException) {
            // ignore on shutdown
        } finally {
            connection = null
            if (initialized.compareAndSet(true, false)) {
                // Best-effort to remove hook if still present
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook) } catch (_: IllegalStateException) { }
            }
        }
    }

    /** Convenience helper to use the connection. */
    fun <T> withConnection(block: (Connection) -> T): T {
        val conn = getConnection()
        return block(conn)
    }

    private fun initializeSchema(conn: Connection) {
        // Execute all schema DDL in a transaction for atomicity
        val prevAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                for (sql in Schema.statements) {
                    st.addBatch(sql)
                }
                st.executeBatch()
            }
            conn.commit()
        } catch (e: SQLException) {
            try { conn.rollback() } catch (_: SQLException) {}
            throw e
        } finally {
            conn.autoCommit = prevAutoCommit
        }
    }

    private fun ensureParentDirectory(dbPath: String) {
        val path = Path.of(dbPath)
        val parent = path.parent ?: return
        if (!Files.exists(parent)) {
            Files.createDirectories(parent)
        }
    }

    private fun loadConfig(): Config = try {
        ConfigFactory.load()
    } catch (_: Exception) {
        ConfigFactory.empty()
    }
}

// --- Small extension helpers for config ---
private fun Config.getStringOrDefault(path: String, default: String): String =
    if (this.hasPath(path)) this.getString(path) else default

private fun Config.getBooleanOrDefault(path: String, default: Boolean): Boolean =
    if (this.hasPath(path)) this.getBoolean(path) else default
