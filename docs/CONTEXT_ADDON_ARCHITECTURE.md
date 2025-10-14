# Add-On Architecture: Universal Project Context Retrieval System

## Document Control
- **Project**: Dual-Agent Orchestrator - Context Retrieval Add-On
- **Version**: 1.0
- **Date**: October 12, 2025
- **Status**: Design Specification
- **Related**: CONTEXT_RETRIEVAL_SPEC.md (base specification)

---

## 1. Executive Summary

### 1.1 Purpose
Design a plugin-based, extensible architecture for the Universal Project Context Retrieval System that integrates seamlessly with the existing Kotlin-based orchestrator while maintaining loose coupling and independent deployability.

### 1.2 Integration Strategy

**Hybrid Deployment Model:**
- **Embedded Mode**: Context Engine runs as a module within the orchestrator JVM
- **Standalone Mode**: Context Engine runs as a separate service with MCP/HTTP API
- **Fallback Mode**: Graceful degradation when context service unavailable

### 1.3 Key Design Principles
- **Zero Breaking Changes**: Existing orchestrator continues to function without context system
- **Plugin Architecture**: Context capabilities discovered via SPI, similar to AgentFactory pattern
- **Configuration-Driven**: Enable/disable via context.toml configuration
- **Progressive Enhancement**: Start with basic features, add advanced capabilities incrementally

---

## 2. Architectural Overview

### 2.1 High-Level Component Structure

**ORCHESTRATOR CORE**
- Contains existing modules: Routing, Consensus, Metrics
- New Context Module added as optional plugin
- Context Module contains:
  - Context Manager (coordinates retrieval)
  - Query Optimizer (MMR re-ranking, relevance)
  - Budget Manager (token allocation)
  - Context Provider SPI Registry (discovers providers)
    - Semantic Provider (embedding-based search)
    - Symbol Provider (AST/symbol-based)
    - Full-Text Provider (keyword/BM25)
    - Git History Provider (blame, commits)
    - Hybrid Provider (combines strategies)
- Extended MCP Tools: query_context, refresh_context, get_context_stats, rebuild_context, get_rebuild_status

**CONTEXT ENGINE (Standalone Service)**
- Watcher Daemon (monitors file changes)
- Chunker Pipeline (splits files into semantic chunks)
- Embedder Service (generates vector embeddings)
- Indexer Service (maintains search index)
- Vector Search (cosine similarity queries)
- MMR Re-ranker (diversity optimization)
- DuckDB Storage Layer with tables:
  - file_state (tracks files and fingerprints)
  - chunks (semantic text chunks)
  - embeddings (vector representations)
  - links (cross-file references)
  - usage_metrics (token tracking)

**Communication Flow:**
Orchestrator Core → Context Module → MCP/HTTP → Context Engine → DuckDB

---

## 3. Plugin Architecture (SPI-Based)

### 3.1 Context Provider Interface

Following the existing AgentFactory SPI pattern:

**ContextProvider Interface:**
- Service Provider Interface for context providers
- Implementations discovered via Java SPI (same as AgentFactory)
- Each provider has:
  - Unique ID (e.g., "semantic", "symbol", "git-history")
  - Human-readable name
  - Provider type/category
- Core methods:
  - query(): Accepts natural language query, scope, and budget; returns ranked snippets
  - isAvailable(): Checks if provider is configured and ready
  - initialize(): Sets up provider with configuration map
  - shutdown(): Cleanup resources

**Provider Types:**
- SEMANTIC: Embedding-based vector search
- SYMBOL: AST/symbol-based search
- FULL_TEXT: BM25/keyword search
- GIT_HISTORY: Git blame, commit history
- DEPENDENCY: Dependency graph traversal
- HYBRID: Combines multiple strategies

**Core Data Structures:**

**ContextScope:**
- paths: List of file/directory paths to search
- languages: Filter by programming languages
- kinds: Filter by chunk types
- excludePatterns: Paths to ignore

**TokenBudget:**
- maxTokens: Maximum tokens to return
- reserveForPrompt: Tokens reserved for prompt construction
- diversityWeight: Balance between relevance and diversity

**ContextSnippet:**
- chunkId: Unique identifier
- score: Relevance score (0.0 to 1.0)
- filePath: Source file path
- label: Optional label (function name, section heading)
- kind: Type of chunk (see ChunkKind)
- text: Actual content
- language: Programming language
- offsets: Start and end positions in file
- metadata: Additional provider-specific data

**ChunkKind Types:**
- PARAGRAPH: Text paragraphs
- CODE_FUNCTION: Function/method definitions
- CODE_CLASS: Class/interface definitions
- CODE_BLOCK: Code fences in markdown
- YAML_BLOCK: YAML configuration sections
- SQL_STATEMENT: SQL queries/DDL
- MARKDOWN_SECTION: Markdown sections by heading
- DOCSTRING: Documentation strings
- COMMENT: Code comments

### 3.2 SPI Registration

**Service Registration File:**
Location: src/main/resources/META-INF/services/com.orchestrator.context.ContextProvider

Contents list all provider implementations:
- com.orchestrator.context.providers.SemanticContextProvider
- com.orchestrator.context.providers.SymbolContextProvider
- com.orchestrator.context.providers.FullTextContextProvider
- com.orchestrator.context.providers.GitHistoryContextProvider
- com.orchestrator.context.providers.HybridContextProvider

This follows the exact same pattern as the existing AgentFactory registration.

### 3.3 Context Provider Registry

**ContextProviderRegistry:**
- Singleton object that discovers and manages providers
- Mirrors the AgentRegistry pattern exactly
- Uses Java ServiceLoader for automatic discovery
- Initialization:
  - Loads all providers via SPI
  - Registers them in internal map by ID
  - Prints discovery messages to console
- Public API:
  - getProvider(id): Retrieve specific provider by ID
  - getAllProviders(): Get all registered providers
  - getProvidersByType(type): Filter providers by type category

This ensures zero hardcoding - new providers can be added by:
1. Creating provider implementation
2. Adding SPI registration entry
3. Adding configuration to context.toml
4. No core code changes required

---

## 4. Configuration Schema

### 4.1 context.toml Configuration File

**Core Settings:**
- enabled: true/false - Master switch for context system
- mode: "embedded" | "standalone" | "hybrid" - Deployment mode
- fallback_enabled: Continue without context if service fails

**Engine Configuration (for standalone/hybrid modes):**
- host: Service hostname (default: localhost)
- port: Service port (default: 9090)
- timeout_ms: Request timeout in milliseconds
- retry_attempts: Number of retry attempts on failure

**Storage Configuration:**
- db_path: Path to DuckDB database file (default: ./context.duckdb)
- backup_enabled: Enable periodic backups
- backup_interval_hours: Backup frequency

**Watcher Configuration:**
- enabled: Enable file watching
- debounce_ms: Debounce delay for file changes (default: 500ms)
- watch_paths: Directories to monitor (e.g., ["src/", "docs/", "config/"] or ["auto"] for auto-detection)
- ignore_patterns: Patterns to ignore ([".git", "node_modules", "build", "dist", ".venv", "target"])
- max_file_size_mb: Skip files larger than this limit (default: 5MB)
- use_gitignore: Boolean - also apply patterns from .gitignore (default: true)
- use_contextignore: Boolean - apply patterns from .contextignore (default: true)

**Indexing Configuration:**
File filtering and inclusion rules:
- allowed_extensions: Whitelist of file extensions to index (e.g., [".kt", ".py", ".ts", ".md", ".yaml"])
- blocked_extensions: Blacklist of extensions to skip (e.g., [".exe", ".bin", ".jpg", ".zip"])
- max_file_size_mb: Maximum file size to index (default: 5)
- warn_file_size_mb: Warn when files exceed this size (default: 2)
- size_exceptions: Specific files allowed despite size limit (e.g., ["docs/api-reference.md"])
- follow_symlinks: Boolean - follow symbolic links (default: false)
- max_symlink_depth: If following symlinks, maximum depth (default: 3)
- binary_detection: "extension" | "mime" | "content" | "all" (default: "all")
- binary_threshold: Percentage of non-ASCII chars to consider binary (default: 30)

**Embedding Configuration:**
- model: Embedding model name (e.g., sentence-transformers/all-MiniLM-L6-v2)
- dimension: Vector dimension (e.g., 384)
- batch_size: Batch size for embedding calls (default: 128)
- normalize: L2-normalize vectors (default: true)
- cache_enabled: Cache embeddings

**Chunking Configuration (per language):**

Markdown settings:
- max_tokens: 400
- split_by_headings: true
- preserve_code_blocks: true

Python settings:
- max_tokens: 600
- split_by_function: true
- overlap_percent: 15
- preserve_docstrings: true

Kotlin settings:
- max_tokens: 600
- split_by_class: true
- split_by_function: true
- preserve_kdoc: true

TypeScript settings:
- max_tokens: 600
- split_by_export: true
- preserve_jsdoc: true

**Query Configuration:**
- default_k: Number of results to return (default: 12)
- mmr_lambda: Diversity vs relevance tradeoff (0.0 to 1.0, default: 0.5)
- min_score_threshold: Minimum relevance score (default: 0.3)
- rerank_enabled: Enable MMR re-ranking (default: true)

**Budget Configuration:**
- default_max_tokens: Default token budget (default: 1500)
- reserve_for_prompt: Tokens reserved for prompt (default: 500)
- warn_threshold_percent: Warn when exceeding this % (default: 80)

**Provider Configurations:**

