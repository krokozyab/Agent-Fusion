# CLAUDE.md - Agent Fusion Project

## Project Overview

**Agent Fusion** is a Kotlin-based local MCP (Model Context Protocol) stack that enables multiple AI coding agents to collaborate while sharing a rich project index. The project consists of two flagship modules:

- **Task Orchestrator** – multi-agent workflow engine, consensus router, task queue manager, and web dashboard
- **Context Addon** – live filesystem indexer with embeddings, semantic search, and context retrieval tools

### Core Concept

Instead of using a single AI agent for all tasks, this orchestrator:
- Routes tasks to the most suitable agent(s) based on complexity, risk, and capabilities
- Enables consensus workflows where multiple agents review critical decisions
- Optimizes token usage by leveraging each agent's strengths and sharing context
- Provides conversation-based handoff between agents with full state preservation
- Supports extensible plugin architecture for adding new AI models (Claude Code, Codex CLI, Gemini, Amazon Q, etc.)
- Delivers real-time observability through web dashboard with SSE updates

### Architecture Pattern

```
User → Agent (Claude Code/Codex CLI/Gemini/Q) → Orchestrator MCP Server
  ↓
Task Routing → Agent Selection → Consensus (if needed) → Result Aggregation
  ↓
Context Addon (query_context) → Hybrid Search → RRF Fusion → MMR Re-ranking
```

The orchestrator runs as an **HTTP MCP server** that all agents connect to as **MCP clients**. The context addon provides a local knowledge base accessible to all agents.

---

## Technology Stack

### Core Technologies
- **Language**: Kotlin 2.2.10 (JVM)
- **Build**: Gradle 8.11.1 with Kotlin DSL
- **Concurrency**: Kotlin Coroutines & Flows with structured concurrency
- **Serialization**: kotlinx.serialization (JSON + TOML)

### Key Libraries
- **MCP**: `io.modelcontextprotocol:kotlin-sdk` (Kotlin Multiplatform MCP SDK)
- **HTTP Server**: Ktor 3.0.2 (for MCP HTTP transport + web dashboard)
- **Database**: DuckDB 1.1.3 (for both orchestrator and context storage)
- **Database Access**: Raw JDBC (no ORM - manual PreparedStatements for full control)
- **Embeddings**: ONNX Runtime 1.20.1 (local sentence-transformers model)
- **Web UI**: HTMX 2.0.4 + Server-Sent Events for real-time updates

### Why These Choices?
- **DuckDB**: Optimized for analytical queries (metrics, token usage, embeddings), embedded database, no server needed
- **Raw JDBC**: Full control, transparency, no ORM magic, minimal dependencies
- **HTTP MCP**: Universal support across Claude Code, Codex CLI, Gemini Code Assist, Amazon Q
- **Single connection**: No pooling needed for single-user desktop application
- **ONNX**: Local embeddings without API calls, privacy-first, offline capable
- **HTMX + SSE**: Server-driven UI with real-time updates, minimal JavaScript

---

## Project Structure

