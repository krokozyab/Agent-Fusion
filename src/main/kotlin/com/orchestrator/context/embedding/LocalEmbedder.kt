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

/** Thrown when the embedding model cannot be initialized (missing, unreadable, or invalid). */
class EmbedderInitException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

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
    // Set once if model initialization fails fatally. Subsequent calls rethrow it immediately instead
    // of re-attempting createSession per file: a bad/unreadable model is the same for every file, and
    // retrying produced a storm of identical stack traces (one per indexed file). A fresh embedder
    // instance (e.g. a new indexing run) starts clean and retries.
    private var initFailure: Throwable? = null

    // Serializes the native ONNX run so a single forward pass uses all cores. Without this, the batch
    // indexer's concurrent file workers issued overlapping sess.run calls, each spawning `cores`
    // intra-op threads → oversubscription and cache thrash. Tokenization stays outside the lock, so
    // callers can tokenize the next batch while one runs.
    private val inferenceLock = Any()

    // BERT WordPiece tokenizer matching the model's training vocabulary. Immutable
    // after construction, safe to share across coroutines.
    private val tokenizer = BertTokenizer(maxSequenceLength = 512)

    private suspend fun ensureInitialized() = mutex.withLock {
        if (session != null) return@withLock
        // Fail fast on a previously-recorded fatal failure instead of re-attempting per file.
        initFailure?.let { throw it }

        val path = modelPath ?: getDefaultModelPath()
        log.info("Initializing embedder with model: modelPath=$modelPath, resolvedPath=$path, modelName=$modelName, dimension=$dimension")
        try {
            if (!path.exists()) {
                throw IllegalStateException("Model not found at $path. Please download the ONNX model first.")
            }
            log.info("Model file found at: $path (size=${path.toFile().length()} bytes)")
            environment = OrtEnvironment.getEnvironment()
            // Default session options already use all cores for intra-op (with SEQUENTIAL exec and
            // ALL_OPT graph optimization). We deliberately do NOT pass an explicit SessionOptions:
            // in this onnxruntime build a user-managed SessionOptions trips "Attempt to use
            // DefaultLogger but none has been registered" during createSession. Oversubscription is
            // prevented instead by serializing inference (see inferenceLock), so a single pass gets
            // every core without N concurrent passes each spawning `cores` threads.
            session = environment!!.createSession(path.toString())
            log.info("ONNX session created successfully for model at: $path")
        } catch (t: Throwable) {
            val failure = EmbedderInitException(describeInitFailure(path, t), t)
            initFailure = failure
            // Log once here; callers that rethrow on later files get the cached cause without re-logging.
            log.error("Embedder initialization failed permanently: {}", failure.message)
            throw failure
        }
    }

    /**
     * Build an actionable message for a model-init failure, adding a hint for the macOS privacy (TCC)
     * case where the file can be stat-ed but its contents are unreadable (EPERM) — typical for models
     * left in protected folders like Downloads/Desktop/Documents.
     */
    private fun describeInitFailure(path: Path, cause: Throwable): String {
        val raw = cause.message.orEmpty()
        val looksLikeAccessBlock = raw.contains("system error number 1", ignoreCase = true) ||
            raw.contains("Operation not permitted", ignoreCase = true) ||
            raw.contains("ORT_FAIL", ignoreCase = true)
        val base = "Failed to initialize embedding model at $path"
        return if (looksLikeAccessBlock) {
            "$base — the model file could not be read. On macOS this is usually a privacy (TCC) block " +
                "on protected folders (Downloads/Desktop/Documents): the process can see the file but " +
                "not read its contents. Move the model to a non-protected location (e.g. ~/fusion-models) " +
                "and point ONNX_MODEL_PATH / context.embedding.modelPath at it, or grant the launcher " +
                "Full Disk Access. Cause: $raw"
        } else {
            "$base. Cause: $raw"
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
        
        // Serialize the native run so a single inference gets all cores (see inferenceLock). Tensor
        // allocation lives inside the lock too, bounding off-heap memory to one batch at a time.
        val output = synchronized(inferenceLock) {
            val inputIdsTensor = OnnxTensor.createTensor(env, inputIds)
            val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMask)
            val tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds)

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to tokenTypeIdsTensor
            )

            // try/finally: the input tensors and the run result hold native (off-heap) memory. If
            // sess.run threw, the previous code skipped the close() calls and leaked that memory on
            // every failed batch. Always release them.
            try {
                val results = sess.run(inputs)
                try {
                    @Suppress("UNCHECKED_CAST")
                    results[0].value as Array<Array<FloatArray>>
                } finally {
                    results.close()
                }
            } finally {
                inputIdsTensor.close()
                attentionMaskTensor.close()
                tokenTypeIdsTensor.close()
            }
        }

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

    private fun tokenize(text: String): IntArray = tokenizer.tokenize(text)

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
