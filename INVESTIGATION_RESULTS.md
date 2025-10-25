# Context Search Investigation Results
**Date**: 2025-10-17
**Issue**: `docs/TASK_ROUTING_GUIDE.md` not appearing in semantic search results

---

## Executive Summary

✅ **File IS properly indexed** with 31 chunks and 31 embeddings
❌ **Search results dominated by SESSION_CONTEXT.md** (higher BM25 scores)
✅ **File CAN be found with specific content-based queries**
⚠️ **Path filtering with glob patterns (`docs/*.md`) returns 0 hits**

---

## Root Cause Analysis

### Problem 1: SESSION_CONTEXT.md Dominance

**Issue**: SESSION_CONTEXT.md contains a detailed summary of TASK_ROUTING_GUIDE.md from the previous investigation session, causing it to:
- Rank higher in BM25 full-text search (scores like 0.636 vs 0.139)
- Appear first in hybrid search results
- Shadow the original file in search results

**Evidence**:
```
Query: "task routing three entry points create_simple_task"
Results:
1. SESSION_CONTEXT.md (score: 0.5719) ← Winner
2. docs/TASK_ROUTING_GUIDE.md (not returned or ranked lower)
```

### Problem 2: Path Filtering Issues

**Issue**: Glob patterns in `paths` parameter return 0 hits

**Test Results**:
```kotlin
// ✅ Works - exact file path
paths: ["docs/TASK_ROUTING_GUIDE.md"] → 1 hit

// ❌ Fails - glob pattern
paths: ["docs/*.md"] → 0 hits

// ✅ Works - no path filter (but returns SESSION_CONTEXT.md)
paths: null → multiple hits (SESSION_CONTEXT.md dominates)
```

### Problem 3: Semantic Search Not Engaging

**Observation**: Provider statistics show:
```json
"semantic": {"snippets": 0, "durationMs": 614, "type": "SEMANTIC"},
"hybrid": {"snippets": 1, "durationMs": 505, "type": "HYBRID"}
```

Semantic search is running but returning 0 snippets, falling back to hybrid (BM25 full-text).

---

## Successful Queries (File Found)

### Query 1: Unique Section Heading
```kotlin
query: "Step 0 Calibration Refreshes strategy picker configuration from telemetry"
result: docs/TASK_ROUTING_GUIDE.md (score: 0.6528) ✅
```

### Query 2: Specific Feature Description
```kotlin
query: "Agent availability checking OFFLINE error BUSY warning"
result: docs/TASK_ROUTING_GUIDE.md (score: 0.3144) ✅
```

### Query 3: Exact File Path + Specific Content
```kotlin
query: "Step 0 Calibration Refreshes strategy picker"
paths: ["docs/TASK_ROUTING_GUIDE.md"]
result: docs/TASK_ROUTING_GUIDE.md (score: 0.6528) ✅
```

---

## Failed Queries (File Not Found or Shadowed)

### Query 1: Filename Search
```kotlin
query: "TASK_ROUTING_GUIDE"
result: SESSION_CONTEXT.md (multiple hits), docs/TASK_ROUTING_GUIDE.md not in top 5
```

### Query 2: General Content Search
```kotlin
query: "routing module task classification strategy selection agent"
result: SESSION_CONTEXT.md (score: 0.6954), docs/TASK_ROUTING_GUIDE.md not returned
```

### Query 3: Glob Pattern Path Filter
```kotlin
query: "task routing three entry points"
paths: ["docs/*.md"]
result: 0 hits ❌
```

---

## Recommendations

### Immediate Actions

1. **Exclude SESSION_CONTEXT.md from indexing**
   ```toml
   # config/context.toml
   [indexing]
   exclude_patterns = ["SESSION_CONTEXT.md", "INVESTIGATION_*.md"]
   ```

2. **Refresh context index to remove SESSION_CONTEXT.md**
   ```bash
   # Option A: Delete file and refresh
   rm SESSION_CONTEXT.md
   mcp__orchestrator__refresh_context()

   # Option B: Add to .gitignore and exclude_patterns
   echo "SESSION_CONTEXT.md" >> .gitignore
   ```

