package com.orchestrator.context.neo4j

import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.embedding.Embedder

class EmbeddingCoordinator(
    private val embedder: Embedder,
    private val batchSize: Int = 512
) {
    
    suspend fun embedChunksBatch(chunks: List<Chunk>): Map<Long, FloatArray> {
        if (chunks.isEmpty()) return emptyMap()
        
        val texts = chunks.map { it.content }
        val embeddings = embedder.embedBatch(texts)
        
        return chunks.zip(embeddings).associate { (chunk, embedding) ->
            chunk.id to embedding
        }
    }
    
    suspend fun embedChunksBatchInGroups(chunks: List<Chunk>): Map<Long, FloatArray> {
        if (chunks.isEmpty()) return emptyMap()
        
        return chunks.chunked(batchSize).flatMap { batch ->
            embedChunksBatch(batch).entries
        }.associate { it.key to it.value }
    }
}
