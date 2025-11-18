# Neo4j Document Indexing - Incremental & Synchronous Strategy

## Overview

This document details how to implement **incremental, synchronous document indexing** that works seamlessly with your existing semantic embedding pipeline and Neo4j graph structure.

Your system already has:
- ✅ **IncrementalIndexer** for file watching and incremental updates
- ✅ **Batch embeddings** (embedBatch) for semantic indexing
- ✅ **Synchronous pipeline** that processes changes immediately
- ✅ **DuckDB + Neo4j** dual storage

Now we extend this to handle documents with **proper synchronization** between:
1. **File changes** (watcher detects new/modified/deleted files)
2. **Document extraction** (extract structure from documents)
3. **Semantic embedding** (generate embeddings for content)
4. **Neo4j indexing** (store structure in graph)
5. **DuckDB indexing** (store chunks + embeddings)

---

## Part 1: Unified Indexing Pipeline Architecture

### Current Flow (Code-Only)
```
File Watcher
    ↓ (detects change)
IncrementalIndexer.update(paths)
    ↓
Process each file:
    ├─ Chunking (by language)
    ├─ AST extraction (for Neo4j structure)
    ├─ DuckDB indexing (chunks + metadata)
    └─ Semantic embedding (batch to embedder)
    ↓
Update semantic embeddings in DuckDB
    ↓
Neo4j structure indexed separately
    ↓
Search ready
```

### Extended Flow (Code + Documents)
```
File Watcher
    ↓ (detects code + document changes)
IncrementalIndexer.update(paths)
    ↓
Dispatch by file type:
    ├─ CODE FILES:
    │  ├─ Chunk by language
    │  ├─ AST extract → Neo4j
    │  ├─ DuckDB chunks
    │  └─ Batch embeddings
    │
    └─ DOCUMENT FILES:
       ├─ Detect type (PDF/Word/MD/TXT)
       ├─ Extract structure → Neo4j
       ├─ Chunk by section → DuckDB
       ├─ Batch embeddings
       └─ Link chunks to sections (Neo4j)
    ↓
All semantic embeddings in one batch operation
    ↓
Both Neo4j + DuckDB updated together
    ↓
Search ready (code + documents)
```

---

## Part 2: Synchronous Indexing Pipeline

### 2.1 Unified File Processing

```kotlin
// File: src/main/kotlin/com/orchestrator/context/neo4j/UnifiedIncrementalIndexer.kt

class UnifiedIncrementalIndexer(
    private val incrementalIndexer: IncrementalIndexer,  // Your existing indexer
    private val duckdbProvider: DuckDBContextProvider,
    private val embedder: Embedder,                       // Your semantic embedder
    private val documentExtractorFactory: DocumentExtractorFactory,
    private val neo4jIndexer: Neo4jIndexer,
    private val documentIndexer: DocumentIndexer,
    private val astExtractor: KotlinAstExtractor
) {
    
    /**
     * Unified entry point for incremental indexing (code + documents)
     * Processes both file types with synchronous semantic embedding
     */
    suspend fun updateIncremental(
        paths: List<Path>,
        parallelism: Int? = null,
        onProgress: ((BatchProgress) -> Unit)? = null,
        detectImplicitDeletions: Boolean = false
    ) {
        // Delegate to existing IncrementalIndexer for file discovery/watcher logic
        incrementalIndexer.update(
            paths = paths,
            parallelism = parallelism,
            onProgress = onProgress,
            detectImplicitDeletions = detectImplicitDeletions
        )
    }
}
```

### 2.2 Batch Processing with Synchronized Embedding