```
agent-fusion/
│
├── build.gradle.kts
├── settings.gradle.kts
├── fusionagent.toml          # Main configuration file
├── CLAUDE.md (this file)
│
├── docs/
│   ├── INSTALL.md                              # Setup guide
│   ├── AGENT_ORCHESTRATOR_INSTRUCTIONS.md      # Agent playbook
│   ├── README_TASK_ORCHESTRATOR.md             # Task orchestration deep dive
│   ├── README_CONTEXT_ADDON.md                 # Context system deep dive
│   ├── CONVERSATION_HANDOFF_WORKFLOW.md        # Multi-agent workflows
│   ├── TASK_ROUTING_GUIDE.md                   # Routing logic explained
│   ├── MCP_TOOL_QUICK_REFERENCE.md             # MCP tools reference
│   ├── API_REFERENCE.md                        # HTTP + MCP endpoints
│   └── fusionagent_config_docs.md              # Configuration reference
│
├── devdoc/
│   ├── CONTEXT_ADDON_ARCHITECTURE.md           # Context system architecture
│   ├── WEB_DASHBOARD_ARCHITECTURE.md           # Dashboard design
│   ├── context/
│   │   ├── SEARCH_ARCHITECTURE.md              # Search pipeline (RRF + MMR)
│   │   └── QUERY_CONTEXT_PROVIDERS.md          # Search providers
│   └── (extensive development documentation)
│
├── src/
│   ├── main/
│   │   ├── kotlin/
│   │   │   └── com/orchestrator/
│   │   │       │
│   │   │       ├── Main.kt                     # Application entry point
│   │   │       │
│   │   │       ├── core/
│   │   │       │   ├── AgentRegistry.kt        # Active agent registry (from fusionagent.toml)
│   │   │       │   ├── EventBus.kt             # In-process event system for pub/sub
│   │   │       │   ├── StateMachine.kt         # Task workflow state tracking
│   │   │       │   └── WorkflowExecutor.kt     # Base workflow execution logic
│   │   │       │
│   │   │       ├── agents/
│   │   │       │   ├── Agent.kt                # Agent interface
│   │   │       │   ├── CodexCLIAgent.kt        # Codex CLI agent impl
│   │   │       │   ├── ClaudeCodeAgent.kt      # Claude Code agent impl
│   │   │       │   ├── GeminiAgent.kt          # Gemini agent impl
│   │   │       │   └── AmazonQAgent.kt         # Amazon Q agent impl
│   │   │       │
│   │   │       ├── modules/
│   │   │       │   ├── routing/                # Task routing logic
│   │   │       │   │   ├── RoutingModule.kt    # Main routing coordinator
│   │   │       │   │   ├── TaskClassifier.kt   # Classifies task type/complexity
│   │   │       │   │   ├── AgentSelector.kt    # Selects best agent(s)
│   │   │       │   │   └── DirectiveParser.kt  # Parses user routing hints
│   │   │       │   │
│   │   │       │   ├── consensus/              # Consensus management
│   │   │       │   │   ├── ConsensusModule.kt  # Orchestrates consensus workflow
│   │   │       │   │   ├── ProposalManager.kt  # Manages agent proposals
│   │   │       │   │   └── strategies/
│   │   │       │   │       ├── VotingStrategy.kt           # Democratic voting
│   │   │       │   │       ├── ReasoningQualityStrategy.kt # Quality-based selection
│   │   │       │   │       └── CustomStrategy.kt           # Extensible custom logic
│   │   │       │   │
│   │   │       │   ├── context/                # Context Module API
│   │   │       │   │   └── ContextModule.kt    # Public interface to context addon
│   │   │       │   │
│   │   │       │   └── metrics/                # Metrics & observability
│   │   │       │       ├── MetricsModule.kt    # Token usage, task metrics
│   │   │       │       └── AlertManager.kt     # Threshold alerts
│   │   │       │
│   │   │       ├── workflows/
│   │   │       │   ├── ConsensusWorkflow.kt    # Multi-agent consensus workflow
│   │   │       │   ├── SoloWorkflow.kt         # Single-agent workflow
│   │   │       │   └── HandoffWorkflow.kt      # Agent-to-agent handoff
│   │   │       │
│   │   │       ├── mcp/
│   │   │       │   ├── McpServerImpl.kt        # HTTP MCP server implementation
│   │   │       │   └── tools/                  # MCP tool implementations
│   │   │       │       ├── CreateSimpleTaskTool.kt         # Solo task creation
│   │   │       │       ├── CreateConsensusTaskTool.kt      # Consensus task creation
│   │   │       │       ├── AssignTaskTool.kt               # Direct agent assignment
│   │   │       │       ├── GetPendingTasksTool.kt          # Task inbox
│   │   │       │       ├── ContinueTaskTool.kt             # Load task context
│   │   │       │       ├── RespondToTaskTool.kt            # Submit response (one-shot)
│   │   │       │       ├── SubmitInputTool.kt              # Submit proposal
│   │   │       │       ├── CompleteTaskTool.kt             # Mark task done
│   │   │       │       ├── GetTaskStatusTool.kt            # Check task status
│   │   │       │       ├── QueryContextTool.kt             # Semantic code search
│   │   │       │       ├── GetContextStatsTool.kt          # Index statistics
│   │   │       │       ├── RefreshContextTool.kt           # Incremental re-index
│   │   │       │       ├── RebuildContextTool.kt           # Full re-index
│   │   │       │       └── GetRebuildStatusTool.kt         # Check rebuild progress
│   │   │       │
│   │   │       ├── context/                    # Context Addon Implementation
│   │   │       │   ├── config/
│   │   │       │   │   ├── ContextConfig.kt                # Configuration model
│   │   │       │   │   └── ContextConfigLoader.kt          # TOML loader
│   │   │       │   │
│   │   │       │   ├── discovery/              # Filesystem scanning
│   │   │       │   │   ├── DirectoryScanner.kt             # Recursive file discovery
│   │   │       │   │   ├── PathValidator.kt                # Security & filter checks
│   │   │       │   │   ├── PathFilter.kt                   # Ignore patterns (gitignore)
│   │   │       │   │   ├── ExtensionFilter.kt              # Allowed file extensions
│   │   │       │   │   ├── SkipFilter.kt                   # Skip patterns
│   │   │       │   │   ├── SymlinkHandler.kt               # Safe symlink resolution
│   │   │       │   │   └── BinaryDetector.kt               # Binary file detection
│   │   │       │   │
│   │   │       │   ├── watcher/                # Filesystem monitoring
│   │   │       │   │   ├── WatcherDaemon.kt                # Main watcher coordinator
│   │   │       │   │   ├── FileWatcher.kt                  # NIO WatchService wrapper
│   │   │       │   │   └── WatcherRegistry.kt              # Global watcher instance
│   │   │       │   │
│   │   │       │   ├── indexing/               # Indexing pipeline
│   │   │       │   │   ├── FileIndexer.kt                  # Per-file chunking + embedding
│   │   │       │   │   ├── BatchIndexer.kt                 # Batch processing
│   │   │       │   │   ├── IncrementalIndexer.kt           # Incremental updates
│   │   │       │   │   ├── ChangeDetector.kt               # Detect file changes
│   │   │       │   │   └── Chunker.kt                      # Language-aware chunking
│   │   │       │   │
│   │   │       │   ├── embedding/              # Embedding generation
│   │   │       │   │   ├── Embedder.kt                     # Embedder interface
│   │   │       │   │   └── LocalEmbedder.kt                # ONNX-based local embedder
│   │   │       │   │
│   │   │       │   ├── storage/                # DuckDB storage
│   │   │       │   │   ├── ContextDatabase.kt              # Connection manager
│   │   │       │   │   └── ContextDatabaseSchema.kt        # Schema DDL
│   │   │       │   │
│   │   │       │   ├── providers/              # Search providers
│   │   │       │   │   ├── ContextProvider.kt              # Provider interface
│   │   │       │   │   ├── SemanticContextProvider.kt      # Vector search
│   │   │       │   │   ├── SymbolContextProvider.kt        # Symbol index search
│   │   │       │   │   ├── FullTextContextProvider.kt      # BM25 keyword search
│   │   │       │   │   ├── GitHistoryProvider.kt           # Git commit search
│   │   │       │   │   └── HybridContextProvider.kt        # Coordinates all providers
│   │   │       │   │
│   │   │       │   ├── search/                 # Search algorithms
│   │   │       │   │   ├── VectorSearchEngine.kt           # Cosine similarity search
│   │   │       │   │   ├── RrfFusion.kt                    # Reciprocal Rank Fusion
│   │   │       │   │   ├── MmrReranker.kt                  # Maximal Marginal Relevance
│   │   │       │   │   └── BM25SearchEngine.kt             # BM25 scoring
│   │   │       │   │
│   │   │       │   └── bootstrap/              # Initial indexing
│   │   │       │       ├── BootstrapIndexer.kt             # Full codebase scan
│   │   │       │       ├── BootstrapProgressTracker.kt     # Progress tracking
│   │   │       │       └── StartupReconciler.kt            # Startup sync
│   │   │       │
│   │   │       ├── web/                        # Web Dashboard
│   │   │       │   ├── WebServer.kt            # Ktor server setup
│   │   │       │   ├── routes/                 # HTTP routes
│   │   │       │   │   ├── HomeRoutes.kt                   # Dashboard home
│   │   │       │   │   ├── TaskRoutes.kt                   # Task management
│   │   │       │   │   ├── IndexRoutes.kt                  # Context index status
│   │   │       │   │   └── SSERoutes.kt                    # Server-Sent Events
│   │   │       │   │
│   │   │       │   ├── pages/                  # HTMX page generators
│   │   │       │   │   ├── IndexStatusPage.kt              # Index dashboard
│   │   │       │   │   └── TaskListPage.kt                 # Task list UI
│   │   │       │   │
│   │   │       │   ├── sse/                    # Real-time updates
│   │   │       │   │   ├── SSEManager.kt                   # SSE connection manager
│   │   │       │   │   ├── SSEConnection.kt                # Single connection
│   │   │       │   │   ├── SSEEvent.kt                     # Event types
│   │   │       │   │   └── FragmentGenerator.kt            # HTML fragment generator
│   │   │       │   │
│   │   │       │   └── services/               # Web services
│   │   │       │       ├── IndexOperationsService.kt       # Trigger refresh/rebuild
│   │   │       │       └── FilesystemSnapshotCalculator.kt # Filesystem snapshot
│   │   │       │
│   │   │       ├── storage/                    # Orchestrator storage
│   │   │       │   ├── Database.kt             # Main DuckDB connection
│   │   │       │   └── repositories/           # Repository layer
│   │   │       │       ├── TaskRepository.kt
│   │   │       │       ├── ProposalRepository.kt
│   │   │       │       ├── DecisionRepository.kt
│   │   │       │       └── MessageRepository.kt
│   │   │       │
│   │   │       ├── domain/                     # Domain models
│   │   │       │   ├── Task.kt
│   │   │       │   ├── Proposal.kt
│   │   │       │   ├── Decision.kt
│   │   │       │   ├── Agent.kt
│   │   │       │   ├── TaskStatus.kt
│   │   │       │   ├── RoutingStrategy.kt
│   │   │       │   └── InputType.kt
│   │   │       │
│   │   │       ├── config/                     # Configuration
│   │   │       │   └── ConfigLoader.kt         # fusionagent.toml loader
│   │   │       │
│   │   │       └── utils/
│   │   │           ├── Logger.kt               # Logging facade
│   │   │           └── TokenEstimator.kt       # Token counting
│   │   │
│   │   └── resources/
│   │       ├── static/                         # Web UI assets
│   │       │   ├── css/
│   │       │   ├── js/
│   │       │   └── index.html
│   │       │
│   │       └── embedding-models/               # ONNX models
│   │           └── all-MiniLM-L6-v2.onnx       # Sentence transformer
│   │
│   └── test/
│       └── kotlin/
│           └── com/orchestrator/
│               ├── integration/                # Integration tests
│               │   ├── ConsensusTaskIntegrationTest.kt
│               │   └── ContextAddonIntegrationTest.kt
│               │
│               └── workflows/                  # Workflow tests
│                   └── ConsensusWorkflowTest.kt
│
└── data/                                       # Runtime data (generated)
    ├── orchestrator.db                         # Main database
    └── context.db                              # Context index database
```

