package com.orchestrator.context.embedding

/**
 * Interface for embedding services that convert text into vector representations.
 * Implementations should support both single and batch operations for efficiency.
 */
interface Embedder {
    
    /**
     * Generate an embedding vector for a single text input.
     * 
     * @param text The text to embed
     * @return A float array representing the embedding vector
     */
    suspend fun embed(text: String): FloatArray
    
    /**
     * Generate embedding vectors for multiple text inputs in a single batch.
     * Batch operations are typically more efficient than multiple single calls.
     * 
     * @param texts The list of texts to embed
     * @return A list of float arrays, one embedding per input text
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
    
    /**
     * Get the dimensionality of the embedding vectors produced by this embedder.
     * 
     * @return The number of dimensions in each embedding vector
     */
    fun getDimension(): Int
    
    /**
     * Get the model identifier for this embedder.
     * 
     * @return The model name/identifier (e.g., "text-embedding-ada-002", "all-MiniLM-L6-v2")
     */
    fun getModel(): String
}
