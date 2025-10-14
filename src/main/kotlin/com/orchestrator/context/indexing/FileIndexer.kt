package com.orchestrator.context.indexing

import com.orchestrator.context.ContextDataService
import com.orchestrator.context.ContextRepository
import com.orchestrator.context.chunking.Chunker
import com.orchestrator.context.chunking.ChunkerRegistry
import com.orchestrator.context.chunking.TokenEstimator
import com.orchestrator.context.domain.Chunk
import com.orchestrator.context.domain.Embedding
import com.orchestrator.context.domain.FileState
import com.orchestrator.context.embedding.Embedder
import com.orchestrator.utils.Logger
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

/**
 * Index a single file by chunking its content, generating embeddings, and persisting the artefacts.
 */
class FileIndexer(
    private val embedder: Embedder,
    projectRoot: Path,
    private val dataService: ContextDataService = ContextDataService(),
    private val metadataExtractor: FileMetadataExtractor = FileMetadataExtractor,
    private val chunkerRegistry: ChunkerRegistry = ChunkerRegistry,
    private val tokenEstimator: TokenEstimator = TokenEstimator,
    private val readCharset: Charset = StandardCharsets.UTF_8,
    private val embeddingBatchSize: Int = 64
) {

    init {
        require(embeddingBatchSize > 0) { "embeddingBatchSize must be positive" }
    }

    private val log = Logger.logger("com.orchestrator.context.indexing.FileIndexer")
    private val root: Path = projectRoot.toAbsolutePath().normalize()

    /**
     * Index the provided file path synchronously.
     */
    fun indexFile(path: Path): IndexResult = runBlocking {
        indexFileInternal(path)
    }

    /**
     * Suspend-friendly variant for coroutine callers.
     */
    suspend fun indexFileAsync(path: Path): IndexResult = indexFileInternal(path)

    /**
     * Index the provided file path and return the outcome.
     */
    private suspend fun indexFileInternal(path: Path): IndexResult {
        val absolutePath = path.toAbsolutePath().normalize()
        if (!absolutePath.startsWith(root)) {
            val message = "Path $absolutePath is outside project root $root"
            log.warn(message)
            return IndexResult(
                success = false,
                relativePath = path.toString(),
                chunkCount = 0,
                embeddingCount = 0,
                error = message
            )
        }

        val relativePath = root.relativize(absolutePath).toString()
        return runCatching {
            val metadata = metadataExtractor.extractMetadata(absolutePath)
            coroutineContext.ensureActive()
            val content = readFile(absolutePath)
            coroutineContext.ensureActive()

            val chunker = chunkerRegistry.getChunker(absolutePath)
            val languageHint = resolveLanguage(metadata, chunker, absolutePath)
            val chunkLanguage = languageHint ?: "text"
            val rawChunks = chunker.chunk(content, relativePath, chunkLanguage)
            val normalizedChunks = normalizeChunks(rawChunks, chunker)

            coroutineContext.ensureActive()
            val embeddings = generateEmbeddings(normalizedChunks)
            val now = Instant.now()

            val chunkArtifacts = normalizedChunks.mapIndexed { index, chunk ->
                val embedding = embeddings[index]
                ContextRepository.ChunkArtifacts(
                    chunk = chunk.copy(id = 0, fileId = 0, ordinal = index),
                    embeddings = listOf(embedding),
                    links = emptyList()
                )
            }

            val fileState = FileState(
                id = 0,
                relativePath = relativePath,
                contentHash = metadata.contentHash,
                sizeBytes = metadata.sizeBytes,
                modifiedTimeNs = metadata.modifiedTimeNs,
                language = languageHint,
                kind = chunker.strategy.id,
                fingerprint = null,
                indexedAt = now,
                isDeleted = false
            )

            dataService.syncFileArtifacts(fileState, chunkArtifacts)

            IndexResult(
                success = true,
                relativePath = relativePath,
                chunkCount = normalizedChunks.size,
                embeddingCount = embeddings.size,
                error = null
            )
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            val message = throwable.message ?: throwable::class.simpleName ?: "Unknown indexing error"
            log.error("Failed to index $relativePath: $message", throwable)
            IndexResult(
                success = false,
                relativePath = relativePath,
                chunkCount = 0,
                embeddingCount = 0,
                error = message
            )
        }
    }

    private fun readFile(path: Path): String = try {
        Files.readString(path, readCharset)
    } catch (ioe: IOException) {
        throw IOException("Failed to read file: $path (${ioe.message})", ioe)
    }

    private fun normalizeChunks(chunks: List<Chunk>, chunker: Chunker): List<Chunk> {
        if (chunks.isEmpty()) return emptyList()
        return chunks.mapIndexed { index, chunk ->
            val estimate = when (val provided = chunk.tokenEstimate) {
                null -> fallbackTokenEstimate(chunk, chunker)
                else -> max(provided, MIN_TOKEN_ESTIMATE)
            }
            chunk.copy(
                id = 0,
                fileId = 0,
                ordinal = index,
                tokenEstimate = estimate
            )
        }
    }

    private fun fallbackTokenEstimate(chunk: Chunk, chunker: Chunker): Int {
        val viaChunker = chunker.estimateTokens(chunk.content)
        if (viaChunker >= MIN_TOKEN_ESTIMATE) return viaChunker
        return max(tokenEstimator.estimate(chunk.content), MIN_TOKEN_ESTIMATE)
    }

    private suspend fun generateEmbeddings(chunks: List<Chunk>): List<Embedding> {
        if (chunks.isEmpty()) return emptyList()
        val texts = chunks.map { it.content }
        val vectors = embedInBatches(texts)
        if (vectors.size != chunks.size) {
            throw IllegalStateException("Embedding count ${vectors.size} does not match chunk count ${chunks.size}")
        }
        val dimension = embedder.getDimension()
        val model = embedder.getModel()
        val now = Instant.now()
        return vectors.mapIndexed { index, vector ->
            if (vector.size != dimension) {
                throw IllegalStateException("Embedding dimension mismatch for chunk $index: expected $dimension, got ${vector.size}")
            }
            Embedding(
                id = 0,
                chunkId = 0,
                model = model,
                dimensions = dimension,
                vector = vector.toList(),
                createdAt = now
            )
        }
    }

    private suspend fun embedInBatches(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val results = ArrayList<FloatArray>(texts.size)
        var index = 0
        while (index < texts.size) {
            coroutineContext.ensureActive()
            val end = min(index + embeddingBatchSize, texts.size)
            val batch = texts.subList(index, end)
            val batchResult = embedder.embedBatch(batch)
            if (batchResult.size != batch.size) {
                throw IllegalStateException("Embedder returned ${batchResult.size} vectors for batch of size ${batch.size}")
            }
            results.addAll(batchResult)
            index = end
        }
        return results
    }

    private fun resolveLanguage(metadata: FileMetadata, chunker: Chunker, path: Path): String? {
        metadata.language?.let { return it }
        val strategyLanguages = chunker.strategy.supportedLanguages
        if (strategyLanguages.size == 1) {
            return strategyLanguages.first()
        }

        val extension = path.fileName?.toString()
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
        if (extension != null && strategyLanguages.contains(extension)) {
            return extension
        }

        metadata.mimeType?.let { mime ->
            val candidate = mime.substringAfterLast('/')
            if (candidate.isNotBlank()) return candidate
        }
        return null
    }

    companion object {
        private const val MIN_TOKEN_ESTIMATE = 1
    }
}

data class IndexResult(
    val success: Boolean,
    val relativePath: String,
    val chunkCount: Int,
    val embeddingCount: Int,
    val error: String?
)
