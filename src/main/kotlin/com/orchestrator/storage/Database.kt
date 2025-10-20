package com.orchestrator.storage

import com.orchestrator.storage.schema.Schema
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.duckdb.DuckDBDriver
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    private const val DEFAULT_POOL_SIZE = 4

    private val initialized = AtomicBoolean(false)
    private val schemaInitialized = AtomicBoolean(false)
    private val dataSourceRef = AtomicReference<HikariDataSource?>()
    private val configLock = Any()

    private val shutdownHook = Thread {
        try {
            shutdown()
        } catch (_: Throwable) {
            // swallow
        }
    }

    /** Obtain a JDBC connection from the pooled data source. Caller is responsible for closing it. */
    fun getConnection(): Connection {
        val dataSource = ensureDataSource()
        return dataSource.connection
    }

    /** Execute a simple health check query. */
    fun isHealthy(): Boolean {
        return try {
            withConnection { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT 1").use { rs ->
                        rs.next() && rs.getInt(1) == 1
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Gracefully close the data source and remove shutdown hook. */
    fun shutdown() {
        val dataSource = dataSourceRef.getAndSet(null) ?: return

        // Attempt a checkpoint before closing to flush WAL.
        runCatching {
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("CHECKPOINT") }
            }
        }.onFailure {
            if (it !is SQLException) {
                System.err.println("Warning: Failed to checkpoint database on shutdown: ${it.message}")
            }
        }

        runCatching { dataSource.close() }

        schemaInitialized.set(false)

        if (initialized.compareAndSet(true, false)) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (_: IllegalStateException) {
                // JVM is probably already shutting down.
            }
        }
    }

    /** Convenience helper to use the connection. */
    fun <T> withConnection(block: (Connection) -> T): T {
        return getConnection().use { conn -> block(conn) }
    }

    /**
     * FOR TESTING ONLY: Overrides the default database path to enable isolated in-memory testing.
     * This is not thread-safe and should only be called from test `setUp` methods.
     */
    fun overrideForTests(dbPath: String = "") {
        shutdown()
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = "jdbc:duckdb:$dbPath"
            driverClassName = DuckDBDriver::class.qualifiedName
            maximumPoolSize = 1
            poolName = "orchestrator-duckdb-test"
        }
        val dataSource = HikariDataSource(hikariConfig)
        if (schemaInitialized.compareAndSet(false, true)) {
            dataSource.connection.use { initializeSchema(it) }
        }
        dataSourceRef.set(dataSource)
    }

    private fun initializeSchema(conn: Connection) {
        // Execute all schema DDL in a transaction for atomicity
        val prevAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                for (sql in Schema.statements) {
                    if (sql.trimStart().startsWith("COMMENT ON", ignoreCase = true)) continue
                    st.execute(sql)
                }
            }
            conn.commit()
        } catch (e: SQLException) {
            try { conn.rollback() } catch (_: SQLException) {}
            throw e
        } finally {
            conn.autoCommit = prevAutoCommit
        }
    }

    private fun ensureDataSource(): HikariDataSource {
        dataSourceRef.get()?.let { return it }

        synchronized(configLock) {
            dataSourceRef.get()?.let { return it }

            // Ensure DuckDB driver is available (required for some environments)
            try {
                Class.forName(DuckDBDriver::class.qualifiedName)
            } catch (_: ClassNotFoundException) {
                // DuckDB DriverManager can still resolve via SPI, proceed
            }

            val config = loadConfig()
            val dbPath = config.getStringOrDefault("$CONF_ROOT.path", DEFAULT_DB_PATH)
            val initSchema = config.getBooleanOrDefault("$CONF_ROOT.initSchema", true)
            val poolSize = config.getIntOrDefault("$CONF_ROOT.poolSize", DEFAULT_POOL_SIZE)

            ensureParentDirectory(dbPath)

            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:duckdb:$dbPath"
                driverClassName = DuckDBDriver::class.qualifiedName
                maximumPoolSize = poolSize
                minimumIdle = 1
                isAutoCommit = true
                poolName = "orchestrator-duckdb"
            }

            val dataSource = HikariDataSource(hikariConfig)

            if (initialized.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(shutdownHook)
            }

            if (initSchema && schemaInitialized.compareAndSet(false, true)) {
                dataSource.connection.use { initializeSchema(it) }
            }

            dataSourceRef.set(dataSource)
            return dataSource
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

private fun Config.getIntOrDefault(path: String, default: Int): Int =
    if (this.hasPath(path)) this.getInt(path) else default
