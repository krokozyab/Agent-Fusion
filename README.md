
<img src="pics/agent_banner.png" alt="Agent Fusion Banner" width="100%" style="max-width: 900px; max-height: 240px; display: block; margin: 0 auto;">


[![License](https://img.shields.io/badge/License-Mit-blue.svg?style=for-the-badge&logo=mit)](LICENSE.md)


Agent Fusion MCP tool gives multiple AI coding assistants instant access to your files—code, documents, PDFs, and more—through intelligent indexing. Self-contained deployment: runs as a single JAR with no external APIs, Docker, or cloud dependencies—just Java and a TOML config
It has two independent components (each can be used alone or together):

- **MCP Context Engine: RAG for Code & Documents** – Local semantic search and graph traversal for AI agents. Indexes code, Word docs, PDFs, and Markdown into chunks with embeddings. Query via query_context using three parallel providers: semantic (sentence-transformers), symbol (identifier matching), full-text (keyword search). Returns ranked results with hierarchical relationships encoded as chunk IDs (parent, children, siblings, prev/next). Agents can search semantically, then traverse the knowledge graph to gather surrounding context—useful for understanding class structures, call chains, or document organization. File watcher monitors configured directories and automatically re-indexes changes, so your search index stays current as you code—no manual refresh needed. Two-file deployment (JAR + TOML config), no external dependencies, runs entirely local. Compatible with MCP protocol (works with Claudeany and any MCP-compatible AI agent). Embedding model is configurable. Includes lightweight embedding model; swap for a more powerful model if needed (see [`docs/other_model.md`](docs/other_model.md)).
- **Task Manager** – Optionally coordinates work between multiple AIs. Routes tasks, enables voting on decisions, and tracks everything in a web dashboard.

<img src="pics/home_page.png" alt="Agent Fusion Banner" width="100%" style="max-width: 900px; max-height: 300px; display: block; margin: 0 auto;">



<img src="pics/context_explorer.png" alt="Agent Fusion Banner" width="100%" style="max-width: 900px; max-height: 300px; display: block; margin: 0 auto;">


🎥 **[Watch the demo](https://youtu.be/kXkTh0fJ0Lc)** to see AI assistants collaborating in action.

---

## Why Semantic Search Matters

Most AI coding agents rely on grep and text matching. Ask them "find authentication logic" and they grep for "authentication" — missing every file that actually implements auth but calls it "login", "credentials", "token validation", or "access control".

**Semantic search understands concepts**, not just keywords:

- Ask for "database connection pooling" → finds connection managers, pool handlers, DB initialization
- Ask for "error handling patterns" → discovers try-catch blocks, error loggers, exception handlers
- Ask for "authentication flow" → locates login controllers, JWT validators, session managers

Even when the exact words aren't in the code.

---

## How It Works

Agent Fusion fetches up-to-date code examples and documentation right into your LLM's context:

1️⃣ **Write your prompt naturally** – Ask your AI assistant what you'd normally ask

2️⃣ **Tell the LLM to use query_context** – Just add "use query_context to find X" in your prompt

3️⃣ **Get working code answers** – Instant, accurate answers based on your actual codebase

**No tab-switching. No hallucinated APIs that don't exist. No outdated code generation.**

---

## Quick Start

**[Installation Guide](docs/INSTALL.md)** – Step-by-step setup (takes 5-10 minutes)

---

### How Search Works

The Context Engine uses **three search types combined**:

- **Semantic Search** – AI-powered understanding of meaning (finds "user authentication" when you search "login system")
- **Full-Text Search** – Fast keyword matching (finds exact phrases and terms)
- **Hybrid Search** – Combines all results ranked by relevance (you get the best matches from all methods)

Results are ranked by relevance across all methods, ensuring you find what you're actually looking for — not just keyword matches.

The Context Engine is independent—use it alone for smart search, or combine it with the Task Manager. Configured in `fusionagent.toml`, stores everything locally. Configure watch paths and file types to index in the config file.


## Task Manager: Coordinate Multiple AIs

The **Task Manager** is completely optional. Use it to coordinate work between multiple AIs:

1. **One AI starts a task** – "Design a new authentication system"
2. **The system routes it** – Simple tasks go to one AI, complex tasks go to multiple
3. **AIs collaborate** – They can see each other's ideas, discuss pros/cons
4. **The group decides** – For important decisions, they vote and you see all viewpoints
5. **Everything is tracked** – All proposals and decisions saved with full reasoning

The Task Manager works best when AIs have access to the Context Engine—they stay coordinated. But you can use Task Manager without Context Engine if you prefer traditional task management.

**Use Task Manager when**:
- You want multiple AIs discussing important decisions
- You need voting/consensus on architectural changes
- You want a complete audit trail of AI reasoning

## Architecture: Two Independent Systems

### Context Engine
Intelligent indexing and search for any files (works standalone):
- **Setup**: Configure folders to watch and file types to index in `fusionagent.toml`
- **Indexing**: Automatically finds and indexes your files, watches for changes
- **Supports**: Code (.kt, .py, .ts, .java), documents (.pdf, .docx, .md), and any file type you configure – [See chunking strategies](docs/CHUNKING_STRATEGIES.md)
- **Search**: Smart search that understands meaning, not just keywords (semantic + symbol + full-text + git history)
- **Local**: Everything stored locally in DuckDB, never sent to cloud
- **Agent Access**: Tell your AI agents "use query_context to find X" and they'll search instantly
- **Standalone**: Works independently without Task Manager—great for teams who just need smart file search

### Task Manager
Workflow coordination for multiple AIs (optional addon):
- Routes tasks intelligently (solo vs consensus)
- Enables collaborative decision-making with AI voting
- Tracks all proposals, votes, and final decisions
- Provides web dashboard with real-time updates
- Can be used standalone for traditional task management
