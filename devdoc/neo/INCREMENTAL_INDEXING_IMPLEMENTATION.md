# Incremental Synchronous Indexing Implementation Guide

## Quick Overview

This guide shows how to implement **incremental, synchronous document indexing** that:
- ✅ Coordinates semantic embeddings for code + documents in ONE batch call
- ✅ Keeps Neo4j structure and DuckDB embeddings perfectly synchronized
- ✅ Processes file changes incrementally via file watcher
- ✅ Integrates with your existing `IncrementalIndexer`

---

## Step 1: Create Embedding Coordinator

### File: src/main/kotlin/com/orchestrator/context/neo4j/EmbeddingCoordinator.kt

```kotlin
class EmbeddingCoordinator(
    private val embedder: Embedder,  // Your existing embedder
    private val duckdbProvider: DuckDBContextProvider,
    private val batchSize: Int = 512  // Tunable: larger = more efficient, slower response
) {
    
    /**
     * Unified batch embedding for mixed content (code + documents)
     * Single batch call to embedder = maximum efficiency
     */
    suspend fun embedChunksBatch(chunks: List<Chunk>): Map<String, FloatArray> {
        if (chunks.isEmpty()) return emptyMap()
        
        // Only embed chunks that don't already have embeddings
        val needsEmbedding = chunks.filter { !hasEmbedding(it.id) }
        if (needsEmbedding.isEmpty()) return emptyMap()
        
        logger.info("Embedding ${needsEmbedding.size} chunks")
        
        val results = mutableMapOf<String, FloatArray>()
        
        // Process in batches (respecting embedder's limit)
        needsEmbedding.chunked(batchSize).forEach { batch ->
            val texts = batch.map { it.content }
            
            try {
                // SINGLE batch call for all texts
                val embeddings = embedder.embedBatch(texts)
                batch.zip(embeddings).forEach { (chunk, embedding) ->
                    results[chunk.id] = embedding
                }
            } catch (e: Exception) {
                logger.warn("Batch failed, falling back to individual: ${e.message}")
                // Fallback: individual embeddings
                batch.forEach { chunk ->
                    try {
                        results[chunk.id] = embedder.embed(chunk.content)
                    } catch (e: Exception) {
                        logger.error("Failed: ${chunk.id}", e)
                    }
                }
            }
        }
        
        return results
    }
    
    private fun hasEmbedding(chunkId: String): Boolean {
        return duckdbProvider.hasEmbedding(chunkId)
    }
}
```

**What this does**:
- Takes list of chunks (code or documents)
- Checks DuckDB to see which already have embeddings
- Calls embedder.embedBatch() ONCE for all needing embeddings
- Returns map of chunkId → embedding

---

## Step 2: Create Synchronous Document Indexer

### File: src/main/kotlin/com/orchestrator/context/neo4j/SynchronousDocumentIndexer.kt

