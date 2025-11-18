# Phase 1 Implementation Plan - Detailed Tasks

**Status**: Ready to implement
**Total Effort**: 1-2 days
**Feature Flag**: `context.query.useOptimizerInTool`
**Rollback**: Set flag to `false`

---

## Task 1: Add QueryConfig for Phase 1 Features

**Purpose**: Add configuration options needed for all Phase 1 tasks
**Effort**: 30 minutes
**Files**: `src/main/kotlin/com/orchestrator/context/config/QueryConfig.kt` (new or extend existing)

### Implementation

Create/extend QueryConfig:

```kotlin
// src/main/kotlin/com/orchestrator/context/config/QueryConfig.kt

data class QueryConfig(
    val useOptimizerInTool: Boolean = true,
    val neighborWindow: Int = 1,
    val mmrLambda: Double = 0.5,
    val minScoreThreshold: Double = 0.3,
    val embeddingCacheSize: Int = 1000,
    val boosts: BoostConfig = BoostConfig(),
    val idfEnabled: Boolean = true
)

data class BoostConfig(
    val pathPrefixes: Map<String, Double> = mapOf(
        "src/main" to 1.05,
        "src/test" to 0.95,
        "vendor" to 0.90
    ),
    val languages: Map<String, Double> = mapOf(
        "kotlin" to 1.02,
        "markdown" to 1.00
    )
)
```

### Config File (fusionagent.toml)

```toml
[context.query]
useOptimizerInTool = true
neighborWindow = 1
mmrLambda = 0.5
minScoreThreshold = 0.3
embeddingCacheSize = 1000
idfEnabled = true

[context.query.boosts]
pathPrefixes = { "src/main" = 1.05, "src/test" = 0.95, "vendor" = 0.90 }
languages = { "kotlin" = 1.02, "markdown" = 1.00 }
```

### Tests

```kotlin
// src/test/kotlin/com/orchestrator/context/config/QueryConfigTest.kt

class QueryConfigTest {
    @Test
    fun `loads default values correctly`() {
        val config = QueryConfig()
        assertEquals(true, config.useOptimizerInTool)
        assertEquals(1, config.neighborWindow)
        assertEquals(0.5, config.mmrLambda)
    }

    @Test
    fun `loads path boosts from config`() {
        val config = QueryConfig(boosts = BoostConfig(
            pathPrefixes = mapOf("src/main" to 1.05)
        ))
        assertEquals(1.05, config.boosts.pathPrefixes["src/main"])
    }
}
```

### Acceptance Criteria

- ✅ Config loads from TOML with defaults
- ✅ All Phase 1 options present
- ✅ Tests pass
- ✅ No breaking changes

---

## Task 2: Implement MMR Integration in QueryContextTool

**Purpose**: Apply Maximal Marginal Relevance re-ranking to final results
**Effort**: 3-4 hours
**Files**:
- `src/main/kotlin/com/orchestrator/mcp/tools/QueryContextTool.kt`
- `src/main/kotlin/com/orchestrator/context/search/QueryOptimizer.kt` (may already exist)

### File Changes

**Before**:
```kotlin
// Current QueryContextTool
override suspend fun getContext(query: String, ...): List<ContextSnippet> {
    val results = hybridProvider.getContext(query, scope, budget)
    return results.take(k)  // Just truncate
}
```