```kotlin
// File: src/main/kotlin/com/orchestrator/context/neo4j/SynchronousDocumentIndexer.kt

class SynchronousDocumentIndexer(
    private val duckdbProvider: DuckDBContextProvider,
    private val embedder: Embedder,
    private val documentIndexer: DocumentIndexer,
    private val neo4jIndexer: Neo4jIndexer,
    private val documentExtractorFactory: DocumentExtractorFactory
) {
    
    /**
     * Process and index a single document with synchronous semantic embedding
     * Called by incremental indexer for each document file
     */
    suspend fun indexDocumentSynchronously(
        filePath: Path,
        content: ByteArray
    ) {
        // 1. Extract document structure (fast)
        val document = documentExtractorFactory.extract(filePath.toString(), content)
            ?: run {
                logger.warn("Failed to extract document: $filePath")
                return
            }
        
        // 2. Store document metadata to Neo4j (fast, no I/O)
        documentIndexer.indexDocument(document)
        
        // 3. Chunk document for DuckDB + embeddings
        val chunks = chunkDocumentForIndexing(document)
        
        // 4. Generate semantic embeddings for ALL chunks in one batch
        val embeddingsNeeded = chunks.filter { !isAlreadyEmbedded(it) }
        if (embeddingsNeeded.isNotEmpty()) {
            val embeddings = embedBatchWithFallback(embeddingsNeeded)
            
            // 5. Store chunks + embeddings to DuckDB atomically
            duckdbProvider.indexChunksBatch(chunks, embeddings)
        }
        
        // 6. Link chunks to Neo4j structure (link to sections)
        chunks.forEach { chunk ->
            linkChunkToDocumentStructure(chunk, document)
        }
        
        logger.info("Indexed document: ${document.title} (${chunks.size} chunks, ${chunks.count { isAlreadyEmbedded(it) }} embedded)")
    }
    
    /**
     * Batch process multiple documents with synchronized embedding
     * More efficient than one-at-a-time for incremental updates
     */
    suspend fun indexDocumentsBatch(
        documentsToIndex: List<Pair<Path, ByteArray>>,
        batchSize: Int = 10
    ) {
        // Process documents in groups
        documentsToIndex.chunked(batchSize).forEach { batch ->
            // Extract all documents
            val extracted = batch.mapNotNull { (path, content) ->
                documentExtractorFactory.extract(path.toString(), content)?.let { doc ->
                    Triple(path, content, doc)
                }
            }
            
            // Index Neo4j structures (fast, parallel)
            extracted.forEach { (_, _, doc) ->
                documentIndexer.indexDocument(doc)
            }
            
            // Collect ALL chunks from batch
            val allChunks = extracted.flatMap { (_, _, doc) ->
                chunkDocumentForIndexing(doc)
            }
            
            // Generate embeddings for ALL chunks at once (single batch call)
            val embeddingsNeeded = allChunks.filter { !isAlreadyEmbedded(it) }
            if (embeddingsNeeded.isNotEmpty()) {
                val embeddings = embedBatchWithFallback(embeddingsNeeded)
                
                // Store all chunks + embeddings atomically to DuckDB
                duckdbProvider.indexChunksBatch(allChunks, embeddings)
            }
            
            // Link chunks to structure (fast, parallel)
            extracted.forEach { (_, _, doc) ->
                val chunks = allChunks.filter { it.filePath == doc.filePath }
                chunks.forEach { chunk ->
                    linkChunkToDocumentStructure(chunk, doc)
                }
            }
        }
    }
    
    /**
     * Generate semantic embeddings with fallback on failure
     * Retries with partial batches if full batch fails
     */
    private suspend fun embedBatchWithFallback(
        chunks: List<Chunk>,
        maxRetries: Int = 2
    ): Map<String, FloatArray> {
        val textToEmbed = chunks.map { it.content }
        
        return try {
            // Try batch embedding
            val embeddings = embedder.embedBatch(textToEmbed)
            chunks.zip(embeddings).associate { (chunk, embedding) ->
                chunk.id to embedding
            }
        } catch (e: Exception) {
            logger.warn("Batch embedding failed, trying smaller batches: ${e.message}")
            
            // Fallback: embed in smaller batches
            val results = mutableMapOf<String, FloatArray>()
            val smallerBatchSize = maxOf(1, chunks.size / 2)
            
            chunks.chunked(smallerBatchSize).forEach { batch ->
                try {
                    val embeddings = embedder.embedBatch(batch.map { it.content })
                    batch.zip(embeddings).forEach { (chunk, embedding) ->
                        results[chunk.id] = embedding
                    }
                } catch (e: Exception) {
                    logger.error("Failed to embed batch: ${e.message}")
                    // Skip these chunks - continue with others
                }
            }
            
            results
        }
    }
    
    /**
     * Check if chunk already has embeddings (avoid re-embedding)
     */
    private fun isAlreadyEmbedded(chunk: Chunk): Boolean {
        // Check DuckDB to see if embedding exists
        return duckdbProvider.hasEmbedding(chunk.id)
    }
    
    private fun chunkDocumentForIndexing(document: ExtractedDocument): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        
        document.sections.forEach { section ->
            // Chunk each section
            chunks.addAll(chunkSection(section, document))
        }
        
        return chunks
    }
    
    private fun chunkSection(section: ExtractedSection, document: ExtractedDocument): List<Chunk> {
        return section.paragraphs.map { para ->
            Chunk(
                id = generateChunkId(document.filePath, section.title, para.order),
                content = para.text,
                filePath = document.filePath,
                language = document.metadata.language,
                kind = ChunkKind.DOCUMENT_PARAGRAPH,
                startLine = para.startLine,
                endLine = para.endLine,
                metadata = mapOf(
                    "documentType" to document.documentType.name,
                    "section" to section.title,
                    "sectionLevel" to section.level.toString()
                )
            )
        }
    }
    
    private fun linkChunkToDocumentStructure(chunk: Chunk, document: ExtractedDocument) {
        // Link chunk to Neo4j section
        val sectionId = chunk.metadata["section"]?.let { section ->
            generateSectionId(document.filePath, section)
        } ?: return
        
        documentIndexer.linkChunkToDocument(chunk.id, sectionId)
    }
    
    private fun generateChunkId(docPath: String, section: String, order: Int): String {
        return "${docPath.hashCode()}_${section.hashCode()}_${order}".replace("-", "_")
    }
    
    private fun generateSectionId(docPath: String, title: String): String {
        return "${docPath.hashCode()}_${title.hashCode()}".replace("-", "_")
    }
}
```

