package com.orchestrator.context.storage

import com.orchestrator.context.config.StorageConfig
import com.orchestrator.utils.Logger
import org.duckdb.DuckDBDriver
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dedicated DuckDB connection manager for the context subsystem.
 *
 * Responsibilities:
 * - Initialize the database file and schema on first use.
 * - Provide a single shared JDBC connection (DuckDB is embedded and optimised for this pattern).
 * - Offer helper wrappers for transactional execution and connection access.
 * - Expose lifecycle hooks for shutdown.
 */
object ContextDatabase {
    private val log = Logger.logger("com.orchestrator.context.storage.ContextDatabase")

    private val initialized = AtomicBoolean(false)
    private val guard = ReentrantLock()
    private val connectionLock = ReentrantLock()

    @Volatile
    private var connection: Connection? = null

    private lateinit var storageConfig: StorageConfig

    private val shutdownHook = Thread {
        try {
            shutdown()
        } catch (t: Throwable) {
            log.warn("Error during context database shutdown hook: ${t.message}", t)
        }
    }

    /**
     * Configure and initialize the context database. Idempotent.
     */
    fun initialize(config: StorageConfig) {
        if (::storageConfig.isInitialized && storageConfig.dbPath != config.dbPath) {
            shutdown()
        }
        storageConfig = config
        ensureDriverLoaded()
        guard.withLock {
            if (connection != null && !connection!!.isClosed) {
                return
            }
            val conn = openConnection(config.dbPath)
            applyPragmas(conn)
            if (config.backupEnabled) {
                log.info("Context database backups enabled (interval: ${config.backupIntervalHours}h)")
            }
            ensureSchema(conn)
            connection = conn
            if (initialized.compareAndSet(false, true)) {
                Runtime.getRuntime().addShutdownHook(shutdownHook)
            }
        }
    }

    /** Obtain the active connection, initializing with default configuration if required. */
    fun getConnection(): Connection {
        val existing = connection
        if (existing != null && !existing.isClosed) return existing
        guard.withLock {
            val current = connection
            if (current != null && !current.isClosed) {
                return current
            }
            val config = if (::storageConfig.isInitialized) storageConfig else StorageConfig()
            initialize(config)
            return connection ?: throw IllegalStateException("Context database failed to initialize")
        }
    }

    /** Convenience helper to work with the connection. */
    fun <T> withConnection(block: (Connection) -> T): T {
        val conn = getConnection()
        connectionLock.lock()
        return try {
            block(conn)
        } finally {
            connectionLock.unlock()
        }
    }

    /** Execute [block] within a JDBC transaction on the context database. */
    fun <T> transaction(block: (Connection) -> T): T {
        val conn = getConnection()
        connectionLock.lock()
        val previous = conn.autoCommit
        conn.autoCommit = false
        return try {
            val result = block(conn)
            conn.commit()
            result
        } catch (t: Throwable) {
            try {
                conn.rollback()
            } catch (rollback: SQLException) {
                log.error("Failed to rollback context transaction: ${rollback.message}", rollback)
            }
            throw t
        } finally {
            conn.autoCommit = previous
            connectionLock.unlock()
        }
    }

    /**
     * Apply the schema definition (idempotent). Intended for bootstrapping and migrations.
     */
    fun executeSchema(statements: List<String>) {
        val conn = getConnection()
        applyStatements(conn, statements)
    }

    /** Shutdown and release the JDBC connection. */
    fun shutdown() {
        guard.withLock {
            try {
                connection?.let { conn ->
                    if (!conn.isClosed) {
                        try {
                            conn.createStatement().use { it.execute("CHECKPOINT") }
                        } catch (checkpoint: SQLException) {
                            log.warn("Context DB checkpoint failed on shutdown: ${checkpoint.message}", checkpoint)
                        }
                        conn.close()
                    }
                }
            } catch (e: SQLException) {
                log.warn("Error closing context database: ${e.message}", e)
            } finally {
                connection = null
                if (initialized.compareAndSet(true, false)) {
                    runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
                }
            }
        }
    }

    private fun ensureDriverLoaded() {
        runCatching { Class.forName(DuckDBDriver::class.qualifiedName) }
            .onFailure { log.debug("DuckDB driver class not found, relying on SPI: ${it.message}") }
    }

    private fun openConnection(path: String): Connection {
        ensureParentDirectory(path)
        val url = "jdbc:duckdb:$path"
        return DriverManager.getConnection(url).apply {
            autoCommit = true
        }
    }

