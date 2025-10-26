package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.indexing.BatchIndexer
import com.orchestrator.context.indexing.ChangeDetector
import com.orchestrator.context.indexing.IncrementalIndexer
import com.orchestrator.context.watcher.WatcherRegistry
import com.orchestrator.utils.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * MCP tool for manually triggering context re-indexing.
 * Supports both synchronous (blocking) and asynchronous (background) modes.
 */
class RefreshContextTool(
    private val config: ContextConfig = ContextConfig(),
    private val incrementalIndexer: IncrementalIndexer? = null
) {
    private val log = Logger.logger("com.orchestrator.mcp.tools.RefreshContextTool")

    // Lazy initialization of indexer if not provided
    private val indexer: IncrementalIndexer by lazy {
        incrementalIndexer ?: run {
            // Determine project root from watch paths
            val projectRoot = if (config.watcher.watchPaths.isNotEmpty() && config.watcher.watchPaths.first() != "auto") {
                Paths.get(config.watcher.watchPaths.first())
            } else {
                Paths.get(System.getProperty("user.dir"))
            }

            // Create embedder
            val embedder = com.orchestrator.context.embedding.LocalEmbedder(
                modelPath = null,  // Will use default model location
                modelName = config.embedding.model,
                dimension = config.embedding.dimension
            )

            // Create file indexer
            val fileIndexer = com.orchestrator.context.indexing.FileIndexer(
                embedder = embedder,
                projectRoot = projectRoot,
                embeddingBatchSize = config.embedding.batchSize,
                maxFileSizeMb = config.indexing.maxFileSizeMb,
                warnFileSizeMb = config.indexing.warnFileSizeMb
            )

            // Create batch indexer
            val batchIndexer = BatchIndexer(
                fileIndexer = fileIndexer,
                defaultParallelism = config.bootstrap.parallelWorkers
            )

            // Create change detector
            val changeDetector = ChangeDetector(
                projectRoot = projectRoot
            )

            // Create incremental indexer
            IncrementalIndexer(
                changeDetector = changeDetector,
                batchIndexer = batchIndexer
            )
        }
    }

    data class Params(
        val paths: List<String>? = null,
        val force: Boolean = false,
        val async: Boolean = false,
        val parallelism: Int? = null
    )

    data class Result(
        val mode: String,
        val status: String,
        val jobId: String?,
        val newFiles: Int?,
        val modifiedFiles: Int?,
        val deletedFiles: Int?,
        val unchangedFiles: Int?,
        val indexingFailures: Int?,
        val deletionFailures: Int?,
        val durationMs: Long?,
        val startedAt: Instant,
        val completedAt: Instant?,
        val message: String?
    )

    data class RefreshJob(
        val jobId: String,
        val paths: List<Path>,
        val startedAt: Instant,
        var status: JobStatus,
        var result: com.orchestrator.context.indexing.UpdateResult? = null,
        var error: String? = null
    )

    enum class JobStatus {
        RUNNING, COMPLETED, FAILED
    }

    companion object {
        private val jobs = ConcurrentHashMap<String, RefreshJob>()

        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "refresh_context params",
          "type": "object",
          "properties": {
            "paths": {
              "type": ["array", "null"],
              "items": {"type": "string"},
              "description": "List of paths to refresh. If null, refreshes all watched paths."
            },
            "force": {
              "type": ["boolean", "null"],
              "default": false,
              "description": "Force re-indexing even if files haven't changed"
            },
            "async": {
              "type": ["boolean", "null"],
              "default": false,
              "description": "Run indexing in background and return jobId immediately"
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

        fun getJob(jobId: String): RefreshJob? = jobs[jobId]

        fun clearCompletedJobs() {
            jobs.entries.removeIf { (_, job) ->
                job.status == JobStatus.COMPLETED || job.status == JobStatus.FAILED
            }
        }
    }

    fun execute(
        params: Params = Params(),
        onProgress: ((com.orchestrator.context.indexing.BatchProgress) -> Unit)? = null
    ): Result {
        val startedAt = Instant.now()

        // Resolve paths
        val targetPaths = resolvePaths(params.paths)

        if (targetPaths.isEmpty()) {
            return Result(
                mode = "sync",
                status = "error",
                jobId = null,
                newFiles = null,
                modifiedFiles = null,
                deletedFiles = null,
                unchangedFiles = null,
                indexingFailures = null,
                deletionFailures = null,
                durationMs = null,
                startedAt = startedAt,
                completedAt = Instant.now(),
                message = "No valid paths to refresh. Check that paths exist and are within watched directories."
            )
        }

        log.info("Refreshing context for {} paths (force={}, async={})", targetPaths.size, params.force, params.async)

        return runBlocking {
            WatcherRegistry.pauseWhile {
                if (params.async) {
                    executeAsync(targetPaths, params, startedAt)
                } else {
                    executeSync(targetPaths, params, startedAt, onProgress)
                }
            }
        }
    }

    private fun executeSync(
        paths: List<Path>,
        params: Params,
        startedAt: Instant,
        onProgress: ((com.orchestrator.context.indexing.BatchProgress) -> Unit)?
    ): Result {
        return try {
            val updateResult = runBlocking {
                indexer.updateAsync(
                    paths = paths,
                    parallelism = params.parallelism,
                    onProgress = onProgress
                )
            }

            val completedAt = Instant.now()

            log.info(
                "Refresh completed: new={} modified={} deleted={} unchanged={} failures={}",
                updateResult.newCount,
                updateResult.modifiedCount,
                updateResult.deletedCount,
                updateResult.unchangedCount,
                updateResult.indexingFailures + updateResult.deletionFailures
            )

            Result(
                mode = "sync",
                status = if (updateResult.hasFailures) "completed_with_errors" else "completed",
                jobId = null,
                newFiles = updateResult.newCount,
                modifiedFiles = updateResult.modifiedCount,
                deletedFiles = updateResult.deletedCount,
                unchangedFiles = updateResult.unchangedCount,
                indexingFailures = updateResult.indexingFailures,
                deletionFailures = updateResult.deletionFailures,
                durationMs = updateResult.durationMillis,
                startedAt = startedAt,
                completedAt = completedAt,
                message = buildSuccessMessage(updateResult)
            )
        } catch (e: Exception) {
            val errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
            log.error("Refresh failed: $errorMessage", e)

            Result(
                mode = "sync",
                status = "failed",
                jobId = null,
                newFiles = null,
                modifiedFiles = null,
                deletedFiles = null,
                unchangedFiles = null,
                indexingFailures = null,
                deletionFailures = null,
                durationMs = null,
                startedAt = startedAt,
                completedAt = Instant.now(),
                message = "Refresh failed: $errorMessage"
            )
        }
    }

    private fun executeAsync(paths: List<Path>, params: Params, startedAt: Instant): Result {
        val jobId = UUID.randomUUID().toString()
        val job = RefreshJob(
            jobId = jobId,
            paths = paths,
            startedAt = startedAt,
            status = JobStatus.RUNNING
        )

        jobs[jobId] = job

        // Launch background coroutine
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            try {
                val updateResult = indexer.updateAsync(
                    paths = paths,
                    parallelism = params.parallelism
                )

                job.result = updateResult
                job.status = JobStatus.COMPLETED

                log.info(
                    "Async refresh completed (job={}): new={} modified={} deleted={}",
                    jobId,
                    updateResult.newCount,
                    updateResult.modifiedCount,
                    updateResult.deletedCount
                )
            } catch (e: Exception) {
                val errorMessage = e.message ?: e::class.simpleName ?: "Unknown error"
                job.error = errorMessage
                job.status = JobStatus.FAILED
                log.error("Async refresh failed (job={}): {}", jobId, errorMessage, e)
            }
        }

        return Result(
            mode = "async",
            status = "running",
            jobId = jobId,
            newFiles = null,
            modifiedFiles = null,
            deletedFiles = null,
            unchangedFiles = null,
            indexingFailures = null,
            deletionFailures = null,
            durationMs = null,
            startedAt = startedAt,
            completedAt = null,
            message = "Background refresh started. Use jobId to check status."
        )
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
                    // Use current working directory
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

    private fun buildSuccessMessage(result: com.orchestrator.context.indexing.UpdateResult): String {
        val parts = mutableListOf<String>()

        if (result.newCount > 0) parts.add("${result.newCount} new")
        if (result.modifiedCount > 0) parts.add("${result.modifiedCount} modified")
        if (result.deletedCount > 0) parts.add("${result.deletedCount} deleted")
        if (result.unchangedCount > 0) parts.add("${result.unchangedCount} unchanged")

        val summary = if (parts.isEmpty()) "No changes detected" else parts.joinToString(", ")

        return if (result.hasFailures) {
            "$summary (${result.indexingFailures + result.deletionFailures} failures)"
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
                newFiles = null,
                modifiedFiles = null,
                deletedFiles = null,
                unchangedFiles = null,
                indexingFailures = null,
                deletionFailures = null,
                durationMs = null,
                startedAt = job.startedAt,
                completedAt = null,
                message = "Job is still running"
            )
            JobStatus.COMPLETED -> {
                val result = job.result!!
                Result(
                    mode = "async",
                    status = if (result.hasFailures) "completed_with_errors" else "completed",
                    jobId = jobId,
                    newFiles = result.newCount,
                    modifiedFiles = result.modifiedCount,
                    deletedFiles = result.deletedCount,
                    unchangedFiles = result.unchangedCount,
                    indexingFailures = result.indexingFailures,
                    deletionFailures = result.deletionFailures,
                    durationMs = result.durationMillis,
                    startedAt = job.startedAt,
                    completedAt = result.completedAt,
                    message = buildSuccessMessage(result)
                )
            }
            JobStatus.FAILED -> Result(
                mode = "async",
                status = "failed",
                jobId = jobId,
                newFiles = null,
                modifiedFiles = null,
                deletedFiles = null,
                unchangedFiles = null,
                indexingFailures = null,
                deletionFailures = null,
                durationMs = null,
                startedAt = job.startedAt,
                completedAt = Instant.now(),
                message = "Job failed: ${job.error}"
            )
        }
    }
}