---

## Part 3: Integration with Existing Semantic Pipeline

### 3.1 Embedding Coordination

```kotlin
// File: src/main/kotlin/com/orchestrator/context/neo4j/EmbeddingCoordinator.kt

/**
 * Coordinates semantic embeddings between code and documents
 * Ensures single batch call to embedder regardless of mix of content types
 */
class EmbeddingCoordinator(
    private val embedder: Embedder,
    private val duckdbProvider: DuckDBContextProvider,
    private val batchSize: Int = 512  // Max texts per batch call
) {
    
    /**
     * Unified batch embedding for mixed content (code + documents)
     * Returns map of: chunkId → FloatArray (embedding)
     */
    suspend fun embedChunksBatch(
        chunks: List<Chunk>
    ): Map<String, FloatArray> {
        if (chunks.isEmpty()) return emptyMap()
        
        // Filter out already-embedded chunks
        val needsEmbedding = chunks.filter { !duckdbProvider.hasEmbedding(it.id) }
        if (needsEmbedding.isEmpty()) {
            logger.debug("All chunks already embedded")
            return emptyMap()
        }
        
        logger.info("Embedding ${needsEmbedding.size} chunks (batch size: $batchSize)")
        
        val results = mutableMapOf<String, FloatArray>()
        
        // Process in batches respecting embedder's batch size limit
        needsEmbedding.chunked(batchSize).forEach { batch ->
            try {
                val texts = batch.map { it.content }
                val embeddings = embedder.embedBatch(texts)
                
                batch.zip(embeddings).forEach { (chunk, embedding) ->
                    results[chunk.id] = embedding
                    
                    // Store embedding immediately (don't wait for end)
                    storeEmbedding(chunk, embedding)
                }
                
                logger.info("Embedded batch of ${batch.size} chunks")
            } catch (e: Exception) {
                logger.error("Failed to embed batch: ${e.message}, will retry individually")
                
                // Fallback: try individual embeddings
                batch.forEach { chunk ->
                    try {
                        val embedding = embedder.embed(chunk.content)
                        results[chunk.id] = embedding
                        storeEmbedding(chunk, embedding)
                    } catch (e: Exception) {
                        logger.warn("Failed to embed chunk ${chunk.id}: ${e.message}")
                    }
                }
            }
        }
        
        return results
    }
    
    private suspend fun storeEmbedding(chunk: Chunk, embedding: FloatArray) {
        try {
            duckdbProvider.storeEmbedding(
                chunkId = chunk.id,
                embedding = embedding,
                model = embedder.getModel(),
                dimension = embedder.getDimension()
            )
        } catch (e: Exception) {
            logger.error("Failed to store embedding for ${chunk.id}: ${e.message}")
        }
    }
}
```

