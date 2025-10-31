package com.orchestrator.context.bootstrap

import com.orchestrator.context.config.BootstrapConfig
import com.orchestrator.context.discovery.DirectoryScanner
import com.orchestrator.context.discovery.PathValidator
import com.orchestrator.context.indexing.BatchIndexer
import com.orchestrator.context.indexing.BatchProgress
import com.orchestrator.utils.Logger
import java.nio.file.Path
import java.time.Instant

class BootstrapOrchestrator(
    private val roots: List<Path>,
    private val config: BootstrapConfig,
    private val scanner: DirectoryScanner,
    private val prioritizer: FilePrioritizer,
    private val indexer: BatchIndexer,
    private val progressTracker: BootstrapProgressTracker,
    private val errorLogger: BootstrapErrorLogger
) {

    private val log = Logger.logger("com.orchestrator.context.bootstrap.BootstrapOrchestrator")

    suspend fun bootstrap(
        onProgress: ((BootstrapProgress) -> Unit)? = null,
        forceScan: Boolean = true
    ): BootstrapResult {
        val startTime = Instant.now()

        val remainingFiles = progressTracker.getRemaining()
        val filesToProcess = when {
            remainingFiles.isNotEmpty() -> {
                log.info("Resuming bootstrap with ${remainingFiles.size} files remaining.")
                remainingFiles
            }
            forceScan -> {
                log.info("Starting new bootstrap scan from roots: ${roots.map { it.toAbsolutePath() }}")
                val scannedFiles = scanner.scan(roots)
                log.info("Bootstrap scan discovered {} files from {} roots", scannedFiles.size, roots.size)
                if (scannedFiles.isEmpty()) {
                    log.warn("No files discovered during bootstrap scan! Roots: ${roots.map { it.toAbsolutePath() }}")
                }
                val prioritizedFiles = prioritizer.prioritize(scannedFiles, config)
                log.info("Prioritizer processed {} scanned files into {} prioritized files", scannedFiles.size, prioritizedFiles.size)
                progressTracker.initProgress(prioritizedFiles)
                prioritizedFiles
            }
            else -> {
                log.info("Bootstrap progress table intentionally cleared; no files queued.")
                emptyList()
            }
        }

        if (filesToProcess.isEmpty()) {
            log.warn("No files to process. Scan returned 0 files from roots: ${roots.map { it.toAbsolutePath() }}")
            return BootstrapResult(true, 0, 0, 0, java.time.Duration.ZERO)
        }

        val totalFiles = filesToProcess.size
        var processedFiles = 0
        var successfulFiles = 0
        var failedFiles = 0

        val batchResult = indexer.indexFilesAsync(filesToProcess, config.parallelWorkers) { batchProgress ->
            processedFiles = batchProgress.processedFiles
            successfulFiles = batchProgress.succeeded
            failedFiles = batchProgress.failed

            batchProgress.lastPath?.let {
                if (batchProgress.lastError != null) {
                    errorLogger.logError(Path.of(it), RuntimeException(batchProgress.lastError))
                    progressTracker.markFailed(Path.of(it), batchProgress.lastError)
                } else {
                    progressTracker.markCompleted(Path.of(it))
                }
            }

            onProgress?.invoke(
                BootstrapProgress(
                    totalFiles = totalFiles,
                    processedFiles = processedFiles,
                    successfulFiles = successfulFiles,
                    failedFiles = failedFiles,
                    lastProcessedFile = batchProgress.lastPath
                )
            )
        }

        errorLogger.clearErrors()

        val endTime = Instant.now()
        val duration = java.time.Duration.between(startTime, endTime)

        return BootstrapResult(
            success = batchResult.isSuccessful,
            totalFiles = totalFiles,
            successfulFiles = successfulFiles,
            failedFiles = failedFiles,
            duration = duration
        )
    }
}

data class BootstrapResult(
    val success: Boolean,
    val totalFiles: Int,
    val successfulFiles: Int,
    val failedFiles: Int,
    val duration: java.time.Duration
)

data class BootstrapProgress(
    val totalFiles: Int,
    val processedFiles: Int,
    val successfulFiles: Int,
    val failedFiles: Int,
    val lastProcessedFile: String?
)