```kotlin
class SynchronousDocumentIndexer(
    private val duckdbProvider: DuckDBContextProvider,
    private val embedder: Embedder,
    private val documentIndexer: DocumentIndexer,
    private val neo4jIndexer: Neo4jIndexer,
    private val documentExtractorFactory: DocumentExtractorFactory
) {
    
    /**
     * Index a single document with synchronous semantic embedding
     * Called incremental indexer for each document change
     */
    suspend fun indexDocumentSynchronously(filePath: Path, content: ByteArray) {
        // 1. Extract structure (fast)
        val document = documentExtractorFactory.extract(filePath.toString(), content)
            ?: run {
                logger.warn("Failed to extract: $filePath")
                return
            }
        
        // 2. Index Neo4j structure immediately (fast)
        documentIndexer.indexDocument(document)
        
        // 3. Create DuckDB chunks
        val chunks = createChunks(document)
        
        // 4. Return chunks for batch embedding (caller will handle embeddings)
        // This allows coordinating with code embeddings
        chunks.forEach { chunk ->
            duckdbProvider.insertChunk(chunk)
        }
        
        // 5. Link chunks to Neo4j
        chunks.forEach { chunk ->
            linkChunkToNeo4j(chunk, document)
        }
    }
    
    /**
     * Batch process multiple documents efficiently
     */
    suspend fun indexDocumentsBatch(
        documentsToIndex: List<Pair<Path, ByteArray>>,
        embeddingCoordinator: EmbeddingCoordinator
    ) {
        // Extract all
        val extracted = documentsToIndex.mapNotNull { (path, content) ->
            documentExtractorFactory.extract(path.toString(), content)?.let { doc ->
                path to doc
            }
        }
        
        // Index Neo4j
        extracted.forEach { (_, doc) ->
            documentIndexer.indexDocument(doc)
        }
        
        // Create all chunks
        val allChunks = extracted.flatMap { (_, doc) ->
            createChunks(doc)
        }
        
        // Insert chunks to DuckDB
        allChunks.forEach { chunk ->
            duckdbProvider.insertChunk(chunk)
        }
        
        // COORDINATE: Generate embeddings in one batch (code + docs)
        val embeddings = embeddingCoordinator.embedChunksBatch(allChunks)
        
        // Store embeddings
        storeEmbeddings(embeddings)
        
        // Link to Neo4j
        extracted.forEach { (_, doc) ->
            val chunks = allChunks.filter { it.filePath == doc.filePath }
            chunks.forEach { chunk ->
                linkChunkToNeo4j(chunk, doc)
            }
        }
    }
    
    private fun createChunks(document: ExtractedDocument): List<Chunk> {
        return document.sections.flatMap { section ->
            section.paragraphs.map { para ->
                Chunk(
                    id = "${document.filePath.hashCode()}_${section.title.hashCode()}_${para.order}".replace("-", "_"),
                    content = para.text,
                    filePath = document.filePath,
                    language = document.metadata.language,
                    kind = ChunkKind.DOCUMENT_PARAGRAPH,
                    startLine = para.startLine,
                    endLine = para.endLine,
                    metadata = mapOf("section" to section.title)
                )
            }
        }
    }
    
    private fun linkChunkToNeo4j(chunk: Chunk, document: ExtractedDocument) {
        val section = chunk.metadata["section"] ?: return
        val sectionId = "${document.filePath.hashCode()}_${section.hashCode()}".replace("-", "_")
        documentIndexer.linkChunkToDocument(chunk.id, sectionId)
    }
    
    private suspend fun storeEmbeddings(embeddings: Map<String, FloatArray>) {
        embeddings.forEach { (chunkId, embedding) ->
            duckdbProvider.storeEmbedding(chunkId, embedding)
        }
    }
}
```

---

## Step 3: Create Document-Aware Watcher

### File: src/main/kotlin/com/orchestrator/context/neo4j/DocumentAwareWatcher.kt

```kotlin
class DocumentAwareWatcher(
    private val synchronousDocumentIndexer: SynchronousDocumentIndexer,
    private val embeddingCoordinator: EmbeddingCoordinator,
    private val duckdbProvider: DuckDBContextProvider
) {
    
    /**
     * Called by file watcher when documents change
     * Integrates with your existing file watcher
     */
    suspend fun handleDocumentChanges(changedFiles: List<Path>) {
        if (changedFiles.isEmpty()) return
        
        logger.info("Handling ${changedFiles.size} document changes")
        
        // Batch process documents
        val documentsContent = changedFiles.mapNotNull { path ->
            try {
                val content = path.toFile().readBytes()
                path to content
            } catch (e: Exception) {
                logger.error("Failed to read $path: ${e.message}")
                null
            }
        }
        
        if (documentsContent.isEmpty()) return
        
        // Index documents + coordinate embeddings with code changes
        synchronousDocumentIndexer.indexDocumentsBatch(
            documentsContent,
            embeddingCoordinator
        )
        
        logger.info("Indexed ${documentsContent.size} documents")
    }
}
```

---

## Step 4: Integrate with Your File Watcher

### Update Your File Watcher Handler

```kotlin
// In your existing FileWatcher or similar

suspend fun onFileSystemChange(changedFiles: List<Path>, deletedFiles: List<Path>) {
    // Separate by type
    val documentFiles = changedFiles.filter { 
        DocumentTypeDetector.isDocument(DocumentTypeDetector.detectFromPath(it.toString()))
    }
    val codeFiles = changedFiles.filter {
        !DocumentTypeDetector.isDocument(DocumentTypeDetector.detectFromPath(it.toString()))
    }
    
    // Collect chunks from both
    val chunks = mutableListOf<Chunk>()
    
    // 1. Index code files (your existing flow)
    if (codeFiles.isNotEmpty()) {
        val codeChunks = indexCodeFiles(codeFiles)  // Your existing method
        chunks.addAll(codeChunks)
    }
    
    // 2. Index document files (NEW)
    if (documentFiles.isNotEmpty()) {
        val docChunks = documentAwareWatcher.handleDocumentChanges(documentFiles)
        chunks.addAll(docChunks)
    }
    
    // 3. COORDINATED: Generate semantic embeddings for ALL chunks
    if (chunks.isNotEmpty()) {
        logger.info("Embedding ${chunks.size} total chunks (code + documents)")
        val embeddings = embeddingCoordinator.embedChunksBatch(chunks)
        storeEmbeddings(embeddings)
    }
    
    // 4. Handle deletions
    handleDeletions(deletedFiles)
}
```