### 3.2 Synchronized DuckDB Storage

```kotlin
// Extension to your existing DuckDBContextProvider

class DuckDBContextProvider {
    // ... existing methods ...
    
    /**
     * Store multiple chunks + embeddings atomically
     * Ensures consistency if one fails
     */
    suspend fun indexChunksBatch(
        chunks: List<Chunk>,
        embeddings: Map<String, FloatArray>
    ) {
        ContextDatabase.transaction { conn ->
            // Insert all chunks
            chunks.forEach { chunk ->
                insertChunk(conn, chunk)
            }
            
            // Insert all embeddings
            embeddings.forEach { (chunkId, embedding) ->
                insertEmbedding(conn, chunkId, embedding)
            }
            
            // Update full-text index
            rebuildFullTextIndex(conn, chunks)
        }
    }
    
    suspend fun hasEmbedding(chunkId: String): Boolean {
        return ContextDatabase.query { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM embeddings WHERE chunk_id = ?"
            ).use { ps ->
                ps.setString(1, chunkId)
                val rs = ps.executeQuery()
                rs.next()
                rs.getInt(1) > 0
            }
        }
    }
    
    private fun insertChunk(conn: Connection, chunk: Chunk) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO chunks (
                chunk_id, file_path, language, kind, content, 
                start_line, end_line, metadata, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """
        ).use { ps ->
            ps.setString(1, chunk.id)
            ps.setString(2, chunk.filePath)
            ps.setString(3, chunk.language)
            ps.setString(4, chunk.kind.name)
            ps.setString(5, chunk.content)
            ps.setInt(6, chunk.startLine)
            ps.setInt(7, chunk.endLine)
            ps.setString(8, chunk.metadata.toJsonString())
            ps.setString(9, LocalDateTime.now().toString())
            ps.executeUpdate()
        }
    }
    
    private fun insertEmbedding(
        conn: Connection,
        chunkId: String,
        embedding: FloatArray
    ) {
        conn.prepareStatement(
            """
            INSERT OR REPLACE INTO embeddings (
                chunk_id, model, dimensions, vector, created_at
            ) VALUES (?, ?, ?, ?, ?)
            """
        ).use { ps ->
            ps.setString(1, chunkId)
            ps.setString(2, embedder.getModel())
            ps.setInt(3, embedding.size)
            ps.setBytes(4, embeddingToBytes(embedding))
            ps.setString(5, LocalDateTime.now().toString())
            ps.executeUpdate()
        }
    }
    
    private fun rebuildFullTextIndex(conn: Connection, chunks: List<Chunk>) {
        // Update FTS index for new chunks
        val ftsStatement = """
            INSERT INTO chunks_fts(chunk_id, content, kind)
            SELECT chunk_id, content, kind FROM chunks
            WHERE chunk_id IN (${chunks.map { "?" }.joinToString(",")})
        """
        
        conn.prepareStatement(ftsStatement).use { ps ->
            chunks.forEachIndexed { idx, chunk ->
                ps.setString(idx + 1, chunk.id)
            }
            ps.executeUpdate()
        }
    }
}
```

---

## Part 4: File Watcher Integration

### 4.1 Document Type Aware Watcher