---

## Core Features

### 1. Task Orchestration & Routing

The routing module intelligently assigns tasks to agents based on:
- **Task Classification**: Type (ARCHITECTURE, IMPLEMENTATION, BUGFIX, REVIEW, etc.), complexity (1-10), risk (1-10)
- **Agent Capabilities**: Each agent has capability scores (0.0-1.0) for different task types
- **User Directives**: Natural language hints parsed from task descriptions:
  - `forceConsensus`: "critical", "important", "get consensus", "need both agents"
  - `preventConsensus`: "solo", "skip consensus", "emergency", "urgent"
  - `assignToAgent`: "ask Codex", "Claude should", agent name mentions
  - `immediate`: "now", "ASAP", "urgent"

**Routing Decision Matrix**:
```
IF user explicitly assigns agent → ASSIGNED routing
ELSE IF preventConsensus OR (complexity ≤ 6 AND risk ≤ 5) → SOLO routing
ELSE IF forceConsensus OR (complexity ≥ 7 OR risk ≥ 7) → CONSENSUS routing
ELSE use agent capabilities + task type to select SOLO or CONSENSUS
```

### 2. Consensus Workflows

When multiple agents collaborate on a task:

1. **Proposal Collection**: Each assigned agent submits a `Proposal` with:
   - Content (solution, design, analysis)
   - InputType (ARCHITECTURAL_PLAN, CODE_REVIEW, IMPLEMENTATION_PLAN, etc.)
   - Confidence score (0.0-1.0)
   - Token usage tracking

