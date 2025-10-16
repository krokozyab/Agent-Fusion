package com.orchestrator.context.watcher

import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.discovery.PathFilter
import com.orchestrator.utils.Logger
import java.io.Closeable
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Watches the configured directories for file-system changes and emits events as a [SharedFlow].
 *
 * The watcher registers each directory recursively (including new directories created at runtime)
 * and debounces successive events for the same path according to [WatcherConfig.debounceMs].
 */
class FileWatcher(
    private val scope: CoroutineScope,
    watchPaths: List<Path>,
    private val config: WatcherConfig,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : Closeable {

    private val log = Logger.logger("com.orchestrator.context.watcher.FileWatcher")
    private val watchService: WatchService = FileSystems.getDefault().newWatchService()
    private val running = AtomicBoolean(false)
    private var watcherJob: Job? = null

    private val watchRoots: List<Path> = watchPaths
        .map { it.toAbsolutePath().normalize() }
        .distinct()

    private val filters: Map<Path, PathFilter> = watchRoots.associateWith { root ->
        PathFilter.fromSources(
            root,
            configPatterns = config.ignorePatterns,
            includeGitignore = config.useGitignore,
            includeContextignore = config.useContextignore,
            includeDockerignore = true
        )
    }

    private val keyRoots = ConcurrentHashMap<WatchKey, Path>()
    private val registeredDirectories = ConcurrentHashMap.newKeySet<Path>()
    private val recursive = true
    private val sensitivityModifiers: Array<WatchEvent.Modifier> = loadSensitivityModifiers()

    private val _events = MutableSharedFlow<FileWatchEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<FileWatchEvent> = _events.asSharedFlow()

    private val debouncer = EventDebouncer(scope, debounceMillis = config.debounceMs, dispatcher = dispatcher)
    private val debouncerJob: Job = scope.launch(dispatcher) {
        debouncer.events.collect { event ->
            _events.emit(event)
        }
    }

    /**
     * Begin watching the configured directories. Calling [start] multiple times is a no-op.
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (watchRoots.isEmpty()) {
            log.warn("FileWatcher started with no watch roots; no events will be emitted.")
            return
        }
        watchRoots.forEach { root ->
            if (!Files.exists(root) || !Files.isDirectory(root)) {
                log.warn("Watch root {} does not exist or is not a directory; skipping.", root)
                return@forEach
            }
            registerRecursively(root, root)
        }

        watcherJob = scope.launch(dispatcher) {
            try {
                watchLoop()
            } catch (ex: CancellationException) {
                // Scope cancellation – expected during shutdown.
            } catch (ex: ClosedWatchServiceException) {
                // Service closed as part of shutdown.
            } catch (ex: Throwable) {
                log.error("FileWatcher loop terminated unexpectedly: {}", ex.message, ex)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun watchLoop() {
        while (true) {
            val key = watchService.take()
            val root = keyRoots[key] ?: run {
                key.reset()
                continue
            }

            for (rawEvent in key.pollEvents()) {
                when (rawEvent.kind()) {
                    StandardWatchEventKinds.OVERFLOW -> emitOverflow(root)
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY -> processEntryEvent(root, rawEvent)
                }
            }

            val valid = key.reset()
            if (!valid) {
                val removedRoot = keyRoots.remove(key)
                removedRoot?.let { registeredDirectories.remove(it) }
            }
        }
    }

    private fun processEntryEvent(root: Path, rawEvent: WatchEvent<*>) {
        @Suppress("UNCHECKED_CAST")
        val event = rawEvent as WatchEvent<Path>
        val relative = event.context()
        val absolute = root.resolve(relative).normalize()
        if (shouldIgnore(root, absolute)) return

        val kind = when (event.kind()) {
            StandardWatchEventKinds.ENTRY_CREATE -> FileWatchEvent.Kind.CREATED
            StandardWatchEventKinds.ENTRY_MODIFY -> FileWatchEvent.Kind.MODIFIED
            StandardWatchEventKinds.ENTRY_DELETE -> FileWatchEvent.Kind.DELETED
            else -> FileWatchEvent.Kind.MODIFIED
        }

        val isDirectory = when (kind) {
            FileWatchEvent.Kind.DELETED -> registeredDirectories.contains(absolute)
            else -> absolute.isExistingDirectory()
        }

        if (kind == FileWatchEvent.Kind.CREATED && recursive && isDirectory) {
            registerRecursively(absolute, root)
        }

        if (kind == FileWatchEvent.Kind.DELETED && isDirectory) {
            registeredDirectories.remove(absolute)
        }

        emitEvent(
            FileWatchEvent(
                kind = kind,
                path = absolute,
                root = root,
                isDirectory = isDirectory,
                timestamp = Instant.now()
            )
        )
    }

    private fun emitOverflow(root: Path) {
        emitEvent(
            FileWatchEvent(
                kind = FileWatchEvent.Kind.OVERFLOW,
                path = root,
                root = root,
                isDirectory = true,
                timestamp = Instant.now()
            )
        )
    }

    private fun emitEvent(event: FileWatchEvent) {
        debouncer.submit(event)
    }

    private fun registerRecursively(directory: Path, root: Path) {
        if (!directory.isExistingDirectory()) return
        try {
            Files.walkFileTree(directory, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    registerDirectory(dir, root)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) {
                        log.debug("Failed to access {} while registering watcher: {}", file, exc.message)
                    }
                    return FileVisitResult.SKIP_SUBTREE
                }
            })
        } catch (ex: IOException) {
            log.warn("Unable to register watcher for {}: {}", directory, ex.message)
        }
    }

    private fun registerDirectory(dir: Path, root: Path) {
        val normalized = dir.toAbsolutePath().normalize()
        val alreadyRegistered = !registeredDirectories.add(normalized)
        if (alreadyRegistered) return
        if (!normalized.isExistingDirectory()) {
            registeredDirectories.remove(normalized)
            return
        }
        if (normalized != root && shouldIgnore(root, normalized)) {
            registeredDirectories.remove(normalized)
            return
        }
        try {
            val key = if (sensitivityModifiers.isEmpty()) {
                normalized.register(
                    watchService,
                    WATCH_KINDS
                )
            } else {
                normalized.register(
                    watchService,
                    WATCH_KINDS,
                    *sensitivityModifiers
                )
            }
            keyRoots[key] = normalized
        } catch (ex: IOException) {
            registeredDirectories.remove(normalized)
            log.warn("Failed to register watch key for {}: {}", normalized, ex.message)
        }
    }

    private fun shouldIgnore(root: Path, candidate: Path): Boolean {
        val filter = filters[root] ?: return false
        return filter.shouldIgnore(candidate)
    }

    override fun close() {
        if (!running.getAndSet(false)) {
            runCatching { watchService.close() }
            return
        }
        runCatching { watchService.close() }
        watcherJob?.cancel()
        watcherJob = null
        debouncerJob.cancel()
        debouncer.close()
        registeredDirectories.clear()
        keyRoots.clear()
    }

    private fun Path.isExistingDirectory(): Boolean =
        Files.exists(this, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(this, LinkOption.NOFOLLOW_LINKS)

    private fun loadSensitivityModifiers(): Array<WatchEvent.Modifier> {
        return runCatching {
            val clazz = Class.forName("com.sun.nio.file.SensitivityWatchEventModifier")
            val field = clazz.getField("HIGH")
            arrayOf(field.get(null) as WatchEvent.Modifier)
        }.getOrDefault(emptyArray())
    }

    companion object {
        private val WATCH_KINDS = arrayOf(
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        )

        fun fromConfig(
            scope: CoroutineScope,
            paths: List<String>,
            config: WatcherConfig,
            dispatcher: CoroutineDispatcher = Dispatchers.IO
        ): FileWatcher {
            val resolved = paths.mapNotNull { resolveWatchPath(it) }
            return FileWatcher(scope, resolved, config, dispatcher)
        }

        private fun resolveWatchPath(raw: String): Path? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            return Paths.get(trimmed)
        }
    }
}

data class FileWatchEvent(
    val kind: Kind,
    val path: Path,
    val root: Path,
    val isDirectory: Boolean,
    val timestamp: Instant
) {
    enum class Kind {
        CREATED,
        MODIFIED,
        DELETED,
        OVERFLOW
    }
}