```kotlin
// File: src/main/kotlin/com/orchestrator/context/neo4j/DocumentAwareWatcher.kt

class DocumentAwareWatcher(
    private val incrementalIndexer: IncrementalIndexer,
    private val synchronousDocumentIndexer: SynchronousDocumentIndexer,
    private val embeddingCoordinator: EmbeddingCoordinator,
    private val documentTypeDetector: DocumentTypeDetector
) {
    
    /**
     * Watch for both code and document file changes
     * Called by file watcher when files change
     */
    suspend fun handleFileChanges(
        changedFiles: List<Path>,
        deletedFiles: List<Path>,
        parallelism: Int = 4
    ) {
        // Separate by type
        val codeFiles = changedFiles.filter { DocumentTypeDetector.isCode(DocumentTypeDetector.detectFromPath(it.toString())) }
        val documentFiles = changedFiles.filter { DocumentTypeDetector.isDocument(DocumentTypeDetector.detectFromPath(it.toString())) }
        
        logger.info("Handling ${codeFiles.size} code changes + ${documentFiles.size} document changes")
        
        // Process in parallel but coordinate embeddings
        val chunks = mutableListOf<Chunk>()
        
        // 1. Index code files (use existing incremental indexer)
        if (codeFiles.isNotEmpty()) {
            incrementalIndexer.update(codeFiles, parallelism = parallelism)
            
            // Collect chunks from code indexing
            // (Your existing system returns these)
            chunks.addAll(getChunksFromCodeIndexing(codeFiles))
        }
        
        // 2. Index document files (new path)
        if (documentFiles.isNotEmpty()) {
            documentFiles.forEachIndexed { idx, file ->
                try {
                    val content = file.toFile().readBytes()
                    synchronousDocumentIndexer.indexDocumentSynchronously(file, content)
                    
                    // Get chunks from document indexing
                    chunks.addAll(getChunksFromDocumentIndexing(file))
                    
                    logger.info("Indexed document file ${idx + 1}/${documentFiles.size}: ${file.fileName}")
                } catch (e: Exception) {
                    logger.error("Failed to index document ${file}: ${e.message}", e)
                }
            }
        }
        
        // 3. Generate semantic embeddings for ALL chunks (code + documents) in one batch
        if (chunks.isNotEmpty()) {
            logger.info("Embedding ${chunks.size} total chunks (code + documents) in unified batch")
            val embeddings = embeddingCoordinator.embedChunksBatch(chunks)
            
            // Store embeddings to DuckDB
            if (embeddings.isNotEmpty()) {
                storeEmbeddings(embeddings)
            }
        }
        
        // 4. Handle deletions
        if (deletedFiles.isNotEmpty()) {
            handleDeletions(deletedFiles)
        }
        
        logger.info("File watcher update complete: ${codeFiles.size} code + ${documentFiles.size} documents + ${deletedFiles.size} deletions")
    }
    
    private suspend fun storeEmbeddings(embeddings: Map<String, FloatArray>) {
        // Store to DuckDB in one transaction
        ContextDatabase.transaction { conn ->
            embeddings.forEach { (chunkId, embedding) ->
                conn.prepareStatement(
                    """
                    INSERT OR REPLACE INTO embeddings (
                        chunk_id, model, dimensions, vector, created_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """
                ).use { ps ->
                    ps.setString(1, chunkId)
                    ps.setString(2, embeddingCoordinator.getModel())
                    ps.setInt(3, embedding.size)
                    ps.setBytes(4, embeddingToBytes(embedding))
                    ps.setString(5, LocalDateTime.now().toString())
                    ps.executeUpdate()
                }
            }
        }
    }
    
    private suspend fun handleDeletions(deletedFiles: List<Path>) {
        deletedFiles.forEach { file ->
            // Remove chunks from DuckDB
            duckdbProvider.deleteChunksForFile(file.toString())
            
            // Remove document from Neo4j
            neo4jProvider.deleteDocumentAndStructure(file.toString())
            
            logger.info("Deleted index for: $file")
        }
    }
}
```

---

## Part 5: Configuration for Synchronized Indexing

```toml
# fusionagent.toml

[context_engine]
# Synchronous indexing settings
synchronous_indexing = true      # Process files as they change
batch_embeddings = true          # Batch semantic embeddings
coordinate_embeddings = true     # Single batch call for code + docs

# Incremental indexing
incremental_enabled = true
incremental_batch_size = 10
incremental_parallelism = 4

[embedding]
batch_size = 512                 # Max texts per batch call to embedder
batch_timeout_ms = 30000         # Wait this long to accumulate batch
individual_fallback = true       # Fall back to individual embeds on batch failure

[neo4j]
synchronous_storage = true       # Store structure immediately
batch_neo4j_writes = true        # Group Neo4j writes
neo4j_transaction_timeout_ms = 60000

[neo4j.documents]
enabled = true
synchronous_indexing = true      # Index docs as they arrive
batch_document_extraction = true
max_documents_per_batch = 10

[duckdb]
synchronous_writes = true        # Write to DuckDB immediately
transaction_mode = "WRITE_AHEAD"  # Ensure atomicity
batch_chunks = true              # Group chunk writes
```

