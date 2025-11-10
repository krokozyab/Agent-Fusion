package com.orchestrator.context.indexing

import com.orchestrator.utils.Logger
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Batch indexer that coordinates indexing work across a configurable pool of workers.
 */
class BatchIndexer(
    private val fileIndexer: FileIndexer,
    private val defaultParallelism: Int = max(Runtime.getRuntime().availableProcessors() - 1, 1),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC()
) {

    private val log = Logger.logger("com.orchestrator.context.indexing.BatchIndexer")

    /**
     * Blocking variant that launches a coroutine worker pool under the hood.
     */
    fun indexFiles(
        paths: List<Path>,
        parallelism: Int = defaultParallelism,
        onProgress: ((BatchProgress) -> Unit)? = null
    ): BatchResult = runBlocking {
        indexFilesInternal(paths, parallelism, onProgress)
    }

    /**
     * Suspend-friendly variant for coroutine callers.
     */
    suspend fun indexFilesAsync(
        paths: List<Path>,
        parallelism: Int = defaultParallelism,
        onProgress: ((BatchProgress) -> Unit)? = null
    ): BatchResult = indexFilesInternal(paths, parallelism, onProgress)

    private suspend fun indexFilesInternal(
        paths: List<Path>,
        parallelism: Int,
        progressListener: ((BatchProgress) -> Unit)?
    ): BatchResult {
        if (paths.isEmpty()) {
            val now = Instant.now(clock)
            val stats = BatchStats(
                totalFiles = 0,
                processedFiles = 0,
                succeeded = 0,
                failed = 0,
                startedAt = now,
                completedAt = now,
                durationMillis = 0
            )
            return BatchResult(emptyList(), emptyList(), stats)
        }

        val totalFiles = paths.size
        val start = Instant.now(clock)
        val processedCounter = AtomicInteger(0)
        val successCounter = AtomicInteger(0)
        val failureCounter = AtomicInteger(0)
        val successes = Collections.synchronizedList(mutableListOf<IndexResult>())
        val failures = Collections.synchronizedList(mutableListOf<BatchFailure>())

        // Performance metrics tracking: monitor embeddings/second to detect memory issues or slowdowns
        val embeddingCounter = AtomicInteger(0)
        val reportInterval = 10  // seconds between progress reports
        var lastReportTime = start

        val requestedParallelism = if (parallelism > 0) parallelism else defaultParallelism
        val workerLimit = max(1, min(requestedParallelism, totalFiles))
        log.debug(
            "Starting batch indexing for {} files with {} workers",
            totalFiles,
            workerLimit
        )

        supervisorScope {
            val semaphore = Semaphore(workerLimit)
            for (path in paths) {
                launch(dispatcher) {
                    semaphore.withPermit {
                        val (result, failure, errorMessage) = indexSingle(path)
                        if (result != null && result.success) {
                            successes.add(result)
                            successCounter.incrementAndGet()
                            // Count embeddings for performance metrics
                            embeddingCounter.addAndGet(result.embeddingCount)
                        }
                        if (failure != null) {
                            failures.add(failure)
                            failureCounter.incrementAndGet()
                        }

                        val processed = processedCounter.incrementAndGet()

                        // Periodic performance metrics reporting (every 10 seconds)
                        val now = Instant.now(clock)
                        val elapsedSinceReport = Duration.between(lastReportTime, now).toSeconds()
                        if (elapsedSinceReport >= reportInterval && processed > 0) {
                            val embeddingsSoFar = embeddingCounter.get()
                            val elapsedSeconds = Duration.between(start, now).toSeconds().coerceAtLeast(1)
                            val embeddingsPerSecond = embeddingsSoFar / elapsedSeconds.toDouble()
                            log.info(
                                "Batch indexing progress: {}/{} files, {} embeddings ({:.0f} eps)",
                                processed,
                                totalFiles,
                                embeddingsSoFar,
                                embeddingsPerSecond
                            )
                            lastReportTime = now
                        }

                        progressListener?.invoke(
                            BatchProgress(
                                totalFiles = totalFiles,
                                processedFiles = processed,
                                succeeded = successCounter.get(),
                                failed = failureCounter.get(),
                                lastPath = result?.relativePath ?: failure?.relativePath ?: path.toString(),
                                lastError = errorMessage
                            )
                        )

                        // MEMORY MANAGEMENT: Force GC between files to prevent accumulation
                        // Each file's embeddings, chunks, and ONNX tensors should be freed before indexing the next file
                        if (result?.chunkCount ?: 0 > 20) {
                            System.gc()
                        }
                    }
                }
            }
        }

        val completed = Instant.now(clock)
        val stats = BatchStats(
            totalFiles = totalFiles,
            processedFiles = processedCounter.get(),
            succeeded = successCounter.get(),
            failed = failureCounter.get(),
            startedAt = start,
            completedAt = completed,
            durationMillis = Duration.between(start, completed).toMillis()
        )

        // Final metrics summary: shows overall performance for debugging and optimization
        val totalEmbeddings = embeddingCounter.get()
        val totalSeconds = Duration.between(start, completed).toSeconds().coerceAtLeast(1)
        val finalEmbeddingsPerSecond = totalEmbeddings / totalSeconds.toDouble()
        log.info(
            "Batch indexing complete: {} files ({} succeeded, {} failed), {} embeddings in {}s ({:.0f} eps)",
            totalFiles,
            successCounter.get(),
            failureCounter.get(),
            totalEmbeddings,
            totalSeconds,
            finalEmbeddingsPerSecond
        )
        // Alert if performance is degraded: < 100 eps indicates memory or GC issues
        if (totalEmbeddings > 0 && finalEmbeddingsPerSecond < 100) {
            log.warn(
                "Batch indexing performance degraded: {:.0f} eps (expected 500+). Check memory and GC logs.",
                finalEmbeddingsPerSecond
            )
        }

        return BatchResult(
            successes = successes.toList(),
            failures = failures.toList(),
            stats = stats
        )
    }

    private suspend fun indexSingle(path: Path): Triple<IndexResult?, BatchFailure?, String?> {
        return try {
            val result = fileIndexer.indexFileAsync(path)
            if (result.success) {
                Triple(result, null, null)
            } else {
                val failure = BatchFailure(
                    path = path,
                    relativePath = result.relativePath,
                    error = result.error ?: "Unknown indexing error",
                    cause = null
                )
                Triple(result, failure, failure.error)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val message = throwable.message ?: throwable::class.simpleName ?: "Unknown failure"
            log.error("Failed to index $path: $message", throwable)
            val failure = BatchFailure(
                path = path,
                relativePath = path.fileName?.toString() ?: path.toString(),
                error = message,
                cause = throwable
            )
            Triple(null, failure, message)
        }
    }
}

data class BatchResult(
    val successes: List<IndexResult>,
    val failures: List<BatchFailure>,
    val stats: BatchStats
) {
    val isSuccessful: Boolean get() = failures.isEmpty()
    val hasFailures: Boolean get() = failures.isNotEmpty()
}

data class BatchFailure(
    val path: Path,
    val relativePath: String,
    val error: String,
    val cause: Throwable?
)

data class BatchStats(
    val totalFiles: Int,
    val processedFiles: Int,
    val succeeded: Int,
    val failed: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val durationMillis: Long
)

data class BatchProgress(
    val totalFiles: Int,
    val processedFiles: Int,
    val succeeded: Int,
    val failed: Int,
    val lastPath: String?,
    val lastError: String?
)
