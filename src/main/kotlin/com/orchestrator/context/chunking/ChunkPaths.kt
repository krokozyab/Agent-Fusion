package com.orchestrator.context.chunking

import com.orchestrator.context.domain.ChunkKind

object ChunkPaths {
    /**
     * Build a stable chunk path with kind prefix and '/'-separated segments.
     * Empty segments are skipped.
     */
    fun path(kind: ChunkKind, vararg segments: String?): String {
        val parts = segments.filter { !it.isNullOrBlank() }.map { it!!.trim() }
        val suffix = if (parts.isEmpty()) kind.name.lowercase() else parts.joinToString("/")
        return "${kind.name}:$suffix"
    }

    fun path(kind: ChunkKind, segments: List<String?>): String =
        path(kind, *segments.toTypedArray())
}