3. **Fix glob pattern path filtering** (Bug Report Required)
   - Current: `paths: ["docs/*.md"]` returns 0 hits
   - Expected: Should match all markdown files in docs/
   - Workaround: Use exact file paths or no filter + excludePatterns

### Query Best Practices

**DO** ✅:
- Use specific content terms from the target document
- Query unique section headings or technical terms
- Use exact file paths when known: `paths: ["docs/TASK_ROUTING_GUIDE.md"]`
- Use `excludePatterns` to filter out noise: `excludePatterns: ["SESSION_CONTEXT.md"]`

**DON'T** ❌:
- Search by filename only ("TASK_ROUTING_GUIDE")
- Use generic terms that appear in many files
- Rely on glob patterns in `paths` parameter (currently broken)
- Expect semantic search to work without content-based queries

### Long-Term Improvements

1. **Implement file recency decay** - older files with duplicated content should rank lower
2. **Improve semantic search activation** - currently returning 0 snippets, falling back to BM25
3. **Fix glob pattern support** in path filtering
4. **Add file type boosting** - prefer docs/ over session notes
5. **Context diversity** - penalize duplicate content across files

---

## Testing Matrix

| Query Type | Path Filter | Expected Result | Actual Result | Status |
|------------|-------------|-----------------|---------------|--------|
| Unique content | None | Target file | ✅ Found | PASS |
| Unique content | Exact path | Target file | ✅ Found | PASS |
| Unique content | Glob pattern | Target file | ❌ 0 hits | FAIL |
| Generic content | None | Target file | ❌ SESSION_CONTEXT.md | FAIL |
| Filename only | None | Target file | ❌ SESSION_CONTEXT.md | FAIL |
| Filename only | Exact path | Target file | ❌ 0 hits | FAIL |

---

## Configuration Verification

### Context Stats
```json
{
  "storage": {
    "files": 410,
    "chunks": 6049,
    "embeddings": 6049,
    "totalSizeBytes": 2960724
  },
  "languageDistribution": [
    {"language": "kotlin", "fileCount": 371},
    {"language": "markdown", "fileCount": 30}
  ],
  "providerStatus": [
    {"id": "semantic", "enabled": true, "weight": 0.6},
    {"id": "symbol", "enabled": true, "weight": 0.3},
    {"id": "full_text", "enabled": false, "weight": 0.1},
    {"id": "hybrid", "enabled": true, "weight": 0.5}
  ]
}
```

### File Verification (DuckDB)
```sql
-- File indexed
SELECT rel_path, indexed_at, size_bytes
FROM file_state
WHERE rel_path = 'docs/TASK_ROUTING_GUIDE.md'
-- Result: 1 row (indexed 2025-10-17 09:46:35, 5338 bytes)

-- Chunks created
SELECT COUNT(*) FROM chunks c
JOIN file_state f ON c.file_id = f.file_id
WHERE f.rel_path = 'docs/TASK_ROUTING_GUIDE.md'
-- Result: 31 chunks

-- Embeddings created
SELECT COUNT(*) FROM embeddings e
JOIN chunks c ON e.chunk_id = c.chunk_id
JOIN file_state f ON c.file_id = f.file_id
WHERE f.rel_path = 'docs/TASK_ROUTING_GUIDE.md'
-- Result: 31 embeddings
```

---

## Conclusion

**The file is properly indexed and searchable**, but search results are dominated by SESSION_CONTEXT.md which contains duplicate/summarized content. The solution is to:

1. Exclude session notes from indexing
2. Refresh the context index
3. Use content-based queries with specific terms
4. Apply `excludePatterns` when SESSION_CONTEXT.md cannot be removed

**Path filtering with glob patterns is broken** and needs investigation/fixing in the context query implementation.

---

## Next Steps

- [ ] Add SESSION_CONTEXT.md to exclude_patterns in config/context.toml
- [ ] Run refresh_context() to remove SESSION_CONTEXT.md from index
- [ ] File bug report for glob pattern path filtering
- [ ] Test search quality after SESSION_CONTEXT.md removal
- [ ] Consider implementing file recency scoring
- [ ] Investigate why semantic search returns 0 snippets