---

## Part 6: Incremental Reindexing Strategy

### 6.1 Reindex Specific Files

```kotlin
// File: src/main/kotlin/com/orchestrator/context/neo4j/ReindexingStrategy.kt

class ReindexingStrategy(
    private val documentIndexer: DocumentIndexer,
    private val neo4jIndexer: Neo4jIndexer,
    private val duckdbProvider: DuckDBContextProvider,
    private val embeddingCoordinator: EmbeddingCoordinator
) {
    
    /**
     * Reindex specific documents (e.g., after extraction format changes)
     * Preserves DuckDB embeddings if content unchanged
     */
    suspend fun reindexDocuments(
        filePaths: List<Path>,
        forceReembedding: Boolean = false
    ) {
        logger.info("Reindexing ${filePaths.size} documents (forceReembedding=$forceReembedding)")
        
        filePaths.forEach { filePath ->
            try {
                // 1. Remove old Neo4j structure
                removeOldNeo4jStructure(filePath.toString())
                
                // 2. Extract fresh structure
                val content = filePath.toFile().readBytes()
                val document = documentExtractorFactory.extract(filePath.toString(), content)
                    ?: return@forEach
                
                // 3. Reindex Neo4j structure
                documentIndexer.indexDocument(document)
                
                // 4. If content changed, regenerate chunks and embeddings
                if (forceReembedding) {
                    // Remove old chunks
                    duckdbProvider.deleteChunksForFile(filePath.toString())
                    
                    // Generate new chunks
                    val chunks = chunkDocumentForIndexing(document)
                    
                    // Generate new embeddings
                    val embeddings = embeddingCoordinator.embedChunksBatch(chunks)
                    
                    // Store chunks + embeddings atomically
                    duckdbProvider.indexChunksBatch(chunks, embeddings)
                    
                    logger.info("Reindexed with new embeddings: $filePath")
                } else {
                    logger.info("Reindexed structure only: $filePath")
                }
            } catch (e: Exception) {
                logger.error("Failed to reindex $filePath: ${e.message}", e)
            }
        }
    }
    
    /**
     * Reindex all documents (handles migration, format changes, etc.)
     */
    suspend fun reindexAllDocuments(
        documentDir: Path,
        forceReembedding: Boolean = false,
        parallelism: Int = 4
    ) {
        logger.info("Full reindex of documents in $documentDir")
        
        val documentFiles = documentDir.toFile().walkTopDown()
            .filter { DocumentTypeDetector.isDocument(DocumentTypeDetector.detectFromPath(it.absolutePath)) }
            .map { it.toPath() }
            .toList()
        
        logger.info("Found ${documentFiles.size} documents to reindex")
        
        // Process in parallel
        documentFiles.chunked(parallelism).forEach { batch ->
            coroutineScope {
                batch.forEach { file ->
                    launch {
                        reindexDocuments(listOf(file), forceReembedding)
                    }
                }
            }
        }
    }
    
    /**
     * Incremental reindex: only reindex changed files since last run
     */
    suspend fun reindexIncremental(
        lastIndexTime: LocalDateTime,
        documentDirs: List<Path>,
        forceReembedding: Boolean = false
    ) {
        logger.info("Incremental reindex since $lastIndexTime")
        
        val changedFiles = documentDirs.flatMap { dir ->
            dir.toFile().walkTopDown()
                .filter { DocumentTypeDetector.isDocument(DocumentTypeDetector.detectFromPath(it.absolutePath)) }
                .filter { it.lastModified() > lastIndexTime.toInstant().toEpochMilli() }
                .map { it.toPath() }
        }
        
        logger.info("Found ${changedFiles.size} changed documents")
        
        if (changedFiles.isNotEmpty()) {
            reindexDocuments(changedFiles, forceReembedding)
        }
    }
}
```

### 6.2 Partial Reindexing (On Demand)

