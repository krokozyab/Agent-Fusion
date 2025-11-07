package com.orchestrator.context.discovery

import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Filters files based on skip patterns using glob pattern matching.
 * Skip patterns are applied AFTER extension filtering to exclude specific files
 * even if they match allowed extensions.
 *
 * Examples:
 *   *.min.js -> skips minified JavaScript files
 *   *.test.ts -> skips test TypeScript files
 *   dist -> skips files in dist directories
 *   .min patterns -> skips files with .min in the name
 */
class SkipFilter private constructor(
    private val matchers: List<GlobMatcher>
) {

    private data class GlobMatcher(val pattern: String, val isAbsolutePattern: Boolean)

    fun shouldSkip(path: Path): Boolean {
        if (matchers.isEmpty()) return false

        return matchers.any { matcher ->
            try {
                // For simple patterns (no "/" or "**"), only match files without directory separators
                if (!matcher.isAbsolutePattern) {
                    val fileNameMatcher = FileSystems.getDefault().getPathMatcher("glob:${matcher.pattern}")
                    val pathStr = path.toString().replace("\\", "/")

                    // For simple patterns, only match if the path has no directory separators
                    // This respects the behavior of * not matching /
                    if (!pathStr.contains("/")) {
                        if (fileNameMatcher.matches(path)) return@any true
                    }
                } else {
                    // For absolute patterns with "/" or "**", use as-is
                    val pathMatcher = FileSystems.getDefault().getPathMatcher("glob:${matcher.pattern}")
                    if (pathMatcher.matches(path)) return@any true

                    // For patterns starting with **, also try the pattern without the leading **/
                    // This handles cases like **/dist/** matching both dist/file and src/dist/file
                    if (matcher.pattern.startsWith("**/")) {
                        val simplifiedPattern = matcher.pattern.substring(3) // Remove **/
                        val simplifiedMatcher = FileSystems.getDefault().getPathMatcher("glob:$simplifiedPattern")
                        if (simplifiedMatcher.matches(path)) return@any true
                    }
                }

                false
            } catch (e: Exception) {
                // If glob pattern is invalid, silently skip
                false
            }
        }
    }

    companion object {
        fun fromPatterns(patterns: List<String> = emptyList()): SkipFilter {
            val matchers = patterns
                .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                .map { pattern ->
                    // Treat patterns with "/" or "**" as absolute patterns
                    val isAbsolutePattern = pattern.contains("/") || pattern.contains("**")
                    GlobMatcher(pattern, isAbsolutePattern)
                }
            return SkipFilter(matchers)
        }
    }
}
