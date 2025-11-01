package com.orchestrator.context.watcher

import com.orchestrator.context.config.IndexingConfig
import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.discovery.DirectoryScanner
import com.orchestrator.context.discovery.ExtensionFilter
import com.orchestrator.context.discovery.IncludePathsFilter
import com.orchestrator.context.discovery.PathFilter
import com.orchestrator.context.discovery.PathValidator
import com.orchestrator.context.discovery.SymlinkHandler
import com.orchestrator.context.indexing.IncrementalIndexer
import com.orchestrator.context.indexing.UpdateResult
import com.orchestrator.utils.Logger
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.LinkedHashSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background service that listens for filesystem events and triggers incremental indexing in batches.
 */
class WatcherDaemon(
    private val scope: CoroutineScope,
    private val projectRoot: Path,
    private val watcherConfig: WatcherConfig,
    indexingConfig: IndexingConfig,
    private val incrementalIndexer: IncrementalIndexer,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val batchWindowMillis: Long = DEFAULT_BATCH_WINDOW_MS,
    private val onUpdate: ((UpdateResult) -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null,
    private val fileWatcherFactory: (CoroutineScope, List<Path>, WatcherConfig, CoroutineDispatcher) -> FileWatcher =
        { watchScope, roots, config, watchDispatcher -> FileWatcher(watchScope, roots, config, watchDispatcher) }
) : Closeable {

    private val log = Logger.logger("com.orchestrator.context.watcher.WatcherDaemon")
    private val running = AtomicBoolean(false)
    private val watchRoots: List<Path> = resolveWatchRoots(projectRoot, watcherConfig.watchPaths)
    private val loggedSkippedPaths = mutableSetOf<Path>()

    private val pathFilter: PathFilter = PathFilter.fromSources(
        projectRoot.toAbsolutePath().normalize(),
        configPatterns = watcherConfig.ignorePatterns,
        includeGitignore = watcherConfig.useGitignore,
        includeContextignore = watcherConfig.useContextignore,
        includeDockerignore = true
    )
    private val extensionFilter: ExtensionFilter = ExtensionFilter.fromConfig(
        indexingConfig.allowedExtensions,
        emptyList()
    ).also {
        log.info("WatcherDaemon initialized with {} allowed extensions: {}",
                 indexingConfig.allowedExtensions.size,
                 indexingConfig.allowedExtensions.sorted())
    }
    private val includePathsFilter: IncludePathsFilter = IncludePathsFilter.fromConfig(
        watcherConfig.includePaths,
        projectRoot
    )

    private val pathValidator: PathValidator = PathValidator(
        watchRoots,
        pathFilter,
        extensionFilter,
        includePathsFilter,
        SymlinkHandler(watchRoots, indexingConfig),
        indexingConfig
    )

    private var fileWatcher: FileWatcher? = null
    private var watcherJob: Job? = null
    private var flushJob: Job? = null

    private val pendingMutex = Mutex()
    private val pendingPaths = LinkedHashSet<Path>()

    /**
     * Start the daemon. Subsequent invocations are no-ops.
     */
    fun start(skipStartupScan: Boolean = false) {
        if (!running.compareAndSet(false, true)) return
        if (!watcherConfig.enabled) {
            log.info("Watcher daemon disabled by configuration; not starting.")
            running.set(false)
            return
        }
        if (watchRoots.isEmpty()) {
            log.warn("Watcher daemon has no watch roots; disabling.")
            running.set(false)
            return
        }

        if (!skipStartupScan) {
            // Perform initial scan to catch files created while watcher was stopped
            runBlocking {
                try {
                    log.info("Performing startup scan to detect files created while watcher was stopped...")
                    val scanner = DirectoryScanner(pathValidator, parallel = false)
                    val discoveredFiles = scanner.scan(watchRoots)

                    if (discoveredFiles.isNotEmpty()) {
                        log.info("Startup scan found {} file(s), triggering incremental indexing...", discoveredFiles.size)
                        val result = incrementalIndexer.updateAsync(discoveredFiles)
                        log.info(
                            "Startup scan indexed: new={}, modified={}, deleted={}, unchanged={}, failures={}",
                            result.newCount,
                            result.modifiedCount,
                            result.deletedCount,
                            result.unchangedCount,
                            result.indexingFailures + result.deletionFailures
                        )
                        if (result.newCount + result.modifiedCount > 0) {
                            val sampleNew = result.changeSet.newFiles.take(5).joinToString { it.relativePath }
                            val sampleModified = result.changeSet.modifiedFiles.take(5).joinToString { it.relativePath }
                            if (sampleNew.isNotEmpty()) {
                                log.info("Startup scan new files sample: {}", sampleNew)
                            }
                            if (sampleModified.isNotEmpty()) {
                                log.info("Startup scan modified files sample: {}", sampleModified)
                            }
                        }
                        onUpdate?.invoke(result)
                    } else {
                        log.info("Startup scan found no files to index")
                    }
                } catch (throwable: Throwable) {
                    log.error("Startup scan failed: {}", throwable.message, throwable)
                    onError?.invoke(throwable)
                    // Continue anyway - watcher will catch future changes
                }
            }
        }

        val watcher = runCatching {
            fileWatcherFactory(scope, watchRoots, watcherConfig, dispatcher)
        }.getOrElse { throwable ->
            running.set(false)
            log.error("Failed to initialize file watcher: {}", throwable.message, throwable)
            onError?.invoke(throwable)
            return
        }

        fileWatcher = watcher
        watcher.start()

        watcherJob = scope.launch(dispatcher) {
            try {
                watcher.events.collect { event ->
                    try {
                        handleEvent(event)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (throwable: Throwable) {
                        log.error("Failed handling watch event for {}: {}", event.path, throwable.message, throwable)
                        onError?.invoke(throwable)
                    }
                }
            } catch (cancelled: CancellationException) {
                // Expected on shutdown.
            } finally {
                flushPendingImmediate()
            }
        }
    }

    private suspend fun handleEvent(event: FileWatchEvent) {
        when (event.kind) {
            FileWatchEvent.Kind.OVERFLOW -> {
                log.warn("File watcher reported overflow for root {}", event.root)
            }
            FileWatchEvent.Kind.CREATED,
            FileWatchEvent.Kind.MODIFIED,
            FileWatchEvent.Kind.DELETED -> processFileEvent(event)
        }
    }

    private suspend fun processFileEvent(event: FileWatchEvent) {
        if (event.isDirectory) {
            log.debug("Skipping directory event {} for {}", event.kind, event.path)
            return
        }
        val validation = pathValidator.validate(event.path)
        if (!validation.valid) {
            // Only log the first time we skip a particular path to avoid log spam
            val normalizedPath = event.path.toAbsolutePath().normalize()
            if (loggedSkippedPaths.add(normalizedPath)) {
                log.debug(
                    "Skipping {} due to validation failure {} ({})",
                    event.path,
                    validation.code,
                    validation.message
                )
            }
            return
        }
        enqueue(event.path)
    }

    private suspend fun enqueue(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        var shouldSchedule = false
        pendingMutex.withLock {
            pendingPaths.add(normalized)
            shouldSchedule = flushJob?.isActive != true
            if (shouldSchedule) {
                flushJob = scope.launch(dispatcher) {
                    if (batchWindowMillis <= 0) {
                        flushPendingImmediate()
                    } else {
                        delay(batchWindowMillis)
                        flushPending()
                    }
                }
            }
        }
    }

    private suspend fun flushPendingImmediate() {
        val snapshot = pendingMutex.withLock {
            val items = pendingPaths.toList()
            pendingPaths.clear()
            flushJob = null
            items
        }
        if (snapshot.isNotEmpty()) {
            processBatch(snapshot)
        }
    }

    private suspend fun flushPending() {
        val snapshot = pendingMutex.withLock {
            val items = pendingPaths.toList()
            pendingPaths.clear()
            flushJob = null
            items
        }
        if (snapshot.isNotEmpty()) {
            processBatch(snapshot)
        }
    }

    private suspend fun processBatch(paths: List<Path>) {
        if (paths.isEmpty()) return
        val unique = LinkedHashSet<Path>()
        paths.forEach { unique.add(it) }
        if (unique.isEmpty()) return

        runCatching {
            incrementalIndexer.updateAsync(unique.toList())
        }.onSuccess { result ->
            onUpdate?.invoke(result)
            if (result.hasFailures) {
                log.warn(
                    "Incremental index update completed with {} failure(s) for {} path(s).",
                    result.indexingFailures + result.deletionFailures,
                    unique.size
                )
            } else {
                log.debug("Incremental index update succeeded for {} path(s).", unique.size)
            }
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            log.error(
                "Incremental indexing failed for {} path(s): {}",
                unique.size,
                throwable.message,
                throwable
            )
            onError?.invoke(throwable)
        }
    }

    /**
     * Stop the daemon and flush any pending paths synchronously.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        watcherJob?.cancel()
        watcherJob = null

        val remaining = runBlocking {
            pendingMutex.withLock {
                flushJob?.cancel()
                flushJob = null
                val items = pendingPaths.toList()
                pendingPaths.clear()
                items
            }
        }

        runBlocking {
            if (remaining.isNotEmpty()) {
                processBatch(remaining)
            }
        }

        runCatching { fileWatcher?.close() }
        fileWatcher = null
    }

    suspend fun <T> pauseWhile(block: suspend () -> T): T {
        val wasRunning = running.get()
        if (!wasRunning) {
            return block()
        }

        stop()

        return try {
            block()
        } finally {
            runCatching { start(skipStartupScan = true) }
                .onFailure { throwable ->
                    log.error("Failed to restart watcher after pause: {}", throwable.message, throwable)
                }
        }
    }

    override fun close() {
        stop()
    }

    companion object {
        private const val DEFAULT_BATCH_WINDOW_MS = 1_000L

        private fun resolveWatchRoots(projectRoot: Path, watchPaths: List<String>): List<Path> {
            val normalizedRoot = projectRoot.toAbsolutePath().normalize()
            if (watchPaths.isEmpty()) return listOf(normalizedRoot)
            val roots = LinkedHashSet<Path>()
            watchPaths.forEach { raw ->
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
}
}
