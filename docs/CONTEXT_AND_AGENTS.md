# Context Spine Guide

## Why the Context Spine Exists
The context spine guarantees every orchestration surface consumes the same project snapshot. It continuously ingests watched roots, normalizes metadata, and persists embeddings plus raw chunks in DuckDB. With a single source of truth, retrieval tooling can answer semantic questions ("where is the chunker config?"), structural ones (symbol search), and raw-text queries without rescanning the filesystem.

## Architectural Overview

| Layer | Responsibilities | Notes |
|-------|------------------|-------|
| **Discovery** | `fusionagent.toml` declares watch roots/ignore patterns; fallback heuristics inspect `.git`, `package.json`, Gradle files when paths are `auto`. | Keeps nested modules aligned without manual glob lists. |
| **Watcher Daemon** | Streams create/update/delete events with debounce controls and honors size/extension allowlists. | Designed to run embedded or as part of a standalone context service. |
| **Chunker Pipeline** | Language-aware chunkers (Javaparser for JVM, YAML/JSON parsers for configs, binary extractors for docs) emit stable chunk IDs and metadata. | Chunk kinds include `CODE_CLASS`, `MARKDOWN_SECTION`, `YAML_BLOCK`, etc. |
| **Embedding & Storage** | Embedding provider generates vectors and writes them plus raw text into DuckDB tables (`file_state`, `chunks`, `embeddings`, `usage_metrics`). | DuckDB also stores indexing telemetry for drift analysis. |
| **Serving Layer** | Semantic, symbol, and raw-text providers answer MCP queries; `/index` dashboard consumes the same APIs via SSE for rebuild progress. | Provider registry follows the SPI model so additional retrieval strategies plug in without core changes. |

### Deployment Modes
- **Embedded** – Context engine runs inside the orchestrator JVM for minimal latency.
- **Standalone** – Engine exposes MCP/HTTP APIs from a separate process; orchestrator connects over the network.
- **Hybrid** – Embedded mode handles queries while a standalone watcher keeps DuckDB synchronized, enabling failover.

### Plugin & Provider Model
- Context providers follow a Java SPI contract (`ContextProvider`) with lifecycle hooks (`initialize`, `query`, `shutdown`).
- The registry discovers providers via `META-INF/services`, mirroring the AgentFactory pattern for consistency.
- Token budgets, scopes, and diversity weights are enforced centrally so queries remain predictable even as providers evolve.

## Data & Control Flow
1. **Discovery & Watch** – Roots are discovered, ignore patterns applied, and change events queued.
2. **Chunk & Embed** – Chunkers transform files; embeddings are generated in batches with optional normalization and caching.
3. **Persist** – DuckDB transactions update `file_state`, `chunks`, `embeddings`, and `links`, recording checksums for later drift detection.
4. **Serve** – MCP tools (`query_context`, `get_context_stats`, `rebuild_context`, etc.) plus the `/index` UI consume the stored data. An MMR re-ranker balances diversity vs relevance before returning snippets.

## Operational Practices
1. **Rebuild & refresh** – Use `rebuild_context` after changing watch roots or chunkers; `refresh_context` suffices for routine scans. Always snapshot `context.duckdb*` before destructive rebuilds.
2. **Monitoring** – `/index` lists filesystem vs catalog counts so mismatches and orphaned files stand out. Pair this with `logs/` and `GetContextStatsTool` when diagnosing slow queries.
3. **Cold storage hygiene** – Treat DuckDB artifacts as append-only; interact through tooling so metadata, embeddings, and usage records remain consistent.
4. **Fallback readiness** – When running standalone mode, keep fallback mode enabled so the orchestrator gracefully degrades rather than failing tasks outright.

## Expanding Coverage
- Amend `fusionagent.toml` to add or remove watch roots, adjust per-language chunk settings, or tweak ignore patterns; follow up with a refresh/rebuild.
- Document new configuration keys or heuristics in `docs/fusionagent_config_docs.md` so other operators mirror the same setup.
- For new languages, add chunkers under `src/main/kotlin/com/orchestrator/context/chunking/...` and back them with tests in `src/test/kotlin` to validate boundaries before indexing large trees.

## Benefits Recap
- **Authoritative snapshot** – Every dashboard, MCP tool, and downstream service consumes the same DuckDB-backed source of truth.
- **Performance** – Pre-chunked embeddings and symbol tables keep context lookups fast, even for multi-repo workspaces.
- **Observability & auditability** – Stored usage metrics, rebuild histories, and SSE progress feeds provide evidence for debugging indexing issues.
- **Deployment flexibility** – Embedded, standalone, and hybrid modes let operators match the spine to their infra constraints without rewriting orchestration logic.
- **Extensibility** – SPI-based providers and pluggable chunkers make it straightforward to incorporate new models, file types, or ranking strategies without touching the control plane.