Semantic provider:
- enabled: true
- weight: 0.6 (relative importance)

Symbol provider:
- enabled: true
- weight: 0.3
- index_ast: true (build AST index)

Full-text provider:
- enabled: false (disabled by default, hybrid covers this)
- weight: 0.1

Git history provider:
- enabled: true
- weight: 0.2
- max_commits: 100

Hybrid provider:
- enabled: true
- combines: [semantic, symbol, git_history]
- fusion_strategy: "rrf" (Reciprocal Rank Fusion)

**Metrics Configuration:**
- enabled: true
- track_latency: true
- track_token_usage: true
- track_cache_hits: true
- export_interval_minutes: 5

**Bootstrap Configuration:**
Settings for initial indexing when starting fresh:
- enabled: true (enable bootstrap process)
- parallel_workers: 7 (number of concurrent file processors, typically CPU cores - 1)
- batch_size: 128 (chunks per embedding batch)
- priority_extensions: [.kt, .py, .ts, .java, .md] (process these first)
- max_initial_files: 0 (limit for testing, 0 = unlimited)
- fail_fast: false (continue on individual file errors)
- show_progress: true (log progress updates)
- progress_interval_seconds: 30 (frequency of progress messages)

**Security Configuration:**
- scrub_secrets: true (redact sensitive patterns)
- secret_patterns: List of regex patterns for credentials
  - 'password\s*=\s*["\'].*["\']'
  - 'api[_-]?key\s*=\s*["\'].*["\']'
  - 'token\s*=\s*["\'].*["\']'
- encrypt_db: false (optional encryption at rest)

### 4.2 Configuration Loading

**ContextConfig Data Structure:**
Hierarchical configuration with nested structures:
- Top level: enabled, mode, fallback settings
- EngineConfig: Connection settings for standalone mode
- StorageConfig: Database and backup settings
- WatcherConfig: File monitoring settings
- EmbeddingConfig: Model and vector settings
- ChunkingConfig: Per-language chunking rules (map of language to config)
- QueryConfig: Search and ranking parameters
- BudgetConfig: Token allocation settings
- ProviderConfig: Per-provider settings (map of provider ID to config)
- MetricsConfig: Monitoring settings
- SecurityConfig: Security and privacy settings

**Deployment Modes:**
- EMBEDDED: Context engine runs in same JVM as orchestrator
- STANDALONE: Context engine as separate service process
- HYBRID: Try embedded first, fallback to standalone

**ContextConfigLoader:**
- Loads configuration from TOML file (default: context.toml)
- Validates all required fields
- Throws clear error if configuration missing or invalid
- Uses Typesafe Config library for parsing
- Supports environment variable substitution

---

## 5. Integration with Orchestrator

### 5.1 Context Module (Orchestrator Plugin)

**ContextModule Class:**
Integrates context retrieval into the orchestrator, following the same pattern as RoutingModule, ConsensusModule, and MetricsModule.

**Responsibilities:**
- Coordinates context retrieval across multiple providers
- Manages query optimization and result re-ranking
- Enforces token budgets
- Tracks metrics for token usage reduction
- Provides graceful degradation when unavailable

**Components:**
- providers: List of enabled ContextProvider instances
- queryOptimizer: Handles MMR re-ranking and relevance tuning
- budgetManager: Calculates and enforces token budgets

**Initialization:**
- Loads configuration from context.toml
- Discovers providers via ContextProviderRegistry
- Filters to only enabled providers from configuration
- Initializes each provider with its specific configuration
- Creates QueryOptimizer and BudgetManager instances

**Core Method: getTaskContext()**
Primary integration point - called before task execution to retrieve relevant context.

**Process Flow:**
1. Check if context system is enabled (return empty context if disabled)
2. Build semantic query from task title, description, and metadata
3. Determine search scope from task hints (paths, languages, chunk types)
4. Calculate token budget based on task complexity and type
5. Query all enabled providers in parallel
6. Merge and deduplicate results by chunk ID
7. Apply MMR re-ranking for diversity (using QueryOptimizer)
8. Record metrics to usage_metrics table
9. Return TaskContext object with snippets

**Error Handling:**
- If context retrieval fails and fallback_enabled is true:
  - Log warning but continue without context
  - Return empty TaskContext
- If fallback_enabled is false:
  - Propagate exception to caller

**Helper Methods:**

buildQuery():
- Constructs semantic query from task metadata
- Combines task title + description + keywords
- Returns single query string

determineScope():
- Extracts search scope from task metadata
- Builds ContextScope with paths, languages, chunk kinds
- Applies global ignore patterns from configuration

queryProviders():
- Queries all enabled providers in parallel using coroutines
- Each provider query wrapped in try-catch for resilience
- Adds provider ID to snippet metadata
- Returns flattened and deduplicated results

recordMetrics():
- Logs context retrieval metrics to DuckDB
- Tracks snippet count, total characters, token estimates
- Used for before/after comparison reporting

shutdown():
- Gracefully shuts down all providers
- Releases resources

**TaskContext Data Structure:**
Contains results of context retrieval for a specific task:
- taskId: Associated task identifier
- snippets: List of ranked ContextSnippet objects
- totalTokens: Estimated total tokens consumed
- providers: Snippets grouped by provider ID (for transparency)

empty() factory method: Returns empty context for disabled/failed scenarios

### 5.2 Query Optimizer

**QueryOptimizer Class:**
Implements Maximal Marginal Relevance (MMR) and other ranking optimizations.

**Purpose:**
- Re-rank retrieved snippets to balance relevance and diversity
- Prevent redundant/similar snippets
- Respect token budgets
- Improve coverage across codebase

**Key Method: rerank()**
Applies MMR algorithm to snippet list with token budget constraints.

**MMR Algorithm:**
1. If snippet list empty, return empty list
2. Initialize selected list and remaining budget
3. First iteration: Select highest-scoring snippet
4. Subsequent iterations:
   - For each remaining candidate, calculate MMR score:
     - mmrScore = lambda × relevance - (1 - lambda) × maxSimilarity
     - relevance = candidate's original score
     - maxSimilarity = maximum similarity to already-selected snippets
   - Select candidate with highest MMR score
   - Check if it fits in remaining token budget
   - If fits: add to selected, deduct tokens from budget
   - If doesn't fit: break (budget exhausted)
5. Return selected snippets in order

**Configuration:**
- mmrLambda: Balance parameter (0.0 = pure diversity, 1.0 = pure relevance)
- Default from QueryConfig: 0.5 (balanced)

**Similarity Calculation:**
- cosineSimilarity(): Computes text overlap similarity
- Placeholder implementation: Jaccard similarity on word sets
- Real implementation: Use embedding vectors from providers
- Returns value between 0.0 (no similarity) and 1.0 (identical)

**Token Estimation:**
- Uses simple heuristic: 4 characters per token
- Could be replaced with tiktoken or similar for accuracy

**Benefits:**
- Avoids duplicate information
- Ensures diverse file coverage
- Respects strict token budgets
- Maintains high relevance scores

### 5.3 Budget Manager

**BudgetManager Class:**
Calculates and manages token budgets for context retrieval.

**Purpose:**
- Determine appropriate budget for each task/agent combination
- Adjust based on task characteristics
- Warn when approaching limits
- Ensure predictable resource usage

**Key Method: calculateBudget()**
Computes TokenBudget for a specific task and agent.

**Budget Calculation Logic:**

Base Budget:
- Start with default_max_tokens from configuration (e.g., 1500)

Complexity Adjustment:
- High complexity (8-10): Increase budget by 50%
- Low complexity (1-3): Decrease budget by 30%
- Medium complexity (4-7): Keep base budget

Task Type Adjustment:
- ARCHITECTURE tasks: Increase by 30% (need more context)
- BUGFIX tasks: Decrease by 20% (focused scope)
- Other types: No adjustment

Warning Threshold:
- Calculate warn_threshold from configuration percentage
- If budget exceeds threshold, log warning message
- Helps identify unexpectedly expensive context retrievals

**Return Value:**
TokenBudget object with:
- maxTokens: Final calculated maximum
- reserveForPrompt: Reserved tokens (from config)
- diversityWeight: Fixed at 0.5

**Benefits:**
- Right-sizes context for task needs
- Prevents token waste on simple tasks
- Provides adequate context for complex tasks
- Alerts to unusual budget requirements

**BudgetConfig Structure:**
- defaultMaxTokens: Base budget amount
- reserveForPrompt: Tokens held back for prompt construction
- warnThresholdPercent: Alert threshold (e.g., 80%)

---

## 6. MCP Tools Extension

### 6.1 New MCP Tools

Five new MCP tools added to the orchestrator's tool catalog for context operations:

**Tool: query_context**
Allows AI agents to explicitly query the context system for relevant code/documentation.

Parameters:
- query (required): Natural language query string
- k (optional, default 12): Number of results to return
- scope (optional): Scope filters
  - paths: List of file/directory paths
  - languages: Filter by programming languages
  - kinds: Filter by chunk types (CODE_FUNCTION, MARKDOWN_SECTION, etc.)
- filters (optional): Additional filters
  - minScore: Minimum relevance threshold
  - maxTokens: Override default token budget

Response Structure:
- query: Echo of the query string
- total: Total number of matches found
- hits: Array of result objects
  - chunkId: Unique identifier
  - score: Relevance score (0.0 to 1.0)
  - filePath: Source file path
  - label: Function/section name (if applicable)
  - kind: Chunk type
  - snippet: Actual text content
  - language: Programming language
  - offsets: {start, end} positions in file
