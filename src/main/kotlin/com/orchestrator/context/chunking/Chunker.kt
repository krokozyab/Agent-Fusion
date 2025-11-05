package com.orchestrator.context.chunking

import com.orchestrator.context.domain.Chunk

/**
 * Contract implemented by all language or format specific chunkers. Chunkers split a file into
 * addressable [Chunk] instances and provide quick token estimations for downstream budgeting.
 */
interface Chunker {

    /** Metadata describing the strategy so configuration can discover and select chunkers. */
    val strategy: ChunkingStrategy

    /**
     * Produce a stable list of chunks for the supplied file content.
     *
     * @param content  full file contents already decoded as UTF-8 text
     * @param filePath logical project-relative file path
     * @param language highlight dialect or mime-language hint (lowercase slug)
     */
    fun chunk(content: String, filePath: String, language: String): List<Chunk>

    /** Lightweight token estimation used to cap chunk sizes without full model invocation. */
    fun estimateTokens(text: String): Int
}

/**
 * Declarative descriptor for chunking strategies exposed through configuration and SPI discovery.
 */
data class ChunkingStrategy(
    val id: String,
    val displayName: String,
    val supportedLanguages: Set<String> = emptySet(),
    val defaultMaxTokens: Int? = null,
    val description: String? = null
)