2. **Consensus Strategies** (executed in order until agreement):
   - **VotingStrategy**: Democratic voting based on confidence scores
   - **ReasoningQualityStrategy**: Evaluates proposal depth, completeness, risk awareness
   - **CustomStrategy**: Extensible for domain-specific logic

3. **Decision Recording**: `DecisionRepository` stores:
   - All proposals considered
   - Selected proposal(s) with rationale
   - Agreement rate
   - Token metrics

4. **State Transitions**:
   ```
   PENDING → IN_PROGRESS → WAITING_INPUT → COMPLETED
   ```

### 3. Context Addon - Local Knowledge Base

The context addon provides **semantic code search** without external API calls:

#### Architecture
```
File Change → WatcherDaemon → IncrementalIndexer → FileIndexer
   ↓                                                    ↓
Debounced events                              Chunker (language-aware)
                                                       ↓
                                              LocalEmbedder (ONNX)
                                                       ↓
                                              ContextDatabase (DuckDB)
```

#### Search Pipeline (query_context)

Multi-stage pipeline for relevance + diversity:

```
Agent query → HybridContextProvider
   ↓
Parallel fan-out to:
   ├─ SemanticContextProvider    (vector cosine similarity)
   ├─ SymbolContextProvider       (exact symbol matches)
   ├─ FullTextContextProvider     (BM25 keyword search)
   └─ GitHistoryProvider          (commit history search)
   ↓
RrfFusion (Reciprocal Rank Fusion)
   Formula: score = 1/(k + rank₁) + 1/(k + rank₂) + ...
   Purpose: "Wisdom of crowds" - results ranked high by multiple providers win
   ↓
MmrReranker (Maximal Marginal Relevance)
   Formula: MMR = λ·sim(query, doc) - (1-λ)·max sim(doc, selected)
   Purpose: Balance relevance vs diversity - avoid redundant results
   ↓
Final ranked results (diverse + relevant)
```

#### Key Features
- **Live Indexing**: Watches filesystem, re-indexes on file changes with debouncing
- **Language-Aware Chunking**: Splits code by function/class/module boundaries
- **Local Embeddings**: ONNX runtime with `sentence-transformers/all-MiniLM-L6-v2` (384 dimensions)
- **Ignore Patterns**: Respects `.gitignore`, `.contextignore`, `.dockerignore`
- **Size Limits**: Configurable max file size with per-file exceptions
- **Incremental Updates**: Only re-indexes changed files
- **Bootstrap & Rebuild**: Full codebase indexing with progress tracking
- **Multi-Provider Search**: Semantic + symbol + full-text + git history

### 4. Web Dashboard

Real-time observability interface powered by Ktor + HTMX + SSE:

#### Features
- **Live Task Queue**: See all tasks, proposals, decisions with filtering
- **Agent Status**: Online/offline status, capabilities, token usage
- **Context Index Status**:
  - Indexed files count vs filesystem count
  - Mismatch detection (missing/orphaned files)
  - Provider health (enabled/disabled, query contribution)
  - Refresh/rebuild triggers with progress bars
- **Real-Time Updates**: Server-Sent Events for live updates:
  - Task status changes
  - Index progress during refresh/rebuild
  - Agent activity
- **Metrics Dashboard**: Token usage, task completion rates, consensus outcomes

#### SSE Event Types
```kotlin
enum class SSEEventType {
    CONNECTED,      // Initial connection established
    MESSAGE,        // General updates (task changes, etc.)
    KEEP_ALIVE,     // Heartbeat every 30s
    DISCONNECTED,   // Connection closed
    ERROR          // Error notifications
}
```

#### Key Routes
- `GET /` - Dashboard home with activity feed
- `GET /tasks` - Task list with proposals
- `GET /tasks/:id` - Task detail view
- `GET /index` - Context index status & controls
- `GET /sse/events` - Server-Sent Events stream
- `POST /api/index/refresh` - Trigger incremental re-index
- `POST /api/index/rebuild` - Trigger full rebuild (with confirmation)

