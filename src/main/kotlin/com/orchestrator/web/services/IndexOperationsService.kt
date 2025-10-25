package com.orchestrator.web.services

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.core.EventBus
import com.orchestrator.mcp.tools.RefreshContextTool
import com.orchestrator.mcp.tools.RefreshContextTool.Params as RefreshParams
import com.orchestrator.mcp.tools.RebuildContextTool
import com.orchestrator.mcp.tools.RebuildContextTool.Params as RebuildParams
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.utils.Logger
import com.orchestrator.web.plugins.ApplicationConfigKey
import com.orchestrator.web.sse.IndexProgressEvent
import com.orchestrator.web.sse.IndexStatusUpdatedEvent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.util.AttributeKey
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates long-running index operations triggered from the web dashboard.
 *
 * Each operation is dispatched on a dedicated background scope to avoid blocking
 * request threads. Progress and completion updates are published to the global
 * event bus so SSE subscribers can react in real time.
 */
interface IndexOperationsService {
    fun triggerRefresh(): OperationTriggerResult
    fun triggerRebuild(confirm: Boolean = true): OperationTriggerResult
    fun optimize(): OperationTriggerResult

    companion object {
        private val ServiceKey = AttributeKey<IndexOperationsService>("web-index-operations-service")

        fun forApplication(application: Application): IndexOperationsService {
            return application.attributes.getOrNull(ServiceKey) ?: run {
                val contextConfig = application.attributes.getOrNull(ApplicationConfigKey)?.context ?: ContextConfig()
                val service = DefaultIndexOperationsService(contextConfig = contextConfig)

                application.monitor.subscribe(ApplicationStopping) {
                    service.shutdown()
                }

                application.attributes.put(ServiceKey, service)
                service
            }
        }

        fun install(application: Application, service: IndexOperationsService) {
            application.attributes.put(ServiceKey, service)
        }
    }
}

data class OperationTriggerResult(
    val accepted: Boolean,
    val message: String,
    val code: ResultCode = if (accepted) ResultCode.ACCEPTED else ResultCode.REJECTED
) {
    enum class ResultCode { ACCEPTED, REJECTED }
}

