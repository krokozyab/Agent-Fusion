# Search Quality Improvement Plan

This document outlines a pragmatic, phased roadmap to significantly improve search quality and performance across the context subsystem. It is designed for incremental delivery with feature flags, clear acceptance criteria, and measurable outcomes.

## 1) Current State (Baseline)

- Vector semantic search
  - Cosine similarity over dense vectors loaded from DuckDB, thresholded and top-K: `src/main/kotlin/com/orchestrator/context/search/VectorSearchEngine.kt`.
  - Filters by language/kind/path; small doc-language boost.
  - Embeddings stored as JSON text: `context/schema.sql` → `embeddings.vector TEXT`.
- Providers
  - `SemanticContextProvider`: embed query → vector search → MMR rerank → snippets.
  - `FullTextContextProvider`: LIKE-based keyword search with heuristic scoring; no BM25/IDF.
  - `SymbolContextProvider`: heuristic symbol extraction/weighting; no AST; decent recall.
  - `HybridContextProvider`: RRF fusion of provider lists, budget enforcement.
- Query tool
  - `QueryContextTool` aggregates providers, dedups, applies score threshold, then token budget. It does not apply `QueryOptimizer`/MMR today.
- Reranking
  - `QueryOptimizer` + `MmrReranker` exists; used in `ContextRetrievalModule`, not in `QueryContextTool`.

Constraints
- Full table scan of embeddings for every semantic query (CPU + memory heavy as the index grows).
- Non-semantic results lack real vectors, so MMR diversity is limited when mixing providers.
- Full-text relies on LIKE; lacks BM25/IDF, stemming, and phrase matching beyond heuristics.

## 2) Objectives and Non-Goals

Objectives
- Improve top-K precision and recall for both natural language and code-symbol queries.
- Reduce latency and memory of semantic retrieval at scale.
- Provide stable, explainable ranking with metrics and easy rollback.

Non-Goals (for now)
- Distributed/vector DB deployment (can come later). Keep embedded & JVM-local where possible.
- Perfect AST precision across all languages (incremental Tree-sitter adoption later).

## 3) Phased Roadmap

### Phase 1: Quality and Consistency (1–2 days)

Deliverables
- Apply `QueryOptimizer` (MMR) within `QueryContextTool` to all provider results.
- For non-semantic hits (full-text/symbol), embed top M candidates (e.g., M=50) to supply real vectors before MMR.
- Neighbor expansion: include ±N adjacent chunks where budget allows to increase coherence (e.g., N=1 configurable).
- Path/language boosts: configurable post-score adjustments (e.g., boost `src/main/**`, de-boost vendor dirs).
- Minimal IDF weighting in full-text scoring to approximate BM25 without external deps.

Changes
- `src/main/kotlin/com/orchestrator/mcp/tools/QueryContextTool.kt`
  - Convert aggregated snippets → `SearchResult` values.
  - For results lacking vectors, call embedder on snippet.text (batched) and attach vectors.
  - Invoke `QueryOptimizer.optimize(query, results, budget)`; map back to snippets.
  - Feature flag `context.query.useOptimizerInTool`.
- `src/main/kotlin/com/orchestrator/context/search/VectorSearchEngine.kt`
  - Add configurable path/language boosts post dot-product; e.g., `context.query.boosts` config.
  - Add neighbor expansion support flag returning adjacent chunks (enforced later when mapping snippets).
- `src/main/kotlin/com/orchestrator/context/providers/FullTextContextProvider.kt`
  - Maintain/compute per-term document frequency (DF) table (DuckDB side) and apply `tf * idf` scoring.
  - Optional: cache DF in-memory with TTL.

Config additions
- `QueryConfig`:
  - `useOptimizerInTool: Boolean = true`
  - `neighborWindow: Int = 1`
  - `boosts: { pathPrefixes: map<string, double>, languages: map<string, double> }`
- `ProviderConfig.full_text`:
  - `idfEnabled: Boolean = true`

Acceptance Criteria
- Reranking reduces “off-topic” mixed-provider results in top 10 (manual spot-check + sample queries).
- Latency impact ≤ +20% for default `k` on medium repos; still acceptable under budget.
- Unit tests cover rerank flow and neighbor expansion.

### Phase 2: Retrieval Engines (3–5 days)

