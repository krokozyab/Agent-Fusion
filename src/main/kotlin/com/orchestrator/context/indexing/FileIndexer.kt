package com.orchestrator.context.indexing

import com.orchestrator.context.ContextDataService
import com.orchestrator.context.ContextRepository
import com.orchestrator.context.chunking.Chunker
import com.orchestrator.context.chunking.ChunkerRegistry
import com.orchestrator.context.chunking.TokenEstimator
import com.orchestrator.context.chunking.WordDocumentExtractor
import com.orchestrator.context.chunking.PdfDocumentExtractor
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
import java.sql.SQLException
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
 * Supports multiple watch roots to allow indexing files from the main project root as well as
 * external directories configured via include_paths.
 */
class FileIndexer(
    private val embedder: Embedder,
    projectRoot: Path,
    watchRoots: List<Path> = emptyList(),
    private val dataService: ContextDataService = ContextDataService(),
    private val metadataExtractor: FileMetadataExtractor = FileMetadataExtractor,
    private val chunkerRegistry: ChunkerRegistry = ChunkerRegistry,
    private val tokenEstimator: TokenEstimator = TokenEstimator,
    private val readCharset: Charset = StandardCharsets.UTF_8,
    private val embeddingBatchSize: Int = 64,
    private val maxFileSizeMb: Int = 5,
    private val warnFileSizeMb: Int = 2
) {

    init {
        require(embeddingBatchSize > 0) { "embeddingBatchSize must be positive" }
        require(maxFileSizeMb > 0) { "maxFileSizeMb must be positive" }
        require(warnFileSizeMb > 0) { "warnFileSizeMb must be positive" }
    }

    private val log = Logger.logger("com.orchestrator.context.indexing.FileIndexer")
    private val projectRoot: Path = projectRoot.toAbsolutePath().normalize()
    private val allRoots: List<Path> = if (watchRoots.isNotEmpty()) {
        (listOf(projectRoot) + watchRoots).map { it.toAbsolutePath().normalize() }.distinct()
    } else {
        listOf(projectRoot)
    }
    private val maxFileSizeBytes = maxFileSizeMb * 1024L * 1024L
    private val warnFileSizeBytes = warnFileSizeMb * 1024L * 1024L

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

        // Use absolute path consistently to avoid duplicates across watch roots
        val relativePath = absolutePath.toString()
        return runCatching {
            val extension = absolutePath.fileName.toString()
                .substringAfterLast('.', "")
                .lowercase(Locale.US)

            val metadata = metadataExtractor.extractMetadata(absolutePath)
            coroutineContext.ensureActive()

            // Check file size before reading
            val fileSizeBytes = metadata.sizeBytes
            if (fileSizeBytes > maxFileSizeBytes) {
                val sizeMb = fileSizeBytes / (1024.0 * 1024.0)
                val message = "File exceeds maximum size limit: ${String.format("%.2f", sizeMb)} MB > $maxFileSizeMb MB"
                log.warn("Skipping $relativePath: $message")
                return IndexResult(
                    success = false,
                    relativePath = relativePath,
                    chunkCount = 0,
                    embeddingCount = 0,
                    error = message
                )
            }

            if (fileSizeBytes > warnFileSizeBytes) {
                val sizeMb = fileSizeBytes / (1024.0 * 1024.0)
                log.warn("Indexing large file $relativePath (${String.format("%.2f", sizeMb)} MB). This may take a while...")
            }

            val content = readFile(absolutePath, extension)
            coroutineContext.ensureActive()

            val chunker = chunkerRegistry.getChunker(absolutePath)
            val languageHint = resolveLanguage(metadata, chunker, absolutePath)
            val chunkLanguage = languageHint ?: "text"
            val rawChunks = chunker.chunk(content, relativePath, chunkLanguage)
            val normalizedChunks = normalizeChunks(rawChunks, chunker)

            if (normalizedChunks.size > 50) {
                log.info("Chunked {} into {} chunks, starting embedding generation...", relativePath, normalizedChunks.size)
            }

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
                absolutePath = absolutePath.toString(),
                contentHash = metadata.contentHash,
                sizeBytes = metadata.sizeBytes,
                modifiedTimeNs = metadata.modifiedTimeNs,
                language = languageHint,
                kind = chunker.strategy.id,
                fingerprint = null,
                indexedAt = now,
                isDeleted = false
            )

            val persistedChunkCount = try {
                dataService.syncFileArtifacts(fileState, chunkArtifacts)
                chunkArtifacts.size
            } catch (sql: SQLException) {
                log.warn(
                    "Falling back to metadata-only index for {} after database error: {}",
                    relativePath,
                    sql.message
                )
                dataService.syncFileArtifacts(fileState, emptyList())
                0
            }

            IndexResult(
                success = true,
                relativePath = relativePath,
                chunkCount = persistedChunkCount,
                embeddingCount = if (persistedChunkCount == 0) 0 else embeddings.size,
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

    private fun readFile(path: Path, extension: String): String = try {
        when {
            WordDocumentExtractor.supports(extension) -> WordDocumentExtractor.extract(path, extension)
            extension == "pdf" -> PdfDocumentExtractor.extract(path)
            else -> Files.readString(path, readCharset)
        }
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

        val chunkCount = chunks.size
        if (chunkCount > 10) {
            log.debug("Generating embeddings for {} chunks", chunkCount)
        }

        val texts = chunks.map { it.content }
        val vectors = embedInBatches(texts, chunkCount)
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

    private suspend fun embedInBatches(texts: List<String>, totalChunks: Int): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val results = ArrayList<FloatArray>(texts.size)
        var index = 0
        var batchNumber = 0
        val totalBatches = (texts.size + embeddingBatchSize - 1) / embeddingBatchSize

        while (index < texts.size) {
            coroutineContext.ensureActive()
            val end = min(index + embeddingBatchSize, texts.size)
            val batch = texts.subList(index, end)
            batchNumber++

            // Log progress for large files
            if (totalBatches > 5) {
                log.debug("Processing embedding batch {}/{} ({} chunks)", batchNumber, totalBatches, batch.size)
            }

            val batchResult = embedder.embedBatch(batch)
            if (batchResult.size != batch.size) {
                throw IllegalStateException("Embedder returned ${batchResult.size} vectors for batch of size ${batch.size}")
            }
            results.addAll(batchResult)
            index = end
        }

        if (totalBatches > 5) {
            log.debug("Completed all {} batches ({} total embeddings)", totalBatches, results.size)
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

    private fun findMatchingRoot(absolutePath: Path): Path? {
        val normalized = absolutePath.toAbsolutePath().normalize()
        return allRoots.find { root ->
            normalized == root || normalized.startsWith(root)
        }
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