- totalTokens: Estimated token count for all hits

Process Flow:
1. Parse and validate parameters
2. Build ContextScope from scope parameter
3. Create TokenBudget from filters.maxTokens or default
4. Query ContextModule (which queries all enabled providers)
5. Filter by minScore threshold if specified
6. Convert ContextSnippet objects to HitDTO format
7. Return results with metadata

Use Cases:
- Agent needs specific information not in initial context
- User explicitly asks to "search the codebase for X"
- Agent wants to verify or expand on existing context

**Tool: refresh_context**
Triggers manual re-indexing of specified files or directories.

Parameters:
- paths (required): List of file/directory paths to refresh
- force (optional, default false): Force re-index even if unchanged
- async (optional, default true): Run asynchronously

Response Structure:
- status: "queued" or "completed"
- filesQueued: Number of files scheduled for reindex
- message: Status message

Process Flow:
1. Validate paths exist and are within allowed roots
2. If async mode:
   - Add files to reindex queue
   - Return immediately with "queued" status
3. If synchronous mode:
   - Block until re-indexing completes
   - Return "completed" status
4. Reindex process:
   - Compute file hashes
   - Compare with file_state table
   - Re-chunk and re-embed changed files
   - Update DuckDB atomically

Use Cases:
- User just edited files and wants fresh context
- Agent detects stale context
- Scheduled periodic refreshes

**Tool: get_context_stats**
Returns statistics and health information about the context system.

Parameters:
- None (or optional filters for specific providers/languages)

Response Structure:
- enabled: Boolean - is context system enabled
- mode: Deployment mode (embedded/standalone/hybrid)
- providers: Array of provider status
  - id: Provider identifier
  - name: Provider name
  - enabled: Is provider active
  - available: Is provider currently reachable
  - stats: Provider-specific statistics
- storage: Storage statistics
  - totalFiles: Number of indexed files
  - totalChunks: Number of chunks
  - totalEmbeddings: Number of embeddings
  - dbSizeMB: Database size in megabytes
  - lastIndexed: Timestamp of most recent index update
- performance: Performance metrics
  - avgQueryLatencyMs: Average query time
  - cacheHitRate: Cache effectiveness percentage
  - avgTokensPerQuery: Average tokens retrieved
- languages: Distribution of chunks by language
- recentActivity: Recent index/query operations

Use Cases:
- Debugging context retrieval issues
- Monitoring system health
- Understanding what's indexed
- Performance analysis

**Tool: rebuild_context**
Performs a complete rebuild of the context index, similar to first-time bootstrap. This is a destructive operation that clears all existing indexed data and re-processes everything from scratch.

Parameters:
- confirm: Boolean (required, must be true) - Safety confirmation
- async: Boolean (optional, default true) - Run asynchronously
- paths: Array of strings (optional) - Specific paths to rebuild, or empty/null for all
- options: Object (optional)
  - clearErrors: Boolean (default true) - Clear bootstrap_errors table
  - vacuum: Boolean (default true) - VACUUM database after rebuild
  - validateOnly: Boolean (default false) - Dry-run mode, report what would be done

Response Structure:
- status: "queued" | "in_progress" | "completed" | "failed"
- jobId: Unique identifier for tracking this rebuild job
- scope: "full" | "partial"
- estimatedFiles: Number of files to process
- estimatedTime: Estimated completion time in seconds
- message: Status message
- progress: Progress object (if in_progress)
  - filesProcessed: Current count
  - filesTotal: Total count
  - percentComplete: 0-100
  - currentFile: Path currently being processed
  - startedAt: Timestamp
  - estimatedCompletion: Timestamp

Process Flow:
1. **Validation:**
   - Check confirm parameter is true (safety check)
   - Verify user has permission for destructive operation
   - Check if another rebuild is already in progress (block if so)
   - Validate paths if provided

2. **Pre-Rebuild Phase:**
   - Log rebuild request with timestamp and parameters
   - Create rebuild job record in jobs table
   - If validateOnly mode:
     - Scan directories
     - Count files to process
     - Calculate estimates
     - Return report without modifying data
     - Exit

3. **Destructive Phase:**
   - Begin transaction
   - If full rebuild (no paths specified):
     - DROP all context-related tables (chunks, embeddings, links)
     - Keep usage_metrics for historical data
     - DELETE all records from file_state
     - If clearErrors: DELETE from bootstrap_errors
   - If partial rebuild (paths specified):
     - DELETE from file_state WHERE path matches patterns
     - CASCADE deletes chunks, embeddings, links
   - Recreate tables and indexes
   - Commit transaction

4. **Rebuild Phase:**
   - Create fresh bootstrap_progress table
   - Run full bootstrap process (same as initial startup)
     - Directory scanning
     - File prioritization
     - Parallel processing with workers
     - Chunking, embedding, indexing
   - Update job status continuously
   - Track progress in bootstrap_progress

5. **Post-Rebuild Phase:**
   - If vacuum enabled: Run VACUUM on database (reclaim space)
   - Calculate final statistics
   - Update job record to "completed" status
   - Drop bootstrap_progress table
   - Log completion with metrics

6. **Return Response:**
   - If async: Return immediately with jobId and "queued" status
   - If sync: Block until complete, return final status

**Safety Features:**

Multiple confirmation layers:
1. Required confirm=true parameter
2. Warning in tool description about destructive operation
3. Optional dry-run mode (validateOnly=true)
4. Cannot start if another rebuild in progress
5. Atomic transactions (rollback on failure)

**Job Tracking:**

jobs table schema:
- job_id: UUID
- job_type: "rebuild_full" | "rebuild_partial"
- status: "queued" | "in_progress" | "completed" | "failed" | "cancelled"
- scope: Paths or "all"
- files_total: Total files to process
- files_processed: Current progress
- started_at: Timestamp
- completed_at: Timestamp
- error_message: If failed
- metrics: JSON with final stats

**Progress Monitoring:**

Users can check rebuild progress via:
- get_rebuild_status tool (new, see below)
- get_context_stats shows "Rebuild in progress: 45%"
- Async notifications if webhook configured

Use Cases:
- **Corrupted Index**: Database corruption requires clean rebuild
- **Model Change**: Switched embedding models, need new vectors
- **Major Refactor**: Massive codebase changes, faster to rebuild than incremental
- **Debugging**: Suspected stale/incorrect index
- **Migration**: Moving to new database file
- **Configuration Change**: Changed chunking strategies significantly
- **Testing**: Validate indexing behavior

Example Usage:

Full rebuild (async):
```
Request:
{
  "name": "rebuild_context",
  "params": {
    "confirm": true,
    "async": true
  }
}

Response:
{
  "status": "queued",
  "jobId": "rebuild-abc123",
  "scope": "full",
  "estimatedFiles": 10247,
  "estimatedTime": 680,
  "message": "Full rebuild queued. This will re-index all 10,247 files (~11 minutes)."
}
```

Partial rebuild (specific paths):
```
Request:
{
  "name": "rebuild_context",
  "params": {
    "confirm": true,
    "paths": ["src/auth/", "src/api/"],
    "async": false
  }
}

Response:
{
  "status": "completed",
  "jobId": "rebuild-def456",
  "scope": "partial",
  "estimatedFiles": 234,
  "message": "Partial rebuild completed. Re-indexed 234 files in src/auth/ and src/api/."
}
```

Dry-run validation:
```
Request:
{
  "name": "rebuild_context",
  "params": {
    "confirm": true,
    "options": {
      "validateOnly": true
    }
  }
}

Response:
{
  "status": "completed",
  "jobId": "rebuild-ghi789",
  "scope": "full",
  "estimatedFiles": 10247,
  "estimatedTime": 680,
  "message": "Dry-run: Would re-index 10,247 files across 45 directories. Estimated 11 minutes. No changes made.",
  "details": {
    "byLanguage": {
      "kotlin": 3421,
      "python": 2156,
      "typescript": 1987,
      "markdown": 1543,
      "other": 1140
    },
    "totalSizeGB": 1.2,
    "currentIndexSize": "234 MB"
  }
}
```

**Tool: get_rebuild_status**
Check the status of an ongoing or completed rebuild job.

Parameters:
- jobId: String (required) - Job identifier from rebuild_context response
- includeLogs: Boolean (optional, default false) - Include detailed logs

Response Structure:
- jobId: Job identifier
- status: Current status
- progress: Progress details (if in_progress)
- result: Final result (if completed)
- error: Error details (if failed)
- logs: Array of log entries (if includeLogs=true)

Use Cases:
- Monitor long-running rebuild
- Check if rebuild completed
- Diagnose rebuild failures

---

## 7. Orchestrator Workflow Integration

### 7.1 Pre-Task Hook

**Integration Point:**
Before any task is assigned to an agent, the orchestrator calls ContextModule.getTaskContext() to retrieve relevant context.

**Workflow Modification:**

Current Flow:
1. Task created via create_simple_task or create_consensus_task
2. RoutingModule determines agent(s)
3. Task assigned to agent(s)
4. Agent executes task

Enhanced Flow with Context:
1. Task created via MCP tool
2. RoutingModule determines agent(s)
3. **ContextModule.getTaskContext() called** (NEW)
4. Context rendered into prompt format
5. Task assigned to agent(s) **with context block**
6. Agent executes task with contextual awareness

**Context Block Rendering:**

The retrieved TaskContext is converted into an XML-formatted context block that's injected into the agent's prompt:

