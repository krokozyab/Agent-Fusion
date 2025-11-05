package com.orchestrator.context

import com.orchestrator.context.ContextRepository.ChunkArtifacts
import com.orchestrator.context.ContextRepository.FileArtifacts
import com.orchestrator.context.ContextRepository.ChunkWithFile
import com.orchestrator.context.domain.ContextScope
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.domain.TokenBudget
import com.orchestrator.context.domain.ContextSnippet

/**
 * Thin service layer that orchestrates repository calls and provides
 * high-level entry points for future context workflows.
 */
class ContextDataService(
    private val repository: ContextRepository = ContextRepository
) {
    /**
     * Persist the given file state and associated artefacts in a single transaction.
     * Returns the persisted snapshot (with generated identifiers populated).
     */
    fun syncFileArtifacts(fileState: FileState, chunks: List<ChunkArtifacts>): FileArtifacts =
        repository.replaceFileArtifacts(fileState, chunks)

    /** Load persisted artefacts for a file path, if available. */
    fun loadFileArtifacts(relativePath: String): FileArtifacts? =
        repository.fetchFileArtifactsByPath(relativePath)

    /** Query for chunks that match the provided scope filters. */
    fun queryChunks(scope: ContextScope): List<ChunkWithFile> = repository.searchChunks(scope)

    /** Retrieve context snippets clipped to the provided token budget. */
    fun buildSnippets(scope: ContextScope, budget: TokenBudget): List<ContextSnippet> =
        repository.fetchSnippets(scope, budget)

    /** Delete all persisted artefacts for the provided file path. */
    fun deleteFile(relativePath: String): Boolean = repository.deleteFileArtifacts(relativePath)

    /** Delete all persisted artefacts for the provided absolute path (unique across multiple watch roots). */
    fun deleteFileByAbsPath(absolutePath: String): Boolean = repository.deleteFileArtifactsByAbsPath(absolutePath)
}