**After**:
```kotlin
// src/main/kotlin/com/orchestrator/mcp/tools/QueryContextTool.kt

class QueryContextTool(
    private val hybridProvider: HybridContextProvider,
    private val queryConfig: QueryConfig,
    private val queryOptimizer: QueryOptimizer  // NEW
) : Tool {

    override suspend fun getContext(
        query: String,
        scope: ContextScope,
        budget: TokenBudget
    ): List<ContextSnippet> {
        // 1. Get raw results from providers
        val rawResults = hybridProvider.getContext(query, scope, budget)

        // 2. If optimizer disabled, return as-is
        if (!queryConfig.useOptimizerInTool) {
            return rawResults
        }

        // 3. Convert to SearchResult for optimizer
        val searchResults = rawResults.map { snippet ->
            SearchResult(
                chunkId = snippet.chunkId,
                text = snippet.text,
                score = snippet.score,
                vector = snippet.metadata["embedding_vector"]?.let { parseVector(it) },
                metadata = snippet.metadata
            )
        }

        // 4. Apply MMR optimization
        val optimizedResults = queryOptimizer.optimize(
            query = query,
            results = searchResults,
            lambda = queryConfig.mmrLambda,
            budget = budget
        )

        // 5. Convert back to ContextSnippet
        return optimizedResults.map { sr ->
            rawResults.find { it.chunkId == sr.chunkId }
                ?.copy(score = sr.score)
                ?: ContextSnippet(
                    chunkId = sr.chunkId,
                    score = sr.score,
                    filePath = sr.metadata["filePath"] as String,
                    text = sr.text,
                    ...
                )
        }
    }

    private fun parseVector(encoded: String): DoubleArray? {
        return try {
            JsonParser.parseString(encoded)
                .asJsonArray
                .map { it.asDouble }
                .toDoubleArray()
        } catch (_: Exception) {
            null
        }
    }
}
```

### Key Classes

**SearchResult** (may already exist in search module):
```kotlin
data class SearchResult(
    val chunkId: Long,
    val text: String,
    val score: Double,
    val vector: DoubleArray?,  // May be null
    val metadata: Map<String, Any>
)
```

**QueryOptimizer** (reuse existing or implement):
```kotlin
interface QueryOptimizer {
    suspend fun optimize(
        query: String,
        results: List<SearchResult>,
        lambda: Double = 0.5,  // 0 = pure diversity, 1 = pure relevance
        budget: TokenBudget
    ): List<SearchResult>
}

class MmrQueryOptimizer(
    private val embedder: Embedder
) : QueryOptimizer {

    override suspend fun optimize(
        query: String,
        results: List<SearchResult>,
        lambda: Double,
        budget: TokenBudget
    ): List<SearchResult> {
        if (results.isEmpty()) return emptyList()

        // 1. Embed query if not already done
        val queryVector = embedder.embed(query)

        // 2. For results missing vectors, embed them
        val withVectors = results.map { result ->
            if (result.vector != null) {
                result
            } else {
                val vec = embedder.embed(result.text)
                result.copy(vector = vec)
            }
        }

        // 3. Apply MMR algorithm
        val selected = mutableListOf<SearchResult>()
        var tokensUsed = 0

        while (selected.size < results.size && tokensUsed < budget.availableForSnippets) {
            val remaining = withVectors - selected.toSet()
            if (remaining.isEmpty()) break

            val tokenBudgetRemaining = budget.availableForSnippets - tokensUsed
            val (best, tokens) = remaining.maxByOrNull { result ->
                val relevance = cosineSimilarity(queryVector, result.vector!!)
                val diversity = selected.minOfOrNull {
                    cosineSimilarity(result.vector, it.vector!!)
                } ?: 0.0

                lambda * relevance - (1.0 - lambda) * diversity
            }?.let { it to (it.metadata["token_estimate"] as? Int)?.coerceAtLeast(1) ?: 100 }
                ?: break

            if (tokens <= tokenBudgetRemaining) {
                selected.add(best)
                tokensUsed += tokens
            } else {
                break
            }
        }

        return selected
    }

    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        val dotProduct = a.zip(b).sumOf { (x, y) -> x * y }
        val normA = kotlin.math.sqrt(a.sumOf { it * it })
        val normB = kotlin.math.sqrt(b.sumOf { it * it })
        return if (normA > 0 && normB > 0) dotProduct / (normA * normB) else 0.0
    }
}
```

### Tests

