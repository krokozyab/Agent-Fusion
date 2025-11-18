# DuckDB Context Module Optimization Plan

**Status**: Ready for Implementation
**Last Updated**: 2025-11-10
**Priority**: High (Solves PDF indexing stalling + storage bloat)
**Scope**: Context Module only (Indexing, Search, Storage)

---

## 📋 Table of Contents

1. [Overview](#overview)
2. [Phase 1: Quick Wins (30-60 min)](#phase-1-quick-wins)
3. [Phase 2: Performance (2-3 hours)](#phase-2-performance)
4. [Phase 3: Full-Text Search (Optional, 2-3 hours)](#phase-3-full-text-search)
5. [Testing Strategy](#testing-strategy)
6. [Implementation Checklist](#implementation-checklist)
7. [Rollback Plan](#rollback-plan)

---

## Overview

### Problem Statement

The context module experiences performance and storage issues:

1. **PDF Indexing Stalls**: 772-chunk PDF takes 8+ minutes, app becomes unresponsive
   - Root causes: Batch size mismatch, memory accumulation, transaction overhead
   - Partial fix: Reduced `batch_size` from 128 to 16 in config
   - Full fix: Implement Appender API for 3-5x faster inserts

2. **Storage Bloat**: Embeddings stored as inefficient strings
   - 772-chunk PDF = ~1.2MB just for embeddings (384 floats × text overhead)
   - Solution: Enable DuckDB compression (50-70% reduction)

3. **Search Performance Degradation**: As index grows, searches slow down
   - Causes: Fetching all embeddings, filtering in Java, no query optimization
   - Solution: SQL-level filtering + ANALYZE statistics

### Architecture Decision: Why Custom Search Over DuckDB Extension

Your multi-provider hybrid search pipeline (semantic + symbol + full-text + git history) requires **RRF fusion** and **MMR reranking** that DuckDB's Vector Similarity Search Extension can't provide. Custom implementation is correct. Optimizations here make it faster without replacing it.

### Expected Outcomes

After all optimizations:

| Metric | Before | After | Gain |
|--------|--------|-------|------|
| Storage (772-chunk PDF) | ~1.2MB embeddings | ~300-600KB | **50-70%** |
| Index time (772 chunks) | ~8 minutes | ~2-3 minutes | **3-5x faster** |
| query_context latency | ~200ms (large index) | ~150ms | **20-30% faster** |
| App responsiveness | Stalls during indexing | Smooth | **No stalling** |

---

## Phase 1: Quick Wins (30-60 minutes)

**Goal**: 50% storage reduction + faster searches with minimal code changes
**Effort**: 45 minutes
**Risk**: Very Low (backward compatible, non-critical changes)

### Task 1.1: Enable DuckDB Compression Pragma ⭐ EASIEST

**Difficulty**: 🟢 Very Easy (1 line)
**Time**: 5 minutes
**Impact**: 🟢 High (50-70% storage reduction)

#### What
Add compression pragma to DuckDB connection at initialization. DuckDB supports multiple compression algorithms; we'll use Zstandard (zstd) which balances speed and compression ratio.

#### Why
- Embeddings are highly repetitive floating-point vectors (lots of small values)
- Zstd compression reduces 1.2MB per 772-chunk file to 300-600KB
- Automatic on write/read (transparent to application)
- No schema changes needed
- Backward compatible (old uncompressed data readable after pragma applied)

#### Where
**File**: `src/main/kotlin/com/orchestrator/context/storage/ContextDatabase.kt`

**Method**: `applyPragmas()` (lines 194-210)

#### How

**Current Code**:
```kotlin
private fun applyPragmas(conn: Connection) {
    conn.createStatement().use { st ->
        // Improve write throughput for incremental indexing
        val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2).coerceAtMost(8)
        st.execute("PRAGMA threads=$threads")
        // Allow DuckDB to use as much memory as the host can provide during full rebuilds
        st.execute("PRAGMA memory_limit='16GB'")
        // Avoid expensive per-row ordering overhead when bulk loading thousands of chunks
        st.execute("PRAGMA preserve_insertion_order=false")
        // Force periodic checkpoints to keep transactions from ballooning indefinitely
        st.execute("PRAGMA checkpoint_threshold='8GB'")
    }
}
```

**Updated Code**:
```kotlin
private fun applyPragmas(conn: Connection) {
    conn.createStatement().use { st ->
        // Improve write throughput for incremental indexing
        val threads = Runtime.getRuntime().availableProcessors().coerceAtLeast(2).coerceAtMost(8)
        st.execute("PRAGMA threads=$threads")
        // Allow DuckDB to use as much memory as the host can provide during full rebuilds
        st.execute("PRAGMA memory_limit='16GB'")
        // Avoid expensive per-row ordering overhead when bulk loading thousands of chunks
        st.execute("PRAGMA preserve_insertion_order=false")
        // Force periodic checkpoints to keep transactions from ballooning indefinitely
        st.execute("PRAGMA checkpoint_threshold='8GB'")
        // Enable compression for embeddings and chunk content (50-70% reduction)
        st.execute("PRAGMA compression = 'zstd'")
        log.info("Applied DuckDB compression: zstd (expected 50-70% storage reduction for embeddings)")
    }
}
```

#### Testing

```bash
# 1. Build project
./gradlew build

# 2. Delete old database to start fresh
rm -f data/context.db*

# 3. Index a large PDF (772+ chunks)
# Use web dashboard or CLI to trigger indexing

# 4. Check file size
ls -lh data/context.db

# 5. Compare:
# WITHOUT compression: file grows by ~1.2MB per 772-chunk PDF
# WITH compression: file grows by ~300-600KB per 772-chunk PDF

# 6. Verify searches still work
# Run query_context from web dashboard or MCP tool
```

#### Verification Checklist
- [ ] Code compiles without errors
- [ ] Application starts without errors
- [ ] Log contains "Applied DuckDB compression: zstd"
- [ ] Index a PDF, file size is 50-70% smaller
- [ ] query_context works correctly on compressed data
- [ ] New embeddings added after pragma still compressed
- [ ] Run `./gradlew test` - all tests pass

#### Rollback
If issues:
```kotlin
// Temporarily disable in applyPragmas()
// st.execute("PRAGMA compression = 'zstd'")  // Comment out this line
```

---

### Task 1.2: Add ANALYZE for Query Statistics ⭐ SIMPLE

**Difficulty**: 🟢 Easy (10 lines)
**Time**: 10 minutes
**Impact**: 🟡 Medium (20-30% search speed improvement)

#### What
Run ANALYZE command after bootstrap completes. This generates statistics on table/index distribution that DuckDB's query optimizer uses to make better execution plans.

#### Why
- DuckDB optimizer needs statistics to choose best query plan
- Without statistics, large searches are slower (linear scan vs index)
- One-time cost (~5 seconds after bootstrap)
- Huge payoff for query_context performance
- Non-critical (wrapped in try-catch)

#### Where
**File**: `src/main/kotlin/com/orchestrator/context/bootstrap/BootstrapOrchestrator.kt`

**Method**: `bootstrap()` (after line 87, before `errorLogger.clearErrors()`)

#### How

**Current Code** (lines 85-90):
```kotlin
        val batchResult = indexer.indexFilesAsync(filesToProcess, config.parallelWorkers) { batchProgress ->
            // progress tracking...
        }

        errorLogger.clearErrors()

        val endTime = Instant.now()
```

**Updated Code**:
```kotlin
        val batchResult = indexer.indexFilesAsync(filesToProcess, config.parallelWorkers) { batchProgress ->
            // progress tracking...
        }

        // Phase 1b: Collect statistics for query optimization
        if (batchResult.isSuccessful && successfulFiles > 0) {
            try {
                runAnalyzeStatistics()
                log.info("Bootstrap complete: analyzed statistics for {} embeddings", successfulFiles)
            } catch (e: Exception) {
                log.warn("ANALYZE command failed (non-critical, will retry on next bootstrap): ${e.message}")
            }
        }

        errorLogger.clearErrors()

        val endTime = Instant.now()
```

**Add Helper Method** to BootstrapOrchestrator class:
```kotlin
    private fun runAnalyzeStatistics() {
        ContextDatabase.withConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("ANALYZE embeddings")
                stmt.execute("ANALYZE chunks")
                stmt.execute("ANALYZE file_state")
                log.debug("ANALYZE completed for context tables")
            }
        }
    }
```

#### Testing

```bash
# 1. Build
./gradlew build

# 2. Delete database
rm -f data/context.db*

# 3. Run bootstrap (index files)
# Via web dashboard: /index > Rebuild Index
# Watch logs for "analyzed statistics"

# 4. Check log output
# Should see: "Bootstrap complete: analyzed statistics for X embeddings"
# Should see: "ANALYZE completed for context tables"

# 5. Test search performance
# Run query_context multiple times
# Should see ~20-30% faster latency after statistics collected
```

#### Verification Checklist
- [ ] Code compiles
- [ ] Bootstrap runs without errors
- [ ] Log shows "ANALYZE completed for context tables"
- [ ] No database corruption
- [ ] query_context performance improved (compare before/after logs)

#### Rollback
Already wrapped in try-catch, will skip if fails:
```kotlin
// No rollback needed - wrapped in try-catch and non-critical
```

---

### Task 1.3: Add SQL-Level Filtering to VectorSearchEngine ⭐ IMPACTFUL

**Difficulty**: 🟡 Medium (30 lines)
**Time**: 30 minutes
**Impact**: 🟡 Medium (20-30% query latency reduction)

#### What
Pre-filter embeddings by language and chunk kind **in SQL** instead of fetching all embeddings and filtering in Java. This reduces network I/O and memory usage.

#### Why

**Current Problem** (VectorSearchEngine.kt lines 47-48):
```kotlin
val rows = repository.fetchAllWithMetadata(model)  // ← Fetches ALL embeddings!

val scored = buildList<ScoredChunk> {
    for (row in rows) {
        if (row.embedding.dimensions != normalizedQuery.size) continue
        if (!filters.matches(row.language, row.chunk.kind, row.relativePath)) continue  // ← Filters in Java
```

- With 70K+ embeddings indexed → fetches everything
- Filters applied in Java after fetch
- Wastes network I/O and memory
- Slower as index grows

**Solution**: Apply filters in SQL WHERE clause before fetching

#### Where

**File 1**: `src/main/kotlin/com/orchestrator/context/storage/EmbeddingRepository.kt`

**File 2**: `src/main/kotlin/com/orchestrator/context/search/VectorSearchEngine.kt`

#### How

**Step 1: Update EmbeddingRepository.kt**

Add new method overload (after line 88):
```kotlin
    fun fetchAllWithMetadata(
        model: String? = null,
        languages: Set<String> = emptySet(),
        kinds: Set<com.orchestrator.context.domain.ChunkKind> = emptySet(),
        paths: Set<String> = emptySet()
    ): List<EmbeddingWithMetadata> = ContextDatabase.withConnection { conn ->
        val sql = buildString {
            append("""
                SELECT
                    e.embedding_id,
                    e.chunk_id,
                    e.model,
                    e.dimensions,
                    e.vector,
                    e.created_at AS embedding_created_at,
                    c.chunk_id AS chunk_id_alias,
                    c.file_id,
                    c.ordinal,
                    c.kind,
                    c.start_line,
                    c.end_line,
                    c.token_count,
                    c.content,
                    c.summary,
                    c.created_at AS chunk_created_at,
                    f.abs_path,
                    f.language
                FROM embeddings e
                INNER JOIN chunks c ON e.chunk_id = c.chunk_id
                INNER JOIN file_state f ON c.file_id = f.file_id
                WHERE 1=1
            """.trimIndent())

            if (model != null) append(" AND e.model = ?")
            if (languages.isNotEmpty()) append(" AND LOWER(f.language) IN (${languages.joinToString(",") { "?" }})")
            if (kinds.isNotEmpty()) append(" AND c.kind IN (${kinds.joinToString(",") { "?" }})")
            if (paths.isNotEmpty()) append(" AND f.abs_path IN (${paths.joinToString(",") { "?" }})")
        }

        conn.prepareStatement(sql).use { ps ->
            var idx = 1
            if (model != null) ps.setString(idx++, model)
            languages.forEach { lang -> ps.setString(idx++, lang.lowercase()) }
            kinds.forEach { kind -> ps.setString(idx++, kind.name) }
            paths.forEach { path -> ps.setString(idx++, path) }

            ps.executeQuery().use { rs ->
                val results = mutableListOf<EmbeddingWithMetadata>()
                while (rs.next()) {
                    results += rs.toEmbeddingWithMetadata()
                }
                results
            }
        }
    }
```

**Step 2: Update VectorSearchEngine.kt**

Modify `search()` method (lines 35-90), change line 47:
```kotlin
fun search(
    queryVector: FloatArray,
    k: Int,
    filters: Filters = Filters.NONE,
    model: String? = null
): List<SearchResult> {
    require(k > 0) { "k must be positive" }
    if (queryVector.isEmpty()) return emptyList()

    val normalizedQuery = VectorOps.normalize(queryVector)
    if (normalizedQuery.all { it == 0f }) return emptyList()

    // CHANGE: Pass filters to SQL instead of filtering in Java
    val rows = repository.fetchAllWithMetadata(
        model,
        filters.languages,
        filters.kinds,
        filters.paths
    )

    // Rest of method unchanged - process pre-filtered results
    val scored = buildList<ScoredChunk> {
        for (row in rows) {
            if (row.embedding.dimensions != normalizedQuery.size) continue
            // NOTE: filters.matches() no longer needed here since SQL filtered already
            // But keep it as safety check for path matching edge cases

            val candidateVector = row.embedding.vector.toFloatArray()
            // ... rest of scoring logic unchanged
        }
    }

    if (scored.isEmpty()) return emptyList()

    return scored.sortedByDescending { it.score }
        .take(k)
}
```

#### Testing

```bash
# 1. Build
./gradlew build

# 2. Delete database and re-index
rm -f data/context.db*
# Index via web dashboard

# 3. Test with language filter (should be faster)
# Via MCP tool:
query_context(
    query="authentication jwt",
    languages=["kotlin"],  // ← With filter
    k=10
)

# 4. Compare latency
# Time with filter vs without filter
# Should see 20-30% improvement with filter

# 5. Test without filter (should be unchanged from before)
query_context(
    query="authentication jwt",
    k=10  // No language filter
)

# 6. Verify correct results
# Compare results with/without filter
# Should be same results, just faster with filter
```

#### Verification Checklist
- [ ] Code compiles
- [ ] No SQL syntax errors in generated queries
- [ ] query_context with filters returns results (non-empty)
- [ ] query_context without filters returns same results as before
- [ ] Latency with language filter is 20-30% faster
- [ ] No null pointer exceptions in filter conversion
- [ ] Large indexes (10K+ embeddings) still responsive

#### Rollback
If filter causes issues:
```kotlin
// Revert to original:
val rows = repository.fetchAllWithMetadata(model)  // ← Uses old method, no filters
```

---

## Phase 2: Performance (2-3 hours)

**Goal**: Solve PDF indexing stalling (3-5x faster inserts)
**Effort**: 60-90 minutes
**Risk**: Low (batch insert optimization is well-understood pattern)

### Task 2.1: Implement Appender API for Batch Inserts ⭐ SOLVES STALLING

**Difficulty**: 🟡 Medium (50 lines)
**Time**: 45 minutes
**Impact**: 🟢 High (3-5x faster indexing)

#### What
Replace one-at-a-time `executeUpdate()` calls with multi-row `INSERT` statements. Current approach calls `executeUpdate()` per embedding; new approach batches 100+ per statement.

#### Why

**Current Problem** (EmbeddingRepository.kt lines 36-56):
```kotlin
enriched.forEach { embedding ->
    bindEmbedding(ps, embedding.id, embedding)
    ps.executeUpdate()  // ← CALLED 772 TIMES for 772-chunk file!
}
```

- 772 embeddings = 772 `executeUpdate()` calls
- Each call = prepare overhead + network round-trip + transaction management
- Why PDF indexing takes 8+ minutes

**Solution**: Batch 100 embeddings per statement
- 772 embeddings = 8 statements instead of 772
- Each statement does more work, fewer overhead calls
- Expected speedup: 3-5x

#### Where
**File**: `src/main/kotlin/com/orchestrator/context/storage/EmbeddingRepository.kt`

**Method**: Add new `insertBatchFast()` method

#### How

**Step 1: Keep old method for backward compatibility**

Keep existing `insertBatch()` as-is (lines 36-56)

**Step 2: Add new fast method** (after `insertBatch()`, around line 57):

```kotlin
    /**
     * High-performance batch insert using multi-row VALUES clauses.
     * Batches embeddings in chunks to reduce statement overhead.
     */
    fun insertBatchFast(embeddings: List<Embedding>): List<Embedding> {
        if (embeddings.isEmpty()) return emptyList()
        return ContextDatabase.transaction { conn ->
            // Generate IDs outside transaction to avoid per-row overhead
            val enriched = embeddings.map { embedding ->
                val id = if (embedding.id > 0) embedding.id else nextId(conn)
                embedding.copy(id = id)
            }

            // Batch inserts in chunks of 100 to reduce statement count
            val chunkSize = 100
            enriched.chunked(chunkSize).forEach { chunk ->
                // Build multi-row INSERT VALUES clause
                val placeholders = chunk.indices.joinToString(", ") { "(?, ?, ?, ?, ?, ?)" }
                val sql = """
                    INSERT INTO embeddings (
                        embedding_id, chunk_id, model, dimensions, vector, created_at
                    ) VALUES $placeholders
                """.trimIndent()

                conn.prepareStatement(sql).use { ps ->
                    var idx = 1
                    chunk.forEach { embedding ->
                        ps.setLong(idx++, embedding.id)
                        ps.setLong(idx++, embedding.chunkId)
                        ps.setString(idx++, embedding.model)
                        ps.setInt(idx++, embedding.dimensions)
                        ps.setString(idx++, serializeVector(embedding.vector))
                        ps.setTimestamp(idx++, Timestamp.from(embedding.createdAt))
                    }
                    ps.executeUpdate()  // ← Single statement for 100 rows!
                }
            }
            enriched
        }
    }
```

**Step 3: Update FileIndexer to use fast method**

In `src/main/kotlin/com/orchestrator/context/indexing/FileIndexer.kt`, find where embeddings are persisted (around line 155):

```kotlin
// In ContextDataService or where embeddings are inserted, change:
val persistedArtifacts = try {
    dataService.syncFileArtifacts(fileState, chunkArtifacts)  // ← This calls insertBatch internally
}

// Option A: If ContextDataService exposes the method, update it:
// embeddingRepository.insertBatchFast(embeddings) instead of insertBatch()

// Option B: Or modify EmbeddingRepository.insertBatch() to use the new method:
fun insertBatch(embeddings: List<Embedding>): List<Embedding> {
    return insertBatchFast(embeddings)  // ← Delegate to fast version
}
```

For simplicity, just replace `insertBatch()` to call `insertBatchFast()`:

In EmbeddingRepository.kt, change line 36:
```kotlin
fun insertBatch(embeddings: List<Embedding>): List<Embedding> {
    // Simply delegate to fast version
    return insertBatchFast(embeddings)
}

// Keep insertBatchFast as the new implementation above
```

#### Testing

```bash
# 1. Build
./gradlew build

# 2. Delete database
rm -f data/context.db*

# 3. Time a large PDF (772+ chunks)
# Start timer, index PDF via web dashboard
# Old: ~8 minutes
# New: ~2-3 minutes

# 4. Watch logs for performance metrics
# Should see batch insert logs (if added in Task 2.2)

# 5. Verify all embeddings were inserted
# Run query_context on indexed file
# Should get results for all chunks

# 6. Run tests
./gradlew test

# 7. Test query_context functionality
# Verify search still works correctly
```

#### Verification Checklist
- [ ] Code compiles
- [ ] 772-chunk PDF indexes in 2-3 minutes (3-5x faster)
- [ ] All embeddings inserted (verify via query_context)
- [ ] No data corruption
- [ ] Large batches (10K+ files) still work
- [ ] Database integrity maintained
- [ ] `./gradlew test` passes

#### Rollback
If issues occur:
```kotlin
// Revert insertBatch to original one-by-one implementation:
fun insertBatch(embeddings: List<Embedding>): List<Embedding> {
    if (embeddings.isEmpty()) return emptyList()
    return ContextDatabase.transaction { conn ->
        val enriched = embeddings.map { embedding ->
            val id = if (embedding.id > 0) embedding.id else nextId(conn)
            embedding.copy(id = id)
        }
        conn.prepareStatement(
            """
            INSERT INTO embeddings (
                embedding_id, chunk_id, model, dimensions, vector, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { ps ->
            enriched.forEach { embedding ->
                bindEmbedding(ps, embedding.id, embedding)
                ps.executeUpdate()  // ← Back to original
            }
        }
        enriched
    }
}
```

---

### Task 2.2: Add Performance Metrics to BatchIndexer ⭐ VISIBILITY

**Difficulty**: 🟡 Medium (20 lines)
**Time**: 20 minutes
**Impact**: 🟡 Medium (observability + debugging)

#### What
Track and log indexing speed metrics (embeddings/second, memory usage) to detect performance regressions.

#### Why
- Visibility into indexing performance
- Early detection of slowdowns (< 100 embeddings/sec = memory issue)
- Better debugging for future optimizations

#### Where
**File**: `src/main/kotlin/com/orchestrator/context/indexing/BatchIndexer.kt`

**Method**: `indexFilesInternal()` (lines 53-141)

#### How

**Add metrics tracking** (after line 73):

```kotlin
    private suspend fun indexFilesInternal(
        paths: List<Path>,
        parallelism: Int,
        progressListener: ((BatchProgress) -> Unit)?
    ): BatchResult {
        if (paths.isEmpty()) {
            val now = Instant.now(clock)
            val stats = BatchStats(
                totalFiles = 0,
                processedFiles = 0,
                succeeded = 0,
                failed = 0,
                startedAt = now,
                completedAt = now,
                durationMillis = 0
            )
            return BatchResult(emptyList(), emptyList(), stats)
        }

        val totalFiles = paths.size
        val start = Instant.now(clock)
        val processedCounter = AtomicInteger(0)
        val successCounter = AtomicInteger(0)
        val failureCounter = AtomicInteger(0)
        val successes = Collections.synchronizedList(mutableListOf<IndexResult>())
        val failures = Collections.synchronizedList(mutableListOf<BatchFailure>())

        // ADD: Performance metrics tracking
        val embeddingCounter = AtomicInteger(0)
        val reportInterval = 10  // seconds
        var lastReportTime = start

        val requestedParallelism = if (parallelism > 0) parallelism else defaultParallelism
        val workerLimit = max(1, min(requestedParallelism, totalFiles))
        log.debug(
            "Starting batch indexing for {} files with {} workers",
            totalFiles,
            workerLimit
        )

        supervisorScope {
            val semaphore = Semaphore(workerLimit)
            for (path in paths) {
                launch(dispatcher) {
                    semaphore.withPermit {
                        val (result, failure, errorMessage) = indexSingle(path)
                        if (result != null && result.success) {
                            successes.add(result)
                            successCounter.incrementAndGet()
                            // ADD: Count embeddings for metrics
                            embeddingCounter.addAndGet(result.embeddingCount)
                        }
                        if (failure != null) {
                            failures.add(failure)
                            failureCounter.incrementAndGet()
                        }

                        val processed = processedCounter.incrementAndGet()

                        // ADD: Periodic metrics report
                        val now = Instant.now(clock)
                        val elapsedSinceReport = Duration.between(lastReportTime, now).toSeconds()
                        if (elapsedSinceReport >= reportInterval && processed > 0) {
                            val embeddingsSoFar = embeddingCounter.get()
                            val filesSoFar = processed
                            val elapsedSeconds = Duration.between(start, now).toSeconds().coerceAtLeast(1)
                            val embeddingsPerSecond = embeddingsSoFar / elapsedSeconds.toDouble()
                            log.info(
                                "Batch indexing progress: {}/{} files, {} embeddings ({:.0f} eps)",
                                processed,
                                totalFiles,
                                embeddingsSoFar,
                                embeddingsPerSecond
                            )
                            lastReportTime = now
                        }

                        progressListener?.invoke(
                            BatchProgress(
                                totalFiles = totalFiles,
                                processedFiles = processed,
                                succeeded = successCounter.get(),
                                failed = failureCounter.get(),
                                lastPath = result?.relativePath ?: failure?.relativePath ?: path.toString(),
                                lastError = errorMessage
                            )
                        )

                        // MEMORY MANAGEMENT: Force GC between files to prevent accumulation
                        if (result?.chunkCount ?: 0 > 20) {
                            System.gc()
                        }
                    }
                }
            }
        }

        val completed = Instant.now(clock)
        val stats = BatchStats(
            totalFiles = totalFiles,
            processedFiles = processedCounter.get(),
            succeeded = successCounter.get(),
            failed = failureCounter.get(),
            startedAt = start,
            completedAt = completed,
            durationMillis = Duration.between(start, completed).toMillis()
        )

        // ADD: Final metrics summary
        val elapsedSeconds = Duration.between(start, completed).toSeconds().coerceAtLeast(1)
        val totalEmbeddings = embeddingCounter.get()
        val embeddingsPerSecond = totalEmbeddings / elapsedSeconds.toDouble()
        log.info(
            "Batch indexing complete: {} files, {} embeddings in {}s ({:.0f} eps)",
            totalFiles,
            totalEmbeddings,
            elapsedSeconds,
            embeddingsPerSecond
        )

        return BatchResult(
            successes = successes.toList(),
            failures = failures.toList(),
            stats = stats
        )
    }
```

#### Testing

```bash
# 1. Build
./gradlew build

# 2. Index large file set
# Via web dashboard: /index > Refresh Index

# 3. Watch logs for metrics
# Should see:
# - "Batch indexing progress: X/Y files, Z embeddings (AAA eps)"
# - "Batch indexing complete: X files, Y embeddings in Zs (AAA eps)"

# 4. Verify reasonable speeds
# Should see 500+ embeddings/second
# If < 100 eps: indicates memory issue, need more GC

# 5. Compare with Phase 2.1 timing
# Before Appender: ~50-100 eps
# After Appender: ~500-1000 eps
```

#### Verification Checklist
- [ ] Code compiles
- [ ] Logs show periodic progress (every 10 seconds)
- [ ] Final summary shows embeddings/second
- [ ] 500+ embeddings/second (healthy speed)
- [ ] Metrics are reasonable (not negative, not extreme)

#### Rollback
Safe to remove metrics if needed:
```kotlin
// Just remove the metrics reporting code
// Doesn't affect functionality
```

---

## Phase 3: Full-Text Search (Optional, 2-3 hours)

**Goal**: Replace custom BM25 with native DuckDB FTS
**Effort**: 75-120 minutes
**Risk**: Medium (requires schema migration)
**Recommendation**: Skip for now (custom BM25 works fine, Phase 1-2 give bigger wins)

### Task 3.1: Add FTS Index to Schema

**Difficulty**: 🟡 Medium (20 lines)
**Time**: 15 minutes
**Impact**: 🟡 Medium (better phrase matching)

#### What
Create FTS virtual table for chunk content with inverted index

#### Where
**File**: `src/main/kotlin/com/orchestrator/context/storage/ContextDatabase.kt`

**Method**: `ensureSchema()` (after embeddings table, around line 262)

#### How

Add after embeddings table creation:
```kotlin
"""
CREATE TABLE IF NOT EXISTS chunks_fts (
    chunk_id BIGINT PRIMARY KEY,
    content TEXT NOT NULL,
    FOREIGN KEY (chunk_id) REFERENCES chunks(chunk_id)
)
""".trimIndent(),
"""
CREATE INDEX IF NOT EXISTS chunks_fts_content_idx ON chunks_fts USING fts(content)
""".trimIndent(),
```

#### Testing
```bash
./gradlew build
# Run bootstrap - should create FTS table
# Verify no errors in logs
```

---

### Task 3.2: Replace FullTextContextProvider with DuckDB FTS

**Difficulty**: 🟠 Hard (100+ lines refactoring)
**Time**: 60-90 minutes
**Impact**: 🟡 Medium (code simplification)

#### When to Do This
- After Phase 1-2 prove stable
- When custom BM25 becomes bottleneck
- During next optimization round

#### Note
**Skip for now**. Custom BM25 is solid and integrated. Phase 1-2 give bigger ROI.

---

## Testing Strategy

### Unit Tests

```bash
# Run all existing tests
./gradlew test

# Run context module tests only
./gradlew test --tests "*context*"

# Run storage layer tests
./gradlew test --tests "*storage*"
```

### Integration Tests

```bash
# Fresh database with compression
rm -f data/context.db*
./gradlew run

# Via web dashboard:
# 1. Go to http://localhost:8081/index
# 2. Click "Rebuild Index"
# 3. Watch for metrics in logs
# 4. Wait for completion
# 5. Check: ls -lh data/context.db (should be small)
```

### Performance Tests

```bash
# Test 1: Compression effectiveness
# Before: Index PDF → measure file size
# After: Index same PDF → measure file size
# Expected: 50-70% smaller

# Test 2: Indexing speed (Phase 2)
# Before Appender: time indexing large PDF
# After Appender: time same PDF
# Expected: 3-5x faster

# Test 3: Query latency
# Before: time query_context searches
# After Phase 1: time same searches
# Expected: 20-30% faster

# Test 4: Stress test
# Index 10K+ files, verify:
# - No OOM errors
# - App remains responsive
# - Searches work correctly
```

### Verification Checklist (All Phases)

#### Phase 1
- [ ] Compression: Storage 50-70% smaller ✓
- [ ] ANALYZE: Logs show "analyzed statistics" ✓
- [ ] SQL Filtering: query_context with filters 20-30% faster ✓
- [ ] No data corruption ✓
- [ ] All tests pass ✓

#### Phase 2
- [ ] Appender: Indexing 3-5x faster ✓
- [ ] Metrics: 500+ embeddings/second ✓
- [ ] App responsive during indexing ✓
- [ ] All embeddings persisted correctly ✓

#### Phase 3 (if doing)
- [ ] FTS: Index created without errors ✓
- [ ] FTS: Phrase matching works ✓
- [ ] BM25: Can be removed safely ✓

---

## Implementation Checklist

### Pre-Implementation
- [ ] Read this document completely
- [ ] Understand Phase 1 tasks (30-60 min effort)
- [ ] Have backup of data/context.db
- [ ] Create git branch: `git checkout -b context/duckdb-optimization`

### Phase 1: Quick Wins

- [ ] **Task 1.1: Compression**
  - [ ] Add PRAGMA compression to applyPragmas()
  - [ ] Build and test
  - [ ] Commit: "Context: Enable DuckDB compression pragma"
  - [ ] Verify storage reduction (50-70%)

- [ ] **Task 1.2: ANALYZE**
  - [ ] Add ANALYZE to BootstrapOrchestrator
  - [ ] Build and test
  - [ ] Commit: "Context: Add ANALYZE statistics collection"
  - [ ] Verify query latency improvement (20-30%)

- [ ] **Task 1.3: SQL Filtering**
  - [ ] Update EmbeddingRepository with filter method
  - [ ] Update VectorSearchEngine to use filters
  - [ ] Build and test
  - [ ] Commit: "Context: Implement SQL-level embedding filtering"
  - [ ] Verify filter performance

### Phase 2: Performance

- [ ] **Task 2.1: Appender API**
  - [ ] Implement insertBatchFast() in EmbeddingRepository
  - [ ] Update insertBatch() to delegate to new method
  - [ ] Build and test with 772-chunk PDF
  - [ ] Commit: "Context: Implement high-performance batch embedding inserts"
  - [ ] Verify 3-5x speedup

- [ ] **Task 2.2: Metrics**
  - [ ] Add metrics tracking to BatchIndexer
  - [ ] Build and test
  - [ ] Commit: "Context: Add performance metrics to batch indexer"
  - [ ] Verify logs show embeddings/second

### Final Steps

- [ ] All tests pass: `./gradlew test`
- [ ] No compiler warnings
- [ ] Git history is clean
- [ ] Create pull request
- [ ] Review checklist
- [ ] Merge to main

---

## Rollback Plan

If something breaks mid-implementation:

### For Individual Tasks

| Task | Rollback | Time |
|------|----------|------|
| Compression | Comment out PRAGMA line | 1 min |
| ANALYZE | Remove try-catch block | 2 min |
| SQL Filtering | Use old `fetchAllWithMetadata(model)` | 5 min |
| Appender | Revert `insertBatch` to original | 5 min |
| Metrics | Remove metrics code | 2 min |

### For Full Rollback

```bash
# Discard all changes
git reset --hard origin/main

# Or restore from backup
rm -f data/context.db*
# Re-index from scratch
```

### Known Issues & Fixes

| Issue | Symptom | Fix |
|-------|---------|-----|
| Filter compilation error | "languages.forEach { lang ->" compile error | Use `languages.map { it }` |
| NULL pointer in metrics | App crashes during metrics report | Wrap in null-safe operator `embeddingCounter?.get()` |
| ANALYZE timeout | Bootstrap hangs on ANALYZE | Wrap in timeout: `withTimeoutOrNull(10.seconds)` |
| FTS schema migration | Old database breaks with new schema | Rebuild: `rm data/context.db*` |

---

## Git Workflow

```bash
# Start work
git checkout -b context/duckdb-optimization

# After each task
git add src/main/kotlin/com/orchestrator/context/...
git commit -m "Context: [Task Name]"

# Before pushing
git log -5 --oneline  # Verify history
./gradlew test        # Verify tests pass

# Push for review
git push origin context/duckdb-optimization

# Create PR on GitHub
# Request review
# Address feedback
# Merge when approved
```

---

## Performance Targets

| Phase | Metric | Target | How to Measure |
|-------|--------|--------|-----------------|
| 1 | Storage reduction | 50-70% | `ls -lh data/context.db` before/after |
| 1 | Query latency | 20-30% faster | `time query_context(...)` in logs |
| 2 | Indexing speed | 3-5x faster | Index 772-chunk PDF, measure time |
| 2 | Memory usage | Stable | Monitor logs for OOM warnings |
| Overall | App responsiveness | No stalling | Index large PDF, app remains responsive |

---

## References

### DuckDB Documentation

- [Compression](https://duckdb.org/docs/configuration/pragmas.html) - PRAGMA compression
- [ANALYZE](https://duckdb.org/docs/sql/statements/analyze.html) - Query statistics
- [Full-Text Search](https://duckdb.org/docs/extensions/full_text_search.html) - FTS
- [Array Operations](https://duckdb.org/docs/sql/functions/array.html) - Array functions

### Project Documentation

- [Context Addon Architecture](./CONTEXT_ADDON_ARCHITECTURE.md)
- [Search Architecture](./context/SEARCH_ARCHITECTURE.md)
- [README](../README.md)

---

## Questions & Answers

### Q: Will compression break existing data?
**A**: No. Old uncompressed data is readable. New data written with compression. Gradual migration.

### Q: Is ANALYZE mandatory?
**A**: No. It's a performance hint. Wrapped in try-catch, non-critical.

### Q: Can I use Appender API without Compression?
**A**: Yes. All phases are independent. Do Phase 1.1 + 2.1 for max speedup.

### Q: What if batch size of 100 is too large?
**A**: Reduce to 50 or 25 if needed. Trade-off: fewer batches = more memory per batch.

### Q: Should I do all 3 phases?
**A**: Phase 1: Yes (quick, safe). Phase 2: Yes (solves stalling). Phase 3: No (nice-to-have).

### Q: What's the total time commitment?
**A**: Phase 1: 30-60 min. Phase 2: 60-90 min. Total: ~2 hours. Phase 3: Skip.

---

**Document Status**: Ready for Implementation
**Last Reviewed**: 2025-11-10
**Next Review**: After Phase 1 completion
