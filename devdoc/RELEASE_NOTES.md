# Release Notes

## Version 1.1.0 - Context Explorer

### New Features

**🔍 Context Explorer - Visual Interface for Context System**

A web-based dashboard that shows you exactly how the context engine works and what it returns when AI agents query your codebase.

**What It Does:**

When AI agents use `query_context` in CLI/MCP mode, you can't see what they're getting back. Context Explorer lets you:

- **See What Agents See** - Run the same queries your AI agents run and inspect the exact results they receive
- **Understand Search Mechanics** - Watch how semantic, symbol, full-text, and git history providers work together and rank results
- **Inspect Indexed Content** - View exactly what chunks were created from your files and how they're stored
- **Debug Query Results** - Test queries before giving them to agents; see why certain code appears in results
- **Monitor Token Usage** - Track how much context each query consumes from your LLM's token budget
- **Verify Indexing** - Confirm your files are indexed correctly with proper chunking and metadata

**Key Features:**

- **Live Query Testing** - Type queries and see real-time results with relevance scores
- **Provider Breakdown** - See which search provider (semantic/symbol/full-text/git) found each result
- **Code Preview** - View indexed code chunks with syntax highlighting and file context
- **Filter Controls** - Test how language and chunk type filters affect results
- **Relevance Scores** - Understand why results are ranked in a specific order

**Access:** Navigate to **Context Explorer** (🔍) in the dashboard menu or visit `http://localhost:8081/explorer`

**Use Cases:**

- **Verify Agent Queries** - Before telling an agent "use query_context to find X", test the query yourself to ensure it returns useful results
- **Debug Missing Results** - If an agent can't find something, use Explorer to see what the context engine actually returns
- **Optimize Queries** - Experiment with different query phrasings to find what works best
- **Understand Indexing** - See how your code is chunked and stored (classes, functions, methods, docs)
- **Monitor System Health** - Verify files are being indexed and search providers are working

### Technical Details

- Direct access to the same context engine AI agents use via MCP
- Real-time query execution with provider-level result breakdown
- Server-side rendering with progressive enhancement
- No additional configuration required

### Why This Matters

In CLI/MCP mode, AI agents query your codebase invisibly—you never see what they're getting back. Context Explorer makes the context system transparent:

- **Visibility** - See exactly what data agents receive when they call `query_context`
- **Control** - Test and refine queries before giving them to agents
- **Debugging** - Understand why agents can or can't find specific code
- **Confidence** - Verify your indexing configuration is working as expected

---

**Upgrade:** Pull latest changes and restart the server. No configuration changes required.
