package com.orchestrator.context.embedding

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalEmbedderTest {

    @Test
    fun `getDimension returns configured dimension`() {
        val embedder = LocalEmbedder(
            modelPath = Path("/tmp/model.onnx"),
            dimension = 384
        )
        assertEquals(384, embedder.getDimension())
    }

    @Test
    fun `getModel returns configured model name`() {
        val embedder = LocalEmbedder(
            modelPath = Path("/tmp/model.onnx"),
            modelName = "test-model"
        )
        assertEquals("test-model", embedder.getModel())
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ONNX_MODEL_PATH", matches = ".+")
    fun `embed returns vector of correct dimension`() = runTest {
        val modelPath = Paths.get(System.getenv("ONNX_MODEL_PATH"))
        val embedder = LocalEmbedder(modelPath = modelPath, dimension = 384)
        
        val embedding = embedder.embed("Hello world")
        
        assertEquals(384, embedding.size)
        embedder.close()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ONNX_MODEL_PATH", matches = ".+")
    fun `embedBatch processes multiple texts`() = runTest {
        val modelPath = Paths.get(System.getenv("ONNX_MODEL_PATH"))
        val embedder = LocalEmbedder(modelPath = modelPath, dimension = 384)
        
        val texts = listOf("Hello world", "Test embedding", "Another text")
        val embeddings = embedder.embedBatch(texts)
        
        assertEquals(3, embeddings.size)
        embeddings.forEach { assertEquals(384, it.size) }
        embedder.close()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ONNX_MODEL_PATH", matches = ".+")
    fun `normalization produces unit vectors`() = runTest {
        val modelPath = Paths.get(System.getenv("ONNX_MODEL_PATH"))
        val embedder = LocalEmbedder(modelPath = modelPath, normalize = true)
        
        val embedding = embedder.embed("Test text")
        val norm = sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        
        assertTrue(abs(norm - 1.0f) < 0.01f, "Expected unit vector, got norm=$norm")
        embedder.close()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ONNX_MODEL_PATH", matches = ".+")
    fun `embedBatch handles large batches`() = runTest {
        val modelPath = Paths.get(System.getenv("ONNX_MODEL_PATH"))
        val embedder = LocalEmbedder(modelPath = modelPath, maxBatchSize = 10)
        
        val texts = (1..50).map { "Text number $it" }
        val embeddings = embedder.embedBatch(texts)
        
        assertEquals(50, embeddings.size)
        embedder.close()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ONNX_MODEL_PATH", matches = ".+")
    fun `similar texts produce similar embeddings`() = runTest {
        val modelPath = Paths.get(System.getenv("ONNX_MODEL_PATH"))
        val embedder = LocalEmbedder(modelPath = modelPath, normalize = true)
        
        val emb1 = embedder.embed("The cat sits on the mat")
        val emb2 = embedder.embed("A cat is sitting on a mat")
        val emb3 = embedder.embed("Completely different topic about cars")
        
        val similarity12 = cosineSimilarity(emb1, emb2)
        val similarity13 = cosineSimilarity(emb1, emb3)
        
        assertTrue(similarity12 > similarity13, "Similar texts should have higher similarity")
        embedder.close()
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ONNX_MODEL_PATH", matches = ".+")
    fun `embedBatch with empty list returns empty list`() = runTest {
        val modelPath = Paths.get(System.getenv("ONNX_MODEL_PATH"))
        val embedder = LocalEmbedder(modelPath = modelPath)
        
        val embeddings = embedder.embedBatch(emptyList())
        
        assertTrue(embeddings.isEmpty())
        embedder.close()
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size)
        val dotProduct = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }
        val normA = sqrt(a.sumOf { (it * it).toDouble() })
        val normB = sqrt(b.sumOf { (it * it).toDouble() })
        return (dotProduct / (normA * normB)).toFloat()
    }
}