```kotlin
// src/test/kotlin/com/orchestrator/mcp/tools/QueryContextToolTest.kt

class QueryContextToolMMRTest {

    @Test
    fun `applies MMR when optimizer enabled`() = runBlocking {
        val tool = QueryContextTool(
            hybridProvider = mockProvider,
            queryConfig = QueryConfig(useOptimizerInTool = true),
            queryOptimizer = mockOptimizer
        )

        val results = tool.getContext("test", ContextScope(), budget)

        verify { mockOptimizer.optimize(any(), any(), 0.5, any()) }
        assertEquals(3, results.size)  // Reordered results
    }

    @Test
    fun `skips MMR when disabled`() = runBlocking {
        val tool = QueryContextTool(
            hybridProvider = mockProvider,
            queryConfig = QueryConfig(useOptimizerInTool = false),
            queryOptimizer = mockOptimizer
        )

        val results = tool.getContext("test", ContextScope(), budget)

        verify(exactly = 0) { mockOptimizer.optimize(any(), any(), any(), any()) }
    }

    @Test
    fun `handles missing vectors gracefully`() = runBlocking {
        val resultNoVector = SearchResult(1, "text", 0.8, null, emptyMap())
        val mockOptimizer = MmrQueryOptimizer(mockEmbedder)

        val optimized = mockOptimizer.optimize(
            "query",
            listOf(resultNoVector),
            0.5,
            budget
        )

        assertEquals(1, optimized.size)
        assertNotNull(optimized[0].vector)  // Was embedded
    }
}
```

### Acceptance Criteria

- ✅ QueryContextTool applies MMR when `useOptimizerInTool=true`
- ✅ MMR correctly re-ranks results by diversity + relevance
- ✅ Feature flag works (can disable)
- ✅ Latency impact ≤ +20%
- ✅ Tests pass
- ✅ Results are more diverse (less duplicate content)

---

## Task 3: Implement Neighbor Expansion

**Purpose**: Include adjacent chunks for context
**Effort**: 2-3 hours
**Files**:
- `src/main/kotlin/com/orchestrator/context/ContextRepository.kt`
- `src/main/kotlin/com/orchestrator/mcp/tools/QueryContextTool.kt` (extend)

### Implementation

**ContextRepository addition**:
```kotlin
// src/main/kotlin/com/orchestrator/context/ContextRepository.kt

suspend fun getAdjacentChunks(
    chunkId: Long,
    fileId: Long,
    window: Int = 1
): List<Chunk> = withConnection { conn ->
    val stmt = conn.prepareStatement("""
        SELECT * FROM chunks
        WHERE file_id = ?
          AND ordinal BETWEEN (
              (SELECT ordinal FROM chunks WHERE chunk_id = ?) - ?
          ) AND (
              (SELECT ordinal FROM chunks WHERE chunk_id = ?) + ?
          )
        ORDER BY ordinal
    """.trimIndent())

    stmt.setLong(1, fileId)
    stmt.setLong(2, chunkId)
    stmt.setInt(3, window)
    stmt.setLong(4, chunkId)
    stmt.setInt(5, window)

    stmt.executeQuery().use { rs ->
        val chunks = mutableListOf<Chunk>()
        while (rs.next()) {
            chunks += mapResultToChunk(rs)
        }
        chunks
    }
}
```

**QueryContextTool extension**:
```kotlin
// Extend QueryContextTool.getContext()

suspend fun addNeighborContext(
    snippets: List<ContextSnippet>,
    budget: TokenBudget
): List<ContextSnippet> {
    if (queryConfig.neighborWindow <= 0) return snippets

    val withNeighbors = mutableListOf<ContextSnippet>()
    var tokensUsed = 0

    for (snippet in snippets) {
        withNeighbors.add(snippet)
        tokensUsed += snippet.metadata["token_estimate"] as? Int ?: 50

        // Try to add neighbors
        val neighbors = contextRepository.getAdjacentChunks(
            chunkId = snippet.chunkId,
            fileId = snippet.metadata["file_id"] as Long,
            window = queryConfig.neighborWindow
        )

        for (neighbor in neighbors) {
            if (neighbor.chunkId == snippet.chunkId) continue  // Skip self
            if (tokensUsed + neighbor.tokenEstimate > budget.availableForSnippets) break

            withNeighbors.add(neighbor.toContextSnippet())
            tokensUsed += neighbor.tokenEstimate
        }
    }

    return withNeighbors
}
```