---

## Configuration (fusionagent.toml)

The entire system is configured via a single TOML file:

### `[orchestrator.server]`
```toml
[orchestrator.server]
host = "127.0.0.1"          # MCP server host
port = 3000                  # MCP server port
```

### `[web]`
```toml
[web]
host = "0.0.0.0"            # Web dashboard host
port = 8081                  # Web dashboard port
auto_launch_browser = true   # Auto-open browser on startup

[web.cors]
enabled = true
allowed_origins = ["http://localhost:*"]
```

### `[agents.<id>]`
```toml
[agents.claude-code]
type = "CLAUDE_CODE"
name = "Claude Code"
model = "claude-sonnet-4-5-20250929"
# apiKeyRef not needed - Claude Desktop provides auth

[agents.codex-cli]
type = "CODEX_CLI"
name = "Codex CLI"
model = "gpt-4o"
apiKeyRef = "OPENAI_API_KEY"  # Environment variable
organization = "org-..."       # Optional
temperature = 0.7
max_tokens = 4000
```

### `[context]` - Context Addon
```toml
[context]
enabled = true

[context.storage]
db_path = "data/context.db"  # DuckDB file path

[context.watcher]
enabled = true
debounce_ms = 500                # Debounce file system events
watch_paths = ["auto"]           # "auto" = detect project root, or explicit paths
ignore_patterns = [
    "node_modules/",
    ".git/",
    "build/",
    "*.log"
]
use_gitignore = true             # Respect .gitignore
use_contextignore = true         # Respect .contextignore (loaded first)
use_dockerignore = true          # Respect .dockerignore

[context.indexing]
allowed_extensions = ["kt", "java", "py", "js", "ts", "md", ...]
blocked_extensions = ["exe", "dll", "so", "dylib"]
max_file_size_mb = 10            # Skip files larger than this
warn_file_size_mb = 2            # Warn when indexing large files
size_exceptions = [              # Files exempt from size limit
    "large-data.json",
    ".svg"  # extension-based exception
]
skip_patterns = [                # Skip even if extension matches
    "**/test/**",
    "**/__pycache__/**"
]

[context.embedding]
model = "sentence-transformers/all-MiniLM-L6-v2"  # ONNX model name
model_path = "src/main/resources/embedding-models/all-MiniLM-L6-v2.onnx"
dimension = 384                  # Embedding vector size
normalize = true                 # L2 normalize embeddings
batch_size = 32                  # Batch embedding for performance

[context.query]
default_k = 10                   # Default results per query
min_score_threshold = 0.0        # Minimum relevance score
token_budget = 4000              # Max tokens in results
```

---

## MCP Tools Reference

### Task Management Tools

#### `create_simple_task`
Create a task for single-agent execution (SOLO routing by default).

**Use when**: Routine tasks, low complexity/risk, user says "solo" or "skip consensus"

```json
{
  "title": "Fix null pointer bug in UserService",
  "description": "User login fails with NPE on line 42",
  "type": "BUGFIX",
  "complexity": 5,
  "risk": 3,
  "directives": {
    "skipConsensus": true,
    "immediate": false
  }
}
```

#### `create_consensus_task`
Create a task requiring multiple agents (CONSENSUS routing).

**Use when**: Critical decisions, high complexity/risk, user says "need consensus", "get both agents"

```json
{
  "title": "Design authentication system",
  "description": "Multi-factor auth with OAuth2, needs architectural review",
  "type": "ARCHITECTURE",
  "complexity": 8,
  "risk": 9,
  "directives": {
    "forceConsensus": true
  }
}
```

#### `assign_task`
Directly assign a task to a specific agent (ASSIGNED routing).

**Use when**: User explicitly names agent ("Codex should", "ask Claude")

```json
{
  "title": "Review security implications",
  "targetAgent": "codex-cli",
  "description": "Security audit of auth module",
  "type": "REVIEW"
}
```

#### `get_pending_tasks`
Check your task inbox (assigned to you).

**Always call at session start!** This discovers work from other agents.

```json
{
  "agentId": "claude-code",  // Optional, defaults to you
  "statuses": ["PENDING", "IN_PROGRESS"]
}
```

Returns:
```json
{
  "agentId": "claude-code",
  "count": 3,
  "tasks": [
    {
      "id": "task-123",
      "title": "Fix auth bug",
      "status": "PENDING",
      "type": "BUGFIX",
      "priority": "HIGH",
      "contextPreview": "User login fails..."
    }
  ]
}
```

#### `respond_to_task` (Recommended - One-Shot)
Load task context + submit response in a single operation.

**Use when**: Ready to respond immediately without separate analysis step.

```json
{
  "taskId": "task-123",
  "response": {
    "content": "Fixed NPE by adding null check on line 42...",
    "inputType": "IMPLEMENTATION_PLAN",
    "confidence": 0.85
  }
}
```

#### `continue_task` (Advanced)
Load full task context (proposals, history, files) without submitting yet.

**Use when**: Need to analyze before deciding to submit.

