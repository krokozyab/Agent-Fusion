package com.orchestrator.context.discovery

import com.orchestrator.context.config.IndexingConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Enforces symlink policies when traversing watched project directories.
 */
class SymlinkHandler(
    allowedRoots: List<Path>,
    private val defaultConfig: IndexingConfig
) {

    private val normalizedRoots: List<Path> = allowedRoots.map { it.toAbsolutePath().normalize() }

    fun shouldFollow(link: Path): Boolean = shouldFollow(link, defaultConfig)

    fun shouldFollow(link: Path, config: IndexingConfig): Boolean {
        if (!config.followSymlinks) return false
        if (!Files.isSymbolicLink(link)) return false

        // Cycle safety is handled per-traversal elsewhere: resolveTarget() detects symlink chains,
        // and DirectoryScanner tracks visited directories within a single scan. We deliberately do
        // NOT keep a persistent visited set here — that made a symlink follow-able only once for the
        // lifetime of this handler, so an edited symlinked file would never be re-indexed (and the
        // mutable set was shared across threads unsynchronised).
        val target = resolveTarget(link, config.maxSymlinkDepth) ?: return false
        return !isEscape(target, normalizedRoots)
    }

    fun resolveTarget(link: Path): Path? = resolveTarget(link, defaultConfig.maxSymlinkDepth)

    fun isEscape(link: Path): Boolean = isEscape(link, normalizedRoots)

    fun isEscape(link: Path, allowedRoots: List<Path>): Boolean {
        val normalizedTarget = link.toAbsolutePath().normalize()
        if (allowedRoots.isEmpty()) return true
        return allowedRoots.none { root ->
            val normalizedRoot = root.toAbsolutePath().normalize()
            normalizedTarget == normalizedRoot || normalizedTarget.startsWith(normalizedRoot)
        }
    }

    private fun resolveTarget(link: Path, maxDepth: Int): Path? {
        var depth = 0
        var current = link.toAbsolutePath().normalize()
        val seen = mutableSetOf<Path>()

        while (Files.isSymbolicLink(current)) {
            if (depth >= maxDepth) return null
            val normalizedCurrent = current.toAbsolutePath().normalize()
            if (!seen.add(normalizedCurrent)) return null

            val rawTarget = try {
                Files.readSymbolicLink(current)
            } catch (_: IOException) {
                return null
            }

            current = (current.parent?.resolve(rawTarget) ?: rawTarget)
                .toAbsolutePath()
                .normalize()
            depth++
        }

        if (!Files.exists(current)) return null
        return current
    }

}
