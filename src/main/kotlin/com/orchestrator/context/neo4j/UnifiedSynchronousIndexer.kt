package com.orchestrator.context.neo4j

import com.orchestrator.context.domain.Chunk
import java.nio.file.Path

class UnifiedSynchronousIndexer(
    private val coordinator: DualStorageCoordinator,
    private val embeddingCoordinator: EmbeddingCoordinator
) {
    
    suspend fun indexDocument(filePath: Path, chunks: List<Chunk>): IndexingResult {
        // Note: Document indexing is handled by FileIndexer.indexToNeo4j()
        // This method is kept for compatibility but not actively used
        return IndexingResult.Success(
            nodesCreated = 0,
            chunksIndexed = chunks.size,
            embeddingsCreated = 0
        )
    }
    
    suspend fun indexCode(structure: CodeStructure, chunks: List<Chunk>): IndexingResult {
        val structureResult = coordinator.indexCode(structure)
        
        if (structureResult !is IndexResult.Success) {
            return IndexingResult.Failed("Structure indexing failed: $structureResult")
        }
        
        coordinator.linkChunksToCode(chunks, structure)
        
        val embeddings = embeddingCoordinator.embedChunksBatch(chunks)
        
        return IndexingResult.Success(
            nodesCreated = structureResult.nodesCreated,
            chunksIndexed = chunks.size,
            embeddingsCreated = embeddings.size
        )
    }
    
    suspend fun indexDocumentBatch(
        documents: List<Pair<Path, List<Chunk>>>
    ): BatchIndexingResult {
        val allChunks = documents.flatMap { it.second }
        // Note: Document batch indexing is handled by FileIndexer.indexToNeo4j()
        // This method is kept for compatibility but not actively used
        return BatchIndexingResult(
            successCount = documents.size,
            failureCount = 0,
            totalNodesCreated = 0,
            totalChunksIndexed = allChunks.size,
            totalEmbeddingsCreated = 0
        )
    }
    
    suspend fun indexCodeBatch(
        codeFiles: List<Pair<CodeStructure, List<Chunk>>>
    ): BatchIndexingResult {
        val allChunks = codeFiles.flatMap { it.second }
        val embeddings = embeddingCoordinator.embedChunksBatchInGroups(allChunks)
        
        var successCount = 0
        var failureCount = 0
        var totalNodes = 0
        
        codeFiles.forEach { (structure, chunks) ->
            val result = coordinator.indexCode(structure)
            when (result) {
                is IndexResult.Success -> {
                    coordinator.linkChunksToCode(chunks, structure)
                    successCount++
                    totalNodes += result.nodesCreated
                }
                else -> failureCount++
            }
        }
        
        return BatchIndexingResult(
            successCount = successCount,
            failureCount = failureCount,
            totalNodesCreated = totalNodes,
            totalChunksIndexed = allChunks.size,
            totalEmbeddingsCreated = embeddings.size
        )
    }
    
    fun deleteFile(filePath: Path, isCode: Boolean) {
        coordinator.deleteFile(filePath, isCode)
    }
}

sealed class IndexingResult {
    data class Success(
        val nodesCreated: Int,
        val chunksIndexed: Int,
        val embeddingsCreated: Int
    ) : IndexingResult()
    
    data class Failed(val error: String) : IndexingResult()
}

data class BatchIndexingResult(
    val successCount: Int,
    val failureCount: Int,
    val totalNodesCreated: Int,
    val totalChunksIndexed: Int,
    val totalEmbeddingsCreated: Int
)