```json
{
  "taskId": "task-123",
  "maxTokens": 8000  // Optional context size limit
}
```

Returns task + all proposals + conversation history + file operations.

#### `submit_input`
Submit your proposal to a task (use after `continue_task`).

```json
{
  "taskId": "task-123",
  "content": {
    "approach": "Refactor UserService to use dependency injection",
    "tradeoffs": "More complexity, better testability"
  },
  "inputType": "ARCHITECTURAL_PLAN",
  "confidence": 0.8
}
```

#### `complete_task`
Mark task as done with final decision record.

**Only the task creator or lead agent should call this.**

```json
{
  "taskId": "task-123",
  "resultSummary": "Bug fixed, tests passing, deployed to staging",
  "decision": {
    "considered": [...],      // All proposals
    "selected": ["prop-456"], // Winner(s)
    "rationale": "Most comprehensive solution with best test coverage"
  }
}
```

#### `get_task_status`
Quick status check without loading full context.

```json
{
  "taskId": "task-123"
}
```

### Context Tools

#### `query_context` (Primary Search Tool)
Semantic code search using hybrid provider pipeline.

**Best for**: Finding code examples, understanding concepts, locating implementations.

```json
{
  "query": "authentication JWT token validation",  // 2-5 keywords (NOT questions!)
  "k": 20,                      // Max results
  "maxTokens": 6000,            // Token budget
  "paths": ["src/main/"],       // Optional path filters
  "languages": ["kotlin"],      // Optional language filters
  "kinds": ["CODE_CLASS", "CODE_FUNCTION"],  // Optional chunk type filters
  "excludePatterns": ["test/", "*.md"]       // Optional exclusions
}
```

**Query Tips**:
- Use SHORT keywords like grep: "task consensus workflow", "proposal manager repository"
- NOT questions: ❌ "how does authentication work?" ✅ "authentication JWT validate"
- NOT natural language: ❌ "show me all auth code" ✅ "authentication token"

Returns:
```json
{
  "hits": [
    {
      "chunkId": 123,
      "score": 0.85,
      "filePath": "/src/main/kotlin/com/orchestrator/auth/JwtValidator.kt",
      "kind": "CODE_CLASS",
      "text": "class JwtValidator...",
      "language": "kotlin",
      "offsets": [42, 67],  // Line range
      "metadata": {
        "provider": "semantic",
        "model": "sentence-transformers/all-MiniLM-L6-v2",
        "score": "0.850"
      }
    }
  ],
  "metadata": {
    "totalHits": 45,
    "tokensUsed": 2340,
    "providers": {
      "semantic": { "snippets": 20 },
      "symbol": { "snippets": 15 },
      "full_text": { "snippets": 10 }
    }
  }
}
```

#### `get_context_stats`
Index statistics and provider health.

```json
{}  // No parameters
```

Returns:
```json
{
  "storage": {
    "fileCount": 1234,
    "chunkCount": 5678,
    "embeddingCount": 5678
  },
  "providerStatus": [
    { "id": "semantic", "enabled": true, "type": "SEMANTIC" },
    { "id": "symbol", "enabled": true, "type": "SYMBOL" },
    { "id": "fulltext", "enabled": true, "type": "FULL_TEXT" }
  ],
  "languageDistribution": {
    "kotlin": 456,
    "java": 123,
    "markdown": 89
  }
}
```

#### `refresh_context`
Trigger incremental re-index (only changed files).

```json
{
  "paths": null,       // null = all watched paths
  "force": false,      // true = re-index even if unchanged
  "async": true        // true = background, false = blocking
}
```

Returns immediately if `async=true` with `jobId` for tracking.

#### `rebuild_context`
Full rebuild (DESTRUCTIVE - clears all data).

**Requires `confirm: true` to execute!**

```json
{
  "confirm": true,     // REQUIRED safety check
  "paths": null,       // null = all watched paths
  "async": true,       // true = background
  "validateOnly": false  // true = dry-run (no execution)
}
```

#### `get_rebuild_status`
Check async rebuild progress.

```json
{
  "jobId": "rebuild-abc123",
  "includeLogs": false
}
```

---

## Workflow Patterns

### Pattern 1: Solo Task (Simple Workflow)
Agent handles task independently without other agents.

```
User → Agent: "Fix bug in UserService"
  ↓
Agent → query_context("UserService bug null pointer")
  ↓
Agent → create_simple_task({title, description, type: BUGFIX})
  ↓
Orchestrator → Routes to SOLO (low complexity)
  ↓
Agent → Implements fix
  ↓
Agent → complete_task({result, tests_passed: true})
```

### Pattern 2: Consensus Workflow (Recommended for Critical Tasks)
Multiple agents collaborate, proposals are collected, consensus reached.

```
User → Agent A: "Design authentication system (critical)"
  ↓
Agent A → query_context("authentication OAuth2 security")
  ↓
Agent A → create_consensus_task({title, forceConsensus: true})
  ↓
Orchestrator → Routes to CONSENSUS → Assigns Agent A + Agent B
  ↓
Agent A → respond_to_task({content: "OAuth2 design", inputType: ARCHITECTURAL_PLAN})
  ↓
User switches to Agent B
  ↓
Agent B → get_pending_tasks() → Discovers task-123
  ↓
Agent B → continue_task("task-123") → Loads Agent A's proposal
  ↓
Agent B → respond_to_task({content: "Alternative design", inputType: ARCHITECTURAL_PLAN})
  ↓
User switches back to Agent A (or Orchestrator auto-triggers)
  ↓
Orchestrator → ConsensusModule.decide() → VotingStrategy + ReasoningQualityStrategy
  ↓
Orchestrator → Creates Decision record (winner: Agent A's proposal, agreement: 75%)
  ↓
Agent A (or User) → complete_task({decision summary})
```