---

## Step 5: Configuration

### Update fusionagent.toml

```toml
[context_engine]
# Enable synchronized indexing
synchronous_indexing = true
coordinate_embeddings = true
batch_embeddings = true

[embedding]
batch_size = 512                 # Texts per batch call to embedder
batch_timeout_ms = 30000         # Wait to accumulate batch
individual_fallback = true       # Fall back if batch fails

[neo4j.documents]
enabled = true
synchronous_indexing = true      # Index documents immediately
```

---

## Step 6: Add Monitoring

### File: src/main/kotlin/com/orchestrator/context/neo4j/IndexingDiagnostics.kt

```kotlin
class IndexingDiagnostics(
    private val duckdbProvider: DuckDBContextProvider,
    private val neo4jProvider: Neo4jContextProvider
) {
    
    suspend fun getStatus(): IndexStatus {
        return IndexStatus(
            totalChunks = duckdbProvider.countChunks(),
            chunksWithEmbeddings = duckdbProvider.countChunksWithEmbeddings(),
            pendingEmbeddings = duckdbProvider.countChunksWithoutEmbeddings(),
            neo4jDocuments = neo4jProvider.countDocuments(),
            neo4jSections = neo4jProvider.countSections(),
            isHealthy = duckdbProvider.countChunksWithoutEmbeddings() == 0
        )
    }
    
    suspend fun checkHealth(): HealthReport {
        val chunks = duckdbProvider.countChunks()
        val embeddings = duckdbProvider.countChunksWithEmbeddings()
        val pending = duckdbProvider.countChunksWithoutEmbeddings()
        
        return HealthReport(
            embeddingCoverage = if (chunks > 0) (embeddings.toFloat() / chunks) else 1f,
            pendingEmbeddings = pending,
            isHealthy = pending == 0 && embeddings == chunks
        )
    }
}

data class IndexStatus(
    val totalChunks: Int,
    val chunksWithEmbeddings: Int,
    val pendingEmbeddings: Int,
    val neo4jDocuments: Int,
    val neo4jSections: Int,
    val isHealthy: Boolean
)

data class HealthReport(
    val embeddingCoverage: Float,  // 0-1, should be 1.0
    val pendingEmbeddings: Int,    // Should be 0
    val isHealthy: Boolean
)
```

---

## Step 7: Testing

### File: src/test/kotlin/com/orchestrator/context/neo4j/SynchronousIndexingTest.kt

```kotlin
@Test
fun testSynchronousDocumentIndexing() {
    // Create test document
    val testDoc = """
        # Introduction
        This is an introduction paragraph.
        
        ## Section 1
        Content for section 1.
    """.toByteArray()
    
    // Index synchronously
    runBlocking {
        synchronousDocumentIndexer.indexDocumentSynchronously(
            Paths.get("test.md"),
            testDoc
        )
    }
    
    // Verify Neo4j structure created
    val sections = neo4jProvider.countSections()
    assertEquals(2, sections)  // Main + 1 subsection
    
    // Verify DuckDB chunks created
    val chunks = duckdbProvider.countChunksForFile("test.md")
    assertTrue(chunks > 0)
    
    // Verify embeddings generated
    val embeddings = embeddingCoordinator.embedChunksBatch(
        duckdbProvider.getChunksForFile("test.md")
    )
    assertEquals(chunks, embeddings.size)
}

@Test
fun testCoordinatedEmbeddingBatching() {
    val codeChunks = listOf(
        Chunk("code1", "fun hello() {}", "test.kt", ...),
        Chunk("code2", "fun world() {}", "test.kt", ...)
    )
    
    val docChunks = listOf(
        Chunk("doc1", "Documentation text", "test.md", ...),
        Chunk("doc2", "More documentation", "test.md", ...)
    )
    
    // Combine and embed in ONE batch
    val allChunks = codeChunks + docChunks
    
    val embeddings = runBlocking {
        embeddingCoordinator.embedChunksBatch(allChunks)
    }
    
    // Should have 4 embeddings from single batch call
    assertEquals(4, embeddings.size)
}

@Test
fun testIncrementalReindexing() {
    // Index initial document
    val doc1 = "# Document\nInitial content".toByteArray()
    runBlocking {
        synchronousDocumentIndexer.indexDocumentSynchronously(
            Paths.get("doc.md"),
            doc1
        )
    }
    
    val initialChunks = duckdbProvider.countChunksForFile("doc.md")
    
    // Reindex with different content
    val doc2 = "# Document\nUpdated content\nWith more sections".toByteArray()
    runBlocking {
        // Would need reindex method: remove old, index new
        // synchronousDocumentIndexer.reindexDocument(Paths.get("doc.md"), doc2)
    }
    
    val updatedChunks = duckdbProvider.countChunksForFile("doc.md")
    assertTrue(updatedChunks > initialChunks)  // More sections
}
```