Deliverables
- Introduce Lucene for BM25 full-text search (embedded index on chunks).
- Switch semantic retrieval to ANN KNN (Lucene HNSW) to avoid full scans.
- Two-stage retrieval: BM25 prefilter → semantic rerank on top-K candidates only.
- Offsets/highlighting in full-text (first match range) to improve snippet fidelity.
- Link-aware expansion: pull small related snippets via `links` table for context glue.

Changes
- Add new module `search/lucene`:
  - Indexer: build/update Lucene index(es) from DuckDB `chunks` and `embeddings`.
  - Query APIs: BM25 (text) + KNN (vectors).
  - Background job to bootstrap/rebuild; wire into existing `RebuildContextTool`.
- `SemanticContextProvider` → switch `searchEngine` from in-memory scan to Lucene KNN.
- `FullTextContextProvider` → swap SQL LIKE for Lucene BM25; return precise offsets for highlighting.
- `HybridContextProvider` → unchanged; inputs now higher quality.

Config additions
- `IndexingConfig`:
  - `luceneDir: String = ".context/lucene"`
  - `luceneKnnEnabled: Boolean = true`
  - `luceneAnalyzer: String = "standard"`
- `EmbeddingConfig`:
  - `writeBinary: Boolean = true` (keeps JSON for backward compat initially)

Acceptance Criteria
- P50 latency improvement for semantic queries (target ≥ 2x faster on medium repos).
- Better lexical recall (BM25) visible on doc/README queries; >20% improvement on sample Recall@10.
- Stable index rebuild/resume and watcher refresh tests pass.

### Phase 3: Reranker + Signals + Eval (as needed)

Deliverables
- ONNX cross-encoder reranker (e.g., bge-reranker) for final ordering of top ~100.
- Optional Tree-sitter symbol extraction to improve precision for methods/classes.
- IR evaluation harness with JSONL of queries and gold chunk IDs; compute Recall@k/NDCG@k.
- Enhanced telemetry: per-stage timings, cache hits, candidate counts.

Changes
- Add `LocalReranker` analogous to `LocalEmbedder` using ONNX Runtime; batch scoring `(query, doc)` pairs.
- Wrap reranker in `QueryOptimizer` as optional final stage.
- Replace `SymbolIndexBuilder` with Tree-sitter-backed builder under feature flag.
- Add `tools` or test runner to execute IR eval against current DB.

Config additions
- `QueryConfig`:
  - `crossEncoderEnabled: Boolean = false`
  - `crossEncoderModelPath: String?`
  - `crossEncoderBatchSize: Int = 32`
- `ProviderConfig.symbol`:
  - `treeSitterEnabled: Boolean = false`

Acceptance Criteria
- Measurable uplift in NDCG@10 on evaluation set (target ≥ +10%).
- Cross-encoder latency overhead controlled (≤ 200ms P50 with batch).

## 4) Detailed Task Breakdown

### Phase 1 Tasks
1. QueryContextTool rerank integration
   - Add `QueryOptimizer` dependency and a small adapter to convert `ContextSnippet` → `SearchResult` (vector may be missing).
   - Batch-embed non-semantic top-M snippet texts using `Embedder` (reuse global embedder)
     - Cache embeddings short-term (LRU by `chunkId` or text hash) to avoid rework.
   - Apply `optimize()`, map back to snippets; preserve metadata and recompute token budget.
   - Feature flag via `QueryConfig.useOptimizerInTool`.

2. Neighbor expansion
   - After final selection, for each snippet include adjacent chunks up to `neighborWindow` if budget allows.
   - Retrieve neighbors via simple SQL by `file_id` + `ordinal`.

3. Path/language boosts
   - Extend `VectorSearchEngine` to adjust `adjustedScore *= pathBoost * languageBoost` using maps from config.
   - Defaults: `src/main` +0.05, `src/test` -0.05, `vendor` -0.1 (tunable).

4. Minimal IDF for full-text
   - Create `full_text_terms(term TEXT PRIMARY KEY, df INTEGER, last_updated TIMESTAMP)` (or compute DF on the fly into temp table per rebuild).
   - Update scoring to `score = sum(tf(term, chunk) * log(N/df))` with simple tokenization.
   - Backfill DF during rebuild; maintain incrementally for modified files.

5. Tests
   - Unit: reranker integration, neighbor expansion boundary cases, path boost logic, IDF scoring sanity.
   - Integration: `QueryContextTool` returns better ordering on controlled fixtures.

### Phase 2 Tasks
1. Lucene BM25 index
   - Add module for Lucene indexers (chunks → text field, path/language fields).
   - Wire into watcher/bootstrap to keep in sync.
   - Replace `FullTextContextProvider` SQL with Lucene queries; return offsets/highlights.