### Pattern 3: Agent Handoff
One agent starts, explicitly hands off to another.

```
User → Agent A (Claude Code): "Implement feature X, then have Codex review"
  ↓
Agent A → create_simple_task({title: "Implement X"})
  ↓
Agent A → Implements feature
  ↓
Agent A → assign_task({title: "Review implementation", targetAgent: "codex-cli"})
  ↓
User switches to Agent B (Codex CLI)
  ↓
Agent B → get_pending_tasks() → Finds review task
  ↓
Agent B → continue_task(task-id) → Reviews code
  ↓
Agent B → respond_to_task({content: "Review feedback", inputType: CODE_REVIEW})
  ↓
User switches back to Agent A
  ↓
Agent A → Addresses feedback → complete_task()
```

---

## Critical Architecture Decisions

### Why DuckDB for Both Databases?
- **Embedded**: No server setup, single file per database
- **Analytical**: Optimized for aggregations (metrics, token usage), handles embeddings well
- **ACID**: Full transactions despite being embedded
- **Vector Support**: Native array type for embeddings with fast operations
- **SQL-based**: Familiar query language, no ORM learning curve

### Why Raw JDBC (No ORM)?
- **Transparency**: See exact SQL, no magic queries
- **Control**: Full control over transactions, batching, prepared statements
- **Performance**: No ORM overhead, direct JDBC performance
- **Debugging**: Easy to debug - just read the SQL strings
- **Simplicity**: No ORM configuration, annotations, or entity management

### Why Local Embeddings (ONNX)?
- **Privacy**: No code sent to external APIs
- **Speed**: No network latency, instant results
- **Cost**: Zero per-query cost
- **Offline**: Works without internet
- **Control**: Pin model version, no API changes

### Why HTMX + SSE for Web UI?
- **Server-Driven**: Logic on server, HTML fragments sent to client
- **Minimal JS**: No complex frontend framework
- **Real-Time**: SSE for live updates without WebSocket complexity
- **SEO-Friendly**: Server-rendered HTML
- **Simple**: No build step, no bundler, no npm dependencies

### Why Kotlin Coroutines?
- **Structured Concurrency**: Automatic cancellation, no leaked threads
- **Readability**: Sequential code for async operations
- **Backpressure**: Flows handle backpressure naturally
- **Integration**: Native support in Ktor, excellent for I/O

---

## Agent Capabilities Matrix

| Capability | Claude Code | Codex CLI | Gemini | Amazon Q |
|-----------|-------------|-----------|--------|----------|
| **Implementation** | 0.95 | 0.90 | 0.85 | 0.80 |
| **Architecture** | 0.80 | 0.95 | 0.75 | 0.70 |
| **Code Review** | 0.90 | 0.85 | 0.80 | 0.85 |
| **Refactoring** | 0.95 | 0.80 | 0.75 | 0.70 |
| **Bug Fixing** | 0.90 | 0.85 | 0.80 | 0.85 |
| **Testing** | 0.85 | 0.90 | 0.80 | 0.75 |
| **Documentation** | 0.90 | 0.85 | 0.85 | 0.80 |
| **Data Analysis** | 0.70 | 0.95 | 0.90 | 0.75 |

Scores are 0.0-1.0, where 1.0 = strongest capability. Used by RoutingModule for agent selection.

---

## Development Setup

### Prerequisites
- JDK 21+
- Gradle 8.11.1+
- DuckDB JDBC driver (auto-downloaded)

### Build & Run
```bash
# Build project
./gradlew build

# Run orchestrator
./gradlew run

# Run with custom config
./gradlew run --args="-a /path/to/fusionagent.toml"

# Run tests
./gradlew test

# Integration tests
./gradlew integrationTest
```

### First-Time Setup
1. Copy `fusionagent.toml.example` to `fusionagent.toml`
2. Configure agents (API keys in environment variables)
3. Configure context addon (watch paths, extensions)
4. Run `./gradlew run`
5. Open http://localhost:8081 (web dashboard)
6. Configure Claude Desktop / Codex CLI to connect to `http://127.0.0.1:3000/mcp`

### Database Files
- `data/orchestrator.db` - Task queue, proposals, decisions
- `data/context.db` - File chunks, embeddings, search index

### Logs
- `logs/orchestrator.log` - Application logs
- Web dashboard console - SSE events

---

## Troubleshooting

### Context Index Mismatches
**Problem**: Dashboard shows files in database but not on filesystem (or vice versa).

**Solution**:
1. Check `/index` page for mismatch details
2. Click "Refresh Index" for incremental sync
3. If corruption suspected, use "Rebuild Index" (confirm required)

### Agent Not Following Workflow
**Problem**: Agent completes tasks without analyzing proposals or synthesizing results.

