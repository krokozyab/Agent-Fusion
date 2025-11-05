package com.orchestrator.context.discovery

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * Aggregates ignore patterns from configuration and ignore files, then evaluates glob matches.
 */
class PathFilter private constructor(
    private val matchers: List<Regex>,
    private val caseInsensitive: Boolean
) {

    fun shouldIgnore(path: Path): Boolean {
        if (matchers.isEmpty()) return false
        val absolute = normalize(path.toAbsolutePath().normalize().toString())
        val relative = path.fileName?.let { normalize(it.toString()) }
        return matchers.any { regex ->
            regex.matches(absolute) || (relative != null && regex.matches(relative))
        }
    }

    private fun normalize(value: String): String {
        val cleaned = value.replace("\\", "/")
        return if (caseInsensitive) cleaned.lowercase(Locale.US) else cleaned
    }

    companion object {
        fun fromSources(
            root: Path,
            configPatterns: List<String> = emptyList(),
            caseInsensitive: Boolean = true,
            includeGitignore: Boolean = true,
            includeContextignore: Boolean = true,
            includeDockerignore: Boolean = true
        ): PathFilter {
            val collected = mutableListOf<String>()
            configPatterns.forEach { collected.addAll(expandPattern(it)) }

            val ignoreFiles = buildIgnoreFileList(includeGitignore, includeContextignore, includeDockerignore)

            ignoreFiles.forEach { name ->
                val file = root.resolve(name)
                if (Files.exists(file) && file.isRegularFile()) {
                    Files.lines(file).use { lines ->
                        lines.asSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() && !it.startsWith("#") }
                            .forEach { pattern -> collected.addAll(expandPattern(pattern)) }
                    }
                }
            }
            val matchers = collected.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .map { preprocess(it) }
                .map { globToRegex(it, caseInsensitive) }
            return PathFilter(matchers, caseInsensitive)
        }

        private fun buildIgnoreFileList(
            includeGitignore: Boolean,
            includeContextignore: Boolean,
            includeDockerignore: Boolean
        ): List<String> {
            val files = mutableListOf<String>()
            if (includeContextignore) files += ".contextignore"
            if (includeGitignore) files += ".gitignore"
            if (includeDockerignore) files += ".dockerignore"
            return files
        }

        private fun expandPattern(pattern: String): List<String> {
            val trimmed = pattern.trim()
            if (trimmed.isEmpty()) return emptyList()
            val list = mutableListOf(trimmed)
            val hasWildcard = trimmed.any { it == '*' || it == '?' }
            if (!hasWildcard && !trimmed.endsWith("/**")) {
                list += if (trimmed.endsWith("/")) "$trimmed**" else "$trimmed/**"
            }
            return list
        }

        private fun preprocess(pattern: String): String {
            var cleaned = pattern.replace("\\", "/")
            if (cleaned.endsWith("/")) cleaned += "**"
            if (!cleaned.startsWith("/")) cleaned = "**/$cleaned"
            return cleaned
        }

        private fun globToRegex(glob: String, caseInsensitive: Boolean): Regex {
            val regex = buildString {
                append('^')
                var i = 0
                while (i < glob.length) {
                    val c = glob[i]
                    when (c) {
                        '*' -> {
                            if (i + 1 < glob.length && glob[i + 1] == '*') {
                                append(".*")
                                i += 2
                            } else {
                                append("[^/]*")
                                i++
                            }
                        }
                        '?' -> {
                            append("[^/]")
                            i++
                        }
                        '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                            append('\\').append(c)
                            i++
                        }
                        else -> {
                            append(c)
                            i++
                        }
                    }
                }
                append('$')
            }
            val options = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return Regex(regex, options)
        }
    }
}
