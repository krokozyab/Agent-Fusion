package com.orchestrator.context.discovery

import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * Filters candidate files based on configured include paths allowlist.
 *
 * When include paths are configured, ONLY files within those paths are included.
 * When include paths are empty (disabled), all files are included (blacklist mode via other filters).
 *
 * Supports three types of paths:
 * - Relative to project root: "src/", "lib/"
 * - Absolute paths: "/home/user/projects/my-lib/", "/opt/shared-code/"
 * - Relative to parent directories: "../sibling-project/", "../../shared/utils/"
 */
class IncludePathsFilter private constructor(
    private val mode: Mode,
    private val allowedPaths: Set<Path>
) {

    enum class Mode { ALLOWLIST, DISABLED }

    /**
     * Returns true if the path should be included based on the configured allowlist.
     *
     * - If disabled: always returns true (no filtering)
     * - If allowlist: returns true if path is under one of the allowed paths
     */
    fun shouldInclude(candidatePath: Path): Boolean {
        if (mode == Mode.DISABLED) return true

        val normalized = candidatePath.toAbsolutePath().normalize()

        // A path is included if it is under one of the allowed paths
        return allowedPaths.any { allowedPath ->
            val normalizedAllowed = allowedPath.toAbsolutePath().normalize()
            normalized == normalizedAllowed || normalized.startsWith(normalizedAllowed)
        }
    }

    companion object {
        /**
         * Creates a filter with an allowlist of paths.
         * Only files under these paths will be included.
         */
        fun allowlist(paths: Collection<Path>): IncludePathsFilter =
            IncludePathsFilter(Mode.ALLOWLIST, paths.map { it.toAbsolutePath().normalize() }.toSet())

        /**
         * Creates a disabled filter that includes all paths.
         */
        fun disabled(): IncludePathsFilter = IncludePathsFilter(Mode.DISABLED, emptySet())

        /**
         * Creates a filter based on configuration.
         *
         * Expects raw path strings (relative, absolute, or parent-relative) and a base directory
         * for resolving relative paths.
         *
         * @param includePaths Raw path strings from configuration
         * @param baseDir Base directory for resolving relative paths
         * @return Filter in ALLOWLIST mode if paths are non-empty, DISABLED otherwise
         */
        fun fromConfig(
            includePaths: Collection<String>,
            baseDir: Path
        ): IncludePathsFilter {
            val normalized = includePaths
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { resolvePath(it, baseDir) }
                .toSet()

            return if (normalized.isNotEmpty()) {
                allowlist(normalized)
            } else {
                disabled()
            }
        }

        /**
         * Resolves a path string to an absolute Path.
         *
         * Supports three path types:
         * 1. Relative paths: resolved relative to baseDir
         *    Examples: "src/", "lib/components/"
         *
         * 2. Absolute paths: used as-is
         *    Examples: "/home/user/projects/my-lib/", "/opt/shared-code/"
         *
         * 3. Parent-relative paths: resolved relative to baseDir
         *    Examples: "../sibling-project/", "../../shared/utils/"
         *
         * @param pathStr Raw path string from configuration
         * @param baseDir Base directory for resolving relative paths
         * @return Normalized absolute Path
         */
        private fun resolvePath(pathStr: String, baseDir: Path): Path {
            val candidate = Path.of(pathStr)
            return if (candidate.isAbsolute) {
                candidate
            } else {
                baseDir.resolve(candidate)
            }
        }
    }
}
