package com.orchestrator.web.services

import com.orchestrator.context.config.ContextConfig
import com.orchestrator.context.config.WatcherConfig
import com.orchestrator.context.discovery.DirectoryScanner
import com.orchestrator.context.discovery.ExtensionFilter
import com.orchestrator.context.discovery.IncludePathsFilter
import com.orchestrator.context.discovery.PathFilter
import com.orchestrator.context.discovery.PathValidator
import com.orchestrator.context.discovery.SymlinkHandler
import com.orchestrator.modules.context.ContextModule
import com.orchestrator.modules.context.ContextModule.FileIndexStatus
import com.orchestrator.modules.context.ContextModule.IndexStatusSnapshot
import com.orchestrator.utils.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.LinkedHashMap

/**
 * Reusable calculator that computes [FilesystemIndexSnapshot] by scanning the
 * configured watch roots and comparing them against the catalog.
 *
 * Shared between the IndexOperationsService (manual web-triggered actions) and
 * background file watcher notifications so both paths produce consistent data.
 */
class FilesystemSnapshotCalculator(
    private val contextConfig: ContextConfig,
    projectRootOverride: Path? = null
) {
    private val logger = Logger.logger("com.orchestrator.web.services.FilesystemSnapshotCalculator")

    private val projectRoot: Path = projectRootOverride ?: Paths.get("").toAbsolutePath().normalize()
    private val configuredWatchRoots: List<Path> = resolveWatchRoots(projectRoot, contextConfig.watcher)
    val watchRoots: List<Path> = if (configuredWatchRoots.isEmpty()) listOf(projectRoot) else configuredWatchRoots

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
        allowedRoots = watchRoots,
        defaultConfig = contextConfig.indexing
    )
    private val pathValidator: PathValidator = PathValidator(
        watchPaths = watchRoots,
        pathFilter = pathFilter,
        extensionFilter = extensionFilter,
        includePathsFilter = includePathsFilter,
        symlinkHandler = symlinkHandler,
        indexingConfig = contextConfig.indexing
    )

    fun snapshot(): FilesystemIndexSnapshot {
        return runCatching { computeSnapshot() }
            .onFailure { throwable ->
                logger.warn("Failed to compute filesystem snapshot: ${throwable.message}")
            }
            .getOrElse {
                FilesystemIndexSnapshot(
                    totalFiles = 0,
                    roots = watchRoots.map { FilesystemIndexSnapshot.RootSummary(it.toString(), 0) },
                    watchRoots = watchRoots.map(Path::toString),
                    scannedAt = Instant.now(),
                    missingFromCatalog = emptyList(),
                    orphanedInCatalog = emptyList(),
                    missingTotal = 0,
                    orphanedTotal = 0
                )
            }
    }

    private fun computeSnapshot(): FilesystemIndexSnapshot {
        if (watchRoots.isEmpty()) {
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
            parallel = watchRoots.size > 1
        )
        val discovered = scanner.scan(watchRoots)

        if (discovered.isEmpty()) {
            return FilesystemIndexSnapshot(
                totalFiles = 0,
                roots = watchRoots.map { FilesystemIndexSnapshot.RootSummary(it.toString(), 0) },
                watchRoots = watchRoots.map(Path::toString),
                scannedAt = Instant.now(),
                missingFromCatalog = emptyList(),
                orphanedInCatalog = emptyList(),
                missingTotal = 0,
                orphanedTotal = 0
            )
        }

        val counts = LinkedHashMap<Path, Int>()
        watchRoots.forEach { counts[it] = 0 }

        for (path in discovered) {
            val normalized = path.toAbsolutePath().normalize()
            val matchingRoot = watchRoots.firstOrNull { normalized.startsWith(it) } ?: watchRoots.first()
            counts[matchingRoot] = (counts[matchingRoot] ?: 0) + 1
        }

        val rootSummaries = counts.map { (root, total) ->
            FilesystemIndexSnapshot.RootSummary(root.toString(), total)
        }

        val discoveredSet = discovered.map { normalizeAbsolute(it) }.toSet()
        val indexStatus = ContextModule.getIndexStatus()
        val catalogSet = collectCatalogEntries(indexStatus, watchRoots)

        val missingAbsolute = (discoveredSet - catalogSet).sorted()
        val orphanedAbsolute = (catalogSet - discoveredSet).sorted()

        val missingDisplay = missingAbsolute
            .map { toDisplayPath(Paths.get(it), watchRoots) }
            .take(MAX_DISCREPANCY_ITEMS)

        val orphanedDisplay = orphanedAbsolute
            .map { toDisplayPath(Paths.get(it), watchRoots) }
            .take(MAX_DISCREPANCY_ITEMS)

        return FilesystemIndexSnapshot(
            totalFiles = discovered.size,
            roots = rootSummaries,
            watchRoots = watchRoots.map(Path::toString),
            scannedAt = Instant.now(),
            missingFromCatalog = missingDisplay,
            orphanedInCatalog = orphanedDisplay,
            missingTotal = missingAbsolute.size,
            orphanedTotal = orphanedAbsolute.size
        )
    }

    private fun collectCatalogEntries(
        indexStatus: IndexStatusSnapshot,
        roots: List<Path>
    ): Set<String> {
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

        return indexedSet + pendingSet
    }

    private fun resolveCatalogEntryPath(path: String, roots: List<Path>): Path? {
        val normalized = Paths.get(path)

        if (normalized.isAbsolute) {
            if (Files.exists(normalized)) {
                return normalized.toAbsolutePath().normalize()
            }
            val fallback = projectRoot.resolve(path).normalize()
            return if (Files.exists(fallback)) fallback else normalized
        }

        roots.forEach { root ->
            val candidate = root.resolve(normalized).normalize()
            if (Files.exists(candidate)) {
                return candidate
            }
        }

        return projectRoot.resolve(normalized).toAbsolutePath().normalize()
    }

    private fun toDisplayPath(path: Path, roots: List<Path>): String {
        val normalized = path.normalize()
        val matchingRoot = roots.firstOrNull { normalized.startsWith(it) } ?: return normalized.toString()

        val relative = matchingRoot.relativize(normalized)
        val rootDisplay = toDisplayRoot(matchingRoot)
        return "$rootDisplay/$relative"
    }

    private fun toDisplayRoot(root: Path): String {
        val normalizedRoot = root.normalize()
        val project = projectRoot.normalize()
        if (normalizedRoot.startsWith(project)) {
            return normalizedRoot.relativize(project).let { rel ->
                if (rel.toString().isEmpty()) "." else rel.toString()
            }
        }
        return normalizedRoot.toString()
    }

    private fun normalizeAbsolute(path: Path): String =
        path.toAbsolutePath().normalize().toString()

    companion object {
        private const val MAX_DISCREPANCY_ITEMS = 20

        fun resolveWatchRoots(projectRoot: Path, watcherConfig: WatcherConfig): List<Path> {
            val normalizedRoot = projectRoot.toAbsolutePath().normalize()
            val watchPaths = watcherConfig.watchPaths

            if (watchPaths.isEmpty()) {
                return emptyList()
            }

            return watchPaths.mapNotNull { raw ->
                resolveAbsolutePath(normalizedRoot, raw)
                    ?.takeIf { Files.exists(it) }
            }
        }

        private fun resolveAbsolutePath(baseDir: Path, rawPath: String): Path? {
            val trimmed = rawPath.trim()
            if (trimmed.isEmpty()) return null

            val candidate = Paths.get(trimmed)
            return if (candidate.isAbsolute) {
                candidate.normalize()
            } else {
                baseDir.resolve(trimmed).toAbsolutePath().normalize()
            }
        }
    }
}