```
<project_context>
  <snippet path="src/auth/JwtValidator.kt" label="validateToken" kind="CODE_FUNCTION" score="0.87">
  [5-10 lines of code]
  </snippet>
  
  <snippet path="docs/security.md" label="JWT Authentication" kind="MARKDOWN_SECTION" score="0.82">
  [Relevant documentation text]
  </snippet>
  
  ... (up to K snippets within token budget)
</project_context>
```

**Rendering Rules:**
1. Sort snippets by score (descending)
2. Group by file path for readability
3. Include path, label, kind, and score as attributes
4. Truncate snippets if necessary to fit budget
5. Add language syntax highlighting hints when applicable
6. Deduplicate overlapping snippets from same file
7. Add source attribution for transparency

**Token Budget Management:**
- Use tokens_est from chunks table
- Keep running total as snippets added
- Stop when budget exhausted
- Prioritize diversity (MMR) over pure relevance
- Reserve tokens for prompt template overhead

### 7.2 Prompt Template Updates

**Enhanced Task Prompt:**

Before (without context):
```
You are executing task #123: Implement JWT authentication
Description: Add JWT token validation to the auth service
Type: IMPLEMENTATION
Complexity: 7

Please implement this task.
```

After (with context):
```
You are executing task #123: Implement JWT authentication
Description: Add JWT token validation to the auth service
Type: IMPLEMENTATION
Complexity: 7

<project_context>
[Relevant snippets from codebase and docs]
</project_context>

Guidelines:
- Prefer information from <project_context> over general knowledge
- Reference specific files/functions mentioned in context
- If context is insufficient, use query_context tool for more information
- Maintain consistency with existing patterns shown in context

Please implement this task using the provided context.
```

**Benefits:**
- Agent has immediate relevant context
- No need to scan entire repo
- Maintains codebase consistency
- Reduces hallucination
- Faster task completion

### 7.3 Metrics Collection

**Before/After Comparison:**

The orchestrator tracks token usage before and after context integration to measure the 80% reduction goal.

**Metrics Tracked:**
- tokens_input: Prompt tokens sent to agent
- tokens_output: Response tokens from agent
- context_tokens: Tokens consumed by context block
- query_latency_ms: Time to retrieve context
- snippets_count: Number of snippets provided
- providers_used: Which providers contributed

**Storage:**
All metrics stored in usage_metrics table (already defined in base spec).

**Reporting:**
Simple SQL query compares before/after averages:

```
Before Context Integration:
- Avg Input Tokens: 5000
- Avg Output Tokens: 2000
- Total: 7000

After Context Integration:
- Avg Input Tokens: 800 (from context) + 200 (prompt) = 1000
- Avg Output Tokens: 400
- Total: 1400

Reduction: 80%
```

**Per-Task Tracking:**
Each task record includes:
- context_enabled: Boolean
- context_tokens_used: Token count
- context_snippets_count: Number of snippets
- context_query_time_ms: Retrieval latency

**Dashboard Queries:**
- Top 10 tasks by token savings
- Provider effectiveness (snippets used vs provided)
- Languages with best/worst context coverage
- Time-series of token usage trends

---

## 8. Context Engine (Standalone Service)

### 8.0 Initial Bootstrap and First-Run Behavior

**Project Boundary Definition:**

The context system must know which files belong to the project and should be indexed. This is configured through multiple mechanisms:

**1. Explicit Watch Paths (Primary Method)**

Configuration in context.toml defines root directories:
```
[context.watcher]
watch_paths = ["src/", "docs/", "config/", "tests/"]
```

Rules:
- Only files within these paths are considered for indexing
- Paths are relative to the context engine's working directory (typically project root)
- Can be absolute paths: ["/home/user/myproject/src/"]
- Multiple roots supported for monorepos or split projects
- Subdirectories automatically included unless excluded

**2. Project Root Detection (Auto-Discovery)**

If watch_paths is empty or set to ["auto"], the system auto-detects project root:

Detection heuristics (in order):
1. Git repository root (.git directory)
2. Package manager markers:
   - package.json (Node.js)
   - pom.xml or build.gradle (Java/Kotlin)
   - Cargo.toml (Rust)
   - pyproject.toml or setup.py (Python)
   - go.mod (Go)
3. Common project structure indicators:
   - src/ + tests/ directories
   - build/ or target/ directories
   - README.md at root
4. Working directory (fallback)

Once root detected, default watch_paths:
- src/
- lib/
- docs/
- config/
- Any source code directories found

Excluded by default:
- All patterns in ignore_patterns
- Hidden directories (starting with .)
- Build/output directories

**3. Ignore Patterns (Negative Filtering)**

Configuration defines exclusions:
```
[context.watcher]
ignore_patterns = [
  ".git",
  "node_modules",
  "target",
  "build",
  "dist",
  ".venv",
  "__pycache__",
  "*.class",
  "*.pyc",
  ".idea",
  ".vscode"
]
```

Pattern matching:
- Glob patterns: `*.log`, `temp/*`, `**/cache`
- Exact names: `node_modules` (matches anywhere in path)
- Path prefixes: `.git/` (only at start)
- Case-insensitive by default (configurable)

Additional ignore sources:
- .gitignore file (if exists and use_gitignore=true)
- .contextignore file (context-specific exclusions)
- .dockerignore file (if use_dockerignore=true)

**4. Extension Allowlist (Positive Filtering)**

Configuration whitelists file types:
```
[context.indexing]
allowed_extensions = [
  # Code
  ".kt", ".java", ".py", ".ts", ".js", ".go", ".rs", ".c", ".cpp", ".h",
  # Docs
  ".md", ".rst", ".txt", ".adoc",
  # Config
  ".yaml", ".yml", ".toml", ".json", ".xml", ".properties",
  # Data
  ".sql", ".csv",
  # Scripts
  ".sh", ".bash", ".ps1"
]

# Alternative: block specific extensions
blocked_extensions = [".exe", ".bin", ".jpg", ".png", ".pdf", ".zip"]
```

Default behavior:
- If allowed_extensions specified: Only these file types indexed
- If blocked_extensions specified: All except these indexed
- If neither specified: Heuristic (text files only, detected by MIME type)

**5. File Size Limits**

Configuration prevents indexing large files:
```
[context.indexing]
max_file_size_mb = 5
warn_file_size_mb = 2
```

Behavior:
- Files >5MB skipped entirely
- Files >2MB trigger warning log
- Exception list for specific large files:
  ```
  size_exceptions = ["docs/api-reference.md", "data/schema.sql"]
  ```

**6. Binary Detection**

The system detects and skips binary files:

Detection methods:
1. Extension-based: `.exe`, `.bin`, `.jpg`, `.png`, `.pdf`, `.zip`, etc.
2. MIME type detection: Use file magic numbers
3. Content analysis: Check first 8KB for null bytes or high non-ASCII ratio

Threshold:
- If >30% non-ASCII in first 8KB → binary
- If null bytes detected → binary
- Skip binary files unless explicitly allowed

**7. Symlink Handling**

Configuration for symbolic links:
```
[context.indexing]
follow_symlinks = false  # Default: don't follow
max_symlink_depth = 3    # If following, limit depth
```

Behavior:
- follow_symlinks=false: Ignore symlinks (prevents external files)
- follow_symlinks=true: Follow but track visited inodes (prevent loops)
- Symlinks pointing outside watch_paths → ignored (security)

**8. Security Boundaries**

Hard limits prevent indexing outside project:

**Absolute Path Validation:**
Every file path validated before indexing:
```
function validatePath(filePath, watchPaths):
  absolutePath = resolve(filePath)
  
  # Must be under at least one watch path
  for watchPath in watchPaths:
    if absolutePath.startsWith(watchPath):
      # Further checks
      if not containsPathTraversal(absolutePath):
        if not isSymlinkEscape(absolutePath, watchPath):
          return true
  
  return false  # Reject: outside project boundaries
```

**Path Traversal Protection:**
- Reject paths with `..` segments after normalization
- Reject paths with `.` segments (obfuscation)
- Canonicalize all paths before validation

**Symlink Escape Prevention:**
- If symlink target resolves outside watch_paths → reject
- Track real paths, not just symlink paths
- Prevent /tmp or /home escapes

**Home Directory Protection:**
- Never index user home directory (unless explicitly in watch_paths)
- Warn if watch_paths contains ~ or $HOME
- Require explicit absolute paths for safety

**9. Configuration Validation**

On startup, the system validates configuration:

Validation checks:
1. All watch_paths exist and are accessible
2. No watch_paths overlap (e.g., "src/" and "src/main/" → warn)
3. watch_paths not too broad (e.g., "/" → reject)
4. At least one valid path specified or auto-detectable
5. ignore_patterns valid glob syntax
6. allowed_extensions format correct
7. Security: no sensitive system directories (/etc, /sys, /proc)

Errors on:
- No valid watch_paths found
- All watch_paths inaccessible (permissions)
- Dangerous paths detected

Warnings on:
- Very broad paths (/, /usr, /var)
- No ignore_patterns (will index everything)
- Very large project (>100k files estimated)

**10. Runtime Path Filtering**

During indexing, each file goes through filter pipeline:

**Filter Pipeline:**
```
File discovered
  ↓
Is path under watch_paths? → NO → Skip
  ↓ YES
Is path in ignore_patterns? → YES → Skip
  ↓ NO
Is extension allowed? → NO → Skip
  ↓ YES
Is file size within limits? → NO → Skip
  ↓ YES
Is binary file? → YES → Skip
  ↓ NO
Is symlink escape? → YES → Skip
  ↓ NO
Is already indexed (unchanged)? → YES → Skip
  ↓ NO
→ INDEX FILE
```