private class DefaultIndexOperationsService(
    private val contextConfig: ContextConfig,
    private val eventBus: EventBus = EventBus.global
) : IndexOperationsService {

    private val logger = Logger.logger("com.orchestrator.web.services.IndexOperationsService")
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(supervisor + Dispatchers.IO)

    private val refreshActive = AtomicBoolean(false)
    private val rebuildActive = AtomicBoolean(false)
    private val optimizeActive = AtomicBoolean(false)

    private val refreshTool by lazy { RefreshContextTool(contextConfig) }
    private val rebuildTool by lazy { RebuildContextTool(contextConfig) }

    override fun triggerRefresh(): OperationTriggerResult {
        if (!refreshActive.compareAndSet(false, true)) {
            return OperationTriggerResult(
                accepted = false,
                message = "An index refresh is already in progress.",
                code = OperationTriggerResult.ResultCode.REJECTED
            )
        }

        val operationId = "refresh-${UUID.randomUUID()}"
        scope.launch {
            publishProgress(
                operationId = operationId,
                percentage = 0,
                title = "Context Refresh",
                message = "Refresh started"
            )

            try {
                val result = withContext(Dispatchers.IO) {
                    refreshTool.execute(RefreshParams(async = false)) { progress ->
                        val total = progress.totalFiles
                        val processed = progress.processedFiles
                        val percentage = if (total > 0) (processed * 100) / total else 10
                        publishProgress(
                            operationId = operationId,
                            percentage = percentage.coerceIn(0, 95),
                            processed = processed,
                            total = total,
                            title = "Context Refresh",
                            message = progress.lastPath?.let { "Indexing $it" } ?: "Indexing files"
                        )
                    }
                }

                publishProgress(
                    operationId = operationId,
                    percentage = 100,
                    processed = (result.newFiles ?: 0) + (result.modifiedFiles ?: 0),
                    total = result.totalCount(),
                    title = "Context Refresh",
                    message = result.message ?: "Refresh completed"
                )
            } catch (t: Throwable) {
                logger.error("Index refresh failed: ${t.message}", t)
                publishProgress(
                    operationId = operationId,
                    percentage = 100,
                    title = "Context Refresh",
                    message = "Refresh failed: ${t.message ?: t::class.simpleName ?: "error"}"
                )
            } finally {
                publishSummary()
                refreshActive.set(false)
            }
        }

        return OperationTriggerResult(
            accepted = true,
            message = "Index refresh started."
        )
    }

    override fun triggerRebuild(confirm: Boolean): OperationTriggerResult {
        if (!confirm) {
            return OperationTriggerResult(
                accepted = false,
                message = "Rebuild requires confirmation.",
                code = OperationTriggerResult.ResultCode.REJECTED
            )
        }

        if (!rebuildActive.compareAndSet(false, true)) {
            return OperationTriggerResult(
                accepted = false,
                message = "A rebuild operation is already running.",
                code = OperationTriggerResult.ResultCode.REJECTED
            )
        }

        val operationId = "rebuild-${UUID.randomUUID()}"
        scope.launch {
            publishProgress(
                operationId = operationId,
                percentage = 0,
                title = "Context Rebuild",
                message = "Rebuild started"
            )

            try {
                val result = withContext(Dispatchers.IO) {
                    rebuildTool.execute(
                        RebuildParams(
                            confirm = true,
                            async = true,
                            validateOnly = false
                        )
                    ) { progress ->
                        val total = progress.totalFiles
                        val processed = progress.processedFiles
                        val percentage = if (total > 0) (processed * 100) / total else 10
                        publishProgress(
                            operationId = operationId,
                            percentage = percentage.coerceIn(5, 95),
                            processed = processed,
                            total = total,
                            title = "Context Rebuild",
                            message = progress.lastProcessedFile?.let { "Indexed $it" } ?: "Rebuilding..."
                        )
                    }
                }

                // If async, poll for job completion
                if (result.jobId != null) {
                    logger.info("Rebuild job started: ${result.jobId}")
                    monitorRebuildJob(result.jobId, operationId)
                } else {
                    // Synchronous result (shouldn't happen with async=true but handle it)
                    val statusMessage = result.message ?: when (result.status.lowercase()) {
                        "completed" -> "Rebuild completed successfully"
                        "completed_with_errors" -> "Rebuild completed with errors"
                        "failed" -> "Rebuild failed"
                        else -> "Rebuild status: ${result.status}"
                    }

                    publishProgress(
                        operationId = operationId,
                        percentage = 100,
                        processed = result.processedFiles,
                        total = result.totalFiles,
                        title = "Context Rebuild",
                        message = statusMessage
                    )
                    publishSummary()
                    rebuildActive.set(false)
                }
            } catch (t: Throwable) {
                logger.error("Context rebuild failed: ${t.message}", t)
                publishProgress(
                    operationId = operationId,
                    percentage = 100,
                    title = "Context Rebuild",
                    message = "Rebuild failed: ${t.message ?: t::class.simpleName ?: "error"}"
                )
                publishSummary()
                rebuildActive.set(false)
            }
        }

        return OperationTriggerResult(
            accepted = true,
            message = "Index rebuild started."
        )
    }

    override fun optimize(): OperationTriggerResult {
        if (!optimizeActive.compareAndSet(false, true)) {
            return OperationTriggerResult(
                accepted = false,
                message = "Database optimization already in progress.",
                code = OperationTriggerResult.ResultCode.REJECTED
            )
        }

        val operationId = "optimize-${UUID.randomUUID()}"
        scope.launch {
            publishProgress(
                operationId = operationId,
                percentage = 0,
                title = "Optimize Database",
                message = "Optimization started"
            )

            try {
                withContext(Dispatchers.IO) {
                    ContextDatabase.withConnection { conn ->
                        conn.createStatement().use { st ->
                            st.execute("VACUUM")
                            st.execute("ANALYZE")
                        }
                    }
                }

                publishProgress(
                    operationId = operationId,
                    percentage = 100,
                    title = "Optimize Database",
                    message = "Optimization completed"
                )
            } catch (t: Throwable) {
                logger.error("Database optimization failed: ${t.message}", t)
                publishProgress(
                    operationId = operationId,
                    percentage = 100,
                    title = "Optimize Database",
                    message = "Optimization failed: ${t.message ?: t::class.simpleName ?: "error"}"
                )
            } finally {
                publishSummary()
                optimizeActive.set(false)
            }
        }

        return OperationTriggerResult(
            accepted = true,
            message = "Database optimization started."
        )
    }

    private fun monitorRebuildJob(jobId: String, operationId: String) {
        scope.launch {
            try {
                var isComplete = false
                var lastPhase: String? = null

                while (!isComplete) {
                    delay(1000)

                    val statusResult = withContext(Dispatchers.IO) {
                        rebuildTool.getJobStatus(jobId)
                    }

                    if (statusResult == null) {
                        logger.warn("Rebuild job status not found for jobId: $jobId")
                        break
                    }

                    val phase = statusResult.phase ?: "rebuild"
                    val normalizedStatus = statusResult.status.lowercase()
                    val percentage = phaseToPercentage(phase, normalizedStatus)

                    if (phase != lastPhase || normalizedStatus != "running") {
                        publishProgress(
                            operationId = operationId,
                            percentage = percentage,
                            processed = statusResult.processedFiles,
                            total = statusResult.totalFiles,
                            title = "Context Rebuild",
                            message = statusResult.message ?: "Phase: ${phase.replace('-', ' ')}"
                        )
                        lastPhase = phase
                    }

                    isComplete = normalizedStatus in listOf("completed", "completed_with_errors", "failed")

                    if (isComplete) {
                        publishProgress(
                            operationId = operationId,
                            percentage = 100,
                            processed = statusResult.processedFiles,
                            total = statusResult.totalFiles,
                            title = "Context Rebuild",
                            message = statusResult.message ?: "Rebuild ${normalizedStatus.replace('_', ' ')}"
                        )
                    }
                }
            } finally {
                publishSummary()
                rebuildActive.set(false)
            }
        }
    }

    private fun phaseToPercentage(phase: String, status: String): Int = when (status) {
        "completed", "completed_with_errors", "failed" -> 100
        else -> when (phase.lowercase()) {
            "validation" -> 5
            "pre-rebuild" -> 10
            "destructive" -> 25
            "rebuild" -> 60
            "post-rebuild" -> 90
            else -> 50
        }
    }

    fun shutdown() {
        supervisor.cancel()
    }

    private fun publishProgress(
        operationId: String,
        percentage: Int,
        processed: Int? = null,
        total: Int? = null,
        title: String? = null,
        message: String? = null
    ) {
        val event = IndexProgressEvent(
            operationId = operationId,
            percentage = percentage.coerceIn(0, 100),
            processed = processed,
            total = total,
            title = title,
            message = message,
            timestamp = Instant.now()
        )
        eventBus.publish(event)
    }

    private fun publishSummary() {
        runCatching {
            val snapshot = ContextModule.getIndexStatus()
            eventBus.publish(IndexStatusUpdatedEvent(snapshot))
        }.onFailure { throwable ->
            logger.warn("Failed to publish index summary: ${throwable.message}")
        }
    }

    private fun RebuildContextTool.Result.totalFiles(): Int? =
        listOfNotNull(totalFiles, processedFiles).maxOrNull()?.takeIf { it > 0 }

    private fun RefreshContextTool.Result.totalCount(): Int? {
        val values = listOfNotNull(newFiles, modifiedFiles, deletedFiles, unchangedFiles)
        return if (values.isEmpty()) null else max(values.sum(), 0)
    }
}
