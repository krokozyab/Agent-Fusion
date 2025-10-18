package com.orchestrator.mcp.tools

import com.orchestrator.context.bootstrap.BootstrapOrchestrator
import com.orchestrator.context.bootstrap.BootstrapErrorLogger
import com.orchestrator.context.bootstrap.BootstrapProgressTracker
import com.orchestrator.context.bootstrap.FilePrioritizer
import com.orchestrator.context.config.BootstrapConfig
import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.discovery.DirectoryScanner
import com.orchestrator.context.indexing.BatchIndexer
import com.orchestrator.context.indexing.FileIndexer
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.utils.Logger
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MCP tool for full context rebuild with safety checks.
 *
 * Safety features:
 * - Requires explicit confirm=true to execute destructive operations
 * - Blocks if another rebuild is in progress
 * - Validates paths before starting
 * - Supports dry-run mode (validateOnly=true)
 * - Async execution with job tracking
 */
class RebuildContextTool(
    private val config: ContextConfig = ContextConfig()
) {
    private val log = Logger.logger("com.orchestrator.mcp.tools.RebuildContextTool")

    data class Params(
        val confirm: Boolean = false,
        val async: Boolean = false,
        val paths: List<String>? = null,
        val validateOnly: Boolean = false,
        val parallelism: Int? = null
    )

    data class Result(
        val mode: String,
        val status: String,
        val jobId: String?,
        val phase: String,
        val totalFiles: Int?,
        val processedFiles: Int?,
        val successfulFiles: Int?,
        val failedFiles: Int?,
        val durationMs: Long?,
        val startedAt: Instant,
        val completedAt: Instant?,
        val message: String?,
        val validationErrors: List<String>?
    )

    data class RebuildJob(
        val jobId: String,
        val paths: List<Path>,
        val startedAt: Instant,
        var status: JobStatus,
        var phase: String = "validation",
        var totalFiles: Int = 0,
        var processedFiles: Int = 0,
        var successfulFiles: Int = 0,
        var failedFiles: Int = 0,
        var error: String? = null
    )

    enum class JobStatus {
        RUNNING, COMPLETED, FAILED
    }

    companion object {
        private val rebuildInProgress = AtomicBoolean(false)
        private val jobs = ConcurrentHashMap<String, RebuildJob>()

        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "rebuild_context params",
          "type": "object",
          "properties": {
            "confirm": {
              "type": ["boolean", "null"],
              "default": false,
              "description": "Must be true to execute destructive rebuild. Safety check."
            },
            "async": {
              "type": ["boolean", "null"],
              "default": false,
              "description": "Run rebuild in background and return jobId immediately"
            },
            "paths": {
              "type": ["array", "null"],
              "items": {"type": "string"},
              "description": "Paths to rebuild. If null, rebuilds all watched paths."
            },
            "validateOnly": {
              "type": ["boolean", "null"],
              "default": false,
              "description": "Dry-run mode. Validate paths and configuration without executing rebuild."
            },
            "parallelism": {
              "type": ["integer", "null"],
              "minimum": 1,
              "description": "Number of parallel workers for indexing"
            }
          },
          "additionalProperties": false
        }
        """

        fun getJob(jobId: String): RebuildJob? = jobs[jobId]

        fun clearCompletedJobs() {
            jobs.entries.removeIf { (_, job) ->
                job.status == JobStatus.COMPLETED || job.status == JobStatus.FAILED
            }
        }
    }

    fun execute(params: Params = Params()): Result {
        val startedAt = Instant.now()

        // Phase 1: Validation
        val validationResult = validateRequest(params)
        if (validationResult.isNotEmpty()) {
            return Result(
                mode = "sync",
                status = "error",
                jobId = null,
                phase = "validation",
                totalFiles = null,
                processedFiles = null,
                successfulFiles = null,
                failedFiles = null,
                durationMs = null,
                startedAt = startedAt,
                completedAt = Instant.now(),
                message = "Validation failed",
                validationErrors = validationResult
            )
        }

        // Resolve paths
        val targetPaths = resolvePaths(params.paths)
        if (targetPaths.isEmpty()) {
            return Result(
                mode = "sync",
                status = "error",
                jobId = null,
                phase = "validation",
                totalFiles = null,
                processedFiles = null,
                successfulFiles = null,
                failedFiles = null,
                durationMs = null,
                startedAt = startedAt,
                completedAt = Instant.now(),
                message = "No valid paths to rebuild. Check that paths exist and are within watched directories.",
                validationErrors = null
            )
        }

        // If validateOnly, return success without execution
        if (params.validateOnly) {
            return Result(
                mode = "sync",
                status = "validated",
                jobId = null,
                phase = "validation",
                totalFiles = null,
                processedFiles = null,
                successfulFiles = null,
                failedFiles = null,
                durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli(),
                startedAt = startedAt,
                completedAt = Instant.now(),
                message = "Validation successful. ${targetPaths.size} paths ready for rebuild. Use confirm=true to execute.",
                validationErrors = null
            )
        }

        log.info("Starting context rebuild for {} paths (confirm={}, async={})", targetPaths.size, params.confirm, params.async)

        return if (params.async) {
            executeAsync(targetPaths, params, startedAt)
        } else {
            executeSync(targetPaths, params, startedAt)
        }
    }

    private fun validateRequest(params: Params): List<String> {
        val errors = mutableListOf<String>()

        // Check if confirm is true (unless validateOnly)
        if (!params.validateOnly && !params.confirm) {
            errors.add("Safety check failed: confirm=true is required to execute rebuild. This is a destructive operation.")
        }

        // Check if another rebuild is in progress
        if (!params.validateOnly && rebuildInProgress.get()) {
            errors.add("Another rebuild is already in progress. Wait for it to complete or cancel it first.")
        }

        // Validate parallelism
        if (params.parallelism != null && params.parallelism < 1) {
            errors.add("parallelism must be >= 1, got ${params.parallelism}")
        }

        return errors
    }

    private fun resolvePaths(requestedPaths: List<String>?): List<Path> {
        // If no paths specified, use all watched paths from config
        val pathsToResolve = if (requestedPaths.isNullOrEmpty()) {
            config.watcher.watchPaths
        } else {
            requestedPaths
        }

        return pathsToResolve.mapNotNull { pathStr ->
            try {
                val path = if (pathStr == "auto") {
                    Paths.get(System.getProperty("user.dir"))
                } else {
                    Paths.get(pathStr)
                }

                // Validate path exists
                if (Files.exists(path)) {
                    path
                } else {
                    log.warn("Path does not exist: {}", pathStr)
                    null
                }
            } catch (e: Exception) {
                log.warn("Invalid path: {} - {}", pathStr, e.message)
                null
            }
        }
    }

    private fun executeSync(paths: List<Path>, params: Params, startedAt: Instant): Result {
        if (!rebuildInProgress.compareAndSet(false, true)) {
            return Result(
                mode = "sync",
                status = "error",
                jobId = null,
                phase = "pre-rebuild",
                totalFiles = null,
                processedFiles = null,
                successfulFiles = null,
                failedFiles = null,
                durationMs = null,
                startedAt = startedAt,
                completedAt = Instant.now(),
                message = "Another rebuild is already in progress",
                validationErrors = null
            )
        }

        return try {
            // Use runBlocking but ensure we're using async methods internally
            runBlocking {
                executeSyncInternal(paths, params, startedAt)
            }
        } catch (e: Exception) {
            val errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            log.error("Rebuild failed: $errorMessage", e)

            Result(
                mode = "sync",
                status = "failed",
                jobId = null,
                phase = "error",
                totalFiles = null,
                processedFiles = null,
                successfulFiles = null,
                failedFiles = null,
                durationMs = null,
                startedAt = startedAt,
                completedAt = Instant.now(),
                message = "Rebuild failed: $errorMessage",
                validationErrors = null
            )
        } finally {
            rebuildInProgress.set(false)
        }
    }

    private suspend fun executeSyncInternal(paths: List<Path>, params: Params, startedAt: Instant): Result {
        // Phase 2: Pre-rebuild
        log.info("Pre-rebuild: preparing database for rebuild")

        // Phase 3: Destructive phase
        log.info("Destructive phase: clearing existing context data")
        clearContextData()

        // Phase 4: Rebuild phase
        log.info("Rebuild phase: running bootstrap for {} paths", paths.size)
        val bootstrapResult = runBootstrap(paths, params.parallelism)

        // Phase 5: Post-rebuild
        log.info("Post-rebuild: optimizing database")
        optimizeDatabase()

        val completedAt = Instant.now()
        val durationMs = completedAt.toEpochMilli() - startedAt.toEpochMilli()

        log.info(
            "Rebuild completed: total={} successful={} failed={} duration={}ms",
            bootstrapResult.totalFiles,
            bootstrapResult.successfulFiles,
            bootstrapResult.failedFiles,
            durationMs
        )

        return Result(
            mode = "sync",
            status = if (bootstrapResult.success) "completed" else "completed_with_errors",
            jobId = null,
            phase = "post-rebuild",
            totalFiles = bootstrapResult.totalFiles,
            processedFiles = bootstrapResult.totalFiles,
            successfulFiles = bootstrapResult.successfulFiles,
            failedFiles = bootstrapResult.failedFiles,
            durationMs = durationMs,
            startedAt = startedAt,
            completedAt = completedAt,
            message = buildSuccessMessage(bootstrapResult),
            validationErrors = null
        )
    }

    private fun executeAsync(paths: List<Path>, params: Params, startedAt: Instant): Result {
        if (!rebuildInProgress.compareAndSet(false, true)) {
            return Result(
                mode = "async",
                status = "error",
                jobId = null,
                phase = "pre-rebuild",
                totalFiles = null,
                processedFiles = null,
                successfulFiles = null,
                failedFiles = null,
                durationMs = null,
                startedAt = startedAt,
                completedAt = Instant.now(),
                message = "Another rebuild is already in progress",
                validationErrors = null
            )
        }

        val jobId = UUID.randomUUID().toString()
        val job = RebuildJob(
            jobId = jobId,
            paths = paths,
            startedAt = startedAt,
            status = JobStatus.RUNNING,
            phase = "pre-rebuild"
        )

        jobs[jobId] = job

        // Launch background coroutine
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            try {
                // Phase 2: Pre-rebuild
                log.info("Async rebuild (job={}): Pre-rebuild phase", jobId)
                job.phase = "pre-rebuild"

                // Phase 3: Destructive phase
                log.info("Async rebuild (job={}): Destructive phase", jobId)
                job.phase = "destructive"
                clearContextData()

                // Phase 4: Rebuild phase
                log.info("Async rebuild (job={}): Rebuild phase", jobId)
                job.phase = "rebuild"
                val bootstrapResult = runBootstrap(paths, params.parallelism)

                job.totalFiles = bootstrapResult.totalFiles
                job.processedFiles = bootstrapResult.totalFiles
                job.successfulFiles = bootstrapResult.successfulFiles
                job.failedFiles = bootstrapResult.failedFiles

                // Phase 5: Post-rebuild
                log.info("Async rebuild (job={}): Post-rebuild phase", jobId)
                job.phase = "post-rebuild"
                optimizeDatabase()

                job.status = JobStatus.COMPLETED
                job.phase = "completed"

                log.info(
                    "Async rebuild completed (job={}): total={} successful={} failed={}",
                    jobId,
                    bootstrapResult.totalFiles,
                    bootstrapResult.successfulFiles,
                    bootstrapResult.failedFiles
                )
            } catch (e: Exception) {
                val errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
                job.error = errorMessage
                job.status = JobStatus.FAILED
                job.phase = "failed"
                log.error("Async rebuild failed (job={}): {}", jobId, errorMessage, e)
            } finally {
                rebuildInProgress.set(false)
            }
        }

        return Result(
            mode = "async",
            status = "running",
            jobId = jobId,
            phase = "pre-rebuild",
            totalFiles = null,
            processedFiles = null,
            successfulFiles = null,
            failedFiles = null,
            durationMs = null,
            startedAt = startedAt,
            completedAt = null,
            message = "Background rebuild started. Use jobId to check status.",
            validationErrors = null
        )
    }

    private fun clearContextData() {
        log.info("Clearing context data from database")

        // Use withConnection instead of transaction to allow multiple independent operations
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                // Drop and recreate tables to avoid foreign key issues
                // This is the cleanest approach for a full rebuild
                st.execute("DROP TABLE IF EXISTS usage_metrics CASCADE")
                st.execute("DROP TABLE IF EXISTS embeddings CASCADE")
                st.execute("DROP TABLE IF EXISTS symbols CASCADE")
                st.execute("DROP TABLE IF EXISTS links CASCADE")
                st.execute("DROP TABLE IF EXISTS chunks CASCADE")
                st.execute("DROP TABLE IF EXISTS file_state CASCADE")
                st.execute("DROP TABLE IF EXISTS project_config CASCADE")

                // Drop sequences
                st.execute("DROP SEQUENCE IF EXISTS file_state_seq")
                st.execute("DROP SEQUENCE IF EXISTS chunks_seq")
                st.execute("DROP SEQUENCE IF EXISTS embeddings_seq")
                st.execute("DROP SEQUENCE IF EXISTS links_seq")
                st.execute("DROP SEQUENCE IF EXISTS symbols_seq")
                st.execute("DROP SEQUENCE IF EXISTS usage_metrics_seq")
                st.execute("DROP SEQUENCE IF EXISTS project_config_seq")
            }

            // Recreate schema
            val statements = listOf(
                "CREATE SEQUENCE file_state_seq START 1",
                "CREATE SEQUENCE chunks_seq START 1",
                "CREATE SEQUENCE embeddings_seq START 1",
                "CREATE SEQUENCE links_seq START 1",
                "CREATE SEQUENCE symbols_seq START 1",
                "CREATE SEQUENCE usage_metrics_seq START 1",
                "CREATE SEQUENCE project_config_seq START 1",
                """
                CREATE TABLE file_state (
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
                CREATE TABLE chunks (
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
                CREATE TABLE embeddings (
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
                CREATE TABLE links (
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
                CREATE TABLE symbols (
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
                CREATE TABLE usage_metrics (
                    metric_id             BIGINT PRIMARY KEY,
                    task_id               VARCHAR,
                    snippets_returned     INTEGER,
                    total_tokens          INTEGER,
                    retrieval_latency_ms  INTEGER,
                    created_at            TIMESTAMP NOT NULL
                )
                """.trimIndent()
            )

            conn.createStatement().use { st ->
                statements.forEach { sql ->
                    st.execute(sql)
                }
            }
        }

        log.info("Context data cleared successfully")
    }

    private suspend fun runBootstrap(
        paths: List<Path>,
        parallelism: Int?
    ): com.orchestrator.context.bootstrap.BootstrapResult {
        // Determine project root from paths
        val projectRoot = paths.first()

        // Create embedder
        val embedder = com.orchestrator.context.embedding.LocalEmbedder(
            modelPath = null,
            modelName = config.embedding.model,
            dimension = config.embedding.dimension
        )

        // Create file indexer
        val fileIndexer = FileIndexer(
            embedder = embedder,
            projectRoot = projectRoot,
            embeddingBatchSize = config.embedding.batchSize,
            maxFileSizeMb = config.indexing.maxFileSizeMb,
            warnFileSizeMb = config.indexing.warnFileSizeMb
        )

        // Create batch indexer
        val batchIndexer = BatchIndexer(
            fileIndexer = fileIndexer,
            defaultParallelism = parallelism ?: config.bootstrap.parallelWorkers
        )

        // Create bootstrap config
        val bootstrapConfig = BootstrapConfig(
            parallelWorkers = parallelism ?: config.bootstrap.parallelWorkers
        )

        // Create path filter
        val pathFilter = com.orchestrator.context.discovery.PathFilter.fromSources(
            root = projectRoot,
            configPatterns = config.watcher.ignorePatterns
        )

        // Create extension filter
        val extensionFilter = com.orchestrator.context.discovery.ExtensionFilter.fromConfig(
            allowlist = config.indexing.allowedExtensions,
            blocklist = config.indexing.blockedExtensions
        )

        // Create symlink handler
        val symlinkHandler = com.orchestrator.context.discovery.SymlinkHandler(
            allowedRoots = paths,
            defaultConfig = config.indexing
        )

        // Create path validator
        val validator = com.orchestrator.context.discovery.PathValidator(
            watchPaths = paths.map { it.toAbsolutePath().normalize() },
            pathFilter = pathFilter,
            extensionFilter = extensionFilter,
            symlinkHandler = symlinkHandler,
            indexingConfig = config.indexing
        )

        // Create scanner
        val scanner = DirectoryScanner(
            validator = validator
        )

        // Create progress tracker
        val progressTracker = BootstrapProgressTracker()

        // Create error logger
        val errorLogger = BootstrapErrorLogger()

        // Create orchestrator
        val orchestrator = BootstrapOrchestrator(
            roots = paths,
            config = bootstrapConfig,
            scanner = scanner,
            prioritizer = FilePrioritizer,
            indexer = batchIndexer,
            progressTracker = progressTracker,
            errorLogger = errorLogger
        )

        // Run bootstrap
        return orchestrator.bootstrap()
    }

    private fun optimizeDatabase() {
        log.info("Running database optimization (VACUUM and ANALYZE)")
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("VACUUM")
                st.execute("ANALYZE")
            }
        }
        log.info("Database optimization completed")
    }

    private fun buildSuccessMessage(result: com.orchestrator.context.bootstrap.BootstrapResult): String {
        val summary = "Rebuild completed: ${result.successfulFiles}/${result.totalFiles} files indexed successfully"
        return if (result.failedFiles > 0) {
            "$summary (${result.failedFiles} failures)"
        } else {
            summary
        }
    }

    fun getJobStatus(jobId: String): Result? {
        val job = jobs[jobId] ?: return null

        return when (job.status) {
            JobStatus.RUNNING -> Result(
                mode = "async",
                status = "running",
                jobId = jobId,
                phase = job.phase,
                totalFiles = if (job.totalFiles > 0) job.totalFiles else null,
                processedFiles = if (job.processedFiles > 0) job.processedFiles else null,
                successfulFiles = if (job.successfulFiles > 0) job.successfulFiles else null,
                failedFiles = if (job.failedFiles > 0) job.failedFiles else null,
                durationMs = null,
                startedAt = job.startedAt,
                completedAt = null,
                message = "Rebuild is running (phase: ${job.phase})",
                validationErrors = null
            )
            JobStatus.COMPLETED -> Result(
                mode = "async",
                status = if (job.failedFiles > 0) "completed_with_errors" else "completed",
                jobId = jobId,
                phase = job.phase,
                totalFiles = job.totalFiles,
                processedFiles = job.processedFiles,
                successfulFiles = job.successfulFiles,
                failedFiles = job.failedFiles,
                durationMs = Instant.now().toEpochMilli() - job.startedAt.toEpochMilli(),
                startedAt = job.startedAt,
                completedAt = Instant.now(),
                message = "Rebuild completed: ${job.successfulFiles}/${job.totalFiles} files indexed successfully",
                validationErrors = null
            )
            JobStatus.FAILED -> Result(
                mode = "async",
                status = "failed",
                jobId = jobId,
                phase = job.phase,
                totalFiles = if (job.totalFiles > 0) job.totalFiles else null,
                processedFiles = if (job.processedFiles > 0) job.processedFiles else null,
                successfulFiles = if (job.successfulFiles > 0) job.successfulFiles else null,
                failedFiles = if (job.failedFiles > 0) job.failedFiles else null,
                durationMs = null,
                startedAt = job.startedAt,
                completedAt = Instant.now(),
                message = "Rebuild failed: ${job.error}",
                validationErrors = null
            )
        }
    }
}