```kotlin
/**
 * Rebuild Neo4j structure without regenerating embeddings
 * Useful for: Neo4j schema changes, relationship updates
 */
suspend fun rebuildNeo4jStructureOnly(
    documentFiles: List<Path>
) {
    logger.info("Rebuilding Neo4j structure for ${documentFiles.size} documents")
    
    documentFiles.forEach { filePath ->
        try {
            // Remove old Neo4j structure
            removeOldNeo4jStructure(filePath.toString())
            
            // Extract and reindex
            val content = filePath.toFile().readBytes()
            val document = documentExtractorFactory.extract(filePath.toString(), content)
                ?: return@forEach
            
            documentIndexer.indexDocument(document)
            
            // Link existing DuckDB chunks to new Neo4j structure
            val chunks = duckdbProvider.getChunksForFile(filePath.toString())
            chunks.forEach { chunk ->
                linkChunkToDocumentStructure(chunk, document)
            }
        } catch (e: Exception) {
            logger.error("Failed to rebuild structure for $filePath: ${e.message}")
        }
    }
}

/**
 * Regenerate embeddings only (keep existing structure)
 * Useful for: Embedder model updates, embedding dimension changes
 */
suspend fun regenerateEmbeddingsOnly(
    documentFiles: List<Path>
) {
    logger.info("Regenerating embeddings for ${documentFiles.size} documents")
    
    // Get all chunks for these files
    val allChunks = documentFiles.flatMap { file ->
        duckdbProvider.getChunksForFile(file.toString())
    }
    
    logger.info("Regenerating embeddings for ${allChunks.size} chunks")
    
    // Generate fresh embeddings for all chunks
    val embeddings = embeddingCoordinator.embedChunksBatch(allChunks)
    
    // Store new embeddings
    duckdbProvider.updateEmbeddings(embeddings)
    
    logger.info("Regenerated ${embeddings.size} embeddings")
}
```

---

## Part 7: Monitoring & Diagnostics

```kotlin
// File: src/main/kotlin/com/orchestrator/context/neo4j/IndexingMetrics.kt

class IndexingMetrics {
    
    suspend fun getDocumentIndexingStatus(): DocumentIndexStatus {
        return DocumentIndexStatus(
            totalDocumentsIndexed = duckdbProvider.countDocuments(),
            totalDocumentChunks = duckdbProvider.countChunks(ChunkKind.DOCUMENT_PARAGRAPH),
            totalDocumentEmbeddings = duckdbProvider.countEmbeddings(ChunkKind.DOCUMENT_PARAGRAPH),
            totalNeo4jDocuments = neo4jProvider.countDocuments(),
            totalNeo4jSections = neo4jProvider.countSections(),
            pendingEmbeddings = duckdbProvider.countChunksWithoutEmbeddings(),
            lastDocumentIndexedAt = duckdbProvider.getLastIndexTime(ChunkKind.DOCUMENT_PARAGRAPH),
            averageEmbeddingBatchSize = embeddingCoordinator.getAverageBatchSize()
        )
    }
    
    suspend fun getIndexingHealth(): IndexHealth {
        val duckdbChunks = duckdbProvider.countChunks()
        val duckdbEmbeddings = duckdbProvider.countEmbeddings()
        val neo4jChunks = neo4jProvider.countLinkedChunks()
        
        return IndexHealth(
            // Check 1: DuckDB-Neo4j consistency
            embeddingCoverage = if (duckdbChunks > 0) duckdbEmbeddings.toFloat() / duckdbChunks else 1f,
            neo4jLinkage = if (duckdbChunks > 0) neo4jChunks.toFloat() / duckdbChunks else 1f,
            
            // Check 2: Embedding status
            pendingEmbeddings = duckdbProvider.countChunksWithoutEmbeddings(),
            
            // Check 3: Recent activity
            lastIndexedBefore = Duration.between(
                duckdbProvider.getLastIndexTime(),
                LocalDateTime.now()
            ),
            
            // Check 4: Neo4j structure integrity
            orphanedChunks = neo4jProvider.countOrphanedChunks(),
            orphanedSections = neo4jProvider.countOrphanedSections(),
            
            isHealthy = embeddingCoverage > 0.95f &&
                       neo4jLinkage > 0.95f &&
                       duckdbProvider.countChunksWithoutEmbeddings() == 0 &&
                       neo4jProvider.countOrphanedChunks() == 0
        )
    }
}

data class DocumentIndexStatus(
    val totalDocumentsIndexed: Int,
    val totalDocumentChunks: Int,
    val totalDocumentEmbeddings: Int,
    val totalNeo4jDocuments: Int,
    val totalNeo4jSections: Int,
    val pendingEmbeddings: Int,
    val lastDocumentIndexedAt: LocalDateTime?,
    val averageEmbeddingBatchSize: Int
)

data class IndexHealth(
    val embeddingCoverage: Float,        // 0-1, should be 1.0
    val neo4jLinkage: Float,             // 0-1, should be 1.0
    val pendingEmbeddings: Int,          // Should be 0
    val lastIndexedBefore: Duration,
    val orphanedChunks: Int,             // Should be 0
    val orphanedSections: Int,           // Should be 0
    val isHealthy: Boolean
)
```

