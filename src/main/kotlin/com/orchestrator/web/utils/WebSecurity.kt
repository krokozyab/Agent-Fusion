package com.orchestrator.web.utils

import java.io.File

/**
 * Shared web-layer security helpers: HTML escaping and path-traversal containment.
 */
object WebSecurity {

    /** Escape a string for safe interpolation into HTML text/attribute context. */
    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * Resolve [requestedPath] and confirm it is a regular file located inside one of [allowedRoots].
     *
     * Both the request and the roots are canonicalized (resolving `..` and symlinks) before the
     * containment check, so this defeats path-traversal (`../../etc/passwd`) and symlink escapes.
     *
     * @return the canonical [File] if it is allowed and is a regular file, otherwise null.
     */
    fun resolveWithinRoots(requestedPath: String, allowedRoots: List<String>): File? {
        if (requestedPath.isBlank() || allowedRoots.isEmpty()) return null

        val target = try {
            File(requestedPath).canonicalFile
        } catch (_: Exception) {
            return null
        }
        if (!target.isFile) return null

        val canonicalRoots = allowedRoots.mapNotNull { root ->
            try {
                File(root).canonicalFile
            } catch (_: Exception) {
                null
            }
        }

        val contained = canonicalRoots.any { root -> isWithin(target, root) }
        return if (contained) target else null
    }

    /** True if [file] equals [root] or is nested under it, using path-segment boundaries. */
    private fun isWithin(file: File, root: File): Boolean {
        var current: File? = file
        while (current != null) {
            if (current == root) return true
            current = current.parentFile
        }
        return false
    }
}