**Solution**: Ensure agent has read [AGENT_ORCHESTRATOR_INSTRUCTIONS.md](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md). Key rules:
- For consensus tasks: Submit your analysis FIRST via `respond_to_task`
- Wait for other agents, load all proposals with `continue_task`
- Synthesize all proposals before calling `complete_task`

### SSE Connection Issues
**Problem**: Web dashboard not showing real-time updates.

**Solution**:
1. Check browser console for SSE errors
2. Verify no proxy/firewall blocking SSE
3. Check `logs/orchestrator.log` for SSE connection logs
4. Try refresh page (SSE auto-reconnects)

### Query Context Returns No Results
**Problem**: `query_context` returns empty even though files exist.

**Solution**:
1. Check index status: `get_context_stats`
2. Verify files are in watched paths (not ignored)
3. Check file extensions are in `allowed_extensions`
4. Try shorter query (2-5 keywords, not natural language)
5. Trigger `refresh_context` to update index

### Embedding Model Not Found
**Problem**: "ONNX model not found" error on startup.

**Solution**:
1. Verify `context.embedding.model_path` points to valid `.onnx` file
2. Download model: [all-MiniLM-L6-v2.onnx](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
3. Place in `src/main/resources/embedding-models/`
4. Or set `model_path` to absolute path

---

## Glossary

- **Agent**: AI model/assistant (Claude Code, Codex CLI, Gemini, Amazon Q)
- **MCP**: Model Context Protocol (standard for AI-tool communication)
- **Orchestrator**: This system (coordinates multiple agents)
- **Task**: Work unit requiring agent(s) input
- **Proposal**: Agent's suggested solution to a task
- **Consensus**: Agreement between multiple agents via voting/quality strategies
- **Routing**: Deciding which agent(s) should handle a task (SOLO, CONSENSUS, ASSIGNED)
- **Handoff**: Transferring task context between agents
- **Context Addon**: Local knowledge base with semantic search
- **query_context**: MCP tool for searching indexed codebase
- **Chunk**: Code snippet indexed separately (function, class, paragraph)
- **Embedding**: Vector representation of code/text (384 dimensions)
- **RRF**: Reciprocal Rank Fusion (merges provider results)
- **MMR**: Maximal Marginal Relevance (re-ranks for diversity)
- **SSE**: Server-Sent Events (real-time web updates)
- **HTMX**: HTML over the wire (server-driven UI)
- **Capability**: What an agent can do (implementation, review, architecture)
- **Strength**: How well an agent does something (0.0-1.0 score)
- **User Directive**: Natural language hint from user about routing preference
- **Force Consensus**: User explicitly requests multi-agent collaboration
- **Prevent Consensus**: User bypasses consensus (e.g., emergency, preference)
- **Emergency Bypass**: User skips normal workflow due to production issue
- **Agent Assignment**: User specifies which agent should handle task
- **Bootstrap**: Initial full codebase indexing
- **Refresh**: Incremental re-index (only changed files)
- **Rebuild**: Full re-index (destructive, clears all data)
- **Watcher**: Filesystem monitor that triggers re-indexing on changes
- **Provider**: Search backend (semantic, symbol, full-text, git history)
- **Hybrid Provider**: Coordinates multiple providers in parallel

---

## Resources

### Documentation
- [README.md](README.md) - Project overview and quick start
- [docs/INSTALL.md](docs/INSTALL.md) - Detailed installation guide
- [docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md](docs/AGENT_ORCHESTRATOR_INSTRUCTIONS.md) - Agent playbook (share with AI)
- [docs/README_TASK_ORCHESTRATOR.md](docs/README_TASK_ORCHESTRATOR.md) - Task orchestration deep dive
- [docs/README_CONTEXT_ADDON.md](docs/README_CONTEXT_ADDON.md) - Context system deep dive
- [docs/CONVERSATION_HANDOFF_WORKFLOW.md](docs/CONVERSATION_HANDOFF_WORKFLOW.md) - Multi-agent workflows explained
- [docs/TASK_ROUTING_GUIDE.md](docs/TASK_ROUTING_GUIDE.md) - Routing logic and decision trees
- [docs/MCP_TOOL_QUICK_REFERENCE.md](docs/MCP_TOOL_QUICK_REFERENCE.md) - MCP tools cheat sheet
- [docs/fusionagent_config_docs.md](docs/fusionagent_config_docs.md) - Configuration reference
- [devdoc/CONTEXT_ADDON_ARCHITECTURE.md](devdoc/CONTEXT_ADDON_ARCHITECTURE.md) - Context architecture
- [devdoc/WEB_DASHBOARD_ARCHITECTURE.md](devdoc/WEB_DASHBOARD_ARCHITECTURE.md) - Dashboard design
- [devdoc/context/SEARCH_ARCHITECTURE.md](devdoc/context/SEARCH_ARCHITECTURE.md) - Search pipeline (RRF + MMR)

### Demo
🎥 [Watch the demo](https://youtu.be/kXkTh0fJ0Lc) - See consensus collaboration in action

### Source Code
- [GitHub Repository](https://github.com/sergrudenko/agent-fusion)
- [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk)

---

**This document is a living guide. Update it as the architecture evolves.**