---

## Part 8: Complete Workflow Example

### End-to-End Synchronous Indexing

```kotlin
// File: src/main/kotlin/com/orchestrator/context/neo4j/E2EIndexingWorkflow.kt

/**
 * Complete end-to-end example of synchronous document indexing
 * with semantic embeddings coordinated across code + documents
 */
class E2EIndexingWorkflow(
    private val watcher: DocumentAwareWatcher,
    private val synchronousDocumentIndexer: SynchronousDocumentIndexer,
    private val embeddingCoordinator: EmbeddingCoordinator,
    private val metrics: IndexingMetrics
) {
    
    suspend fun onFileSystemChange(
        changedFiles: List<Path>,
        deletedFiles: List<Path>
    ) {
        logger.info("File system change: ${changedFiles.size} changes, ${deletedFiles.size} deletions")
        
        // 1. Separate files by type
        val documentChanges = changedFiles.filter {
            DocumentTypeDetector.isDocument(DocumentTypeDetector.detectFromPath(it.toString()))
        }
        
        if (documentChanges.isEmpty()) {
            logger.debug("No document changes, skipping")
            return
        }
        
        // 2. Process documents synchronously
        val startTime = System.currentTimeMillis()
        
        try {
            // 3. Index to both Neo4j + DuckDB with synchronized embeddings
            watcher.handleFileChanges(documentChanges, deletedFiles)
            
            // 4. Report metrics
            val elapsed = System.currentTimeMillis() - startTime
            val status = metrics.getDocumentIndexingStatus()
            
            logger.info("""
                Indexing complete in ${elapsed}ms:
                  - Documents: ${status.totalDocumentsIndexed}
                  - Chunks: ${status.totalDocumentChunks}
                  - Embeddings: ${status.totalDocumentEmbeddings}
                  - Neo4j sections: ${status.totalNeo4jSections}
                  - Pending embeddings: ${status.pendingEmbeddings}
            """.trimIndent())
            
            // 5. Check health
            val health = metrics.getIndexingHealth()
            if (!health.isHealthy) {
                logger.warn("Index health check failed:")
                logger.warn("  - Embedding coverage: ${health.embeddingCoverage}")
                logger.warn("  - Neo4j linkage: ${health.neo4jLinkage}")
                logger.warn("  - Orphaned chunks: ${health.orphanedChunks}")
                logger.warn("  - Orphaned sections: ${health.orphanedSections}")
            }
        } catch (e: Exception) {
            logger.error("Indexing failed: ${e.message}", e)
        }
    }
}
```

---

## Summary

This architecture provides:

✅ **Incremental indexing** - Process changes as they happen
✅ **Synchronous processing** - Everything stays in sync
✅ **Batch embeddings** - Single batch call for code + documents
✅ **Neo4j + DuckDB coordination** - Structure + semantics together
✅ **Atomic transactions** - No partial updates
✅ **Monitoring** - Health checks and metrics
✅ **Reindexing support** - Rebuild structure, regenerate embeddings, or both
✅ **File watcher integration** - Works with your existing system

The key innovation: **single batch call to embedder for ALL content (code + documents)**, enabling maximum efficiency while maintaining perfect synchronization between Neo4j structure and DuckDB semantic embeddings.
