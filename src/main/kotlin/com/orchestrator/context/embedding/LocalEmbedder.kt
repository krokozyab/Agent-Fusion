package com.orchestrator.context.embedding

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.math.sqrt
import com.orchestrator.utils.Logger

class LocalEmbedder(
    private val modelPath: Path?,
    private val modelName: String = "sentence-transformers/all-MiniLM-L6-v2",
    private val dimension: Int = 384,
    private val normalize: Boolean = true,
    private val maxBatchSize: Int = 32
) : Embedder {

    private val log = Logger.logger("com.orchestrator.context.embedding.LocalEmbedder")

    companion object {
        private fun getDefaultModelPath(): Path {
            val resourcePath = LocalEmbedder::class.java.getResource("/models/all-MiniLM-L6-v2.onnx")
            if (resourcePath != null) {
                val tempFile = kotlin.io.path.createTempFile("all-MiniLM-L6-v2", ".onnx")
                tempFile.toFile().deleteOnExit()
                resourcePath.openStream().use { input ->
                    tempFile.toFile().outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                return tempFile
            }
            
            val envPath = System.getenv("ONNX_MODEL_PATH")
            if (envPath != null) {
                return Path.of(envPath)
            }
            
            val jarLocation = Path.of(LocalEmbedder::class.java.protectionDomain.codeSource.location.toURI())
            val jarDir = if (jarLocation.toString().endsWith(".jar")) jarLocation.parent else jarLocation
            return jarDir.resolve("all-MiniLM-L6-v2.onnx")
        }
    }

    private val mutex = Mutex()
    private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null

    private suspend fun ensureInitialized() = mutex.withLock {
        if (session == null) {
            val path = modelPath ?: getDefaultModelPath()
            log.info("Initializing embedder with model: modelPath=$modelPath, resolvedPath=$path, modelName=$modelName, dimension=$dimension")
            if (!path.exists()) {
                throw IllegalStateException("Model not found at $path. Please download the ONNX model first.")
            }
            log.info("Model file found at: $path (size=${path.toFile().length()} bytes)")
            environment = OrtEnvironment.getEnvironment()
            session = environment!!.createSession(path.toString())
            log.info("ONNX session created successfully for model at: $path")
        }
    }

    override suspend fun embed(text: String): FloatArray {
        ensureInitialized()
        val tokens = tokenize(text)
        val embedding = runInference(listOf(tokens)).first()
        return if (normalize) normalizeVector(embedding) else embedding
    }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        ensureInitialized()
        
        return texts.chunked(maxBatchSize).flatMap { batch ->
            val tokensList = batch.map { tokenize(it) }
            val embeddings = runInference(tokensList)
            if (normalize) embeddings.map { normalizeVector(it) } else embeddings
        }
    }

    private fun runInference(tokensList: List<IntArray>): List<FloatArray> {
        val env = environment ?: throw IllegalStateException("Environment not initialized")
        val sess = session ?: throw IllegalStateException("Session not initialized")

        val batchSize = tokensList.size
        val maxSeqLen = tokensList.maxOf { it.size }
        
        val inputIds = Array(batchSize) { i ->
            LongArray(maxSeqLen) { j ->
                if (j < tokensList[i].size) tokensList[i][j].toLong() else 0L
            }
        }
        
        val attentionMask = Array(batchSize) { i ->
            LongArray(maxSeqLen) { j ->
                if (j < tokensList[i].size) 1L else 0L
            }
        }

        val tokenTypeIds = Array(batchSize) { LongArray(maxSeqLen) { 0L } }
        
        val inputIdsTensor = OnnxTensor.createTensor(env, inputIds)
        val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask)
        val tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds)

        val inputs = mapOf(
            "input_ids" to inputIdsTensor,
            "attention_mask" to attentionMaskTensor,
            "token_type_ids" to tokenTypeIdsTensor
        )

        val results = sess.run(inputs)
        val output = results[0].value as Array<Array<FloatArray>>
        
        inputIdsTensor.close()
        attentionMaskTensor.close()
        tokenTypeIdsTensor.close()
        results.close()

        return output.mapIndexed { i, sequence -> meanPooling(sequence, attentionMask[i]) }
    }

    private fun meanPooling(sequence: Array<FloatArray>, mask: LongArray): FloatArray {
        val hiddenSize = sequence[0].size
        val result = FloatArray(hiddenSize)
        var count = 0
        
        for (i in sequence.indices) {
            if (mask[i] == 1L) {
                for (j in 0 until hiddenSize) {
                    result[j] += sequence[i][j]
                }
                count++
            }
        }
        
        if (count > 0) {
            for (j in 0 until hiddenSize) {
                result[j] /= count
            }
        }
        
        return result
    }

    private fun tokenize(text: String): IntArray {
        // Fast hash-based tokenization optimized for performance
        val vocabSize = 30522
        val reservedOffset = 1000
        val availableRange = vocabSize - reservedOffset
        val maxTokenPayload = 510  // 512 - 2 (for [CLS] and [SEP])

        val tokens = text.lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { token ->
                val hash = token.hashCode()
                val positive = hash and Int.MAX_VALUE
                reservedOffset + (positive % availableRange)
            }
            .take(maxTokenPayload)

        // [CLS] = 101, [SEP] = 102
        return intArrayOf(101) + tokens.toIntArray() + intArrayOf(102)
    }

    private fun normalizeVector(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm > 0) vector.map { it / norm }.toFloatArray() else vector
    }

    override fun getDimension(): Int = dimension

    override fun getModel(): String = modelName

    fun close() {
        session?.close()
        session = null
    }
}
