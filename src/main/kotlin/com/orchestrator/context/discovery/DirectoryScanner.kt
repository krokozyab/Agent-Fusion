package com.orchestrator.context.discovery

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Recursively walks project roots and returns indexable files vetted by [PathValidator].
 */
class DirectoryScanner(
    private val validator: PathValidator,
    private val parallel: Boolean = false,
    private val progressListener: ((Path, PathValidator.ValidationResult) -> Unit)? = null
) {

    private val validationLock = Any()

    fun scan(roots: List<Path>): List<Path> {
        if (roots.isEmpty()) return emptyList()

        val normalizedRoots = roots.map { it.toAbsolutePath().normalize() }
        val results = Collections.synchronizedSet(LinkedHashSet<Path>())

        val existingRoots = normalizedRoots.filter { Files.exists(it) }
        if (existingRoots.isEmpty()) {
            // Log which roots don't exist for debugging
            val missingRoots = normalizedRoots.filterNot { Files.exists(it) }
            throw IllegalArgumentException("No watch roots exist! Checked: $missingRoots")
        }

        val runner: (Path) -> Unit = { root -> scanRoot(root, results) }

        if (parallel && existingRoots.size > 1) {
            existingRoots.parallelStream().forEach(runner)
        } else {
            existingRoots.forEach(runner)
        }

        return ArrayList(results)
    }

    private fun scanRoot(root: Path, results: MutableSet<Path>) {
        val stack = ArrayDeque<Path>()
        stack.add(root)
        val visitedDirectories = mutableSetOf<Path>()

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val normalized = current.toAbsolutePath().normalize()

            if (!Files.exists(normalized)) continue

            val validation = validate(normalized)
            progressListener?.invoke(normalized, validation)

            if (!validation.valid) {
                // Skip branch entirely if directory rejected.
                continue
            }

            if (Files.isDirectory(normalized)) {
                if (!visitedDirectories.add(normalized)) continue
                try {
                    Files.newDirectoryStream(normalized).use { stream ->
                        for (child in stream) {
                            val childNormalized = child.toAbsolutePath().normalize()
                            if (shouldIgnore(childNormalized)) continue
                            stack.add(childNormalized)
                        }
                    }
                } catch (_: IOException) {
                    // Ignore unreadable directories.
                }
            } else {
                results.add(normalized)
            }
        }
    }

    private fun validate(path: Path): PathValidator.ValidationResult =
        synchronized(validationLock) { validator.validate(path) }

    private fun shouldIgnore(path: Path): Boolean =
        synchronized(validationLock) { validator.isInIgnorePatterns(path) }

    companion object {
        fun scan(roots: List<Path>, validator: PathValidator): List<Path> =
            DirectoryScanner(validator).scan(roots)
    }
}