Each filter logs rejection reason for debugging.

**11. Project Metadata Storage**

DuckDB schema includes project boundary metadata:

**project_config table:**
- config_id INTEGER PRIMARY KEY
- watch_paths TEXT[] NOT NULL
- ignore_patterns TEXT[] NOT NULL
- allowed_extensions TEXT[]
- blocked_extensions TEXT[]
- max_file_size_mb REAL
- follow_symlinks BOOLEAN
- created_at TIMESTAMP
- updated_at TIMESTAMP

Purpose:
- Detect configuration changes (triggers re-scan)
- Validate file_state entries still within boundaries
- Audit trail for security

On startup:
- Compare current config with stored config
- If watch_paths changed → flag affected files for re-evaluation
- If ignore_patterns changed → re-scan all paths
- If extension rules changed → re-evaluate all files

**12. Multi-Project Support (Future)**

Configuration supports multiple isolated projects:

```
[context.projects.backend]
watch_paths = ["backend/src/", "backend/docs/"]
ignore_patterns = ["backend/node_modules"]

[context.projects.frontend]
watch_paths = ["frontend/src/", "frontend/public/"]
ignore_patterns = ["frontend/dist"]
```

Behavior:
- Each project has separate namespace in file_state
- Queries scoped to specific project
- Cross-project queries possible with explicit flag
- Isolation for monorepos

**13. Examples**

**Example 1: Kotlin Microservice**
```
Project structure:
/home/user/myservice/
  src/main/kotlin/
  src/test/kotlin/
  docs/
  build/
  .git/
  
Configuration:
watch_paths = ["src/", "docs/"]
ignore_patterns = ["build", ".git"]

Result: Only src/ and docs/ indexed, build/ and .git/ skipped
```

**Example 2: Monorepo**
```
Project structure:
/home/user/monorepo/
  services/auth/
  services/api/
  services/web/
  shared/lib/
  node_modules/
  
Configuration:
watch_paths = ["services/", "shared/lib/"]
ignore_patterns = ["node_modules", "*/dist", "*.test.ts"]

Result: All services + shared lib indexed, node_modules and tests skipped
```

**Example 3: Auto-Detection**
```
Project structure:
/home/user/project/
  .git/
  src/
  tests/
  README.md
  
Configuration:
watch_paths = ["auto"]

Result: System detects .git root, indexes src/ and tests/, skips .git/
```

**14. Validation Commands**

CLI tools for validating project boundaries:

```bash
# Show what would be indexed (dry-run)
contextd validate-config

# List all files that would be indexed
contextd list-indexable --limit 100

# Check if specific file would be indexed
contextd check-file src/auth/JwtValidator.kt

# Show statistics on project boundaries
contextd project-stats
```

Output examples:
```
✓ Configuration valid
✓ 3 watch paths found: src/, docs/, config/
✓ 8,234 indexable files detected
✓ 1,456 files excluded by ignore_patterns
✓ 12 files excluded by size limits
✓ No security issues detected

Watch paths:
  src/        → 6,789 files (82%)
  docs/       → 1,234 files (15%)
  config/     → 211 files (3%)

Excluded:
  .git/       → 456 files (git history)
  node_modules/ → 892 files (dependencies)
  build/      → 108 files (build artifacts)
```

**Database Schema Extensions:**

In addition to the core tables from the base specification (file_state, chunks, embeddings, links, usage_metrics), the bootstrap and rebuild functionality requires additional tables:

**bootstrap_progress table:**
- file_path TEXT PRIMARY KEY
- status TEXT ('pending' | 'processing' | 'completed' | 'failed')
- started_at TIMESTAMP
- completed_at TIMESTAMP
- error_message TEXT

Purpose: Track progress during bootstrap, enable resumption after interruption. Temporary table, dropped after completion.

**bootstrap_errors table:**
- error_id BIGINT PRIMARY KEY AUTO_INCREMENT
- file_path TEXT NOT NULL
- error_type TEXT NOT NULL
- error_message TEXT NOT NULL
- stack_trace TEXT
- occurred_at TIMESTAMP NOT NULL DEFAULT now()
- retry_count INTEGER DEFAULT 0

Purpose: Log files that failed during indexing for later retry. Persistent for debugging.

**jobs table:**
- job_id UUID PRIMARY KEY
- job_type TEXT NOT NULL ('rebuild_full' | 'rebuild_partial' | 'refresh')
- status TEXT NOT NULL ('queued' | 'in_progress' | 'completed' | 'failed' | 'cancelled')
- scope TEXT ('all' or JSON array of paths)
- files_total INTEGER
- files_processed INTEGER DEFAULT 0
- started_at TIMESTAMP NOT NULL DEFAULT now()
- completed_at TIMESTAMP
- error_message TEXT
- metrics JSON (final statistics)
- created_by TEXT (agent or user identifier)

Purpose: Track long-running rebuild operations, enable async execution and status polling.

Indexes:
- CREATE INDEX idx_jobs_status ON jobs(status)
- CREATE INDEX idx_jobs_created ON jobs(started_at DESC)

