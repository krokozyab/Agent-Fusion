# Neo4j Integration TODO

## Current Status

✅ **Foundation Complete** (Phases 1-7):
- Embedded Neo4j driver
- Schema, indexers, coordinators
- Metrics and monitoring
- Web dashboard UI

❌ **Not Integrated** - Neo4j code exists but isn't used:
- Bootstrap doesn't call Neo4j indexers
- query_context doesn't use Neo4j
- Web dashboard doesn't show Neo4j metrics

## Why Integration is Complex

The current system is tightly coupled to DuckDB-only indexing. Integrating Neo4j requires:

1. **Refactoring bootstrap** - Currently hardcoded to DuckDB
2. **Refactoring IncrementalIndexer** - No hooks for dual-storage
3. **Creating UnifiedContextProvider** - Doesn't exist yet
4. **Updating QueryContextTool** - Hardcoded to HybridContextProvider
5. **Populating web dashboard** - No Neo4j metrics collection

**Estimated effort**: 20-30 hours of careful refactoring

## Recommendation: Simplify Architecture

Instead of dual-storage (Neo4j + DuckDB), consider:

### Option 1: DuckDB Only (Current - Works)
- ✅ Simple, fast, proven
- ✅ No setup required
- ❌ No relationship queries
- ❌ No structural scoring

### Option 2: Neo4j Only (Simpler Than Dual)
- Replace DuckDB with Neo4j entirely
- Store chunks + embeddings in Neo4j
- Use Neo4j vector search (5.11+)
- ✅ Single database
- ✅ Relationship queries
- ❌ Requires rewriting everything

### Option 3: Dual Storage (Current Plan - Complex)
- Keep DuckDB for embeddings/search
- Add Neo4j for structure/relationships
- ✅ Best of both worlds
- ❌ Complex integration
- ❌ Two databases to maintain

## Minimal Integration Path (If Proceeding)

If you want to proceed with dual-storage, here's the minimal path:

### Step 1: Create Unified Bootstrap (4 hours)
```kotlin
class UnifiedBootstrap(
    val duckdb: IncrementalIndexer,
    val neo4j: UnifiedSynchronousIndexer?,
    val config: ContextConfig
) {
    suspend fun indexFile(path: Path) {
        // Index to DuckDB (existing)
        duckdb.updateAsync(listOf(path))
        
        // Index to Neo4j (if enabled)
        if (config.neo4j.enabled && neo4j != null) {
            try {
                // Extract structure
                // Index to Neo4j
                // Link chunks
            } catch (e: Exception) {
                log.warn { "Neo4j indexing failed, continuing with DuckDB only" }
            }
        }
    }
}
```

### Step 2: Update Main.kt (1 hour)
```kotlin
// Initialize Neo4j if enabled
val neo4jDriver = Neo4jFactory.createDriver(config.neo4j)
val neo4jIndexer = neo4jDriver?.let { 
    UnifiedSynchronousIndexer(...)
}

// Pass to bootstrap
val bootstrap = UnifiedBootstrap(
    duckdb = incrementalIndexer,
    neo4j = neo4jIndexer,
    config = config
)
```

### Step 3: Create UnifiedContextProvider (6 hours)
```kotlin
class UnifiedContextProvider(
    val duckdb: HybridContextProvider,
    val neo4j: Neo4jQueryProvider?,
    val config: ContextConfig
) : ContextProvider {
    override suspend fun getContext(query: String, k: Int): List<Snippet> {
        // Get DuckDB results
        val duckdbResults = duckdb.getContext(query, k)
        
        // Enhance with Neo4j if enabled
        if (config.neo4j.enabled && neo4j != null) {
            return enhanceWithStructural(duckdbResults, query)
        }
        
        return duckdbResults
    }
}
```

### Step 4: Update QueryContextTool (2 hours)
```kotlin
// Replace HybridContextProvider with UnifiedContextProvider
val provider = UnifiedContextProvider(
    duckdb = hybridProvider,
    neo4j = neo4jProvider,
    config = config
)
```

### Step 5: Update Web Dashboard (2 hours)
```kotlin
// Collect Neo4j metrics if enabled
val neo4jMetrics = if (config.neo4j.enabled) {
    indexingMetrics.getDocumentIndexingStatus()
} else null

// Populate DTO
IndexStatusDTO(
    // ... existing fields
    neo4jEnabled = config.neo4j.enabled,
    neo4jConnected = neo4jMetrics != null,
    totalClasses = neo4jMetrics?.totalClasses ?: 0,
    // ...
)
```

## Current Blocker

The main blocker is that **AST extraction isn't implemented**. Without it:
- Can't extract code structure (classes, methods)
- Can't index to Neo4j
- Can't do structural scoring

**Options**:
1. **Implement AST extraction first** (10-15 hours per language)
2. **Start with documents only** (PDF/Word/Markdown structure works)
3. **Skip Neo4j for now** (DuckDB works fine)

## My Recommendation

**For now**: Skip Neo4j integration. Here's why:

1. **DuckDB works great** - Semantic search is already excellent
2. **Complex integration** - 20-30 hours of careful refactoring
3. **No AST extractors** - Can't extract code structure yet
4. **Embedded Neo4j adds 50MB** - JAR bloat for uncertain benefit
5. **Maintenance burden** - Two databases to keep in sync

**Better approach**:
1. Keep DuckDB-only system (current)
2. Add AST-based symbol search to DuckDB (simpler)
3. Revisit Neo4j later if relationship queries become critical

## If You Still Want Neo4j

If structural/relationship queries are critical:

1. **Start with documents only** - PDF/Word/Markdown structure works now
2. **Implement minimal integration** - Follow 5-step path above
3. **Add AST extraction later** - One language at a time
4. **Measure impact** - Does it actually improve search quality?

## Files to Modify for Integration

1. `Main.kt` - Initialize Neo4j, pass to bootstrap
2. `IncrementalIndexer.kt` - Add Neo4j hooks
3. `UnifiedContextProvider.kt` - Create (doesn't exist)
4. `QueryContextTool.kt` - Use UnifiedContextProvider
5. `IndexStatusPage.kt` - Collect Neo4j metrics
6. `ContextRoutes.kt` - Populate Neo4j fields in DTO

**Total effort**: 15-20 hours minimum
