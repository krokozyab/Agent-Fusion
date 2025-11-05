package com.orchestrator.context.discovery

import com.orchestrator.context.config.IndexingConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Enforces symlink policies when traversing watched project directories.
 */
class SymlinkHandler(
    allowedRoots: List<Path>,
    private val defaultConfig: IndexingConfig
) {

    private val normalizedRoots: List<Path> = allowedRoots.map { it.toAbsolutePath().normalize() }
    private val visitedInodes = mutableSetOf<Any?>()
    private val visitedPaths = mutableSetOf<Path>()

    fun shouldFollow(link: Path): Boolean = shouldFollow(link, defaultConfig)

    fun shouldFollow(link: Path, config: IndexingConfig): Boolean {
        if (!config.followSymlinks) return false
        if (!Files.isSymbolicLink(link)) return false

        val target = resolveTarget(link, config.maxSymlinkDepth) ?: return false
        if (isEscape(target, normalizedRoots)) return false

        return markVisited(target)
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

    private fun markVisited(target: Path): Boolean {
        val normalized = target.toAbsolutePath().normalize()
        val key = inodeKey(normalized)
        if (key != null) {
            if (!visitedInodes.add(key)) return false
        } else {
            if (!visitedPaths.add(normalized)) return false
        }
        return true
    }

    private fun inodeKey(path: Path): Any? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
    } catch (_: IOException) {
        null
    }
}
