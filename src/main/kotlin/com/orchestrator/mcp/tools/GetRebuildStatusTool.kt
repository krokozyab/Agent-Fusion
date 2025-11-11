package com.orchestrator.mcp.tools

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.utils.Logger
import java.time.Instant

/**
 * MCP tool for checking rebuild job status.
 *
 * This tool queries the status of background rebuild jobs started by rebuild_context.
 * It provides real-time progress updates and optional log history.
 */
class GetRebuildStatusTool(
    private val config: ContextConfig = ContextConfig()
) {
    private val log = Logger.logger("com.orchestrator.mcp.tools.GetRebuildStatusTool")

    data class Params(
        val jobId: String,
        val includeLogs: Boolean = false
    )

    data class Result(
        val jobId: String,
        val status: String,
        val phase: String,
        val progress: Progress?,
        val timing: Timing,
        val error: String?,
        val logs: List<LogEntry>?
    )

    data class Progress(
        val totalFiles: Int,
        val processedFiles: Int,
        val successfulFiles: Int,
        val failedFiles: Int,
        val percentComplete: Int
    )

    data class Timing(
        val startedAt: Instant,
        val completedAt: Instant?,
        val durationMs: Long?,
        val estimatedRemainingMs: Long?
    )

    data class LogEntry(
        val timestamp: Instant,
        val level: String,
        val message: String
    )

    companion object {
        const val JSON_SCHEMA: String = """
        {
          "${'$'}schema": "http://json-schema.org/draft-07/schema#",
          "title": "get_rebuild_status params",
          "type": "object",
          "properties": {
            "jobId": {
              "type": "string",
              "description": "Job ID returned from rebuild_context async execution"
            },
            "includeLogs": {
              "type": ["boolean", "null"],
              "default": false,
              "description": "Include execution logs in the response"
            }
          },
          "required": ["jobId"],
          "additionalProperties": false
        }
        """
    }

    fun execute(params: Params): Result {
        log.debug("Getting status for job: {}", params.jobId)

        // Get job from RebuildContextTool
        val rebuildTool = RebuildContextTool(config)
        val jobStatus = rebuildTool.getJobStatus(params.jobId)

        if (jobStatus == null) {
            // Job not found
            return Result(
                jobId = params.jobId,
                status = "not_found",
                phase = "unknown",
                progress = null,
                timing = Timing(
                    startedAt = Instant.now(),
                    completedAt = null,
                    durationMs = null,
                    estimatedRemainingMs = null
                ),
                error = "Job ID not found. It may have been completed and removed, or never existed.",
                logs = if (params.includeLogs) emptyList() else null
            )
        }

        // Calculate progress
        val progress = if (jobStatus.totalFiles != null && jobStatus.totalFiles > 0) {
            val processedFiles = jobStatus.processedFiles ?: 0
            val percentComplete = if (jobStatus.totalFiles > 0) {
                ((processedFiles.toDouble() / jobStatus.totalFiles) * 100).toInt()
            } else {
                0
            }

            Progress(
                totalFiles = jobStatus.totalFiles,
                processedFiles = processedFiles,
                successfulFiles = jobStatus.successfulFiles ?: 0,
                failedFiles = jobStatus.failedFiles ?: 0,
                percentComplete = percentComplete
            )
        } else {
            null
        }

        // Calculate timing and estimates
        val now = Instant.now()
        val durationMs = jobStatus.durationMs ?: (now.toEpochMilli() - jobStatus.startedAt.toEpochMilli())

        val estimatedRemainingMs = if (jobStatus.status == "running" && progress != null && progress.percentComplete > 0) {
            val avgMsPerPercent = durationMs.toDouble() / progress.percentComplete
            val remainingPercent = 100 - progress.percentComplete
            (avgMsPerPercent * remainingPercent).toLong()
        } else {
            null
        }

        val timing = Timing(
            startedAt = jobStatus.startedAt,
            completedAt = jobStatus.completedAt,
            durationMs = durationMs,
            estimatedRemainingMs = estimatedRemainingMs
        )

        // Get logs if requested
        val logs = if (params.includeLogs) {
            getLogs(params.jobId)
        } else {
            null
        }

        // Map status from RebuildContextTool.Result to our format
        val statusStr = when (jobStatus.status) {
            "running" -> "running"
            "completed" -> "completed"
            "completed_with_errors" -> "completed_with_errors"
            "failed" -> "failed"
            else -> jobStatus.status
        }

        return Result(
            jobId = params.jobId,
            status = statusStr,
            phase = jobStatus.phase,
            progress = progress,
            timing = timing,
            error = if (statusStr == "failed") jobStatus.message else null,
            logs = logs
        )
    }

    private fun getLogs(jobId: String): List<LogEntry> {
        // Access the RebuildJob directly to get detailed logs
        val job = RebuildContextTool.getJob(jobId) ?: return emptyList()

        val logs = mutableListOf<LogEntry>()

        // Add start log
        logs.add(LogEntry(
            timestamp = job.startedAt,
            level = "INFO",
            message = "Rebuild job started for ${job.paths.size} path(s)"
        ))

        // Add phase-specific logs based on current phase
        when (job.phase) {
            "pre-rebuild" -> {
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "INFO",
                    message = "Phase: Pre-rebuild - Preparing database"
                ))
            }
            "destructive" -> {
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "INFO",
                    message = "Phase: Pre-rebuild - Preparing database"
                ))
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "WARN",
                    message = "Phase: Destructive - Clearing existing context data"
                ))
            }
            "rebuild" -> {
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "INFO",
                    message = "Phase: Pre-rebuild - Preparing database"
                ))
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "WARN",
                    message = "Phase: Destructive - Clearing existing context data"
                ))
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "INFO",
                    message = "Phase: Rebuild - Indexing files (${job.processedFiles}/${job.totalFiles})"
                ))
            }
            "post-rebuild" -> {
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "INFO",
                    message = "Phase: Pre-rebuild - Preparing database"
                ))
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "WARN",
                    message = "Phase: Destructive - Clearing existing context data"
                ))
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "INFO",
                    message = "Phase: Rebuild - Indexed ${job.totalFiles} files"
                ))
                logs.add(LogEntry(
                    timestamp = Instant.now(),
                    level = "INFO",
                    message = "Phase: Post-rebuild - Optimizing database"
                ))
            }
            "completed" -> {
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "INFO",
                    message = "Phase: Pre-rebuild - Preparing database"
                ))
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "WARN",
                    message = "Phase: Destructive - Clearing existing context data"
                ))
                logs.add(LogEntry(
                    timestamp = job.startedAt,
                    level = "INFO",
                    message = "Phase: Rebuild - Indexed ${job.totalFiles} files"
                ))
                logs.add(LogEntry(
                    timestamp = Instant.now(),
                    level = "INFO",
                    message = "Phase: Post-rebuild - Optimizing database"
                ))
                logs.add(LogEntry(
                    timestamp = Instant.now(),
                    level = "INFO",
                    message = "Rebuild completed: ${job.successfulFiles}/${job.totalFiles} files successful, ${job.failedFiles} failed"
                ))
            }
            "failed" -> {
                logs.add(LogEntry(
                    timestamp = Instant.now(),
                    level = "ERROR",
                    message = "Rebuild failed: ${job.error ?: "Unknown error"}"
                ))
            }
        }

        return logs
    }
}
