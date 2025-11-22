# Chunk Overlap Implementation Plan

## Goal
Add sentence-based overlap to existing chunks to improve retrieval recall when concepts span chunk boundaries.

## Assumptions
- Current chunkers have `overlapPercent` parameter but don't use it
- Target 15-20% overlap (configurable)
- Sentence-based overlap (preserve semantic units)
- Apply to all chunker types uniformly

---

## Tasks

### 1. Core Overlap Utility
**Create**: `src/main/kotlin/com/orchestrator/context/chunking/OverlapProcessor.kt`

**Implement**:
- `addOverlap(chunks: List<Chunk>, overlapPercent: Int): List<Chunk>`
- Sentence splitter: prefer existing splitter util if present; otherwise basic regex on `. `, `! `, `? ` with fallback to whole chunk when boundaries are ambiguous (abbreviations, ellipses, quotes). Normalize whitespace for Markdown/PDF inputs before splitting.
- `takeLastSentences(text: String, targetTokens: Int): String`
- `takeFirstSentences(text: String, targetTokens: Int): String`
- Token estimation helper (reuse existing `estimateTokens`)

**Logic**:
```kotlin
For each chunk[i]:
  - Extract last N sentences from chunk[i-1] (if exists)
  - Extract first M sentences from chunk[i+1] (if exists)
  - Prepend/append to chunk[i].content
  - overlapTargetTokensFromPercent = round(originalTokens * overlapPercent / 100)
  - Cap overlapped chunk tokens at min(originalTokens * 2, originalTokens + overlapTargetTokensFromPercent)
  - Preserve existing ids/positions/offsets; no schema changes
```

**Edge cases**: 
- Single chunk: no overlap
- First/last chunk: only overlap on one side
- Short/empty/whitespace-only neighbor chunks: skip adding overlap
- Single-sentence chunks: reuse full sentence (no splitting)
- Do not exceed token cap rule above; if neighbor is tiny, only take available sentences
- Preserve chunk ordering and existing offsets; overlapped text is only for retrieval content, not for re-indexing offsets

---

### 2. Update Existing Chunkers
**Modify**: `JavaChunker`, `KotlinChunker`, `PythonChunker`, `MarkdownChunker`, `PdfChunker`, etc.

**Change**:
```kotlin
override fun chunk(content: String, filePath: String): List<Chunk> {
    val baseChunks = [existing chunking logic]
    return OverlapProcessor.addOverlap(baseChunks, overlapPercent)
}
```

**Files to update**:
- `JavaChunker.kt`
- `KotlinChunker.kt`
- `PythonChunker.kt`
- `MarkdownChunker.kt`
- `PdfChunker.kt`
- Any other `SimpleChunker` implementations

---

### 3. Configuration
**Update**: `config/context.example.toml`

**Add**:
```toml
[chunking]
overlap_enabled = true
overlap_percent = 15  # 15% overlap (adjustable 0-50%)
```

**Wire config**: Pass to chunker constructors from registry. Overlap percent is global in config but stored per-chunker instance to allow future per-type tuning. Default: overlap_enabled=false to preserve current behavior when not configured.

---

### 4. Tests
**Create**: `src/test/kotlin/com/orchestrator/context/chunking/OverlapProcessorTest.kt`

**Test cases**:
- Single chunk → no overlap added
- Two chunks → bidirectional overlap
- Three+ chunks → middle chunks get both sides
- Short sentences → don't overflow max tokens (respect cap rule)
- Empty/whitespace chunks → handled gracefully (skipped)
- Token limit respected (overlap doesn't exceed cap)
- Sentence boundary detection (period/exclamation/question) with abbreviations/ellipsis/quotes fallback path
- Idempotency: calling addOverlap twice does not double-append

**Integration/benchmark**:
- Optional/manual: small fixture corpus (e.g., 10–20 docs) with boundary concepts; run retrieval once with overlap on/off and assert overlap improves recall for known boundary query. Keep out of CI or mark as @Disabled if it requires heavy corpus.

---

### 5. Validation
**Run**:
- `./gradlew test` → all tests pass
- Optional manual benchmark: index small corpus (10–20 docs) with overlap enabled/disabled; measure precision@5, recall@10 for 3–5 boundary queries using lightweight harness or scripted queries.

**Success criteria**:
- No chunk size explosions (capped as defined)
- Overlap mode shows qualitative recall improvement on boundary queries in manual check; if benchmarked, target +20–30% recall@10. Performance note: watch for >10% indexing slowdown; log if exceeded.

---

## Deliverables
- ✅ `OverlapProcessor` utility class
- ✅ Updated all chunker implementations
- ✅ Config with enable/disable flag
- ✅ Comprehensive tests (unit + integration)
- ✅ Validation report comparing overlap ON/OFF

**Estimated effort**: 1-2 days  
**Risk**: Low (non-breaking change, feature-flagged)  
**Impact**: High (solves boundary problem directly)

---

## Optional Enhancements (Post-MVP)
- Smart overlap based on chunk type (more for prose, less for code)
- Overlap direction control (backward-only, forward-only, both)
- Adaptive overlap (increase at detected topic boundaries)