2. ANN KNN for vectors
   - Create Lucene KNN index on embeddings using `KnnFloatVectorField`.
   - Replace `VectorSearchEngine` scan with Lucene KNN top-K.

3. Two-stage retrieval
   - BM25 fetch top N (e.g., 1k) chunk IDs → pull their vectors → ANN rerank, then `QueryOptimizer` MMR.

4. Link-aware expansion
   - For selected snippets, fetch 1–2 related via `links` table (`import/call`) with small additive boost.

5. Tests
   - Performance regression checks (timed tests or microbenchmarks where feasible).
   - Provider correctness with Lucene backed results.

### Phase 3 Tasks
1. Cross-encoder reranker (ONNX)
   - Implement `LocalReranker` with batching and CPU inference; add to optimizer pipeline.

2. Tree-sitter symbols (optional)
   - Swap `SymbolIndexBuilder` behind flag; measure precision improvements on symbol queries.

3. IR Evaluation harness
   - Add `test-data/ir-queries.jsonl` and runner to compute Recall@k/NDCG@k.

## 5) Config and Schema Changes

Config keys to add (proposed)
```toml
[context.query]
useOptimizerInTool = true
neighborWindow = 1
mmrLambda = 0.5          # existing
minScoreThreshold = 0.3  # existing

[context.query.boosts]
# prefix → multiplier (applied post-similarity, clamped)
pathPrefixes = { "src/main" = 1.05, "src/test" = 0.95, "vendor" = 0.90 }
languages = { "kotlin" = 1.02, "markdown" = 1.00 }

[context.providers.full_text]
idfEnabled = true

[context.indexing]
luceneDir = ".context/lucene"
luceneKnnEnabled = true
```

Schema (DuckDB)
- Phase 1: optional `full_text_terms` table for DF.
- Phase 2: optional binary vector column for faster IO (`embeddings.vector_blob BLOB`) while keeping `vector TEXT` for backward compatibility.

## 6) Rollout Strategy and Feature Flags

- Default to safe toggles: enable reranking in the tool (`useOptimizerInTool=true`), neighborWindow=1, conservative boosts.
- Lucene and cross-encoder disabled by default; guarded by config.
- Provide `RebuildContextTool` entry points to rebuild Lucene/DF state.
- Rollback path: toggle flags off; existing SQL-based providers continue to work.

## 7) Metrics, Observability, and Success Criteria

Metrics to record (augment `ContextMetricsRecorder` and `QueryContextTool` metadata):
- Per-stage latency (BM25/ANN/cross-enc), candidate sizes, cache hit rates.
- Final diversity score (average pairwise similarity) and provider contribution mix.

Success criteria
- Phase 1: Visible improvement in top-10 relevance on sample tasks; ≤20% latency hit.
- Phase 2: ≥2x P50 latency improvement on semantic queries; Recall@10 +20% on doc queries.
- Phase 3: NDCG@10 +10% with cross-encoder enabled.

## 8) Risks and Mitigations

- Latency regressions from extra embedding/reranking → mitigate via batching and caps (M=50), plus feature flags.
- Index consistency between DuckDB and Lucene → integrate with watcher and rebuild tools; add health checks.
- ONNX model size and load time → lazy init, cache sessions, document model acquisition.

## 9) Open Questions

- Which repos and query sets should define our IR evaluation baseline?
- Do we prefer Lucene-only versus external vector DBs for larger codebases?
- How aggressive should path/language boosts be by default?

## 10) Milestones and Ownership

- M1 (Phase 1): Rerank-in-tool, neighbor expansion, boosts, IDF. Owner: Context
- M2 (Phase 2): Lucene BM25/KNN + two-stage retrieval. Owner: Context + Infra
- M3 (Phase 3): Cross-encoder reranker, eval harness. Owner: Context + ML

## 11) Acceptance Tests (Examples)

- Given query "PathFilter shouldIgnore", top 5 contain code where `shouldIgnore` is defined and used, not only README hits.
- Given query "authentication JWT token", BM25 results include config and code handlers; semantic rerank prioritizes Kotlin sources over images/docs unless doc query.
- Mixed-provider reranking reduces duplicate or near-duplicate snippets and increases line coverage diversity.

---

Implementation can proceed incrementally per phase; this document is the source of truth for scope, toggles, and acceptance criteria.

