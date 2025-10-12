package com.orchestrator.context.domain

import java.time.Instant

/**
 * Immutable view of an indexed file and its relevant metadata used across indexing stages.
 */
data class FileState(
    val id: Long,
    val relativePath: String,
    val contentHash: String,
    val sizeBytes: Long,
    val modifiedTimeNs: Long,
    val language: String?,
    val kind: String?,
    val fingerprint: String?,
    val indexedAt: Instant,
    val isDeleted: Boolean
) {
    /** True when the file remains active in the working set. */
    val isActive: Boolean get() = !isDeleted
}
