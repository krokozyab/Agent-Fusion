package com.orchestrator.web.services

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.discovery.DirectoryScanner
import com.orchestrator.context.discovery.ExtensionFilter
import com.orchestrator.context.discovery.IncludePathsFilter
import com.orchestrator.context.discovery.PathFilter
import com.orchestrator.context.discovery.PathValidator
import com.orchestrator.context.discovery.SymlinkHandler
import com.orchestrator.context.bootstrap.BootstrapProgressTracker
import com.orchestrator.context.storage.ContextDatabase
import com.orchestrator.context.watcher.WatcherRegistry
import com.orchestrator.core.EventBus
import com.orchestrator.mcp.tools.RefreshContextTool
import com.orchestrator.mcp.tools.RefreshContextTool.Params as RefreshParams
import com.orchestrator.mcp.tools.RebuildContextTool
import com.orchestrator.mcp.tools.RebuildContextTool.Params as RebuildParams
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.modules.context.ContextModule.FileIndexStatus
import com.orchestrator.utils.Logger
import com.orchestrator.web.plugins.ApplicationConfigKey
import com.orchestrator.web.sse.IndexProgressEvent
import com.orchestrator.web.sse.IndexStatusUpdatedEvent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.util.AttributeKey
import java.time.Instant
import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashMap
import java.util.LinkedHashSet
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
    fun filesystemSnapshot(): FilesystemIndexSnapshot

    companion object {
        private val ServiceKey = AttributeKey<IndexOperationsService>("web-index-operations-service")

        fun forApplication(application: Application): IndexOperationsService {
            return application.attributes.getOrNull(ServiceKey) ?: run {
                val contextConfig = application.attributes
                    .getOrNull(ApplicationConfigKey)
                    ?.context
                    ?: runCatching { ContextModule.configuration() }.getOrElse { ContextConfig() }
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

data class FilesystemIndexSnapshot(
    val totalFiles: Int,
    val roots: List<RootSummary>,
    val watchRoots: List<String>,
    val scannedAt: Instant,
    val missingFromCatalog: List<String> = emptyList(),
    val orphanedInCatalog: List<String> = emptyList(),
    val missingTotal: Int = 0,
    val orphanedTotal: Int = 0
) {
    data class RootSummary(
        val path: String,
        val totalFiles: Int
    )
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

    private val refreshTool by lazy { RefreshContextTool(contextConfig) }
    private val rebuildTool by lazy { RebuildContextTool(contextConfig) }

    private val projectRoot: Path = Paths.get("").toAbsolutePath().normalize()
    private val configuredWatchRoots: List<Path> = resolveWatchRoots(projectRoot, contextConfig.watcher)
    private val effectiveWatchRoots: List<Path> =
        if (configuredWatchRoots.isEmpty()) listOf(projectRoot) else configuredWatchRoots

    private val pathFilter: PathFilter = PathFilter.fromSources(
        projectRoot.toAbsolutePath().normalize(),
        configPatterns = contextConfig.watcher.ignorePatterns,
        includeGitignore = contextConfig.watcher.useGitignore,
        includeContextignore = contextConfig.watcher.useContextignore,
        includeDockerignore = true
    )
    private val extensionFilter: ExtensionFilter = ExtensionFilter.fromConfig(
        allowlist = contextConfig.indexing.allowedExtensions,
        blocklist = contextConfig.indexing.blockedExtensions
    )
    private val includePathsFilter: IncludePathsFilter = IncludePathsFilter.fromConfig(
        includePaths = contextConfig.watcher.includePaths,
        baseDir = projectRoot
    )
    private val symlinkHandler: SymlinkHandler = SymlinkHandler(
        allowedRoots = effectiveWatchRoots,
        defaultConfig = contextConfig.indexing
    )
    private val pathValidator: PathValidator = PathValidator(
        watchPaths = effectiveWatchRoots,
        pathFilter = pathFilter,
        extensionFilter = extensionFilter,
        includePathsFilter = includePathsFilter,
        symlinkHandler = symlinkHandler,
        indexingConfig = contextConfig.indexing
    )

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
            WatcherRegistry.pauseWhile {
                publishProgress(
                    operationId = operationId,
                    percentage = 5,
                    title = "Context Refresh",
                    message = "Initializing refresh..."
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
            WatcherRegistry.pauseWhile {
                publishProgress(
                    operationId = operationId,
                    percentage = 5,
                    title = "Context Rebuild",
                    message = "Initializing rebuild..."
                )

                try {
                    runCatching {
                        BootstrapProgressTracker().reset()
                        logger.info("Bootstrap progress table reset before rebuild")
                    }.onFailure { resetError ->
                        logger.warn("Failed to reset bootstrap progress before rebuild: ${resetError.message}")
                    }

                    val result = withContext(Dispatchers.IO) {
                        rebuildTool.execute(
                            RebuildParams(
                                confirm = true,
                                async = false,
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

                    if (result.jobId != null) {
                        logger.info("Rebuild job started: ${result.jobId}")
                        monitorRebuildJob(result.jobId, operationId)
                    } else {
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
        }

        return OperationTriggerResult(
            accepted = true,
            message = "Index rebuild started."
        )
    }

    override fun filesystemSnapshot(): FilesystemIndexSnapshot {
        return runCatching { computeFilesystemSnapshot() }
            .onFailure { throwable ->
                logger.warn("Failed to compute filesystem snapshot: ${throwable.message}")
            }
            .getOrElse {
                val roots = effectiveWatchRoots.ifEmpty { listOf(projectRoot) }
                FilesystemIndexSnapshot(
                    totalFiles = 0,
                    roots = roots.map { FilesystemIndexSnapshot.RootSummary(it.toString(), 0) },
                    watchRoots = roots.map(Path::toString),
                    scannedAt = Instant.now(),
                    missingFromCatalog = emptyList(),
                    orphanedInCatalog = emptyList(),
                    missingTotal = 0,
                    orphanedTotal = 0
                )
            }
    }

    private fun computeFilesystemSnapshot(): FilesystemIndexSnapshot {
        val roots = effectiveWatchRoots.ifEmpty { listOf(projectRoot) }
        if (roots.isEmpty()) {
            return FilesystemIndexSnapshot(
                totalFiles = 0,
                roots = emptyList(),
                watchRoots = emptyList(),
                scannedAt = Instant.now(),
                missingFromCatalog = emptyList(),
                orphanedInCatalog = emptyList(),
                missingTotal = 0,
                orphanedTotal = 0
            )
        }

        val scanner = DirectoryScanner(
            validator = pathValidator,
            parallel = roots.size > 1
        )

        val discovered = scanner.scan(roots)
        if (discovered.isEmpty()) {
            return FilesystemIndexSnapshot(
                totalFiles = 0,
                roots = roots.map { FilesystemIndexSnapshot.RootSummary(it.toString(), 0) },
                watchRoots = roots.map(Path::toString),
                scannedAt = Instant.now(),
                missingFromCatalog = emptyList(),
                orphanedInCatalog = emptyList(),
                missingTotal = 0,
                orphanedTotal = 0
            )
        }

        val counts = LinkedHashMap<Path, Int>()
        roots.forEach { counts[it] = 0 }

        for (path in discovered) {
            val normalized = path.toAbsolutePath().normalize()
            val matchingRoot = roots.firstOrNull { normalized.startsWith(it) } ?: roots.first()
            counts[matchingRoot] = (counts[matchingRoot] ?: 0) + 1
        }

        val rootSummaries = counts.map { (root, total) ->
            FilesystemIndexSnapshot.RootSummary(root.toString(), total)
        }

        val discoveredSet = discovered.map { normalizeAbsolute(it) }.toSet()

        val indexStatus = ContextModule.getIndexStatus()
        val indexedEntries = indexStatus.files.filter {
            it.status == FileIndexStatus.INDEXED || it.status == FileIndexStatus.ERROR
        }
        val pendingEntries = indexStatus.files.filter { it.status == FileIndexStatus.PENDING }

        val indexedSet = indexedEntries.mapNotNull { entry ->
            resolveCatalogEntryPath(entry.path, roots)?.let { normalizeAbsolute(it) }
        }.toSet()

        val pendingSet = pendingEntries.mapNotNull { entry ->
            resolveCatalogEntryPath(entry.path, roots)?.let { normalizeAbsolute(it) }
        }.toSet()

        val catalogSet = indexedSet + pendingSet

        val missingAbsolute = (discoveredSet - catalogSet).sorted()
        val orphanedAbsolute = (indexedSet - discoveredSet).sorted()

        val missingDisplay = missingAbsolute
            .map { toDisplayPath(Paths.get(it), roots) }
            .take(MAX_DISCREPANCY_ITEMS)

        val orphanedDisplay = orphanedAbsolute
            .map { toDisplayPath(Paths.get(it), roots) }
            .take(MAX_DISCREPANCY_ITEMS)

        return FilesystemIndexSnapshot(
            totalFiles = discovered.size,
            roots = rootSummaries,
            watchRoots = roots.map(Path::toString),
            scannedAt = Instant.now(),
            missingFromCatalog = missingDisplay,
            orphanedInCatalog = orphanedDisplay,
            missingTotal = missingAbsolute.size,
            orphanedTotal = orphanedAbsolute.size
        )
    }

    private fun resolveCatalogEntryPath(relPath: String, roots: List<Path>): Path? {
        val candidate = runCatching { Paths.get(relPath) }.getOrElse { Paths.get(relPath) }
        if (candidate.isAbsolute) return candidate.toAbsolutePath().normalize()
        if (candidate.nameCount == 0) return null

        val normalized = candidate.normalize()
        val segments = normalized.map { it.toString() }

        roots.forEach { root ->
            val rootName = root.fileName?.toString() ?: return@forEach

            var index = 0
            while (index < segments.size && segments[index] == rootName) {
                index++
            }

            if (index == 0) {
                return@forEach
            }

            val remaining = segments.subList(index, segments.size)
            val resolved = remaining.fold(root) { acc, segment -> acc.resolve(segment) }
            return resolved.toAbsolutePath().normalize()
        }

        return projectRoot.resolve(normalized).toAbsolutePath().normalize()
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
                // Small delay to ensure all progress events are delivered before summary
                delay(500)
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
            val filesystem = filesystemSnapshot()
            eventBus.publish(IndexStatusUpdatedEvent(snapshot, filesystem))
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

    companion object {
        private const val MAX_DISCREPANCY_ITEMS = 20

        private fun resolveWatchRoots(projectRoot: Path, watcherConfig: WatcherConfig): List<Path> {
            val normalizedRoot = projectRoot.toAbsolutePath().normalize()
            if (watcherConfig.watchPaths.isEmpty()) {
                return emptyList()
            }

            val roots = LinkedHashSet<Path>()
            watcherConfig.watchPaths.forEach { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return@forEach
                if (trimmed.equals("auto", ignoreCase = true)) {
                    roots.add(normalizedRoot)
                    return@forEach
                }
                val candidate = runCatching { Paths.get(trimmed) }.getOrNull()
                val resolved = when {
                    candidate == null -> null
                    candidate.isAbsolute -> candidate
                    else -> normalizedRoot.resolve(candidate)
                } ?: return@forEach
                roots.add(resolved.toAbsolutePath().normalize())
            }
            return roots.toList()
        }

        private fun normalizeAbsolute(path: Path): String =
            path.toAbsolutePath().normalize().toString()

        private fun toDisplayPath(absolute: Path, roots: List<Path>): String {
            val normalized = absolute.toAbsolutePath().normalize()
            val root = roots.firstOrNull { normalized.startsWith(it) }
            if (root == null) {
                return normalized.toString()
            }
            val label = root.fileName?.toString() ?: root.toString()
            val relative = runCatching { root.relativize(normalized).toString() }.getOrElse { normalized.toString() }
                .removePrefix("./")
            return if (relative.isEmpty()) label else "$label/$relative"
        }
    }
}