**First-Time Startup Detection:**
When the Context Engine starts for the first time (or when context.duckdb doesn't exist), it performs a complete initial indexing of the configured project directories.

**Bootstrap Process:**

**Step 1: Database Initialization**
- Create context.duckdb if it doesn't exist
- Execute schema.sql to create all tables (file_state, chunks, embeddings, links, usage_metrics)
- Create indexes for optimal query performance
- Mark database version for future migrations

**Step 2: Directory Discovery**
- Read watch_paths from configuration (e.g., ["src/", "docs/", "config/"])
- Recursively scan all directories
- Apply ignore_patterns to filter out unwanted paths (.git, node_modules, build, etc.)
- Apply file size limits (skip files >5MB unless whitelisted)
- Apply extension filtering (only index text/code files)
- Build initial file list with metadata (path, size, mtime)

**Step 3: Batch Processing Strategy**
Given that initial indexing could involve thousands of files, the system uses a batched approach:

**File Processing Queue:**
- Priority 1: Small files first (<10KB) - quick wins for immediate usability
- Priority 2: Source code files (*.kt, *.py, *.ts, *.java)
- Priority 3: Documentation files (*.md, *.rst)
- Priority 4: Configuration files (*.yaml, *.toml, *.json)
- Priority 5: Large files (up to size limit)

**Parallel Processing:**
- Worker pool with configurable concurrency (default: CPU cores - 1)
- Each worker processes files independently
- Shared DuckDB connection with transaction batching

**Per-File Processing:**
1. Read file content from disk
2. Compute BLAKE3 hash for content fingerprint
3. Detect language/MIME type
4. Select appropriate chunking strategy
5. Chunk file into semantic units
6. Estimate tokens per chunk
7. Batch chunks for embedding (accumulate up to 128 chunks)
8. Generate embeddings via embedding service
9. Begin transaction
10. INSERT into file_state
11. INSERT chunks (may be 1 to 100+ per file)
12. INSERT embeddings (one per chunk)
13. COMMIT transaction
14. Update progress counter

**Progress Tracking and Resumption:**
To handle interruptions during initial indexing:

**Progress Table:**
Add temporary bootstrap_progress table:
- file_path: Path being processed
- status: 'pending' | 'processing' | 'completed' | 'failed'
- started_at: Timestamp
- completed_at: Timestamp
- error_message: If failed

**Resume Logic:**
- On startup, check if bootstrap_progress exists
- If exists and has 'pending' or 'processing' files → resume interrupted bootstrap
- Process remaining files
- When complete, drop bootstrap_progress table

**Incremental Availability:**
The system becomes usable BEFORE complete indexing finishes:
- Query operations work on partially-indexed data
- file_state table shows what's already indexed
- Query results improve as more files are processed
- Status endpoint shows indexing progress (e.g., "1,234 / 5,678 files indexed")

**Performance Estimates:**
For a typical medium-sized project:
- 10,000 files
- Average 200 lines/file = 2 million lines
- At 3,000 lines/sec throughput = ~11 minutes total
- But first 1,000 files done in ~2 minutes (usable quickly)

**User Notifications:**
During bootstrap, the system provides:
- Initial: "Context system starting initial indexing of 10,000 files..."
- Progress: "Indexed 1,234 / 10,000 files (12%)... Estimated 9 minutes remaining"
- Incremental: "Basic context available (1,000 files indexed). Continuing in background..."
- Complete: "Initial indexing complete. 10,000 files, 45,678 chunks, 234 MB database."

**Configuration for Bootstrap:**

Additional bootstrap settings in context.toml:

```
[context.bootstrap]
enabled = true
parallel_workers = 7  # CPU cores - 1
batch_size = 128  # Chunks per embedding batch
priority_extensions = [".kt", ".py", ".ts", ".java", ".md"]
max_initial_files = 0  # 0 = unlimited, >0 = limit for testing
fail_fast = false  # Continue on individual file errors
show_progress = true  # Log progress updates
progress_interval_seconds = 30  # How often to show progress
```

**Failure Handling:**
- Individual file failures don't stop bootstrap
- Errors logged to bootstrap_errors table
- Can retry failed files later with refresh_context tool
- Critical failures (DB corruption, out of disk) abort bootstrap

**Post-Bootstrap:**
Once initial indexing completes:
- File watcher activates for incremental updates
- System switches to maintenance mode (only process changes)
- bootstrap_progress table dropped
- Normal query performance achieved

**Avoiding Re-Bootstrap:**
The system detects existing indexed state:
- Check if context.duckdb exists
- Check if file_state has records
- Check if files in watch_paths already indexed
- If ≥90% of current files are in file_state → skip bootstrap, just incremental update
- If <90% → partial re-index of missing files

**Manual Re-Index:**
Users can force complete re-index via CLI or MCP tool:

CLI commands:
```
# Full rebuild (interactive confirmation)
contextd rebuild

# Full rebuild (force, no confirmation)
contextd rebuild --force

# Partial rebuild (specific paths)
contextd rebuild --paths src/auth/ docs/

# Dry-run (show what would be rebuilt)
contextd rebuild --dry-run

# With options
contextd rebuild --no-vacuum --keep-errors
```

MCP tool:
```
rebuild_context with confirm=true
```

Both perform the same full bootstrap process as initial startup.

### 8.1 Service Architecture

When deployed in standalone mode, the Context Engine runs as a separate process/service.

**Components:**

**Watcher Daemon:**
- Monitors configured directories for file changes
- Uses OS-native events (inotify/FSEvents/ReadDirectoryChangesW)
- Debounces rapid changes (500ms default)
- Filters by ignore patterns (.git, node_modules, etc.)
- Queues changed files for reindexing

**Chunker Pipeline:**
- Language-aware chunking strategies
- Deterministic and reproducible
- Preserves semantic boundaries
- Supports: Python, TypeScript, Kotlin, Java, Markdown, YAML, SQL
- Tree-sitter integration for AST-based chunking
- Overlap for context continuity (10-15%)

**Embedder Service:**
- Batches chunks for efficient embedding API calls
- Caches embeddings to avoid recomputation
- Supports configurable models (sentence-transformers by default)
- Normalizes vectors for cosine similarity
- Rate limiting and retry logic

**Indexer Service:**
- Atomic transactions per file
- Upsert file_state
- Delete old chunks/embeddings by file_id
- Insert new chunks/embeddings
- Maintains referential integrity

**Vector Search:**
- Computes cosine similarity in-memory
- Efficient: pre-normalized vectors allow dot product
- No external vector DB needed for initial scale
- Optional: Create auxiliary normalized vector cache

**MMR Re-ranker:**
- Same algorithm as QueryOptimizer
- Applied at engine level for consistency
- Ensures diverse results before returning to orchestrator

**DuckDB Storage:**
- Single file database (context.duckdb)
- Embedded mode: no server process
- Efficient analytical queries
- JSON and array support
- Transaction safety

### 8.2 API Specification

**Standalone service exposes HTTP API compatible with MCP protocol:**

**Endpoint: POST /add_context**
Parameters:
- file_path: Path to file
- lang: Language hint (optional)
- mime: MIME type (optional)
- content: File content (optional, reads from disk if omitted)

Response:
- file_id: UUID of file record
- chunks: Number of chunks created
- embeddings: Number of embeddings generated

**Endpoint: POST /delete_context**
Parameters:
- file_path: Path to file

Response:
- deleted: Boolean success flag

**Endpoint: POST /query_context**
Parameters:
- q: Query string
- k: Number of results (default 12)
- model: Embedding model (optional)
- filters: {lang, kind, path_glob}
- mmr_lambda: Diversity parameter (default 0.5)

Response:
- q_embed_model: Model used for query embedding
- total: Total matches
- hits: Array of snippet objects

**Endpoint: GET /stats**
Parameters: None

Response:
- Statistics object (same as get_context_stats)

**Communication:**
- JSON-RPC 2.0 over HTTP
- Backward compatible with MCP stdio transport (via adapter)
- Authentication: Optional API key header

### 8.3 Deployment Modes

**Embedded Mode:**
- Context Engine loaded as Kotlin module in orchestrator JVM
- Direct in-memory function calls (no HTTP)
- Lowest latency
- Simplest deployment
- Single process

**Standalone Mode:**
- Context Engine as separate process
- Communication via HTTP API
- Independent scaling
- Can restart without affecting orchestrator
- Suitable for high-volume indexing

**Hybrid Mode:**
- Try embedded first for low latency
- Fallback to standalone if embedded unavailable
- Best of both worlds
- Resilience to engine crashes

---

## 9. Provider Implementations

### 9.1 Semantic Context Provider

**Implementation Strategy:**
- Uses sentence transformer models for embeddings
- Stores vectors in embeddings table
- Cosine similarity search via DuckDB
- MMR re-ranking for diversity

**Query Process:**
1. Embed query text using configured model
2. Compute cosine similarity with all chunk embeddings
3. Filter by scope (paths, languages, kinds)
4. Return top-K by score
5. Apply minimum score threshold

**Configuration:**
- model: sentence-transformers/all-MiniLM-L6-v2 (default)
- dimension: 384
- weight: 0.6 (relative importance in hybrid)

**Strengths:**
- Semantic understanding
- Handles synonyms and paraphrasing
- Good for conceptual queries

**Weaknesses:**
- Slower than keyword search
- Requires embedding model
- Less precise for exact symbol lookups

### 9.2 Symbol Context Provider

**Implementation Strategy:**
- Builds AST index using tree-sitter
- Indexes: classes, functions, variables, imports
- Fast symbol lookup by name
- Cross-reference tracking

**Query Process:**
1. Extract symbols from query (heuristic or LLM-based)
2. Lookup symbols in index
3. Return definitions + call sites
4. Include imports and dependencies

**Configuration:**
- index_ast: true (build AST index)
- weight: 0.3

**Strengths:**
- Extremely fast
- Precise for symbol lookups
- Shows usage patterns

**Weaknesses:**
- Requires parsing
- Language-specific
- Doesn't understand semantics

### 9.3 Full-Text Context Provider

**Implementation Strategy:**
- BM25 scoring algorithm
- Keyword-based search
- DuckDB full-text index

**Query Process:**
1. Tokenize query
2. Compute BM25 scores
3. Rank by relevance
4. Return top-K

**Configuration:**
- enabled: false (disabled by default, hybrid covers this)
- weight: 0.1

**Strengths:**
- Very fast
- No ML dependencies
- Good for exact keyword matches

**Weaknesses:**
- No semantic understanding
- Requires exact terms
- Poor for conceptual queries

### 9.4 Git History Context Provider

**Implementation Strategy:**
- Mines git commit history
- git blame for file authorship
- Recent changes analysis
- Co-change patterns

**Query Process:**
1. Extract file paths from query
2. Get recent commits affecting those files
3. Find co-changed files (files modified together)
4. Return relevant commit messages and diffs

**Configuration:**
- max_commits: 100 (limit history depth)
- weight: 0.2

**Strengths:**
- Temporal context
- Understands change patterns
- Good for "what changed recently" queries

**Weaknesses:**
- Requires git repository
- Slower than other providers
- Less useful for new files

### 9.5 Hybrid Context Provider

**Implementation Strategy:**
- Combines results from multiple providers
- Reciprocal Rank Fusion (RRF) for score merging
- Configurable fusion strategy

**Query Process:**
1. Query all configured sub-providers in parallel
2. Collect results with scores
3. Apply RRF formula:
   - RRF(chunk) = Σ 1/(k + rank_in_provider_i)
   - k = 60 (constant)
4. Re-rank by fused scores
5. Return top-K

**Configuration:**
- combines: [semantic, symbol, git_history]
- fusion_strategy: "rrf"
- Alternative strategies: weighted_average, cascade

**Strengths:**
- Best of all providers
- Robust to individual provider failures
- Balanced results

**Weaknesses:**
- Higher latency (parallel queries)
- More complex configuration

---

## 10. File Chunking Strategies

### 10.1 Markdown Chunking

**Strategy:**
- Split by ATX headings (#, ##, ###)
- Each section becomes chunk with heading as label
- Further split long sections by paragraphs
- Preserve code fences as standalone chunks
- Max token target: 400

**Process:**
1. Parse markdown to AST
2. Extract heading hierarchy
3. For each section:
   - Create chunk with heading as label
   - If section >400 tokens, split by paragraphs
   - Keep code blocks intact
4. Label code blocks by language

**Example:**
```
# Authentication (chunk 1, label="Authentication")
## JWT Tokens (chunk 2, label="JWT Tokens")
Explanation text...
```python (chunk 3, label="Python code", kind=CODE_BLOCK)
def validate_token():
    ...
```
```

### 10.2 Python Chunking

**Strategy:**
- Module-level docstring as first chunk
- Class definitions as chunks (with class docstring)
- Function definitions as chunks (with function docstring)
- Long functions split by logical blocks
- Max tokens: 600, overlap: 15%

**Process:**
1. Parse Python AST
2. Extract module docstring
3. For each class:
   - Create chunk with class def + docstring
   - Split methods into separate chunks
4. For each top-level function:
   - Create chunk with signature + docstring + body
   - If >600 tokens, split by statement blocks

**Example:**
```
Chunk 1: Module docstring
Chunk 2: Class User (with __init__)
Chunk 3: User.validate_password method
Chunk 4: authenticate function
```

### 10.3 TypeScript/JavaScript Chunking

**Strategy:**
- Split by exported declarations
- Keep JSDoc with function/class
- Preserve import statements in context
- Max tokens: 600

**Process:**
1. Parse TypeScript AST
2. Extract exports
3. For each export:
   - Create chunk with JSDoc + declaration
   - Include relevant imports
4. Group related exports by file module

**Example:**
```
Chunk 1: export interface User {...}
Chunk 2: export class AuthService {...}
Chunk 3: export function hashPassword {...}
```

### 10.4 YAML Chunking

**Strategy:**
- Split by top-level keys
- Each key/value as chunk
- Large strings split by lines
- Keep key path as label

**Process:**
1. Parse YAML
2. For each top-level key:
   - Create chunk with key + value
   - If value is large string, split
   - Label with key path (e.g., "services.database")

**Example:**
```
Chunk 1: services.database (kind=YAML_BLOCK, label="services.database")
Chunk 2: services.cache (kind=YAML_BLOCK, label="services.cache")
```

### 10.5 SQL Chunking

**Strategy:**
- Split by statement (CREATE, SELECT, INSERT, etc.)
- Attach preceding comments
- Each statement within token cap

**Process:**
1. Parse SQL (simple splitter or full parser)
2. For each statement:
   - Include preceding comments
   - Create chunk with statement
   - Label by statement type + table name

**Example:**
```
Chunk 1: CREATE TABLE users (label="CREATE users")
Chunk 2: CREATE INDEX idx_users_email (label="INDEX users.email")
```

---

## 11. Security and Privacy

### 11.1 Secret Scrubbing

**Purpose:**
Prevent sensitive credentials from appearing in context snippets or being stored in plaintext.

**Implementation:**
- Regex-based detection of common patterns
- Scrub before storing in chunks table
- Store redacted version for retrieval
- Keep original content hash for change detection

**Patterns Detected:**
- password = "..." or password: "..."
- api_key = "..." or apiKey: "..."
- token = "..." or access_token: "..."
- AWS access keys (AKIA...)
- Private keys (BEGIN PRIVATE KEY)
- Database connection strings

**Redaction Strategy:**
- Replace value with [REDACTED]
- Preserve structure for context
- Log detection for security audit

**Example:**
```
Before: password = "super_secret_123"
After: password = "[REDACTED]"
```

### 11.2 Access Control

**Directory Allowlist:**
- Configure allowed root directories
- Reject attempts to index outside roots
- Prevent directory traversal attacks

**File Size Limits:**
- Max file size: 5MB (configurable)
- Prevents memory exhaustion
- Skips large binaries

**Encryption at Rest (Optional):**
- encrypt_db: true in configuration
- Encrypts DuckDB file with AES-256
- Key management via environment variable or key file
- Transparent to application layer

### 11.3 Privacy Considerations

**Local-Only Processing:**
- All indexing happens locally
- Embeddings generated via local models (or API with user's key)
- No data sent to third parties without explicit configuration

**Opt-Out:**
- Users can disable context system entirely (enabled: false)
- Per-directory exclusion via ignore_patterns
- File-level exclusion by naming convention (*.private, *.secret)

---

## 12. Performance Targets

### 12.1 Indexing Performance

**Target Throughput:**
- 3,000+ lines/second for text/code on M1/M2 class machine
- 1,000+ lines/second for complex languages (with AST parsing)
- 500+ lines/second including embedding generation

**Optimization Strategies:**
- Parallel processing of files (worker pool)
- Batch embedding API calls (128 chunks/batch)
- Incremental indexing (only changed files)
- Efficient change detection (BLAKE3 hashing)
- Connection pooling for DuckDB (if needed)

### 12.2 Query Performance

**Target Latency:**
- Cold query: ≤250ms for top-12 on 10,000 chunks
- Warm query (cached): ≤50ms
- End-to-end (with MMR): ≤300ms

**Optimization Strategies:**
- Pre-normalized vectors (dot product only)
- Efficient DuckDB indexes
- Result caching for common queries
- Parallel provider queries
- Early termination for budget exhaustion

### 12.3 Storage Efficiency

**Target Size:**
- ≤300MB for 10,000 chunks @ 768-dim float32
- Breakdown:
  - Vectors: ~30MB (10k × 768 × 4 bytes)
  - Text: ~100MB (avg 1KB/chunk)
  - Metadata: ~20MB
  - Indexes: ~50MB
  - Overhead: ~100MB

**Compression:**
- DuckDB native compression
- Optional: quantize embeddings to float16 (50% size reduction)
- Periodic VACUUM for cleanup

---

## 13. Implementation Phases

### Phase 0: Bootstrap Mechanism (Week 1)
- ✓ Database schema with bootstrap_progress table
- ✓ Directory scanning and filtering logic
- ✓ File prioritization queue
- ✓ Parallel worker pool
- ✓ Progress tracking and resumption
- ✓ Initial indexing workflow
- ✓ Incremental availability (query partial results)
- ✓ Bootstrap completion detection
- ✓ CLI for manual re-index

### Phase 1: Foundation (Week 1-2)
- ✓ Define interfaces (ContextProvider, ContextScope, etc.)
- ✓ Set up SPI registry pattern
- ✓ Create configuration schema (context.toml)
- ✓ Implement ContextModule skeleton
- ✓ DuckDB schema creation
- ✓ Basic file watcher (no debouncing yet)

### Phase 2: Core Providers (Week 3-4)
- Implement SemanticContextProvider
  - Embedding integration
  - Vector storage
  - Cosine similarity search
- Implement SymbolContextProvider
  - Tree-sitter integration
  - AST indexing
  - Symbol lookup
- Basic full-text provider (simple keyword matching)

### Phase 3: Chunking Pipeline (Week 5-6)
- Language-aware chunkers:
  - Markdown
  - Python
  - TypeScript/Kotlin
  - YAML
  - SQL
- Token estimation
- Overlap logic
- Label extraction

### Phase 4: Integration (Week 7-8)
- QueryOptimizer with MMR
- BudgetManager
- Pre-task hook in orchestrator
- Prompt template updates
- Context block rendering

### Phase 5: MCP Tools (Week 9)
- query_context tool
- refresh_context tool
- get_context_stats tool
- rebuild_context tool (with safety validations)
- get_rebuild_status tool
- Job tracking table and async execution
- Integration tests

### Phase 6: Advanced Features (Week 10-12)
- Git history provider
- Hybrid provider with RRF
- Secret scrubbing
- Metrics collection and reporting
- Performance optimization

### Phase 7: Standalone Service (Week 13-14)
- HTTP API implementation
- Standalone deployment scripts
- Client library for orchestrator
- Hybrid mode fallback logic

### Phase 8: Testing & Docs (Week 15-16)
- Comprehensive test suite
- Performance benchmarks
- Before/after metrics collection
- Documentation
- Acceptance demo

---

## 14. Testing Strategy

### 14.1 Unit Tests

**Chunker Tests:**
- Fixture files for each language
- Verify chunk boundaries
- Check labels and kinds
- Token count accuracy

**Provider Tests:**
- Mock embedding API
- Query accuracy
- Scope filtering
- Score thresholds

**MMR Tests:**
- Diversity vs relevance tradeoff
- Budget exhaustion
- Edge cases (empty results, single result)

### 14.2 Integration Tests

**End-to-End Workflow:**
1. Add files to index
2. Query for relevant context
3. Verify results include expected snippets
4. Modify file
5. Verify auto-refresh
6. Query again, verify updated results

**Rebuild Workflow:**
1. Index 1000 test files
2. Trigger rebuild_context with validateOnly=true
3. Verify dry-run report accurate
4. Trigger full rebuild_context (async)
5. Poll get_rebuild_status until complete
6. Verify all files re-indexed
7. Verify queries work correctly
8. Verify database size reasonable (VACUUM worked)

**Partial Rebuild:**
1. Index full test project
2. Trigger rebuild_context for specific paths
3. Verify only specified paths re-indexed
4. Verify other paths untouched

**Safety Tests:**
1. Attempt rebuild without confirm=true → rejected
2. Start rebuild, attempt second rebuild → blocked
3. Cancel rebuild mid-process → graceful cleanup

**Provider Combination:**
- Hybrid provider with multiple sub-providers
- RRF score merging
- Provider failure resilience

### 14.3 Performance Tests

**Indexing Throughput:**
- Synthetic 100k-line project
- Measure lines/second
- Verify target ≥3k lines/sec

**Query Latency:**
- 10k chunk database
- 1000 random queries
- Verify p95 ≤250ms

**Resilience Tests:**
- Embedding API rate limits
- Simulate queued retries
- Verify graceful degradation

### 14.4 Golden Queries

**Predefined query/expected results pairs:**
- "How do we validate JWT tokens?" → JwtValidator.kt functions
- "Database schema for users" → User table DDL + migrations
- "Recent changes to authentication" → Git commits + auth files

**Acceptance Criteria:**
- Top-3 results contain expected files/snippets
- Relevance scores >0.7
- No false positives in top-10

---

## 15. Metrics and Reporting

### 15.1 Token Usage Metrics

**Pre-Integration Baseline:**
- Run 10 historical tasks without context
- Record input/output tokens per task
- Calculate average: ~7000 tokens total

**Post-Integration:**
- Re-run same tasks with context enabled
- Record tokens: context + prompt + output
- Calculate average: ~1400 tokens total

**Reduction Calculation:**
- (7000 - 1400) / 7000 = 80% reduction ✓

**Metrics Table:**
All stored in usage_metrics table:
- ts: Timestamp
- op: "before_integration" | "after_integration"
- agent: "claude-code" | "codex-cli"
- task_id: Task identifier
- tokens_input: Prompt tokens
- tokens_output: Response tokens
- notes: Additional context

### 15.2 Provider Effectiveness

**Metrics Tracked:**
- Snippets provided vs used (selection rate)
- Average score by provider
- Query latency by provider
- Cache hit rate

**Analysis:**
- Which providers contribute most to final results?
- Are certain providers redundant?
- Optimize weights based on effectiveness

### 15.3 Dashboard Queries

**Top Tasks by Savings:**
```sql
SELECT task_id,
       AVG(CASE WHEN op = 'before' THEN tokens_input + tokens_output END) as before,
       AVG(CASE WHEN op = 'after' THEN tokens_input + tokens_output END) as after,
       (before - after) / before * 100 as reduction_pct
FROM usage_metrics
GROUP BY task_id
ORDER BY reduction_pct DESC
LIMIT 10
```

**Language Coverage:**
```sql
SELECT lang, COUNT(*) as chunks, SUM(tokens_est) as total_tokens
FROM chunks
JOIN file_state ON chunks.file_id = file_state.file_id
GROUP BY lang
ORDER BY chunks DESC
```

**Time-Series Trend:**
```sql
SELECT DATE_TRUNC('day', ts) as day,
       AVG(tokens_input + tokens_output) as avg_tokens
FROM usage_metrics
WHERE op = 'after_integration'
GROUP BY day
ORDER BY day
```

---

## 16. Future Enhancements

### 16.1 Symbol Graph

**Concept:**
Build call graph and dependency graph using links table.

**Benefits:**
- "Show me all callers of function X"
- "What depends on this module?"
- Impact analysis for refactoring

**Implementation:**
- Parse imports and function calls
- Store in links table with kind="call" or kind="import"
- Graph traversal queries
- Re-rank by graph distance

### 16.2 Hybrid Search (BM25 + Vector)

**Concept:**
Combine keyword search (BM25) with semantic search for best-of-both.

**Benefits:**
- Fast first-token relevance
- Semantic understanding
- Better than either alone

**Implementation:**
- Run BM25 and vector search in parallel
- Merge results with weighted scoring
- Tune weights based on query type

### 16.3 IDE Integration

**Concept:**
Jump-to-definition from context snippets in IDE.

**Benefits:**
- Seamless navigation
- Verify context accuracy
- Faster development

**Implementation:**
- VS Code extension
- Click on snippet → open file at offset
- Highlight referenced code

### 16.4 Structured Answers

**Concept:**
Compose snippets into auto-generated brief for prompts.

**Benefits:**
- Condensed context
- Higher-level summaries
- Even lower token usage

**Implementation:**
- LLM-based summarization of top snippets
- Generate concise "executive summary"
- Include as <context_summary> in prompt

---

## 17. Acceptance Criteria

### 17.0 Bootstrap Requirements

✅ **Initial Indexing Works:**
- Fresh startup on 10k file project completes successfully
- All configured directories scanned
- Ignore patterns respected
- File size limits enforced
- Progress tracking accurate

✅ **Incremental Availability:**
- Query works on partial index (before completion)
- Results improve as more files indexed
- Status endpoint shows progress accurately
- User notified of indexing state

✅ **Resume After Interruption:**
- Bootstrap can be stopped mid-process
- On restart, resumes from last checkpoint
- No duplicate processing
- No data corruption

✅ **Performance:**
- Processes files at ≥3k lines/sec
- 10k file project indexed in ≤15 minutes
- First 1,000 files done in ≤3 minutes
- Parallel workers utilized efficiently

✅ **Error Handling:**
- Individual file failures don't stop bootstrap
- Errors logged to bootstrap_errors table
- Can retry failed files
- Clear error messages

### 17.1 Functional Requirements

✅ **Context Retrieval Works:**
- Orchestrator calls getTaskContext() before task execution
- Relevant snippets returned for diverse query types
- Results respect token budgets
- Fallback works when service unavailable

✅ **File Watching Works:**
- File changes detected within 2 seconds
- Auto-reindexing completes without errors
- New content appears in query results immediately

✅ **Token Reduction Achieved:**
- Side-by-side comparison shows ≥80% reduction
- Measured across 5+ representative tasks
- Both simple and complex tasks benefit

✅ **MCP Tools Function:**
- query_context returns relevant results
- refresh_context triggers reindexing
- get_context_stats provides accurate metrics
- rebuild_context performs full reindex safely
- get_rebuild_status tracks progress accurately
- Async rebuild doesn't block orchestrator
- Safety confirmations prevent accidental data loss

### 17.2 Performance Requirements

✅ **Indexing Performance:**
- Throughput ≥3k lines/sec on M1/M2
- 100k-line project indexes in <33 seconds

✅ **Query Performance:**
- Cold query ≤250ms for 12 results on 10k chunks
- Warm query ≤50ms (cached)

✅ **Storage Efficiency:**
- 10k chunks fit in ≤300MB
- Database grows linearly with chunk count

### 17.3 Quality Requirements

✅ **Relevance:**
- Golden queries return expected files in top-3
- Relevance scores >0.7 for good matches
- No obvious false positives in top-10

✅ **Diversity:**
- MMR prevents duplicate information
- Results span multiple files (not all from one file)

✅ **Reliability:**
- No crashes or data corruption
- Graceful handling of malformed files
- Clear error messages

---

## 18. Risks and Mitigations

### 18.1 Embedding Model Drift

**Risk:**
Changing embedding models breaks existing vectors.

**Mitigation:**
- Pin model version in configuration
- Store model name in embeddings table
- Support multiple models simultaneously
- Migration tool for bulk re-embedding

### 18.2 Large Binary Files

**Risk:**
Indexing large binaries consumes memory/disk.

**Mitigation:**
- Extension allowlist (only text/code files)
- File size cap (5MB default)
- Detect binary content and skip

### 18.3 Secret Leakage

**Risk:**
Credentials stored in chunks.

**Mitigation:**
- Secret scrubbing via regex patterns
- Allowlist of safe directories
- Optional database encryption
- Security audit of indexed content

### 18.4 Performance Degradation at Scale

**Risk:**
Query latency increases with chunk count.

**Mitigation:**
- Efficient DuckDB indexes
- Result caching
- Partition large datasets
- Consider external vector DB if needed (future)

### 18.5 Context Staleness

**Risk:**
Indexed content out-of-date with filesystem.

**Mitigation:**
- File watcher for real-time updates
- Periodic full scans (daily)
- Manual refresh tool
- Timestamp tracking in file_state

---

## 19. Summary

### 19.1 Key Architectural Decisions

**Plugin-Based Extensibility:**
- SPI pattern for providers (mirrors AgentFactory)
- Zero hardcoding of provider types
- Configuration-driven enable/disable

**Hybrid Deployment:**
- Support embedded, standalone, and hybrid modes
- Graceful degradation on failure
- Flexibility for different deployment scenarios

**Token-First Design:**
- Budget management at every layer
- MMR for diversity
- Configurable thresholds
- Metrics-driven optimization

**DuckDB as Foundation:**
- Embedded analytical database
- No separate server needed
- Efficient queries and storage
- Transaction safety

### 19.2 Integration Points

**Orchestrator:**
- ContextModule as new plugin module
- Pre-task hook for context retrieval
- Extended MCP tools (query_context, refresh_context, get_context_stats)
- Prompt template enhancements

**Agents (Claude/Codex):**
- Receive context in <project_context> blocks
- Can explicitly query via query_context tool
- Transparency into data sources

**Metrics System:**
- Reuse existing usage_metrics table
- Before/after comparison reporting
- Provider effectiveness tracking

### 19.3 Success Metrics

**Primary Goal:**
- ≥80% token reduction across representative task set

**Secondary Goals:**
- Query latency ≤250ms (p95)
- Indexing throughput ≥3k lines/sec
- Relevance: golden queries pass
- Zero breaking changes to existing orchestrator

### 19.4 Implementation Readiness

**Prerequisites Met:**
- Existing orchestrator architecture understood
- SPI pattern established and proven
- DuckDB already in use
- Configuration system in place
- MCP tools infrastructure ready

**Next Steps:**
1. Review and approve this architecture spec
2. Create detailed implementation tasks (break into TASK-### items)
3. Assign tasks to Codex (architecture/planning) and Claude (implementation)
4. Begin Phase 1: Foundation
5. Iterate through phases with regular integration testing

**Estimated Timeline:**
16 weeks total for complete implementation (all 8 phases)

---

## 20. Appendix

### 20.1 Glossary

- **Chunk**: Semantic unit of code/text (function, paragraph, section)
- **Embedding**: Vector representation of text
- **MMR**: Maximal Marginal Relevance (diversity algorithm)
- **SPI**: Service Provider Interface (Java plugin mechanism)
- **RRF**: Reciprocal Rank Fusion (score merging algorithm)
- **BM25**: Best Matching 25 (keyword search algorithm)
- **AST**: Abstract Syntax Tree
- **Tree-sitter**: Incremental parsing library

### 20.2 References

- Original Context Retrieval Spec: CONTEXT_RETRIEVAL_SPEC.md
- Orchestrator Architecture: CLAUDE.md
- Functional Requirements: FUNCTIONAL_REQUIREMENTS.md
- API Reference: API_REFERENCE.md
- Implementation Plan: IMPLEMENTATION_PLAN.md

### 20.3 Contact

- **Architect**: Codex CLI (architectural decisions, planning)
- **Implementer**: Claude Code (coding, testing, integration)
- **Owner/Reviewer**: Orchestrator (Sergey)

---

**End of Add-On Architecture Specification**