    private fun applyPragmas(conn: Connection) {
        conn.createStatement().use { st ->
            // Improve write throughput for incremental indexing
            val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2).coerceAtMost(8)
            st.execute("PRAGMA threads=$threads")
        }
    }

    private fun ensureSchema(conn: Connection) {
        // Placeholder for future schema module integration. For now ensure minimal tables exist.
        val statements = listOf(
            "CREATE SEQUENCE IF NOT EXISTS file_state_seq START 1",
            "CREATE SEQUENCE IF NOT EXISTS chunks_seq START 1",
            "CREATE SEQUENCE IF NOT EXISTS embeddings_seq START 1",
            "CREATE SEQUENCE IF NOT EXISTS links_seq START 1",
            "CREATE SEQUENCE IF NOT EXISTS symbols_seq START 1",
            "CREATE SEQUENCE IF NOT EXISTS usage_metrics_seq START 1",
            "CREATE SEQUENCE IF NOT EXISTS project_config_seq START 1",
            """
            CREATE TABLE IF NOT EXISTS file_state (
                file_id               BIGINT PRIMARY KEY,
                rel_path              VARCHAR NOT NULL UNIQUE,
                content_hash          VARCHAR,
                size_bytes            BIGINT NOT NULL,
                mtime_ns              BIGINT NOT NULL,
                language              VARCHAR,
                kind                  VARCHAR,
                fingerprint           VARCHAR,
                indexed_at            TIMESTAMP,
                is_deleted            BOOLEAN NOT NULL DEFAULT FALSE
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS chunks (
                chunk_id              BIGINT PRIMARY KEY,
                file_id               BIGINT NOT NULL,
                ordinal               INTEGER NOT NULL,
                kind                  VARCHAR NOT NULL,
                start_line            INTEGER NOT NULL,
                end_line              INTEGER NOT NULL,
                token_count           INTEGER,
                content               TEXT NOT NULL,
                summary               TEXT,
                created_at            TIMESTAMP NOT NULL,
                FOREIGN KEY(file_id) REFERENCES file_state(file_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS embeddings (
                embedding_id          BIGINT PRIMARY KEY,
                chunk_id              BIGINT NOT NULL,
                model                 VARCHAR NOT NULL,
                dimensions            INTEGER NOT NULL,
                vector                TEXT NOT NULL,
                created_at            TIMESTAMP NOT NULL,
                FOREIGN KEY(chunk_id) REFERENCES chunks(chunk_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS links (
                link_id               BIGINT PRIMARY KEY,
                source_chunk_id       BIGINT NOT NULL,
                target_file_id        BIGINT NOT NULL,
                target_chunk_id       BIGINT,
                link_type             VARCHAR NOT NULL,
                label                 VARCHAR,
                score                 DOUBLE,
                created_at            TIMESTAMP NOT NULL,
                FOREIGN KEY(source_chunk_id) REFERENCES chunks(chunk_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS symbols (
                symbol_id            BIGINT PRIMARY KEY,
                file_id              BIGINT NOT NULL,
                chunk_id             BIGINT,
                symbol_type          VARCHAR NOT NULL,
                name                 VARCHAR NOT NULL,
                qualified_name       VARCHAR,
                signature            TEXT,
                language             VARCHAR,
                start_line           INTEGER,
                end_line             INTEGER,
                created_at           TIMESTAMP NOT NULL,
                FOREIGN KEY(file_id) REFERENCES file_state(file_id),
                FOREIGN KEY(chunk_id) REFERENCES chunks(chunk_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS usage_metrics (
                metric_id             BIGINT PRIMARY KEY,
                task_id               VARCHAR,
                snippets_returned     INTEGER,
                total_tokens          INTEGER,
                retrieval_latency_ms  INTEGER,
                created_at            TIMESTAMP NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS project_config (
                config_id             BIGINT PRIMARY KEY DEFAULT nextval('project_config_seq'),
                scope                 TEXT NOT NULL,
                include_globs         JSON,
                exclude_globs         JSON,
                root_paths            JSON,
                created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent()
        )
        applyStatements(conn, statements)
    }

    private fun ensureParentDirectory(path: String) {
        val dbPath = Path.of(path)
        val parent = dbPath.parent ?: return
        if (!Files.exists(parent)) {
            Files.createDirectories(parent)
        }
    }

    private fun applyStatements(conn: Connection, statements: List<String>) {
        val original = conn.autoCommit
        conn.autoCommit = false
        try {
            conn.createStatement().use { st ->
                statements.forEach { sql ->
                    if (sql.trimStart().startsWith("COMMENT ON", ignoreCase = true)) return@forEach
                    st.execute(sql)
                }
            }
            conn.commit()
        } catch (e: SQLException) {
            runCatching { conn.rollback() }
            throw e
        } finally {
            conn.autoCommit = original
        }
    }
}
