package com.orchestrator.context.domain

import java.time.Instant

/**
 * Representation of a symbol extracted from a source file.
 *
 * Symbol records are persisted so that downstream providers can efficiently
 * answer symbol-oriented queries without reparsing files.
 */
data class SymbolRecord(
    val id: Long,
    val fileId: Long,
    val chunkId: Long?,
    val symbolType: SymbolType,
    val name: String,
    val qualifiedName: String?,
    val signature: String?,
    val language: String?,
    val startLine: Int?,
    val endLine: Int?,
    val createdAt: Instant
) {
    init {
        require(name.isNotBlank()) { "Symbol name must not be blank" }
    }
}