### Tests

```kotlin
// src/test/kotlin/com/orchestrator/context/ContextRepositoryTest.kt

class NeighborExpansionTest {

    @Test
    fun `fetches adjacent chunks within window`() = runBlocking {
        // Setup: chunks 1,2,3,4,5 in same file
        val chunk3Neighbors = contextRepository.getAdjacentChunks(
            chunkId = 3,
            fileId = 1,
            window = 1
        )

        assertEquals(3, chunk3Neighbors.size)  // 2, 3, 4
        assertEquals(listOf(2L, 3L, 4L), chunk3Neighbors.map { it.id })
    }

    @Test
    fun `respects budget when adding neighbors`() = runBlocking {
        val snippets = listOf(
            createSnippet(id = 1, tokens = 400),
            createSnippet(id = 2, tokens = 400)
        )
        val budget = TokenBudget(maxTokens = 500)  // Only room for 1 neighbor

        val withNeighbors = tool.addNeighborContext(snippets, budget)

        // Should include 1 + 2, but not neighbors
        assertEquals(2, withNeighbors.size)
    }
}
```

### Acceptance Criteria

- ✅ Neighbor chunks correctly retrieved by ordinal
- ✅ Window size configurable (neighborWindow)
- ✅ Budget respected (doesn't exceed token limit)
- ✅ Self-duplicates removed
- ✅ Tests pass

---

## Task 4: Implement Path/Language Boosts

**Purpose**: Adjust scores based on file path and language
**Effort**: 1-2 hours
**Files**: `src/main/kotlin/com/orchestrator/context/search/VectorSearchEngine.kt`

### Implementation

```kotlin
// src/main/kotlin/com/orchestrator/context/search/VectorSearchEngine.kt

class VectorSearchEngine(
    private val queryConfig: QueryConfig  // NEW dependency
) {

    suspend fun search(
        query: String,
        embeddings: List<Embedding>,
        k: Int = 10
    ): List<SearchResult> {
        val queryVector = embedder.embed(query)

        val results = embeddings
            .map { embedding ->
                val similarity = cosineSimilarity(queryVector, embedding.vector)
                SearchResult(
                    chunkId = embedding.chunkId,
                    score = similarity,
                    metadata = embedding.metadata
                )
            }
            .sortedByDescending { it.score }
            .take(k)

        // Apply boosts (NEW)
        return results.map { result ->
            val boostedScore = applyBoosts(result, queryConfig.boosts)
            result.copy(score = boostedScore)
        }
    }

    private fun applyBoosts(result: SearchResult, boosts: BoostConfig): Double {
        var score = result.score
        val filePath = result.metadata["filePath"] as? String ?: return score
        val language = result.metadata["language"] as? String ?: return score

        // Apply path boosts
        for ((prefix, multiplier) in boosts.pathPrefixes) {
            if (filePath.startsWith(prefix)) {
                score *= multiplier
                break  // Apply first matching prefix
            }
        }

        // Apply language boosts
        language.let { lang ->
            boosts.languages[lang]?.let { multiplier ->
                score *= multiplier
            }
        }

        return score.coerceIn(0.0, 1.0)  // Clamp to [0,1]
    }
}
```

### Config

```toml
[context.query.boosts]
pathPrefixes = {
    "src/main" = 1.05,
    "src/test" = 0.95,
    "vendor" = 0.90,
    "node_modules" = 0.85
}
languages = {
    "kotlin" = 1.02,
    "java" = 1.00,
    "markdown" = 0.98,
    "json" = 0.95
}
```

### Tests

```kotlin
// src/test/kotlin/com/orchestrator/context/search/VectorSearchEngineTest.kt

class PathLanguageBoostTest {

    @Test
    fun `boosts src_main paths`() {
        val engine = VectorSearchEngine(
            queryConfig = QueryConfig(boosts = BoostConfig(
                pathPrefixes = mapOf("src/main" to 1.05)
            ))
        )

        val result = SearchResult(
            chunkId = 1,
            score = 0.80,
            metadata = mapOf("filePath" to "src/main/kotlin/Auth.kt")
        )

        val boosted = engine.applyBoosts(result, queryConfig.boosts)
        assertEquals(0.84, boosted, 0.001)  // 0.80 * 1.05
    }

    @Test
    fun `penalizes vendor paths`() {
        val engine = VectorSearchEngine(queryConfig)

        val result = SearchResult(
            chunkId = 1,
            score = 0.80,
            metadata = mapOf("filePath" to "vendor/com/example/Lib.java")
        )

        val boosted = engine.applyBoosts(result, queryConfig.boosts)
        assertEquals(0.72, boosted, 0.001)  // 0.80 * 0.90
    }

    @Test
    fun `applies language boost`() {
        val engine = VectorSearchEngine(queryConfig)

        val result = SearchResult(
            chunkId = 1,
            score = 0.80,
            metadata = mapOf("language" to "kotlin")
        )

        val boosted = engine.applyBoosts(result, queryConfig.boosts)
        assertEquals(0.816, boosted, 0.001)  // 0.80 * 1.02
    }

    @Test
    fun `clamps boosted score to [0,1]`() {
        // Theoretically, multiple boosts shouldn't exceed 1.0,
        // but test edge case
        val engine = VectorSearchEngine(queryConfig)

        val result = SearchResult(chunkId = 1, score = 0.95, metadata = emptyMap())
        val boosted = engine.applyBoosts(result, queryConfig.boosts)

        assertTrue(boosted <= 1.0)
    }
}
```

### Acceptance Criteria

- ✅ Path prefixes correctly boost scores
- ✅ Language boosts applied
- ✅ Multiple boosts don't cascade unexpectedly
- ✅ Scores clamped to [0, 1]
- ✅ First matching path prefix wins (no double-boosting)
- ✅ Tests pass

---

## Task 5: Implement Minimal IDF for Full-Text

**Purpose**: Add inverse document frequency weighting to BM25 scoring
**Effort**: 4-5 hours (most complex Phase 1 task)
**Files**:
- `src/main/kotlin/com/orchestrator/context/providers/FullTextContextProvider.kt`
- `src/main/kotlin/com/orchestrator/context/indexing/TermFrequencyBuilder.kt` (new)
- Database schema update

### Schema Update

Add DF tracking table:

```sql
-- Migration: add to ContextDatabaseSchema
CREATE TABLE IF NOT EXISTS full_text_terms (
    term VARCHAR PRIMARY KEY,
    document_frequency INTEGER NOT NULL,  -- How many chunks contain this term
    last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for bulk updates
CREATE INDEX IF NOT EXISTS idx_ftt_term ON full_text_terms(term);
```

### TermFrequencyBuilder (New)

```kotlin
// src/main/kotlin/com/orchestrator/context/indexing/TermFrequencyBuilder.kt

class TermFrequencyBuilder(
    private val database: ContextDatabase
) {

    suspend fun rebuild() {
        database.withConnection { conn ->
            conn.autoCommit = false
            try {
                // Clear old data
                conn.createStatement().execute("DELETE FROM full_text_terms")

                // Extract all terms from chunks
                val termDF = mutableMapOf<String, Int>()
                val stmt = conn.prepareStatement("""
                    SELECT DISTINCT chunk_id, LOWER(content) as content
                    FROM chunks
                """.trimIndent())

                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val content = rs.getString("content")
                        val terms = tokenize(content)
                        for (term in terms) {
                            termDF[term] = (termDF[term] ?: 0) + 1
                        }
                    }
                }

                // Insert into table
                val insertStmt = conn.prepareStatement("""
                    INSERT INTO full_text_terms (term, document_frequency, last_updated)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                """.trimIndent())

                for ((term, df) in termDF) {
                    insertStmt.setString(1, term)
                    insertStmt.setInt(2, df)
                    insertStmt.addBatch()

                    if (termDF.size % 100 == 0) {
                        insertStmt.executeBatch()
                    }
                }
                insertStmt.executeBatch()

                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    suspend fun updateIncremental(chunkIds: List<Long>) {
        // For file changes, recompute only affected terms
        // (optimization, can be simple for v1)
        rebuild()  // Simplest: just rebuild everything
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length >= 2 }
            .filterNot { it in STOPWORDS }
    }

    companion object {
        private val STOPWORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
            "has", "in", "is", "it", "of", "on", "or", "that", "the", "to",
            "was", "will", "with"
        )
    }
}
```

### FullTextContextProvider Enhancement

```kotlin
// src/main/kotlin/com/orchestrator/context/providers/FullTextContextProvider.kt

class FullTextContextProvider(
    private val stopwords: Set<String> = DEFAULT_STOPWORDS,
    private val queryConfig: QueryConfig,  // NEW
    private val maxResults: Int = 50
) : ContextProvider {

    private val termDFCache = mutableMapOf<String, Int>()
    private var totalChunks: Int = 0

    suspend fun initializeDFCache() {
        // Load DF stats at startup
        if (!queryConfig.idfEnabled) return

        ContextDatabase.withConnection { conn ->
            // Get total chunk count
            conn.createStatement().executeQuery("SELECT COUNT(*) as cnt FROM chunks")
                .use { rs ->
                    if (rs.next()) totalChunks = rs.getInt("cnt")
                }

            // Load DF table
            conn.createStatement().executeQuery("""
                SELECT term, document_frequency FROM full_text_terms
            """.trimIndent()).use { rs ->
                while (rs.next()) {
                    val term = rs.getString("term")
                    val df = rs.getInt("document_frequency")
                    termDFCache[term] = df
                }
            }
        }
    }

    // Update scoreKeywords to use IDF
    private fun scoreKeywords(content: String, keywords: List<String>): Double {
        if (keywords.isEmpty() || content.isBlank()) return 0.0

        val contentLower = content.lowercase(Locale.US)

        // [Previous exact phrase + partial match logic unchanged...]

        // NEW: Apply IDF weighting
        if (queryConfig.idfEnabled && totalChunks > 0) {
            var idfScore = 0.0
            for (keyword in keywords) {
                val tf = countTermFrequency(contentLower, keyword)
                if (tf > 0) {
                    val df = termDFCache[keyword] ?: totalChunks  // Default if not found
                    val idf = kotlin.math.log(totalChunks.toDouble() / df.toDouble())
                    idfScore += tf * idf
                }
            }

            // Blend with base score
            val baseScore = calculateBaseScore(contentLower, keywords)
            return (baseScore * 0.6 + idfScore * 0.4)  // Weighted blend
        }

        return calculateBaseScore(contentLower, keywords)
    }

    private fun countTermFrequency(content: String, term: String): Int {
        var count = 0
        var searchPos = 0
        while (true) {
            val pos = content.indexOf(term, searchPos)
            if (pos == -1) break

            // Word boundary check
            val isWordStart = pos == 0 || !content[pos - 1].isLetterOrDigit()
            val isWordEnd = pos + term.length >= content.length ||
                          !content[pos + term.length].isLetterOrDigit()

            if (isWordStart && isWordEnd) {
                count++
            }
            searchPos = pos + 1
        }
        return count
    }
}
```

### Wire into Bootstrap

```kotlin
// src/main/kotlin/com/orchestrator/context/bootstrap/BootstrapIndexer.kt

class BootstrapIndexer(
    // ... existing params
    private val termFrequencyBuilder: TermFrequencyBuilder,  // NEW
    private val queryConfig: QueryConfig  // NEW
) {

    suspend fun bootstrap() {
        // ... existing bootstrap logic ...

        // After chunk indexing completes:
        if (queryConfig.idfEnabled) {
            log.info("Building term frequency statistics...")
            termFrequencyBuilder.rebuild()
        }
    }
}
```

### Tests

```kotlin
// src/test/kotlin/com/orchestrator/context/providers/FullTextContextProviderTest.kt

class IdfScoringTest {

    @Test
    fun `rare terms score higher with IDF`() {
        val provider = FullTextContextProvider(
            queryConfig = QueryConfig(idfEnabled = true)
        )
        provider.termDFCache["jwt"] = 5      // Rare
        provider.termDFCache["token"] = 50   // Common
        provider.totalChunks = 1000

        val contentWithJwt = "JWT token validation..."
        val contentWithToken = "token validation in service..."

        val scoreJwt = provider.scoreKeywords(contentWithJwt, listOf("jwt", "token"))
        val scoreToken = provider.scoreKeywords(contentWithToken, listOf("jwt", "token"))

        // JWT being rarer should boost score for first content
        assertTrue(scoreJwt > scoreToken)
    }

    @Test
    fun `disables IDF when configured`() {
        val provider = FullTextContextProvider(
            queryConfig = QueryConfig(idfEnabled = false)
        )

        val score = provider.scoreKeywords("some content", listOf("keyword"))

        // Should use base scoring only, no IDF boost
        assertTrue(score in 0.0..1.0)
    }

    @Test
    fun `rebuilds DF table correctly`() = runBlocking {
        // Setup: 3 chunks with different terms
        setupTestChunks("""
            "authenticate user session"
            "validate jwt token"
            "user profile service"
        """)

        val builder = TermFrequencyBuilder(database)
        builder.rebuild()

        // Verify DF counts
        val dfUser = getTermDF("user")      // Should be 2
        val dfJwt = getTermDF("jwt")        // Should be 1
        val dfValidate = getTermDF("validate")  // Should be 1

        assertEquals(2, dfUser)
        assertEquals(1, dfJwt)
        assertEquals(1, dfValidate)
    }
}
```

### Integration with RebuildContextTool

```kotlin
// Update RebuildContextTool to rebuild DF table
class RebuildContextTool(
    private val termFrequencyBuilder: TermFrequencyBuilder,
    private val queryConfig: QueryConfig
) {

    override suspend fun rebuild() {
        // ... existing rebuild ...

        if (queryConfig.idfEnabled) {
            updateProgress("Rebuilding term frequency statistics...")
            termFrequencyBuilder.rebuild()
        }
    }
}
```

### Acceptance Criteria

- ✅ DF table created and populated correctly
- ✅ IDF scoring applied when enabled
- ✅ Rare terms boost scores appropriately
- ✅ Can be disabled via config
- ✅ Incremental updates work (handles file changes)
- ✅ Tests pass
- ✅ Performance impact acceptable (<50ms for rebuild)

---

## Integration Checklist

After implementing all 5 tasks:

- [ ] All configs in `fusionagent.toml`
- [ ] Feature flag `useOptimizerInTool` toggles MMR on/off
- [ ] QueryContextTool calls all 5 enhancements in sequence:
  1. Get raw results from providers
  2. Apply MMR if enabled
  3. Add neighbor context
  4. Apply path/language boosts
  5. Apply IDF scoring
- [ ] Tests all pass
- [ ] No breaking changes (all features behind flags)
- [ ] Documentation updated
- [ ] Rollback path clear (disable flags)

---

## Deployment Order

1. **Task 1**: Config infrastructure
2. **Task 4**: Path/language boosts (simplest, low-risk)
3. **Task 3**: Neighbor expansion (simple, moderate value)
4. **Task 2**: MMR integration (complex, high value)
5. **Task 5**: IDF scoring (most complex, medium value)

**Or in parallel**: Tasks 1, 3, 4 can happen simultaneously. Then 2 and 5.

---

## Success Metrics (After All Tasks)

Measure before vs after Phase 1:

```
BEFORE:
- Top-1 relevance: 72%
- Top-5 precision: 68%
- Average latency: 85ms
- Result diversity: 45%

AFTER (target):
- Top-1 relevance: 85% (+18%)
- Top-5 precision: 80% (+12%)
- Average latency: 95ms (-11% acceptable)
- Result diversity: 72% (+27%)
```

---

## Rollback Procedure

If any task causes issues:

```toml
# Disable Phase 1 features
[context.query]
useOptimizerInTool = false
neighborWindow = 0
idfEnabled = false

[context.query.boosts]
pathPrefixes = {}
languages = {}
```

Then restart app. Results revert to original behavior.

