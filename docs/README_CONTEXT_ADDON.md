# Context Addon

The Context Addon extends the Task Orchestrator with a fast local knowledge base. It watches the filesystem, chunks source files, stores embeddings in DuckDB, and exposes retrieval tools to every connected agent through MCP.

## What It Delivers
- **Live File Indexing** – a multi-root watcher (`WatcherDaemon`) streams changes into DuckDB with language-aware chunking.
- **Embedding Retrieval** – chunk embeddings power `query_context` and other MCP tools (the addon now runs exclusively in embedding mode).
- **Filesystem Inventory** – `/index` shows catalog counts vs real filesystem counts, highlights mismatches, and lets you rebuild/refresh safely.
- **Bootstrap & Rebuild Pipeline** – `RebuildContextTool` and `RefreshContextTool` orchestrate background indexing with progress SSE.
- **Provider Health** – metrics on which retrieval providers are enabled and their contribution to query results.

## Key Docs
- [Architecture Deep Dive](CONTEXT_ADDON_ARCHITECTURE.md)
- [Implementation Plan](../devdoc/CONTEXT_IMPLEMENTATION_PLAN.md)
- [MCP Tool Reference](MCP_TOOL_QUICK_REFERENCE.md)
- [Sequence Diagrams](SEQUENCE_DIAGRAMS.md) – see indexing & rebuild flows

## Important Components
| Area | Files |
|------|-------|
| Configuration | `config/fusionagent.toml`, `src/main/kotlin/com/orchestrator/context/config` |
| Watcher & scanner | `src/main/kotlin/com/orchestrator/context/watcher` and `.../discovery` |
| Indexing pipeline | `src/main/kotlin/com/orchestrator/context/indexing` |
| Storage & DuckDB | `src/main/kotlin/com/orchestrator/context/storage`, `.../ContextDatabase.kt` |
| MCP tools | `src/main/kotlin/com/orchestrator/mcp/tools` (`QueryContextTool`, `GetContextStatsTool`, etc.) |
| Web index page | `src/main/kotlin/com/orchestrator/web/pages/IndexStatusPage.kt` |

## Operating the Addon
1. Configure watch roots and allowed extensions in `fusionagent.toml`.
2. Start the orchestrator (`./gradlew run`). The watcher performs a startup scan and begins streaming changes.
3. Use `/index` to trigger **Refresh** (incremental) or **Rebuild** (full re-index) and monitor progress via SSE.
4. Agents call MCP tools such as `query_context`, `list_tables_json`, or `get_context_stats` to retrieve context.

## Troubleshooting
- **Mismatch counts** – the dashboard now shows catalog vs filesystem counts with lists of missing/orphaned files.
- **Bootstrap progress errors** – `BootstrapProgressTracker` guarantees tables exist; check `logs/orchestrator.log` for details.
- **Large assets** – File indexer enforces size and extension allowlists; adjust `context.indexing` settings in the config.

## Related Guides
- [INSTALL.md](INSTALL.md) – enabling the addon with the orchestrator
- [WEB_DASHBOARD_ARCHITECTURE.md](WEB_DASHBOARD_ARCHITECTURE.md) – explains the `/index` UX
- [API_REFERENCE.md](API_REFERENCE.md) – HTTP endpoints for triggering refresh/rebuild