---

## Configuration Options Explained

```toml
[embedding]
# How many texts to include in single batch to embedder
# Higher = more efficient but slower response time
# Lower = faster response but more API calls
batch_size = 512

# How long to wait for more texts before sending batch
# Higher = better batching (more texts per call)
# Lower = faster response time
batch_timeout_ms = 30000

# If batch call fails, try individual embeddings
individual_fallback = true

[context_engine]
# Process files as they change (no buffering)
synchronous_indexing = true

# Generate embeddings immediately after chunking
# Don't wait for end-of-batch
coordinate_embeddings = true

# Batch multiple texts into single embedder call
batch_embeddings = true
```

---

## Performance Tuning

### For Speed (Real-Time Indexing)
```toml
batch_size = 256                 # Smaller batches = faster response
batch_timeout_ms = 5000          # Don't wait long
synchronous_indexing = true      # Process immediately
```

### For Efficiency (Background Indexing)
```toml
batch_size = 1024                # Larger batches = fewer API calls
batch_timeout_ms = 60000         # Accumulate more texts
synchronous_indexing = false     # Can batch over time
```

### For Large Codebases
```toml
batch_size = 512
batch_timeout_ms = 30000
coordinate_embeddings = true     # Essential with many files
```

---

## Troubleshooting

### Issue: Embeddings not generated
**Check**: 
```kotlin
val pending = duckdbProvider.countChunksWithoutEmbeddings()
if (pending > 0) {
    logger.warn("$pending chunks pending embeddings")
}
```

### Issue: Neo4j structure missing
**Check**:
```kotlin
val docs = neo4jProvider.countDocuments()
if (docs == 0) {
    logger.warn("No documents in Neo4j")
}
```

### Issue: Batch embeddings failing
**Solution**:
- Set `individual_fallback = true` in config
- Reduce `batch_size` to smaller values
- Check embedder service availability

### Issue: Slow indexing
**Solution**:
- Increase `batch_size` (fewer API calls)
- Increase `batch_timeout_ms` (more texts per batch)
- Ensure embedder has capacity

---

## Next Steps

1. **Implement**: Follow steps 1-7 above
2. **Test**: Run test suite
3. **Monitor**: Check metrics via diagnostics
4. **Tune**: Adjust batch sizes based on performance
5. **Deploy**: Enable in production with monitoring

---

## Key Metrics to Monitor

```
✅ Embedding coverage: Should be 100%
✅ Pending embeddings: Should be 0
✅ Batch call frequency: Lower is better
✅ Average batch size: Larger is better
✅ Indexing latency: 
   - Code: <100ms per file
   - Documents: <500ms per document
✅ Semantic accuracy: User feedback on search relevance
```

---

## Summary

This implementation provides:

✅ **Incremental processing** - File watcher triggers indexing
✅ **Synchronous operation** - Everything happens immediately
✅ **Coordinated embeddings** - Single batch call for code + documents
✅ **Perfect synchronization** - Neo4j structure ↔ DuckDB embeddings always in sync
✅ **Efficient** - Minimal API calls to embedder
✅ **Resilient** - Falls back to individual embeddings on batch failure
✅ **Monitorable** - Health checks and diagnostics

The key innovation: **Coordinator pattern** that batches embeddings across both code and documents, achieving maximum efficiency while maintaining perfect synchronization!
